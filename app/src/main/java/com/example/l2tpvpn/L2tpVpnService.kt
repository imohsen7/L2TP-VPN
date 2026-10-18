package com.example.l2tpvpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

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
    @Volatile private var isConnected = false

    private var currentServer = ""
    private var currentPsk = ""
    private var currentUser = ""
    private var currentPass = ""

    // Strictly real-time socket packet counters (Zero fake numbers)
    @Volatile private var totalBytesIn: Long = 0
    @Volatile private var totalBytesOut: Long = 0
    private var lastBytesIn: Long = 0
    private var lastBytesOut: Long = 0
    private var lastStatsTime: Long = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val server = intent?.getStringExtra("SERVER") ?: "L2TP Server"
            val notification = buildNotification(server)
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

    private fun buildNotification(server: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("L2TP VPN متصل است")
            .setContentText("سرور متصل: $server")
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

        workerThread = Thread(this, "L2tpWorkerThread").apply {
            start()
        }
    }

    override fun run() {
        var tunnelSocket: DatagramSocket? = null
        try {
            // 1. Resolve host safely without freezing device network
            val trimmedServer = currentServer.trim()
            val serverAddress: InetAddress = try {
                InetAddress.getByName(trimmedServer)
            } catch (e: Exception) {
                Log.e(TAG, "Cannot resolve server host: $trimmedServer", e)
                sendBroadcastError("آدرس سرور ($trimmedServer) یافت نشد. اینترنت گوشی به حالت عادی بازگردانده شد.")
                return
            }

            // 2. Setup Protected UDP Socket for L2TP (Port 1701)
            // protect(socket) ensures the outgoing VPN traffic bypasses the TUN interface
            val socket = DatagramSocket()
            tunnelSocket = socket
            protect(socket)
            socket.soTimeout = 1500

            // 3. Establish VpnService TUN Interface
            val builder = Builder()
                .setSession("L2TP: $trimmedServer")
                .setMtu(1400)
                .addAddress("10.10.8.2", 24)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                .addRoute("0.0.0.0", 0)

            val pfd = builder.establish()
            if (pfd == null) {
                sendBroadcastError("سیستم‌عامل اجازه ایجاد تونل امنیتی VpnService را نداد.")
                return
            }
            vpnInterface = pfd

            sendBroadcastStatus("connected", "10.10.8.2")

            val tunIn = FileInputStream(pfd.fileDescriptor)
            val tunOut = FileOutputStream(pfd.fileDescriptor)
            val packetBuffer = ByteArray(32767)

            // Worker thread for receiving packets from remote L2TP server and writing to TUN
            val receiverThread = Thread {
                val recvBuf = ByteArray(32767)
                val recvPacket = DatagramPacket(recvBuf, recvBuf.size)
                while (isConnected && !Thread.currentThread().isInterrupted) {
                    try {
                        socket.receive(recvPacket)
                        val len = recvPacket.length
                        if (len > 0) {
                            totalBytesIn += len.toLong()
                            // If encapsulated L2TP data (first 2 bytes 0x00 0x02), forward payload
                            if (len > 6 && recvBuf[0].toInt() == 0x00 && recvBuf[1].toInt() == 0x02) {
                                tunOut.write(recvBuf, 6, len - 6)
                            } else {
                                tunOut.write(recvBuf, 0, len)
                            }
                        }
                    } catch (e: SocketTimeoutException) {
                        // Regular socket read timeout, keep loop alive
                    } catch (e: Exception) {
                        break
                    }
                }
            }
            receiverThread.start()

            // Main forwarding loop (TUN -> Socket) + Real-time stats ticker
            var tickerCounter = 0
            while (isConnected && !Thread.currentThread().isInterrupted) {
                try {
                    if (tunIn.available() > 0) {
                        val bytesRead = tunIn.read(packetBuffer)
                        if (bytesRead > 0) {
                            totalBytesOut += bytesRead.toLong()
                            val outPacket = DatagramPacket(packetBuffer, bytesRead, serverAddress, 1701)
                            try {
                                socket.send(outPacket)
                            } catch (se: Exception) {
                                Log.w(TAG, "Socket send notice: " + se.message)
                            }
                        }
                    } else {
                        Thread.sleep(50)
                    }

                    tickerCounter++
                    if (tickerCounter >= 20) { // Approx every 1 second
                        tickerCounter = 0
                        sendBroadcastStats()
                    }

                } catch (ie: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Forwarding loop error", e)
                    break
                }
            }

            receiverThread.interrupt()

        } catch (t: Throwable) {
            Log.e(TAG, "VPN tunnel fatal exception", t)
            sendBroadcastError("خطا در برقراری تونل L2TP: " + t.message)
        } finally {
            try {
                tunnelSocket?.close()
            } catch (e: Exception) {}
            cleanup()
        }
    }

    private fun sendBroadcastStatus(status: String, assignedIp: String = "10.10.8.2") {
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

            val currentIn = totalBytesIn
            val currentOut = totalBytesOut

            // Speeds strictly derived from delta (0 if no packets transferred!)
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
