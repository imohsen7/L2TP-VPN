package com.example.l2tpvpn

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private val VPN_REQUEST_CODE = 2048

    private lateinit var etServer: EditText
    private lateinit var etPsk: EditText
    private lateinit var etUsername: EditText
    private lateinit var etPassword: EditText
    private lateinit var btnToggle: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvSpeed: TextView

    private var isConnected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etServer = findViewById(R.id.etServer)
        etPsk = findViewById(R.id.etPsk)
        etUsername = findViewById(R.id.etUsername)
        etPassword = findViewById(R.id.etPassword)
        btnToggle = findViewById(R.id.btnToggle)
        tvStatus = findViewById(R.id.tvStatus)
        tvSpeed = findViewById(R.id.tvSpeed)

        // Preload settings
        etServer.setText("de-fra1.l2tp-vpn.net")
        etPsk.setText("vpnsecret2026")
        etUsername.setText("vpnuser_de")
        etPassword.setText("password123")

        btnToggle.setOnClickListener {
            if (isConnected) {
                disconnectVpn()
            } else {
                prepareAndConnectVpn()
            }
        }
    }

    private fun prepareAndConnectVpn() {
        val server = etServer.text.toString().trim()
        val psk = etPsk.text.toString().trim()

        if (server.isEmpty() || psk.isEmpty()) {
            Toast.makeText(this, "لطفاً آدرس سرور و کلید PSK را وارد کنید", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = VpnService.prepare(this)
        if (intent != null) {
            startActivityForResult(intent, VPN_REQUEST_CODE)
        } else {
            onActivityResult(VPN_REQUEST_CODE, Activity.RESULT_OK, null)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            startVpnService()
        }
    }

    private fun startVpnService() {
        val intent = Intent(this, L2tpVpnService::class.java).apply {
            action = L2tpVpnService.ACTION_CONNECT
            putExtra("SERVER", etServer.text.toString().trim())
            putExtra("PSK", etPsk.text.toString().trim())
            putExtra("USER", etUsername.text.toString().trim())
            putExtra("PASS", etPassword.text.toString().trim())
        }
        startService(intent)

        isConnected = true
        btnToggle.text = "قطع اتصال"
        tvStatus.text = "وضعیت: متصل شد"
        tvStatus.setTextColor(getColor(android.R.color.holo_green_dark))
    }

    private fun disconnectVpn() {
        val intent = Intent(this, L2tpVpnService::class.java).apply {
            action = L2tpVpnService.ACTION_DISCONNECT
        }
        startService(intent)

        isConnected = false
        btnToggle.text = "اتصال به وی‌پی‌ان"
        tvStatus.text = "وضعیت: قطع شده"
        tvStatus.setTextColor(getColor(android.R.color.darker_gray))
    }
}
