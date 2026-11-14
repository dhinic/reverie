package app.reverie;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.View;
import android.view.TextureView;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.security.Security;
import java.util.ArrayList;
import java.util.List;

/**
 * Black full-screen slideshow of random photos and videos from {@link PhotoSource}. Photos crossfade; a video
 * plays to its end (or the configured cap). The worker keeps a history of what was shown, so Up goes back and
 * Down goes forward ({@link #handleKey}). Used by both the screensaver and the activity.
 */
final class SlideshowView extends FrameLayout {
    private static final String TAG = "Reverie";
    private static final long FADE_MS = 1200;
    // Not 0: a TextureView with alpha 0 is skipped when drawing, so its frames would pile up unseen.
    private static final float VIDEO_HIDDEN = 0.01f;

    static {
        // Android ships a stripped-down "BC" provider without MD4, which SMB's NTLM login needs.
        Security.removeProvider("BC");
        Security.addProvider(new BouncyCastleProvider());
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final TextView status;
    // start-up screen: the mark spinning over a message, until the first photo or video is on screen
    private final LinearLayout loadBox;
    private final LoadingView loader;
    private final TextView loadMsg;
    private final TextView loadCaption;
    private boolean firstShown;
    private final TextureView videoView;
    private ImageView top;
    private ImageView bottom;
    private Bitmap topBitmap;
    private Bitmap bottomBitmap;

    // main thread only
    private Surface videoSurface;
    private ExoPlayer player;
    private SmbMediaDataSource playerSource;
    private boolean fadedIn;
    // The next video, opened and buffered while a photo is still on screen (no surface until it is shown).
    private ExoPlayer pending;
    private SmbMediaDataSource pendingSource;
    private volatile String pendingPath;

    private volatile boolean running;
    private Thread worker;
    private PhotoSource source;

    // navigation signal: -1 back, +1 next (also set when a video ends), 0 none
    private final Object navLock = new Object();
    private int nav;
    private volatile boolean videoError;

    SlideshowView(Context c) {
        super(c);
        setBackgroundColor(Color.BLACK);
        videoView = new TextureView(c);
        videoView.setAlpha(VIDEO_HIDDEN);
        videoView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                videoSurface = new Surface(st);
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                releasePlayer();
                if (videoSurface != null) videoSurface.release();
                videoSurface = null;
                return true;
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture st) { }
        });
        addView(videoView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        bottom = newImage(c);
        top = newImage(c);
        addView(bottom);
        addView(top);
        loadBox = new LinearLayout(c);
        loadBox.setOrientation(LinearLayout.VERTICAL);
        loadBox.setGravity(Gravity.CENTER_HORIZONTAL);
        loader = new LoadingView(c);
        loader.setSpinning(true);
        loadBox.addView(loader, new LinearLayout.LayoutParams(Ui.dp(c, 96), Ui.dp(c, 96)));
        loadMsg = Ui.text(c, "", 24, Ui.TEXT, Ui.LIGHT);
        loadMsg.setGravity(Gravity.CENTER);
        loadMsg.setPadding(0, Ui.dp(c, 32), 0, 0);
        // wrap_content: a match_parent TextView keeps its first (empty) width after setText
        loadBox.addView(loadMsg, Ui.margin(c, 0, 0));
        loadCaption = Ui.text(c, "", 17, Ui.TEXT_45, Ui.REGULAR);
        loadCaption.setPadding(0, Ui.dp(c, 8), 0, 0);
        loadBox.addView(loadCaption, Ui.margin(c, 0, 0));
        addView(loadBox, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        TextView wordmark = Ui.text(c, "R E V E R I E", 15, Ui.TEXT_45, Ui.MEDIUM);
        LayoutParams wmLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        wmLp.setMargins(Ui.dp(c, 96), Ui.dp(c, 48), 0, 0);
        loadBox.setTag(wordmark);
        addView(wordmark, wmLp);
        status = new TextView(c);
        Ui.chip(status);
        status.setVisibility(GONE);
        LayoutParams statusLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START);
        statusLp.setMargins(Ui.dp(c, 48), 0, 0, Ui.dp(c, 32));   // keep inside the TV safe area
        addView(status, statusLp);
    }

    private static ImageView newImage(Context c) {
        ImageView v = new ImageView(c);
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        v.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        return v;
    }

    /** Up/Left: previous item. Down/Right: next item. Returns true when the key was used. */
    boolean handleKey(KeyEvent e) {
        int k = e.getKeyCode();
        int d;
        if (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_LEFT) d = -1;
        else if (k == KeyEvent.KEYCODE_DPAD_DOWN || k == KeyEvent.KEYCODE_DPAD_RIGHT) d = 1;
        else return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) signal(d, true);
        return true;
    }

    private void signal(int d, boolean force) {
        Log.i(TAG, "nav " + d + (force ? " (key)" : ""));
        PhotoSource s = source;
        if (force && s != null) s.abortPrefetch = true;
        synchronized (navLock) {
            if (force || nav == 0) nav = d;
            navLock.notifyAll();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        running = true;
        worker = new Thread(this::run, "reverie-worker");
        worker.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        running = false;
        signal(1, true);
        if (worker != null) worker.interrupt();
        PhotoSource s = source;
        if (s != null) new Thread(s::close, "reverie-close").start();
        releasePlayer();
        releasePending();
        top.animate().cancel();
        bottom.animate().cancel();
        top.setImageDrawable(null);
        bottom.setImageDrawable(null);
        recycle(topBitmap);
        recycle(bottomBitmap);
        topBitmap = bottomBitmap = null;
    }

    private static void recycle(Bitmap b) {
        if (b != null && !b.isRecycled()) b.recycle();
    }

    /** Before the first item: the message under the spinning mark. After it: the dim chip bottom-left. */
    private void setStatus(final String s) {
        setStatus(s, true);
    }

    private void setStatus(final String s, final boolean spinning) {
        main.post(() -> {
            if (!firstShown) {
                loadMsg.setText(s == null ? "" : s);
                loader.setSpinning(spinning);
                return;
            }
            status.setText(s == null ? "" : s);
            status.setVisibility(s == null || s.isEmpty() ? GONE : VISIBLE);
        });
    }

    /** Main thread: the first photo or video is coming up, fade the start-up screen away under it. */
    private void hideLoader() {
        if (firstShown) return;
        firstShown = true;
        View wordmark = (View) loadBox.getTag();
        for (View v : new View[]{loadBox, wordmark}) {
            v.animate().alpha(0f).setDuration(FADE_MS).withEndAction(() -> {
                v.setVisibility(GONE);
                loader.setSpinning(false);
            }).start();
        }
    }

    private boolean sleep(long ms) {
        try {
            Thread.sleep(ms);
            return running;
        } catch (InterruptedException e) {
            return false;
        }
    }

    /** Waits up to ms for a key or a finished video; returns -1 (back) or +1 (next, also on timeout). */
    private int waitNav(long end) {
        synchronized (navLock) {
            try {
                while (nav == 0 && running) {
                    long rest = end - System.currentTimeMillis();
                    if (rest <= 0) break;
                    navLock.wait(rest);
                }
            } catch (InterruptedException e) {
                return 1;
            }
            int d = nav == 0 ? 1 : nav;
            nav = 0;
            return d;
        }
    }

    /** Dwell on the current item; "back" on the very first item is ignored. */
    private int dwell(long ms, int idx) {
        long end = System.currentTimeMillis() + Math.max(0, ms);
        while (true) {
            int d = waitNav(end);
            if (d == -1 && idx == 0 && running) continue;
            return d;
        }
    }

    // ---- worker thread -------------------------------------------------------------------------------------

    private void run() {
        final Config cfg;
        try {
            cfg = Config.load(getContext());
        } catch (Exception e) {
            setStatus("Reverie: " + e.getMessage());
            return;
        }
        final PhotoSource src = new PhotoSource(getContext(), cfg);
        source = src;
        main.post(() -> loadCaption.setText(cfg.path.isEmpty() ? cfg.share : cfg.path.replace('\\', '/')));
        setStatus("Connecting to " + cfg.server + "…");

        Thread walker = new Thread(() -> {
            while (running) {
                try {
                    src.loadListing();
                    return;
                } catch (Exception e) {
                    Log.w(TAG, "listing failed: " + e);
                    setStatus("Listing failed: " + e.getMessage());
                    if (!sleep(15000)) return;
                }
            }
        }, "reverie-walker");
        walker.start();

        DisplayMetrics dm = getResources().getDisplayMetrics();
        int w = Math.max(1280, dm.widthPixels);
        int h = Math.max(720, dm.heightPixels);
        long interval = cfg.secondsPerPhoto * 1000L;

        List<String> hist = new ArrayList<>();
        int idx = -1;          // index of what is on screen
        int target = 0;        // index to show next
        int fails = 0;
        Bitmap prefetched = null;
        int prefetchedIdx = -1;

        try {
            while (running) {
                while (hist.size() <= target) {
                    String p = src.randomPath();
                    if (p == null) break;
                    hist.add(p);
                }
                if (hist.size() <= target) {
                    if (src.isListed()) setStatus("No photos found in that folder", false);
                    else if (src.count() == 0) setStatus("Connecting to " + cfg.server + "…");
                    else setStatus(String.format("Listing photos…  %,d found", src.count()));
                    if (!sleep(1500)) break;
                    continue;
                }
                String path = hist.get(target);
                boolean video = PhotoSource.isVideo(path);
                // keep a photo prefetched for just after this video
                if (prefetchedIdx != target && !(video && prefetchedIdx == target + 1)) {
                    recycle(prefetched);
                    prefetched = null;
                    prefetchedIdx = -1;
                }
                Bitmap bmp = null;
                SmbMediaDataSource ds = null;
                boolean adopt = false;      // the video was already buffered ahead of time
                if (video) {
                    if (path.equals(pendingPath)) adopt = true;
                    else ds = openVideo(src, path);
                } else if (prefetched != null && prefetchedIdx == target) {
                    bmp = prefetched;
                    prefetched = null;
                    prefetchedIdx = -1;
                } else {
                    bmp = loadPhoto(src, path, w, h);
                }
                if (bmp == null && ds == null && !adopt) {
                    fails++;
                    int dir = (target >= idx || target == 0) ? 1 : -1;
                    target = Math.max(0, target + dir);
                    if (!sleep(Math.min(20000, 1500L * fails))) break;
                    continue;
                }
                fails = 0;
                setStatus(null);
                idx = target;
                Log.i(TAG, "show #" + idx + (video ? " video" : " photo") + (adopt ? " (prebuffered)" : ""));

                long t0 = System.currentTimeMillis();
                if (video) {
                    videoError = false;
                    startVideo(path, ds, cfg.videoSound);
                } else {
                    show(bmp);
                }

                // Look ahead while this item is on screen. A video next is opened and buffered in the
                // background, and the photo after it is still loaded meanwhile.
                int next = idx + 1;
                fillHistory(src, hist, next);
                if (hist.size() > next && PhotoSource.isVideo(hist.get(next))) {
                    prepareVideoAhead(src, hist.get(next));
                    next++;
                    fillHistory(src, hist, next);
                }
                if (hist.size() > next && !PhotoSource.isVideo(hist.get(next)) && prefetchedIdx != next) {
                    recycle(prefetched);
                    src.abortPrefetch = false;
                    prefetched = loadPhoto(src, hist.get(next), w, h, true);
                    prefetchedIdx = prefetched == null ? -1 : next;
                }

                int delta;
                if (video) {
                    delta = dwell(cfg.maxVideoSeconds * 1000L, idx);
                    if (videoError && running && !sleep(1500)) break;
                } else {
                    delta = dwell(interval - (System.currentTimeMillis() - t0), idx);
                }
                target = Math.max(0, idx + delta);
            }
        } finally {
            recycle(prefetched);
        }
    }

    private Bitmap loadPhoto(PhotoSource src, String path, int w, int h) {
        return loadPhoto(src, path, w, h, false);
    }

    private Bitmap loadPhoto(PhotoSource src, String path, int w, int h, boolean prefetch) {
        try {
            return src.loadPhoto(path, w, h, prefetch);
        } catch (OutOfMemoryError e) {
            Log.w(TAG, "out of memory loading a photo");
            System.gc();
            return null;
        } catch (Exception e) {
            Log.w(TAG, "load failed: " + e);
            setStatus("Network: " + e.getMessage());
            return null;
        }
    }

    private SmbMediaDataSource openVideo(PhotoSource src, String path) {
        try {
            return src.openVideo(path);
        } catch (Exception e) {
            Log.w(TAG, "video open failed: " + e);
            setStatus("Network: " + e.getMessage());
            return null;
        }
    }

    // ---- main thread ---------------------------------------------------------------------------------------

    private void show(final Bitmap b) {
        main.post(() -> {
            if (!isAttachedToWindow()) {
                recycle(b);
                return;
            }
            releasePlayerAfter(player, FADE_MS);
            hideLoader();
            final ImageView incoming = bottom;
            final ImageView outgoing = top;
            final Bitmap outgoingBitmap = topBitmap;
            incoming.setImageBitmap(b);
            bottomBitmap = b;
            incoming.setBackgroundColor(Color.BLACK);
            incoming.setAlpha(0f);
            incoming.bringToFront();
            status.bringToFront();
            incoming.animate().alpha(1f).setDuration(FADE_MS).withEndAction(() -> {
                outgoing.setImageDrawable(null);
                outgoing.setBackgroundColor(Color.TRANSPARENT);   // must not hide a video underneath
                recycle(outgoingBitmap);
                top = incoming;
                topBitmap = bottomBitmap;
                bottom = outgoing;
                bottomBitmap = null;
            }).start();
        });
    }

    private static void fillHistory(PhotoSource src, List<String> hist, int upTo) {
        while (hist.size() <= upTo) {
            String p = src.randomPath();
            if (p == null) break;
            hist.add(p);
        }
    }

    /** Worker: opens the video and lets the player buffer it with no surface, so it starts at once when its turn comes. */
    private void prepareVideoAhead(PhotoSource src, final String path) {
        if (path.equals(pendingPath)) return;
        final SmbMediaDataSource ds = openVideo(src, path);
        if (ds == null) return;
        main.post(() -> {
            releasePending();
            ExoPlayer p = isAttachedToWindow() ? newPlayer(ds) : null;
            if (p == null) {
                closeAsync(ds);
                return;
            }
            pending = p;
            pendingSource = ds;
            pendingPath = path;
        });
    }

    /** Starts the video: adopts the one buffered ahead of time if it is this one, else opens it now (ds). */
    private void startVideo(final String path, final SmbMediaDataSource ds, final boolean sound) {
        main.post(() -> {
            if (!isAttachedToWindow() || videoSurface == null) {
                if (ds != null) closeAsync(ds);
                videoError = true;
                signal(1, false);
                return;
            }
            releasePlayer();
            fadedIn = false;
            ExoPlayer p;
            SmbMediaDataSource source;
            if (pending != null && path.equals(pendingPath)) {
                p = pending;
                source = pendingSource;
                pending = null;
                pendingSource = null;
                pendingPath = null;
            } else {
                releasePending();
                p = ds == null ? null : newPlayer(ds);
                source = ds;
                if (p == null) {
                    if (ds != null) closeAsync(ds);
                    videoError = true;
                    signal(1, false);
                    return;
                }
            }
            player = p;
            playerSource = source;
            hideLoader();
            float vol = sound ? 1f : 0f;
            p.setVolume(vol);
            p.setVideoSurface(videoSurface);
            p.setPlayWhenReady(true);
            // safety net in case the first rendered frame is never reported
            final ExoPlayer started = p;
            main.postDelayed(() -> { if (player == started) fadeInVideo(); }, 2500);
        });
    }

    /**
     * Creates a player and starts buffering without playing. ExoPlayer, unlike MediaPlayer, falls back from a
     * Dolby Vision track (iPhone .mov) to its HEVC base layer when the box has no Dolby Vision decoder.
     */
    private ExoPlayer newPlayer(SmbMediaDataSource ds) {
        try {
            final ExoPlayer p = new ExoPlayer.Builder(getContext(), new DefaultRenderersFactory(getContext())
                    .setEnableDecoderFallback(true))
                    .setLoadControl(new DefaultLoadControl.Builder().setBufferDurationsMs(10000, 30000, 1500, 3000)
                            .setTargetBufferBytes(24 << 20).setPrioritizeTimeOverSizeThresholds(false).build())
                    .build();
            p.addAnalyticsListener(new androidx.media3.exoplayer.analytics.AnalyticsListener() {
                @Override public void onVideoDecoderInitialized(
                        androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime t, String name, long a, long b) {
                    Log.i(TAG, "video decoder: " + name);
                }
                @Override public void onDroppedVideoFrames(
                        androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime t, int n, long ms) {
                    Log.w(TAG, "dropped " + n + " video frames in " + ms + " ms");
                }
            });
            p.addListener(new Player.Listener() {
                @Override public void onVideoSizeChanged(VideoSize v) {
                    if (player == p) fitVideo(Math.round(v.width * v.pixelWidthHeightRatio), v.height);
                }
                // Fade in only once a frame is on screen; before that the texture holds the previous video's last frame.
                @Override public void onRenderedFirstFrame() {
                    if (player == p) fadeInVideo();
                }
                @Override public void onPlaybackStateChanged(int state) {
                    Log.i(TAG, (player == p ? "video " : "pending ") + "state " + state + " at " + p.getCurrentPosition()
                            + "/" + p.getDuration() + " ms, buffered " + p.getBufferedPercentage() + "%");
                    if (state == Player.STATE_ENDED && player == p) signal(1, false);
                }
                @Override public void onPlayerError(PlaybackException e) {
                    Log.w(TAG, "video error " + e.getErrorCodeName() + ": " + e.getMessage());
                    if (player == p) {
                        videoError = true;
                        setStatus("Cannot play this video");
                        signal(1, false);
                    } else if (pending == p) {
                        releasePending();   // it will be opened normally when its turn comes
                    }
                }
            });
            p.setMediaSource(new ProgressiveMediaSource.Factory(() -> new SmbDataSource(ds))
                    .createMediaSource(MediaItem.fromUri("smb://nas/video")));
            p.prepare();
            return p;
        } catch (Exception e) {
            Log.w(TAG, "video start failed: " + e);
            return null;
        }
    }

    private void releasePending() {
        ExoPlayer p = pending;
        SmbMediaDataSource ds = pendingSource;
        pending = null;
        pendingSource = null;
        pendingPath = null;
        if (p != null) {
            try { p.release(); } catch (Exception ignored) { }
        }
        if (ds != null) closeAsync(ds);
    }

    private void fadeInVideo() {
        if (fadedIn) return;
        fadedIn = true;
        status.bringToFront();
        videoView.animate().alpha(1f).setDuration(FADE_MS).start();
        final ImageView v = top;
        final Bitmap bm = topBitmap;
        v.animate().alpha(0f).setDuration(FADE_MS).withEndAction(() -> {
            if (top == v) {
                v.setImageDrawable(null);
                recycle(bm);
                topBitmap = null;
            }
        }).start();
    }

    private void fitVideo(int vw, int vh) {
        int W = getWidth();
        int H = getHeight();
        if (vw <= 0 || vh <= 0 || W <= 0 || H <= 0) return;
        float s = Math.min((float) W / vw, (float) H / vh);
        LayoutParams lp = new LayoutParams(Math.round(vw * s), Math.round(vh * s), Gravity.CENTER);
        videoView.setLayoutParams(lp);
    }

    /** Silences the old video now and releases it once the photo has faded in over it. */
    private void releasePlayerAfter(final ExoPlayer old, long ms) {
        if (old == null) return;
        try { old.setVolume(0f); } catch (Exception ignored) { }
        main.postDelayed(() -> {
            if (player == old) releasePlayer();   // not if a newer video has started meanwhile
        }, ms);
    }

    private void releasePlayer() {
        ExoPlayer mp = player;
        SmbMediaDataSource ds = playerSource;
        player = null;
        playerSource = null;
        videoView.animate().cancel();
        videoView.setAlpha(VIDEO_HIDDEN);
        if (mp != null) {
            try { mp.release(); } catch (Exception ignored) { }
        }
        if (ds != null) closeAsync(ds);
    }

    /** Closing takes the SMB lock, so never do it on the main thread. */
    private static void closeAsync(final SmbMediaDataSource ds) {
        new Thread(ds::close, "reverie-video-close").start();
    }
}
