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
import java.util.concurrent.Executors

/**
 * ZeroTier 应用层服务 - 使用 libzt 在进程内运行 ZeroTier 协议栈
 *
 * 修复记录：
 * - [FIX#11] 使用 libzt AAR 自带类替代自定义 JNI
 * - [FIX#12] 添加内存日志缓冲区
 * - [FIX#13] 使用专用单线程 Dispatcher 确保 libzt 所有调用在同线程（libzt 不线程安全）
 */
object ZeroTierService {

    private const val TAG = "ZeroTierService"
    private const val ZT_HOME_DIR = "zerotier"
    private const val PLANET_FILE = "planet"
    private const val MAX_LOG_ENTRIES = 200

    // [FIX#13] 专用单线程 Dispatcher：libzt 要求所有调用在同线程
    private val ztDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ZeroTier-main").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    // [FIX#13] 延迟初始化 node，确保在 ZT 线程创建
    @Volatile
    private var node: ZeroTierNode? = null

    // 标记 native 库是否加载成功
    @Volatile
    private var nativeLibLoaded = false

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

    private var scope: CoroutineScope? = null
    private var started = false
    private var currentNetworkId: Long = 0L
    private var currentNetworkIdHex: String = ""

    private fun log(level: String, msg: String, throwable: Throwable? = null) {
        val timestamp = logDateFormat.format(Date())
        val line = "[$timestamp] [$level] $msg"

        when (level) {
            "E" -> Log.e(TAG, msg, throwable)
            "W" -> Log.w(TAG, msg, throwable)
            "I" -> Log.i(TAG, msg, throwable)
            "D" -> Log.d(TAG, msg, throwable)
        }

        synchronized(logBuffer) {
            logBuffer.add(line)
            if (throwable != null) {
                logBuffer.add("[$timestamp] [$level]   ${throwable.stackTraceToString()}")
            }
            while (logBuffer.size > MAX_LOG_ENTRIES) {
                logBuffer.removeAt(0)
            }
        }
    }

    fun getLog(): String {
        val deviceInfo = "Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} | " +
                "Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}) | " +
                "Arch: ${android.os.Build.SUPPORTED_ABIS.joinToString(",")}"
        val header = "=== ZeroTier Browser Log ===\n$deviceInfo\n\n"
        return synchronized(logBuffer) {
            header + logBuffer.joinToString("\n")
        }
    }

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
     * 所有 libzt 调用都在 ztDispatcher 单线程中执行
     */
    fun start(context: Context, networkId: String): Result<Unit> {
        if (started) {
            log("D", "Already started, skipping")
            return Result.success(Unit)
        }

        return try {
            if (!nativeLibLoaded) {
                log("E", "Cannot start: libzt not loaded")
                return Result.failure(IllegalStateException("libzt native library not loaded"))
            }
            if (!isValidNetworkId(networkId)) {
                log("E", "Invalid Network ID: '$networkId'")
                return Result.failure(IllegalArgumentException("Invalid Network ID"))
            }

            // [FIX#13] 使用 runBlocking 桥接，确保整个启动流程在 ZT 线程
            runBlocking(ztDispatcher) {
                startInternal(context, networkId)
            }
        } catch (e: Throwable) {
            log("E", "ZeroTier start exception: ${e.javaClass.simpleName}: ${e.message}", e)
            _status.value = Status.OFFLINE
            Result.failure(Exception("ZeroTier start: ${e.javaClass.simpleName}: ${e.message}"))
        }
    }

    private suspend fun startInternal(context: Context, networkId: String): Result<Unit> {
        _status.value = Status.CONNECTING
        log("I", "Starting ZeroTier... network=$networkId device=${android.os.Build.MODEL}")

        // [FIX#13] 在 ZT 线程创建 node
        if (node == null) {
            node = ZeroTierNode()
            log("I", "ZeroTierNode created")
        }

        val ztDir = File(context.filesDir, ZT_HOME_DIR).apply { mkdirs() }
        val planetPath = File(ztDir, PLANET_FILE).absolutePath

        val hasPlanet = File(planetPath).exists()
        if (!hasPlanet) {
            log("I", "Planet file not found, copying from assets")
            copyPlanetFromAssets(context, planetPath)
        } else {
            log("D", "Planet exists at $planetPath")
        }

        // initFromStorage
        val initResult = node!!.initFromStorage(ztDir.absolutePath)
        if (initResult != 0) {
            log("E", "initFromStorage failed: code=$initResult path=${ztDir.absolutePath}")
            _status.value = Status.OFFLINE
            return Result.failure(Exception("ZeroTier init failed: code $initResult"))
        }
        log("I", "initFromStorage OK")

        // node start
        val startResult = node!!.start()
        if (startResult != 0) {
            log("E", "node.start failed: code=$startResult")
            _status.value = Status.OFFLINE
            return Result.failure(Exception("ZeroTier start failed: code $startResult"))
        }
        log("I", "node.start OK")

        // join network
        val nwid = networkId.toLong(16)
        val joinResult = node!!.join(nwid)
        if (joinResult != 0) {
            log("E", "node.join failed: code=$joinResult network=$networkId")
            node!!.stop()
            _status.value = Status.OFFLINE
            return Result.failure(Exception("Failed to join network: code $joinResult"))
        }
        log("I", "node.join($networkId) OK, waiting for online...")

        currentNetworkId = nwid
        currentNetworkIdHex = networkId
        started = true

        // 启动等待上线的协程（在 ZT 线程中轮询）
        scope = CoroutineScope(ztDispatcher + SupervisorJob())
        scope?.launch {
            waitForOnline(nwid)
        }

        Result.success(Unit)
    }

    private suspend fun waitForOnline(nwid: Long) {
        log("I", "Waiting for ZeroTier online (30s timeout)...")
        var retries = 30
        var lastError: String? = null
        while (retries > 0 && isActive()) {
            try {
                val online = node?.isOnline() ?: false
                if (online) {
                    _status.value = Status.ONLINE
                    val addr = node?.getIPv4Address(nwid)?.hostAddress ?: "unknown"
                    val addr6 = node?.getIPv6Address(nwid)?.hostAddress ?: ""
                    val mac = node?.getMACAddress(nwid) ?: ""
                    log("I", "ZeroTier ONLINE | IPv4=$addr | IPv6=$addr6 | MAC=$mac")
                    return
                }
            } catch (e: Throwable) {
                lastError = e.message
                log("D", "isOnline check: ${e.message}")
            }
            delay(1000)
            retries--
        }
        if (isActive()) {
            log("W", "Connection timeout (30s). lastError=$lastError")
            _status.value = Status.OFFLINE
        }
    }

    fun stop() {
        if (!started) return
        log("I", "Stopping ZeroTier...")
        
        scope?.cancel()
        scope = null

        if (nativeLibLoaded) {
            runBlocking(ztDispatcher) {
                try {
                    node?.leave(currentNetworkId)
                    node?.stop()
                    log("I", "ZeroTier stopped")
                } catch (e: Throwable) {
                    log("E", "Error stopping ZeroTier", e)
                }
            }
        }

        started = false
        currentNetworkId = 0L
        _status.value = Status.STOPPED
    }

    private fun isActive(): Boolean = scope?.isActive == true

    fun getNetworkAddress(): String? {
        if (currentNetworkId == 0L || !nativeLibLoaded) return null
        return try {
            runBlocking(ztDispatcher) {
                node?.getIPv4Address(currentNetworkId)?.hostAddress
            }
        } catch (e: Throwable) {
            null
        }
    }

    fun get6PlaneAddress(): String? {
        if (currentNetworkId == 0L || !nativeLibLoaded) return null
        return try {
            runBlocking(ztDispatcher) {
                node?.getIPv6Address(currentNetworkId)?.hostAddress
            }
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * 创建 ZeroTier 原生 socket（用于 ZT 子网内连接）
     */
    fun createSocket(): Int {
        if (!nativeLibLoaded) return -1
        return try {
            runBlocking(ztDispatcher) {
                ZeroTierNative.zts_bsd_socket(2, 1, 0)
            }
        } catch (e: Throwable) {
            log("E", "createSocket failed", e)
            -1
        }
    }

    fun connectSocket(fd: Int, host: String, port: Int): Int {
        if (!nativeLibLoaded) return -1
        return try {
            runBlocking(ztDispatcher) {
                ZeroTierNative.zts_connect(fd, host, port, 0)
            }
        } catch (e: Throwable) {
            log("E", "connectSocket failed: fd=$fd host=$host:$port", e)
            -1
        }
    }

    fun closeSocket(fd: Int): Int {
        if (!nativeLibLoaded) return -1
        return try {
            runBlocking(ztDispatcher) {
                ZeroTierNative.zts_bsd_close(fd)
            }
        } catch (e: Throwable) {
            log("E", "closeSocket failed: fd=$fd", e)
            -1
        }
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
            log("W", "No planet in assets, using default root servers")
        }
    }
}
