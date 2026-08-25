package com.nuviostremio.model

import com.google.gson.annotations.SerializedName

// ─── Plugin Repo / Plugin Models ───────────────────────────────────────────

data class PluginRepo(
    val id: String,
    val name: String,
    val url: String,          // URL to the repo index JSON
    val description: String = "",
    var isInstalled: Boolean = false
)

data class NuvioPlugin(
    val id: String,
    val name: String,
    val version: String = "1.0",
    val description: String = "",
    val author: String = "",
    val repoId: String,
    val scriptUrl: String,    // URL to the .js provider file
    val iconUrl: String = "",
    val types: List<String> = listOf("movie", "series"),
    var isEnabled: Boolean = false,
    var localScriptPath: String = "" // path to downloaded JS file on device
)

// ─── Nuvio Repo Index Format ───────────────────────────────────────────────

data class NuvioRepoIndex(
    @SerializedName("name") val name: String = "",
    @SerializedName("description") val description: String = "",
    @SerializedName("plugins") val plugins: List<NuvioRepoPlugin> = emptyList()
)

data class NuvioRepoPlugin(
    @SerializedName("id") val id: String = "",
    @SerializedName("name") val name: String = "",
    @SerializedName("version") val version: String = "1.0",
    @SerializedName("description") val description: String = "",
    @SerializedName("author") val author: String = "",
    @SerializedName("script") val script: String = "", // relative or absolute URL
    @SerializedName("icon") val icon: String = "",
    @SerializedName("types") val types: List<String> = listOf("movie", "series")
)

// ─── Stream Models ────────────────────────────────────────────────────────

data class StreamResult(
    val url: String,
    val title: String,
    val quality: String = "Unknown",
    val qualityRank: Int = 99,
    val pluginId: String,
    val pluginName: String,
    val behaviorHints: BehaviorHints = BehaviorHints()
)

data class BehaviorHints(
    val bingeGroup: String? = null,
    val notWebReady: Boolean = false
)

// Stremio-compatible stream object for JSON serialization
data class StremioStream(
    @SerializedName("url") val url: String,
    @SerializedName("title") val title: String,
    @SerializedName("behaviorHints") val behaviorHints: Map<String, Any> = emptyMap()
)

data class StremioStreamsResponse(
    @SerializedName("streams") val streams: List<StremioStream>
)

// ─── Stremio Manifest ────────────────────────────────────────────────────

data class StremioManifest(
    @SerializedName("id") val id: String = "com.nuviostremio.addon",
    @SerializedName("version") val version: String = "1.0.0",
    @SerializedName("name") val name: String = "NuvioStremio",
    @SerializedName("description") val description: String = "Local Nuvio plugin bridge for Stremio",
    @SerializedName("resources") val resources: List<String> = listOf("stream"),
    @SerializedName("types") val types: List<String> = listOf("movie", "series"),
    @SerializedName("idPrefixes") val idPrefixes: List<String> = listOf("tt", "kitsu")
)

// ─── Quality parsing helper ───────────────────────────────────────────────

object QualityParser {
    private val qualityPatterns = listOf(
        Pair(Regex("(?i)4k|2160p|uhd"), 1),
        Pair(Regex("(?i)1080p|fhd|full.?hd"), 2),
        Pair(Regex("(?i)720p|hd"), 3),
        Pair(Regex("(?i)480p|sd"), 4),
        Pair(Regex("(?i)360p|240p"), 5),
        Pair(Regex("(?i)cam|ts|tc|hdcam"), 6)
    )

    fun parseQuality(title: String, url: String): Pair<String, Int> {
        val combined = "$title $url"
        for ((pattern, rank) in qualityPatterns) {
            val match = pattern.find(combined)
            if (match != null) {
                return Pair(match.value.uppercase(), rank)
            }
        }
        return Pair("Unknown", 99)
    }
}
