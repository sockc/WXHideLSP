# WX Hide LSP v0.2.6

LSPosed module for WeChat local UI hiding.

## v0.2.6 changes

- Fix WeChat search residue during incremental input such as `A0` / `A0英`.
- When the current search query is related to a hidden rule, hide the local `联系人` header, blank spacer and `加载中` residue before the network-search row appears.
- Keep `搜索网络结果` visible and untouched.
- Avoid scanning/cleaning Discover, Moments and large page containers.
- Keep full settings under WeChat `我 → 设置 → 功能 → WX Hide LSP`; APK desktop page remains status-only.
- Keep stable debug signing for direct upgrade from v0.2.3+.

## Upgrade

From v0.2.3 and later, direct install should work because the debug signing key is stable. If Android reports signature conflict, uninstall the old APK once and install again.


## v0.2.6 search tuning

- Partial queries such as `A0` / `A0英` no longer trigger section cleanup.
- Search cleanup starts only after the query contains a full hidden rule or alias, such as `A0英智`.
- This avoids hiding the normal Contacts section when other non-hidden contacts also match the same prefix.
