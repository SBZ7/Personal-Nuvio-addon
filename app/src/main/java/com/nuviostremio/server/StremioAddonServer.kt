package com.nuviostremio.server

import android.util.Log
import com.google.gson.Gson
import com.nuviostremio.engine.NuvioJsRunner
import com.nuviostremio.engine.PluginManager
import com.nuviostremio.model.BehaviorHints
import com.nuviostremio.model.QualityParser
import com.nuviostremio.model.StremioManifest
import com.nuviostremio.model.StremioStream
import com.nuviostremio.model.StremioStreamsResponse
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking

private const val TAG = "StremioAddonServer"
private const val PORT = 8585

class StremioAddonServer(
    private val pluginManager: PluginManager,
    private val jsRunner: NuvioJsRunner
) : NanoHTTPD(PORT) {

    private val gson = Gson()

    // Track server state for UI
    var onStateChanged: ((Boolean) -> Unit)? = null

    override fun start() {
        super.start(SOCKET_READ_TIMEOUT, false)
        Log.i(TAG, "Stremio addon server started on port $PORT")
        onStateChanged?.invoke(true)
    }

    override fun stop() {
        super.stop()
        Log.i(TAG, "Stremio addon server stopped")
        onStateChanged?.invoke(false)
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri.trimEnd('/')
        Log.d(TAG, "Request: ${session.method} $uri")

        // CORS headers for Stremio
        return when {
            uri == "" || uri == "/" -> handleRoot()
            uri == "/manifest.json" -> handleManifest()
            uri.startsWith("/stream/") -> handleStream(uri)
            uri == "/health" -> newFixedLengthResponse(
                Response.Status.OK, "text/plain", "OK"
            )
            else -> newFixedLengthResponse(
                Response.Status.NOT_FOUND, "application/json",
                """{"error":"Not found"}"""
            )
        }.also { addCorsHeaders(it) }
    }

    private fun addCorsHeaders(response: Response) {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Headers", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        response.addHeader("Cache-Control", "no-cache")
    }

    private fun handleRoot(): Response {
        val html = """
            <!DOCTYPE html>
            <html>
            <head><title>NuvioStremio Addon</title>
            <style>
                body { font-family: sans-serif; background: #1a1a2e; color: #eee; 
                       display: flex; flex-direction: column; align-items: center; 
                       padding: 40px; }
                h1 { color: #e94560; }
                .btn { background: #e94560; color: white; border: none; padding: 14px 28px;
                       border-radius: 8px; font-size: 16px; cursor: pointer; 
                       text-decoration: none; display: inline-block; margin: 8px; }
                .info { background: #16213e; padding: 20px; border-radius: 12px; 
                        margin: 20px 0; max-width: 500px; text-align: center; }
                code { background: #0f3460; padding: 4px 8px; border-radius: 4px; }
            </style>
            </head>
            <body>
            <h1>🎬 NuvioStremio</h1>
            <div class="info">
                <p>Your local Stremio addon is running!</p>
                <p>Manifest URL:</p>
                <code>http://127.0.0.1:8585/manifest.json</code>
            </div>
            <a class="btn" href="stremio://127.0.0.1:8585/manifest.json">
                Install in Stremio
            </a>
            <a class="btn" href="/manifest.json">View Manifest</a>
            </body></html>
        """.trimIndent()
        return newFixedLengthResponse(Response.Status.OK, "text/html", html)
    }

    private fun handleManifest(): Response {
        val enabledPlugins = pluginManager.getEnabledPlugins()
        val types = enabledPlugins.flatMap { it.types }.distinct().ifEmpty {
            listOf("movie", "series")
        }
        val manifest = StremioManifest(
            types = types,
            description = "Local Nuvio plugin bridge · ${enabledPlugins.size} plugin(s) active"
        )
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json",
            gson.toJson(manifest)
        )
    }

    private fun handleStream(uri: String): Response {
        // URI format: /stream/{type}/{id}.json
        // e.g. /stream/movie/tt1234567.json
        //      /stream/series/tt1234567:1:2.json
        val parts = uri.removePrefix("/stream/").removeSuffix(".json").split("/")
        if (parts.size < 2) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST, "application/json",
                """{"streams":[]}"""
            )
        }
        val mediaType = parts[0]
        val mediaId = parts.drop(1).joinToString("/")

        Log.i(TAG, "Stream request: type=$mediaType id=$mediaId")

        val enabledPlugins = pluginManager.getEnabledPlugins()
        if (enabledPlugins.isEmpty()) {
            return newFixedLengthResponse(
                Response.Status.OK, "application/json",
                gson.toJson(StremioStreamsResponse(streams = listOf(
                    StremioStream(
                        url = "",
                        title = "⚠️ No plugins enabled. Open NuvioStremio app to install plugins."
                    )
                )))
            )
        }

        // Run all enabled plugins and collect streams
        val allStreams = runBlocking {
            enabledPlugins.flatMap { plugin ->
                try {
                    jsRunner.fetchStreams(plugin, mediaId, mediaType)
                } catch (e: Exception) {
                    Log.e(TAG, "Plugin ${plugin.id} failed: ${e.message}")
                    emptyList()
                }
            }
        }

        // Sort by quality rank (ascending = best first)
        val sorted = allStreams
            .filter { it.url.isNotEmpty() }
            .sortedBy { it.qualityRank }
            .distinctBy { it.url }

        val stremioStreams = sorted.map { stream ->
            StremioStream(
                url = stream.url,
                title = buildTitle(stream),
                behaviorHints = mapOf(
                    "notWebReady" to false
                )
            )
        }

        Log.i(TAG, "Returning ${stremioStreams.size} streams for $mediaId")
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json",
            gson.toJson(StremioStreamsResponse(streams = stremioStreams))
        )
    }

    private fun buildTitle(stream: com.nuviostremio.model.StreamResult): String {
        val qualityLabel = if (stream.quality != "Unknown") "🎬 ${stream.quality}" else "🎬"
        return "$qualityLabel\n${stream.pluginName}\n${stream.title}"
    }

    companion object {
        fun isPortAvailable(): Boolean {
            return try {
                java.net.ServerSocket(PORT).use { true }
            } catch (e: Exception) {
                false
            }
        }
    }
}
