# Streambert Mobile (Android port)

An unofficial Android port of [Streambert](https://github.com/truelockmc/streambert) 2.7.0.
It reuses the original Electron renderer (the React UI from `app.asar`) inside a
native Android WebView, replaces the Electron IPC bridge with a JavaScript shim,
and hands video playback off to a full-screen native player.

> **Status:** experimental. This is a wrapper port, not a from-scratch rewrite.
> Browsing and playback work; some desktop-only features are stubbed out.

- **Package:** `com.streambert.mobile`
- **Version:** `2.7.4-mobile` (versionCode 12)
- **Min SDK:** 23 (Android 6.0) · **Target SDK:** 34 (Android 14)
- **APK:** `Streambert-Mobile.apk`

---

## Install

1. Copy `Streambert-Mobile.apk` to your phone (USB, cloud drive, `adb push`, …).
2. Allow installation from unknown sources when prompted.
3. Install with a file manager, or:

   ```bash
   adb install -r Streambert-Mobile.apk
   ```

On first launch the app asks for a free **TMDB API Read Access Token**.
Create one at <https://www.themoviedb.org/settings/api> — the original
[guide](https://github.com/truelockmc/streambert/blob/main/tmdb-tutorial.md)
still applies.

---

## Mobile UI

The upstream renderer is desktop-shaped, so `mobile.css` + `mobile-shim.js`
adapt it for phones:

- The left icon rail becomes a **bottom navigation bar** with visible labels
  (Search, Home, Library, Downloads, Settings). Desktop-only entries are hidden.
- Home defaults to a **three-column poster grid** instead of the one-card
  carousel. (You can still switch back to the carousel in Settings.)
- The desktop **genre browser** is included: the "Browse by Genre" chips on Home
  open a paged genre page with a Movies / Series toggle and **Load More**. The
  search sheet also keeps its multi-select genre filter.
- **Surprise Me** in the hero picks a random unwatched movie or series, skipping
  age-restricted titles.
- **Age limit.** When a limit is set in Settings → Content, titles above it are
  hidden from Home, Trending, Top Rated, Recommended, Continue Watching, the
  Library/Watchlist/History, search results and genre pages. Titles without a
  certificate are treated as 18+: hidden whenever the limit is below 18, but
  they still never show a rating badge.
- The hero is shorter, the detail page centres its poster and uses a
  two-column action grid, and search/settings/downloads get phone padding.
- The player card shows the app's **source chip** (e.g. `Vidrift`) at the top
  left; tapping it lists every hoster (VidSrc, Vidfast, VidLove, Videasy,
  Vidrift, Vidnest, DeVideoSrc, Frembed, KinoHub, HiAnime). The choice is saved
  and reused for the next title. Playback no longer auto-starts, so you can
  switch source first and then tap the big play button.
- **Android Back** first closes a modal or leaves a detail page, then falls
  back to the usual "press back again to exit" prompt.

## What works

- Home / trending, genre browsing, Surprise Me, search, movie and TV detail
  pages, seasons and episodes.
- Source selection and stream resolution for the regular hosters.
- Playback of the hoster's own page in a full-screen `PlayerActivity` with the
  desktop app's user agent and referer headers. There is no separate native
  video renderer: the server's controls and fullscreen button drive playback.
- The app's thin bar (close / title / download / open-in-browser) is hidden by
  default. Tap the video once to reveal it; it auto-hides after 3.5 s. Android
  Back always leaves the player.
- Fullscreen video (HTML5 fullscreen API is bridged to the native view).
- Ad/tracker host blocking for player pages (226 hosts).
- TMDB API key storage, library, watch history, progress and settings
  (kept in WebView `localStorage` / Android `SharedPreferences`).
- **Downloads.** The player's download button saves the playing stream to
  `Download/Streambert/`:
  - plain `.mp4` / `.webm` files go through Android's `DownloadManager`;
  - HLS streams go through a built-in segment downloader (`HlsDownloader`)
    that resolves the master playlist, fetches every segment, handles AES-128
    encryption, and concatenates them into a `.m2ts` (or `.mp4` for
    fragmented-MP4 streams). It runs in a foreground service, so progress and
    cancel live in the notification and the download continues if you leave
    the player.
  - **Separate audio tracks are merged in.** Hosters such as Vidrift serve
    video-only fMP4 playlists (`v1.m3u8`) and keep the audio in an
    `#EXT-X-MEDIA:TYPE=AUDIO` rendition (`a3.m3u8`). When the player only
    reports the variant playlist, the downloader looks for the sibling
    `master.m3u8`, picks the matching variant plus its default audio
    rendition, and muxes both into one MP4 (`Fmp4Muxer`). Because both
    renditions are CMAF, the media fragments are copied verbatim and only the
    track ids and fragment sequence numbers are rewritten, so the sample data
    and timestamps stay untouched and audio stays in sync.
  - Not supported yet: pause/resume across app restarts, and separate audio
    for non-fMP4 (MPEG-TS) streams — those still download video only, and
    DRM-protected streams are refused.
- **Downloads tab.** The app's own Downloads page lists everything in
  `Download/Streambert/` (`DownloadIndex`). Finished files are merged into the
  page automatically, with Watch (opens your video player), Show in folder,
  delete (with a native confirmation dialog) and the usual sort/search tools.
  The desktop **Download Folder** setting and the "Notify when a download
  completes" toggle are hidden on mobile, because the native downloader always
  uses the public Downloads folder and posts its own notification.
  Tapping download again for a title that is already saved shows
  "Already saved" instead of creating a duplicate.
- "Open in browser" escape hatch for hosters that misbehave in the WebView.

## Not available (desktop-only, graceful stubs)

- The desktop downloader (`vid-dl-cli` + ffmpeg). The mobile player has its own
  HLS downloader instead; the legacy desktop download dialog is intercepted and
  replaced by the native one.
- HiAnime / AllManga scraping — the app automatically falls back to another source.
- Subtitle search and download (Wyzie).
- In-app updater — install a newer APK to update.
- Scheduled backups, Discord Rich Presence, PiP desktop window, tray/zoom/window controls.

---

## Build from source

No Gradle or Android Studio is required. The script drives `aapt2`, `javac`,
`d8`, `zipalign` and `apksigner` directly.

### Requirements

- Windows with PowerShell 5.1+
- A JDK (17 or 21) — either on `JAVA_HOME` or the portable fallback below
- Android SDK with:
  - `platforms;android-35` (or another platform containing `android.jar`)
  - `build-tools;35.0.0` (or newer)

The script looks for the SDK in `ANDROID_HOME`, `ANDROID_SDK_ROOT`,
`%LOCALAPPDATA%\Android\Sdk`, then
`%LOCALAPPDATA%\Temp\opencode\android-tools\android-sdk`. The JDK is taken from
`JAVA_HOME`, falling back to
`%LOCALAPPDATA%\Temp\opencode\android-tools\jdk\jdk-21.0.13+9`.

### Build

```powershell
cd streambert-android
powershell -ExecutionPolicy Bypass -File .\build.ps1
```

Output: `Streambert-Mobile.apk`, signed with the debug keystore
`streambert-debug.keystore` (password `android`). Replace it with your own
release key before distributing.

### Project layout

```
streambert-android/
├── AndroidManifest.xml
├── build.ps1                     # manual build + sign pipeline
├── java/com/streambert/mobile/
│   ├── MainActivity.java         # WebView + https://streambert.local asset server
│   ├── PlayerActivity.java       # full-screen player, media capture, download
│   ├── DownloadService.java      # foreground HLS download service
│   ├── DownloadIndex.java        # lists/deletes Download/Streambert for the Downloads tab
│   ├── HlsDownloader.java        # playlist parsing, AES-128, segment downloader
│   ├── Fmp4Muxer.java            # merges separate video/audio CMAF renditions into one MP4
│   └── AdBlock.java              # host blocklist matcher
├── assets/
│   ├── ad_hosts.txt              # blocked hosts (from upstream index.js)
│   └── www/                      # original renderer + mobile layer
│       ├── index.html
│       ├── assets/               # compiled React app from app.asar
│       ├── mobile.css
│       └── mobile-shim.js        # window.electron compatibility layer
├── res/mipmap-xxxhdpi/ic_launcher.png
├── LICENSE                       # GPL-3.0
└── Streambert-Mobile.apk
```

### How the port works

1. `MainActivity` serves `assets/www` through a `WebViewAssetLoader`-style
   request interceptor at `https://streambert.local`, so ES modules, CORS and
   `localStorage` behave like a normal secure origin.
2. `mobile-shim.js` defines `window.electron` with the same method names the
   React app calls, returning resolved promises and stubbing desktop features.
3. The app's `<webview>` elements are decorated into tappable placeholders.
   Tapping one calls `Android.openPlayer(url, ua, title)`; the app's own
   source chip stays visible above the placeholder so the hoster can be changed
   before playing.
4. `PlayerActivity` loads the embed page with a desktop user agent, blocks
   ad hosts, captures the first `.mp4`/`.m3u8` request, and exposes
   download / copy-link / open-in-browser actions.
5. `MainActivity.onBackPressed()` calls `window.__streambertBack()` in the
   WebView so hardware Back follows the app's own navigation before exiting.

### Security notes

- The TMDB token is stored in Android `SharedPreferences` **without**
  OS-level encryption (the desktop app used the OS keychain). Use a
  low-privilege TMDB token.
- Cleartext HTTP is enabled because some hosters still use it.
- WebView remote debugging is enabled (this is a debug-style sideload build).
  Disable `WebView.setWebContentsDebuggingEnabled(true)` in `MainActivity.java`
  for a production release.
- The debug signing key is public. Re-sign with your own key for real distribution.

---

## License

Streambert is licensed under the **GNU General Public License v3.0**. This port
is a modified version of the same work and is distributed under the same
license; see [`LICENSE`](LICENSE). The original source is
<https://github.com/truelockmc/streambert>.

This project is not affiliated with or endorsed by the upstream author.
Streaming copyrighted content may be illegal in your jurisdiction — you are
responsible for how you use this software.
