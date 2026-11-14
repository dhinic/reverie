package app.reverie;

import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.SmbConfig;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** One short-lived read-only SMB session used by Settings to test a login and browse folders. Not thread safe. */
final class SmbBrowser implements Closeable {
    private final SMBClient client;
    private Connection conn;
    private Session session;
    private DiskShare share;

    /** Logs in. A blank user means guest. Throws with a readable message on failure. */
    SmbBrowser(String server, String user, String password, String domain) throws IOException {
        SmbConfig sc = SmbConfig.builder()
                .withTimeout(10, TimeUnit.SECONDS)
                .withSoTimeout(10, TimeUnit.SECONDS)
                .build();
        client = new SMBClient(sc);
        try {
            conn = client.connect(server);
            session = conn.authenticate(user.isEmpty()
                    ? AuthenticationContext.guest()
                    : new AuthenticationContext(user, password.toCharArray(), domain));
        } catch (IOException | RuntimeException e) {
            close();
            throw new IOException(describe(e), e);
        }
    }

    /** Opens the share; throws if it does not exist or access is denied. */
    void openShare(String name) throws IOException {
        try {
            if (share != null) share.close();
            share = (DiskShare) session.connectShare(name);
        } catch (RuntimeException e) {
            throw new IOException(describe(e), e);
        }
    }

    /** Names of the sub-folders of path (a "\" separated path inside the share, "" for the top), sorted. */
    List<String> folders(String path) throws IOException {
        List<String> out = new ArrayList<>();
        try {
            for (FileIdBothDirectoryInformation e : share.list(path)) {
                String n = e.getFileName();
                if (n.equals(".") || n.equals("..") || n.startsWith(".") || n.startsWith("@")) continue;
                if ((e.getFileAttributes() & FileAttributes.FILE_ATTRIBUTE_DIRECTORY.getValue()) != 0) out.add(n);
            }
        } catch (RuntimeException e) {
            throw new IOException(describe(e), e);
        }
        Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    @Override
    public void close() {
        try { if (share != null) share.close(); } catch (Exception ignored) { }
        try { if (session != null) session.close(); } catch (Exception ignored) { }
        try { if (conn != null) conn.close(); } catch (Exception ignored) { }
        try { client.close(); } catch (Exception ignored) { }
    }

    private static String describe(Throwable e) {
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
    }
}
