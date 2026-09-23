package com.yayuy.overgrownvegetation.effect;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.EffectTable;
import com.yayuy.overgrownvegetation.entity.BlightFernEntity;
import com.yayuy.overgrownvegetation.entity.OvergrownNexusEntity;
import com.yayuy.overgrownvegetation.entity.SporophyllAura;
import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import com.yayuy.overgrownvegetation.registry.ModMobEffects;
import com.yayuy.overgrownvegetation.spore.SporeConcentration;
import com.yayuy.overgrownvegetation.spore.SporeExposure;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * 胞子濃度({@link SporeConcentration})・過繫茂バイオーム滞在・巨大樹の核への接近による
 * 「胞子感染」({@link ModMobEffects#SPORE_INFECTION})の付与/進行/継続の判定。
 * 数値はすべて {@code effects.json}({@link EffectTable})。
 *
 * <h2>プレイヤー</h2>
 * <ul>
 *     <li><b>未感染</b>: 胞子濃度が {@code playerHighMinPercent}(51%)以上なら {@code playerHighDwellTicks}(20 秒)の
 *     連続滞在で胞子感染1 が確定。{@code playerLowMinPercent}(1%)〜51% 未満なら {@code playerLowRollIntervalTicks}
 *     (30 秒)滞在するごとに「胞子濃度 % と同じ確率」で胞子感染1。1% 未満で滞在カウントはリセット。</li>
 *     <li><b>継続</b>(残り時間が次の判定までに尽きるとき):
 *     胞子感染1 → 濃度が {@code level1.continuePercent}(51%)以上なら途切れず再付与。
 *     胞子感染2/3 → 過繫茂バイオーム内なら途切れず再付与、外なら一段下げる(2 → 1)。
 *     胞子感染3 は外でも下げない — 自然満了で死亡させる({@link SporeInfectionExpiryHandler})。</li>
 *     <li><b>過繫茂バイオーム滞在</b>: 感染状態を問わず {@code level2.overgrownBiomeDwellTicks}(5 分)で胞子感染2。
 *     胞子感染2 を持った状態でさらに {@code level3.overgrownBiomeDwellTicks}(10 分)で胞子感染3。</li>
 *     <li><b>ハイヴノード</b>: 胞子感染2 を持った状態でハイヴノードから {@code level3.giantTreeCoreRadius}(60 ブロック)以内に入ると胞子感染3。</li>
 * </ul>
 *
 * <h2>Mob</h2>
 * <p>胞子濃度が {@code mobMinPercent}(50%)以上のチャンクに {@code mobDwellTicks}(30 秒)連続滞在で胞子感染1。
 * 感染中は計測しない(1 分後の死亡は効果の満了が担う)。
 *
 * <p>負荷対策として、各エンティティにつき {@link OvergrownVegetationConfig.Common#sporeInfectionCheckIntervalTicks}
 * (既定 20)tick に 1 回だけ判定し、その 1 回で各カウントを間隔ぶん進める/0 に戻す
 * (「合計」ではなく「連続」滞在)。{@link BlightFernEntity}・{@link ParasitizedMob}・{@link OvergrownNexusEntity} は除外。
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
                || !entity.isAlive()) {
            // ボス 2 種は自分が広げた汚染地形の上に居座るのが通常動作なので、そもそも測らない
            // (付与自体は ParasitizedSporeImmunity が全経路で弾いている。ここは無駄な濃度参照を省くだけ)。
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

    private static void tickPlayer(ServerLevel level, Player player, int interval) {
        EffectTable fx = DesignData.effects();
        SporeExposure exposure = player.getData(ModAttachments.SPORE_EXPOSURE);
        BlockPos pos = player.blockPosition();
        double concentration = SporeConcentration.percentAt(level, pos);
        // スポロフィルの傘の下は過繫茂バイオームと同じ扱い(ユーザー指定 2026-09-14)。
        // 感染が満了したとき、途切れずに 1 → 2 → 3 と上がっていくのはこの判定のため。
        boolean inFullyOvergrown = ModBiomes.isFullyOvergrown(level.getBiome(pos))
                || SporophyllAura.isInside(level, pos);
        MobEffectInstance current = player.getEffect(ModMobEffects.SPORE_INFECTION);
        int currentLevel = current == null ? -1 : current.getAmplifier();

        // --- A. 未感染: 胞子濃度による胞子感染1 ---
        if (current == null) {
            if (concentration >= fx.playerHighMinPercent()) {
                exposure.concentrationTicks += interval;
                if (exposure.concentrationTicks >= fx.playerHighDwellTicks()) {
                    exposure.concentrationTicks = 0;
                    SporeInfectionEffects.infect(player, 0);
                }
            } else if (concentration >= fx.playerLowMinPercent()) {
                exposure.concentrationTicks += interval;
                if (exposure.concentrationTicks >= fx.playerLowRollIntervalTicks()) {
                    exposure.concentrationTicks = 0;
                    if (player.getRandom().nextDouble() * 100.0D < concentration) {
                        SporeInfectionEffects.infect(player, 0);
                    }
                }
            } else {
                exposure.concentrationTicks = 0;
            }
        } else {
            exposure.concentrationTicks = 0;
            // --- B. 継続 / 段階の引き下げ(残り時間が次の判定までに尽きるとき) ---
            if (current.getDuration() <= interval) {
                if (currentLevel < 2 && SporophyllAura.isInside(level, pos)) {
                    // スポロフィルの傘の下だけは<b>満了のたびに 1 段階上がる</b>
                    // (ユーザー指定 2026-09-14:「胞子感染1 が切れると 2 に、2 が切れると 3 に」)。
                    // 過繫茂バイオームは満了しても同じ段階を張り直すだけなので、ここが一番きつい場所になる。
                    SporeInfectionEffects.infect(player, currentLevel + 1);
                } else if (currentLevel == 0) {
                    if (concentration >= fx.level(0).continuePercent()) {
                        SporeInfectionEffects.infect(player, 0);
                    }
                } else if (currentLevel == 1) {
                    if (inFullyOvergrown) {
                        SporeInfectionEffects.infect(player, 1);
                    } else {
                        SporeInfectionEffects.downgrade(player, 0);
                    }
                } else if (inFullyOvergrown && !fx.level(currentLevel).killPlayerOnExpiry()) {
                    // 胞子感染3 は満了で死亡する設定が既定なので、通常ここには来ない。
                    SporeInfectionEffects.infect(player, currentLevel);
                }
            }
        }

        // --- C. 過繫茂バイオーム滞在による進行 ---
        if (inFullyOvergrown) {
            exposure.overgrownBiomeTicks += interval;
            int toLevel2 = fx.level(1).overgrownBiomeDwellTicks();
            if (toLevel2 > 0 && currentLevel < 1 && exposure.overgrownBiomeTicks >= toLevel2) {
                exposure.overgrownBiomeTicks = 0;
                if (SporeInfectionEffects.infect(player, 1)) {
                    currentLevel = 1;
                }
            }
            if (currentLevel == 1) {
                exposure.level2BiomeTicks += interval;
                int toLevel3 = fx.level(2).overgrownBiomeDwellTicks();
                if (toLevel3 > 0 && exposure.level2BiomeTicks >= toLevel3) {
                    exposure.level2BiomeTicks = 0;
                    if (SporeInfectionEffects.infect(player, 2)) {
                        currentLevel = 2;
                    }
                }
            } else {
                exposure.level2BiomeTicks = 0;
            }
        } else {
            exposure.overgrownBiomeTicks = 0;
            exposure.level2BiomeTicks = 0;
        }

        // --- D. 巨大樹の核への接近(胞子感染2 → 3) ---
        if (currentLevel == 1) {
            double radius = fx.level(2).giantTreeCoreRadius();
            if (radius > 0.0D && level.dimension() == Level.OVERWORLD
                    && PhaseData.get(level).hasHiveNodeWithin(pos, radius)) {
                SporeInfectionEffects.infect(player, 2);
            }
        }
    }

    /**
     * <p>カウンタ({@link ModAttachments#SPORE_EXPOSURE})は<b>本当に数え始めるときまで作らない</b>。
     * {@code getData} は付いていなければその場で作って貼り付けるので、頭で呼ぶと
     * <b>世界中の全エンティティ</b>が —— 胞子とは一生無縁な個体まで —— カウンタを 1 つずつ抱える。
     */
    private static void tickMob(ServerLevel level, LivingEntity mob, int interval) {
        if (mob.hasEffect(ModMobEffects.SPORE_INFECTION)) {
            // 感染中は計測不要(1 分後の死亡は効果の満了が担う)。
            resetConcentration(mob);
            return;
        }
        EffectTable fx = DesignData.effects();
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
