package com.streambert.mobile;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

/**
 * Native Media3 (ExoPlayer) player for direct .m3u8 / .mp4 streams and
 * local downloaded files.  Provides hardware decoding, native subtitle
 * track switching, and touch gestures (brightness, volume, double-tap seek).
 */
public class ExoPlayerActivity extends Activity {

    public static final String EXTRA_URL = "url";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_IS_LOCAL = "isLocal";

    private static final long SEEK_MS = 10000L;

    private ExoPlayer player;
    private PlayerView playerView;

    private GestureDetector gestureDetector;
    private AudioManager audioManager;
    private int maxVolume;
    private float currentBrightness = -1f; // -1 = system default
    private long lastTapTime = 0;
    private int tapCount = 0;

    // Overlay for gesture feedback
    private TextView gestureOverlay;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        String url = intent == null ? null : intent.getStringExtra(EXTRA_URL);
        String title = intent == null ? null : intent.getStringExtra(EXTRA_TITLE);

        if (url == null || url.isEmpty()) {
            Toast.makeText(this, "No stream to play", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        setContentView(root);

        playerView = new PlayerView(this);
        playerView.setUseController(true);
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS);
        root.addView(playerView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // Gesture feedback overlay
        gestureOverlay = new TextView(this);
        gestureOverlay.setTextColor(0xFFFFFFFF);
        gestureOverlay.setTextSize(18);
        gestureOverlay.setPadding(24, 12, 24, 12);
        gestureOverlay.setBackgroundColor(0x88000000);
        gestureOverlay.setVisibility(View.GONE);
        FrameLayout.LayoutParams overlayParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        overlayParams.gravity = android.view.Gravity.CENTER;
        root.addView(gestureOverlay, overlayParams);

        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);

        MediaItem.Builder itemBuilder = new MediaItem.Builder()
                .setUri(Uri.parse(url));

        if (title != null && !title.isEmpty()) {
            itemBuilder.setMediaMetadata(
                    new androidx.media3.common.MediaMetadata.Builder()
                            .setTitle(title).build());
        }

        String lower = url.toLowerCase();
        if (lower.endsWith(".m3u8")) {
            itemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8);
        } else if (lower.endsWith(".mp4")) {
            itemBuilder.setMimeType(MimeTypes.VIDEO_MP4);
        } else if (lower.endsWith(".mpd")) {
            itemBuilder.setMimeType(MimeTypes.APPLICATION_MPD);
        }

        MediaItem mediaItem = itemBuilder.build();
        player.setMediaItem(mediaItem);
        player.prepare();
        player.play();

        if (title != null && !title.isEmpty()) {
            setTitle(title);
        }

        // Set up gesture detection
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);

        // Try to get current screen brightness
        try {
            currentBrightness = Settings.System.getInt(getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS) / 255f;
        } catch (Settings.SettingNotFoundException e) {
            currentBrightness = 0.5f;
        }

        gestureDetector = new GestureDetector(this, new GestureListener());
        playerView.setOnTouchListener((v, event) -> gestureDetector.onTouchEvent(event));
    }

    private class GestureListener extends GestureDetector.SimpleOnGestureListener {
        private float startY;
        private float startX;
        private boolean isVerticalSwipe = false;
        private boolean isLeftSide = false;
        private int startVolume = 0;
        private float startBrightness = 0f;

        @Override
        public boolean onDown(MotionEvent e) {
            startY = e.getY();
            startX = e.getX();
            isVerticalSwipe = false;
            isLeftSide = startX < playerView.getWidth() / 2f;
            startVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            startBrightness = currentBrightness < 0 ? 0.5f : currentBrightness;
            return true;
        }

        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
            if (e1 == null) return false;
            float deltaY = e2.getY() - e1.getY();
            float deltaX = Math.abs(e2.getX() - e1.getX());

            // Only treat as vertical swipe if vertical movement dominates
            if (!isVerticalSwipe && Math.abs(deltaY) > 30 && Math.abs(deltaY) > deltaX * 1.5) {
                isVerticalSwipe = true;
            }

            if (isVerticalSwipe) {
                float ratio = -deltaY / (playerView.getHeight() * 0.5f);
                if (isLeftSide) {
                    // Brightness
                    float newBrightness = Math.max(0.01f, Math.min(1f, startBrightness + ratio));
                    currentBrightness = newBrightness;
                    Window window = getWindow();
                    WindowManager.LayoutParams lp = window.getAttributes();
                    lp.screenBrightness = newBrightness;
                    window.setAttributes(lp);
                    showGestureOverlay("Brightness: " + (int) (newBrightness * 100) + "%");
                } else {
                    // Volume
                    int newVolume = Math.max(0, Math.min(maxVolume,
                            (int) (startVolume + ratio * maxVolume)));
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0);
                    showGestureOverlay("Volume: " + (int) ((newVolume * 100f) / maxVolume) + "%");
                }
                return true;
            }
            return false;
        }

        @Override
        public boolean onDoubleTap(MotionEvent e) {
            float x = e.getX();
            float width = playerView.getWidth();
            if (player == null) return true;

            if (x < width / 3f) {
                // Seek back 10s
                long pos = player.getCurrentPosition() - SEEK_MS;
                player.seekTo(Math.max(0, pos));
                showGestureOverlay("⏪ -10s");
            } else if (x > width * 2f / 3f) {
                // Seek forward 10s
                long pos = player.getCurrentPosition() + SEEK_MS;
                long dur = player.getDuration();
                player.seekTo(dur > 0 ? Math.min(dur, pos) : pos);
                showGestureOverlay("⏩ +10s");
            } else {
                // Center: toggle play/pause
                if (player.isPlaying()) {
                    player.pause();
                    showGestureOverlay("⏸ Paused");
                } else {
                    player.play();
                    showGestureOverlay("▶ Playing");
                }
            }
            return true;
        }

        @Override
        public boolean onSingleTapConfirmed(MotionEvent e) {
            // Let PlayerView handle single tap (show/hide controls)
            return false;
        }
    }

    private void showGestureOverlay(String text) {
        gestureOverlay.setText(text);
        gestureOverlay.setVisibility(View.VISIBLE);
        gestureOverlay.removeCallbacks(hideOverlayRunnable);
        gestureOverlay.postDelayed(hideOverlayRunnable, 800);
    }

    private final Runnable hideOverlayRunnable = new Runnable() {
        @Override
        public void run() {
            gestureOverlay.setVisibility(View.GONE);
        }
    };

    @Override
    protected void onStart() {
        super.onStart();
        if (player != null) player.play();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (player != null) player.pause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (player != null) {
            player.release();
            player = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (player != null) player.stop();
        super.onBackPressed();
    }
}