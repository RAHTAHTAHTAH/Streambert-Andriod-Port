/*
 * Streambert Mobile - Android compatibility layer
 * ------------------------------------------------
 * This file emulates the Electron preload bridge (window.electron) inside an
 * Android WebView and routes video playback to the native full-screen player.
 *
 * Streambert is GPL-3.0. This file is part of a modified/ported build and is
 * therefore also GPL-3.0. Original project: https://github.com/truelockmc/streambert
 */
(function () {
  "use strict";

  var bridge = window.Android || {};
  var LS = null;
  try { LS = window.localStorage; } catch (e) {}

  var CLEAN_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
    "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

  function toast(msg) {
    try {
      if (bridge.toast) bridge.toast(String(msg));
    } catch (e) {}
  }

  function openExternal(url) {
    try {
      if (bridge.openUrl) bridge.openUrl(String(url));
      else window.open(url, "_blank");
    } catch (e) {}
  }

  function isHttp(url) {
    return typeof url === "string" && /^https?:\/\//i.test(url);
  }

  // Native download list cache. Keyed by entry id so repeated syncs only
  // notify React about genuinely new files.
  var knownDownloads = Object.create(null);
  var lastDownloadSync = 0;
  var suppressingDownloadModal = false;

  // ---------------------------------------------------------------------
  // Tiny event emitter used by the renderer's on/off IPC subscriptions
  // ---------------------------------------------------------------------
  var handlers = Object.create(null);
  function emit(name, data) {
    var set = handlers[name];
    if (!set) return;
    set.forEach(function (cb) {
      try { cb(data); } catch (e) { console.error(e); }
    });
  }
  function on(name) {
    return function (cb) {
      if (typeof cb !== "function") return cb;
      (handlers[name] || (handlers[name] = new Set())).add(cb);
      return cb;
    };
  }
  function off(name) {
    return function (h) {
      if (handlers[name]) handlers[name].delete(h);
    };
  }

  // Mobile defaults: do not nag about desktop updates, and prefer the poster
  // grid over the desktop carousel (one large card is awkward on a phone).
  try {
    if (LS && LS.getItem("streambert_autoCheckUpdates") === null) {
      LS.setItem("streambert_autoCheckUpdates", "false");
    }
    if (LS && LS.getItem("streambert_homeViewMode") === null) {
      LS.setItem("streambert_homeViewMode", JSON.stringify("list"));
    }
    // The native service already posts a completion notification; the
    // renderer's toast would fire again every time the list is synced.
    if (LS) {
      LS.setItem("streambert_notifyDownloadComplete", "false");
    }
  } catch (e) {}

  // ---------------------------------------------------------------------
  // Player hand-off
  // ---------------------------------------------------------------------
  function launchPlayer(url, title, userAgent) {
    if (!isHttp(url)) return false;
    try {
      if (bridge.openPlayer) {
        bridge.openPlayer(String(url), userAgent || CLEAN_UA, title || "Streambert");
        return true;
      }
    } catch (e) {
      console.error("openPlayer failed", e);
    }
    return false;
  }

  var api = {
    isMobile: true,
    cleanUserAgent: CLEAN_UA,

    // -- info ------------------------------------------------------------
    getPlatform: function () { return Promise.resolve("android"); },
    getAppVersion: function () {
      try { return Promise.resolve(bridge.getVersion ? bridge.getVersion() : "2.7.0"); }
      catch (e) { return Promise.resolve("2.7.0"); }
    },
    getInstallPath: function () { return Promise.resolve(""); },

    // -- external links / player ----------------------------------------
    openExternal: function (url) { openExternal(url); },
    openStreamExternal: function (args) {
      var url = args && args.url ? args.url : args;
      var title = args && args.title ? args.title : "Streambert";
      if (launchPlayer(url, title, args && args.userAgent)) {
        return Promise.resolve({ ok: true });
      }
      return Promise.resolve({ ok: false, error: "No playable URL" });
    },
    openPipWindow: function (url, title) {
      return Promise.resolve(launchPlayer(url, title, CLEAN_UA));
    },
    closePipWindow: function () { return Promise.resolve(); },
    getPipWebContentsId: function () { return Promise.resolve(null); },
    setPlayerVideo: function (args) {
      var url = args && args.url ? args.url : "";
      if (url) launchPlayer(url, "Streambert", args && args.userAgent);
      return Promise.resolve({ playerUrl: url });
    },
    playerStopped: function () {},

    // -- downloads (native Download/Streambert folder) --------------------
    checkDownloader: function () {
      return Promise.resolve({ exists: true, path: "Download/Streambert" });
    },
    runDownload: function () {
      return Promise.resolve(startNativeDownload());
    },
    getDownloads: function () { return Promise.resolve(readDownloads()); },
    deleteDownload: function (args) {
      var id = args && args.id;
      var filePath = args && args.filePath;
      if (!filePath) return Promise.resolve({ ok: true });
      var ok = false;
      try {
        ok = bridge.deleteDownload ? !!bridge.deleteDownload(String(filePath)) : false;
      } catch (e) {}
      if (ok && id) delete knownDownloads[String(id)];
      return Promise.resolve({ ok: ok });
    },
    deleteAllDownloads: function () {
      var ok = false;
      try {
        ok = bridge.deleteAllDownloads ? !!bridge.deleteAllDownloads() : false;
      } catch (e) {}
      if (ok) knownDownloads = Object.create(null);
      return Promise.resolve({ ok: ok });
    },
    getDownloadsSize: function () {
      var bytes = 0;
      try {
        bytes = bridge.getDownloadsSize ? Number(bridge.getDownloadsSize()) || 0 : 0;
      } catch (e) {}
      return Promise.resolve({ bytes: bytes });
    },
    showInFolder: function () {
      try { if (bridge.showInFolder) bridge.showInFolder(); } catch (e) {}
      return Promise.resolve();
    },
    fileExists: function (path) {
      var exists = false;
      try {
        exists = bridge.fileExists ? !!bridge.fileExists(String(path)) : false;
      } catch (e) {}
      return Promise.resolve(exists);
    },
    scanDirectory: function () { return Promise.resolve([]); },
    pruneSubtitlePaths: function () { return Promise.resolve({ ok: true, subtitlePaths: [] }); },
    pickFolder: function () { return Promise.resolve(null); },
    openPath: function (path) {
      try { if (bridge.openPath) bridge.openPath(String(path)); } catch (e) {}
      return Promise.resolve();
    },
    shareText: function (subject, text) {
      try { if (bridge.shareText) bridge.shareText(String(subject || ""), String(text || "")); } catch (e) {}
      return Promise.resolve();
    },
    openPathAtTime: function (path) {
      try { if (bridge.openPath) bridge.openPath(String(path)); } catch (e) {}
      return Promise.resolve();
    },
    getVideoDuration: function () { return Promise.resolve({ ok: false, duration: 0 }); },

    // -- subtitles -------------------------------------------------------
    searchSubtitles: function () {
      return Promise.resolve({
        ok: false,
        error: "Subtitle search is not available in this mobile build.",
      });
    },
    getSubtitleUrl: function () { return Promise.resolve(null); },
    downloadSubtitlesForFile: function () {
      return Promise.resolve({ ok: false, error: "Not available on Android" });
    },
    deleteSubtitleFile: function () { return Promise.resolve({ ok: false }); },
    wyzieOpenRedeem: function () {
      openExternal("https://store.wyzie.ru/");
      return Promise.resolve();
    },
    wyzieValidateKey: function () {
      return Promise.resolve({
        ok: false,
        error: "Wyzie keys are not supported in this mobile build.",
      });
    },

    // -- anime resolver (desktop main-process scraper; unavailable) ------
    resolveHiAnime: function () {
      return Promise.resolve({
        ok: false,
        error: "HiAnime is not supported in this mobile build - switching source.",
      });
    },
    debugHiAnime: function () { return Promise.resolve(null); },
    onHiAnimeProgress: function () { return function () {}; },

    // -- secure storage (Android SharedPreferences) ----------------------
    secureGet: function (key) {
      try {
        return Promise.resolve(bridge.secureGet ? bridge.secureGet(String(key)) : null);
      } catch (e) { return Promise.resolve(null); }
    },
    secureSet: function (key, value) {
      try {
        if (bridge.secureSet) {
          bridge.secureSet(String(key), value === null || value === undefined ? "" : String(value));
        }
      } catch (e) {}
      return Promise.resolve();
    },

    // -- notifications / app lifecycle -----------------------------------
    showNotification: function (data) {
      if (data) toast((data.title ? data.title + ": " : "") + (data.body || ""));
      return Promise.resolve();
    },
    quitApp: function () {
      try { if (bridge.exitApp) bridge.exitApp(); } catch (e) {}
      return Promise.resolve();
    },
    resetApp: function () {
      try { if (LS) LS.clear(); } catch (e) {}
      toast("App data cleared. Restarting...");
      setTimeout(function () { location.reload(); }, 500);
      return Promise.resolve();
    },
    respondClose: function () {},
    windowClose: function () {
      try { if (bridge.exitApp) bridge.exitApp(); } catch (e) {}
      return Promise.resolve();
    },
    windowMinimize: function () { return Promise.resolve(); },
    windowToggleMaximize: function () { return Promise.resolve(); },
    windowIsMaximized: function () { return Promise.resolve(false); },
    setZoomFactor: function () {},

    // -- cache / data ----------------------------------------------------
    getCacheSize: function () { return Promise.resolve(0); },
    clearAppCache: function () {
      try { if (bridge.clearCache) bridge.clearCache(); } catch (e) {}
      return Promise.resolve();
    },
    clearWatchData: function () {
      try {
        if (LS) {
          Object.keys(LS).forEach(function (k) {
            if (
              k.indexOf("streambert_progress") === 0 ||
              k.indexOf("streambert_watched") === 0 ||
              k.indexOf("streambert_history") === 0 ||
              k.indexOf("dlTime_") === 0 ||
              k.indexOf("dlDur_") === 0
            ) LS.removeItem(k);
          });
        }
      } catch (e) {}
      return Promise.resolve();
    },
    getBlockStats: function () { return Promise.resolve({ total: 0 }); },

    // -- updater (APK updates are sideloaded) ----------------------------
    detectUpdateFormat: function () { return Promise.resolve(null); },
    downloadAndInstallUpdate: function () {
      return Promise.resolve({
        ok: false,
        error: "Install a newer APK to update Streambert Mobile.",
      });
    },
    cancelUpdate: function () { return Promise.resolve(); },

    // -- backups (desktop-only) ------------------------------------------
    getScheduledBackupSettings: function () { return Promise.resolve(null); },
    setScheduledBackupSettings: function () { return Promise.resolve(); },
    performScheduledBackup: function () {
      return Promise.resolve({ ok: false, error: "Not available on Android" });
    },

    // -- discord (desktop-only) ------------------------------------------
    discordRpcSetEnabled: function () { return Promise.resolve(); },
    discordRpcUpdateActivity: function () { return Promise.resolve(); },

    fetchReleaseImage: function () { return Promise.resolve(null); },

    // -- events ----------------------------------------------------------
    onM3u8Found: on("m3u8-found"),
    offM3u8Found: off("m3u8-found"),
    onSubtitleFound: on("subtitle-found"),
    offSubtitleFound: off("subtitle-found"),
    onDownloadProgress: on("download-progress"),
    offDownloadProgress: off("download-progress"),
    onConfirmClose: on("confirm-close"),
    offConfirmClose: off("confirm-close"),
    onWebviewEnterFullscreen: on("webview-enter-fullscreen"),
    offWebviewEnterFullscreen: off("webview-enter-fullscreen"),
    onWebviewLeaveFullscreen: on("webview-leave-fullscreen"),
    offWebviewLeaveFullscreen: off("webview-leave-fullscreen"),
    onBlockedUpdate: on("blocked-stats-update"),
    offBlockedUpdate: off("blocked-stats-update"),
    onPipOpened: on("pip-window-opened"),
    offPipOpened: off("pip-window-opened"),
    onPipClosed: on("pip-window-closed"),
    offPipClosed: off("pip-window-closed"),
    onWindowMaximize: on("window-maximized"),
    offWindowMaximize: off("window-maximized"),
    onUpdateProgress: on("update-progress"),
    offUpdateProgress: off("update-progress"),
    onScheduledBackupRequested: on("scheduled-backup-requested"),
    offScheduledBackupRequested: off("scheduled-backup-requested"),
  };

  window.electron = api;

  // ---------------------------------------------------------------------
  // Native downloads: read Download/Streambert and feed new files into the
  // renderer's Downloads page through its normal download-progress event.
  // ---------------------------------------------------------------------
  function readDownloads() {
    try {
      if (!bridge.listDownloads) return [];
      var raw = bridge.listDownloads();
      var list = raw ? JSON.parse(raw) : [];
      return Array.isArray(list) ? list : [];
    } catch (e) {
      return [];
    }
  }

  function syncDownloads(force) {
    if (!bridge.listDownloads) return;
    // Do not consume entries before the renderer has subscribed.
    if (!handlers["download-progress"] || handlers["download-progress"].size === 0) return;

    var now = Date.now();
    if (!force && now - lastDownloadSync < 1500) return;
    lastDownloadSync = now;

    var list = readDownloads();
    for (var i = 0; i < list.length; i++) {
      var entry = list[i];
      if (!entry || !entry.id) continue;
      var key = String(entry.id);
      if (knownDownloads[key] === entry.size) continue;
      knownDownloads[key] = entry.size;
      emit("download-progress", entry);
    }
  }

  function startNativeDownload() {
    var result = { ok: false, reason: "unavailable" };
    try {
      if (bridge.downloadCurrent) {
        var raw = bridge.downloadCurrent(mediaTitle());
        result = raw ? JSON.parse(raw) : result;
      }
    } catch (e) {
      result = { ok: false, reason: "error" };
    }

    if (result && result.ok) {
      toast("Download started - progress is in the notification.");
    } else if (result && result.reason === "permission") {
      toast("Allow storage access, then tap download again.");
    } else if (result && result.reason === "no_stream") {
      toast("Start the video first, then tap download.");
    } else if (result && result.reason === "exists") {
      toast("Already saved - open the Downloads tab to watch it.");
    } else {
      toast("Could not start the download.");
    }
    return result;
  }

  window.__streambertDownloadsChanged = function () {
    syncDownloads(true);
  };

  // Replace the desktop DownloadModal: the player-overlay download button
  // now starts the native HLS/MP4 downloader instead.
  document.addEventListener("click", function (e) {
    var target = e.target;
    if (!target || !target.closest) return;
    var btn = target.closest(".player-wrap .player-overlay-btn");
    if (!btn) return;
    var label = (btn.getAttribute("title") || "").trim();
    if (!/^(Download|Already downloaded|Downloading)/i.test(label)) return;
    e.preventDefault();
    e.stopPropagation();
    if (e.stopImmediatePropagation) e.stopImmediatePropagation();
    startNativeDownload();
  }, true);

  // Catch files that finished while the page was loading.
  setTimeout(function () { syncDownloads(true); }, 2000);

  // Native player can report the captured stream URL back to the app.
  window.__streambertNativeStream = function (url) {
    if (isHttp(url)) emit("m3u8-found", url);
  };
  window.__streambertNativeToast = function (msg) {
    toast(msg);
  };

  // ---------------------------------------------------------------------
  // Mobile chrome: turn the desktop left rail into a labelled bottom nav
  // and tag pages so mobile.css can target them.
  // ---------------------------------------------------------------------
  function normalizeNav() {
    var bar = document.querySelector(".sidebar");
    if (!bar) return;

    var buttons = bar.querySelectorAll(".sidebar-btn");
    for (var i = 0; i < buttons.length; i++) {
      var btn = buttons[i];
      var tip = btn.querySelector(".tooltip");
      var raw = (tip ? tip.textContent : btn.getAttribute("title") || "").trim();

      // The app renders its own Back action on pages that need one, and
      // Android hardware Back is bridged below.
      if (/^Back\b/i.test(raw)) {
        btn.classList.add("sb-nav-back");
        continue;
      }
      // Desktop-only entries.
      if (/^Help\b/i.test(raw) || /^Quit App$/i.test(raw)) {
        btn.classList.add("sb-nav-hide");
        continue;
      }
      if (!tip) continue;

      // "Search  (Ctrl+F)" -> "Search", "Library & History" -> "Library".
      var label = raw.replace(/\s*\([^)]*\)\s*$/, "").trim();
      if (label === "Library & History") label = "Library";
      if (tip.textContent !== label) tip.textContent = label;
      if (btn.getAttribute("aria-label") !== label) {
        btn.setAttribute("aria-label", label);
      }
    }
  }

  function classifyPage() {
    var main = document.querySelector(".main");
    if (!main) return;

    var dlPage = main.querySelector(".dl-page");
    main.classList.toggle("sb-settings", !dlPage && !!main.querySelector(".settings-section-title"));
    main.classList.toggle("sb-detail", !!main.querySelector(".detail-hero"));
    main.classList.toggle("sb-downloads", !!dlPage);
    main.classList.toggle("sb-library", !!main.querySelector(".library-header"));

    // Local-folder scanning is desktop-only. Only the header (and its scan
    // controls) is hidden: completed downloads are rendered in the list that
    // follows it, so the list itself must stay visible.
    var localHeader = main.querySelector(".dl-page__local-header");
    if (localHeader) localHeader.classList.add("sb-dl-local");

    if (dlPage) {
      syncDownloads();
      var dlEmpty = dlPage.querySelector(".empty-state p");
      if (dlEmpty && /scan a folder/i.test(dlEmpty.textContent)) {
        dlEmpty.textContent = "Start a download from the player (tap the video, then the download icon).";
      }
      var dlEmptyText = dlPage.querySelector(".dl-page__empty-text");
      if (dlEmptyText && /scan a folder/i.test(dlEmptyText.textContent)) {
        dlEmptyText.textContent = "No downloads yet. Start one from the player.";
      }
    }

    // Desktop keyboard hint in the search modal.
    var hint = document.querySelector(".search-hint");
    if (hint && /esc to close/i.test(hint.textContent)) {
      hint.textContent = hint.textContent.replace(/esc to close/i, "tap \u00d7 to close");
    }

    hideDownloadSettings();
    suppressDownloadModal();
  }

  // The desktop Download Folder setting has no meaning on Android, and the
  // old completion toggle is replaced by the native notification.
  function hideDownloadSettings() {
    var main = document.querySelector(".main.sb-settings");
    if (!main) return;

    if (!main.querySelector(".sb-hide-download-settings")) {
      var titles = main.querySelectorAll(".settings-section-title");
      for (var i = 0; i < titles.length; i++) {
        if (/^Download Folder$/i.test((titles[i].textContent || "").trim())) {
          var group = titles[i].parentElement && titles[i].parentElement.parentElement;
          if (group) group.classList.add("sb-hide-download-settings");
          break;
        }
      }
    }

    if (!main.querySelector(".sb-hide-download-setting-row")) {
      var divs = main.querySelectorAll("div");
      for (var j = 0; j < divs.length; j++) {
        var el = divs[j];
        if (el.children.length === 0 &&
            /^Notify when a download completes$/i.test((el.textContent || "").trim())) {
          var row = el.parentElement && el.parentElement.parentElement;
          if (row) row.classList.add("sb-hide-download-setting-row");
          break;
        }
      }
    }
  }

  // The legacy modal can still be opened by old keyboard shortcuts; close it
  // immediately so only the native download flow is visible.
  function suppressDownloadModal() {
    if (suppressingDownloadModal) return;
    var modal = document.querySelector(".download-modal");
    if (!modal) return;

    suppressingDownloadModal = true;
    var close = modal.querySelector(".download-modal-header .icon-btn");
    if (close) {
      close.click();
    } else {
      var backdrop = modal.closest(".modal-backdrop");
      if (backdrop) backdrop.style.display = "none";
    }
    setTimeout(function () { suppressingDownloadModal = false; }, 200);
  }

  // Netflix-style wording for the detail page's saved state. The button
  // otherwise reads "Save" / "Saved".
  function normalizeDetailActions() {
    var buttons = document.querySelectorAll(".detail-actions button");
    for (var i = 0; i < buttons.length; i++) {
      var btn = buttons[i];
      for (var j = 0; j < btn.childNodes.length; j++) {
        var node = btn.childNodes[j];
        if (node.nodeType !== 3) continue;
        if (/^\s*Saved?\s*$/.test(node.nodeValue)) {
          node.nodeValue = "My List";
        }
      }
    }
  }

  var chromePending = false;
  function refreshChrome() {
    if (chromePending) return;
    chromePending = true;
    var run = function () {
      chromePending = false;
      try { normalizeNav(); } catch (e) {}
      try { classifyPage(); } catch (e) {}
      try { normalizeDetailActions(); } catch (e) {}
      try { ensureSeasonButton(); } catch (e) {}
    };
    if (window.requestAnimationFrame) window.requestAnimationFrame(run);
    else setTimeout(run, 50);
  }

  // ---------------------------------------------------------------------
  // "Download season"
  // ---------------------------------------------------------------------
  // The renderer keeps the embed URL for the selected episode in the src of
  // its <webview> element. There is no public API to ask for the URL of an
  // episode that is not selected, so this walks the season: select an
  // episode, read the URL the app just built, repeat. Nothing is loaded or
  // played during the walk; the native side resolves the streams afterwards.
  var seasonBusy = false;

  function seasonNumber() {
    var buttons = document.querySelectorAll(".season-selector .season-btn");
    for (var i = 0; i < buttons.length; i++) {
      if (buttons[i].classList.contains("active")) {
        var match = (buttons[i].textContent || "").match(/(\d+)/);
        return match ? parseInt(match[1], 10) : i + 1;
      }
    }
    return 1;
  }

  function showTitle() {
    var el = document.querySelector(".detail-title");
    var name = el && el.textContent ? el.textContent.trim() : "";
    if (!name) name = (document.title || "").trim();
    return name;
  }

  function usableEpisodeCards() {
    var grid = document.querySelector(".episodes-grid");
    if (!grid) return [];
    var cards = grid.querySelectorAll(".episode-card");
    var out = [];
    for (var i = 0; i < cards.length; i++) {
      var card = cards[i];
      if (card.classList.contains("episode-card--restricted")) continue;
      if (card.classList.contains("episode-card--unreleased")) continue;
      out.push(card);
    }
    return out;
  }

  function episodeNumberOf(card, fallback) {
    var el = card.querySelector(".episode-num");
    var match = el && el.textContent ? el.textContent.match(/(\d+)/) : null;
    return match ? parseInt(match[1], 10) : fallback;
  }

  function episodeNameOf(card) {
    var el = card.querySelector(".episode-name");
    return el && el.textContent ? el.textContent.trim() : "";
  }

  function webviewSrc() {
    var view = document.querySelector("webview");
    return view ? (view.getAttribute("src") || "") : "";
  }

  function waitForEpisodeSrc(previous, timeout) {
    return new Promise(function (resolve) {
      var started = Date.now();
      function poll() {
        var src = webviewSrc();
        if (isHttp(src) && src !== previous) return resolve(src);
        if (Date.now() - started > timeout) return resolve("");
        setTimeout(poll, 50);
      }
      poll();
    });
  }

  function delay(ms) {
    return new Promise(function (resolve) { setTimeout(resolve, ms); });
  }

  function collectSeason() {
    var cards = usableEpisodeCards();
    if (!cards.length) return Promise.resolve([]);

    var previous = document.querySelector(".episode-card.playing");
    var lastSrc = webviewSrc();
    var collected = [];

    return cards.reduce(function (chain, card, index) {
      return chain.then(function () {
        var number = episodeNumberOf(card, index + 1);
        var name = episodeNameOf(card);
        card.click();
        return waitForEpisodeSrc(lastSrc, 1800).then(function (src) {
          if (src) {
            lastSrc = src;
            collected.push({ n: number, title: name, url: src });
          }
          return delay(40);
        });
      });
    }, Promise.resolve()).then(function () {
      if (previous && previous !== cards[cards.length - 1]) {
        try { previous.click(); } catch (e) {}
      }
      return collected;
    });
  }

  function onSeasonDownload(button) {
    if (seasonBusy) return;
    if (!bridge.startSeasonDownload) {
      toast("Update the app to download a whole season.");
      return;
    }
    var cards = usableEpisodeCards();
    if (!cards.length) {
      toast("No episodes to download in this season.");
      return;
    }

    seasonBusy = true;
    var label = button.innerHTML;
    button.textContent = "Collecting\u2026";
    button.classList.add("sb-season-dl-busy");
    toast("Reading " + cards.length + " episode links\u2026");

    collectSeason().then(function (items) {
      if (!items.length) {
        toast("Could not read the episode links. Try another source.");
        return;
      }
      var payload = {
        show: showTitle(),
        season: seasonNumber(),
        episodes: items,
      };
      var result = {};
      try {
        result = JSON.parse(bridge.startSeasonDownload(JSON.stringify(payload)) || "{}");
      } catch (e) {
        result = {};
      }
      if (result && result.reason === "permission") {
        toast("Allow storage access, then tap Download Season again.");
      } else if (!result || !result.ok) {
        toast("Could not start the season download.");
      }
    }).catch(function (e) {
      console.error("season download failed", e);
      toast("Could not read the episode links.");
    }).then(function () {
      seasonBusy = false;
      button.innerHTML = label;
      button.classList.remove("sb-season-dl-busy");
    });
  }

  function ensureSeasonButton() {
    var selector = document.querySelector(".season-selector");
    if (!selector) return;
    if (!document.querySelector(".episodes-grid")) return;

    var existing = selector.querySelector(".sb-season-dl");
    if (existing) {
      if (seasonBusy) existing.classList.add("sb-season-dl-busy");
      return;
    }

    var button = document.createElement("button");
    button.type = "button";
    button.className = "season-btn sb-season-dl";
    button.innerHTML = '<span class="sb-season-dl-icon">\u2913</span> Download Season';
    button.title = "Download every episode of the selected season";
    button.addEventListener("click", function (e) {
      e.preventDefault();
      e.stopPropagation();
      onSeasonDownload(button);
    });
    selector.appendChild(button);
  }


  // ---------------------------------------------------------------------
  // Hardware Back bridge (called from MainActivity.onBackPressed).
  // Returns true when the app handled it; false lets Android show the
  // "press back again to exit" prompt.
  // ---------------------------------------------------------------------
  window.__streambertBack = function () {
    var overlay = document.querySelector(".modal-overlay");
    if (overlay) {
      var close = overlay.querySelector("button");
      if (close) {
        close.click();
        return true;
      }
    }

    // The setup screen has nothing behind it; let Back fall through to the
    // normal "press back again to exit" prompt.
    if (document.querySelector(".apikey-modal")) return false;

    var actions = document.querySelectorAll(".detail-actions .btn");
    for (var i = 0; i < actions.length; i++) {
      if (/^Back\b/i.test(actions[i].textContent || "")) {
        actions[i].click();
        return true;
      }
    }

    var navBack = document.querySelector(".sidebar-btn.sb-nav-back");
    if (navBack) {
      navBack.click();
      return true;
    }
    return false;
  };

  refreshChrome();
  new MutationObserver(refreshChrome).observe(document.documentElement, {
    childList: true,
    subtree: true,
    characterData: true,
  });

  // Tapping the already-active bottom-nav item behaves like Netflix: it
  // scrolls back to the top (and Home also leaves the Movies/TV/Anime tab).
  document.addEventListener("click", function (e) {
    var btn = e.target && e.target.closest ? e.target.closest(".sidebar-btn.active") : null;
    if (!btn) return;
    var label = (btn.getAttribute("aria-label") || btn.textContent || "").trim();
    if (/^Home$/i.test(label)) {
      try { window.dispatchEvent(new CustomEvent("streambert:home")); } catch (err) {}
    }
    var main = document.querySelector(".main");
    if (!main) return;
    if (main.scrollTo) main.scrollTo({ top: 0, behavior: "smooth" });
    else main.scrollTop = 0;
  });

  // ---------------------------------------------------------------------
  // Electron <webview> compatibility
  // ---------------------------------------------------------------------
  // Chromium refuses custom element names without a dash, so we decorate the
  // unknown <webview> elements React creates. Instead of loading the embed
  // page in-place (which many hosters forbid in iframes), tapping the
  // placeholder launches the native full-screen player Activity.
  function decorate(el) {
    if (!el || el.__sbDecorated) return;
    el.__sbDecorated = true;

    if (!el.executeJavaScript) {
      el.executeJavaScript = function () { return Promise.resolve(null); };
      el.getWebContentsId = function () { return 1; };
      el.loadURL = function (u) { el.setAttribute("src", u); };
      el.getURL = function () { return el.getAttribute("src") || ""; };
      el.reload = function () {};
      el.stop = function () {};
      el.canGoBack = function () { return false; };
    }

    var overlay = document.createElement("div");
    overlay.className = "sb-mobile-player-overlay";
    overlay.innerHTML =
      '<div class="sb-mobile-player-play">&#9654;</div>' +
      '<div class="sb-mobile-player-caption">Tap to open player</div>' +
      '<div class="sb-mobile-player-hint">Change the source with the chip at the top left</div>';
    overlay.addEventListener("click", function (e) {
      e.preventDefault();
      e.stopPropagation();
      launchFrom(el);
    });
    el.appendChild(overlay);

    var lastSrc = null;
    function refresh() {
      var src = el.getAttribute("src") || "";
      if (src === lastSrc) return;
      lastSrc = src;

      if (!isHttp(src)) {
        overlay.style.display = "none";
        return;
      }
      overlay.style.display = "flex";

      // Let the app clear its loading spinner.
      setTimeout(function () {
        try { el.dispatchEvent(new Event("dom-ready")); } catch (e) {}
        try { el.dispatchEvent(new Event("did-finish-load")); } catch (e) {}
      }, 50);

      // Do not launch automatically: the placeholder keeps the app's source
      // chip reachable so the source can be changed before playing.
    }

    new MutationObserver(refresh).observe(el, {
      attributes: true,
      attributeFilter: ["src"],
    });
    refresh();
  }

  // The player title doubles as the download file name, so prefer the real
  // movie/episode name over the generic document title.
  function mediaTitle() {
    try {
      var name = "";
      var titleEl = document.querySelector(".detail-title");
      if (titleEl && titleEl.textContent) name = titleEl.textContent.trim();
      if (!name) {
        var heading = document.querySelector("h1");
        if (heading && heading.textContent) name = heading.textContent.trim();
      }
      if (!name) name = (document.title || "").trim();
      if (!name) name = "Streambert";
      var ep = document.querySelector(".episode-card.playing .episode-num");
      if (ep && ep.textContent && ep.textContent.trim()) {
        name += " " + ep.textContent.trim();
      }
      return name;
    } catch (e) {
      return "Streambert";
    }
  }

  function launchFrom(el) {
    var src = el.getAttribute("src") || "";
    if (!isHttp(src)) return;
    var ua = el.getAttribute("useragent") || CLEAN_UA;
    launchPlayer(src, mediaTitle(), ua);
  }

  function scan() {
    var nodes = document.querySelectorAll("webview");
    for (var i = 0; i < nodes.length; i++) decorate(nodes[i]);
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", scan);
  } else {
    scan();
  }
  new MutationObserver(scan).observe(document.documentElement, {
    childList: true,
    subtree: true,
  });
})();
