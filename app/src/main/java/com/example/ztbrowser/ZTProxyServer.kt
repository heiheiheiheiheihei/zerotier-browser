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

                val remoteSocket: Socket = if (useZT && ZeroTierService.status.value == ZeroTierService.Status.ONLINE) {
                    connectViaZeroTier(target)
                } else {
                    connectDirect(target)
                }

                // [FIX#6] 根据 bind 地址类型构建正确的 SOCKS5 响应
                socks5SendResponse(output, remoteSocket)

                val remoteInput = remoteSocket.getInputStream()
                val remoteOutput = remoteSocket.getOutputStream()

                val routeMethod = if (useZT) "ZeroTier" else "direct"
                ZeroTierService.log("I", "[$connId] $routeMethod route → ${target.host}:${target.port}")

                // 双向转发
                coroutineScope {
                    val job1 = launch(Dispatchers.IO) {
                        try {
                            input.copyTo(remoteOutput, bufferSize = 8192)
                        } catch (_: Exception) {}
                    }
                    val job2 = launch(Dispatchers.IO) {
                        try {
                            remoteInput.copyTo(output, bufferSize = 8192)
                        } catch (_: Exception) {}
                    }
                    // 任意一方结束时取消另一方
                    job1.invokeOnCompletion { job2.cancel() }
                    job2.invokeOnCompletion { job1.cancel() }
                }
            } catch (e: Exception) {
                ZeroTierService.log("W", "[$connId] Connection error: ${e.message}", e)
            } finally {
                ZeroTierService.log("D", "[$connId] Connection closed")
                try { clientSocket.close() } catch (_: Exception) {}
                activeConnections.remove(connId)
            }
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
     * [FIX#6] 根据 remoteSocket 的实际地址类型构建响应
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

    /**
     * [FIX#7] 通过 ZeroTier 网络连接目标
     *
     * libzt 在用户态运行，不创建内核可见的虚拟网卡。
     * 因此系统 Socket() 无法路由到 ZT 子网地址。
     * 正确做法：使用 libzt 提供的 zts_socket/zts_connect 创建原生 socket。
     *
     * 当前实现：先尝试 libzt socket，失败则降级为系统 socket
     * （如果 ZeroTier 同时以系统 VPN 方式安装了虚拟网卡则可路由）
     */
    private fun connectViaZeroTier(target: TargetAddr): Socket {
        // 优先使用 libzt 原生 socket
        return try {
            val fd = ZeroTierService.createSocket()
            if (fd < 0) throw Exception("zts_socket failed")

            val result = ZeroTierService.connectSocket(fd, target.host, target.port)
            if (result < 0) {
                ZeroTierService.closeSocket(fd)
                throw Exception("zts_connect failed: $result")
            }

            // 用 fd 构造 Socket（需要 Android 特有 API 或反射）
            createSocketFromFd(fd)
        } catch (e: Exception) {
            ZeroTierService.log("W", "libzt socket failed: ${e.message}, falling back to system socket", e)
            // 降级：系统 socket（仅当 ZT 以 VPN 方式安装了路由时可用）
            val socket = Socket()
            socket.connect(InetSocketAddress(target.host, target.port), 10_000)
            socket
        }
    }

    /**
     * 从文件描述符构造 Java Socket
     * 简化实现：通过反射或 FileDescriptor 构造
     */
    @Throws(Exception::class)
    private fun createSocketFromFd(fd: Int): Socket {
        // 构造 FileDescriptor 并注入 native fd
        val fileDescriptor = java.io.FileDescriptor::class.java.getDeclaredConstructor().apply {
            isAccessible = true
        }.newInstance()

        val fdField = java.io.FileDescriptor::class.java.getDeclaredField("fd").apply {
            isAccessible = true
        }
        fdField.setInt(fileDescriptor, fd)

        // 构造 PlainSocketImpl(FileDescriptor)
        val socketImplClass = Class.forName("java.net.PlainSocketImpl")
        val socketImpl = socketImplClass.getDeclaredConstructor(java.io.FileDescriptor::class.java).apply {
            isAccessible = true
        }.newInstance(fileDescriptor)

        // 通过 protected Socket(SocketImpl) 构造 Socket 并注入正确的 impl
        val socketCtor = Socket::class.java.getDeclaredConstructor(java.net.SocketImpl::class.java)
        socketCtor.isAccessible = true
        val socket = socketCtor.newInstance(socketImpl)

        return socket
    }

    private fun connectDirect(target: TargetAddr): Socket {
        val socket = Socket()
        socket.connect(InetSocketAddress(target.host, target.port), 10_000)
        return socket
    }
}

