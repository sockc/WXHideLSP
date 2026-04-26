# WX Hide LSP v0.2.4

LSPosed module for WeChat local UI hiding.

## v0.2.4 changes

- Fix search page residue: when a hidden contact result is removed, also hide the local `联系人` section/header and its empty spacer before `搜索网络结果`.
- Keep WeChat network search visible; the module no longer tries to hide `搜索网络结果 / 搜一搜 / 网络结果` rows.
- Add layout refresh after hide/restore to reduce blank blocks in RecyclerView/ListView.
- Keep APK desktop page as status page; full settings remain under WeChat `我 → 设置 → 功能 → WX Hide LSP`.
- Keep stable debug signing for direct upgrade from v0.2.3+.

## Upgrade

From v0.2.3 and later, direct install should work because the debug signing key is stable. If Android reports signature conflict, uninstall the old APK once and install again.
