package com.streambert.mobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.provider.MediaStore;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Foreground service that saves an HLS stream to Download/Streambert.
 *
 * <p>Downloads continue while the player is closed, with progress shown in a
 * notification. Files land in the public Downloads collection via MediaStore
 * on Android 10+, and directly in the Downloads folder on older versions.</p>
 */
public class DownloadService extends Service {

    static final String EXTRA_URL = "url";
    static final String EXTRA_UA = "ua";
    static final String EXTRA_TITLE = "title";
    static final String EXTRA_REFERER = "referer";

    /** Broadcast to the running UI so the Downloads page can refresh. */
    static final String ACTION_DOWNLOAD_FINISHED = "com.streambert.mobile.DOWNLOAD_FINISHED";

    private static final String ACTION_CANCEL = "com.streambert.mobile.CANCEL_DOWNLOAD";
    private static final String CHANNEL_ID = "streambert_downloads";
    private static final int PROGRESS_ID = 4101;
    private static final int DONE_ID = 4102;

    private volatile boolean cancelled = false;
    private Thread worker;
    private long lastNotifyAt = 0L;
    private int lastPercent = -1;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_CANCEL.equals(intent.getAction())) {
            cancelled = true;
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        final String url = intent.getStringExtra(EXTRA_URL);
        final String userAgent = intent.getStringExtra(EXTRA_UA);
        final String title = intent.getStringExtra(EXTRA_TITLE);
        final String referer = intent.getStringExtra(EXTRA_REFERER);

        if (url == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        Notification notification = buildNotification("Starting download…", 0, true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(PROGRESS_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(PROGRESS_ID, notification);
        }

        worker = new Thread(() -> runDownload(url, userAgent, title, referer), "hls-download");
        worker.start();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        cancelled = true;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ------------------------------------------------------------------
    // Download
    // ------------------------------------------------------------------

    private void runDownload(String url, String userAgent, String title, String referer) {
        String baseName = HlsDownloader.safeName(title) + "-" + System.currentTimeMillis();
        OutputStream out = null;
        Uri mediaUri = null;
        File tempFile = null;

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, baseName + ".part");
                values.put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/Streambert");
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                mediaUri = getContentResolver()
                        .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (mediaUri == null) throw new IOException("Could not create download entry");
                OutputStream raw = getContentResolver().openOutputStream(mediaUri);
                if (raw != null) out = new BufferedOutputStream(raw, 128 * 1024);
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS), "Streambert");
                if (!dir.exists() && !dir.mkdirs()) {
                    throw new IOException("Could not create Download/Streambert");
                }
                tempFile = new File(dir, baseName + ".part");
                out = new BufferedOutputStream(new FileOutputStream(tempFile));
            }
            if (out == null) throw new IOException("Could not open output file");

            HlsDownloader downloader = new HlsDownloader(this, referer, userAgent,
                    new HlsDownloader.Listener() {
                        @Override
                        public void onProgress(long bytes, int done, int total, String label) {
                            int percent = total <= 0 ? 0 : (int) ((done * 100L) / total);
                            notifyProgress(label + "  •  " + formatBytes(bytes), percent);
                        }

                        @Override
                        public boolean isCancelled() {
                            return cancelled;
                        }
                    });

            HlsDownloader.Result result = downloader.download(url, out);
            out.close();
            out = null;

            String extension = HlsDownloader.extensionFor(result.mp4);
            String mime = HlsDownloader.mimeFor(result.mp4);
            String finalName = baseName + extension;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, finalName);
                values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                getContentResolver().update(mediaUri, values, null, null);
            } else {
                File destination = new File(tempFile.getParentFile(), finalName);
                if (!tempFile.renameTo(destination)) {
                    throw new IOException("Could not finalise the download");
                }
                // Make the file visible to MediaStore so the Downloads page can
                // open it with a content:// URI on Android 7-9.
                MediaScannerConnection.scanFile(this,
                        new String[]{destination.getAbsolutePath()},
                        new String[]{mime}, null);
            }

            notifyDone(finalName, result.bytes);
        } catch (Exception e) {
            android.util.Log.e("StreambertDownload", "Download failed", e);
            closeQuietly(out);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mediaUri != null) {
                try {
                    getContentResolver().delete(mediaUri, null, null);
                } catch (Exception ignored) {
                }
            }
            if (tempFile != null) {
                //noinspection ResultOfMethodCallIgnored
                tempFile.delete();
            }
            if (!cancelled) {
                notifyFailed(e.getMessage() == null ? "Download failed" : e.getMessage());
            }
        } finally {
            broadcastFinished();
            stopForeground(true);
            stopSelf();
        }
    }

    private void broadcastFinished() {
        try {
            Intent done = new Intent(ACTION_DOWNLOAD_FINISHED);
            done.setPackage(getPackageName());
            sendBroadcast(done);
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------
    // Notifications
    // ------------------------------------------------------------------

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "Downloads", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Streambert video downloads");
        manager.createNotificationChannel(channel);
    }

    private void notifyProgress(String text, int percent) {
        long now = System.currentTimeMillis();
        if (now - lastNotifyAt < 600 && percent != 100) return;
        if (percent == lastPercent) return;
        lastNotifyAt = now;
        lastPercent = percent;

        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        manager.notify(PROGRESS_ID, buildNotification(text, percent, false));
    }

    private void notifyDone(String fileName, long bytes) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        Notification.Builder builder = baseBuilder()
                .setContentTitle("Download complete")
                .setContentText(fileName + "  •  " + formatBytes(bytes))
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setAutoCancel(true)
                .setOngoing(false);
        manager.notify(DONE_ID, builder.build());
    }

    private void notifyFailed(String message) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        Notification.Builder builder = baseBuilder()
                .setContentTitle("Download failed")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setAutoCancel(true)
                .setOngoing(false);
        manager.notify(DONE_ID, builder.build());
    }

    private Notification buildNotification(String text, int percent, boolean indeterminate) {
        Notification.Builder builder = baseBuilder()
                .setContentTitle("Downloading video")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(100, Math.max(0, percent), indeterminate);

        Intent cancel = new Intent(this, DownloadService.class).setAction(ACTION_CANCEL);
        PendingIntent cancelIntent = PendingIntent.getService(this, 1, cancel,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        builder.addAction(new Notification.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelIntent).build());
        return builder.build();
    }

    private Notification.Builder baseBuilder() {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openIntent = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return builder.setContentIntent(openIntent);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static void closeQuietly(OutputStream out) {
        if (out == null) return;
        try {
            out.close();
        } catch (IOException ignored) {
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(java.util.Locale.US, "%.0f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(java.util.Locale.US, "%.1f MB", mb);
        return String.format(java.util.Locale.US, "%.2f GB", mb / 1024.0);
    }
}
