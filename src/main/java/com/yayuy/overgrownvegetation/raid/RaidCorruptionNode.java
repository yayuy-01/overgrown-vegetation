package com.yayuy.overgrownvegetation.raid;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.corruption.CorruptionSpreader;
import com.yayuy.overgrownvegetation.phase.CorruptionCounter;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;

/**
 * 徐々に伸びる根({@link RaidRootGrowth})に沿って落とされる「汚染の起点」1つ。
 * その場所を中心に {@link CorruptionSpreader} で球状に汚染を広げる。blight_fern と同じ仕組みだが、
 * エンティティを持たず {@link com.yayuy.overgrownvegetation.entity.OvergrownNexusEntity} が
 * まとめて tick する。
 */
public final class RaidCorruptionNode {

    private final BlockPos origin;
    private final CorruptionSpreader spreader = new CorruptionSpreader();

    public RaidCorruptionNode(BlockPos origin) {
        this.origin = origin.immutable();
        this.spreader.setDecorationChanceMultiplier(
                OvergrownVegetationConfig.COMMON.raidCorruptionArchMultiplier.get());
    }

    public BlockPos origin() {
        return origin;
    }

    public boolean isFinished() {
        return spreader.isFinished();
    }

    /** 1サイクル進める。実際に汚染したブロック数を {@link CorruptionCounter} に計上する。 */
    public void tick(ServerLevel level) {
        if (spreader.isFinished()) {
            return;
        }
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        spreader.seedGround(level, origin);
        int before = spreader.getCorruptedCount();
        spreader.tick(level, origin,
                cfg.raidCorruptionRadius.get(),
                cfg.blightFernInteriorDepth.get(),
                cfg.raidCorruptionCoreRadius.get(),
                cfg.raidTendrilInitialCount.get(),
                cfg.raidTendrilMaxActive.get());
        CorruptionCounter.record(level, spreader.getCorruptedCount() - before);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.put("origin", NbtUtils.writeBlockPos(origin));
        spreader.save(tag);
        return tag;
    }

    public static RaidCorruptionNode load(CompoundTag tag) {
        BlockPos origin = NbtUtils.readBlockPos(tag, "origin").orElse(BlockPos.ZERO);
        RaidCorruptionNode node = new RaidCorruptionNode(origin);
        node.spreader.load(tag);
        return node;
    }
}
