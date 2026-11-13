package com.streambert.mobile;

import android.content.ContentResolver;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Remuxes a downloaded media file into a standard, universally seekable MP4
 * using Android's built-in {@link MediaExtractor} + {@link MediaMuxer}.
 *
 * <p>No external libraries are required. The remux copies every track and
 * sample verbatim, only rewriting the container, so audio/video sync and
 * quality are preserved.</p>
 *
 * <p>The sample buffer grows to fit the largest sample: a fixed buffer silently
 * truncates large video keyframes, which produces an MP4 that exists on disk
 * but cannot be decoded. After muxing we re-open the result and require at
 * least one readable sample before reporting success, so a broken remux falls
 * back to the raw (still playable) download instead of replacing it.</p>
 */
final class MediaRemuxer {

    private static final String TAG = "StreambertRemux";
    private static final int INITIAL_BUFFER = 1024 * 1024;

    private MediaRemuxer() {
    }

    /**
     * Remuxes {@code inputUri} into {@code outputUri}. Both must be
     * readable/writable by the caller.
     *
     * @return {@code true} only when the output is a valid, non-empty MP4.
     */
    static boolean remux(ContentResolver resolver, Uri inputUri, Uri outputUri) {
        boolean ok = copySamples(resolver, inputUri, outputUri);
        if (!ok) return false;
        return isPlayable(resolver, outputUri);
    }

    private static boolean copySamples(ContentResolver resolver, Uri inputUri, Uri outputUri) {
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
            int added = 0;
            for (int i = 0; i < trackCount; i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                try {
                    trackMap[i] = muxer.addTrack(format);
                    added++;
                } catch (Exception e) {
                    trackMap[i] = -1;
                    Log.w(TAG, "Unsupported track " + i + " (" +
                            format.getString(MediaFormat.KEY_MIME) + ")", e);
                }
            }
            if (added == 0) {
                Log.w(TAG, "No supported tracks");
                return false;
            }

            muxer.start();

            android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();
            ByteBuffer buffer = ByteBuffer.allocate(INITIAL_BUFFER);
            int samplesWritten = 0;
            int track = extractor.getSampleTrackIndex();
            while (track >= 0) {
                if (trackMap[track] < 0) {
                    // Unsupported track: skip its samples.
                    extractor.advance();
                    track = extractor.getSampleTrackIndex();
                    continue;
                }

                long sampleSize = extractor.getSampleSize();
                if (sampleSize > buffer.capacity()) {
                    buffer = ByteBuffer.allocate((int) sampleSize);
                }

                info.offset = 0;
                info.size = extractor.readSampleData(buffer, 0);
                if (info.size < 0) break;
                info.presentationTimeUs = extractor.getSampleTime();
                info.flags = extractor.getSampleFlags();
                try {
                    muxer.writeSampleData(trackMap[track], buffer, info);
                    samplesWritten++;
                } catch (Exception e) {
                    Log.w(TAG, "writeSampleData failed for track " + track, e);
                }
                extractor.advance();
                track = extractor.getSampleTrackIndex();
            }

            if (samplesWritten == 0) {
                Log.w(TAG, "No samples written; remux failed");
                try { muxer.stop(); } catch (Exception ignored) { }
                return false;
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

    /** Confirms the muxed file has at least one track with a readable sample. */
    private static boolean isPlayable(ContentResolver resolver, Uri uri) {
        MediaExtractor check = null;
        ParcelFileDescriptor pfd = null;
        try {
            pfd = resolver.openFileDescriptor(uri, "r");
            if (pfd == null) return false;
            check = new MediaExtractor();
            check.setDataSource(pfd.getFileDescriptor());
            if (check.getTrackCount() == 0) return false;
            int track = check.getSampleTrackIndex();
            if (track < 0) return false;
            ByteBuffer probe = ByteBuffer.allocate(64 * 1024);
            int size = check.readSampleData(probe, 0);
            return size > 0;
        } catch (Exception e) {
            Log.w(TAG, "Playability check failed", e);
            return false;
        } finally {
            if (check != null) {
                try { check.release(); } catch (Exception ignored) { }
            }
            closeQuietly(pfd);
        }
    }

    /**
     * Remuxes a local file into a new .mp4 file. Used for pre-Android 10
     * where files are stored on disk.
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
            int added = 0;
            for (int i = 0; i < trackCount; i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                try {
                    trackMap[i] = muxer.addTrack(format);
                    added++;
                } catch (Exception e) {
                    trackMap[i] = -1;
                    Log.w(TAG, "Unsupported track " + i, e);
                }
            }
            if (added == 0) return false;

            muxer.start();

            android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();
            ByteBuffer buffer = ByteBuffer.allocate(INITIAL_BUFFER);
            int samplesWritten = 0;
            int track = extractor.getSampleTrackIndex();
            while (track >= 0) {
                if (trackMap[track] < 0) {
                    extractor.advance();
                    track = extractor.getSampleTrackIndex();
                    continue;
                }

                long sampleSize = extractor.getSampleSize();
                if (sampleSize > buffer.capacity()) {
                    buffer = ByteBuffer.allocate((int) sampleSize);
                }

                info.offset = 0;
                info.size = extractor.readSampleData(buffer, 0);
                if (info.size < 0) break;
                info.presentationTimeUs = extractor.getSampleTime();
                info.flags = extractor.getSampleFlags();
                try {
                    muxer.writeSampleData(trackMap[track], buffer, info);
                    samplesWritten++;
                } catch (Exception e) {
                    Log.w(TAG, "writeSampleData failed for track " + track, e);
                }
                extractor.advance();
                track = extractor.getSampleTrackIndex();
            }

            if (samplesWritten == 0) {
                Log.w(TAG, "No samples written; remux failed");
                try { muxer.stop(); } catch (Exception ignored) { }
                return false;
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