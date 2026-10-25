package tw.minecraft.mahjong;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tw.minecraft.mahjong.arena.ArenaDebtStoreTest;
import tw.minecraft.mahjong.bank.DiamondLedgerPropertyTest;
import tw.minecraft.mahjong.bank.DiamondLedgerTest;
import tw.minecraft.mahjong.bank.PlayerReceiptFileTest;
import tw.minecraft.mahjong.engine.EngineTest;
import tw.minecraft.mahjong.engine.EscrowSettlementTest;
import tw.minecraft.mahjong.engine.ManualDrawTest;
import tw.minecraft.mahjong.engine.HouseRulesTest;

/** Exposes the existing executable regression suites to Maven and CI. */
@Timeout(180)
final class RegressionSuiteTest {
    @Test void rulesAndSimulatedGames() throws Exception { EngineTest.main(new String[0]); }
    @Test void manualDrawing() throws Exception { ManualDrawTest.main(new String[0]); }
    @Test void japaneseMatch() throws Exception { tw.minecraft.mahjong.engine.JapaneseMatchTest.main(new String[0]); }
    @Test void japaneseEngine() throws Exception { tw.minecraft.mahjong.engine.RiichiEngineTest.main(new String[0]); }
    @Test void japaneseScoring() throws Exception { tw.minecraft.mahjong.engine.RiichiScorerTest.main(new String[0]); }
    @Test void houseRulesAndSeatDirection() throws Exception { HouseRulesTest.main(new String[0]); }
    @Test void actual113Checkpoints() throws Exception {
        for(String phase : new String[]{"claim", "draw"})
            HouseRulesTest.legacy(getClass().getResourceAsStream("/legacy/legacy-1.1.3-"+phase+".bin"));
        for(String kind : new String[]{"open", "closed", "added"})
            HouseRulesTest.legacyKong(getClass().getResourceAsStream("/legacy/legacy-1.1.3-"+kind+"-kong.bin"),kind.equals("open"));
    }
    @Test void cappedPayments() { EscrowSettlementTest.main(new String[0]); }
    @Test void ledgerRecovery() throws Exception { DiamondLedgerTest.main(storage()); }
    @Test void ledgerModel() throws Exception { DiamondLedgerPropertyTest.main(storage()); }
    @Test void playerReceipts() throws Exception { PlayerReceiptFileTest.main(storage()); }
    @Test void arenaDebtRecovery() throws Exception { ArenaDebtStoreTest.main(storage()); }
    private String[] storage() throws java.io.IOException {
        Path base=java.nio.file.Files.createDirectories(Path.of("target","regression-temp"));
        return new String[] {java.nio.file.Files.createTempDirectory(base,"case-").toAbsolutePath().toString()};
    }
}
