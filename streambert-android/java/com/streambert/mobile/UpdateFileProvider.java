package com.streambert.mobile;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Serves the downloaded update APK to the system installer.
 *
 * <p>Since Android 7 an app cannot hand a {@code file://} URI to another app,
 * and the project has no AndroidX dependency (so no FileProvider). This tiny
 * provider exposes exactly one folder - the updater's cache directory - and is
 * only readable while the installer holds the grant that
 * {@link UpdateManager} attaches to its install intent.</p>
 */
public class UpdateFileProvider extends ContentProvider {

    static final String AUTHORITY = "com.streambert.mobile.updates";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read-only provider");
        File file = resolve(uri);
        if (file == null) throw new FileNotFoundException("Unknown update file");
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        String[] columns = projection != null ? projection : new String[]{
                OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        File file = resolve(uri);
        if (file == null) return cursor;
        Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.SIZE.equals(columns[i])) {
                row[i] = file.length();
            } else if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) {
                row[i] = file.getName();
            }
        }
        cursor.addRow(row);
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    /** Maps content://.../Streambert-Mobile.apk to the cached update file. */
    private File resolve(Uri uri) {
        if (getContext() == null || uri == null) return null;
        String name = uri.getLastPathSegment();
        if (name == null || name.isEmpty()) return null;
        if (!name.matches("[A-Za-z0-9._-]+\\.apk")) return null;
        File dir = new File(getContext().getCacheDir(), "updates");
        File file = new File(dir, name);
        try {
            if (!file.getCanonicalPath().startsWith(dir.getCanonicalPath() + File.separator)) {
                return null;
            }
        } catch (Exception e) {
            return null;
        }
        return file.isFile() ? file : null;
    }
}
