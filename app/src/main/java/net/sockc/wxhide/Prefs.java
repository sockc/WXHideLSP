package net.sockc.wxhide;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class Prefs {
    public static final String SP_NAME = "wxhide_rules";
    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_KEYWORDS = "keywords";

    private Prefs() {}

    public static boolean isEnabled(Context context) {
        return sp(context).getBoolean(KEY_ENABLED, true);
    }

    public static String getRawKeywords(Context context) {
        return sp(context).getString(KEY_KEYWORDS, "");
    }

    public static void save(Context context, boolean enabled, String rawKeywords) {
        sp(context).edit()
                .putBoolean(KEY_ENABLED, enabled)
                .putString(KEY_KEYWORDS, rawKeywords == null ? "" : rawKeywords)
                .apply();
    }

    public static List<String> parseKeywords(String raw) {
        Set<String> set = new LinkedHashSet<>();
        if (raw == null) return new ArrayList<>();
        String[] lines = raw.split("\\r?\\n");
        for (String line : lines) {
            String s = normalize(line);
            if (s.length() == 0) continue;
            if (s.startsWith("#")) continue;
            set.add(s);
        }
        return new ArrayList<>(set);
    }

    public static String normalize(String s) {
        if (s == null) return "";
        // 去掉常见零宽字符，减少微信昵称特殊字符导致的匹配失败。
        return s.replace("\u200B", "")
                .replace("\u200C", "")
                .replace("\u200D", "")
                .replace("\uFEFF", "")
                .trim()
                .toLowerCase();
    }

    private static SharedPreferences sp(Context context) {
        return context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
    }
}
