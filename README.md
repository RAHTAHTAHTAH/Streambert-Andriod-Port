# Streambert Mobile (Android)

Android port of the Streambert desktop app. Built as an installable APK
(version `2.7.4-mobile`, versionCode 12) that reuses the Streambert React
renderer inside a native Android WebView, with a native full-screen player and
a native HLS download service.

The WebView loads `https://streambert.local/index.html`; Electron-only IPC is
emulated by `assets/www/mobile-shim.js`, which routes calls to the
`window.Android` JavaScript bridge exposed by `MainActivity`.

---

## 1. Original Streambert features (ported to Android)

Core browsing & playback
- Home page with hero banner, trending, top rated, recommended and continue-watching rows
- Movie and TV detail pages (cast, score, runtime, overview)
- Search across movies and series
- Library: watchlist, history, continue watching
- Streaming from multiple providers (Videasy, Vidking, VidSrc, etc.) with in-app source switching
- Full-screen native player (tap to reveal controls, auto-hide after 3.5 s)
- Ad blocking in the player WebView
- Watched threshold / auto-mark watched
- Autoplay next episode with countdown and overlay position
- Anime intro skip via AniSkip (HiAnime source)
- Trailer playback via Invidious (privacy-friendly YouTube frontend, with fallback instances)

Metadata & parental controls
- TMDB metadata, posters, cast, ratings and age certification
- Configurable TMDB read-access token and metadata language
- Age rating & parental controls: hide/play-block titles above the configured limit
- Rating country selection (maps certificates like PG/FSK/BBFC to a minimum age)

Subtitles
- Subtitle language preference
- Subtitle settings (SubDL / Wyzie API keys where supported)

Settings & appearance
- Theme presets + custom colour editor
- Accent colour (with player pass-through)
- Compact card grid, reduce animations
- Start page selection (Home / Library / Downloads)
- Home page row layout and visibility
- Library sort order and watch-history privacy
- Clear cache, clear watch progress, delete all downloads, full app reset

Downloads
- Downloaded files appear in the in-app Downloads page
- Open, delete and "delete all" downloads
- Download size accounting

---

## 2. Features added from user suggestions

- **Genre browser** — browse movies/series by genre, with a dedicated Genre page.
- **Surprise Me** button — jump to a random title.
- **Search genre filter** — filter search results by genre.
- **Next episode button** — quick jump to the next episode from the player/episode list.
- **Mobile-friendly Settings** — desktop-only options removed/converted (see below).
  
---

## 3. Features not adapted / not working on Android

Desktop-only features that have no Android equivalent (removed from the
mobile Settings page):
- Update checker, update source toggle and "check on startup" (updates are sideloaded APKs)
- Discord Rich Presence
- Scheduled backups (desktop file-system folder)
- Install location / "open install folder"
- Font-size zoom (`setZoomFactor`)
- Controller / gamepad support
- Download folder picker (fixed to `Download/Streambert`, managed by Android/MediaStore)
- Desktop notification toggles (converted to Android notifications; the
  "notify on download complete" toggle is removed because Android always posts one)

Features that are limited or unavailable on Android:
- Subtitle search/download (SubDL/Wyzie flows are disabled in this build; only the manual settings remain)
- HiAnime resolver (desktop scraper; unavailable on mobile)
- PiP window mode
- Drag-and-drop / file-system scanning of a local folder
- Scheduled backup export to a chosen folder
- Some hosters that require a desktop browser environment

Known limitations:
- HLS downloads are saved as fragmented MP4 (`.mp4`) or MPEG-TS (`.m2ts`); very old players may not handle them.
- HEVC-only streams depend on device decoder support for in-app playback.

---

## Build (Android Studio)

Requirements:
- Portable JDK 21
- Android SDK build-tools 35 + platform android-35
- Node (for the Vite renderer build)

Steps:
1. Edit the renderer in `streambert-mobile-build/` (or sync from `streambert-desktop/src`).
2. Build the renderer:
   ```
   node streambert-desktop/node_modules/vite/bin/vite.js build --outDir dist
   ```
   (run from `streambert-mobile-build/`)
3. Copy `dist/assets` into `streambert-android/assets/www/assets` and update
   `assets/www/index.html` hashes.
4. Bump `versionCode`/`versionName` in `AndroidManifest.xml` if needed.
5. Build the APK:
   ```
   powershell -ExecutionPolicy Bypass -File .\build.ps1
   ```
   (run from `streambert-android/`)
6. Install:
   ```
   adb install -r Streambert-Mobile.apk
   ```

The resulting APK is signed with the debug key and is ~0.45 MB.

## Device tested
- Samsung Galaxy A25 (SM-A256E), Android 16
- WebView debugging enabled; CDP available on `localabstract:webview_devtools_remote_<pid>`
- Downloads land in `Download/Streambert/` and are indexed in MediaStore

## Version history
- `2.7.4-mobile` (12) — in-app player for downloads; prefer H.264 variants
- `2.7.3-mobile` (11) — mobile Settings cleanup
- `2.7.2-mobile` (10) — unrated titles treated as 18
- `2.7.1-mobile` (9) — age-limit filtering across pages
- `2.7.0-mobile` — initial Android port
