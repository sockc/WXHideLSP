package net.sockc.wxhide;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class Prefs {
    public static final String PACKAGE_NAME = "net.sockc.wxhide";
    public static final String WECHAT_PACKAGE = "com.tencent.mm";
    public static final String ACTION_CONFIG_CHANGED = "net.sockc.wxhide.CONFIG_CHANGED";

    public static final String SP_NAME = "wxhide_config";
    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_RULES = "rules";
    public static final String KEY_DEEP_SEARCH = "deep_search";
    public static final String KEY_WECHAT_ENTRY = "wechat_entry";
    public static final String KEY_LAUNCHER_VISIBLE = "launcher_visible";
    public static final String KEY_LAST_EVENT = "last_event";
    public static final String KEY_LAST_DETAIL = "last_detail";
    public static final String KEY_LAST_TIME = "last_time";
    public static final String KEY_HIT_COUNT = "hit_count";

    public static final String GLOBAL_ENABLED = "net_sockc_wxhide_enabled";
    public static final String GLOBAL_RULES_B64 = "net_sockc_wxhide_rules_b64";
    public static final String GLOBAL_DEEP_SEARCH = "net_sockc_wxhide_deep_search";
    public static final String GLOBAL_WECHAT_ENTRY = "net_sockc_wxhide_wechat_entry";

    private Prefs() {}

    public static SharedPreferences sp(Context context) {
        return context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
    }

    public static boolean getEnabledLocal(Context context) {
        return sp(context).getBoolean(KEY_ENABLED, true);
    }

    public static String getRulesLocal(Context context) {
        return sp(context).getString(KEY_RULES, "");
    }

    public static boolean getDeepSearchLocal(Context context) {
        return sp(context).getBoolean(KEY_DEEP_SEARCH, true);
    }

    public static boolean getWechatEntryLocal(Context context) {
        return sp(context).getBoolean(KEY_WECHAT_ENTRY, true);
    }

    public static boolean getLauncherVisibleLocal(Context context) {
        return sp(context).getBoolean(KEY_LAUNCHER_VISIBLE, true);
    }

    public static void saveLocal(Context context, boolean enabled, String rules, boolean deepSearch, boolean wechatEntry, boolean launcherVisible) {
        sp(context).edit()
                .putBoolean(KEY_ENABLED, enabled)
                .putString(KEY_RULES, rules == null ? "" : rules)
                .putBoolean(KEY_DEEP_SEARCH, deepSearch)
                .putBoolean(KEY_WECHAT_ENTRY, wechatEntry)
                .putBoolean(KEY_LAUNCHER_VISIBLE, launcherVisible)
                .apply();
        setLauncherVisible(context, launcherVisible);
    }

    public static void saveStatus(Context context, String event, String detail, boolean hit) {
        SharedPreferences sp = sp(context);
        int hitCount = sp.getInt(KEY_HIT_COUNT, 0);
        if (hit) hitCount++;
        sp.edit()
                .putString(KEY_LAST_EVENT, event == null ? "" : event)
                .putString(KEY_LAST_DETAIL, detail == null ? "" : detail)
                .putLong(KEY_LAST_TIME, System.currentTimeMillis())
                .putInt(KEY_HIT_COUNT, hitCount)
                .apply();
    }

    public static String toB64(String s) {
        if (s == null) s = "";
        return Base64.encodeToString(s.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    }

    public static String fromB64(String s) {
        if (s == null || s.length() == 0) return "";
        try {
            byte[] data = Base64.decode(s, Base64.DEFAULT);
            return new String(data, StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static boolean getEnabledFromGlobal(Context context) {
        try {
            String v = Settings.Global.getString(context.getContentResolver(), GLOBAL_ENABLED);
            if (v == null || v.length() == 0) return true;
            return "1".equals(v) || "true".equalsIgnoreCase(v);
        } catch (Throwable ignored) {
            return true;
        }
    }

    public static String getRulesFromGlobal(Context context) {
        try {
            String b64 = Settings.Global.getString(context.getContentResolver(), GLOBAL_RULES_B64);
            return fromB64(b64);
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static boolean getDeepSearchFromGlobal(Context context) {
        try {
            String v = Settings.Global.getString(context.getContentResolver(), GLOBAL_DEEP_SEARCH);
            if (v == null || v.length() == 0) return true;
            return "1".equals(v) || "true".equalsIgnoreCase(v);
        } catch (Throwable ignored) {
            return true;
        }
    }

    public static boolean getWechatEntryFromGlobal(Context context) {
        try {
            String v = Settings.Global.getString(context.getContentResolver(), GLOBAL_WECHAT_ENTRY);
            if (v == null || v.length() == 0) return true;
            return "1".equals(v) || "true".equalsIgnoreCase(v);
        } catch (Throwable ignored) {
            return true;
        }
    }

    public static boolean putGlobalViaRoot(boolean enabled, String rules, boolean deepSearch, boolean wechatEntry) {
        String b64 = toB64(rules == null ? "" : rules);
        String cmd = "settings put global " + GLOBAL_ENABLED + " " + (enabled ? "1" : "0")
                + " && settings put global " + GLOBAL_RULES_B64 + " '" + escapeSingleQuote(b64) + "'"
                + " && settings put global " + GLOBAL_DEEP_SEARCH + " " + (deepSearch ? "1" : "0")
                + " && settings put global " + GLOBAL_WECHAT_ENTRY + " " + (wechatEntry ? "1" : "0");
        return runRoot(cmd);
    }

    public static boolean forceStopWechatViaRoot() {
        return runRoot("am force-stop " + WECHAT_PACKAGE);
    }

    public static void setLauncherVisible(Context context, boolean visible) {
        try {
            ComponentName alias = new ComponentName(context, PACKAGE_NAME + ".LauncherActivity");
            int state = visible ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
            context.getPackageManager().setComponentEnabledSetting(alias, state, PackageManager.DONT_KILL_APP);
        } catch (Throwable ignored) {}
    }

    private static String escapeSingleQuote(String s) {
        return s == null ? "" : s.replace("'", "'\\''");
    }

    public static boolean runRoot(String command) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
            int code = p.waitFor();
            return code == 0;
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (p != null) p.destroy();
        }
    }

    public static String runShellRead(String command) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"sh", "-c", command});
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            InputStream is = p.getInputStream();
            byte[] buf = new byte[512];
            int len;
            while ((len = is.read(buf)) > 0) bos.write(buf, 0, len);
            p.waitFor();
            return bos.toString("UTF-8").trim();
        } catch (Throwable e) {
            return "";
        } finally {
            if (p != null) p.destroy();
        }
    }
}
