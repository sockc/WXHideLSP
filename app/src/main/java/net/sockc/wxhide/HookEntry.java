package net.sockc.wxhide;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class HookEntry implements IXposedHookLoadPackage {
    private static final String TAG = "WXHideLSP";
    private static final int TAG_HIDE_STATE = 0x7f0c2026;
    private static final int TAG_ENTRY_INSTALLED = 0x7f0c2027;
    private static final long CONFIG_TTL_MS = 700L;
    private static final long SENSITIVE_TOKEN_TTL_MS = 12000L;

    private static volatile boolean initialized = false;
    private static volatile long lastConfigReadAt = 0L;
    private static volatile Config cachedConfig = new Config(true, Collections.<String>emptyList(), true, true);
    private static volatile String lastSensitiveToken = "";
    private static volatile long lastSensitiveTokenAt = 0L;
    private static final Set<String> hookedAdapterClasses = Collections.synchronizedSet(new HashSet<String>());

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam == null || !Prefs.WECHAT_PACKAGE.equals(lpparam.packageName)) return;

        XposedBridge.log(TAG + ": handleLoadPackage package=" + lpparam.packageName + ", process=" + lpparam.processName);

        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Context context = null;
                try { context = (Context) param.args[0]; } catch (Throwable ignored) {}
                if (context == null) return;
                record(context, "attach", "package=" + lpparam.packageName + ", process=" + lpparam.processName, false);
                initOnce(lpparam.classLoader, context);
            }
        });
    }

    private static void initOnce(final ClassLoader classLoader, final Context context) {
        if (initialized) return;
        synchronized (HookEntry.class) {
            if (initialized) return;
            initialized = true;
        }

        try {
            Config cfg = readConfig(context, true);
            XposedBridge.log(TAG + ": initOnce, rules=" + cfg.rules.size() + ", enabled=" + cfg.enabled
                    + ", deep=" + cfg.deepSearch + ", entry=" + cfg.wechatEntry);
            record(context, "loaded", "hooks initialized, rules=" + cfg.rules.size() + ", enabled=" + cfg.enabled, false);

            hookActivity(context);
            hookTextView(context);
            hookViewGroup(context);
            hookRecyclerView(classLoader, context, "androidx.recyclerview.widget.RecyclerView");
            hookRecyclerView(classLoader, context, "android.support.v7.widget.RecyclerView");
            hookAbsListView(context);
            hookViewTouch(context);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": init failed: " + t);
            record(context, "error", "init failed: " + t.getClass().getSimpleName() + ": " + t.getMessage(), false);
        }
    }

    private static void hookActivity(final Context context) {
        try {
            XposedBridge.hookAllMethods(Activity.class, "onResume", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!(param.thisObject instanceof Activity)) return;
                    final Activity activity = (Activity) param.thisObject;
                    record(activity, "loaded", "Activity.onResume: " + activity.getClass().getName(), false);
                    activity.getWindow().getDecorView().postDelayed(new Runnable() {
                        @Override public void run() {
                            injectWechatSettingsEntry(activity);
                        }
                    }, 350L);
                }
            });
            XposedBridge.log(TAG + ": hook Activity.onResume ok");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook Activity.onResume failed: " + t);
        }
    }

    private static void hookTextView(final Context context) {
        try {
            XposedBridge.hookAllMethods(TextView.class, "setText", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    final Object obj = param.thisObject;
                    if (!(obj instanceof TextView)) return;
                    final TextView tv = (TextView) obj;
                    updateSensitiveTokenFromText(context, safeCharSeq(tv.getText()));
                    tv.postDelayed(new Runnable() {
                        @Override public void run() { applyNearView(context, tv); }
                    }, 50L);
                    tv.postDelayed(new Runnable() {
                        @Override public void run() { applyNearView(context, tv); }
                    }, 220L);
                }
            });
            XposedBridge.log(TAG + ": hook TextView.setText ok");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook TextView.setText failed: " + t);
        }
    }

    private static void hookViewGroup(final Context context) {
        try {
            XposedBridge.hookAllMethods(ViewGroup.class, "addView", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args == null || param.args.length == 0 || !(param.args[0] instanceof View)) return;
                    final View child = (View) param.args[0];
                    child.postDelayed(new Runnable() { @Override public void run() { applyNearView(context, child); } }, 80L);
                    child.postDelayed(new Runnable() { @Override public void run() { applyNearView(context, child); } }, 260L);
                }
            });
            XposedBridge.log(TAG + ": hook ViewGroup.addView ok");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook ViewGroup.addView failed: " + t);
        }
    }

    private static void hookViewTouch(final Context context) {
        try {
            XposedBridge.hookAllMethods(View.class, "dispatchTouchEvent", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!(param.thisObject instanceof View)) return;
                    View v = (View) param.thisObject;
                    Object state = v.getTag(TAG_HIDE_STATE);
                    if (state instanceof HideState && param.args != null && param.args.length > 0 && param.args[0] instanceof MotionEvent) {
                        param.setResult(false);
                    }
                }
            });
            XposedBridge.log(TAG + ": hook View.dispatchTouchEvent ok");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook View.dispatchTouchEvent skipped: " + t.getClass().getSimpleName());
        }
    }

    private static void hookAbsListView(final Context context) {
        try {
            Class<?> abs = Class.forName("android.widget.AbsListView");
            for (final Method m : abs.getDeclaredMethods()) {
                if (!"obtainView".equals(m.getName())) continue;
                try {
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (param.getResult() instanceof View) {
                                final View v = (View) param.getResult();
                                v.post(new Runnable() { @Override public void run() { applyRoot(context, v); } });
                            }
                        }
                    });
                } catch (Throwable ignored) {}
            }
            XposedBridge.log(TAG + ": hook AbsListView.obtainView ok");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook AbsListView.obtainView failed: " + t);
        }
    }

    private static void hookRecyclerView(final ClassLoader cl, final Context context, String className) {
        try {
            final Class<?> rvClass = Class.forName(className, false, cl);
            XposedBridge.hookAllMethods(rvClass, "setAdapter", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args != null && param.args.length > 0 && param.args[0] != null) hookAdapterClass(param.args[0].getClass(), context);
                }
            });
            XposedBridge.hookAllMethods(rvClass, "swapAdapter", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args != null && param.args.length > 0 && param.args[0] != null) hookAdapterClass(param.args[0].getClass(), context);
                }
            });
            XposedBridge.log(TAG + ": hook RecyclerView ok: " + className);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook RecyclerView skipped: " + className + ", " + t.getClass().getSimpleName());
        }
    }

    private static void hookAdapterClass(Class<?> adapterClass, final Context context) {
        if (adapterClass == null) return;
        String name = adapterClass.getName();
        if (!hookedAdapterClasses.add(name)) return;

        int count = 0;
        Class<?> c = adapterClass;
        while (c != null && c != Object.class) {
            Method[] methods;
            try { methods = c.getDeclaredMethods(); } catch (Throwable e) { methods = new Method[0]; }
            for (final Method m : methods) {
                String mn = m.getName();
                if (!"onBindViewHolder".equals(mn) && !"onCreateViewHolder".equals(mn)) continue;
                if (Modifier.isAbstract(m.getModifiers())) continue;
                try {
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            Object holder = null;
                            if ("onCreateViewHolder".equals(m.getName())) holder = param.getResult();
                            else if (param.args != null && param.args.length > 0) holder = param.args[0];
                            final View itemView = getItemView(holder);
                            if (itemView != null) {
                                itemView.postDelayed(new Runnable() { @Override public void run() { applyRoot(context, itemView); } }, 30L);
                                itemView.postDelayed(new Runnable() { @Override public void run() { applyRoot(context, itemView); } }, 180L);
                            }
                        }
                    });
                    count++;
                } catch (Throwable ignored) {}
            }
            c = c.getSuperclass();
        }
        XposedBridge.log(TAG + ": hook adapter " + name + ", methods=" + count);
    }

    private static View getItemView(Object holder) {
        if (holder == null) return null;
        Class<?> c = holder.getClass();
        while (c != null && c != Object.class) {
            try {
                Field f = c.getDeclaredField("itemView");
                f.setAccessible(true);
                Object v = f.get(holder);
                return v instanceof View ? (View) v : null;
            } catch (Throwable ignored) { c = c.getSuperclass(); }
        }
        return null;
    }

    private static void applyNearView(Context context, View v) {
        if (v == null) return;
        View root = findItemRoot(v);
        if (root == null) root = v;
        applyRoot(context, root);
    }

    private static View findItemRoot(View v) {
        View current = v;
        View last = v;
        int depth = 0;
        while (current != null && current.getParent() instanceof View && depth < 12) {
            View parent = (View) current.getParent();
            String n = parent.getClass().getName();
            if (isListContainerName(n)) return current;
            if (isActivityContent(parent)) return current;
            last = parent;
            current = parent;
            depth++;
        }
        return last;
    }

    private static boolean isListContainerName(String n) {
        if (n == null) return false;
        return n.contains("RecyclerView") || n.contains("AbsListView") || n.contains("ListView");
    }

    private static boolean isActivityContent(View v) {
        try { return v.getId() == android.R.id.content; } catch (Throwable ignored) { return false; }
    }

    private static void applyRoot(Context context, View root) {
        if (root == null || context == null) return;
        Config cfg = readConfig(context, false);
        if (!cfg.enabled || cfg.rules.isEmpty()) {
            restoreIfNeeded(root);
            return;
        }

        View target = findItemRoot(root);
        if (target == null) target = root;
        String text = collectText(target, 0, new StringBuilder(512)).toString();
        MatchResult mr = matchText(text, cfg);
        if (mr.matched) {
            updateSensitiveToken(mr.token);
            hide(target);
            if (cfg.deepSearch) hideRelatedHeaderOrContainer(target, text);
            record(context, "hit", "matched: " + mr.reason + " on view=" + target.getClass().getSimpleName(), true);
        } else if (cfg.deepSearch && shouldHideBecauseSensitiveSearch(text)) {
            hide(target);
            record(context, "hit", "deep search cleanup on view=" + target.getClass().getSimpleName(), true);
        } else {
            restoreIfNeeded(target);
        }
    }

    private static void hideRelatedHeaderOrContainer(View target, String text) {
        if (target == null) return;
        // Remove margin/padding remnants from the matched item and one safe parent layer.
        hide(target);
        View parent = null;
        try { if (target.getParent() instanceof View) parent = (View) target.getParent(); } catch (Throwable ignored) {}
        if (parent != null && !isListContainerName(parent.getClass().getName()) && !isActivityContent(parent)) {
            String pt = collectText(parent, 0, new StringBuilder(512)).toString();
            if (matchSearchRelatedText(pt) || matchSectionCleanupText(pt) || safeContainsSensitiveToken(pt)) hide(parent);
        }
    }

    private static boolean shouldHideBecauseSensitiveSearch(String text) {
        if (text == null || text.length() == 0) return false;
        long now = SystemClock.uptimeMillis();
        String token = lastSensitiveToken;
        if (token == null || token.length() < 2 || now - lastSensitiveTokenAt > SENSITIVE_TOKEN_TTL_MS) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        String t = token.toLowerCase(Locale.ROOT);
        if (lower.contains(t)) return true;
        if (matchSearchRelatedText(text)) return true;
        return matchSectionCleanupText(text);
    }

    private static boolean matchSearchRelatedText(String text) {
        if (text == null) return false;
        return text.contains("搜索网络结果") || text.contains("搜一搜") || text.contains("网络结果") || text.contains("Search the web");
    }

    private static boolean matchSectionCleanupText(String text) {
        if (text == null) return false;
        String t = text.replace("\n", "").replace(" ", "").trim();
        return "联系人".equals(t) || "聊天记录".equals(t) || "公众号".equals(t) || "小程序".equals(t) || "群聊".equals(t);
    }

    private static boolean safeContainsSensitiveToken(String text) {
        String token = lastSensitiveToken;
        if (text == null || token == null || token.length() < 2) return false;
        return text.toLowerCase(Locale.ROOT).contains(token.toLowerCase(Locale.ROOT));
    }

    private static StringBuilder collectText(View v, int depth, StringBuilder sb) {
        if (v == null || depth > 9 || sb.length() > 6000) return sb;
        try {
            if (v instanceof TextView) {
                CharSequence cs = ((TextView) v).getText();
                if (cs != null) sb.append(cs).append('\n');
            }
            CharSequence cd = v.getContentDescription();
            if (cd != null) sb.append(cd).append('\n');
            if (v instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) v;
                int count = Math.min(vg.getChildCount(), 100);
                for (int i = 0; i < count; i++) collectText(vg.getChildAt(i), depth + 1, sb);
            }
        } catch (Throwable ignored) {}
        return sb;
    }

    private static MatchResult matchText(String text, Config cfg) {
        if (text == null || text.length() == 0) return MatchResult.no();
        String lower = text.toLowerCase(Locale.ROOT);
        for (String r : cfg.rules) {
            if (r == null) continue;
            String rule = r.trim();
            if (rule.length() == 0) continue;
            String rl = rule.toLowerCase(Locale.ROOT);
            if (lower.contains(rl)) return MatchResult.yes(rule, "exact rule");
        }
        if (cfg.deepSearch) {
            List<String> tokens = splitTokens(text);
            for (String token : tokens) {
                if (token.length() < 2 || isCommonToken(token)) continue;
                String tl = token.toLowerCase(Locale.ROOT);
                for (String r : cfg.rules) {
                    if (r == null) continue;
                    String rl = r.trim().toLowerCase(Locale.ROOT);
                    if (rl.length() < 2) continue;
                    if (rl.contains(tl) || tl.contains(rl)) {
                        if (matchSearchRelatedText(text) || token.length() >= 2) {
                            return MatchResult.yes(token, "deep token");
                        }
                    }
                }
            }
        }
        return MatchResult.no();
    }

    private static List<String> splitTokens(String text) {
        ArrayList<String> out = new ArrayList<String>();
        if (text == null) return out;
        String[] arr = text.split("[\\r\\n\\t ,，:：/\\\\|()（）\\[\\]{}<>《》]+");
        if (arr.length <= 1) arr = text.split("\\r?\\n");
        for (String s : arr) {
            if (s == null) continue;
            String t = s.trim();
            if (t.length() >= 2 && t.length() <= 30) out.add(t);
        }
        return out;
    }

    private static boolean isCommonToken(String t) {
        if (t == null) return true;
        String s = t.trim();
        return "联系人".equals(s) || "聊天记录".equals(s) || "搜索网络结果".equals(s)
                || "搜一搜".equals(s) || "公众号".equals(s) || "小程序".equals(s)
                || "朋友圈".equals(s) || "群聊".equals(s) || "更多".equals(s);
    }

    private static void updateSensitiveTokenFromText(Context context, String s) {
        if (s == null || s.trim().length() < 2) return;
        Config cfg = readConfig(context, false);
        if (!cfg.enabled || cfg.rules.isEmpty()) return;
        MatchResult mr = matchText(s, cfg);
        if (mr.matched) updateSensitiveToken(mr.token);
    }

    private static String safeCharSeq(CharSequence cs) {
        return cs == null ? "" : cs.toString();
    }

    private static void updateSensitiveToken(String token) {
        if (token == null) return;
        String t = token.trim();
        if (t.length() < 2) return;
        lastSensitiveToken = t;
        lastSensitiveTokenAt = SystemClock.uptimeMillis();
    }

    private static void hide(View root) {
        try {
            Object old = root.getTag(TAG_HIDE_STATE);
            if (!(old instanceof HideState)) {
                ViewGroup.LayoutParams lp = root.getLayoutParams();
                int h = lp == null ? ViewGroup.LayoutParams.WRAP_CONTENT : lp.height;
                int w = lp == null ? ViewGroup.LayoutParams.WRAP_CONTENT : lp.width;
                int ml = 0, mt = 0, mr = 0, mb = 0;
                if (lp instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
                    ml = mlp.leftMargin; mt = mlp.topMargin; mr = mlp.rightMargin; mb = mlp.bottomMargin;
                }
                root.setTag(TAG_HIDE_STATE, new HideState(root.getVisibility(), root.getAlpha(), root.getMinimumHeight(), w, h,
                        root.getPaddingLeft(), root.getPaddingTop(), root.getPaddingRight(), root.getPaddingBottom(),
                        ml, mt, mr, mb, root.isClickable(), root.isEnabled()));
            }
            root.setAlpha(0f);
            root.setVisibility(View.GONE);
            root.setMinimumHeight(0);
            root.setPadding(0, 0, 0, 0);
            root.setClickable(false);
            root.setEnabled(false);
            ViewGroup.LayoutParams lp = root.getLayoutParams();
            if (lp != null) {
                lp.height = 0;
                if (lp instanceof ViewGroup.MarginLayoutParams) ((ViewGroup.MarginLayoutParams) lp).setMargins(0, 0, 0, 0);
                root.setLayoutParams(lp);
            }
        } catch (Throwable ignored) {}
    }

    private static void restoreIfNeeded(View root) {
        try {
            Object old = root.getTag(TAG_HIDE_STATE);
            if (!(old instanceof HideState)) return;
            HideState st = (HideState) old;
            root.setTag(TAG_HIDE_STATE, null);
            root.setVisibility(st.visibility);
            root.setAlpha(st.alpha);
            root.setMinimumHeight(st.minHeight);
            root.setPadding(st.pl, st.pt, st.pr, st.pb);
            root.setClickable(st.clickable);
            root.setEnabled(st.enabled);
            ViewGroup.LayoutParams lp = root.getLayoutParams();
            if (lp != null) {
                lp.width = st.width;
                lp.height = st.height;
                if (lp instanceof ViewGroup.MarginLayoutParams) ((ViewGroup.MarginLayoutParams) lp).setMargins(st.ml, st.mt, st.mr, st.mb);
                root.setLayoutParams(lp);
            }
        } catch (Throwable ignored) {}
    }

    private static Config readConfig(Context context, boolean force) {
        long now = SystemClock.uptimeMillis();
        if (!force && now - lastConfigReadAt < CONFIG_TTL_MS) return cachedConfig;
        lastConfigReadAt = now;

        boolean enabled = true;
        boolean deepSearch = true;
        boolean wechatEntry = true;
        String rules = "";

        try {
            enabled = Prefs.getEnabledFromGlobal(context);
            rules = Prefs.getRulesFromGlobal(context);
            deepSearch = Prefs.getDeepSearchFromGlobal(context);
            wechatEntry = Prefs.getWechatEntryFromGlobal(context);
        } catch (Throwable ignored) {}

        if (rules == null || rules.trim().length() == 0) {
            try {
                Cursor c = context.getContentResolver().query(Uri.parse("content://net.sockc.wxhide.provider/config"), null, null, null, null);
                if (c != null) {
                    try {
                        if (c.moveToFirst()) {
                            enabled = "1".equals(c.getString(0));
                            rules = c.getString(1);
                            if (c.getColumnCount() > 2) deepSearch = "1".equals(c.getString(2));
                            if (c.getColumnCount() > 3) wechatEntry = "1".equals(c.getString(3));
                        }
                    } finally { c.close(); }
                }
            } catch (Throwable ignored) {}
        }

        List<String> list = new ArrayList<String>();
        if (rules != null) {
            String[] lines = rules.split("\\r?\\n");
            for (String line : lines) {
                String s = line == null ? "" : line.trim();
                if (s.length() >= 2) list.add(s);
            }
        }
        cachedConfig = new Config(enabled, list, deepSearch, wechatEntry);
        return cachedConfig;
    }

    private static void injectWechatSettingsEntry(final Activity activity) {
        if (activity == null) return;
        final Config cfg = readConfig(activity, false);
        if (!cfg.wechatEntry) return;
        String cls = activity.getClass().getName().toLowerCase(Locale.ROOT);
        View decor = activity.getWindow().getDecorView();
        String rootText = collectText(decor, 0, new StringBuilder(256)).toString();
        boolean likelySettings = cls.contains("setting") || cls.contains("settings") || rootText.contains("设置");
        if (!likelySettings) return;

        final FrameLayout content = activity.findViewById(android.R.id.content);
        if (content == null) return;
        Object installed = content.getTag(TAG_ENTRY_INSTALLED);
        if (installed instanceof Boolean && ((Boolean) installed)) return;
        content.setTag(TAG_ENTRY_INSTALLED, Boolean.TRUE);

        TextView entry = new TextView(activity);
        entry.setText("WX Hide LSP 设置");
        entry.setTextSize(16);
        entry.setGravity(Gravity.CENTER);
        entry.setTextColor(0xff111111);
        entry.setBackgroundColor(0xeeffffff);
        int pad = dp(activity, 12);
        entry.setPadding(pad, pad, pad, pad);
        entry.setClickable(true);
        entry.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    Intent i = new Intent();
                    i.setClassName(Prefs.PACKAGE_NAME, Prefs.PACKAGE_NAME + ".MainActivity");
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    activity.startActivity(i);
                } catch (Throwable t) {
                    record(activity, "error", "open module settings failed: " + t.getClass().getSimpleName(), false);
                }
            }
        });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM;
        lp.leftMargin = dp(activity, 18);
        lp.rightMargin = dp(activity, 18);
        lp.bottomMargin = dp(activity, 20);
        content.addView(entry, lp);
        record(activity, "loaded", "wechat settings entry injected: " + activity.getClass().getName(), false);
    }

    private static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static void record(Context context, String event, String detail, boolean hit) {
        try { XposedBridge.log(TAG + ": " + event + " - " + detail); } catch (Throwable ignored) {}
        try {
            Intent i = new Intent(StatusReceiver.ACTION);
            i.setClassName(Prefs.PACKAGE_NAME, Prefs.PACKAGE_NAME + ".StatusReceiver");
            i.putExtra(StatusReceiver.EXTRA_EVENT, event);
            i.putExtra(StatusReceiver.EXTRA_DETAIL, detail);
            i.putExtra(StatusReceiver.EXTRA_HIT, hit);
            context.sendBroadcast(i);
        } catch (Throwable ignored) {}
    }

    private static final class MatchResult {
        final boolean matched;
        final String token;
        final String reason;
        private MatchResult(boolean matched, String token, String reason) {
            this.matched = matched; this.token = token == null ? "" : token; this.reason = reason == null ? "" : reason;
        }
        static MatchResult yes(String token, String reason) { return new MatchResult(true, token, reason); }
        static MatchResult no() { return new MatchResult(false, "", ""); }
    }

    private static final class Config {
        final boolean enabled;
        final List<String> rules;
        final boolean deepSearch;
        final boolean wechatEntry;
        Config(boolean enabled, List<String> rules, boolean deepSearch, boolean wechatEntry) {
            this.enabled = enabled;
            this.rules = rules == null ? Collections.<String>emptyList() : rules;
            this.deepSearch = deepSearch;
            this.wechatEntry = wechatEntry;
        }
    }

    private static final class HideState {
        final int visibility, minHeight, width, height;
        final float alpha;
        final int pl, pt, pr, pb, ml, mt, mr, mb;
        final boolean clickable, enabled;
        HideState(int visibility, float alpha, int minHeight, int width, int height,
                  int pl, int pt, int pr, int pb, int ml, int mt, int mr, int mb,
                  boolean clickable, boolean enabled) {
            this.visibility = visibility; this.alpha = alpha; this.minHeight = minHeight; this.width = width; this.height = height;
            this.pl = pl; this.pt = pt; this.pr = pr; this.pb = pb; this.ml = ml; this.mt = mt; this.mr = mr; this.mb = mb;
            this.clickable = clickable; this.enabled = enabled;
        }
    }
}
