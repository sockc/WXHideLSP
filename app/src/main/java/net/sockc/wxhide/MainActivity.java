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
        title.setText("WX Hide LSP v0.1.6");
        title.setTextSize(28);
        title.setGravity(Gravity.START);
        title.setPadding(0, 0, 0, dp(12));
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView tips = new TextView(this);
        tips.setText("安全文件夹/分身微信建议使用本版：保存规则会同时写入 Settings.Global，微信子用户进程可直接读取。\n每行一个关键词，保存后重启微信。");
        tips.setTextSize(14);
        tips.setPadding(0, 0, 0, dp(12));
        root.addView(tips, new LinearLayout.LayoutParams(-1, -2));

        enableBox = new CheckBox(this);
        enableBox.setText("启用隐藏规则");
        enableBox.setTextSize(18);
        root.addView(enableBox, new LinearLayout.LayoutParams(-1, -2));

        rulesEdit = new EditText(this);
        rulesEdit.setTextSize(18);
        rulesEdit.setGravity(Gravity.TOP | Gravity.START);
        rulesEdit.setMinLines(6);
        rulesEdit.setSingleLine(false);
        rulesEdit.setHint("每行一个关键词，例如：\n张三\n某某群\n完整备注名");
        root.addView(rulesEdit, new LinearLayout.LayoutParams(-1, dp(210)));

        Button save = button("保存规则 + 写入安全文件夹兼容配置");
        save.setOnClickListener(v -> saveConfig(false));
        root.addView(save);

        Button saveStop = button("保存并尝试强停微信（需要 ROOT）");
        saveStop.setOnClickListener(v -> saveConfig(true));
        root.addView(saveStop);

        Button disable = button("临时关闭模块规则");
        disable.setOnClickListener(v -> {
            enableBox.setChecked(false);
            saveConfig(false);
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
                + "1. 微信在三星安全文件夹里时，本机配置 App 和微信可能处于不同用户空间，旧版 ContentProvider 读取会失败。\n"
                + "2. v0.1.6 增加 Settings.Global 规则读取，适配安全文件夹/分身环境。\n"
                + "3. 如果状态仍为空，但隐藏生效，属于状态广播跨用户被拦截，不影响规则。\n"
                + "4. 如果隐藏不生效，去 LSPosed 日志搜索 WXHideLSP，看是否有 attach/global rules 记录。\n"
                + "5. 不建议使用过短关键词，容易误隐藏正常联系人。");
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
        enableBox.setChecked(enabled);
        rulesEdit.setText(rules);
        updateConfigText(enabled, rules);
    }

    private void saveConfig(boolean forceStopWechat) {
        boolean enabled = enableBox.isChecked();
        String rules = rulesEdit.getText() == null ? "" : rulesEdit.getText().toString();
        Prefs.saveLocal(this, enabled, rules);
        boolean globalOk = Prefs.putGlobalViaRoot(enabled, rules);
        boolean stopOk = true;
        if (forceStopWechat) stopOk = Prefs.forceStopWechatViaRoot();

        updateConfigText(enabled, rules);
        String msg = "已保存本机配置";
        msg += globalOk ? "，已写入全局配置" : "，全局配置写入失败/未授权 ROOT";
        if (forceStopWechat) msg += stopOk ? "，已强停微信" : "，强停微信失败";
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    private void updateConfigText(boolean enabled, String rules) {
        int count = 0;
        if (rules != null) {
            String[] arr = rules.split("\\r?\\n");
            for (String s : arr) if (s.trim().length() > 0) count++;
        }
        configText.setText("当前配置：" + (enabled ? "已启用" : "已关闭") + "\n关键词数量：" + count);
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
        String rules = Prefs.fromB64(b64);
        Toast.makeText(this, "global enabled=" + enabled + "，rules 字符数=" + rules.length(), Toast.LENGTH_LONG).show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
