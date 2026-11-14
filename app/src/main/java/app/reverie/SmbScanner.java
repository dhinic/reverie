package app.reverie;

import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.content.Context;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Finds devices on the local /24 network that answer on the SMB port (445). Blocks; run it off the main thread. */
final class SmbScanner {
    private SmbScanner() { }

    /** Dotted addresses, sorted; empty when the TV has no IPv4 network. */
    static List<String> scan(Context c) {
        String base = localPrefix(c);
        if (base == null) return Collections.emptyList();
        final List<String> found = Collections.synchronizedList(new ArrayList<String>());
        ExecutorService pool = Executors.newFixedThreadPool(48);
        for (int i = 1; i < 255; i++) {
            final String ip = base + i;
            pool.execute(() -> {
                try (Socket s = new Socket()) {
                    s.connect(new InetSocketAddress(ip, 445), 600);
                    found.add(ip);
                } catch (Exception ignored) { }
            });
        }
        pool.shutdown();
        try { pool.awaitTermination(30, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        List<String> out = new ArrayList<>(found);
        Collections.sort(out, (a, b) -> Integer.compare(last(a), last(b)));
        return out;
    }

    private static int last(String ip) {
        return Integer.parseInt(ip.substring(ip.lastIndexOf('.') + 1));
    }

    /** "192.168.1." from the active network, or null. */
    private static String localPrefix(Context c) {
        ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network n = cm == null ? null : cm.getActiveNetwork();
        LinkProperties lp = n == null ? null : cm.getLinkProperties(n);
        if (lp == null) return null;
        for (LinkAddress a : lp.getLinkAddresses()) {
            InetAddress ia = a.getAddress();
            if (ia instanceof Inet4Address && !ia.isLoopbackAddress()) {
                String h = ia.getHostAddress();
                return h.substring(0, h.lastIndexOf('.') + 1);
            }
        }
        return null;
    }
}
