package net.sockc.wxhide;

import android.app.Activity;
import android.app.Application;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class HookEntry implements IXposedHookLoadPackage {
    private static final String TAG = "WXHideLSP";
    private static final String WECHAT_PACKAGE = "com.tencent.mm";
    private static final Uri RULES_URI = Uri.parse("content://net.sockc.wxhide.provider/rules");
    private static final Uri STATUS_URI = Uri.parse("content://net.sockc.wxhide.provider/status");

    // 用高位自定义 key 缓存原始状态，避免 RecyclerView 复用后一直隐藏。
    private static final int TAG_ORIGINAL_HEIGHT = 0x72684351;
    private static final int TAG_ORIGINAL_VISIBILITY = 0x72684352;
    private static final int TAG_ORIGINAL_ALPHA = 0x72684353;

    private static final AtomicBoolean fullHooksInstalled = new AtomicBoolean(false);
    private static final AtomicBoolean attachProbeInstalled = new AtomicBoolean(false);
    private static final Set<String> hookedAdapterClasses = Collections.synchronizedSet(new HashSet<String>());
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static volatile long lastLoadAt = 0L;
    private static volatile List<String> cachedKeywords = Collections.emptyList();
    private static volatile boolean cachedEnabled = true;
    private static volatile long lastReportAt = 0L;

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        // v0.1.5：先对所有 LSPosed 已勾选作用域安装 Application.attach 探针。
        // 这样可以区分“模块没有注入”和“微信 Hook 没命中”。
        log("handleLoadPackage probe: package=" + lpparam.packageName + ", process=" + lpparam.processName);
        hookApplicationAttachProbe(lpparam);

        if (!WECHAT_PACKAGE.equals(lpparam.packageName)) return;

        // 不限制 processName。部分微信/分身环境下 UI 入口不一定按预期出现在主进程。
        if (!fullHooksInstalled.compareAndSet(false, true)) return;

        log("handleLoadPackage wechat: package=" + lpparam.packageName + ", process=" + lpparam.processName);
        hookActivityResume();
        hookRecyclerView(lpparam.classLoader);
        hookAbsListView();
        hookTextViewTextChange();
    }

    private void hookApplicationAttachProbe(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (!attachProbeInstalled.compareAndSet(false, true)) return;
        try {
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Context context = null;
                    if (param.args != null && param.args.length > 0 && param.args[0] instanceof Context) {
                        context = ((Context) param.args[0]).getApplicationContext();
                    }
                    if (context == null && param.thisObject instanceof Application) {
                        context = ((Application) param.thisObject).getApplicationContext();
                    }
                    if (context == null) return;

                    if (WECHAT_PACKAGE.equals(lpparam.packageName)) {
                        reloadRules(context, true);
                        reportStatus(context, "attach", "Application.attach: package=" + lpparam.packageName + ", process=" + lpparam.processName);
                    } else {
                        reportStatus(context, "probe", "Application.attach: package=" + lpparam.packageName + ", process=" + lpparam.processName);
                    }
                }
            });
            log("hook Application.attach probe ok");
        } catch (Throwable t) {
            log("hook Application.attach probe failed", t);
        }
    }
    private void hookActivityResume() {
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Activity activity = (Activity) param.thisObject;
                    Context context = activity.getApplicationContext();
                    reloadRules(context, true);
                    reportStatus(context, "loaded", "Activity.onResume: " + activity.getClass().getName());
                    scanActivityLater(activity, 120L);
                    scanActivityLater(activity, 520L);
                }
            });
            log("hook Activity.onResume ok");
        } catch (Throwable t) {
            log("hook Activity.onResume failed", t);
        }
    }

    private void hookRecyclerView(ClassLoader classLoader) {
        hookRecyclerViewByName(classLoader, "androidx.recyclerview.widget.RecyclerView", "androidx.recyclerview.widget.RecyclerView$Adapter", "androidx.recyclerview.widget.RecyclerView$ViewHolder");
        hookRecyclerViewByName(classLoader, "android.support.v7.widget.RecyclerView", "android.support.v7.widget.RecyclerView$Adapter", "android.support.v7.widget.RecyclerView$ViewHolder");
    }

    private void hookRecyclerViewByName(ClassLoader classLoader, String recyclerName, String adapterName, String holderName) {
        try {
            Class<?> recyclerClass = findClassOrNull(recyclerName, classLoader);
            Class<?> adapterClass = findClassOrNull(adapterName, classLoader);
            Class<?> holderClass = findClassOrNull(holderName, classLoader);
            if (recyclerClass == null || adapterClass == null || holderClass == null) {
                log("skip RecyclerView hook, class not found: " + recyclerName);
                return;
            }

            hookAdapterClass(adapterClass);

            // 微信很多 Adapter 会重写 onBindViewHolder；只 Hook 基类不够。
            // 这里在 RecyclerView.setAdapter 时动态 Hook 真实 Adapter 类。
            XposedHelpers.findAndHookMethod(recyclerClass, "setAdapter", adapterClass, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object adapter = param.args[0];
                    if (adapter != null) {
                        hookAdapterClass(adapter.getClass());
                    }
                }
            });
            log("hook RecyclerView.setAdapter ok: " + recyclerName);
        } catch (Throwable t) {
            log("hook RecyclerView failed: " + recyclerName, t);
        }
    }

    private void hookAdapterClass(Class<?> adapterClass) {
        if (adapterClass == null) return;
        String name = adapterClass.getName();
        if (!hookedAdapterClasses.add(name)) return;

        XC_MethodHook bindHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.args == null || param.args.length == 0) return;
                View itemView = getItemView(param.args[0]);
                if (itemView == null) return;
                applyRuleToView(itemView.getContext(), itemView);
            }
        };

        XC_MethodHook attachHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.args == null || param.args.length == 0) return;
                View itemView = getItemView(param.args[0]);
                if (itemView == null) return;
                applyRuleToView(itemView.getContext(), itemView);
            }
        };

        try {
            XposedBridge.hookAllMethods(adapterClass, "onBindViewHolder", bindHook);
            log("hook Adapter.onBindViewHolder ok: " + name);
        } catch (Throwable t) {
            log("hook Adapter.onBindViewHolder failed: " + name, t);
        }

        try {
            XposedBridge.hookAllMethods(adapterClass, "onViewAttachedToWindow", attachHook);
            log("hook Adapter.onViewAttachedToWindow ok: " + name);
        } catch (Throwable ignored) {
            // 不是所有 Adapter 都有这个方法；忽略即可。
        }
    }

    /**
     * 兼容旧页面 ListView，例如部分联系人页、搜索页、设置页仍可能使用 AbsListView。
     */
    private void hookAbsListView() {
        try {
            XposedHelpers.findAndHookMethod(AbsListView.class, "obtainView", int.class, boolean[].class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object result = param.getResult();
                    if (!(result instanceof View)) return;
                    View view = (View) result;
                    applyRuleToView(view.getContext(), view);
                }
            });
            log("hook AbsListView.obtainView ok");
        } catch (Throwable t) {
            log("hook AbsListView.obtainView failed", t);
        }
    }

    /**
     * 兜底 Hook：有些微信页面文字是异步设置的，Adapter bind 时 item 里还没有最终文字。
     * 这里监听 TextView.setText，命中关键词后只隐藏最近的列表项，不隐藏整个页面。
     */
    private void hookTextViewTextChange() {
        try {
            XposedHelpers.findAndHookMethod(TextView.class, "setText", CharSequence.class, TextView.BufferType.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    final TextView tv = (TextView) param.thisObject;
                    if (tv == null) return;
                    Context context = tv.getContext();
                    if (context == null) return;
                    if (!maybeContainsKeyword(context, tv.getText())) return;
                    tv.post(new Runnable() {
                        @Override
                        public void run() {
                            View item = findNearestListItem(tv);
                            if (item != null) applyRuleToView(item.getContext(), item);
                        }
                    });
                }
            });
            log("hook TextView.setText ok");
        } catch (Throwable t) {
            log("hook TextView.setText failed", t);
        }

        try {
            XposedHelpers.findAndHookMethod(View.class, "setContentDescription", CharSequence.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    final View view = (View) param.thisObject;
                    if (view == null) return;
                    Context context = view.getContext();
                    if (context == null) return;
                    if (!maybeContainsKeyword(context, view.getContentDescription())) return;
                    view.post(new Runnable() {
                        @Override
                        public void run() {
                            View item = findNearestListItem(view);
                            if (item != null) applyRuleToView(item.getContext(), item);
                        }
                    });
                }
            });
            log("hook View.setContentDescription ok");
        } catch (Throwable t) {
            log("hook View.setContentDescription failed", t);
        }
    }

    private Class<?> findClassOrNull(String name, ClassLoader classLoader) {
        try {
            ClassLoader loader = classLoader != null ? classLoader : HookEntry.class.getClassLoader();
            return Class.forName(name, false, loader);
        } catch (Throwable ignored) {
            try {
                return Class.forName(name);
            } catch (Throwable ignoredAgain) {
                return null;
            }
        }
    }

    private View getItemView(Object holder) {
        if (holder == null) return null;
        try {
            Class<?> c = holder.getClass();
            while (c != null) {
                try {
                    Field field = c.getDeclaredField("itemView");
                    field.setAccessible(true);
                    Object value = field.get(holder);
                    return value instanceof View ? (View) value : null;
                } catch (NoSuchFieldException ignored) {
                    c = c.getSuperclass();
                }
            }
        } catch (Throwable t) {
            log("read RecyclerView.ViewHolder.itemView failed", t);
        }
        return null;
    }

    private void scanActivityLater(final Activity activity, long delayMs) {
        MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (activity == null || activity.isFinishing()) return;
                    View root = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
                    if (root == null) return;
                    scanListItems(activity.getApplicationContext(), root, 0);
                } catch (Throwable t) {
                    log("scan activity failed", t);
                }
            }
        }, delayMs);
    }

    private void scanListItems(Context context, View view, int depth) {
        if (context == null || view == null || depth > 12) return;
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        int count = group.getChildCount();
        for (int i = 0; i < count; i++) {
            View child = group.getChildAt(i);
            if (isListContainer(group)) {
                applyRuleToView(context, child);
            } else {
                scanListItems(context, child, depth + 1);
            }
        }
    }

    private void applyRuleToView(Context context, View view) {
        if (context == null || view == null) return;
        // 如果这个 View 上一次被隐藏，先恢复后再读取文本，避免因为 INVISIBLE 导致读不到文字。
        if (view.getTag(TAG_ORIGINAL_VISIBILITY) != null) {
            setHiddenState(view, false);
        }

        List<String> keywords = reloadRules(context, false);
        boolean hide = cachedEnabled && !keywords.isEmpty() && containsKeyword(view, keywords);
        setHiddenState(view, hide);

        if (hide) {
            reportStatus(context, "hit", "hidden item: " + previewText(view));
        }
    }

    private boolean maybeContainsKeyword(Context context, CharSequence text) {
        if (context == null || text == null) return false;
        List<String> keywords = reloadRules(context, false);
        if (!cachedEnabled || keywords.isEmpty()) return false;
        String s = Prefs.normalize(text.toString());
        if (s.isEmpty()) return false;
        for (String keyword : keywords) {
            if (!keyword.isEmpty() && s.contains(keyword)) return true;
        }
        return false;
    }

    private List<String> reloadRules(Context context, boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastLoadAt < 900L) return cachedKeywords;
        lastLoadAt = now;

        if (context == null) return cachedKeywords;

        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(RULES_URI, null, null, null, null);
            if (cursor == null) return cachedKeywords;

            List<String> next = new ArrayList<>();
            boolean enabled = true;
            int enabledIdx = cursor.getColumnIndex("enabled");
            int keywordIdx = cursor.getColumnIndex("keyword");

            while (cursor.moveToNext()) {
                if (enabledIdx >= 0) enabled = cursor.getInt(enabledIdx) == 1;
                if (keywordIdx >= 0) {
                    String keyword = Prefs.normalize(cursor.getString(keywordIdx));
                    if (!keyword.isEmpty()) next.add(keyword);
                }
            }
            cachedEnabled = enabled;
            cachedKeywords = next;
            return cachedKeywords;
        } catch (Throwable t) {
            log("reload rules failed", t);
            reportStatus(context, "error", "reload rules failed: " + t.getClass().getSimpleName());
            return cachedKeywords;
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    private boolean containsKeyword(View root, List<String> keywords) {
        String text = Prefs.normalize(previewText(root));
        if (text.isEmpty()) return false;

        for (String keyword : keywords) {
            if (!keyword.isEmpty() && text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String previewText(View root) {
        StringBuilder sb = new StringBuilder(256);
        collectText(root, sb, 0, 0);
        String s = sb.toString().replace('\n', ' ').replace('\r', ' ').trim();
        if (s.length() > 80) s = s.substring(0, 80);
        return s;
    }

    private int collectText(View view, StringBuilder out, int depth, int count) {
        if (view == null || out.length() > 3000 || depth > 8 || count > 120) return count;

        CharSequence cd = view.getContentDescription();
        if (cd != null && cd.length() > 0) {
            out.append(' ').append(cd);
        }

        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null && text.length() > 0) {
                out.append(' ').append(text);
            }
        }

        count++;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            int childCount = group.getChildCount();
            for (int i = 0; i < childCount; i++) {
                count = collectText(group.getChildAt(i), out, depth + 1, count);
                if (count > 120) break;
            }
        }
        return count;
    }

    private View findNearestListItem(View child) {
        if (child == null) return null;
        View current = child;
        for (int i = 0; i < 12; i++) {
            ViewParent parent = current.getParent();
            if (parent == null) return null;
            if (isListContainer(parent)) return current;
            if (parent instanceof View) {
                current = (View) parent;
            } else {
                return null;
            }
        }
        return null;
    }

    private boolean isListContainer(Object object) {
        if (object == null) return false;
        if (object instanceof AbsListView) return true;
        if (object instanceof AdapterView) return true;
        String name = object.getClass().getName();
        return name.contains("RecyclerView") || name.contains("WxRecyclerView") || name.contains("ListView");
    }

    private void setHiddenState(View view, boolean hide) {
        if (view == null) return;
        ViewGroup.LayoutParams lp = view.getLayoutParams();

        if (hide) {
            if (view.getTag(TAG_ORIGINAL_VISIBILITY) == null) {
                view.setTag(TAG_ORIGINAL_VISIBILITY, view.getVisibility());
            }
            if (view.getTag(TAG_ORIGINAL_ALPHA) == null) {
                view.setTag(TAG_ORIGINAL_ALPHA, view.getAlpha());
            }
            if (lp != null && view.getTag(TAG_ORIGINAL_HEIGHT) == null) {
                view.setTag(TAG_ORIGINAL_HEIGHT, lp.height);
            }

            view.setAlpha(0f);
            view.setVisibility(View.INVISIBLE);
            view.setMinimumHeight(0);
            if (lp != null) {
                lp.height = 1;
                view.setLayoutParams(lp);
            }
        } else {
            Object oldVisibility = view.getTag(TAG_ORIGINAL_VISIBILITY);
            Object oldAlpha = view.getTag(TAG_ORIGINAL_ALPHA);
            Object oldHeight = view.getTag(TAG_ORIGINAL_HEIGHT);

            if (oldVisibility instanceof Integer) {
                view.setVisibility((Integer) oldVisibility);
            }

            if (oldAlpha instanceof Float) {
                view.setAlpha((Float) oldAlpha);
            }

            if (lp != null && oldHeight instanceof Integer) {
                lp.height = (Integer) oldHeight;
                view.setLayoutParams(lp);
            }

            view.setTag(TAG_ORIGINAL_VISIBILITY, null);
            view.setTag(TAG_ORIGINAL_ALPHA, null);
            view.setTag(TAG_ORIGINAL_HEIGHT, null);
        }
    }

    private void reportStatus(Context context, String event, String detail) {
        if (context == null) return;
        long now = System.currentTimeMillis();
        if (now - lastReportAt < 700L && !"hit".equals(event) && !"error".equals(event)) return;
        lastReportAt = now;
        try {
            ContentValues values = new ContentValues();
            values.put("event", event == null ? "" : event);
            values.put("detail", detail == null ? "" : detail);
            context.getContentResolver().update(STATUS_URI, values, null, null);
        } catch (Throwable t) {
            log("report status failed", t);
        }
    }

    private static void log(String msg) {
        XposedBridge.log(TAG + ": " + msg);
    }

    private static void log(String msg, Throwable t) {
        XposedBridge.log(TAG + ": " + msg);
        XposedBridge.log(t);
    }
}
