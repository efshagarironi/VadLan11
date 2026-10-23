package com.mylauncher;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.service.quicksettings.TileService;
import android.text.*;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.*;
import android.view.animation.*;
import android.widget.*;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import org.json.*;

public class MainActivity extends Activity {
    static final int HOME = 0, SETTINGS = 1, BG = 2, BEHAVIOR = 3, LANG = 4, PICKER = 5,
            DOCK = 6, ICON = 8, MOCK = 9, FOLDER = 10, BACKUP = 11, RESTORE = 12,
            FONT = 13, FGRP = 14, FPICK = 15, DOCKBG = 16, HPAGE = 17, HFBG = 18, MBG = 19, TBG = 20, TBAR = 21, MBOX = 22, SBOX = 23, ACTS = 24, BUB = 25, BLBG = 26, SWL = 27, AIL = 28;
    static final int DLG = 0xFFEEEEEE;
    static final int REQ_IMG = 77, REQ_FIMG = 78, REQ_MIMG = 79, REQ_TIMG = 80;

    static class AppItem { String label, pkg; Drawable icon; }

    static class Item {
        boolean folder, docked;
        String pkg, id, name = "";
        int color = 0x3A28282C;
        int page = -1;
        List<String> apps = new ArrayList<>();
    }

    // neck that joins the open folder to the closed folder in the dock
    static class Bridge extends View {
        Paint pt = new Paint(Paint.ANTI_ALIAS_FLAG);
        Path ph = new Path();
        boolean on;
        float cx, top, bot, half, wf;
        int c1, c2;
        Bridge(Context c) { super(c); }
        void set(boolean o, float cx, float top, float bot, float half, float wf, int c1, int c2) {
            this.on = o; this.cx = cx; this.top = top; this.bot = bot;
            this.half = half; this.wf = wf; this.c1 = c1; this.c2 = c2;
            invalidate();
        }
        @Override
        protected void onDraw(Canvas c) {
            if (!on || bot - top < 1.5f) return;
            int[] l = new int[2];
            getLocationOnScreen(l);
            float t = top - l[1], b = bot - l[1], x = cx - l[0];
            float hf = Math.min(b - t, wf - half);
            ph.reset();
            ph.moveTo(x - wf, t - 0.5f);
            ph.quadTo(x - half, t - 0.5f, x - half, t + hf);
            ph.lineTo(x - half, b);
            ph.lineTo(x + half, b);
            ph.lineTo(x + half, t + hf);
            ph.quadTo(x + half, t - 0.5f, x + wf, t - 0.5f);
            ph.close();
            pt.setShader(new LinearGradient(0, t, 0, b, c1, c2, Shader.TileMode.CLAMP));
            c.drawPath(ph, pt);
        }
    }

    // tab that is part of the open folder: round on top, melted into the folder at the bottom,
    // edges (top, left, right) styled like the immersive folder: same radius, thin 0x33FFFFFF line
    static class TabDrawable extends Drawable {
        Paint pt = new Paint(Paint.ANTI_ALIAS_FLAG);
        Paint ln = new Paint(Paint.ANTI_ALIAS_FLAG);
        Path ph = new Path();
        Path ol = new Path();
        float r, fl, sw;
        TabDrawable(int color, int line, float r, float fl, float sw) {
            pt.setColor(color);
            ln.setStyle(Paint.Style.STROKE);
            ln.setColor(line);
            ln.setStrokeWidth(sw);
            this.r = r;
            this.fl = fl;
            this.sw = sw;
        }
        @Override
        public void draw(Canvas c) {
            Rect b = getBounds();
            float L = b.left + fl, R = b.right - fl, T = b.top, B = b.bottom;
            float r = Math.max(0f, Math.min(this.r, Math.min(B - T - fl, (R - L) / 2f)));
            ph.reset();
            ph.moveTo(L - fl, B);
            ph.quadTo(L, B, L, B - fl);
            ph.lineTo(L, T + r);
            ph.quadTo(L, T, L + r, T);
            ph.lineTo(R - r, T);
            ph.quadTo(R, T, R, T + r);
            ph.lineTo(R, B - fl);
            ph.quadTo(R, B, R + fl, B);
            ph.close();
            c.drawPath(ph, pt);
            float h = sw / 2f;
            float Lo = L + h, Ro = R - h, To = T + h;
            ol.reset();
            ol.moveTo(L - fl, B - h);
            ol.quadTo(Lo, B - h, Lo, B - fl);
            ol.lineTo(Lo, To + r);
            ol.quadTo(Lo, To, Lo + r, To);
            ol.lineTo(Ro - r, To);
            ol.quadTo(Ro, To, Ro, To + r);
            ol.lineTo(Ro, B - fl);
            ol.quadTo(Ro, B - h, R + fl, B - h);
            c.drawPath(ol, ln); // open path: no line on the side joined to the folder
        }
        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(ColorFilter f) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    // frame of the home page: like the immersive folder panel, with an optional picture and optional 3D edges
    static class FrameDrawable extends Drawable {
        Paint pt = new Paint(Paint.ANTI_ALIAS_FLAG);
        Paint ip = new Paint(Paint.FILTER_BITMAP_FLAG);
        Path cl = new Path();
        Matrix mx = new Matrix();
        int color, fade;
        boolean d3;
        float r, st, zoom, px, py;
        Bitmap src;
        FrameDrawable(int color, boolean d3, float r, float st, Bitmap src, float zoom, float px, float py, int fade) {
            this.color = color;
            this.d3 = d3;
            this.r = r;
            this.st = st;
            this.src = src;
            this.zoom = zoom;
            this.px = px;
            this.py = py;
            this.fade = fade;
        }
        @Override
        public void draw(Canvas c) {
            Rect b = getBounds();
            float L = b.left, T = b.top, R = b.right, B = b.bottom;
            float w = R - L, h = B - T;
            if (w < 4 || h < 4) return;
            float rr = Math.min(r, Math.min(w, h) / 2f);
            RectF rf = new RectF(L, T, R, B);
            cl.reset();
            cl.addRoundRect(rf, rr, rr, Path.Direction.CW);
            pt.setShader(null);
            pt.setStyle(Paint.Style.FILL);
            c.save();
            c.clipPath(cl);
            if (src != null) {
                float s = Math.max(w / src.getWidth(), h / src.getHeight()) * zoom;
                float iw = src.getWidth() * s, ih = src.getHeight() * s;
                mx.reset();
                mx.postScale(s, s);
                mx.postTranslate(L + (w - iw) * px, T + (h - ih) * py);
                c.drawBitmap(src, mx, ip);
                if (fade > 0) c.drawColor(((fade * 255 / 100) << 24) | (color & 0x00FFFFFF));
            } else {
                pt.setColor(color);
                c.drawRect(rf, pt);
            }
            if (d3) {
                float sh = Math.min(h / 3f, st * 12f);
                pt.setColor(0xFFFFFFFF);
                pt.setShader(new LinearGradient(0, T, 0, T + sh, 0x38FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
                c.drawRect(L, T, R, T + sh, pt);
                pt.setShader(new LinearGradient(0, B - sh, 0, B, 0x00000000, 0x40000000, Shader.TileMode.CLAMP));
                c.drawRect(L, B - sh, R, B, pt);
                pt.setShader(null);
            }
            c.restore();
            pt.setStyle(Paint.Style.STROKE);
            if (d3) {
                float h1 = st / 2f;
                pt.setStrokeWidth(st);
                pt.setShader(new LinearGradient(L, T, R, B,
                        new int[]{0xD0FFFFFF, 0x30FFFFFF, 0x30000000, 0xD0000000},
                        new float[]{0f, 0.45f, 0.55f, 1f}, Shader.TileMode.CLAMP));
                c.drawRoundRect(new RectF(L + h1, T + h1, R - h1, B - h1), rr - h1, rr - h1, pt);
                float w2 = Math.max(1f, st / 3f);
                float o = st + w2 / 2f;
                pt.setStrokeWidth(w2);
                pt.setShader(new LinearGradient(L, T, R, B,
                        new int[]{0x70000000, 0x00000000, 0x00FFFFFF, 0x60FFFFFF},
                        new float[]{0f, 0.45f, 0.55f, 1f}, Shader.TileMode.CLAMP));
                c.drawRoundRect(new RectF(L + o, T + o, R - o, B - o), rr - o, rr - o, pt);
                pt.setShader(null);
            } else {
                float w2 = Math.max(1f, st / 3f);
                float h1 = w2 / 2f;
                pt.setStrokeWidth(w2);
                pt.setColor(0x33FFFFFF);
                c.drawRoundRect(new RectF(L + h1, T + h1, R - h1, B - h1), rr - h1, rr - h1, pt);
            }
            pt.setStyle(Paint.Style.FILL);
        }
        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(ColorFilter f) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    // a control of the immersive folder that flies to the top bar
    static class Ctl {
        View v;
        int w, h;
        float sx, sy, ex, ey, s0;
    }

    SharedPreferences sp;
    int bg, fgc, page = HOME, dockPage1 = 0, shownPage = -1, openCx, openCy, openW = 100, openH = 100;
    boolean fa, plusOpen = false, editName = false, folderSettings = false,
            animFolder = false, animSettings = false, compact = false, dragFromDock = false,
            closing = false, immersive = false;
    View trashBtn, cancelBtn, plusMenuV, gridView, headV, dockBoxV, smallName, frameV;
    View homeMenuV, homePlusV, countBarV, topLineV, topBoxV;
    boolean menuHold = false;
    FrameLayout selLayer;
    View selBoxV, selSliderV;
    String selSig = "";
    int selBoxS = 0, dropTi = 0, dropIns = 0;
    boolean selSliderOpen = false, dragFromFolder = false, dragActive = false;
    Set<String> selDock = new HashSet<>();
    List<String> dragKeys = new ArrayList<>();
    int homeScrollY = 0;
    boolean fromDock = false;
    float prevCx = 0;
    Bitmap snapPanel, snapTabs;
    Bridge bridgeV;
    FrameLayout ovLayer;
    List<View> folderCtl = new ArrayList<>();
    Map<TextView, Float> baseSz = new WeakHashMap<>();
    Bitmap bgBmp, bgSrc;
    Bitmap mbgBmp, mbgSrc, hfSrc, tbSrc;
    Consumer<Float> folderApply;
    String fGrp = "s", fLang = "fa";
    Map<String, Typeface> faceCache = new HashMap<>();
    final int[] colors = {0xFF19191C, 0xFF222226, 0xFF121214, 0xFF000000,
            0xFF1B2A3A, 0xFF1E3326, 0xFF3B1F24, 0xFF2E2140};
    final int[] fcolors = {0x3A28282C, 0x66000000, 0x66E53935, 0x66FB8C00, 0x66FDD835, 0x6643A047,
            0x6600ACC1, 0x661E88E5, 0x665E35B1, 0x66D81B60, 0x668D6E63, 0x6690A4AE};

    FrameLayout container;
    View curPanel, normalBar, selBar;
    TextView selCount;
    FrameLayout dockHolder1;

    List<Item> home = new ArrayList<>();
    List<List<String>> dock1 = new ArrayList<>();
    Set<String> sel = new LinkedHashSet<>();
    Map<String, List<View>> cells = new HashMap<>();
    Map<String, List<View>> gears = new HashMap<>();
    String openFolder = null, pickTarget = null;
    String lastClosedFolder = null;
    int folderDir = 1;
    float swipeX0, swipeY0;
    boolean swipeFired;

    List<AppItem> allApps = new ArrayList<>();
    List<String> added = new ArrayList<>();
    Set<String> selected = new LinkedHashSet<>();
    String query = "";
    LinearLayout listBox;
    TextView allText;
    View allDot;
    Button addBtn;

    String t(String f, String e) { return fa ? f : e; }

    // ================= lifecycle =================
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences("l", 0);
        fa = sp.getString("lang", "fa").equals("fa");
        bg = sp.getInt("bg", colors[0]);
        loadBgImage();
        loadMenuImage();
        loadFrameImage();
        applyWindowBg();
        container = new FrameLayout(this);
        setContentView(container);
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }
        loadHome();
        loadDock();
        fixDocked();
        notifyBar();
        lastClosedFolder = sp.getString("lastClosed", null);
        if (sp.getBoolean("fpKeep", false)) {
            String kf = sp.getString("keepFolder", "");
            if (kf != null && !kf.isEmpty() && findFolder(kf) != null) {
                openFolder = kf;
                fromDock = findFolder(kf).docked;
            }
        }
        checkMockIntent(getIntent());
        // keep the shortcut tile state equal to the saved choice
        try {
            getPackageManager().setComponentEnabledSetting(new ComponentName(this, VadTile.class),
                    sp.getBoolean("tile", false) ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                            : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
        } catch (Exception e) { }
        try {
            if (sp.getBoolean("mock_on", false) && Settings.canDrawOverlays(this)) {
                startMock();
            }
        } catch (Exception e) { }
        show();
    }

    void startMock() {
        try {
            Intent i = new Intent(this, MockBubbleService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
        } catch (Exception e) { }
    }

    boolean hit(View v, MotionEvent ev) {
        if (v == null || v.getWidth() == 0) return false;
        int[] l = new int[2];
        v.getLocationOnScreen(l);
        float x = ev.getRawX(), y = ev.getRawY();
        return x >= l[0] && x <= l[0] + v.getWidth() * v.getScaleX()
                && y >= l[1] && y <= l[1] + v.getHeight() * v.getScaleY();
    }

    // touching anything outside the open folder closes the folder
    // (a closed folder can be touched: the new folder then replaces the open one)
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        // swipe up on the icon pages (paged mode): opens the folder that was closed last, on any page
        if (page == HOME && pagedMode() && openFolder == null && !dragActive) {
            int act = ev.getActionMasked();
            if (act == MotionEvent.ACTION_DOWN) {
                swipeX0 = ev.getRawX();
                swipeY0 = ev.getRawY();
                swipeFired = false;
            } else if (act == MotionEvent.ACTION_MOVE && !swipeFired) {
                float dx = ev.getRawX() - swipeX0, dy = ev.getRawY() - swipeY0;
                boolean aboveDock = true;
                if (dockBoxV != null && dockBoxV.getHeight() > 0) {
                    int[] dl = new int[2];
                    dockBoxV.getLocationOnScreen(dl);
                    aboveDock = swipeY0 < dl[1];
                }
                if (aboveDock && dy < -dp(40) && Math.abs(dy) > Math.abs(dx) * 1.3f) {
                    swipeFired = true;
                    openLastClosed();
                }
            }
        }
        if (page == HOME && openFolder != null && ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            if (closing) return true;
            if (immersive && hit(dockBoxV, ev)) return super.dispatchTouchEvent(ev);
            boolean inside = false;
            if (curPanel != null) {
                int[] l = new int[2];
                curPanel.getLocationOnScreen(l);
                float x = ev.getRawX(), y = ev.getRawY();
                float pw = curPanel.getWidth() * curPanel.getScaleX();
                float ph = curPanel.getHeight() * curPanel.getScaleY();
                inside = x >= l[0] && x <= l[0] + pw && y >= l[1] && y <= l[1] + ph;
            }
            if (!inside) {
                for (View cv : folderCtl) {
                    if (hit(cv, ev)) { inside = true; break; }
                }
            }
            if (!inside && !immersive) {
                for (Map.Entry<String, List<View>> en : cells.entrySet()) {
                    if (!en.getKey().startsWith("folder:")) continue;
                    for (View cv2 : en.getValue()) {
                        if (hit(cv2, ev)) { inside = true; break; }
                    }
                    if (inside) break;
                }
            }
            if (!inside) {
                if (sp.getBoolean("fpKeep", false)) {
                    if (hit(homeMenuV, ev) || hit(homePlusV, ev) || hit(countBarV, ev)) return super.dispatchTouchEvent(ev);
                    return true;
                }
                closeFolder();
                return true;
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    protected void onNewIntent(Intent in) {
        super.onNewIntent(in);
        setIntent(in);
        page = HOME;
        if (!sp.getBoolean("fpKeep", false)) openFolder = null;
        plusOpen = false;
        folderSettings = false;
        sel.clear();
        mockFromBubble = false;
        checkMockIntent(in);
        show();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && container != null && container.getChildCount() > 0) {
            container.getChildAt(container.getChildCount() - 1).requestApplyInsets();
        }
    }

    // top / bottom space of the home page: the same numbers are used for the first build and for the real insets
    int[] homeInsets(WindowInsets ins) {
        if (ins == null) ins = getWindow().getDecorView().getRootWindowInsets();
        int top = 0, bot = 0;
        if (ins != null) {
            top = ins.getSystemWindowInsetTop();
            bot = ins.getSystemWindowInsetBottom();
            if (Build.VERSION.SDK_INT >= 28) {
                DisplayCutout dc = ins.getDisplayCutout();
                if (dc != null && dc.getSafeInsetTop() > 0) top = dc.getSafeInsetTop() + dp(6);
            }
        } else {
            int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) top = getResources().getDimensionPixelSize(id);
        }
        return new int[]{top, bot};
    }

    // true while the Mock settings were opened from the bubble (long press): Back then closes them completely
    boolean mockFromBubble = false;

    private void checkMockIntent(Intent in) {
        if (in != null && in.getBooleanExtra("open_mock_settings", false)) {
            page = MOCK;
            mockFromBubble = true;
            in.removeExtra("open_mock_settings");
        }
        if (in != null && in.getBooleanExtra("open_acts", false)) {
            page = ACTS;
            in.removeExtra("open_acts");
        }
    }

    void closeMockFromBubble() {
        mockFromBubble = false;
        page = HOME;
        moveTaskToBack(true);
        container.postDelayed(() -> show(), 400);
    }

    @Override
    public void onRequestPermissionsResult(int c, String[] p, int[] r) { notifyBar(); }

    @Override
    protected void onStart() {
        super.onStart();
        instCache.clear();
        iconCache.clear();
        labelCache.clear();
        sp.edit().putBoolean("open", true).apply();
        MockBubbleService.launcherVisible = true;
        MockBubbleService.refresh();
        requestTile();
    }

    @Override
    protected void onStop() {
        super.onStop();
        saveRunRect();
        sp.edit().putBoolean("open", false).apply();
        MockBubbleService.launcherVisible = false;
        MockBubbleService.refresh();
        requestTile();
    }

    @Override
    public void onBackPressed() {
        if (mockFromBubble && page == MOCK) {
            closeMockFromBubble();
            return;
        }
        if (page == HOME) {
            if (!sel.isEmpty()) { sel.clear(); refreshCells(); updateSel(); }
            else if (plusOpen) closePlus();
            else if (openFolder != null) closeFolder();
            else super.onBackPressed();
        } else if (page == SETTINGS) {
            page = HOME;
            show();
        } else if (page == PICKER) {
            pickTarget = null;
            page = HOME;
            show();
        } else if (page == RESTORE) {
            if (!rSel.isEmpty()) { rSel.clear(); show(); return; }
            page = BACKUP;
            show();
        } else if (page == FPICK) {
            page = FGRP;
            show();
        } else if (page == FGRP) {
            page = FONT;
            show();
        } else if (page == DOCKBG) {
            page = DOCK;
            show();
        } else if (page == HFBG) {
            page = HPAGE;
            show();
        } else if (page == MBG || page == TBG || page == MBOX || page == SBOX) {
            page = bgBack;
            bgBack = HOME;
            show();
        } else if (page == ICON) {
            page = HPAGE;
            show();
        } else if (page == ACTS || page == BLBG || page == SWL || page == AIL) {
            page = BUB;
            show();
        } else if (page == BUB) {
            page = MOCK;
            show();
        } else if (page == MOCK) {
            page = SETTINGS;
            show();
        } else {
            page = SETTINGS;
            show();
        }
    }

    void requestTile() {
        try {
            TileService.requestListeningState(this, new ComponentName(this, VadTile.class));
        } catch (Exception e) { }
    }

    void notifyBar() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("l", "VadLan",
                NotificationManager.IMPORTANCE_LOW));
        Intent i = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, "l")
                .setSmallIcon(R.drawable.ic_tile)
                .setContentTitle("VadLan")
                .setContentText(t("برای باز کردن لمس کنید", "Tap to open"))
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        nm.notify(1, n);
    }

    // ================= background image =================
    void loadBgImage() {
        bgBmp = null;
        bgSrc = null;
        if (!sp.getBoolean("bgImg", false)) return;
        try {
            File fl = new File(getFilesDir(), "bg_src.jpg");
            if (!fl.exists()) fl = new File(getFilesDir(), "bg.jpg");
            if (fl.exists()) bgSrc = BitmapFactory.decodeFile(fl.getAbsolutePath());
        } catch (Throwable e) { }
        renderBg();
    }

    // draws the picture with zoom / position / fade into a screen-sized bitmap
    void renderBg() {
        if (bgSrc == null) { bgBmp = null; return; }
        try {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            int W = dm.widthPixels, H = dm.heightPixels;
            float zoom = sp.getInt("bgZoom", 100) / 100f;
            float px = sp.getInt("bgPx", 50) / 100f;
            float py = sp.getInt("bgPy", 50) / 100f;
            float s = Math.max(W / (float) bgSrc.getWidth(), H / (float) bgSrc.getHeight()) * zoom;
            float iw = bgSrc.getWidth() * s, ih = bgSrc.getHeight() * s;
            Bitmap out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(out);
            Matrix m = new Matrix();
            m.postScale(s, s);
            m.postTranslate((W - iw) * px, (H - ih) * py);
            cv.drawBitmap(bgSrc, m, new Paint(Paint.FILTER_BITMAP_FLAG));
            int fade = sp.getInt("bgFade", 0);
            if (fade > 0) cv.drawColor(((fade * 255 / 100) << 24) | (bg & 0x00FFFFFF));
            bgBmp = out;
        } catch (Throwable e) { }
    }

    void applyWindowBg() {
        if (bgBmp != null) getWindow().setBackgroundDrawable(new BitmapDrawable(getResources(), bgBmp));
        else getWindow().setBackgroundDrawable(new ColorDrawable(bg));
    }

    // ---- settings menu pages have their own background (color / picture), separate from the home page ----
    boolean menuPage() { return page != HOME && page != PICKER; }

    int mbg() { return sp.getInt("mBg", colors[0]); }

    Drawable menuBgDrawable() {
        Drawable top = mbgBmp != null ? new BitmapDrawable(getResources(), mbgBmp) : new ColorDrawable(mbg());
        return new LayerDrawable(new Drawable[]{new ColorDrawable(0xFF000000), top});
    }

    // reads a saved picture when its switch ("pre"+"Img") is on
    Bitmap loadSrc(String pre, String file) {
        if (!sp.getBoolean(pre + "Img", false)) return null;
        try {
            File fl = new File(getFilesDir(), file);
            if (fl.exists()) return BitmapFactory.decodeFile(fl.getAbsolutePath());
        } catch (Throwable e) { }
        return null;
    }

    // zoom / position / fade of a picture into a screen-sized bitmap
    Bitmap renderImg(Bitmap src, String pre, int color) {
        if (src == null) return null;
        try {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            int W = dm.widthPixels, H = dm.heightPixels;
            float zoom = sp.getInt(pre + "Zoom", 100) / 100f;
            float px = sp.getInt(pre + "Px", 50) / 100f;
            float py = sp.getInt(pre + "Py", 50) / 100f;
            float s = Math.max(W / (float) src.getWidth(), H / (float) src.getHeight()) * zoom;
            float iw = src.getWidth() * s, ih = src.getHeight() * s;
            Bitmap out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(out);
            Matrix m = new Matrix();
            m.postScale(s, s);
            m.postTranslate((W - iw) * px, (H - ih) * py);
            cv.drawBitmap(src, m, new Paint(Paint.FILTER_BITMAP_FLAG));
            int fade = sp.getInt(pre + "Fade", 0);
            if (fade > 0) cv.drawColor(((fade * 255 / 100) << 24) | (color & 0x00FFFFFF));
            return out;
        } catch (Throwable e) { return null; }
    }

    void loadMenuImage() {
        mbgSrc = loadSrc("m", "menu_src.jpg");
        mbgBmp = renderImg(mbgSrc, "m", mbg());
    }

    void loadFrameImage() {
        hfSrc = loadSrc("hf", "hf_src.jpg");
        tbSrc = loadSrc("tb", "tb_src.jpg");
    }

    // page background: transparent when a picture is used (settings menu pages get theirs in show())
    int pbg() { return menuPage() ? 0 : (bgBmp != null ? 0 : bg); }

    void pickImg(int req) {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        try {
            startActivityForResult(Intent.createChooser(i, t("انتخاب عکس", "Choose image")), req);
        } catch (Exception e) {
            Toast.makeText(this, t("گالری پیدا نشد", "No gallery found"), Toast.LENGTH_SHORT).show();
        }
    }

    void removeBgImage() {
        sp.edit().putBoolean("bgImg", false).apply();
        bgBmp = null;
        bgSrc = null;
        applyWindowBg();
        show();
    }

    @Override
    protected void onActivityResult(int rc, int res, Intent data) {
        super.onActivityResult(rc, res, data);
        if ((rc != REQ_IMG && rc != REQ_FIMG && rc != REQ_MIMG && rc != REQ_TIMG) || res != RESULT_OK
                || data == null || data.getData() == null) return;
        try {
            Uri u = data.getData();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            InputStream in1 = getContentResolver().openInputStream(u);
            BitmapFactory.decodeStream(in1, null, o);
            in1.close();
            int ss = 1;
            while (Math.max(o.outWidth, o.outHeight) / ss > 2400) ss *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = ss;
            InputStream in2 = getContentResolver().openInputStream(u);
            Bitmap src = BitmapFactory.decodeStream(in2, null, o2);
            in2.close();
            if (src == null) throw new Exception("decode");
            String fn = rc == REQ_IMG ? "bg_src.jpg" : (rc == REQ_FIMG ? "hf_src.jpg"                              : (rc == REQ_TIMG ? "tb_src.jpg" : "menu_src.jpg"));
            FileOutputStream fo = new FileOutputStream(new File(getFilesDir(), fn));
            src.compress(Bitmap.CompressFormat.JPEG, 92, fo);
            fo.close();
            if (rc == REQ_IMG) {
                sp.edit().putBoolean("bgImg", true).remove("bgZoom").remove("bgPx")
                        .remove("bgPy").remove("bgFade").apply();
                bgSrc = src;
                renderBg();
                applyWindowBg();
                page = BG;
            } else if (rc == REQ_FIMG) {
                sp.edit().putBoolean("hfImg", true).remove("hfZoom").remove("hfPx")
                        .remove("hfPy").remove("hfFade").apply();
                hfSrc = src;
                page = HFBG;
            } else if (rc == REQ_TIMG) {
                sp.edit().putBoolean("tbImg", true).remove("tbZoom").remove("tbPx")
                        .remove("tbPy").remove("tbFade").apply();
                tbSrc = src;
                page = TBG;
            } else {
                sp.edit().putBoolean("mImg", true).remove("mZoom").remove("mPx")
                        .remove("mPy").remove("mFade").apply();
                mbgSrc = src;
                mbgBmp = renderImg(src, "m", mbg());
                page = MBG;
            }
            show();
        } catch (Throwable e) {
            Toast.makeText(this, t("خطا در خواندن عکس", "Could not read the image"), Toast.LENGTH_SHORT).show();
        }
    }

    // ================= glass / rounded boxes =================
    static final int BOX_R = 28;
    Bitmap mbgBlur, mbgBlurSrc;
    View menuRoot;

    // blurred copies of the settings picture: [0] = main glass boxes, [1] = the lighter boxes inside other options
    final Bitmap[] glassBlurBmp = new Bitmap[2];
    final int[] glassBlurPct = {-1, -1};
    Bitmap glassBlurSrc;

    Bitmap blurFor(int idx) {
        if (mbgBmp == null) return null;
        if (glassBlurSrc != mbgBmp) {
            glassBlurBmp[0] = null;
            glassBlurBmp[1] = null;
            glassBlurPct[0] = -1;
            glassBlurPct[1] = -1;
            glassBlurSrc = mbgBmp;
        }
        int p = 5;
        if (glassBlurBmp[idx] == null || glassBlurPct[idx] != p) {
            glassBlurBmp[idx] = makeBlur(mbgBmp, p);
            glassBlurPct[idx] = p;
        }
        return glassBlurBmp[idx];
    }

    boolean glass() { return sp.getBoolean("glass", false); }

    // frosted glass box: a very lightly blurred copy of the menu picture, a tint, a sheen and a thin edge
    // alpha of a color multiplied by k (used to make some boxes a little fainter)
    int scaleA(int c, float k) {
        return (c & 0x00FFFFFF) | (Math.round(Color.alpha(c) * k) << 24);
    }

    // 1 = normal boxes; below 1 = boxes built meanwhile are a little fainter
    float boxFade = 1f;

    // open / closed state of the groups inside the settings pages (kept when a page is rebuilt)
    final Map<String, Boolean> accState = new HashMap<>();
    // page the menu-color / top-frame-color pages go back to
    int bgBack = HOME;

    // open folder panel: swiping down closes the folder (once its own scroll is at the top);
    // with the animation option off, swiping left opens the folder on its right, swiping right the one on its left
    class SwipePanel extends LinearLayout {
        ScrollView sc;
        boolean swipeOn = false;
        float x0, y0;
        boolean atTop, fired;
        int mode = 0;

        SwipePanel(Context c) { super(c); }

        boolean sideOk(float dx) {
            boolean loopOn = sp.getBoolean("fpLoop", false);
            if ((!loopOn && sp.getBoolean("fpAnim", true) && sp.getInt("fdAnim", -1) < 0) || !fromDock || openFolder == null) return false;
            return dockNeighbor(openFolder, dx > 0) != null;
        }

        boolean detect(MotionEvent ev) {
            int slop = ViewConfiguration.get(MainActivity.this).getScaledTouchSlop();
            int a = ev.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                x0 = ev.getX();
                y0 = ev.getY();
                mode = 0;
                fired = false;
                // the folder scroll goes first: closing is only possible when the gesture starts with the scroll at the top
                atTop = sc == null || !sc.canScrollVertically(-1);
            } else if (a == MotionEvent.ACTION_MOVE && mode == 0 && !fired) {
                float dx = ev.getX() - x0, dy = ev.getY() - y0;
                if (dy > slop && dy > Math.abs(dx) * 1.3f && atTop) mode = 1;
                else if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * 1.3f && sideOk(dx)) mode = 2;
            }
            return mode != 0;
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            return swipeOn && detect(ev);
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            if (swipeOn && (mode != 0 || detect(ev))) {
                int a = ev.getActionMasked();
                if (a == MotionEvent.ACTION_MOVE && !fired) {
                    float dx = ev.getX() - x0, dy = ev.getY() - y0;
                    if (mode == 1 && dy > dp(60)) {
                        fired = true;
                        closeFolder();
                    } else if (mode == 2 && Math.abs(dx) > dp(60)) {
                        fired = true;
                        String nid = dockNeighbor(openFolder, dx > 0);
                        if (nid != null) { folderDir = dx > 0 ? 1 : -1; openDockFolder(nid); }
                    }
                } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                    mode = 0;
                }
                return true;
            }
            return super.onTouchEvent(ev);
        }
    }

    class Glass extends Drawable {
        float fade = 1f;
        boolean blurOn = false;
        Paint bpt = new Paint(Paint.FILTER_BITMAP_FLAG);
        Paint pt = new Paint(Paint.ANTI_ALIAS_FLAG);
        Path cl = new Path();
        RectF rf = new RectF(), rf2 = new RectF();
        Rect dst = new Rect();
        int tint;
        float r;
        Bitmap blur;
        Glass(int tint, float r, Bitmap blur) {
            this.tint = tint;
            this.r = r;
            this.blur = blur;
        }
        @Override
        public void draw(Canvas c) {
            Rect b = getBounds();
            if (b.width() < 4 || b.height() < 4) return;
            float rr = Math.min(r, Math.min(b.width(), b.height()) / 2f);
            rf.set(b);
            cl.reset();
            cl.addRoundRect(rf, rr, rr, Path.Direction.CW);
            c.save();
            c.clipPath(cl);
            Callback cb = getCallback();
            Bitmap bm = blurOn ? blurFor(fade < 1f ? 1 : 0) : null;
            if (bm != null && menuRoot != null && menuRoot.getWidth() > 0 && cb instanceof View) {
                int[] a = new int[2], l = new int[2];
                menuRoot.getLocationOnScreen(a);
                ((View) cb).getLocationOnScreen(l);
                dst.set(a[0] - l[0], a[1] - l[1], a[0] - l[0] + menuRoot.getWidth(),
                        a[1] - l[1] + menuRoot.getHeight());
                bpt.setAlpha(Math.round(255 * fade));
                c.drawBitmap(bm, null, dst, bpt);
            }
            pt.setStyle(Paint.Style.FILL);
            pt.setShader(null);
            pt.setColor(scaleA(tint, fade));
            c.drawRect(rf, pt);
            pt.setShader(new LinearGradient(0, b.top, 0, b.top + b.height() * 0.6f,
                    scaleA(0x30FFFFFF, fade), 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawRect(rf, pt);
            pt.setShader(null);
            c.restore();
            float st = Math.max(1f, dp(1)), h = st / 2f;
            pt.setStyle(Paint.Style.STROKE);
            pt.setStrokeWidth(st);
            pt.setShader(new LinearGradient(b.left, b.top, b.right, b.bottom,
                    scaleA(0x88FFFFFF, fade), scaleA(0x22FFFFFF, fade), Shader.TileMode.CLAMP));
            rf2.set(b.left + h, b.top + h, b.right - h, b.bottom - h);
            c.drawRoundRect(rf2, rr - h, rr - h, pt);
            pt.setShader(null);
            pt.setStyle(Paint.Style.FILL);
        }
        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(ColorFilter f) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    // small blurred copy of a picture (very low blur)
    // pct = 0..100: 0 keeps the picture sharp, 100 is a very strong blur (5 = the old very light blur)
    Bitmap makeBlur(Bitmap s, int pct) {
        if (pct <= 0) return s;
        try {
            int rad = Math.max(1, Math.round(pct / 100f * 24f));
            int bw = Math.max(2, s.getWidth() / 4), bh = Math.max(2, s.getHeight() / 4);
            Bitmap b = Bitmap.createScaledBitmap(s, bw, bh, true);
            int[] px = new int[bw * bh];
            b.getPixels(px, 0, bw, 0, 0, bw, bh);
            boxBlur(px, bw, bh, rad);
            boxBlur(px, bw, bh, rad);
            Bitmap o = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            o.setPixels(px, 0, bw, 0, 0, bw, bh);
            return o;
        } catch (Throwable e) { return null; }
    }

    // background of every settings box: plain rounded box, or glass when the option is on
    Drawable boxBg(int plain, int rDp) {
        final String ck = boxFade < 1f ? "boxSub" : "boxMain";
        final boolean has = sp.contains(ck);
        if (!glass()) {
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(rDp));
            g.setColor(has ? (boxFade < 0.5f ? scaleA(sp.getInt(ck, 0), boxFade / 0.6f) : sp.getInt(ck, 0)) : scaleA(plain, boxFade));
            return g;
        }
        Glass gl = new Glass(has ? sp.getInt(ck, 0) : (light() ? 0x44FFFFFF : 0x26FFFFFF), dp(rDp), null);
        gl.blurOn = menuPage() && mbgBmp != null;
        gl.fade = has ? (boxFade < 0.5f ? boxFade / 0.6f : 1f) : boxFade;
        return gl;
    }

    void invalidateGlass(View v) {
        if (v.getBackground() instanceof Glass) v.invalidate();
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) invalidateGlass(g.getChildAt(i));
        }
    }

    // text that has no box of its own gets the same box as the other options
    TextView labelBox(String text, int fg) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(fg);
        tv.setTextSize(14);
        tv.setPadding(dp(14), dp(12), dp(14), dp(12));
        tv.setBackground(boxBg(light() ? 0x14000000 : 0x1AFFFFFF, BOX_R));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(10), 0, 0);
        tv.setLayoutParams(lp);
        return tv;
    }

    // ================= data =================
    void loadHome() {
        home.clear();
        String s = sp.getString("home", null);
        if (s == null) return;
        try {
            JSONArray a = new JSONArray(s);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Item it = new Item();
                if (o.has("id")) {
                    it.folder = true;
                    it.id = o.getString("id");
                    it.name = o.optString("n", "");
                    it.color = o.optInt("c", 0x3A28282C);
                    it.docked = o.optBoolean("d", false);
                    JSONArray ar = o.getJSONArray("a");
                    for (int j = 0; j < ar.length(); j++) it.apps.add(ar.getString(j));
                } else {
                    it.pkg = o.getString("p");
                }
                it.page = o.optInt("pg", -1);
                home.add(it);
            }
        } catch (Exception e) { }
        normalizePages();
    }

    void saveHome() {
        normalizePages();
        try {
            JSONArray a = new JSONArray();
            for (Item it : home) {
                JSONObject o = new JSONObject();
                if (it.page >= 0) o.put("pg", it.page);
                if (it.folder) {
                    o.put("id", it.id);
                    o.put("n", it.name);
                    o.put("c", it.color);
                    if (it.docked) o.put("d", true);
                    JSONArray ar = new JSONArray();
                    for (String p : it.apps) ar.put(p);
                    o.put("a", ar);
                } else {
                    o.put("p", it.pkg);
                }
                a.put(o);
            }
            sp.edit().putString("home", a.toString()).apply();
        } catch (Exception e) { }
    }

    void loadDock() {
        dock1.clear();
        String s = sp.getString("dock1", "");
        for (String pg : s.split(";", -1)) {
            List<String> l = new ArrayList<>();
            for (String x : pg.split(",")) if (!x.isEmpty()) l.add(x);
            dock1.add(l);
        }
    }

    void saveDock() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < dock1.size(); i++) {
            if (i > 0) b.append(';');
            b.append(String.join(",", dock1.get(i)));
        }
        sp.edit().putString("dock1", b.toString()).apply();
    }

    List<String> dockPageList(int i) {
        while (dock1.size() <= i) dock1.add(new ArrayList<String>());
        return dock1.get(i);
    }

    // folders marked as docked but missing from the dock go back to the home grid
    void fixDocked() {
        boolean ch = false;
        for (Item it : home) {
            if (!it.folder || !it.docked) continue;
            boolean in = false;
            for (List<String> l : dock1) if (l.contains("folder:" + it.id)) in = true;
            if (!in) { it.docked = false; ch = true; }
        }
        if (ch) saveHome();
    }

    // folders always live in "home"; docked ones are just hidden from the grid
    Item findFolder(String id) {
        for (Item it : home) if (it.folder && it.id.equals(id)) return it;
        return null;
    }

    String folderTitle(Item f) {
        if (f == null) return "?";
        return f.name.isEmpty() ? t("پوشه", "Folder") : f.name;
    }

    Map<String, Boolean> instCache = new HashMap<>();
    Map<String, Drawable> iconCache = new HashMap<>();
    Map<String, String> labelCache = new HashMap<>();

    boolean installed(String p) {
        Boolean b = instCache.get(p);
        if (b == null) { b = installedRaw(p); instCache.put(p, b); }
        return b;
    }

    Drawable icon(String p) {
        if (iconCache.containsKey(p)) return iconCache.get(p);
        Drawable d = iconRaw(p);
        if (d != null) iconCache.put(p, d);
        return d;
    }

    String label(String p) {
        String s = labelCache.get(p);
        if (s == null) { s = labelRaw(p); labelCache.put(p, s); }
        return s;
    }

    boolean installedRaw(String p) {
        try { getPackageManager().getApplicationInfo(p, 0); return true; }
        catch (Exception e) { return false; }
    }

    Drawable iconRaw(String p) {
        try { return getPackageManager().getApplicationIcon(p); }
        catch (Exception e) { return null; }
    }

    String labelRaw(String p) {
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString();
        } catch (Exception e) { return p; }
    }

    // ================= helpers =================
    int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    int sc(int v) { return v * sp.getInt("top_scale", 100) / 100; }

    boolean isLight(int c) {
        return (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) > 150;
    }

    int solid(int c) {
        int a = Color.alpha(c);
        return Color.rgb(Color.red(c) * a / 255, Color.green(c) * a / 255, Color.blue(c) * a / 255);
    }

    boolean light() { return isLight(solid(menuPage() ? mbg() : bg)); }

    String hex(int c) { return String.format("#%08X", c); }

    GradientDrawable oval(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    GradientDrawable rr(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(radius);
        g.setColor(color);
        return g;
    }

    LinearLayout vbox() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackgroundColor(pbg());
        l.setPadding(dp(16), dp(16), dp(16), dp(16));
        return l;
    }

    void postShow() { container.post(() -> show()); }

    // height taken by the dock (it floats over the home page)
    int dockReserve() {
        int r = 0;
        if (sp.getBoolean("dockOn1", true)) r += dp(sp.getInt("dockH1", 90)) + dp(8) + dockShift();
        return r;
    }

    // dock position: > 0 = raised (mm), 0 = now, lowest = the small gap above the navigation keys
    int dockShift() {
        int dy = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_MM, sp.getInt("dockY", 0), getResources().getDisplayMetrics());
        return dy < 0 ? -Math.min(-dy, dp(6)) : dy;
    }

    // top frame position: > 0 = lower; the negative side stops at the screen top. The home frame follows (layout margin)
    void applyTopShift(View rootV) {
        if (!(rootV instanceof LinearLayout) || ((LinearLayout) rootV).getChildCount() == 0) return;
        View head = ((LinearLayout) rootV).getChildAt(0);
        int ty = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_MM, sp.getInt("topBoxY", 0), getResources().getDisplayMetrics());
        if (ty < 0) ty = Math.max(ty, -Math.max(0, rootV.getPaddingTop() - dp(2)));
        ViewGroup.LayoutParams lp = head.getLayoutParams();
        if (lp instanceof LinearLayout.LayoutParams && ((LinearLayout.LayoutParams) lp).topMargin != ty) {
            ((LinearLayout.LayoutParams) lp).topMargin = ty;
            head.setLayoutParams(lp);
        }
    }

    void vib(int ms) {
        try {
            Vibrator vb = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vb != null) {
                if (Build.VERSION.SDK_INT >= 26) {
                    vb.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    vb.vibrate(ms);
                }
            }
        } catch (Exception e) {}
    }

    void pressDown(View v) {
        vib(12);
        v.animate().scaleX(0.86f).scaleY(0.86f).setDuration(90)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    void pressUp(View v) {
        v.animate().scaleX(1f).scaleY(1f).setDuration(160)
                .setInterpolator(new OvershootInterpolator(2f)).start();
    }

    // small soft motion under the finger; does not consume the touch
    void pressAnim(View v) {
        v.setOnTouchListener((w, ev) -> {
            int a = ev.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) pressDown(w);
            else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) pressUp(w);
            return false;
        });
    }

    Button btn(String text, int fg, View.OnClickListener c) {
        Button x = new Button(this);
        x.setText(text);
        x.setTextColor(fg);
        x.setAllCaps(false);
        x.setBackground(boxBg(light() ? 0x22000000 : 0x33FFFFFF, 22));
        x.setOnClickListener(c);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(48), 1);
        p.setMargins(dp(4), dp(4), dp(4), dp(4));
        x.setLayoutParams(p);
        return x;
    }

    ImageView iconBtn(int res, int fg, View.OnClickListener c) {
        ImageView v = new ImageView(this);
        v.setImageResource(res);
        v.setColorFilter(fg);
        v.setPadding(dp(9), dp(9), dp(9), dp(9));
        v.setBackground(oval(light() ? 0x22000000 : 0x33FFFFFF));
        v.setOnClickListener(c);
        pressAnim(v);
        return v;
    }

    // top icons of the home page: own width and height (in dp)
    FrameLayout.LayoutParams absHome(int g, int wDp, int hDp) {
        return new FrameLayout.LayoutParams(dp(wDp), dp(hDp), g | Gravity.CENTER_VERTICAL);
    }

    // circle, or a box like the counters when "header icons shape" is on
    void styleTop(ImageView v, int fg) {
        v.setColorFilter(sp.getInt("top_ic", fg));
        // same box as the clock and date boxes: rounded, with a very thin white edge
        int c = sp.contains("top_ibg") ? sp.getInt("top_ibg", 0x33FFFFFF) : sp.getInt("box_bg", 0x33FFFFFF);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(16));
        g.setColor(c);
        g.setStroke(Math.max(1, dp(1)), 0x33FFFFFF);
        v.setBackground(g);
    }

    // opens after holding for 1 second; the normal tap still works
    void holdOne(final View v, final Runnable r) {
        final Runnable fire = () -> { menuHold = true; vib(25); r.run(); };
        v.setOnTouchListener((w, ev) -> {
            int a = ev.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                menuHold = false;
                pressDown(w);
                w.postDelayed(fire, 1000);
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                pressUp(w);
                w.removeCallbacks(fire);
            } else if (a == MotionEvent.ACTION_MOVE) {
                if (ev.getX() < -dp(20) || ev.getY() < -dp(20)
                        || ev.getX() > w.getWidth() + dp(20) || ev.getY() > w.getHeight() + dp(20)) w.removeCallbacks(fire);
            }
            return false;
        });
    }

    int topLineColor() {
        int c = solid(bg);
        if (bgBmp != null) {
            try {
                int w = bgBmp.getWidth(), h = Math.min(bgBmp.getHeight(), dp(120));
                long r = 0, g = 0, b = 0;
                int n = 0;
                for (int y = 0; y < h; y += 8) {
                    for (int x = 0; x < w; x += 8) {
                        int p = bgBmp.getPixel(x, y);
                        r += Color.red(p);
                        g += Color.green(p);
                        b += Color.blue(p);
                        n++;
                    }
                }
                if (n > 0) c = Color.rgb((int) (r / n), (int) (g / n), (int) (b / n));
            } catch (Throwable e) { }
        }
        return isLight(c) ? 0xB3000000 : 0xB3FFFFFF;
    }

    // check circle of a row (ind 1 = green tick, 2 = empty circle, 3 = filled circle)
    TextView indView(int ind) {
        TextView ck = new TextView(this);
        ck.setTag("x");
        ck.setText("✓");
        if (ind == 1) {
            ck.setTextSize(20);
            ck.setTextColor(0xFF34C759);
            return ck;
        }
        ck.setTextSize(13);
        ck.setGravity(Gravity.CENTER);
        GradientDrawable g2 = oval(ind == 3 ? 0xFF34C759 : 0);
        if (ind == 3) {
            ck.setTextColor(0xFFFFFFFF);
        } else {
            ck.setTextColor(0);
            g2.setStroke(dp(2), 0x88888888);
        }
        ck.setBackground(g2);
        ck.setLayoutParams(new LinearLayout.LayoutParams(dp(24), dp(24)));
        return ck;
    }

    // a boxed on/off option that flips its own circle when tapped
    View toggleRow(int dot, String sym, String title, String sub, final String key,
                   final boolean def, int fg) {
        return toggleRowThen(dot, sym, title, sub, key, def, fg, null);
    }

    View toggleRowThen(int dot, String sym, String title, String sub, final String key,
                       final boolean def, int fg, final Runnable after) {
        View r = row(dot, sym, title, sub, sp.getBoolean(key, def) ? 3 : 2, fg, null);
        r.setOnClickListener(v -> {
            boolean on = !sp.getBoolean(key, def);
            sp.edit().putBoolean(key, on).apply();
            if (after != null) { after.run(); return; }
            ViewGroup g = (ViewGroup) v;
            g.removeViewAt(g.getChildCount() - 1);
            g.addView(indView(on ? 3 : 2));
        });
        return r;
    }

    // goes to the menu-color / top-frame-color page (from the settings page or from the floating window)
    void goBgPage(int p, AlertDialog[] dlg) {
        bgBack = dlg == null ? TBAR : HOME;
        page = p;
        if (dlg != null && dlg[0] != null) dlg[0].dismiss();
        else show();
    }

    // toolbar settings: counters, icons placement / size / color, menu color, frame ...
    // used inside the main settings page (dlg == null) and in the window that opens by holding the hamburger
    LinearLayout toolbarContent(final int fg, final AlertDialog[] dlg) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        final int dszF = sp.getInt("home_btn_sz", 40);
        // counters settings
        box.addView(accordionGroup(0xFF26A69A, "🔢", t("داده‌ها نوار ابزار", "Toolbar data"), fg,
                countersContent(fg)));
        // placement of the two icons: first lever = horizontal, second = vertical
        box.addView(accordionGroupFaded(0xFF00ACC1, "↔", t("جای‌گیری آیکون‌ها", "Icons placement"), fg, 0.6f, () -> new View[]{
                accordionGroup(0xFF5C6BC0, "☰", t("همبرگری", "Hamburger"), fg,
                        slider(t("جابجایی طولی", "Horizontal move"), "hamX", -100, 100, 0, " dp", fg, null),
                        slider(t("جابجایی عرضی", "Vertical move"), "hamY", -30, 30, 0, " dp", fg, null)),
                accordionGroup(0xFFFF7043, "+", t("بعلاوه", "Plus"), fg,
                        slider(t("جابجایی طولی", "Horizontal move"), "plusX", -100, 100, 0, " dp", fg, null),
                        slider(t("جابجایی عرضی", "Vertical move"), "plusY", -30, 30, 0, " dp", fg, null))}));
        // size of the two icons: first lever = vertical, second = horizontal
        box.addView(accordionGroupFaded(0xFF2196F3, "📐", t("سایز", "Size"), fg, 0.6f, () -> new View[]{
                accordionGroup(0xFF5C6BC0, "☰", t("سایز آیکون همبرگری", "Hamburger icon size"), fg,
                        slider(t("سایز عمودی", "Vertical size"), "hamH", 24, 120, dszF, " dp", fg, null),
                        slider(t("سایز افقی", "Horizontal size"), "hamW", 24, 120, dszF, " dp", fg, null)),
                accordionGroup(0xFFFF7043, "+", t("سایز آیکون بعلاوه", "Plus icon size"), fg,
                        slider(t("سایز عمودی", "Vertical size"), "plusH", 24, 120, dszF, " dp", fg, null),
                        slider(t("سایز افقی", "Horizontal size"), "plusW", 24, 120, dszF, " dp", fg, null))}));
        box.addView(accordionGroupFaded(0xFFE91E63, "🎨", t("رنگ", "Color"), fg, 0.6f, () -> new View[]{
                row(0xFFE91E63, "🖌", t("رنگ و شفافیت آیکون‌ها", "Icons color and transparency"), null, 0, fg,
                        w -> prefColorDialog("top_ic", fgc, t("رنگ آیکون‌ها", "Icons color"))),
                row(0xFFAB47BC, "🖌", t("رنگ و شفافیت پس‌زمینه آیکون‌ها", "Icons background color and transparency"),
                        null, 0, fg,
                        w -> prefColorDialog("top_ibg", light() ? 0x22000000 : 0x33FFFFFF,
                                t("رنگ پس‌زمینه آیکون‌ها", "Icons background color")))}));
        // menu color: color, transparency and picture of the settings (sliding) pages
        box.addView(row(0xFF9C27B0, "🖌", t("رنگ منو", "Menu color"), null, 0, fg, x -> goBgPage(MBG, dlg)));
        // the glass option changes the settings page itself, so the page is rebuilt right away
        Runnable again = null;
        if (dlg == null) again = () -> show();
        box.addView(toggleRowThen(0xFF26C6DA, "◇", t("کادر شیشه‌ای", "Glass boxes"),
                t("کادرهای تنظیمات شیشه‌ای با بلور بسیار کم می‌شوند", "Settings boxes look like glass with a very light blur"),
                "glass", false, fg, again));
        // color and transparency of the main boxes, and of the lighter boxes inside other options
        box.addView(row(0xFF26C6DA, "🎨", t("رنگ کادر اصلی", "Main box color"),
                t("رنگ و شفافیت کادرهای اصلی", "Color and transparency of the main boxes"), 0, fg,
                x -> goBgPage(MBOX, dlg)));
        box.addView(row(0xFF00ACC1, "🎨", t("رنگ کادر فرعی", "Secondary box color"),
                t("رنگ و شفافیت کادرهای کم‌رنگ‌تر زیرمجموعه", "Color and transparency of the lighter boxes inside other options"),
                0, fg, x -> goBgPage(SBOX, dlg)));
        box.addView(toggleRow(0xFFEF5350, "◻", t("شکل آیکون‌های سربرگ", "Header icons shape"),
                t("آیکون‌ها بدون دایره و داخل کادر مثل کادر شمارش", "Icons sit in boxes like the counters, not circles"),
                "topIconBox", false, fg));
        box.addView(toggleRow(0xFF7E57C2, "▣", t("کادر تنظیمات", "Settings frame"),
                t("کادری دور آیکون‌ها و کادرهای شمارش", "A frame around the icons and the counters"),
                "topBoxOn", false, fg));
        // color, transparency, picture and color code of the frame at the top of the home page
        box.addView(row(0xFFFF7043, "🖌", t("رنگ کادر تنظیمات", "Settings frame color"),
                t("رنگ، شفافیت، عکس و کد رنگ کادر بالای صفحه", "Color, transparency, picture and color code of the top frame"),
                0, fg, x -> goBgPage(TBG, dlg)));
        box.addView(accordionGroup(0xFF8BC34A, "📏", t("سایز کادر بالا", "Top frame size"), fg,
                slider(t("طول", "Length"), "topBoxW", 40, 100, 100, "%", fg, null),
                slider(t("عرض", "Width"), "topBoxH", 30, 100, 56, " dp", fg, null)));
        // moves the whole top frame (icons, counters, frame): up to just under the camera, down about 10 mm
        box.addView(accordionRow(0xFF26A69A, "↕", t("جای کادر تنظیمات", "Settings frame position"), "topBoxY", -2, 10, 0, " mm", fg, null));
        return box;
    }

    // toolbar settings page (slides in like the home page settings)
    View toolbarPage(final int fg) {
        LinearLayout r = vbox();
        r.addView(topBar(t("نوار ابزار", "Toolbar"), fg));
        r.addView(toolbarContent(fg, null));
        return wrapScroll(r);
    }

    // window that opens by holding the hamburger on the home page
    void topBtnMenu() {
        final AlertDialog[] dlg = new AlertDialog[1];
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), dp(8));
        box.addView(toolbarContent(DLG, dlg));
        sv.addView(box);
        dlg[0] = new AlertDialog.Builder(this).setTitle(t("تنظیمات آیکون‌های بالا", "Top icons settings")).setView(sv)
                .setPositiveButton(t("تأیید", "OK"), null)
                .setOnDismissListener(d -> show()).show();
    }

    FrameLayout.LayoutParams abs(int g) {
        int sz = sc(sp.getInt("top_btn_sz", 40));
        return new FrameLayout.LayoutParams(dp(sz), dp(sz), g | Gravity.CENTER_VERTICAL);
    }

    FrameLayout topBar(String title, int fg) {
        FrameLayout h = new FrameLayout(this);
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextSize(20);
        tv.setTextColor(fg);
        tv.setTypeface(null, Typeface.BOLD);
        h.addView(tv, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        h.addView(iconBtn(R.drawable.ic_back, fg, v -> onBackPressed()), abs(Gravity.LEFT));
        return h;
    }

    View row(int dot, String sym, String title, String sub, int ind, int fg, View.OnClickListener c) {
        int ds = compact ? 22 : 30;
        int pad = compact ? 8 : 14;
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(pad), dp(pad), dp(pad), dp(pad));
        r.setBackground(boxBg(light() ? 0x14000000 : 0x1AFFFFFF, BOX_R));
        TextView d = new TextView(this);
        d.setTag("x");
        d.setText(sym);
        d.setGravity(Gravity.CENTER);
        d.setTextSize(compact ? 11 : 15);
        d.setTextColor(0xFFFFFFFF);
        d.setBackground(oval(dot));
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(ds), dp(ds));
        dl.setMarginEnd(dp(compact ? 8 : 14));
        r.addView(d, dl);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView a = new TextView(this);
        a.setText(title);
        a.setTextSize(compact ? 13 : 16);
        a.setTextColor(fg);
        col.addView(a);
        if (sub != null) {
            TextView s = new TextView(this);
            s.setText(sub);
            s.setTextSize(12);
            s.setTextColor(fg);
            s.setAlpha(0.65f);
            col.addView(s);
        }
        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        if (ind >= 1) r.addView(indView(ind));
        r.setOnClickListener(c);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(compact ? 6 : 10), 0, 0);
        r.setLayoutParams(lp);
        return r;
    }

    View accordionRow(int dot, String sym, String title, String key, final int min, int max, int def,
                      final String unit, int fg, final Runnable onStop) {
        return accordionRowL(dot, sym, title, key, min, max, def, unit, fg, onStop, null);
    }

    // same as accordionRow, plus a callback that runs while the lever is moving
    View accordionRowL(int dot, String sym, String title, String key, final int min, int max, int def,
                       final String unit, int fg, final Runnable onStop, IntConsumer live) {
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);

        int cur = sp.getInt(key, def);
        final View sliderView = slider(title, key, min, max, def, unit, fg, onStop, live);
        sliderView.setVisibility(View.GONE);

        View r = row(dot, sym, title, cur + unit, 0, fg, v -> {
            if (sliderView.getVisibility() == View.VISIBLE) {
                sliderView.setVisibility(View.GONE);
            } else {
                sliderView.setVisibility(View.VISIBLE);
            }
        });

        wrapper.addView(r);
        wrapper.addView(sliderView);
        return wrapper;
    }

    // same as accordionGroup, but the boxes inside it are built a little fainter (glass look, lighter)
    View accordionGroupFaded(int dot, String sym, String title, int fg, float k,
                             java.util.function.Supplier<View[]> items) {
        float old = boxFade;
        boxFade = k;
        View[] its;
        try { its = items.get(); } finally { boxFade = old; }
        return accordionGroup(dot, sym, title, fg, its);
    }

    // a row that opens several sliders (or other rows) at once
    View accordionGroup(int dot, String sym, String title, int fg, View... items) {
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        for (View it : items) box.addView(it);
        final String ak = page + "|" + title;
        box.setVisibility(Boolean.TRUE.equals(accState.get(ak)) ? View.VISIBLE : View.GONE);
        wrapper.addView(row(dot, sym, title, null, 0, fg, x -> {
            boolean now = box.getVisibility() != View.VISIBLE;
            box.setVisibility(now ? View.VISIBLE : View.GONE);
            accState.put(ak, now);
        }));
        wrapper.addView(box);
        return wrapper;
    }

    View slider(final String label, final String key, final int min, int max, int def,
                final String unit, int fg, final Runnable onStop) {
        return slider(label, key, min, max, def, unit, fg, onStop, null);
    }

    View slider(final String label, final String key, final int min, int max, int def,
                final String unit, int fg, final Runnable onStop, final IntConsumer live) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(12), dp(compact ? 6 : 10), dp(12), dp(6));
        final TextView tv = new TextView(this);
        tv.setTextColor(fg);
        tv.setTextSize(compact ? 12 : 14);
        int cur = sp.getInt(key, def);
        tv.setText(label + ": " + cur + unit);
        SeekBar sb = new SeekBar(this);
        sb.setMax(max - min);
        sb.setProgress(cur - min);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean u) {
                tv.setText(label + ": " + (p + min) + unit);
                sp.edit().putInt(key, p + min).apply();
                if (live != null) live.accept(p + min);
            }
            public void onStartTrackingTouch(SeekBar s) { }
            public void onStopTrackingTouch(SeekBar s) { if (onStop != null) onStop.run(); }
        });
        c.addView(tv);
        c.addView(sb);
        return c;
    }

    // live resize of every settings text while the font size lever moves
    void liveSettingsSize(int v) {
        float k = v / 16f;
        for (Map.Entry<TextView, Float> e : baseSz.entrySet()) {
            TextView tv = e.getKey();
            if (tv != null) tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, e.getValue() * k);
        }
    }

    void colorDialog(String title, int cur, final IntConsumer cb) {
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), dp(8));
        final ColorPicker cp = new ColorPicker(this);
        cp.setColor(cur);
        final TextView hx = new TextView(this);
        hx.setText(hex(cur));
        hx.setTextColor(DLG);
        hx.setPadding(0, dp(8), 0, 0);
        cp.setListener(c -> hx.setText(hex(c)));
        for (int r = 0; r < 2; r++) {
            LinearLayout rowL = new LinearLayout(this);
            for (int k = 0; k < 6; k++) {
                final int c = fcolors[r * 6 + k];
                View sw = new View(this);
                GradientDrawable g = oval(c | 0xFF000000);
                g.setStroke(dp(2), 0x88888888);
                sw.setBackground(g);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(36), dp(36));
                lp.setMargins(dp(4), dp(4), dp(4), dp(4));
                sw.setOnClickListener(w -> { cp.setColor(c); hx.setText(hex(c)); });
                rowL.addView(sw, lp);
            }
            box.addView(rowL);
        }
        box.addView(cp, new LinearLayout.LayoutParams(-1, -2));
        box.addView(hx);
        sv.addView(box);
        new AlertDialog.Builder(this).setTitle(title).setView(sv)
                .setPositiveButton(t("تأیید", "OK"), (d, w) -> cb.accept(cp.getColor()))
                .setNegativeButton(t("لغو", "Cancel"), null).show();
    }

    void prefColorDialog(final String key, int def, String title) {
        colorDialog(title, sp.getInt(key, def), c -> {
            sp.edit().putInt(key, c).apply();
            show();
        });
    }

    // ================= fonts =================
    boolean hasFa(CharSequence s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 0x0600 && c <= 0x06FF) || (c >= 0x0750 && c <= 0x077F)
                    || (c >= 0xFB50 && c <= 0xFDFF) || (c >= 0xFE70 && c <= 0xFEFF)) return true;
        }
        return false;
    }

    // key: "sys:name" | "asset:fa/File.ttf" | "file:/path/File.ttf"; level 2..10 = thickness
    Typeface face(String key, int level) {
        if (key == null || key.isEmpty()) return null;
        String ck = key + "|" + level;
        if (faceCache.containsKey(ck)) return faceCache.get(ck);
        Typeface tf = null;
        int w = level * 100;
        try {
            if (key.startsWith("sys:")) {
                String fam = key.substring(4);
                if (Build.VERSION.SDK_INT >= 28) {
                    tf = Typeface.create(Typeface.create(fam, Typeface.NORMAL), w, false);
                } else {
                    tf = Typeface.create(fam, level >= 7 ? Typeface.BOLD : Typeface.NORMAL);
                }
            } else {
                boolean vf = key.contains("-VF");
                if (key.startsWith("asset:")) {
                    String path = "fonts/" + key.substring(6);
                    if (vf) {
                        try {
                            tf = new Typeface.Builder(getAssets(), path)
                                    .setFontVariationSettings("'wght' " + w).build();
                        } catch (Throwable e) { tf = null; }
                    }
                    if (tf == null) tf = Typeface.createFromAsset(getAssets(), path);
                } else if (key.startsWith("file:")) {
                    File f = new File(key.substring(5));
                    if (vf) {
                        try {
                            tf = new Typeface.Builder(f)
                                    .setFontVariationSettings("'wght' " + w).build();
                        } catch (Throwable e) { tf = null; }
                    }
                    if (tf == null) tf = Typeface.createFromFile(f);
                }
            }
        } catch (Throwable e) { tf = null; }
        faceCache.put(ck, tf);
        return tf;
    }

    // applies the chosen font + thickness of a group ("s" settings, "i" icons/folders)
    void fnt(TextView tv, String grp) {
        try {
            boolean f = hasFa(tv.getText());
            String key = sp.getString("font" + grp + (f ? "_fa" : "_en"), "");
            int lv = sp.getInt("font" + grp + "_wt", 4);
            if (key.isEmpty() && lv == 4) return;
            if (key.isEmpty()) key = "sys:sans-serif";
            Typeface tf = face(key, lv);
            if (tf != null) tv.setTypeface(tf);
            boolean stat = !key.startsWith("sys:") && !key.contains("-VF");
            TextPaint tp = tv.getPaint();
            if (stat && lv > 4) {
                tp.setStyle(Paint.Style.FILL_AND_STROKE);
                tp.setStrokeWidth(tv.getTextSize() * (lv - 4) / 80f);
            } else {
                tp.setStyle(Paint.Style.FILL);
                tp.setStrokeWidth(0);
            }
        } catch (Throwable e) { }
    }

    // walks a page: untagged texts get the settings font and size (not on home),
    // texts tagged "ic" / "icf" get the icon/folder font; every scroll bar is switched off
    void walk(View v, boolean homePage) {
        if (v instanceof ScrollView) ((ScrollView) v).setVerticalScrollBarEnabled(false);
        if (v instanceof HorizontalScrollView) ((HorizontalScrollView) v).setHorizontalScrollBarEnabled(false);
        if (v instanceof TextView) {
            TextView tv = (TextView) v;
            Object tg = v.getTag();
            if ("ic".equals(tg) || "icf".equals(tg)) {
                fnt(tv, "i");
            } else if (tg == null && !homePage) {
                baseSz.put(tv, tv.getTextSize());
                if (sp.contains("fontS_size")) {
                    float k = sp.getInt("fontS_size", 16) / 16f;
                    tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, tv.getTextSize() * k);
                }
                fnt(tv, "s");
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) walk(g.getChildAt(i), homePage);
        }
    }

    String fontName(String key) {
        if (key == null || key.isEmpty()) return t("پیش‌فرض", "Default");
        if (key.startsWith("sys:")) return key.substring(4);
        String n = key.substring(key.lastIndexOf('/') + 1);
        n = n.replaceAll("(?i)\\.(ttf|otf)$", "").replace("-VF", "").replace('_', ' ');
        return n;
    }

    List<String> fontKeys(String lang) {
        List<String> l = new ArrayList<>();
        l.add("");
        if (lang.equals("fa")) {
            l.add("sys:sans-serif");
            l.add("sys:serif");
            l.add("sys:monospace");
        } else {
            l.add("sys:sans-serif");
            l.add("sys:sans-serif-condensed");
            l.add("sys:sans-serif-light");
            l.add("sys:serif");
            l.add("sys:monospace");
            l.add("sys:cursive");
            l.add("sys:casual");
        }
        try {
            String[] fs = getAssets().list("fonts/" + lang);
            if (fs != null) {
                Arrays.sort(fs);
                for (String f : fs) {
                    String lo = f.toLowerCase();
                    if (lo.endsWith(".ttf") || lo.endsWith(".otf")) l.add("asset:" + lang + "/" + f);
                }
            }
        } catch (Exception e) { }
        try {
            if (hasStorage()) {
                File[] fs = new File(backupDir(), "fonts").listFiles();
                if (fs != null) {
                    Arrays.sort(fs, (x, y) -> x.getName().compareToIgnoreCase(y.getName()));
                    for (File f : fs) {
                        String lo = f.getName().toLowerCase();
                        if (lo.endsWith(".ttf") || lo.endsWith(".otf")) l.add("file:" + f.getAbsolutePath());
                    }
                }
            }
        } catch (Exception e) { }
        return l;
    }

    // ================= page switching + animation =================
    int rank(int p) { return (p == HOME || p == PICKER) ? 0 : (p == SETTINGS ? 1 : 2); }

    int transDir(int from, int to) {
        if (from < 0 || from == to) return 0;
        int rf = rank(from), rt = rank(to);
        if (rf == 0 && rt == 1) return 1;
        if (rf == 1 && rt == 0) return -1;
        if (rf == 1 && rt == 2) return 2;
        if (rf == 2 && rt == 1) return -2;
        return 0;
    }

    // settings pages carry their own opaque background, so nothing needs to be painted here
    void swap(final View nv, int dir) {
        while (container.getChildCount() > 1) container.removeViewAt(0);
        final View old = container.getChildCount() > 0 ? container.getChildAt(0) : null;
        int W = container.getWidth() > 0 ? container.getWidth()
                : getResources().getDisplayMetrics().widthPixels;
        if (old == null || dir == 0) {
            container.removeAllViews();
            container.addView(nv, new FrameLayout.LayoutParams(-1, -1));
            return;
        }
        if (dir > 0) {
            container.addView(nv, new FrameLayout.LayoutParams(-1, -1));
            nv.setTranslationX(dir == 1 ? -W : W);
            nv.animate().translationX(0).setDuration(300)
                    .setInterpolator(new DecelerateInterpolator())
                    .withEndAction(() -> container.removeView(old)).start();
        } else {
            container.addView(nv, 0, new FrameLayout.LayoutParams(-1, -1));
            old.animate().translationX(dir == -1 ? -W : W).setDuration(260)
                    .setInterpolator(new AccelerateInterpolator())
                    .withEndAction(() -> container.removeView(old)).start();
        }
    }

    // safe top / bottom space of the settings pages (camera cutout + status bar, small gap below)
    int[] safeInsets(WindowInsets ins) {
        int top = 0, bot = 0;
        if (ins != null) {
            top = ins.getSystemWindowInsetTop();
            bot = ins.getSystemWindowInsetBottom();
            if (Build.VERSION.SDK_INT >= 28) {
                DisplayCutout dc = ins.getDisplayCutout();
                if (dc != null) top = Math.max(top, dc.getSafeInsetTop());
            }
        }
        if (top <= 0) {
            int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) top = getResources().getDimensionPixelSize(id);
        }
        return new int[]{top + dp(10), bot};
    }

    void saveKeep() {
    if (sp.getBoolean("fpKeep", false)) {
        String kv = openFolder == null ? "" : openFolder;
        if (!kv.equals(sp.getString("keepFolder", ""))) sp.edit().putString("keepFolder", kv).apply();
    }
}

    void show() {
        saveKeep();
        boolean mp = menuPage();
        int fg = 0xFFFFFFFF;
        if (mp && mbgBmp == null && isLight(solid(mbg()))) fg = 0xFF111111;
        fgc = fg;
        int barC = mp ? solid(mbg()) : (bgBmp != null ? 0 : solid(bg));
        // home page: the page reaches under the status bar / camera and the navigation bar (the toolbar and the dock
        // are moved by the insets listener); settings pages keep the normal layout
        boolean edge = true; // same window mode on every page: nothing resizes while the settings page slides
        getWindow().setStatusBarColor(edge ? 0 : barC);
        getWindow().setNavigationBarColor(edge ? 0 : barC);
        getWindow().getDecorView().setSystemUiVisibility(edge
                ? (View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION) : 0);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams wlp = getWindow().getAttributes();
            int cm = edge ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
            if (wlp.layoutInDisplayCutoutMode != cm) {
                wlp.layoutInDisplayCutoutMode = cm;
                getWindow().setAttributes(wlp);
            }
        }
        View v;
        if (page == SETTINGS) v = settingsPage(fg);
        else if (page == HPAGE) v = homeSetPage(fg);
        else if (page == TBAR) v = toolbarPage(fg);
        else if (page == BG) v = bgPage(fg);
        else if (page == HFBG) v = bgPageG(2, fg);
        else if (page == MBG) v = bgPageG(1, fg);
        else if (page == TBG) v = bgPageG(3, fg);
        else if (page == MBOX) v = bgPageG(4, fg);
        else if (page == SBOX) v = bgPageG(5, fg);
        else if (page == BEHAVIOR) v = behaviorPage(fg);
        else if (page == DOCK) v = dockPageView(fg);
        else if (page == DOCKBG) v = dockBgPage(fg);
        else if (page == ICON) v = iconPage(fg);
        else if (page == FOLDER) v = folderPage(fg);
        else if (page == FONT) v = fontMenuPage(fg);
        else if (page == FGRP) v = fontGroupPage(fg);
        else if (page == FPICK) v = fontPickPage(fg);
        else if (page == LANG) v = langPage(fg);
        else if (page == PICKER) v = pickerPage(fg);
        else if (page == MOCK) v = mockPage(fg);
        else if (page == ACTS) v = actsPage(fg);
        else if (page == SWL) v = swlPage(fg);
        else if (page == AIL) v = aiPage(fg);
        else if (page == BUB) v = bubblePage(fg);
        else if (page == BLBG) v = bgPageG(6, fg);
        else if (page == BACKUP) v = backupPage(fg);
        else if (page == RESTORE) v = restorePage(fg);
        else v = homePage(fg);
        v.setLayoutDirection(fa ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        if (page != HOME) {
            // settings pages keep their content between the status bar and the navigation keys themselves
            final int pl = v.getPaddingLeft(), pt0 = v.getPaddingTop(), pr = v.getPaddingRight(), pb0 = v.getPaddingBottom();
            v.setOnApplyWindowInsetsListener((x, ins) -> {
                int[] sb = safeInsets(ins);
                x.setPadding(pl, pt0 + sb[0], pr, pb0 + sb[1]);
                return ins;
            });
            // first guess right away, so the page never starts under the camera
            int[] s0 = safeInsets(getWindow().getDecorView().getRootWindowInsets());
            v.setPadding(pl, pt0 + s0[0], pr, pb0 + s0[1]);
            v.requestApplyInsets();
        }
        menuRoot = mp ? v : null;
        if (mp) v.setBackground(menuBgDrawable());
        baseSz.clear();
        walk(v, page == HOME);
        // glass boxes that show a piece of the menu picture are redrawn while the page scrolls
        if (mp && glass() && mbgBmp != null && v instanceof ScrollView) {
            v.setOnScrollChangeListener((vv, x, y, ox, oy) -> invalidateGlass(vv));
        }
        int from = shownPage;
        shownPage = page;
        swap(v, transDir(from, page));
        if (page == HOME) container.postDelayed(() -> saveRunRect(), 600);
    }

    // ================= home =================
    View homePage(final int fg) {
        cells.clear();
        gears.clear();
        curPanel = null;
        plusMenuV = null;
        smallName = null;
        frameV = null;
        folderCtl.clear();
        immersive = false;
        ovLayer = null;
        bridgeV = null;
        if (openFolder != null && sp.getBoolean("fpFull", false)) {
            ovLayer = new FrameLayout(this);
            ovLayer.setClipChildren(false);
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(pbg());
        root.setClipChildren(false);
        // the top toolbar sits a little below the front camera and the dock a little above the navigation bar
        // (the home frame grows by the freed space); the real insets replace this first guess
        int[] hi0 = homeInsets(null);
        root.setPadding(dp(12), hi0[0], dp(12), hi0[1] + dp(2));
        root.setOnApplyWindowInsetsListener((v, ins) -> {
            int[] hi = homeInsets(ins);
            v.setPadding(dp(12), hi[0], dp(12), hi[1] + dp(2));
            applyTopShift(v);
            v.post(() -> v.requestLayout());
            return ins;
        });
        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { v.requestApplyInsets(); }
            @Override public void onViewDetachedFromWindow(View v) { }
        });
        FrameLayout head = new FrameLayout(this);
        headV = head;
        normalBar = homeBar(fg);
        topBoxV = null;
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, -2);
        if (sp.getBoolean("topBoxOn", false)) {
            // frame around the hamburger, the plus and the counters (color, picture, length and width are adjustable)
            View tb = new View(this);
            tb.setBackground(new FrameDrawable(sp.getInt("topBoxBg", 0x6628282C), false,
                    dp(22), dp(3), tbSrc, sp.getInt("tbZoom", 100) / 100f,
                    sp.getInt("tbPx", 50) / 100f, sp.getInt("tbPy", 50) / 100f,
                    sp.getInt("tbFade", 0)));
            head.addView(tb, new FrameLayout.LayoutParams(-1, -1));
            topBoxV = tb;
            int availW = getResources().getDisplayMetrics().widthPixels - dp(24);
            hlp = new LinearLayout.LayoutParams(availW * sp.getInt("topBoxW", 100) / 100,
                    dp(Math.max(30, Math.min(100, sp.getInt("topBoxH", 56)))));
            hlp.gravity = Gravity.CENTER_HORIZONTAL;
            hlp.bottomMargin = dp(4);
            normalBar.setPadding(dp(10), 0, dp(10), 0);
            head.addView(normalBar, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL));
        } else {
            head.addView(normalBar, new FrameLayout.LayoutParams(-1, -2));
        }
        root.addView(head, hlp);
        applyTopShift(root);
        topLineV = null;
        if (false) {
            View tl = new View(this);
            tl.setBackgroundColor(topLineColor());
            LinearLayout.LayoutParams tll = new LinearLayout.LayoutParams(-1, Math.max(1, dp(1) / 2));
            tll.setMargins(-dp(12), dp(4), -dp(12), 0);
            root.addView(tl, tll);
            topLineV = tl;
        }
        final boolean dockTop = false;
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-1, -2);
        dl.setMargins(0, dp(4), 0, dp(4));

        LinearLayout dockBox = new LinearLayout(this);
        dockBoxV = dockBox;
        dockBox.setOrientation(LinearLayout.VERTICAL);
        View dock1V = buildDock();
        if (dock1V != null) dockBox.addView(dock1V, dl);

        FrameLayout mid = new FrameLayout(this);
        mid.setClipChildren(false);
        // home frame: a panel like the immersive folder, from the top of the dock up to the top icons
        final boolean framed = sp.getBoolean("hfOn", false);
        if (framed && !(pagedMode() && sp.getBoolean("hfFx", false))) {
            frameV = new View(this);
            frameV.setBackground(new FrameDrawable(sp.getInt("hfBg", 0x6628282C), false,
                    dp(26), dp(3), hfSrc, sp.getInt("hfZoom", 100) / 100f, sp.getInt("hfPx", 50) / 100f,
                    sp.getInt("hfPy", 50) / 100f, sp.getInt("hfFade", 0)));
            FrameLayout.LayoutParams fpp = new FrameLayout.LayoutParams(-1, -1);
            if (dockTop) fpp.topMargin = dockReserve(); else fpp.bottomMargin = dockReserve();
            mid.addView(frameV, fpp);
        }
        View gv = gridScroll(fg);
        gridView = gv;
        if (openFolder != null && !animFolder) {
            gv.setAlpha(1f - sp.getInt("fpDim", 25) / 100f);
            if (frameV != null) frameV.setAlpha(1f - sp.getInt("fpDim", 25) / 100f);
        } else if (openFolder != null && snapPanel != null) {
            // switching from one open folder to another: the page is already in its dimmed state, so nothing flashes
            boolean ff = sp.getInt("fpAlpha", 80) >= 100;
            float de = ff ? 0f : 1f - sp.getInt("fpDim", 25) / 100f;
            gv.setAlpha(de);
            if (frameV != null) frameV.setAlpha(de);
            if (ff && !sp.getBoolean("fpFull", false)) {
                head.setAlpha(0f);
                dockBox.setAlpha(0f);
                if (topLineV != null) topLineV.setAlpha(0f);
            }
        }
        FrameLayout.LayoutParams gvp = new FrameLayout.LayoutParams(-1, -1);
        if (framed) {
            if (dockTop) gvp.topMargin = dockReserve(); else gvp.bottomMargin = dockReserve();
        }
        mid.addView(gv, gvp);
        if (hpPager != null) {
            FrameLayout.LayoutParams e1 = new FrameLayout.LayoutParams(dp(12), -1, Gravity.LEFT);
            e1.bottomMargin = dockReserve();
            mid.addView(edgeStrip(true), e1);
            FrameLayout.LayoutParams e2 = new FrameLayout.LayoutParams(dp(12), -1, Gravity.RIGHT);
            e2.bottomMargin = dockReserve();
            mid.addView(edgeStrip(false), e2);
        }
        // the dock floats over the grid, which scrolls behind it
        FrameLayout.LayoutParams dbp = new FrameLayout.LayoutParams(-1, -2, dockTop ? Gravity.TOP : Gravity.BOTTOM);
        dbp.bottomMargin = dockShift();
        mid.addView(dockBox, dbp);
        trashBtn = trashView();
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(dp(36), dp(36),
                (dockTop ? Gravity.TOP : Gravity.BOTTOM) | Gravity.CENTER_HORIZONTAL);
        if (dockTop) tlp.topMargin = dockReserve(); else tlp.bottomMargin = dockReserve();
        if (plusOpen) mid.addView(plusMenu(fg), new FrameLayout.LayoutParams(-1, -1));
        if (openFolder != null) mid.addView(folderPopup(fg), new FrameLayout.LayoutParams(-1, -1));
        // the three drag buttons sit above the open folder, so they stay reachable
        mid.addView(trashBtn, tlp);
        cancelBtn = cancelView();
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(dp(36), dp(36), tlp.gravity);
        clp.topMargin = tlp.topMargin;
        clp.bottomMargin = tlp.bottomMargin;
        mid.addView(cancelBtn, clp);
        int gapPx = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_MM, 10, getResources().getDisplayMetrics());
        View nb = newFolderView();
        FrameLayout.LayoutParams nlp2 = new FrameLayout.LayoutParams(dp(36), dp(36), tlp.gravity);
        nlp2.topMargin = tlp.topMargin;
        nlp2.bottomMargin = tlp.bottomMargin;
        nb.setTranslationX(-(dp(36) + gapPx));
        mid.addView(nb, nlp2);
        View mb = moveHomeView();
        FrameLayout.LayoutParams mlp2 = new FrameLayout.LayoutParams(dp(36), dp(36), tlp.gravity);
        mlp2.topMargin = tlp.topMargin;
        mlp2.bottomMargin = tlp.bottomMargin;
        mb.setTranslationX(dp(36) + gapPx);
        mid.addView(mb, mlp2);
        root.addView(mid, new LinearLayout.LayoutParams(-1, 0, 1));
        updateSel();
        if (ovLayer != null) {
            // immersive folder: its top controls live in a layer above the whole page
            FrameLayout outer = new FrameLayout(this);
            outer.setClipChildren(false);
            outer.addView(root, new FrameLayout.LayoutParams(-1, -1));
            if (fromDock) {
                bridgeV = new Bridge(this);
                outer.addView(bridgeV, new FrameLayout.LayoutParams(-1, -1));
            }
            outer.addView(ovLayer, new FrameLayout.LayoutParams(-1, -1));
            return outer;
        }
        return root;
    }

    FrameLayout homeBar(int fg) {
        FrameLayout h = new FrameLayout(this);
        countBarV = countBar(fg);
        h.addView(countBarV, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        int dsz = sp.getInt("home_btn_sz", 40);
        homeMenuV = iconBtn(R.drawable.ic_menu, fg, v -> {
            if (menuHold) { menuHold = false; return; }
            plusOpen = false;
            page = SETTINGS;
            show();
        });
        styleTop((ImageView) homeMenuV, fg);
        holdOne(homeMenuV, () -> topBtnMenu());
        homeMenuV.setTranslationX(dp(sp.getInt("hamX", 0)));
        homeMenuV.setTranslationY(dp(sp.getInt("hamY", 0)));
        h.addView(homeMenuV, absHome(Gravity.LEFT, sp.getInt("hamW", dsz), sp.getInt("hamH", dsz)));

        homePlusV = iconBtn(R.drawable.ic_plus, fg, v -> {
            if (plusOpen) closePlus();
            else { plusOpen = true; show(); }
        });
        styleTop((ImageView) homePlusV, fg);
        homePlusV.setTranslationX(dp(sp.getInt("plusX", 0)));
        homePlusV.setTranslationY(dp(sp.getInt("plusY", 0)));
        h.addView(homePlusV, absHome(Gravity.RIGHT, sp.getInt("plusW", dsz), sp.getInt("plusH", dsz)));
        return h;
    }

    View countBar(int fg) {
        LinearLayout c = new LinearLayout(this);
        c.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        c.setGravity(Gravity.CENTER);
        c.setTranslationX(dp(sp.getInt("box_px", 0)));
        c.setTranslationY(dp(sp.getInt("box_py", 0)));
        int appsHome = 0;
        for (Item it : home) {
            if (!it.folder) appsHome++;
        }

        int folderCount = 0;
        for (Item it : home) if (it.folder) folderCount++;
        if (openFolder != null) {
            Item f = findFolder(openFolder);
            if (f != null) folderCount = f.apps.size();
        }

        if (sp.getBoolean("stackOn", false)) { c.addView(stackBox(fg)); return c; }
        boolean clkOn = sp.getBoolean("clockOn", false), dtOn = sp.getBoolean("dateOn", false);
        if (clkOn || dtOn) {
            // clock on: folder counter -> clock (centered), icon counter removed
            // date on + clock on: date on the right of the clock
            // date on + clock off: folder counter and icon counter removed, date centered
            if (clkOn) c.addView(clockBox(fg));
            if (dtOn) c.addView(dateBox(fg));
            return c;
        }
        c.addView(countBox(folderCount, fg));
        c.addView(countBox(appsHome, fg));
        return c;
    }

    Typeface weightFace(int i) {
        String[] n = {"sans-serif-thin", "sans-serif-light", "sans-serif", "sans-serif"};
        return Typeface.create(n[Math.max(0, Math.min(3, i))], i >= 3 ? Typeface.BOLD : Typeface.NORMAL);
    }

    View countBox(int n, int fg) {
        TextView tv = new TextView(this);
        tv.setText(String.valueOf(n));
        tv.setGravity(Gravity.CENTER);
        tv.setTextSize(sc(sp.getInt("box_ts", 18)));
        tv.setTextColor(sp.getInt("box_tc", fg));
        tv.setTypeface(weightFace(sp.getInt("box_tw", 3)));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(16));
        g.setColor(sp.getInt("box_bg", 0x33FFFFFF));
        tv.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                dp(sc(sp.getInt("box_w", 62))), dp(sc(sp.getInt("box_h", 40))));
        lp.setMargins(dp(2), 0, dp(2), 0);
        tv.setLayoutParams(lp);
        longOne(tv, () -> boxMenu());
        return tv;
    }

    // ================= clock and Jalali date =================
    static final String DATE_SEP = ".";

    int[] g2j(int gy, int gm, int gd) {
        int[] gdm = {0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334};
        int gy2 = gm > 2 ? gy + 1 : gy;
        int days = 355666 + 365 * gy + (gy2 + 3) / 4 - (gy2 + 99) / 100 + (gy2 + 399) / 400 + gd + gdm[gm - 1];
        int jy = -1595 + 33 * (days / 12053);
        days %= 12053;
        jy += 4 * (days / 1461);
        days %= 1461;
        if (days > 365) { jy += (days - 1) / 365; days = (days - 1) % 365; }
        int jm = days < 186 ? 1 + days / 31 : 7 + (days - 186) / 30;
        int jd = 1 + (days < 186 ? days % 31 : (days - 186) % 30);
        return new int[]{jy, jm, jd};
    }

    void setClock(TextView tv) {
        Calendar c = Calendar.getInstance();
        String hm = String.format(Locale.US, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
        String ss = String.format(Locale.US, " %02d", c.get(Calendar.SECOND));
        android.text.SpannableString st = new android.text.SpannableString(hm + ss);
        st.setSpan(new android.text.style.RelativeSizeSpan(0.6f), hm.length(), st.length(), 0);
        st.setSpan(new android.text.style.SuperscriptSpan(), hm.length(), st.length(), 0);
        st.setSpan(new android.text.style.ForegroundColorSpan((sp.getInt("box_tc", fgc) & 0x00FFFFFF) | 0x88000000),
                hm.length(), st.length(), 0);
        tv.setText(st);
    }

    void setDate(TextView tv) {
        Calendar c = Calendar.getInstance();
        int[] j = g2j(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
        tv.setText(String.format(Locale.US, "%02d" + dateSep() + "%02d" + dateSep() + "%02d",
                j[0] % 100, j[1], j[2]));
    }

    // refreshes the text while the box is on screen (clock every second, date every 30 seconds)
    void startTick(final TextView tv, final boolean date) {
        final Runnable[] r = new Runnable[1];
        r[0] = () -> {
            if (!tv.isAttachedToWindow()) return;
            if (date) setDate(tv); else setClock(tv);
            tv.postDelayed(r[0], date ? 60000 : 1000);
        };
        tv.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { v.removeCallbacks(r[0]); v.post(r[0]); }
            @Override public void onViewDetachedFromWindow(View v) { v.removeCallbacks(r[0]); }
        });
        if (tv.isAttachedToWindow()) tv.post(r[0]);
    }

    // clock / date boxes: same look as the counters, a little wider, thin light edge
    TextView timeBox(int fg, float wMul, float tsMul, boolean date) {
        TextView tv = new TextView(this);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setIncludeFontPadding(false);
        tv.setTextSize(sc(Math.round(sp.getInt("box_ts", 18) * tsMul)));
        tv.setTextColor(sp.getInt("box_tc", fg));
        tv.setTypeface(weightFace(sp.getInt("box_tw", 3)));
        try { tv.setFontFeatureSettings("tnum"); } catch (Throwable e) { }
        tv.setLetterSpacing(0.04f);
        tv.setAutoSizeTextTypeUniformWithConfiguration(6, Math.max(7, sc(Math.round(sp.getInt("box_ts", 18) * tsMul))), 1, TypedValue.COMPLEX_UNIT_SP);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(16));
        g.setColor(sp.getInt("box_bg", 0x33FFFFFF));
        g.setStroke(Math.max(1, dp(1)), 0x33FFFFFF);
        tv.setBackground(g);
        tv.setShadowLayer(dp(2), 0, 0, 0x55000000);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                dp(Math.round(sc(sp.getInt("box_w", 62)) * wMul)), dp(sc(sp.getInt("box_h", 40))));
        lp.setMargins(dp(2), 0, dp(2), 0);
        tv.setLayoutParams(lp);
        if (date) setDate(tv); else setClock(tv);
        startTick(tv, date);
        longOne(tv, () -> boxMenu());
        return tv;
    }

    View clockBox(int fg) { return timeBox(fg, 1.0f, 0.85f, false); }

    View dateBox(int fg) { return timeBox(fg, 1.0f, 0.85f, true); }

    static final String[] ANIM_FA = {"کشویی", "محو شدن", "زوم", "ریزش", "کرکره‌ای", "لایه‌ای (Stack)", "ورق‌زدن (Flip)", "شیشه‌ای (Glass)", "زوم عمقی (Zoom)", "چرخش سه‌بعدی (3D)", "مکعب سه‌بعدی (3D Cube)"};
    static final String[] ANIM_EN = {"Slide", "Fade", "Zoom", "Drop", "Shutter", "Stack", "Flip", "Glass", "Zoom depth", "3D flip", "3D cube"};
    static final int[] ANIM_IDS = {0, 1, 2, 3, 4, 5, 6, 9, 10, 11, 12};

    String dateSep() { return sp.getBoolean("momayyez", false) ? "\u066B" : DATE_SEP; }

    // clock / date / stacked / decimal-mark options (rebuilt in place when one is tapped)
    LinearLayout timeSection(final int fg) {
        LinearLayout sec = new LinearLayout(this);
        sec.setOrientation(LinearLayout.VERTICAL);
        timeRows(sec, fg);
        return sec;
    }

    void timeRows(final LinearLayout sec, final int fg) {
        sec.removeAllViews();
        sec.addView(timeOpt(sec, fg, 0xFF00ACC1, "🕰", t("ساعت", "Clock"),
                t("ساعت دیجیتال به جای کادر شمارش پوشه، در وسط نوار ابزار",
                        "Digital clock instead of the folder counter, in the toolbar center"), "clockOn"));
        sec.addView(timeOpt(sec, fg, 0xFF26C6DA, "📅", t("تاریخ", "Date"),
                t("تاریخ شمسی به جای کادر شمارش آیکون، سمت راست ساعت",
                        "Jalali date instead of the icon counter, right of the clock"), "dateOn"));
        sec.addView(timeOpt(sec, fg, 0xFF7E57C2, "▤", t("ساعت و تاریخ", "Clock and date"),
                t("ساعت بزرگ و تاریخ کوچک‌تر و کم‌رنگ‌تر زیر آن، در بالای صفحه اصلی",
                        "Big clock with a smaller, fainter date under it"), "stackOn"));
        sec.addView(timeOpt(sec, fg, 0xFFFF7043, "٫", t("ممیز", "Decimal mark"),
                t("جداکننده‌های تاریخ به ممیز (٫) تبدیل شوند",
                        "Date separators become the decimal mark (٫)"), "momayyez"));
        sec.addView(accordionRow(0xFF8BC34A, "↕", t("فاصله تاریخ و ساعت", "Clock and date gap"),
                "stackGapUm", -3000, 3000, 0, " µm", fg, null));
    }

    View timeOpt(final LinearLayout sec, final int fg, int dot, String sym, String title, String sub, final String key) {
        View r = row(dot, sym, title, sub, sp.getBoolean(key, false) ? 3 : 2, fg, null);
        r.setOnClickListener(v -> {
            boolean now = !sp.getBoolean(key, false);
            SharedPreferences.Editor ed = sp.edit().putBoolean(key, now);
            if (now && (key.equals("clockOn") || key.equals("dateOn"))) ed.putBoolean("stackOn", false);
            if (now && key.equals("stackOn")) ed.putBoolean("clockOn", false).putBoolean("dateOn", false);
            ed.apply();
            timeRows(sec, fg);
        });
        return r;
    }

    // one box: big clock on top, smaller and fainter date under it (same look as the other boxes)
    View stackBox(int fg) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER);
        b.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(16));
        g.setColor(sp.getInt("box_bg", 0x33FFFFFF));
        g.setStroke(Math.max(1, dp(1)), 0x33FFFFFF);
        b.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                dp(sc(sp.getInt("box_w", 62))), dp(sc(sp.getInt("box_h", 40)) * 3 / 2));
        lp.setMargins(dp(2), 0, dp(2), 0);
        b.setLayoutParams(lp);
        int ts = Math.max(7, sc(sp.getInt("box_ts", 18)));

        TextView ck = new TextView(this);
        ck.setGravity(Gravity.CENTER);
        ck.setSingleLine(true);
        ck.setIncludeFontPadding(false);
        ck.setTextSize(ts * 0.95f);
        ck.setAutoSizeTextTypeUniformWithConfiguration(6, ts, 1, TypedValue.COMPLEX_UNIT_SP);
        ck.setTextColor(sp.getInt("box_tc", fg));
        ck.setTypeface(weightFace(sp.getInt("box_tw", 3)));
        b.addView(ck, new LinearLayout.LayoutParams(-1, 0, 1.2f));

        TextView dt = new TextView(this);
        dt.setGravity(Gravity.CENTER);
        dt.setSingleLine(true);
        dt.setIncludeFontPadding(false);
        dt.setTextSize(ts * 0.6f);
        dt.setAutoSizeTextTypeUniformWithConfiguration(6, Math.max(7, ts * 6 / 10), 1, TypedValue.COMPLEX_UNIT_SP);
        dt.setTextColor(sp.getInt("box_tc", fg));
        dt.setAlpha(0.65f);
        dt.setTypeface(weightFace(sp.getInt("box_tw", 3)));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, 0, 0.8f);
        dlp.topMargin = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_MM, sp.getInt("stackGapUm", 0) / 1000f, getResources().getDisplayMetrics());
        b.addView(dt, dlp);

        setClock(ck);
        startTick(ck, false);
        setDate(dt);
        startTick(dt, true);
        longOne(b, () -> boxMenu());
        return b;
    }

    // position of one page or panel: rel 0 = visible, +1 = one step to the right, -1 = one step to the left
    void applyPageTransform(View v, float rel, int anim, int W, int H) {
        float a = Math.abs(rel);
        int pw = v.getWidth() > 0 ? v.getWidth() : W;
        int ph = v.getHeight() > 0 ? v.getHeight() : H;
        v.setCameraDistance(12000f * getResources().getDisplayMetrics().density);
        float tx = -rel * W, ty = 0, al = 1f, scx = 1f, scy = 1f, rot = 0, rotY = 0, tz = 0;
        v.setPivotX(pw / 2f);
        v.setPivotY(ph / 2f);
        switch (anim) {
            case 1: tx = 0; al = Math.max(0f, 1f - a); break;                       // fade
            case 2: tx = 0; scx = Math.max(0.4f, 1f - a * 0.5f); scy = scx; al = Math.max(0f, 1f - a); break; // zoom
            case 3: ty = a * H * 0.25f; al = Math.max(0f, 1f - a); break;           // drop
            case 4: scx = Math.max(0.2f, 1f - a * 0.6f); break;                     // shutter
            case 5: scx = scy = Math.max(0.8f, 1f - a * 0.1f); tz = -a * 20f; break; // stack
            case 6: tx = 0; rotY = -rel * 90f; break;                               // flip
            case 7: tx = 0; v.setPivotX(rel > 0 ? 0 : pw); rotY = -rel * 60f; break; // page
            case 8: scx = scy = Math.max(0.75f, 1f - a * 0.25f); tz = -a * 40f; tx = -rel * W * 0.7f; al = Math.max(0f, 1f - a * 0.4f); break; // depth
            case 9: tx = -rel * W * 0.5f; scx = scy = Math.max(0.9f, 1f - a * 0.1f); al = Math.max(0f, 1f - a); break; // glass
            case 10: tx = 0; scx = scy = 1f + a * 0.3f; al = Math.max(0f, 1f - a); break; // zoom depth
            case 11: rotY = rel * 70f; break;                                       // 3d flip
            case 12: tx = 0; v.setPivotX(rel > 0 ? 0 : pw); rotY = rel * 90f; break; // 3d cube
            default: break;                                                         // slide
        }
        v.setTranslationX(tx);
        v.setTranslationY(ty);
        v.setTranslationZ(tz);
        v.setRotation(rot);
        v.setRotationY(rotY);
        v.setScaleX(scx);
        v.setScaleY(scy);
        v.setAlpha(al);
    }

    // list of transition animations under "Paged mode off" on the home settings page
    // rows of the effect list; withDefault adds a first row that keeps the old behaviour (folders only)
    View[] animRows(final int fg, final String key, boolean withDefault) {
        int cur = sp.getInt(key, withDefault ? -1 : 0);
        List<View> items = new ArrayList<>();
        if (withDefault) {
            items.add(row(0xFF78909C, "•", t("پیش‌فرض", "Default"), null, cur == -1 ? 3 : 2, fg,
                    v -> { sp.edit().putInt(key, -1).apply(); show(); }));
        }
        for (int i = 0; i < ANIM_FA.length; i++) {
            final int idx = ANIM_IDS[i];
            items.add(row(0xFF26A69A, "▶", t(ANIM_FA[i], ANIM_EN[i]), null, cur == ANIM_IDS[i] ? 3 : 2, fg,
                    v -> { sp.edit().putInt(key, idx).apply(); show(); }));
        }
        return items.toArray(new View[0]);
    }

    // home settings: effects (boxes are a little fainter)
    View transAnimGroup(final int fg) {
        return accordionGroupFaded(0xFF26A69A, "🎞", t("افکت ها", "Effects"), fg, 0.6f,
                () -> animRows(fg, "pgAnim", false));
    }

    boolean onScreen(View v) {
        int[] l = new int[2];
        v.getLocationOnScreen(l);
        int sw = getResources().getDisplayMetrics().widthPixels;
        return l[0] >= 0 && l[0] + v.getWidth() <= sw;
    }

    // a tap does nothing; the settings open only after holding for 1 second
    void longOne(View v, final Runnable r) {
        final Runnable fire = () -> { vib(25); r.run(); };
        v.setOnTouchListener((w, ev) -> {
            int a = ev.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                pressDown(w);
                w.postDelayed(fire, 1000);
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                pressUp(w);
                w.removeCallbacks(fire);
            } else if (a == MotionEvent.ACTION_MOVE) {
                if (ev.getX() < -dp(20) || ev.getY() < -dp(20)
                        || ev.getX() > w.getWidth() + dp(20) || ev.getY() > w.getHeight() + dp(20)) w.removeCallbacks(fire);
            }
            return true;
        });
    }

    // counters settings (folder and icon counters)
    LinearLayout countersContent(int fg) {
        float oldFade = boxFade;
        boxFade = 0.6f; // every box inside is a little fainter
        try {
            return countersBoxes(fg);
        } finally {
            boxFade = oldFade;
        }
    }

    LinearLayout countersBoxes(int fg) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
                          // placement of the two counters together: first lever = horizontal, second = vertical
        box.addView(accordionGroup(0xFF00ACC1, "↔", t("جای‌گیری", "Placement"), fg,
                slider(t("جابجایی طولی", "Horizontal move"), "box_px", -100, 100, 0, " dp", fg, null),
                slider(t("جابجایی عرضی", "Vertical move"), "box_py", -30, 30, 0, " dp", fg, null)));
        box.addView(timeSection(fg));
        box.addView(accordionRow(0xFF2196F3, "📐", t("عرض کادرها", "Boxes width"), "box_w", 20, 140, 62, " dp", fg, null));
        box.addView(accordionRow(0xFF2196F3, "📐", t("ارتفاع کادرها", "Boxes height"), "box_h", 10, 50, 40, " dp", fg, null));
        box.addView(accordionRow(0xFF4CAF50, "🔤", t("اندازه اعداد", "Numbers size"), "box_ts", 10, 40, 18, " sp", fg, null));
        box.addView(accordionRow(0xFFFF9800, "⚖", t("کلفتی اعداد (۰ تا ۳)", "Numbers weight (0 to 3)"), "box_tw", 0, 3, 3, "", fg, null));
        box.addView(row(0xFFE91E63, "🎨", t("رنگ و شفافیت کادرها", "Boxes color and transparency"), null, 0, fg,
                w -> prefColorDialog("box_bg", 0x33FFFFFF, t("رنگ کادرها", "Boxes color"))));
        box.addView(row(0xFF9C27B0, "🔤", t("رنگ اعداد", "Numbers color"), null, 0, fg,
                w -> prefColorDialog("box_tc", fgc, t("رنگ اعداد", "Numbers color"))));
        return box;
    }

    // window that opens by holding the counters on the home page
    void boxMenu() {
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), dp(8));
        box.addView(countersContent(DLG));
        sv.addView(box);
        new AlertDialog.Builder(this).setTitle(t("تنظیمات کادرها", "Boxes settings")).setView(sv)
                .setPositiveButton(t("تأیید", "OK"), null)
                .setOnDismissListener(d -> show()).show();
    }

    View selectionBar(int fg) {
        LinearLayout b = new LinearLayout(this);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.addView(btn("✕", fg, v -> { sel.clear(); refreshCells(); updateSel(); }));
        selCount = new TextView(this);
        selCount.setTextColor(fg);
        selCount.setTextSize(18);
        selCount.setGravity(Gravity.CENTER);
        b.addView(selCount, new LinearLayout.LayoutParams(0, -2, 1));
        b.addView(btn(t("پوشه جدید", "New folder"), fg, v -> {
            List<String> ap = new ArrayList<>();
            for (String s : sel) if (!s.startsWith("folder:")) ap.add(s);
            addFolderDialog(ap);
        }));
        b.addView(btn(t("حذف", "Remove"), fg, v -> {
            for (String k : new ArrayList<>(sel)) {
                if (k.startsWith("folder:")) continue;
                final String p = k;
                home.removeIf(q -> !q.folder && p.equals(q.pkg));
                for (Item it : home) if (it.folder) it.apps.remove(p);
                removeFromDocks(p);
            }
            sel.clear();
            saveHome();
            show();
        }));
        return b;
    }

    // the cancel button shows while items are selected and the finger is up
    void updateSel() {
        if (cancelBtn == null) return;
        boolean on = !sel.isEmpty() && !dragActive;
        cancelBtn.setAlpha(on ? 0.6f : 0f);
        cancelBtn.setClickable(on);
    }

    View cancelView() {
        TextView c = new TextView(this);
        c.setTag("x");
        c.setText("✕");
        c.setTextSize(16);
        c.setTextColor(0xFFFFFFFF);
        c.setGravity(Gravity.CENTER);
        c.setBackground(oval(0x44888888));
        c.setAlpha(0f);
        c.setClickable(false);
        c.setOnClickListener(w -> {
            sel.clear();
            selDock.clear();
            refreshCells();
            updateSel();
        });
        return c;
    }

    // select an item (dock = it was selected from the dock)
    void selHold(String key, boolean dock, View w, String payload) {
        boolean was = sel.contains(key);
        if (!was) sel.add(key);
        if (dock) selDock.add(key); else selDock.remove(key);
        refreshCells();
        updateSel();
        if (dock) dockDrag(w, payload, was); else dragStart(w, payload, was);
    }

    void toggleSelD(String key) {
        if (sel.remove(key)) selDock.remove(key);
        else { sel.add(key); selDock.add(key); }
        refreshCells();
        updateSel();
    }

    void styleSel(View v, boolean on) {
        if (on) {
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(18));
            g.setColor(0x553D7BF7);
            g.setStroke(dp(2), 0xFF3D7BF7);
            v.setBackground(g);
        } else {
            v.setBackground(null);
        }
    }

    void addTo(Map<String, List<View>> m, String key, View v) {
        List<View> l = m.get(key);
        if (l == null) { l = new ArrayList<>(); m.put(key, l); }
        l.add(v);
    }

    void refreshCells() {
        for (Map.Entry<String, List<View>> e : cells.entrySet()) {
            boolean on = sel.contains(e.getKey());
            for (View v : e.getValue()) styleSel(v, on);
        }
        for (Map.Entry<String, List<View>> e : gears.entrySet()) {
            boolean on = sel.contains(e.getKey());
            for (View v : e.getValue()) v.setVisibility(on ? View.VISIBLE : View.GONE);
        }
    }

    void toggleSel(String key) {
        if (sel.remove(key)) selDock.remove(key);
        else { sel.add(key); selDock.remove(key); }
        refreshCells();
        updateSel();
    }

    // small gear shown at the top right of a selected item
    ImageView makeGear(int gdp, String key) {
        ImageView gr = new ImageView(this);
        gr.setImageResource(R.drawable.ic_gear);
        gr.setColorFilter(0xFFFFFFFF);
        int pd = dp(Math.max(2, gdp / 6));
        gr.setPadding(pd, pd, pd, pd);
        gr.setBackground(oval(0xCC3D7BF7));
        gr.setVisibility(sel.contains(key) ? View.VISIBLE : View.GONE);
        addTo(gears, key, gr);
        return gr;
    }

    void openIconSettings() {
        sel.clear();
        openFolder = null;
        plusOpen = false;
        page = ICON;
        show();
    }

    // wraps an app cell so it can show the gear (fill = cell has a fixed height)
    View gearWrap(LinearLayout inner, String key, boolean fill) {
        FrameLayout fc = new FrameLayout(this);
        inner.setLayoutParams(new FrameLayout.LayoutParams(-1, fill ? -1 : -2));
        fc.addView(inner);
        ImageView gr = makeGear(26, key);
        gr.setOnClickListener(w -> openIconSettings());
        FrameLayout.LayoutParams gl = new FrameLayout.LayoutParams(dp(26), dp(26), Gravity.TOP | Gravity.RIGHT);
        gl.setMargins(0, dp(2), dp(2), 0);
        fc.addView(gr, gl);
        addTo(cells, key, fc);
        styleSel(fc, sel.contains(key));
        return fc;
    }

    void dragStart(View w, String payload) { dragStart(w, payload, true); }

    void dragStart(View w, String payload, boolean multi) {
        dragFromDock = false;
        dragFromFolder = false;
        dragActive = true;
        updateSel();
        dragKeys.clear();
        String key = payload.startsWith("app:") ? payload.substring(4) : payload;
        if (multi && sel.size() > 1 && sel.contains(key)) {
            for (String k : sel) dragKeys.add(k.startsWith("folder:") ? k : "app:" + k);
        } else {
            dragKeys.add(payload);
        }
        if (trashBtn != null) trashBtn.setAlpha(0.8f);
        ClipData cd = ClipData.newPlainText("vad", payload);
        w.startDragAndDrop(cd, new View.DragShadowBuilder(w), payload, 0);
    }

    // drag started from the dock (apps and folders can go to home or trash)
    void dockDrag(View w, String payload, boolean multi) {
        dragStart(w, payload, multi);
        dragFromDock = true;
    }

    List<String> keysFor(String payload) {
        if (dragKeys.size() > 1 && dragKeys.contains(payload)) return new ArrayList<>(dragKeys);
        List<String> l = new ArrayList<>();
        l.add(payload);
        return l;
    }

    void endDropCleanup() {
        dragKeys.clear();
        dragFromDock = false;
        dragFromFolder = false;
        sel.clear();
        selDock.clear();
    }

    void removeFromDocks(String entry) {
        for (List<String> l : dock1) l.remove(entry);
        saveDock();
    }

    ImageView styleAppIcon(Drawable d, int sizePx) {
        return styleAppIcon(d, sizePx, dp(18));
    }

    ImageView styleAppIcon(Drawable d, int sizePx, final int radiusPx) {
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(d);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radiusPx);
            }
        });
        iv.setClipToOutline(true);
        iv.setLayoutParams(new LinearLayout.LayoutParams(sizePx, sizePx));
        return iv;
    }

    LinearLayout cell(String pkg, int fg, int iconPx, int textSp, boolean showName) {
        LinearLayout it = new LinearLayout(this);
        it.setOrientation(LinearLayout.VERTICAL);
        it.setGravity(Gravity.CENTER);
        it.setPadding(dp(2), dp(4), dp(2), dp(4));
        ImageView iv = styleAppIcon(icon(pkg), iconPx);
        it.addView(iv);
        if (showName) {
            TextView tv = new TextView(this);
            tv.setTag("ic");
            tv.setText(label(pkg));
            tv.setTextColor(fg);
            tv.setTextSize(textSp);
            tv.setMaxLines(1);
            tv.setEllipsize(TextUtils.TruncateAt.END);
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(0, dp(4), 0, 0);
            it.addView(tv);
        }
        return it;
    }

    // fixed rows: Persian fills each row from the right, English from the left
    LinearLayout buildRows(List<View> views, int cols, int cellH, int gapPx) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        int n = views.size();
        int rowsN = (n + cols - 1) / cols;
        for (int r = 0; r < rowsN; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
            View[] slots = new View[cols];
            for (int j = 0; j < cols; j++) {
                int idx = r * cols + j;
                View v = idx < n ? views.get(idx) : new View(this);
                slots[fa ? cols - 1 - j : j] = v;
            }
            for (int k = 0; k < cols; k++) {
                slots[k].setLayoutParams(new LinearLayout.LayoutParams(0, cellH > 0 ? cellH : -2, 1));
                row.addView(slots[k]);
            }
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
            if (r > 0) rl.topMargin = gapPx;
            box.addView(row, rl);
        }
        return box;
    }

    // blur everything behind the open folder (Android 12+)
    void applyBlur(float r) {
        if (Build.VERSION.SDK_INT < 31) return;
        RenderEffect e = r > 0.5f ? RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP) : null;
        View[] vs = immersive ? new View[]{gridView, frameV} : new View[]{gridView, frameV, headV, dockBoxV};
        for (View v : vs) if (v != null) v.setRenderEffect(e);
    }

    // ---- frosted glass: blurred picture of what is under the open folder panel ----
    void boxBlurLine(int[] src, int[] dst, int start, int step, int n, int r) {
        int div = 2 * r + 1;
        int sr = 0, sg = 0, sb = 0;
        for (int i = -r; i <= r; i++) {
            int c = src[start + Math.max(0, Math.min(n - 1, i)) * step];
            sr += (c >> 16) & 255;
            sg += (c >> 8) & 255;
            sb += c & 255;
        }
        for (int i = 0; i < n; i++) {
            dst[start + i * step] = 0xFF000000 | ((sr / div) << 16) | ((sg / div) << 8) | (sb / div);
            int out = src[start + Math.max(0, i - r) * step];
            int in = src[start + Math.min(n - 1, i + r + 1) * step];
            sr += ((in >> 16) & 255) - ((out >> 16) & 255);
            sg += ((in >> 8) & 255) - ((out >> 8) & 255);
            sb += (in & 255) - (out & 255);
        }
    }

    void boxBlur(int[] px, int w, int h, int r) {
        int[] tmp = new int[px.length];
        for (int y = 0; y < h; y++) boxBlurLine(px, tmp, y * w, 1, w, r);
        for (int x = 0; x < w; x++) boxBlurLine(tmp, px, x, w, h, r);
    }

    // draws the home screen part that lies under the panel into a small bitmap and blurs it
    Bitmap blurBackdrop(int sx, int sy, int pw, int ph, int level, float dimAlpha) {
        final int ds = 6;
        int bw = Math.max(2, pw / ds), bh = Math.max(2, ph / ds);
        Bitmap b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.scale(bw / (float) pw, bh / (float) ph);
        c.translate(-sx, -sy);
        if (bgBmp != null) c.drawBitmap(bgBmp, 0, 0, null);
        else c.drawColor(solid(bg) | 0xFF000000);
        View[] vs = {headV, frameV, gridView, dockBoxV};
        for (View v : vs) {
            if (v == null || v.getWidth() == 0) continue;
            try {
                int[] l = new int[2];
                v.getLocationOnScreen(l);
                c.save();
                c.translate(l[0], l[1]);
                if (v == gridView) {
                    c.saveLayerAlpha(new RectF(0, 0, v.getWidth(), v.getHeight()),
                            (int) (dimAlpha * 255), Canvas.ALL_SAVE_FLAG);
                    v.draw(c);
                    c.restore();
                } else {
                    v.draw(c);
                }
                c.restore();
            } catch (Throwable e) { }
        }
        int r = Math.max(1, Math.round(level / 100f * 28f));
        int[] px = new int[bw * bh];
        b.getPixels(px, 0, bw, 0, 0, bw, bh);
        for (int i = 0; i < 3; i++) boxBlur(px, bw, bh, r);
        b.setPixels(px, 0, bw, 0, 0, bw, bh);
        return b;
    }

    // closed folder: a 3 x 3 preview (up to 9 apps)
    FrameLayout folderBox(Item f, int boxPx) {
        FrameLayout box = new FrameLayout(this);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(boxPx / 2.8f);
        g.setColor(f.color == 0x66FFFFFF ? 0x3A28282C : f.color);
        g.setStroke(dp(1), 0x22FFFFFF);
        box.setBackground(g);
        box.setTag("fb");
        GridLayout mini = new GridLayout(this);
        mini.setColumnCount(3);
        int gap = Math.max(1, boxPx / 40);
        int pad = Math.max(dp(3), boxPx / 12);
        int mp = Math.max(dp(3), (boxPx - 2 * pad - 6 * gap) / 3);
        int n = Math.min(9, f.apps.size());
        for (int i = 0; i < n; i++) {
            Drawable d = icon(f.apps.get(i));
            if (d == null) continue;
            ImageView iv = styleAppIcon(d, mp, mp / 4);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = mp;
            lp.height = mp;
            lp.setMargins(gap, gap, gap, gap);
            iv.setLayoutParams(lp);
            mini.addView(iv);
        }
        box.addView(mini, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        return box;
    }


    // ================= horizontal pages =================
    // option "Horizontal pages" on = one home page that scrolls down; off = several pages (swipe left / right, endless)
    int hpCur = 0;
    Pager hpPager;
    boolean hpNavigated = false, hpArmed = false;

    boolean pagedMode() { return !sp.getBoolean("hpScroll", true); }

    int pageCap() { return Math.max(1, sp.getInt("gridCols", 4) * Math.max(4, Math.min(20, sp.getInt("pgRows", 7)))); }

    boolean visibleItem(Item it) { return it.folder ? !it.docked : (it.pkg != null && installed(it.pkg)); }

    int countPage(int p) {
        int n = 0;
        for (Item it : home) if (it.page == p && visibleItem(it)) n++;
        return n;
    }

    int maxPage() {
        int mx = -1;
        for (Item it : home) if (visibleItem(it)) mx = Math.max(mx, it.page);
        return mx;
    }

    int pageCount() { return Math.max(1, maxPage() + 1); }

    void compressPages() {
        TreeSet<Integer> ps = new TreeSet<>();
        for (Item it : home) if (visibleItem(it) && it.page >= 0) ps.add(it.page);
        Map<Integer, Integer> m = new HashMap<>();
        int i = 0;
        for (int v : ps) m.put(v, i++);
        for (Item it : home) if (visibleItem(it) && it.page >= 0) it.page = m.get(it.page);
    }

    // gives every icon a page; a full page pushes its last icon to the next page; empty pages disappear
    void normalizePages() {
        if (!pagedMode()) return;
        int cap = pageCap();
        for (Item it : home) if (!visibleItem(it)) it.page = -1;
        compressPages();
        for (Item it : new ArrayList<>(home)) {
            if (!visibleItem(it) || it.page >= 0) continue;
            int mx = maxPage();
            int target = -1;
            for (int p = 0; p <= mx; p++) if (countPage(p) < cap) { target = p; break; }
            if (target < 0) target = mx + 1;
            it.page = target;
        }
        for (int p = 0; p < 500 && p <= maxPage(); p++) {
            while (countPage(p) > cap) {
                Item last = null;
                for (Item q : home) if (visibleItem(q) && q.page == p) last = q;
                home.remove(last);
                int at = home.size();
                for (int i = 0; i < home.size(); i++) {
                    if (visibleItem(home.get(i)) && home.get(i).page == p + 1) { at = i; break; }
                }
                home.add(at, last);
                last.page = p + 1;
            }
        }
        compressPages();
        hpCur = Math.max(0, Math.min(hpCur, pageCount() - 1));
    }

    void onPagesToggled() {
        if (pagedMode()) {
            hpCur = 0;
        } else {
            Collections.sort(home, (a, b) -> Integer.compare(a.page < 0 ? 100000 : a.page, b.page < 0 ? 100000 : b.page));
        }
        saveHome();
    }

    // puts the dragged items on page pi (no capacity check)
    void placeOnPage(String payload, int pi) {
        for (String k : keysFor(payload)) {
            final String p = k.startsWith("app:") ? k.substring(4) : k;
            boolean fromDock = selDock.contains(p);
            if (k.startsWith("folder:")) {
                Item d = findFolder(k.substring(7));
                if (d == null) continue;
                d.docked = false;
                removeFromDocks(k);
                home.remove(d);
                home.add(d);
                d.page = pi;
            } else {
                if (fromDock) removeFromDocks(p);
                if (dragFromFolder) for (Item it : home) if (it.folder) it.apps.remove(p);
                Item ex = null;
                boolean inFolder = false;
                for (Item it : home) {
                    if (it.folder) { if (it.apps.contains(p)) inFolder = true; }
                    else if (p.equals(it.pkg)) ex = it;
                }
                if (ex != null) {
                    home.remove(ex);
                    home.add(ex);
                    ex.page = pi;
                } else if (!inFolder) {
                    Item a = new Item();
                    a.pkg = p;
                    a.page = pi;
                    home.add(a);
                }
            }
        }
    }

    // drop on an empty part of page pi
    void dropOnPage(String payload, int pi) {
        int add = 0;
        for (String k : keysFor(payload)) {
            final String p = k.startsWith("app:") ? k.substring(4) : k;
            if (k.startsWith("folder:")) {
                Item d = findFolder(k.substring(7));
                if (d != null && !(d.page == pi && !d.docked)) add++;
            } else {
                boolean here = false;
                for (Item it : home) if (!it.folder && p.equals(it.pkg) && it.page == pi) here = true;
                if (!here) add++;
            }
        }
        if (countPage(pi) + add > pageCap()) {
            Toast.makeText(this, t("این صفحه پر است", "This page is full"), Toast.LENGTH_SHORT).show();
            dragKeys.clear();
            dragFromDock = false;
            dragFromFolder = false;
            return;
        }
        placeOnPage(payload, pi);
        hpCur = pi;
        endDropCleanup();
        saveHome();
        saveDock();
        postShow();
    }

    // dropped on the far left edge: a new page opens on the left of the last page and takes the icons
    void dropNewPage(String payload) {
        int np = pageCount();
        placeOnPage(payload, np);
        endDropCleanup();
        hpCur = np;
        saveHome();
        hpCur = pageCount() - 1;
        saveDock();
        postShow();
    }

    // strip at the left / right edge of the home page that shows while an icon is dragged:
    // holding the icon on it turns the page; dropping on the left one quickly opens a new page
    View edgeStrip(final boolean left) {
        final View v = new View(this);
        final Runnable[] nav = new Runnable[1];
        nav[0] = () -> {
            if (hpPager != null && hpPager.step(left ? 1 : -1)) { hpNavigated = true; vib(15); }
            else if (left && hpPager != null && hpPager.anim == null && hpPager.cur == hpPager.n - 1 && !hpArmed) {
                // holding on the left edge of the last page: a new page is ready, it opens when the icon is dropped
                hpArmed = true;
                v.setBackgroundColor(0x8834C759);
                vib(25);
            }
            v.postDelayed(nav[0], 800);
        };
        v.setOnDragListener((w, ev) -> {
            switch (ev.getAction()) {
                case DragEvent.ACTION_DRAG_STARTED:
                    hpNavigated = false;
                    hpArmed = false;
                    if (hpPager == null) return false;
                    w.setBackgroundColor(0x22FFFFFF);
                    return true;
                case DragEvent.ACTION_DRAG_ENTERED:
                    w.setBackgroundColor(0x55FFFFFF);
                    w.postDelayed(nav[0], 600);
                    return true;
                case DragEvent.ACTION_DRAG_EXITED:
                    w.removeCallbacks(nav[0]);
                    hpArmed = false;
                    w.setBackgroundColor(0x22FFFFFF);
                    return true;
                case DragEvent.ACTION_DRAG_ENDED:
                    w.removeCallbacks(nav[0]);
                    w.setBackgroundColor(0);
                    return true;
                case DragEvent.ACTION_DROP:
                    w.removeCallbacks(nav[0]);
                    w.setBackgroundColor(0);
                    boolean wasArmed = hpArmed;
                    hpArmed = false;
                    if (hpPager == null) return false;
                    String pl = ev.getClipData().getItemAt(0).getText().toString();
                    if (left && wasArmed) dropNewPage(pl);
                    else dropOnPage(pl, hpPager.cur);
                    return true;
            }
            return false;
        });
        return v;
    }

    // the pages sit side by side (the newest on the left); swiping turns them round without end
    class Pager extends FrameLayout {
        View[] pages;
        int n, cur;
        float pos;
        float x0, y0, pos0;
        boolean drag;
        VelocityTracker vt;
        Runnable onSwipeUp;
        boolean upFired;
        ValueAnimator anim;
        LinearLayout dots;

        Pager(Context c, int n) {
            super(c);
            this.n = n;
            pages = new View[n];
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            layoutPages();
        }

        void layoutPages() {
            int W = getWidth();
            int H = getHeight();
            if (W <= 0) return;
            int anim = sp.getInt("pgAnim", 0);
            for (int i = 0; i < n; i++) {
                if (pages[i] == null) continue;
                float rel = i - pos;
                rel -= n * Math.round(rel / n);
                applyPageTransform(pages[i], rel, anim, W, H);
                pages[i].setVisibility(Math.abs(rel) < 1.01f ? View.VISIBLE : View.INVISIBLE);
            }
        }

        void buildDots(int bottomPx) {
            if (n < 2) return;
            dots = new LinearLayout(MainActivity.this);
            dots.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            lp.bottomMargin = bottomPx;
            addView(dots, lp);
            updateDots();
        }

        void updateDots() {
            if (dots == null) return;
            dots.removeAllViews();
            for (int j = 0; j < n; j++) {
                boolean on = (n - 1 - j) == cur;
                View d = new View(MainActivity.this);
                d.setBackground(oval(on ? 0xCCFFFFFF : 0x55FFFFFF));
                LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(on ? 7 : 5), dp(on ? 7 : 5));
                dl.setMargins(dp(3), 0, dp(3), 0);
                dl.gravity = Gravity.CENTER_VERTICAL;
                dots.addView(d, dl);
            }
        }

        void animateTo(final float target) {
            if (anim != null) anim.cancel();
            anim = ValueAnimator.ofFloat(pos, target);
            anim.setDuration(220);
            anim.setInterpolator(new DecelerateInterpolator());
            anim.addUpdateListener(a -> { pos = (Float) a.getAnimatedValue(); layoutPages(); });
            anim.addListener(new AnimatorListenerAdapter() {
                boolean cancelled;
                @Override public void onAnimationCancel(Animator an) { cancelled = true; }
                @Override public void onAnimationEnd(Animator an) {
                    if (cancelled) return;
                    cur = ((Math.round(target) % n) + n) % n;
                    pos = cur;
                    hpCur = cur;
                    layoutPages();
                    updateDots();
                    anim = null;
                }
            });
            anim.start();
        }

        // turns one page (d = +1: the page on the left, -1: the one on the right); no wrap
        boolean step(int d) {
            if (anim != null) return false;
            int t = cur + d;
            if (t < 0 || t >= n) return false;
            animateTo(pos + d);
            return true;
        }

        boolean check(MotionEvent ev) {
            if (drag) return true;
            int slop = ViewConfiguration.get(MainActivity.this).getScaledTouchSlop();
            float dx = ev.getX() - x0, dy = ev.getY() - y0;
            if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * 1.2f) {
                drag = true;
                if (vt == null) vt = VelocityTracker.obtain(); else vt.clear();
                return true;
            }
            return false;
        }

        void down(MotionEvent ev) {
            x0 = ev.getX();
            y0 = ev.getY();
            if (anim != null) anim.cancel();
            anim = null;
            pos0 = pos;
            drag = false;
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            int a = ev.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) { down(ev); upFired = false; return false; }
            if (a == MotionEvent.ACTION_MOVE && !upFired && onSwipeUp != null && !drag) {
                float dx = ev.getX() - x0, dy = ev.getY() - y0;
                if (dy < -dp(40) && Math.abs(dy) > Math.abs(dx) * 1.3f) {
                    upFired = true;
                    onSwipeUp.run();
                    return false;
                }
            }
            if (n < 2) return false;
            if (a == MotionEvent.ACTION_MOVE) return check(ev);
            return false;
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            if (n < 2) return false;
            int a = ev.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) { down(ev); return true; }
            if (!drag && a == MotionEvent.ACTION_MOVE) check(ev);
            if (!drag) return true;
            if (vt != null) vt.addMovement(ev);
            int W = Math.max(1, getWidth());
            if (a == MotionEvent.ACTION_MOVE) {
                pos = pos0 + (ev.getX() - x0) / W;
                layoutPages();
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                float vx = 0;
                if (vt != null) { vt.computeCurrentVelocity(1000); vx = vt.getXVelocity(); vt.recycle(); vt = null; }
                float delta = pos - pos0;
                int base = Math.round(pos0);
                float target = base;
                if (a == MotionEvent.ACTION_UP) {
                    if (delta > 0.25f || (vx > 900 && delta > 0.03f)) target = base + 1;
                    else if (delta < -0.25f || (vx < -900 && delta < -0.03f)) target = base - 1;
                }
                drag = false;
                animateTo(target);
            }
            return true;
        }
    }

    // item cell of the home page (null when the app is not installed)
    View homeItemView(final Item item, int fg, int iconPx, int folderPx, boolean hide, boolean folderNames, int ts, int fts) {
        if (item.folder) return folderCell(item, fg, folderPx, folderNames, fts);
        final String pkg = item.pkg;
        if (!installed(pkg)) return null;
        View c = gearWrap(cell(pkg, fg, iconPx, ts, !hide), pkg, true);
        c.setOnClickListener(w -> { if (sel.isEmpty()) launchApp(pkg); else toggleSel(pkg); });
        c.setOnLongClickListener(w -> {
            selHold(pkg, false, w, "app:" + pkg);
            return true;
        });
        return c;
    }

    // one page of icons: the rows share the whole height of the frame, so more rows = icons closer together
    class GridPage extends FrameLayout {
        LinearLayout rowsBox;
        int rows, topPad, botPad;

        int content;
        final Map<View, Integer> origIcon = new HashMap<>();

        GridPage(Context c, LinearLayout rb, int rows, int topPad, int botPad, int content) {
            super(c);
            this.content = content;
            this.rowsBox = rb;
            this.rows = rows;
            this.topPad = topPad;
            this.botPad = botPad;
            addView(rb, new FrameLayout.LayoutParams(-1, -2));
        }

        // the rows are given the whole height before the page is measured
        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            int h = View.MeasureSpec.getSize(hSpec);
            // gap g = space between two rows = space above the first row = space below the last row
            int avail = h - topPad - botPad;
            int g = (avail - rows * content) / (rows + 1);
            int ch, off;
            if (g >= 0) { ch = content + g; off = g / 2; }
            else { ch = Math.max(dp(16), avail / Math.max(1, rows)); off = 0; }
            rowsBox.setPadding(0, topPad + off, 0, 0);
            // rows too tight for icon + name: the app icons shrink just enough, so the names are never cut off
            int over = g < 0 ? Math.max(0, content - ch) : 0;
            for (int r = 0; r < rowsBox.getChildCount(); r++) {
                ViewGroup rw = (ViewGroup) rowsBox.getChildAt(r);
                for (int k = 0; k < rw.getChildCount(); k++) {
                    View cv = rw.getChildAt(k);
                    if (!(cv instanceof FrameLayout) || ((FrameLayout) cv).getChildCount() == 0) continue;
                    View inner = ((FrameLayout) cv).getChildAt(0);
                    if (!(inner instanceof LinearLayout) || ((LinearLayout) inner).getChildCount() < 2) continue;
                    View ic = ((LinearLayout) inner).getChildAt(0);
                    if (!(ic instanceof ImageView)) continue;
                    ViewGroup.LayoutParams ilp = ic.getLayoutParams();
                    if (ilp == null) continue;
                    Integer o0 = origIcon.get(ic);
                    if (o0 == null) { o0 = ilp.height; origIcon.put(ic, o0); }
                    int ns = over > 0 ? Math.max(dp(18), o0 - over) : o0;
                    if (ilp.height != ns) { ilp.width = ns; ilp.height = ns; ic.setLayoutParams(ilp); }
                }
            }
            for (int r = 0; r < rowsBox.getChildCount(); r++) {
                ViewGroup row = (ViewGroup) rowsBox.getChildAt(r);
                for (int k = 0; k < row.getChildCount(); k++) {
                    row.getChildAt(k).getLayoutParams().height = ch;
                }
            }
            super.onMeasure(wSpec, hSpec);
        }
    }

    View pagedGrid(int fg, int cols, int cellH, int iconPx, int folderPx, boolean hide, boolean folderNames,
                   int ts, int fts, int rowGapPx) {
        normalizePages();
        final int pgRows = Math.max(4, Math.min(20, sp.getInt("pgRows", 7)));
        // icon and name sizes are only the ones chosen by hand in the Icon settings (nothing shrinks or grows by itself)
        int isz = sp.getInt("iconSz", 56);
        iconPx = dp(isz);
        folderPx = dp(sp.getBoolean("folderSync", false) ? isz : sp.getInt("folderSz", 56));
        ts = sp.getInt("nameSz", 12);
        fts = sp.contains("fpTsBot") ? sp.getInt("fpTsBot", 12) : ts;
        final int cH = Math.max(iconPx, folderPx) + dp(8)
                + (hide ? 0 : dp(4) + (int) (Math.max(ts, fts) * getResources().getDisplayMetrics().scaledDensity * 1.25f));
        int n = pageCount();
        hpCur = Math.max(0, Math.min(hpCur, n - 1));
        final Pager pg = new Pager(this, n);
        pg.cur = hpCur;
        pg.pos = hpCur;
        hpPager = pg;
        pg.onSwipeUp = () -> openLastClosed();
        boolean any = false;
        for (int p = 0; p < n; p++) {
            List<View> views = new ArrayList<>();
            for (Item item : home) {
                if (!visibleItem(item) || item.page != p) continue;
                View c = homeItemView(item, fg, iconPx, folderPx, hide, folderNames, ts, fts);
                if (c == null) continue;
                makeDropTarget(c, item);
                views.add(c);
            }
            if (!views.isEmpty()) any = true;
            LinearLayout rowsBox = buildRows(views, cols, dp(60), 0);
            rowsBox.setPadding(0, dp(8), 0, 0);
            rowsBox.setClipChildren(false);
            GridPage page = new GridPage(this, rowsBox, pgRows, dp(8),
                    sp.getBoolean("hfOn", false) ? dp(16) : dockReserve() + dp(20), cH);
            page.setClipChildren(false);
            if (sp.getBoolean("hfFx", false) && sp.getBoolean("hfOn", false)) {
                page.setBackground(new FrameDrawable(sp.getInt("hfBg", 0x6628282C), false, dp(26), dp(3), hfSrc,
                        sp.getInt("hfZoom", 100) / 100f, sp.getInt("hfPx", 50) / 100f,
                        sp.getInt("hfPy", 50) / 100f, sp.getInt("hfFade", 0)));
            }
            final int pi = p;
            page.setOnDragListener((w, ev) -> {
                if (ev.getAction() == DragEvent.ACTION_DROP) {
                    dropOnPage(ev.getClipData().getItemAt(0).getText().toString(), pi);
                    return true;
                }
                return true;
            });
            pg.addView(page, new FrameLayout.LayoutParams(-1, -1));
            pg.pages[p] = page;
        }
        if (!any && n == 1) {
            TextView e = new TextView(this);
            e.setText(t("برای افزودن برنامه یا پوشه، دکمه + را بزنید", "Tap + to add apps or folders"));
            e.setTextColor(fg);
            e.setAlpha(0.7f);
            e.setPadding(0, dp(24), 0, 0);
            pg.addView(e);
        }
        final boolean framed = sp.getBoolean("hfOn", false);
        pg.buildDots(framed ? dp(8) : dockReserve() + dp(2));
        if (framed) {
            pg.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(26));
                }
            });
            pg.setClipToOutline(true);
        }
        pg.layoutPages();
        refreshCells();
        return pg;
    }

    View gridScroll(final int fg) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        // the row count is fixed now; "Horizontal spacing" only moves rows 2, 3, ... (the first row never moves)
        int cols = sp.getInt("gridCols", 4), rows = 7;
        int rowGapPx = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_MM,
                Math.max(-6, Math.min(8, sp.getInt("gridGap", 0))), dm);
        boolean hide = sp.getBoolean("hideNames", false);
        boolean folderNames = !hide && sp.getBoolean("fpNameHome", true);
        int iconSz = sp.getInt("iconSz", 56);
        int folderSz = sp.getBoolean("folderSync", false) ? iconSz : sp.getInt("folderSz", 56);
        int cellW = (dm.widthPixels - dp(24)) / cols;
        int iconPx = Math.min(dp(iconSz), cellW - dp(4));
        int folderPx = Math.min(dp(folderSz), cellW - dp(4));
        int reserve = dp(150) + (sp.getBoolean("dockOn1", true) ? dp(sp.getInt("dockH1", 80) + 14) : 0);
        int cellH = Math.max((dm.heightPixels - reserve) / rows,
                Math.max(iconPx, folderPx) + (hide ? dp(10) : dp(30)));
        int ts = sp.contains("nameSz") ? sp.getInt("nameSz", 12) : (cols <= 5 ? 12 : (cols <= 8 ? 10 : 8));
        int fts = sp.contains("fpTsBot") ? sp.getInt("fpTsBot", 12) : ts;
        hpPager = null;
        if (pagedMode()) return pagedGrid(fg, cols, cellH, iconPx, folderPx, hide, folderNames, ts, fts, rowGapPx);

        // the home page scrolls behind the dock and fades out there
        // (with the home frame on, the page is cut to the rounded frame and fades at its top and bottom edge)
        final boolean framed = sp.getBoolean("hfOn", false);
        final boolean fadeTop = false;
        final int dres = framed ? 0 : dockReserve();
        final int fadeLen = framed ? dp(24) : dp(40);
        final int topFade = framed ? dp(10) : fadeLen;
        final float clipR = dp(26);
        ScrollView sv = new ScrollView(this) {
            final Paint maskP = new Paint();
            final Path clipP = new Path();
            final RectF clipRf = new RectF();
            { maskP.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN)); }

            @Override
            protected void dispatchDraw(Canvas c) {
                if (dres <= 0 && !framed) { super.dispatchDraw(c); return; }
                final int sx = getScrollX(), sy = getScrollY();
                int w = getWidth(), h = getHeight();
                c.save();
                c.translate(sx, sy);
                if (framed) {
                    // nothing may leave the rounded frame, not even at the corners
                    clipRf.set(0, 0, w, h);
                    clipP.reset();
                    clipP.addRoundRect(clipRf, clipR, clipR, Path.Direction.CW);
                    c.clipPath(clipP);
                }
                int layer = c.saveLayer(0, 0, w, h, null);
                c.translate(-sx, -sy);
                super.dispatchDraw(c);
                c.translate(sx, sy);
                if (framed) {
                    maskP.setShader(new LinearGradient(0, 0, 0, topFade,
                            0x00FFFFFF, 0xFFFFFFFF, Shader.TileMode.CLAMP));
                    c.drawRect(0, 0, w, topFade, maskP);
                    maskP.setShader(new LinearGradient(0, h - fadeLen, 0, h,
                            0xFFFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
                    c.drawRect(0, h - fadeLen, w, h, maskP);
                } else {
                    float end = dres * 0.45f;
                    if (fadeTop) {
                        maskP.setShader(new LinearGradient(0, end, 0, dres + fadeLen,
                                0x00FFFFFF, 0xFFFFFFFF, Shader.TileMode.CLAMP));
                        c.drawRect(0, 0, w, dres + fadeLen, maskP);
                    } else {
                        maskP.setShader(new LinearGradient(0, h - dres - fadeLen, 0, h - end,
                                0xFFFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
                        c.drawRect(0, h - dres - fadeLen, w, h, maskP);
                    }
                }
                c.restoreToCount(layer);
                c.restore();
            }
        };
        sv.setVerticalScrollBarEnabled(false);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        // the scroll position is kept: it only goes back to the top when the app is fully closed
        final int keepY = homeScrollY;
        final ScrollView svf = sv;
        sv.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                ViewTreeObserver vo = svf.getViewTreeObserver();
                if (vo.isAlive()) vo.removeOnPreDrawListener(this);
                svf.scrollTo(0, keepY);
                svf.setOnScrollChangeListener((vv, x, y, ox, oy) -> homeScrollY = y);
                return true;
            }
        });
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        List<View> views = new ArrayList<>();
        for (final Item item : home) {
            if (item.folder && item.docked) continue;
            View c = homeItemView(item, fg, iconPx, folderPx, hide, folderNames, ts, fts);
            if (c == null) continue;
            makeDropTarget(c, item);
            views.add(c);
        }
        LinearLayout rowsBox = buildRows(views, cols, cellH, rowGapPx);
        // the first row moves away from / toward the top frame line by the same amount as the rows below it
        // top and bottom distance = the same gap that lies between two rows of icons
        int contentH = Math.max(iconPx, folderPx) + dp(8)
                + (hide ? 0 : dp(4) + (int) (Math.max(ts, fts) * dm.scaledDensity * 1.25f));
        int edgeGap = Math.max(0, cellH - contentH) / 2;
        // however small the spacing gets, the first row stops at the top edge of the frame (never behind it)
        int firstTop = Math.max(0, edgeGap + rowGapPx);
        int firstNeg = 0;
        rowsBox.setPadding(0, firstTop + (fadeTop ? dres + fadeLen : 0), 0, Math.max(0, edgeGap + rowGapPx) + (fadeTop ? 0 : dres));
        rowsBox.setClipChildren(false);
        col.setClipChildren(false);
        if (firstNeg < 0 && rowsBox.getChildCount() > 0) {
            ((LinearLayout.LayoutParams) rowsBox.getChildAt(0).getLayoutParams()).topMargin = firstNeg;
        }
        col.addView(rowsBox, new LinearLayout.LayoutParams(-1, -2));
        if (views.isEmpty()) {
            TextView e = new TextView(this);
            e.setText(t("برای افزودن برنامه یا پوشه، دکمه + را بزنید", "Tap + to add apps or folders"));
            e.setTextColor(fg);
            e.setAlpha(0.7f);
            e.setPadding(0, dp(24), 0, 0);
            col.addView(e);
        }
        sv.addView(col);
        // dropping something dragged from the dock onto an empty spot moves it to home
        sv.setOnDragListener((w, ev) -> {
            if (ev.getAction() == DragEvent.ACTION_DROP) {
                if (!dragFromDock && !dragFromFolder) return false;
                dropOnGrid(ev.getClipData().getItemAt(0).getText().toString());
                return true;
            }
            return true;
        });
        refreshCells();
        return sv;
    }

    View folderCell(final Item f, int fg, int boxPx, boolean showName, int ts) {
        FrameLayout fc = new FrameLayout(this);
        LinearLayout it = new LinearLayout(this);
        it.setOrientation(LinearLayout.VERTICAL);
        it.setGravity(Gravity.CENTER);
        it.setPadding(dp(2), dp(4), dp(2), dp(4));
        final FrameLayout box = folderBox(f, boxPx);
        boolean fadeSmall = sp.getBoolean("fpHideSmall", true);
        boolean isOpenF = f.id.equals(openFolder) && fadeSmall;
        if (isOpenF) box.setVisibility(View.INVISIBLE);
        it.addView(box, new LinearLayout.LayoutParams(boxPx, boxPx));
        if (showName) {
            TextView tv = new TextView(this);
            tv.setTag("ic");
            tv.setText(folderTitle(f));
            tv.setTextColor(fg);
            tv.setTextSize(ts);
            tv.setMaxLines(1);
            tv.setEllipsize(TextUtils.TruncateAt.END);
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(0, dp(4), 0, 0);
            it.addView(tv);
            if (isOpenF) smallName = tv;
        }
        fc.addView(it, new FrameLayout.LayoutParams(-1, -1));

        // small gear shown on a selected folder: opens the open-folder settings
        ImageView gr = makeGear(26, "folder:" + f.id);
        gr.setOnClickListener(w -> {
            sel.clear();
            openFolderFrom(box, f.id, true);
        });
        FrameLayout.LayoutParams gl = new FrameLayout.LayoutParams(dp(26), dp(26), Gravity.TOP | Gravity.RIGHT);
        gl.setMargins(0, dp(2), dp(2), 0);
        fc.addView(gr, gl);
        addTo(cells, "folder:" + f.id, fc);
        styleSel(fc, sel.contains("folder:" + f.id));

        fc.setOnClickListener(w -> {
            if (sel.isEmpty()) {
                if (f.id.equals(openFolder)) closeFolder();
                else openFolderFrom(box, f.id, false);
            } else toggleSel("folder:" + f.id);
        });
        fc.setOnLongClickListener(w -> {
            selHold("folder:" + f.id, false, w, "folder:" + f.id);
            return true;
        });
        return fc;
    }

    // box = the closed folder view that the open folder grows out of
    // the dock folder that is the nearest one to the left / right of a docked folder (same dock page)
    String dockNeighbor(String id, boolean left) {
        int pages = sp.getInt("dockPages1", 1);
        for (int pi = 0; pi < pages; pi++) {
            List<String> l = dockPageList(pi);
            int i = l.indexOf("folder:" + id);
            if (i < 0) continue;
            if (left) {
                for (int k = i - 1; k >= 0; k--)
                    if (l.get(k).startsWith("folder:") && findFolder(l.get(k).substring(7)) != null) return l.get(k).substring(7);
            } else {
                for (int k = i + 1; k < l.size(); k++)
                    if (l.get(k).startsWith("folder:") && findFolder(l.get(k).substring(7)) != null) return l.get(k).substring(7);
            }
            // endless rotation: after the last folder comes the first one, before the first comes the last
            if (sp.getBoolean("fpLoop", false)) {
                if (left) {
                    for (int k = l.size() - 1; k > i; k--)
                        if (l.get(k).startsWith("folder:") && findFolder(l.get(k).substring(7)) != null) return l.get(k).substring(7);
                } else {
                    for (int k = 0; k < i; k++)
                        if (l.get(k).startsWith("folder:") && findFolder(l.get(k).substring(7)) != null) return l.get(k).substring(7);
                }
            }
            return null;
        }
        return null;
    }

    boolean inDock(View v) {
        for (ViewParent pp = v.getParent(); pp != null; pp = pp.getParent()) if (pp == dockBoxV) return true;
        return false;
    }

    // opens a folder of the dock from its closed box
    void openDockFolder(String id) {
        List<View> cl = cells.get("folder:" + id);
        if (cl == null) return;
        for (View c : cl) {
            View b = findTag(c, "fb");
            if (b != null && inDock(b)) { openFolderFrom(b, id, false); return; }
        }
    }

    // swiping up on a closed dock folder opens it at once, while the finger is still moving
    View.OnTouchListener swipeUpOpen(final View box, final String id) {
        final float[] st = new float[2];
        final boolean[] on = {false};
        return (w, ev) -> {
            int a = ev.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                st[0] = ev.getRawX();
                st[1] = ev.getRawY();
                on[0] = true;
            } else if (a == MotionEvent.ACTION_MOVE) {
                if (on[0] && sel.isEmpty()) {
                    float dx = ev.getRawX() - st[0], dy = ev.getRawY() - st[1];
                    if (dy < -dp(24) && Math.abs(dy) > Math.abs(dx) * 1.3f) {
                        on[0] = false;
                        ev.setAction(MotionEvent.ACTION_CANCEL);
                        if (!id.equals(openFolder)) w.post(() -> openFolderFrom(box, id, false));
                    }
                }
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                on[0] = false;
            }
            return false;
        };
    }

    void openFolderFrom(View box, String id, boolean settings) {
        int[] loc = new int[2];
        box.getLocationOnScreen(loc);
        boolean nowDock = false;
        for (ViewParent pp = box.getParent(); pp != null; pp = pp.getParent()) {
            if (pp == dockBoxV) { nowDock = true; break; }
        }
        snapPanel = null;
        snapTabs = null;
        // switching from one open folder to another (popup or immersive): keep a picture of the old one
        if (openFolder != null && !openFolder.equals(id) && curPanel != null && curPanel.getWidth() > 0) {
            try {
                if (!immersive) {
                    // popup folder: only the panel is pictured
                    snapPanel = Bitmap.createBitmap(curPanel.getWidth(), curPanel.getHeight(), Bitmap.Config.ARGB_8888);
                    curPanel.draw(new Canvas(snapPanel));
                } else if (fromDock && nowDock && ovLayer != null && curPanel.getHeight() > 0 && ovLayer.getWidth() > 0) {
                    snapPanel = Bitmap.createBitmap(curPanel.getWidth(), curPanel.getHeight(), Bitmap.Config.ARGB_8888);
                    curPanel.draw(new Canvas(snapPanel));
                    snapTabs = Bitmap.createBitmap(ovLayer.getWidth(), ovLayer.getHeight(), Bitmap.Config.ARGB_8888);
                    ovLayer.draw(new Canvas(snapTabs));
                    prevCx = openCx;
                }
            } catch (Throwable e) {
                snapPanel = null;
                snapTabs = null;
            }
        }
        fromDock = nowDock;
        openW = Math.max(dp(20), box.getWidth());
        openH = Math.max(dp(20), box.getHeight());
        openCx = loc[0] + box.getWidth() / 2;
        openCy = loc[1] + box.getHeight() / 2;
        openFolder = id;
        editName = false;
        folderSettings = settings;
        animSettings = false;
        animFolder = true;
        show();
    }

    View findTag(View v, String tag) {
        if (v == null) return null;
        if (tag.equals(v.getTag())) return v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View r = findTag(g.getChildAt(i), tag);
                if (r != null) return r;
            }
        }
        return null;
    }

    // after the closing animation: remove the popup, show the real closed folder again
    boolean quickClose(View pnl, String id) {
        try {
            if (pnl == null || id == null || page != HOME) return false;
            View wrap = (View) pnl.getParent();
            if (wrap == null || !(wrap.getParent() instanceof ViewGroup)) return false;
            ViewGroup mid = (ViewGroup) wrap.getParent();
            List<View> cl = cells.get("folder:" + id);
                                  boolean found = false;
            if (cl != null) {
                for (View c : cl) {
                    View b = findTag(c, "fb");
                    if (b != null) found = true;
                }
            }
            if (!found) return false;
            View[] pv = {gridView, frameV, headV, dockBoxV};
            for (View v : pv) {
                if (v == null) continue;
                v.setLayerType(View.LAYER_TYPE_NONE, null);
                if (Build.VERSION.SDK_INT >= 31) v.setRenderEffect(null);
                v.setAlpha(1f);
            }
            if (topLineV != null) topLineV.setAlpha(1f);
            mid.removeView(wrap);
            if (ovLayer != null) ovLayer.removeAllViews();
            if (bridgeV != null) bridgeV.set(false, 0, 0, 0, 0, 0, 0, 0);
            ovLayer = null;
            bridgeV = null;
            immersive = false;
            curPanel = null;
            folderApply = null;
            folderCtl.clear();
            for (View c : cl) {
                View b = findTag(c, "fb");
                if (b != null) b.setVisibility(View.VISIBLE);
            }
            if (smallName != null) smallName.setAlpha(1f);
            smallName = null;
            // the counter showed the apps of the open folder: back to the folders count
            if (countBarV != null && countBarV.getParent() instanceof ViewGroup) {
                ViewGroup par = (ViewGroup) countBarV.getParent();
                int idx = par.indexOfChild(countBarV);
                par.removeView(countBarV);
                countBarV = countBar(fgc);
                par.addView(countBarV, idx, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
            }
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    void closeFolder() {
        if (closing) return;
        if (openFolder != null) {
            lastClosedFolder = openFolder;
            sp.edit().putString("lastClosed", openFolder).apply();
        }
        if (curPanel != null && folderApply != null) {
            final Consumer<Float> ap = folderApply;
            final View pnl = curPanel;
            final String closingId = openFolder;
            curPanel = null;
            folderApply = null;
            closing = true;
            // cached pictures of the page parts keep every frame cheap
            try {
                if (gridView != null) gridView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
                if (frameV != null) frameV.setLayerType(View.LAYER_TYPE_HARDWARE, null);
                if (headV != null) headV.setLayerType(View.LAYER_TYPE_HARDWARE, null);
                if (dockBoxV != null) dockBoxV.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            } catch (Throwable e) { }
            ValueAnimator va = ValueAnimator.ofFloat(1f, 0f);
            va.setDuration(250);
            va.setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f));
            va.addUpdateListener(a -> ap.accept((Float) a.getAnimatedValue()));
            va.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator an) {
                    closing = false;
                    openFolder = null;
                    saveKeep();
                    editName = false;
                    folderSettings = false;
                    // no full rebuild (it made the last frames stutter): only tidy up in place
                    if (!quickClose(pnl, closingId)) show();
                }
            });
            va.start();
        } else {
            openFolder = null;
            editName = false;
            folderSettings = false;
            show();
        }
    }

    void makeDropTarget(View c, final Item target) {
        c.setOnDragListener((w, ev) -> {
            switch (ev.getAction()) {
                case DragEvent.ACTION_DRAG_STARTED:
                    return true;
                case DragEvent.ACTION_DRAG_ENTERED:
                    w.setAlpha(0.55f);
                    return true;
                case DragEvent.ACTION_DRAG_EXITED:
                case DragEvent.ACTION_DRAG_ENDED:
                    w.setAlpha(1f);
                    return true;
                case DragEvent.ACTION_DROP:
                    w.setAlpha(1f);
                    dropOnItem(ev.getClipData().getItemAt(0).getText().toString(), target);
                    return true;
            }
            return false;
        });
    }

    void dropOnItem(String payload, Item target) {
        List<String> ks = keysFor(payload);
        dropTi = home.indexOf(target);
        dropIns = 0;
        boolean any = false;
        for (String k : ks) {
            dragFromDock = selDock.contains(k.startsWith("app:") ? k.substring(4) : k);
            if (dropOnItem1(k, target)) any = true;
        }
        if (!any) {
            // dropped on itself: nothing changes, the selection stays
            dragKeys.clear();
            dragFromDock = false;
            return;
        }
        endDropCleanup();
        saveHome();
        saveDock();
        postShow();
    }

    // the dropped item takes the place of the item under it, which moves one step aside
    boolean dropOnItem1(String payload, Item target) {
        if (payload.startsWith("folder:")) {
            Item d = findFolder(payload.substring(7));
            if (d == null || d == target) return false;
            if (dragFromDock) {
                d.docked = false;
                removeFromDocks("folder:" + d.id);
            }
            home.remove(d);
            home.add(Math.max(0, Math.min(dropTi + dropIns++, home.size())), d);
            d.page = target.page;
        } else {
            final String p = payload.substring(4);
            boolean wasDock = dragFromDock;
            if (dragFromDock) removeFromDocks(p);
            if (!target.folder && p.equals(target.pkg)) return wasDock;
            for (Item it : home) if (it.folder) it.apps.remove(p);
            home.removeIf(q -> !q.folder && p.equals(q.pkg));
            if (target.folder) {
                if (!target.apps.contains(p)) target.apps.add(p);
            } else {
                Item a = new Item();
                a.pkg = p;
                a.page = target.page;
                home.add(Math.max(0, Math.min(dropTi + dropIns++, home.size())), a);
            }
        }
        return true;
    }

    // drop on an empty part of the home grid (from the dock or from an open folder)
    void dropOnGrid(String payload) {
        if (pagedMode()) { dropOnPage(payload, hpCur); return; }
        for (String k : keysFor(payload)) {
            final String p = k.startsWith("app:") ? k.substring(4) : k;
            boolean fromDock = selDock.contains(p);
            if (k.startsWith("folder:")) {
                Item d = findFolder(k.substring(7));
                if (d == null) continue;
                d.docked = false;
                removeFromDocks(k);
                home.remove(d);
                home.add(d);
            } else {
                if (fromDock) removeFromDocks(p);
                if (dragFromFolder) for (Item it : home) if (it.folder) it.apps.remove(p);
                boolean exists = false;
                for (Item it : home) {
                    if (it.folder ? it.apps.contains(p) : p.equals(it.pkg)) exists = true;
                }
                if (!exists) {
                    Item a = new Item();
                    a.pkg = p;
                    home.add(a);
                }
            }
        }
        endDropCleanup();
        saveHome();
        saveDock();
        postShow();
    }

    // drop on an app inside the open folder: the dropped app takes the place of that app, which moves one step aside
    void dropInFolder(Item f, String payload, String target) {
        List<String> ap = new ArrayList<>();
        for (String k : keysFor(payload)) {
            if (!k.startsWith("app:")) continue;
            String p = k.substring(4);
            if (!p.equals(target) && !ap.contains(p)) ap.add(p);
        }
        if (ap.isEmpty()) {
            // dropped on itself: nothing changes, the selection stays
            dragKeys.clear();
            dragFromDock = false;
            dragFromFolder = false;
            return;
        }
        int at = f.apps.indexOf(target);
        for (final String p : ap) {
            if (selDock.contains(p)) removeFromDocks(p);
            for (Item it : home) if (it.folder) it.apps.remove(p);
            home.removeIf(q -> !q.folder && p.equals(q.pkg));
        }
        if (at < 0) at = f.apps.size();
        at = Math.max(0, Math.min(at, f.apps.size()));
        f.apps.addAll(at, ap);
        endDropCleanup();
        saveHome();
        saveDock();
        postShow();
    }

    // drop on a folder that lives in the dock
    void dropIntoFolder(Item f, String payload) {
        boolean anyApp = false;
        for (String k : keysFor(payload)) if (k.startsWith("app:")) anyApp = true;
        if (!anyApp) {
            // only folders: nothing to put inside, keep the selection
            dragKeys.clear();
            return;
        }
        for (String k : keysFor(payload)) {
            if (k.startsWith("folder:")) continue;
            final String p = k.substring(4);
            if (selDock.contains(p)) removeFromDocks(p);
            for (Item it : home) if (it.folder && it != f) it.apps.remove(p);
            home.removeIf(q -> !q.folder && p.equals(q.pkg));
            if (!f.apps.contains(p)) f.apps.add(p);
        }
        endDropCleanup();
        saveHome();
        saveDock();
        postShow();
    }

    View trashView() { return actionView(0); }

    View newFolderView() { return actionView(1); }

    View moveHomeView() { return actionView(2); }

    // kind 0 = trash, 1 = new folder (+), 2 = phone (move to the home page)
    View actionView(final int kind) {
        TextView tr = new TextView(this);
        tr.setTag("x");
        tr.setText(kind == 0 ? "🗑" : (kind == 1 ? "+" : "📱"));
        tr.setTextSize(kind == 1 ? 22 : 18);
        tr.setTextColor(0xFFFFFFFF);
        tr.setGravity(Gravity.CENTER);
        tr.setBackground(oval(0x44888888));
        tr.setAlpha(0f);
        tr.setOnDragListener((w, ev) -> {
            switch (ev.getAction()) {
                case DragEvent.ACTION_DRAG_STARTED:
                    if (!actionApplies(kind)) return false;
                    w.setAlpha(0.6f);
                    return true;
                case DragEvent.ACTION_DRAG_ENTERED:
                    w.setScaleX(1.3f);
                    w.setScaleY(1.3f);
                    w.setAlpha(1f);
                    return true;
                case DragEvent.ACTION_DRAG_EXITED:
                    w.setScaleX(1f);
                    w.setScaleY(1f);
                    w.setAlpha(0.6f);
                    return true;
                case DragEvent.ACTION_DRAG_ENDED:
                    w.setAlpha(0f);
                    w.setScaleX(1f);
                    w.setScaleY(1f);
                    if (kind == 0) {
                        dragActive = false;
                        updateSel();
                    }
                    return true;
                case DragEvent.ACTION_DROP:
                    w.setScaleX(1f);
                    w.setScaleY(1f);
                    String p = ev.getClipData().getItemAt(0).getText().toString();
                    if (kind == 0) trashDrop(p);
                    else if (kind == 1) newFolderDrop(p);
                    else moveHomeDrop(p);
                    return true;
            }
            return false;
        });
        return tr;
    }

    boolean actionApplies(int kind) {
        if (kind == 0) return true;
        boolean anyApp = false, elsewhere = dragFromDock || dragFromFolder;
        for (String k : dragKeys) {
            if (k.startsWith("app:")) anyApp = true;
            if (selDock.contains(k.startsWith("app:") ? k.substring(4) : k)) elsewhere = true;
        }
        return kind == 1 ? anyApp : elsewhere;
    }

    // dropped on +: ask for a name, then the apps go into a new folder on the home page
    void newFolderDrop(String payload) {
        List<String> ap = new ArrayList<>();
        for (String k : keysFor(payload)) if (k.startsWith("app:")) ap.add(k.substring(4));
        if (ap.isEmpty()) return;
        addFolderDialog(ap);
    }

    // dropped on the phone: ask first, cancel drops the whole selection
    void moveHomeDrop(final String payload) {
        final List<String> ks = keysFor(payload);
        final boolean fromF = dragFromFolder;
        new AlertDialog.Builder(this).setTitle(t("انتقال به صفحه اصلی", "Move to home"))
                .setMessage(t("می‌خواهید به صفحه اصلی انتقال دهید؟", "Do you want to move them to the home page?"))
                .setPositiveButton(t("تأیید", "OK"), (d, w) -> {
                    dragKeys.clear();
                    dragKeys.addAll(ks);
                    dragFromFolder = fromF;
                    dropOnGrid(payload);
                })
                .setNegativeButton(t("لغو", "Cancel"), (d, w) -> { endDropCleanup(); show(); })
                .setOnCancelListener(d -> { endDropCleanup(); show(); })
                .show();
    }

    void trashDrop(String payload) {
        List<String> ks = keysFor(payload);
        trashKeys(ks, dragFromDock);
    }

    // removes apps (only from the dock when they came from it) and asks what to do with folders
    void trashKeys(List<String> ks, boolean dockOnly) {
        final List<Item> fl = new ArrayList<>();
        for (String k : ks) {
            if (k.startsWith("folder:")) {
                Item f = findFolder(k.substring(7));
                if (f != null) fl.add(f);
                continue;
            }
            final String p = k.startsWith("app:") ? k.substring(4) : k;
            if (dockOnly && selDock.contains(p)) {
                removeFromDocks(p);
                continue;
            }
            for (Item it : home) if (it.folder) it.apps.remove(p);
            home.removeIf(q -> !q.folder && p.equals(q.pkg));
            removeFromDocks(p);
        }
        endDropCleanup();
        saveHome();
        saveDock();
        if (fl.isEmpty()) { postShow(); return; }
        boolean any = false;
        for (Item f : fl) if (!f.apps.isEmpty()) any = true;
        if (!any) {
            for (Item f : fl) deleteFolder(f, false);
            return;
        }
        new AlertDialog.Builder(this).setTitle(t("حذف پوشه", "Delete folder"))
                .setMessage(t("با برنامه‌های داخل پوشه چه کنم؟", "What should happen to the apps inside?"))
                .setPositiveButton(t("حذف با برنامه‌ها", "Delete with apps"), (d, w) -> { for (Item f : fl) deleteFolder(f, false); })
                .setNeutralButton(t("انتقال برنامه‌ها به صفحه اصلی", "Move apps to home"), (d, w) -> { for (Item f : fl) deleteFolder(f, true); })
                .setNegativeButton(t("لغو", "Cancel"), (d, w) -> postShow()).show();
    }

    void deleteFolder(Item f, boolean keepApps) {
        int idx = home.indexOf(f);
        home.remove(f);
        if (keepApps) {
            List<Item> back = new ArrayList<>();
            for (String p : f.apps) {
                Item a = new Item();
                a.pkg = p;
                back.add(a);
            }
            home.addAll(Math.max(0, Math.min(idx, home.size())), back);
        }
        for (List<String> l : dock1) l.remove("folder:" + f.id);
        if (f.id.equals(openFolder)) openFolder = null;
        dragFromDock = false;
        sel.clear();
        saveHome();
        saveDock();
        postShow();
    }

    // swipe up on the home page (paged mode): opens the folder that was closed last
    void openLastClosed() {
        if (page != HOME || openFolder != null || lastClosedFolder == null) return;
        if (findFolder(lastClosedFolder) == null) return;
        View box = null;
        List<View> cl = cells.get("folder:" + lastClosedFolder);
        if (cl != null) {
            for (View c : cl) {
                View b = findTag(c, "fb");
                if (b != null) { box = b; break; }
            }
        }
        if (box != null && box.getWidth() > 0 && onScreen(box)) {
            openFolderFrom(box, lastClosedFolder, false);
            return;
        }
        // the folder is on another page: it grows out of the middle of the screen
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int s = dp(sp.getInt("folderSz", 56));
        snapPanel = null;
        snapTabs = null;
        fromDock = false;
        openW = s;
        openH = s;
        openCx = dm.widthPixels / 2;
        openCy = dm.heightPixels / 2;
        openFolder = lastClosedFolder;
        editName = false;
        folderSettings = false;
        animSettings = false;
        animFolder = true;
        show();
    }

    // ---- plus menu: grows out of the + button and shrinks back into it ----
    // closing no longer rebuilds the whole page at the end (that caused the lag in the last frames)
    void closePlus() {
        if (plusMenuV == null) {
            plusOpen = false;
            show();
            return;
        }
        final View m = plusMenuV;
        plusMenuV = null;
        plusOpen = false;
        m.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        m.animate().alpha(0f).scaleX(0.1f).scaleY(0.1f).setDuration(200)
                .setInterpolator(new AccelerateInterpolator())
                .withEndAction(() -> {
                    try {
                        View wrap = (View) m.getParent();
                        if (wrap != null && wrap.getParent() instanceof ViewGroup) {
                            ((ViewGroup) wrap.getParent()).removeView(wrap);
                        } else {
                            show();
                        }
                    } catch (Throwable e) {
                        show();
                    }
                }).start();
    }

    View plusMenu(final int fg) {
        final FrameLayout wrap = new FrameLayout(this);
        View scrim = new View(this);
        scrim.setOnClickListener(w -> closePlus());
        wrap.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        final LinearLayout m = new LinearLayout(this);
        m.setOrientation(LinearLayout.VERTICAL);
        // two separate main boxes, one under the other (they follow every main-box setting: color, glass ...)
        m.addView(row(0xFF3D7BF7, "+", t("افزودن برنامه", "Add app"), null, 0, fg, w -> {
            plusOpen = false;
            openPicker(null);
        }));
        m.addView(row(0xFFFB8C00, "📁", t("افزودن پوشه", "Add folder"), null, 0, fg, w -> {
            plusOpen = false;
            addFolderDialog(new ArrayList<String>());
        }));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.RIGHT | Gravity.TOP);
        lp.setMargins(0, dp(4), dp(4), 0);
        wrap.addView(m, lp);

        plusMenuV = m;
        m.setAlpha(0f);
        m.setScaleX(0.1f);
        m.setScaleY(0.1f);
        m.post(() -> {
            m.setPivotX(m.getWidth() - dp(24));
            m.setPivotY(-dp(24));
            m.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(240)
                    .setInterpolator(new DecelerateInterpolator()).start();
        });
        return wrap;
    }

    View menuRow(int color, String sym, String label, int fg, View.OnClickListener c) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(4), dp(6), dp(4), dp(6));
        TextView d = new TextView(this);
        d.setText(sym);
        d.setTextSize(18);
        d.setTextColor(0xFFFFFFFF);
        d.setGravity(Gravity.CENTER);
        d.setBackground(oval(color));
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(38), dp(38));
        dl.setMarginEnd(dp(12));
        r.addView(d, dl);
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(15);
        tv.setTextColor(fg);
        r.addView(tv);
        r.setOnClickListener(c);
        return r;
    }
    void addFolderDialog(final List<String> initial) {
        final EditText et = new EditText(this);
        et.setHint(t("نام پوشه", "Folder name"));
        et.setSingleLine(true);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(et, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this).setTitle(t("پوشه جدید", "New folder")).setView(box)
                .setPositiveButton(t("تأیید", "OK"), (d, w) -> {
                    Item f = new Item();
                    f.folder = true;
                    f.id = "f" + System.currentTimeMillis();
                    f.name = et.getText().toString().trim();
                    f.color = 0x3A28282C;
                    f.apps.addAll(initial);
                    int idx = home.size();
                    if (!initial.isEmpty()) {
                        for (int i = 0; i < home.size(); i++) {
                            if (!home.get(i).folder && initial.contains(home.get(i).pkg)) { idx = i; f.page = home.get(i).page; break; }
                        }
                        home.removeIf(q -> !q.folder && initial.contains(q.pkg));
                    }
                    home.add(Math.min(idx, home.size()), f);
                    Item of = openFolder != null ? findFolder(openFolder) : null;
                    boolean fromOpen = false;
                    for (String p : initial) {
                        if (of != null && of != f && of.apps.contains(p)) fromOpen = true;
                        for (Item it : home) if (it.folder && it != f) it.apps.remove(p);
                        if (selDock.contains(p)) removeFromDocks(p);
                    }
                    if (fromOpen) openFolder = null;
                    sel.clear();
                    selDock.clear();
                    saveHome();
                    show();
                }).setNegativeButton(t("لغو", "Cancel"), null).show();
    }

    // settings list shared by the open-folder popup, the folder gear and the main settings
    View folderSettingsBody(final Item f, final int fg, final Runnable rs, boolean cmp) {
        compact = cmp;
        LinearLayout sb = new LinearLayout(this);
        sb.setOrientation(LinearLayout.VERTICAL);
        final boolean fullOn = sp.getBoolean("fpFull", false);
        sb.addView(row(0xFF7E57C2, "▣", t("فراگیر", "Immersive"),
                t("باز شدن پوشه در تمام صفحه اصلی", "Folder opens over the whole home page"),
                fullOn ? 3 : 2, fg, w -> {
            sp.edit().putBoolean("fpFull", !fullOn).apply();
            show();
        }));
        // boxes of the immersive folder header: when ticked the boxes disappear, the icons stay
        final boolean noBox = sp.getBoolean("fpNoBox", false);
        sb.addView(row(0xFF26A69A, "◻", t("کادرها", "Boxes"),
                t("حذف کادرهای سربرگ پوشه فراگیر (آیکون‌ها می‌مانند)",
                        "Remove the header boxes of the immersive folder (icons stay)"),
                noBox ? 3 : 2, fg, w -> {
            sp.edit().putBoolean("fpNoBox", !noBox).apply();
            show();
        }));
        final boolean keepF = sp.getBoolean("fpKeep", false);
        sb.addView(row(0xFF43A047, "📌", t("حفظ پوشه", "Keep folder open"),
                t("پوشه باز تا زمانی که خودت می‌بندی باز می‌ماند، حتی بعد از بستن لانچر",
                        "The open folder stays open until you close it, even after the launcher was closed"),
                keepF ? 3 : 2, fg, w -> {
            sp.edit().putBoolean("fpKeep", !keepF).commit();
            show();
        }));
        // endless rotation: swipe left / right on an open dock folder moves to the next folder, round and round
        final boolean loopOn = sp.getBoolean("fpLoop", false);
        sb.addView(row(0xFF00ACC1, "∞", t("چرخش نامحدود", "Endless rotation"),
                t("با کشیدن انگشت به چپ و راست روی پوشه باز، به پوشه بعدی می‌روید؛ بعد از آخرین پوشه دوباره اولی باز می‌شود",
                        "Swipe left / right on an open folder to move to the next one; after the last folder the first opens again"),
                loopOn ? 3 : 2, fg, w -> {
            sp.edit().putBoolean("fpLoop", !loopOn).apply();
            show();
        }));
        // animation: on = the grow animation plays every time; off = it plays once, then crossfade
        final boolean animOn = sp.getBoolean("fpAnim", true);
        sb.addView(row(0xFFEC407A, "✨", t("انیمیشن", "Animation"),
                t("خاموش: انیمیشن فقط برای باز شدن اولین پوشه، جایگزینی پوشه‌ها با Crossfade",
                        "Off: grow animation only for the first folder, then crossfade"),
                animOn ? 3 : 2, fg, w -> {
            sp.edit().putBoolean("fpAnim", !animOn).apply();
            show();
        }));
        if (f != null) {
            sb.addView(row(f.color | 0xFF000000, "🎨", t("رنگ", "Color"), null, 0, fg, w ->
                    colorDialog(t("رنگ پوشه", "Folder color"), f.color, c -> {
                        f.color = c;
                        saveHome();
                        show();
                    })));
        }
        sb.addView(accordionGroupFaded(0xFFEC407A, "🎞", t("افکت ها", "Effects"), fg, 0.6f,
                () -> animRows(fg, "fdAnim", true)));
        final boolean closeOnLaunch = sp.getBoolean("fpCloseOnLaunch", false);
        sb.addView(row(0xFFE53935, "🔒", t("بستن پوشه", "Close Folder"),
                t("بستن پوشه بعد از انتخاب برنامه", "Close folder after launching app"),
                closeOnLaunch ? 3 : 2, fg, w -> {
            sp.edit().putBoolean("fpCloseOnLaunch", !closeOnLaunch).apply();
            show();
        }));
        final boolean hideSmall = sp.getBoolean("fpHideSmall", true);
        sb.addView(row(0xFF00897B, "👻", t("محو پوشه کوچک", "Hide small folder"),
                t("پوشه بسته و اسمش هنگام باز بودن (پاپ آپ و فراگیر) محو می‌شوند",
                        "Closed folder and its name fade while open (popup and immersive)"),
                hideSmall ? 3 : 2, fg, w -> {
            sp.edit().putBoolean("fpHideSmall", !hideSmall).apply();
            show();
        }));
        sb.addView(accordionRow(0xFF2196F3, "📊", t("تعداد ستون‌ها", "Columns count"), "fpCols", 3, 8, 3, "", fg, rs));
        sb.addView(accordionRow(0xFF4CAF50, "📐", t("فاصله افقی ردیف‌ها", "Row spacing"), "fpRowGap", -3, 5, 2, " mm", fg, rs));
        sb.addView(accordionRow(0xFFFF9800, "🔍", t("سایز پوشه باز", "Open folder size"), "fpSize", 60, 150, 100, "%", fg, rs));
        sb.addView(accordionRow(0xFF8BC34A, "📁", t("سایز پوشه بسته", "Closed folder size"), "folderSz", 32, 96, 56, " dp", fg, rs));
        sb.addView(accordionRow(0xFF9C27B0, "💧", t("شفافیت پوشه", "Folder opacity"), "fpAlpha", 10, 100, 80, "%", fg, rs));
        sb.addView(accordionRow(0xFF607D8B, "🌑", t("مات", "Fade"), "fpDim", 0, 98, 25, "%", fg, rs));
        sb.addView(accordionRow(0xFF00CCD6, "📱", t("سایز آیکون", "Icon size"), "fpIcon", 24, 72, 40, " dp", fg, rs));
        sb.addView(accordionGroup(0xFF8D6E63, "🔠", t("سایز متن", "Text size"), fg,
                slider(t("بالای پوشه", "Folder top"), "fpTsTop", 4, 44, 14, " sp", fg, rs),
                slider(t("پایین پوشه", "Folder bottom"), "fpTsBot", 6, 24, 12, " sp", fg, rs)));
        final boolean nmOn = sp.getInt("fpNames", 1) == 1;
        sb.addView(row(0xFF42A5F5, "🏷", t("آیکون", "Icon"), null, nmOn ? 3 : 2, fg, w -> {
            sp.edit().putInt("fpNames", nmOn ? 0 : 1).apply();
            show();
        }));
        final boolean fnHome = sp.getBoolean("fpNameHome", true);
        sb.addView(row(0xFF26A69A, "📁", t("پوشه", "Folder"), null, fnHome ? 3 : 2, fg, w -> {
            sp.edit().putBoolean("fpNameHome", !fnHome).apply();
            show();
        }));
        final boolean fnDock = sp.getBoolean("fpNameDock", true);
        sb.addView(row(0xFF5C6BC0, "⚓", t("پوشه داک", "Dock folder"), null, fnDock ? 3 : 2, fg, w -> {
            sp.edit().putBoolean("fpNameDock", !fnDock).apply();
            show();
        }));
        compact = false;
        return sb;
    }

    View folderPage(int fg) {
        LinearLayout r = vbox();
        r.addView(topBar(t("تنظیمات پوشه", "Folder Settings"), fg));
        r.addView(folderSettingsBody(null, fg, null, false));
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setBackgroundColor(pbg());
        sv.addView(r);
        return sv;
    }

    // ---- header controls of the open folder ----
    TextView plusCtl(final Item f, int fg, int sizePx) {
        TextView pl = new TextView(this);
        pl.setText("+");
        pl.setTextSize(22f * sizePx / dp(26));
        pl.setGravity(Gravity.CENTER);
        pl.setTextColor(fg);
        pl.setOnClickListener(w -> openPicker(f.id));
        return pl;
    }

    ImageView gearCtl(int fg, int sizePx) {
        ImageView gr = new ImageView(this);
        gr.setImageResource(R.drawable.ic_gear);
        gr.setColorFilter(fg);
        int pd = Math.round(dp(3) * (float) sizePx / dp(26));
        gr.setPadding(pd, pd, pd, pd);
        gr.setOnClickListener(w -> {
            folderSettings = !folderSettings;
            animSettings = folderSettings;
            show();
        });
        return gr;
    }

    int panelColor(Item f) {
        return (f.color & 0x00FFFFFF) | ((sp.getInt("fpAlpha", 80) * 255 / 100) << 24);
    }

    // same color, opacity, corner radius and thin edge line as the open folder panel;
    // with the "Boxes" option on there is no box at all (icons keep their place)
    Drawable tabBg(Item f) {
        if (sp.getBoolean("fpNoBox", false)) return null;
        return new TabDrawable(panelColor(f), 0x33FFFFFF, dp(26), dp(9), Math.max(1, dp(1)));
    }

    // very thin line under the header of the popup folder, in a lighter shade of the folder color
    int headLineColor(Item f) {
        int c = f.color == 0x66FFFFFF ? 0x3A28282C : f.color;
        int r = Color.red(c), g = Color.green(c), b = Color.blue(c);
        r += (255 - r) * 45 / 100;
        g += (255 - g) * 45 / 100;
        b += (255 - b) * 45 / 100;
        return Color.argb(0xD0, r, g, b);
    }

    float dockRadiusPx(int hDp) { return dp(hDp) / 2f * (100 - sp.getInt("dockRound", 20)) / 100f; }

    // folder name: plain text inside the folder, a tab attached to the folder when immersive
    View nameControl(final Item f, final int fg, final boolean full, int btnSz) {
        final int fl = dp(9);
        if (!editName) {
            TextView nm = new TextView(this);
            nm.setTag("icf");
            nm.setText(folderTitle(f));
            nm.setTextColor(fg);
            nm.setTypeface(null, Typeface.BOLD);
            nm.setSingleLine(true);
            nm.setEllipsize(TextUtils.TruncateAt.END);
            nm.setOnClickListener(w -> { editName = true; show(); });
            if (full) {
                nm.setTextSize(Math.max(4, Math.min(44, sp.getInt("fpTsTop", 14))));
                nm.setGravity(Gravity.CENTER);
                nm.setPadding(dp(12) + fl, 0, dp(12) + fl, 0);
                nm.setBackground(tabBg(f));
            } else {
                nm.setTextSize(Math.max(4, Math.min(44, sp.getInt("fpTsTop", 14))));
                DisplayMetrics ndm = getResources().getDisplayMetrics();
                int npw = (int) Math.min(ndm.widthPixels - dp(24),
                        Math.max(dp(220), ndm.widthPixels / 2) * sp.getInt("fpSize", 100) / 100f);
                nm.setMaxWidth(Math.max(dp(60), npw - dp(24) - 2 * dp(66)));
            }
            return nm;
        }
        LinearLayout er = new LinearLayout(this);
        er.setGravity(Gravity.CENTER_VERTICAL);
        if (full) {
            er.setPadding(dp(6) + fl, 0, dp(6) + fl, 0);
            er.setBackground(tabBg(f));
        }
        final EditText en = new EditText(this);
        en.setText(f.name);
        en.setTextSize(13);
        en.setTextColor(fg);
        en.setSingleLine(true);
        en.setSelectAllOnFocus(true);
        en.setPadding(dp(4), 0, dp(4), 0);
        er.addView(en, new LinearLayout.LayoutParams(full ? 0 : dp(72), -2, full ? 1 : 0));
        TextView ok = new TextView(this);
        ok.setText("✓");
        ok.setTextSize(14);
        ok.setTextColor(0xFFFFFFFF);
        ok.setGravity(Gravity.CENTER);
        ok.setBackground(oval(0xFF34C759));
        ok.setOnClickListener(w -> {
            f.name = en.getText().toString().trim();
            saveHome();
            editName = false;
            show();
        });
        LinearLayout.LayoutParams ol = new LinearLayout.LayoutParams(dp(26), dp(26));
        ol.setMarginStart(dp(4));
        er.addView(ok, ol);
        en.postDelayed(() -> {
            en.requestFocus();
            android.view.inputmethod.InputMethodManager im =
                    (android.view.inputmethod.InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            im.showSoftInput(en, 0);
        }, 150);
        return er;
    }

    Ctl mk(View v, int w, int h, float ex, float ey) {
        Ctl c = new Ctl();
        c.v = v;
        c.w = w;
        c.h = h;
        c.ex = ex;
        c.ey = ey;
        c.sx = openCx;
        c.sy = openCy;
        c.s0 = 0.2f;
        v.setPivotX(w / 2f);
        v.setPivotY(h / 2f);
        return c;
    }

    View folderBoxOf(String id) {
    List<View> cl = cells.get("folder:" + id);
    if (cl == null) return null;
    for (View c : cl) {
        View b = findTag(c, "fb");
        if (b != null) return b;
    }
    return null;
}

View folderPopup(final int fg) {
        final Item f = findFolder(openFolder);
        final FrameLayout wrap = new FrameLayout(this);
        if (f == null) { openFolder = null; return wrap; }
        final boolean full = ovLayer != null && sp.getBoolean("fpFull", false);
        final FrameLayout ov = ovLayer;
        immersive = full;
        folderCtl.clear();
        final boolean anim = animFolder;
        animFolder = false;
        final boolean animS = animSettings;
        animSettings = false;
        final Bitmap sPanel = snapPanel, sTabs = snapTabs;
        snapPanel = null;
        snapTabs = null;
        // replacing one open folder by another (popup or immersive): the old one stays as a picture and fades out
        final boolean switching = anim && sPanel != null && (!full || (sTabs != null && ov != null));
        // animation option off: replacing one folder by another is a smooth crossfade
        final boolean xfade = switching && (!sp.getBoolean("fpAnim", true) || sp.getInt("fdAnim", -1) >= 0);
        // the neck between the open folder and the closed one in the dock only shows when the closed one stays visible
        final boolean bridgeOn = full && fromDock && !sp.getBoolean("fpHideSmall", true);
        final float prevCxF = prevCx;
        final boolean[] opening = {anim};
        DisplayMetrics dm = getResources().getDisplayMetrics();
        View scrim = new View(this);
        scrim.setOnClickListener(w -> closeFolder());
        wrap.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        int fpSize = sp.getInt("fpSize", 100);
        int pw = (int) Math.min(dm.widthPixels - dp(24),
                Math.max(dp(220), dm.widthPixels / 2) * fpSize / 100f);
        final int ph = (int) Math.min(dm.heightPixels * 0.7f, dm.heightPixels * 0.36f * fpSize / 100f);
        final SwipePanel panel = new SwipePanel(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        // the header line of the popup folder reaches the side walls through the padding
        panel.setClipToPadding(false);
        final GradientDrawable pg = new GradientDrawable();
        pg.setCornerRadius(dp(26));
        pg.setColor(panelColor(f));
        pg.setStroke(dp(1), 0x33FFFFFF);
        panel.setBackground(pg);
        panel.setPadding(dp(12), dp(8), dp(12), dp(12));
        panel.setClickable(true);
        panel.swipeOn = !folderSettings;
        curPanel = panel;

        final int btnSz = dp(sp.getInt("home_btn_sz", 40));
        final int hSz = full ? btnSz : dp(26);
        final TextView plV = plusCtl(f, fg, hSz);
        final ImageView grV = gearCtl(fg, hSz);
        final View nameV = nameControl(f, fg, full, btnSz);
        final int fl = dp(9);
        final int gw = 2 * btnSz + dp(8) + 2 * fl;
        final LinearLayout grp = new LinearLayout(this);
        final ImageView gTabs;
        if (switching && full) {
            gTabs = new ImageView(this);
            gTabs.setImageBitmap(sTabs);
            gTabs.setScaleType(ImageView.ScaleType.FIT_XY);
            ov.addView(gTabs, new FrameLayout.LayoutParams(-1, -1));
        } else {
            gTabs = null;
        }
        if (!full) {
            FrameLayout hd = new FrameLayout(this);
            hd.addView(nameV, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
            FrameLayout.LayoutParams pll = new FrameLayout.LayoutParams(dp(26), dp(26), Gravity.RIGHT | Gravity.CENTER_VERTICAL);
            pll.setMargins(0, 0, dp(32), 0);
            hd.addView(plV, pll);
            hd.addView(grV, new FrameLayout.LayoutParams(dp(26), dp(26), Gravity.RIGHT | Gravity.CENTER_VERTICAL));
            panel.addView(hd, new LinearLayout.LayoutParams(-1,
                Math.max(dp(28), (int) (sp.getInt("fpTsTop", 14) * getResources().getDisplayMetrics().scaledDensity * 1.45f))));
            // thin line under the name and the header icons: wall to wall, as high as possible
            View hl = new View(this);
            hl.setBackgroundColor(headLineColor(f));
            LinearLayout.LayoutParams hll = new LinearLayout.LayoutParams(-1, Math.max(1, dp(1) / 2));
            hll.setMargins(-dp(12), 0, -dp(12), 0);
            panel.addView(hl, hll);
        } else {
            // immersive: the tabs live in the layer above the page, attached to the folder top
            nameV.setAlpha(0f);
            grp.setAlpha(0f);
            grp.setGravity(Gravity.CENTER_VERTICAL);
            // fixed order in every language: plus on the left, gear on its right
            grp.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
            grp.setPadding(fl + dp(4), 0, fl + dp(4), 0);
            grp.setBackground(tabBg(f));
            grp.addView(plV, new LinearLayout.LayoutParams(btnSz, btnSz));
            grp.addView(grV, new LinearLayout.LayoutParams(btnSz, btnSz));
            ov.addView(nameV, new FrameLayout.LayoutParams(-2, btnSz, Gravity.TOP | Gravity.LEFT));
            ov.addView(grp, new FrameLayout.LayoutParams(gw, btnSz, Gravity.TOP | Gravity.LEFT));
            folderCtl.add(nameV);
            folderCtl.add(grp);
        }

        if (folderSettings) {
            ScrollView ss = new ScrollView(this);
            ss.setVerticalScrollBarEnabled(false);
            ss.setOverScrollMode(View.OVER_SCROLL_NEVER);
            ss.addView(folderSettingsBody(f, fg, () -> show(), true));
            panel.addView(ss, new LinearLayout.LayoutParams(-1, 0, 1));
            if (animS) {
                ss.setTranslationY(-dp(40));
                ss.setAlpha(0f);
                ss.animate().translationY(0).alpha(1f).setDuration(260)
                        .setInterpolator(new DecelerateInterpolator()).start();
            }
        } else {
            ScrollView bs = new ScrollView(this);
            bs.setVerticalScrollBarEnabled(false);
            bs.setOverScrollMode(View.OVER_SCROLL_NEVER);
            int fpCols = sp.getInt("fpCols", 3);
            int fpRowGap = Math.max(-3, Math.min(5, sp.getInt("fpRowGap", 2)));
            int rowGapPx = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_MM, fpRowGap, dm);
            boolean nmShow = sp.getInt("fpNames", 1) == 1;
            int ip = dp(sp.getInt("fpIcon", 40));
            List<View> pv = new ArrayList<>();
            for (final String pkg : f.apps) {
                if (!installed(pkg)) continue;
                View c = gearWrap(cell(pkg, fg, ip, 10, nmShow), pkg, false);
                c.setOnClickListener(w -> {
                    if (!sel.isEmpty()) { toggleSel(pkg); return; }
                    launchApp(pkg);
                    if (sp.getBoolean("fpCloseOnLaunch", false)) closeFolder();
                });
                c.setOnLongClickListener(w -> {
                    selHold(pkg, false, w, "app:" + pkg);
                    dragFromFolder = true;
                    return true;
                });
                c.setOnDragListener((w, ev) -> {
                    switch (ev.getAction()) {
                        case DragEvent.ACTION_DRAG_STARTED:
                            return true;
                        case DragEvent.ACTION_DRAG_ENTERED:
                            w.setAlpha(0.55f);
                            return true;
                        case DragEvent.ACTION_DRAG_EXITED:
                            w.setAlpha(1f);
                            return true;
                        case DragEvent.ACTION_DRAG_ENDED:
                            w.setAlpha(1f);
                            if (!ev.getResult() && ("app:" + pkg).equals(ev.getLocalState())) {
                                removeFromFolderDialog(f, pkg);
                            }
                            return true;
                        case DragEvent.ACTION_DROP:
                            w.setAlpha(1f);
                            dropInFolder(f, ev.getClipData().getItemAt(0).getText().toString(), pkg);
                            return true;
                    }
                    return true;
                });
                pv.add(c);
            }
            // the row gap is applied only between row 2, 3, ...; the first row keeps a small fixed distance from the line
            LinearLayout rowsBox = buildRows(pv, fpCols, 0, rowGapPx);
            rowsBox.setPadding(0, full ? 0 : dp(6), 0, dp(8));
            bs.addView(rowsBox, new FrameLayout.LayoutParams(-1, -2));
            panel.sc = bs;
            panel.addView(bs, new LinearLayout.LayoutParams(-1, 0, 1));
            if (f.apps.isEmpty()) {
                TextView e = new TextView(this);
                e.setText(t("با دکمه + برنامه اضافه کنید", "Use + to add apps"));
                e.setTextColor(fg);
                e.setTextSize(12);
                e.setAlpha(0.7f);
                e.setGravity(Gravity.CENTER);
                panel.addView(e, full ? 0 : 2);
            }
        }
        final int dres2 = dockReserve();
        final boolean dTop2 = false;
        wrap.setPadding(0, dTop2 ? dres2 : 0, 0, dTop2 ? 0 : dres2);
        // switching folders: a picture of the previous folder stays underneath and fades out
        final ImageView gPanel;
        if (switching) {
            gPanel = new ImageView(this);
            gPanel.setImageBitmap(sPanel);
            gPanel.setScaleType(ImageView.ScaleType.FIT_XY);
            wrap.addView(gPanel, full ? new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER)
                    : new FrameLayout.LayoutParams(pw, ph, Gravity.CENTER));
        } else {
            gPanel = null;
        }
        wrap.addView(panel, full ? new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER)
                : new FrameLayout.LayoutParams(pw, ph, Gravity.CENTER));
        final FrameLayout ghost = folderBox(f, openW);
        ghost.setAlpha(0f);
        final boolean fadeSmall = sp.getBoolean("fpHideSmall", true) && !bridgeOn;
        final int blurLvl = 0;
        final int bc1 = panelColor(f);
        final int bc2 = f.color == 0x66FFFFFF ? 0x3A28282C : f.color;
        final float[] rad = {dp(26)};
        final List<Ctl> ctls = new ArrayList<>();
        final int[] ovl = new int[2];
        if (anim) panel.setAlpha(0f);
        panel.post(() -> {
            if (!anim) {
                // no opening animation (e.g. restored after a fresh start): use the closed box as origin
                View kb = folderBoxOf(f.id);
                if (kb != null && kb.getWidth() > 0) {
                    int[] bl = new int[2];
                    kb.getLocationOnScreen(bl);
                    openCx = bl[0] + kb.getWidth() / 2;
                    openCy = bl[1] + kb.getHeight() / 2;
                    openW = Math.max(dp(20), kb.getWidth());
                    openH = Math.max(dp(20), kb.getHeight());
                }
            }
            int[] wl = new int[2];
            wrap.getLocationOnScreen(wl);
            final float pcx = wl[0] + panel.getLeft() + panel.getWidth() / 2f;
            final float pcy = wl[1] + panel.getTop() + panel.getHeight() / 2f;
            panel.setPivotX(panel.getWidth() / 2f);
            panel.setPivotY(panel.getHeight() / 2f);
            final float tx0 = openCx - pcx;
            final float ty0 = openCy - pcy;
            final float sx0 = Math.max(0.05f, openW / (float) Math.max(1, panel.getWidth()));
            final float sy0 = Math.max(0.05f, openH / (float) Math.max(1, panel.getHeight()));
            final float r0 = (openW / 2.8f) / Math.max(0.05f, (sx0 + sy0) / 2f);
            final float rEnd = dp(26);
            final boolean fullFade = sp.getInt("fpAlpha", 80) >= 100;
            final float dimEnd = fullFade ? 0f : 1f - sp.getInt("fpDim", 25) / 100f;
            final float chromeEnd = (fullFade && !full) ? 0f : 1f;
            final float blurMax = blurLvl / 100f * dp(40);
            final float pTop = wl[1] + panel.getTop();
            final boolean att = !dTop2 && headV != null && headV.getHeight() > 0;
            if (full && ov != null && homeMenuV != null && homePlusV != null && countBarV != null) {
                ov.getLocationOnScreen(ovl);
                int[] ml = new int[2], pl2 = new int[2], cl = new int[2];
                homeMenuV.getLocationOnScreen(ml);
                homePlusV.getLocationOnScreen(pl2);
                countBarV.getLocationOnScreen(cl);
                int tabH = att ? headV.getHeight() : btnSz;
                float cyEnd = ml[1] + homeMenuV.getHeight() / 2f;
                float viewL = ml[0] + homeMenuV.getWidth() + dp(10) - fl;
                int maxNameW = Math.max(dp(60), (int) (cl[0] - dp(6) - viewL));
                int nw;
                if (nameV instanceof TextView) {
                    TextView ntv = (TextView) nameV;
                    nw = Math.min(maxNameW, (int) ntv.getPaint().measureText(ntv.getText().toString()) + dp(24) + 2 * fl);
                    nw = Math.max(nw, btnSz);
                } else {
                    nw = dp(150);
                }
                float gCx = pl2[0] - dp(10) + fl - gw / 2f;
                ViewGroup.LayoutParams nlp = nameV.getLayoutParams();
                nlp.width = nw;
                nlp.height = tabH;
                nameV.setLayoutParams(nlp);
                ViewGroup.LayoutParams glp = grp.getLayoutParams();
                glp.height = tabH;
                grp.setLayoutParams(glp);
                Ctl cn = mk(nameV, nw, tabH, viewL + nw / 2f, cyEnd);
                Ctl cg = mk(grp, gw, tabH, gCx, cyEnd);
                ctls.add(cn);
                ctls.add(cg);
            }
            if (Build.VERSION.SDK_INT < 31 && blurLvl > 0 && panel.getWidth() > 0 && panel.getHeight() > 0) {
                try {
                    Bitmap bb = blurBackdrop(wl[0] + panel.getLeft(), wl[1] + panel.getTop(),
                            panel.getWidth(), panel.getHeight(), blurLvl, dimEnd);
                    BitmapDrawable bd = new BitmapDrawable(getResources(), bb);
                    panel.setBackground(new LayerDrawable(new Drawable[]{bd, pg}));
                    panel.setPadding(dp(12), dp(8), dp(12), dp(12));
                    panel.setOutlineProvider(new ViewOutlineProvider() {
                        @Override
                        public void getOutline(View v, Outline o) {
                            o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), rad[0]);
                        }
                    });
                    panel.setClipToOutline(true);
                } catch (Throwable e) { }
            }
            if (fadeSmall) {
                FrameLayout.LayoutParams gl = new FrameLayout.LayoutParams(openW, openH, Gravity.TOP | Gravity.LEFT);
                gl.leftMargin = Math.round(openCx - openW / 2f - wl[0]);
                gl.topMargin = Math.round(openCy - openH / 2f - wl[1] - wrap.getPaddingTop());
                wrap.addView(ghost, gl);
            }
            final Consumer<Float> ap = p -> {
                final boolean sw = switching && opening[0];
                // crossfade: no moving or scaling, the new folder only fades in over the old one
                final boolean xf = sw && xfade;
                float sxp = xf ? 1f : sx0 + (1f - sx0) * p;
                float syp = xf ? 1f : sy0 + (1f - sy0) * p;
                float txv = xf ? 0f : tx0 * (1f - p);
                float tyv = xf ? 0f : ty0 * (1f - p);
                panel.setScaleX(sxp);
                panel.setScaleY(syp);
                if (xf) panel.setAlpha(p);
                else if (closing) panel.setAlpha(Math.min(1f, p / 0.12f));
                panel.setTranslationX(txv);
                panel.setTranslationY(tyv);
                rad[0] = xf ? rEnd : r0 + (rEnd - r0) * p;
                pg.setCornerRadius(rad[0]);
                if (blurLvl > 0) panel.invalidateOutline();
                float ca = xf ? 1f : Math.max(0f, Math.min(1f, (p - 0.3f) / 0.7f));
                for (int i = 0; i < panel.getChildCount(); i++) panel.getChildAt(i).setAlpha(ca);
                for (int ci = 0; ci < ctls.size(); ci++) {
                    Ctl c = ctls.get(ci);
                    float cx = xf ? c.ex : c.sx + (c.ex - c.sx) * p;
                    float cy = xf ? c.ey : c.sy + (c.ey - c.sy) * p;
                    float s = xf ? 1f : c.s0 + (1f - c.s0) * p;
                    // attached to the folder: the tabs ride on the moving top edge of the panel
                    c.v.setScaleX(s);
                    c.v.setScaleY(s);
                    c.v.setTranslationX(cx - c.w / 2f - ovl[0]);
                    c.v.setTranslationY(cy - c.h / 2f - ovl[1]);
                    c.v.setAlpha(xf ? p : Math.min(1f, p / 0.4f));
                }
                if (gPanel != null && opening[0]) {
                    gPanel.setAlpha(xf ? 1f - p : 1f - Math.max(0f, Math.min(1f, (p - 0.2f) / 0.8f)));
                }
                if (gTabs != null && opening[0]) gTabs.setAlpha(xf ? 1f - p : 1f - Math.min(1f, p / 0.6f));
                float ga = Math.max(0f, Math.min(1f, 1f - p / 0.3f));
                ghost.setAlpha(ga);
                if (smallName != null) smallName.setAlpha(ga);
                if (gridView != null) gridView.setAlpha(sw ? dimEnd : 1f + (dimEnd - 1f) * p);
                if (frameV != null) frameV.setAlpha(sw ? dimEnd : 1f + (dimEnd - 1f) * p);
                if (headV != null) headV.setAlpha(1f + (chromeEnd - 1f) * p);
                if (topLineV != null) topLineV.setAlpha(1f + (chromeEnd - 1f) * p);
                if (dockBoxV != null) dockBoxV.setAlpha(1f + (chromeEnd - 1f) * p);
                if (blurLvl > 0) applyBlur(blurMax * p);
                if (bridgeOn && bridgeV != null) {
                    float pcxp = pcx + txv;
                    float pcyp = pcy + tyv;
                    float pwp = panel.getWidth() * (sw ? 1f : sxp);
                    float bot = sw ? pcy + panel.getHeight() / 2f : pcyp + panel.getHeight() * syp / 2f;
                    float bcx = sw ? prevCxF + (openCx - prevCxF) * p : openCx;
                    float cxp = sw ? pcx : pcxp;
                    float avail = Math.min(bcx - (cxp - pwp / 2f + rad[0]), (cxp + pwp / 2f - rad[0]) - bcx);
                    float top0 = openCy - openH / 2f;
                    float half = openW * 0.28f;
                    if (avail < half || top0 - bot < 1.5f) {
                        bridgeV.set(false, 0, 0, 0, 0, 0, 0, 0);
                    } else {
                        float wf = Math.min(half + Math.min(top0 - bot, half * 1.2f + dp(6)), avail);
                        bridgeV.set(true, bcx, bot, top0, half, Math.max(half, wf), bc1, bc2);
                    }
                }
                if (xfade && opening[0] && sp.getInt("fdAnim", -1) >= 0) {
                    int trA = sp.getInt("fdAnim", -1);
                    applyPageTransform(panel, folderDir * (1f - p), trA, dm.widthPixels, dm.heightPixels);
                    if (gPanel != null) applyPageTransform(gPanel, -folderDir * p, trA, dm.widthPixels, dm.heightPixels);
                }
            };
            folderApply = ap;
            if (anim) {
                ap.accept(0f);
                if (!xfade) panel.setAlpha(1f);
                if (switching) {
                    if (gridView != null) gridView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
                    if (frameV != null) frameV.setLayerType(View.LAYER_TYPE_HARDWARE, null);
                }
                ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
                va.setDuration(xfade ? 260 : 290);
                va.setInterpolator(xfade ? new AccelerateDecelerateInterpolator()
                        : new PathInterpolator(0.2f, 0f, 0f, 1f));
                va.addUpdateListener(an -> ap.accept((Float) an.getAnimatedValue()));
                va.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator an2) {
                        opening[0] = false;
                        if (gridView != null) gridView.setLayerType(View.LAYER_TYPE_NONE, null);
                        if (frameV != null) frameV.setLayerType(View.LAYER_TYPE_NONE, null);
                        if (gPanel != null) wrap.removeView(gPanel);
                        if (gTabs != null && ov != null) ov.removeView(gTabs);
                    }
                });
                va.start();
            } else {
                ap.accept(1f);
            }
        });
        return wrap;
    }

    void removeFromFolderDialog(final Item f, final String pkg) {
        new AlertDialog.Builder(this).setTitle(t("حذف از پوشه؟", "Remove from folder?"))
                .setMessage(label(pkg))
                .setPositiveButton(t("حذف", "Remove"), (d, w) -> {
                    f.apps.remove(pkg);
                    Item a = new Item();
                    a.pkg = pkg;
                    home.add(a);
                    sel.clear();
                    saveHome();
                    show();
                }).setNegativeButton(t("لغو", "Cancel"), null).show();
    }

    // ================= dock =================
    View buildDock() {
        if (!sp.getBoolean("dockOn1", true)) {
            dockHolder1 = null;
            return null;
        }
        FrameLayout holder = new FrameLayout(this);
        dockHolder1 = holder;
        fillDock();
        return holder;
    }

    void fillDock() {
        final FrameLayout holder = dockHolder1;
        if (holder == null) return;
        holder.removeAllViews();
        final int count = sp.getInt("dockCount1", 4);
        final int pages = sp.getInt("dockPages1", 1);
        int h = sp.getInt("dockH1", 90);
        int iconSz = sp.getInt("dockI1", 52);
        int wPct = sp.getInt("dockW1", 96);
        int sw = getResources().getDisplayMetrics().widthPixels - dp(24);
        final int pageW = (int) (sw * wPct / 100f);

        boolean showAppNames = !sp.getBoolean("hideDockNames", false);
        boolean showFolderNames = sp.getBoolean("fpNameDock", true);
        boolean fadeSmall = sp.getBoolean("fpHideSmall", true);
        int dockTs = sp.getInt("dockTs1", sp.getInt("dockTs", 10));
        int textPx = (int) (dockTs * getResources().getDisplayMetrics().density * 1.4f) + dp(4);
        int avail = dp(h) - dp(8);
        int maxByW = pageW / count - dp(6);
        int szApp = Math.max(dp(16), Math.min(Math.min(dp(iconSz), maxByW),
                showAppNames ? avail - textPx : avail));
        // folders on the dock follow the folder size (or the dock icon size when synced)
        int folderBase = sp.getBoolean("folderSync", false) ? iconSz : sp.getInt("folderSz", 56);
        int szFolder = Math.max(dp(16), Math.min(Math.min(dp(folderBase), maxByW),
                showFolderNames ? avail - textPx : avail));

        FrameLayout box = new FrameLayout(this);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dockRadiusPx(h));
        g.setColor(sp.getInt("dockBg", 0x40FFFFFF));
        g.setStroke(dp(1), 0x22FFFFFF);
        box.setBackground(g);
        final HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        LinearLayout rowL = new LinearLayout(this);
        rowL.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        int total = 0;
        for (int pi = 0; pi < pages; pi++) {
            LinearLayout pg = new LinearLayout(this);
            pg.setGravity(Gravity.CENTER_VERTICAL);
            final List<String> l = dockPageList(pi);
            for (int k = 0; k < count; k++) {
                FrameLayout sw2 = new FrameLayout(this);
                LinearLayout slot = new LinearLayout(this);
                slot.setOrientation(LinearLayout.VERTICAL);
                slot.setGravity(Gravity.CENTER);
                sw2.addView(slot, new FrameLayout.LayoutParams(-1, -1));
                if (k < l.size()) {
                    final String e = l.get(k);
                    final boolean isF = e.startsWith("folder:");
                    View iv = null;
                    String nm = null;
                    boolean isOpenF = false;
                    if (isF) {
                        final Item f = findFolder(e.substring(7));
                        if (f != null) {
                            final FrameLayout fbx = folderBox(f, szFolder);
                            fbx.setLayoutParams(new LinearLayout.LayoutParams(szFolder, szFolder));
                            iv = fbx;
                            if (showFolderNames) nm = folderTitle(f);
                            // the closed folder and its name fade while the folder is open (popup and immersive)
                            isOpenF = f.id.equals(openFolder) && fadeSmall;
                            if (isOpenF) fbx.setVisibility(View.INVISIBLE);
                            slot.setOnClickListener(w -> {
                                if (sel.isEmpty()) {
                                    if (f.id.equals(openFolder)) closeFolder();
                                    else openFolderFrom(fbx, f.id, false);
                                } else toggleSelD(e);
                            });
                            slot.setOnTouchListener(swipeUpOpen(fbx, f.id));
                            slot.setOnDragListener((w, ev) -> {
                                switch (ev.getAction()) {
                                    case DragEvent.ACTION_DRAG_STARTED:
                                        return true;
                                    case DragEvent.ACTION_DRAG_ENTERED:
                                        w.setAlpha(0.6f);
                                        return true;
                                    case DragEvent.ACTION_DRAG_EXITED:
                                    case DragEvent.ACTION_DRAG_ENDED:
                                        w.setAlpha(1f);
                                        return true;
                                    case DragEvent.ACTION_DROP:
                                        w.setAlpha(1f);
                                        dropIntoFolder(f, ev.getClipData().getItemAt(0).getText().toString());
                                        return true;
                                }
                                return false;
                            });
                        }
                    } else if (installed(e)) {
                        iv = styleAppIcon(icon(e), szApp);
                        if (showAppNames) nm = label(e);
                        slot.setOnClickListener(w -> {
                            if (sel.isEmpty()) launchApp(e);
                            else toggleSelD(e);
                        });
                    }
                    if (iv != null) {
                        total++;
                        final View ivf = iv;
                        slot.addView(iv);
                        if (nm != null) {
                            TextView tv = new TextView(this);
                            tv.setTag("ic");
                            tv.setText(nm);
                            tv.setTextColor(fgc);
                            tv.setTextSize(dockTs);
                            tv.setMaxLines(1);
                            tv.setEllipsize(TextUtils.TruncateAt.END);
                            tv.setGravity(Gravity.CENTER);
                            tv.setPadding(dp(2), dp(2), dp(2), 0);
                            slot.addView(tv, new LinearLayout.LayoutParams(-1, -2));
                            if (isOpenF) smallName = tv;
                        }
                        // the selection key of a dock item is its entry (package or "folder:id")
                        final String payload = isF ? e : "app:" + e;
                        slot.setOnLongClickListener(w -> {
                            selHold(e, true, w, payload);
                            return true;
                        });
                        addTo(cells, e, sw2);
                        ImageView dg = makeGear(20, e);
                        dg.setOnClickListener(w -> {
                            if (isF) {
                                sel.clear();
                                openFolderFrom(ivf, e.substring(7), true);
                            } else {
                                openIconSettings();
                            }
                        });
                        sw2.addView(dg, new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.TOP | Gravity.RIGHT));
                        styleSel(sw2, sel.contains(e));
                    }
                }
                pg.addView(sw2, new LinearLayout.LayoutParams(0, -1, 1));
            }
            rowL.addView(pg, new LinearLayout.LayoutParams(pageW, -1));
        }
        hs.addView(rowL);
        hs.setOnTouchListener((w, ev) -> {
            int a = ev.getAction();
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                w.postDelayed(() -> {
                    int p = Math.round(hs.getScrollX() / (float) pageW);
                    dockPage1 = Math.max(0, Math.min(pages - 1, p));
                    hs.smoothScrollTo(dockPage1 * pageW, 0);
                }, 100);
            }
            return false;
        });
        hs.post(() -> hs.scrollTo(Math.min(dockPage1, pages - 1) * pageW, 0));
        box.addView(hs, new FrameLayout.LayoutParams(-1, -1));
        if (total == 0) {
            TextView hint = new TextView(this);
            hint.setText(t("برنامه یا پوشه را نگه دارید و اینجا بکشید", "Hold an app or folder and drag it here"));
            hint.setTextSize(12);
            hint.setTextColor(fgc);
            hint.setAlpha(0.6f);
            box.addView(hint, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        }
        box.setOnDragListener((w, ev) -> {
            switch (ev.getAction()) {
                case DragEvent.ACTION_DRAG_STARTED:
                    return true;
                case DragEvent.ACTION_DRAG_ENTERED:
                    w.setAlpha(0.7f);
                    if (dragFromFolder && openFolder != null && !closing) closeFolder();
                    return true;
                case DragEvent.ACTION_DRAG_EXITED:
                case DragEvent.ACTION_DRAG_ENDED:
                    w.setAlpha(1f);
                    return true;
                                              case DragEvent.ACTION_DROP:
                    w.setAlpha(1f);
                    addToDock(ev.getClipData().getItemAt(0).getText().toString());
                    return true;
            }
            return false;
        });
        holder.addView(box, new FrameLayout.LayoutParams(pageW, dp(h), Gravity.CENTER_HORIZONTAL));
    }

    void addToDock(String payload) {
        int count = sp.getInt("dockCount1", 4), pages = sp.getInt("dockPages1", 1);
        boolean already = false, full = false, changed = false;
        for (String k : keysFor(payload)) {
            String entry = k.startsWith("app:") ? k.substring(4) : k;
            boolean dup = false;
            for (List<String> l : dock1) if (l.contains(entry)) dup = true;
            if (dup) {
                if (!selDock.contains(entry) && !k.startsWith("folder:")) {
                    already = true;
                    for (Item it : home) if (it.folder) it.apps.remove(entry);
                    home.removeIf(q -> !q.folder && entry.equals(q.pkg));
                    changed = true;
                } else if (!selDock.contains(entry)) {
                    already = true;
                } // items from the dock itself stay silent
                continue;
            }
            List<String> l = dockPageList(Math.min(dockPage1, pages - 1));
            if (l.size() >= count) { full = true; continue; }
            if (k.startsWith("folder:")) {
                Item f = findFolder(k.substring(7));
                if (f != null) f.docked = true; // keep the folder data, just hide it from the grid
            }
            if (!k.startsWith("folder:") && !selDock.contains(entry)) {
                // moved from the home page or from a folder: it leaves there
                for (Item it : home) if (it.folder) it.apps.remove(entry);
                home.removeIf(q -> !q.folder && entry.equals(q.pkg));
            }
            l.add(entry);
            changed = true;
        }
        if (already) Toast.makeText(this, t("قبلاً در داک هست", "Already in the dock"), Toast.LENGTH_SHORT).show();
        if (full) Toast.makeText(this, t("این صفحه داک پر است", "This dock page is full"), Toast.LENGTH_SHORT).show();
        if (!changed) {
            dragKeys.clear();
            dragFromDock = false;
            return;
        }
        endDropCleanup();
        saveHome();
        saveDock();
        postShow();
    }

    // ================= launching =================
    // screen rectangle of the home page frame (or of the icon grid when the frame is off)
    Rect frameRect() {
        View v = (frameV != null && frameV.getWidth() > 0) ? frameV : gridView;
        if (v == null || v.getWidth() == 0 || v.getHeight() == 0) return null;
        int[] l = new int[2];
        v.getLocationOnScreen(l);
        return new Rect(l[0], l[1], l[0] + v.getWidth(), l[1] + v.getHeight());
    }

    // window size of "Run apps": the home frame, or (second tick) top frame + home frame + dock together
    Rect runRect() {
        if (sp.getBoolean("hfRunFull", false)) {
            // whole display, from just under the Android status bar down to just above the navigation bar
            try {
                DisplayMetrics dm = new DisplayMetrics();
                getWindowManager().getDefaultDisplay().getRealMetrics(dm);
                int top = 0, bottom = 0;
                View root = getWindow().getDecorView();
                WindowInsets wi = root.getRootWindowInsets();
                if (wi != null) {
                    top = wi.getSystemWindowInsetTop();
                    bottom = wi.getSystemWindowInsetBottom();
                }
                android.content.res.Resources rs = getResources();
                if (top <= 0) {
                    int id = rs.getIdentifier("status_bar_height", "dimen", "android");
                    if (id > 0) top = rs.getDimensionPixelSize(id);
                }
                if (bottom <= 0) {
                    int id = rs.getIdentifier("navigation_bar_height", "dimen", "android");
                    if (id > 0) bottom = rs.getDimensionPixelSize(id);
                }
                return new Rect(0, top, dm.widthPixels, dm.heightPixels - bottom);
            } catch (Throwable e) { }
        }
        Rect f = frameRect();
        if (!sp.getBoolean("hfRunAll", false)) return f;
        Rect u = f == null ? null : new Rect(f);
        View[] vs = {topBoxV != null ? topBoxV : headV, dockBoxV};
        for (View v : vs) {
            if (v == null || v.getWidth() == 0 || v.getHeight() == 0 || v.getVisibility() != View.VISIBLE) continue;
            int[] l = new int[2];
            v.getLocationOnScreen(l);
            Rect r = new Rect(l[0], l[1], l[0] + v.getWidth(), l[1] + v.getHeight());
            if (u == null) u = r; else u.union(r);
        }
        return u;
    }

    // "Run apps" option: the app opens as a window exactly over the frame, growing from its center.
    // Android only keeps the window inside the frame when the phone has free-form windows on;
    // otherwise the app opens normally and the launcher stays behind it.
    void launchInFrame(Intent li) {
        try {
            Rect r = runRect();
            ActivityOptions o;
            if (r != null && container != null && container.getWidth() > 0) {
                int[] cl = new int[2];
                container.getLocationOnScreen(cl);
                o = ActivityOptions.makeScaleUpAnimation(container,
                        r.centerX() - cl[0], r.centerY() - cl[1], 1, 1);
                o.setLaunchBounds(r);
            } else {
                o = ActivityOptions.makeBasic();
            }
            startActivity(li, o.toBundle());
        } catch (Throwable e) {
            try { startActivity(li); } catch (Throwable e2) { }
        }
    }

    // the size of "Run apps" is kept, so the bubble can open an app as a popup of that same size
    void saveRunRect() {
        try {
            if (page != HOME) return;
            Rect r = runRect();
            if (r == null || r.width() < dp(50) || r.height() < dp(50)) return;
            String v = r.left + "," + r.top + "," + r.right + "," + r.bottom;
            if (!v.equals(sp.getString("runRect", ""))) sp.edit().putString("runRect", v).apply();
        } catch (Throwable e) { }
    }

    void launchApp(String pkg) {
        Intent li = getPackageManager().getLaunchIntentForPackage(pkg);
        if (li == null) return;
        if (sp.getBoolean("hfRun", false) || sp.getBoolean("hfRunAll", false) || sp.getBoolean("hfRunFull", false)) {
            // the launcher always stays open in this mode
            launchInFrame(li);
            return;
        }
        startActivity(li);
        int mode = sp.getInt("mode", 0);
        if (mode == 0) {
            finish();
        } else if (mode == 2) {
            finishAndRemoveTask();
            if (!sp.getBoolean("mock_on", false)) {
                // write every pending setting to disk before the process is killed
                sp.edit().putBoolean("open", false).commit();
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(0);
            }
        }
    }

    // ================= settings =================
    ScrollView wrapScroll(View content) {
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        sv.setBackgroundColor(pbg());
        sv.addView(content);
        return sv;
    }

    // average color of the settings pages background (used by the engraved version text)
    int menuAvg() {
        int c = solid(mbg());
        if (mbgBmp != null) {
            try {
                int w = mbgBmp.getWidth(), h = mbgBmp.getHeight();
                long r = 0, g = 0, b = 0;
                int n = 0;
                for (int y = h * 3 / 4; y < h; y += 8) {
                    for (int x = 0; x < w; x += 8) {
                        int p = mbgBmp.getPixel(x, y);
                        r += Color.red(p);
                        g += Color.green(p);
                        b += Color.blue(p);
                        n++;
                    }
                }
                if (n > 0) c = Color.rgb((int) (r / n), (int) (g / n), (int) (b / n));
            } catch (Throwable e) { }
        }
        return c;
    }

    int verMix(int a, int b, float k) {
        return Color.rgb(Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * k),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * k),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k));
    }

    // version number carved into the page: follows the page color, always shows the installed version
    View versionLabel() {
        String ver = "";
        try { ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Throwable e) { }
        TextView tv = new TextView(this);
        tv.setText(t("نسخه ", "Version ") + ver);
        tv.setTextSize(15);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        int base = menuAvg();
        boolean lt = isLight(base);
        // clear text with a carved edge: dark text and a light edge on light pages, light text and a dark edge on dark pages
        tv.setTextColor(verMix(base, lt ? 0xFF000000 : 0xFFFFFFFF, 0.72f));
        tv.setShadowLayer(1.5f, 0f, lt ? dp(1) : -dp(1), verMix(base, lt ? 0xFFFFFFFF : 0xFF000000, 0.85f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(28), 0, dp(16));
        tv.setLayoutParams(lp);
        return tv;
    }

    View settingsPage(final int fg) {
        LinearLayout r = vbox();
        r.addView(topBar(t("تنظیمات", "Settings"), fg));
        r.addView(row(0xFF00ACC1, "☰", t("نوار ابزار", "Toolbar"), null, 0, fg,
                v -> { page = TBAR; show(); }));
        r.addView(row(0xFF5E35B1, "🏠", t("صفحه خانگی", "Home Page"), null, 0, fg,
                v -> { page = HPAGE; show(); }));
        r.addView(row(0xFF5C6BC0, "🎨", t("پس‌زمینه", "Background"), null, 0, fg,
                v -> { page = BG; show(); }));
        r.addView(row(0xFFFF9800, "🚀", t("رفتار", "Behavior"), null, 0, fg,
                v -> { page = BEHAVIOR; show(); }));
        r.addView(row(0xFF29B6F6, "⚓", t("داک", "Dock"), null, 0, fg,
                v -> { page = DOCK; show(); }));
        r.addView(row(0xFFFF5722, "🔮", t("ماک (حباب شناور)", "Mock (Floating Bubble)"), null, 0, fg,
                v -> { page = MOCK; show(); }));
        r.addView(row(0xFF8D6E63, "📁", t("تنظیمات پوشه", "Folder Settings"), null, 0, fg,
                v -> { page = FOLDER; show(); }));
        r.addView(row(0xFF3F51B5, "Aa", t("فونت", "Font"), null, 0, fg,
                v -> { page = FONT; show(); }));
        r.addView(row(0xFFEC407A, "🌐", t("زبان", "Language"), fa ? "فارسی" : "English", 0, fg,
                v -> { page = LANG; show(); }));
        r.addView(row(0xFF43A047, "💾", t("پشتیبان", "Backup"),
                t("گرفتن و فراخوانی نسخه پشتیبان تنظیمات", "Create and restore settings backups"), 0, fg,
                v -> { page = BACKUP; show(); }));
        r.addView(versionLabel());
        return wrapScroll(r);
    }

    // ---------- home page: frame, frame background, icon ----------
    View homeSetPage(final int fg) {
        LinearLayout r = vbox();
        r.addView(topBar(t("صفحه خانگی", "Home Page"), fg));
        final boolean on = sp.getBoolean("hfOn", false);
        r.addView(row(0xFF7E57C2, "▣", t("کادر صفحه خانگی", "Home page frame"),
                t("کادری مثل پوشه فراگیر پشت آیکون‌های صفحه", "A frame like the immersive folder behind the icons"),
                on ? 3 : 2, fg, v -> {
            sp.edit().putBoolean("hfOn", !on).apply();
            show();
        }));
        r.addView(row(0xFFFF7043, "🖼", t("پس‌زمینه کادر", "Frame background"),
                t("رنگ، شفافیت و عکس کادر", "Color, transparency and picture of the frame"), 0, fg,
                v -> { page = HFBG; show(); }));
        // one page that scrolls down (on) or several pages that turn left and right (off)
        r.addView(toggleRowThen(0xFF26A69A, "↔", t("صفحات افقی", "Horizontal pages"),
                t("روشن: یک صفحه که به پایین اسکرول می‌شود. خاموش: چند صفحه که با کشیدن به چپ و راست می‌چرخند",
                        "On: one page that scrolls down. Off: several pages that turn left and right"),
                "hpScroll", true, fg, () -> { onPagesToggled(); show(); }));
        // icon settings open right here, in lighter glass boxes
        r.addView(toggleRow(0xFF7E57C2, "▣", t("افکت کادر", "Frame effect"),
                t("افکت صفحه روی کل کادر صفحه اصلی اجرا می‌شود و آیکون‌ها با کادر جابجا می‌شوند (نیاز به صفحات افقی و کادر روشن)",
                        "The page effect runs on the whole home frame and the icons move with it (needs paged mode and frame on)"),
                "hfFx", false, fg));
        if (pagedMode()) r.addView(transAnimGroup(fg));
        r.addView(accordionGroupFaded(0xFFAB47BC, "🧩", t("آیکون", "Icon"), fg, 0.6f, () -> iconItems(fg)));
        return wrapScroll(r);
    }

    // ---------- font pages ----------
    View fontMenuPage(int fg) {
        LinearLayout r = vbox();
        r.addView(topBar(t("فونت", "Font"), fg));
        r.addView(row(0xFF3F51B5, "Aa", t("فونت تنظیمات", "Settings font"),
                t("فونت و اندازه متن منوی تنظیمات", "Font and size of the settings menu text"), 0, fg,
                v -> { fGrp = "s"; page = FGRP; show(); }));
        r.addView(row(0xFF00897B, "Aa", t("فونت آیکون‌ و پوشه", "Icons & folders font"),
                t("فونت نام آیکون‌ها و پوشه‌ها", "Font of icon and folder names"), 0, fg,
                v -> { fGrp = "i"; page = FGRP; show(); }));
        TextView note = labelBox(t("فونت دلخواه (مثل میترا): فایل ttf یا otf را در پوشه VadLan/fonts حافظه گوشی بگذارید تا در لیست فونت‌ها بیاید.",
                "Custom fonts (like Mitra): put ttf or otf files in the VadLan/fonts folder of the phone storage and they will show in the font lists."), fg);
        note.setTextSize(12);
        note.setAlpha(0.85f);
        r.addView(note);
        return wrapScroll(r);
    }

    View fontRow(final String g, final String lang, int fg) {
        String key = sp.getString("font" + g + "_" + lang, "");
        boolean isFa = lang.equals("fa");
        String title = isFa ? t("فونت فارسی", "Persian font") : t("فونت انگلیسی", "English font");
        boolean other = isFa != fa;
        String sub = fontName(key);
        if (other) {
            sub = (isFa ? t("برای متن‌های فارسی", "Used for Persian text")
                    : t("برای متن‌های انگلیسی", "Used for English text")) + " · " + sub;
        }
        return row(isFa ? 0xFF3F51B5 : 0xFF00897B, isFa ? "ف" : "En", title, sub, 0, fg,
                v -> { fGrp = g; fLang = lang; page = FPICK; show(); });
    }

    View fontGroupPage(int fg) {
        final String g = fGrp;
        LinearLayout r = vbox();
        r.addView(topBar(g.equals("s") ? t("فونت تنظیمات", "Settings font")
                : t("فونت آیکون‌ و پوشه", "Icons & folders font"), fg));
        String main = fa ? "fa" : "en";
        String other = fa ? "en" : "fa";
        r.addView(fontRow(g, main, fg));
        if (g.equals("s")) {
            // size lever of the settings text: 4 to 36, changes the text while it moves
            r.addView(accordionRowL(0xFF4CAF50, "🔠", t("سایز فونت", "Font size"),
                    "fontS_size", 4, 36, 16, "", fg, null, v -> liveSettingsSize(v)));
        }
        r.addView(accordionRow(0xFFFF9800, "🅱", t("باریکی فونت", "Font thickness"),
                "font" + g + "_wt", 2, 10, 4, "", fg, () -> show()));
        r.addView(fontRow(g, other, fg));
        return wrapScroll(r);
    }

    View fontPickPage(int fg) {
        final String g = fGrp, lang = fLang;
        LinearLayout r = vbox();
        r.addView(topBar(lang.equals("fa") ? t("فونت فارسی", "Persian font")
                : t("فونت انگلیسی", "English font"), fg));
        String cur = sp.getString("font" + g + "_" + lang, "");
        String sample = lang.equals("fa") ? "نمونه متن فارسی ۱۲۳" : "Sample Text 123";
        for (final String key : fontKeys(lang)) {
            LinearLayout it = new LinearLayout(this);
            it.setGravity(Gravity.CENTER_VERTICAL);
            it.setPadding(dp(14), dp(12), dp(14), dp(12));
            it.setBackground(boxBg(light() ? 0x14000000 : 0x1AFFFFFF, BOX_R));
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            TextView nm = new TextView(this);
            nm.setText(fontName(key));
            nm.setTextSize(12);
            nm.setTextColor(fg);
            nm.setAlpha(0.65f);
            col.addView(nm);
            TextView sm = new TextView(this);
            sm.setTag("x");
            sm.setText(sample);
            sm.setTextSize(20);
            sm.setTextColor(fg);
            Typeface tf = face(key, 4);
            if (tf != null) sm.setTypeface(tf);
            col.addView(sm);
            it.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
            if (key.equals(cur)) {
                TextView ck = new TextView(this);
                ck.setTag("x");
                ck.setText("✓");
                ck.setTextSize(20);
                ck.setTextColor(0xFF34C759);
                it.addView(ck);
            }
            it.setOnClickListener(w -> {
                sp.edit().putString("font" + g + "_" + lang, key).commit();
                page = FGRP;
                show();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(0, dp(10), 0, 0);
            r.addView(it, lp);
        }
        return wrapScroll(r);
    }

    View mockPage(int fg) {
        LinearLayout r = vbox();
        r.addView(topBar(t("تنظیمات ماک", "Mock Settings"), fg));

        boolean mockOn = sp.getBoolean("mock_on", false);
        r.addView(row(0xFFFF5722, "🔮", t("فعال‌سازی ماک", "Enable Mock"),
                t("نمایش حباب شناور روی سایر برنامه‌ها", "Show floating bubble over other apps"),
                mockOn ? 3 : 2, fg, v -> {
            if (!mockOn) {
                if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
                    Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                    Toast.makeText(this, t("لطفاً مجوز نمایش روی برنامه‌ها را بدهید", "Please grant overlay permission"), Toast.LENGTH_LONG).show();
                    return;
                }
                sp.edit().putBoolean("mock_on", true).commit();
                startMock();
            } else {
                sp.edit().putBoolean("mock_on", false).commit();
                stopService(new Intent(this, MockBubbleService.class));
            }
            show();
        }));

        Runnable updateMock = () -> {
            if (sp.getBoolean("mock_on", false)) {
                startMock();
            }
        };

        // everything about what the bubble does and its list lives on its own page
        r.addView(row(0xFF00897B, "💬", t("حباب", "Bubble"),
                t("لانچر، عملکردها و ظاهر لیست حباب", "Launcher, actions and the look of the bubble list"),
                0, fg, v -> {
            page = BUB;
            show();
        }));
        r.addView(accordionRow(0xFF2196F3, "📐", t("سایز حباب", "Bubble Size"), "mock_size", 30, 100, 56, " dp", fg, updateMock));
        r.addView(accordionRow(0xFF4CAF50, "💧", t("شفافیت حباب", "Bubble Opacity"), "mock_alpha", 20, 100, 90, "%", fg, updateMock));
        r.addView(accordionRow(0xFFFFB300, "⏱", t("سرعت", "Speed"), "mock_speed", 50, 2000, 2000, " ms", fg, updateMock));
        r.addView(accordionRow(0xFF5C6BC0, "🙈", t("نهان", "Hide"), "mock_hide", 30, 70, 68, "%", fg, updateMock));
        r.addView(accordionRow(0xFF00ACC1, "↔", t("محل حباب (افقی)", "Bubble position (horizontal)"), "mock_px", 0, 100, 70, "%", fg, updateMock));
        r.addView(accordionRow(0xFF7E57C2, "↕", t("محل حباب (عمودی)", "Bubble position (vertical)"), "mock_py", 0, 100, 40, "%", fg, updateMock));

        boolean over = sp.getBoolean("mock_over", true);
        r.addView(row(0xFF9C27B0, "🔝", t("نمایش روی تمام برنامه‌ها", "Overlay Over Apps"),
                t("خاموش: حباب فقط روی صفحه لانچر دیده می‌شود", "Off: bubble is visible only on the launcher"),
                over ? 3 : 2, fg, v -> {
            sp.edit().putBoolean("mock_over", !over).apply();
            updateMock.run();
            show();
        }));

        return wrapScroll(r);
    }

    // ---------- bubble page ----------
    View bubblePage(final int fg) {
        LinearLayout r = vbox();
        r.addView(topBar(t("حباب", "Bubble"), fg));
        final boolean bubLau = sp.getBoolean("bub_launcher", true);
        final boolean bubAct = sp.getBoolean("bub_act_on", false);
        r.addView(row(0xFF3D7BF7, "🏠", t("لانچر", "Launcher"),
                t("با زدن روی حباب، برنامه لانچر باز شود", "Tapping the bubble opens the launcher app"),
                bubLau ? 3 : 2, fg, v -> {
            sp.edit().putBoolean("bub_launcher", !bubLau).apply();
            show();
        }));
        r.addView(row(0xFFEF6C00, "⚡", t("عملکرد", "Actions"),
                t("با زدن روی حباب، لیست عملکردها باز شود؛ با زدن روی نام برنامه فقط آخرین عملکرد آن اجرا می‌شود",
                        "Tapping the bubble opens the action list; tapping an app name runs only its last action"),
                bubAct ? 3 : 2, fg, v -> {
            sp.edit().putBoolean("bub_act_on", !bubAct).apply();
            show();
        }));
        r.addView(swipeGroup(fg));
        if (bubAct) {
            // how the action list opens: a list of effects (lighter boxes) and its speed
            final int fx = sp.getInt("bub_fx", 6);
            final String[] fxNames = {
                    t("بدون افکت", "No effect"), t("قطره‌ای (کش‌دار)", "Drop (stretchy)"), t("محو شدن", "Fade"),
                    t("کشویی", "Slide from the side"), t("بزرگ شدن از حباب", "Grow from the bubble"),
                    t("کشویی از بالا", "Slide from the top")};
            final int[] fxIds = {0, 6, 1, 2, 3, 5};
            final int[] fxDots = {0xFF9E9E9E, 0xFF29B6F6, 0xFF42A5F5, 0xFF26A69A, 0xFFFF7043, 0xFF66BB6A};
            r.addView(accordionGroupFaded(0xFF26A69A, "🎞", t("افکت", "Effect"), fg, 0.6f, () -> {
                View[] its = new View[fxNames.length];
                for (int i = 0; i < its.length; i++) {
                    final int k = i;
                    its[i] = row(fxDots[i], "✦", fxNames[i], null, fx == fxIds[i] ? 1 : 0, fg, v -> {
                        sp.edit().putInt("bub_fx", fxIds[k]).apply();
                        show();
                    });
                }
                return its;
            }));
            r.addView(accordionRow(0xFFFFB300, "⏱", t("سرعت افکت", "Effect speed"), "bub_fxms", 100, 1500, 300, " ms", fg, null));
        }
        r.addView(row(0xFF8E24AA, "📝", t("ثبت عملکردها", "Record actions"),
                BubbleActs.count(BubbleActs.load(sp)) + " / " + BubbleActs.MAX,
                0, fg, v -> {
            page = ACTS;
            show();
        }));
        // icon and name of every action in the list: at least one of them is always on
        final boolean icoOn = sp.getBoolean("bub_ico", false);
        final boolean nmOn = sp.getBoolean("bub_nm", true);
        r.addView(row(0xFF42A5F5, "🖼", t("آیکون عملکرد", "Action icon"),
                t("آیکون برنامه کنار هر عملکرد در لیست", "The app icon next to every action in the list"),
                icoOn ? 3 : 2, fg, v -> {
            SharedPreferences.Editor ed = sp.edit().putBoolean("bub_ico", !icoOn);
            if (icoOn && !nmOn) ed.putBoolean("bub_nm", true);
            ed.apply();
            show();
        }));
        r.addView(accordionRow(0xFF26C6DA, "📏", t("سایز آیکون عملکرد", "Action icon size"), "bub_isz", 5, 32, 20, " dp", fg, null));
        r.addView(row(0xFFFFA726, "🔤", t("اسم عملکرد", "Action name"),
                t("نام هر عملکرد سمت راست آیکون آن", "The name of every action to the right of its icon"),
                nmOn || !icoOn ? 3 : 2, fg, v -> {
            SharedPreferences.Editor ed = sp.edit().putBoolean("bub_nm", !nmOn);
            if (nmOn && !icoOn) ed.putBoolean("bub_ico", true);
            ed.apply();
            show();
        }));
        // every app of the list in its own box, stacked, with a gap between them
        final boolean sep = sp.getBoolean("bub_sep", false);
        r.addView(toggleRowThen(0xFF26A69A, "▦", t("کادر جدا", "Separate boxes"),
                t("هر برنامه در کادر جداگانه زیر هم؛ عرض و ارتفاع لیست اندازه همه کادرها را هم‌زمان تغییر می‌دهد",
                        "Every app in its own box, stacked; list width and height resize all the boxes together"),
                "bub_sep", false, fg, () -> show()));
        // how many boxes are visible at once (the others scroll in)
        r.addView(accordionRow(0xFF5C6BC0, "🔢", t("تعداد کادرها", "Number of boxes"), "bub_cnt", 2, 20, 20, "", fg, null));
        // look of the list (even fainter boxes inside)
        r.addView(accordionGroupFaded(0xFF5E35B1, "🎨", t("پس‌زمینه لیست عملکرد", "Action list background"), fg, 0.4f, () -> {
            List<View> l = new ArrayList<>();
            l.add(row(0xFFE91E63, "🎨", t("رنگ", "Color"),
                    t("رنگ و شفافیت پس‌زمینه لیست", "Color and opacity of the list background"),
                    0, fg, v -> {
                page = BLBG;
                show();
            }));
            l.add(accordionRow(0xFF42A5F5, "↔", t("عرض لیست", "List width"), "bub_lw", 15, 200, 190, " dp", fg, null));
            l.add(accordionRow(0xFF26C6DA, "↕", t("ارتفاع لیست", "List height"), "bub_lh", 20, 100, 100, "%", fg, null));
            if (sep) l.add(accordionRow(0xFF8D6E63, "⇕", t("فاصله کادر لیست", "List box spacing"), "bub_gap", -2000, 6000, 800, " µm", fg, null));
            l.add(row(0xFF7E57C2, "🪟", t("شیشه‌ای", "Glass"),
                    t("لبه روشن و محو شدن پشت لیست", "Bright edge and blur behind the list"),
                    sp.getBoolean("bub_lglass", false) ? 3 : 2, fg, v -> {
                sp.edit().putBoolean("bub_lglass", !sp.getBoolean("bub_lglass", false)).apply();
                show();
            }));
            l.add(accordionRow(0xFFFFB300, "🔤", t("اندازه نام برنامه‌ها", "App name size"), "bub_lts", 10, 28, 15, " sp", fg, null));
            return l.toArray(new View[0]);
        }));
        return wrapScroll(r);
    }

    // ---------- bubble actions: up to 20 saved actions ----------
    View actsPage(final int fg) {
        LinearLayout r = vbox();
        r.addView(topBar(t("عملکردهای حباب", "Bubble actions"), fg));
        TextView hint = new TextView(this);
        hint.setText(t("هر جایگاه یک عملکرد است: برنامه را انتخاب می‌کنید، یک نام دلخواه می‌دهید و نوع اجرا را برمی‌گزینید (پاپ آپ، کامل، ضبط روش داخل برنامه یا کاشی تنظیمات سریع). در لیست حباب با همان نام دلخواه دیده می‌شود. با ضبط روش، برنامه باز می‌شود و کلیک‌های شما ذخیره می‌شود؛ با زدن روی نام، همان کلیک‌ها دوباره اجرا می‌شود.",
                "Each slot is one action: pick an app, give it a custom name and choose how it runs (popup, full, record a way inside the app, or a Quick Settings tile). It shows in the bubble list with that name. When recording, the app opens and your taps are saved; tapping the name plays the same taps again."));
        hint.setTextSize(12);
        hint.setTextColor(fg);
        hint.setAlpha(0.65f);
        hint.setPadding(dp(4), dp(10), dp(4), 0);
        r.addView(hint);
        final float oldFade = boxFade;
        boxFade = 0.6f; // the slots are secondary (fainter) boxes
        final BubbleActs.Act[] acts = BubbleActs.load(sp);
        for (int i = 0; i < BubbleActs.MAX; i++) {
            final int slot = i;
            BubbleActs.Act a = acts[i];
            if (a == null) {
                r.addView(row(0xFF9E9E9E, "+", (i + 1) + ". " + t("ثبت عملکرد", "Record action"),
                        null, 0, fg, v -> actSlotClick(slot)));
            } else {
                String sub = (a.label == null || a.label.isEmpty() ? a.type : a.label)
                        + "  •  " + BubbleActs.appLabel(this, a.pkg);
                r.addView(row(0xFF26A69A, "✓", (i + 1) + ". " + BubbleActs.title(this, a),
                        sub, 0, fg, v -> actSlotClick(slot)));
            }
        }
        // hold one second on an action to pick it up; the others slide aside, it sits where it is let go
        final List<View> rows = new ArrayList<>();
        boolean[] cm = new boolean[BubbleActs.MAX];
        for (int i = 0; i < BubbleActs.MAX; i++) {
            rows.add(r.getChildAt(r.getChildCount() - BubbleActs.MAX + i));
            cm[i] = acts[i] != null;
        }
        attachReorder(rows, cm, (from, to) -> moveAct(from, to));
        boxFade = oldFade;
        return wrapScrollKeep(r);
    }

    // moves an action from one slot to another; the ones in between shift by one place
    void moveAct(int f, int to) {
        BubbleActs.Act[] a = BubbleActs.load(sp);
        boolean[] on = new boolean[BubbleActs.MAX];
        for (int i = 0; i < BubbleActs.MAX; i++) on[i] = sp.getBoolean("bub_on_s" + i, false);
        BubbleActs.Act x = a[f];
        boolean xo = on[f];
        if (f < to) {
            for (int k = f; k < to; k++) { a[k] = a[k + 1]; on[k] = on[k + 1]; }
        } else {
            for (int k = f; k > to; k--) { a[k] = a[k - 1]; on[k] = on[k - 1]; }
        }
        a[to] = x;
        on[to] = xo;
        BubbleActs.save(sp, a);
        SharedPreferences.Editor ed = sp.edit();
        for (int i = 0; i < BubbleActs.MAX; i++) ed.putBoolean("bub_on_s" + i, on[i]);
        ed.apply();
        show();
    }

    void actSlotClick(final int slot) {
        final BubbleActs.Act[] acts = BubbleActs.load(sp);
        final BubbleActs.Act a = acts[slot];
        if (a == null) {
            actPickApp(slot);
            return;
        }
        CharSequence[] items = {
                t("تغییر عملکرد", "Change action"),
                t("تغییر نام", "Rename"),
                t("حذف", "Delete")};
        new AlertDialog.Builder(this).setTitle((slot + 1) + ". " + BubbleActs.title(this, a))
                .setItems(items, (d, w) -> {
                    if (w == 0) {
                        actPickApp(slot);
                    } else if (w == 1) {
                        actAskName(slot, a.pkg, BubbleActs.appLabel(this, a.pkg), BubbleActs.title(this, a), true);
                    } else {
                        acts[slot] = null;
                        BubbleActs.save(sp, acts);
                        sp.edit().remove("bub_on_s" + slot).apply();
                        show();
                    }
                }).setNegativeButton(t("لغو", "Cancel"), null).show();
    }

    // ---------- holding a row for one second to move it ----------
    interface Mv { void go(int from, int to); }

    int keepScrollY = -1;

    // a settings page that comes back at the same scroll place after a move
    ScrollView wrapScrollKeep(View content) {
        final ScrollView sv = wrapScroll(content);
        if (keepScrollY >= 0) {
            final int y = keepScrollY;
            keepScrollY = -1;
            sv.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    sv.getViewTreeObserver().removeOnPreDrawListener(this);
                    sv.scrollTo(0, y);
                    return true;
                }
            });
        }
        return sv;
    }

    void attachReorder(List<View> rows, boolean[] canMove, Mv mv) {
        for (int i = 0; i < rows.size(); i++) {
            if (canMove[i]) rows.get(i).setOnTouchListener(new Reorder(rows, i, mv));
        }
    }

    class Reorder implements View.OnTouchListener {
        final List<View> rows;
        final int idx;
        final Mv mv;
        boolean drag;
        float downX, downY, lastY, grab;
        int cur;
        int[] tops, hs;
        ScrollView sv;
        final Runnable hold = new Runnable() {
            @Override
            public void run() { begin(); }
        };
        final Runnable scroller = new Runnable() {
            @Override
            public void run() {
                if (!drag || sv == null) return;
                int[] sl = new int[2];
                sv.getLocationOnScreen(sl);
                float y = lastY - sl[1];
                int step = 0;
                if (y < dp(90)) step = -dp(10);
                else if (y > sv.getHeight() - dp(90)) step = dp(10);
                if (step != 0) {
                    int before = sv.getScrollY();
                    sv.scrollBy(0, step);
                    if (sv.getScrollY() != before) update();
                }
                sv.postDelayed(this, 16);
            }
        };

        Reorder(List<View> rows, int idx, Mv mv) {
            this.rows = rows;
            this.idx = idx;
            this.mv = mv;
        }

        float pitch(int k) { return k + 1 < tops.length ? tops[k + 1] - tops[k] : hs[k] + dp(10); }

        void begin() {
            View v = rows.get(idx);
            drag = true;
            try { v.getParent().requestDisallowInterceptTouchEvent(true); } catch (Throwable e) { }
            v.setPressed(false);
            v.cancelLongPress();
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            int n = rows.size();
            tops = new int[n];
            hs = new int[n];
            for (int k = 0; k < n; k++) {
                tops[k] = rows.get(k).getTop();
                hs[k] = rows.get(k).getHeight();
            }
            cur = idx;
            ViewParent p = v.getParent();
            while (p != null && !(p instanceof ScrollView)) p = p.getParent();
            sv = (ScrollView) p;
            int[] pl = new int[2];
            ((View) v.getParent()).getLocationOnScreen(pl);
            grab = (lastY - pl[1]) - tops[idx];
            v.setScaleX(1.03f);
            v.setScaleY(1.03f);
            v.setTranslationZ(dp(10));
            v.setAlpha(0.92f);
            if (sv != null) sv.post(scroller);
        }

        void update() {
            View v = rows.get(idx);
            int[] pl = new int[2];
            ((View) v.getParent()).getLocationOnScreen(pl);
            float ty = (lastY - pl[1]) - grab - tops[idx];
            v.setTranslationY(ty);
            float center = tops[idx] + ty + hs[idx] / 2f;
            int to = rows.size() - 1;
            for (int k = 0; k < rows.size(); k++) {
                if (center < tops[k] + hs[k]) { to = k; break; }
            }
            if (to != cur) {
                cur = to;
                float foot = pitch(idx);
                for (int k = 0; k < rows.size(); k++) {
                    if (k == idx) continue;
                    float off = 0;
                    if (idx < cur && k > idx && k <= cur) off = -foot;
                    else if (idx > cur && k >= cur && k < idx) off = foot;
                    rows.get(k).animate().translationY(off).setDuration(140).start();
                }
            }
        }

        void finish(boolean ok) {
            drag = false;
            final View v = rows.get(idx);
            if (sv != null) sv.removeCallbacks(scroller);
            final int from = idx, to = cur;
            if (ok && to != from) {
                float fin = tops[to] - tops[from];
                if (from < to) fin += pitch(to) - pitch(from);
                v.animate().translationY(fin).setDuration(120).withEndAction(() -> {
                    keepScrollY = sv != null ? sv.getScrollY() : -1;
                    mv.go(from, to);
                }).start();
            } else {
                v.animate().translationY(0).scaleX(1f).scaleY(1f).alpha(1f).translationZ(0).setDuration(140).start();
                for (View o : rows) if (o != v) o.animate().translationY(0).setDuration(140).start();
                v.setPressed(false);
            }
        }

        @Override
        public boolean onTouch(View v, MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    drag = false;
                    downX = ev.getRawX();
                    downY = ev.getRawY();
                    lastY = downY;
                    v.postDelayed(hold, 1000);
                    return false;
                case MotionEvent.ACTION_MOVE:
                    lastY = ev.getRawY();
                    if (drag) { update(); return true; }
                    if (Math.abs(ev.getRawX() - downX) > dp(10) || Math.abs(ev.getRawY() - downY) > dp(10)) v.removeCallbacks(hold);
                    return false;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.removeCallbacks(hold);
                    if (drag) {
                        finish(ev.getActionMasked() == MotionEvent.ACTION_UP);
                        return true;
                    }
                    return false;
            }
            return false;
        }
    }

    // ---------- bubble moves: left, down and right (up always opens VadLan) ----------
    String swDir = "left";

    static final int[] AI_DOT = {0xFF4285F4, 0xFF10A37F, 0xFFD97757, 0xFF5C6BC0, 0xFF26A69A, 0xFFAB47BC, 0xFF34A853, 0xFF43A047, 0xFF00897B};
    static final String[] AI_SYM = {"✦", "◎", "✺", "🎙", "🎥", "🖥", "文", "🎤", "📷"};

    boolean faNow() { return sp.getString("lang", "fa").equals("fa"); }

    String swArrow(String d) { return d.equals("left") ? "←" : d.equals("down") ? "↓" : "→"; }

    String swDirName(String d) {
        return d.equals("left") ? t("چپ", "Left") : d.equals("down") ? t("پایین", "Down") : t("راست", "Right");
    }

    // what a move does, in a few words
    String swSummary(String d) {
        if (sp.getBoolean("swai_" + d, false)) return t("هوش مصنوعی", "AI");
        if ("list".equals(sp.getString("swm_" + d, ""))) {
            int n = BubbleActs.swList(sp, d).size();
            if (n > 0) return t("لیست برنامه‌ها", "App list") + " (" + n + ")";
        }
        String pkg = sp.getString("swipe_" + d, "");
        if (pkg != null && !pkg.isEmpty() && installed(pkg)) return label(pkg);
        return swDirName(d);
    }

    // bubble moves: three boxes (left, down, right); each one opens the choices of what the move does
    View swipeGroup(final int fg) {
        return accordionGroupFaded(0xFF00ACC1, "↕", t("حرکات حباب", "Bubble moves"), fg, 0.6f, () -> {
            String[] dirs = {"left", "down", "right"};
            int[] dots = {0xFF26A69A, 0xFFFF7043, 0xFFAB47BC};
            View[] its = new View[3];
            for (int i = 0; i < 3; i++) {
                final String d = dirs[i];
                its[i] = row(dots[i], swArrow(d), swSummary(d), null, 0, fg, v -> swipeMenu(d));
            }
            return its;
        });
    }

    void swipeMenu(final String d) {
        final boolean ai = sp.getBoolean("swai_" + d, false);
        final List<CharSequence> items = new ArrayList<>();
        items.add(t("📱 انتخاب برنامه", "📱 Choose an app"));
        items.add("➕ " + t("لیست برنامه‌ها", "App list") + " (" + BubbleActs.swList(sp, d).size() + " / " + BubbleActs.SW_MAX + ")");
        items.add((ai ? "☑ " : "☐ ") + t("هوش مصنوعی", "AI"));
        if (ai) items.add(t("⚙ ویرایش لیست هوش مصنوعی", "⚙ Edit the AI list"));
        items.add(t("🗑 بدون عملکرد", "🗑 No action"));
        new AlertDialog.Builder(this).setTitle(swArrow(d) + "  " + swDirName(d))
                .setItems(items.toArray(new CharSequence[0]), (dlg, w) -> {
                    if (w == 0) {
                        pickSwipeApp(d);
                    } else if (w == 1) {
                        sp.edit().putString("swm_" + d, "list").putBoolean("swai_" + d, false).apply();
                        swDir = d;
                        page = SWL;
                        show();
                    } else if (w == 2) {
                        if (ai) {
                            sp.edit().putBoolean("swai_" + d, false).apply();
                            show();
                        } else {
                            sp.edit().putBoolean("swai_" + d, true).apply();
                            swDir = d;
                            page = AIL;
                            show();
                        }
                    } else if (ai && w == 3) {
                        swDir = d;
                        page = AIL;
                        show();
                    } else {
                        sp.edit().putBoolean("swai_" + d, false).remove("swipe_" + d).remove("swm_" + d).remove("swl_" + d).apply();
                        show();
                    }
                })
                .setNegativeButton(t("لغو", "Cancel"), null).show();
    }

    // one app for this move: pick it, then choose popup or full
    void pickSwipeApp(final String d) {
        chooseApp(pkg -> chooseWin(win -> {
            sp.edit().putString("swipe_" + d, pkg).putString("swipe_" + d + "_w", win)
                    .putString("swm_" + d, "app").putBoolean("swai_" + d, false).apply();
            show();
        }));
    }

    void chooseApp(final Consumer<String> cb) {
        PackageManager pm = getPackageManager();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        Set<String> seen = new HashSet<>();
        final List<String[]> l = new ArrayList<>();
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            String p = ri.activityInfo.packageName;
            if (p.equals(getPackageName()) || !seen.add(p)) continue;
            l.add(new String[]{ri.loadLabel(pm).toString(), p});
        }
        Collections.sort(l, (x, y) -> x[0].compareToIgnoreCase(y[0]));
        CharSequence[] names = new CharSequence[l.size()];
        for (int i = 0; i < names.length; i++) names[i] = l.get(i)[0];
        new AlertDialog.Builder(this).setTitle(t("انتخاب برنامه", "Choose app"))
                .setItems(names, (dlg, w) -> cb.accept(l.get(w)[1]))
                .setNegativeButton(t("لغو", "Cancel"), null).show();
    }

    void chooseWin(final Consumer<String> cb) {
        CharSequence[] items = {
                t("🗗 باز شدن در پاپ آپ", "🗗 Open in a popup"),
                t("⛶ باز شدن کامل", "⛶ Open full")};
        new AlertDialog.Builder(this).setTitle(t("نوع باز شدن", "How it opens"))
                .setItems(items, (dlg, w) -> cb.accept(w == 0 ? "popup" : "full"))
                .setNegativeButton(t("لغو", "Cancel"), null).show();
    }

    String winText(String w) {
        return "popup".equals(w) ? t("باز شدن در پاپ آپ", "Open in popup") : t("باز شدن کامل", "Open full");
    }

    TextView pageHint(int fg, String s) {
        TextView hint = new TextView(this);
        hint.setText(s);
        hint.setTextSize(12);
        hint.setTextColor(fg);
        hint.setAlpha(0.65f);
        hint.setPadding(dp(4), dp(10), dp(4), 0);
        return hint;
    }

    // the apps of one move: a "+" adds an app, a one second hold moves one up or down
    View swlPage(final int fg) {
        final String d = swDir;
        LinearLayout r = vbox();
        r.addView(topBar(swArrow(d) + "  " + t("لیست برنامه‌ها", "App list"), fg));
        r.addView(pageHint(fg, t("وقتی حباب را به این سمت می‌کشید، این برنامه‌ها در لیستی مثل لیست عملکرد باز می‌شوند. برای جابه‌جایی، یک ثانیه روی برنامه نگه دارید و به بالا یا پایین ببرید.",
                "Dragging the bubble this way opens these apps in a list like the action list. To move one, hold it for one second and slide it up or down.")));
        final float oldFade = boxFade;
        boxFade = 0.6f;
        final List<String[]> l = BubbleActs.swList(sp, d);
        final List<View> rows = new ArrayList<>();
        for (int i = 0; i < l.size(); i++) {
            final int k = i;
            String[] e = l.get(i);
            View rv = row(0xFF26A69A, "✓", (i + 1) + ". " + (installed(e[0]) ? label(e[0]) : e[0]),
                    winText(e[1]), 0, fg, v -> swItemClick(d, k));
            r.addView(rv);
            rows.add(rv);
        }
        if (l.size() < BubbleActs.SW_MAX) {
            r.addView(row(0xFF9E9E9E, "+", t("افزودن برنامه", "Add app"), l.size() + " / " + BubbleActs.SW_MAX, 0, fg,
                    v -> chooseApp(pkg -> chooseWin(win -> {
                        List<String[]> n2 = BubbleActs.swList(sp, d);
                        n2.add(new String[]{pkg, win});
                        BubbleActs.swSave(sp, d, n2);
                        sp.edit().putString("swm_" + d, "list").putBoolean("swai_" + d, false).apply();
                        show();
                    }))));
        }
        boxFade = oldFade;
        boolean[] cm = new boolean[rows.size()];
        Arrays.fill(cm, true);
        attachReorder(rows, cm, (from, to) -> {
            List<String[]> n2 = BubbleActs.swList(sp, d);
            if (from >= n2.size() || to >= n2.size()) return;
            String[] x = n2.remove(from);
            n2.add(to, x);
            BubbleActs.swSave(sp, d, n2);
            show();
        });
        return wrapScrollKeep(r);
    }

    void swItemClick(final String d, final int k) {
        List<String[]> l0 = BubbleActs.swList(sp, d);
        if (k >= l0.size()) return;
        CharSequence[] items = {
                t("🗗 باز شدن در پاپ آپ", "🗗 Open in a popup"),
                t("⛶ باز شدن کامل", "⛶ Open full"),
                t("🔄 تغییر برنامه", "🔄 Change app"),
                t("حذف", "Delete")};
        String nm = installed(l0.get(k)[0]) ? label(l0.get(k)[0]) : l0.get(k)[0];
        new AlertDialog.Builder(this).setTitle(nm).setItems(items, (dlg, w) -> {
            List<String[]> n2 = BubbleActs.swList(sp, d);
            if (k >= n2.size()) return;
            if (w == 0 || w == 1) {
                n2.get(k)[1] = w == 0 ? "popup" : "full";
                BubbleActs.swSave(sp, d, n2);
                show();
            } else if (w == 2) {
                chooseApp(pkg -> {
                    List<String[]> n3 = BubbleActs.swList(sp, d);
                    if (k >= n3.size()) return;
                    n3.get(k)[0] = pkg;
                    BubbleActs.swSave(sp, d, n3);
                    show();
                });
            } else {
                n2.remove(k);
                BubbleActs.swSave(sp, d, n2);
                show();
            }
        }).setNegativeButton(t("لغو", "Cancel"), null).show();
    }

    // the AI actions of one move: a tap chooses popup or full, a one second hold moves one up or down
    View aiPage(final int fg) {
        final String d = swDir;
        final boolean fa = faNow();
        LinearLayout r = vbox();
        r.addView(topBar(swArrow(d) + "  " + t("هوش مصنوعی", "AI"), fg));
        r.addView(pageHint(fg, t("وقتی حباب را به این سمت می‌کشید، این عملکردها در لیستی مثل لیست عملکرد باز می‌شوند. با زدن روی هر کدام، باز شدن کامل یا پاپ آپ را برایش تعیین کنید. برای جابه‌جایی، یک ثانیه نگه دارید و به بالا یا پایین ببرید.",
                "Dragging the bubble this way opens these actions in a list like the action list. Tap one to choose popup or full for it. To move one, hold it for one second and slide it up or down.")));
        final float oldFade = boxFade;
        boxFade = 0.6f;
        final int[] ord = BubbleActs.aiOrder(sp, d);
        final List<View> rows = new ArrayList<>();
        for (int i = 0; i < ord.length; i++) {
            final int id = ord[i];
            View rv = row(AI_DOT[id], AI_SYM[id], BubbleActs.aiName(fa, id),
                    winText(BubbleActs.aiWin(sp, d, id)), 0, fg, v -> aiItemClick(d, id));
            r.addView(rv);
            rows.add(rv);
        }
        boxFade = oldFade;
        boolean[] cm = new boolean[rows.size()];
        Arrays.fill(cm, true);
        attachReorder(rows, cm, (from, to) -> {
            int[] o = BubbleActs.aiOrder(sp, d);
            int x = o[from];
            if (from < to) {
                for (int k = from; k < to; k++) o[k] = o[k + 1];
            } else {
                for (int k = from; k > to; k--) o[k] = o[k - 1];
            }
            o[to] = x;
            BubbleActs.aiSaveOrder(sp, d, o);
            show();
        });
        return wrapScrollKeep(r);
    }

    void aiItemClick(final String d, final int id) {
        String cur = BubbleActs.aiWin(sp, d, id);
        CharSequence[] items = {
                ("popup".equals(cur) ? "✓ " : "") + t("🗗 باز شدن در پاپ آپ", "🗗 Open in a popup"),
                ("full".equals(cur) ? "✓ " : "") + t("⛶ باز شدن کامل", "⛶ Open full")};
        new AlertDialog.Builder(this).setTitle(BubbleActs.aiName(faNow(), id)).setItems(items, (dlg, w) -> {
            sp.edit().putString("swai_" + d + "_w" + id, w == 0 ? "popup" : "full").apply();
            show();
        }).setNegativeButton(t("لغو", "Cancel"), null).show();
    }

    // step 1: which app
    void actPickApp(final int slot) {
        PackageManager pm = getPackageManager();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        Set<String> seen = new HashSet<>();
        final List<String[]> l = new ArrayList<>();
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            String p = ri.activityInfo.packageName;
            if (p.equals(getPackageName()) || !seen.add(p)) continue;
            l.add(new String[]{ri.loadLabel(pm).toString(), p});
        }
        Collections.sort(l, (x, y) -> x[0].compareToIgnoreCase(y[0]));
        CharSequence[] names = new CharSequence[l.size()];
        for (int i = 0; i < names.length; i++) names[i] = l.get(i)[0];
        new AlertDialog.Builder(this).setTitle(t("انتخاب برنامه", "Choose app"))
                .setItems(names, (d, w) -> actAskName(slot, l.get(w)[1], l.get(w)[0], l.get(w)[0], false))
                .setNegativeButton(t("لغو", "Cancel"), null).show();
    }

    // step 2: the name this action gets in the bubble list
    void actAskName(final int slot, final String pkg, final String appName, String initial, final boolean rename) {
        final EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setText(initial);
        et.setSelection(initial.length());
        FrameLayout fl = new FrameLayout(this);
        fl.setPadding(dp(20), dp(8), dp(20), 0);
        fl.addView(et, new FrameLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this).setTitle(t("نام عملکرد", "Action name"))
                .setMessage(t("این نام در لیست عملکرد حباب نمایش داده می‌شود.", "This name is shown in the bubble action list."))
                .setView(fl)
                .setPositiveButton(t("تأیید", "OK"), (d, w) -> {
                    String nm = et.getText().toString().trim();
                    if (nm.isEmpty()) nm = appName;
                    if (rename) {
                        BubbleActs.Act[] acts = BubbleActs.load(sp);
                        if (acts[slot] != null) {
                            acts[slot].name = nm;
                            BubbleActs.save(sp, acts);
                        }
                        show();
                    } else {
                        actMenu(slot, pkg, nm, appName);
                    }
                })
                .setNegativeButton(t("لغو", "Cancel"), null).show();
    }

    // step 3: how the action runs
    void actMenu(final int slot, final String pkg, final String name, final String appName) {
        CharSequence[] items = {
                t("🗗 باز شدن در پاپ آپ", "🗗 Open in a popup"),
                t("⛶ باز شدن کامل", "⛶ Open full"),
                t("🎬 ضبط روش داخل برنامه", "🎬 Record a way inside the app"),
                t("🔲 کاشی تنظیمات سریع", "🔲 Quick Settings tile")};
        new AlertDialog.Builder(this).setTitle(name).setItems(items, (d, w) -> {
            if (w == 0) actSave(slot, pkg, "app", "popup", t("باز شدن در پاپ آپ", "Open in popup"), name);
            else if (w == 1) actSave(slot, pkg, "app", "full", t("باز شدن کامل", "Open full"), name);
            else if (w == 2) actStartMacro(slot, pkg, name);
            else actPickTile(slot, pkg, name, appName);
        }).setNegativeButton(t("لغو", "Cancel"), null).show();
    }

    // the app opens and everything tapped in it is recorded until "Stop recording" is pressed
    void actStartMacro(int slot, String pkg, String name) {
        if (!TileClickService.ready()) {
            actAskA11y();
            return;
        }
        actSave(slot, pkg, "macro", "[]", "🎬 0", name);
        Toast.makeText(this, t("برنامه باز شد؛ روش دلخواه را انتخاب کنید و در پایان «پایان ضبط» را بزنید",
                "The app opened: pick the way you want, then press \"Stop recording\""), Toast.LENGTH_LONG).show();
        TileClickService.record(pkg, slot);
    }

    void actAskA11y() {
        new AlertDialog.Builder(this).setTitle(t("دسترسی‌پذیری", "Accessibility"))
                .setMessage(t("برای این کار باید سرویس دسترسی‌پذیری VadLan را در تنظیمات گوشی روشن کنید.",
                        "Turn on the VadLan accessibility service in the phone settings first."))
                .setPositiveButton(t("باز کردن تنظیمات", "Open settings"), (d2, w2) -> {
                    try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); } catch (Exception e) { }
                })
                .setNegativeButton(t("بعداً", "Later"), null).show();
    }

    // a Quick Settings tile: the name written on the tile is typed here
    void actPickTile(final int slot, final String pkg, final String name, String appName) {
        final EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setText(appName);
        et.setSelection(appName.length());
        FrameLayout fl = new FrameLayout(this);
        fl.setPadding(dp(20), dp(8), dp(20), 0);
        fl.addView(et, new FrameLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this).setTitle(t("نام کاشی", "Tile name"))
                .setMessage(t("نامی که روی کاشی در پنل تنظیمات سریع نوشته شده است را وارد کنید.",
                        "Type the name written on the tile in the Quick Settings panel."))
                .setView(fl)
                .setPositiveButton(t("ثبت", "Save"), (d, w) -> {
                    String nm = et.getText().toString().trim();
                    if (nm.isEmpty()) return;
                    actSave(slot, pkg, "tile", nm, "🔲 " + nm, name);
                    if (!TileClickService.ready()) actAskA11y();
                })
                .setNegativeButton(t("لغو", "Cancel"), null).show();
    }

    void actSave(int slot, String pkg, String type, String id, String label, String name) {
        BubbleActs.Act[] acts = BubbleActs.load(sp);
        BubbleActs.Act a = new BubbleActs.Act();
        a.pkg = pkg;
        a.type = type;
        a.id = id;
        a.label = label;
        a.name = name;
        a.ts = System.currentTimeMillis();
        acts[slot] = a;
        BubbleActs.save(sp, acts);
        page = ACTS;
        show();
    }

    void setTile(boolean on) {
        ComponentName cn = new ComponentName(this, VadTile.class);
        getPackageManager().setComponentEnabledSetting(cn,
                on ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
        sp.edit().putBoolean("tile", on).commit();
        if (on) {
            final String manual = t("پنل تنظیمات سریع را باز کنید، ویرایش را بزنید و Vad را اضافه کنید.",
                    "Open Quick Settings, tap edit and add Vad.");
            if (Build.VERSION.SDK_INT >= 33) {
                container.postDelayed(() -> {
                    try {
                        StatusBarManager sbm = (StatusBarManager) getSystemService("statusbar");
                        sbm.requestAddTileService(new ComponentName(this, VadTile.class), "Vad",
                                Icon.createWithResource(this, R.drawable.ic_tile),
                                getMainExecutor(), r -> { });
                    } catch (Exception e) {
                        Toast.makeText(this, manual, Toast.LENGTH_LONG).show();
                    }
                }, 600);
            } else {
                Toast.makeText(this, manual, Toast.LENGTH_LONG).show();
            }
        }
        show();
    }

    // ---------- background pages ----------
    // the main Background option only changes the home page
    View bgPage(final int fg) { return bgPageG(0, fg); }

    // mode 0 = home page, 1 = settings menu, 2 = home frame, 3 = top settings frame
    int curColor(int mode) {
        if (mode == 4) return sp.contains("boxMain") ? sp.getInt("boxMain", 0) : (glass() ? (light() ? 0x44FFFFFF : 0x26FFFFFF) : (light() ? 0x14000000 : 0x1AFFFFFF));
        if (mode == 5) return sp.contains("boxSub") ? sp.getInt("boxSub", 0)
                : scaleA(glass() ? (light() ? 0x44FFFFFF : 0x26FFFFFF) : (light() ? 0x14000000 : 0x1AFFFFFF), 0.6f);
        if (mode == 6) return sp.getInt("bub_lcol", 0xF2202024);
        return mode == 0 ? bg : (mode == 1 ? mbg()
                : (mode == 2 ? sp.getInt("hfBg", 0x6628282C) : sp.getInt("topBoxBg", 0x6628282C)));
    }

    // a picture setting changed (slider released)
    void imgChanged(int mode) {
        if (mode == 0) {
            renderBg();
            applyWindowBg();
        } else if (mode == 1) {
            mbgBmp = renderImg(mbgSrc, "m", mbg());
            View cv = container.getChildAt(container.getChildCount() - 1);
            if (cv != null) cv.setBackground(menuBgDrawable());
        }
    }

    void setColorG(int mode, int c) {
        if (mode == 0) {
            setBg(c);
            return;
        }
        if (mode >= 4) {
            sp.edit().putInt(mode == 4 ? "boxMain" : mode == 5 ? "boxSub" : "bub_lcol", c).apply();
            show();
            return;
        }
        if (mode == 1) {
            sp.edit().putInt("mBg", c).putBoolean("mImg", false).apply();
            mbgSrc = null;
            mbgBmp = null;
        } else if (mode == 2) {
            sp.edit().putInt("hfBg", c).putBoolean("hfImg", false).apply();
            hfSrc = null;
        } else {
            sp.edit().putInt("topBoxBg", c).putBoolean("tbImg", false).apply();
            tbSrc = null;
        }
        show();
    }

    void removeImgG(int mode) {
        if (mode == 0) {
            removeBgImage();
        } else if (mode == 1) {
            sp.edit().putBoolean("mImg", false).apply();
            mbgSrc = null;
            mbgBmp = null;
            show();
        } else if (mode == 2) {
            sp.edit().putBoolean("hfImg", false).apply();
            hfSrc = null;
            show();
        } else {
            sp.edit().putBoolean("tbImg", false).apply();
            tbSrc = null;
            show();
        }
    }

    // the hex code field sits in a box like the other options
    void styleHexField(EditText et, int fg) {
        et.setHintTextColor(0x88888888);
        et.setTextColor(fg);
        et.setSingleLine(true);
        et.setPadding(dp(14), dp(10), dp(14), dp(10));
        et.setBackground(boxBg(light() ? 0x14000000 : 0x1AFFFFFF, BOX_R));
    }

    // mode 0 = home page background, 1 = settings menu color, 2 = home frame background, 3 = top settings frame
    View bgPageG(final int mode, final int fg) {
        final String pre = mode == 0 ? "bg" : (mode == 1 ? "m" : (mode == 2 ? "hf" : (mode == 3 ? "tb" : "bx")));
        final int req = mode == 0 ? REQ_IMG : (mode == 1 ? REQ_MIMG : (mode == 2 ? REQ_FIMG : REQ_TIMG));
        String title = mode == 0 ? t("پس‌زمینه", "Background")
                : (mode == 1 ? t("رنگ منو", "Menu color")
                : (mode == 2 ? t("پس‌زمینه کادر", "Frame background")
                : (mode == 3 ? t("رنگ کادر تنظیمات", "Settings frame color")
                : (mode == 4 ? t("رنگ کادر اصلی", "Main box color") : mode == 5 ? t("رنگ کادر فرعی", "Secondary box color") : t("رنگ لیست عملکرد", "Action list color")))));
        LinearLayout p = vbox();
        p.addView(topBar(title, fg));

        boolean hasImg = mode >= 4 ? false : mode == 0 ? bgBmp != null : (mode == 1 ? mbgBmp != null
                : (mode == 2 ? hfSrc != null : tbSrc != null));
        if (mode < 4) p.addView(row(0xFF26A69A, "🖼", t("عکس پس‌زمینه", "Background image"),
                hasImg ? t("فعال", "Active") : t("انتخاب عکس از گالری", "Pick a photo from the gallery"),
                0, fg, v -> pickImg(req)));
        if (hasImg) {
            // changes are applied to the screen when the slider is released
            Runnable rb = () -> imgChanged(mode);
            p.addView(accordionRow(0xFF607D8B, "🌫", t("مات", "Fade"), pre + "Fade", 0, 98, 0, "%", fg, rb));
            p.addView(accordionGroup(0xFF8BC34A, "✂", t("برش و اندازه عکس", "Crop & size"), fg,
                    slider(t("اندازه", "Zoom"), pre + "Zoom", 100, 300, 100, "%", fg, rb),
                    slider(t("جابه‌جایی افقی", "Horizontal"), pre + "Px", 0, 100, 50, "%", fg, rb),
                    slider(t("جابه‌جایی عمودی", "Vertical"), pre + "Py", 0, 100, 50, "%", fg, rb)));
            p.addView(row(0xFFE53935, "✕", t("حذف عکس", "Remove image"), null, 0, fg,
                    v -> removeImgG(mode)));
        }

        p.addView(labelBox(t("رنگ پس‌زمینه:", "Background color:"), fg));
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout r = new LinearLayout(this);
        final ColorPicker cp = new ColorPicker(this);
        final EditText et = new EditText(this);
        for (final int c : colors) {
            View sw = new View(this);
            GradientDrawable g = oval(c);
            g.setStroke(dp(2), 0x88888888);
            sw.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(44), dp(44));
            lp.setMargins(dp(6), dp(8), dp(6), dp(8));
            sw.setLayoutParams(lp);
            sw.setOnClickListener(v -> setColorG(mode, c));
            r.addView(sw);
        }
        hs.addView(r);
        p.addView(hs);

        p.addView(labelBox(t("انتخاب دلخواه (رنگ و شفافیت):", "Custom color (with transparency):"), fg));
        cp.setColor(curColor(mode));
        cp.setListener(c -> et.setText(hex(c)));
        LinearLayout.LayoutParams cpl = new LinearLayout.LayoutParams(-1, -2);
        cpl.topMargin = dp(8);
        p.addView(cp, cpl);

        LinearLayout r2 = new LinearLayout(this);
        r2.setGravity(Gravity.CENTER_VERTICAL);
        et.setText(hex(curColor(mode)));
        styleHexField(et, fg);
        LinearLayout.LayoutParams el = new LinearLayout.LayoutParams(0, -2, 2);
        el.setMargins(0, dp(8), dp(4), dp(4));
        et.setLayoutParams(el);
        r2.addView(et);
        r2.addView(btn(t("اعمال", "Apply"), fg, v -> {
            try { setColorG(mode, Color.parseColor(et.getText().toString().trim())); }
            catch (Exception e) {
                Toast.makeText(this, t("کد رنگ نامعتبر است", "Invalid color code"),
                        Toast.LENGTH_SHORT).show();
            }
        }));
        p.addView(r2);
        return wrapScroll(p);
    }

    void setBg(int c) {
        bg = c;
        bgBmp = null;
        bgSrc = null;
        sp.edit().putInt("bg", bg).putBoolean("bgImg", false).apply();
        applyWindowBg();
        show();
    }

    // dock background: same tools as the background page (colors, picker, opacity, hex code)
    View dockBgPage(final int fg) {
        LinearLayout p = vbox();
        p.addView(topBar(t("پس‌زمینه داک", "Dock background"), fg));

        p.addView(labelBox(t("رنگ پس‌زمینه داک:", "Dock color:"), fg));
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout r = new LinearLayout(this);
        final ColorPicker cp = new ColorPicker(this);
        final EditText et = new EditText(this);
        for (final int c : colors) {
            View sw = new View(this);
            GradientDrawable g = oval(c);
            g.setStroke(dp(2), 0x88888888);
            sw.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(44), dp(44));
            lp.setMargins(dp(6), dp(8), dp(6), dp(8));
            sw.setLayoutParams(lp);
            sw.setOnClickListener(v -> setDockBg(c));
            r.addView(sw);
        }
        hs.addView(r);
        p.addView(hs);

        p.addView(labelBox(t("انتخاب دلخواه (رنگ و شفافیت):", "Custom color (with transparency):"), fg));
        int cur = sp.getInt("dockBg", 0x40FFFFFF);
        cp.setColor(cur);
        cp.setListener(c -> et.setText(hex(c)));
        LinearLayout.LayoutParams cpl = new LinearLayout.LayoutParams(-1, -2);
        cpl.topMargin = dp(8);
        p.addView(cp, cpl);

        LinearLayout r2 = new LinearLayout(this);
        r2.setGravity(Gravity.CENTER_VERTICAL);
        et.setText(hex(cur));
        styleHexField(et, fg);
        LinearLayout.LayoutParams el = new LinearLayout.LayoutParams(0, -2, 2);
        el.setMargins(0, dp(8), dp(4), dp(4));
        et.setLayoutParams(el);
        r2.addView(et);
        r2.addView(btn(t("اعمال", "Apply"), fg, v -> {
            try { setDockBg(Color.parseColor(et.getText().toString().trim())); }
            catch (Exception e) {
                Toast.makeText(this, t("کد رنگ نامعتبر است", "Invalid color code"),
                        Toast.LENGTH_SHORT).show();
            }
        }));
        p.addView(r2);
        return wrapScroll(p);
    }

    void setDockBg(int c) {
        sp.edit().putInt("dockBg", c).apply();
        show();
    }

    View behaviorPage(final int fg) {
        LinearLayout r = vbox();
        r.addView(topBar(t("رفتار", "Behavior"), fg));
        // apps open inside the launcher as a window: over the home frame only, over top frame + home frame + dock, or full screen
        r.addView(accordionGroupFaded(0xFF43A047, "▶", t("اجرای اپلیکیشن", "Run apps"), fg, 0.6f, () -> new View[]{
                toggleRow(0xFF7E57C2, "▣", t("فقط کادر", "Frame only"),
                        t("برنامه‌ها داخل لانچر و به اندازه کادر آیکون‌های صفحه خانگی باز می‌شوند (نیاز به پنجره آزاد / Freeform در گوشی)",
                                "Apps open inside the launcher, as big as the home icons frame (needs free-form windows on the phone)"),
                        "hfRun", false, fg),
                toggleRow(0xFF00897B, "▤", t("کادر بالا و داک", "Top frame and dock"),
                        t("برنامه‌ها به اندازه مجموع کادر بالا، کادر آیکون‌های صفحه خانگی و کادر داک باز می‌شوند",
                                "Apps open as big as the top frame, the home icons frame and the dock together"),
                        "hfRunAll", false, fg),
                toggleRow(0xFFEF6C00, "▥", t("تمام صفحه", "Full screen"),
                        t("برنامه‌ها به اندازه کل صفحه نمایش، از زیر نوار اعلان اندروید تا بالای نوار پیمایش پایین، باز می‌شوند (نیاز به پنجره آزاد / Freeform در گوشی)",
                                "Apps open as big as the whole display, from below the Android status bar to above the navigation bar (needs free-form windows on the phone)"),
                        "hfRunFull", false, fg)}));
        // what the launcher does when an app opens (lighter glass boxes)
        final int m = sp.getInt("mode", 0);
        r.addView(accordionGroupFaded(0xFF1E88E5, "🚀", t("رفتار لانچر", "Launcher behavior"), fg, 0.6f, () -> new View[]{
                row(0xFF43A047, "🚪", t("رفتن لانچر", "Launcher Goes"),
                        t("با انتخاب برنامه، لانچر بسته می‌شود", "Launcher closes when you open an app"),
                        m == 0 ? 1 : 0, fg, v -> { sp.edit().putInt("mode", 0).commit(); show(); }),
                row(0xFF1E88E5, "📌", t("ماندن لانچر", "Launcher Stays"),
                        t("لانچر بسته نمی‌شود و هنگام برگشت پشت برنامه باز می‌ماند",
                                "Launcher stays open behind the app"),
                        m == 1 ? 1 : 0, fg, v -> { sp.edit().putInt("mode", 1).commit(); show(); }),
                row(0xFFE53935, "🛑", t("بستن لانچر", "Close Launcher"),
                        t("پس از اجرای برنامه، فرآیند لانچر از پس‌زمینه و حافظه رم کاملاً خارج می‌شود",
                                "Terminates the launcher process completely upon app launch"),
                        m == 2 ? 1 : 0, fg, v -> { sp.edit().putInt("mode", 2).commit(); show(); })}));
        r.addView(row(0xFF26A69A, "⚡", t("میانبر", "Shortcut"),
                t("افزودن کلید Vad به پنل تنظیمات سریع", "Add the Vad button to Quick Settings"),
                sp.getBoolean("tile", false) ? 3 : 2, fg,
                v -> setTile(!sp.getBoolean("tile", false))));
        return wrapScroll(r);
    }

    View dockPageView(int fg) {
        LinearLayout r = vbox();
        r.addView(topBar(t("داک", "Dock"), fg));

        boolean on1 = sp.getBoolean("dockOn1", true);
        r.addView(row(0xFF8E24AA, "👁", t("نمایش", "Show"), null, on1 ? 3 : 2, fg,
                v -> { sp.edit().putBoolean("dockOn1", !on1).apply(); show(); }));
        // icon: name on/off, icon size, icon name size (glass boxes, a little fainter)
        r.addView(accordionGroupFaded(0xFF00CCD6, "📱", t("آیکون", "Icon"), fg, 0.6f, () -> new View[]{
                toggleRow(0xFF7E57C2, "🏷", t("اسم آیکون", "Icon name"),
                        t("با فعال بودن، نام آیکون‌ها در داک حذف می‌شود", "When on, app names in the dock are hidden"),
                        "hideDockNames", false, fg),
                accordionRow(0xFF00CCD6, "📱", t("سایز آیکون", "Icon size"), "dockI1", 24, 64, 52, " dp", fg, null),
                accordionRow(0xFFFFB300, "🔤", t("سایز اسم آیکون", "Icon name size"), "dockTs1", 6, 20, 10, " sp", fg, null)}));
        r.addView(row(0xFF5C6BC0, "🎨", t("پس‌زمینه داک", "Dock background"), null, 0, fg,
                v -> { page = DOCKBG; show(); }));
        r.addView(accordionRow(0xFF2196F3, "🔢", t("تعداد اپلیکیشن", "Apps count"), "dockCount1", 4, 10, 4, "", fg, null));
        r.addView(accordionRow(0xFF4CAF50, "📄", t("تعداد صفحه", "Pages count"), "dockPages1", 1, 5, 1, "", fg, null));
        // dock position: positive = up (mm), 0 = now, -1 = lowest (touching the navigation keys)
        r.addView(accordionRow(0xFF00BCD4, "↕", t("جای داک", "Dock position"), "dockY", -1, 10, 0, " mm", fg, null));
        r.addView(accordionRow(0xFFFF9800, "📐", t("ارتفاع داک", "Dock height"), "dockH1", 56, 140, 90, " dp", fg, null));
        r.addView(accordionRow(0xFF9C27B0, "↔", t("عرض داک", "Dock width"), "dockW1", 60, 100, 96, "%", fg, null));
        r.addView(accordionRow(0xFF26C6DA, "🔲", t("لبه‌ها", "Edges"), "dockRound", 0, 100, 20, "", fg, null));

        return wrapScroll(r);
    }

    // icon settings (shared by the icon page and the group inside the home page)
    View[] iconItems(final int fg) {
        List<View> l = new ArrayList<>();
        l.add(accordionRow(0xFF2196F3, "📱", t("سایز آیکون", "Icon size"), "iconSz", 32, 96, 56, " dp", fg, null));
        l.add(accordionRow(0xFF26A69A, "🔤", t("سایز اسم آیکون", "Icon name size"), "nameSz", 6, 20, 12, " sp", fg, null));

        final boolean sync = sp.getBoolean("folderSync", false);
        l.add(row(0xFF66BB6A, "🔗", t("سایز پوشه هماهنگ", "Synced folder size"),
                t("اندازه پوشه‌ها در داک و صفحه اصلی برابر آیکون برنامه‌ها می‌شود",
                        "Folders everywhere match the app icon size"),
                sync ? 3 : 2, fg,
                v -> { sp.edit().putBoolean("folderSync", !sync).apply(); show(); }));

        l.add(accordionRow(0xFF9C27B0, "↔", t("ردیف عمودی", "Vertical rows"), "gridCols", 4, 20, 4, "", fg, null));
        // the first row follows the row spacing too
        // pages off: number of rows from the top to the bottom of the frame (spacing follows); pages on: spacing in mm
        if (pagedMode()) l.add(accordionRow(0xFF607D8B, "↕", t("ردیف افقی", "Horizontal rows"), "pgRows", 4, 20, 7, "", fg, null));
        else l.add(accordionRow(0xFF607D8B, "↕", t("ردیف افقی", "Horizontal rows"), "gridGap", -6, 8, 0, " mm", fg, null));

        final boolean hide = sp.getBoolean("hideNames", false);
        l.add(row(0xFFAB47BC, "🏷", t("حذف نام برنامه", "Hide app names"),
                t("نام آیکون‌ها و پوشه‌ها در صفحه اصلی حذف می‌شود", "Hides names under icons and folders"),
                hide ? 3 : 2, fg,
                v -> { sp.edit().putBoolean("hideNames", !hide).apply(); show(); }));
        return l.toArray(new View[0]);
    }

    View iconPage(int fg) {
        LinearLayout r = vbox();
        r.addView(topBar(t("آیکون و نوار بالا", "Icon & Top Bar"), fg));
        for (View v : iconItems(fg)) r.addView(v);
        return wrapScroll(r);
    }

    View langPage(int fg) {
        LinearLayout r = vbox();
        r.addView(topBar(t("زبان", "Language"), fg));
        r.addView(row(0xFFEC407A, "ف", "فارسی", null, fa ? 1 : 0, fg, v -> setLang("fa")));
        r.addView(row(0xFF42A5F5, "En", "English", null, !fa ? 1 : 0, fg, v -> setLang("en")));
        return r;
    }

    void setLang(String l) {
        sp.edit().putString("lang", l).apply();
        fa = l.equals("fa");
        notifyBar();
        show();
    }

    // ================= backup / restore =================
    File backupDir() {
        return new File(Environment.getExternalStorageDirectory(), "VadLan");
    }

    boolean hasStorage() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE")
                == PackageManager.PERMISSION_GRANTED;
    }

    boolean ensureStorage() {
        if (hasStorage()) return true;
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                } catch (Exception e2) { }
            }
        } else {
            requestPermissions(new String[]{"android.permission.WRITE_EXTERNAL_STORAGE",
                    "android.permission.READ_EXTERNAL_STORAGE"}, 2);
        }
        Toast.makeText(this, t("لطفاً مجوز دسترسی به حافظه را بدهید و دوباره امتحان کنید",
                "Please allow storage access, then try again"), Toast.LENGTH_LONG).show();
        return false;
    }

    // adds a saved picture file to the backup (base64)
    void putFile(JSONObject root, String key, String name) throws Exception {
        File f = new File(getFilesDir(), name);
        if (f.exists()) {
            byte[] data = java.nio.file.Files.readAllBytes(f.toPath());
            root.put(key, Base64.getEncoder().encodeToString(data));
        }
    }

    void getFile(JSONObject root, String key, String name) throws Exception {
        if (root.has(key)) {
            byte[] data = Base64.getDecoder().decode(root.getString(key));
            java.nio.file.Files.write(new File(getFilesDir(), name).toPath(), data);
        }
    }

    String buildBackup() throws Exception {
        JSONObject root = new JSONObject();
        root.put("app", "VadLan");
        root.put("ver", 1);
        JSONObject pr = new JSONObject();
        for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
            String k = e.getKey();
            if (k.equals("open")) continue;
            Object v = e.getValue();
            JSONObject o = new JSONObject();
            if (v instanceof Boolean) { o.put("t", "b"); o.put("v", ((Boolean) v).booleanValue()); }
            else if (v instanceof Integer) { o.put("t", "i"); o.put("v", ((Integer) v).intValue()); }
            else if (v instanceof Long) { o.put("t", "l"); o.put("v", ((Long) v).longValue()); }
            else if (v instanceof Float) { o.put("t", "f"); o.put("v", ((Float) v).doubleValue()); }
            else if (v instanceof String) { o.put("t", "s"); o.put("v", (String) v); }
            else continue;
            pr.put(k, o);
        }
        root.put("prefs", pr);
        if (sp.getBoolean("bgImg", false)) {
            File f = new File(getFilesDir(), "bg_src.jpg");
            if (!f.exists()) f = new File(getFilesDir(), "bg.jpg");
            if (f.exists()) {
                byte[] data = java.nio.file.Files.readAllBytes(f.toPath());
                root.put("bg", Base64.getEncoder().encodeToString(data));
            }
        }
        if (sp.getBoolean("hfImg", false)) putFile(root, "hf", "hf_src.jpg");
        if (sp.getBoolean("mImg", false)) putFile(root, "menu", "menu_src.jpg");
        if (sp.getBoolean("tbImg", false)) putFile(root, "tb", "tb_src.jpg");
        return root.toString();
    }

    void doBackup() {
        if (!ensureStorage()) return;
        try {
            File dir = backupDir();
            if (!dir.exists() && !dir.mkdirs()) throw new Exception("mkdir");
            String name = "VadLan_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".json";
            File out = new File(dir, name);
            java.nio.file.Files.write(out.toPath(), buildBackup().getBytes("UTF-8"));
            Toast.makeText(this, t("پشتیبان ذخیره شد: ", "Backup saved: ") + "VadLan/" + name,
                    Toast.LENGTH_LONG).show();
        } catch (Throwable e) {
            Toast.makeText(this, t("خطا در گرفتن پشتیبان", "Backup failed"), Toast.LENGTH_LONG).show();
        }
    }

    void confirmRestore(final File f) {
        new AlertDialog.Builder(this).setTitle(t("فراخوان پشتیبان", "Restore backup"))
                .setMessage(f.getName() + "\n\n" + t("همه تنظیمات فعلی با این نسخه جایگزین می‌شود.",
                        "All current settings will be replaced by this backup."))
                .setPositiveButton(t("فراخوانی", "Restore"), (d, w) -> restoreFrom(f))
                .setNegativeButton(t("لغو", "Cancel"), null).show();
    }

    void restoreFrom(File f) {
        try {
            String s = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
            JSONObject root = new JSONObject(s);
            if (!"VadLan".equals(root.optString("app"))) throw new Exception("format");
            JSONObject pr = root.getJSONObject("prefs");
            SharedPreferences.Editor ed = sp.edit();
            ed.clear();
            Iterator<String> it = pr.keys();
            while (it.hasNext()) {
                String k = it.next();
                JSONObject o = pr.getJSONObject(k);
                String ty = o.getString("t");
                if (ty.equals("b")) ed.putBoolean(k, o.getBoolean("v"));
                else if (ty.equals("i")) ed.putInt(k, o.getInt("v"));
                else if (ty.equals("l")) ed.putLong(k, o.getLong("v"));
                else if (ty.equals("f")) ed.putFloat(k, (float) o.getDouble("v"));
                else if (ty.equals("s")) ed.putString(k, o.getString("v"));
            }
            ed.putBoolean("open", true);
            ed.commit();
            if (root.has("bg")) {
                byte[] data = Base64.getDecoder().decode(root.getString("bg"));
                java.nio.file.Files.write(new File(getFilesDir(), "bg_src.jpg").toPath(), data);
            }
            getFile(root, "hf", "hf_src.jpg");
            getFile(root, "menu", "menu_src.jpg");
            getFile(root, "tb", "tb_src.jpg");
            // reload everything from the restored settings
            fa = sp.getString("lang", "fa").equals("fa");
            bg = sp.getInt("bg", colors[0]);
            loadBgImage();
            loadMenuImage();
            loadFrameImage();
            applyWindowBg();
            loadHome();
            loadDock();
            fixDocked();
            faceCache.clear();
            mbgBlur = null;
            mbgBlurSrc = null;
            glassBlurSrc = null;
            dockPage1 = 0;
            sel.clear();
            openFolder = null;
            plusOpen = false;
            folderSettings = false;
            try {
                getPackageManager().setComponentEnabledSetting(new ComponentName(this, VadTile.class),
                        sp.getBoolean("tile", false) ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                                : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP);
            } catch (Exception e) { }
            try {
                if (sp.getBoolean("mock_on", false) && Settings.canDrawOverlays(this)) {
                    startMock();
                } else {
                    stopService(new Intent(this, MockBubbleService.class));
                }
            } catch (Exception e) { }
            notifyBar();
            page = SETTINGS;
            show();
            Toast.makeText(this, t("پشتیبان با موفقیت فراخوانی شد", "Backup restored"),
                    Toast.LENGTH_LONG).show();
        } catch (Throwable e) {
            Toast.makeText(this, t("فایل پشتیبان نامعتبر است", "Invalid backup file"),
                    Toast.LENGTH_LONG).show();
        }
    }

    // starts an intent and tells whether some app accepted it
    boolean tryStart(Intent in) {
        try {
            in.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(in);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    // opens the VadLan backup folder in the phone's file manager
    void openBackupFolder() {
        if (!hasStorage()) { ensureStorage(); return; }
        File d = backupDir();
        try { if (!d.exists()) d.mkdirs(); } catch (Exception e) { }
        final String path = d.getAbsolutePath();
        final Uri doc = Uri.parse("content://com.android.externalstorage.documents/document/primary%3AVadLan");
        final Uri tree = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AVadLan");

        // 1) Google Files / Android Files: open the folder directly
        String[] dpk = {"com.google.android.documentsui", "com.android.documentsui", null};
        for (String pk : dpk) {
            Intent a = new Intent(Intent.ACTION_VIEW);
            a.setDataAndType(doc, "vnd.android.document/directory");
            if (pk != null) a.setPackage(pk);
            if (tryStart(a)) return;
        }
        // 2) Samsung My Files
        Intent sm = new Intent("samsung.myfiles.intent.action.LAUNCHMYFILES");
        sm.setClassName("com.sec.android.app.myfiles", "com.sec.android.app.myfiles.external.ui.MainActivity");
        sm.putExtra("samsung.myfiles.intent.extra.START_PATH", path);
        if (tryStart(sm)) return;
        Intent sm2 = new Intent("samsung.myfiles.intent.action.LAUNCHSTORAGE");
        sm2.setPackage("com.sec.android.app.myfiles");
        sm2.putExtra("FOLDERPATH", path);
        if (tryStart(sm2)) return;
        // 3) other file managers
        Intent b = new Intent("android.provider.action.BROWSE");
        b.setData(doc);
        if (tryStart(b)) return;
        Intent b2 = new Intent(Intent.ACTION_VIEW);
        b2.setDataAndType(tree, "vnd.android.document/directory");
        if (tryStart(b2)) return;
        Intent b3 = new Intent(Intent.ACTION_VIEW);
        b3.setDataAndType(Uri.fromFile(d), "resource/folder");
        if (tryStart(b3)) return;
        // 4) system file picker that starts inside the folder
        Intent c = new Intent(Intent.ACTION_GET_CONTENT);
        c.setType("*/*");
        c.addCategory(Intent.CATEGORY_OPENABLE);
        c.putExtra("android.provider.extra.INITIAL_URI", doc);
        if (tryStart(c)) return;
        Toast.makeText(this, t("فایل منیجر پیدا نشد. مسیر: ", "No file manager found. Path: ") + path,
                Toast.LENGTH_LONG).show();
    }

    View backupPage(int fg) {
        LinearLayout r = vbox();
        r.addView(topBar(t("پشتیبان", "Backup"), fg));
        r.addView(row(0xFF43A047, "⬆", t("گرفتن پشتیبان", "Create backup"),
                t("ذخیره همه تنظیمات در پوشه VadLan حافظه اصلی",
                        "Saves all settings to the VadLan folder in main storage"),
                0, fg, v -> doBackup()));
        View rest = row(0xFF1E88E5, "⬇", t("فراخوان پشتیبان", "Restore backup"),
                t("انتخاب از پشتیبان‌های ذخیره‌شده در گوشی (نگه داشتن: باز کردن پوشه در فایل منیجر)",
                        "Choose from backups saved on the phone (hold: open the folder in the file manager)"),
                0, fg, v -> { if (ensureStorage()) { page = RESTORE; show(); } });
        rest.setOnLongClickListener(v -> { vib(25); openBackupFolder(); return true; });
        r.addView(rest);
        return r;
    }

    // backup files picked in the restore list (hold a file to start picking)
    final Set<String> rSel = new HashSet<>();

    void deleteBackups(final List<File> files) {
        new AlertDialog.Builder(this).setTitle(t("حذف پشتیبان", "Delete backups"))
                .setMessage(t("تعداد فایل‌های انتخاب‌شده: ", "Selected files: ") + files.size() + "\n\n"
                        + t("این فایل‌ها برای همیشه حذف می‌شوند.", "These files will be deleted permanently."))
                .setPositiveButton(t("حذف", "Delete"), (d, w) -> {
                    int bad = 0;
                    for (File f : files) { try { if (!f.delete()) bad++; } catch (Throwable e) { bad++; } }
                    rSel.clear();
                    if (bad > 0) Toast.makeText(this, t("حذف بعضی فایل‌ها ممکن نشد", "Some files could not be deleted"), Toast.LENGTH_LONG).show();
                    show();
                })
                .setNegativeButton(t("لغو", "Cancel"), null).show();
    }

    View restorePage(final int fg) {
        LinearLayout r = vbox();
        r.addView(topBar(t("فراخوان پشتیبان", "Restore backup"), fg));
        File[] fs = null;
        try {
            fs = backupDir().listFiles((d, n) -> n.toLowerCase().endsWith(".json"));
        } catch (Exception e) { }
        if (fs == null || fs.length == 0) {
            rSel.clear();
            r.addView(labelBox(t("هیچ نسخه پشتیبانی در پوشه VadLan پیدا نشد", "No backups found in the VadLan folder"), fg));
        } else {
            Arrays.sort(fs, (x, y) -> Long.compare(y.lastModified(), x.lastModified()));
            final List<File> all = new ArrayList<>(Arrays.asList(fs));
            Set<String> names = new HashSet<>();
            for (File f : all) names.add(f.getName());
            rSel.retainAll(names);
            final boolean picking = !rSel.isEmpty();
            if (picking) {
                final List<File> chosen = new ArrayList<>();
                for (File f : all) if (rSel.contains(f.getName())) chosen.add(f);
                r.addView(row(0xFFE53935, "🗑", t("حذف انتخاب‌شده‌ها", "Delete selected") + " (" + chosen.size() + ")",
                        null, 0, fg, v -> deleteBackups(chosen)));
                final boolean allOn = chosen.size() == all.size();
                r.addView(row(0xFF1E88E5, "☑", allOn ? t("برداشتن انتخاب همه", "Deselect all") : t("انتخاب همه", "Select all"),
                        null, 0, fg, v -> {
                    if (allOn) rSel.clear();
                    else for (File f : all) rSel.add(f.getName());
                    show();
                }));
                r.addView(row(0xFF757575, "✕", t("پایان انتخاب", "Done selecting"),
                        null, 0, fg, v -> { rSel.clear(); show(); }));
            }
            for (final File f : all) {
                String when = new SimpleDateFormat("yyyy/MM/dd  HH:mm", Locale.US)
                        .format(new Date(f.lastModified()));
                final String nm = f.getName();
                View row = row(0xFF1E88E5, "💾", nm, when,
                        picking ? (rSel.contains(nm) ? 3 : 2) : 0, fg, v -> {
                    if (!rSel.isEmpty()) {
                        if (!rSel.remove(nm)) rSel.add(nm);
                        show();
                    } else {
                        confirmRestore(f);
                    }
                });
                row.setOnLongClickListener(v -> {
                    vib(25);
                    if (!rSel.remove(nm)) rSel.add(nm);
                    show();
                    return true;
                });
                r.addView(row);
            }
        }
        return wrapScroll(r);
    }

    // ================= picker =================
    void openPicker(String target) {
        PackageManager pm = getPackageManager();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        Set<String> seen = new HashSet<>();
        allApps = new ArrayList<>();
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            String p = ri.activityInfo.packageName;
            if (p.equals(getPackageName()) || !seen.add(p)) continue;
            AppItem a = new AppItem();
            a.pkg = p;
            a.label = ri.loadLabel(pm).toString();
            a.icon = ri.loadIcon(pm);
            allApps.add(a);
        }
        Collections.sort(allApps, (x, y) -> x.label.compareToIgnoreCase(y.label));
        selected.clear();
        query = "";
        pickTarget = target;
        added = new ArrayList<>();
        for (Item it : home) {
            if (it.folder) added.addAll(it.apps);
            else if (target == null) added.add(it.pkg);
        }
        page = PICKER;
        show();
    }

    List<AppItem> visible() {
        List<AppItem> l = new ArrayList<>();
        for (AppItem a : allApps)
            if (query.isEmpty() || a.label.toLowerCase().contains(query)) l.add(a);
        return l;
    }

    boolean allSel() {
        int n = 0;
        for (AppItem a : visible()) {
            if (added.contains(a.pkg)) continue;
            n++;
            if (!selected.contains(a.pkg)) return false;
        }
        return n > 0;
    }

    View pickerPage(final int fg) {
        LinearLayout r = vbox();
        r.addView(topBar(t("افزودن برنامه‌ها", "Add apps"), fg));

        final EditText et = new EditText(this);
        et.setHint(t("جستجو...", "Search..."));
        et.setHintTextColor(0x88888888);
        et.setTextColor(fg);
        et.setSingleLine(true);
        et.setPadding(dp(14), dp(10), dp(14), dp(10));
        GradientDrawable sg = new GradientDrawable();
        sg.setCornerRadius(dp(22));
        sg.setColor(light() ? 0x14000000 : 0x1AFFFFFF);
        et.setBackground(sg);
        et.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable s) {
                query = s.toString().trim().toLowerCase();
                fillList();
                updateTop();
            }
        });
        LinearLayout.LayoutParams el = new LinearLayout.LayoutParams(-1, -2);
        el.setMargins(0, dp(12), 0, 0);
        r.addView(et, el);

        LinearLayout all = new LinearLayout(this);
        all.setGravity(Gravity.CENTER_VERTICAL);
        all.setPadding(dp(4), dp(12), dp(12), dp(12));
        allDot = new View(this);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(22), dp(22));
        dl.setMarginEnd(dp(10));
        all.addView(allDot, dl);
        allText = new TextView(this);
        allText.setTextSize(16);
        allText.setTextColor(fg);
        all.addView(allText);
        all.setOnClickListener(v -> {
            if (allSel()) {
                selected.clear();
            } else {
                for (AppItem a : visible())
                    if (!added.contains(a.pkg)) selected.add(a.pkg);
            }
            fillList();
            updateTop();
        });
        r.addView(all, new LinearLayout.LayoutParams(-2, -2));

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        sv.addView(listBox);
        r.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));

        addBtn = new Button(this);
        addBtn.setAllCaps(false);
        addBtn.setTextColor(0xFFFFFFFF);
        GradientDrawable ag = new GradientDrawable();
        ag.setCornerRadius(dp(22));
        ag.setColor(0xFF3D7BF7);
        addBtn.setBackground(ag);
        addBtn.setOnClickListener(v -> {
            if (selected.isEmpty()) {
                Toast.makeText(this, t("برنامه‌ای انتخاب نشده", "No app selected"),
                        Toast.LENGTH_SHORT).show();
                return;
            }
            if (pickTarget == null) {
                for (String p : selected) {
                    Item a = new Item();
                    a.pkg = p;
                    home.add(a);
                }
            } else {
                Item f = findFolder(pickTarget);
                if (f != null) {
                    for (String p : selected) if (!f.apps.contains(p)) f.apps.add(p);
                    home.removeIf(q -> !q.folder && selected.contains(q.pkg));
                }
            }
            saveHome();
            pickTarget = null;
            page = HOME;
            show();
        });
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, dp(52));
        bl.setMargins(0, dp(10), 0, 0);
        r.addView(addBtn, bl);

        fillList();
        updateTop();
        return r;
    }

    void fillList() {
        listBox.removeAllViews();
        for (final AppItem a : visible()) {
            final boolean isAdded = added.contains(a.pkg);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(8), dp(8), dp(8), dp(8));
            row.addView(styleAppIcon(a.icon, dp(40)));
            TextView tv = new TextView(this);
            tv.setText(a.label);
            tv.setTextSize(16);
            tv.setTextColor(fgc);
            tv.setMaxLines(1);
            tv.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1);
            tl.setMarginStart(dp(12));
            tl.setMarginEnd(dp(12));
            row.addView(tv, tl);
            final TextView tick = new TextView(this);
            tick.setTag("x");
            tick.setText("✓");
            tick.setTextSize(14);
            tick.setGravity(Gravity.CENTER);
            tick.setLayoutParams(new LinearLayout.LayoutParams(dp(26), dp(26)));
            styleTick(tick, isAdded ? 2 : (selected.contains(a.pkg) ? 1 : 0));
            row.addView(tick);
            row.setOnClickListener(v -> {
                if (isAdded) return;
                if (selected.remove(a.pkg)) styleTick(tick, 0);
                else { selected.add(a.pkg); styleTick(tick, 1); }
                updateTop();
            });
            row.setOnLongClickListener(v -> {
                if (!isAdded && !selected.contains(a.pkg)) {
                    selected.add(a.pkg);
                    styleTick(tick, 1);
                    updateTop();
                }
                return true;
            });
            listBox.addView(row);
        }
    }

    void styleTick(TextView tk, int s) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        if (s == 0) {
            g.setColor(0);
            g.setStroke(dp(2), 0xFF888888);
            tk.setTextColor(0);
        } else {
            g.setColor(s == 1 ? 0xFF34C759 : 0xFF8A8A8A);
            tk.setTextColor(0xFFFFFFFF);
        }
        tk.setBackground(g);
    }

    void updateTop() {
        boolean s = allSel();
        allText.setText(s ? t("لغو", "Cancel") : t("همه", "All"));
        GradientDrawable g = oval(s ? 0xFF34C759 : 0);
        if (!s) g.setStroke(dp(2), fgc);
        allDot.setBackground(g);
        addBtn.setText(t("افزودن", "Add") + " (" + selected.size() + ")");
    }
}
