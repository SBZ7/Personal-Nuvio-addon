package com.nuviostremio.engine

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nuviostremio.model.NuvioPlugin
import com.nuviostremio.model.NuvioRepoIndex
import com.nuviostremio.model.NuvioRepoPlugin
import com.nuviostremio.model.PluginRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

class PluginManager(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("nuvio_plugins", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val pluginsDir: File
        get() = File(context.filesDir, "plugins").also { it.mkdirs() }

    // ─── Repos ────────────────────────────────────────────────────────────

    fun getInstalledRepos(): List<PluginRepo> {
        val json = prefs.getString("repos", "[]") ?: "[]"
        val type = object : TypeToken<List<PluginRepo>>() {}.type
        return gson.fromJson(json, type) ?: emptyList()
    }

    private fun saveRepos(repos: List<PluginRepo>) {
        prefs.edit().putString("repos", gson.toJson(repos)).apply()
    }

    fun addRepo(repo: PluginRepo) {
        val repos = getInstalledRepos().toMutableList()
        if (repos.none { it.id == repo.id }) {
            repos.add(repo.copy(isInstalled = true))
            saveRepos(repos)
        }
    }

    fun removeRepo(repoId: String) {
        val repos = getInstalledRepos().filter { it.id != repoId }
        saveRepos(repos)
        // Also remove all plugins from this repo
        val plugins = getInstalledPlugins().filter { it.repoId != repoId }
        savePlugins(plugins)
    }

    // ─── Plugins ──────────────────────────────────────────────────────────

    fun getInstalledPlugins(): List<NuvioPlugin> {
        val json = prefs.getString("plugins", "[]") ?: "[]"
        val type = object : TypeToken<List<NuvioPlugin>>() {}.type
        return gson.fromJson(json, type) ?: emptyList()
    }

    fun getEnabledPlugins(): List<NuvioPlugin> =
        getInstalledPlugins().filter { it.isEnabled && it.localScriptPath.isNotEmpty() }

    private fun savePlugins(plugins: List<NuvioPlugin>) {
        prefs.edit().putString("plugins", gson.toJson(plugins)).apply()
    }

    fun togglePlugin(pluginId: String, enabled: Boolean) {
        val plugins = getInstalledPlugins().map {
            if (it.id == pluginId) it.copy(isEnabled = enabled) else it
        }
        savePlugins(plugins)
    }

    fun removePlugin(pluginId: String) {
        val plugin = getInstalledPlugins().find { it.id == pluginId }
        plugin?.localScriptPath?.let { File(it).delete() }
        savePlugins(getInstalledPlugins().filter { it.id != pluginId })
    }

    // ─── Network: Fetch repo index ─────────────────────────────────────────

    suspend fun fetchRepoIndex(repoUrl: String): Result<NuvioRepoIndex> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(repoUrl).build()
                val response = http.newCall(request).execute()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP ${response.code}"))
                }
                val body = response.body?.string() ?: return@withContext Result.failure(
                    Exception("Empty response")
                )
                // Try to parse as NuvioRepoIndex
                val index = try {
                    gson.fromJson(body, NuvioRepoIndex::class.java)
                } catch (e: Exception) {
                    // Maybe it's a flat list of plugins
                    val listType = object : TypeToken<List<NuvioRepoPlugin>>() {}.type
                    val plugins: List<NuvioRepoPlugin> = gson.fromJson(body, listType)
                    NuvioRepoIndex(plugins = plugins)
                }
                Result.success(index)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    // ─── Network: Install plugin (download JS) ─────────────────────────────

    suspend fun installPlugin(plugin: NuvioPlugin, repoBaseUrl: String): Result<NuvioPlugin> =
        withContext(Dispatchers.IO) {
            try {
                val scriptUrl = if (plugin.scriptUrl.startsWith("http")) {
                    plugin.scriptUrl
                } else {
                    val base = repoBaseUrl.trimEnd('/')
                    "$base/${plugin.scriptUrl.trimStart('/')}"
                }

                val request = Request.Builder().url(scriptUrl).build()
                val response = http.newCall(request).execute()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP ${response.code}"))
                }
                val scriptContent = response.body?.string()
                    ?: return@withContext Result.failure(Exception("Empty script"))

                // Save the JS file locally
                val scriptFile = File(pluginsDir, "${plugin.id}.js")
                scriptFile.writeText(scriptContent)

                val installedPlugin = plugin.copy(
                    localScriptPath = scriptFile.absolutePath,
                    isEnabled = true
                )

                // Add to installed list
                val plugins = getInstalledPlugins().toMutableList()
                plugins.removeIf { it.id == plugin.id }
                plugins.add(installedPlugin)
                savePlugins(plugins)

                Result.success(installedPlugin)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    // ─── Load JS script content ────────────────────────────────────────────

    fun readPluginScript(plugin: NuvioPlugin): String? {
        val file = File(plugin.localScriptPath)
        return if (file.exists()) file.readText() else null
    }

    // ─── Repo URL → base URL helper ───────────────────────────────────────

    fun getBaseUrl(repoUrl: String): String {
        val uri = java.net.URI(repoUrl)
        val path = uri.path.substringBeforeLast("/")
        return "${uri.scheme}://${uri.host}${if (uri.port != -1) ":${uri.port}" else ""}$path"
    }
}
