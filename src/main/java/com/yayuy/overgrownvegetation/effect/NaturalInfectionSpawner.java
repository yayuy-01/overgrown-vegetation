package com.yayuy.overgrownvegetation.effect;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.entity.BlightFernEntity;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;

/**
 * フェーズ1以降、自然スポーン({@code MobSpawnType.NATURAL})する対象 Mob
 * ({@code #overgrown_vegetation:phase_infectable_animals})に、湧いた瞬間から「胞子感染1」を
 * {@code phase1InfectionChance}(既定 5%)の確率で持たせる。
 *
 * <p>スポーンエッグ・{@code /summon}・繁殖・スポナーなど自然スポーン以外は対象外。
 * {@link BlightFernEntity} と「過繁茂した Mob」(グループ所属 Mob)自身も除外する
 * (既に感染しきっている / 感染する意味が無い)。
 *
 * <p>「過繁茂した Mob」そのものの湧きはこのクラスでは扱わない —
 * 過繁茂バイオームの中への直接スポーンは {@link com.yayuy.overgrownvegetation.phase.OvergrownBiomeSpawner}
 * が担当する。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class NaturalInfectionSpawner {

    @SubscribeEvent
    public static void onFinalizeSpawn(FinalizeSpawnEvent event) {
        if (event.getSpawnType() != MobSpawnType.NATURAL) {
            return;
        }
        Mob mob = event.getEntity();
        if (mob instanceof BlightFernEntity || MobGroup.of(mob) != null) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        if (PhaseData.get(serverLevel).getPhase() < 1) {
            return;
        }

        EntityType<?> type = mob.getType();
        if (!type.is(ModTags.PHASE_INFECTABLE_ANIMALS)) {
            return;
        }
        double infectChance = OvergrownVegetationConfig.COMMON.phase1InfectionChance.get();
        if (infectChance <= 0.0D || mob.getRandom().nextDouble() >= infectChance) {
            return;
        }
        SporeInfectionEffects.infect(mob, 0);
    }

    private NaturalInfectionSpawner() {
    }
}
