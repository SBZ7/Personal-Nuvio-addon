# NuvioStremio

A local Stremio addon server for Android that runs Nuvio JS provider plugins and serves streams over HTTP on port **8585**.

## Features

- 🎬 **Local Stremio Addon** — serves a fully Stremio-compatible addon at `http://<device-ip>:8585`
- 🧩 **Nuvio Plugin Manager** — browse [nuvioplugins.com](https://nuvioplugins.com/index.html) inside the app, install repos and plugins with one tap
- ⚡ **JS Engine** — runs Nuvio `.js` provider files using Mozilla Rhino (no root, no Node.js)
- 🏆 **Quality Sorting** — streams are auto-sorted: 4K → 1080p → 720p → 480p
- 📡 **Foreground Service** — server keeps running in background with a persistent notification
- 🔄 **Auto-start on Boot** (optional setting)

## How to build (no Android Studio needed)

### Method 1 — GitHub Actions (recommended)

1. **Fork or push** this repo to your GitHub account
2. Go to **Actions** tab → the `Build APK` workflow runs automatically on every push
3. After it completes (~5 minutes), go to **Actions → Build APK → latest run → Artifacts**
4. Download `NuvioStremio-debug.zip`, extract the APK, sideload it onto your phone

### Method 2 — Local build

```bash
# Requires JDK 17 + Android SDK
./gradlew assembleDebug
# APK at: app/build/outputs/apk/debug/app-debug.apk
```

## How to use

1. Install the APK on your Android phone
2. Open **NuvioStremio**
3. Tap **Repos** → the app loads [nuvioplugins.com](https://nuvioplugins.com/index.html)
4. Find a repo → tap the repo link → tap **Install**
5. Tap **Repos → Browse Plugins** on your installed repo → install the plugins you want
6. Go back to **Home** → tap **Start Server**
7. Copy the addon URL or tap **Install in Stremio**
8. Add the manifest URL in Stremio: `http://<your-phone-ip>:8585/manifest.json`

## Architecture

```
Android App
├── UI (3 tabs)
│   ├── HomeFragment     — server on/off, status, URL
│   ├── RepoFragment     — WebView (nuvioplugins.com) + repo list
│   └── PluginFragment   — per-repo plugin browser + installed list
│
├── Engine
│   ├── PluginManager    — install/store repos & plugins (SharedPrefs + files)
│   └── NuvioJsRunner    — executes JS plugins via Mozilla Rhino
│
└── Server
    ├── StremioAddonServer  — NanoHTTPD on port 8585
    │   ├── GET /manifest.json
    │   └── GET /stream/{type}/{id}.json
    └── StremioForegroundService — keeps server alive
```

## Nuvio Plugin API

Nuvio plugins are standard JS files that export a `getStreams(id, type)` function.
The app supports multiple export patterns:

```js
// Pattern 1
module.exports.getStreams = async (id, type) => [{ url, title }]

// Pattern 2  
module.exports = async (id, type) => [{ url, title }]

// Pattern 3
async function getStreams(id, type) { return [...] }
```

The `id` passed is the raw Stremio media ID (`tt1234567`, `kitsu:12345`, etc.).

## Notes

- The server is accessible on your **local Wi-Fi network** at `http://<phone-LAN-ip>:8585`
- Use `http://127.0.0.1:8585` if accessing from Stremio installed on the same phone
- Nuvio plugins run synchronously via Rhino. Most plugins using `fetch()` work because the app provides a **synchronous OkHttp-backed fetch polyfill**
- Streams are deduplicated by URL and sorted by detected quality
