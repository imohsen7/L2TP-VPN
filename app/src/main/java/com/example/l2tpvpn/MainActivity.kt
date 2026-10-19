package com.example.l2tpvpn

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
    private var pendingProtocol = "l2tp"
    private var pendingDns1 = "1.1.1.1"
    private var pendingDns2 = "8.8.8.8"
    private var pendingMtu = 1400

    private val vpnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getStringExtra("STATUS")
            val message = intent?.getStringExtra("MESSAGE") ?: ""
            val error = intent?.getStringExtra("ERROR") ?: ""
            val json = JSONObject().apply {
                if (status != null) put("status", status)
                if (message.isNotEmpty()) put("message", message)
                if (error.isNotEmpty()) put("error", error)
                if (intent?.hasExtra("BYTES_IN") == true) put("bytesIn", intent.getLongExtra("BYTES_IN", 0L))
                if (intent?.hasExtra("BYTES_OUT") == true) put("bytesOut", intent.getLongExtra("BYTES_OUT", 0L))
                if (intent?.hasExtra("SPEED_IN") == true) put("speedIn", intent.getLongExtra("SPEED_IN", 0L))
                if (intent?.hasExtra("SPEED_OUT") == true) put("speedOut", intent.getLongExtra("SPEED_OUT", 0L))
                if (intent?.hasExtra("ASSIGNED_IP") == true) put("assignedIp", intent.getStringExtra("ASSIGNED_IP"))
            }
            runOnUiThread {
                try {
                    val script = "if (window.onNativeVpnUpdate) { window.onNativeVpnUpdate(" + json.toString() + "); }"
                    webView.evaluateJavascript(script, null)
                } catch (e: Exception) {
                    Log.e(TAG, "evaluateJavascript error", e)
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
                        Log.d("WebViewConsole", (message?.message() ?: "") + " [line " + (message?.lineNumber() ?: 0) + "]")
                        return true
                    }
                }
                webViewClient = object : WebViewClient() {
                    override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                        Log.e(TAG, "WebView error: " + description + " at " + failingUrl)
                    }
                }
                addJavascriptInterface(AndroidBridge(), "AndroidBridge")
            }

            setContentView(webView)
            webView.loadUrl("file:///android_asset/www/index.html")

            val filter = IntentFilter(L2tpVpnService.ACTION_VPN_UPDATE)
            ContextCompat.registerReceiver(this, vpnReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        } catch (t: Throwable) {
            Log.e(TAG, "Error in onCreate", t)
        }
    }

    inner class AndroidBridge {
        @JavascriptInterface
        fun connectVpn(server: String, psk: String, user: String, pass: String) {
            connectVpn(server, psk, user, pass, "l2tp", "1.1.1.1", "8.8.8.8", 1400)
        }

        @JavascriptInterface
        fun connectVpn(server: String, psk: String, user: String, pass: String, protocol: String) {
            connectVpn(server, psk, user, pass, protocol, "1.1.1.1", "8.8.8.8", 1400)
        }

        @JavascriptInterface
        fun connectVpn(
            server: String, 
            psk: String, 
            user: String, 
            pass: String, 
            protocol: String, 
            dns1: String, 
            dns2: String, 
            mtu: Int
        ) {
            runOnUiThread {
                pendingServer = server
                pendingPsk = psk
                pendingUser = user
                pendingPass = pass
                pendingProtocol = if (protocol.equals("pptp", ignoreCase = true)) "pptp" else "l2tp"
                pendingDns1 = if (dns1.isNotBlank()) dns1 else "1.1.1.1"
                pendingDns2 = if (dns2.isNotBlank()) dns2 else "8.8.8.8"
                pendingMtu = if (mtu in 1280..1500) mtu else 1400

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
                webView.evaluateJavascript("if (window.onNativeVpnUpdate) { window.onNativeVpnUpdate({status: 'error', error: 'اجازه دسترسی توسط کاربر لغو شد'}); }", null)
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
                putExtra("PROTOCOL", pendingProtocol)
                putExtra("DNS1", pendingDns1)
                putExtra("DNS2", pendingDns2)
                putExtra("MTU", pendingMtu)
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
        } catch (e: Exception) {
            Log.e(TAG, "unregisterReceiver error", e)
        }
        super.onDestroy()
    }
}
