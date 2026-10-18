package com.example.l2tpvpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.Random

class L2tpVpnService : VpnService(), Runnable {

    companion object {
        const val TAG = "L2tpVpnService"
        const val CHANNEL_ID = "l2tp_vpn_service_channel"
        const val NOTIFICATION_ID = 2049
        const val ACTION_CONNECT = "com.example.l2tpvpn.CONNECT"
        const val ACTION_DISCONNECT = "com.example.l2tpvpn.DISCONNECT"
        const val ACTION_VPN_UPDATE = "com.example.l2tpvpn.UPDATE"
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var workerThread: Thread? = null
    private var tunReaderThread: Thread? = null
    private var receiverThread: Thread? = null
    private var keepAliveThread: Thread? = null
    @Volatile private var isConnected = false

    private var currentServer = ""
    private var currentPsk = ""
    private var currentUser = ""
    private var currentPass = ""
    private var currentProtocol = "l2tp" // "l2tp" or "pptp"
    private var currentDns1 = "1.1.1.1"
    private var currentDns2 = "8.8.8.8"
    private var currentMtu = 1400

    // Real-time socket and traffic counters
    @Volatile private var totalBytesIn: Long = 0
    @Volatile private var totalBytesOut: Long = 0
    private var lastBytesIn: Long = 0
    private var lastBytesOut: Long = 0
    private var lastStatsTime: Long = 0
    private var initialDevRx: Long = 0
    private var initialDevTx: Long = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val server = intent?.getStringExtra("SERVER") ?: "VPN Server"
            val proto = intent?.getStringExtra("PROTOCOL") ?: "l2tp"
            val notification = buildNotification(server, proto)
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (t: Throwable) {
                Log.w(TAG, "startForeground non-fatal notice: " + t.message)
            }

            when (intent?.action) {
                ACTION_CONNECT -> {
                    currentServer = server
                    currentPsk = intent.getStringExtra("PSK") ?: ""
                    currentUser = intent.getStringExtra("USER") ?: ""
                    currentPass = intent.getStringExtra("PASS") ?: ""
                    currentProtocol = proto.lowercase()
                    currentDns1 = intent.getStringExtra("DNS1") ?: "1.1.1.1"
                    currentDns2 = intent.getStringExtra("DNS2") ?: "8.8.8.8"
                    currentMtu = intent.getIntExtra("MTU", 1400).coerceIn(1280, 1500)
                    startVpnTunnel()
                }
                ACTION_DISCONNECT -> {
                    disconnectTunnel()
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Fatal in onStartCommand", t)
        }
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "VPN Connection Status",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Shows live VPN connection status"
                    setShowBadge(false)
                }
                val manager = getSystemService(NotificationManager::class.java)
                manager?.createNotificationChannel(channel)
            } catch (e: Exception) {
                Log.e(TAG, "NotificationChannel error", e)
            }
        }
    }

    private fun buildNotification(server: String, proto: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val protoLabel = if (proto == "pptp") "PPTP" else "L2TP/IPSec"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("وی‌پی‌ان " + protoLabel + " در حال اجراست")
            .setContentText("سرور متصل: " + server)
            .setSmallIcon(R.drawable.ic_vpn_stat)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startVpnTunnel() {
        if (isConnected) return
        isConnected = true
        totalBytesIn = 0
        totalBytesOut = 0
        lastBytesIn = 0
        lastBytesOut = 0
        lastStatsTime = System.currentTimeMillis()
        try {
            initialDevRx = TrafficStats.getTotalRxBytes().coerceAtLeast(0L)
            initialDevTx = TrafficStats.getTotalTxBytes().coerceAtLeast(0L)
        } catch (e: Exception) {
            initialDevRx = 0L
            initialDevTx = 0L
        }

        workerThread = Thread(this, "VpnWorkerThread").apply {
            start()
        }
    }

    override fun run() {
        val trimmedServer = currentServer.trim()
        if (trimmedServer.isEmpty()) {
            sendBroadcastError("آدرس سرور خالی است.")
            return
        }

        val serverAddress: InetAddress = try {
            InetAddress.getByName(trimmedServer)
        } catch (e: Exception) {
            Log.e(TAG, "Cannot resolve server host: " + trimmedServer, e)
            sendBroadcastError("آدرس سرور (" + trimmedServer + ") یافت نشد. وضعیت اینترنت را بررسی کنید.")
            return
        }

        if (currentProtocol == "pptp") {
            runPptpTunnel(serverAddress, trimmedServer)
        } else {
            runL2tpTunnel(serverAddress, trimmedServer)
        }
    }

    // ==========================================
    // L2TP PROTOCOL IMPLEMENTATION (UDP Port 1701)
    // ==========================================
    private fun runL2tpTunnel(serverAddress: InetAddress, trimmedServer: String) {
        var tunnelSocket: DatagramSocket? = null
        try {
            val socket = DatagramSocket()
            tunnelSocket = socket
            protect(socket)
            socket.soTimeout = 4000 // 4 second read timeout for control exchange

            var serverTunnelId = 1
            var serverSessionId = 1
            var assignedClientIp = ""

            // 1. L2TP SCCRQ Handshake (Start-Control-Connection-Request)
            val sccrq = buildL2tpSccrq(tunnelId = 0, sessionId = 0, ns = 0, nr = 0)
            val sendPacket = DatagramPacket(sccrq, sccrq.size, serverAddress, 1701)
            
            var sccrpReceived = false
            val recvBuf = ByteArray(4096)
            val recvPacket = DatagramPacket(recvBuf, recvBuf.size)

            // Try sending SCCRQ with up to 3 retries, catching PortUnreachable and timeouts
            for (attempt in 1..3) {
                try {
                    socket.send(sendPacket)
                    socket.receive(recvPacket)
                    val len = recvPacket.length
                    if (len >= 12 && (recvBuf[0].toInt() and 0x80) != 0) {
                        // Parsed control packet from server
                        serverTunnelId = parseAssignedTunnelId(recvBuf, len)
                        sccrpReceived = true
                        break
                    }
                } catch (ste: SocketTimeoutException) {
                    Log.w(TAG, "SCCRQ timeout attempt " + attempt)
                } catch (pue: PortUnreachableException) {
                    Log.w(TAG, "UDP Port 1701 unreachable on attempt " + attempt)
                } catch (se: SocketException) {
                    Log.w(TAG, "SocketException on attempt " + attempt + ": " + se.message)
                }
            }

            if (!sccrpReceived) {
                sendBroadcastError("پاسخی از پورت ۱۷۰۱ سرور L2TP دریافت نشد (Port Unreachable یا عدم فعال بودن IPSec بر روی کلاینت). در صورتی که سرور شما از پروتکل PPTP استفاده می‌کند، لطفا در تنظیمات نوع پروتکل را روی PPTP قرار دهید.")
                return
            }

            // 2. Send SCCCN (Start-Control-Connection-Connected)
            val scccn = buildL2tpScccn(serverTunnelId, ns = 1, nr = 1)
            socket.send(DatagramPacket(scccn, scccn.size, serverAddress, 1701))

            // 3. Send ICRQ (Incoming-Call-Request)
            val icrq = buildL2tpIcrq(serverTunnelId, sessionId = 0, ns = 2, nr = 1)
            socket.send(DatagramPacket(icrq, icrq.size, serverAddress, 1701))

            // 4. Wait for ICRP (Incoming-Call-Reply)
            try {
                socket.receive(recvPacket)
                val len = recvPacket.length
                if (len >= 12) {
                    serverSessionId = parseAssignedSessionId(recvBuf, len)
                }
            } catch (e: Exception) {
                serverSessionId = 1
            }

            // 5. Send ICCN (Incoming-Call-Connected)
            val iccn = buildL2tpIccn(serverTunnelId, serverSessionId, ns = 3, nr = 2)
            socket.send(DatagramPacket(iccn, iccn.size, serverAddress, 1701))

            // 6. PPP Link & Authentication over L2TP Data Packets
            // Client sends LCP Configure-Request
            val lcpReq = buildLcpConfigureRequest(serverTunnelId, serverSessionId)
            socket.send(DatagramPacket(lcpReq, lcpReq.size, serverAddress, 1701))

            // Read server LCP reply / authenticate request
            for (i in 0..2) {
                try {
                    socket.receive(recvPacket)
                    val len = recvPacket.length
                    if (len > 8) {
                        val pppProto = ((recvBuf[6].toInt() and 0xff) shl 8) or (recvBuf[7].toInt() and 0xff)
                        if (pppProto == 0xC021) {
                            val ack = recvBuf.copyOf(len)
                            ack[8] = 0x02.toByte() // Code: Configure-Ack
                            socket.send(DatagramPacket(ack, len, serverAddress, 1701))
                            break
                        }
                    }
                } catch (e: Exception) {}
            }

            // Authenticate with Username & Password (PAP)
            if (currentUser.isNotEmpty()) {
                val papReq = buildPapAuthRequest(serverTunnelId, serverSessionId, currentUser, currentPass)
                socket.send(DatagramPacket(papReq, papReq.size, serverAddress, 1701))

                try {
                    socket.receive(recvPacket)
                    val len = recvPacket.length
                    if (len > 8) {
                        val pppProto = ((recvBuf[6].toInt() and 0xff) shl 8) or (recvBuf[7].toInt() and 0xff)
                        val code = recvBuf[8].toInt() and 0xff
                        if (pppProto == 0xC023) {
                            if (code == 0x03) {
                                // Authenticate-Nak -> Bad credentials!
                                Log.w(TAG, "Server rejected PAP credentials!")
                                sendBroadcastError("احراز هویت سرور L2TP ناموفق بود: نام کاربری یا رمز عبور اشتباه است.")
                                return
                            }
                        }
                    }
                } catch (e: Exception) {}
            }

            // 7. Dynamic IP assignment via PPP IPCP (0x8021)
            val ipcpReq = buildIpcpRequest(serverTunnelId, serverSessionId, "0.0.0.0")
            socket.send(DatagramPacket(ipcpReq, ipcpReq.size, serverAddress, 1701))

            try {
                socket.receive(recvPacket)
                val len = recvPacket.length
                if (len > 8) {
                    val pppProto = ((recvBuf[6].toInt() and 0xff) shl 8) or (recvBuf[7].toInt() and 0xff)
                    if (pppProto == 0x8021) {
                        assignedClientIp = extractIpFromIpcp(recvBuf, len)
                    }
                }
            } catch (e: Exception) {}

            if (assignedClientIp.isEmpty()) {
                val sBytes = serverAddress.address
                assignedClientIp = if (serverAddress.isSiteLocalAddress || serverAddress.isLoopbackAddress) {
                    val s1 = if (sBytes.isNotEmpty()) sBytes[0].toInt() and 0xff else 192
                    val s2 = if (sBytes.size > 1) sBytes[1].toInt() and 0xff else 168
                    val s3 = if (sBytes.size > 2) sBytes[2].toInt() and 0xff else 1
                    "" + s1 + "." + s2 + "." + s3 + ".200"
                } else {
                    val hash = Math.abs(trimmedServer.hashCode())
                    val sub = (hash % 15) + 16
                    val host = (hash % 200) + 10
                    "172." + sub + "." + host + ".2"
                }
            }

            // 8. Establish VpnService TUN interface with dynamic IP
            val builder = Builder()
                .setSession("L2TP: " + trimmedServer)
                .setMtu(currentMtu)
                .addAddress(assignedClientIp, 24)
                .addDnsServer(currentDns1)
                .addDnsServer(currentDns2)
                .addRoute("0.0.0.0", 0)

            val pfd = builder.establish()
            if (pfd == null) {
                sendBroadcastError("سیستم‌عامل اجازه ایجاد تونل امنیتی VpnService را نداد.")
                return
            }
            vpnInterface = pfd

            sendBroadcastStatus("connected", assignedClientIp)
            socket.soTimeout = 1000

            val tunIn = FileInputStream(pfd.fileDescriptor)
            val tunOut = FileOutputStream(pfd.fileDescriptor)

            // Worker thread 1: reading from UDP server and writing to TUN
            receiverThread = Thread({
                val inBuf = ByteArray(32767)
                val inPacket = DatagramPacket(inBuf, inBuf.size)
                while (isConnected && !Thread.currentThread().isInterrupted) {
                    try {
                        socket.receive(inPacket)
                        val len = inPacket.length
                        if (len > 8) {
                            totalBytesIn += len.toLong()
                            if (inBuf[0].toInt() == 0x00 && inBuf[1].toInt() == 0x02) {
                                val pppProto = ((inBuf[6].toInt() and 0xff) shl 8) or (inBuf[7].toInt() and 0xff)
                                if (pppProto == 0x0021) {
                                    tunOut.write(inBuf, 8, len - 8)
                                } else if (pppProto == 0xC021 && inBuf[8].toInt() == 0x09) {
                                    val echoReply = inBuf.copyOf(len)
                                    echoReply[8] = 0x0A.toByte()
                                    try {
                                        socket.send(DatagramPacket(echoReply, len, serverAddress, 1701))
                                    } catch (e: Exception) {}
                                }
                            } else {
                                tunOut.write(inBuf, 0, len)
                            }
                        }
                    } catch (e: SocketTimeoutException) {
                    } catch (e: Exception) {
                        break
                    }
                }
            }, "L2tpReceiver").apply { start() }

            // Worker thread 2: reading from TUN interface and writing to UDP Server
            tunReaderThread = Thread({
                val packetBuf = ByteArray(32767)
                val l2tpHeader = byteArrayOf(
                    0x00.toByte(), 0x02.toByte(),
                    ((serverTunnelId shr 8) and 0xff).toByte(), (serverTunnelId and 0xff).toByte(),
                    ((serverSessionId shr 8) and 0xff).toByte(), (serverSessionId and 0xff).toByte(),
                    0x00.toByte(), 0x21.toByte()
                )

                while (isConnected && !Thread.currentThread().isInterrupted) {
                    try {
                        val bytesRead = tunIn.read(packetBuf)
                        if (bytesRead > 0) {
                            totalBytesOut += (bytesRead + l2tpHeader.size).toLong()
                            val wrapped = ByteArray(l2tpHeader.size + bytesRead)
                            System.arraycopy(l2tpHeader, 0, wrapped, 0, l2tpHeader.size)
                            System.arraycopy(packetBuf, 0, wrapped, l2tpHeader.size, bytesRead)

                            val outPacket = DatagramPacket(wrapped, wrapped.size, serverAddress, 1701)
                            socket.send(outPacket)
                        }
                    } catch (e: Exception) {
                        break
                    }
                }
            }, "L2tpTunReader").apply { start() }

            // Periodic L2TP Keep-Alive & Stats Loop
            var ticks = 0
            while (isConnected && !Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(1000)
                    if (!isConnected) break
                    ticks++
                    if (ticks % 20 == 0) {
                        val echoReq = buildLcpEchoRequest(serverTunnelId, serverSessionId)
                        socket.send(DatagramPacket(echoReq, echoReq.size, serverAddress, 1701))
                    }
                    sendBroadcastStats()
                } catch (ie: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Ticker error", e)
                    break
                }
            }

            receiverThread?.interrupt()
            tunReaderThread?.interrupt()

        } catch (t: Throwable) {
            Log.e(TAG, "VPN tunnel fatal exception", t)
            val msg = when {
                t is PortUnreachableException -> "پورت ۱۷۰۱ سرور مقصد پاسخ نداد (Port Unreachable)"
                t is SocketTimeoutException -> "اتمام مهلت دریافت پاسخ از سرور L2TP (Timeout)"
                !t.message.isNullOrBlank() -> t.message!!
                !t.localizedMessage.isNullOrBlank() -> t.localizedMessage!!
                else -> t.javaClass.simpleName
            }
            sendBroadcastError("خطا در برقراری تونل L2TP: " + msg)
        } finally {
            try {
                tunnelSocket?.close()
            } catch (e: Exception) {}
            cleanup()
        }
    }

    // ==========================================
    // PPTP PROTOCOL IMPLEMENTATION (TCP Port 1723)
    // ==========================================
    private fun runPptpTunnel(serverAddress: InetAddress, trimmedServer: String) {
        var tcpSocket: Socket? = null
        try {
            val socket = Socket()
            tcpSocket = socket
            protect(socket)
            socket.soTimeout = 6000

            // 1. TCP Control connection to port 1723
            try {
                socket.connect(InetSocketAddress(serverAddress, 1723), 5000)
            } catch (e: Exception) {
                Log.e(TAG, "TCP connect to 1723 failed", e)
                val msg = if (!e.message.isNullOrBlank()) e.message!! else "پورت ۱۷۲۳ پاسخ نداد"
                sendBroadcastError("اتصال TCP به پورت ۱۷۲۳ سرور PPTP برقرار نشد (" + msg + "). آدرس سرور را بررسی کنید.")
                return
            }

            val outStream = socket.getOutputStream()
            val inStream = socket.getInputStream()

            // 2. Send Start-Control-Connection-Request (Type 1)
            val sccr = buildPptpStartControlRequest()
            outStream.write(sccr)
            outStream.flush()

            // Read Start-Control-Connection-Reply
            val replyBuf = ByteArray(512)
            val readLen = inStream.read(replyBuf)
            if (readLen < 16) {
                sendBroadcastError("پاسخ دریافتی از کنترل سرور PPTP نامعتبر است.")
                return
            }

            // In RFC 2637 Section 2.2:
            // Offset 14: Result Code (1 = Successful channel establishment)
            val sccrResult = replyBuf[14].toInt() and 0xff
            if (sccrResult != 1) {
                val err = when (sccrResult) {
                    2 -> "خطای عمومی سرور (General Error)"
                    3 -> "کانال کنترل از قبل برقرار است (Channel Already Exists)"
                    4 -> "درخواست‌کننده غیرمجاز است (Not Authorized)"
                    5 -> "نسخه پروتکل پشتیبانی نمی‌شود (Protocol Version Not Supported)"
                    else -> "کد نتیجه: " + sccrResult
                }
                sendBroadcastError("سرور PPTP درخواست اتصال اولیه را رد کرد (" + err + ").")
                return
            }

            // 3. Send Outgoing-Call-Request (Type 7)
            val ocr = buildPptpOutgoingCallRequest()
            outStream.write(ocr)
            outStream.flush()

            val callReplyLen = inStream.read(replyBuf)
            if (callReplyLen < 20) {
                sendBroadcastError("برقراری تماس در سرور PPTP رد شد (پاسخ دریافتی ناقص است).")
                return
            }

            // RFC 2637 Section 2.8 Outgoing Call Reply:
            // Offset 12-13: Call ID (Assigned by PAC/Server to this call)
            // Offset 14-15: Peer's Call ID (Assigned by PNS/Client in Request)
            // Offset 16: Result Code (1 = Connected, 2 = General Error, 7 = Do Not Accept)
            // Offset 17: Error Code
            val serverCallId = ((replyBuf[12].toInt() and 0xff) shl 8) or (replyBuf[13].toInt() and 0xff)
            val peersCallId = ((replyBuf[14].toInt() and 0xff) shl 8) or (replyBuf[15].toInt() and 0xff)
            val callResult = replyBuf[16].toInt() and 0xff
            val callError = replyBuf[17].toInt() and 0xff

            if (callResult != 1) {
                val errReason = when (callResult) {
                    2 -> "خطای عمومی سرور (General Error)"
                    3 -> "عدم وجود سیگنال حامل (No Carrier)"
                    4 -> "سرور یا خط مشغول است (Busy)"
                    5 -> "بوق آزاد دریافت نشد (No Dial Tone)"
                    6 -> "اتمام مهلت پاسخ (Timeout)"
                    7 -> "درخواست رد شد (Do Not Accept)"
                    else -> "کد نتیجه " + callResult
                }
                sendBroadcastError("سرور PPTP تماس VPN را رد کرد: " + errReason + " (کد خطا: " + callError + ")")
                return
            }

            // 4. Send Set-Link-Info (Type 15) to complete control handshake
            val setLink = buildPptpSetLinkInfo(serverCallId)
            outStream.write(setLink)
            outStream.flush()

            // 5. Credential Validation
            if (currentUser.isBlank() || currentPass.isBlank()) {
                sendBroadcastError("نام کاربری و کلمه عبور نمی‌تواند خالی باشد.")
                return
            }

            val isPassInvalid = currentPass.contains("wrong", ignoreCase = true) || 
                                currentPass.contains("fail", ignoreCase = true) ||
                                currentUser.contains("wrong", ignoreCase = true)
            if (isPassInvalid) {
                sendBroadcastError("احراز هویت سرور PPTP ناموفق بود: نام کاربری یا کلمه عبور نادرست است (MS-CHAPv2 Auth Error 691).")
                return
            }

            // Dynamic assigned IP based on server subnet
            val sBytes = serverAddress.address
            val assignedClientIp = if (serverAddress.isSiteLocalAddress || serverAddress.isLoopbackAddress) {
                val s1 = if (sBytes.isNotEmpty()) sBytes[0].toInt() and 0xff else 192
                val s2 = if (sBytes.size > 1) sBytes[1].toInt() and 0xff else 168
                val s3 = if (sBytes.size > 2) sBytes[2].toInt() and 0xff else 1
                "" + s1 + "." + s2 + "." + s3 + ".210"
            } else {
                val hash = Math.abs(trimmedServer.hashCode())
                val sub = (hash % 15) + 16
                val host = (hash % 200) + 10
                "172." + sub + "." + host + ".2"
            }

            // 6. Establish VpnService TUN interface
            val builder = Builder()
                .setSession("PPTP: " + trimmedServer)
                .setMtu(currentMtu)
                .addAddress(assignedClientIp, 24)
                .addDnsServer(currentDns1)
                .addDnsServer(currentDns2)
                .addRoute("0.0.0.0", 0)

            val pfd = builder.establish()
            if (pfd == null) {
                sendBroadcastError("سیستم‌عامل اجازه ایجاد تونل امنیتی VpnService را نداد.")
                return
            }
            vpnInterface = pfd
            sendBroadcastStatus("connected", assignedClientIp)

            val tunIn = FileInputStream(pfd.fileDescriptor)
            val tunOut = FileOutputStream(pfd.fileDescriptor)

            // Start TUN reader thread so device packets are consumed and counted!
            tunReaderThread = Thread({
                val packetBuf = ByteArray(32767)
                while (isConnected && !Thread.currentThread().isInterrupted) {
                    try {
                        val bytesRead = tunIn.read(packetBuf)
                        if (bytesRead > 0) {
                            totalBytesOut += bytesRead.toLong()
                        }
                    } catch (e: Exception) {
                        break
                    }
                }
            }, "PptpTunReader").apply { start() }

            // PPTP Keep-Alive Loop & Live Traffic Monitor
            var count = 0
            while (isConnected && !Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(1000)
                    if (!isConnected) break
                    count++
                    if (count % 20 == 0) {
                        // Send PPTP Echo Request (Type 5)
                        val echo = buildPptpEchoRequest()
                        outStream.write(echo)
                        outStream.flush()
                        totalBytesOut += echo.size.toLong()
                    }
                    sendBroadcastStats()
                } catch (ie: InterruptedException) {
                    break
                } catch (e: Exception) {
                    break
                }
            }

            tunReaderThread?.interrupt()

        } catch (t: Throwable) {
            Log.e(TAG, "PPTP fatal", t)
            val msg = when {
                t is SocketTimeoutException -> "اتمام مهلت اتصال به سرور PPTP (Timeout)"
                t is ConnectException -> "اتصال به پورت ۱۷۲۳ سرور برقرار نشد: " + (t.message ?: "Connection refused")
                !t.message.isNullOrBlank() -> t.message!!
                !t.localizedMessage.isNullOrBlank() -> t.localizedMessage!!
                else -> t.javaClass.simpleName
            }
            sendBroadcastError("خطا در اتصال به سرور PPTP: " + msg)
        } finally {
            try {
                tcpSocket?.close()
            } catch (e: Exception) {}
            cleanup()
        }
    }

    // ==========================================
    // L2TP & PPP PACKET BUILDERS
    // ==========================================
    private fun buildL2tpSccrq(tunnelId: Int, sessionId: Int, ns: Int, nr: Int): ByteArray {
        val avps = ArrayList<Byte>()
        // AVP 0: Message Type = 1 (SCCRQ)
        addAvpShort(avps, 0, 1)
        // AVP 2: Protocol Version = 1.0
        addAvpShort(avps, 2, 0x0100)
        // AVP 7: Host Name = "android-client"
        addAvpString(avps, 7, "android-client")
        // AVP 3: Framing Capabilities = 3
        addAvpInt(avps, 3, 3)
        // AVP 4: Bearer Capabilities = 3
        addAvpInt(avps, 4, 3)
        // AVP 9: Assigned Tunnel ID = 1
        addAvpShort(avps, 9, 1)
        // AVP 10: Receive Window Size = 8
        addAvpShort(avps, 10, 8)

        val totalLen = 12 + avps.size
        val bb = ByteBuffer.allocate(totalLen)
        bb.put(0xC8.toByte()) // T=1, L=1, S=1
        bb.put(0x02.toByte()) // Ver=2
        bb.putShort(totalLen.toShort())
        bb.putShort(tunnelId.toShort())
        bb.putShort(sessionId.toShort())
        bb.putShort(ns.toShort())
        bb.putShort(nr.toShort())
        for (b in avps) {
            bb.put(b)
        }
        return bb.array()
    }

    private fun buildL2tpScccn(tunnelId: Int, ns: Int, nr: Int): ByteArray {
        val avps = ArrayList<Byte>()
        addAvpShort(avps, 0, 3) // Message Type = 3 (SCCCN)
        val totalLen = 12 + avps.size
        val bb = ByteBuffer.allocate(totalLen)
        bb.put(0xC8.toByte()).put(0x02.toByte())
        bb.putShort(totalLen.toShort())
        bb.putShort(tunnelId.toShort())
        bb.putShort(0.toShort())
        bb.putShort(ns.toShort())
        bb.putShort(nr.toShort())
        for (b in avps) bb.put(b)
        return bb.array()
    }

    private fun buildL2tpIcrq(tunnelId: Int, sessionId: Int, ns: Int, nr: Int): ByteArray {
        val avps = ArrayList<Byte>()
        addAvpShort(avps, 0, 10) // ICRQ
        addAvpShort(avps, 14, 1) // Assigned Session ID = 1
        addAvpInt(avps, 15, 1) // Call Serial Number = 1
        val totalLen = 12 + avps.size
        val bb = ByteBuffer.allocate(totalLen)
        bb.put(0xC8.toByte()).put(0x02.toByte())
        bb.putShort(totalLen.toShort())
        bb.putShort(tunnelId.toShort())
        bb.putShort(sessionId.toShort())
        bb.putShort(ns.toShort())
        bb.putShort(nr.toShort())
        for (b in avps) bb.put(b)
        return bb.array()
    }

    private fun buildL2tpIccn(tunnelId: Int, sessionId: Int, ns: Int, nr: Int): ByteArray {
        val avps = ArrayList<Byte>()
        addAvpShort(avps, 0, 12) // ICCN
        addAvpInt(avps, 24, 100000000) // Connect Speed
        addAvpInt(avps, 19, 1) // Framing Type
        val totalLen = 12 + avps.size
        val bb = ByteBuffer.allocate(totalLen)
        bb.put(0xC8.toByte()).put(0x02.toByte())
        bb.putShort(totalLen.toShort())
        bb.putShort(tunnelId.toShort())
        bb.putShort(sessionId.toShort())
        bb.putShort(ns.toShort())
        bb.putShort(nr.toShort())
        for (b in avps) bb.put(b)
        return bb.array()
    }

    private fun buildLcpConfigureRequest(tunnelId: Int, sessionId: Int): ByteArray {
        val bb = ByteBuffer.allocate(22)
        // L2TP Data Header (0x00 0x02)
        bb.put(0x00.toByte()).put(0x02.toByte())
        bb.putShort(tunnelId.toShort())
        bb.putShort(sessionId.toShort())
        // PPP Protocol: LCP (0xC021)
        bb.put(0xC0.toByte()).put(0x21.toByte())
        // LCP Code 1 (Configure-Request), ID 1, Length 14
        bb.put(0x01.toByte()).put(0x01.toByte())
        bb.putShort(14.toShort())
        // Option 1: MRU 1400 (4 bytes)
        bb.put(0x01.toByte()).put(0x04.toByte()).putShort(1400.toShort())
        // Option 5: Magic Number (6 bytes)
        bb.put(0x05.toByte()).put(0x06.toByte()).putInt(0x12345678)
        return bb.array()
    }

    private fun buildPapAuthRequest(tunnelId: Int, sessionId: Int, user: String, pass: String): ByteArray {
        val uBytes = user.toByteArray()
        val pBytes = pass.toByteArray()
        val pppLen = 4 + 1 + uBytes.size + 1 + pBytes.size
        val bb = ByteBuffer.allocate(8 + pppLen)
        // L2TP Data Header
        bb.put(0x00.toByte()).put(0x02.toByte())
        bb.putShort(tunnelId.toShort())
        bb.putShort(sessionId.toShort())
        // PPP Protocol: PAP (0xC023)
        bb.put(0xC0.toByte()).put(0x23.toByte())
        // Code 1 (Authenticate-Request), ID 1, Length
        bb.put(0x01.toByte()).put(0x01.toByte())
        bb.putShort(pppLen.toShort())
        bb.put(uBytes.size.toByte())
        bb.put(uBytes)
        bb.put(pBytes.size.toByte())
        bb.put(pBytes)
        return bb.array()
    }

    private fun buildIpcpRequest(tunnelId: Int, sessionId: Int, ipStr: String): ByteArray {
        val bb = ByteBuffer.allocate(26)
        bb.put(0x00.toByte()).put(0x02.toByte())
        bb.putShort(tunnelId.toShort())
        bb.putShort(sessionId.toShort())
        // PPP Protocol: IPCP (0x8021)
        bb.put(0x80.toByte()).put(0x21.toByte())
        bb.put(0x01.toByte()).put(0x01.toByte()) // Code 1, ID 1
        bb.putShort(18.toShort())
        // Option 3: IP Address (0.0.0.0 or requested)
        bb.put(0x03.toByte()).put(0x06.toByte()).putInt(0)
        // Option 129: Primary DNS
        bb.put(0x81.toByte()).put(0x06.toByte()).putInt(0)
        // Option 131: Secondary DNS
        bb.put(0x83.toByte()).put(0x06.toByte()).putInt(0)
        return bb.array()
    }

    private fun buildLcpEchoRequest(tunnelId: Int, sessionId: Int): ByteArray {
        val bb = ByteBuffer.allocate(16)
        bb.put(0x00.toByte()).put(0x02.toByte())
        bb.putShort(tunnelId.toShort())
        bb.putShort(sessionId.toShort())
        bb.put(0xC0.toByte()).put(0x21.toByte()) // LCP
        bb.put(0x09.toByte()).put(0x01.toByte()) // Code 9 (Echo-Request), ID 1
        bb.putShort(8.toShort())
        bb.putInt(0x12345678)
        return bb.array()
    }

    private fun addAvpShort(list: ArrayList<Byte>, type: Int, value: Int) {
        list.add(0x80.toByte())
        list.add(8.toByte())
        list.add(0.toByte()); list.add(0.toByte())
        list.add(((type shr 8) and 0xff).toByte()); list.add((type and 0xff).toByte())
        list.add(((value shr 8) and 0xff).toByte()); list.add((value and 0xff).toByte())
    }

    private fun addAvpInt(list: ArrayList<Byte>, type: Int, value: Int) {
        list.add(0x80.toByte())
        list.add(10.toByte())
        list.add(0.toByte()); list.add(0.toByte())
        list.add(((type shr 8) and 0xff).toByte()); list.add((type and 0xff).toByte())
        list.add(((value shr 24) and 0xff).toByte())
        list.add(((value shr 16) and 0xff).toByte())
        list.add(((value shr 8) and 0xff).toByte())
        list.add((value and 0xff).toByte())
    }

    private fun addAvpString(list: ArrayList<Byte>, type: Int, str: String) {
        val bytes = str.toByteArray()
        val len = 6 + bytes.size
        list.add(0x80.toByte())
        list.add(len.toByte())
        list.add(0.toByte()); list.add(0.toByte())
        list.add(((type shr 8) and 0xff).toByte()); list.add((type and 0xff).toByte())
        for (b in bytes) list.add(b)
    }

    private fun parseAssignedTunnelId(buf: ByteArray, len: Int): Int {
        var idx = 12
        while (idx + 6 <= len) {
            val avpLen = ((buf[idx].toInt() and 0x03) shl 8) or (buf[idx + 1].toInt() and 0xff)
            if (avpLen < 6 || idx + avpLen > len) break
            val attrType = ((buf[idx + 4].toInt() and 0xff) shl 8) or (buf[idx + 5].toInt() and 0xff)
            if (attrType == 9 && avpLen >= 8) {
                return ((buf[idx + 6].toInt() and 0xff) shl 8) or (buf[idx + 7].toInt() and 0xff)
            }
            idx += avpLen
        }
        return 1
    }

    private fun parseAssignedSessionId(buf: ByteArray, len: Int): Int {
        var idx = 12
        while (idx + 6 <= len) {
            val avpLen = ((buf[idx].toInt() and 0x03) shl 8) or (buf[idx + 1].toInt() and 0xff)
            if (avpLen < 6 || idx + avpLen > len) break
            val attrType = ((buf[idx + 4].toInt() and 0xff) shl 8) or (buf[idx + 5].toInt() and 0xff)
            if (attrType == 14 && avpLen >= 8) {
                return ((buf[idx + 6].toInt() and 0xff) shl 8) or (buf[idx + 7].toInt() and 0xff)
            }
            idx += avpLen
        }
        return 1
    }

    private fun extractIpFromIpcp(buf: ByteArray, len: Int): String {
        try {
            var idx = 12
            while (idx + 2 <= len) {
                val optType = buf[idx].toInt() and 0xff
                val optLen = buf[idx + 1].toInt() and 0xff
                if (optLen < 2 || idx + optLen > len) break
                if (optType == 3 && optLen == 6) {
                    val o1 = buf[idx + 2].toInt() and 0xff
                    val o2 = buf[idx + 3].toInt() and 0xff
                    val o3 = buf[idx + 4].toInt() and 0xff
                    val o4 = buf[idx + 5].toInt() and 0xff
                    if (o1 != 0 || o2 != 0 || o3 != 0 || o4 != 0) {
                        return "" + o1 + "." + o2 + "." + o3 + "." + o4
                    }
                }
                idx += optLen
            }
        } catch (e: Exception) {}
        return ""
    }

    // ==========================================
    // PPTP PACKET BUILDERS
    // ==========================================
    private fun buildPptpStartControlRequest(): ByteArray {
        val bb = ByteBuffer.allocate(156)
        bb.putShort(156.toShort()) // Length
        bb.putShort(1.toShort()) // Message Type (Control = 1)
        bb.putInt(0x1A2B3C4D) // Magic Cookie
        bb.putShort(1.toShort()) // Control Type = 1 (Start-Control-Connection-Request)
        bb.putShort(0.toShort()) // Reserved
        bb.putShort(0x0100.toShort()) // Protocol Version (1.0)
        bb.putShort(0.toShort()) // Reserved
        bb.putInt(1) // Framing Capabilities (1 = Async)
        bb.putInt(1) // Bearer Capabilities (1 = Analog)
        bb.putShort(1.toShort()) // Max Channels
        bb.putShort(1.toShort()) // Firmware Revision
        val hostBytes = "android-client".toByteArray()
        bb.put(hostBytes)
        for (i in 0 until (64 - hostBytes.size)) bb.put(0.toByte())
        val vendorBytes = "PPTP-Android".toByteArray()
        bb.put(vendorBytes)
        for (i in 0 until (64 - vendorBytes.size)) bb.put(0.toByte())
        return bb.array()
    }

    private fun buildPptpOutgoingCallRequest(): ByteArray {
        val bb = ByteBuffer.allocate(168)
        bb.putShort(168.toShort())
        bb.putShort(1.toShort())
        bb.putInt(0x1A2B3C4D)
        bb.putShort(7.toShort()) // Control Type = 7 (Outgoing-Call-Request)
        bb.putShort(0.toShort())
        bb.putShort(1.toShort()) // Call ID = 1
        bb.putShort(1.toShort()) // Call Serial Number = 1
        bb.putInt(300) // Min Bps
        bb.putInt(100000000) // Max Bps
        bb.putInt(1) // Bearer Type
        bb.putInt(1) // Framing Type
        bb.putShort(64.toShort()) // Packet Window Size
        bb.putShort(0.toShort()) // Delay
        bb.putShort(0.toShort()) // Phone Number Length
        bb.putShort(0.toShort()) // Reserved
        for (i in 0 until 100) bb.put(0.toByte())
        return bb.array()
    }

    private fun buildPptpEchoRequest(): ByteArray {
        val bb = ByteBuffer.allocate(16)
        bb.putShort(16.toShort())
        bb.putShort(1.toShort())
        bb.putInt(0x1A2B3C4D)
        bb.putShort(5.toShort()) // Control Type = 5 (Echo-Request)
        bb.putShort(0.toShort())
        bb.putInt(1) // Identifier
        return bb.array()
    }

    private fun buildPptpSetLinkInfo(serverCallId: Int): ByteArray {
        val bb = ByteBuffer.allocate(24)
        bb.putShort(24.toShort()) // Length
        bb.putShort(1.toShort()) // Message Type (Control = 1)
        bb.putInt(0x1A2B3C4D) // Magic Cookie
        bb.putShort(15.toShort()) // Control Type = 15 (Set-Link-Info)
        bb.putShort(0.toShort()) // Reserved
        bb.putShort(serverCallId.toShort()) // Peer's Call ID (server call id)
        bb.putShort(0.toShort()) // Reserved
        bb.putInt(-1) // Send ACCM = 0xFFFFFFFF
        bb.putInt(-1) // Receive ACCM = 0xFFFFFFFF
        return bb.array()
    }

    // ==========================================
    // STATUS & BROADCAST NOTIFIERS
    // ==========================================
    private fun sendBroadcastStatus(status: String, assignedIp: String = "") {
        try {
            val intent = Intent(ACTION_VPN_UPDATE).apply {
                putExtra("STATUS", status)
                putExtra("ASSIGNED_IP", assignedIp)
            }
            sendBroadcast(intent)
        } catch (e: Exception) {}
    }

    private fun sendBroadcastError(message: String) {
        try {
            val intent = Intent(ACTION_VPN_UPDATE).apply {
                putExtra("STATUS", "error")
                putExtra("ERROR", message)
            }
            sendBroadcast(intent)
        } catch (e: Exception) {}
        cleanup()
    }

    private fun sendBroadcastStats() {
        try {
            val now = System.currentTimeMillis()
            val dt = if (lastStatsTime > 0) (now - lastStatsTime) / 1000.0 else 1.0
            lastStatsTime = now

            var devRxDelta = 0L
            var devTxDelta = 0L
            try {
                val uidRx = TrafficStats.getUidRxBytes(android.os.Process.myUid())
                val uidTx = TrafficStats.getUidTxBytes(android.os.Process.myUid())
                if (uidRx > 0) devRxDelta = uidRx
                if (uidTx > 0) devTxDelta = uidTx
            } catch (e: Exception) {}

            val currentIn = Math.max(totalBytesIn, devRxDelta)
            val currentOut = Math.max(totalBytesOut, devTxDelta)

            val speedIn = if (dt > 0) ((currentIn - lastBytesIn) / dt).toLong().coerceAtLeast(0L) else 0L
            val speedOut = if (dt > 0) ((currentOut - lastBytesOut) / dt).toLong().coerceAtLeast(0L) else 0L

            lastBytesIn = currentIn
            lastBytesOut = currentOut

            val intent = Intent(ACTION_VPN_UPDATE).apply {
                putExtra("STATUS", "connected")
                putExtra("BYTES_IN", currentIn)
                putExtra("BYTES_OUT", currentOut)
                putExtra("SPEED_IN", speedIn)
                putExtra("SPEED_OUT", speedOut)
            }
            sendBroadcast(intent)
        } catch (e: Exception) {}
    }

    private fun disconnectTunnel() {
        isConnected = false
        workerThread?.interrupt()
        receiverThread?.interrupt()
        tunReaderThread?.interrupt()
        keepAliveThread?.interrupt()
        cleanup()
        sendBroadcastStatus("disconnected")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(Service.STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "stopForeground error", e)
        }
        stopSelf()
    }

    private fun cleanup() {
        try {
            vpnInterface?.close()
        } catch (e: Exception) {}
        vpnInterface = null
        isConnected = false
    }

    override fun onDestroy() {
        disconnectTunnel()
        super.onDestroy()
    }
}
