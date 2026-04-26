package net.sockc.wxhide;

import android.app.Activity;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final Uri STATUS_URI = Uri.parse("content://net.sockc.wxhide.provider/status");

    private CheckBox enabledBox;
    private EditText keywordsEdit;
    private TextView statusView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int pad = dp(16);
        ScrollView scrollView = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        scrollView.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView title = new TextView(this);
        title.setText("WX Hide LSP v0.1.5");
        title.setTextSize(22);
        title.setGravity(Gravity.START);
        root.addView(title, lp(-1, -2));

        TextView desc = new TextView(this);
        desc.setText("本模块只在本机隐藏微信列表显示，不删除微信数据库，不上传任何内容。\n\n用法：每行一个关键词，建议填微信聊天列表或联系人列表里能看到的备注、昵称、群名。保存后强制停止微信再打开。");
        desc.setTextSize(14);
        desc.setPadding(0, dp(10), 0, dp(10));
        root.addView(desc, lp(-1, -2));

        enabledBox = new CheckBox(this);
        enabledBox.setText("启用隐藏规则");
        enabledBox.setChecked(Prefs.isEnabled(this));
        root.addView(enabledBox, lp(-1, -2));

        keywordsEdit = new EditText(this);
        keywordsEdit.setHint("每行一个关键词，例如：\n张三\n家庭群\nsecret_name");
        keywordsEdit.setMinLines(8);
        keywordsEdit.setGravity(Gravity.TOP | Gravity.START);
        keywordsEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        keywordsEdit.setText(Prefs.getRawKeywords(this));
        root.addView(keywordsEdit, lp(-1, dp(220)));

        Button save = new Button(this);
        save.setText("保存规则");
        save.setOnClickListener(v -> {
            Prefs.save(this, enabledBox.isChecked(), keywordsEdit.getText().toString());
            Toast.makeText(this, "已保存。请强制停止微信后重新打开。", Toast.LENGTH_LONG).show();
            refreshStatus();
        });
        root.addView(save, lp(-1, -2));

        Button saveAndStop = new Button(this);
        saveAndStop.setText("保存并尝试强停微信（需要 root）");
        saveAndStop.setOnClickListener(v -> {
            Prefs.save(this, enabledBox.isChecked(), keywordsEdit.getText().toString());
            boolean ok = forceStopWeChatByRoot();
            Toast.makeText(this, ok ? "已保存，并已尝试强停微信。现在重新打开微信测试。" : "已保存，但 root 强停失败。请手动强制停止微信。", Toast.LENGTH_LONG).show();
            refreshStatus();
        });
        root.addView(saveAndStop, lp(-1, -2));

        Button disable = new Button(this);
        disable.setText("临时关闭模块规则");
        disable.setOnClickListener(v -> {
            enabledBox.setChecked(false);
            Prefs.save(this, false, keywordsEdit.getText().toString());
            Toast.makeText(this, "已关闭规则。请强制停止微信后重新打开。", Toast.LENGTH_LONG).show();
            refreshStatus();
        });
        root.addView(disable, lp(-1, -2));

        statusView = new TextView(this);
        statusView.setTextSize(13);
        statusView.setPadding(0, dp(12), 0, dp(8));
        root.addView(statusView, lp(-1, -2));

        Button refresh = new Button(this);
        refresh.setText("刷新模块状态");
        refresh.setOnClickListener(v -> refreshStatus());
        root.addView(refresh, lp(-1, -2));

        TextView note = new TextView(this);
        note.setText("说明：\n1. v0.1.5 增加全作用域探针。只要 LSPosed 把模块注入到已勾选 App，就会显示 probe/attach。\n2. 正常勾选微信后，打开微信再刷新状态，应该显示 attach/loaded/hit。\n3. 如果一直是暂无加载记录，优先检查：Magisk/KSU/APatch 排除列表是否把微信排除了、是否使用微信分身/安全文件夹、LSPosed 作用域是否勾选到正确用户。\n4. 临时调试时可以把“设置”也加入作用域，打开系统设置后如果显示 probe，说明模块本身能注入；如果仍为空，是 LSPosed/排除列表问题。\n5. 不建议填过短关键词，否则容易误隐藏正常联系人。");
        note.setTextSize(13);
        note.setPadding(0, dp(12), 0, 0);
        root.addView(note, lp(-1, -2));

        setContentView(scrollView);
        refreshStatus();
    }

    private void refreshStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("当前配置：")
                .append(Prefs.isEnabled(this) ? "已启用" : "已关闭")
                .append("\n关键词数量：")
                .append(Prefs.parseKeywords(Prefs.getRawKeywords(this)).size())
                .append("\n\nLSPosed 状态：");

        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(STATUS_URI, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                long time = cursor.getLong(cursor.getColumnIndex("time"));
                String event = cursor.getString(cursor.getColumnIndex("event"));
                String detail = cursor.getString(cursor.getColumnIndex("detail"));
                if (time <= 0 || event == null || event.length() == 0) {
                    sb.append("暂无加载记录");
                } else {
                    sb.append("\n最后事件：").append(event)
                            .append("\n时间：").append(formatTime(time))
                            .append("\n详情：").append(detail == null ? "" : detail);
                }
            } else {
                sb.append("读取失败");
            }
        } catch (Throwable t) {
            sb.append("读取失败：").append(t.getClass().getSimpleName()).append(" ").append(t.getMessage());
        } finally {
            if (cursor != null) cursor.close();
        }
        statusView.setText(sb.toString());
    }

    private String formatTime(long time) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date(time));
    }

    private boolean forceStopWeChatByRoot() {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"su", "-c", "am force-stop com.tencent.mm"});
            int code = process.waitFor();
            return code == 0;
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (process != null) process.destroy();
        }
    }

    private LinearLayout.LayoutParams lp(int w, int h) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(w, h);
        params.setMargins(0, dp(6), 0, dp(6));
        return params;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
