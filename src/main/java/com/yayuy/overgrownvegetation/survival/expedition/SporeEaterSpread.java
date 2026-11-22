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
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import com.yayuy.overgrownvegetation.port.event.tick.LevelTickEvent;

/**
 * 胞子喰い茸が過繁茂の森の地面に少しずつ生える(2026-11-16 段 4)。地脈の花の {@code LeyFlowerSpread} と同じ作り:
 * {@code mushroomSpreadIntervalTicks} ごとに、プレイヤー 1 人のまわり {@code mushroomSpreadRadius} の中で {@code mushroomSpreadTries} 回列を選び、
 * 過繁茂のバイオーム・葉を除いた高さ地図の地面の上の空気・空の光 {@code mushroomMaxSkyLight} 以下(葉の下の暗がり)・
 * まわり {@code mushroomCrowdRadius} に {@code mushroomCrowdMax} 本未満なら 1 本置く。天井の無い次元だけ(ネザーは高さ地図が天井の上を答えるため、今は生えない)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE)
public final class SporeEaterSpread {

    private SporeEaterSpread() {
    }

    private static ExpeditionSpec spec() {
        return DesignData.sporeField().expedition();
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        // 天井のある次元(ネザー)は高さ地図が天井の上を答えるので、ここでは生やさない(洞窟の床を探す作りは後で)
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimensionType().hasCeiling()) return;
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
            // 11-18 軽量化 ④: 読み込み途中のチャンクを見ない(hasChunkAt は読み込み途中でも真。着いた直後に 2 秒止まっていた)。混み具合を数える分(mushroomCrowdRadius)+ 1 ブロック
            if (!com.yayuy.overgrownvegetation.perf.ChunkEdgeGuard.areaLoaded(level, new BlockPos(x, 0, z), s.mushroomCrowdRadius() + 1)) continue;
            BlockPos pos = findFloor(level, x, z);
            if (pos != null && tryPlace(level, pos)) placed++;
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
        if (!roomFor(level.getBlockState(pos))) return false;
        if (checkBiome && !ModBiomes.isFullyOvergrown(level.getBiome(pos))) return false;
        if (level.getBrightness(LightLayer.SKY, pos) > s.mushroomMaxSkyLight()) return false;
        BlockState mushroom = ModExpedition.SPORE_EATER_MUSHROOM.get().defaultBlockState();
        if (!mushroom.canSurvive(level, pos)) return false;
        return !crowded(level, pos, s);
    }

    /** 林冠の下をたどる深さ(高さ地図の所から下へ)。 */
    private static final int FLOOR_SCAN = 48;

    /**
     * 列 (x, z) の林床で茸が入れそうなマス(上の面がしっかりしたブロックの上の空気・下草で、空の光が上限以下)を、高さ地図の所から下へ探す。
     * 過繁茂の森の林冠はシダの葉・蔓・葉の縁など「葉」でない物が多く、高さ地図はその上を答えるので、上だけ見ると明るい林冠の上しか選ばない
     * (11-16 にクライアントで 0 本だった原因)。葉の上には置かない。無ければ null。
     */
    static BlockPos findFloor(ServerLevel level, int x, int z) {
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        int maxLight = spec().mushroomMaxSkyLight();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, top, z);
        for (int y = top; y > Math.max(level.getMinBuildHeight(), top - FLOOR_SCAN); y--) {
            p.setY(y);
            BlockState here = level.getBlockState(p);
            if (!roomFor(here)) continue;
            BlockPos below = p.below();
            BlockState under = level.getBlockState(below);
            if (under.is(net.minecraft.tags.BlockTags.LEAVES) || !under.isFaceSturdy(level, below, net.minecraft.core.Direction.UP)) continue;
            if (level.getBrightness(LightLayer.SKY, p) > maxLight) continue;
            return p.immutable();
        }
        return null;
    }

    /** まわり {@code r} の列を全部見て、決まりのどこで落ちたかを数える(開発用 {@code /ov perf spore mushroom})。 */
    public static String diagnose(ServerLevel level, BlockPos center, int r) {
        ExpeditionSpec s = spec();
        java.util.Map<String, Integer> tally = new java.util.TreeMap<>();
        java.util.Map<String, Integer> blockers = new java.util.TreeMap<>();
        int ok = 0;
        for (int x = center.getX() - r; x <= center.getX() + r; x++) {
            for (int z = center.getZ() - r; z <= center.getZ() + r; z++) {
                if (!com.yayuy.overgrownvegetation.perf.ChunkEdgeGuard.hasChunkAt(level, new BlockPos(x, 0, z))) { tally.merge("読み込まれていない", 1, Integer::sum); continue; }
                BlockPos found = findFloor(level, x, z);
                BlockPos pos = found != null ? found : new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
                BlockState here = level.getBlockState(pos);
                String why;
                if (!roomFor(here)) {
                    why = "マスが埋まっている";
                    blockers.merge(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(here.getBlock()).getPath(), 1, Integer::sum);
                } else if (!ModBiomes.isFullyOvergrown(level.getBiome(pos))) why = "過繁茂でない";
                else if (level.getBrightness(LightLayer.SKY, pos) > s.mushroomMaxSkyLight()) why = "明るい";
                else if (!ModExpedition.SPORE_EATER_MUSHROOM.get().defaultBlockState().canSurvive(level, pos)) why = "下が立たない";
                else if (crowded(level, pos, s)) why = "混んでいる";
                else { why = "生えてよい"; ok++; }
                tally.merge(why, 1, Integer::sum);
            }
        }
        java.util.List<java.util.Map.Entry<String, Integer>> top = new java.util.ArrayList<>(blockers.entrySet());
        top.sort((a, b) -> b.getValue() - a.getValue());
        return "茸: 生えてよい列 " + ok + " / " + tally + " 埋めている物 " + top.subList(0, Math.min(6, top.size()));
    }

    /**
     * そのマスに茸が入れるか: 空気か、置き換えてよい 1 マスの下草(過繁茂の林床は 9 割以上が下草で埋まっているので、空気だけだとほとんど生えない。
     * 11-16 にクライアントで確かめた)。2 マスの草・水・茸そのものは置き換えない。
     */
    private static boolean roomFor(BlockState here) {
        if (here.isAir()) return true;
        return here.canBeReplaced()
                && here.getFluidState().isEmpty()
                && !(here.getBlock() instanceof net.minecraft.world.level.block.DoublePlantBlock)
                && !here.is(ModExpedition.SPORE_EATER_MUSHROOM.get());
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
