package com.nuviostremio.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.nuviostremio.R
import com.nuviostremio.engine.PluginManager

class HomeFragment : Fragment() {

    private lateinit var tvStatus: TextView
    private lateinit var tvUrl: TextView
    private lateinit var btnToggle: Button
    private lateinit var btnCopy: Button
    private lateinit var btnInstall: Button
    private lateinit var ivStatus: ImageView
    private lateinit var tvPluginCount: TextView

    private val mainActivity get() = activity as? MainActivity

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvStatus = view.findViewById(R.id.tv_status)
        tvUrl = view.findViewById(R.id.tv_url)
        btnToggle = view.findViewById(R.id.btn_toggle)
        btnCopy = view.findViewById(R.id.btn_copy)
        btnInstall = view.findViewById(R.id.btn_install_stremio)
        ivStatus = view.findViewById(R.id.iv_status_dot)
        tvPluginCount = view.findViewById(R.id.tv_plugin_count)

        val pluginManager = PluginManager(requireContext())
        val count = pluginManager.getEnabledPlugins().size
        tvPluginCount.text = "$count plugin(s) active"

        val ip = mainActivity?.getLocalIp() ?: "127.0.0.1"
        val addonUrl = "http://$ip:8585/manifest.json"
        tvUrl.text = addonUrl

        updateUi(mainActivity?.isServerRunning() ?: false)

        btnToggle.setOnClickListener {
            if (mainActivity?.isServerRunning() == true) {
                mainActivity?.stopServer()
            } else {
                mainActivity?.startServer()
            }
        }

        btnCopy.setOnClickListener {
            mainActivity?.copyToClipboard(addonUrl)
        }

        btnInstall.setOnClickListener {
            val intent = android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("stremio://$ip:8585/manifest.json")
            )
            try {
                startActivity(intent)
            } catch (e: Exception) {
                mainActivity?.copyToClipboard(addonUrl)
                android.widget.Toast.makeText(
                    requireContext(),
                    "Stremio not found — URL copied instead",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    fun onServerStateChanged(running: Boolean) {
        updateUi(running)
        val pluginManager = PluginManager(requireContext())
        val count = pluginManager.getEnabledPlugins().size
        tvPluginCount.text = "$count plugin(s) active"
    }

    private fun updateUi(running: Boolean) {
        if (running) {
            tvStatus.text = "Server Running"
            tvStatus.setTextColor(resources.getColor(R.color.green, null))
            btnToggle.text = "Stop Server"
            btnToggle.setBackgroundColor(resources.getColor(R.color.red, null))
            ivStatus.setColorFilter(resources.getColor(R.color.green, null))
            btnCopy.isEnabled = true
            btnInstall.isEnabled = true
        } else {
            tvStatus.text = "Server Stopped"
            tvStatus.setTextColor(resources.getColor(R.color.grey, null))
            btnToggle.text = "Start Server"
            btnToggle.setBackgroundColor(resources.getColor(R.color.accent, null))
            ivStatus.setColorFilter(resources.getColor(R.color.grey, null))
            btnCopy.isEnabled = false
            btnInstall.isEnabled = false
        }
    }
}
