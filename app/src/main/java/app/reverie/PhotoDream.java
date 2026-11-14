package app.reverie;

import android.content.Intent;
import android.service.dreams.DreamService;
import android.view.KeyEvent;

/**
 * The system screensaver. Interactive, so a stray key does not wake the TV: Up/Down move through the
 * photos, Menu opens Settings, and Back, OK or Home leave.
 */
public class PhotoDream extends DreamService {
    private SlideshowView view;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(true);
        setFullscreen(true);
        view = new SlideshowView(this);
        setContentView(view);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (view != null && view.handleKey(e)) return true;
        if (e.getAction() == KeyEvent.ACTION_UP) {
            if (e.getKeyCode() == KeyEvent.KEYCODE_MENU) {
                Intent i = new Intent(this, SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            }
            finish();
        }
        return true;
    }
}
