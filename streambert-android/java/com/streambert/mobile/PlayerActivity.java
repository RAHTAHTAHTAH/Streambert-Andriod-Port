package com.streambert.mobile;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Icon;
import android.util.Rational;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Full-screen native player. Loads the hoster's embed page in a plain WebView
 * (the Android equivalent of Streambert's Electron <webview>) and captures the
 * underlying HLS/MP4 URL so it can be downloaded or copied.
 */
public class PlayerActivity extends Activity {

    static final String EXTRA_URL = "url";
    static final String EXTRA_UA = "ua";
    static final String EXTRA_TITLE = "title";

    /**
     * Season download mode. The payload is a JSON object:
     * {"show":"...","season":2,"episodes":[{"n":1,"title":"...","url":"..."}]}
     */
    static final String EXTRA_SEASON_QUEUE = "season_queue";

    private static final long QUEUE_CAPTURE_TIMEOUT_MS = 25_000L;

    // Shared with MainActivity.Bridge so the renderer's download button can
    // start a download of the stream the player last captured.
    static final String PREFS_STREAM = "streambert_last_stream";
    static final String KEY_URL = "url";
    static final String KEY_HLS = "hls";
    static final String KEY_TITLE = "title";
    static final String KEY_UA = "ua";
    static final String KEY_REFERER = "referer";
    static final String KEY_AT = "at";

    private static final String TAG = "StreambertPlayer";
    private static final String CLEAN_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    private FrameLayout root;
    private WebView web;
    private LinearLayout topBar;
    private TextView downloadBtn;
    private View errorView;

    private String pageUrl;
    private String userAgent = CLEAN_UA;
    private String title = "Streambert";
    private String referer = "";

    private volatile String lastMediaUrl = null;
    private volatile boolean lastIsHls = false;

    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private WebChromeClient chrome;

    private static final String ACTION_PIP_CLOSE = "com.streambert.mobile.PIP_CLOSE";
    private static final String ACTION_PIP_PLAY = "com.streambert.mobile.PIP_PLAY";
    private static final String ACTION_PIP_PAUSE = "com.streambert.mobile.PIP_PAUSE";
    private BroadcastReceiver pipReceiver;

    // ---- "Download season" queue -------------------------------------
    private static final class Episode {
        final int number;
        final String name;
        final String url;

        Episode(int number, String name, String url) {
            this.number = number;
            this.name = name;
            this.url = url;
        }
    }

    private final List<Episode> seasonQueue = new ArrayList<>();
    private boolean queueMode = false;
    private int queueIndex = 0;
    private boolean queueItemCaptured = false;
    private int queuedCount = 0;
    private int skippedCount = 0;
    private String seasonShow = "";
    private int seasonNumber = 1;
    private FrameLayout queueCover;
    private TextView queueCoverTitle;
    private TextView queueCoverHint;
    private LinearLayout queueBar;
    private TextView queueBarLabel;

    private final Runnable queueTimeout = new Runnable() {
        @Override
        public void run() {
            if (!queueMode || queueItemCaptured) return;
            hideQueueCover();
            if (queueBarLabel != null) {
                queueBarLabel.setText("Tap play to start this episode");
            }
            toast("Tap play in the video to start this episode");
        }
    };

    /** The thin app bar is hidden by default so the hoster's page owns the screen. */
    private boolean topBarVisible = false;
    private final Runnable hideBarRunnable = new Runnable() {
        @Override
        public void run() {
            setTopBarVisible(false);
        }
    };

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        AdBlock.load(this);

        Intent intent = getIntent();
        pageUrl = intent.getStringExtra(EXTRA_URL);
        String uaExtra = intent.getStringExtra(EXTRA_UA);
        if (uaExtra != null && !uaExtra.trim().isEmpty()) userAgent = uaExtra.trim();
        String titleExtra = intent.getStringExtra(EXTRA_TITLE);
        if (titleExtra != null && !titleExtra.trim().isEmpty()) title = titleExtra.trim();

        parseSeasonQueue(intent);
        if (queueMode) {
            Episode first = seasonQueue.get(0);
            pageUrl = first.url;
            title = episodeTitle(first);
        }

        if (pageUrl == null) {
            finish();
            return;
        }

        Uri pageUri = Uri.parse(pageUrl);
        if (pageUri.getHost() != null) {
            referer = pageUri.getScheme() + "://" + pageUri.getHost() + "/";
        }

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        buildUi();

        if (queueMode) {
            buildQueueUi();
            if (downloadBtn != null) downloadBtn.setVisibility(View.GONE);
            if (topBar != null) topBar.setVisibility(View.GONE);
        }

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportMultipleWindows(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUserAgentString(userAgent);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (uri == null) return null;
                String url = uri.toString();

                if (!request.isForMainFrame() && AdBlock.isBlocked(url)) {
                    return new WebResourceResponse("text/plain", "utf-8",
                            new ByteArrayInputStream(new byte[0]));
                }
                captureMedia(url);
                return null;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleNavigation(request.getUrl());
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleNavigation(Uri.parse(url));
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                injectPopupBlocker();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                injectPopupBlocker();
                if (queueMode) mutePage();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        android.webkit.WebResourceError error) {
                if (request.isForMainFrame()) {
                    showError("Could not load this player page.\n\n" +
                            (error.getDescription() == null ? "" : error.getDescription()));
                }
            }

            @Override
            public void onReceivedSslError(WebView view, android.webkit.SslErrorHandler handler,
                                           android.net.http.SslError error) {
                handler.cancel();
                toast("Secure connection failed");
            }

            @Override
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                toast("Player crashed - please reopen it");
                finish();
                return true;
            }
        });

        web.setWebChromeClient(chrome = new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customViewCallback = callback;
                root.addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                setTopBarVisible(false);
                hideSystemUi();
            }

            @Override
            public void onHideCustomView() {
                if (customView == null) return;
                root.removeView(customView);
                customView = null;
                if (customViewCallback != null) {
                    customViewCallback.onCustomViewHidden();
                    customViewCallback = null;
                }
                setTopBarVisible(false);
                hideSystemUi();
            }

            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage message) {
                Log.d(TAG, message.message());
                return true;
            }
        });

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            // Keeping the inspector attached in release wastes memory while a
            // video page is decoding; only enable it for debuggable builds.
            WebView.setWebContentsDebuggingEnabled(
                    (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0);
        }

        // Register PiP action receiver.
        pipReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null || web == null) return;
                String action = intent.getAction();
                if (ACTION_PIP_CLOSE.equals(action)) {
                    finish();
                } else if (ACTION_PIP_PLAY.equals(action)) {
                    web.evaluateJavascript(
                            "(function(){var v=document.querySelector('video');if(v)v.play();})()", null);
                    updatePipParams();
                } else if (ACTION_PIP_PAUSE.equals(action)) {
                    web.evaluateJavascript(
                            "(function(){var v=document.querySelector('video');if(v)v.pause();})()", null);
                    updatePipParams();
                }
            }
        };
        IntentFilter pipFilter = new IntentFilter();
        pipFilter.addAction(ACTION_PIP_CLOSE);
        pipFilter.addAction(ACTION_PIP_PLAY);
        pipFilter.addAction(ACTION_PIP_PAUSE);
        registerReceiver(pipReceiver, pipFilter);

        hideSystemUi();

        if (queueMode) {
            // The queue drives the WebView itself; the thin player bar would
            // only get in the way.
            startQueueItem(0);
            return;
        }

        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", referer);
        web.loadUrl(pageUrl, headers);

        // Flash the bar once so the tap-to-show interaction is discoverable,
        // then let the hoster's page own the screen.
        setTopBarVisible(true);
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // A single tap toggles the thin app bar. Returning false keeps the
        // gesture going to the hoster's page (play/pause, their own controls).
        web.setOnTouchListener(new View.OnTouchListener() {
            private float downX, downY;
            private long downAt;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getX();
                        downY = event.getY();
                        downAt = System.currentTimeMillis();
                        break;
                    case MotionEvent.ACTION_UP:
                        float dx = Math.abs(event.getX() - downX);
                        float dy = Math.abs(event.getY() - downY);
                        if (dx < dp(14) && dy < dp(14)
                                && System.currentTimeMillis() - downAt < 450) {
                            setTopBarVisible(!topBarVisible);
                        }
                        break;
                    default:
                        break;
                }
                return false;
            }
        });

        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundColor(0xCC000000);
        int pad = dp(8);
        topBar.setPadding(pad, pad, pad, pad);

        TextView close = makeButton("\u2715");
        close.setTextSize(20);
        close.setContentDescription("Close player");
        close.setOnClickListener(v -> finish());

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(0xFFF0F0F0);
        titleView.setTextSize(14);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleView.setPadding(dp(12), 0, dp(12), 0);
        titleView.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        downloadBtn = makeButton("\u2913");
        downloadBtn.setTextSize(20);
        downloadBtn.setContentDescription("Download or copy stream link");
        downloadBtn.setVisibility(View.GONE);
        downloadBtn.setOnClickListener(v -> handleDownload());

        TextView pipBtn = makeButton("\u29C9");
        pipBtn.setTextSize(18);
        pipBtn.setContentDescription("Picture in Picture");
        pipBtn.setOnClickListener(v -> enterPipMode());

        TextView browserBtn = makeButton("\u2197");
        browserBtn.setTextSize(18);
        browserBtn.setContentDescription("Open in browser");
        browserBtn.setOnClickListener(v -> openInBrowser());

        topBar.addView(close, new LinearLayout.LayoutParams(dp(40), dp(40)));
        topBar.addView(titleView);
        topBar.addView(downloadBtn, new LinearLayout.LayoutParams(dp(40), dp(40)));
        topBar.addView(pipBtn, new LinearLayout.LayoutParams(dp(40), dp(40)));
        topBar.addView(browserBtn, new LinearLayout.LayoutParams(dp(40), dp(40)));

        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP);
        root.addView(topBar, topParams);

        topBar.setOnApplyWindowInsetsListener((v, insets) -> {
            int left = 0, top = 0, right = 0;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                left = bars.left;
                top = bars.top;
                right = bars.right;
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
            }
            v.setPadding(pad + left, pad + top, pad + right, pad);
            return insets;
        });

        setContentView(root);
    }

    private TextView makeButton(String text) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextColor(0xFFFFFFFF);
        b.setGravity(Gravity.CENTER);
        b.setBackgroundColor(0x22FFFFFF);
        return b;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /**
     * Shows or hides the thin app bar. It never covers a server fullscreen
     * video, and auto-hides a few seconds after being revealed.
     */
    private void setTopBarVisible(boolean visible) {
        if (topBar == null) return;
        if (customView != null) visible = false;
        topBarVisible = visible;
        topBar.removeCallbacks(hideBarRunnable);
        topBar.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (visible) topBar.postDelayed(hideBarRunnable, 3500);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    private boolean handleNavigation(Uri uri) {
        if (uri == null) return true;
        String scheme = uri.getScheme();
        if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
            if (AdBlock.isBlocked(uri.toString())) return true;
            return false;
        }
        return true;
    }

    private void captureMedia(String url) {
        if (url == null) return;
        String lower = url.toLowerCase(Locale.US);
        boolean isHls = lower.contains(".m3u8");
        boolean isVideo = lower.matches(".*\\.(mp4|m4v|webm|mkv)(\\?.*)?$");
        if (!isHls && !isVideo) return;

        if (queueMode) {
            synchronized (this) {
                if (url.equals(lastMediaUrl)) return;
                lastMediaUrl = url;
                lastIsHls = isHls;
            }
            onQueueMedia(url, isHls);
            return;
        }

        synchronized (this) {
            if (url.equals(lastMediaUrl)) return;
            lastMediaUrl = url;
            lastIsHls = isHls;
        }

        // Remember the stream so the in-app download button can reuse it after
        // the player has been closed.
        getSharedPreferences(PREFS_STREAM, MODE_PRIVATE).edit()
                .putString(KEY_URL, url)
                .putBoolean(KEY_HLS, isHls)
                .putString(KEY_TITLE, title)
                .putString(KEY_UA, userAgent)
                .putString(KEY_REFERER, referer)
                .putLong(KEY_AT, System.currentTimeMillis())
                .apply();

        runOnUiThread(() -> {
            if (downloadBtn != null) downloadBtn.setVisibility(View.VISIBLE);
        });
    }

    // ------------------------------------------------------------------
    // Download season
    // ------------------------------------------------------------------

    private void parseSeasonQueue(Intent intent) {
        String json = intent == null ? null : intent.getStringExtra(EXTRA_SEASON_QUEUE);
        if (json == null || json.trim().isEmpty()) return;
        try {
            JSONObject root = new JSONObject(json);
            seasonShow = root.optString("show", "");
            seasonNumber = root.optInt("season", 1);
            JSONArray episodes = root.optJSONArray("episodes");
            if (episodes != null) {
                for (int i = 0; i < episodes.length() && seasonQueue.size() < 100; i++) {
                    JSONObject item = episodes.optJSONObject(i);
                    if (item == null) continue;
                    String url = item.optString("url", "");
                    if (!isHttp(url)) continue;
                    seasonQueue.add(new Episode(
                            item.optInt("n", i + 1),
                            item.optString("title", ""),
                            url));
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not parse season queue", e);
        }
        queueMode = !seasonQueue.isEmpty();
    }

    private void buildQueueUi() {
        queueBar = new LinearLayout(this);
        queueBar.setOrientation(LinearLayout.HORIZONTAL);
        queueBar.setGravity(Gravity.CENTER_VERTICAL);
        queueBar.setBackgroundColor(0xE6000000);
        queueBar.setPadding(dp(14), dp(10), dp(14), dp(10));

        queueBarLabel = new TextView(this);
        queueBarLabel.setTextColor(0xFFF0F0F0);
        queueBarLabel.setTextSize(14);
        queueBarLabel.setSingleLine(true);
        queueBarLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        queueBarLabel.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView skip = makeButton("Skip");
        skip.setTextSize(13);
        skip.setPadding(dp(12), dp(6), dp(12), dp(6));
        skip.setOnClickListener(v -> skipQueueItem());

        TextView cancel = makeButton("Cancel");
        cancel.setTextSize(13);
        cancel.setPadding(dp(12), dp(6), dp(12), dp(6));
        cancel.setOnClickListener(v -> finishQueue(false));

        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionParams.setMargins(dp(8), 0, 0, 0);
        queueBar.addView(queueBarLabel);
        queueBar.addView(skip, actionParams);
        queueBar.addView(cancel, actionParams);

        queueCover = new FrameLayout(this);
        queueCover.setBackgroundColor(0xF2000000);
        queueCover.setClickable(true);
        queueCover.setFocusable(true);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setPadding(dp(32), dp(32), dp(32), dp(32));

        queueCoverTitle = new TextView(this);
        queueCoverTitle.setTextColor(0xFFFFFFFF);
        queueCoverTitle.setTextSize(22);
        queueCoverTitle.setGravity(Gravity.CENTER);

        queueCoverHint = new TextView(this);
        queueCoverHint.setTextColor(0xFFB8B8B8);
        queueCoverHint.setTextSize(14);
        queueCoverHint.setGravity(Gravity.CENTER);
        queueCoverHint.setPadding(0, dp(12), 0, 0);

        panel.addView(queueCoverTitle);
        panel.addView(queueCoverHint);
        queueCover.addView(panel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        root.addView(queueCover, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // Added last so it stays on top of the cover: Skip and Cancel must be
        // reachable while a stream is being resolved.
        root.addView(queueBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP));
    }

    private void startQueueItem(int index) {
        if (!queueMode) return;
        if (index >= seasonQueue.size()) {
            finishQueue(true);
            return;
        }

        queueIndex = index;
        synchronized (this) {
            queueItemCaptured = false;
            lastMediaUrl = null;
            lastIsHls = false;
        }

        Episode episode = seasonQueue.get(index);
        title = episodeTitle(episode);
        pageUrl = episode.url;
        Uri uri = Uri.parse(pageUrl);
        if (uri.getHost() != null) {
            referer = uri.getScheme() + "://" + uri.getHost() + "/";
        }

        String position = "Episode " + (index + 1) + " of " + seasonQueue.size();
        showQueueCover(position, "Finding the stream\u2026");
        if (queueBarLabel != null) {
            queueBarLabel.setText("Season " + seasonNumber + "  \u2022  " + position);
        }

        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", referer);
        web.loadUrl(pageUrl, headers);

        web.removeCallbacks(queueTimeout);
        web.postDelayed(queueTimeout, QUEUE_CAPTURE_TIMEOUT_MS);
        web.postDelayed(this::mutePage, 1500);
        web.postDelayed(this::mutePage, 4000);
    }

    /** Called from the WebView's request interceptor (background thread). */
    private void onQueueMedia(String url, boolean isHls) {
        synchronized (this) {
            if (queueItemCaptured) return;
            queueItemCaptured = true;
        }
        web.removeCallbacks(queueTimeout);

        final Episode episode = seasonQueue.get(queueIndex);
        enqueueEpisode(episode, url, isHls);

        runOnUiThread(() -> {
            if (queueCoverTitle != null) queueCoverTitle.setText("Queued");
            if (queueCoverHint != null) queueCoverHint.setText(episodeTitle(episode));
            if (queueBarLabel != null) {
                queueBarLabel.setText("Season " + seasonNumber + "  \u2022  E"
                        + episode.number + " queued");
            }
        });

        web.postDelayed(() -> {
            if (!isFinishing()) startQueueItem(queueIndex + 1);
        }, 900);
    }

    private void enqueueEpisode(Episode episode, String url, boolean hls) {
        String name = episodeTitle(episode);
        if (alreadyDownloaded(name)) {
            skippedCount++;
            return;
        }

        if (hls) {
            Intent service = new Intent(this, DownloadService.class);
            service.putExtra(DownloadService.EXTRA_URL, url);
            service.putExtra(DownloadService.EXTRA_UA, userAgent);
            service.putExtra(DownloadService.EXTRA_TITLE, name);
            service.putExtra(DownloadService.EXTRA_NAME, name);
            service.putExtra(DownloadService.EXTRA_REFERER, referer);
            service.putExtra(DownloadService.EXTRA_INDEX, episode.number);
            service.putExtra(DownloadService.EXTRA_TOTAL, seasonQueue.size());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(service);
            } else {
                startService(service);
            }
            queuedCount++;
            return;
        }

        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setTitle(name);
            request.setDescription("Downloading with Streambert");
            request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                    "Streambert/" + name + ".mp4");
            request.addRequestHeader("User-Agent", userAgent);
            if (referer != null && !referer.isEmpty()) {
                request.addRequestHeader("Referer", referer);
            }
            DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (manager != null) {
                manager.enqueue(request);
                queuedCount++;
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not queue direct video download", e);
        }
    }

    private void skipQueueItem() {
        if (!queueMode) return;
        synchronized (this) {
            if (queueItemCaptured) return;
        }
        web.removeCallbacks(queueTimeout);
        startQueueItem(queueIndex + 1);
    }

    private void finishQueue(boolean completed) {
        queueMode = false;
        if (web != null) web.removeCallbacks(queueTimeout);
        if (completed) {
            if (queuedCount == 0) {
                toast(skippedCount > 0
                        ? "All episodes in this season are already downloaded."
                        : "No streams found for this season.");
            } else {
                String message = "Queued " + queuedCount
                        + (queuedCount == 1 ? " episode" : " episodes");
                if (skippedCount > 0) {
                    message += " (" + skippedCount + " already downloaded)";
                }
                toast(message + " \u2014 downloads continue in the background.");
            }
        }
        finish();
    }

    private void showQueueCover(String titleText, String hintText) {
        if (queueCover == null) return;
        queueCover.setVisibility(View.VISIBLE);
        if (queueCoverTitle != null) queueCoverTitle.setText(titleText);
        if (queueCoverHint != null) queueCoverHint.setText(hintText);
    }

    private void hideQueueCover() {
        if (queueCover != null) queueCover.setVisibility(View.GONE);
    }

    private void mutePage() {
        if (web == null) return;
        web.evaluateJavascript(
                "(function(){try{var m=document.querySelectorAll('video,audio');" +
                "for(var i=0;i<m.length;i++){m[i].muted=true;m[i].volume=0;" +
                "m[i].removeAttribute('autoplay');}}catch(e){}})();", null);
    }

    private String episodeTitle(Episode episode) {
        String show = seasonShow == null ? "" : seasonShow.trim();
        String code = "S" + pad2(seasonNumber) + "E" + pad2(episode.number);
        String name = episode.name == null ? "" : episode.name.trim();
        if (name.isEmpty()) name = "Episode " + episode.number;
        return show.isEmpty() ? code + " - " + name : show + " - " + code + " - " + name;
    }

    private static String pad2(int value) {
        return value < 10 ? "0" + value : String.valueOf(value);
    }

    private boolean alreadyDownloaded(String baseName) {
        try {
            String wanted = normalizeName(baseName);
            for (DownloadIndex.Entry entry : DownloadIndex.list(this)) {
                if (entry.name != null
                        && normalizeName(stripExtension(entry.name)).equals(wanted)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static String normalizeName(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", " ").trim();
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static boolean isHttp(String url) {
        return url != null && url.length() > 4
                && (url.regionMatches(true, 0, "http://", 0, 7)
                || url.regionMatches(true, 0, "https://", 0, 8));
    }

    private void handleDownload() {
        if (queueMode) return;
        String url = lastMediaUrl;
        if (url == null) {
            toast("No stream detected yet - start playback first.");
            return;
        }

        if (lastIsHls) {
            startHlsDownload(url);
            return;
        }

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 42);
            toast("Allow storage access, then tap download again.");
            return;
        }

        try {
            String name = sanitize(title) + "-" + System.currentTimeMillis() + ".mp4";
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setTitle(title);
            request.setDescription("Downloading with Streambert");
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                    "Streambert/" + name);
            request.addRequestHeader("User-Agent", userAgent);
            if (!referer.isEmpty()) request.addRequestHeader("Referer", referer);

            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (dm == null) {
                toast("Download manager unavailable");
                return;
            }
            dm.enqueue(request);
            toast("Download started - check your notifications.");
        } catch (Exception e) {
            toast("Download failed: " + e.getMessage());
        }
    }

    /** HLS needs a segment downloader; run it in a foreground service. */
    private void startHlsDownload(String url) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 42);
            toast("Allow storage access, then tap download again.");
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 43);
        }

        Intent service = new Intent(this, DownloadService.class);
        service.putExtra(DownloadService.EXTRA_URL, url);
        service.putExtra(DownloadService.EXTRA_UA, userAgent);
        service.putExtra(DownloadService.EXTRA_TITLE, title);
        service.putExtra(DownloadService.EXTRA_REFERER, referer);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(service);
            } else {
                startService(service);
            }
            toast("Download started - progress is in the notification.");
        } catch (Exception e) {
            toast("Download failed: " + e.getMessage());
        }
    }

    private static String sanitize(String value) {
        String name = value == null ? "video" : value;
        name = name.replaceAll("[\\\\/:*?\"<>|]", " ").trim();
        if (name.isEmpty()) name = "video";
        if (name.length() > 80) name = name.substring(0, 80);
        return name;
    }

    /** Enter Picture-in-Picture mode (Android 8.0+). */
    private void enterPipMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            toast("Picture-in-Picture requires Android 8.0+");
            return;
        }
        try {
            // Make the hoster's video fill the WebView before entering PiP.
            if (web != null) {
                web.evaluateJavascript(
                        "(function(){try{var s=document.createElement('style');" +
                        "s.id='pip-style';s.textContent='video{width:100%!important;height:100%!important;object-fit:contain!important;position:fixed!important;top:0!important;left:0!important;}';" +
                        "if(!document.getElementById('pip-style'))document.head.appendChild(s);}catch(e){}})();",
                        null);
            }
            updatePipParams();
            enterPictureInPictureMode(buildPipParams());
        } catch (Exception e) {
            toast("Could not enter Picture-in-Picture");
        }
    }

    private PictureInPictureParams buildPipParams() {
        PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder()
                .setAspectRatio(new Rational(16, 9));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                List<RemoteAction> actions = new ArrayList<>();

                // Play
                Intent playIntent = new Intent(ACTION_PIP_PLAY);
                PendingIntent playPending = PendingIntent.getBroadcast(
                        this, 1, playIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                actions.add(new RemoteAction(
                        Icon.createWithResource(this, android.R.drawable.ic_media_play),
                        "Play", "Play", playPending));

                // Pause
                Intent pauseIntent = new Intent(ACTION_PIP_PAUSE);
                PendingIntent pausePending = PendingIntent.getBroadcast(
                        this, 2, pauseIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                actions.add(new RemoteAction(
                        Icon.createWithResource(this, android.R.drawable.ic_media_pause),
                        "Pause", "Pause", pausePending));

                // Close
                Intent closeIntent = new Intent(ACTION_PIP_CLOSE);
                PendingIntent closePending = PendingIntent.getBroadcast(
                        this, 3, closeIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                actions.add(new RemoteAction(
                        Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                        "Close", "Close", closePending));

                builder.setActions(actions);
            } catch (Exception ignored) {
            }
        }
        return builder.build();
    }

    private void updatePipParams() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        try {
            setPictureInPictureParams(buildPipParams());
        } catch (Exception ignored) {
        }
    }

    private void openInBrowser() {
        String url = lastMediaUrl != null ? lastMediaUrl : pageUrl;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            toast("No browser available");
        }
    }

    private void injectPopupBlocker() {
        if (web == null) return;
        web.evaluateJavascript(
                "(function(){try{window.open=function(){return null;};}catch(e){}})();",
                null);
    }

    private void showError(String message) {
        hideError();
        TextView tv = new TextView(this);
        tv.setText(message + "\n\nTap to retry");
        tv.setTextColor(0xFFF0F0F0);
        tv.setTextSize(16);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(32), dp(32), dp(32), dp(32));
        tv.setBackgroundColor(0xEE000000);
        tv.setOnClickListener(v -> {
            hideError();
            Map<String, String> headers = new HashMap<>();
            headers.put("Referer", referer);
            web.loadUrl(pageUrl, headers);
        });
        root.addView(tv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        errorView = tv;
        topBar.setVisibility(View.VISIBLE);
    }

    private void hideError() {
        if (errorView != null) {
            root.removeView(errorView);
            errorView = null;
        }
    }

    private void hideSystemUi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
        hideSystemUi();
    }

    @Override
    public void onUserLeaveHint() {
        super.onUserLeaveHint();
        // Auto-enter PiP when the user presses Home while playing.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !isFinishing()) {
            try {
                if (web != null) {
                    web.evaluateJavascript(
                            "(function(){try{var s=document.createElement('style');" +
                            "s.id='pip-style';s.textContent='video{width:100%!important;height:100%!important;object-fit:contain!important;position:fixed!important;top:0!important;left:0!important;}';" +
                            "if(!document.getElementById('pip-style'))document.head.appendChild(s);}catch(e){}})();",
                            null);
                }
                updatePipParams();
                enterPictureInPictureMode(buildPipParams());
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode,
                                             android.content.res.Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        if (isInPictureInPictureMode) {
            // Hide the top bar and any overlays in PiP.
            if (topBar != null) topBar.setVisibility(View.GONE);
            if (web != null) {
                web.setLayoutParams(new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                web.requestLayout();
                // Make the hoster's video fill the WebView in PiP.
                web.evaluateJavascript(
                        "(function(){try{var s=document.createElement('style');" +
                        "s.id='pip-style';s.textContent='video{width:100%!important;height:100%!important;object-fit:contain!important;}';" +
                        "if(!document.getElementById('pip-style'))document.head.appendChild(s);}catch(e){}})();",
                        null);
            }
            hideSystemUi();
        } else {
            // Restore when leaving PiP.
            if (topBar != null) setTopBarVisible(false);
            if (web != null) {
                web.evaluateJavascript(
                        "(function(){try{var s=document.getElementById('pip-style');if(s)s.remove();}catch(e){}})();",
                        null);
            }
            hideSystemUi();
        }
        updatePipParams();
    }

    @Override
    public void onBackPressed() {
        if (customView != null) {
            if (chrome != null) chrome.onHideCustomView();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (pipReceiver != null) {
            try { unregisterReceiver(pipReceiver); } catch (Exception ignored) {}
            pipReceiver = null;
        }
        if (customView != null) {
            root.removeView(customView);
            customView = null;
        }
        if (web != null) {
            web.stopLoading();
            web.loadUrl("about:blank");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
