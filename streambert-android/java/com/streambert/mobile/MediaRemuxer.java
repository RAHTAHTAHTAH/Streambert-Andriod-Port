package com.streambert.mobile;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.IOException;

/**
 * Remuxes a downloaded media file into a standard, universally seekable MP4
 * using Android's built-in {@link MediaExtractor} + {@link MediaMuxer}.
 *
 * <p>No external libraries are required. The remux copies every track and
 * sample verbatim, only rewriting the container, so audio/video sync and
 * quality are preserved. The result is a fast-start MP4 that any player can
 * seek in.</p>
 */
final class MediaRemuxer {

    private static final String TAG = "StreambertRemux";

    private MediaRemuxer() {
    }

    /**
     * Remuxes the file at {@code inputUri} into a new file at {@code outputUri}.
     * Both URIs must be readable/writable by the caller.
     *
     * @return {@code true} on success, {@code false} if extraction or muxing
     *         failed (caller should keep the original file).
     */
    static boolean remux(ContentResolver resolver, Uri inputUri, Uri outputUri) {
        MediaExtractor extractor = null;
        MediaMuxer muxer = null;
        ParcelFileDescriptor inPfd = null;
        ParcelFileDescriptor outPfd = null;
        try {
            inPfd = resolver.openFileDescriptor(inputUri, "r");
            outPfd = resolver.openFileDescriptor(outputUri, "w");
            if (inPfd == null || outPfd == null) return false;

            extractor = new MediaExtractor();
            extractor.setDataSource(inPfd.getFileDescriptor());

            int trackCount = extractor.getTrackCount();
            if (trackCount == 0) {
                Log.w(TAG, "No tracks found in source");
                return false;
            }

            muxer = new MediaMuxer(outPfd.getFileDescriptor(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            int[] trackMap = new int[trackCount];
            for (int i = 0; i < trackCount; i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                trackMap[i] = muxer.addTrack(format);
            }

            muxer.start();

            // Copy all samples, interleaved by presentation time.
            android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(1024 * 1024);
            int track = extractor.getSampleTrackIndex();
            while (track >= 0) {
                info.offset = 0;
                info.size = extractor.readSampleData(buffer, 0);
                if (info.size < 0) break;
                info.presentationTimeUs = extractor.getSampleTime();
                info.flags = extractor.getSampleFlags();
                muxer.writeSampleData(trackMap[track], buffer, info);
                extractor.advance();
                track = extractor.getSampleTrackIndex();
            }

            muxer.stop();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Remux failed", e);
            return false;
        } finally {
            if (muxer != null) {
                try { muxer.release(); } catch (Exception ignored) { }
            }
            if (extractor != null) {
                try { extractor.release(); } catch (Exception ignored) { }
            }
            closeQuietly(inPfd);
            closeQuietly(outPfd);
        }
    }

    /**
     * Remuxes a local file into a new .mp4 file. Used for pre-Android 10
     * where files are stored on disk.
     *
     * @return {@code true} on success.
     */
    static boolean remux(File inputFile, File outputFile) {
        MediaExtractor extractor = null;
        MediaMuxer muxer = null;
        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(inputFile.getAbsolutePath());

            int trackCount = extractor.getTrackCount();
            if (trackCount == 0) {
                Log.w(TAG, "No tracks found in source");
                return false;
            }

            muxer = new MediaMuxer(outputFile.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            int[] trackMap = new int[trackCount];
            for (int i = 0; i < trackCount; i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                trackMap[i] = muxer.addTrack(format);
            }

            muxer.start();

            android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(1024 * 1024);
            int track = extractor.getSampleTrackIndex();
            while (track >= 0) {
                info.offset = 0;
                info.size = extractor.readSampleData(buffer, 0);
                if (info.size < 0) break;
                info.presentationTimeUs = extractor.getSampleTime();
                info.flags = extractor.getSampleFlags();
                muxer.writeSampleData(trackMap[track], buffer, info);
                extractor.advance();
                track = extractor.getSampleTrackIndex();
            }

            muxer.stop();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Remux failed", e);
            return false;
        } finally {
            if (muxer != null) {
                try { muxer.release(); } catch (Exception ignored) { }
            }
            if (extractor != null) {
                try { extractor.release(); } catch (Exception ignored) { }
            }
        }
    }

    private static void closeQuietly(ParcelFileDescriptor pfd) {
        if (pfd == null) return;
        try {
            pfd.close();
        } catch (IOException ignored) {
        }
    }
}