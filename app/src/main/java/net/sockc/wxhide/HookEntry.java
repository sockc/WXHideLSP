package net.sockc.wxhide;

import android.app.Activity;
import de.robv.android.xposed.AndroidAppHelper;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.AbsListView;

import java.lang.reflect.Field;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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

    // 尽量避开常见资源 ID。用于缓存 View 原始状态，避免 RecyclerView 复用后一直隐藏。
    private static final int TAG_ORIGINAL_HEIGHT = 0x72684351;
    private static final int TAG_ORIGINAL_VISIBILITY = 0x72684352;
    private static final int TAG_ORIGINAL_ALPHA = 0x72684353;

    private static final AtomicBoolean hooksInstalled = new AtomicBoolean(false);
    private static volatile long lastLoadAt = 0L;
    private static volatile List<String> cachedKeywords = Collections.emptyList();
    private static volatile boolean cachedEnabled = true;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!WECHAT_PACKAGE.equals(lpparam.packageName)) return;
        if (!WECHAT_PACKAGE.equals(lpparam.processName)) return;

        if (!hooksInstalled.compareAndSet(false, true)) return;

        log("loaded in WeChat: " + lpparam.packageName + ", process=" + lpparam.processName);
        hookActivityResume();
        hookRecyclerView(lpparam.classLoader);
        hookAbsListView();
    }

    private void hookActivityResume() {
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Context context = ((Activity) param.thisObject).getApplicationContext();
                    reloadRules(context, true);
                }
            });
            log("hook Activity.onResume ok");
        } catch (Throwable t) {
            log("hook Activity.onResume failed", t);
        }
    }

    /**
     * 微信新版大量列表使用 RecyclerView。这里 Hook 基类三参数 onBindViewHolder：
     * 大多数 Adapter 即使重写二参数 onBindViewHolder，也会被三参数方法包一层调用，兼容性较好。
     */
    private void hookRecyclerView(ClassLoader classLoader) {
        hookRecyclerViewByName(classLoader, "androidx.recyclerview.widget.RecyclerView$Adapter", "androidx.recyclerview.widget.RecyclerView$ViewHolder");
        hookRecyclerViewByName(classLoader, "android.support.v7.widget.RecyclerView$Adapter", "android.support.v7.widget.RecyclerView$ViewHolder");
    }

    private void hookRecyclerViewByName(ClassLoader classLoader, String adapterName, String holderName) {
        try {
            Class<?> adapterClass = findClassOrNull(adapterName, classLoader);
            Class<?> holderClass = findClassOrNull(holderName, classLoader);
            if (adapterClass == null || holderClass == null) {
                log("skip RecyclerView hook, class not found: " + adapterName);
                return;
            }

            XposedHelpers.findAndHookMethod(adapterClass, "onBindViewHolder", holderClass, int.class, List.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object holder = param.args[0];
                    if (holder == null) return;
                    View itemView = getItemView(holder);
                    if (itemView == null) return;
                    applyRuleToView(itemView.getContext(), itemView);
                }
            });
            log("hook RecyclerView ok: " + adapterName);
        } catch (Throwable t) {
            log("hook RecyclerView failed: " + adapterName, t);
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

    private void applyRuleToView(Context context, View view) {
        if (context == null) context = AndroidAppHelper.currentApplication();
        List<String> keywords = reloadRules(context, false);
        boolean hide = cachedEnabled && !keywords.isEmpty() && containsKeyword(view, keywords);
        setHiddenState(view, hide);
    }

    private List<String> reloadRules(Context context, boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastLoadAt < 1200L) return cachedKeywords;
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
            return cachedKeywords;
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    private boolean containsKeyword(View root, List<String> keywords) {
        StringBuilder sb = new StringBuilder(256);
        collectVisibleText(root, sb, 0, 0);
        String text = Prefs.normalize(sb.toString());
        if (text.isEmpty()) return false;

        for (String keyword : keywords) {
            if (!keyword.isEmpty() && text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private int collectVisibleText(View view, StringBuilder out, int depth, int count) {
        if (view == null || out.length() > 3000 || depth > 8 || count > 120) return count;
        if (view.getVisibility() != View.VISIBLE) return count;

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
                count = collectVisibleText(group.getChildAt(i), out, depth + 1, count);
                if (count > 120) break;
            }
        }
        return count;
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
            } else if (view.getVisibility() == View.INVISIBLE) {
                view.setVisibility(View.VISIBLE);
            }

            if (oldAlpha instanceof Float) {
                view.setAlpha((Float) oldAlpha);
            } else if (view.getAlpha() == 0f) {
                view.setAlpha(1f);
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

    private static void log(String msg) {
        XposedBridge.log(TAG + ": " + msg);
    }

    private static void log(String msg, Throwable t) {
        XposedBridge.log(TAG + ": " + msg);
        XposedBridge.log(t);
    }
}
