package tw.minecraft.mahjong;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tw.minecraft.mahjong.arena.ArenaDebtStoreTest;
import tw.minecraft.mahjong.bank.DiamondLedgerPropertyTest;
import tw.minecraft.mahjong.bank.DiamondLedgerTest;
import tw.minecraft.mahjong.bank.PlayerReceiptFileTest;
import tw.minecraft.mahjong.engine.EngineTest;
import tw.minecraft.mahjong.engine.EscrowSettlementTest;
import tw.minecraft.mahjong.engine.ManualDrawTest;

/** Exposes the existing executable regression suites to Maven and CI. */
@Timeout(180)
final class RegressionSuiteTest {
    @TempDir Path temporary;
    @Test void rulesAndSimulatedGames() throws Exception { EngineTest.main(new String[0]); }
    @Test void manualDrawing() throws Exception { ManualDrawTest.main(new String[0]); }
    @Test void cappedPayments() { EscrowSettlementTest.main(new String[0]); }
    @Test void ledgerRecovery() throws Exception { DiamondLedgerTest.main(storage()); }
    @Test void ledgerModel() throws Exception { DiamondLedgerPropertyTest.main(storage()); }
    @Test void playerReceipts() throws Exception { PlayerReceiptFileTest.main(storage()); }
    @Test void arenaDebtRecovery() throws Exception { ArenaDebtStoreTest.main(storage()); }
    private String[] storage() { return new String[] {temporary.toString()}; }
}
