package net.sockc.wxhide;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

public class ConfigProvider extends ContentProvider {
    public static final String AUTH = "net.sockc.wxhide.provider";
    public static final Uri CONFIG_URI = Uri.parse("content://" + AUTH + "/config");

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        MatrixCursor cursor = new MatrixCursor(new String[]{"enabled", "rules"});
        if (getContext() != null) {
            cursor.addRow(new Object[]{Prefs.getEnabledLocal(getContext()) ? "1" : "0", Prefs.getRulesLocal(getContext())});
        } else {
            cursor.addRow(new Object[]{"1", ""});
        }
        return cursor;
    }

    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
