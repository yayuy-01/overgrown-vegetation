package com.yayuy.overgrownvegetation.director;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.front.Fronts;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * 貯蓄の上限からあふれたバイオマス(2026-11-22 ユーザー決定。正本は Vault「司令塔の調整の残り(alpha まで)(11-22)」)。
 * <ul>
 *   <li>あふれた分は捨てずに、森全体の胞子の吹き出しに足す(「150% たまっていたら、あふれた 50% 分が胞子濃度に行って、ふつうの値 + あふれた分」)。
 *   胞子の量 = あふれた量 ÷ 上限 × 過繁茂のチャンク × overflowSporeFactor(上限 1 つ分あふれると森全体の 1 日分。【仮】)。
 *   樹域の真ん中へ等分して、胞子の場の点の放出として出す。</li>
 *   <li>直近にあふれた量(overflowRecentDays 日で減る)は、蓄えの嚢を建てる重みに使う({@code OrganPlanner})。</li>
 * </ul>
 * どれも保存しない(再起動で 0 から。直近の量は数日で入れ替わるので)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class StorageOverflow {

    /** これまで(保存しない): あふれた量・胞子に回した量(胞子の場の単位)。 */
    public static double total, sporeTotal;
    /** 直近にあふれた量(減っていく)。 */
    private static double recent;
    /** まだ胞子にしていない分。 */
    private static double pending;
    /** 胞子にする間(tick)。 */
    static final int FLUSH_TICKS = 200;

    private StorageOverflow() {
    }

    static void add(double amount) {
        if (amount <= 0.0D) return;
        total += amount;
        recent += amount;
        pending += amount;
    }

    /** 直近の量を days 日ぶん減らす(overflowRecentDays で 1/e)。 */
    static void decay(double days) {
        if (days <= 0.0D) return;
        recent *= Math.exp(-days / DesignData.director().overflowRecentDays);
    }

    /** 直近にあふれた量。 */
    public static double recent() {
        return recent;
    }

    /** テスト用: 0 に戻す。 */
    public static void resetForTests() {
        total = sporeTotal = recent = pending = 0.0D;
    }

    /** テスト用: あふれた分を足す。 */
    public static void addForTests(double amount) {
        add(amount);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % FLUSH_TICKS != 7) return;
        flush(server.overworld());
    }

    /** たまった分を胞子にして樹域の真ん中へ出す。出した量(胞子の場の単位)を返す。 */
    public static double flush(ServerLevel level) {
        if (pending <= 0.0D) return 0.0D;
        Fronts fronts = Fronts.get(level.getServer());
        if (fronts.fronts().isEmpty()) return 0.0D; // 樹域が無ければ持ち越す
        double cap = Math.max(1.0D, ForestDirector.cap(level));
        double chunks = PhaseData.get(level).overgrownLedgerSize();
        double spores = pending / cap * chunks * DesignData.director().overflowSporeFactor;
        pending = 0.0D;
        if (spores <= 0.0D) return 0.0D;
        double each = spores / fronts.fronts().size();
        for (Fronts.Front f : fronts.fronts()) {
            com.yayuy.overgrownvegetation.spore.field.SporeFieldServer.emitPoint(level, BlockPos.containing(f.x(), 64.0D, f.z()), each);
        }
        sporeTotal += spores;
        return spores;
    }
}
