package tw.minecraft.mahjong;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tw.minecraft.mahjong.engine.RuleSet;

class LobbySettingsTest {
    @Test void firstJoinIsHostAndRejoiningDoesNotReorderMembers() {
        var lobby = lobby();
        UUID first = UUID.randomUUID(), second = UUID.randomUUID(), third = UUID.randomUUID();
        assertNull(lobby.host());
        lobby.join(first); lobby.join(second); lobby.join(third); lobby.join(first);
        assertEquals(first, lobby.host());
        lobby.remove(first); assertEquals(second, lobby.host());
        lobby.join(first); lobby.remove(second); assertEquals(third, lobby.host());
        lobby.remove(third); assertEquals(first, lobby.host());
        lobby.remove(first); assertNull(lobby.host());
    }

    @Test void modeMustBeChosenBeforeStakesAndConfirmedBeforeStarting() {
        var lobby = lobby(); UUID host = UUID.randomUUID(); lobby.join(host);
        assertFalse(lobby.selected()); assertFalse(lobby.confirmed());
        assertThrows(IllegalArgumentException.class, lobby::requireConfirmed);
        assertThrows(IllegalArgumentException.class, () -> lobby.stakes(host, 0, 0, true));
        lobby.choose(host, RuleSet.JAPANESE);
        assertTrue(lobby.selected()); assertFalse(lobby.confirmed());
        lobby.stakes(host, 7, 3, false);
        assertThrows(IllegalArgumentException.class, lobby::requireConfirmed);
        lobby.stakes(host, 7, 3, true);
        assertDoesNotThrow(lobby::requireConfirmed);
        assertEquals(RuleSet.JAPANESE, lobby.mode()); assertEquals(7, lobby.base()); assertEquals(3, lobby.perUnit());
    }

    @Test void onlyTheCurrentHostCanChangeOrConfirmRoomTerms() {
        var lobby = lobby(); UUID host = UUID.randomUUID(), guest = UUID.randomUUID(); lobby.join(host); lobby.join(guest);
        assertThrows(IllegalArgumentException.class, () -> lobby.choose(guest, RuleSet.JAPANESE));
        lobby.choose(host, RuleSet.TAIWAN);
        assertThrows(IllegalArgumentException.class, () -> lobby.stakes(guest, 99, 99, true));
        assertEquals(5, lobby.base()); assertEquals(2, lobby.perUnit()); assertFalse(lobby.confirmed());
        lobby.remove(host);
        assertDoesNotThrow(() -> lobby.choose(guest, RuleSet.JAPANESE));
        assertThrows(IllegalArgumentException.class, () -> lobby.stakes(host, 1, 1, true));
    }

    @Test void changingModeOrDraftStakesInvalidatesConfirmation() {
        var lobby = lobby(); UUID host = UUID.randomUUID(); lobby.join(host); lobby.choose(host, RuleSet.TAIWAN);
        lobby.stakes(host, 5, 2, true); assertTrue(lobby.confirmed());
        lobby.choose(host, RuleSet.TAIWAN); assertFalse(lobby.confirmed());
        lobby.stakes(host, 5, 2, true);
        lobby.choose(host, RuleSet.JAPANESE); assertFalse(lobby.confirmed());
        lobby.stakes(host, 5, 2, true);
        lobby.stakes(host, 6, 3, false); assertFalse(lobby.confirmed());
    }

    @Test void hostDepartureRequiresNewSelectionButGuestDepartureDoesNot() {
        var lobby = lobby(); UUID host = UUID.randomUUID(), next = UUID.randomUUID(), guest = UUID.randomUUID();
        lobby.join(host); lobby.join(next); lobby.join(guest); lobby.choose(host, RuleSet.TAIWAN); lobby.stakes(host, 5, 2, true);
        lobby.remove(guest); assertTrue(lobby.selected()); assertTrue(lobby.confirmed());
        lobby.remove(host); assertEquals(next, lobby.host()); assertFalse(lobby.selected()); assertFalse(lobby.confirmed());
        assertThrows(IllegalArgumentException.class, () -> lobby.stakes(next, 5, 2, true));
    }

    @Test void invalidAmountsAreRejectedAtomicallyAndZeroAndIntegerMaximumAreValid() {
        assertThrows(IllegalArgumentException.class, () -> new LobbySettings(RuleSet.TAIWAN, -1, 2));
        assertThrows(IllegalArgumentException.class, () -> new LobbySettings(RuleSet.TAIWAN, 5, -1));
        var lobby = lobby(); UUID host = UUID.randomUUID(); lobby.join(host); lobby.choose(host, RuleSet.TAIWAN); lobby.stakes(host, 5, 2, true);
        assertThrows(IllegalArgumentException.class, () -> lobby.stakes(host, -1, 2, false));
        assertThrows(IllegalArgumentException.class, () -> lobby.stakes(host, 5, -1, false));
        assertEquals(5, lobby.base()); assertEquals(2, lobby.perUnit()); assertTrue(lobby.confirmed());
        lobby.stakes(host, 0, 0, true); assertEquals(0, lobby.base()); assertEquals(0, lobby.perUnit());
        lobby.stakes(host, Integer.MAX_VALUE, Integer.MAX_VALUE, true);
        assertEquals(Integer.MAX_VALUE, lobby.base()); assertEquals(Integer.MAX_VALUE, lobby.perUnit());
    }

    @Test void activeHandsFreezeTermsUntilTheyEnd() {
        var lobby = lobby(); UUID host = UUID.randomUUID(); lobby.join(host); lobby.choose(host, RuleSet.JAPANESE); lobby.stakes(host, 11, 4, true);
        lobby.active(true);
        assertThrows(IllegalArgumentException.class, () -> lobby.choose(host, RuleSet.TAIWAN));
        assertThrows(IllegalArgumentException.class, () -> lobby.stakes(host, 0, 0, true));
        assertEquals(RuleSet.JAPANESE, lobby.mode()); assertEquals(11, lobby.base()); assertEquals(4, lobby.perUnit()); assertTrue(lobby.confirmed());
        lobby.active(false); assertDoesNotThrow(() -> lobby.choose(host, RuleSet.TAIWAN));
    }

    @Test void copiesPreserveHostOrderAndFrozenStateWithoutSharingMutableMembers() {
        var lobby = lobby(); UUID host = UUID.randomUUID(), guest = UUID.randomUUID(); lobby.join(host); lobby.join(guest);
        lobby.choose(host, RuleSet.JAPANESE); lobby.stakes(host, 8, 3, true); lobby.active(true);
        var checkpoint = lobby.copy();
        lobby.active(false); lobby.choose(host, RuleSet.TAIWAN); lobby.remove(host);
        assertEquals(host, checkpoint.host()); assertEquals(RuleSet.JAPANESE, checkpoint.mode());
        assertEquals(8, checkpoint.base()); assertEquals(3, checkpoint.perUnit()); assertTrue(checkpoint.confirmed());
        assertThrows(IllegalArgumentException.class, () -> checkpoint.stakes(host, 1, 1, true));
        checkpoint.remove(host); assertEquals(guest, checkpoint.host());
    }

    @Test void restoredHandUsesItsSavedRulesAndAmountsAndRemainsFrozen() {
        var lobby = lobby(); UUID host = UUID.randomUUID(); lobby.join(host);
        lobby.restoreActive(RuleSet.JAPANESE, 17, 9);
        assertEquals(RuleSet.JAPANESE, lobby.mode()); assertEquals(17, lobby.base()); assertEquals(9, lobby.perUnit());
        assertTrue(lobby.selected()); assertTrue(lobby.confirmed()); assertDoesNotThrow(lobby::requireConfirmed);
        assertThrows(IllegalArgumentException.class, () -> lobby.choose(host, RuleSet.TAIWAN));
        assertThrows(IllegalArgumentException.class, () -> lobby.stakes(host, 5, 2, true));
        lobby.active(false); assertDoesNotThrow(() -> lobby.choose(host, RuleSet.TAIWAN));
    }

    @Test void japaneseLengthRequiresHostSelectionAndInvalidatesConsentBeforeMatchStarts() {
        var lobby = lobby(); UUID host = UUID.randomUUID(), guest = UUID.randomUUID(); lobby.join(host); lobby.join(guest);
        assertEquals(4, lobby.rounds());
        assertThrows(IllegalArgumentException.class, () -> lobby.rounds(host, 8));
        lobby.choose(host, RuleSet.TAIWAN);
        assertThrows(IllegalArgumentException.class, () -> lobby.rounds(host, 8));
        lobby.choose(host, RuleSet.JAPANESE); lobby.stakes(host, 0, 4, true);
        assertThrows(IllegalArgumentException.class, () -> lobby.rounds(guest, 8));
        assertThrows(IllegalArgumentException.class, () -> lobby.rounds(host, 6));
        assertTrue(lobby.confirmed()); assertEquals(4, lobby.rounds());
        lobby.rounds(host, 8); assertEquals(8, lobby.rounds()); assertFalse(lobby.confirmed());
        lobby.stakes(host, 0, 4, true); lobby.active(true);
        assertThrows(IllegalArgumentException.class, () -> lobby.rounds(host, 4));
        assertEquals(8, lobby.copy().rounds()); assertTrue(lobby.copy().confirmed());
    }

    private static LobbySettings lobby() { return new LobbySettings(RuleSet.TAIWAN, 5, 2); }
}
