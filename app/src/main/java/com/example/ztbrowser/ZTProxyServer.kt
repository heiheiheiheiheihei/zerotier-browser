package com.example.ztbrowser

import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.net.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 本地 SOCKS5 代理服务器
 *
 * 修复记录：
 * - [FIX#4] scope 每次 start 时重建，解决 cancel 后无法重启的 bug
 * - [FIX#5] readFully() 替代 read(byte[])，保证读满 SOCKS5 协议帧
 * - [FIX#6] socks5SendResponse 根据地址类型动态构建响应，支持 IPv6
 * - [FIX#7] connectViaZeroTier 优先使用 libzt 原生 socket
 */
class ZTProxyServer(
    private val port: Int = 1080,
    private val ztSubnets: List<String> = emptyList()
) {
    companion object {
        private const val TAG = "ZTProxyServer"
    }

    // [FIX#4] scope 改为可重建
    private var scope: CoroutineScope? = null
    private var serverSocket: ServerSocket? = null
    private val connectionCounter = AtomicInteger(0)
    private val activeConnections = ConcurrentHashMap<Int, Job>()
    @Volatile private var isActive = false

    fun start() {
        if (isActive) {
            ZeroTierService.log("W", "Proxy already running")
            return
        }

        // [FIX#4] 每次 start 都重建 scope
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        isActive = true

        scope?.launch {
            try {
                serverSocket = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
                ZeroTierService.log("I", "SOCKS5 proxy started on 127.0.0.1:$port")
                ZeroTierService.logUserAction("Proxy started (port=$port, subnets=$ztSubnets)")

                while (isActive) {
                    val clientSocket = try {
                        serverSocket?.accept()
                    } catch (e: SocketException) {
                        if (!isActive) break else throw e
                    } ?: break

                    val connId = connectionCounter.incrementAndGet()
                    val job = scope?.launch {
                        handleConnection(connId, clientSocket)
                    }
                    if (job != null) {
                        activeConnections[connId] = job
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    ZeroTierService.log("E", "Proxy server error", e)
                }
            }
        }
    }

    fun stop() {
        isActive = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        scope?.cancel()
        scope = null
        activeConnections.clear()
        ZeroTierService.log("I", "SOCKS5 proxy stopped")
    }

    private suspend fun handleConnection(connId: Int, clientSocket: Socket) =
        withContext(Dispatchers.IO) {
            val clientAddr = "${clientSocket.inetAddress.hostAddress}:${clientSocket.port}"
            ZeroTierService.log("D", "[$connId] New connection from $clientAddr")
            try {
                clientSocket.soTimeout = 30_000
                val input = clientSocket.getInputStream()
                val output = clientSocket.getOutputStream()

                if (!socks5Handshake(input, output)) {
                    ZeroTierService.log("W", "[$connId] SOCKS5 handshake failed")
                    clientSocket.close()
                    return@withContext
                }
                ZeroTierService.log("D", "[$connId] SOCKS5 handshake OK")

                val target = socks5ReadRequest(input, output) ?: run {
                    ZeroTierService.log("W", "[$connId] SOCKS5 request parse failed")
                    clientSocket.close()
                    return@withContext
                }

                val useZT = isInZTSubnet(target.host)
                ZeroTierService.log("D", "[$connId] Target: ${target.host}:${target.port}, viaZT=$useZT, ZTstatus=${ZeroTierService.status.value}")

                val online = ZeroTierService.status.value == ZeroTierService.Status.ONLINE
                if (useZT && online) {
                    // ZT 路径：用 libzt fd 双向转发，不构造 java.net.Socket
                    handleZTConnection(connId, input, output, target)
                } else {
                    // 直连路径：系统 Socket
                    val remoteSocket = connectDirect(target)
                    socks5SendResponse(output, remoteSocket)
                    val routeMethod = if (useZT) "ZeroTier" else "direct"
                    ZeroTierService.log("I", "[$connId] $routeMethod route → ${target.host}:${target.port}")
                    tunnelSockets(connId, input, output, remoteSocket)
                }
            } catch (e: Exception) {
                ZeroTierService.log("W", "[$connId] Connection error: ${e.message}", e)
            } finally {
                ZeroTierService.log("D", "[$connId] Connection closed")
                try { clientSocket.close() } catch (_: Exception) {}
                activeConnections.remove(connId)
            }
        }

    /**
     * ZT 路径专用：通过 libzt fd 双向转发
     *
     * 关键设计：libzt 是用户态协议栈，zts_bsd_socket 返回的 fd 不是内核 fd，
     * 不能包装成 java.net.Socket（会导致 EBADF）。
     * 正确做法：用 zts_bsd_read/zts_bsd_write 收发，与 client Socket 手动桥接。
     */
    private suspend fun handleZTConnection(
        connId: Int,
        clientInput: InputStream,
        clientOutput: OutputStream,
        target: TargetAddr
    ) {
        var fd = -1
        try {
            // 前置检查：ZT 必须有真实 IP
            val ztAddr = ZeroTierService.getNetworkAddress()
            if (ztAddr == null || ztAddr == "::1" || ztAddr == "127.0.0.1" || ztAddr.startsWith("0.0.0.0")) {
                throw Exception("ZeroTier has no real IP (got=$ztAddr) — node may not be authorized by controller")
            }

            fd = ZeroTierService.createSocket()
            if (fd < 0) throw Exception("zts_socket failed (fd=$fd)")

            // 设置接收超时，避免 read 永久阻塞（30s）
            ZeroTierService.setRecvTimeout(fd, 30, 0)

            val result = ZeroTierService.connectSocket(fd, target.host, target.port)
            if (result < 0) {
                throw Exception("zts_connect failed: $result")
            }

            // SOCKS5 成功响应（ZT 路径下没有 localAddress，用 0.0.0.0 占位）
            socks5SendResponseForFd(clientOutput)
            ZeroTierService.log("I", "[$connId] ZeroTier route → ${target.host}:${target.port}")

            // 双向转发：client → fd（写），fd（读）→ client
            coroutineScope {
                val job1 = launch(Dispatchers.IO) {
                    // client → ZT
                    val buf = ByteArray(8192)
                    try {
                        while (true) {
                            val n = clientInput.read(buf)
                            if (n <= 0) break
                            val toWrite = if (n == buf.size) buf else buf.copyOf(n)
                            val written = ZeroTierService.writeSocket(fd, toWrite)
                            if (written < 0) break
                        }
                    } catch (_: Exception) {}
                    try { ZeroTierService.shutdownSocket(fd, 1) } catch (_: Exception) {}
                }
                val job2 = launch(Dispatchers.IO) {
                    // ZT → client
                    val buf = ByteArray(8192)
                    try {
                        while (true) {
                            val n = ZeroTierService.readSocket(fd, buf)
                            if (n <= 0) break
                            clientOutput.write(buf, 0, n)
                            clientOutput.flush()
                        }
                    } catch (_: Exception) {}
                    try { clientOutput.flush() } catch (_: Exception) {}
                }
                // 任意一方结束，取消另一方
                job1.invokeOnCompletion { job2.cancel() }
                job2.invokeOnCompletion { job1.cancel() }
            }
        } catch (e: Exception) {
            ZeroTierService.log("W", "[$connId] ZT connection failed: ${e.message}", e)
            // 发送 SOCKS5 错误响应（连接失败）
            try { writeSocks5Error(clientOutput, 0x05) } catch (_: Exception) {}
        } finally {
            if (fd >= 0) {
                try { ZeroTierService.closeSocket(fd) } catch (_: Exception) {}
            }
        }
    }

    /** 双向 Socket 转发（直连路径专用） */
    private suspend fun tunnelSockets(
        connId: Int,
        clientInput: InputStream,
        clientOutput: OutputStream,
        remoteSocket: Socket
    ) {
        val remoteInput = remoteSocket.getInputStream()
        val remoteOutput = remoteSocket.getOutputStream()
        coroutineScope {
            val job1 = launch(Dispatchers.IO) {
                try {
                    clientInput.copyTo(remoteOutput, bufferSize = 8192)
                } catch (_: Exception) {}
            }
            val job2 = launch(Dispatchers.IO) {
                try {
                    remoteInput.copyTo(clientOutput, bufferSize = 8192)
                } catch (_: Exception) {}
            }
            job1.invokeOnCompletion { job2.cancel() }
            job2.invokeOnCompletion { job1.cancel() }
        }
        try { remoteSocket.close() } catch (_: Exception) {}
    }

    // ======== SOCKS5 协议实现 ========

    /**
     * [FIX#5] 从 InputStream 读取精确字节数
     */
    private fun readFully(input: InputStream, buf: ByteArray, offset: Int, length: Int): Boolean {
        var remaining = length
        var off = offset
        while (remaining > 0) {
            val n = input.read(buf, off, remaining)
            if (n <= 0) return false // EOF 或异常（read 不应返回 0，但防御处理）
            off += n
            remaining -= n
        }
        return true
    }

    private fun socks5Handshake(input: InputStream, output: OutputStream): Boolean {
        val buf = ByteArray(2)
        if (!readFully(input, buf, 0, 2)) return false
        val ver = buf[0].toInt() and 0xFF
        if (ver != 0x05) return false

        val nmethods = buf[1].toInt() and 0xFF
        if (nmethods <= 0 || nmethods > 255) return false

        val methods = ByteArray(nmethods)
        if (!readFully(input, methods, 0, nmethods)) return false

        // 选择无认证 (0x00)
        // 如果客户端支持 0x00，选 0x00；否则返回 0xFF (无可接受方法)
        if (methods.contains(0x00.toByte())) {
            output.write(byteArrayOf(0x05, 0x00))
        } else {
            output.write(byteArrayOf(0x05, 0xFF.toByte()))
            output.flush()
            return false
        }
        output.flush()
        return true
    }

    private data class TargetAddr(val host: String, val port: Int)

    private fun socks5ReadRequest(input: InputStream, output: OutputStream): TargetAddr? {
        val header = ByteArray(4)
        if (!readFully(input, header, 0, 4)) return null

        if ((header[0].toInt() and 0xFF) != 0x05) return null

        val cmd = header[1].toInt() and 0xFF
        if (cmd != 0x01) {
            // 不支持的命令
            writeSocks5Error(output, 0x07) // Command not supported
            return null
        }

        val atyp = header[3].toInt() and 0xFF
        val host: String = when (atyp) {
            0x01 -> { // IPv4
                val addr = ByteArray(4)
                if (!readFully(input, addr, 0, 4)) return null
                InetAddress.getByAddress(addr).hostAddress ?: return null
            }
            0x03 -> { // 域名
                val lenBuf = ByteArray(1)
                if (!readFully(input, lenBuf, 0, 1)) return null
                val len = lenBuf[0].toInt() and 0xFF
                val domain = ByteArray(len)
                if (!readFully(input, domain, 0, len)) return null
                String(domain)
            }
            0x04 -> { // IPv6
                val addr = ByteArray(16)
                if (!readFully(input, addr, 0, 16)) return null
                InetAddress.getByAddress(addr).hostAddress ?: return null
            }
            else -> {
                writeSocks5Error(output, 0x08) // Address type not supported
                return null
            }
        }

        val portBuf = ByteArray(2)
        if (!readFully(input, portBuf, 0, 2)) return null
        val port = ((portBuf[0].toInt() and 0xFF) shl 8) or (portBuf[1].toInt() and 0xFF)

        return TargetAddr(host, port)
    }

    private fun writeSocks5Error(output: OutputStream, rep: Int) {
        output.write(byteArrayOf(0x05, rep.toByte(), 0x00, 0x01, 0, 0, 0, 0, 0, 0))
        output.flush()
    }

    /**
     * [FIX#6] 根据 remoteSocket 的实际地址类型构建响应（直连路径专用）
     */
    private fun socks5SendResponse(output: OutputStream, remoteSocket: Socket) {
        val localAddr = remoteSocket.localAddress
        val localPort = remoteSocket.localPort

        if (localAddr is Inet6Address) {
            val response = ByteArray(22) // 4 + 16 + 2
            response[0] = 0x05
            response[1] = 0x00
            response[2] = 0x00
            response[3] = 0x04 // ATYP = IPv6
            System.arraycopy(localAddr.address, 0, response, 4, 16)
            response[20] = ((localPort shr 8) and 0xFF).toByte()
            response[21] = (localPort and 0xFF).toByte()
            output.write(response)
        } else {
            val response = ByteArray(10) // 4 + 4 + 2
            response[0] = 0x05
            response[1] = 0x00
            response[2] = 0x00
            response[3] = 0x01 // ATYP = IPv4
            val addrBytes = localAddr?.address ?: byteArrayOf(0, 0, 0, 0)
            System.arraycopy(addrBytes, 0, response, 4, 4)
            response[8] = ((localPort shr 8) and 0xFF).toByte()
            response[9] = (localPort and 0xFF).toByte()
            output.write(response)
        }
        output.flush()
    }

    /**
     * ZT 路径专用 SOCKS5 响应：libzt fd 无法获取 localAddress，用 0.0.0.0:0 占位
     * （SOCKS5 客户端通常忽略 BND.ADDR/BND.PORT，只看 REP=0x00 表示成功）
     */
    private fun socks5SendResponseForFd(output: OutputStream) {
        val response = ByteArray(10)
        response[0] = 0x05  // VER
        response[1] = 0x00  // REP = 成功
        response[2] = 0x00  // RSV
        response[3] = 0x01  // ATYP = IPv4
        // BND.ADDR = 0.0.0.0
        response[4] = 0
        response[5] = 0
        response[6] = 0
        response[7] = 0
        // BND.PORT = 0
        response[8] = 0
        response[9] = 0
        output.write(response)
        output.flush()
    }

    // ======== 子网判断 ========

    private fun isInZTSubnet(host: String): Boolean {
        if (ztSubnets.isEmpty()) return false
        try {
            val addr = InetAddress.getByName(host)
            for (subnet in ztSubnets) {
                if (isInSubnet(addr, subnet)) return true
            }
        } catch (_: Exception) {}
        return false
    }

    private fun isInSubnet(addr: InetAddress, cidr: String): Boolean {
        val parts = cidr.split("/")
        if (parts.size != 2) return false
        val network = try { InetAddress.getByName(parts[0]) } catch (_: Exception) { return false }
        val prefixLen = try { parts[1].toInt() } catch (_: Exception) { return false }

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
        return true
    }

    // ======== 连接方法 ========

    private fun connectDirect(target: TargetAddr): Socket {
        val socket = Socket()
        socket.connect(InetSocketAddress(target.host, target.port), 10_000)
        return socket
    }
}

