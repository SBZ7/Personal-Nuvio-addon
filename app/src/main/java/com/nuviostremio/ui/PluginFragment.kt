package com.nuviostremio.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nuviostremio.R
import com.nuviostremio.engine.PluginManager
import com.nuviostremio.model.NuvioPlugin
import com.nuviostremio.model.NuvioRepoPlugin
import kotlinx.coroutines.launch

class PluginFragment : Fragment() {

    private lateinit var pluginManager: PluginManager
    private lateinit var rvPlugins: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvHeader: TextView
    private lateinit var pluginAdapter: PluginAdapter

    private var filterRepoId: String? = null
    private var filterRepoUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        filterRepoId = arguments?.getString(ARG_REPO_ID)
        filterRepoUrl = arguments?.getString(ARG_REPO_URL)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_plugin, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        pluginManager = PluginManager(requireContext())
        rvPlugins = view.findViewById(R.id.rv_plugins)
        progressBar = view.findViewById(R.id.progress_plugin)
        tvHeader = view.findViewById(R.id.tv_plugin_header)

        if (filterRepoId != null) {
            tvHeader.text = "Plugins from Repo"
            loadRepoPlugins()
        } else {
            tvHeader.text = "Installed Plugins"
            loadInstalledPlugins()
        }
    }

    private fun loadInstalledPlugins() {
        val plugins = pluginManager.getInstalledPlugins()
        pluginAdapter = PluginAdapter(
            plugins.map { PluginItem.Installed(it) }.toMutableList(),
            onToggle = { plugin, enabled ->
                pluginManager.togglePlugin(plugin.id, enabled)
            },
            onInstall = { _, _ -> },
            onRemove = { plugin ->
                pluginManager.removePlugin(plugin.id)
                pluginAdapter.removePluginById(plugin.id)
                Toast.makeText(requireContext(), "${plugin.name} removed", Toast.LENGTH_SHORT).show()
            }
        )
        rvPlugins.layoutManager = LinearLayoutManager(requireContext())
        rvPlugins.adapter = pluginAdapter
    }

    private fun loadRepoPlugins() {
        val repoUrl = filterRepoUrl ?: return
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = pluginManager.fetchRepoIndex(repoUrl)
            progressBar.visibility = View.GONE
            result.onSuccess { index ->
                val items = index.plugins.map { rp ->
                    val installed = pluginManager.getInstalledPlugins()
                        .find { it.id == "${filterRepoId}_${rp.id}" }
                    if (installed != null) PluginItem.Installed(installed)
                    else PluginItem.Available(rp, filterRepoId!!, repoUrl)
                }.toMutableList()

                pluginAdapter = PluginAdapter(
                    items,
                    onToggle = { plugin, enabled ->
                        pluginManager.togglePlugin(plugin.id, enabled)
                    },
                    onInstall = { repoPlugin, repoId ->
                        installPlugin(repoPlugin, repoId, repoUrl)
                    },
                    onRemove = { plugin ->
                        pluginManager.removePlugin(plugin.id)
                        pluginAdapter.removePluginById(plugin.id)
                    }
                )
                rvPlugins.layoutManager = LinearLayoutManager(requireContext())
                rvPlugins.adapter = pluginAdapter
            }.onFailure { e ->
                Toast.makeText(requireContext(), "Failed to load plugins: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun installPlugin(repoPlugin: NuvioRepoPlugin, repoId: String, repoUrl: String) {
        val plugin = NuvioPlugin(
            id = "${repoId}_${repoPlugin.id}",
            name = repoPlugin.name,
            version = repoPlugin.version,
            description = repoPlugin.description,
            author = repoPlugin.author,
            repoId = repoId,
            scriptUrl = repoPlugin.script,
            iconUrl = repoPlugin.icon,
            types = repoPlugin.types
        )
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val baseUrl = pluginManager.getBaseUrl(repoUrl)
            val result = pluginManager.installPlugin(plugin, baseUrl)
            progressBar.visibility = View.GONE
            result.onSuccess { installed ->
                pluginAdapter.replaceAvailableWithInstalled(repoPlugin.id, installed)
                Toast.makeText(requireContext(), "${plugin.name} installed!", Toast.LENGTH_SHORT).show()
            }.onFailure { e ->
                Toast.makeText(requireContext(), "Install failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        private const val ARG_REPO_ID = "repo_id"
        private const val ARG_REPO_URL = "repo_url"

        fun newInstance(repoId: String, repoUrl: String) = PluginFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_REPO_ID, repoId)
                putString(ARG_REPO_URL, repoUrl)
            }
        }
    }
}

// ─── Plugin item sealed class ────────────────────────────────────────────────

sealed class PluginItem {
    data class Installed(val plugin: NuvioPlugin) : PluginItem()
    data class Available(
        val repoPlugin: NuvioRepoPlugin,
        val repoId: String,
        val repoUrl: String
    ) : PluginItem()
}

// ─── PluginAdapter ────────────────────────────────────────────────────────────

class PluginAdapter(
    private val items: MutableList<PluginItem>,
    private val onToggle: (NuvioPlugin, Boolean) -> Unit,
    private val onInstall: (NuvioRepoPlugin, String) -> Unit,
    private val onRemove: (NuvioPlugin) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_INSTALLED = 0
        private const val TYPE_AVAILABLE = 1
    }

    inner class InstalledVH(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tv_plugin_name)
        val tvDesc: TextView = view.findViewById(R.id.tv_plugin_desc)
        val swEnabled: Switch = view.findViewById(R.id.sw_plugin_enabled)
        val btnRemove: android.widget.Button = view.findViewById(R.id.btn_plugin_remove)
        val tvQuality: TextView = view.findViewById(R.id.tv_plugin_status)
    }

    inner class AvailableVH(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tv_plugin_name)
        val tvDesc: TextView = view.findViewById(R.id.tv_plugin_desc)
        val btnInstall: android.widget.Button = view.findViewById(R.id.btn_plugin_install)
        val tvStatus: TextView = view.findViewById(R.id.tv_plugin_status)
    }

    override fun getItemViewType(position: Int) =
        if (items[position] is PluginItem.Installed) TYPE_INSTALLED else TYPE_AVAILABLE

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_INSTALLED) {
            InstalledVH(inflater.inflate(R.layout.item_plugin_installed, parent, false))
        } else {
            AvailableVH(inflater.inflate(R.layout.item_plugin_available, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is PluginItem.Installed -> {
                val h = holder as InstalledVH
                val plugin = item.plugin
                h.tvName.text = plugin.name
                h.tvDesc.text = plugin.description.ifEmpty { plugin.author }
                h.tvQuality.text = "v${plugin.version} · ${plugin.types.joinToString(", ")}"
                h.swEnabled.isChecked = plugin.isEnabled
                h.swEnabled.setOnCheckedChangeListener { _, checked ->
                    onToggle(plugin, checked)
                }
                h.btnRemove.setOnClickListener { onRemove(plugin) }
            }
            is PluginItem.Available -> {
                val h = holder as AvailableVH
                val rp = item.repoPlugin
                h.tvName.text = rp.name
                h.tvDesc.text = rp.description.ifEmpty { rp.author }
                h.tvStatus.text = "v${rp.version} · Not installed"
                h.btnInstall.setOnClickListener { onInstall(rp, item.repoId) }
            }
        }
    }

    override fun getItemCount() = items.size

    fun removePluginById(id: String) {
        val idx = items.indexOfFirst { it is PluginItem.Installed && it.plugin.id == id }
        if (idx >= 0) { items.removeAt(idx); notifyItemRemoved(idx) }
    }

    fun replaceAvailableWithInstalled(repoPluginId: String, installed: NuvioPlugin) {
        val idx = items.indexOfFirst {
            it is PluginItem.Available && it.repoPlugin.id == repoPluginId
        }
        if (idx >= 0) {
            items[idx] = PluginItem.Installed(installed)
            notifyItemChanged(idx)
        } else {
            items.add(PluginItem.Installed(installed))
            notifyItemInserted(items.size - 1)
        }
    }
}
