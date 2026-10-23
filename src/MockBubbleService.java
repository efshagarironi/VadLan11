package com.mylauncher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.LinearGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewPropertyAnimator;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.List;

public class MockBubbleService extends Service {
    static MockBubbleService inst;
    static boolean launcherVisible = false;

    static void refresh() {
        if (inst != null) inst.applyVisibility();
    }

    private WindowManager wm;
    private View bubbleView;
    private WindowManager.LayoutParams params;
    private Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences sp;

    private Runnable collapseRunnable = new Runnable() {
        @Override
        public void run() {
            if (listRoot != null) return; // the action list is open: the bubble stays out
            if (backAnim != null) return; // the bubble is gliding back
            if (bubbleView != null && params != null) {
                int sw = getResources().getDisplayMetrics().widthPixels;
                boolean right = sp.getInt("mock_px", 70) > 50;
                int hide = Math.max(30, Math.min(70, sp.getInt("mock_hide", 68)));
                int visible = Math.max(1, params.width * (100 - hide) / 100);
                params.x = right ? sw - visible : -(params.width - visible);
                bubbleView.setAlpha(sp.getInt("mock_alpha", 90) / 100f * 0.55f);
                try { wm.updateViewLayout(bubbleView, params); } catch (Exception e) {}
            }
        }
    };

    // how long the bubble waits before it folds into the side (50 ms to 2 s)
    private int collapseDelay() {
        return Math.max(50, Math.min(2000, sp.getInt("mock_speed", 2000)));
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        inst = this;
        sp = getSharedPreferences("l", 0);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        ensureForeground();
    }

    // keeps the service alive so the system does not close the bubble
    private void ensureForeground() {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel("m", "VadLan Mock",
                    NotificationManager.IMPORTANCE_MIN));
            Notification n = new Notification.Builder(this, "m")
                    .setSmallIcon(R.drawable.ic_tile)
                    .setContentTitle("VadLan Mock")
                    .setOngoing(true)
                    .build();
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(2, n);
            }
        } catch (Throwable e) { }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ensureForeground();
        if (bubbleView == null) createBubble();
        else updateParams();
        return START_STICKY;
    }

    private void applyPosition() {
        if (params == null) return;
        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        params.x = (sw - params.width) * sp.getInt("mock_px", 70) / 100;
        params.y = (sh - params.height) * sp.getInt("mock_py", 40) / 100;
    }

    private void applyVisibility() {
        if (bubbleView == null) return;
        boolean over = sp.getBoolean("mock_over", true);
        bubbleView.setVisibility((over || launcherVisible) ? View.VISIBLE : View.GONE);
    }

    public void updateParams() {
        if (params == null || bubbleView == null) return;
        int size = dpToPx(sp.getInt("mock_size", 56));
        params.width = size;
        params.height = size;
        applyPosition();
        bubbleView.setAlpha(sp.getInt("mock_alpha", 90) / 100f);
        applyVisibility();
        try { wm.updateViewLayout(bubbleView, params); } catch (Exception e) {}
        handler.removeCallbacks(collapseRunnable);
        handler.postDelayed(collapseRunnable, collapseDelay());
    }

    private void openLauncher(boolean settings) {
        Intent i = new Intent(MockBubbleService.this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (settings) i.putExtra("open_mock_settings", true);
        startActivity(i);
    }

    // ---------- bubble tap: launcher and / or the vertical list of app actions ----------
    private FrameLayout listRoot;      // the list window (null = closed)
    private LinearLayout listCard;
    private WindowManager.LayoutParams listParams;
    private long lastOutside = 0, downAt = 0;

    private boolean fa() { return sp.getString("lang", "fa").equals("fa"); }

    private String tr(String f, String e) { return fa() ? f : e; }

    private void onBubbleTap() {
        if (listRoot != null) { closeList(); return; }
        // this very touch just closed the list (touch outside the list = on the bubble): do not open it again
        if (Math.abs(downAt - lastOutside) < 250) return;
        boolean lau = sp.getBoolean("bub_launcher", true);
        boolean act = sp.getBoolean("bub_act_on", false);
        boolean has = BubbleActs.pkgs(BubbleActs.load(sp)).size() > 0;
        if (act && has) {
            dropSquash();
            openList(lau);
        } else if (lau) {
            openLauncher(false);
        }
    }

    private ValueAnimator backAnim;

    // after a swipe the bubble glides back to its own place with a soft, quick spring
    private void springBack(final int tx0, final int ty0, final Runnable after) {
        if (params == null || bubbleView == null) return;
        if (backAnim != null) backAnim.cancel();
        final int fx0 = params.x, fy0 = params.y;
        final ValueAnimator an = ValueAnimator.ofFloat(0f, 1f);
        backAnim = an;
        an.setDuration(200);
        an.setInterpolator(new OvershootInterpolator(1.1f));
        an.addUpdateListener(a -> {
            if (bubbleView == null) return;
            float t = (Float) a.getAnimatedValue();
            params.x = fx0 + Math.round((tx0 - fx0) * t);
            params.y = fy0 + Math.round((ty0 - fy0) * t);
            try { wm.updateViewLayout(bubbleView, params); } catch (Exception e) { }
        });
        an.addListener(new AnimatorListenerAdapter() {
            boolean cancelled;
            @Override
            public void onAnimationCancel(Animator x) { cancelled = true; }
            @Override
            public void onAnimationEnd(Animator x) {
                if (backAnim != an) return;
                backAnim = null;
                if (params != null) { params.x = tx0; params.y = ty0; }
                if (bubbleView != null) {
                    try { wm.updateViewLayout(bubbleView, params); } catch (Exception e) { }
                }
                handler.removeCallbacks(collapseRunnable);
                if (listRoot == null) handler.postDelayed(collapseRunnable, collapseDelay());
                if (after != null && !cancelled) after.run();
            }
        });
        an.start();
    }

    // the bubble is pressed and pulled like a water drop, then springs back
    private void dropSquash() {
        final View b = bubbleView;
        if (b == null) return;
        b.animate().cancel();
        b.setPivotX(b.getWidth() / 2f);
        b.setPivotY(b.getHeight() / 2f);
        b.animate().scaleX(1.16f).scaleY(0.78f).setDuration(110)
                .withEndAction(() -> b.animate().scaleX(1f).scaleY(1f).setDuration(520)
                        .setInterpolator(new OvershootInterpolator(4f)).start()).start();
    }

    // dragging the bubble up / left / down / right opens the app chosen for that direction
    private void runSwipe(float dx, float dy) {
        if (Math.abs(dy) >= Math.abs(dx) && dy < 0) {
            openLauncher(false);   // up: the VadLan app itself
            return;
        }
        final String d = Math.abs(dy) >= Math.abs(dx) ? "down" : (dx < 0 ? "left" : "right");
        final boolean fa = fa();
        if (sp.getBoolean("swai_" + d, false)) {
            // AI: a list like the action list, with the AI actions in the chosen order
            List<LItem> l = new java.util.ArrayList<>();
            for (final int id : BubbleActs.aiOrder(sp, d)) {
                LItem it = new LItem();
                it.pkg = BubbleActs.aiPkg(id);
                it.text = BubbleActs.aiName(fa, id);
                it.click = () -> { closeList(); runAiItem(d, id); };
                l.add(it);
            }
            dropSquash();
            openCustomList(l);
            return;
        }
        if ("list".equals(sp.getString("swm_" + d, ""))) {
            // a list of apps, in the look of the action list
            List<LItem> l = new java.util.ArrayList<>();
            for (final String[] e : BubbleActs.swList(sp, d)) {
                if (getPackageManager().getLaunchIntentForPackage(e[0]) == null) continue;
                LItem it = new LItem();
                it.pkg = e[0];
                it.text = BubbleActs.appLabel(this, e[0]);
                it.click = () -> { closeList(); runApp(e[0], "popup".equals(e[1])); };
                l.add(it);
            }
            if (!l.isEmpty()) {
                dropSquash();
                openCustomList(l);
            }
            return;
        }
        String pkg = sp.getString("swipe_" + d, "");
        if (pkg == null || pkg.isEmpty()) return;
        runApp(pkg, "popup".equals(sp.getString("swipe_" + d + "_w", "full")));
    }

    private void runApp(String pkg, boolean popup) {
        if (!BubbleActs.openApp(this, pkg, popup))
            Toast.makeText(this, tr("برنامه پیدا نشد", "App not found"), Toast.LENGTH_SHORT).show();
    }

    private void runAiItem(String d, int id) {
        boolean popup = "popup".equals(BubbleActs.aiWin(sp, d, id));
        if (id == 5) { aiScreen(popup); return; }
        if (!BubbleActs.runAi(this, id, popup))
            Toast.makeText(this, tr("برنامه نصب نیست", "The app is not installed"), Toast.LENGTH_SHORT).show();
    }

    // the screen is pictured (without the bubble) and handed to Gemini
    private void aiScreen(final boolean popup) {
        if (getPackageManager().getLaunchIntentForPackage(BubbleActs.GEM) == null) {
            Toast.makeText(this, tr("برنامه نصب نیست", "The app is not installed"), Toast.LENGTH_SHORT).show();
            return;
        }
        if (!TileClickService.ready()) { BubbleActs.needA11y(this); return; }
        if (Build.VERSION.SDK_INT < 30) {
            Toast.makeText(this, tr("تحلیل صفحه به اندروید ۱۱ یا بالاتر نیاز دارد", "Screen analysis needs Android 11 or newer"),
                    Toast.LENGTH_SHORT).show();
            BubbleActs.openApp(this, BubbleActs.GEM, popup);
            return;
        }
        if (bubbleView != null) bubbleView.setVisibility(View.INVISIBLE);
        long wait = (sp.getInt("bub_fx", 6) == 0 ? 0 : Math.max(80, Math.min(1500, sp.getInt("bub_fxms", 300)) / 2)) + 250;
        handler.postDelayed(() -> TileClickService.shotAndShare(this, BubbleActs.GEM, popup,
                tr("این صفحه را تحلیل کن", "Analyze this screen"), () -> applyVisibility()), wait);
    }

    private boolean listWithLauncher = false;

    // one box of a list that is not the action list (the lists of the bubble moves)
    static class LItem {
        String dot;
        int dotColor;
        String pkg;
        String text;
        Runnable click;
    }

    private List<LItem> customItems = null;

    private void openList(boolean withLauncher) {
        customItems = null;
        openListCore(withLauncher);
    }

    private void openCustomList(List<LItem> items) {
        customItems = items;
        openListCore(false);
    }

    private void openListCore(boolean withLauncher) {
        listWithLauncher = withLauncher;
        handler.removeCallbacks(collapseRunnable);
        applyPosition();
        bubbleView.setAlpha(sp.getInt("mock_alpha", 90) / 100f);
        try { wm.updateViewLayout(bubbleView, params); } catch (Exception e) {}

        listRoot = new FrameLayout(this) {
            @Override
            public boolean dispatchTouchEvent(MotionEvent ev) {
                if (ev.getAction() == MotionEvent.ACTION_OUTSIDE) {
                    lastOutside = SystemClock.uptimeMillis();
                    closeList();
                    return true;
                }
                return super.dispatchTouchEvent(ev);
            }
        };
        listRoot.setLayoutDirection(fa() ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        final boolean sep = sp.getBoolean("bub_sep", false);
        final boolean glass = sp.getBoolean("bub_lglass", false);
        if (sep) listRoot.setBackgroundColor(0);   // separate boxes: every row has its own background
        else listRoot.setBackground(boxDrawable(sp.getInt("bub_lcol", 0xF2202024), glass, dpToPx(16)));
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        listCard = new LinearLayout(this);
        listCard.setOrientation(LinearLayout.VERTICAL);
        int cpad = sep ? 0 : dpToPx(6);
        listCard.setPadding(cpad, cpad, cpad, cpad);
        sv.addView(listCard);
        listRoot.addView(sv, new FrameLayout.LayoutParams(-1, -1));

        listParams = new WindowManager.LayoutParams(
                -2, -2, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        listParams.gravity = Gravity.TOP | Gravity.LEFT;
        if (glass && !sep && Build.VERSION.SDK_INT >= 31) {
            try {
                listParams.flags |= WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
                listParams.setBlurBehindRadius(dpToPx(24));
            } catch (Throwable e) { }
        }
        fillList();
        if (sp.getInt("bub_fx", 6) != 0) listRoot.setAlpha(0f);   // the opening effect shows it
        layoutList(true);
        playOpen();
    }

    // opening effect of the action list (0 none, 1 fade, 2 slide from the side, 3 grow from the bubble, 5 slide from the top, 6 stretchy drop)
    private void playOpen() {
        final FrameLayout r = listRoot;
        final int fx = sp.getInt("bub_fx", 6);
        if (r == null || fx == 0) return;
        final long ms = Math.max(100, Math.min(1500, sp.getInt("bub_fxms", 300)));
        final boolean right = sp.getInt("mock_px", 70) > 50;
        r.post(() -> {
            if (listRoot != r) return;
            int w = Math.max(1, r.getWidth()), h = Math.max(1, r.getHeight());
            ViewPropertyAnimator an = r.animate().setDuration(ms);
            if (fx == 2) {
                r.setTranslationX((right ? 1 : -1) * (w + dpToPx(16)));
                r.setAlpha(1f);
                an.translationX(0).setInterpolator(new DecelerateInterpolator(2f));
            } else if (fx == 3) {
                r.setPivotX(right ? w : 0);
                r.setPivotY(0);
                r.setScaleX(0.1f);
                r.setScaleY(0.1f);
                an.scaleX(1f).scaleY(1f).alpha(1f).setInterpolator(new DecelerateInterpolator());
            } else if (fx == 6) {
                // a drop that stretches out of the bubble, wobbles and settles
                float bubCx = params.x + params.width / 2f;
                float px = Math.max(0f, Math.min(w, bubCx - listParams.x));
                boolean above = listParams.y < params.y;
                r.setPivotX(px);
                r.setPivotY(above ? h : 0);
                r.setScaleX(0.3f);
                r.setScaleY(0.05f);
                r.setAlpha(1f);
                ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
                va.setDuration(Math.max(450, ms * 2));
                va.addUpdateListener(a -> {
                    if (listRoot != r) return;
                    float t = (Float) a.getAnimatedValue();
                    float e = 1f - (float) Math.pow(1f - t, 3);
                    float y = 1f - (float) (Math.exp(-5f * t) * Math.cos(9f * t));
                    y = Math.max(0.05f, y);
                    float x = (0.3f + 0.7f * e) - 0.6f * Math.max(0f, y - 1f);
                    r.setScaleX(Math.max(0.2f, x));
                    r.setScaleY(y);
                });
                va.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator an2) {
                        r.setScaleX(1f);
                        r.setScaleY(1f);
                    }
                });
                va.start();
                return;
            } else if (fx == 5) {
                r.setTranslationY(-(h + dpToPx(24)));
                r.setAlpha(1f);
                an.translationY(0).setInterpolator(new DecelerateInterpolator(2f));
            } else {
                an.alpha(1f);
            }
            an.start();
        });
    }

    private void closeList() {
        final FrameLayout old = listRoot;
        listRoot = null;
        listCard = null;
        customItems = null;
        if (old != null) {
            final int fx = sp.getInt("bub_fx", 6);
            if (fx == 0) {
                try { wm.removeView(old); } catch (Exception e) {}
            } else {
                // a short fade-out, then the window goes
                old.animate().alpha(0f).setDuration(Math.max(80, Math.min(1500, sp.getInt("bub_fxms", 300)) / 2))
                        .withEndAction(() -> { try { wm.removeView(old); } catch (Exception e) {} }).start();
            }
        }
        handler.removeCallbacks(collapseRunnable);
        handler.postDelayed(collapseRunnable, collapseDelay());
    }

    // measures the list and puts it below the bubble, on the same side of the screen
    private void layoutList(boolean add) {
        if (listRoot == null || params == null) return;
        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        int w = Math.min(dpToPx(Math.max(15, Math.min(200, sp.getInt("bub_lw", 190)))), sw - dpToPx(16));
        listCard.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        final boolean sepL = sp.getBoolean("bub_sep", false);
        // separate boxes: the height lever sizes every box; otherwise it limits the height of the whole list
        int maxH = sepL ? sh : sh * Math.max(20, Math.min(100, sp.getInt("bub_lh", 100))) / 100;
        int h = Math.min(listCard.getMeasuredHeight(), maxH);
        // "number of boxes": only that many rows are visible, the rest wait below (or above) and scroll in
        int cnt = Math.max(2, Math.min(20, sp.getInt("bub_cnt", 20)));
        if (listCard.getChildCount() > cnt) {
            int hc = listCard.getPaddingTop() + listCard.getPaddingBottom();
            for (int i = 0; i < cnt; i++) {
                View cv = listCard.getChildAt(i);
                LinearLayout.LayoutParams clp = (LinearLayout.LayoutParams) cv.getLayoutParams();
                hc += cv.getMeasuredHeight() + clp.topMargin + clp.bottomMargin;
            }
            h = Math.min(h, hc);
        }
        int gap = dpToPx(2) - Math.round(params.height * 0.06f), edge = dpToPx(24);
        int y = params.y + params.height + gap;
        int room = sh - y - edge;
        if (room < Math.min(h, dpToPx(160)) && params.y > room) {
            // not enough room below the bubble: open above it
            room = params.y - gap - edge;
            h = Math.min(h, room);
            y = params.y - gap - h;
        } else {
            h = Math.min(h, room);
        }
        boolean right = sp.getInt("mock_px", 70) > 50;
        listParams.width = w;
        listParams.height = sepL ? Math.max(1, h) : Math.max(dpToPx(60), h);
        listParams.x = right ? sw - w - dpToPx(8) : dpToPx(8);
        listParams.y = Math.max(edge, y);
        try {
            if (add) wm.addView(listRoot, listParams);
            else wm.updateViewLayout(listRoot, listParams);
        } catch (Exception e) {}
    }

    private GradientDrawable boxDrawable(int col, boolean glass, int rPx) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(rPx);
        g.setColor(col);
        g.setStroke(dpToPx(1), glass ? 0x66FFFFFF : 0x33FFFFFF);
        return g;
    }

    // gap between separate boxes: micrometers (negative = overlap)
    private int gapPx() {
        float um = sp.getInt("bub_gap", 800);
        return (int) android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_MM, um / 1000f,
                getResources().getDisplayMetrics());
    }

    private TextView listText(String s, float sp14, int color) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(sp14);
        tv.setTextColor(color);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        return tv;
    }

    // one box of the list: optional dot, the app icon and / or the name
    private void addListItem(String dot, int dotColor, String pkg, String text, Runnable click) {
        final boolean sep = sp.getBoolean("bub_sep", false);
        boolean showIco = sp.getBoolean("bub_ico", false) && pkg != null;
        boolean showNm = sp.getBoolean("bub_nm", true) || !showIco;   // the icon and the name are never both off
        int ts = Math.max(10, Math.min(28, sp.getInt("bub_lts", 15)));
        int isz = dpToPx(Math.max(5, Math.min(32, sp.getInt("bub_isz", 20))));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        if (showIco) row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);   // the name sits to the right of its icon
        int content = Math.max(showNm ? (int) (dpToPx(ts) * 1.5f) : 0, showIco ? isz + dpToPx(6) : 0);
        int vp = Math.max(dpToPx(6), dpToPx(11) * ts / 15);
        row.setPadding(dpToPx(12), sep ? 0 : vp, dpToPx(12), sep ? 0 : vp);
        int boxH = 0;
        if (sep) {
            // one box per action: the height lever sizes all boxes together
            int lh = Math.max(20, Math.min(100, sp.getInt("bub_lh", 100)));
            boxH = Math.max(dpToPx(8) + dpToPx(52) * lh / 100, content);
            row.setBackground(boxDrawable(sp.getInt("bub_lcol", 0xF2202024), sp.getBoolean("bub_lglass", false), dpToPx(14)));
        }
        if (dot != null) {
            TextView d = listText(dot, 12, dotColor);
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-2, -2);
            dl.setMarginEnd(dpToPx(8));
            row.addView(d, dl);
        }
        if (showIco) {
            ImageView iv = new ImageView(this);
            try { iv.setImageDrawable(getPackageManager().getApplicationIcon(pkg)); } catch (Throwable e) { }
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(isz, isz);
            if (showNm) il.setMarginEnd(dpToPx(8));
            row.addView(iv, il);
        }
        if (showNm) {
            TextView nameTv = listText(text, ts, 0xFFFFFFFF);
            if (showIco) nameTv.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            row.addView(nameTv, new LinearLayout.LayoutParams(0, -2, 1));
        } else {
            row.setGravity(Gravity.CENTER);
        }
        row.setOnTouchListener((v, ev) -> {
            int a = ev.getAction();
            if (sep) {
                if (a == MotionEvent.ACTION_DOWN) v.setAlpha(0.65f);
                else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) v.setAlpha(1f);
            } else {
                if (a == MotionEvent.ACTION_DOWN) v.setBackgroundColor(0x33FFFFFF);
                else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) v.setBackgroundColor(0);
            }
            return false;
        });
        row.setOnClickListener(v -> click.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, sep ? boxH : -2);
        if (sep && listCard.getChildCount() > 0) lp.topMargin = gapPx();
        listCard.addView(row, lp);
    }

    // one box for every saved action, named with the custom name
    private void fillList() {
        if (listCard == null) return;
        listCard.removeAllViews();
        if (customItems != null) {
            for (LItem it : customItems) addListItem(it.dot, it.dotColor, it.pkg, it.text, it.click);
            return;
        }
        final BubbleActs.Act[] all = BubbleActs.load(sp);
        if (listWithLauncher) {
            addListItem(null, 0, getPackageName(), "🏠 " + tr("لانچر", "Launcher"), () -> {
                closeList();
                openLauncher(false);
            });
        }
        for (int i = 0; i < all.length; i++) {
            final BubbleActs.Act a = all[i];
            if (a == null) continue;
            final int slot = i;
            final boolean tile = "tile".equals(a.type);
            boolean on = tile && sp.getBoolean("bub_on_s" + i, false);
            addListItem(tile ? "●" : null, on ? 0xFF4CAF50 : 0x55FFFFFF, a.pkg, BubbleActs.title(this, a), () -> {
                BubbleActs.Act cur = BubbleActs.load(sp)[slot];
                if (cur != null) {
                    if (BubbleActs.run(this, cur)) {
                        // a tile pressed again toggles back (connect / disconnect)
                        if ("tile".equals(cur.type))
                            sp.edit().putBoolean("bub_on_s" + slot, !sp.getBoolean("bub_on_s" + slot, false)).apply();
                    } else if (!"tile".equals(cur.type) && !"macro".equals(cur.type)) {
                        Toast.makeText(this, tr("اجرای عملکرد ممکن نشد", "Could not run the action"), Toast.LENGTH_SHORT).show();
                    }
                }
                closeList();
            });
        }
    }

    private void createBubble() {
        int size = dpToPx(sp.getInt("mock_size", 56));
        params = new WindowManager.LayoutParams(
                size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.LEFT;
        applyPosition();

        bubbleView = new View(this) {
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            final int[] cols = {0xFFFBBC00, 0xFFFC9A00, 0xFFFF5608, 0xFFFF2813, 0xFFA41A4A, 0xFF8600B2,
                    0xFF3D00A5, 0xFF0044FF, 0xFF0090CC, 0xFF69B030, 0xFFD3EA2B, 0xFFFFFF33};
            @Override
            protected void onDraw(Canvas canvas) {
                float cx = getWidth() / 2f, cy = getHeight() / 2f;
                float R = cx * 0.84f;            // outer radius of the ring
                float st = R * 0.36f;            // thickness of the ring
                float rm = R - st / 2f;          // middle radius
                RectF oval = new RectF(cx - rm, cy - rm, cx + rm, cy + rm);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeCap(Paint.Cap.BUTT);
                paint.setShader(null);
                // soft shadow under the ring
                paint.setStrokeWidth(st);
                paint.setColor(0x33000000);
                canvas.drawCircle(cx + st * 0.1f, cy + st * 0.2f, rm, paint);
                // the twelve colours of the colour wheel
                for (int i = 0; i < 12; i++) {
                    paint.setColor(cols[i]);
                    canvas.drawArc(oval, -105f + i * 30f, 30.5f, false, paint);
                }
                // round (tube) shading: dark outer and inner edge, bright line in the middle
                float e = st * 0.3f;
                paint.setStrokeWidth(e);
                paint.setColor(0x44000000);
                canvas.drawCircle(cx, cy, R - e / 2f, paint);
                paint.setColor(0x38000000);
                canvas.drawCircle(cx, cy, R - st + e / 2f, paint);
                paint.setStrokeWidth(st * 0.22f);
                paint.setColor(0x40FFFFFF);
                canvas.drawCircle(cx, cy, rm, paint);
                // light from the top left, shade at the bottom right
                paint.setStrokeWidth(st);
                paint.setShader(new LinearGradient(cx - R, cy - R, cx + R, cy + R,
                        new int[]{0x66FFFFFF, 0x00FFFFFF, 0x00000000, 0x66000000},
                        new float[]{0f, 0.4f, 0.6f, 1f}, Shader.TileMode.CLAMP));
                canvas.drawCircle(cx, cy, rm, paint);
                paint.setShader(null);
                // thin bright rim
                float rw = Math.max(1f, st * 0.07f);
                paint.setStrokeWidth(rw);
                paint.setColor(0x55FFFFFF);
                canvas.drawCircle(cx, cy, R - rw / 2f, paint);
                paint.setStyle(Paint.Style.FILL);
            }
        };
        bubbleView.setAlpha(sp.getInt("mock_alpha", 90) / 100f);

        bubbleView.setOnTouchListener(new View.OnTouchListener() {
            int ix, iy;
            float tx, ty, lastDx, lastDy;
            boolean moved, longFired, unlocked, gesture;
            Runnable longRun = new Runnable() {
                @Override
                public void run() {
                    if (!moved && !gesture) {
                        // held still for one second: the bubble is unlocked and can be moved
                        unlocked = true;
                        longFired = true;
                        try {
                            android.os.Vibrator vb = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
                            if (vb != null) vb.vibrate(android.os.VibrationEffect.createOneShot(30,
                                    android.os.VibrationEffect.DEFAULT_AMPLITUDE));
                        } catch (Throwable e) { }
                        if (listRoot != null) closeList();
                        bubbleView.animate().scaleX(1.12f).scaleY(1.12f).setDuration(120).start();
                    }
                }
            };

            @Override
            public boolean onTouch(View v, MotionEvent ev) {
                switch (ev.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        if (backAnim != null) { backAnim.cancel(); backAnim = null; }
                        handler.removeCallbacks(collapseRunnable);
                        bubbleView.setAlpha(sp.getInt("mock_alpha", 90) / 100f);
                        applyPosition();
                        try { wm.updateViewLayout(bubbleView, params); } catch (Exception e) {}
                        ix = params.x;
                        iy = params.y;
                        tx = ev.getRawX();
                        ty = ev.getRawY();
                        moved = false;
                        longFired = false;
                        unlocked = false;
                        gesture = false;
                        lastDx = 0;
                        lastDy = 0;
                        downAt = SystemClock.uptimeMillis();
                        handler.postDelayed(longRun, 1000);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = ev.getRawX() - tx, dy = ev.getRawY() - ty;
                        lastDx = dx;
                        lastDy = dy;
                        if (!unlocked && !gesture && (Math.abs(dx) > dpToPx(8) || Math.abs(dy) > dpToPx(8))) {
                            // the bubble is locked: dragging it is a swipe, it does not move
                            gesture = true;
                            handler.removeCallbacks(longRun);
                            if (listRoot != null) closeList();
                        }
                        if (unlocked && !moved && (Math.abs(dx) > dpToPx(8) || Math.abs(dy) > dpToPx(8))) {
                            moved = true;
                            if (listRoot != null) closeList();
                        }
                        if (moved || gesture) {
                            params.x = ix + (int) dx;
                            params.y = iy + (int) dy;
                            try { wm.updateViewLayout(bubbleView, params); } catch (Exception e) {}
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        handler.removeCallbacks(longRun);
                        if (unlocked) bubbleView.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                        int sw = getResources().getDisplayMetrics().widthPixels;
                        int sh = getResources().getDisplayMetrics().heightPixels;
                        if (gesture && !moved) {
                            // the bubble glides back at once; the app starts only when it is home (opening the launcher
                            // on the main thread would freeze the glide for a moment)
                            final float sdx = lastDx, sdy = lastDy;
                            boolean fire = ev.getAction() == MotionEvent.ACTION_UP
                                    && Math.max(Math.abs(sdx), Math.abs(sdy)) > dpToPx(28);
                            springBack(ix, iy, fire ? new Runnable() {
                                @Override
                                public void run() { runSwipe(sdx, sdy); }
                            } : null);
                        } else if (moved) {
                            int px = (int) (params.x * 100f / Math.max(1, sw - params.width));
                            int py = (int) (params.y * 100f / Math.max(1, sh - params.height));
                            px = Math.max(0, Math.min(100, px));
                            py = Math.max(0, Math.min(100, py));
                            sp.edit().putInt("mock_px", px).putInt("mock_py", py).apply();
                        } else if (!longFired && ev.getAction() == MotionEvent.ACTION_UP) {
                            onBubbleTap();
                        }
                        if (listRoot == null) handler.postDelayed(collapseRunnable, collapseDelay());
                        return true;
                }
                return false;
            }
        });

        try {
            wm.addView(bubbleView, params);
            applyVisibility();
            handler.postDelayed(collapseRunnable, collapseDelay());
        } catch (Exception e) {}
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        inst = null;
        handler.removeCallbacksAndMessages(null);
        if (listRoot != null && wm != null) {
            try { wm.removeView(listRoot); } catch (Exception e) {}
            listRoot = null;
        }
        if (bubbleView != null && wm != null) {
            try { wm.removeView(bubbleView); } catch (Exception e) {}
        }
        bubbleView = null;
        try { stopForeground(true); } catch (Exception e) {}
    }
}
