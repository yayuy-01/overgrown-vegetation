package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.ClimaxDeepConverter;
import com.yayuy.overgrownvegetation.corruption.ClimaxOrePlacer;
import com.yayuy.overgrownvegetation.corruption.EnergyCrystalPlacer;
import com.yayuy.overgrownvegetation.corruption.LeyVeinField;
import com.yayuy.overgrownvegetation.corruption.OvergrownBiomeConverter;
import com.yayuy.overgrownvegetation.corruption.OvergrowthRegionMap;
import com.yayuy.overgrownvegetation.data.ClimaxTable;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.OvergrowthVariant;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.function.Predicate;

/**
 * 結晶地帯と極相の地下の鉱石(順 1、2026-10-03)のテスト。
 *
 * <p>前半 3 本は<b>計算だけ</b>(地脈のチャンク判定・結晶地帯のくじ・鉱石の高さの分布)。
 * 後半 2 本は<b>遠くの専用チャンク</b>で、極相化 → 全層置換 → 鉱石と結晶 までを本物の経路で通す
 * (チャンク丸ごと書き換えるので、テスト場の中ではやらない —— {@link ClimaxDeepGameTests} と同じ理由)。
 * 遠いチャンクは実行をまたいで残るので、毎回はじめに石の板を敷き直す。
 *
 * <p>GameTest の世界はシード 0 なので、選ばれるチャンクは毎回同じ(決定的)。
 *
 * <p>後半 2 本は石の板を毎回 2 万マスほど敷き直すので、<b>テスト全体の時間のかなりの部分を占める</b>
 * (2026-10-03 で全 393 本 35 秒のうち)。全体が遅くなったら、まずここを疑う。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class CrystalBiomeGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(CrystalBiomeGameTests.class);
        }

        private Registration() {
        }
    }

    private static final String TEMPLATE = "gametest_empty7";
    private static final String BATCH = "crystalBiome";

    /** 石の板の範囲。鉱石の高さ(y −30〜40)を丸ごと含む。y 0 より下は深層岩。 */
    private static final int SLAB_BOTTOM = -32;
    private static final int SLAB_TOP = 40;
    /** 前の実行の結晶を消すために空気へ戻す上限。 */
    private static final int CLEAR_TOP = 100;

    /**
     * {@link LeyVeinField#hasVeinInChunk} は 2 ブロックおきにしか見ないが、地脈が通っているチャンクを
     * <b>取りこぼさない</b>(全 256 列を見たときと食い違うのは、角を 3 列以下かすめるだけのチャンクに限る。
     * 縁の 1 列だけをかすめる地脈を見落としていたのを、このテストが見つけた)。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 100, batch = BATCH)
    public static void hasVeinInChunkMatchesFullScan(GameTestHelper helper) {
        long seed = helper.getLevel().getSeed();
        int withVein = 0;
        int without = 0;
        for (int cz = 0; cz < 40; cz++) {
            for (int cx = 0; cx < 40; cx++) {
                int chunkX = 20_000 + cx;
                int chunkZ = 20_000 + cz;
                int columns = 0;
                for (int dz = 0; dz < 16; dz++) {
                    for (int dx = 0; dx < 16; dx++) {
                        if (LeyVeinField.isVeinAt(seed, (chunkX << 4) + dx, (chunkZ << 4) + dz)) {
                            columns++;
                        }
                    }
                }
                boolean quick = LeyVeinField.hasVeinInChunk(seed, chunkX, chunkZ);
                if (quick && columns == 0) {
                    helper.fail("地脈が 1 列も無いのに通っていると答えた: chunk " + chunkX + "," + chunkZ);
                    return;
                }
                if (!quick && columns > 3) {
                    helper.fail("地脈が " + columns + " 列あるのに見落とした: chunk " + chunkX + "," + chunkZ);
                    return;
                }
                if (quick) {
                    withVein++;
                } else {
                    without++;
                }
            }
        }
        helper.assertTrue(withVein > 0 && without > 0,
                "地脈のある/無いチャンクの片方が 0(場が動いていない?): ある " + withVein + " / 無い " + without);
        helper.succeed();
    }

    /**
     * 結晶地帯は<b>密林の地域からだけ</b>選ばれ、その割合はおおよそ {@code crystal.regionChance}(既定 0.25)。
     * 環境のくじとは別の乱数なので、結晶地帯を足しても密林・湿地・菌湿地の割り当ては変わらない
     * (ここでは「結晶地帯なら必ず密林」を見る)。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 100, batch = BATCH)
    public static void crystalRegionsAreJungleOnlyAndRare(GameTestHelper helper) {
        if (com.yayuy.overgrownvegetation.corruption.SealedBiomes.swampAndCrystalSealed()) {
            helper.succeed(); // 結晶地帯は 0.2.0 まで封印(SealedBiomes)。封印を外したらまた走る
            return;
        }
        long seed = helper.getLevel().getSeed();
        double chance = DesignData.climax().crystal().regionChance();
        int jungle = 0;
        int crystal = 0;
        for (int i = 0; i < 64; i++) {
            for (int j = 0; j < 64; j++) {
                int x = 1_000_000 + i * 700;
                int z = 1_000_000 + j * 700;
                boolean isCrystal = OvergrowthRegionMap.isCrystalRegion(seed, x, z);
                OvergrowthVariant variant = OvergrowthRegionMap.variantAt(seed, x, z);
                if (isCrystal && variant != OvergrowthVariant.JUNGLE) {
                    helper.fail("密林でない地域(" + variant + ")が結晶地帯になった: " + x + "," + z);
                    return;
                }
                if (variant == OvergrowthVariant.JUNGLE) {
                    jungle++;
                    if (isCrystal) {
                        crystal++;
                    }
                }
            }
        }
        double ratio = jungle == 0 ? 0.0D : crystal / (double) jungle;
        helper.assertTrue(jungle > 0, "密林の地域が 1 つも無い");
        helper.assertTrue(Math.abs(ratio - chance) < 0.12D,
                "密林のうち結晶地帯の割合が " + String.format("%.3f", ratio) + "(設計値 " + chance + ")");
        helper.succeed();
    }

    /** 鉱石の高さは y −30〜40 に収まり、y 15〜20 あたりがいちばん多い(ユーザー指定)。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 100, batch = BATCH)
    public static void oreHeightIsTriangularWithPeakAt15to20(GameTestHelper helper) {
        ClimaxTable.Ore ore = DesignData.climax().phloemiteOre();
        RandomSource random = RandomSource.create(20261003L);
        int[] buckets = new int[(ore.maxY() - ore.minY()) / 5 + 1];
        for (int i = 0; i < 50_000; i++) {
            int y = ClimaxOrePlacer.triangular(random, ore.minY(), ore.maxY(), ore.peakY());
            if (y < ore.minY() || y > ore.maxY()) {
                helper.fail("高さが範囲の外: " + y);
                return;
            }
            buckets[(y - ore.minY()) / 5]++;
        }
        int best = 0;
        for (int i = 1; i < buckets.length; i++) {
            if (buckets[i] > buckets[best]) {
                best = i;
            }
        }
        int from = ore.minY() + best * 5;
        helper.assertTrue(from >= 10 && from <= 20,
                "いちばん多い高さの帯が y " + from + "〜" + (from + 4) + "(15〜20 あたりのはず)");
        helper.succeed();
    }

    /**
     * 結晶地帯の地域にある、地脈の通るチャンクを極相にすると:
     * バイオームが結晶地帯になり、全層置換を終えたところで<b>地下にチラコイトとフロエマイト</b>、
     * <b>地脈の上にエネルギー結晶</b>ができる。鉱石は y 0 を境に石の版と深層岩の版に分かれる。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 600, batch = BATCH)
    public static void crystalChunkGrowsCrystalsAndThylakite(GameTestHelper helper) {
        if (com.yayuy.overgrownvegetation.corruption.SealedBiomes.swampAndCrystalSealed()) {
            helper.succeed(); // 結晶地帯は 0.2.0 まで封印(SealedBiomes)。封印を外したらまた走る
            return;
        }
        ServerLevel level = helper.getLevel();
        long seed = level.getSeed();
        ClimaxTable.Crystal spec = DesignData.climax().crystal();
        ChunkPos pos = findChunk(41_000, 41_000, p -> OvergrowthRegionMap.isCrystalRegion(seed, center(p), centerZ(p))
                && !EnergyCrystalPlacer.spireSites(seed, p, spec).isEmpty());
        if (pos == null) {
            helper.fail("結晶を立てる候補のある結晶地帯のチャンクが見つからない(くじ・地脈の設計値を確かめる)");
            return;
        }
        prepareSlab(level, pos);
        OvergrownBiomeConverter.forceClimax(level, pos);
        Holder<Biome> biome = level.getBiome(pos.getMiddleBlockPosition(level.getMinBuildHeight()));
        if (!biome.is(ModBiomes.ENGULFED_CRYSTAL)) {
            helper.fail("結晶地帯の地域なのに極相の結晶地帯にならなかった: " + biome.unwrapKey().orElse(null));
            return;
        }
        ClimaxDeepConverter.convertNow(level, pos);

        Counts counts = count(level, pos);
        if (counts.misplaced != null) {
            helper.fail(counts.misplaced);
            return;
        }
        helper.assertTrue(counts.thylakite > 0, "結晶地帯の地下にチラコイトが無い");
        helper.assertTrue(counts.phloemite > 0, "地脈の通るチャンクの地下にフロエマイトが無い");
        helper.assertTrue(counts.crystal > 0, "地脈の上にエネルギー結晶が 1 つも立っていない");
        // 1 本目の根元: 板の上面(地表)が結晶に置き換わっている = 地面から生えていて浮いていない。
        EnergyCrystalPlacer.Site site = EnergyCrystalPlacer.spireSites(seed, pos, spec).get(0);
        BlockState root = level.getBlockState(new BlockPos(site.x(), SLAB_TOP, site.z()));
        helper.assertTrue(root.is(ModBlocks.ENERGY_CRYSTAL.get()),
                "結晶の根元の地表が結晶になっていない(浮いている?): " + root + " at " + site.x() + "," + site.z());
        helper.succeed();
    }

    /**
     * 結晶地帯で<b>ない</b>密林の地域の、地脈の通るチャンクを極相にすると:
     * バイオームは普通の極相のまま、フロエマイトはできるが<b>チラコイトと結晶はできない</b>。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 600, batch = BATCH)
    public static void plainJungleClimaxGetsPhloemiteOnly(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        long seed = level.getSeed();
        ChunkPos pos = findChunk(41_000, 41_100, p -> OvergrowthRegionMap.variantAt(seed, center(p), centerZ(p)) == OvergrowthVariant.JUNGLE
                && !OvergrowthRegionMap.isCrystalRegion(seed, center(p), centerZ(p))
                && LeyVeinField.hasVeinInChunk(seed, p.x, p.z));
        if (pos == null) {
            helper.fail("地脈の通る普通の密林のチャンクが見つからない");
            return;
        }
        prepareSlab(level, pos);
        OvergrownBiomeConverter.forceClimax(level, pos);
        Holder<Biome> biome = level.getBiome(pos.getMiddleBlockPosition(level.getMinBuildHeight()));
        if (!biome.is(ModBiomes.ENGULFED)) {
            helper.fail("普通の密林の地域が極相の密林にならなかった: " + biome.unwrapKey().orElse(null));
            return;
        }
        ClimaxDeepConverter.convertNow(level, pos);

        Counts counts = count(level, pos);
        if (counts.misplaced != null) {
            helper.fail(counts.misplaced);
            return;
        }
        helper.assertTrue(counts.phloemite > 0, "地脈の通るチャンクの地下にフロエマイトが無い");
        helper.assertTrue(counts.thylakite == 0, "結晶地帯でないのにチラコイトが " + counts.thylakite + " マス");
        helper.assertTrue(counts.crystal == 0, "結晶地帯でないのにエネルギー結晶が " + counts.crystal + " マス");
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------------

    private static int center(ChunkPos p) {
        return (p.x << 4) + 8;
    }

    private static int centerZ(ChunkPos p) {
        return (p.z << 4) + 8;
    }

    /** {@code (startX, startZ)} から x 方向へ 3 チャンクおきに探し、最初に条件を満たしたチャンク。チャンクは読まない。 */
    private static ChunkPos findChunk(int startX, int startZ, Predicate<ChunkPos> want) {
        for (int i = 0; i < 4000; i++) {
            ChunkPos p = new ChunkPos(startX + i * 3, startZ);
            if (want.test(p)) {
                return p;
            }
        }
        return null;
    }

    /**
     * そのチャンク(と、結晶が傾いてはみ出せるように周り 8 チャンク)を読み込み、y −32〜40 を石の板にする
     * (y 0 より下は深層岩)。上は空気へ戻す —— 前の実行の結晶・鉱石・苔を消してから始める。
     */
    private static void prepareSlab(ServerLevel level, ChunkPos pos) {
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                level.getChunk(pos.x + dx, pos.z + dz);
            }
        }
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState deepslate = Blocks.DEEPSLATE.defaultBlockState();
        for (int y = SLAB_BOTTOM; y <= CLEAR_TOP; y++) {
            BlockState state = y > SLAB_TOP ? air : y < 0 ? deepslate : stone;
            for (int dz = 0; dz < 16; dz++) {
                for (int dx = 0; dx < 16; dx++) {
                    cursor.set(pos.getMinBlockX() + dx, y, pos.getMinBlockZ() + dz);
                    level.setBlock(cursor, state, Block.UPDATE_CLIENTS);
                }
            }
        }
    }

    private static final class Counts {
        int phloemite;
        int thylakite;
        int crystal;
        String misplaced;
    }

    /** チャンクの中の鉱石と結晶を数える。石の版が y 0 より下 / 深層岩の版が y 0 以上にあれば {@code misplaced}。 */
    private static Counts count(ServerLevel level, ChunkPos pos) {
        Counts counts = new Counts();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = SLAB_BOTTOM; y <= CLEAR_TOP; y++) {
            for (int dz = 0; dz < 16; dz++) {
                for (int dx = 0; dx < 16; dx++) {
                    cursor.set(pos.getMinBlockX() + dx, y, pos.getMinBlockZ() + dz);
                    BlockState state = level.getBlockState(cursor);
                    boolean stoneOre = state.is(ModBlocks.PHLOEMITE_ORE.get()) || state.is(ModBlocks.THYLAKITE_ORE.get());
                    boolean deepOre = state.is(ModBlocks.DEEPSLATE_PHLOEMITE_ORE.get())
                            || state.is(ModBlocks.DEEPSLATE_THYLAKITE_ORE.get());
                    if (stoneOre && y < 0 && counts.misplaced == null) {
                        counts.misplaced = "石の版の鉱石が深層岩の高さにある: y=" + y + " " + state;
                    }
                    if (deepOre && y >= 0 && counts.misplaced == null) {
                        counts.misplaced = "深層岩の版の鉱石が石の高さにある: y=" + y + " " + state;
                    }
                    if (state.is(ModBlocks.PHLOEMITE_ORE.get()) || state.is(ModBlocks.DEEPSLATE_PHLOEMITE_ORE.get())) {
                        counts.phloemite++;
                    } else if (state.is(ModBlocks.THYLAKITE_ORE.get()) || state.is(ModBlocks.DEEPSLATE_THYLAKITE_ORE.get())) {
                        counts.thylakite++;
                    } else if (state.is(ModBlocks.ENERGY_CRYSTAL.get())) {
                        counts.crystal++;
                    }
                }
            }
        }
        return counts;
    }

    private CrystalBiomeGameTests() {
    }
}
