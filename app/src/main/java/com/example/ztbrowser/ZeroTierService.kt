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
            "U" -> Log.i(TAG, "[USER] $msg", throwable)
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

    /** 记录用户操作（按钮点击、输入、导航等） */
    fun logUserAction(action: String) {
        log("U", action)
    }

    fun getLog(): String {
        val deviceInfo = "Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} | " +
                "Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}) | " +
                "Arch: ${android.os.Build.SUPPORTED_ABIS.joinToString(",")}"
        val header = "=== ZeroTier Browser Log ===\n$deviceInfo\n\n"

        val memLog = synchronized(logBuffer) { logBuffer.joinToString("\n") }
        val parts = mutableListOf<String>()

        logFilePath?.let { path ->
            try {
                val prevFile = File(path).resolveSibling("zt_log.prev.txt")
                if (prevFile.exists()) parts.add("=== Previous run ===\n" + prevFile.readText())
            } catch (_: Exception) {}
        }

        if (memLog.isNotEmpty()) {
            parts.add(memLog)
        } else {
            logFilePath?.let { path ->
                try {
                    val curFile = File(path)
                    if (curFile.exists()) parts.add(curFile.readText())
                } catch (_: Exception) {}
            }
        }

        return if (parts.isNotEmpty()) header + parts.joinToString("\n\n") else header
    }

    fun isValidNetworkId(id: String): Boolean {
        if (id.length != 16) return false
        return try { id.toLong(16); true } catch (_: NumberFormatException) { false }
    }

    private fun <T> runOnZtThread(timeoutSec: Long = 10, block: () -> T): T {
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
        if (!latch.await(timeoutSec, TimeUnit.SECONDS)) {
            throw Exception("ZeroTier operation timed out (${timeoutSec}s)")
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

        // 选择性清理：删除可能损坏的缓存文件，但保留身份文件（避免 MAC 变化）
        val ztDir = File(context.filesDir, ZT_HOME_DIR)
        ztDir.mkdirs()

        // 备份身份文件
        val identityPublic = File(ztDir, "identity.public")
        val identitySecret = File(ztDir, "identity.secret")
        val savedPublic = identityPublic.takeIf { it.exists() }?.readBytes()
        val savedSecret = identitySecret.takeIf { it.exists() }?.readBytes()

        // 只删除可能损坏的缓存文件（保留身份）
        val toDelete = listOf("planet", "peers.d", "moons.d", "networks.d")
        for (name in toDelete) {
            File(ztDir, name).let { if (it.exists()) it.deleteRecursively() }
        }

        // 恢复身份文件（保证下次启动 MAC 不变）
        if (savedPublic != null) identityPublic.writeBytes(savedPublic)
        if (savedSecret != null) identitySecret.writeBytes(savedSecret)

        // 日志文件重置（deleteRecursively 可能删过 peers.d 等但没有删日志）
        logFilePath?.let { path ->
            try { logFileWriter?.close() } catch (_: Exception) {}
            logFileWriter = PrintWriter(File(path), "UTF-8")
        }
        log("I", "ZT dir cleaned (identity preserved): ${ztDir.absolutePath}")

        log("D", "Creating ZeroTierNode...")
        val ztNode = ZeroTierNode()
        node = ztNode
        log("I", "ZeroTierNode created")

        // 强制从 assets 复制 planet（确保干净状态）
        val planetPath = File(ztDir, PLANET_FILE).absolutePath
        log("I", "Copying planet from assets")
        copyPlanetFromAssets(context, planetPath)

        log("D", "Calling initFromStorage...")
        val initResult = ztNode.initFromStorage(ztDir.absolutePath)
        log("I", "initFromStorage returned: $initResult")
        if (initResult != 0) {
            _status.value = Status.OFFLINE
            return
        }

        log("D", "Calling node.start()...")
        val startResult = ztNode.start()
        log("I", "node.start returned: $startResult")
        if (startResult != 0) {
            _status.value = Status.OFFLINE
            return
        }

        // 等待原生节点完成初始化（start() 返回后仍需时间完成内部设置）
        // Android 16 arm64 上立即 join() 会触发 native SIGSEGV
        try {
            Thread.sleep(2000)
        } catch (_: InterruptedException) {}
        log("D", "Node init delay (2s) done, proceeding to join")

        val nwid = networkId.toLong(16)

        // 强制刷盘，确保崩溃前的日志完整保存
        logFileWriter?.flush()

        val joinResult: Int
        try {
            log("D", "Calling node.join($networkId)...")
            joinResult = ztNode.join(nwid)
            log("I", "node.join returned: $joinResult")
        } catch (e: Throwable) {
            logFileWriter?.flush()
            log("E", "node.join crashed: ${e.javaClass.simpleName}: ${e.message}", e)
            node = null
            _status.value = Status.OFFLINE
            return
        }

        if (joinResult != 0) {
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
        log("I", "Starting online poll (60s timeout, waiting for real IP)...")
        val maxRetries = 60
        val retries = intArrayOf(0)
        var lastDumpRetry = -1

        pollRunnable = object : Runnable {
            override fun run() {
                if (!started) return
                try {
                    if (node?.isOnline() == true) {
                        val addr = node?.getIPv4Address(nwid)?.hostAddress ?: "unknown"
                        val addr6 = node?.getIPv6Address(nwid)?.hostAddress ?: ""
                        val mac = node?.getMACAddress(nwid) ?: ""

                        val isRealAddr = addr != "unknown" &&
                            addr != "::1" && addr != "127.0.0.1" &&
                            !addr.startsWith("0.0.0.0") && !addr.startsWith("::0")

                        if (isRealAddr) {
                            _status.value = Status.ONLINE
                            cachedIPv4 = addr
                            cachedIPv6 = addr6
                            log("I", "ONLINE | IPv4=$addr | IPv6=$addr6 | MAC=$mac")
                            return
                        }

                        // 每 15 次 dump 一次完整的网络诊断信息
                        if (retries[0] % 15 == 0 && retries[0] != lastDumpRetry) {
                            lastDumpRetry = retries[0]
                            dumpNetworkDiagnostics(nwid)
                        }

                        if (retries[0] % 10 == 0) {
                            log("D", "Waiting for IP assignment... (retry ${retries[0]}, IPv4=$addr, IPv6=$addr6)")
                        }
                    }
                } catch (e: Throwable) {
                    log("D", "Poll err: ${e.message}")
                }
                retries[0]++
                if (retries[0] >= maxRetries) {
                    log("W", "Connection timeout (60s) — node online but no IP assigned.")
                    log("W", "Possible causes: 1) node not authorized on controller  2) network ID doesn't exist  3) controller unreachable")
                    _status.value = Status.OFFLINE
                } else {
                    ztHandler.postDelayed(this, 1000)
                }
            }
        }
        ztHandler.postDelayed(pollRunnable!!, 1000)
    }

    /** 诊断 dump：列出节点知道的所有网络信息 */
    private fun dumpNetworkDiagnostics(nwid: Long) {
        try {
            val n = node ?: return
            val online = n.isOnline()
            val ipv4 = n.getIPv4Address(nwid)
            val ipv6 = n.getIPv6Address(nwid)
            val mac = n.getMACAddress(nwid)
            log("D", "--- NETWORK DIAGNOSTIC (retry ${nwid}) ---")
            log("D", "  isOnline()=$online")
            log("D", "  getIPv4Address($nwid)=${ipv4} hostAddr=${ipv4?.hostAddress}")
            log("D", "  getIPv6Address($nwid)=${ipv6} hostAddr=${ipv6?.hostAddress}")
            log("D", "  getMACAddress($nwid)=$mac")
            log("D", "  cachedIPv4=$cachedIPv4  cachedIPv6=$cachedIPv6")
            // 尝试 toString / class 信息
            log("D", "  IPv4 class=${ipv4?.javaClass?.name}  canonical=${ipv4?.canonicalHostName}")
            log("D", "  IPv6 class=${ipv6?.javaClass?.name}  canonical=${ipv6?.canonicalHostName}")
            log("D", "--------------------------------------------")
        } catch (e: Throwable) {
            log("D", "Diagnostic err: ${e.message}")
        }
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
            runOnZtThread(timeoutSec = 5) { ZeroTierNative.zts_connect(fd, host, port, 0) }
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




