package com.nuviostremio.engine

import android.util.Log
import com.google.gson.Gson
import com.nuviostremio.model.NuvioPlugin
import com.nuviostremio.model.QualityParser
import com.nuviostremio.model.StreamResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mozilla.javascript.Context
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject

private const val TAG = "NuvioJsRunner"

/**
 * Executes a Nuvio JS provider file inside Mozilla Rhino and collects streams.
 *
 * Nuvio providers typically expose one of:
 *   - async function getStreams(id, type) -> Array<{url, title, ...}>
 *   - async function scrape(id)           -> same
 *   - module.exports = { getStreams }
 *   - exports.default = { ... }
 *
 * We wrap the call in a Promise-like synchronous bridge since Rhino doesn't
 * support native async/await. We convert async functions to sync by injecting
 * a minimal fetch polyfill backed by OkHttp.
 */
class NuvioJsRunner(private val pluginManager: PluginManager) {

    private val gson = Gson()

    // HTTP client shared with fetch polyfill
    private val okHttp = okhttp3.OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    suspend fun fetchStreams(
        plugin: NuvioPlugin,
        mediaId: String,   // e.g. "tt1234567" or "kitsu:12345"
        mediaType: String  // "movie" or "series"
    ): List<StreamResult> = withContext(Dispatchers.IO) {
        val script = pluginManager.readPluginScript(plugin)
        if (script == null) {
            Log.w(TAG, "No script for plugin ${plugin.id}")
            return@withContext emptyList()
        }

        try {
            runJsPlugin(script, plugin, mediaId, mediaType)
        } catch (e: Exception) {
            Log.e(TAG, "JS execution error for ${plugin.id}: ${e.message}")
            emptyList()
        }
    }

    private fun runJsPlugin(
        script: String,
        plugin: NuvioPlugin,
        mediaId: String,
        mediaType: String
    ): List<StreamResult> {
        val cx = Context.enter()
        cx.optimizationLevel = -1  // interpreted mode (required for Android)
        cx.languageVersion = Context.VERSION_ES6

        try {
            val scope = cx.initSafeStandardObjects()

            // ── Polyfills ──────────────────────────────────────────────────

            injectConsole(cx, scope)
            injectFetch(cx, scope)
            injectModuleExports(cx, scope)

            // ── Run the provider script ────────────────────────────────────

            cx.evaluateString(scope, script, plugin.id, 1, null)

            // ── Detect and call the export function ───────────────────────

            val rawStreams = tryCallExportedFunction(cx, scope, mediaId, mediaType)

            // ── Convert to StreamResult ────────────────────────────────────

            return parseRawStreams(rawStreams, plugin)
        } finally {
            Context.exit()
        }
    }

    private fun injectConsole(cx: Context, scope: Scriptable) {
        val console = cx.newObject(scope)
        ScriptableObject.putProperty(scope, "console", console)
        val logFn = org.mozilla.javascript.BaseFunction()
        // Just drop console.log calls — or log to Android Log
        val consoleJs = """
            var console = {
                log: function() {},
                warn: function() {},
                error: function() {},
                info: function() {}
            };
        """.trimIndent()
        cx.evaluateString(scope, consoleJs, "console", 1, null)
    }

    private fun injectFetch(cx: Context, scope: Scriptable) {
        // Synchronous fetch polyfill using OkHttp (Rhino has no event loop)
        val fetchWrapper = object : org.mozilla.javascript.BaseFunction() {
            override fun call(
                ctx: Context, s: Scriptable, thisObj: Scriptable, args: Array<Any>
            ): Any {
                val url = args.getOrNull(0)?.toString() ?: return ctx.newObject(s)
                val optionsObj = args.getOrNull(1) as? NativeObject

                val method = (optionsObj?.get("method", optionsObj) as? String)?.uppercase() ?: "GET"
                val body = optionsObj?.get("body", optionsObj)?.toString()
                val headersObj = optionsObj?.get("headers", optionsObj) as? NativeObject

                return try {
                    val reqBuilder = okhttp3.Request.Builder().url(url)
                    headersObj?.let { h ->
                        h.ids.forEach { key ->
                            reqBuilder.addHeader(key.toString(), h.get(key, h).toString())
                        }
                    }
                    if (method == "POST" && body != null) {
                        reqBuilder.post(
                            okhttp3.RequestBody.create(
                                okhttp3.MediaType.parse("application/json"),
                                body
                            )
                        )
                    }
                    val response = okHttp.newCall(reqBuilder.build()).execute()
                    val text = response.body()?.string() ?: ""
                    val statusCode = response.code()

                    // Return a Response-like object with .text() and .json() methods
                    val responseJs = ctx.newObject(s)
                    ScriptableObject.putProperty(responseJs, "ok", statusCode in 200..299)
                    ScriptableObject.putProperty(responseJs, "status", statusCode)
                    ScriptableObject.putProperty(responseJs, "_text", text)

                    val textFn = object : org.mozilla.javascript.BaseFunction() {
                        override fun call(c: Context, sc: Scriptable, t: Scriptable, a: Array<Any>): Any {
                            return text
                        }
                    }
                    val jsonFn = object : org.mozilla.javascript.BaseFunction() {
                        override fun call(c: Context, sc: Scriptable, t: Scriptable, a: Array<Any>): Any {
                            return try {
                                c.evaluateString(sc, "($text)", "json", 1, null)
                            } catch (e: Exception) { c.newObject(sc) }
                        }
                    }

                    ScriptableObject.putProperty(responseJs, "text", textFn)
                    ScriptableObject.putProperty(responseJs, "json", jsonFn)
                    responseJs
                } catch (e: Exception) {
                    Log.w(TAG, "fetch error: ${e.message}")
                    val errObj = ctx.newObject(s)
                    ScriptableObject.putProperty(errObj, "ok", false)
                    ScriptableObject.putProperty(errObj, "status", 0)
                    errObj
                }
            }
        }
        ScriptableObject.putProperty(scope, "fetch", fetchWrapper)
    }

    private fun injectModuleExports(cx: Context, scope: Scriptable) {
        cx.evaluateString(
            scope,
            """
            var module = { exports: {} };
            var exports = module.exports;
            var __streams_result__ = [];
            """.trimIndent(),
            "module_init",
            1,
            null
        )
    }

    private fun tryCallExportedFunction(
        cx: Context,
        scope: Scriptable,
        mediaId: String,
        mediaType: String
    ): List<Any?> {
        val idJson = gson.toJson(mediaId)
        val typeJson = gson.toJson(mediaType)

        // Try common Nuvio export patterns
        val callerScript = """
            (function() {
                var _result = [];
                var _fn = null;
                
                // Pattern 1: module.exports.getStreams
                if (typeof module !== 'undefined' && module.exports && typeof module.exports.getStreams === 'function') {
                    _fn = module.exports.getStreams;
                }
                // Pattern 2: module.exports.scrape
                else if (typeof module !== 'undefined' && module.exports && typeof module.exports.scrape === 'function') {
                    _fn = module.exports.scrape;
                }
                // Pattern 3: module.exports is a function
                else if (typeof module !== 'undefined' && typeof module.exports === 'function') {
                    _fn = module.exports;
                }
                // Pattern 4: global getStreams
                else if (typeof getStreams === 'function') {
                    _fn = getStreams;
                }
                // Pattern 5: global scrape
                else if (typeof scrape === 'function') {
                    _fn = scrape;
                }
                // Pattern 6: exports.default
                else if (typeof exports !== 'undefined' && exports.default && typeof exports.default.getStreams === 'function') {
                    _fn = exports.default.getStreams;
                }
                
                if (_fn) {
                    try {
                        var r = _fn($idJson, $typeJson);
                        // Handle both sync return and thenable
                        if (r && typeof r.then === 'function') {
                            // Rhino doesn't support native async; try to access .result if available
                            // Most Nuvio providers using fetch will work because our fetch is sync
                            r.then(function(v) { _result = v || []; });
                        } else if (Array.isArray(r)) {
                            _result = r;
                        }
                    } catch(e) {
                        // ignore
                    }
                }
                return _result;
            })();
        """.trimIndent()

        return try {
            val raw = cx.evaluateString(scope, callerScript, "caller", 1, null)
            nativeArrayToList(raw)
        } catch (e: Exception) {
            Log.w(TAG, "caller script error: ${e.message}")
            emptyList()
        }
    }

    private fun nativeArrayToList(value: Any?): List<Any?> {
        if (value is NativeArray) {
            val list = mutableListOf<Any?>()
            for (i in 0 until value.length) list.add(value[i])
            return list
        }
        return emptyList()
    }

    private fun parseRawStreams(rawStreams: List<Any?>, plugin: NuvioPlugin): List<StreamResult> {
        val results = mutableListOf<StreamResult>()
        for (raw in rawStreams) {
            if (raw !is NativeObject) continue
            val url = raw.get("url", raw)?.toString() ?: continue
            if (url.isEmpty() || url == "undefined") continue

            val title = raw.get("title", raw)?.toString()
                ?: raw.get("name", raw)?.toString()
                ?: "Stream"

            val (quality, qualityRank) = QualityParser.parseQuality(title, url)

            results.add(
                StreamResult(
                    url = url,
                    title = "[${plugin.name}] $title",
                    quality = quality,
                    qualityRank = qualityRank,
                    pluginId = plugin.id,
                    pluginName = plugin.name
                )
            )
        }
        return results
    }
}
