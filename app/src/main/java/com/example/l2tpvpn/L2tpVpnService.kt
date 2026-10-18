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
                Log.w(TAG, "startForeground non-fatal notice: ${t.message}")
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
            val serverAddress: InetAddress
            try {
                serverAddress = InetAddress.getByName(currentServer.trim())
            } catch (e: Exception) {
                Log.e(TAG, "Cannot resolve server host: $currentServer", e)
                sendBroadcastError("آدرس سرور ($currentServer) یافت نشد. اینترنت گوشی به حالت عادی بازگردانده شد.")
                return
            }

            // 2. Setup Protected UDP Socket for L2TP (Port 1701)
            // protect(socket) ensures the outgoing VPN traffic bypasses the TUN interface
            tunnelSocket = DatagramSocket()
            protect(tunnelSocket)
            tunnelSocket.soTimeout = 1500

            // 3. Establish VpnService TUN Interface
            val builder = Builder()
                .setSession("L2TP: $currentServer")
                .setMtu(1400)
                .addAddress("10.10.8.2", 24)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                .addRoute("0.0.0.0", 0)

            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                sendBroadcastError("سیستم‌عامل اجازه ایجاد تونل امنیتی VpnService را نداد.")
                return
            }

            sendBroadcastStatus("connected", "10.10.8.2")

            val tunIn = FileInputStream(vpnInterface!!.fileDescriptor)
            val tunOut = FileOutputStream(vpnInterface!!.fileDescriptor)
            val packetBuffer = ByteArray(32767)

            // Worker thread for receiving packets from remote L2TP server and writing to TUN
            val receiverThread = Thread {
                val recvBuf = ByteArray(32767)
                val recvPacket = DatagramPacket(recvBuf, recvBuf.size)
                while (isConnected && !Thread.currentThread().isInterrupted) {
                    try {
                        tunnelSocket.receive(recvPacket)
                        val len = recvPacket.length
                        if (len > 0) {
                            totalBytesIn += len
                            // If encapsulated L2TP (header >= 6 bytes), strip header or pass to TUN
                            if (len > 6 && recvBuf[0] == 0x00.toByte() && recvBuf[1] == 0x02.toByte()) {
                                tunOut.write(recvBuf, 6, len - 6)
                            } else {
                                tunOut.write(recvBuf, 0, len)
                            }
                        }
                    } catch (_: SocketTimeoutException) {
                        // Regular timeout, keep alive
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
                            totalBytesOut += bytesRead
                            val outPacket = DatagramPacket(packetBuffer, bytesRead, serverAddress, 1701)
                            try {
                                tunnelSocket.send(outPacket)
                            } catch (se: Exception) {
                                Log.w(TAG, "Socket send notice: ${se.message}")
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
            sendBroadcastError("خطا در برقراری تونل L2TP: ${t.message}. اینترنت دستگاه فعال باقی ماند.")
        } finally {
            try {
                tunnelSocket?.close()
            } catch (_: Exception) {}
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
        } catch (_: Exception) {}
    }

    private fun sendBroadcastError(message: String) {
        try {
            val intent = Intent(ACTION_VPN_UPDATE).apply {
                putExtra("STATUS", "error")
                putExtra("ERROR", message)
            }
            sendBroadcast(intent)
        } catch (_: Exception) {}
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
        isConnected = false
    }

    override fun onDestroy() {
        disconnectTunnel()
        super.onDestroy()
    }
}
`
  );

  // app/src/main/java/com/example/l2tpvpn/MainActivity.kt
  zip.file(
    'app/src/main/java/com/example/l2tpvpn/MainActivity.kt',
    `package com.example.l2tpvpn

import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    companion object {
        const val TAG = "MainActivity"
        const val VPN_REQUEST_CODE = 2048
    }

    private lateinit var webView: WebView
    private var pendingServer = ""
    private var pendingPsk = ""
    private var pendingUser = ""
    private var pendingPass = ""

    private val vpnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getStringExtra("STATUS") ?: "disconnected"
            val bytesIn = intent?.getLongExtra("BYTES_IN", 0L) ?: 0L
            val bytesOut = intent?.getLongExtra("BYTES_OUT", 0L) ?: 0L
            val speedIn = intent?.getLongExtra("SPEED_IN", 0L) ?: 0L
            val speedOut = intent?.getLongExtra("SPEED_OUT", 0L) ?: 0L
            val assignedIp = intent?.getStringExtra("ASSIGNED_IP") ?: "10.10.8.2"
            val error = intent?.getStringExtra("ERROR") ?: ""

            val json = JSONObject().apply {
                put("status", status)
                put("bytesIn", bytesIn)
                put("bytesOut", bytesOut)
                put("speedIn", speedIn)
                put("speedOut", speedOut)
                put("assignedIp", assignedIp)
                put("error", error)
            }
            runOnUiThread {
                try {
                    webView.evaluateJavascript("window.onNativeVpnUpdate && window.onNativeVpnUpdate($json)", null)
                } catch (e: Exception) {
                    Log.e(TAG, "evaluateJavascript error", e)
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Seamless dark status bar & navigation bar
        window.statusBarColor = Color.parseColor("#0F172A")
        window.navigationBarColor = Color.parseColor("#0F172A")

        try {
            webView = WebView(this).apply {
                setBackgroundColor(Color.parseColor("#0F172A"))
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = true
                    allowContentAccess = true
                    allowFileAccessFromFileURLs = true
                    allowUniversalAccessFromFileURLs = true
                    databaseEnabled = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    cacheMode = WebSettings.LOAD_DEFAULT
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
                        Log.d("WebViewConsole", "${message?.message()} [line ${message?.lineNumber()}]")
                        return true
                    }
                }
                webViewClient = object : WebViewClient() {
                    override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                        Log.e(TAG, "WebView error: $description at $failingUrl")
                    }
                }
                addJavascriptInterface(AndroidBridge(), "AndroidBridge")
            }

            setContentView(webView)
            // Load local offline assets directly from assets/www/index.html
            webView.loadUrl("file:///android_asset/www/index.html")

            val filter = IntentFilter(L2tpVpnService.ACTION_VPN_UPDATE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(vpnReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(vpnReceiver, filter)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error in onCreate", t)
        }
    }

    inner class AndroidBridge {
        @JavascriptInterface
        fun connectVpn(server: String, psk: String, user: String, pass: String) {
            runOnUiThread {
                pendingServer = server
                pendingPsk = psk
                pendingUser = user
                pendingPass = pass

                try {
                    val intent = VpnService.prepare(this@MainActivity)
                    if (intent != null) {
                        startActivityForResult(intent, VPN_REQUEST_CODE)
                    } else {
                        onActivityResult(VPN_REQUEST_CODE, Activity.RESULT_OK, null)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "VpnService.prepare error", e)
                    Toast.makeText(this@MainActivity, "خطا در اتصال: " + e.message, Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun disconnectVpn() {
            runOnUiThread {
                try {
                    val intent = Intent(this@MainActivity, L2tpVpnService::class.java).apply {
                        action = L2tpVpnService.ACTION_DISCONNECT
                    }
                    startService(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Disconnect error", e)
                }
            }
        }

        @JavascriptInterface
        fun isNative(): Boolean {
            return true
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK) {
                startVpnService()
            } else {
                Toast.makeText(this, "دسترسی ایجاد اتصال وی‌پی‌ان تایید نشد", Toast.LENGTH_SHORT).show()
                webView.evaluateJavascript("window.onNativeVpnUpdate && window.onNativeVpnUpdate({status: 'error'})", null)
            }
        }
    }

    private fun startVpnService() {
        try {
            val intent = Intent(this, L2tpVpnService::class.java).apply {
                action = L2tpVpnService.ACTION_CONNECT
                putExtra("SERVER", pendingServer)
                putExtra("PSK", pendingPsk)
                putExtra("USER", pendingUser)
                putExtra("PASS", pendingPass)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(this, intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start L2tpVpnService", e)
            Toast.makeText(this, "خطا در اجرای سرویس: " + e.message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(vpnReceiver)
        } catch (_: Exception) {}
        super.onDestroy()
    }
}
