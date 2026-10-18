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

    private val vpnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getStringExtra("STATUS") ?: "disconnected"
            val bytesIn = intent?.getLongExtra("BYTES_IN", 0L) ?: 0L
            val bytesOut = intent?.getLongExtra("BYTES_OUT", 0L) ?: 0L
            val speedIn = intent?.getLongExtra("SPEED_IN", 0L) ?: 0L
            val speedOut = intent?.getLongExtra("SPEED_OUT", 0L) ?: 0L

            val json = JSONObject().apply {
                put("status", status)
                put("bytesIn", bytesIn)
                put("bytesOut", bytesOut)
                put("speedIn", speedIn)
                put("speedOut", speedOut)
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
