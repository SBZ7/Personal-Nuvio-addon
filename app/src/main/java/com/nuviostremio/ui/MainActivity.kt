package com.nuviostremio.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.nuviostremio.R
import com.nuviostremio.server.StremioForegroundService

class MainActivity : AppCompatActivity() {

    private var serverRunning = false

    private val serverStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            serverRunning = intent.getBooleanExtra("running", false)
            // Notify current fragment
            val frag = supportFragmentManager.findFragmentById(R.id.fragment_container)
            if (frag is HomeFragment) frag.onServerStateChanged(serverRunning)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val nav = findViewById<BottomNavigationView>(R.id.bottom_nav)
        nav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> showFragment(HomeFragment())
                R.id.nav_repos -> showFragment(RepoFragment())
                R.id.nav_plugins -> showFragment(PluginFragment())
            }
            true
        }

        if (savedInstanceState == null) {
            showFragment(HomeFragment())
        }
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(
            serverStateReceiver,
            IntentFilter(StremioForegroundService.ACTION_SERVER_STATE),
            RECEIVER_NOT_EXPORTED
        )
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(serverStateReceiver)
    }

    private fun showFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
    }

    fun getLocalIp(): String {
        return try {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            val ip = wm.connectionInfo.ipAddress
            if (ip == 0) "127.0.0.1"
            else String.format(
                "%d.%d.%d.%d",
                ip and 0xff, ip shr 8 and 0xff,
                ip shr 16 and 0xff, ip shr 24 and 0xff
            )
        } catch (e: Exception) {
            "127.0.0.1"
        }
    }

    fun startServer() {
        StremioForegroundService.start(this)
    }

    fun stopServer() {
        StremioForegroundService.stop(this)
    }

    fun isServerRunning() = serverRunning

    fun copyToClipboard(text: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("url", text))
        Toast.makeText(this, "Copied!", Toast.LENGTH_SHORT).show()
    }
}
