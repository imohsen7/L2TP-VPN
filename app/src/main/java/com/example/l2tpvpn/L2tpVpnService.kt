package com.example.l2tpvpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.nio.channels.DatagramChannel

class L2tpVpnService : VpnService(), Runnable {

    companion object {
        const val TAG = "L2tpVpnService"
        const val CHANNEL_ID = "vpn_channel_id"
        const val NOTIFICATION_ID = 1001
        const val ACTION_CONNECT = "com.example.l2tpvpn.CONNECT"
        const val ACTION_DISCONNECT = "com.example.l2tpvpn.DISCONNECT"
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var workerThread: Thread? = null
    private var isConnected = false

    private var totalBytesIn: Long = 0
    private var totalBytesOut: Long = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val server = intent.getStringExtra("SERVER") ?: "de-fra1.l2tp-vpn.net"
                val psk = intent.getStringExtra("PSK") ?: "vpnsecret2026"
                val user = intent.getStringExtra("USER") ?: "vpnuser_de"
                val pass = intent.getStringExtra("PASS") ?: "password123"
                startForegroundNotification(server)
                startVpnTunnel(server, psk, user, pass)
            }
            ACTION_DISCONNECT -> {
                disconnectTunnel()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundNotification(server: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VPN Status",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("L2TP VPN متصل است")
            .setContentText("سرور فعال: $server")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun startVpnTunnel(server: String, psk: String, user: String, pass: String) {
        if (isConnected) return
        isConnected = true

        workerThread = Thread(this, "L2tpWorkerThread").apply {
            start()
        }
    }

    override fun run() {
        try {
            // Android 7.0+ (API 24+) Tun interface builder
            val builder = Builder()
                .setSession("آلمان - فرانکفورت (L2TP/IPSec)")
                .setMtu(1400)
                .addAddress("10.10.8.2", 24)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                .addRoute("0.0.0.0", 0)

            val tunnel = DatagramChannel.open()
            protect(tunnel.socket()) // Prevent routing loop

            vpnInterface = builder.establish()
            Log.i(TAG, "Virtual TUN interface established for L2TP VPN.")

            val input = FileInputStream(vpnInterface?.fileDescriptor)
            val output = FileOutputStream(vpnInterface?.fileDescriptor)
            val buffer = ByteArray(32767)

            while (isConnected) {
                val readBytes = input.read(buffer)
                if (readBytes > 0) {
                    totalBytesOut += readBytes
                }
                Thread.sleep(10)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error in VPN service", e)
        } finally {
            cleanup()
        }
    }

    private fun disconnectTunnel() {
        isConnected = false
        workerThread?.interrupt()
        cleanup()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanup() {
        try {
            vpnInterface?.close()
        } catch (_: Exception) {}
        vpnInterface = null
    }

    override fun onDestroy() {
        disconnectTunnel()
        super.onDestroy()
    }
}
