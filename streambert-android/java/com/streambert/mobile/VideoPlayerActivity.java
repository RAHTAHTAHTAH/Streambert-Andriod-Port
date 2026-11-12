package com.streambert.mobile;

import android.app.Activity;
import android.content.Intent;
import android.app.PictureInPictureParams;
import android.media.MediaPlayer;
import android.net.Uri;
import android.util.Rational;
import android.os.Bundle;
import android.util.Log;
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
    public static final String EXTRA_SUBTITLE = "subtitle";

    private VideoView videoView;
    private MediaController mediaController;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        String uriStr = intent == null ? null : intent.getStringExtra(EXTRA_URI);
        String title = intent == null ? null : intent.getStringExtra(EXTRA_TITLE);
        String subtitlePath = intent == null ? null : intent.getStringExtra(EXTRA_SUBTITLE);
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
        final String subtitlePathFinal = subtitlePath;
        videoView.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                mp.setLooping(false);
                // Best-effort: attach an external subtitle file if provided.
                if (subtitlePathFinal != null && !subtitlePathFinal.isEmpty()) {
                    try {
                        // addTimedTextSource(String, String) takes a MIME type.
                        mp.addTimedTextSource(subtitlePathFinal, "application/x-subrip");
                        int trackIndex = -1;
                        MediaPlayer.TrackInfo[] tracks = mp.getTrackInfo();
                        if (tracks != null) {
                            for (int i = 0; i < tracks.length; i++) {
                                if (tracks[i] != null
                                        && tracks[i].getTrackType()
                                                == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT) {
                                    trackIndex = i;
                                    break;
                                }
                            }
                        }
                        if (trackIndex >= 0) mp.selectTrack(trackIndex);
                    } catch (Exception e) {
                        Log.w("StreambertPlayer", "Could not attach subtitle", e);
                    }
                }
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
    public void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
                && videoView != null && videoView.isPlaying() && !isFinishing()) {
            try {
                PictureInPictureParams params = new PictureInPictureParams.Builder()
                        .setAspectRatio(new Rational(16, 9))
                        .build();
                enterPictureInPictureMode(params);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Don't pause if in PiP mode.
        if (isInPictureInPictureMode()) return;
        if (videoView != null && videoView.isPlaying()) videoView.pause();
    }

    @Override
    public void onBackPressed() {
        if (videoView != null && videoView.isPlaying()) videoView.stopPlayback();
        super.onBackPressed();
    }
}
