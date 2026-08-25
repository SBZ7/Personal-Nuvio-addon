package com.nuviostremio.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nuviostremio.R
import com.nuviostremio.engine.PluginManager
import com.nuviostremio.model.PluginRepo
import kotlinx.coroutines.launch
import java.util.UUID

class RepoFragment : Fragment() {

    private lateinit var pluginManager: PluginManager
    private lateinit var webView: WebView
    private lateinit var rvRepos: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var repoAdapter: RepoAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_repo, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        pluginManager = PluginManager(requireContext())

        webView = view.findViewById(R.id.webview_nuvio)
        rvRepos = view.findViewById(R.id.rv_repos)
        progressBar = view.findViewById(R.id.progress_repo)

        setupWebView()
        setupRepoList()

        view.findViewById<View>(R.id.btn_add_repo_manual).setOnClickListener {
            showAddRepoDialog()
        }
    }

    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
        }

        // Inject JS interface so webpage can post repo URLs back to the app
        webView.addJavascriptInterface(NuvioWebInterface(), "NuvioApp")

        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                // If it looks like a repo JSON link, offer to install it
                if (url.endsWith(".json") && !url.contains("nuvioplugins.com")) {
                    offerInstallRepo(url)
                    return true
                }
                return false
            }

            override fun onPageFinished(view: WebView, url: String) {
                // Inject JS to intercept clicks on repo links
                view.evaluateJavascript(
                    """
                    (function() {
                        document.querySelectorAll('a[href]').forEach(function(a) {
                            var href = a.href;
                            if (href && (href.endsWith('.json') || href.includes('repo') || href.includes('plugin'))) {
                                a.addEventListener('click', function(e) {
                                    e.preventDefault();
                                    NuvioApp.onRepoLinkClicked(href);
                                });
                            }
                        });
                    })();
                    """.trimIndent(), null
                )
                progressBar.visibility = View.GONE
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                progressBar.visibility = View.VISIBLE
            }
        }

        webView.loadUrl("https://nuvioplugins.com/index.html")
    }

    private fun setupRepoList() {
        repoAdapter = RepoAdapter(
            pluginManager.getInstalledRepos().toMutableList(),
            onRemove = { repo ->
                pluginManager.removeRepo(repo.id)
                repoAdapter.removeRepo(repo)
                Toast.makeText(requireContext(), "Repo removed", Toast.LENGTH_SHORT).show()
            },
            onBrowse = { repo ->
                showRepoPlugins(repo)
            }
        )
        rvRepos.layoutManager = LinearLayoutManager(requireContext())
        rvRepos.adapter = repoAdapter
    }

    private fun showAddRepoDialog() {
        val editText = EditText(requireContext()).apply {
            hint = "https://example.com/repo/index.json"
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Add Plugin Repo")
            .setMessage("Paste the repo index.json URL:")
            .setView(editText)
            .setPositiveButton("Add") { _, _ ->
                val url = editText.text.toString().trim()
                if (url.isNotEmpty()) offerInstallRepo(url)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun offerInstallRepo(url: String) {
        AlertDialog.Builder(requireContext())
            .setTitle("Install Repo?")
            .setMessage(url)
            .setPositiveButton("Install") { _, _ -> installRepo(url) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun installRepo(url: String) {
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = pluginManager.fetchRepoIndex(url)
            progressBar.visibility = View.GONE
            result.onSuccess { index ->
                val repo = PluginRepo(
                    id = UUID.randomUUID().toString(),
                    name = index.name.ifEmpty { url.substringAfterLast("/").substringBefore(".") },
                    url = url,
                    description = index.description,
                    isInstalled = true
                )
                pluginManager.addRepo(repo)
                repoAdapter.addRepo(repo)
                Toast.makeText(
                    requireContext(),
                    "Repo installed: ${repo.name} (${index.plugins.size} plugins)",
                    Toast.LENGTH_LONG
                ).show()
            }.onFailure { e ->
                Toast.makeText(requireContext(), "Failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showRepoPlugins(repo: PluginRepo) {
        // Navigate to plugin fragment filtered by this repo
        val frag = PluginFragment.newInstance(repo.id, repo.url)
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, frag)
            .addToBackStack(null)
            .commit()
        // Update bottom nav highlight manually
        activity?.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(
            R.id.bottom_nav
        )?.selectedItemId = R.id.nav_plugins
    }

    inner class NuvioWebInterface {
        @JavascriptInterface
        fun onRepoLinkClicked(url: String) {
            requireActivity().runOnUiThread {
                offerInstallRepo(url)
            }
        }
    }
}

// ─── RepoAdapter ─────────────────────────────────────────────────────────────

class RepoAdapter(
    private val repos: MutableList<PluginRepo>,
    private val onRemove: (PluginRepo) -> Unit,
    private val onBrowse: (PluginRepo) -> Unit
) : RecyclerView.Adapter<RepoAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: android.widget.TextView = view.findViewById(R.id.tv_repo_name)
        val tvUrl: android.widget.TextView = view.findViewById(R.id.tv_repo_url)
        val btnRemove: android.widget.Button = view.findViewById(R.id.btn_repo_remove)
        val btnBrowse: android.widget.Button = view.findViewById(R.id.btn_repo_browse)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_repo, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val repo = repos[position]
        holder.tvName.text = repo.name
        holder.tvUrl.text = repo.url
        holder.btnRemove.setOnClickListener { onRemove(repo) }
        holder.btnBrowse.setOnClickListener { onBrowse(repo) }
    }

    override fun getItemCount() = repos.size

    fun addRepo(repo: PluginRepo) {
        repos.add(repo)
        notifyItemInserted(repos.size - 1)
    }

    fun removeRepo(repo: PluginRepo) {
        val idx = repos.indexOfFirst { it.id == repo.id }
        if (idx >= 0) { repos.removeAt(idx); notifyItemRemoved(idx) }
    }
}
