package com.yayuy.overgrownvegetation.gear;

import com.mojang.serialization.Codec;
import com.yayuy.overgrownvegetation.corruption.LeyLines;
import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.tags.BlockTags;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>導晶鉱</b>のでき方(2026-11-21 ユーザー決定「地脈の線の近くの地下に最初からある」)。
 * <ul>
 *   <li>世界生成: {@link Placer} が地下の鉱石の段(underground_ores)で、原点のチャンクの中に鉱脈の芯を {@code veinsPerChunk} 回選び、
 *   芯が地脈の線から {@code leyReach} 以内のときだけ、まわりの石・深層岩を {@code veinSize} 個まで導晶鉱にする(チャンクの外へは出さない)。</li>
 *   <li>汚染: 飲み込まれても変質しない(菌集鉱にも置き換えない。ユーザー「菌集鉱を採るのに導晶鉱を残す必要が出てしまう」)。
 *   緑の版へ変わるのは {@code CorruptionRegistry} の個別の表。置き換わったその 1 回({@link #onConverted})だけ、空気に面した所に小さな根晶が生える。</li>
 * </ul>
 * 値は {@code gear_tiers.json} の {@code conduit}【仮】。
 */
public final class ConduitOre {

    private ConduitOre() {
    }

    private static GearTierTable.Conduit c() {
        return DesignData.gearTiers().conduit;
    }

    /** 芯 (x, z) が地脈の線の近くか(世界の種だけで決まる)。 */
    public static boolean nearLey(long seed, int x, int z) {
        return LeyLines.distanceToLine(seed, x, z) <= c().leyReach;
    }

    /** 置き換える岩なら、それが変わる導晶鉱。そうでなければ null。 */
    public static Block oreFor(BlockState state) {
        if (state.is(BlockTags.STONE_ORE_REPLACEABLES)) return ModGear.CONDUIT_ORE.get();
        if (state.is(BlockTags.DEEPSLATE_ORE_REPLACEABLES)) return ModGear.DEEPSLATE_CONDUIT_ORE.get();
        return null;
    }

    /** {@code core} から鉱脈を置く(地脈は見ない。GameTest からも)。{@code chunkX/Z} のチャンクの外へは出さない。置いた数を返す。 */
    public static int vein(LevelAccessor level, BlockPos core, RandomSource random, int size, int chunkX, int chunkZ) {
        int placed = 0;
        BlockPos at = core;
        for (int i = 0; i < size * 4 && placed < size; i++) {
            if ((at.getX() >> 4) == chunkX && (at.getZ() >> 4) == chunkZ) {
                Block ore = oreFor(level.getBlockState(at));
                if (ore != null && level.setBlock(at, ore.defaultBlockState(), 2)) placed++;
            }
            at = at.relative(Direction.getRandom(random));
            if (at.distManhattan(core) > 3) at = core;
        }
        return placed;
    }

    /** 世界生成で導晶鉱を置く。 */
    public static final class Placer extends Feature<NoneFeatureConfiguration> {
        public Placer(Codec<NoneFeatureConfiguration> codec) {
            super(codec);
        }

        @Override
        public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
            WorldGenLevel level = context.level();
            RandomSource random = context.random();
            BlockPos origin = context.origin();
            int chunkX = origin.getX() >> 4;
            int chunkZ = origin.getZ() >> 4;
            GearTierTable.Conduit c = c();
            int lo = Math.max(c.minY, level.getMinBuildHeight());
            int hi = Math.min(c.maxY, level.getMaxBuildHeight() - 1);
            if (hi < lo) return false;
            long seed = level.getSeed();
            boolean any = false;
            for (int i = 0; i < c.veinsPerChunk; i++) {
                int x = (chunkX << 4) + random.nextInt(16);
                int z = (chunkZ << 4) + random.nextInt(16);
                int y = lo + random.nextInt(hi - lo + 1);
                if (!nearLey(seed, x, z)) continue;
                any |= vein(level, new BlockPos(x, y, z), random, Math.max(1, c.veinSize), chunkX, chunkZ) > 0;
            }
            return any;
        }
    }

    /** 飲み込まれた導晶鉱か。 */
    public static boolean isOvergrownOre(BlockState state) {
        return state.is(ModGear.OVERGROWN_CONDUIT_ORE.get()) || state.is(ModGear.OVERGROWN_DEEPSLATE_CONDUIT_ORE.get());
    }

    /**
     * 汚染でブロックが置き換わったとき({@code CorruptionRegistry#placeCorrupted})。導晶鉱が緑の版になったその 1 回だけ、空気に面した面に小さな根晶を生やす。
     * 装飾の口(フェーズの前触れ・菌糸の染み下がり・骨粉など何度も来る)では生やさないので、掘った小さな根晶は生え直さない(根晶の本筋は器官のまま)。
     * 生やした数を返す。
     */
    public static int onConverted(ServerLevel level, BlockPos pos, BlockState previous, BlockState now) {
        if (!(previous.is(ModGear.CONDUIT_ORE.get()) || previous.is(ModGear.DEEPSLATE_CONDUIT_ORE.get())) || !isOvergrownOre(now)) return 0;
        return growBuds(level, pos, level.random);
    }

    /** {@code pos} の飲み込まれた導晶鉱のまわりに小さな根晶を生やす(確率と上限は設計値。GameTest からも)。 */
    public static int growBuds(LevelAccessor level, BlockPos pos, RandomSource random) {
        GearTierTable.Conduit c = c();
        int have = 0;
        List<Direction> open = new ArrayList<>();
        for (Direction d : Direction.values()) {
            BlockPos n = pos.relative(d);
            BlockState s = level.getBlockState(n);
            if (s.is(ModGear.SMALL_ROOT_CRYSTAL.get()) && s.getValue(AmethystClusterBlock.FACING) == d) have++;
            else if (s.isAir() || s.is(Blocks.CAVE_AIR)) open.add(d);
        }
        int placed = 0;
        java.util.Collections.shuffle(open, new java.util.Random(random.nextLong()));
        for (Direction d : open) {
            if (have + placed >= c.budMax) break;
            if (random.nextDouble() >= c.budChance) continue;
            BlockState bud = ModGear.SMALL_ROOT_CRYSTAL.get().defaultBlockState().setValue(AmethystClusterBlock.FACING, d);
            if (level.setBlock(pos.relative(d), bud, 3)) placed++;
        }
        return placed;
    }
}
