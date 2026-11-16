package com.yayuy.overgrownvegetation.survival.expedition;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.ExpeditionSpec;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import com.yayuy.overgrownvegetation.registry.ModExpedition;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * 胞子喰い茸が過繁茂の森の地面に少しずつ生える(2026-11-16 段 4)。地脈の花の {@code LeyFlowerSpread} と同じ作り:
 * {@code mushroomSpreadIntervalTicks} ごとに、プレイヤー 1 人のまわり {@code mushroomSpreadRadius} の中で {@code mushroomSpreadTries} 回列を選び、
 * 過繁茂のバイオーム・葉を除いた高さ地図の地面の上の空気・空の光 {@code mushroomMaxSkyLight} 以下(葉の下の暗がり)・
 * まわり {@code mushroomCrowdRadius} に {@code mushroomCrowdMax} 本未満なら 1 本置く。どの次元でも(過繁茂のバイオームだけを見る)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class SporeEaterSpread {

    private SporeEaterSpread() {
    }

    private static ExpeditionSpec spec() {
        return DesignData.sporeField().expedition();
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        ExpeditionSpec s = spec();
        if (s.mushroomSpreadTries() <= 0 || level.getGameTime() % s.mushroomSpreadIntervalTicks() != 0L) return;
        for (ServerPlayer player : level.players()) {
            if (!player.isSpectator()) spreadAround(level, player.blockPosition(), level.random);
        }
    }

    /** {@code center} のまわりで決まりどおりに試し、置いた本数を返す。 */
    public static int spreadAround(ServerLevel level, BlockPos center, RandomSource random) {
        ExpeditionSpec s = spec();
        int placed = 0;
        int r = s.mushroomSpreadRadius();
        for (int i = 0; i < s.mushroomSpreadTries(); i++) {
            int x = center.getX() + random.nextInt(2 * r + 1) - r;
            int z = center.getZ() + random.nextInt(2 * r + 1) - r;
            if (!level.hasChunkAt(new BlockPos(x, 0, z))) continue;
            BlockPos pos = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
            if (tryPlace(level, pos)) placed++;
        }
        return placed;
    }

    /** {@code pos}(空気のマス)に生えてよければ置く(GameTest からも呼ぶ)。 */
    public static boolean tryPlace(ServerLevel level, BlockPos pos) {
        if (!canGrowAt(level, pos)) return false;
        return level.setBlock(pos, ModExpedition.SPORE_EATER_MUSHROOM.get().defaultBlockState(), 3);
    }

    /** {@code pos} に生える決まりを満たすか。 */
    public static boolean canGrowAt(ServerLevel level, BlockPos pos) {
        return canGrowAt(level, pos, true);
    }

    /** {@code checkBiome} が偽なら過繁茂のバイオームかを見ない(テスト場は過繁茂でないので、GameTest でほかの決まりを確かめるため)。 */
    public static boolean canGrowAt(ServerLevel level, BlockPos pos, boolean checkBiome) {
        ExpeditionSpec s = spec();
        if (!level.getBlockState(pos).isAir()) return false;
        if (checkBiome && !ModBiomes.isFullyOvergrown(level.getBiome(pos))) return false;
        if (level.getBrightness(LightLayer.SKY, pos) > s.mushroomMaxSkyLight()) return false;
        BlockState mushroom = ModExpedition.SPORE_EATER_MUSHROOM.get().defaultBlockState();
        if (!mushroom.canSurvive(level, pos)) return false;
        return !crowded(level, pos, s);
    }

    private static boolean crowded(ServerLevel level, BlockPos pos, ExpeditionSpec s) {
        int r = s.mushroomCrowdRadius();
        int count = 0;
        for (BlockPos p : BlockPos.betweenClosed(pos.offset(-r, -2, -r), pos.offset(r, 2, r))) {
            if (level.getBlockState(p).is(ModExpedition.SPORE_EATER_MUSHROOM.get()) && ++count >= s.mushroomCrowdMax()) {
                return true;
            }
        }
        return false;
    }
}
