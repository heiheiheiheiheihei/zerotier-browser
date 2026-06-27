package com.example.ztbrowser

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.zerotier.sockets.ZeroTierNative
import com.zerotier.sockets.ZeroTierNode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object ZeroTierService {

    private const val TAG = "ZeroTierService"
    private const val ZT_HOME_DIR = "zerotier"
    private const val PLANET_FILE = "planet"
    private const val MAX_LOG_ENTRIES = 200

    private val ztThread = HandlerThread("ZeroTier-main").apply { start() }
    private val ztHandler = Handler(ztThread.looper)

    @Volatile
    private var node: ZeroTierNode? = null
    @Volatile
    private var nativeLibLoaded = false

    // 缓存地址，避免 getNetworkAddress() 触发 runOnZtThread 阻塞主线程
    @Volatile
    private var cachedIPv4: String? = null
    @Volatile
    private var cachedIPv6: String? = null

    private val logBuffer = mutableListOf<String>()
    private val logDateFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())

    // 文件日志持久化（闪退后日志不丢失）
    private var logFileWriter: PrintWriter? = null
    @Volatile
    private var logFilePath: String? = null

    fun setupFileLogging(context: Context) {
        val ztDir = File(context.filesDir, ZT_HOME_DIR).apply { mkdirs() }
        val file = File(ztDir, "zt_log.txt")
        // 保留上次日志作为备份（崩溃日志不丢失）
        File(ztDir, "zt_log.prev.txt").delete()
        if (file.exists()) file.renameTo(File(ztDir, "zt_log.prev.txt"))
        logFilePath = file.absolutePath
        logFileWriter = PrintWriter(file, "UTF-8").apply {
            val ts = logDateFormat.format(Date())
            write("[$ts] [I] === App started ===\n")
            // 立即刷入内存缓冲区的已有日志（init 阶段的日志）
            synchronized(logBuffer) {
                for (entry in logBuffer) write(entry + "\n")
            }
            flush()
        }
    }

    init {
        log("I", "ZT init: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} / Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}) / ${android.os.Build.SUPPORTED_ABIS.joinToString(",")}")
        try {
            System.loadLibrary("zt")
            nativeLibLoaded = true
            log("I", "libzt native library loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            nativeLibLoaded = false
            log("E", "Failed to load libzt native library", e)
        }
    }

    enum class Status { STOPPED, CONNECTING, ONLINE, OFFLINE }

    private val _status = MutableStateFlow(Status.STOPPED)
    val status: StateFlow<Status> = _status.asStateFlow()

    private var started = false
    private var currentNetworkId: Long = 0L
    private var currentNetworkIdHex: String = ""
    private var pollRunnable: Runnable? = null

    internal fun log(level: String, msg: String, throwable: Throwable? = null) {
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
            while (logBuffer.size > MAX_LOG_ENTRIES) logBuffer.removeAt(0)
        }
        // 同步写入持久化日志文件（每条立即刷盘，闪退不丢）
        logFileWriter?.let { w ->
            synchronized(w) {
                w.write(line + "\n")
                if (throwable != null) w.write("[$timestamp] [$level]   ${throwable.stackTraceToString()}\n")
                w.flush()
            }
        }
    }

    fun getLog(): String {
        val deviceInfo = "Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} | " +
                "Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}) | " +
                "Arch: ${android.os.Build.SUPPORTED_ABIS.joinToString(",")}"
        val header = "=== ZeroTier Browser Log ===\n$deviceInfo\n\n"

        // 内存缓冲区 + 上次持久化日志（总是带上，崩溃时可对比）
        val memLog = synchronized(logBuffer) { logBuffer.joinToString("\n") }
        val parts = mutableListOf<String>()

        logFilePath?.let { path ->
            val prevFile = File(path).resolveSibling("zt_log.prev.txt")
            if (prevFile.exists()) parts.add("=== Previous run ===\n" + prevFile.readText())
        }

        if (memLog.isNotEmpty()) parts.add(memLog)
        else {
            logFilePath?.let { path ->
                val curFile = File(path)
                if (curFile.exists()) parts.add(curFile.readText())
            }
        }

        return if (parts.isNotEmpty()) header + parts.joinToString("\n\n") else header
    }

    fun isValidNetworkId(id: String): Boolean {
        if (id.length != 16) return false
        return try { id.toLong(16); true } catch (_: NumberFormatException) { false }
    }

    private fun <T> runOnZtThread(block: () -> T): T {
        val result = AtomicReference<T>()
        val error = AtomicReference<Throwable>()
        val latch = CountDownLatch(1)
        ztHandler.post {
            try {
                result.set(block())
            } catch (e: Throwable) {
                error.set(e)
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(10, TimeUnit.SECONDS)) {
            throw Exception("ZeroTier operation timed out (10s)")
        }
        val err = error.get()
        if (err != null) throw err
        return result.get()
    }

    fun start(context: Context, networkId: String) {
        if (started) {
            log("D", "Already started, skipping")
            return
        }
        log("I", "start() called, network=$networkId")

        if (!nativeLibLoaded) {
            log("E", "Cannot start: libzt not loaded")
            _status.value = Status.OFFLINE
            return
        }
        if (!isValidNetworkId(networkId)) {
            log("E", "Invalid Network ID: '$networkId'")
            _status.value = Status.OFFLINE
            return
        }

        // 异步执行，不阻塞调用线程（防止主线程 ANR）
        ztHandler.post {
            try {
                startInternal(context, networkId)
            } catch (e: Throwable) {
                log("E", "start exception: ${e.javaClass.simpleName}: ${e.message}", e)
                _status.value = Status.OFFLINE
            }
        }
    }

    private fun startInternal(context: Context, networkId: String) {
        _status.value = Status.CONNECTING
        log("I", "startInternal on ZT thread, network=$networkId")

        if (node == null) {
            log("D", "Creating ZeroTierNode...")
            node = ZeroTierNode()
            log("I", "ZeroTierNode created")
        }

        // 每次启动前清除上次可能残留的损坏 ZeroTier 数据目录
        val ztDir = File(context.filesDir, ZT_HOME_DIR)
        ztDir.deleteRecursively()
        ztDir.mkdirs()
        val planetPath = File(ztDir, PLANET_FILE).absolutePath
        log("D", "ZT dir: ${ztDir.absolutePath}")

        // 强制从 assets 复制 planet（确保干净状态）
        log("I", "Copying planet from assets")
        copyPlanetFromAssets(context, planetPath)

        log("D", "Calling initFromStorage...")
        val initResult = node!!.initFromStorage(ztDir.absolutePath)
        log("I", "initFromStorage returned: $initResult")
        if (initResult != 0) {
            _status.value = Status.OFFLINE
            return
        }

        log("D", "Calling node.start()...")
        val startResult = node!!.start()
        log("I", "node.start returned: $startResult")
        if (startResult != 0) {
            _status.value = Status.OFFLINE
            return
        }

        val nwid = networkId.toLong(16)
        log("D", "Calling node.join($networkId)...")
        val joinResult = node!!.join(nwid)
        log("I", "node.join returned: $joinResult")
        if (joinResult != 0) {
            // 不要调用 node.stop() —— 原生层可能崩溃
            log("E", "node.join failed with code $joinResult, abandoning node")
            node = null
            _status.value = Status.OFFLINE
            return
        }

        currentNetworkId = nwid
        currentNetworkIdHex = networkId
        started = true

        startPollingOnline(nwid)
        log("I", "startInternal done, polling started")
    }

    private fun startPollingOnline(nwid: Long) {
        log("I", "Starting online poll (30s timeout)...")
        val maxRetries = 30
        val retries = intArrayOf(0)

        pollRunnable = object : Runnable {
            override fun run() {
                if (!started) return
                try {
                    if (node?.isOnline() == true) {
                        _status.value = Status.ONLINE
                        val addr = node?.getIPv4Address(nwid)?.hostAddress ?: "unknown"
                        val addr6 = node?.getIPv6Address(nwid)?.hostAddress ?: ""
                        val mac = node?.getMACAddress(nwid) ?: ""
                        // 缓存地址，供 getNetworkAddress() / get6PlaneAddress() 非阻塞读取
                        cachedIPv4 = addr
                        cachedIPv6 = addr6
                        log("I", "ONLINE | IPv4=$addr | IPv6=$addr6 | MAC=$mac")
                        return
                    }
                } catch (e: Throwable) {
                    log("D", "Poll err: ${e.message}")
                }
                retries[0]++
                if (retries[0] >= maxRetries) {
                    log("W", "Connection timeout (30s)")
                    _status.value = Status.OFFLINE
                } else {
                    ztHandler.postDelayed(this, 1000)
                }
            }
        }
        ztHandler.postDelayed(pollRunnable!!, 1000)
    }

    fun stop() {
        if (!started) return
        log("I", "Stopping ZeroTier...")
        pollRunnable?.let { ztHandler.removeCallbacks(it) }
        pollRunnable = null

        // 捕获当前状态用于异步清理，避免在 stop 中阻塞调用线程（防止 ANR）
        val nwid = currentNetworkId
        val curNode = node
        val shouldCleanup = nativeLibLoaded && curNode != null

        started = false
        currentNetworkId = 0L
        currentNetworkIdHex = ""
        cachedIPv4 = null
        cachedIPv6 = null
        node = null  // 置空，确保下次 start() 创建新节点而非复用已 stop 的旧节点
        _status.value = Status.STOPPED

        if (shouldCleanup) {
            ztHandler.post {
                try {
                    curNode!!.leave(nwid)
                    curNode.stop()
                    log("I", "ZeroTier stopped")
                } catch (e: Throwable) {
                    log("E", "Error stopping", e)
                }
            }
        }
    }

    fun getNetworkAddress(): String? {
        if (currentNetworkId == 0L || !nativeLibLoaded) return null
        return cachedIPv4
    }

    fun get6PlaneAddress(): String? {
        if (currentNetworkId == 0L || !nativeLibLoaded) return null
        return cachedIPv6
    }

    fun createSocket(): Int {
        if (!nativeLibLoaded) return -1
        return try {
            runOnZtThread { ZeroTierNative.zts_bsd_socket(2, 1, 0) }
        } catch (e: Throwable) {
            log("E", "createSocket failed", e); -1
        }
    }

    fun connectSocket(fd: Int, host: String, port: Int): Int {
        if (!nativeLibLoaded) return -1
        return try {
            runOnZtThread { ZeroTierNative.zts_connect(fd, host, port, 0) }
        } catch (e: Throwable) {
            log("E", "connectSocket failed: fd=$fd $host:$port", e); -1
        }
    }

    fun closeSocket(fd: Int): Int {
        if (!nativeLibLoaded) return -1
        return try {
            runOnZtThread { ZeroTierNative.zts_bsd_close(fd) }
        } catch (e: Throwable) {
            log("E", "closeSocket failed: fd=$fd", e); -1
        }
    }

    private fun copyPlanetFromAssets(context: Context, destPath: String) {
        try {
            context.assets.open("planet").use { input ->
                File(destPath).outputStream().use { output -> input.copyTo(output) }
            }
            log("I", "Planet copied from assets")
        } catch (e: Exception) {
            log("W", "No planet in assets, using defaults")
        }
    }
}




