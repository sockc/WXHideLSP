# WX Hide LSP v0.2.0

Local UI hiding rules for WeChat, designed for LSPosed.

## v0.2.0 changes

- Deeper search-page hiding: hides matching rows, related search rows, section headers, and empty-looking search containers more aggressively.
- Optional WeChat Settings entry: adds a small `WX Hide LSP 设置` entry inside WeChat settings-like pages, which opens this module's configuration page.
- Optional launcher icon hiding: hides only the desktop launcher alias. The module still remains visible in LSPosed and Android app management.
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
5. Enable deep search hiding if needed.
6. Optionally enable WeChat settings entry before hiding launcher icon.
7. Force stop WeChat and reopen it.
