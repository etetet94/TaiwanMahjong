package tw.minecraft.mahjong.engine;

import java.io.Serializable;
import java.util.List;

public record Meld(Kind kind, int tile, boolean open, int fromSeat, Integer calledTile) implements Serializable {
    private static final long serialVersionUID = 0L;
    public enum Kind { SEQUENCE, TRIPLET, QUAD }
    public Meld(Kind kind, int tile, boolean open, int fromSeat) {
        this(kind, tile, open, fromSeat, open && kind != Kind.SEQUENCE ? tile : null);
    }
    public Meld {
        if (kind == null || tile < 0 || tile >= 34) throw new IllegalArgumentException("無效的面子");
        if (kind == Kind.SEQUENCE && (tile >= 27 || tile % 9 > 6)) throw new IllegalArgumentException("無效的順子");
        if (calledTile != null && (!open || calledTile < tile || calledTile > tile + (kind == Kind.SEQUENCE ? 2 : 0)))
            throw new IllegalArgumentException("被鳴牌必須屬於這組明面子");
    }
    public List<Integer> tiles() {
        return switch (kind) {
            case SEQUENCE -> List.of(tile, tile + 1, tile + 2);
            case TRIPLET -> List.of(tile, tile, tile);
            case QUAD -> List.of(tile, tile, tile, tile);
        };
    }
}
