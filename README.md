# WX Hide LSP v0.1.4

一个用于 LSPosed/Xposed 生态的微信本机 UI 隐藏模块。

## 功能

- 仅作用域：`com.tencent.mm`
- 通过关键词隐藏微信会话列表、联系人列表、搜索结果等列表条目
- 不删除微信数据库
- 不上传、不转发、不备份聊天内容
- 配置只保存在模块自身 SharedPreferences 中
- v0.1.4 增加状态页、动态 Adapter Hook、TextView 异步文字兜底 Hook

## GitHub Actions 构建

上传到 GitHub 仓库根目录后，打开 Actions -> Build WXHideLSP APK -> Run workflow。

仓库根目录应直接包含：

```text
app/
.github/
build.gradle
settings.gradle
gradle.properties
README.md
```

## 使用

1. 安装 APK。
2. LSPosed 中启用模块，作用域只勾选微信 `com.tencent.mm`。
3. 重启手机或强制停止微信。
4. 打开 `WX Hide LSP`，每行填写一个关键词，例如备注名、昵称、群名。
5. 保存后强制停止微信，再打开微信测试。
6. 回到模块 App 点“刷新模块状态”，如果显示 `loaded` 或 `hit`，说明模块已加载。

## 注意

- 这是 UI 隐藏，不是数据库级隐藏。
- 关键词不要太短，否则可能误隐藏正常联系人或聊天。
- 如果状态一直为空，说明模块入口没有进入微信进程。优先检查 LSPosed 作用域是否勾选微信、是否勾选了正确用户或分身微信，并重启手机。
