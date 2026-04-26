package net.sockc.wxhide;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Process;

import java.util.List;

public class ConfigProvider extends ContentProvider {
    public static final String AUTHORITY = "net.sockc.wxhide.provider";
    public static final Uri RULES_URI = Uri.parse("content://" + AUTHORITY + "/rules");
    public static final Uri STATUS_URI = Uri.parse("content://" + AUTHORITY + "/status");

    private static final String WECHAT_PACKAGE = "com.tencent.mm";
    private static final String MODULE_PACKAGE = "net.sockc.wxhide";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        Context context = getContext();
        if (context == null || !isCallerAllowed(context)) {
            return emptyRulesCursor();
        }

        String path = uri == null ? "" : uri.getPath();
        if ("/status".equals(path)) {
            MatrixCursor cursor = new MatrixCursor(new String[]{"time", "event", "detail", "enabled", "keyword_count"});
            cursor.addRow(new Object[]{
                    Prefs.getLastStatusTime(context),
                    Prefs.getLastStatusEvent(context),
                    Prefs.getLastStatusDetail(context),
                    Prefs.isEnabled(context) ? 1 : 0,
                    Prefs.parseKeywords(Prefs.getRawKeywords(context)).size()
            });
            return cursor;
        }

        MatrixCursor cursor = new MatrixCursor(new String[]{"enabled", "keyword"});
        boolean enabled = Prefs.isEnabled(context);
        List<String> keywords = Prefs.parseKeywords(Prefs.getRawKeywords(context));

        if (!enabled) {
            cursor.addRow(new Object[]{0, ""});
            return cursor;
        }

        if (keywords.isEmpty()) {
            cursor.addRow(new Object[]{1, ""});
            return cursor;
        }

        for (String keyword : keywords) {
            cursor.addRow(new Object[]{1, keyword});
        }
        return cursor;
    }

    private Cursor emptyRulesCursor() {
        return new MatrixCursor(new String[]{"enabled", "keyword"});
    }

    private boolean isCallerAllowed(Context context) {
        int callingUid = Binder.getCallingUid();
        if (callingUid == Process.myUid()) return true;

        PackageManager pm = context.getPackageManager();
        String[] pkgs = pm.getPackagesForUid(callingUid);
        if (pkgs == null) return false;
        for (String p : pkgs) {
            if (WECHAT_PACKAGE.equals(p) || MODULE_PACKAGE.equals(p)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String getType(Uri uri) { return "vnd.android.cursor.item/vnd.net.sockc.wxhide.rule"; }

    @Override
    public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("read only"); }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException("read only"); }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        Context context = getContext();
        if (context == null || !isCallerAllowed(context)) return 0;
        String path = uri == null ? "" : uri.getPath();
        if (!"/status".equals(path)) return 0;
        String event = values == null ? "" : values.getAsString("event");
        String detail = values == null ? "" : values.getAsString("detail");
        Prefs.saveStatus(context, event, detail);
        return 1;
    }
}
