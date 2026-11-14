package app.reverie;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.util.Log;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.SmbConfig;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Read-only access to one folder tree on an SMB share: lists the photos once (cached in files/list2.txt),
 * then hands out random ones, decoded down to about screen size. Every SMB call holds {@link #lock}, so the
 * background walker and the slideshow can share the one connection.
 */
final class PhotoSource {
    private static final String TAG = "Reverie";
    private static final long MAX_FILE_BYTES = 40L * 1024 * 1024;
    private static final long LIST_MAX_AGE_MS = 7L * 24 * 3600 * 1000;
    private static final String[] PHOTO_EXT = {".jpg", ".jpeg", ".png", ".webp", ".heic", ".heif"};
    private static final String[] VIDEO_EXT = {".mp4", ".m4v", ".mov", ".mkv", ".webm", ".3gp"};

    private final Config cfg;
    private final File cacheFile;
    private final Object lock = new Object();
    private final List<String> files = new ArrayList<>();   // guarded by files
    private final Random rnd = new Random();
    private final ArrayDeque<String> recent = new ArrayDeque<>();

    private SMBClient client;
    private Connection conn;
    private Session session;
    private DiskShare share;
    // Video streams over its own connection: a whole-photo download on the other one would otherwise block
    // every 1 MB video read behind it (the prebuffer never filled and playback ran dry).
    private SMBClient videoClient;
    private Connection videoConn;
    private Session videoSession;
    private DiskShare videoShare;
    private final Object videoLock = new Object();
    private volatile boolean closed;
    /** Set by a key press: a look-ahead download in progress gives up so the worker can act on the key. */
    volatile boolean abortPrefetch;
    // The first read after the photo connection idled through a video stalled ~30 s three times (a lost packet
    // on an idle Wi-Fi TCP connection, then retransmit back-off). A tiny query every few seconds keeps it warm.
    private final java.util.concurrent.ScheduledExecutorService keepWarm =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
    private volatile boolean walking;
    private volatile boolean listed;    // loadListing finished (cache read, walk done or failed)

    PhotoSource(Context c, Config cfg) {
        this.cfg = cfg;
        this.cacheFile = cacheFile(c);
        keepWarm.scheduleWithFixedDelay(() -> {
            synchronized (lock) {
                try { if (share != null && share.isConnected()) share.getShareInformation(); } catch (Exception ignored) { }
            }
        }, 5, 5, TimeUnit.SECONDS);
    }

    static File cacheFile(Context c) {
        return new File(c.getFilesDir(), "list2.txt");
    }

    int count() {
        synchronized (files) { return files.size(); }
    }

    boolean isWalking() { return walking; }

    boolean isListed() { return listed; }

    private void connect() throws IOException {
        synchronized (lock) {
            if (share != null && share.isConnected()) return;
            closeQuietly();
            client = new SMBClient(smbConfig());
            conn = client.connect(cfg.server);
            Log.i(TAG, "SMB (photos) " + conn.getNegotiatedProtocol().getDialect() + ", max read " + conn.getNegotiatedProtocol().getMaxReadSize() + " B");
            session = conn.authenticate(auth());
            share = (DiskShare) session.connectShare(cfg.share);
            Log.i(TAG, "SMB signing " + session.getSessionContext().isSigningRequired() + ", encrypt "
                    + session.getSessionContext().isEncryptData());
        }
    }

    private DiskShare connectVideo() throws IOException {
        synchronized (videoLock) {
            if (videoShare != null && videoShare.isConnected()) return videoShare;
            closeVideoQuietly();
            videoClient = new SMBClient(smbConfig());
            videoConn = videoClient.connect(cfg.server);
            Log.i(TAG, "SMB (video) " + videoConn.getNegotiatedProtocol().getDialect() + ", max read " + videoConn.getNegotiatedProtocol().getMaxReadSize() + " B");
            videoSession = videoConn.authenticate(auth());
            videoShare = (DiskShare) videoSession.connectShare(cfg.share);
            return videoShare;
        }
    }

    private static SmbConfig smbConfig() {
        return SmbConfig.builder()
                .withTimeout(30, TimeUnit.SECONDS)
                .withSoTimeout(30, TimeUnit.SECONDS)
                .withReadBufferSize(SmbMediaDataSource.CHUNK)   // smbj clamps this to what the NAS allows
                // shortcut: SMB 2.1 only, it streams 55% faster than 3.1.1 on a low-end streaming box; add 3.x if a NAS refuses it
                .withDialects(com.hierynomus.mssmb2.SMB2Dialect.SMB_2_1)
                .build();
    }

    private AuthenticationContext auth() {
        return cfg.isGuest() ? AuthenticationContext.guest()
                : new AuthenticationContext(cfg.user, cfg.password.toCharArray(), cfg.domain);
    }

    private void closeQuietly() {
        try { if (share != null) share.close(); } catch (Exception ignored) { }
        try { if (session != null) session.close(); } catch (Exception ignored) { }
        try { if (conn != null) conn.close(); } catch (Exception ignored) { }
        try { if (client != null) client.close(); } catch (Exception ignored) { }
        share = null; session = null; conn = null; client = null;
    }

    private void closeVideoQuietly() {
        try { if (videoShare != null) videoShare.close(); } catch (Exception ignored) { }
        try { if (videoSession != null) videoSession.close(); } catch (Exception ignored) { }
        try { if (videoConn != null) videoConn.close(); } catch (Exception ignored) { }
        try { if (videoClient != null) videoClient.close(); } catch (Exception ignored) { }
        videoShare = null; videoSession = null; videoConn = null; videoClient = null;
    }

    void close() {
        closed = true;
        keepWarm.shutdownNow();
        synchronized (lock) { closeQuietly(); }
        synchronized (videoLock) { closeVideoQuietly(); }
    }

    /** Reads the cached list if it is fresh, else walks the share. Blocks; run it off the main thread. */
    void loadListing() throws IOException {
        boolean fresh = cacheFile.exists() && System.currentTimeMillis() - cacheFile.lastModified() < LIST_MAX_AGE_MS;
        if (cacheFile.exists()) {
            try (BufferedReader r = new BufferedReader(new FileReader(cacheFile))) {
                String line;
                synchronized (files) {
                    while ((line = r.readLine()) != null) if (!line.isEmpty()) files.add(line);
                    dropLivePhotoClips(files);
                }
            } catch (IOException e) {
                Log.w(TAG, "cache unreadable: " + e);
            }
            Log.i(TAG, "cached list: " + count() + " photos, fresh=" + fresh);
        }
        try {
            if (!fresh || count() == 0) walk();
        } finally {
            listed = true;
        }
    }

    private void walk() throws IOException {
        walking = true;
        try {
            connect();
            List<String> found = new ArrayList<>();
            ArrayDeque<String> dirs = new ArrayDeque<>();
            dirs.add(cfg.path);
            boolean firstBatch = count() == 0;
            while (!dirs.isEmpty() && !closed) {
                String dir = dirs.poll();
                List<FileIdBothDirectoryInformation> list;
                synchronized (lock) { list = share.list(dir); }
                for (FileIdBothDirectoryInformation e : list) {
                    String name = e.getFileName();
                    if (name.equals(".") || name.equals("..") || name.startsWith("@") || name.startsWith(".")) continue;
                    String full = dir.isEmpty() ? name : dir + "\\" + name;
                    if ((e.getFileAttributes() & FileAttributes.FILE_ATTRIBUTE_DIRECTORY.getValue()) != 0) {
                        dirs.add(full);
                    } else if (isPhoto(name) || isVideo(name)) {
                        found.add(full);
                        if (firstBatch) synchronized (files) { files.add(full); }  // show something while walking
                    }
                }
            }
            if (closed) return;
            Log.i(TAG, "walk done: " + found.size() + " photos");
            if (found.isEmpty()) return;
            dropLivePhotoClips(found);
            synchronized (files) { files.clear(); files.addAll(found); }
            try (BufferedWriter w = new BufferedWriter(new FileWriter(cacheFile))) {
                for (String s : found) { w.write(s); w.newLine(); }
            }
        } finally {
            walking = false;
        }
    }

    /**
     * An iPhone Live Photo arrives as IMG_1234.HEIC plus a 3 s IMG_1234.MOV. The clip is the still's moving
     * version, not a video worth showing (and this box renders them purple), so a video is dropped when a photo
     * with the same name sits in the list.
     */
    static void dropLivePhotoClips(List<String> list) {
        java.util.HashSet<String> stills = new java.util.HashSet<>();
        for (String p : list) if (isPhoto(p)) stills.add(stem(p));
        int before = list.size();
        list.removeIf(p -> isVideo(p) && stills.contains(stem(p)));
        if (list.size() != before) Log.i(TAG, "dropped " + (before - list.size()) + " Live Photo clips");
    }

    private static String stem(String path) {
        String n = path.toLowerCase(Locale.ROOT);
        int dot = n.lastIndexOf('.');
        return dot < 0 ? n : n.substring(0, dot);
    }

    private static boolean endsWithAny(String name, String[] exts) {
        String n = name.toLowerCase(Locale.ROOT);
        for (String e : exts) if (n.endsWith(e)) return true;
        return false;
    }

    private static boolean isPhoto(String name) { return endsWithAny(name, PHOTO_EXT); }

    static boolean isVideo(String name) { return endsWithAny(name, VIDEO_EXT); }

    /** A random file from the list (photos only when videos are switched off), avoiding the last 30. Null if none. */
    String randomPath() {
        synchronized (files) {
            if (files.isEmpty()) return null;
            String p = null;
            for (int i = 0; i < 24; i++) {
                p = files.get(rnd.nextInt(files.size()));
                if (!cfg.includeVideos && isVideo(p)) { p = null; continue; }
                if (!recent.contains(p)) break;
            }
            if (p == null) return null;
            recent.add(p);
            while (recent.size() > 30) recent.poll();
            return p;
        }
    }

    /** The photo at path decoded to roughly w x h (never smaller), upright. Null when it was unusable. */
    Bitmap loadPhoto(String path, int w, int h) throws IOException {
        return loadPhoto(path, w, h, false);
    }

    /** As above; a prefetch stops (null) when {@link #abortPrefetch} is raised while it downloads. */
    Bitmap loadPhoto(String path, int w, int h, boolean prefetch) throws IOException {
        byte[] data;
        long t0 = System.currentTimeMillis();
        try {
            connect();
            data = read(path, prefetch);
        } catch (IOException e) {
            synchronized (lock) { closeQuietly(); }   // reconnect on the next try
            throw e;
        }
        if (data == null) return null;
        long ms = Math.max(1, System.currentTimeMillis() - t0);
        Log.i(TAG, String.format("photo %d KB in %d ms (%.1f MB/s)%s", data.length / 1024, ms,
                data.length / 1048.576 / ms, prefetch ? " ahead" : ""));

        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int sample = 1;
        while (o.outWidth / (sample * 2) >= w && o.outHeight / (sample * 2) >= h) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length, o2);
        if (bmp == null) return null;

        int rotation = 0;
        try {
            ExifInterface ex = new ExifInterface(new ByteArrayInputStream(data));
            switch (ex.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                case ExifInterface.ORIENTATION_ROTATE_90: rotation = 90; break;
                case ExifInterface.ORIENTATION_ROTATE_180: rotation = 180; break;
                case ExifInterface.ORIENTATION_ROTATE_270: rotation = 270; break;
                default: break;
            }
        } catch (IOException ignored) { }
        if (rotation != 0) {
            Matrix m = new Matrix();
            m.postRotate(rotation);
            Bitmap r = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            if (r != bmp) bmp.recycle();
            bmp = r;
        }
        Log.i(TAG, String.format("decoded %dx%d /%d -> %dx%d in %d ms (%s)", o.outWidth, o.outHeight, sample,
                bmp.getWidth(), bmp.getHeight(), System.currentTimeMillis() - t0 - ms,
                path.substring(path.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT)));
        return bmp;
    }

    /** A seekable view of a video on the share, on the video connection. */
    SmbMediaDataSource openVideo(String path) throws IOException {
        try {
            DiskShare s = connectVideo();
            synchronized (videoLock) {
                return new SmbMediaDataSource(s.openFile(path, EnumSet.of(AccessMask.GENERIC_READ), null,
                        SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null), videoLock);
            }
        } catch (IOException | RuntimeException e) {
            synchronized (videoLock) { closeVideoQuietly(); }   // reconnect on the next try
            throw e;
        }
    }

    private byte[] read(String path, boolean abortable) throws IOException {
        synchronized (lock) {
            try (com.hierynomus.smbj.share.File f = share.openFile(path, EnumSet.of(AccessMask.GENERIC_READ), null,
                    SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null)) {
                if (f.getFileInformation().getStandardInformation().getEndOfFile() > MAX_FILE_BYTES) return null;
                ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);
                try (InputStream in = f.getInputStream()) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        if (abortable && abortPrefetch) return null;
                        out.write(buf, 0, n);
                    }
                }
                return out.toByteArray();
            }
        }
    }
}
