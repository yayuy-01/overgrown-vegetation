package com.yayuy.overgrownvegetation.phase;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.effect.SporeInfectionEffects;
import com.yayuy.overgrownvegetation.entity.BlightFernEntity;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;

import java.util.EnumSet;

/**
 * 過繁茂・繁茂バイオームの中の、{@link ParasitizedMob}・{@link BlightFernEntity}・
 * グループ所属 Mob 以外の Mob の自然湧きを振り分ける。
 *
 * <ul>
 *     <li><b>過繁茂バイオーム({@link ModBiomes#isFullyOvergrown})</b>: このバイオームには
 *     このMODの Mob だけで生態系を築かせたいので、足元のブロックに関係なくバニラ Mob の
 *     自然湧きは<b>すべて打ち消す</b>。</li>
 *     <li><b>繁茂バイオーム({@link ModBiomes#isOvergrowthStage})</b> の汚染ブロックの上:
 *     その湧きを打ち消す。</li>
 *     <li><b>繁茂バイオームの汚染されていないブロックの上</b>: 通常 Mob をそのまま湧かせるが、
 *     {@code overgrownBiomeWildSpawnInfectChance}(既定 1.0)で「胞子感染1」を持たせる。</li>
 * </ul>
 *
 * <p>過繁茂バイオームの json は {@code spawners} が空なのでバイオーム由来の自然スポーンは
 * もともと発生しないが、ピリジャーの巡回隊({@link MobSpawnType#PATROL})・ファントム・
 * 増援({@link MobSpawnType#REINFORCEMENT})などバイオームのスポーン設定を経由しない湧きは
 * 依然として過繁茂バイオームの中に現れうる。それらがここの対象。
 *
 * <p>スポーンエッグ・{@code /summon}・繁殖・スポナー・{@code MOB_SUMMONED}(死体ブロックの
 * blight_fern 湧きなど)といった意図的な湧きは対象外。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class OvergrownBiomeSpawnFilter {

    /** 「自然に湧いた」とみなして振り分ける対象のスポーン種別。 */
    private static final EnumSet<MobSpawnType> WILD_SPAWN_TYPES = EnumSet.of(
            MobSpawnType.NATURAL,
            MobSpawnType.CHUNK_GENERATION,
            MobSpawnType.PATROL,
            MobSpawnType.REINFORCEMENT,
            MobSpawnType.JOCKEY);

    // NaturalInfectionSpawner(既定 NORMAL)より後に走らせる。あちらが「過繁茂した(MOB名)」への
    // 置き換えでこの湧きを既にキャンセル済みなら、ここは何もしない。
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onFinalizeSpawn(FinalizeSpawnEvent event) {
        if (event.isSpawnCancelled()) {
            return;
        }
        if (!OvergrownVegetationConfig.COMMON.overgrownBiomeBlockWildSpawns.get()) {
            return;
        }
        if (!WILD_SPAWN_TYPES.contains(event.getSpawnType())) {
            return;
        }
        Mob mob = event.getEntity();
        // 「過繁茂した Mob」(グループ所属)・汚染シダは、OvergrownBiomeSpawner 自身が
        // MobSpawnType.NATURAL で直接湧かせている当のものなので打ち消さない。
        // ParasitizedMob だけを見ていると overgrown_shielder(グループ3)など
        // ParasitizedMob でないグループ Mob が汚染ブロックの上で弾かれてしまう。
        if (mob instanceof ParasitizedMob || mob instanceof BlightFernEntity || MobGroup.of(mob) != null) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        BlockPos pos = mob.blockPosition();
        var biome = serverLevel.getBiome(pos);
        if (!ModBiomes.isOvergrownLike(biome)) {
            return;
        }

        if (ModBiomes.isFullyOvergrown(biome)) {
            // 過繁茂バイオームは MOD の Mob だけの生態系にする — バニラ Mob の自然湧きは全部打ち消す。
            event.setSpawnCancelled(true);
            return;
        }

        if (serverLevel.getBlockState(pos.below()).is(ModTags.CORRUPTED)) {
            // 繁茂バイオームの汚染ブロックの上 — 過繁茂した Mob だけが存在する場所なので打ち消す。
            event.setSpawnCancelled(true);
            return;
        }

        // 汚染されていないブロックの上 — 通常 Mob を湧かせつつ「胞子感染1」を持たせる。
        double infectChance = OvergrownVegetationConfig.COMMON.overgrownBiomeWildSpawnInfectChance.get();
        if (infectChance > 0.0D && mob.getRandom().nextDouble() < infectChance) {
            SporeInfectionEffects.infect(mob, 0);
        }
    }

    private OvergrownBiomeSpawnFilter() {
    }
}
