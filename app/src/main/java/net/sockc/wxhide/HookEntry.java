package net.sockc.wxhide;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
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
    private static final long CONFIG_TTL_MS = 900L;

    private static volatile boolean initialized = false;
    private static volatile long lastConfigReadAt = 0L;
    private static volatile Config cachedConfig = new Config(true, Collections.<String>emptyList());
    private static final Set<String> hookedAdapterClasses = Collections.synchronizedSet(new HashSet<String>());

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam == null || !Prefs.WECHAT_PACKAGE.equals(lpparam.packageName)) return;

        XposedBridge.log(TAG + ": handleLoadPackage package=" + lpparam.packageName + ", process=" + lpparam.processName);

        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Context context = null;
                try {
                    context = (Context) param.args[0];
                } catch (Throwable ignored) {}
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
            XposedBridge.log(TAG + ": initOnce, rules=" + cfg.rules.size() + ", enabled=" + cfg.enabled);
            record(context, "loaded", "hooks initialized, rules=" + cfg.rules.size() + ", enabled=" + cfg.enabled, false);

            hookTextView(context);
            hookViewGroup(context);
            hookRecyclerView(classLoader, context, "androidx.recyclerview.widget.RecyclerView");
            hookRecyclerView(classLoader, context, "android.support.v7.widget.RecyclerView");
            hookAbsListView(context);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": init failed: " + t);
            record(context, "error", "init failed: " + t.getClass().getSimpleName() + ": " + t.getMessage(), false);
        }
    }

    private static void hookTextView(final Context context) {
        try {
            XposedBridge.hookAllMethods(TextView.class, "setText", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    final Object obj = param.thisObject;
                    if (!(obj instanceof TextView)) return;
                    final View v = (View) obj;
                    v.postDelayed(new Runnable() {
                        @Override public void run() { applyNearView(context, v); }
                    }, 60L);
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
                    child.postDelayed(new Runnable() {
                        @Override public void run() { applyNearView(context, child); }
                    }, 120L);
                }
            });
            XposedBridge.log(TAG + ": hook ViewGroup.addView ok");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook ViewGroup.addView failed: " + t);
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
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
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
                    if (param.args != null && param.args.length > 0 && param.args[0] != null) {
                        hookAdapterClass(param.args[0].getClass(), context);
                    }
                }
            });
            XposedBridge.hookAllMethods(rvClass, "swapAdapter", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args != null && param.args.length > 0 && param.args[0] != null) {
                        hookAdapterClass(param.args[0].getClass(), context);
                    }
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
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object holder = null;
                            if ("onCreateViewHolder".equals(m.getName())) {
                                holder = param.getResult();
                            } else if (param.args != null && param.args.length > 0) {
                                holder = param.args[0];
                            }
                            final View itemView = getItemView(holder);
                            if (itemView != null) {
                                itemView.postDelayed(new Runnable() {
                                    @Override public void run() { applyRoot(context, itemView); }
                                }, 50L);
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
            } catch (Throwable ignored) {
                c = c.getSuperclass();
            }
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
        while (current != null && current.getParent() instanceof View && depth < 10) {
            View parent = (View) current.getParent();
            String n = parent.getClass().getName();
            if (n.contains("RecyclerView") || n.contains("AbsListView") || n.contains("ListView")) {
                return current;
            }
            last = parent;
            current = parent;
            depth++;
        }
        return last;
    }

    private static void applyRoot(Context context, View root) {
        if (root == null || context == null) return;
        Config cfg = readConfig(context, false);
        if (!cfg.enabled || cfg.rules.isEmpty()) {
            restoreIfNeeded(root);
            return;
        }
        String text = collectText(root, 0, new StringBuilder(512)).toString();
        boolean matched = matches(text, cfg.rules);
        if (matched) {
            hide(root);
            record(context, "hit", "matched one rule on view=" + root.getClass().getSimpleName(), true);
        } else {
            restoreIfNeeded(root);
        }
    }

    private static StringBuilder collectText(View v, int depth, StringBuilder sb) {
        if (v == null || depth > 8 || sb.length() > 4096) return sb;
        try {
            if (v instanceof TextView) {
                CharSequence cs = ((TextView) v).getText();
                if (cs != null) sb.append(cs).append('\n');
            }
            CharSequence cd = v.getContentDescription();
            if (cd != null) sb.append(cd).append('\n');
            if (v instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) v;
                int count = Math.min(vg.getChildCount(), 80);
                for (int i = 0; i < count; i++) collectText(vg.getChildAt(i), depth + 1, sb);
            }
        } catch (Throwable ignored) {}
        return sb;
    }

    private static boolean matches(String text, List<String> rules) {
        if (text == null || text.length() == 0) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        for (String r : rules) {
            if (r == null) continue;
            String rule = r.trim();
            if (rule.length() == 0) continue;
            if (lower.contains(rule.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private static void hide(View root) {
        try {
            Object old = root.getTag(TAG_HIDE_STATE);
            if (!(old instanceof HideState)) {
                ViewGroup.LayoutParams lp = root.getLayoutParams();
                int h = lp == null ? ViewGroup.LayoutParams.WRAP_CONTENT : lp.height;
                root.setTag(TAG_HIDE_STATE, new HideState(root.getVisibility(), root.getAlpha(), root.getMinimumHeight(), h));
            }
            root.setAlpha(0f);
            root.setVisibility(View.GONE);
            root.setMinimumHeight(0);
            ViewGroup.LayoutParams lp = root.getLayoutParams();
            if (lp != null && lp.height != 0) {
                lp.height = 0;
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
            ViewGroup.LayoutParams lp = root.getLayoutParams();
            if (lp != null) {
                lp.height = st.height;
                root.setLayoutParams(lp);
            }
        } catch (Throwable ignored) {}
    }

    private static Config readConfig(Context context, boolean force) {
        long now = SystemClock.uptimeMillis();
        if (!force && now - lastConfigReadAt < CONFIG_TTL_MS) return cachedConfig;
        lastConfigReadAt = now;

        boolean enabled = true;
        String rules = "";

        try {
            enabled = Prefs.getEnabledFromGlobal(context);
            rules = Prefs.getRulesFromGlobal(context);
        } catch (Throwable ignored) {}

        if (rules == null || rules.trim().length() == 0) {
            try {
                Cursor c = context.getContentResolver().query(Uri.parse("content://net.sockc.wxhide.provider/config"), null, null, null, null);
                if (c != null) {
                    try {
                        if (c.moveToFirst()) {
                            enabled = "1".equals(c.getString(0));
                            rules = c.getString(1);
                        }
                    } finally {
                        c.close();
                    }
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
        cachedConfig = new Config(enabled, list);
        return cachedConfig;
    }

    private static void record(Context context, String event, String detail, boolean hit) {
        try {
            XposedBridge.log(TAG + ": " + event + " - " + detail);
        } catch (Throwable ignored) {}
        try {
            Intent i = new Intent(StatusReceiver.ACTION);
            i.setClassName(Prefs.PACKAGE_NAME, Prefs.PACKAGE_NAME + ".StatusReceiver");
            i.putExtra(StatusReceiver.EXTRA_EVENT, event);
            i.putExtra(StatusReceiver.EXTRA_DETAIL, detail);
            i.putExtra(StatusReceiver.EXTRA_HIT, hit);
            context.sendBroadcast(i);
        } catch (Throwable ignored) {}
    }

    private static final class Config {
        final boolean enabled;
        final List<String> rules;
        Config(boolean enabled, List<String> rules) {
            this.enabled = enabled;
            this.rules = rules == null ? Collections.<String>emptyList() : rules;
        }
    }

    private static final class HideState {
        final int visibility;
        final float alpha;
        final int minHeight;
        final int height;
        HideState(int visibility, float alpha, int minHeight, int height) {
            this.visibility = visibility;
            this.alpha = alpha;
            this.minHeight = minHeight;
            this.height = height;
        }
    }
}
