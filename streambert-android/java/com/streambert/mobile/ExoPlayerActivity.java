package com.streambert.mobile;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

/**
 * Native Media3 (ExoPlayer) player for direct .m3u8 / .mp4 streams and
 * local downloaded files.  Provides hardware decoding and native subtitle
 * track switching.
 */
public class ExoPlayerActivity extends Activity {

    public static final String EXTRA_URL = "url";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_IS_LOCAL = "isLocal";

    private ExoPlayer player;
    private PlayerView playerView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        String url = intent == null ? null : intent.getStringExtra(EXTRA_URL);
        String title = intent == null ? null : intent.getStringExtra(EXTRA_TITLE);
        boolean isLocal = intent != null && intent.getBooleanExtra(EXTRA_IS_LOCAL, false);

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

        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);

        MediaItem.Builder itemBuilder = new MediaItem.Builder()
                .setUri(Uri.parse(url));

        if (title != null && !title.isEmpty()) {
            itemBuilder.setMediaMetadata(
                    new androidx.media3.common.MediaMetadata.Builder()
                            .setTitle(title).build());
        }

        // Detect MIME type from URL
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
    }

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