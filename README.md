# WX Hide LSP v0.2.2

Local UI hiding rules for WeChat, designed for LSPosed.

## v0.2.2 changes

- Safe hiding mode: only hides matched contact rows and chat-history rows.
- Removed the aggressive search cleanup from v0.2.1, so it no longer hides network search results, large blank containers, Moments, or Discover pages.
- Search behaves normally: searching a hidden contact only removes the matching local contact/chat rows; web/search-network rows are left alone.
- WeChat Settings entry no longer uses the bottom floating fallback. It only tries to inject into the settings function list.
- Keeps Samsung Secure Folder / clone profile compatibility.

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
3. Scope WeChat: `com.tencent.mm`, including Samsung Secure Folder profile if used.
4. Open WX Hide LSP and add one keyword per line.
5. Keep “搜索页安全隐藏” enabled if you want matching search result rows removed.
6. Optionally enable WeChat settings entry before hiding launcher icon.
7. Force stop WeChat and reopen it.

## Notes

- This module does not delete WeChat data. It only hides matched UI rows locally.
- v0.2.2 intentionally avoids hiding “搜索网络结果 / 搜一搜 / 网络结果” to prevent the black-screen / white-screen issue seen in v0.2.1.
- If the WeChat settings entry still does not enter the real function menu, check LSPosed logs for `WXHideLSP: inline settings entry failed` and send the exact log line.
