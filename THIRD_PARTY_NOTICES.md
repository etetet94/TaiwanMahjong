# 第三方素材與依賴

- **萬、筒、索及字牌的 34 張圖案**：來自 [FluffyStuff/riichi-mahjong-tiles](https://github.com/FluffyStuff/riichi-mahjong-tiles)，作者以 CC0 公有領域奉獻提供。原圖與授權已保存在 `resource-pack/source/`。
- **花牌插畫與牌身組合**：由本專案的資源包產生器繪製，採 CC0。字形相關權利另依以下字型授權。
- **Noto Sans CJK TC**：採 SIL Open Font License 1.1，字型與完整授權在 `resource-pack/fonts/`。資源包只包含已繪製的牌圖，不包含字型檔。
- **Paper API / Adventure / JOML**：由 Maven 解析的建置依賴，執行時由 Paper 提供；不重新封裝進插件 JAR。
- **JUnit**：僅用於測試；不包含於插件 JAR。
- **Pillow**：僅用於重建圖片；依其專案授權散布，透過 `requirements.txt` 安裝，不附帶其程式碼。

本專案不附帶 Minecraft、Paper 伺服器本體或商業插件。MIT 授權不取代第三方素材各自的授權。
