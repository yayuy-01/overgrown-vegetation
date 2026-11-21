package com.yayuy.overgrownvegetation.director;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.EconomyTable;
import com.yayuy.overgrownvegetation.entity.AbstractParasitizedMonster;
import com.yayuy.overgrownvegetation.organ.ForestOrgans;
import com.yayuy.overgrownvegetation.organ.OrganKind;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

import java.util.Locale;

/**
 * <b>森の資源(バイオマス)の収支</b>(2026-11-20 ユーザー決定。正本は Vault「森の知性(司令塔)」の 11-20 の節)。
 * 予算そのもの(貯まる・払う・上限)は {@link ForestDirector}。ここは「何が稼ぎ、何が払い、何が戻すか」を 1 か所に集めた口。
 * 数は {@code director.json} の {@code economy}({@link EconomyTable})の【仮】。
 * <ul>
 *   <li><b>稼ぎ</b>(毎秒少しずつ。{@link #incomePerDay}): 11-21 から毎秒 = 過繁茂 1 チャンク 0.004 + 井戸 + 巨大樹(広さに比例。物差しは段階 0 の感染 Mob = 100)。維持費は {@link ForestUpkeep}。</li>
 *   <li><b>まとまって入る</b>({@link #onInfected}): 生き物が感染体に変わったとき(汎用の感染体になった・死体になって G1 が起きる)。</li>
 *   <li><b>自然湧きも払う</b>({@link #payNatural}): 縁・地下・霧の中の本物。払えなければ湧かない。消えた(デスポーン)ら戻る。</li>
 *   <li><b>戻る</b>({@link #onDeath}): 払って出した植物側の Mob が森(過繁茂のチャンク)の中で倒されたら一部が戻る。外では戻らない。</li>
 *   <li><b>値段を軽く</b>({@link #priceFactor}): 苗床の群体は全体の Mob の値段を、Mob から苗床になった物はそこから孵す Mob の値段を軽くする。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class ForestEconomy {

    /** 払って出した Mob に付ける「払った額」(エンティティの保存データ)。戻すときに読み、戻したら消す。 */
    public static final String PAID_KEY = "ov_paid";

    private ForestEconomy() {
    }

    // ---------------------------------------------------------------- 見る口の数(保存しない。/ov director で見る)

    static double gainedInfect, refundedDeath, refundedDespawn, paidNatural, spentPhase, spentTower;
    static long infections, naturalSkipped;

    public static EconomyTable table() {
        return DesignData.director().economy;
    }

    // ---------------------------------------------------------------- 稼ぎ

    /** 稼ぎの元の今の値(表示とテスト用)。leyOrgans は大きい版に直した本数。 */
    public record Sources(int chunks, int leyBig, int leySmall, int giantTrees, double perDay) {
    }

    /** テスト用: 1 日の稼ぎを決め打ちにする(null で戻す)。テストのワールドは台帳が空に近く、稼ぎも上限もほぼ 0 になるため。 */
    @javax.annotation.Nullable
    private static Double incomeOverride;

    public static void setIncomeForTests(@javax.annotation.Nullable Double perDay) {
        incomeOverride = perDay;
    }

    @javax.annotation.Nullable
    public static Double incomeOverride() {
        return incomeOverride;
    }

    /** 井戸の器官(育ち切ったもの)の本数。小さい版は small.strength 倍。 */
    public static double leyOrgans(ServerLevel level) {
        return organWeight(level, OrganKind.LEY);
    }

    /** 苗床の群体(育ち切ったもの)の本数。小さい版は small.strength 倍。 */
    public static double nurseryOrgans(ServerLevel level) {
        return organWeight(level, OrganKind.NURSERY);
    }

    private static double organWeight(ServerLevel level, OrganKind kind) {
        if (!DesignData.organs().enabled) return 0.0D;
        double n = 0.0D;
        for (ForestOrgans.Organ o : ForestOrgans.get(level).organs()) {
            if (o.kind == kind && o.active()) n += DesignData.organs().strength(o.small);
        }
        return n;
    }

    /** 稼ぎの元を読む(過繁茂の台帳・器官の一覧・巨大樹の記録。どれも数えるだけで軽い)。 */
    public static Sources sources(ServerLevel level) {
        int chunks = PhaseData.get(level).overgrownLedgerSize();
        int big = 0, small = 0;
        if (DesignData.organs().enabled) {
            for (ForestOrgans.Organ o : ForestOrgans.get(level).organs()) {
                if (o.kind != OrganKind.LEY || !o.active()) continue;
                if (o.small) small++;
                else big++;
            }
        }
        int trees = PhaseData.get(level).livingGiantTreeCount();
        return new Sources(chunks, big, small, trees, table().incomePerDay(chunks, big, small, trees));
    }

    /** 1 日の稼ぎ(森の健康・設定の倍率の前)。 */
    public static double incomePerDay(ServerLevel level) {
        Double o = incomeOverride;
        return o != null ? o : sources(level).perDay();
    }

    // ---------------------------------------------------------------- 値段

    /** 全体の Mob の値段の倍率(苗床の群体で軽くなる)。サーバーがまだ無いときは 1。 */
    public static double priceFactor() {
        net.minecraft.server.MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) return 1.0D;
        return priceFactor(server.overworld());
    }

    public static double priceFactor(ServerLevel level) {
        return table().nurseryFactor(nurseryOrgans(level));
    }

    /** 自然湧きの 1 体の値段(苗床の倍率込み)。G1 の段階 0 は送り込みと同じ段階 0 の値段、ほかは危険度と型の値段。 */
    public static double naturalPrice(Entity e) {
        double base = e instanceof AbstractParasitizedMonster ? DesignData.director().largeRaid.costStageZero * priceFactor()
                : ForestDirector.spawnCost(e.getType());
        return base * table().naturalCostFactor;
    }

    /** 払った額を個体に書く(倒されたとき・消えたときに戻す元)。 */
    public static void markPaid(Entity e, double price) {
        if (price > 0.0D) e.getPersistentData().putDouble(PAID_KEY, price);
    }

    public static double paid(Entity e) {
        return e.getPersistentData().getDouble(PAID_KEY);
    }

    /**
     * 自然湧き(縁・地下・霧の中の本物)の 1 体を払う。払えたら個体に額を書いて true。払えなければ false(呼ぶ側は湧かせない)。
     * 司令塔が止まっている(enabled false)ときは払わずに通す。
     */
    public static boolean payNatural(ServerLevel level, Mob m) {
        if (!DesignData.director().enabled) return true;
        double price = naturalPrice(m);
        if (price <= 0.0D) return true;
        // 11-21: 払うのは値段 × naturalCostFactor、維持費は定価で数える(8 割の関所)
        if (!ForestDirector.get(level).tryPayUnit(level, price, ForestUpkeep.unitPrice(m), inForest(level, m.getX(), m.getZ()))) {
            naturalSkipped++;
            return false;
        }
        paidNatural += price;
        markPaid(m, price);
        return true;
    }

    // ---------------------------------------------------------------- まとまって入る

    /** 生き物が感染体に変わった(汎用の感染体になった・死体になった)。元の最大体力に応じた量が入る。 */
    public static void onInfected(ServerLevel level, LivingEntity victim) {
        if (!DesignData.director().enabled || level.dimension() != Level.OVERWORLD) return;
        double gain = table().infectGain(victim.getMaxHealth());
        if (gain <= 0.0D) return;
        ForestDirector.get(level).refund(level, gain);
        gainedInfect += gain;
        infections++;
    }

    // ---------------------------------------------------------------- 戻る

    /** 森(過繁茂のチャンク)の中か。 */
    public static boolean inForest(ServerLevel level, double x, double z) {
        return PhaseData.get(level).isOvergrownKey(ChunkPos.asLong(net.minecraft.util.Mth.floor(x) >> 4, net.minecraft.util.Mth.floor(z) >> 4));
    }

    /** 倒されたときに戻す量(純粋な計算)。 */
    public static double deathRefund(double paid, boolean inForest) {
        return inForest ? paid * table().refundShare : 0.0D;
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onDeath(LivingDeathEvent event) {
        LivingEntity e = event.getEntity();
        if (!(e.level() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        double paid = paid(e);
        if (paid <= 0.0D) return;
        e.getPersistentData().remove(PAID_KEY); // 1 回だけ
        double back = deathRefund(paid, inForest(level, e.getX(), e.getZ()));
        if (back <= 0.0D) return;
        ForestDirector.get(level).refund(level, back);
        refundedDeath += back;
    }

    /** 自然湧きの個体がデスポーン(片付け・遠くて消えた)したら、払った額が森へ戻る。読み込みの外へ出ただけ・倒されたのは戻さない。 */
    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent event) {
        Entity e = event.getEntity();
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        if (e.getRemovalReason() != Entity.RemovalReason.DISCARDED) return;
        if (!(e instanceof Mob m) || m.getHealth() <= 0.0F) return;
        double paid = paid(e);
        if (paid <= 0.0D) return;
        if (!com.yayuy.overgrownvegetation.spawn.EnemySpawns.spawnedHere(m) && !m.getTags().contains(com.yayuy.overgrownvegetation.spawn.FogLurkers.TAG)) return;
        e.getPersistentData().remove(PAID_KEY);
        double back = paid * table().despawnRefund;
        if (back <= 0.0D) return;
        ForestDirector.get(level).refund(level, back);
        refundedDespawn += back;
    }

    public static void notePhaseSpend(double v) {
        spentPhase += v;
    }

    public static void noteTowerSpend(double v) {
        spentTower += v;
    }

    // ---------------------------------------------------------------- 見る口

    public static String describe(ServerLevel level) {
        Sources s = sources(level);
        return String.format(Locale.ROOT,
                "稼ぎの元: 過繁茂 %d チャンク・井戸 大 %d 小 %d・巨大樹 %d 本 → 1 日 %.1f(毎秒 %.2f)/ %s(上限 毎秒 %.2f・関所で断った %d)/ 値段の倍率 %.2f(苗床の群体 %.1f 本)/ これまで: 感染 %d 回 +%.0f・"
                        + "倒されて戻った +%.0f・消えて戻った +%.0f・自然湧き −%.0f(払えず見送り %d)・フェーズの器官 −%.0f・塔の飛翔 −%.0f",
                s.chunks(), s.leyBig(), s.leySmall(), s.giantTrees(), s.perDay(), s.perDay() / com.yayuy.overgrownvegetation.data.EconomyTable.SECONDS_PER_DAY,
                ForestUpkeep.describe(level), ForestDirector.upkeepLimitPerSecond(level), ForestDirector.fieldRefused, priceFactor(level), nurseryOrgans(level), infections, gainedInfect,
                refundedDeath, refundedDespawn, paidNatural, naturalSkipped, spentPhase, spentTower);
    }
}
