package net.sockc.wxhide;

import android.app.Activity;
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

public class MainActivity extends Activity {
    private CheckBox enabledBox;
    private EditText keywordsEdit;

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
        title.setText("WX Hide LSP");
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
        });
        root.addView(save, lp(-1, -2));

        Button disable = new Button(this);
        disable.setText("临时关闭模块规则");
        disable.setOnClickListener(v -> {
            enabledBox.setChecked(false);
            Prefs.save(this, false, keywordsEdit.getText().toString());
            Toast.makeText(this, "已关闭规则。请强制停止微信后重新打开。", Toast.LENGTH_LONG).show();
        });
        root.addView(disable, lp(-1, -2));

        TextView note = new TextView(this);
        note.setText("说明：\n1. 这是 UI 过滤版，微信更新后比硬编码 Hook 更稳。\n2. 如果某个页面没有隐藏，说明该页面不是标准 RecyclerView/ListView 或文字是异步加载，后续可针对该页面加专用 Hook。\n3. 不建议填过短关键词，否则容易误隐藏正常联系人。");
        note.setTextSize(13);
        note.setPadding(0, dp(12), 0, 0);
        root.addView(note, lp(-1, -2));

        setContentView(scrollView);
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
