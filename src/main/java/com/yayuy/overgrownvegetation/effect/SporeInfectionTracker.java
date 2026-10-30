package com.yayuy.overgrownvegetation.effect;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.EffectTable;
import com.yayuy.overgrownvegetation.entity.BlightFernEntity;
import com.yayuy.overgrownvegetation.entity.OvergrownNexusEntity;
import com.yayuy.overgrownvegetation.entity.SporophyllAura;
import com.yayuy.overgrownvegetation.item.ProtectiveMaskItem;
import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import com.yayuy.overgrownvegetation.registry.ModMobEffects;
import com.yayuy.overgrownvegetation.spore.MorningMist;
import com.yayuy.overgrownvegetation.spore.SporeConcentration;
import com.yayuy.overgrownvegetation.spore.SporeExposure;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * 胞子濃度・過繁茂したバイオームによる「胞子感染」({@link ModMobEffects#SPORE_INFECTION})の付与と進行。
 * 数値はすべて {@code effects.json}({@link EffectTable})。
 *
 * <h2>プレイヤー(2026-10-14 ユーザー指定で作り直し)</h2>
 * <ul>
 *     <li><b>未感染</b>: 胞子濃度 {@code infectPercent}(50%)以上の場所に {@code infectDwellTicks}(30 秒)続けているとかかる。
 *     防護マスクをかぶっていればかからない。</li>
 *     <li><b>感染中</b>: 判定のたびに進みを足し、{@code stageTicks}(10 分)で 1 段上がる({@link SporeInfectionEffects#advance})。
 *     濃度 {@code speedUpPercent}(50%)以上の場所で +5%、過繁茂したバイオーム(スポロフィルの傘の下を含む)で +10% 速い。
 *     マスクをかぶっていれば加速しない。{@code xpDrainIntervalTicks}(10 秒)ごとに経験値を吸われる。</li>
 *     <li>前の仕組み(低濃度での確率・過繁茂バイオームに 5 分で 2・ハイヴノードの近くで 3・スポロフィルの傘で満了ごとに 1 段)は消した。</li>
 * </ul>
 *
 * <h2>Mob</h2>
 * <p>胞子の場が有効なオーバーワールドでは、空の下(繁茂・過繁茂のバイオームでは屋根の下も)にいる間だけその升の浮遊 × 秒を積み、
 * g1.json の infection(浮遊 50% に 30 秒相当)で胞子感染1(G1 の作り直し 第 1 段、2026-10-22 ユーザー決定)。
 * それ以外(ネザー・エンド・場が無効)は前のとおり、胞子濃度が {@code mobMinPercent}(50%)以上のチャンクに {@code mobDwellTicks}(30 秒)連続滞在で胞子感染1。
 *
 * <p>負荷対策として、各エンティティにつき {@link OvergrownVegetationConfig.Common#sporeInfectionCheckIntervalTicks}
 * (既定 20)tick に 1 回だけ判定し、その 1 回で各カウントを間隔ぶん進める。{@link BlightFernEntity}・{@link ParasitizedMob}・
 * {@link OvergrownNexusEntity}・攻城戦ボス・ペディキュラは除外。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class SporeInfectionTracker {

    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof LivingEntity entity)) {
            return;
        }
        if (!(entity.level() instanceof ServerLevel serverLevel)) {
            return;
        }

        int checkInterval = Math.max(1, OvergrownVegetationConfig.COMMON.sporeInfectionCheckIntervalTicks.get());
        if (checkInterval > 1 && Math.floorMod(entity.tickCount + entity.getId(), checkInterval) != 0) {
            return;
        }

        if (entity instanceof BlightFernEntity || entity instanceof ParasitizedMob
                || entity instanceof OvergrownNexusEntity
                || entity instanceof com.yayuy.overgrownvegetation.entity.DorgaArchelausTitanicaEntity
                || entity instanceof com.yayuy.overgrownvegetation.entity.PediculaEntity
                || !entity.isAlive()) {
            // ボス 2 種は自分が広げた汚染地形の上に居座るのが通常動作なので、そもそも測らない
            // (付与自体は ParasitizedSporeImmunity が全経路で弾いている。ここは無駄な濃度参照を省くだけ)。
            // ペディキュラも同じ —— いるチャンクを自分で 100% にするので、測ると自分の胞子で感染して死ぬ。
            return;
        }
        if (entity instanceof Player player) {
            if (player.isSpectator()) {
                resetAll(entity);
                return;
            }
            tickPlayer(serverLevel, player, checkInterval);
        } else {
            tickMob(serverLevel, entity, checkInterval);
        }
    }

    /** プレイヤー 1 人ぶんの判定({@code interval} tick ぶん進める)。GameTest からも呼ぶ(偽プレイヤーは tick しないため)。 */
    public static void tickPlayer(ServerLevel level, Player player, int interval) {
        EffectTable.PlayerInfectionSpec spec = DesignData.effects().playerInfection();
        BlockPos pos = player.blockPosition();
        double concentration = SporeConcentration.percentAt(level, pos);
        boolean masked = ProtectiveMaskItem.isWearing(player);
        if (concentration >= spec.infectPercent()) {
            // 濾し布の呼吸覆いは濃い胞子の中でだけすり減る(かかっていてもいなくても。2026-10-15)。
            com.yayuy.overgrownvegetation.item.BreathingClothItem.wear(level, player,
                    player.getData(ModAttachments.SPORE_EXPOSURE), interval);
        }

        if (SporeInfectionEffects.stageOf(player) == 0) {
            // 治った・死んだ後の残り(属性と進み)を片付ける。
            SporeInfectionEffects.syncModifiers(player);
            if (player.hasData(ModAttachments.SPORE_STAGE_TICKS) && player.getData(ModAttachments.SPORE_STAGE_TICKS) != 0) {
                player.setData(ModAttachments.SPORE_STAGE_TICKS, 0);
            }
            if (com.yayuy.overgrownvegetation.spore.SporeConcentration.useField(level)) { // 2026-10-23 F からネザー・エンドも
                // 暴露(2026-10-23 胞子の場 第 2 段 D): 濃度 × 秒を積み、「50% に 30 秒」と同じ量で感染。
                // マスクは暴露を maskPercent(既定 25%、10-23 ユーザー指定)に減らす。0% = かからない
                double dwell = spec.infectDwellTicks() * com.yayuy.overgrownvegetation.item.BreathingClothItem.dwellFactor(player)
                        * MedicineEffects.dwellFactor(player)
                        * Mth.lerp(MorningMist.exposureAt(level, player.getX(), player.getEyeY(), player.getZ()), 1.0D, spec.mistDwellFactor());
                double factor = masked ? DesignData.sporeField().maskFactor() : 1.0D;
                if (accumulateDose(player, concentration, factor, interval, spec.infectPercent() * dwell / 20.0D)) {
                    SporeInfectionEffects.startPlayer(player);
                }
                return;
            }
            if (masked || concentration < spec.infectPercent()) {
                resetConcentration(player);
                return;
            }
            SporeExposure exposure = player.getData(ModAttachments.SPORE_EXPOSURE);
            exposure.concentrationTicks += interval;
            // 濾し布の呼吸覆いを付けていれば 30 秒 → 60 秒(2026-10-15 ユーザー指定)。
            // 朝霧の中は胞子が濃く、早くかかる(2026-10-16 ユーザー指定。濃い朝の霧の底で mistDwellFactor 倍)。
            double mist = MorningMist.exposureAt(level, player.getX(), player.getEyeY(), player.getZ());
            // 胞子除けの粉薬を飲んでいればさらに延びる(2026-10-16、MedicineEffects)。
            double dwell = spec.infectDwellTicks() * com.yayuy.overgrownvegetation.item.BreathingClothItem.dwellFactor(player)
                    * MedicineEffects.dwellFactor(player)
                    * Mth.lerp(mist, 1.0D, spec.mistDwellFactor());
            if (exposure.concentrationTicks >= dwell) {
                exposure.concentrationTicks = 0;
                SporeInfectionEffects.startPlayer(player);
            }
            return;
        }

        SporeExposure exposure = player.getData(ModAttachments.SPORE_EXPOSURE);
        exposure.concentrationTicks = 0;

        // --- 経験値を吸う(10 秒に 1 回) ---
        exposure.xpDrainTicks += interval;
        if (exposure.xpDrainTicks >= spec.xpDrainIntervalTicks()) {
            exposure.xpDrainTicks -= spec.xpDrainIntervalTicks();
            if (spec.xpDrainPoints() > 0 && (player.totalExperience > 0 || player.experienceLevel > 0
                    || player.experienceProgress > 0.0F)) {
                player.giveExperiencePoints(-spec.xpDrainPoints());
            }
        }

        // --- 進行(場所による加速。マスクをかぶっていれば加速しない) ---
        double rate = 1.0D;
        if (!masked) {
            if (concentration >= spec.speedUpPercent()) {
                rate += spec.concentrationSpeedBonus();
            }
            if (ModBiomes.isFullyOvergrown(level.getBiome(pos)) || SporophyllAura.isInside(level, pos)) {
                rate += spec.overgrownBiomeSpeedBonus();
            }
            // 朝霧の中(2026-10-16 ユーザー指定)。霧の深さと濃さに比例。
            rate += spec.mistSpeedBonus() * MorningMist.exposureAt(level, player.getX(), player.getEyeY(), player.getZ());
        }
        // ハイヴノードの周り(2026-10-23 E4、ユーザー決定「5 分の 1」。読み方を確認中なので倍率は設計データ
        // spore_field.json giantTree.infectionSpeedMul、既定 5.0 = 5 倍速)。マスクで防げる(10-23 追記 16【決定】):
        // 上乗せ分(倍率 − 1)にマスクの暴露の割合(exposure.maskPercent、既定 25%)を掛ける = マスクありなら 1 + 4 × 0.25 = 2 倍
        com.yayuy.overgrownvegetation.data.SporeFieldTable gt = com.yayuy.overgrownvegetation.data.DesignData.sporeField();
        if (gt.hiveNodeInfectionRadius() > 0.0D
                && com.yayuy.overgrownvegetation.phase.PhaseData.get(level).hasHiveNodeWithin(pos, gt.hiveNodeInfectionRadius())) {
            double extra = gt.hiveNodeInfectionSpeedMul() - 1.0D;
            rate *= 1.0D + (masked ? extra * gt.maskFactor() : extra);
        }
        double exact = interval * rate + exposure.rateCarry;
        int ticks = (int) Math.floor(exact);
        exposure.rateCarry = exact - ticks;
        SporeInfectionEffects.advance(player, ticks);
        if (player.isAlive()) {
            SporeInfectionEffects.syncModifiers(player);
        }
    }

    /**
     * <p>カウンタ({@link ModAttachments#SPORE_EXPOSURE})は<b>本当に数え始めるときまで作らない</b>。
     * {@code getData} は付いていなければその場で作って貼り付けるので、頭で呼ぶと
     * <b>世界中の全エンティティ</b>が —— 胞子とは一生無縁な個体まで —— カウンタを 1 つずつ抱える。
     */
    public static void tickMob(ServerLevel level, LivingEntity mob, int interval) {
        if (mob.hasEffect(ModMobEffects.SPORE_INFECTION)) {
            // 感染中は計測不要(1 分後の死亡は効果の満了が担う)。
            resetConcentration(mob);
            return;
        }
        EffectTable fx = DesignData.effects();
        if (com.yayuy.overgrownvegetation.spore.field.SporeFieldServer.enabled() && (level.dimension() == net.minecraft.world.level.Level.OVERWORLD
                || level.dimension() == net.minecraft.world.level.Level.NETHER)) { // 2026-10-23 F からネザーも
            // G1 の作り直し 第 1 段(2026-10-22 ユーザー決定): 空の下にいる間だけ、その升の浮遊 × 秒を積み、
            // 浮遊 50% に 30 秒相当(g1.json infection)で感染。屋根の下では積まない(半減期で減っていく)。
            // ただしそこが繁茂・過繁茂のバイオームなら、周り中から胞子が出るので屋根の下でも積む。噛まれて感染する経路は別(今までどおり)。
            com.yayuy.overgrownvegetation.data.G1Table g = DesignData.g1();
            if (accumulateDose(mob, mobAirPercent(level, mob), 1.0D, interval, g.infectPercent * g.infectSeconds)) {
                SporeInfectionEffects.infect(mob, 0);
            }
            return;
        }
        double concentration = SporeConcentration.percentAt(level, mob.blockPosition());
        if (concentration < fx.mobMinPercent()) {
            resetConcentration(mob);
            return;
        }
        SporeExposure exposure = mob.getData(ModAttachments.SPORE_EXPOSURE);
        exposure.concentrationTicks += interval;
        if (exposure.concentrationTicks >= fx.mobDwellTicks()) {
            exposure.concentrationTicks = 0;
            SporeInfectionEffects.infect(mob, 0);
        }
    }

    /**
     * Mob が浴びている浮遊胞子(%)。その升の浮遊({@code SporeFieldServer#airAt})を g1.json の airFullAt(0 なら reading.fullAt)で割る。
     * 空の下でなく(目の高さが「葉以外の動きを止めるブロック」の天辺より下 = 屋根の下。木の葉は屋根にしない、仮決め)、
     * かつ繁茂・過繁茂のバイオームでもなければ 0。写しが無い所(プレイヤーから遠い)も 0。
     */
    public static double mobAirPercent(ServerLevel level, LivingEntity mob) {
        com.yayuy.overgrownvegetation.data.G1Table g = DesignData.g1();
        BlockPos pos = mob.blockPosition();
        if (g.underSkyOnly && !underSky(level, mob) && !ModBiomes.isOvergrownLike(level.getBiome(pos))) {
            return 0.0D;
        }
        double air = com.yayuy.overgrownvegetation.spore.field.SporeFieldServer.airAt(level, pos.getX() >> 4, pos.getZ() >> 4);
        if (Double.isNaN(air)) {
            return 0.0D;
        }
        double full = g.airFullAt > 0.0D ? g.airFullAt : DesignData.sporeField().fullAt();
        // 地割れの周りの下支え(2026-10-24 追記 19。感染の上限は設けない)
        return air / full * 100.0D + com.yayuy.overgrownvegetation.spore.SporeConcentration.riftFloorPercent(level, pos);
    }

    /**
     * 空の下か(目の高さより上に、葉を除いた動きを止めるブロック・水が無い)。高さの地図で引き、地図の天辺が目より上なら
     * 間を数える(48 ブロックより深ければ屋根の下)—— 高さの地図はバリア(テスト場の天井など)も屋根に数えるので、バリアは飛ばす。
     */
    public static boolean underSky(ServerLevel level, LivingEntity mob) {
        int x = mob.getBlockX(), z = mob.getBlockZ();
        int top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        int eye = Mth.floor(mob.getEyeY());
        if (eye >= top) {
            return true;
        }
        if (top - eye > 48) {
            return false;
        }
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int y = eye; y < top; y++) {
            net.minecraft.world.level.block.state.BlockState s = level.getBlockState(p.set(x, y, z));
            if (s.is(net.minecraft.world.level.block.Blocks.BARRIER) || s.is(net.minecraft.tags.BlockTags.LEAVES)) {
                continue;
            }
            if (s.blocksMotion() || !s.getFluidState().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 暴露を積む(2026-10-23 胞子の場 第 2 段 D)。濃度が exposure.minPercent 以上なら 濃度 × 倍率 × 秒 を足し、
     * それ未満なら半減期で減らす。{@code threshold}(%秒)に届いたら 0 に戻して true。
     * まだカウンタを持っていない個体には、積むときまで作らない。
     */
    private static boolean accumulateDose(LivingEntity entity, double concentration, double factor, int interval, double threshold) {
        com.yayuy.overgrownvegetation.data.SporeFieldTable t = DesignData.sporeField();
        double seconds = interval / 20.0D;
        if (concentration >= t.exposureMinPercent() && factor > 0.0D) {
            SporeExposure exposure = entity.getData(ModAttachments.SPORE_EXPOSURE);
            exposure.dose += concentration * factor * seconds;
            if (exposure.dose >= threshold) {
                exposure.dose = 0.0D;
                return true;
            }
            return false;
        }
        if (entity.hasData(ModAttachments.SPORE_EXPOSURE)) {
            SporeExposure exposure = entity.getData(ModAttachments.SPORE_EXPOSURE);
            if (exposure.dose > 0.0D) {
                exposure.dose *= Math.pow(0.5D, seconds / t.exposureHalfLifeSeconds());
                if (exposure.dose < 1.0D) exposure.dose = 0.0D;
            }
        }
        return false;
    }

    /** テスト用: 暴露を 1 回積む(届いたら true)。 */
    public static boolean accumulateDoseForTests(LivingEntity entity, double concentration, double factor, int interval, double threshold) {
        return accumulateDose(entity, concentration, factor, interval, threshold);
    }

    /** 濃度の連続滞在カウントを 0 に戻す。まだカウンタを持っていない個体には作らない。 */
    private static void resetConcentration(LivingEntity entity) {
        if (entity.hasData(ModAttachments.SPORE_EXPOSURE)) {
            entity.getData(ModAttachments.SPORE_EXPOSURE).concentrationTicks = 0;
        }
    }

    private static void resetAll(LivingEntity entity) {
        if (entity.hasData(ModAttachments.SPORE_EXPOSURE)) {
            entity.getData(ModAttachments.SPORE_EXPOSURE).resetAll();
        }
    }

    private SporeInfectionTracker() {
    }
}
