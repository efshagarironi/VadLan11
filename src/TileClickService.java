package com.mylauncher;

import android.accessibilityservice.AccessibilityService;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.TextView;
import android.widget.Toast;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

// opens Quick Settings, taps the tile with the given name and closes the panel again
public class TileClickService extends AccessibilityService {
    static TileClickService inst;
    private final Handler h = new Handler(Looper.getMainLooper());
    private String want = "";
    private int tries = 0;
    private boolean busy = false;

    static boolean ready() { return inst != null; }

    // takes a picture of the screen and hands it to the given app (Android 11 and later)
    static void shotAndShare(final android.content.Context c, final String pkg, final boolean popup,
                             final String prompt, final Runnable done) {
        if (inst == null || Build.VERSION.SDK_INT < 30) {
            if (done != null) done.run();
            return;
        }
        try {
            inst.takeScreenshot(android.view.Display.DEFAULT_DISPLAY, c.getMainExecutor(),
                    new AccessibilityService.TakeScreenshotCallback() {
                @Override
                public void onSuccess(AccessibilityService.ScreenshotResult r) {
                    if (done != null) done.run();
                    try {
                        Bitmap hw = Bitmap.wrapHardwareBuffer(r.getHardwareBuffer(), r.getColorSpace());
                        Bitmap bmp = hw.copy(Bitmap.Config.ARGB_8888, false);
                        r.getHardwareBuffer().close();
                        ContentValues cv = new ContentValues();
                        cv.put(MediaStore.Images.Media.DISPLAY_NAME, "vadlan_screen_" + System.currentTimeMillis() + ".png");
                        cv.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                        cv.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/VadLan");
                        Uri u = c.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                        OutputStream os = c.getContentResolver().openOutputStream(u);
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
                        os.close();
                        Intent i = new Intent(Intent.ACTION_SEND).setType("image/png")
                                .putExtra(Intent.EXTRA_STREAM, u)
                                .putExtra(Intent.EXTRA_TEXT, prompt)
                                .setPackage(pkg)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        if (popup) c.startActivity(i, BubbleActs.popupOpts(c).toBundle());
                        else c.startActivity(i);
                    } catch (Throwable e) {
                        Toast.makeText(c, BubbleActs.fa(c) ? "ارسال تصویر صفحه ممکن نشد" : "Could not send the screenshot",
                                Toast.LENGTH_SHORT).show();
                    }
                }

                @Override
                public void onFailure(int code) {
                    if (done != null) done.run();
                    Toast.makeText(c, BubbleActs.fa(c) ? "گرفتن تصویر صفحه ممکن نشد؛ سرویس دسترسی‌پذیری را یک بار خاموش و روشن کنید"
                            : "Could not take the screenshot; turn the accessibility service off and on once",
                            Toast.LENGTH_LONG).show();
                }
            });
        } catch (Throwable e) {
            if (done != null) done.run();
        }
    }

    static boolean click(String name) {
        if (inst == null || name == null || name.trim().isEmpty()) return false;
        inst.start(name.trim());
        return true;
    }

    @Override
    protected void onServiceConnected() { inst = this; }

    @Override
    public boolean onUnbind(Intent i) {
        inst = null;
        return super.onUnbind(i);
    }

    @Override
    public void onDestroy() {
        inst = null;
        super.onDestroy();
    }

    // ---------- recording the taps made inside an app ----------
    private String recPkg = null;
    private int recSlot = -1;
    private JSONArray recSteps = new JSONArray();
    private String lastStep = "";
    private long lastStepAt = 0;
    private TextView pill;

    private boolean fa() { return getSharedPreferences("l", 0).getString("lang", "fa").equals("fa"); }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    static boolean record(String pkg, int slot) {
        if (inst == null) return false;
        inst.startRecord(pkg, slot);
        return true;
    }

    private void launch(String pkg) {
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
            if (i != null) {
                // a fresh start, so every recording and every replay begins on the first screen of the app
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(i);
            }
        } catch (Throwable e) { }
    }

    private void startRecord(String pkg, int slot) {
        recPkg = pkg;
        recSlot = slot;
        recSteps = new JSONArray();
        lastStep = "";
        launch(pkg);
        showPill();
    }

    private void showPill() {
        if (pill != null) return;
        try {
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            pill = new TextView(this);
            pill.setTextColor(0xFFFFFFFF);
            pill.setTextSize(14);
            pill.setPadding(dp(18), dp(10), dp(18), dp(10));
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(30));
            g.setColor(0xEEE53935);
            pill.setBackground(g);
            pill.setOnClickListener(v -> finishRecord());
            updatePill();
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(-2, -2,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.y = dp(56);
            wm.addView(pill, lp);
        } catch (Throwable e) { pill = null; }
    }

    private void updatePill() {
        if (pill != null) pill.setText((fa() ? "⏹ پایان ضبط (" : "⏹ Stop recording (") + recSteps.length() + ")");
    }

    private void hidePill() {
        try {
            if (pill != null) ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(pill);
        } catch (Throwable e) { }
        pill = null;
    }

    // the visible name of a view: its own text, or the first text inside it
    private String labelOf(AccessibilityNodeInfo n, int depth) {
        if (n == null) return "";
        CharSequence t = n.getText();
        if (t != null && t.toString().trim().length() > 0) return t.toString().trim();
        CharSequence d = n.getContentDescription();
        if (d != null && d.toString().trim().length() > 0) return d.toString().trim();
        if (depth <= 0) return "";
        for (int i = 0; i < n.getChildCount(); i++) {
            String s = labelOf(n.getChild(i), depth - 1);
            if (!s.isEmpty()) return s;
        }
        return "";
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (recPkg == null || e.getEventType() != AccessibilityEvent.TYPE_VIEW_CLICKED) return;
        if (!recPkg.equals(String.valueOf(e.getPackageName()))) return;
        AccessibilityNodeInfo n = e.getSource();
        if (n == null) return;
        try {
            String t = labelOf(n, 3);
            String id = n.getViewIdResourceName() == null ? "" : n.getViewIdResourceName();
            if (t.isEmpty() && id.isEmpty()) return;
            String key = t + "|" + id;
            long now = System.currentTimeMillis();
            if (key.equals(lastStep) && now - lastStepAt < 400) return;
            lastStep = key;
            lastStepAt = now;
            JSONObject o = new JSONObject();
            o.put("t", t);
            o.put("id", id);
            recSteps.put(o);
            updatePill();
        } catch (Throwable ex) { }
    }

    private void finishRecord() {
        hidePill();
        String pkg = recPkg;
        int slot = recSlot;
        JSONArray st = recSteps;
        recPkg = null;
        if (pkg == null) return;
        try {
            SharedPreferences sp = getSharedPreferences("l", 0);
            BubbleActs.Act[] a = BubbleActs.load(sp);
            if (slot >= 0 && slot < a.length && a[slot] != null && pkg.equals(a[slot].pkg)) {
                a[slot].id = st.toString();
                a[slot].label = "🎬 " + st.length() + (fa() ? " مرحله" : " steps");
                BubbleActs.save(sp, a);
            }
        } catch (Throwable e) { }
        Toast.makeText(this, fa() ? "ضبط ذخیره شد" : "Recording saved", Toast.LENGTH_SHORT).show();
        try {
            Intent i = new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra("open_acts", true);
            startActivity(i);
        } catch (Throwable e) { }
    }

    // ---------- playing the recorded taps back ----------
    private JSONArray steps = new JSONArray();
    private int stepIdx = 0, stepTries = 0;
    private String repPkg = "";

    static boolean replay(String pkg, String json) {
        if (inst == null) return false;
        inst.startReplay(pkg, json);
        return true;
    }

    private void startReplay(String pkg, String json) {
        if (busy) return;
        try { steps = new JSONArray(json == null || json.isEmpty() ? "[]" : json); } catch (Throwable e) { steps = new JSONArray(); }
        repPkg = pkg;
        stepIdx = 0;
        stepTries = 0;
        busy = true;
        launch(pkg);
        h.postDelayed(stepRun, 1500);
    }

    private final Runnable stepRun = new Runnable() {
        @Override
        public void run() {
            if (stepIdx >= steps.length()) { busy = false; return; }
            JSONObject o = steps.optJSONObject(stepIdx);
            if (o != null && tapStep(o.optString("t"), o.optString("id"))) {
                stepIdx++;
                stepTries = 0;
                h.postDelayed(this, 900);
                return;
            }
            if (++stepTries < 26) {
                h.postDelayed(this, 300);
            } else {
                busy = false;
                Toast.makeText(TileClickService.this, (fa() ? "مرحله " : "Step ") + (stepIdx + 1)
                        + (fa() ? " پیدا نشد؛ دوباره ضبط کنید" : " not found; record it again"), Toast.LENGTH_LONG).show();
            }
        }
    };

    private boolean clickUp(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo c = n;
        for (int i = 0; i < 7 && c != null; i++) {
            if (c.isClickable() && c.isEnabled() && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
            c = c.getParent();
        }
        return false;
    }

    private boolean tapStep(String text, String id) {
        for (AccessibilityNodeInfo root : roots()) {
            if (!repPkg.equals(String.valueOf(root.getPackageName()))) continue;
            List<AccessibilityNodeInfo> exact = new ArrayList<>(), loose = new ArrayList<>();
            if (!text.isEmpty()) {
                List<AccessibilityNodeInfo> hits = root.findAccessibilityNodeInfosByText(text);
                if (hits != null) for (AccessibilityNodeInfo n : hits) {
                    if (labelOf(n, 0).equalsIgnoreCase(text)) exact.add(n); else loose.add(n);
                }
            }
            if (exact.isEmpty() && loose.isEmpty() && !id.isEmpty()) {
                List<AccessibilityNodeInfo> hits = root.findAccessibilityNodeInfosByViewId(id);
                if (hits != null) exact.addAll(hits);
            }
            for (AccessibilityNodeInfo n : exact) if (clickUp(n)) return true;
            for (AccessibilityNodeInfo n : loose) if (clickUp(n)) return true;
        }
        return false;
    }

    @Override
    public void onInterrupt() { }

    private void start(String name) {
        if (busy) return;
        busy = true;
        want = name.toLowerCase();
        tries = 0;
        performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS);
        h.postDelayed(poll, 400);
    }

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            tries++;
            // first the nodes that sit inside a tile; later any button with that name
            if (tap(tries < 8)) {
                h.postDelayed(close, 600);
                return;
            }
            if (tries < 24) {
                h.postDelayed(this, 200);
            } else {
                closePanel();
                busy = false;
                Toast.makeText(TileClickService.this, "Tile not found: " + want, Toast.LENGTH_LONG).show();
            }
        }
    };

    private final Runnable close = new Runnable() {
        @Override
        public void run() {
            closePanel();
            h.postDelayed(new Runnable() {
                @Override
                public void run() { busy = false; }
            }, 500);
        }
    };

    private void closePanel() {
        if (Build.VERSION.SDK_INT >= 31) performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE);
        else performGlobalAction(GLOBAL_ACTION_BACK);
    }

    private List<AccessibilityNodeInfo> roots() {
        List<AccessibilityNodeInfo> l = new ArrayList<>();
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            if (ws != null) for (AccessibilityWindowInfo w : ws) {
                AccessibilityNodeInfo r = w.getRoot();
                if (r != null) l.add(r);
            }
        } catch (Throwable e) { }
        if (l.isEmpty()) {
            AccessibilityNodeInfo r = getRootInActiveWindow();
            if (r != null) l.add(r);
        }
        return l;
    }

    private boolean insideTile(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo p = n;
        for (int i = 0; i < 6 && p != null; i++) {
            String id = p.getViewIdResourceName();
            if (id != null && id.toLowerCase().contains("tile")) return true;
            p = p.getParent();
        }
        return false;
    }

    private boolean tap(boolean strict) {
        for (AccessibilityNodeInfo root : roots()) {
            if (getPackageName().equals(String.valueOf(root.getPackageName()))) continue;
            List<AccessibilityNodeInfo> hits = root.findAccessibilityNodeInfosByText(want);
            if (hits == null) continue;
            for (AccessibilityNodeInfo n : hits) {
                if (strict && !insideTile(n)) continue;
                AccessibilityNodeInfo c = n;
                for (int i = 0; i < 6 && c != null; i++) {
                    if (c.isClickable() && c.isEnabled()) {
                        if (c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
                    }
                    c = c.getParent();
                }
            }
        }
        return false;
    }
}
