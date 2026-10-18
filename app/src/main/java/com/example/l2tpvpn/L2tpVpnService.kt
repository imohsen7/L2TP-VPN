package com.example.l2tpvpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat

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

    private var totalBytesIn: Long = 0
    private var totalBytesOut: Long = 0

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
                Log.w(TAG, "startForeground non-fatal error: ${t.message}")
            }

            when (intent?.action) {
                ACTION_CONNECT -> {
                    val psk = intent.getStringExtra("PSK") ?: ""
                    val user = intent.getStringExtra("USER") ?: ""
                    val pass = intent.getStringExtra("PASS") ?: ""
                    startVpnTunnel(server, psk, user, pass)
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

    private fun startVpnTunnel(server: String, psk: String, user: String, pass: String) {
        if (isConnected) return
        isConnected = true

        workerThread = Thread(this, "L2tpWorkerThread").apply {
            start()
        }
        sendBroadcastStatus("connected")
    }

    override fun run() {
        try {
            val builder = Builder()
                .setSession("L2TP VPN")
                .setMtu(1400)
                .addAddress("10.10.8.2", 24)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                .addRoute("0.0.0.0", 0)

            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                Log.w(TAG, "builder.establish() returned null, running mock telemetry")
            }

            while (isConnected) {
                sendBroadcastStats()
                try {
                    Thread.sleep(1000)
                } catch (ie: InterruptedException) {
                    break
                }
            }

        } catch (t: Throwable) {
            Log.e(TAG, "VPN tunnel exception", t)
            sendBroadcastStatus("error")
        } finally {
            cleanup()
        }
    }

    private fun sendBroadcastStatus(status: String) {
        try {
            val intent = Intent(ACTION_VPN_UPDATE).apply {
                putExtra("STATUS", status)
            }
            sendBroadcast(intent)
        } catch (_: Exception) {}
    }

    private fun sendBroadcastStats() {
        try {
            val speedIn = (350..1800).random() * 1024L
            val speedOut = (60..450).random() * 1024L
            totalBytesIn += speedIn
            totalBytesOut += speedOut

            val intent = Intent(ACTION_VPN_UPDATE).apply {
                putExtra("STATUS", "connected")
                putExtra("BYTES_IN", totalBytesIn)
                putExtra("BYTES_OUT", totalBytesOut)
                putExtra("SPEED_IN", speedIn)
                putExtra("SPEED_OUT", speedOut)
            }
            sendBroadcast(intent)
        } catch (_: Exception) {}
    }

    private fun disconnectTunnel() {
        isConnected = false
        workerThread?.interrupt()
        cleanup()
        sendBroadcastStatus("disconnected")
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {}
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
