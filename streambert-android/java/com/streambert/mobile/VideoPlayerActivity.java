package com.streambert.mobile;

import android.app.Activity;
import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Icon;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.util.Rational;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.MediaController;
import android.widget.Toast;
import android.widget.VideoView;

import java.util.ArrayList;
import java.util.List;

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

    private static final String ACTION_PIP_PLAY = "com.streambert.mobile.PIP_PLAY";
    private static final String ACTION_PIP_PAUSE = "com.streambert.mobile.PIP_PAUSE";
    private BroadcastReceiver pipReceiver;

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
                updatePipParams();
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

        // Register PiP action receiver.
        pipReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null || videoView == null) return;
                String action = intent.getAction();
                if (ACTION_PIP_PLAY.equals(action)) {
                    videoView.start();
                    updatePipParams();
                } else if (ACTION_PIP_PAUSE.equals(action)) {
                    if (videoView.isPlaying()) videoView.pause();
                    updatePipParams();
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_PIP_PLAY);
        filter.addAction(ACTION_PIP_PAUSE);
        registerReceiver(pipReceiver, filter);

        if (title != null && !title.isEmpty()) {
            setTitle(title);
        }
    }

    @Override
    public void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && videoView != null && !isFinishing()) {
            try {
                updatePipParams();
                enterPictureInPictureMode(buildPipParams());
            } catch (Exception ignored) {
            }
        }
    }

    private PictureInPictureParams buildPipParams() {
        PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder()
                .setAspectRatio(new Rational(16, 9));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                boolean playing = videoView != null && videoView.isPlaying();
                List<RemoteAction> actions = new ArrayList<>();
                Intent intent = new Intent(playing ? ACTION_PIP_PAUSE : ACTION_PIP_PLAY);
                PendingIntent pending = PendingIntent.getBroadcast(
                        this, 0, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                RemoteAction action = new RemoteAction(
                        Icon.createWithResource(this,
                                playing ? android.R.drawable.ic_media_pause
                                        : android.R.drawable.ic_media_play),
                        playing ? "Pause" : "Play",
                        playing ? "Pause playback" : "Play playback",
                        pending);
                actions.add(action);
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

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode,
                                             android.content.res.Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        if (isInPictureInPictureMode) {
            // Ensure the VideoView fills the PiP window.
            if (videoView != null) {
                videoView.setLayoutParams(new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));
                videoView.requestLayout();
            }
            if (mediaController != null) mediaController.hide();
        } else {
            if (videoView != null) {
                videoView.setLayoutParams(new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));
                videoView.requestLayout();
            }
        }
        updatePipParams();
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

    @Override
    protected void onDestroy() {
        if (pipReceiver != null) {
            try { unregisterReceiver(pipReceiver); } catch (Exception ignored) {}
            pipReceiver = null;
        }
        super.onDestroy();
    }
}
