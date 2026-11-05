package com.streambert.mobile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Merges a video-only fragmented MP4 (CMAF) rendition with a separate
 * audio-only rendition into one playable MP4.
 *
 * <p>HLS streams that use {@code #EXT-X-MEDIA:TYPE=AUDIO} keep the audio in its
 * own playlist. Downloading only the variant playlist therefore produces a
 * silent file. Both renditions are fragmented MP4, so the media fragments are
 * copied verbatim and only the track ids / fragment sequence numbers are
 * rewritten. That leaves every sample offset and timestamp untouched, which
 * keeps audio and video in sync.</p>
 */
final class Fmp4Muxer {

    /** The merged {@code ftyp + moov} header plus the ids used by fragments. */
    static final class Merged {
        final byte[] header;
        final int videoTrackId;
        final int audioTrackId;

        Merged(byte[] header, int videoTrackId, int audioTrackId) {
            this.header = header;
            this.videoTrackId = videoTrackId;
            this.audioTrackId = audioTrackId;
        }
    }

    private Fmp4Muxer() {
    }

    // ------------------------------------------------------------------
    // Init segment
    // ------------------------------------------------------------------

    static Merged mergeInits(byte[] videoInit, byte[] audioInit) throws IOException {
        int vMoov = findTopLevel(videoInit, "moov");
        int aMoov = findTopLevel(audioInit, "moov");
        if (vMoov < 0 || aMoov < 0) throw new IOException("init segment has no moov");

        int aTrak = findChild(audioInit, aMoov, "trak");
        if (aTrak < 0) throw new IOException("audio init has no trak");
        int aMvex = findChild(audioInit, aMoov, "mvex");
        int aTrex = aMvex < 0 ? -1 : findChild(audioInit, aMvex, "trex");
        if (aTrex < 0) throw new IOException("audio init is not fragmented");

        int vMvex = findChild(videoInit, vMoov, "mvex");
        if (vMvex < 0) throw new IOException("video init is not fragmented");
        int vMvhd = findChild(videoInit, vMoov, "mvhd");
        if (vMvhd < 0) throw new IOException("video init has no mvhd");

        int videoTrackId = 1;
        int vTrak = findChild(videoInit, vMoov, "trak");
        if (vTrak >= 0) videoTrackId = trackIdAt(videoInit, vTrak);
        int audioTrackId = videoTrackId + 1;

        // Audio trak, renumbered so it does not clash with the video track.
        byte[] audioTrak = copyRange(audioInit, aTrak, boxSize(audioInit, aTrak));
        int trakTkhd = findChild(audioTrak, 0, "tkhd");
        if (trakTkhd < 0) throw new IOException("audio trak has no tkhd");
        writeU32(audioTrak, trackIdOffset(audioTrak, trakTkhd), audioTrackId);

        // Audio trex, renumbered to match.
        byte[] audioTrex = copyRange(audioInit, aTrex, boxSize(audioInit, aTrex));
        writeU32(audioTrex, boxHeader(audioTrex, 0) + 4, audioTrackId);

        ByteArrayOutputStream children = new ByteArrayOutputStream();
        boolean inserted = false;

        int off = vMoov + boxHeader(videoInit, vMoov);
        int end = vMoov + boxSize(videoInit, vMoov);
        while (off + 8 <= end) {
            int size = boxSize(videoInit, off);
            if (size < 8 || off + size > end) break;
            String type = type(videoInit, off);

            if ("mvex".equals(type)) {
                // The audio trak has to be declared before the movie extends box.
                if (!inserted) {
                    children.write(audioTrak, 0, audioTrak.length);
                    inserted = true;
                }
                ByteArrayOutputStream payload = new ByteArrayOutputStream();
                int child = off + boxHeader(videoInit, off);
                int childEnd = off + size;
                while (child + 8 <= childEnd) {
                    int childSize = boxSize(videoInit, child);
                    if (childSize < 8 || child + childSize > childEnd) break;
                    payload.write(videoInit, child, childSize);
                    child += childSize;
                }
                payload.write(audioTrex, 0, audioTrex.length);
                byte[] mvex = makeBox("mvex", payload.toByteArray());
                children.write(mvex, 0, mvex.length);
            } else if ("mvhd".equals(type)) {
                byte[] mvhd = copyRange(videoInit, off, size);
                writeU32(mvhd, nextTrackIdOffset(mvhd), audioTrackId + 1);
                children.write(mvhd, 0, mvhd.length);
            } else {
                children.write(videoInit, off, size);
            }
            off += size;
        }
        if (!inserted) children.write(audioTrak, 0, audioTrak.length);

        byte[] moov = makeBox("moov", children.toByteArray());

        int ftypOff = findTopLevel(videoInit, "ftyp");
        byte[] header;
        if (ftypOff >= 0) {
            byte[] ftyp = copyRange(videoInit, ftypOff, boxSize(videoInit, ftypOff));
            header = new byte[ftyp.length + moov.length];
            System.arraycopy(ftyp, 0, header, 0, ftyp.length);
            System.arraycopy(moov, 0, header, ftyp.length, moov.length);
        } else {
            header = moov;
        }
        return new Merged(header, videoTrackId, audioTrackId);
    }

    // ------------------------------------------------------------------
    // Media fragments
    // ------------------------------------------------------------------

    /**
     * Renumbers the track inside one media segment. Sizes never change, so the
     * caller can write the buffer straight to the output file.
     *
     * @param fileOffset absolute position of this segment in the output file
     */
    static void rewriteFragment(byte[] data, int trackId, int sequenceNumber,
                                long fileOffset) {
        int off = 0;
        while (off + 8 <= data.length) {
            int size = boxSize(data, off);
            if (size < 8 || off + size > data.length) break;
            String type = type(data, off);

            if ("moof".equals(type)) {
                rewriteMoof(data, off, size, trackId, sequenceNumber, fileOffset + off);
            } else if ("sidx".equals(type) && off + 16 <= data.length) {
                // reference_ID must match the track it describes.
                writeU32(data, off + 12, trackId);
            }
            off += size;
        }
    }

    private static void rewriteMoof(byte[] data, int moofOff, int moofSize,
                                    int trackId, int sequenceNumber, long moofPosition) {
        int off = moofOff + boxHeader(data, moofOff);
        int end = moofOff + moofSize;
        while (off + 8 <= end) {
            int size = boxSize(data, off);
            if (size < 8 || off + size > end) break;
            String type = type(data, off);

            if ("mfhd".equals(type)) {
                if (off + 16 <= end) writeU32(data, off + 12, sequenceNumber);
            } else if ("traf".equals(type)) {
                int child = off + boxHeader(data, off);
                int childEnd = off + size;
                while (child + 8 <= childEnd) {
                    int childSize = boxSize(data, child);
                    if (childSize < 8 || child + childSize > childEnd) break;
                    if ("tfhd".equals(type(data, child)) && child + 16 <= childEnd) {
                        int flags = readU32(data, child + 8) & 0xFFFFFF;
                        writeU32(data, child + 12, trackId);
                        if ((flags & 0x000001) != 0 && child + 24 <= childEnd) {
                            writeU64(data, child + 16, moofPosition);
                        }
                    }
                    child += childSize;
                }
            }
            off += size;
        }
    }

    // ------------------------------------------------------------------
    // Box helpers
    // ------------------------------------------------------------------

    private static int findTopLevel(byte[] data, String wanted) {
        int off = 0;
        while (off + 8 <= data.length) {
            int size = boxSize(data, off);
            if (size < 8 || off + size > data.length) return -1;
            if (wanted.equals(type(data, off))) return off;
            off += size;
        }
        return -1;
    }

    private static int findChild(byte[] data, int parentOff, String wanted) {
        int off = parentOff + boxHeader(data, parentOff);
        int end = parentOff + boxSize(data, parentOff);
        while (off + 8 <= end) {
            int size = boxSize(data, off);
            if (size < 8 || off + size > end) return -1;
            if (wanted.equals(type(data, off))) return off;
            off += size;
        }
        return -1;
    }

    private static int trackIdAt(byte[] data, int trakOff) {
        int tkhd = findChild(data, trakOff, "tkhd");
        if (tkhd < 0) return 1;
        return readU32(data, trackIdOffset(data, tkhd));
    }

    /** Absolute offset of the 32-bit track_ID inside a tkhd box. */
    private static int trackIdOffset(byte[] data, int tkhdOff) {
        int header = boxHeader(data, tkhdOff);
        int version = data[tkhdOff + header] & 0xff;
        return tkhdOff + header + 4 + (version == 1 ? 16 : 8);
    }

    /** Offset of next_track_ID inside a standalone mvhd box. */
    private static int nextTrackIdOffset(byte[] mvhd) {
        int header = boxHeader(mvhd, 0);
        int version = mvhd[header] & 0xff;
        return header + 4 + (version == 1
                ? 8 + 8 + 4 + 8 + 4 + 2 + 2 + 8 + 36 + 24
                : 4 + 4 + 4 + 4 + 4 + 2 + 2 + 8 + 36 + 24);
    }

    private static int boxHeader(byte[] data, int off) {
        return readU32(data, off) == 1 ? 16 : 8;
    }

    private static int boxSize(byte[] data, int off) {
        if (off + 8 > data.length) return -1;
        long size = readU32(data, off) & 0xFFFFFFFFL;
        if (size == 1) {
            if (off + 16 > data.length) return -1;
            return (int) readU64(data, off + 8);
        }
        if (size == 0) return data.length - off;
        return (int) size;
    }

    private static String type(byte[] data, int off) {
        return new String(data, off + 4, 4, StandardCharsets.ISO_8859_1);
    }

    private static byte[] copyRange(byte[] data, int off, int length) {
        byte[] out = new byte[length];
        System.arraycopy(data, off, out, 0, length);
        return out;
    }

    private static byte[] makeBox(String type, byte[] payload) {
        byte[] box = new byte[8 + payload.length];
        writeU32(box, 0, box.length);
        byte[] name = type.getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(name, 0, box, 4, 4);
        System.arraycopy(payload, 0, box, 8, payload.length);
        return box;
    }

    private static int readU32(byte[] data, int off) {
        return ((data[off] & 0xff) << 24)
                | ((data[off + 1] & 0xff) << 16)
                | ((data[off + 2] & 0xff) << 8)
                | (data[off + 3] & 0xff);
    }

    private static long readU64(byte[] data, int off) {
        return ((long) readU32(data, off) << 32) | (readU32(data, off + 4) & 0xFFFFFFFFL);
    }

    private static void writeU32(byte[] data, int off, long value) {
        data[off] = (byte) (value >>> 24);
        data[off + 1] = (byte) (value >>> 16);
        data[off + 2] = (byte) (value >>> 8);
        data[off + 3] = (byte) value;
    }

    private static void writeU64(byte[] data, int off, long value) {
        writeU32(data, off, value >>> 32);
        writeU32(data, off + 4, value & 0xFFFFFFFFL);
    }
}
