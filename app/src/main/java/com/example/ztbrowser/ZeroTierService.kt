package com.example.ztbrowser

import android.content.Context
import android.util.Log
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
 */
object ZeroTierService {

    private const val TAG = "ZeroTierService"
    private const val ZT_HOME_DIR = "zerotier"
    private const val PLANET_FILE = "planet"

    init {
        try {
            System.loadLibrary("zt")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load libzt native library", e)
            // 不抛异常，让调用方通过 start() 的返回值得知失败
        }
    }

    enum class Status {
        STOPPED, CONNECTING, ONLINE, OFFLINE
    }

    // libzt 原生方法（JNI）
    private external fun zts_start(identityStoragePath: String, planetPath: String): Int
    private external fun zts_stop(): Int
    private external fun zts_join(networkId: Long): Int
    private external fun zts_leave(networkId: Long): Int
    private external fun zts_is_online(networkId: Long): Boolean
    private external fun zts_get_address(networkId: Long): String
    private external fun zts_get_6plane_addr(networkId: Long): String
    private external fun zts_socket(domain: Int, type: Int, protocol: Int): Int
    private external fun zts_connect(fd: Int, addr: String, port: Int): Int
    private external fun zts_close(fd: Int): Int

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
     */
    fun start(context: Context, networkId: String): Result<Unit> {
        if (started) return Result.success(Unit)

        return try {
            // [FIX#3] 先校验 Network ID
            if (!isValidNetworkId(networkId)) {
                return Result.failure(IllegalArgumentException("Invalid Network ID: 16 hex chars required"))
            }

            _status.value = Status.CONNECTING

            val ztDir = File(context.filesDir, ZT_HOME_DIR).apply { mkdirs() }
            val identityPath = File(ztDir, "identity.secret").absolutePath
            val planetPath = File(ztDir, PLANET_FILE).absolutePath

            if (!File(planetPath).exists()) {
                copyPlanetFromAssets(context, planetPath)
            }

            val result = zts_start(identityPath, planetPath)
            if (result != 0) {
                _status.value = Status.OFFLINE
                return Result.failure(Exception("ZeroTier start failed: code $result"))
            }

            val nwid = networkId.toLong(16)
            val joinResult = zts_join(nwid)
            if (joinResult != 0) {
                zts_stop()
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
        } catch (e: Exception) {
            _status.value = Status.OFFLINE
            Result.failure(e)
        }
    }

    private suspend fun waitForOnline(nwid: Long) {
        var retries = 30
        while (retries > 0 && isActive()) {
            if (zts_is_online(nwid)) {
                _status.value = Status.ONLINE
                val addr = zts_get_address(nwid)
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
        try {
            zts_leave(currentNetworkId)
            zts_stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping ZeroTier", e)
        }
        scope?.cancel()
        scope = null
        started = false
        currentNetworkId = 0L
        _status.value = Status.STOPPED
    }

    private fun isActive(): Boolean = scope?.isActive == true

    fun getNetworkAddress(): String? {
        if (currentNetworkId == 0L) return null
        return try {
            zts_get_address(currentNetworkId)
        } catch (e: Exception) {
            null
        }
    }

    fun get6PlaneAddress(): String? {
        if (currentNetworkId == 0L) return null
        return try {
            zts_get_6plane_addr(currentNetworkId)
        } catch (e: Exception) {
            null
        }
    }

    fun createSocket(): Int {
        return zts_socket(2, 1, 0)
    }

    fun connectSocket(fd: Int, host: String, port: Int): Int {
        return zts_connect(fd, host, port)
    }

    fun closeSocket(fd: Int): Int {
        return zts_close(fd)
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
