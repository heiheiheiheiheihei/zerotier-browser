package com.example.ztbrowser

import android.content.Context
import android.util.Log
import com.zerotier.sockets.ZeroTierNative
import com.zerotier.sockets.ZeroTierNode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * ZeroTier 应用层服务 - 使用 libzt 在进程内运行 ZeroTier 协议栈
 *
 * 关键点：
 * 1. 不使用 Android VpnService，不影响系统 VPN 通道
 * 2. libzt 在应用进程内运行，仅本应用可见
 * 3. 其他应用的网络完全不受影响
 *
 * 修复记录：
 * - [FIX#1] 添加 System.loadLibrary("zt") 初始化 JNI
 * - [FIX#2] 使用类级别 scope 管理协程，避免泄漏
 * - [FIX#3] 添加 Network ID 合法性校验（16位十六进制）
 * - [FIX#11] 使用 libzt AAR 自带的 ZeroTierNode/ZeroTierNative 替代自定义 external fun。
 *           根因：自定义 external fun 声明的 JNI 函数名（com_example_ztbrowser_*）
 *           与 AAR 实际导出的 JNI 符号（com_zerotier_sockets_*）不匹配，导致
 *           UnsatisfiedLinkError。
 */
object ZeroTierService {

    private const val TAG = "ZeroTierService"
    private const val ZT_HOME_DIR = "zerotier"
    private const val PLANET_FILE = "planet"

    // 标记 native 库是否加载成功
    @Volatile
    private var nativeLibLoaded = false

    // libzt 节点封装（内部调用 com.zerotier.sockets.ZeroTierNative）
    private val node = ZeroTierNode()

    init {
        try {
            System.loadLibrary("zt")
            nativeLibLoaded = true
            Log.i(TAG, "libzt native library loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            nativeLibLoaded = false
            Log.e(TAG, "Failed to load libzt native library. ZT functions unavailable.", e)
        }
    }

    enum class Status {
        STOPPED, CONNECTING, ONLINE, OFFLINE
    }

    private val _status = MutableStateFlow(Status.STOPPED)
    val status: StateFlow<Status> = _status.asStateFlow()

    // [FIX#2] 类级 scope，在 stop 时 cancel 并重建
    private var scope: CoroutineScope? = null
    private var started = false
    private var currentNetworkId: Long = 0L

    /**
     * 校验 Network ID 格式
     */
    fun isValidNetworkId(id: String): Boolean {
        if (id.length != 16) return false
        return try {
            id.toLong(16)
            true
        } catch (_: NumberFormatException) {
            false
        }
    }

    /**
     * 初始化并启动 ZeroTier 协议栈
     *
     * 使用 libzt 标准流程：
     * 1. ZeroTierNative static 块已调用 zts_init() 做全局初始化
     * 2. zts_init_from_storage() 从目录加载 identity/planet
     * 3. zts_node_start() 启动协议栈
     * 4. zts_net_join() 加入网络
     */
    fun start(context: Context, networkId: String): Result<Unit> {
        if (started) return Result.success(Unit)

        return try {
            if (!nativeLibLoaded) {
                return Result.failure(IllegalStateException("libzt native library not loaded. Cannot start ZeroTier."))
            }
            // [FIX#3] 先校验 Network ID
            if (!isValidNetworkId(networkId)) {
                return Result.failure(IllegalArgumentException("Invalid Network ID: 16 hex chars required"))
            }

            _status.value = Status.CONNECTING

            val ztDir = File(context.filesDir, ZT_HOME_DIR).apply { mkdirs() }
            val planetPath = File(ztDir, PLANET_FILE).absolutePath

            if (!File(planetPath).exists()) {
                copyPlanetFromAssets(context, planetPath)
            }

            // 使用 ZeroTierNode 初始化（内部调用 zts_init_from_storage）
            val initResult = node.initFromStorage(ztDir.absolutePath)
            if (initResult != 0) {
                _status.value = Status.OFFLINE
                return Result.failure(Exception("ZeroTier init failed: code $initResult"))
            }

            // 启动节点（内部调用 zts_node_start）
            val startResult = node.start()
            if (startResult != 0) {
                _status.value = Status.OFFLINE
                return Result.failure(Exception("ZeroTier start failed: code $startResult"))
            }

            val nwid = networkId.toLong(16)
            val joinResult = node.join(nwid)
            if (joinResult != 0) {
                node.stop()
                _status.value = Status.OFFLINE
                return Result.failure(Exception("Failed to join network: code $joinResult"))
            }

            currentNetworkId = nwid
            started = true

            // [FIX#2] 使用类级 scope，可被管理
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            scope?.launch {
                waitForOnline(nwid)
            }

            Log.i(TAG, "ZeroTier started, joining network $networkId")
            Result.success(Unit)
        } catch (e: Throwable) {
            _status.value = Status.OFFLINE
            Log.e(TAG, "ZeroTier start crashed", e)
            Result.failure(Exception("ZeroTier start failed: ${e.javaClass.simpleName}: ${e.message}"))
        }
    }

    private suspend fun waitForOnline(nwid: Long) {
        var retries = 30
        while (retries > 0 && isActive()) {
            if (node.isOnline()) {
                _status.value = Status.ONLINE
                val addr = node.getIPv4Address(nwid)?.hostAddress ?: "unknown"
                Log.i(TAG, "ZeroTier online, address: $addr")
                return
            }
            delay(1000)
            retries--
        }
        if (isActive()) {
            _status.value = Status.OFFLINE
            Log.w(TAG, "ZeroTier connection timeout")
        }
    }

    fun stop() {
        if (!started) return
        if (nativeLibLoaded) {
            try {
                node.leave(currentNetworkId)
                node.stop()
            } catch (e: Throwable) {
                Log.e(TAG, "Error stopping ZeroTier", e)
            }
        }
        scope?.cancel()
        scope = null
        started = false
        currentNetworkId = 0L
        _status.value = Status.STOPPED
    }

    private fun isActive(): Boolean = scope?.isActive == true

    fun getNetworkAddress(): String? {
        if (currentNetworkId == 0L || !nativeLibLoaded) return null
        return try {
            node.getIPv4Address(currentNetworkId)?.hostAddress
        } catch (e: Throwable) {
            null
        }
    }

    fun get6PlaneAddress(): String? {
        if (currentNetworkId == 0L || !nativeLibLoaded) return null
        return try {
            node.getIPv6Address(currentNetworkId)?.hostAddress
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * 创建 ZeroTier 原生 socket（用于 ZT 子网内连接）
     * 返回文件描述符 fd，由 ZTProxyServer 包装为 java.net.Socket
     */
    fun createSocket(): Int {
        if (!nativeLibLoaded) return -1
        return ZeroTierNative.zts_bsd_socket(2, 1, 0) // AF_INET, SOCK_STREAM, IPPROTO_TCP
    }

    fun connectSocket(fd: Int, host: String, port: Int): Int {
        if (!nativeLibLoaded) return -1
        return ZeroTierNative.zts_connect(fd, host, port, 0)
    }

    fun closeSocket(fd: Int): Int {
        if (!nativeLibLoaded) return -1
        return ZeroTierNative.zts_bsd_close(fd)
    }

    private fun copyPlanetFromAssets(context: Context, destPath: String) {
        try {
            context.assets.open("planet").use { input ->
                File(destPath).outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "No bundled planet file, using default ZeroTier root servers")
        }
    }
}
