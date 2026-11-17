package com.yayuy.overgrownvegetation.gear;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * 繊維の草が<b>縁(繁茂のバイオーム)</b>に少しずつ生える(2026-11-17)。胞子喰い茸の {@code SporeEaterSpread} と同じ作り:
 * {@code intervalTicks} ごとに、プレイヤーのまわり {@code radius} で {@code tries} 回列を選び、高さ地図の所から林床へ下へたどって、
 * しっかりした面の上の空気(か 1 マスの下草)に置く。まわり {@code crowdRadius} に {@code crowdMax} 本あれば置かない。明るさは問わない(縁は明るい)。
 * 天井のある次元(ネザー)では生やさない。数はどれも【仮】。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class FiberGrassSpread {

    private static final int FLOOR_SCAN = 48;

    private FiberGrassSpread() {
    }

    private static GearTierTable.FiberGrass spec() {
        return DesignData.gearTiers().fiberGrass;
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimensionType().hasCeiling()) return;
        GearTierTable.FiberGrass s = spec();
        if (s.tries <= 0 || s.intervalTicks <= 0 || level.getGameTime() % s.intervalTicks != 0L) return;
        for (ServerPlayer player : level.players()) {
            if (!player.isSpectator()) spreadAround(level, player.blockPosition(), level.random);
        }
    }

    public static int spreadAround(ServerLevel level, BlockPos center, RandomSource random) {
        GearTierTable.FiberGrass s = spec();
        int placed = 0;
        int r = Math.max(1, s.radius);
        for (int i = 0; i < s.tries; i++) {
            int x = center.getX() + random.nextInt(2 * r + 1) - r;
            int z = center.getZ() + random.nextInt(2 * r + 1) - r;
            if (!level.hasChunkAt(new BlockPos(x, 0, z))) continue;
            BlockPos pos = findFloor(level, x, z);
            if (pos != null && tryPlace(level, pos, true)) placed++;
        }
        return placed;
    }

    /** {@code pos} に生えてよければ置く。{@code checkBiome} が偽なら繁茂のバイオームかを見ない(GameTest 用)。 */
    public static boolean tryPlace(ServerLevel level, BlockPos pos, boolean checkBiome) {
        if (!roomFor(level.getBlockState(pos))) return false;
        if (checkBiome && !ModBiomes.isOvergrowthStage(level.getBiome(pos))) return false;
        BlockState grass = ModGear.FIBER_GRASS.get().defaultBlockState();
        if (!grass.canSurvive(level, pos) || crowded(level, pos)) return false;
        return level.setBlock(pos, grass, 3);
    }

    static BlockPos findFloor(ServerLevel level, int x, int z) {
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, top, z);
        for (int y = top; y > Math.max(level.getMinBuildHeight(), top - FLOOR_SCAN); y--) {
            p.setY(y);
            if (!roomFor(level.getBlockState(p))) continue;
            BlockPos below = p.below();
            BlockState under = level.getBlockState(below);
            if (under.is(BlockTags.LEAVES) || !under.isFaceSturdy(level, below, Direction.UP)) continue;
            return p.immutable();
        }
        return null;
    }

    private static boolean roomFor(BlockState here) {
        if (here.isAir()) return true;
        return here.canBeReplaced() && here.getFluidState().isEmpty()
                && !(here.getBlock() instanceof DoublePlantBlock) && !here.is(ModGear.FIBER_GRASS.get());
    }

    private static boolean crowded(ServerLevel level, BlockPos pos) {
        GearTierTable.FiberGrass s = spec();
        int r = s.crowdRadius;
        int count = 0;
        for (BlockPos p : BlockPos.betweenClosed(pos.offset(-r, -2, -r), pos.offset(r, 2, r))) {
            if (level.getBlockState(p).is(ModGear.FIBER_GRASS.get()) && ++count >= s.crowdMax) return true;
        }
        return false;
    }
}
