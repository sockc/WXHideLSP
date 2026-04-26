package net.sockc.wxhide;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class StatusReceiver extends BroadcastReceiver {
    public static final String ACTION = "net.sockc.wxhide.STATUS";
    public static final String EXTRA_EVENT = "event";
    public static final String EXTRA_DETAIL = "detail";
    public static final String EXTRA_HIT = "hit";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String event = intent.getStringExtra(EXTRA_EVENT);
        String detail = intent.getStringExtra(EXTRA_DETAIL);
        boolean hit = intent.getBooleanExtra(EXTRA_HIT, false);
        Prefs.saveStatus(context, event, detail, hit);
    }
}
