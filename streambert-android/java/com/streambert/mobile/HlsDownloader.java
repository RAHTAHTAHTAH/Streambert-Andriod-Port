package com.streambert.mobile;

import android.content.Context;
import android.util.Log;
import android.webkit.CookieManager;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Minimal HLS (m3u8) downloader.
 *
 * <p>Most of Streambert's hosters serve an HLS playlist rather than a single
 * MP4, so Android's DownloadManager cannot save them. This class resolves the
 * master playlist, downloads every media segment (AES-128 aware), and writes
 * them sequentially to the supplied stream. The result is a plain MPEG-TS file
 * or a fragmented MP4, both playable by VLC / MX Player / ExoPlayer.</p>
 *
 * <p>No external dependencies, so it fits the Gradle-less build.</p>
 */
final class HlsDownloader {

    interface Listener {
        void onProgress(long bytes, int done, int total, String label);
        boolean isCancelled();
    }

    static final class Result {
        final long bytes;
        final boolean mp4;
        final int segments;

        Result(long bytes, boolean mp4, int segments) {
            this.bytes = bytes;
            this.mp4 = mp4;
            this.segments = segments;
        }
    }

    private static final int CONNECT_TIMEOUT = 20000;
    private static final int READ_TIMEOUT = 40000;
    private static final String TAG = "StreambertHls";

    private final Context ctx;
    private final String referer;
    private final String userAgent;
    private final Listener listener;
    private final Map<String, byte[]> keyCache = new HashMap<>();

    HlsDownloader(Context ctx, String referer, String userAgent, Listener listener) {
        this.ctx = ctx;
        this.referer = referer == null ? "" : referer;
        this.userAgent = userAgent;
        this.listener = listener;
    }

    Result download(String playlistUrl, OutputStream out) throws IOException {
        String text = fetchText(playlistUrl);
        URL base = new URL(playlistUrl);

        Master master = null;
        if (text.contains("#EXT-X-STREAM-INF")) {
            master = parseMaster(text, base);
        } else {
            // The player often hands us the variant playlist directly, which
            // hides the #EXT-X-MEDIA audio rendition. Look for the sibling
            // master playlist to recover it.
            master = siblingMaster(base);
        }

        String audioUrl = null;
        if (master != null) {
            Variant variant = master.pick(playlistUrl);
            if (variant != null) {
                text = fetchText(variant.uri);
                base = new URL(variant.uri);
                AudioRendition audio = master.audioFor(variant);
                if (audio != null && audio.uri != null) audioUrl = audio.uri;
            }
        }

        Playlist playlist = parseMedia(text, base);
        if (playlist.segments.isEmpty()) throw new IOException("Playlist has no segments");

        // Separate audio rendition: mux it into the video fragments so the
        // saved file is not silent.
        if (audioUrl != null) {
            try {
                Playlist audioPlaylist = parseMedia(fetchText(audioUrl), new URL(audioUrl));
                if (playlist.mp4 && audioPlaylist.mp4
                        && playlist.mapUri != null && audioPlaylist.mapUri != null
                        && !audioPlaylist.segments.isEmpty()) {
                    return downloadMuxed(playlist, audioPlaylist, out);
                }
                Log.w(TAG, "Separate audio is not fragmented MP4; saving video only");
            } catch (IOException e) {
                Log.w(TAG, "Audio rendition failed; saving video only", e);
            }
        }

        int total = playlist.segments.size();
        long written = 0;

        if (playlist.mapUri != null) {
            byte[] init = fetchBytes(playlist.mapUri, -1, -1, null, null, 0);
            out.write(init);
            written += init.length;
        }

        int done = 0;
        for (Segment segment : playlist.segments) {
            if (listener != null && listener.isCancelled()) throw new IOException("Cancelled");
            byte[] data = fetchSegment(segment);
            out.write(data);
            written += data.length;
            done++;
            report(written, done, total);
        }

        out.flush();
        return new Result(written, playlist.mp4, total);
    }

    /**
     * Writes the video and audio fragments interleaved into one MP4. Both
     * renditions are CMAF, so the fragments can be copied verbatim after
     * renumbering the audio track.
     */
    private Result downloadMuxed(Playlist video, Playlist audio, OutputStream out)
            throws IOException {
        byte[] videoInit = fetchBytes(video.mapUri, -1, -1, null, null, 0);
        byte[] audioInit = fetchBytes(audio.mapUri, -1, -1, null, null, 0);
        Fmp4Muxer.Merged merged = Fmp4Muxer.mergeInits(videoInit, audioInit);

        out.write(merged.header);
        long written = merged.header.length;

        int videoCount = video.segments.size();
        int audioCount = audio.segments.size();
        int total = videoCount + audioCount;
        int count = Math.max(videoCount, audioCount);
        int done = 0;
        int sequence = 1;

        Log.i(TAG, "Muxing separate audio: " + videoCount + " video + " + audioCount
                + " audio segments (tracks " + merged.videoTrackId + "/"
                + merged.audioTrackId + ")");

        for (int i = 0; i < count; i++) {
            if (listener != null && listener.isCancelled()) throw new IOException("Cancelled");

            if (i < videoCount) {
                byte[] data = fetchSegment(video.segments.get(i));
                Fmp4Muxer.rewriteFragment(data, merged.videoTrackId, sequence++, written);
                out.write(data);
                written += data.length;
                done++;
                report(written, done, total);
            }
            if (i < audioCount) {
                byte[] data = fetchSegment(audio.segments.get(i));
                Fmp4Muxer.rewriteFragment(data, merged.audioTrackId, sequence++, written);
                out.write(data);
                written += data.length;
                done++;
                report(written, done, total);
            }
        }

        out.flush();
        return new Result(written, true, total);
    }

    private byte[] fetchSegment(Segment segment) throws IOException {
        return fetchBytes(segment.url, segment.rangeStart, segment.rangeLength,
                segment.keyUri, segment.iv, segment.seq);
    }

    private void report(long written, int done, int total) {
        if (listener != null) {
            listener.onProgress(written, done, total, done + " / " + total + " segments");
        }
    }

    // ------------------------------------------------------------------
    // Playlist parsing
    // ------------------------------------------------------------------

    private static final class Segment {
        String url;
        long rangeStart = -1;
        long rangeLength = -1;
        String keyUri;
        byte[] iv;
        long seq;
    }

    private static final class Playlist {
        final List<Segment> segments = new ArrayList<>();
        String mapUri;
        boolean mp4;
    }

    private Playlist parseMedia(String text, URL base) throws IOException {
        Playlist playlist = new Playlist();
        String[] lines = text.split("\r?\n");

        String pendingKeyUri = null;
        byte[] pendingIv = null;
        long seq = 0;
        long pendingRangeLen = -1;
        long pendingRangeOff = -1;
        String lastUri = null;
        long lastRangeEnd = 0;

        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) continue;

            if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                seq = parseLong(line.substring(22), 0);
                continue;
            }
            if (line.startsWith("#EXT-X-KEY:")) {
                Map<String, String> attrs = parseAttrs(line.substring(11));
                String method = attrs.get("METHOD");
                if (method == null || "NONE".equalsIgnoreCase(method)) {
                    pendingKeyUri = null;
                    pendingIv = null;
                } else if ("AES-128".equalsIgnoreCase(method)) {
                    pendingKeyUri = resolve(base, attrs.get("URI"));
                    String ivHex = attrs.get("IV");
                    pendingIv = ivHex == null ? null
                            : hexToBytes(ivHex.replace("0x", "").replace("0X", ""));
                } else {
                    throw new IOException("Unsupported HLS encryption: " + method);
                }
                continue;
            }
            if (line.startsWith("#EXT-X-MAP:")) {
                Map<String, String> attrs = parseAttrs(line.substring(11));
                playlist.mapUri = resolve(base, attrs.get("URI"));
                playlist.mp4 = true;
                continue;
            }
            if (line.startsWith("#EXT-X-BYTERANGE:")) {
                String value = line.substring(17);
                String[] parts = value.split("@");
                pendingRangeLen = parseLong(parts[0], -1);
                pendingRangeOff = parts.length > 1 ? parseLong(parts[1], -1) : -1;
                continue;
            }
            if (line.startsWith("#")) continue;

            // A URI line is a media segment.
            Segment segment = new Segment();
            segment.url = resolve(base, line);
            segment.keyUri = pendingKeyUri;
            segment.iv = pendingIv;
            segment.seq = seq++;

            if (pendingRangeLen > 0) {
                segment.rangeLength = pendingRangeLen;
                if (pendingRangeOff >= 0) {
                    segment.rangeStart = pendingRangeOff;
                } else if (segment.url.equals(lastUri)) {
                    segment.rangeStart = lastRangeEnd;
                } else {
                    segment.rangeStart = 0;
                }
                lastRangeEnd = segment.rangeStart + segment.rangeLength;
            }
            lastUri = segment.url;
            pendingRangeLen = -1;
            pendingRangeOff = -1;

            playlist.segments.add(segment);
        }
        return playlist;
    }

    // ------------------------------------------------------------------
    // Master playlist
    // ------------------------------------------------------------------

    private static final class Variant {
        String uri;
        long bandwidth;
        String audioGroup;
        String codecs;
    }

    private static final class AudioRendition {
        String groupId;
        String uri;
        boolean isDefault;
    }

    private static final class Master {
        final List<Variant> variants = new ArrayList<>();
        final Map<String, List<AudioRendition>> audioGroups = new LinkedHashMap<>();

        /** Prefers an AVC (H.264) variant for broad player compatibility, then
         *  the variant the player was already using, else the highest bandwidth. */
        Variant pick(String originalUrl) {
            if (variants.isEmpty()) return null;
            // Prefer AVC/H.264 variants when available: HEVC downloads often
            // fail to render in the system players that open downloaded files.
            Variant avcBest = null;
            for (Variant variant : variants) {
                String c = variant.codecs == null ? "" : variant.codecs.toLowerCase();
                if (c.contains("avc")) {
                    if (avcBest == null || variant.bandwidth > avcBest.bandwidth) {
                        avcBest = variant;
                    }
                }
            }
            if (avcBest != null) return avcBest;

            String wanted = fileName(originalUrl);
            if (wanted != null) {
                for (Variant variant : variants) {
                    if (wanted.equals(fileName(variant.uri))) return variant;
                }
            }
            Variant best = variants.get(0);
            for (Variant variant : variants) {
                if (variant.bandwidth > best.bandwidth) best = variant;
            }
            return best;
        }

        AudioRendition audioFor(Variant variant) {
            if (variant == null || variant.audioGroup == null) return null;
            List<AudioRendition> list = audioGroups.get(variant.audioGroup);
            if (list == null || list.isEmpty()) return null;
            for (AudioRendition rendition : list) {
                if (rendition.isDefault && rendition.uri != null) return rendition;
            }
            for (AudioRendition rendition : list) {
                if (rendition.uri != null) return rendition;
            }
            return null;
        }
    }

    private Master parseMaster(String text, URL base) {
        Master master = new Master();
        long bandwidth = 0;
        String audioGroup = null;
        String pendingCodecs = null;

        for (String raw : text.split("\r?\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;

            if (line.startsWith("#EXT-X-MEDIA:")) {
                Map<String, String> attrs = parseAttrs(line.substring(13));
                if ("AUDIO".equalsIgnoreCase(attrs.get("TYPE"))) {
                    AudioRendition rendition = new AudioRendition();
                    rendition.groupId = attrs.get("GROUP-ID");
                    rendition.uri = resolve(base, attrs.get("URI"));
                    rendition.isDefault = "YES".equalsIgnoreCase(attrs.get("DEFAULT"));
                    if (rendition.groupId != null) {
                        List<AudioRendition> list = master.audioGroups.get(rendition.groupId);
                        if (list == null) {
                            list = new ArrayList<>();
                            master.audioGroups.put(rendition.groupId, list);
                        }
                        list.add(rendition);
                    }
                }
                continue;
            }

            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                Map<String, String> attrs = parseAttrs(line.substring(18));
                bandwidth = parseLong(attrs.get("BANDWIDTH"), 0);
                audioGroup = attrs.get("AUDIO");
                String codecsAttr = attrs.get("CODECS");
                // store temporarily; assigned when the URI line is read
                pendingCodecs = codecsAttr;
                continue;
            }

            if (line.startsWith("#")) continue;

            Variant variant = new Variant();
            variant.uri = resolve(base, line);
            variant.bandwidth = bandwidth;
            variant.audioGroup = audioGroup;
            variant.codecs = pendingCodecs;
            master.variants.add(variant);
            bandwidth = 0;
            audioGroup = null;
            pendingCodecs = null;
        }
        return master;
    }

    /**
     * Fetches {@code master.m3u8} (or {@code index.m3u8}) next to a media
     * playlist so the audio rendition can be discovered.
     */
    private Master siblingMaster(URL base) {
        String[] candidates = {"master.m3u8", "index.m3u8"};
        for (String candidate : candidates) {
            try {
                String url = new URL(base, candidate).toString();
                if (url.equals(base.toString())) continue;
                String text = fetchTextOrNull(url);
                if (text != null && text.contains("#EXT-X-STREAM-INF")) {
                    Log.i(TAG, "Found sibling master playlist: " + url);
                    return parseMaster(text, new URL(url));
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private String fetchTextOrNull(String url) {
        try {
            return fetchText(url);
        } catch (IOException e) {
            return null;
        }
    }

    private static String fileName(String url) {
        if (url == null) return null;
        int query = url.indexOf('?');
        String path = query >= 0 ? url.substring(0, query) : url;
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    // ------------------------------------------------------------------
    // HTTP
    // ------------------------------------------------------------------

    private String fetchText(String url) throws IOException {
        HttpURLConnection conn = open(url);
        try {
            int code = conn.getResponseCode();
            if (code != 200 && code != 206) throw new IOException("HTTP " + code + " for playlist");
            InputStream in = new BufferedInputStream(conn.getInputStream());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            return out.toString("UTF-8");
        } finally {
            conn.disconnect();
        }
    }

    private byte[] fetchBytes(String url, long rangeStart, long rangeLength,
                              String keyUri, byte[] iv, long seq) throws IOException {
        HttpURLConnection conn = open(url);
        try {
            if (rangeStart >= 0 && rangeLength > 0) {
                conn.setRequestProperty("Range",
                        "bytes=" + rangeStart + "-" + (rangeStart + rangeLength - 1));
            }
            int code = conn.getResponseCode();
            if (code != 200 && code != 206) throw new IOException("HTTP " + code + " for segment");
            InputStream in = new BufferedInputStream(conn.getInputStream());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();

            byte[] data = out.toByteArray();
            if (keyUri != null) data = decrypt(data, keyUri, iv, seq);
            return data;
        } finally {
            conn.disconnect();
        }
    }

    private HttpURLConnection open(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", userAgent == null ? "Mozilla/5.0" : userAgent);
        if (!referer.isEmpty()) conn.setRequestProperty("Referer", referer);
        conn.setRequestProperty("Accept", "*/*");
        try {
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null && !cookie.isEmpty()) {
                conn.setRequestProperty("Cookie", cookie);
            }
        } catch (Exception ignored) {
        }
        return conn;
    }

    // ------------------------------------------------------------------
    // AES-128
    // ------------------------------------------------------------------

    private byte[] decrypt(byte[] data, String keyUri, byte[] iv, long seq)
            throws IOException {
        byte[] key = keyCache.get(keyUri);
        if (key == null) {
            key = fetchBytes(keyUri, -1, -1, null, null, 0);
            keyCache.put(keyUri, key);
        }
        if (key.length != 16) throw new IOException("Invalid AES key length");
        if (iv == null) iv = ivFromSequence(seq);

        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new IvParameterSpec(iv));
            return cipher.doFinal(data);
        } catch (BadPaddingException padding) {
            // Some encoders pad differently; decrypt raw and strip PKCS#7 by hand.
            try {
                Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                        new IvParameterSpec(iv));
                byte[] raw = cipher.doFinal(data);
                int pad = raw.length == 0 ? 0 : (raw[raw.length - 1] & 0xff);
                if (pad > 0 && pad <= 16 && pad <= raw.length) {
                    boolean valid = true;
                    for (int i = raw.length - pad; i < raw.length; i++) {
                        if ((raw[i] & 0xff) != pad) {
                            valid = false;
                            break;
                        }
                    }
                    if (valid) return Arrays.copyOf(raw, raw.length - pad);
                }
                return raw;
            } catch (Exception e) {
                throw new IOException("Could not decrypt segment", e);
            }
        } catch (Exception e) {
            throw new IOException("Could not decrypt segment", e);
        }
    }

    private static byte[] ivFromSequence(long sequence) {
        byte[] iv = new byte[16];
        long value = sequence;
        for (int i = 15; i >= 8; i--) {
            iv[i] = (byte) (value & 0xff);
            value >>= 8;
        }
        return iv;
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    private static Map<String, String> parseAttrs(String source) {
        Map<String, String> attrs = new LinkedHashMap<>();
        int i = 0;
        while (i < source.length()) {
            int eq = source.indexOf('=', i);
            if (eq < 0) break;
            String key = source.substring(i, eq).trim();
            int cursor = eq + 1;
            String value;
            if (cursor < source.length() && source.charAt(cursor) == '"') {
                int end = source.indexOf('"', cursor + 1);
                if (end < 0) end = source.length();
                value = source.substring(cursor + 1, end);
                i = end + 1;
                if (i < source.length() && source.charAt(i) == ',') i++;
            } else {
                int end = source.indexOf(',', cursor);
                if (end < 0) end = source.length();
                value = source.substring(cursor, end).trim();
                i = end + 1;
            }
            if (!key.isEmpty()) attrs.put(key, value);
        }
        return attrs;
    }

    private static String resolve(URL base, String reference) {
        if (reference == null) return null;
        try {
            return new URL(base, reference).toString();
        } catch (Exception e) {
            return reference;
        }
    }

    private static long parseLong(String value, long fallback) {
        if (value == null) return fallback;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static byte[] hexToBytes(String hex) {
        if (hex == null) return new byte[0];
        if ((hex.length() & 1) == 1) hex = "0" + hex;
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    static String extensionFor(boolean mp4) {
        // MediaStore maps video/mp2t to .m2ts and appends it to a ".ts" name,
        // producing "movie.ts.m2ts". Naming it .m2ts keeps the file clean.
        return mp4 ? ".mp4" : ".m2ts";
    }

    static String mimeFor(boolean mp4) {
        return mp4 ? "video/mp4" : "video/mp2t";
    }

    static String safeName(String title) {
        String base = title == null ? "Streambert" : title.trim();
        if (base.isEmpty()) base = "Streambert";
        base = base.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (base.length() > 80) base = base.substring(0, 80).trim();
        return base;
    }

    static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.US);
    }
}
