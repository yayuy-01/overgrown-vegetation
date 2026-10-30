package com.yayuy.overgrownvegetation.raid;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.entity.OvergrownNexusEntity;
import com.yayuy.overgrownvegetation.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.attributes.Attributes;

import javax.annotation.Nullable;

/**
 * レイドの開始処理。場所を抽選し、「過繫茂の苗床」({@link OvergrownNexusEntity})を出現させ、
 * {@link RaidData} に登録して全体へ告知する。
 */
public final class RaidController {

    /**
     * レイドを1つ開始しようとする。
     *
     * @param forcedOrigin 出現座標を指定する(コマンド用)。{@code null} なら {@link RaidLocationPicker} で抽選。
     * @return 実際に開始できたら true。難易度がピースフル / 適地が無い / エンティティ生成に失敗した場合は false。
     */
    public static boolean tryStartRaid(ServerLevel overworld, @Nullable BlockPos forcedOrigin) {
        if (com.yayuy.overgrownvegetation.difficulty.Peaceful.is(overworld)) {
            // ピースフルでは始めない(difficulty/Peaceful。2026-09-26)。昇格レイドは lastRaidPhase が進まないので、
            // ピースフルを抜けてから RaidData#tick が起こす。
            return false;
        }
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;

        BlockPos origin = forcedOrigin != null
                ? forcedOrigin
                : RaidLocationPicker.pick(overworld, cfg.raidSpawnMinDistance.get(), cfg.raidSpawnMaxDistance.get());
        if (origin == null) {
            return false;
        }

        // 先に苗床の周囲を強制ロードしておく(誰も居なくても苗床が止まらないように)。
        // 2026-09-25(報告 1097): 同期で読み込むのは苗床を置く中心のチャンクだけ。残りは印だけ付けて裏で読み込む。
        long[] forcedChunks = RaidChunkLoader.forceLoadingCenter(overworld, origin, cfg.raidForceLoadRadius.get());

        EntityType<OvergrownNexusEntity> type = ModEntities.OVERGROWN_NEXUS.get();
        OvergrownNexusEntity nexus = type.create(overworld, null, origin, MobSpawnType.EVENT, false, false);
        if (nexus == null) {
            RaidChunkLoader.release(overworld, forcedChunks);
            OvergrownVegetation.LOGGER.warn("レイド: 苗床エンティティの生成に失敗しました。");
            return false;
        }

        long now = overworld.getGameTime();
        nexus.setRaidStartGameTime(now);
        nexus.moveTo(origin.getX() + 0.5D, origin.getY(), origin.getZ() + 0.5D, overworld.random.nextFloat() * 360.0F, 0.0F);
        nexus.getAttribute(Attributes.MAX_HEALTH).setBaseValue(cfg.raidNexusHealth.get());
        nexus.setHealth(nexus.getMaxHealth());
        overworld.addFreshEntity(nexus);

        RaidData.get(overworld).addRaid(new RaidData.ActiveRaid(nexus.getUUID(), origin, now, forcedChunks));

        overworld.getServer().getPlayerList().broadcastSystemMessage(
                Component.translatable("message.overgrown_vegetation.raid.started"), false);
        // 胞子警報器(2026-09-19): 近くのプレイヤーへ方角と距離つきで。
        com.yayuy.overgrownvegetation.defense.SporeAlarms.warn(overworld,
                Component.translatable("message.overgrown_vegetation.alarm.raid"), net.minecraft.world.phys.Vec3.atCenterOf(origin));
        overworld.playSound(null, origin, SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 4.0F, 0.6F);

        OvergrownVegetation.LOGGER.info("レイド開始: 苗床を {} に出現させました。", origin);
        return true;
    }

    private RaidController() {
    }
}
