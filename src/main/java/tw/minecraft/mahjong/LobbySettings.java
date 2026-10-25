package tw.minecraft.mahjong;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.UUID;
import tw.minecraft.mahjong.engine.RuleSet;

/** Room terms are chosen before anyone can consent to a paid hand. */
public final class LobbySettings {
    private final LinkedHashSet<UUID> members = new LinkedHashSet<>();
    private RuleSet mode;
    private int base, perUnit;
    private int rounds=4;
    private boolean selected, confirmed, active;

    public LobbySettings(RuleSet mode, int base, int perUnit) {
        this.mode = Objects.requireNonNull(mode);
        validate(base, perUnit);
        this.base = base; this.perUnit = perUnit;
    }
    public LobbySettings copy() {
        LobbySettings copy = new LobbySettings(mode, base, perUnit);
        copy.members.addAll(members); copy.selected = selected;
        copy.confirmed = confirmed; copy.active = active; copy.rounds=rounds;
        return copy;
    }
    public UUID host() { return members.isEmpty() ? null : members.iterator().next(); }
    public UUID[] members() { return members.toArray(UUID[]::new); }
    public RuleSet mode() { return mode; }
    public int base() { return base; }
    public int perUnit() { return perUnit; }
    public int rounds() { return rounds; }
    public void rounds(UUID player,int rounds){
        editable(player);
        if(!selected||mode!=RuleSet.JAPANESE)throw new IllegalArgumentException("請先選日麻，再選東風局或半莊。");
        restoreRounds(rounds);confirmed=false;
    }
    public void restoreRounds(int rounds){
        if(rounds!=4&&rounds!=8)throw new IllegalArgumentException("日麻長度為東風4局或半莊8局。");this.rounds=rounds;
    }
    public boolean selected() { return selected; }
    public boolean confirmed() { return selected && confirmed; }
    public void join(UUID player) { members.add(Objects.requireNonNull(player)); }
    public void remove(UUID player) {
        boolean wasHost = Objects.equals(host(), player);
        members.remove(player);
        if (wasHost) { selected = false; confirmed = false; }
    }
    public void requireHost(UUID player) {
        if (!Objects.equals(host(), player)) throw new IllegalArgumentException("只有房主可以設定規則、金額或開始免費局。");
    }
    private void editable(UUID player) {
        requireHost(player);
        if (active) throw new IllegalArgumentException("本局已開始，不能更改規則或金額。");
    }
    public void choose(UUID player, RuleSet mode) {
        editable(player); this.mode = Objects.requireNonNull(mode);
        selected = true; confirmed = false;
    }
    public void stakes(UUID player, int base, int perUnit, boolean confirm) {
        editable(player);
        if (!selected) throw new IllegalArgumentException("請先選擇台麻或日麻並公告，再設定底與每台／飜價格。");
        validate(base, perUnit); this.base = base; this.perUnit = perUnit; confirmed = confirm;
    }
    private static void validate(int base, int perUnit) {
        if (base < 0 || perUnit < 0) throw new IllegalArgumentException("底與每台／飜價格必須是 0～2147483647 的整數。");
    }
    public void requireConfirmed() {
        if (!confirmed()) throw new IllegalArgumentException("請等房主先選擇規則，再確認本桌金額。");
    }
    public void active(boolean active) { this.active = active; }
    public void restoreActive(RuleSet mode, int base, int perUnit) {
        validate(base, perUnit); this.mode = mode; this.base = base; this.perUnit = perUnit;
        selected = true; confirmed = true; active = true;
    }
}
