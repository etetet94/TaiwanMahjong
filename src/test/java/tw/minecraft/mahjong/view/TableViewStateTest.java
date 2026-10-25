package tw.minecraft.mahjong.view;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tw.minecraft.mahjong.engine.Meld;

class TableViewStateTest {
    @Test void claimedSequenceTileMovesToSidewaysSourcePosition() {
        var labels = List.of("一萬", "二萬", "三萬");
        var chi = new Meld(Meld.Kind.SEQUENCE, 0, true, 3, 2);
        assertEquals(List.of("三萬", "一萬", "二萬"), TableView.displayedMeld(labels, chi, 0));
        assertEquals(labels, TableView.displayedMeld(labels, new Meld(Meld.Kind.SEQUENCE, 0, true, 3), 0),
                "legacy chi without called tile metadata retains its existing labels");
        assertEquals(List.of("?", "?", "?", "?"), TableView.displayedMeld(List.of("?", "?", "?", "?"),
                new Meld(Meld.Kind.QUAD, 0, false, -1), -1));
    }

    @Test void oldSeatConstructorStillWorksAndNewMetadataIsImmutable() {
        var old = new TableView.SeatState(null, "seat", List.of(), List.of(), List.of(), List.of());
        assertTrue(old.actualMelds().isEmpty()); assertEquals(-1, old.riichiDiscardIndex());
        var melds = new ArrayList<>(List.of(new Meld(Meld.Kind.TRIPLET, 0, true, 3, 0)));
        var state = new TableView.SeatState(null, "seat", List.of(), List.of(List.of("一萬", "一萬", "一萬")),
                List.of("二萬"), List.of(), melds, 0);
        melds.clear();
        assertEquals(1, state.actualMelds().size()); assertEquals(0, state.riichiDiscardIndex());
        assertThrows(UnsupportedOperationException.class, () -> state.actualMelds().clear());
    }
}
