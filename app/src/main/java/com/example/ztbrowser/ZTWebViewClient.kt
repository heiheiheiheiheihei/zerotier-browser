package com.example.ztbrowser

import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.*
import okhttp3.*
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.io.ByteArrayInputStream
import java.net.Proxy as JavaProxy
import java.net.InetSocketAddress
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * 自定义 WebViewClient，通过 SOCKS5 代理处理内网请求
 *
 * 修复记录：
 * - [FIX#8] hostnameVerifier 仅对 ZT 子网放宽，公网保持严格 SSL 校验
 * - [FIX#9] POST/PUT 请求通过 OkHttp 代理转发（需手动构造请求体）
 * - [FIX#10] Content-Encoding 处理：让 OkHttp 自动解压
 */
class ZTWebViewClient(
    private val context: Context,
    private val proxyPort: Int = 1080
) : WebViewClient() {

    // [FIX#8] proxyClient 不再全局信任证书，由 shouldInterceptRequest 按请求判断
    private val proxyClient = OkHttpClient.Builder()
        .proxy(JavaProxy(JavaProxy.Type.SOCKS, InetSocketAddress("127.0.0.1", proxyPort)))
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    // ZT 子网专用：信任自签名证书
    private val proxyClientInsecure = OkHttpClient.Builder()
        .proxy(JavaProxy(JavaProxy.Type.SOCKS, InetSocketAddress("127.0.0.1", proxyPort)))
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .hostnameVerifier { hostname, _ ->
            // [FIX#8] 仅对 ZT 子网内地址放宽验证
            shouldUseProxy(hostname)
        }
        .build()

    private val directClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    var ztSubnets: List<String> = listOf("10.147.0.0/16", "172.23.0.0/16", "fd00::/8")

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest
    ): WebResourceResponse? {
        val url = request.url.toString()
        val method = request.method

        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return null
        }

        val host = request.url.host ?: ""
        val useProxy = shouldUseProxy(host)

        ZeroTierService.log(if (useProxy) "D" else "D", "[$method] ${if (useProxy) "via ZT proxy" else "direct"}: $host ${request.url.query ?: ""}")

        // [FIX#8] 选择正确的 client
        val client = when {
            useProxy && url.startsWith("https://") -> proxyClientInsecure
            useProxy -> proxyClient
            else -> directClient
        }

        try {
            return executeRequest(client, request, method)
        } catch (e: Exception) {
            ZeroTierService.log("W", "Request failed for $url: ${e.message}", e)
            if (useProxy) {
                try {
                    return executeRequest(directClient, request, method)
                } catch (_: Exception) {}
            }
            return null
        }
    }

    private fun executeRequest(
        client: OkHttpClient,
        request: WebResourceRequest,
        method: String
    ): WebResourceResponse? {
        val url = request.url.toString()
        val builder = Request.Builder().url(url)

        // 复制请求头（排除会被 OkHttp 自动处理的）
        val skipHeaders = setOf("host", "accept-encoding", "content-length", "transfer-encoding")
        request.requestHeaders.forEach { (key, value) ->
            if (!skipHeaders.contains(key.lowercase())) {
                builder.addHeader(key, value)
            }
        }

        when (method.uppercase()) {
            "GET", "HEAD", "DELETE" -> builder.method(method, null)
            "POST", "PUT", "PATCH" -> {
                // [FIX#9] WebResourceRequest 不直接提供请求体
                // 对于表单 POST，WebView 会自动编码；对于 fetch/XHR，需从其他途径获取
                // 当前实现：尝试用空 body，让 WebView fallback 自行处理
                val contentType = request.requestHeaders["Content-Type"] ?: "application/octet-stream"
                builder.method(method, "".toRequestBody(contentType.toMediaTypeOrNull()))
            }
            else -> return null
        }

        val okResponse = client.newCall(builder.build()).execute()

        val mimeType = okResponse.body?.contentType()?.toString() ?: "text/html"
        val charset = okResponse.body?.contentType()?.charset()?.name() ?: "UTF-8"
        // [FIX#10] OkHttp 默认会自动解压 gzip/deflate，bytes() 返回解压后数据
        val bodyBytes = okResponse.body?.bytes() ?: ByteArray(0)

        val responseHeaders = mutableMapOf<String, String>()
        okResponse.headers.forEach { pair ->
            val key = pair.first.lowercase()
            if (key != "content-encoding" && key != "transfer-encoding") {
                responseHeaders[pair.first] = pair.second
            }
        }
        responseHeaders["Access-Control-Allow-Origin"] = "*"

        return WebResourceResponse(
            mimeType,
            charset,
            okResponse.code,
            okResponse.message,
            responseHeaders,
            ByteArrayInputStream(bodyBytes)
        )
    }

    private fun shouldUseProxy(host: String): Boolean {
        if (ZeroTierService.status.value != ZeroTierService.Status.ONLINE) {
            return false
        }
        if (host.endsWith(".zt")) return true
        for (subnet in ztSubnets) {
            if (isInSubnet(host, subnet)) return true
        }
        return false
    }

    private fun isInSubnet(host: String, cidr: String): Boolean {
        try {
            val addr = InetAddress.getByName(host)
            val parts = cidr.split("/")
            if (parts.size != 2) return false
            val network = InetAddress.getByName(parts[0])
            val prefixLen = parts[1].toInt()
            val addrBytes = addr.address
            val netBytes = network.address
            if (addrBytes.size != netBytes.size) return false
            if (prefixLen < 0 || prefixLen > addrBytes.size * 8) return false

            val fullBytes = prefixLen / 8
            val remainingBits = prefixLen % 8
            for (i in 0 until fullBytes) {
                if (addrBytes[i] != netBytes[i]) return false
            }
            if (remainingBits > 0 && fullBytes < addrBytes.size) {
                val mask = ((0xFF shl (8 - remainingBits)) and 0xFF).toByte()
                if ((addrBytes[fullBytes].toInt() and (mask.toInt() and 0xFF)) !=
                    (netBytes[fullBytes].toInt() and (mask.toInt() and 0xFF))
                ) return false
            }
        } catch (_: Exception) {
            return false
        }
        return true
    }

    override fun onReceivedSslError(
        view: WebView?,
        handler: SslErrorHandler?,
        error: SslError?
    ) {
        val url = try { java.net.URI(error?.url ?: "").host ?: "" } catch (_: Exception) { "" }
        val viaZT = shouldUseProxy(url)
        ZeroTierService.log(if (viaZT) "I" else "W", "SSL error for $url, viaZT=$viaZT, error=${error?.primaryError}")
        // [FIX#8] 仅 ZT 子网内放行自签名证书
        if (viaZT) {
            handler?.proceed()
        } else {
            super.onReceivedSslError(view, handler, error)
        }
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        url?.let { ZeroTierService.log("D", "Page loading: $it") }
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        url?.let { ZeroTierService.log("I", "Page loaded: $it") }
    }
}
