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
import android.widget.LinearLayout;
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
    private static final int TAG_INLINE_ENTRY_ROW = 0x7f0c2028;
    private static final long CONFIG_TTL_MS = 700L;

    private static volatile boolean initialized = false;
    private static volatile long lastConfigReadAt = 0L;
    private static volatile Config cachedConfig = new Config(true, Collections.<String>emptyList(), true, true);
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
                    + ", searchSafe=" + cfg.searchSafe + ", entry=" + cfg.wechatEntry);
            record(context, "loaded", "hooks initialized, rules=" + cfg.rules.size() + ", enabled=" + cfg.enabled, false);

            hookActivity(context);
            hookTextView(context);
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
                        @Override public void run() { injectWechatSettingsEntry(activity); }
                    }, 180L);
                    activity.getWindow().getDecorView().postDelayed(new Runnable() {
                        @Override public void run() { injectWechatSettingsEntry(activity); }
                    }, 650L);
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
                    tv.postDelayed(new Runnable() {
                        @Override public void run() { applyFromAnyView(context, tv, false); }
                    }, 80L);
                    tv.postDelayed(new Runnable() {
                        @Override public void run() { applyFromAnyView(context, tv, false); }
                    }, 260L);
                }
            });
            XposedBridge.log(TAG + ": hook TextView.setText ok");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook TextView.setText failed: " + t);
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
                                v.post(new Runnable() { @Override public void run() { applyListItem(context, v, true); } });
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
                                itemView.postDelayed(new Runnable() { @Override public void run() { applyListItem(context, itemView, true); } }, 30L);
                                itemView.postDelayed(new Runnable() { @Override public void run() { applyListItem(context, itemView, true); } }, 180L);
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

    private static void applyFromAnyView(Context context, View v, boolean definitelyItem) {
        if (v == null) return;
        View item = definitelyItem ? v : findSafeListItemRoot(v);
        if (item != null) applyListItem(context, item, true);
    }

    private static void applyListItem(Context context, View item, boolean allowHeaderCleanup) {
        if (item == null || context == null) return;
        Config cfg = readConfig(context, false);
        if (!cfg.enabled || cfg.rules.isEmpty()) {
            restoreIfNeeded(item);
            return;
        }

        View target = findSafeListItemRoot(item);
        if (target == null) target = item;
        if (!isSafeListItem(target)) return;

        String text = collectText(target, 0, new StringBuilder(512)).toString();
        if (isNetworkSearchRow(text)) {
            restoreIfNeeded(target);
            return;
        }

        MatchResult mr = matchText(text, cfg);
        if (mr.matched) {
            hide(target);
            if (cfg.searchSafe && allowHeaderCleanup) maybeHideImmediateSectionHeader(target);
            record(context, "hit", "matched: " + mr.reason + " on item=" + target.getClass().getSimpleName(), true);
        } else {
            restoreIfNeeded(target);
        }
    }

    private static View findSafeListItemRoot(View v) {
        if (v == null) return null;
        View current = v;
        View child = v;
        int depth = 0;
        while (current != null && current.getParent() instanceof View && depth < 14) {
            View parent = (View) current.getParent();
            if (isListContainerName(parent.getClass().getName())) return current;
            if (isActivityContent(parent)) break;
            child = current;
            current = parent;
            depth++;
        }
        if (isSafeStandaloneRow(child)) return child;
        return null;
    }

    private static boolean isSafeListItem(View v) {
        if (v == null || isActivityContent(v)) return false;
        try {
            if (v.getRootView() == v) return false;
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            int h = lp == null ? 0 : lp.height;
            if (h == 0 && !(v.getTag(TAG_HIDE_STATE) instanceof HideState)) return false;
            if (hasListParent(v)) return true;
            return isSafeStandaloneRow(v);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean hasListParent(View v) {
        View cur = v;
        int depth = 0;
        while (cur != null && cur.getParent() instanceof View && depth < 10) {
            View p = (View) cur.getParent();
            if (isListContainerName(p.getClass().getName())) return true;
            if (isActivityContent(p)) return false;
            cur = p;
            depth++;
        }
        return false;
    }

    private static boolean isSafeStandaloneRow(View v) {
        if (v == null) return false;
        int h = 0, w = 0;
        try { h = v.getHeight(); w = v.getWidth(); } catch (Throwable ignored) {}
        if (h <= 0 || w <= 0) return false;
        if (h > dp(v.getContext(), 120)) return false;
        String text = compact(collectText(v, 0, new StringBuilder(512)).toString());
        return text.length() >= 2 && text.length() <= 160;
    }

    private static boolean isListContainerName(String n) {
        if (n == null) return false;
        return n.contains("RecyclerView") || n.contains("AbsListView") || n.contains("ListView");
    }

    private static boolean isActivityContent(View v) {
        try { return v.getId() == android.R.id.content; } catch (Throwable ignored) { return false; }
    }

    private static void maybeHideImmediateSectionHeader(View hiddenItem) {
        try {
            if (!(hiddenItem.getParent() instanceof ViewGroup)) return;
            ViewGroup parent = (ViewGroup) hiddenItem.getParent();
            int idx = parent.indexOfChild(hiddenItem);
            if (idx <= 0) return;
            View prev = parent.getChildAt(idx - 1);
            String t = compact(collectText(prev, 0, new StringBuilder(256)).toString());
            if (isSearchSectionHeader(t)) hide(prev);
        } catch (Throwable ignored) {}
    }

    private static boolean isSearchSectionHeader(String compactText) {
        if (compactText == null) return false;
        return "联系人".equals(compactText) || "聊天记录".equals(compactText) || "群聊".equals(compactText)
                || "公众号".equals(compactText) || "小程序".equals(compactText);
    }

    private static boolean isNetworkSearchRow(String text) {
        if (text == null) return false;
        return text.contains("搜索网络结果") || text.contains("网络结果") || text.contains("搜一搜") || text.contains("Search the web");
    }

    private static String compact(String text) {
        if (text == null) return "";
        return text.replace("\n", "").replace("\r", "").replace("\t", "")
                .replace(" ", "").replace("　", "").trim();
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
            if (lower.contains(rl)) return MatchResult.yes(rule, "rule");
        }
        return MatchResult.no();
    }

    private static String safeCharSeq(CharSequence cs) {
        return cs == null ? "" : cs.toString();
    }

    private static void hide(View root) {
        if (root == null) return;
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
        if (root == null) return;
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

    private static List<String> makeRuleAliases(String rule) {
        ArrayList<String> out = new ArrayList<String>();
        if (rule == null) return out;
        String s = rule.trim();
        StringBuilder cjk = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (isCjk(ch)) {
                cjk.append(ch);
            } else {
                if (cjk.length() >= 2) out.add(cjk.toString());
                cjk.setLength(0);
            }
        }
        if (cjk.length() >= 2) out.add(cjk.toString());
        String noPrefix = s.replaceFirst("^[A-Za-z0-9_\\-]+", "").trim();
        if (noPrefix.length() >= 2 && noPrefix.length() < s.length()) out.add(noPrefix);
        return out;
    }

    private static boolean isCjk(char ch) {
        return (ch >= '\u4e00' && ch <= '\u9fff') || (ch >= '\u3400' && ch <= '\u4dbf');
    }

    private static boolean isCommonToken(String t) {
        if (t == null) return true;
        String s = t.trim();
        return "联系人".equals(s) || "聊天记录".equals(s) || "搜索网络结果".equals(s)
                || "搜一搜".equals(s) || "公众号".equals(s) || "小程序".equals(s)
                || "朋友圈".equals(s) || "群聊".equals(s) || "更多".equals(s) || "网络结果".equals(s);
    }

    private static Config readConfig(Context context, boolean force) {
        long now = SystemClock.uptimeMillis();
        if (!force && now - lastConfigReadAt < CONFIG_TTL_MS) return cachedConfig;
        lastConfigReadAt = now;

        boolean enabled = true;
        boolean searchSafe = true;
        boolean wechatEntry = true;
        String rules = "";

        try {
            enabled = Prefs.getEnabledFromGlobal(context);
            rules = Prefs.getRulesFromGlobal(context);
            searchSafe = Prefs.getDeepSearchFromGlobal(context);
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
                            if (c.getColumnCount() > 2) searchSafe = "1".equals(c.getString(2));
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
                if (s.length() >= 2) {
                    if (!list.contains(s)) list.add(s);
                    for (String alias : makeRuleAliases(s)) {
                        if (alias.length() >= 2 && !list.contains(alias) && !isCommonToken(alias)) list.add(alias);
                    }
                }
            }
        }
        cachedConfig = new Config(enabled, list, searchSafe, wechatEntry);
        return cachedConfig;
    }

    private static void injectWechatSettingsEntry(final Activity activity) {
        if (activity == null) return;
        final Config cfg = readConfig(activity, false);
        if (!cfg.wechatEntry) return;
        String cls = activity.getClass().getName().toLowerCase(Locale.ROOT);
        View decor = activity.getWindow().getDecorView();
        String rootText = collectText(decor, 0, new StringBuilder(1024)).toString();
        boolean likelySettings = cls.contains("setting") || cls.contains("settings") || rootText.contains("关于微信") || rootText.contains("帮助与反馈");
        if (!likelySettings) return;

        final FrameLayout content = activity.findViewById(android.R.id.content);
        if (content == null) return;
        Object installed = content.getTag(TAG_ENTRY_INSTALLED);
        if (installed instanceof Boolean && ((Boolean) installed)) return;

        boolean inlineOk = injectInlineFunctionMenuEntry(activity, decor);
        if (inlineOk) {
            content.setTag(TAG_ENTRY_INSTALLED, Boolean.TRUE);
            record(activity, "loaded", "wechat settings inline entry injected: " + activity.getClass().getName(), false);
        } else {
            record(activity, "loaded", "wechat settings inline entry not found, no bottom fallback", false);
        }
    }

    private static boolean injectInlineFunctionMenuEntry(final Activity activity, View root) {
        try {
            View anchor = findSettingsAnchorRow(root);
            if (anchor == null) return false;
            if (!(anchor.getParent() instanceof ViewGroup)) return false;
            ViewGroup parent = (ViewGroup) anchor.getParent();
            if (isListContainerName(parent.getClass().getName())) return false;
            if (parent.getTag(TAG_INLINE_ENTRY_ROW) instanceof Boolean) return true;
            for (int i = 0; i < parent.getChildCount(); i++) {
                View child = parent.getChildAt(i);
                String t = collectText(child, 0, new StringBuilder(128)).toString();
                if (t.contains("WX Hide LSP")) return true;
            }

            LinearLayout row = new LinearLayout(activity);
            row.setTag(TAG_INLINE_ENTRY_ROW, Boolean.TRUE);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackgroundColor(0xffffffff);
            row.setClickable(true);
            int hp = dp(activity, 16);
            row.setPadding(hp, 0, hp, 0);
            row.setMinimumHeight(dp(activity, 56));
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { openModuleSettings(activity); }
            });

            TextView title = new TextView(activity);
            title.setText("WX Hide LSP");
            title.setTextSize(16);
            title.setTextColor(0xff111111);
            title.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            row.addView(title, titleLp);

            TextView sub = new TextView(activity);
            sub.setText("设置  ›");
            sub.setTextSize(14);
            sub.setTextColor(0xff888888);
            sub.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
            row.addView(sub, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));

            int index = parent.indexOfChild(anchor);
            if (index < 0) index = parent.getChildCount() - 1;
            ViewGroup.LayoutParams newLp = makeRowLayoutParams(activity, parent);
            parent.addView(row, Math.min(index + 1, parent.getChildCount()), newLp);
            parent.setTag(TAG_INLINE_ENTRY_ROW, Boolean.TRUE);
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": inline settings entry failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
    }

    private static ViewGroup.LayoutParams makeRowLayoutParams(Context c, ViewGroup parent) {
        if (parent instanceof LinearLayout) return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 56));
        if (parent instanceof FrameLayout) return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 56));
        return new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 56));
    }

    private static View findSettingsAnchorRow(View root) {
        if (root == null) return null;
        String[] labels = new String[]{"插件", "其他功能", "聊天记录管理", "音视频通话", "关于微信"};
        for (String label : labels) {
            View tv = findTextViewByText(root, label, 0);
            if (tv != null) {
                View row = findSettingsRowFromText(tv);
                if (row != null) return row;
            }
        }
        return null;
    }

    private static View findTextViewByText(View v, String target, int depth) {
        if (v == null || target == null || depth > 18) return null;
        try {
            if (v instanceof TextView) {
                String t = compact(safeCharSeq(((TextView) v).getText()));
                if (target.equals(t) || t.startsWith(target) || t.contains(target)) return v;
            }
            if (v instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) v;
                int count = Math.min(vg.getChildCount(), 240);
                for (int i = 0; i < count; i++) {
                    View r = findTextViewByText(vg.getChildAt(i), target, depth + 1);
                    if (r != null) return r;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static View findSettingsRowFromText(View v) {
        if (v == null) return null;
        View best = v;
        View cur = v;
        int depth = 0;
        while (cur != null && cur.getParent() instanceof View && depth < 10) {
            View p = (View) cur.getParent();
            if (isActivityContent(p) || isListContainerName(p.getClass().getName())) break;
            String pt = compact(collectText(p, 0, new StringBuilder(1024)).toString());
            String ct = compact(collectText(cur, 0, new StringBuilder(512)).toString());
            if (ct.length() > 0 && ct.length() <= 120) best = cur;
            if (pt.length() > 160 || looksLikeSettingsPageGroup(pt)) break;
            cur = p;
            depth++;
        }
        return best;
    }

    private static boolean looksLikeSettingsPageGroup(String t) {
        if (t == null) return false;
        int hits = 0;
        if (t.contains("聊天")) hits++;
        if (t.contains("音视频通话")) hits++;
        if (t.contains("聊天记录管理")) hits++;
        if (t.contains("其他功能")) hits++;
        if (t.contains("插件")) hits++;
        if (t.contains("帮助与反馈")) hits++;
        if (t.contains("关于微信")) hits++;
        return hits >= 3;
    }

    private static void openModuleSettings(Activity activity) {
        try {
            Intent i = new Intent();
            i.setClassName(Prefs.PACKAGE_NAME, Prefs.PACKAGE_NAME + ".MainActivity");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(i);
        } catch (Throwable t) {
            record(activity, "error", "open module settings failed: " + t.getClass().getSimpleName(), false);
        }
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
        final boolean searchSafe;
        final boolean wechatEntry;
        Config(boolean enabled, List<String> rules, boolean searchSafe, boolean wechatEntry) {
            this.enabled = enabled;
            this.rules = rules == null ? Collections.<String>emptyList() : rules;
            this.searchSafe = searchSafe;
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
