package app.reverie;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Settings kept in files/config.json in the app's private storage. Written by the Settings screen
 * ({@link #save}); {@code set-config.sh} can still push the same file with run-as.
 * An empty {@code user} means guest login.
 */
final class Config {
    String server = "";
    String share = "";
    String path = "";       // folder inside the share, "\" separated, may be empty
    String user = "";
    String password = "";
    String domain = "";
    int secondsPerPhoto = 10;
    int maxVideoSeconds = 60;
    boolean videoSound = true;
    boolean includeVideos = true;

    private Config() { }

    boolean isComplete() {
        return !server.isEmpty() && !share.isEmpty();
    }

    boolean isGuest() {
        return user.isEmpty();
    }

    Config copy() {
        Config c = new Config();
        c.server = server; c.share = share; c.path = path; c.user = user; c.password = password;
        c.domain = domain; c.secondsPerPhoto = secondsPerPhoto; c.maxVideoSeconds = maxVideoSeconds;
        c.videoSound = videoSound; c.includeVideos = includeVideos;
        return c;
    }

    private static File file(Context c) {
        return new File(c.getFilesDir(), "config.json");
    }

    /** Loads the file; an empty Config when there is none or it cannot be read. */
    static Config loadOrEmpty(Context c) {
        Config cfg = new Config();
        File f = file(c);
        if (!f.exists()) return cfg;
        try {
            JSONObject j = new JSONObject(read(f));
            cfg.server = j.optString("server", "");
            cfg.share = j.optString("share", "");
            cfg.path = j.optString("path", "").replace('/', '\\');
            cfg.user = j.optString("user", "");
            cfg.password = j.optString("password", "");
            cfg.domain = j.optString("domain", "");
            cfg.secondsPerPhoto = Math.max(3, j.optInt("secondsPerPhoto", 10));
            cfg.maxVideoSeconds = Math.max(5, j.optInt("maxVideoSeconds", 60));
            cfg.videoSound = j.optBoolean("videoSound", true);
            cfg.includeVideos = j.optBoolean("includeVideos", true);
        } catch (IOException | JSONException ignored) {
            // fall through with defaults
        }
        return cfg;
    }

    /** Loads the file and insists it names a server and a share. */
    static Config load(Context c) throws IOException {
        if (!file(c).exists()) throw new IOException("not set up yet: open Settings");
        Config cfg = loadOrEmpty(c);
        if (!cfg.isComplete()) throw new IOException("config needs a server and a share: open Settings");
        return cfg;
    }

    /** Writes the file, and drops the cached photo list when the place to look changed. */
    void save(Context c) throws IOException {
        Config old = loadOrEmpty(c);
        try {
            JSONObject j = new JSONObject();
            j.put("server", server);
            j.put("share", share);
            j.put("path", path);
            j.put("user", user);
            j.put("password", password);
            j.put("domain", domain);
            j.put("secondsPerPhoto", secondsPerPhoto);
            j.put("maxVideoSeconds", maxVideoSeconds);
            j.put("videoSound", videoSound);
            j.put("includeVideos", includeVideos);
            try (FileOutputStream out = new FileOutputStream(file(c))) {
                out.write(j.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (JSONException e) {
            throw new IOException(e);
        }
        if (!old.server.equals(server) || !old.share.equals(share) || !old.path.equals(path)) {
            //noinspection ResultOfMethodCallIgnored
            PhotoSource.cacheFile(c).delete();
        }
    }

    private static String read(File f) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        return out.toString("UTF-8");
    }
}
