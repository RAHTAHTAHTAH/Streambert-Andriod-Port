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
import java.util.ArrayDeque;

/**
 * Foreground service that saves HLS streams to Download/Streambert.
 *
 * <p>Downloads continue while the player is closed, with progress shown in a
 * notification. Files land in the public Downloads collection via MediaStore
 * on Android 10+, and directly in the Downloads folder on older versions.</p>
 *
 * <p>More than one stream can be queued (used by "Download season"): jobs run
 * one after another on a single worker thread, so a season does not hammer the
 * hoster with concurrent requests and the notification always describes the
 * episode that is actually downloading.</p>
 */
public class DownloadService extends Service {

    static final String EXTRA_URL = "url";
    static final String EXTRA_UA = "ua";
    static final String EXTRA_TITLE = "title";
    static final String EXTRA_REFERER = "referer";
    /** Optional final base name (no timestamp, no extension). */
    static final String EXTRA_NAME = "name";
    /** Episode number and season size, only used for the notification label. */
    static final String EXTRA_INDEX = "index";
    static final String EXTRA_TOTAL = "total";

    /** Broadcast to the running UI so the Downloads page can refresh. */
    static final String ACTION_DOWNLOAD_FINISHED = "com.streambert.mobile.DOWNLOAD_FINISHED";

    private static final String ACTION_CANCEL = "com.streambert.mobile.CANCEL_DOWNLOAD";
    private static final String CHANNEL_ID = "streambert_downloads";
    private static final int PROGRESS_ID = 4101;
    private static final int DONE_ID = 4102;

    /** A single queued download. */
    private static final class Job {
        final String url;
        final String userAgent;
        final String title;
        final String referer;
        final String name;
        final int index;
        final int total;

        Job(String url, String userAgent, String title, String referer,
            String name, int index, int total) {
            this.url = url;
            this.userAgent = userAgent;
            this.title = title;
            this.referer = referer;
            this.name = name;
            this.index = index;
            this.total = total;
        }

        String position() {
            if (total <= 1 || index <= 0) return "";
            return "E" + index + " of " + total + "  •  ";
        }
    }

    private final ArrayDeque<Job> queue = new ArrayDeque<>();
    private volatile boolean cancelled = false;
    private Thread worker;
    private Job current;
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
            stopIfIdle();
            return START_NOT_STICKY;
        }
        if (ACTION_CANCEL.equals(intent.getAction())) {
            cancelAll();
            return START_NOT_STICKY;
        }

        final String url = intent.getStringExtra(EXTRA_URL);
        if (url == null) {
            stopIfIdle();
            return START_NOT_STICKY;
        }

        Job job = new Job(
                url,
                intent.getStringExtra(EXTRA_UA),
                intent.getStringExtra(EXTRA_TITLE),
                intent.getStringExtra(EXTRA_REFERER),
                intent.getStringExtra(EXTRA_NAME),
                intent.getIntExtra(EXTRA_INDEX, 0),
                intent.getIntExtra(EXTRA_TOTAL, 0));

        synchronized (queue) {
            queue.add(job);
        }
        synchronized (this) {
            if (worker == null) cancelled = false;
        }

        String label = job.position() + (job.name != null ? job.name : job.title);
        startForegroundCompat("Queued  •  " + label);
        ensureWorker();
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
    // Queue
    // ------------------------------------------------------------------

    private void ensureWorker() {
        synchronized (this) {
            if (worker != null) return;
            worker = new Thread(this::drainQueue, "hls-download");
            worker.start();
        }
    }

    private void drainQueue() {
        try {
            while (!cancelled) {
                Job job;
                synchronized (queue) {
                    job = queue.poll();
                }
                if (job == null) break;

                current = job;
                resetProgress();
                runDownload(job);
                current = null;
                broadcastFinished();
            }
        } finally {
            synchronized (this) {
                worker = null;
            }
        }

        boolean pending;
        synchronized (queue) {
            pending = !queue.isEmpty();
        }
        if (pending) {
            // A job arrived while the worker was finishing the previous one
            // (or the user cancelled an earlier one and then queued again).
            cancelled = false;
            ensureWorker();
            return;
        }

        stopForeground(true);
        stopSelf();
    }

    private void cancelAll() {
        cancelled = true;
        synchronized (queue) {
            queue.clear();
        }
        Thread running;
        synchronized (this) {
            running = worker;
        }
        if (running != null) running.interrupt();
        stopForeground(true);
        stopSelf();
    }

    private void stopIfIdle() {
        synchronized (this) {
            if (worker != null) return;
        }
        boolean pending;
        synchronized (queue) {
            pending = !queue.isEmpty();
        }
        if (!pending) {
            stopForeground(true);
            stopSelf();
        }
    }

    // ------------------------------------------------------------------
    // Download
    // ------------------------------------------------------------------

    private void runDownload(Job job) {
        String baseName = job.name != null && !job.name.trim().isEmpty()
                ? HlsDownloader.safeName(job.name)
                : HlsDownloader.safeName(job.title) + "-" + System.currentTimeMillis();
        String position = job.position();
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

            HlsDownloader downloader = new HlsDownloader(this, job.referer, job.userAgent,
                    new HlsDownloader.Listener() {
                        @Override
                        public void onProgress(long bytes, int done, int total, String label) {
                            int percent = total <= 0 ? 0 : (int) ((done * 100L) / total);
                            notifyProgress(position + label + "  •  " + formatBytes(bytes), percent);
                        }

                        @Override
                        public boolean isCancelled() {
                            return cancelled;
                        }
                    });

            HlsDownloader.Result result = downloader.download(job.url, out);
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

            notifyDone(position + finalName, result.bytes);
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
                notifyFailed(position + (e.getMessage() == null
                        ? "Download failed" : e.getMessage()));
            }
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

    private void startForegroundCompat(String text) {
        Notification notification = buildNotification(text, 0, true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(PROGRESS_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(PROGRESS_ID, notification);
        }
    }

    private void resetProgress() {
        lastNotifyAt = 0L;
        lastPercent = -1;
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
        Job job = current;
        String titleText = (job != null && job.total > 1)
                ? "Downloading season" : "Downloading video";

        Notification.Builder builder = baseBuilder()
                .setContentTitle(titleText)
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
