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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
 * - [FIX#11] 使用 libzt AAR 自带的 ZeroTierNode/ZeroTierNative 替代自定义 external fun
 * - [FIX#12] 添加内存日志缓冲区，支持复制运行日志用于问题反馈
 */
object ZeroTierService {

    private const val TAG = "ZeroTierService"
    private const val ZT_HOME_DIR = "zerotier"
    private const val PLANET_FILE = "planet"
    private const val MAX_LOG_ENTRIES = 200

    // 标记 native 库是否加载成功
    @Volatile
    private var nativeLibLoaded = false

    // libzt 节点封装（内部调用 com.zerotier.sockets.ZeroTierNative）
    private val node = ZeroTierNode()

    // 内存日志缓冲区
    private val logBuffer = mutableListOf<String>()
    private val logDateFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())

    init {
        try {
            System.loadLibrary("zt")
            nativeLibLoaded = true
            log("I", "libzt native library loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            nativeLibLoaded = false
            log("E", "Failed to load libzt native library", e)
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
     * 写入日志（同时输出到 Logcat 和内存缓冲区）
     */
    private fun log(level: String, msg: String, throwable: Throwable? = null) {
        val timestamp = logDateFormat.format(Date())
        val line = "[$timestamp] [$level] $msg"
        
        // 写入 Logcat
        when (level) {
            "E" -> Log.e(TAG, msg, throwable)
            "W" -> Log.w(TAG, msg, throwable)
            "I" -> Log.i(TAG, msg, throwable)
            "D" -> Log.d(TAG, msg, throwable)
        }
        
        // 写入缓冲区
        synchronized(logBuffer) {
            logBuffer.add(line)
            if (throwable != null) {
                logBuffer.add("[$timestamp] [$level]   ${throwable.stackTraceToString()}")
            }
            // 限制最大行数
            while (logBuffer.size > MAX_LOG_ENTRIES) {
                logBuffer.removeAt(0)
            }
        }
    }

    /**
     * 获取所有缓存的运行日志
     */
    fun getLog(): String {
        val deviceInfo = "Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} | " +
                "Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}) | " +
                "Arch: ${android.os.Build.SUPPORTED_ABIS.joinToString(",")}"
        val header = "=== ZeroTier Browser Log ===\n$deviceInfo\n\n"
        return synchronized(logBuffer) {
            header + logBuffer.joinToString("\n")
        }
    }

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

    fun start(context: Context, networkId: String): Result<Unit> {
        if (started) return Result.success(Unit)

        return try {
            if (!nativeLibLoaded) {
                log("E", "Cannot start: libzt native library not loaded")
                return Result.failure(IllegalStateException("libzt native library not loaded. Cannot start ZeroTier."))
            }
            if (!isValidNetworkId(networkId)) {
                log("E", "Invalid Network ID: '$networkId'")
                return Result.failure(IllegalArgumentException("Invalid Network ID: 16 hex chars required"))
            }

            log("I", "Starting ZeroTier... network=$networkId")
            _status.value = Status.CONNECTING

            val ztDir = File(context.filesDir, ZT_HOME_DIR).apply { mkdirs() }
            val planetPath = File(ztDir, PLANET_FILE).absolutePath

            if (!File(planetPath).exists()) {
                log("I", "Planet file not found, copying from assets")
                copyPlanetFromAssets(context, planetPath)
            } else {
                log("D", "Planet file exists at $planetPath")
            }

            val initResult = node.initFromStorage(ztDir.absolutePath)
            if (initResult != 0) {
                log("E", "zts_init_from_storage failed: code=$initResult")
                _status.value = Status.OFFLINE
                return Result.failure(Exception("ZeroTier init failed: code $initResult"))
            }
            log("I", "initFromStorage OK, path=${ztDir.absolutePath}")

            val startResult = node.start()
            if (startResult != 0) {
                log("E", "zts_node_start failed: code=$startResult")
                _status.value = Status.OFFLINE
                return Result.failure(Exception("ZeroTier start failed: code $startResult"))
            }
            log("I", "node.start() OK")

            val nwid = networkId.toLong(16)
            val joinResult = node.join(nwid)
            if (joinResult != 0) {
                log("E", "zts_net_join failed: code=$joinResult")
                node.stop()
                _status.value = Status.OFFLINE
                return Result.failure(Exception("Failed to join network: code $joinResult"))
            }
            log("I", "node.join($networkId) OK")

            currentNetworkId = nwid
            started = true

            scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            scope?.launch {
                waitForOnline(nwid)
            }

            Result.success(Unit)
        } catch (e: Throwable) {
            log("E", "ZeroTier start crashed: ${e.javaClass.simpleName}: ${e.message}", e)
            _status.value = Status.OFFLINE
            Result.failure(Exception("ZeroTier start failed: ${e.javaClass.simpleName}: ${e.message}"))
        }
    }

    private suspend fun waitForOnline(nwid: Long) {
        log("I", "Waiting for ZeroTier to go online (30s timeout)...")
        var retries = 30
        while (retries > 0 && isActive()) {
            if (node.isOnline()) {
                _status.value = Status.ONLINE
                val addr = node.getIPv4Address(nwid)?.hostAddress ?: "unknown"
                val addr6 = node.getIPv6Address(nwid)?.hostAddress ?: ""
                log("I", "ZeroTier ONLINE: IPv4=$addr IPv6=$addr6")
                return
            }
            delay(1000)
            retries--
        }
        if (isActive()) {
            log("W", "ZeroTier connection timeout after 30s")
            _status.value = Status.OFFLINE
        }
    }

    fun stop() {
        if (!started) return
        log("I", "Stopping ZeroTier...")
        if (nativeLibLoaded) {
            try {
                node.leave(currentNetworkId)
                node.stop()
                log("I", "ZeroTier stopped")
            } catch (e: Throwable) {
                log("E", "Error stopping ZeroTier", e)
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

    fun createSocket(): Int {
        if (!nativeLibLoaded) return -1
        return ZeroTierNative.zts_bsd_socket(2, 1, 0)
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
            log("I", "Planet file copied from assets")
        } catch (e: Exception) {
            log("W", "No bundled planet file, using default root servers")
        }
    }
}
