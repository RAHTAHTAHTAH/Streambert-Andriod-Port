package com.streambert.mobile;

import android.app.Activity;
import android.content.Intent;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.MediaController;
import android.widget.Toast;
import android.widget.VideoView;

/**
 * Simple in-app player for downloaded files. Uses the framework MediaPlayer
 * (via VideoView), which supports the codecs the device can decode (including
 * HEVC) and avoids relying on whatever third-party player happens to be the
 * system default.
 */
public class VideoPlayerActivity extends Activity {
    public static final String EXTRA_URI = "uri";
    public static final String EXTRA_TITLE = "title";

    private VideoView videoView;
    private MediaController mediaController;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        String uriStr = intent == null ? null : intent.getStringExtra(EXTRA_URI);
        String title = intent == null ? null : intent.getStringExtra(EXTRA_TITLE);
        if (uriStr == null || uriStr.isEmpty()) {
            Toast.makeText(this, "No video to play", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        Uri uri;
        try {
            uri = Uri.parse(uriStr);
        } catch (Exception e) {
            Toast.makeText(this, "Could not open video", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(android.graphics.Color.BLACK);
        setContentView(root);

        videoView = new VideoView(this);
        root.addView(videoView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        mediaController = new MediaController(this);
        mediaController.setAnchorView(videoView);
        videoView.setMediaController(mediaController);

        videoView.setVideoURI(uri);
        videoView.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                mp.setLooping(false);
                videoView.start();
            }
        });
        videoView.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                Toast.makeText(VideoPlayerActivity.this,
                        "Could not play this video", Toast.LENGTH_LONG).show();
                return true;
            }
        });

        if (title != null && !title.isEmpty()) {
            setTitle(title);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (videoView != null && videoView.isPlaying()) videoView.pause();
    }

    @Override
    public void onBackPressed() {
        if (videoView != null && videoView.isPlaying()) videoView.stopPlayback();
        super.onBackPressed();
    }
}
