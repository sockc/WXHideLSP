# WX Hide LSP

一个用于 LSPosed/LSPatch/Xposed 生态的微信本机 UI 隐藏模块示例。

## 功能

- 仅作用域：`com.tencent.mm`
- 通过关键词隐藏微信会话列表、联系人列表、搜索结果等标准 RecyclerView/ListView 条目
- 不删除微信数据库
- 不读取、上传、转发聊天内容
- 配置只保存在模块自身 SharedPreferences 中

## 适配思路

微信新版内部类名混淆频繁，因此本项目不硬编码微信内部类名，而是在 UI 列表层 Hook：

- `androidx.recyclerview.widget.RecyclerView$Adapter.onBindViewHolder(holder, position, payloads)`
- `android.support.v7.widget.RecyclerView$Adapter.onBindViewHolder(holder, position, payloads)`
- `android.widget.AbsListView.obtainView(position, isScrap)`

绑定完成后递归扫描列表项里的 `TextView` 与 `contentDescription`，匹配到关键词则将该 item 高度压到 1 并设为透明/不可见。

## 使用

1. 用 Android Studio 打开本工程。
2. 构建 Debug APK：`Build > Build APK(s)`，或命令行执行：
   ```bash
   gradle :app:assembleDebug
   ```
3. 安装 APK。
4. 在 LSPosed 中启用模块，作用域只勾选微信 `com.tencent.mm`。
5. 打开 `WX Hide LSP`，每行填写一个关键词，例如备注名、昵称、群名。
6. 保存后强制停止微信，再重新打开。

## 注意

- 这是 UI 隐藏，不是数据库级隐藏。微信搜索、备份、通知、其他设备同步不一定被隐藏。
- 关键词不要太短，否则可能误隐藏正常联系人或聊天。
- 如果某个微信页面不生效，通常是该页面用了特殊自绘/异步加载，需要针对具体页面再加专用 Hook。
- 本项目没有加入密码锁。建议先把基础隐藏跑通，再加密码解锁/快捷开关。

## 文件说明

- `HookEntry.java`：LSP/Xposed Hook 入口
- `ConfigProvider.java`：给微信进程读取模块配置的只读 Provider，仅允许模块自身和微信 UID 查询
- `MainActivity.java`：简单配置界面
- `Prefs.java`：配置读写与关键词规范化

## GitHub Actions 在线构建 APK

如果你没有 Android Studio，可以把整个工程上传到 GitHub，然后用 Actions 自动生成 APK。

1. 在 GitHub 新建一个仓库，例如 `WXHideLSP`。
2. 上传本工程全部文件，注意 `.github/workflows/build-apk.yml` 也要上传。
3. 打开仓库的 `Actions` 页面。
4. 选择 `Build WXHideLSP APK`。
5. 点击 `Run workflow`。
6. 构建成功后，在本次运行页面最下面的 `Artifacts` 下载 `WXHideLSP-debug-apk`。
7. 解压后安装里面的 `app-debug.apk`。

如果 Actions 页面提示需要启用 Workflow，按页面提示启用即可。
