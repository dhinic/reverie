package app.reverie;

import android.media.MediaDataSource;

import java.io.IOException;

/**
 * Lets the player stream a video straight from the NAS: each read asks smbj for one byte range on the video
 * connection. Logs the achieved throughput now and then (the box's Wi-Fi is the usual bottleneck).
 */
final class SmbMediaDataSource extends MediaDataSource {
    /** Bytes per SMB read. Larger than 1 MB only helps if the NAS allows it (Samba: up to 8 MB). */
    static final int CHUNK = 4 << 20;

    private final com.hierynomus.smbj.share.File file;
    private final Object lock;
    private final long size;
    private volatile boolean closed;

    SmbMediaDataSource(com.hierynomus.smbj.share.File file, Object lock) {
        this.file = file;
        this.lock = lock;
        this.size = file.getFileInformation().getStandardInformation().getEndOfFile();
    }

    // Players ask for ~64 KB at a time; one SMB round trip each is far too slow over Wi-Fi, so read ahead in big blocks.
    private final byte[] chunk = new byte[CHUNK];
    private long chunkPos = -1;
    private int chunkLen;
    private long readBytes, readNanos;

    @Override
    public synchronized int readAt(long position, byte[] buffer, int offset, int size) throws IOException {
        if (closed || size == 0) return closed ? -1 : 0;
        if (position >= this.size) return -1;
        if (chunkPos < 0 || position < chunkPos || position >= chunkPos + chunkLen) {
            int want = (int) Math.min(chunk.length, this.size - position);
            int n;
            long t0 = System.nanoTime();
            synchronized (lock) {
                n = file.read(chunk, position, 0, want);
            }
            if (n <= 0) return -1;
            readBytes += n;
            readNanos += System.nanoTime() - t0;
            if (readBytes >= 16 << 20) {
                android.util.Log.i("Reverie", String.format("video read %.1f MB/s", readBytes * 1e9 / 1048576.0 / readNanos));
                readBytes = readNanos = 0;
            }
            chunkPos = position;
            chunkLen = n;
        }
        int from = (int) (position - chunkPos);
        int n = Math.min(size, chunkLen - from);
        System.arraycopy(chunk, from, buffer, offset, n);
        return n;
    }

    @Override
    public long getSize() {
        return size;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        synchronized (lock) {
            try { file.close(); } catch (Exception ignored) { }
        }
    }
}
