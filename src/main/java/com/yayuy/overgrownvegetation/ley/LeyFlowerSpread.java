package com.yayuy.overgrownvegetation.ley;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.LeyLines;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.LeySpec;
import com.yayuy.overgrownvegetation.registry.ModLey;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * 遊んでいる間に、地脈の線の上へ地脈の花が少しずつ増える(決定 25。今までのワールド = 花の世界生成より前に作ったチャンクにも出るように)。
 * オーバーワールドだけ【仮】。{@code flowerSpreadIntervalTicks} ごとに、プレイヤー 1 人のまわり {@code flowerSpreadRadius} の中で
 * {@code flowerSpreadTries} 回列を選び、線の帯の中・読み込み済み・草/土の上の空気・まわり {@code flowerCrowdRadius} の花が {@code flowerCrowdMax} 本未満なら 1 本置く。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class LeyFlowerSpread {

    private LeyFlowerSpread() {
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) {
            return;
        }
        LeySpec spec = DesignData.sporeField().ley();
        if (spec.flowerSpreadTries() <= 0 || level.getGameTime() % spec.flowerSpreadIntervalTicks() != 0L) {
            return;
        }
        for (ServerPlayer player : level.players()) {
            if (!player.isSpectator()) {
                spreadAround(level, player.blockPosition(), spec, level.random);
            }
        }
    }

    /** {@code center} のまわりで決まりどおりに試し、置いた本数を返す(GameTest からも呼ぶ)。 */
    public static int spreadAround(ServerLevel level, BlockPos center, LeySpec spec, RandomSource random) {
        int placed = 0;
        int r = spec.flowerSpreadRadius();
        for (int i = 0; i < spec.flowerSpreadTries(); i++) {
            int x = center.getX() + random.nextInt(2 * r + 1) - r;
            int z = center.getZ() + random.nextInt(2 * r + 1) - r;
            if (!com.yayuy.overgrownvegetation.perf.ChunkEdgeGuard.hasChunkAt(level, new BlockPos(x, 0, z)) || !LeyLines.inFlowerBand(level.getSeed(), x, z)) {
                continue;
            }
            BlockPos pos = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
            if (crowded(level, pos, spec)) {
                continue;
            }
            if (LeyFlowerFeature.placeAt(level, pos)) {
                placed++;
            }
        }
        return placed;
    }

    private static boolean crowded(ServerLevel level, BlockPos pos, LeySpec spec) {
        int r = spec.flowerCrowdRadius();
        int count = 0;
        for (BlockPos p : BlockPos.betweenClosed(pos.offset(-r, -2, -r), pos.offset(r, 2, r))) {
            if (level.getBlockState(p).is(ModLey.FLOWER.get()) && ++count >= spec.flowerCrowdMax()) {
                return true;
            }
        }
        return false;
    }
}
