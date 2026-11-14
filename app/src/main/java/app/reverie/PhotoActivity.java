package app.reverie;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.WindowManager;

/** Runs the slideshow on demand. Up/Down: previous/next. Menu or long-press OK: Settings. Back leaves. */
public class PhotoActivity extends Activity {
    private SlideshowView view;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        view = new SlideshowView(this);
        setContentView(view);
    }

    /** Leave for good when hidden (Home, or the screensaver on top): a stopped activity would keep playing video audio. */
    @Override
    protected void onStop() {
        super.onStop();
        finish();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean ok = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER;
        if ((k == KeyEvent.KEYCODE_MENU && e.getAction() == KeyEvent.ACTION_UP)
                || (ok && e.getAction() == KeyEvent.ACTION_DOWN && e.isLongPress())) {
            startActivity(new Intent(this, SettingsActivity.class));
            finish();
            return true;
        }
        if (view.handleKey(e)) return true;
        return super.dispatchKeyEvent(e);
    }
}
