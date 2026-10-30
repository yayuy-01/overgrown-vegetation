package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.base.build.BaseChunkJobs;
import com.yayuy.overgrownvegetation.base.build.BaseCommand;
import com.yayuy.overgrownvegetation.base.build.BaseEconomy;
import com.yayuy.overgrownvegetation.base.build.BaseGarrison;
import com.yayuy.overgrownvegetation.base.build.BasePlacer;
import com.yayuy.overgrownvegetation.base.build.BasePurifiers;
import com.yayuy.overgrownvegetation.base.build.BaseSite;
import com.yayuy.overgrownvegetation.base.build.BaseSites;
import com.yayuy.overgrownvegetation.base.gen.AlliedBasePiece;
import com.yayuy.overgrownvegetation.base.gen.AlliedBaseStructure;
import com.yayuy.overgrownvegetation.base.gen.BaseStructures;
import com.yayuy.overgrownvegetation.base.gen.BaseWorldgen;
import com.yayuy.overgrownvegetation.base.plan.BasePlan;
import com.yayuy.overgrownvegetation.base.plan.BasePlanner;
import com.yayuy.overgrownvegetation.base.plan.BaseTerrain;
import com.yayuy.overgrownvegetation.base.plan.BaseType;
import com.yayuy.overgrownvegetation.data.DefenseTable;
import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 世界生成の同盟軍の基地(順 2 の着手順 2、2026-10-04)。構造物のデータ・部品の保存・部品の書き出しが今の置き方と同じこと・台帳への載せ方と
 * 「耐えられなければ廃墟」(ユーザー指定 10-04)・読み込まれたチャンクだけの手入れ・種類とレベルの引き方・中心の判定。
 *
 * <p>遠い座標は毎回同じ場所(テスト場の位置は実行ごとに変わる。ずらすとワールドにチャンクが溜まる)。ほかの基地の試験(25600, 25600)・
 * (−25600, 25600)と重ねない。台帳に載せた基地は、手入れの試験のほかは同じ tick のうちに外す。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class BaseWorldgenGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(BaseWorldgenGameTests.class);
        }

        private Registration() {
        }
    }

    /** 部品の書き出しを比べるチャンクの真ん中(25600, −25600 のチャンク)。 */
    private static final BlockPos EMIT_AT = new BlockPos(25608, 0, -25592);
    /** 台帳に載せる試験の中心(ブロックは置かない)。 */
    private static final BlockPos REGISTER_AT = new BlockPos(-25592, 64, -25592);
    private static final BlockPos OVERRUN_AT = new BlockPos(-25592 + 512, 64, -25592);
    /** 読み込まれたチャンクだけの手入れの試験の中心。 */
    private static final BlockPos JOBS_AT = new BlockPos(-21496, 64, -25592);

    private static final BaseTerrain FLAT = BaseTerrain.sample((x, z) -> 64);

    /** 構造物・構造物の組・バイオームのタグがデータから読める(海・川・砂浜・山・吹きさらしの丘には生まれない —— ユーザー指定「最初は海と山以外」)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void structureDataLoads(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Structure s = level.registryAccess().registryOrThrow(Registries.STRUCTURE).get(BaseStructures.ALLIED_BASE_STRUCTURE);
        helper.assertTrue(s instanceof AlliedBaseStructure, "構造物 allied_base が無い: " + s);
        helper.assertTrue(s.step() == GenerationStep.Decoration.TOP_LAYER_MODIFICATION, "生成の段が最後でない(木の後に建てる): " + s.step());
        Registry<Biome> biomes = level.registryAccess().registryOrThrow(Registries.BIOME);
        for (var yes : List.of(Biomes.PLAINS, Biomes.FOREST, Biomes.DESERT, Biomes.TAIGA, Biomes.SAVANNA, Biomes.JUNGLE)) {
            helper.assertTrue(s.biomes().contains(biomes.getHolderOrThrow(yes)), yes.location() + " に生まれない");
        }
        for (var no : List.of(Biomes.OCEAN, Biomes.DEEP_OCEAN, Biomes.RIVER, Biomes.BEACH, Biomes.MEADOW, Biomes.JAGGED_PEAKS,
                Biomes.SNOWY_SLOPES, Biomes.GROVE, Biomes.WINDSWEPT_HILLS, Biomes.STONY_SHORE)) {
            helper.assertTrue(!s.biomes().contains(biomes.getHolderOrThrow(no)), no.location() + " に生まれる");
        }
        StructureSet set = level.registryAccess().registryOrThrow(Registries.STRUCTURE_SET).get(BaseStructures.ALLIED_BASE_SET);
        helper.assertTrue(set != null && set.placement() instanceof RandomSpreadStructurePlacement, "構造物の組 allied_bases が無い");
        RandomSpreadStructurePlacement p = (RandomSpreadStructurePlacement) set.placement();
        // 重なりは離れではなく「隣の候補に譲る」判定で防ぐ(crowdedCandidatesNeverOverlap)。届きの上限は計画の最大(GRID)以下
        helper.assertTrue(p.spacing() > p.separation() && p.separation() >= 1, "間隔・離れがおかしい: " + p.spacing() + " / " + p.separation());
        helper.assertTrue(AlliedBaseStructure.reach(5) <= BasePlan.GRID, "届きの上限が計画の最大を超える");
        helper.succeed();
    }

    /**
     * 隣の候補に譲る判定: 間隔 10・離れ 2 で詰めて並べても、生まれた基地どうしは重ならない(並びの番号の小さい方が先に決まる)。
     * 平らな世界なので村の近く以外の候補はほとんど単独なら生まれ、譲り合いが多く起きる。チャンクは作らない(地形のノイズとバイオームだけ)。
     * もっと詰める(間隔 6)と、ほぼ全部の候補が「先の候補が単独なら生まれる」に当たって譲り、2 か所しか生まれない(重ならない方を取る判定なので仕様どおり。
     * 実際の地形では単独で生まれる候補が 1 割に満たないので、譲るのは候補の 1% 未満)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 100)
    public static void crowdedCandidatesNeverOverlap(GameTestHelper helper) {
        int[] r = com.yayuy.overgrownvegetation.base.gen.BaseSurvey.crowding(helper.getLevel(), new ChunkPos(-3000, 2000), 80, 10, 2);
        helper.assertTrue(r != null, "構造物 allied_base が無い");
        helper.assertTrue(r[0] > 5, "ほとんど生まれない(" + r[0] + ")—— 試験の前提が崩れた");
        helper.assertTrue(r[1] > 0, "ぎっしり並べても譲り合いが起きない");
        helper.assertTrue(r[2] == 0, "生まれた基地どうしが " + r[2] + " 組重なった(生まれた " + r[0] + "・譲った " + r[1] + ")");
        helper.succeed();
    }

    /** 部品は保存して読み直しても同じ中身で、箱は開始のチャンクから ±8 チャンクに収まる(外のチャンクは書き出されない)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void pieceSavesAndLoads(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos center = new BlockPos(8 + 16 * 3, 70, 8 - 16 * 5);
        AlliedBasePiece piece = new AlliedBasePiece(center, 3, 123456789L, 4, BaseType.RESEARCH, BaseSite.VERSION,
                FLAT.samples(), FLAT.surfaceSamples(), level);
        CompoundTag tag = piece.createTag(StructurePieceSerializationContext.fromLevel(level));
        AlliedBasePiece copy = new AlliedBasePiece(tag);
        helper.assertTrue(copy.center().equals(center) && copy.rotation() == 3 && copy.seed() == 123456789L && copy.level() == 4
                && copy.baseType() == BaseType.RESEARCH && copy.version() == BaseSite.VERSION, "部品の中身が保存を通らない");
        helper.assertTrue(java.util.Arrays.equals(copy.terrain().samples(), FLAT.samples())
                && java.util.Arrays.equals(copy.terrain().surfaceSamples(), FLAT.surfaceSamples()), "地形の標本が保存を通らない");
        helper.assertTrue(copy.getBoundingBox().equals(piece.getBoundingBox()), "箱が保存を通らない");
        ChunkPos start = new ChunkPos(center);
        var box = piece.getBoundingBox();
        helper.assertTrue(box.minX() >= (start.x - 8) * 16 && box.maxX() <= (start.x + 8) * 16 + 15
                && box.minZ() >= (start.z - 8) * 16 && box.maxZ() <= (start.z + 8) * 16 + 15, "箱が開始のチャンクから ±8 チャンクを出る: " + box);
        helper.succeed();
    }

    /**
     * 世界生成の書き出し({@link BasePlacer#placeGenerated})は、今の置き方({@link BasePlacer#placeChunk})と同じブロックを置く
     * (中心のチャンクを平らに戻してから 2 回書き出して比べる)。胞子除去装置は置かれない。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 100)
    public static void generatedPieceWritesLikePlacer(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        if (!(level.getChunkSource().getGenerator() instanceof FlatLevelSource flat)) {
            helper.fail("GameTest の世界が平らでない(前提が崩れた)");
            return;
        }
        ChunkPos chunk = new ChunkPos(EMIT_AT);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                level.getChunk(chunk.x + dx, chunk.z + dz);
            }
        }
        List<BlockState> layers = flat.settings().getLayers();
        int top = level.getMinBuildHeight() + layers.size(); // 平らな地面の一つ上
        BlockPos center = new BlockPos(EMIT_AT.getX(), top, EMIT_AT.getZ());
        BaseTerrain terrain = BaseCommand.sampleNoise(level, center, 1);
        AlliedBasePiece piece = new AlliedBasePiece(center, 1, 99L, 1, BaseType.DEFENSE, BaseSite.VERSION,
                terrain.samples(), terrain.surfaceSamples(), level);
        BaseSite em = piece.emission(Level.OVERWORLD);
        int y1 = top + 48;
        flatten(level, chunk, layers, y1);
        BasePlacer.placeChunk(level, em, em.level(), chunk, (x, z) -> true, p -> true);
        Map<BlockPos, BlockState> runtime = snapshot(level, chunk, y1);
        flatten(level, chunk, layers, y1);
        int placed = BasePlacer.placeGenerated(level, em, chunk);
        Map<BlockPos, BlockState> generated = snapshot(level, chunk, y1);
        int diff = 0;
        BlockPos example = null;
        for (Map.Entry<BlockPos, BlockState> e : runtime.entrySet()) {
            if (!generated.get(e.getKey()).equals(e.getValue())) {
                diff++;
                if (example == null) {
                    example = e.getKey();
                }
            }
        }
        int purifiers = 0;
        for (BlockState s : generated.values()) {
            if (s.is(com.yayuy.overgrownvegetation.registry.ModBlocks.SPORE_PURIFIER.get())) {
                purifiers++;
            }
        }
        BlockPos ex = example;
        helper.assertTrue(placed > 0, "世界生成の書き出しが何も置かない");
        helper.assertTrue(diff == 0, "世界生成の書き出しが今の置き方と " + diff + " マス違う(例 " + ex + ": "
                + (ex == null ? "" : runtime.get(ex) + " / " + generated.get(ex)) + ")");
        helper.assertTrue(purifiers == 0, "世界生成で胞子除去装置が置かれた: " + purifiers);
        helper.succeed();
    }

    /** チャンクの [地面の下, y1] を平らな世界の層に戻す。 */
    private static void flatten(ServerLevel level, ChunkPos chunk, List<BlockState> layers, int y1) {
        int min = level.getMinBuildHeight();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = chunk.getMinBlockX(); x <= chunk.getMaxBlockX(); x++) {
            for (int z = chunk.getMinBlockZ(); z <= chunk.getMaxBlockZ(); z++) {
                for (int y = min + 1; y <= y1; y++) {
                    int i = y - min;
                    BlockState want = i < layers.size() && layers.get(i) != null ? layers.get(i) : Blocks.AIR.defaultBlockState();
                    p.set(x, y, z);
                    if (!level.getBlockState(p).equals(want)) {
                        BasePlacer.replace(level, p, want);
                    }
                }
            }
        }
    }

    private static Map<BlockPos, BlockState> snapshot(ServerLevel level, ChunkPos chunk, int y1) {
        Map<BlockPos, BlockState> out = new HashMap<>();
        for (int x = chunk.getMinBlockX(); x <= chunk.getMaxBlockX(); x++) {
            for (int z = chunk.getMinBlockZ(); z <= chunk.getMaxBlockZ(); z++) {
                for (int y = level.getMinBuildHeight(); y <= y1; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    out.put(p, level.getBlockState(p));
                }
            }
        }
        return out;
    }

    /**
     * 中心のチャンクが読み込まれた世界生成の基地を台帳に載せる: 世界生成の印・中身が部品のとおり・2 回は載らない・台帳から外した基地は載せ直さない。
     * 周りが全部汚染されていれば、最初から陥落した廃墟として載り、後片付け(設備を空にする)が始まる(ユーザー指定 10-04「耐えられなければ廃墟」)。
     * 同じ tick のうちに外す。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void registersGeneratedBases(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        BaseSites data = BaseSites.get(server);
        boolean garrison = BaseGarrison.enabled;
        BaseGarrison.enabled = false; // 遠い基地に兵士を出さない(数えられず、ワールドに溜まる)
        BaseSite a = null;
        BaseSite b = null;
        try {
            BaseEconomy.ledgerForTests = c -> false;
            BaseEconomy.guardedForTests = c -> false;
            BaseWorldgen.Request req = new BaseWorldgen.Request(Level.OVERWORLD, REGISTER_AT, 2, 77L, 2, BaseType.SUPPLY, BaseSite.VERSION,
                    FLAT.samples(), FLAT.surfaceSamples(), null);
            a = BaseWorldgen.register(server, req);
            helper.assertTrue(a != null, "台帳に載らない");
            helper.assertTrue(a.generated() && a.level() == 2 && a.type == BaseType.SUPPLY && a.rotation == 2 && a.seed == 77L
                    && a.center.equals(REGISTER_AT) && !a.fallen() && !a.building(), "載った基地の中身が違う");
            boolean allowed = BasePurifiers.allowed(server.overworld());
            helper.assertTrue(a.purifiers() == allowed && a.installingPurifiers() == allowed,
                    "胞子除去装置の後付けがフェーズ(" + (allowed ? "2 以上" : "2 より前") + ")と合わない");
            helper.assertTrue(BaseWorldgen.register(server, req) == null, "同じ基地が 2 回載った");
            data.remove(a);
            a = null;
            helper.assertTrue(data.ignoredGenerated(REGISTER_AT) && BaseWorldgen.register(server, req) == null, "外した基地が載せ直された");
            data.unignoreGenerated(REGISTER_AT);

            BaseEconomy.ledgerForTests = c -> true; // 周りが全部汚染
            BaseWorldgen.Request overrun = new BaseWorldgen.Request(Level.OVERWORLD, OVERRUN_AT, 0, 78L, 5, BaseType.DEFENSE, BaseSite.VERSION,
                    FLAT.samples(), FLAT.surfaceSamples(), null);
            b = BaseWorldgen.register(server, overrun);
            helper.assertTrue(b != null && b.fallen(), "全部汚染された土地の基地が廃墟にならない(レベル 5)");
            helper.assertTrue(b.emptyPendingChunks() > 0 && !b.installingPurifiers() && !b.purifiers(), "廃墟の後片付けが始まらない / 除去装置を後付けしようとした");
        } finally {
            BaseEconomy.ledgerForTests = null;
            BaseEconomy.guardedForTests = null;
            BaseGarrison.enabled = garrison;
            if (a != null) {
                data.remove(a);
            }
            if (b != null) {
                data.remove(b);
            }
            data.unignoreGenerated(REGISTER_AT);
            data.unignoreGenerated(OVERRUN_AT);
        }
        helper.succeed();
    }

    /** 「耐えられなければ廃墟」の線は留守中の計算と同じ: 減る量の平均(下限と上限の真ん中 × 割合)が生まれた日の回復量を超えるか。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void overrunFollowsDeclineLine(GameTestHelper helper) {
        DefenseTable t = DesignData.defense();
        double mean = (t.baseDeclineMin() + t.baseDeclineMax()) / 2.0D;
        for (int lv = 1; lv <= 5; lv++) {
            BaseSite site = BaseSite.forEmission(Level.OVERWORLD, BlockPos.ZERO, 0, 1L, lv, FLAT.samples(), FLAT.surfaceSamples(),
                    BaseSite.VERSION, BaseType.DEFENSE);
            double line = BaseEconomy.recovery(lv, 0L, 0.0D) / mean;
            helper.assertTrue(!BaseEconomy.overrun(site, 0.0D), "汚染なしで廃墟(レベル " + lv + ")");
            helper.assertTrue(line >= 1.0D || BaseEconomy.overrun(site, Math.min(1.0D, line + 0.01D)), "線の上で廃墟にならない(レベル " + lv + ")");
            helper.assertTrue(!BaseEconomy.overrun(site, Math.max(0.0D, line - 0.01D)), "線の下で廃墟になった(レベル " + lv + ")");
        }
        helper.succeed();
    }

    /**
     * 除去装置の後付けと陥落の後片付けは、読み込まれていないチャンクを読み込まない(控えに残る)。読み込まれたら片付く。
     * 遠い基地の 3 × 3 チャンクだけを強制的に読み込んで確かめる(終わったら外す)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200)
    public static void chunkJobsOnlyTouchLoadedChunks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        BaseSites data = BaseSites.get(server);
        BaseSite site = data.add(Level.OVERWORLD, JOBS_AT, 0, 4242L, 1, FLAT, false, BaseType.DEFENSE);
        ChunkPos target = new ChunkPos(JOBS_AT);
        Runnable cleanup = () -> {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    level.setChunkForced(target.x + dx, target.z + dz, false);
                }
            }
            data.remove(site);
        };
        try {
            helper.assertTrue(level.getChunkSource().getChunkNow(target.x, target.z) == null, "前提: 試験の遠いチャンクがもう読み込まれている");
            BasePurifiers.begin(server, data, site);
            int total = site.purifyPendingChunks();
            helper.assertTrue(total > 1 && BaseChunkJobs.isPending(site, BaseChunkJobs.Job.PURIFY, target), "後付けの控えが作られない");
        } catch (RuntimeException e) {
            cleanup.run();
            throw e;
        }
        helper.runAfterDelay(5, () -> {
            try {
                helper.assertTrue(BaseChunkJobs.isPending(site, BaseChunkJobs.Job.PURIFY, target)
                        && level.getChunkSource().getChunkNow(target.x, target.z) == null, "読み込まれていないチャンクに後付けが手を出した");
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        level.setChunkForced(target.x + dx, target.z + dz, true);
                    }
                }
            } catch (RuntimeException e) {
                cleanup.run();
                throw e;
            }
            helper.runAfterDelay(20, () -> {
                try {
                    helper.assertTrue(level.getChunkSource().getChunkNow(target.x, target.z) != null, "前提: 強制で読み込めない");
                    helper.assertTrue(!BaseChunkJobs.isPending(site, BaseChunkJobs.Job.PURIFY, target), "読み込まれたチャンクに後付けが入らない");
                    helper.assertTrue(site.installingPurifiers(), "読み込まれていないチャンクの後付けまで済んだことになった");
                    BaseEconomy.fall(server, data, site, BaseEconomy.Reason.COMMAND);
                    helper.assertTrue(!site.installingPurifiers(), "陥落しても後付けの控えが残る");
                    helper.assertTrue(site.emptyPendingChunks() > 1, "陥落の後片付けの控えが作られない");
                } catch (RuntimeException e) {
                    cleanup.run();
                    throw e;
                }
                helper.runAfterDelay(5, () -> {
                    try {
                        helper.assertTrue(!BaseChunkJobs.isPending(site, BaseChunkJobs.Job.EMPTY, target), "読み込まれたチャンクの後片付けが済まない");
                        helper.assertTrue(site.emptyPendingChunks() > 0, "読み込まれていないチャンクの後片付けまで済んだことになった");
                        BaseSite copy = BaseSite.reloaded(site);
                        helper.assertTrue(copy.emptyPendingChunks() == site.emptyPendingChunks(), "後片付けの控えが保存を通らない");
                    } finally {
                        cleanup.run();
                    }
                    helper.succeed();
                });
            });
        });
    }

    /** 種類とレベルの引き方は設計値の重みのとおり(浄化は 0 = 出ない。ユーザー指定 10-04「研究をまれに」)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void rollsFollowWeights(GameTestHelper helper) {
        DefenseTable t = DesignData.defense();
        RandomSource rnd = RandomSource.create(20261004L);
        int n = 20000;
        Map<BaseType, Integer> types = new HashMap<>();
        int[] levels = new int[6];
        for (int i = 0; i < n; i++) {
            types.merge(AlliedBaseStructure.rollType(rnd, t), 1, Integer::sum);
            levels[AlliedBaseStructure.rollLevel(rnd, t)]++;
        }
        int typeTotal = 0;
        for (BaseType bt : BaseType.values()) {
            typeTotal += t.baseWorldgenTypeWeight(bt.id);
        }
        for (BaseType bt : BaseType.values()) {
            double want = t.baseWorldgenTypeWeight(bt.id) / (double) typeTotal;
            double got = types.getOrDefault(bt, 0) / (double) n;
            helper.assertTrue(Math.abs(got - want) < 0.015, bt.label + " の割合 " + got + " が重み " + want + " と違う");
        }
        helper.assertTrue(types.getOrDefault(BaseType.PURIFICATION, 0) == 0 || t.baseWorldgenTypeWeight("purification") > 0, "浄化基地が生まれた");
        int levelTotal = 0;
        for (int lv = 1; lv <= 5; lv++) {
            levelTotal += t.baseWorldgenLevelWeight(lv);
        }
        for (int lv = 1; lv <= 5; lv++) {
            double want = t.baseWorldgenLevelWeight(lv) / (double) levelTotal;
            helper.assertTrue(Math.abs(levels[lv] / (double) n - want) < 0.015, "レベル " + lv + " の割合が重みと違う");
        }
        helper.succeed();
    }

    /** 地形の標本の読み方(柱を 1 回だけ計算する)は、世界生成の高さ地図を 2 回聞いたのと同じ値になる。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void noiseSamplerMatchesHeightmaps(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var gen = level.getChunkSource().getGenerator();
        var rs = level.getChunkSource().randomState();
        BlockPos center = new BlockPos(-25592, 64, -21496);
        BaseTerrain.Sampler s = com.yayuy.overgrownvegetation.base.build.BaseNoise.sampler(gen, rs, level, center, 1);
        for (int lx = -64; lx <= 64; lx += 32) {
            for (int lz = -64; lz <= 64; lz += 32) {
                // 向き 1 = 時計回りに 1/4: 局所 (x, z) → 世界 (−z, x)
                int wx = center.getX() - lz;
                int wz = center.getZ() + lx;
                int ground = gen.getBaseHeight(wx, wz, net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR_WG, level, rs) - 1;
                int surface = gen.getBaseHeight(wx, wz, net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG, level, rs) - 1;
                helper.assertTrue(s.groundY(lx, lz) == ground && s.surfaceY(lx, lz) == surface,
                        "標本 (" + lx + ", " + lz + ") の地面 " + s.groundY(lx, lz) + " / " + ground + "・水面 " + s.surfaceY(lx, lz) + " / " + surface);
            }
        }
        helper.succeed();
    }

    /** 中心の判定: 平らなら置ける、中心の池・すぐ外の水・急な斜面は断る(世界生成の基地は中心の地形を確かめてから生まれる)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void coreCheckRejectsWaterAndSlopes(GameTestHelper helper) {
        int max = DesignData.defense().baseWorldgenCoreMaxRelief();
        helper.assertTrue(BasePlanner.coreCheck(FLAT, max) == BasePlanner.CORE_OK, "平らな中心に置けない");
        BaseTerrain pond = BaseTerrain.sample(new BaseTerrain.Sampler() {
            @Override
            public int groundY(int x, int z) {
                return Math.abs(x) <= 8 && Math.abs(z) <= 8 ? 58 : 64;
            }

            @Override
            public int surfaceY(int x, int z) {
                return Math.abs(x) <= 8 && Math.abs(z) <= 8 ? 62 : 64;
            }
        });
        helper.assertTrue(BasePlanner.coreCheck(pond, max) == BasePlanner.CORE_WATER, "真ん中の池を断らない");
        BaseTerrain slope = BaseTerrain.sample((x, z) -> 64 + x / 2);
        helper.assertTrue(BasePlanner.coreCheck(slope, max) == BasePlanner.CORE_STEEP, "急な斜面を断らない");
        // 中心だけ先に読んだ標本(BaseNoise#sampleCore)でも、全部読んだときと同じ判定になる(でこぼこ・水たまり入りの地形 300 通り)。
        // 粗い水の点から先に見る判定(AlliedBaseStructure#coreVerdict、/locate を軽くするため 10-04)も同じ答えで、水を見る点は coreCheck と同じ点
        RandomSource rnd = RandomSource.create(77L);
        List<int[]> coarse = BasePlanner.coreWaterPoints(2 * BaseTerrain.STEP);
        List<int[]> fine = BasePlanner.coreWaterPoints(BaseTerrain.STEP);
        int coarseHits = 0;
        int fineOnly = 0;
        for (int i = 0; i < 300; i++) {
            long salt = rnd.nextLong();
            int amp = 2 + rnd.nextInt(30);
            boolean wet = rnd.nextInt(4) == 0;
            BaseTerrain.Sampler bumpy = new BaseTerrain.Sampler() {
                private double h(int x, int z) {
                    long k = (x * 73856093L) ^ (z * 19349663L) ^ salt;
                    return 64 + amp * Math.sin(x / 23.0 + (salt & 7)) * Math.cos(z / 31.0) + (Math.floorMod(k, 7) - 3);
                }

                @Override
                public int groundY(int x, int z) {
                    return (int) Math.round(h(x, z));
                }

                @Override
                public int surfaceY(int x, int z) {
                    int g = groundY(x, z);
                    return wet && Math.floorMod(x * 31 + z * 17 + salt, 97) == 0 ? g + 2 : g;
                }
            };
            int full = BasePlanner.coreCheck(BaseTerrain.sample(bumpy), max);
            int part = BasePlanner.coreCheck(com.yayuy.overgrownvegetation.base.build.BaseNoise.sampleCore(bumpy,
                    AlliedBaseStructure.CORE_SAMPLE_RADIUS), max);
            if (full != part) {
                helper.fail("中心だけの標本の判定 " + part + " が全部の標本の判定 " + full + " と違う(振れ幅 " + amp + ")");
                return;
            }
            int staged = AlliedBaseStructure.coreVerdict(bumpy, max);
            if (staged != full) {
                helper.fail("粗い水の点から見る判定 " + staged + " が全部の標本の判定 " + full + " と違う(振れ幅 " + amp + ")");
                return;
            }
            boolean fineWater = BasePlanner.anyWater(bumpy, fine);
            if ((full == BasePlanner.CORE_WATER) != fineWater) {
                helper.fail("水を見る点が coreCheck と違う(coreCheck " + full + "・点の水 " + fineWater + ")");
                return;
            }
            if (BasePlanner.anyWater(bumpy, coarse)) {
                coarseHits++;
            } else if (fineWater) {
                fineOnly++;
            }
        }
        helper.assertTrue(coarseHits > 0 && fineOnly > 0, "粗い点で断る道・細かい点まで見て断る道のどちらかを通っていない: " + coarseHits + " / " + fineOnly);
        helper.succeed();
    }
}
