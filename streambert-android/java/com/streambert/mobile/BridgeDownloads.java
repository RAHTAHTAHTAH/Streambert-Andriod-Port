package com.streambert.mobile;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.view.View;
import android.widget.Toast;

import android.Manifest;
import android.content.pm.PackageManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

public class BridgeDownloads {

    private final MainActivity activity;

    public BridgeDownloads(MainActivity activity) {
        this.activity = activity;
    }

    @android.webkit.JavascriptInterface
    public String listDownloads() {
        try {
            return DownloadIndex.listJson(activity);
        } catch (Exception e) {
            return "[]";
        }
    }

    @android.webkit.JavascriptInterface
    public boolean deleteDownload(String filePath) {
        try {
            return DownloadIndex.delete(activity, filePath);
        } catch (Exception e) {
            return false;
        }
    }

    @android.webkit.JavascriptInterface
    public boolean deleteAllDownloads() {
        boolean ok = true;
        try {
            for (DownloadIndex.Entry entry : DownloadIndex.list(activity)) {
                if (!DownloadIndex.delete(activity, entry.path)) ok = false;
            }
        } catch (Exception e) {
            ok = false;
        }
        return ok;
    }

    @android.webkit.JavascriptInterface
    public long getDownloadsSize() {
        try {
            return DownloadIndex.totalBytes(activity);
        } catch (Exception e) {
            return 0L;
        }
    }

    @android.webkit.JavascriptInterface
    public boolean fileExists(String path) {
        try {
            return DownloadIndex.exists(activity, path);
        } catch (Exception e) {
            return false;
        }
    }

    @android.webkit.JavascriptInterface
    public void shareText(final String subject, final String text) {
        activity.runOnUiThread(() -> {
            try {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                if (subject != null) i.putExtra(Intent.EXTRA_SUBJECT, subject);
                if (text != null) i.putExtra(Intent.EXTRA_TEXT, text);
                activity.startActivity(Intent.createChooser(i, "Export backup"));
            } catch (Exception e) {
                Toast.makeText(activity, "Could not share file",
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    @android.webkit.JavascriptInterface
    public boolean openPath(String path) {
        Uri uri;
        try {
            uri = DownloadIndex.uriFor(activity, path);
        } catch (Exception e) {
            uri = null;
        }
        if (uri == null) {
            activity.runOnUiThread(() -> Toast.makeText(activity,
                    "File not found", Toast.LENGTH_SHORT).show());
            return false;
        }
        final Uri finalUri = uri;
        final String finalMime = DownloadIndex.mimeForPath(path);

        activity.runOnUiThread(() -> {
            try {
                Intent i = new Intent(activity, VideoPlayerActivity.class);
                i.putExtra(VideoPlayerActivity.EXTRA_URI, finalUri.toString());
                i.putExtra(VideoPlayerActivity.EXTRA_TITLE, path);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(i);
            } catch (Exception e) {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(finalUri, finalMime);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    activity.startActivity(intent);
                } catch (Exception ex) {
                    Toast.makeText(activity,
                            "No video player found", Toast.LENGTH_LONG).show();
                }
            }
        });
        return true;
    }

    @android.webkit.JavascriptInterface
    public void showInFolder() {
        activity.runOnUiThread(() -> {
            try {
                Intent intent = new Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(intent);
            } catch (Exception e) {
                Toast.makeText(activity,
                        "Saved in Download/Streambert", Toast.LENGTH_LONG).show();
            }
        });
    }

    @android.webkit.JavascriptInterface
    public String downloadCurrent(String expectedTitle) {
        SharedPreferences prefs = activity.getSharedPreferences(
                PlayerActivity.PREFS_STREAM, Context.MODE_PRIVATE);
        String url = prefs.getString(PlayerActivity.KEY_URL, null);
        long capturedAt = prefs.getLong(PlayerActivity.KEY_AT, 0L);
        String storedTitle = prefs.getString(PlayerActivity.KEY_TITLE, "");

        if (url == null
                || System.currentTimeMillis() - capturedAt > 12L * 60L * 60L * 1000L) {
            return "{\"ok\":false,\"reason\":\"no_stream\"}";
        }
        if (expectedTitle != null && !expectedTitle.trim().isEmpty()
                && !normalizeTitle(storedTitle).equals(normalizeTitle(expectedTitle))) {
            return "{\"ok\":false,\"reason\":\"no_stream\"}";
        }

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                && activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            activity.runOnUiThread(() -> activity.requestPermissions(
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 44));
            return "{\"ok\":false,\"reason\":\"permission\"}";
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            activity.runOnUiThread(() -> activity.requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, 45));
        }

        boolean hls = prefs.getBoolean(PlayerActivity.KEY_HLS, false);
        String title = prefs.getString(PlayerActivity.KEY_TITLE, "Streambert");
        String userAgent = prefs.getString(PlayerActivity.KEY_UA, null);
        String referer = prefs.getString(PlayerActivity.KEY_REFERER, "");

        try {
            String wanted = normalizeTitle(sanitizeTitle(title));
            for (DownloadIndex.Entry entry : DownloadIndex.list(activity)) {
                if (normalizeTitle(entry.name).equals(wanted)) {
                    return "{\"ok\":false,\"reason\":\"exists\"}";
                }
            }
        } catch (Exception ignored) {
        }

        try {
            if (hls) {
                Intent service = new Intent(activity, DownloadService.class);
                service.putExtra(DownloadService.EXTRA_URL, url);
                service.putExtra(DownloadService.EXTRA_UA, userAgent);
                service.putExtra(DownloadService.EXTRA_TITLE, title);
                service.putExtra(DownloadService.EXTRA_REFERER, referer);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    activity.startForegroundService(service);
                } else {
                    activity.startService(service);
                }
            } else {
                String name = sanitizeTitle(title) + "-"
                        + System.currentTimeMillis() + ".mp4";
                android.app.DownloadManager.Request request =
                        new android.app.DownloadManager.Request(Uri.parse(url));
                request.setTitle(title);
                request.setDescription("Downloading with Streambert");
                request.setNotificationVisibility(
                        android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS, "Streambert/" + name);
                if (userAgent != null) {
                    request.addRequestHeader("User-Agent", userAgent);
                }
                if (referer != null && !referer.isEmpty()) {
                    request.addRequestHeader("Referer", referer);
                }
                android.app.DownloadManager manager =
                        (android.app.DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
                if (manager == null) {
                    return "{\"ok\":false,\"reason\":\"unavailable\"}";
                }
                manager.enqueue(request);
            }
            return "{\"ok\":true}";
        } catch (Exception e) {
            return "{\"ok\":false,\"reason\":\"error\"}";
        }
    }

    @android.webkit.JavascriptInterface
    public String startSeasonDownload(final String json) {
        if (json == null || json.trim().isEmpty()) {
            return "{\"ok\":false,\"reason\":\"empty\"}";
        }

        String show = "";
        int season = 1;
        int count = 0;
        try {
            JSONObject root = new JSONObject(json);
            show = root.optString("show", "");
            season = root.optInt("season", 1);
            JSONArray episodes = root.optJSONArray("episodes");
            count = episodes == null ? 0 : episodes.length();
        } catch (Exception e) {
            return "{\"ok\":false,\"reason\":\"bad_args\"}";
        }
        if (count == 0) return "{\"ok\":false,\"reason\":\"empty\"}";

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                && activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            activity.runOnUiThread(() -> activity.requestPermissions(
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 46));
            return "{\"ok\":false,\"reason\":\"permission\"}";
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            activity.runOnUiThread(() -> activity.requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, 47));
        }

        final String payload = json;
        final int episodeCount = count;
        String label = show.trim().isEmpty()
                ? "Season " + season
                : show.trim() + " \u2013 Season " + season;
        final String message = "Queue " + episodeCount
                + (episodeCount == 1 ? " episode" : " episodes")
                + " of " + label + "?\n\n"
                + "Episodes download one at a time in the background. "
                + "This can use a lot of mobile data.";

        activity.runOnUiThread(() -> {
            try {
                new AlertDialog.Builder(activity)
                        .setTitle("Download season")
                        .setMessage(message)
                        .setPositiveButton("Download", (dialog, which) -> {
                            Intent intent = new Intent(activity, PlayerActivity.class);
                            intent.putExtra(PlayerActivity.EXTRA_SEASON_QUEUE, payload);
                            try {
                                activity.startActivity(intent);
                            } catch (Exception e) {
                                Toast.makeText(activity,
                                        "Could not open the season downloader",
                                        Toast.LENGTH_SHORT).show();
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            } catch (Exception e) {
                Toast.makeText(activity,
                        "Could not start the season download", Toast.LENGTH_SHORT).show();
            }
        });

        return "{\"ok\":true,\"count\":" + count + "}";
    }

    private static String normalizeTitle(String value) {
        if (value == null) return "";
        String text = value.trim().toLowerCase(Locale.US);
        text = text.replaceAll("\\.(mp4|m4ts|ts|m3u8|webm|mkv|mov|avi)$", "");
        text = text.replaceAll("-\\d{10,}$", "");
        text = text.replaceAll("[^a-z0-9]+", " ");
        return text.trim().replaceAll("\\s+", " ");
    }

    private static String sanitizeTitle(String value) {
        String name = value == null ? "video" : value;
        name = name.replaceAll("[\\\\/:*?\"<>|]", " ").trim();
        if (name.isEmpty()) name = "video";
        if (name.length() > 80) name = name.substring(0, 80);
        return name;
    }
}
