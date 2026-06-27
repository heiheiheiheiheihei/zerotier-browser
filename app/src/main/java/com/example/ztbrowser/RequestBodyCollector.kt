package com.example.ztbrowser

import android.webkit.JavascriptInterface
import android.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingDeque

/**
 * POST/PUT/PATCH 请求体收集器
 *
 * Android 的 shouldInterceptRequest 平台限制：无法获取 POST/PUT/PATCH 的请求体。
 * 解决方案：注入 JS 劫持 fetch/XMLHttpRequest.prototype.send，把请求体经
 * @JavascriptInterface 传给原生层缓存。shouldInterceptRequest 时按 (method,url)
 * 匹配取出 body 再用 OkHttp 代理转发。
 *
 * 并发处理：同一 (method,url) 可能被同时请求多次，用 FIFO 队列。每个条目带时间戳，
 * 超过 [TTL_MS] 未消费的自动清理，避免内存泄漏。
 *
 * body 编码：JS 侧把 body 转 Base64 再传（避免二进制/UTF-8 边界问题），原生层解码。
 */
class RequestBodyCollector {

    companion object {
        private const val TAG = "RequestBodyCollector"
        private const val TTL_MS = 5_000L  // 未消费条目存活 5 秒
        private const val MAX_BODY_SIZE = 5 * 1024 * 1024  // 5MB 上限，防止超大 body 撑爆内存
    }

    private data class BodyEntry(
        val body: ByteArray,
        val timestamp: Long,
        val contentType: String?
    )

    // key = "METHOD|url"，value = FIFO 队列（同 url 多次请求按序消费）
    private val store = ConcurrentHashMap<String, LinkedBlockingDeque<BodyEntry>>()

    /**
     * JS 调用：提交请求体
     * @param method HTTP 方法（POST/PUT/PATCH）
     * @param url 请求 URL
     * @param contentType Content-Type 头（可能为空字符串）
     * @param bodyBase64 body 的 Base64 编码（可能为空字符串表示无 body）
     */
    @JavascriptInterface
    fun submitBody(method: String, url: String, contentType: String, bodyBase64: String) {
        try {
            val key = "${method.uppercase()}|$url"
            val body: ByteArray = if (bodyBase64.isEmpty()) {
                ByteArray(0)
            } else {
                val decoded = Base64.decode(bodyBase64, Base64.NO_WRAP)
                if (decoded.size > MAX_BODY_SIZE) {
                    ZeroTierService.log("W", "Body too large (${decoded.size} bytes) for $method $url, truncated")
                    return
                }
                decoded
            }
            val ct = contentType.ifBlank { null }
            val entry = BodyEntry(body, System.currentTimeMillis(), ct)

            // 首次清理过期条目（轻量，每次提交只清理当前 key）
            cleanExpired(key)

            val deque = store.computeIfAbsent(key) { LinkedBlockingDeque() }
            deque.offerLast(entry)
            ZeroTierService.log("D", "Body collected: $method $url (${body.size} bytes, ct=$ct)")
        } catch (e: Exception) {
            ZeroTierService.log("E", "submitBody failed for $method $url: ${e.message}", e)
        }
    }

    /**
     * shouldInterceptRequest 调用：取出对应请求体
     * @return ConsumedBody（bytes + contentType），若无匹配返回 null
     */
    fun consume(method: String, url: String): ConsumedBody? {
        val key = "${method.uppercase()}|$url"
        val deque = store[key] ?: return null
        cleanExpired(key)
        val entry = deque.pollFirst()
        if (entry != null && deque.isEmpty()) {
            store.remove(key)
        }
        return entry?.let { ConsumedBody(it.body, it.contentType) }
    }

    /** 清理指定 key 下过期的条目 */
    private fun cleanExpired(key: String) {
        val deque = store[key] ?: return
        val now = System.currentTimeMillis()
        while (true) {
            val peek = deque.peekFirst() ?: break
            if (now - peek.timestamp > TTL_MS) {
                deque.pollFirst()
                ZeroTierService.log("D", "Body expired: $key")
            } else {
                break
            }
        }
        if (deque.isEmpty()) {
            store.remove(key)
        }
    }

    /** 清空所有缓存（页面切换时调用） */
    fun clear() {
        store.clear()
    }

    /** 供 shouldInterceptRequest 使用的数据结构 */
    data class ConsumedBody(val bytes: ByteArray, val contentType: String?)
}
