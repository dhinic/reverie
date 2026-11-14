package app.reverie;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.FileReader;

/** What the Home grid icon opens: start the slideshow, or change the settings. First run goes to Settings. */
public class MenuActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        final Config cfg = Config.loadOrEmpty(this);
        if (!cfg.isComplete()) {
            startActivity(new Intent(this, SettingsActivity.class));
            finish();
            return;
        }
        LinearLayout list = Ui.page(this, null, null);
        list.setGravity(Gravity.CENTER);
        LoadingView mark = new LoadingView(this);
        list.addView(mark, new LinearLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 64)));
        TextView title = Ui.text(this, "Reverie", 56, Ui.TEXT, Ui.LIGHT);
        title.setLayoutParams(Ui.margin(this, 12, 0));
        list.addView(title);
        list.addView(Ui.text(this, "Your photos and videos, drifting across the TV", 20, Ui.TEXT_75, Ui.REGULAR),
                Ui.margin(this, 0, 0));

        LinearLayout buttons = Ui.buttonRow(this);
        buttons.setGravity(Gravity.CENTER);
        buttons.setLayoutParams(Ui.margin(this, 28, 0));
        TextView start = Ui.pill(this, "▶   Start slideshow", true);
        TextView settings = Ui.pill(this, "Settings", false);
        buttons.addView(start);
        buttons.addView(settings);
        list.addView(buttons);

        start.setOnClickListener(v -> {
            startActivity(new Intent(this, PhotoActivity.class));
            finish();
        });
        settings.setOnClickListener(v -> {
            startActivity(new Intent(this, SettingsActivity.class));
            finish();
        });
        start.requestFocus();

        final TextView caption = Ui.pageCaption(list, cfg.server + " · " + folder(cfg));
        new Thread(() -> {
            final String counts = counts();
            android.util.Log.i("Reverie", "cached list counts: " + counts);
            if (counts != null) runOnUiThread(() -> caption.setText(caption.getText() + " · " + counts));
        }, "reverie-count").start();
    }

    private static String folder(Config cfg) {
        return cfg.path.isEmpty() ? cfg.share : cfg.path.replace('\\', '/');
    }

    /** "4,312 photos · 118 videos" from the cached list, or null when there is no cache yet. */
    private String counts() {
        int photos = 0, videos = 0;
        try (BufferedReader r = new BufferedReader(new FileReader(PhotoSource.cacheFile(this)))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (PhotoSource.isVideo(line)) videos++;
                else photos++;
            }
        } catch (Exception e) {
            android.util.Log.w("Reverie", "count failed: " + e);
            return null;
        }
        return String.format("%,d photos · %,d videos", photos, videos);
    }
}
