package com.streambert.mobile;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Lists the video files Streambert has saved to Download/Streambert and maps
 * them to the shape the renderer's Downloads page expects.
 *
 * <p>Android 10+ uses MediaStore so the app can read and delete its own files
 * without storage permissions. Older releases scan the public folder.</p>
 */
final class DownloadIndex {

    static final String DIR_NAME = "Streambert";

    private static final String[] VIDEO_EXTENSIONS = {
            ".mp4", ".m2ts", ".ts", ".mkv", ".webm", ".m4v", ".mov"
    };

    private DownloadIndex() {
    }

    static final class Entry {
        String id;
        String name;
        String path;
        long bytes;
        long addedAt;
        String mime;
    }

    // ------------------------------------------------------------------
    // Listing
    // ------------------------------------------------------------------

    static List<Entry> list(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return MediaStoreList.list(ctx);
        }
        return listFromFolder();
    }

    static String listJson(Context ctx) {
        JSONArray array = new JSONArray();
        for (Entry entry : list(ctx)) {
            try {
                JSONObject item = new JSONObject();
                item.put("id", entry.id);
                item.put("name", entry.name);
                item.put("filePath", entry.path);
                item.put("size", formatSize(entry.bytes));
                item.put("sizeBytes", entry.bytes);
                item.put("status", "completed");
                item.put("completedAt", entry.addedAt);
                item.put("mediaType", "local");
                array.put(item);
            } catch (Exception ignored) {
            }
        }
        return array.toString();
    }

    /**
     * API 29+ implementation kept in a nested class so older devices never
     * need to resolve MediaStore.Downloads.
     */
    private static final class MediaStoreList {

        static List<Entry> list(Context ctx) {
            List<Entry> entries = new ArrayList<>();
            Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            String[] projection = {
                    MediaStore.Downloads._ID,
                    MediaStore.Downloads.DISPLAY_NAME,
                    MediaStore.Downloads.SIZE,
                    MediaStore.Downloads.DATE_ADDED,
                    MediaStore.Downloads.MIME_TYPE
            };
            String selection = MediaStore.Downloads.RELATIVE_PATH + " LIKE ?";
            String[] args = {
                    Environment.DIRECTORY_DOWNLOADS + "/" + DIR_NAME + "%"
            };

            Cursor cursor = null;
            try {
                ContentResolver resolver = ctx.getContentResolver();
                cursor = resolver.query(collection, projection, selection, args,
                        MediaStore.Downloads.DATE_ADDED + " DESC");
                if (cursor == null) return entries;

                int idCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID);
                int nameCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME);
                int sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.SIZE);
                int dateCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DATE_ADDED);
                int mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.MIME_TYPE);

                while (cursor.moveToNext()) {
                    String fileName = cursor.getString(nameCol);
                    if (!isVideoName(fileName)) continue;

                    Entry entry = new Entry();
                    long mediaId = cursor.getLong(idCol);
                    entry.id = "android:" + mediaId;
                    entry.path = ContentUris.withAppendedId(collection, mediaId).toString();
                    entry.name = displayName(fileName);
                    entry.bytes = cursor.getLong(sizeCol);
                    entry.addedAt = cursor.getLong(dateCol) * 1000L;
                    entry.mime = cursor.getString(mimeCol);
                    entries.add(entry);
                }
            } catch (Exception ignored) {
            } finally {
                if (cursor != null) {
                    try {
                        cursor.close();
                    } catch (Exception ignored) {
                    }
                }
            }
            return entries;
        }
    }

    private static List<Entry> listFromFolder() {
        List<Entry> entries = new ArrayList<>();
        File[] files = folder().listFiles();
        if (files == null) return entries;

        for (File file : files) {
            if (!file.isFile() || !isVideoName(file.getName())) continue;
            Entry entry = new Entry();
            entry.path = file.getAbsolutePath();
            entry.id = "file:" + entry.path;
            entry.name = displayName(file.getName());
            entry.bytes = file.length();
            entry.addedAt = file.lastModified();
            entry.mime = mimeForName(file.getName());
            entries.add(entry);
        }
        Collections.sort(entries, (a, b) -> Long.compare(b.addedAt, a.addedAt));
        return entries;
    }

    static File folder() {
        return new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), DIR_NAME);
    }

    // ------------------------------------------------------------------
    // File operations
    // ------------------------------------------------------------------

    static boolean exists(Context ctx, String path) {
        if (path == null || path.isEmpty()) return false;
        if (path.startsWith("content://")) {
            Cursor cursor = null;
            try {
                cursor = ctx.getContentResolver().query(Uri.parse(path),
                        new String[]{"_id"}, null, null, null);
                return cursor != null && cursor.moveToFirst();
            } catch (Exception ignored) {
                return false;
            } finally {
                if (cursor != null) {
                    try {
                        cursor.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        return new File(path).isFile();
    }

    static boolean delete(Context ctx, String path) {
        if (path == null || path.isEmpty()) return true;
        try {
            if (path.startsWith("content://")) {
                return ctx.getContentResolver().delete(Uri.parse(path), null, null) > 0;
            }
            File file = new File(path);
            return !file.exists() || file.delete();
        } catch (Exception ignored) {
            return false;
        }
    }

    static long totalBytes(Context ctx) {
        long total = 0L;
        for (Entry entry : list(ctx)) {
            if (entry.bytes > 0) total += entry.bytes;
        }
        return total;
    }

    static Uri uriFor(Context ctx, String path) {
        if (path == null || path.isEmpty()) return null;
        if (path.startsWith("content://")) return Uri.parse(path);

        File file = new File(path);
        if (!file.exists()) return null;

        // Ask MediaStore for a content:// URI; file:// URIs are rejected on
        // Android 7+ when handed to another app.
        Cursor cursor = null;
        try {
            Uri collection = MediaStore.Files.getContentUri("external");
            String[] projection = {MediaStore.Files.FileColumns._ID};
            String selection = MediaStore.Files.FileColumns.DATA + "=?";
            cursor = ctx.getContentResolver().query(collection, projection,
                    selection, new String[]{path}, null);
            if (cursor != null && cursor.moveToFirst()) {
                return ContentUris.withAppendedId(collection, cursor.getLong(0));
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Exception ignored) {
                }
            }
        }
        return Uri.fromFile(file);
    }

    static String mimeForPath(String path) {
        if (path == null) return "video/*";
        if (path.startsWith("content://")) return "video/*";
        return mimeForName(path);
    }

    static String mimeForName(String fileName) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.US);
        if (name.endsWith(".mp4") || name.endsWith(".m4v")) return "video/mp4";
        if (name.endsWith(".mkv")) return "video/x-matroska";
        if (name.endsWith(".webm")) return "video/webm";
        if (name.endsWith(".mov")) return "video/quicktime";
        return "video/mp2t";
    }

    // ------------------------------------------------------------------
    // Naming helpers
    // ------------------------------------------------------------------

    static boolean isVideoName(String fileName) {
        if (fileName == null || fileName.isEmpty()) return false;
        String name = fileName.toLowerCase(Locale.US);
        if (name.startsWith(".") || name.endsWith(".part")) return false;
        for (String extension : VIDEO_EXTENSIONS) {
            if (name.endsWith(extension)) return true;
        }
        return false;
    }

    /**
     * Turns "Movie Name-1793804663950.m2ts" into "Movie Name".
     */
    static String displayName(String fileName) {
        if (fileName == null || fileName.isEmpty()) return "Video";
        String name = fileName;
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        name = name.replaceFirst("-\\d{10,}$", "").trim();
        return name.isEmpty() ? fileName : name;
    }

    static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.US, "%.0f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb);
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
    }
}
