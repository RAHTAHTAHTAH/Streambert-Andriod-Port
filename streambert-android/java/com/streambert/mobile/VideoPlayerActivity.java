package com.streambert.mobile;

import android.app.Activity;
import android.content.Intent;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.MediaController;
import android.widget.TextView;
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
    private MediaPlayer mediaPlayer;
    private static final int REQ_SUBTITLE = 2001;

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

        // Subtitle picker button
        TextView subBtn = new TextView(this);
        subBtn.setText("CC");
        subBtn.setTextSize(14);
        subBtn.setTextColor(0xFFFFFFFF);
        subBtn.setPadding(16, 8, 16, 8);
        subBtn.setBackgroundColor(0x66000000);
        subBtn.setOnClickListener(v -> pickSubtitle());
        FrameLayout.LayoutParams subParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        subParams.gravity = android.view.Gravity.TOP | android.view.Gravity.END;
        subParams.topMargin = 16;
        subParams.rightMargin = 16;
        root.addView(subBtn, subParams);

        mediaController = new MediaController(this);
        mediaController.setAnchorView(videoView);
        videoView.setMediaController(mediaController);

        videoView.setVideoURI(uri);
        final String subtitlePathFinal = subtitlePath;
        videoView.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                mediaPlayer = mp;
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
    protected void onPause() {
        super.onPause();
        if (videoView != null && videoView.isPlaying()) videoView.pause();
    }

    private void pickSubtitle() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        String[] mimeTypes = {"text/vtt", "text/plain", "application/x-subrip",
                "text/srt", "application/octet-stream"};
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
        try {
            startActivityForResult(intent, REQ_SUBTITLE);
        } catch (Exception e) {
            Toast.makeText(this, "No file picker available", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_SUBTITLE || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null || mediaPlayer == null) return;
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
        }
        try {
            mediaPlayer.addTimedTextSource(uri.toString(), "application/x-subrip");
            MediaPlayer.TrackInfo[] tracks = mediaPlayer.getTrackInfo();
            if (tracks != null) {
                for (int i = 0; i < tracks.length; i++) {
                    if (tracks[i] != null
                            && tracks[i].getTrackType()
                                    == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT) {
                        mediaPlayer.selectTrack(i);
                        break;
                    }
                }
            }
            Toast.makeText(this, "Subtitle loaded", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.w("StreambertPlayer", "Could not attach subtitle", e);
            Toast.makeText(this, "Could not load subtitle", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onBackPressed() {
        if (videoView != null && videoView.isPlaying()) videoView.stopPlayback();
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }
}
