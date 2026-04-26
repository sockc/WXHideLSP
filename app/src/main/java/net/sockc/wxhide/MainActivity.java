package net.sockc.wxhide;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Date;

public class MainActivity extends Activity {
    private TextView statusText;
    private TextView configText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        refreshStatus();
    }

    private void buildUi() {
        int pad = dp(18);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("WX Hide LSP 状态");
        title.setTextSize(28);
        title.setGravity(Gravity.START);
        title.setPadding(0, 0, 0, dp(12));
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView tips = new TextView(this);
        tips.setText("v0.2.5 开始，APK 桌面页只做模块状态显示。\n"
                + "规则、开关、隐藏桌面图标等功能请从微信：我 → 设置 → 功能 → WX Hide LSP 打开。\n"
                + "如果你已经隐藏桌面图标，也可以继续通过微信设置入口进入配置页。");
        tips.setTextSize(15);
        tips.setPadding(0, 0, 0, dp(12));
        root.addView(tips, new LinearLayout.LayoutParams(-1, -2));

        configText = new TextView(this);
        configText.setTextSize(15);
        configText.setPadding(0, dp(8), 0, dp(8));
        root.addView(configText, new LinearLayout.LayoutParams(-1, -2));

        statusText = new TextView(this);
        statusText.setTextSize(15);
        statusText.setPadding(0, dp(8), 0, dp(8));
        root.addView(statusText, new LinearLayout.LayoutParams(-1, -2));

        Button refresh = button("刷新模块状态");
        refresh.setOnClickListener(v -> refreshStatus());
        root.addView(refresh);

        Button openConfig = button("打开配置页（备用入口）");
        openConfig.setOnClickListener(v -> {
            try {
                Intent i = new Intent(this, ConfigActivity.class);
                startActivity(i);
            } catch (Throwable t) {
                Toast.makeText(this, "打开失败：" + t.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
            }
        });
        root.addView(openConfig);

        TextView note = new TextView(this);
        note.setText("说明：\n"
                + "1. loaded/attach 表示 LSPosed 已注入微信。\n"
                + "2. hit 表示已经命中过隐藏规则。\n"
                + "3. 安全文件夹下状态广播可能偶尔延迟，必要时同时查看 LSPosed 日志，搜索 WXHideLSP。\n"
                + "4. 备用入口只是防止微信入口失效，日常配置建议从微信设置菜单进入。");
        note.setTextSize(14);
        note.setPadding(0, dp(18), 0, dp(24));
        root.addView(note, new LinearLayout.LayoutParams(-1, -2));

        setContentView(scroll);
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(56));
        lp.setMargins(0, dp(8), 0, dp(2));
        b.setLayoutParams(lp);
        return b;
    }

    private void refreshStatus() {
        boolean enabled = Prefs.getEnabledLocal(this);
        String rules = Prefs.getRulesLocal(this);
        boolean deep = Prefs.getDeepSearchLocal(this);
        boolean entry = Prefs.getWechatEntryLocal(this);
        boolean launcher = Prefs.getLauncherVisibleLocal(this);
        int count = 0;
        if (rules != null) {
            String[] arr = rules.split("\\r?\\n");
            for (String s : arr) if (s.trim().length() > 0) count++;
        }
        configText.setText("当前配置：" + (enabled ? "已启用" : "已关闭")
                + "\n关键词数量：" + count
                + "\n搜索安全隐藏：" + (deep ? "已启用" : "已关闭")
                + "\n微信设置入口：" + (entry ? "已启用" : "已关闭")
                + "\n桌面图标：" + (launcher ? "显示" : "隐藏"));

        SharedPreferences sp = Prefs.sp(this);
        String event = sp.getString(Prefs.KEY_LAST_EVENT, "");
        String detail = sp.getString(Prefs.KEY_LAST_DETAIL, "");
        long time = sp.getLong(Prefs.KEY_LAST_TIME, 0L);
        int hits = sp.getInt(Prefs.KEY_HIT_COUNT, 0);
        String when = time > 0 ? DateFormat.format("yyyy-MM-dd HH:mm:ss", new Date(time)).toString() : "无";
        if (event == null || event.length() == 0) {
            statusText.setText("LSPosed 状态：暂无加载记录\n命中次数：" + hits + "\n提示：打开微信后再刷新；安全文件夹下也可查看 LSPosed 日志。搜索关键字：WXHideLSP");
        } else {
            statusText.setText("LSPosed 状态：" + event + "\n时间：" + when + "\n详情：" + detail + "\n命中次数：" + hits);
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
