package tw.minecraft.mahjong.view;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import tw.minecraft.mahjong.engine.Tiles;

/** Stable resource-pack IDs; game rules, tile names and saved hands remain unchanged. */
public final class TileArt {
    private static final Key FONT = Key.key("taiwanmahjong", "tiles");
    private TileArt() {}

    public static Component face(String label) {
        for (int tile = 0; tile < 42; tile++) {
            if (Tiles.name(tile).equals(label)) {
                return Component.text(String.valueOf((char) (0xE100 + tile)), NamedTextColor.WHITE).font(FONT);
            }
        }
        throw new IllegalArgumentException("Unknown tile face: " + label);
    }

    public static ItemStack decorate(ItemStack item, int tile) {
        if (tile < 0 || tile >= 42) throw new IllegalArgumentException("Invalid tile " + tile);
        item.editMeta(meta -> meta.setItemModel(new NamespacedKey("taiwanmahjong", "tile_" + tile)));
        return item;
    }
}
