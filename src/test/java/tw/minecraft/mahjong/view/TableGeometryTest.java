package tw.minecraft.mahjong.view;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TableGeometryTest {
    @Test void riversHaveSixColumnsAndNeverOverlapEachOther() {
        assertEquals(6, TableGeometry.DISCARD_COLUMNS);
        var first = TableGeometry.discard(0, 0);
        assertEquals(first.z(), TableGeometry.discard(0, 5).z(), 1e-9);
        assertEquals(first.x(), TableGeometry.discard(0, 6).x(), 1e-9);
        assertEquals(first.z() + 0.115, TableGeometry.discard(0, 6).z(), 1e-9);
        List<TableGeometry.Point> all = new ArrayList<>();
        for (int seat = 0; seat < 4; seat++) for (int tile = 0; tile < 30; tile++) all.add(TableGeometry.discard(seat, tile));
        // 0.105 is the maximum horizontal footprint, including a sideways riichi discard.
        for (int a = 0; a < all.size(); a++) for (int b = a + 1; b < all.size(); b++) {
            var left = all.get(a); var right = all.get(b);
            assertTrue(Math.abs(left.x() - right.x()) >= 0.105 - 1e-9 || Math.abs(left.z() - right.z()) >= 0.105 - 1e-9,
                    "river tiles overlap at " + a + " and " + b);
        }
    }

    @Test void sideMarkersIdentifyLeftAcrossAndRightForEverySeat() {
        for (int seat = 0; seat < 4; seat++) {
            assertEquals(0, TableGeometry.calledTileIndex(seat, (seat + 3) % 4, 3));
            assertEquals(1, TableGeometry.calledTileIndex(seat, (seat + 2) % 4, 3));
            assertEquals(2, TableGeometry.calledTileIndex(seat, (seat + 1) % 4, 3));
            assertEquals(3, TableGeometry.calledTileIndex(seat, (seat + 1) % 4, 4));
            assertEquals(-1, TableGeometry.calledTileIndex(seat, seat, 3));
            assertEquals(-1, TableGeometry.calledTileIndex(seat, -1, 4));
        }
    }

    @Test void fiveMeldsFitWithSidewaysTileFootprintsAndGaps() {
        var slots = TableGeometry.meldLayout(0, List.of(4, 4, 4, 4, 4), List.of(0, 1, 3, -1, 0));
        assertEquals(20, slots.size());
        assertEquals(4, slots.stream().filter(TableGeometry.MeldSlot::sideways).count());
        double previousRight = -1;
        for (var slot : slots) {
            double footprint = slot.sideways() ? slot.depth() : slot.width();
            double left = slot.center().x() - footprint / 2, right = slot.center().x() + footprint / 2;
            assertTrue(left >= previousRight - 1e-9, "sideways tile overlaps previous tile");
            assertTrue(left >= -0.900001 && right <= 0.900001, "meld escapes its allocated row");
            previousRight = right;
        }
        var east = TableGeometry.meldLayout(1, List.of(4, 4, 4, 4, 4), List.of(0, 1, 3, -1, 0));
        for (int i = 0; i < slots.size(); i++) {
            assertEquals(slots.get(i).center().z(), east.get(i).center().x(), 1e-9);
            assertEquals(-slots.get(i).center().x(), east.get(i).center().z(), 1e-9);
        }
    }
}
