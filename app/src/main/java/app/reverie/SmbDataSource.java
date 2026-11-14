package app.reverie;

import android.net.Uri;

import androidx.media3.common.C;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSpec;

import java.io.IOException;

/**
 * Feeds ExoPlayer from an open SMB file. ExoPlayer may open and close this many times (seeks); closing here only ends
 * that transfer, the file itself is closed by whoever owns the {@link SmbMediaDataSource}.
 */
final class SmbDataSource extends BaseDataSource {
    private final SmbMediaDataSource src;
    private Uri uri;
    private long pos;
    private long remaining;

    SmbDataSource(SmbMediaDataSource src) {
        super(/* isNetwork= */ true);
        this.src = src;
    }

    @Override
    public long open(DataSpec spec) throws IOException {
        uri = spec.uri;
        transferInitializing(spec);
        long size = src.getSize();
        if (spec.position > size) throw new IOException("read position past end of file");
        pos = spec.position;
        remaining = spec.length != C.LENGTH_UNSET ? Math.min(spec.length, size - pos) : size - pos;
        transferStarted(spec);
        return remaining;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) return 0;
        if (remaining == 0) return C.RESULT_END_OF_INPUT;
        int n = src.readAt(pos, buffer, offset, (int) Math.min(length, remaining));
        if (n <= 0) return C.RESULT_END_OF_INPUT;
        pos += n;
        remaining -= n;
        bytesTransferred(n);
        return n;
    }

    @Override
    public Uri getUri() {
        return uri;
    }

    @Override
    public void close() {
        if (uri != null) {
            uri = null;
            transferEnded();
        }
    }
}
