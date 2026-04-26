# WX Hide LSP v0.1.6

Local UI hiding rules for WeChat, designed for LSPosed.

## What this version changes

- Adds Samsung Secure Folder / clone profile compatibility.
- Saves rules both to app SharedPreferences and to Android `Settings.Global` through root.
- WeChat process reads `Settings.Global` first, then falls back to ContentProvider.
- Adds stronger TextView/ViewGroup/RecyclerView/ListView hooks.

## Build

Upload this project to GitHub root and run Actions -> Build WXHideLSP APK.

Repository root should contain:

```text
app/
.github/
build.gradle
settings.gradle
gradle.properties
README.md
```

## Usage

1. Install APK.
2. Enable module in LSPosed.
3. Scope WeChat: `com.tencent.mm`.
4. Open WX Hide LSP and add one keyword per line.
5. Tap "保存规则 + 写入安全文件夹兼容配置".
6. Force stop WeChat and reopen it.

If WeChat is inside Samsung Secure Folder, install/open this module in the same profile when possible, and use the global-config save button.
