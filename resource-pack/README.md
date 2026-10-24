# 麻將資源包

42 種牌面使用 `taiwanmahjong` 命名空間。牌面順序、物品模型名稱與
`U+E100` 至 `U+E129` 字元對應插件，資源包格式為 `97.1`。

從專案根目錄執行（Python 3.10 以上）：

```sh
python -m pip install -r resource-pack/requirements.txt
python tools/build-resource-pack.py
```

輸出：

- `resource-pack/dist/TaiwanMahjong_resource_pack.zip`：可安裝或託管的資源包。
- `resource-pack/dist/tiles-manifest.json`：42 張牌的對應清單。
- `docs/tile-preview.png`：所有牌面的預覽。
- `resource-pack/dist/font-contact-sheet.png`：桌面字形預覽。

建置不需網路、伺服器資料或系統字型。34 張萬筒索及字牌原始 PNG 已放在
`source/regular/`；花牌圖案由程式繪製，文字使用隨附的開放字型。ZIP 項目
排序及時間固定，同一 Python／Pillow／FreeType 環境可重現相同輸出。

來源圖案與原創圖案採 CC0，隨附字型採 SIL OFL 1.1；詳見
[授權及來源](LICENSE.txt) 與 [字型說明](fonts/README.md)。

伺服器管理員需自行提供玩家資源包。若伺服器已有其他資源包，應將此包的
`assets/taiwanmahjong/` 合併到現有資源包，再更新其下載檔與 SHA-1。
請保留本包的授權文字；產生的 ZIP 內也附有授權資訊。
