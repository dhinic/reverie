package app.reverie;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Remote-friendly settings in three sections picked from a rail: Connection (address, login, Connect, then share
 * and folder once the login works), Slideshow, Videos. Every change is saved at once.
 * All network work runs on {@link #io}; results come back on the main thread.
 */
public class SettingsActivity extends Activity {
    private static final int[] SECONDS = {3, 5, 10, 15, 30, 60};
    private static final int[] VIDEO_SECONDS = {15, 30, 60, 120, 300};

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private Config cfg;
    private LinearLayout addressRow, userRow, passRow, shareRow, folderRow, secondsRow, videoLenRow, soundRow, videosRow;
    private TextView connect, connectedStatus, error;
    private LinearLayout[] panels;
    private TextView[] chips;
    private boolean connected;      // the current address + login were seen to work; unlocks share and folder
    private AlertDialog busy;
    private SmbBrowser browser;     // only touched on io

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        cfg = Config.loadOrEmpty(this);
        LinearLayout list = Ui.page(this, "Settings", "Changes are saved as you make them");

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setClipChildren(false);
        LinearLayout rail = new LinearLayout(this);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setClipChildren(false);
        rail.setClipToPadding(false);
        rail.setPadding(0, Ui.dp(this, 4), Ui.dp(this, 24), 0);
        chips = new TextView[]{Ui.railChip(this, "Connection"), Ui.railChip(this, "Slideshow"), Ui.railChip(this, "Videos")};
        for (TextView c : chips) rail.addView(c, Ui.margin(this, 0, 10));
        body.addView(rail);

        // --- Connection
        LinearLayout connection = Ui.panel(this);
        addressRow = Ui.row(this, "NAS address", "");
        userRow = Ui.row(this, "Username", "");
        passRow = Ui.row(this, "Password", "");
        LinearLayout buttons = Ui.buttonRow(this);
        TextView find = Ui.pill(this, "Find NAS on network", false);
        connect = Ui.pill(this, "Connect", true);
        connectedStatus = Ui.status(this, "Connected", Ui.OK);
        buttons.addView(find);
        buttons.addView(connect);
        buttons.addView(connectedStatus);
        error = Ui.text(this, "", 16, Ui.ERROR, Ui.REGULAR);
        error.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), Ui.dp(this, 12));
        error.setVisibility(View.GONE);
        shareRow = Ui.row(this, "Share", "");
        folderRow = Ui.row(this, "Folder", "");
        for (View v : new View[]{addressRow, userRow, passRow, buttons, error, shareRow, folderRow}) connection.addView(v);

        // --- Slideshow
        LinearLayout slideshow = Ui.panel(this);
        secondsRow = Ui.row(this, "Seconds per photo", "");
        slideshow.addView(secondsRow);

        // --- Videos
        LinearLayout videos = Ui.panel(this);
        videosRow = Ui.row(this, "Include videos", "");
        videoLenRow = Ui.row(this, "Longest video shown", "");
        soundRow = Ui.row(this, "Video sound", "");
        for (View v : new View[]{videosRow, videoLenRow, soundRow}) videos.addView(v);

        panels = new LinearLayout[]{connection, slideshow, videos};
        LinearLayout holder = new LinearLayout(this);
        holder.setOrientation(LinearLayout.VERTICAL);
        holder.setClipChildren(false);
        for (LinearLayout p : panels) holder.addView(p);
        body.addView(holder, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        list.addView(body);

        TextView start = Ui.pill(this, "▶   Start", true);
        rail.addView(start, Ui.margin(this, 24, 0));

        for (int i = 0; i < chips.length; i++) {
            final int n = i;
            chips[i].setOnClickListener(v -> showPanel(n));
        }
        addressRow.setOnClickListener(v -> askAddress());
        userRow.setOnClickListener(v -> askText("Username (empty for guest)", cfg.user, false, s -> {
            cfg.user = s;
            loginChanged();
        }));
        passRow.setOnClickListener(v -> askText("Password", cfg.password, true, s -> {
            cfg.password = s;
            loginChanged();
        }));
        find.setOnClickListener(v -> chooseNas());
        connect.setOnClickListener(v -> connect(false));
        shareRow.setOnClickListener(v -> askShare());
        folderRow.setOnClickListener(v -> browseFolders());
        secondsRow.setOnClickListener(v -> choose("Seconds per photo", SECONDS, cfg.secondsPerPhoto, n -> {
            cfg.secondsPerPhoto = n;
            changed();
        }));
        videoLenRow.setOnClickListener(v -> choose("Longest video shown (seconds)", VIDEO_SECONDS, cfg.maxVideoSeconds,
                n -> {
                    cfg.maxVideoSeconds = n;
                    changed();
                }));
        soundRow.setOnClickListener(v -> {
            cfg.videoSound = !cfg.videoSound;
            changed();
        });
        videosRow.setOnClickListener(v -> {
            cfg.includeVideos = !cfg.includeVideos;
            changed();
        });
        start.setOnClickListener(v -> {
            if (!cfg.isComplete()) {
                toast("Connect and choose a share first");
                return;
            }
            startActivity(new Intent(this, PhotoActivity.class));
            finish();
        });

        // a saved share means this login worked before: unlock now, re-check quietly
        connected = !cfg.share.isEmpty();
        showPanel(0);
        refresh();
        addressRow.requestFocus();
        if (connected) connect(true);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        dismissBusy();
        io.execute(this::closeBrowser);
        io.shutdown();
    }

    // ---- state ---------------------------------------------------------------------------------------------

    private void showPanel(int n) {
        for (int i = 0; i < panels.length; i++) {
            panels[i].setVisibility(i == n ? View.VISIBLE : View.GONE);
            Ui.setChipOn(chips[i], i == n);
        }
    }

    private void changed() {
        try {
            cfg.save(this);
        } catch (IOException e) {
            toast("Could not save: " + e.getMessage());
        }
        refresh();
    }

    /** Address or login edited: share and folder belong to the old login, so lock them until Connect works again. */
    private void loginChanged() {
        connected = false;
        showError(null);
        changed();
    }

    private void refresh() {
        Ui.setRow(addressRow, "NAS address", cfg.server.isEmpty() ? "Not set" : cfg.server);
        Ui.setRow(userRow, "Username", cfg.isGuest() ? "Guest" : cfg.user);
        Ui.setRow(passRow, "Password", cfg.password.isEmpty() ? "None" : "••••••••");
        connect.setVisibility(connected ? View.GONE : View.VISIBLE);
        connectedStatus.setVisibility(connected ? View.VISIBLE : View.GONE);
        Ui.setRow(shareRow, "Share", cfg.share.isEmpty() ? "Not set" : cfg.share);
        Ui.setRow(folderRow, "Folder", cfg.share.isEmpty() ? "Choose the share first"
                : (cfg.path.isEmpty() ? "Whole share" : cfg.path.replace('\\', '/')) + "   ›");
        Ui.setLocked(shareRow, !connected, "Connect first");
        Ui.setLocked(folderRow, !connected, "Connect first");
        Ui.setRow(secondsRow, "Seconds per photo", String.valueOf(cfg.secondsPerPhoto));
        Ui.setRow(videoLenRow, "Longest video shown", cfg.maxVideoSeconds + " seconds");
        Ui.setRow(soundRow, "Video sound", cfg.videoSound ? "On" : "Off");
        Ui.setRow(videosRow, "Include videos", cfg.includeVideos ? "Yes" : "Photos only");
    }

    private void showError(String s) {
        error.setText(s == null ? "" : s);
        error.setVisibility(s == null ? View.GONE : View.VISIBLE);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private AlertDialog.Builder dialog(String title) {
        return new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle(title);
    }

    /** Stock list dialogs chop the last visible row; fade it out and leave room under it instead. */
    private AlertDialog showList(AlertDialog.Builder b) {
        AlertDialog d = b.show();
        android.widget.ListView l = d.getListView();
        if (l != null) {
            l.setPadding(0, 0, 0, Ui.dp(this, 24));
            l.setClipToPadding(false);
            l.setScrollIndicators(0);           // no hard lines above and below the list
            l.setVerticalFadingEdgeEnabled(true);
            l.setFadingEdgeLength(Ui.dp(this, 96));
        }
        return d;
    }

    private void showBusy(String text) {
        dismissBusy();
        busy = dialog(text).setCancelable(false).create();
        busy.show();
    }

    private void dismissBusy() {
        if (busy != null) {
            busy.dismiss();
            busy = null;
        }
    }

    private interface IntSink { void accept(int n); }
    private interface StringSink { void accept(String s); }

    private void choose(String title, int[] values, int current, IntSink sink) {
        String[] labels = new String[values.length];
        int sel = 0;
        for (int i = 0; i < values.length; i++) {
            labels[i] = String.valueOf(values[i]);
            if (values[i] == current) sel = i;
        }
        dialog(title).setSingleChoiceItems(labels, sel, (d, which) -> {
            d.dismiss();
            sink.accept(values[which]);
        }).setNegativeButton("Cancel", null).show();
    }

    private EditText field(String hint, String value, boolean password) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT
                | (password ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_VARIATION_URI));
        return e;
    }

    private void askText(String title, String value, boolean password, StringSink sink) {
        final EditText e = field(title, value, password);
        dialog(title).setView(e)
                .setPositiveButton("OK", (d, w) -> sink.accept(e.getText().toString().trim()))
                .setNegativeButton("Cancel", null).show();
    }

    // ---- NAS and login -------------------------------------------------------------------------------------

    private void chooseNas() {
        showBusy("Looking for NAS devices on your network…");
        io.execute(() -> {
            final List<String> found = SmbScanner.scan(this);
            runOnUiThread(() -> {
                if (isFinishing() || busy == null) return;
                dismissBusy();
                final List<String> items = new ArrayList<>(found);
                items.add("Enter address manually…");
                String title = found.isEmpty() ? "No NAS found" : "Choose your NAS";
                showList(dialog(title).setItems(items.toArray(new String[0]), (d, which) -> {
                    if (which == items.size() - 1) askAddress();
                    else useServer(items.get(which));
                }).setNegativeButton("Cancel", null));
            });
        });
    }

    private void askAddress() {
        askText("NAS address, e.g. 192.168.1.10", cfg.server, false, s -> {
            if (!s.isEmpty()) useServer(s);
        });
    }

    private void useServer(final String server) {
        if (!server.equals(cfg.server)) {
            cfg.share = "";
            cfg.path = "";
        }
        cfg.server = server;
        loginChanged();
    }

    /** Tries the address + login. Quiet = no busy text on the button (the re-check when the page opens). */
    private void connect(final boolean quiet) {
        if (cfg.server.isEmpty()) {
            showError("Enter the NAS address first");
            return;
        }
        final Config snapshot = cfg.copy();
        if (!quiet) connect.setText("Connecting…");
        io.execute(() -> {
            String err = null;
            try (SmbBrowser b = new SmbBrowser(snapshot.server, snapshot.user, snapshot.password, snapshot.domain)) {
                // reached and logged in
            } catch (IOException e) {
                err = e.getMessage();
            }
            final String result = err;
            runOnUiThread(() -> {
                if (isFinishing()) return;
                connect.setText("Connect");
                if (!snapshot.server.equals(cfg.server) || !snapshot.user.equals(cfg.user)
                        || !snapshot.password.equals(cfg.password)) return;     // edited meanwhile
                connected = result == null;
                showError(result);
                refresh();
                if (connected && cfg.share.isEmpty() && !quiet) askShare();
            });
        });
    }

    // ---- share and folder ----------------------------------------------------------------------------------

    private void askShare() {
        askText("Share name on " + cfg.server, cfg.share, false, s -> {
            if (s.isEmpty()) return;
            if (!s.equals(cfg.share)) cfg.path = "";
            cfg.share = s;
            changed();
            browseFolders();
        });
    }

    private void browseFolders() {
        if (cfg.share.isEmpty()) {
            askShare();
            return;
        }
        showBusy("Opening " + cfg.share + "…");
        final Config snapshot = cfg.copy();
        io.execute(() -> {
            try {
                closeBrowser();
                browser = new SmbBrowser(snapshot.server, snapshot.user, snapshot.password, snapshot.domain);
                browser.openShare(snapshot.share);
                showFolder(snapshot.path);
            } catch (IOException e) {
                final String msg = e.getMessage();
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    dismissBusy();
                    showError("Could not open \\\\" + snapshot.server + "\\" + snapshot.share + ": " + msg);
                });
            }
        });
    }

    private void closeBrowser() {
        if (browser != null) browser.close();
        browser = null;
    }

    /** On io: lists path and shows the picker on the main thread. */
    private void showFolder(final String path) {
        final List<String> subs;
        try {
            subs = browser.folders(path);
        } catch (IOException e) {
            final String msg = e.getMessage();
            runOnUiThread(() -> {
                dismissBusy();
                toast("Cannot list that folder: " + msg);
            });
            return;
        }
        runOnUiThread(() -> {
            if (isFinishing()) return;
            dismissBusy();
            final List<String> items = new ArrayList<>();
            items.add("✔  Use this folder");
            final boolean canGoUp = !path.isEmpty();
            if (canGoUp) items.add("⬆  Up");
            final int offset = items.size();
            items.addAll(subs);
            String title = "\\\\" + cfg.share + (path.isEmpty() ? "" : "\\" + path);
            showList(dialog(title).setItems(items.toArray(new String[0]), (d, which) -> {
                if (which == 0) {
                    cfg.path = path;
                    changed();
                    io.execute(this::closeBrowser);
                } else if (canGoUp && which == 1) {
                    int cut = path.lastIndexOf('\\');
                    final String parent = cut < 0 ? "" : path.substring(0, cut);
                    io.execute(() -> showFolder(parent));
                } else {
                    final String child = path.isEmpty() ? subs.get(which - offset) : path + "\\" + subs.get(which - offset);
                    io.execute(() -> showFolder(child));
                }
            }).setNegativeButton("Cancel", (DialogInterface d, int w) -> io.execute(this::closeBrowser)));
        });
    }
}
