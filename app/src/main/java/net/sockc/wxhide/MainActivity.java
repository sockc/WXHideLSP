package net.sockc.wxhide;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Date;

public class MainActivity extends Activity {
    private CheckBox enableBox;
    private CheckBox deepSearchBox;
    private CheckBox wechatEntryBox;
    private CheckBox launcherVisibleBox;
    private EditText rulesEdit;
    private TextView configText;
    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        loadConfig();
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
        title.setText("WX Hide LSP v0.2.2");
        title.setTextSize(28);
        title.setGravity(Gravity.START);
        title.setPadding(0, 0, 0, dp(12));
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView tips = new TextView(this);
        tips.setText("v0.2.2 改为安全隐藏：只隐藏命中的联系人行和聊天记录行，不再隐藏网络搜索、朋友圈、发现页或大面积空白容器。\n"
                + "微信设置入口改为只尝试注入功能菜单；失败时不再使用底部悬浮入口，避免挡住微信设置页。\n"
                + "每行一个关键词，建议填完整备注名；本版仍会自动从 A0英智 这类规则提取 英智 作为别名。");
        tips.setTextSize(14);
        tips.setPadding(0, 0, 0, dp(12));
        root.addView(tips, new LinearLayout.LayoutParams(-1, -2));

        enableBox = new CheckBox(this);
        enableBox.setText("启用隐藏规则");
        enableBox.setTextSize(18);
        root.addView(enableBox, new LinearLayout.LayoutParams(-1, -2));

        deepSearchBox = new CheckBox(this);
        deepSearchBox.setText("搜索页安全隐藏（仅隐藏联系人/聊天记录行）");
        deepSearchBox.setTextSize(18);
        root.addView(deepSearchBox, new LinearLayout.LayoutParams(-1, -2));

        wechatEntryBox = new CheckBox(this);
        wechatEntryBox.setText("在微信设置页显示模块入口");
        wechatEntryBox.setTextSize(18);
        root.addView(wechatEntryBox, new LinearLayout.LayoutParams(-1, -2));

        launcherVisibleBox = new CheckBox(this);
        launcherVisibleBox.setText("显示桌面图标");
        launcherVisibleBox.setTextSize(18);
        root.addView(launcherVisibleBox, new LinearLayout.LayoutParams(-1, -2));

        rulesEdit = new EditText(this);
        rulesEdit.setTextSize(18);
        rulesEdit.setGravity(Gravity.TOP | Gravity.START);
        rulesEdit.setMinLines(7);
        rulesEdit.setSingleLine(false);
        rulesEdit.setHint("每行一个关键词，例如：\n张三\n某某群\n完整备注名\n短别名");
        root.addView(rulesEdit, new LinearLayout.LayoutParams(-1, dp(230)));

        Button save = button("保存规则（推荐，无需 ROOT）");
        save.setOnClickListener(v -> saveLocalOnly());
        root.addView(save);

        Button writeGlobal = button("可选：写入安全文件夹备用全局配置（需要 ROOT）");
        writeGlobal.setOnClickListener(v -> writeGlobalConfig());
        root.addView(writeGlobal);

        Button stopWechat = button("尝试强停微信（需要 ROOT）");
        stopWechat.setOnClickListener(v -> forceStopWechatOnly());
        root.addView(stopWechat);

        Button disable = button("临时关闭模块规则");
        disable.setOnClickListener(v -> {
            enableBox.setChecked(false);
            saveLocalOnly();
        });
        root.addView(disable);

        configText = new TextView(this);
        configText.setTextSize(15);
        configText.setPadding(0, dp(16), 0, dp(8));
        root.addView(configText, new LinearLayout.LayoutParams(-1, -2));

        statusText = new TextView(this);
        statusText.setTextSize(15);
        statusText.setPadding(0, dp(8), 0, dp(8));
        root.addView(statusText, new LinearLayout.LayoutParams(-1, -2));

        Button refresh = button("刷新模块状态");
        refresh.setOnClickListener(v -> refreshStatus());
        root.addView(refresh);

        Button readGlobal = button("读取当前全局配置");
        readGlobal.setOnClickListener(v -> readGlobalConfig());
        root.addView(readGlobal);

        TextView note = new TextView(this);
        note.setText("说明：\n"
                + "1. 显示 hit 代表 LSPosed 注入和隐藏命中正常。\n"
                + "2. 本版不会隐藏搜索网络结果，避免返回后通讯录白屏、朋友圈黑屏。\n"
                + "3. 微信设置入口只尝试注入到设置功能列表；失败时不会显示底部悬浮入口。\n"
                + "4. 隐藏桌面图标前，建议先确认微信设置页入口能打开配置页。\n"
                + "5. 修改规则后建议强停/重开微信。没有 ROOT 就手动强停。\n"
                + "6. 不建议使用过短关键词，容易误隐藏正常联系人。");
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

    private void loadConfig() {
        boolean enabled = Prefs.getEnabledLocal(this);
        String rules = Prefs.getRulesLocal(this);
        boolean deep = Prefs.getDeepSearchLocal(this);
        boolean entry = Prefs.getWechatEntryLocal(this);
        boolean launcher = Prefs.getLauncherVisibleLocal(this);
        enableBox.setChecked(enabled);
        deepSearchBox.setChecked(deep);
        wechatEntryBox.setChecked(entry);
        launcherVisibleBox.setChecked(launcher);
        rulesEdit.setText(rules);
        updateConfigText(enabled, rules, deep, entry, launcher);
    }

    private void saveLocalOnly() {
        boolean enabled = enableBox.isChecked();
        String rules = rulesEdit.getText() == null ? "" : rulesEdit.getText().toString();
        boolean deep = deepSearchBox.isChecked();
        boolean entry = wechatEntryBox.isChecked();
        boolean launcher = launcherVisibleBox.isChecked();
        if (!launcher && !entry) {
            Toast.makeText(this, "建议先开启微信设置页入口，再隐藏桌面图标", Toast.LENGTH_LONG).show();
        }
        Prefs.saveLocal(this, enabled, rules, deep, entry, launcher);
        updateConfigText(enabled, rules, deep, entry, launcher);
        Toast.makeText(this, "已保存；修改后建议强停/重开微信", Toast.LENGTH_LONG).show();
    }

    private void writeGlobalConfig() {
        boolean enabled = enableBox.isChecked();
        String rules = rulesEdit.getText() == null ? "" : rulesEdit.getText().toString();
        boolean deep = deepSearchBox.isChecked();
        boolean entry = wechatEntryBox.isChecked();
        boolean launcher = launcherVisibleBox.isChecked();
        Prefs.saveLocal(this, enabled, rules, deep, entry, launcher);
        boolean globalOk = Prefs.putGlobalViaRoot(enabled, rules, deep, entry);
        updateConfigText(enabled, rules, deep, entry, launcher);
        Toast.makeText(this, globalOk ? "已写入全局备用配置" : "全局配置写入失败：未授权 ROOT 或 su 不可用；如果已经 hit，可忽略", Toast.LENGTH_LONG).show();
    }

    private void forceStopWechatOnly() {
        boolean stopOk = Prefs.forceStopWechatViaRoot();
        Toast.makeText(this, stopOk ? "已强停微信" : "强停微信失败：未授权 ROOT 或 su 不可用", Toast.LENGTH_LONG).show();
    }

    private void updateConfigText(boolean enabled, String rules, boolean deep, boolean entry, boolean launcher) {
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
    }

    private void refreshStatus() {
        SharedPreferences sp = Prefs.sp(this);
        String event = sp.getString(Prefs.KEY_LAST_EVENT, "");
        String detail = sp.getString(Prefs.KEY_LAST_DETAIL, "");
        long time = sp.getLong(Prefs.KEY_LAST_TIME, 0L);
        int hits = sp.getInt(Prefs.KEY_HIT_COUNT, 0);
        String when = time > 0 ? DateFormat.format("yyyy-MM-dd HH:mm:ss", new Date(time)).toString() : "无";
        if (event == null || event.length() == 0) {
            statusText.setText("LSPosed 状态：暂无加载记录\n命中次数：" + hits + "\n提示：安全文件夹下状态广播可能被跨用户隔离，请同时查看 LSPosed 日志。搜索关键字：WXHideLSP");
        } else {
            statusText.setText("LSPosed 状态：" + event + "\n时间：" + when + "\n详情：" + detail + "\n命中次数：" + hits);
        }
    }

    private void readGlobalConfig() {
        String enabled = Prefs.runShellRead("settings get global " + Prefs.GLOBAL_ENABLED);
        String b64 = Prefs.runShellRead("settings get global " + Prefs.GLOBAL_RULES_B64);
        String deep = Prefs.runShellRead("settings get global " + Prefs.GLOBAL_DEEP_SEARCH);
        String entry = Prefs.runShellRead("settings get global " + Prefs.GLOBAL_WECHAT_ENTRY);
        String rules = Prefs.fromB64(b64);
        Toast.makeText(this, "global enabled=" + enabled + "，deep=" + deep + "，entry=" + entry + "，rules 字符数=" + rules.length(), Toast.LENGTH_LONG).show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
