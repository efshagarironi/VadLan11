package com.mylauncher;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.os.Process;
import android.provider.Settings;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

// the bubble "actions": up to 20 saved ways of starting something inside an app
// (an app shortcut, the last screen of the app, or just the app itself)
public class BubbleActs {
    static final int MAX = 20;

    static class Act {
        String pkg, type, id, label, name = "";   // name = the custom name shown in the bubble list; type: "sc" shortcut, "cmp" screen, "rcv" receiver, "svc" service, "tile" quick settings tile, "macro" recorded taps inside the app (steps in id), "app" open the app (id: "popup" or "full")
        long ts;                       // last time this action was saved / chosen
    }

    static Act[] load(SharedPreferences sp) {
        Act[] a = new Act[MAX];
        try {
            JSONArray arr = new JSONArray(sp.getString("bub_acts", "[]"));
            for (int i = 0; i < arr.length() && i < MAX; i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null || !o.has("pkg")) continue;
                Act x = new Act();
                x.pkg = o.optString("pkg");
                x.type = o.optString("type", "app");
                x.id = o.optString("id", "");
                x.label = o.optString("label", "");
                x.name = o.optString("name", "");
                x.ts = o.optLong("ts", 0);
                a[i] = x;
            }
        } catch (Exception e) { }
        return a;
    }

    static void save(SharedPreferences sp, Act[] a) {
        JSONArray arr = new JSONArray();
        try {
            for (int i = 0; i < MAX; i++) {
                JSONObject o = new JSONObject();
                Act x = a[i];
                if (x != null) {
                    o.put("pkg", x.pkg);
                    o.put("type", x.type);
                    o.put("id", x.id == null ? "" : x.id);
                    o.put("label", x.label == null ? "" : x.label);
                    o.put("name", x.name == null ? "" : x.name);
                    o.put("ts", x.ts);
                }
                arr.put(o);
            }
        } catch (Exception e) { }
        sp.edit().putString("bub_acts", arr.toString()).apply();
    }

    static int count(Act[] a) {
        int n = 0;
        for (Act x : a) if (x != null) n++;
        return n;
    }

    // apps that have at least one action, in slot order
    static List<String> pkgs(Act[] a) {
        LinkedHashSet<String> s = new LinkedHashSet<>();
        for (Act x : a) if (x != null) s.add(x.pkg);
        return new ArrayList<>(s);
    }

    // the action that is used when the app name is tapped in the bubble list: the most recent one
    static Act current(Act[] a, String pkg) {
        Act best = null;
        for (Act x : a)
            if (x != null && x.pkg.equals(pkg) && (best == null || x.ts > best.ts)) best = x;
        return best;
    }

    // the name shown in the bubble list: the custom name, or the app name
    static String title(Context c, Act x) {
        return x.name != null && !x.name.isEmpty() ? x.name : appLabel(c, x.pkg);
    }

    // asks the person to turn on the accessibility service (needed for tiles and recorded taps)
    static void needA11y(Context c) {
        boolean fa = c.getSharedPreferences("l", 0).getString("lang", "fa").equals("fa");
        Toast.makeText(c, fa ? "سرویس دسترسی‌پذیری VadLan را روشن کنید" : "Turn on the VadLan accessibility service",
                Toast.LENGTH_LONG).show();
        try {
            c.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable e) { }
    }

    static String appLabel(Context c, String pkg) {
        try {
            PackageManager pm = c.getPackageManager();
            return pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString();
        } catch (Throwable e) {
            return pkg;
        }
    }

    // runs the action. It never falls back to opening the app: when the action cannot run, false is returned
    static boolean run(Context c, Act x) {
        try {
            if ("sc".equals(x.type)) {                 // app shortcut
                LauncherApps la = (LauncherApps) c.getSystemService(Context.LAUNCHER_APPS_SERVICE);
                la.startShortcut(x.pkg, x.id, null, null, Process.myUserHandle());
                return true;
            }
            if ("cmp".equals(x.type)) {                // an app screen (activity)
                c.startActivity(new Intent().setClassName(x.pkg, x.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            }
            if ("rcv".equals(x.type)) {                // a broadcast receiver of the app: runs in the background
                c.sendBroadcast(new Intent().setClassName(x.pkg, x.id));
                return true;
            }
            if ("svc".equals(x.type)) {                // a service of the app: runs in the background
                Intent si = new Intent().setClassName(x.pkg, x.id);
                try {
                    c.startService(si);
                } catch (IllegalStateException e) {
                    c.startForegroundService(si);
                }
                return true;
            }
            if ("tile".equals(x.type)) {               // a Quick Settings tile, tapped by the accessibility service
                if (!TileClickService.ready()) { needA11y(c); return false; }
                return TileClickService.click(x.id);
            }
            if ("macro".equals(x.type)) {              // taps recorded inside the app, played back one by one
                if (!TileClickService.ready()) { needA11y(c); return false; }
                return TileClickService.replay(x.pkg, x.id);
            }
            if ("app".equals(x.type)) {                // open the app itself (chosen on purpose)
                Intent i = c.getPackageManager().getLaunchIntentForPackage(x.pkg);
                if (i == null) return false;
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if ("popup".equals(x.id)) {
                    // popup: a window of the size saved from "Run apps" (needs free-form windows on the phone)
                    android.app.ActivityOptions o = android.app.ActivityOptions.makeBasic();
                    try {
                        String[] q = c.getSharedPreferences("l", 0).getString("runRect", "").split(",");
                        if (q.length == 4) o.setLaunchBounds(new android.graphics.Rect(
                                Integer.parseInt(q[0]), Integer.parseInt(q[1]), Integer.parseInt(q[2]), Integer.parseInt(q[3])));
                    } catch (Throwable e) { }
                    c.startActivity(i, o.toBundle());
                } else {
                    c.startActivity(i);
                }
                return true;
            }
        } catch (Throwable e) { }
        return false;
    }

    // true when this "action" is just the main screen of the app, which only opens the app
    static boolean opensAppOnly(Context c, Act x) {
        return "cmp".equals(x.type) && x.id.equals(mainScreen(c, x.pkg));
    }

    // the main screen of the app (the one the app icon opens)
    static String mainScreen(Context c, String pkg) {
        try {
            Intent i = c.getPackageManager().getLaunchIntentForPackage(pkg);
            return i == null || i.getComponent() == null ? null : i.getComponent().getClassName();
        } catch (Throwable e) {
            return null;
        }
    }

    // the parts of an app that other apps may start: {type, class name, shown name}
    static List<String[]> components(Context c, String pkg) {
        List<String[]> out = new ArrayList<>();
        try {
            android.content.pm.PackageInfo pi = c.getPackageManager().getPackageInfo(pkg,
                    PackageManager.GET_ACTIVITIES | PackageManager.GET_RECEIVERS | PackageManager.GET_SERVICES);
            String main = mainScreen(c, pkg);
            if (pi.activities != null) for (android.content.pm.ActivityInfo a : pi.activities)
                if (a.exported && a.enabled && !a.name.equals(main))
                    out.add(new String[]{"cmp", a.name, "🖥 " + a.name.substring(a.name.lastIndexOf('.') + 1)});
            if (pi.receivers != null) for (android.content.pm.ActivityInfo a : pi.receivers)
                if (a.exported && a.enabled && a.permission == null)
                    out.add(new String[]{"rcv", a.name, "📡 " + a.name.substring(a.name.lastIndexOf('.') + 1)});
            if (pi.services != null) for (android.content.pm.ServiceInfo s : pi.services)
                if (s.exported && s.enabled && s.permission == null)
                    out.add(new String[]{"svc", s.name, "⚙ " + s.name.substring(s.name.lastIndexOf('.') + 1)});
        } catch (Throwable e) { }
        // the app's own parts first, then the ones that come from libraries
        final String pre = pkg;
        java.util.Collections.sort(out, (x, y) -> {
            boolean ox = x[1].startsWith(pre), oy = y[1].startsWith(pre);
            return ox != oy ? (ox ? -1 : 1) : x[2].compareToIgnoreCase(y[2]);
        });
        return out;
    }

    // the shortcuts an app offers (needs this launcher to be the default home app)
    static List<ShortcutInfo> shortcuts(Context c, String pkg) {
        List<ShortcutInfo> out = new ArrayList<>();
        try {
            LauncherApps la = (LauncherApps) c.getSystemService(Context.LAUNCHER_APPS_SERVICE);
            LauncherApps.ShortcutQuery q = new LauncherApps.ShortcutQuery();
            q.setPackage(pkg);
            q.setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC
                    | LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST
                    | LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED);
            List<ShortcutInfo> l = la.getShortcuts(q, Process.myUserHandle());
            if (l != null) for (ShortcutInfo s : l) if (s.isEnabled()) out.add(s);
        } catch (Throwable e) { }
        return out;
    }

    // ---------- bubble moves (swipes) ----------
    static final int SW_MAX = 30;
    static final int AI_N = 9;
    static final String GEM = "com.google.android.apps.bard", GPT = "com.openai.chatgpt",
            CLA = "com.anthropic.claude", TRN = "com.google.android.apps.translate";
    static final String[] AI_PKG = {GEM, GPT, CLA, GEM, GEM, GEM, TRN, TRN, TRN};
    static final String[] AI_FA = {"جمنای", "ChatGPT", "کلود", "جمنای (حالت صوتی)", "جمنای (حالت ویدیو)",
            "جمنای (تحلیل صفحه نمایش)", "گوگل ترنسلیت (ترجمه با تایپ)", "گوگل ترنسلیت (ترجمه صوتی)",
            "گوگل ترنسلیت (ترجمه با دوربین)"};
    static final String[] AI_EN = {"Gemini", "ChatGPT", "Claude", "Gemini (voice mode)", "Gemini (video mode)",
            "Gemini (analyze screen)", "Google Translate (typing)", "Google Translate (voice)",
            "Google Translate (camera)"};

    static String aiName(boolean fa, int id) { return fa ? AI_FA[id] : AI_EN[id]; }

    static String aiPkg(int id) { return AI_PKG[id]; }

    static boolean fa(Context c) {
        return c.getSharedPreferences("l", 0).getString("lang", "fa").equals("fa");
    }

    // apps chosen for one direction: {package, "popup" or "full"}
    static List<String[]> swList(SharedPreferences sp, String d) {
        List<String[]> l = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp.getString("swl_" + d, "[]"));
            for (int i = 0; i < a.length() && i < SW_MAX; i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null && o.has("pkg")) l.add(new String[]{o.optString("pkg"), o.optString("win", "full")});
            }
        } catch (Exception e) { }
        return l;
    }

    static void swSave(SharedPreferences sp, String d, List<String[]> l) {
        JSONArray a = new JSONArray();
        try {
            for (String[] e : l) a.put(new JSONObject().put("pkg", e[0]).put("win", e[1]));
        } catch (Exception e) { }
        sp.edit().putString("swl_" + d, a.toString()).apply();
    }

    // order of the AI actions of one direction (every action appears once)
    static int[] aiOrder(SharedPreferences sp, String d) {
        int[] o = new int[AI_N];
        boolean[] seen = new boolean[AI_N];
        int n = 0;
        for (String p : sp.getString("swai_" + d + "_o", "").split(",")) {
            try {
                int v = Integer.parseInt(p.trim());
                if (v >= 0 && v < AI_N && !seen[v]) { seen[v] = true; o[n++] = v; }
            } catch (Exception e) { }
        }
        for (int v = 0; v < AI_N; v++) if (!seen[v]) o[n++] = v;
        return o;
    }

    static void aiSaveOrder(SharedPreferences sp, String d, int[] o) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < o.length; i++) b.append(i > 0 ? "," : "").append(o[i]);
        sp.edit().putString("swai_" + d + "_o", b.toString()).apply();
    }

    static String aiWin(SharedPreferences sp, String d, int id) {
        return sp.getString("swai_" + d + "_w" + id, "full");
    }

    // a window of the size saved from "Run apps": the same size as the popups the launcher opens
    static android.app.ActivityOptions popupOpts(Context c) {
        android.app.ActivityOptions o = android.app.ActivityOptions.makeBasic();
        try {
            String[] q = c.getSharedPreferences("l", 0).getString("runRect", "").split(",");
            if (q.length == 4) o.setLaunchBounds(new android.graphics.Rect(
                    Integer.parseInt(q[0]), Integer.parseInt(q[1]), Integer.parseInt(q[2]), Integer.parseInt(q[3])));
        } catch (Throwable e) { }
        return o;
    }

    // opens an app, as a popup or full
    static boolean openApp(Context c, String pkg, boolean popup) {
        try {
            Intent i = c.getPackageManager().getLaunchIntentForPackage(pkg);
            if (i == null) return false;
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (popup) c.startActivity(i, popupOpts(c).toBundle());
            else c.startActivity(i);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    // starts the shortcut of an app whose name contains one of the words
    static boolean shortcutBy(Context c, String pkg, String[] words, boolean popup) {
        try {
            for (ShortcutInfo si : shortcuts(c, pkg)) {
                String s = ((si.getShortLabel() == null ? "" : si.getShortLabel()) + " "
                        + (si.getLongLabel() == null ? "" : si.getLongLabel())).toLowerCase();
                for (String w : words) {
                    if (s.contains(w)) {
                        LauncherApps la = (LauncherApps) c.getSystemService(Context.LAUNCHER_APPS_SERVICE);
                        la.startShortcut(pkg, si.getId(), null, popup ? popupOpts(c).toBundle() : null, Process.myUserHandle());
                        return true;
                    }
                }
            }
        } catch (Throwable e) { }
        return false;
    }

    // a mode inside an app: its shortcut, else one of the given intents, else the app itself
    static boolean deep(Context c, String pkg, boolean popup, String[] words, String[] actions) {
        if (c.getPackageManager().getLaunchIntentForPackage(pkg) == null) return false;
        if (shortcutBy(c, pkg, words, popup)) return true;
        if (actions != null) {
            for (String a : actions) {
                try {
                    Intent i = new Intent(a).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    if (i.resolveActivity(c.getPackageManager()) == null) continue;
                    if (popup) c.startActivity(i, popupOpts(c).toBundle());
                    else c.startActivity(i);
                    return true;
                } catch (Throwable e) { }
            }
        }
        Toast.makeText(c, fa(c) ? "حالت مستقیم پیدا نشد؛ خود برنامه باز شد" : "The direct mode was not found; the app opened",
                Toast.LENGTH_SHORT).show();
        return openApp(c, pkg, popup);
    }

    // runs one AI action (the screen analysis, id 5, is done by the bubble service)
    static boolean runAi(Context c, int id, boolean popup) {
        switch (id) {
            case 0: return openApp(c, GEM, popup);
            case 1: return openApp(c, GPT, popup);
            case 2: return openApp(c, CLA, popup);
            case 3: return deep(c, GEM, popup, new String[]{"live", "voice", "talk", "صوت", "زنده", "گفت", "مکالمه"},
                    new String[]{"android.intent.action.VOICE_ASSIST"});
            case 4: return deep(c, GEM, popup, new String[]{"video", "camera", "ویدیو", "دوربین"}, null);
            case 6: return openApp(c, TRN, popup);
            case 7: return deep(c, TRN, popup, new String[]{"conversation", "voice", "speak", "transcribe", "مکالمه", "گفتگو", "صوت", "گفت"}, null);
            case 8: return deep(c, TRN, popup, new String[]{"camera", "دوربین"}, null);
        }
        return false;
    }

    static boolean hasUsage(Context c) {
        try {
            AppOpsManager ao = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
            return ao.checkOpNoThrow("android:get_usage_stats", Process.myUid(), c.getPackageName())
                    == AppOpsManager.MODE_ALLOWED;
        } catch (Throwable e) {
            return false;
        }
    }

    // the last screen of the app that came to the front since the given time (null = unknown)
    static String lastScreen(Context c, String pkg, long since) {
        try {
            UsageStatsManager um = (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
            UsageEvents ev = um.queryEvents(Math.max(0, since - 2000), System.currentTimeMillis() + 2000);
            UsageEvents.Event e = new UsageEvents.Event();
            String last = null;
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e);
                // 1 = the screen came to the front
                if (e.getEventType() == 1 && pkg.equals(e.getPackageName()) && e.getClassName() != null)
                    last = e.getClassName();
            }
            return last;
        } catch (Throwable e) {
            return null;
        }
    }
}
