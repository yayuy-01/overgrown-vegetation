package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.CorruptionRegistry;
import com.yayuy.overgrownvegetation.corruption.CorruptionSpreader;
import com.yayuy.overgrownvegetation.corruption.HeatForestAdaptation;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.HashSet;
import java.util.Set;

/**
 * ネザーの熱水の森・段階 1(2026-11-09)のテスト。ネザーの遠くの専用チャンクで、深部の一括置換と同じ手順
 * (上から 1 段ずつ canCorrupt → corrupt → placeCorrupted → 空気に面していれば growSurfacePlants)を回して確かめる。
 * <ul>
 *     <li>地形のブロックが、床 = 熱の敷物・高い天井 = 光の苔・低い天井と中身 = 冷えた殻 になる</li>
 *     <li>溶岩は表面だけ殻になり、下の溶岩と流れている溶岩はそのまま</li>
 *     <li>元のネザーのブロック(赤と灰)と前の過繁茂したネザーラックが残らない</li>
 *     <li>床・低い天井・高い天井・壁に熱水の森の植物が生え、オーバーワールドの洞窟の植物・下草は生えない</li>
 * </ul>
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class HeatForestGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(HeatForestGameTests.class);
        }

        private Registration() {
        }
    }

    /** ネザー側の専用の列(ほかのネザーのテストは 9_000 台の正の座標)。 */
    private static final int CHUNK = -9_300;

    /** 残ってはいけない元のブロック。 */
    private static final Set<Block> VANILLA = Set.of(Blocks.NETHERRACK, Blocks.CRIMSON_NYLIUM, Blocks.WARPED_NYLIUM,
            Blocks.SOUL_SAND, Blocks.SOUL_SOIL, Blocks.BASALT, Blocks.SMOOTH_BASALT, Blocks.BLACKSTONE, Blocks.MAGMA_BLOCK);

    /** 低い天井(空き 5)と床。床の種類を混ぜ、角に溶岩の池、真ん中に柱。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 400, batch = "heat_forest")
    public static void lowCeilingBecomesHeatForest(GameTestHelper helper) {
        ServerLevel nether = nether(helper);
        if (nether == null) return;
        HeatForestAdaptation.forceAdapted(nether); // 10 分たって熱水の森に適応した後
        ChunkPos c = new ChunkPos(CHUNK, CHUNK);
        Block[] floors = {Blocks.NETHERRACK, Blocks.SOUL_SAND, Blocks.BASALT, Blocks.BLACKSTONE, Blocks.CRIMSON_NYLIUM, Blocks.SOUL_SOIL};
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                BlockPos col = new BlockPos(c.getMinBlockX() + x, 0, c.getMinBlockZ() + z);
                Block floor = floors[(x / 3 + z / 3) % floors.length];
                for (int y = 94; y <= 110; y++) {
                    BlockState s;
                    if (y <= 99) s = (y == 99 ? floor : Blocks.NETHERRACK).defaultBlockState();
                    else if (y <= 104) s = Blocks.AIR.defaultBlockState();
                    else s = Blocks.NETHERRACK.defaultBlockState();
                    nether.setBlock(col.atY(y), s, Block.UPDATE_CLIENTS);
                }
                if (x >= 6 && x <= 8 && z >= 6 && z <= 8) { // 柱(壁の植物の場所)
                    for (int y = 100; y <= 104; y++) nether.setBlock(col.atY(y), Blocks.NETHERRACK.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
                if (x >= 12 && z >= 12) { // 溶岩の池(2 段)
                    nether.setBlock(col.atY(98), Blocks.LAVA.defaultBlockState(), Block.UPDATE_CLIENTS);
                    nether.setBlock(col.atY(99), Blocks.LAVA.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
        }
        corruptTopDown(nether, c, 94, 110);

        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int floorPlants = 0, hangers = 0, wallPlants = 0, overworld = 0;
        Set<Block> heat = heatBlocks();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int wx = c.getMinBlockX() + x, wz = c.getMinBlockZ() + z;
                boolean pillar = x >= 6 && x <= 8 && z >= 6 && z <= 8, lava = x >= 12 && z >= 12;
                for (int y = 94; y <= 110; y++) {
                    BlockState s = nether.getBlockState(p.set(wx, y, wz));
                    if (VANILLA.contains(s.getBlock()) || s.is(ModBlocks.OVERGROWN_NETHERRACK.get()) || s.is(ModBlocks.OVERGROWN_SOUL_SAND.get())
                            || s.is(ModBlocks.OVERGROWN_SOUL_SOIL.get())) {
                        helper.fail("元のネザーのブロックが残った: " + s + " @ " + p);
                        return;
                    }
                    if (!heat.contains(s.getBlock()) && !s.isAir() && !s.is(Blocks.LAVA) && !s.is(ModTags.CORRUPTED)) {
                        helper.fail("熱水の森でないブロック: " + s + " @ " + p);
                        return;
                    }
                    if (s.is(ModTags.CORRUPTED) && !heat.contains(s.getBlock())) {
                        overworld++;
                    }
                }
                BlockState floor = nether.getBlockState(p.set(wx, 99, wz));
                if (lava) {
                    if (!floor.is(ModBlocks.HEAT_COOLED_CRUST.get())) { helper.fail("溶岩の表面が殻にならない: " + floor); return; }
                    BlockState under = nether.getBlockState(p.set(wx, 98, wz));
                    if (!under.is(Blocks.LAVA)) { helper.fail("殻の下の溶岩が残っていない: " + under); return; }
                } else if (!pillar && !underStructure(nether.getBlockState(p.set(wx, 100, wz))) && !floor.is(ModBlocks.HEAT_MAT.get()) && !floor.is(ModBlocks.HEAT_MAT_STRANDS.get())) {
                    helper.fail("床が熱の敷物にならない: " + floor + " @ " + p.set(wx, 99, wz));
                    return;
                }
                BlockState ceil = nether.getBlockState(p.set(wx, 105, wz));
                if (!pillar && !ceil.is(ModBlocks.HEAT_COOLED_CRUST.get())) { helper.fail("低い天井が殻にならない: " + ceil); return; }
                BlockState inner = nether.getBlockState(p.set(wx, 96, wz));
                if (!inner.is(ModBlocks.HEAT_COOLED_CRUST.get())) { helper.fail("中身が殻にならない: " + inner); return; }
                if (!pillar && heat.contains(nether.getBlockState(p.set(wx, 100, wz)).getBlock())) floorPlants++;
                if (!pillar && heat.contains(nether.getBlockState(p.set(wx, 104, wz)).getBlock())) hangers++;
            }
        }
        // 柱(6〜8)の 4 面に接するマス全部(角は除く 12 列 × 高さ 5)。11-09 追記 2: 壁の割合を 0.35 に下げたので、数える所を広げた
        for (int y = 100; y <= 104; y++) {
            for (int x = 5; x <= 9; x++) {
                for (int z = 5; z <= 9; z++) {
                    boolean ring = (x == 5 || x == 9) != (z == 5 || z == 9);
                    if (!ring) continue;
                    BlockState s = nether.getBlockState(new BlockPos(c.getMinBlockX() + x, y, c.getMinBlockZ() + z));
                    if (s.is(ModBlocks.HEAT_WALL_FERN.get()) || s.is(ModBlocks.HEAT_WALL_SCALE.get())) wallPlants++;
                }
            }
        }
        // 床 ≒ 230 マス × 0.97、低い天井の垂れ物 ≒ 230 × 0.97 × 0.75、壁 ≒ 60 面 × 0.97 × 0.35 ≒ 20 から垂れ物にふさがれる分を引く(どれも下限は緩く)
        if (floorPlants < 120) { helper.fail("床の植物が少ない: " + floorPlants); return; }
        if (hangers < 60) { helper.fail("低い天井の垂れ物が少ない: " + hangers); return; }
        if (wallPlants < 3) { helper.fail("壁の植物が少ない: " + wallPlants); return; }
        if (overworld > 3) { helper.fail("オーバーワールドの植物が多い(まれの枠を超えた): " + overworld); return; }
        helper.succeed();
    }

    /** 高い天井(空き 32)は光の苔 A になり、根のカーテン・袋の房が下がる。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 400, batch = "heat_forest")
    public static void highCeilingGetsGlowMossAndCurtains(GameTestHelper helper) {
        ServerLevel nether = nether(helper);
        if (nether == null) return;
        HeatForestAdaptation.forceAdapted(nether); // 10 分たって熱水の森に適応した後
        ChunkPos c = new ChunkPos(CHUNK + 2, CHUNK);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                BlockPos col = new BlockPos(c.getMinBlockX() + x, 0, c.getMinBlockZ() + z);
                for (int y = 96; y <= 104; y++) nether.setBlock(col.atY(y), Blocks.NETHERRACK.defaultBlockState(), Block.UPDATE_CLIENTS);
                for (int y = 105; y <= 136; y++) nether.setBlock(col.atY(y), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                for (int y = 137; y <= 139; y++) nether.setBlock(col.atY(y), Blocks.NETHERRACK.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
        corruptTopDown(nether, c, 96, 139);
        int moss = 0, sky = 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int wx = c.getMinBlockX() + x, wz = c.getMinBlockZ() + z;
                if (nether.getBlockState(p.set(wx, 137, wz)).is(ModBlocks.HEAT_CEILING_GLOW_MOSS.get())) moss++;
                BlockState h = nether.getBlockState(p.set(wx, 136, wz));
                if (h.is(ModBlocks.HEAT_ROOT_CURTAIN.get()) || h.is(ModBlocks.HEAT_ROOT_ROPE.get()) || h.is(ModBlocks.HEAT_POD_CLUSTER.get())) sky++;
                if (h.is(ModBlocks.HEAT_HANGING_MOSS.get()) || h.is(ModBlocks.HEAT_BEARD_LICHEN.get())) {
                    helper.fail("高い天井に地上の側の垂れ物: " + h);
                    return;
                }
            }
        }
        if (moss < 250) { helper.fail("高い天井が光の苔にならない: " + moss + " / 256"); return; }
        // 256 × 0.97 × 11% ≒ 27(11-09 に減らした後の値)
        if (sky < 8) { helper.fail("高い天井の垂れ物が少ない: " + sky); return; }
        helper.succeed();
    }

    /** 流れている溶岩は置き換えない(溶岩の滝を残す)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200, batch = "heat_forest")
    public static void flowingLavaStaysInNether(GameTestHelper helper) {
        ServerLevel nether = nether(helper);
        if (nether == null) return;
        HeatForestAdaptation.forceAdapted(nether); // 10 分たって熱水の森に適応した後
        ChunkPos c = new ChunkPos(CHUNK + 4, CHUNK);
        BlockPos at = new BlockPos(c.getMinBlockX() + 8, 100, c.getMinBlockZ() + 8);
        nether.setBlock(at.below(), Blocks.NETHERRACK.defaultBlockState(), Block.UPDATE_CLIENTS);
        nether.setBlock(at.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        BlockState flowing = Blocks.LAVA.defaultBlockState().setValue(LiquidBlock.LEVEL, 3);
        nether.setBlock(at, flowing, Block.UPDATE_CLIENTS);
        BlockState got = CorruptionRegistry.corrupt(nether, at, nether.getBlockState(at), nether.getRandom());
        if (!CorruptionRegistry.noChange(nether.getBlockState(at), got)) {
            helper.fail("流れている溶岩が置き換わる: " + got);
            return;
        }
        helper.succeed();
    }

    /** 鉱石・グロウストーンは熱水の森の版になる(11-09 追記 1)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200, batch = "heat_forest")
    public static void oresBecomeCrustOres(GameTestHelper helper) {
        ServerLevel nether = nether(helper);
        if (nether == null) return;
        HeatForestAdaptation.forceAdapted(nether);
        ChunkPos c = new ChunkPos(CHUNK + 6, CHUNK);
        Block[][] cases = {{Blocks.NETHER_QUARTZ_ORE, ModBlocks.HEAT_QUARTZ_ORE.get()}, {Blocks.NETHER_GOLD_ORE, ModBlocks.HEAT_GOLD_ORE.get()},
                {Blocks.ANCIENT_DEBRIS, ModBlocks.HEAT_ANCIENT_DEBRIS.get()}, {Blocks.GLOWSTONE, ModBlocks.HEAT_GLOWSTONE.get()}};
        for (int i = 0; i < cases.length; i++) {
            BlockPos p = new BlockPos(c.getMinBlockX() + 2 + i * 2, 100, c.getMinBlockZ() + 8);
            nether.setBlock(p, cases[i][0].defaultBlockState(), Block.UPDATE_CLIENTS);
            BlockState got = CorruptionRegistry.corrupt(nether, p, nether.getBlockState(p), nether.getRandom());
            if (!got.is(cases[i][1])) {
                helper.fail(cases[i][0] + " が " + cases[i][1] + " にならない: " + got);
                return;
            }
        }
        helper.succeed();
    }

    /** 適応した後は、前からある汚染(前の作りの地面・オーバーワールドの植物)が熱水の森の物へ置き換わる。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200, batch = "heat_forest")
    public static void legacyCorruptionConvertsAfterAdaptation(GameTestHelper helper) {
        ServerLevel nether = nether(helper);
        if (nether == null) return;
        HeatForestAdaptation.forceAdapted(nether);
        ChunkPos c = new ChunkPos(CHUNK + 8, CHUNK);
        int ground = 0, plants = 0, kept = 0, total = 0;
        Set<Block> heat = heatBlocks();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                BlockPos floor = new BlockPos(c.getMinBlockX() + x, 100, c.getMinBlockZ() + z);
                nether.setBlock(floor, ModBlocks.OVERGROWN_NETHERRACK.get().defaultBlockState(), Block.UPDATE_CLIENTS);
                for (int y = 101; y <= 104; y++) nether.setBlock(floor.atY(y), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                nether.setBlock(floor.above(), ModBlocks.OVERGROWN_ZENMAI.get().defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                BlockPos floor = new BlockPos(c.getMinBlockX() + x, 100, c.getMinBlockZ() + z);
                HeatForestAdaptation.convertAt(nether, floor.above());
                HeatForestAdaptation.convertAt(nether, floor);
                total++;
                BlockState g = nether.getBlockState(floor);
                // 11-10: 置き換えた床の格子の印にも木が立つので、先に立った幹の下の床は殻になる
                if (g.is(ModBlocks.HEAT_MAT.get()) || g.is(ModBlocks.HEAT_MAT_STRANDS.get())
                        || (g.is(ModBlocks.HEAT_COOLED_CRUST.get()) && underStructure(nether.getBlockState(floor.above())))) ground++;
                BlockState p = nether.getBlockState(floor.above());
                if (heat.contains(p.getBlock())) plants++;
                if (p.is(ModBlocks.OVERGROWN_ZENMAI.get())) kept++;
            }
        }
        if (ground != total) { helper.fail("前の作りの地面が熱の敷物にならない: " + ground + " / " + total); return; }
        // 15% は残す。残りは対の熱のゼンマイへ
        if (kept < 10 || kept > 70) { helper.fail("残るオーバーワールドの植物の数が外れ: " + kept + " / " + total); return; }
        if (plants < total - kept - 5) { helper.fail("熱水の森の植物にならない: " + plants); return; }
        helper.succeed();
    }

    /** 最初の汚染から 10 分の間は、オーバーワールドと同じ植生(前の作り)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200, batch = "heat_forest_early")
    public static void beforeAdaptationNetherUsesOverworldVegetation(GameTestHelper helper) {
        ServerLevel nether = nether(helper);
        if (nether == null) return;
        HeatForestAdaptation.forceEarly(nether);
        ChunkPos c = new ChunkPos(CHUNK + 10, CHUNK);
        BlockPos p = new BlockPos(c.getMinBlockX() + 8, 100, c.getMinBlockZ() + 8);
        nether.setBlock(p, Blocks.NETHERRACK.defaultBlockState(), Block.UPDATE_CLIENTS);
        nether.setBlock(p.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        BlockState got = CorruptionRegistry.corrupt(nether, p, nether.getBlockState(p), nether.getRandom());
        boolean ok = got.is(ModBlocks.OVERGROWN_NETHERRACK.get()) || got.is(ModBlocks.OVERGROWN_BLOCK.get());
        HeatForestAdaptation.forceAdapted(nether); // 後のバッチのために戻す
        if (!ok) {
            helper.fail("適応の前なのに熱水の森になった: " + got);
            return;
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ 木の段階の前半(11-10)

    /** 幹・管・煙突の下の床(先に立った隣の木の幹が上をふさぐと、その床は殻になる。11-10 木の段階)。 */
    private static boolean underStructure(BlockState above) {
        return above.is(ModBlocks.HEAT_BARK.get()) || above.is(ModBlocks.HEAT_TUBE.get()) || above.is(ModBlocks.HEAT_TUBE_STEM.get())
                || above.is(ModBlocks.HEAT_CHIMNEY.get()) || above.is(ModBlocks.HEAT_CHIMNEY_BASE.get());
    }

    /** 敷物の床と、上の空き(y 101〜{@code top})の箱を作る。 */
    private static void flatMat(ServerLevel nether, ChunkPos c, int top) {
        for (int x = c.getMinBlockX() - 2; x <= c.getMaxBlockX() + 2; x++) {
            for (int z = c.getMinBlockZ() - 2; z <= c.getMaxBlockZ() + 2; z++) {
                nether.setBlock(new BlockPos(x, 100, z), ModBlocks.HEAT_MAT.get().defaultBlockState(), Block.UPDATE_CLIENTS);
                for (int y = 101; y <= top; y++) nether.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
    }

    private static int count(ServerLevel nether, ChunkPos c, int y0, int y1, Block b) {
        int n = 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = c.getMinBlockX() - 2; x <= c.getMaxBlockX() + 2; x++)
            for (int z = c.getMinBlockZ() - 2; z <= c.getMaxBlockZ() + 2; z++)
                for (int y = y0; y <= y1; y++) if (nether.getBlockState(p.set(x, y, z)).is(b)) n++;
        return n;
    }

    /** 普通の木 B 案 2: 2×2 の幹(高さ 4)・平たい傘・紫の房。管の樹・煙突の塚も置ける。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 300, batch = "heat_forest")
    public static void groundTreesTubesAndMoundsPlace(GameTestHelper helper) {
        ServerLevel nether = nether(helper);
        if (nether == null) return;
        HeatForestAdaptation.forceAdapted(nether);
        ChunkPos c = new ChunkPos(CHUNK + 12, CHUNK);
        flatMat(nether, c, 140);
        BlockPos base = new BlockPos(c.getMinBlockX() + 7, 101, c.getMinBlockZ() + 7);
        if (!com.yayuy.overgrownvegetation.corruption.HeatForestTrees.placeGroundTree(nether, base)) { helper.fail("普通の木が置けない"); return; }
        int bark = count(nether, c, 101, 104, ModBlocks.HEAT_BARK.get());
        int leaves = count(nether, c, 105, 106, ModBlocks.HEAT_LEAVES.get());
        int fringe = count(nether, c, 104, 104, ModBlocks.HEAT_LEAF_FRINGE.get());
        if (bark != 16) { helper.fail("幹が 2×2×4 でない: " + bark); return; }
        if (leaves < 60) { helper.fail("傘の葉が少ない: " + leaves); return; }
        if (fringe < 12) { helper.fail("縁の房が少ない: " + fringe); return; }

        ChunkPos c2 = new ChunkPos(CHUNK + 14, CHUNK);
        flatMat(nether, c2, 140);
        BlockPos b2 = new BlockPos(c2.getMinBlockX() + 7, 101, c2.getMinBlockZ() + 7);
        if (!com.yayuy.overgrownvegetation.corruption.HeatForestTrees.placeTubeTree(nether, b2, true, nether.getRandom())) { helper.fail("管の樹 B が置けない"); return; }
        if (count(nether, c2, 101, 140, ModBlocks.HEAT_TUBE_PLUME.get()) < 1) { helper.fail("管の羽根が無い"); return; }
        BlockPos b3 = b2.offset(-5, 0, -5);
        if (!com.yayuy.overgrownvegetation.corruption.HeatForestTrees.placeTubeTree(nether, b3, false, nether.getRandom())) { helper.fail("管の樹 A が置けない"); return; }
        if (count(nether, c2, 101, 140, ModBlocks.HEAT_TUBE_STEM.get()) < 3) { helper.fail("細い管が無い"); return; }

        ChunkPos c3 = new ChunkPos(CHUNK + 16, CHUNK);
        flatMat(nether, c3, 140);
        BlockPos b4 = new BlockPos(c3.getMinBlockX() + 8, 101, c3.getMinBlockZ() + 8);
        if (!com.yayuy.overgrownvegetation.corruption.HeatForestTrees.placeMound(nether, b4, true, nether.getRandom())) { helper.fail("煙突の塚が置けない"); return; }
        int chim = count(nether, c3, 101, 140, ModBlocks.HEAT_CHIMNEY.get()) + count(nether, c3, 101, 140, ModBlocks.HEAT_CHIMNEY_BASE.get());
        if (chim < 20) { helper.fail("煙突の塚が小さい: " + chim); return; }
        helper.succeed();
    }

    /** 天井の逆さの木の決まり(決): 24 より低い天井には生えない・長さは天井の高さの 1/3 まで・樹冠の下端は床から 15 以上。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 300, batch = "heat_forest")
    public static void invertedTreeFollowsCeilingRules(GameTestHelper helper) {
        ServerLevel nether = nether(helper);
        if (nether == null) return;
        HeatForestAdaptation.forceAdapted(nether);
        ChunkPos low = new ChunkPos(CHUNK + 18, CHUNK);
        ChunkPos high = new ChunkPos(CHUNK + 20, CHUNK);
        for (ChunkPos c : new ChunkPos[]{low, high}) {
            int ceil = c == low ? 120 : 140;   // 空きは 19 / 39
            for (int x = c.getMinBlockX() - 4; x <= c.getMaxBlockX() + 4; x++) for (int z = c.getMinBlockZ() - 4; z <= c.getMaxBlockZ() + 4; z++) {
                nether.setBlock(new BlockPos(x, 100, z), ModBlocks.HEAT_MAT.get().defaultBlockState(), Block.UPDATE_CLIENTS);
                for (int y = 101; y < ceil; y++) nether.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                nether.setBlock(new BlockPos(x, ceil, z), ModBlocks.HEAT_CEILING_GLOW_MOSS.get().defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
        BlockPos lowAt = new BlockPos(low.getMinBlockX() + 8, 119, low.getMinBlockZ() + 8);
        if (com.yayuy.overgrownvegetation.corruption.HeatForestTrees.placeInvertedTree(nether, lowAt, nether.getRandom())) {
            helper.fail("24 より低い天井に逆さの木が生えた");
            return;
        }
        BlockPos at = new BlockPos(high.getMinBlockX() + 8, 139, high.getMinBlockZ() + 8);
        if (!com.yayuy.overgrownvegetation.corruption.HeatForestTrees.placeInvertedTree(nether, at, nether.getRandom())) { helper.fail("高い天井に逆さの木が生えない"); return; }
        int len = 0;
        while (nether.getBlockState(at.below(len)).is(ModBlocks.HEAT_BARK.get())) len++;
        if (len < 3 || len > 39 / 3) { helper.fail("幹の長さが決まりの外: " + len); return; }
        int lowestLeaf = Integer.MAX_VALUE;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) for (int y = 101; y < 140; y++)
            if (nether.getBlockState(p.set(at.getX() + x, y, at.getZ() + z)).is(ModBlocks.HEAT_SKY_LEAVES.get())) lowestLeaf = Math.min(lowestLeaf, y);
        if (lowestLeaf == Integer.MAX_VALUE || lowestLeaf - 100 < 15) { helper.fail("樹冠の下端が床から 15 未満: " + lowestLeaf); return; }
        helper.succeed();
    }

    /**
     * 菌の木(真紅・歪んだ)が汚染されると、オーバーワールドと同じく倒木か消滅になる(11-10 ユーザー決定)。
     * 幹・ウォートブロック・シュルームライト・垂れるツタが残らず、残る幹は切り株と横倒しの倒木だけ。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 400, batch = "heat_forest")
    public static void fungusTreesFallLikeOverworldTrees(GameTestHelper helper) {
        ServerLevel nether = nether(helper);
        if (nether == null) return;
        HeatForestAdaptation.forceAdapted(nether);
        Block[][] kinds = {{Blocks.CRIMSON_STEM, Blocks.NETHER_WART_BLOCK, Blocks.CRIMSON_NYLIUM}, {Blocks.WARPED_STEM, Blocks.WARPED_WART_BLOCK, Blocks.WARPED_NYLIUM}};
        BlockPos[] bases = new BlockPos[2];
        for (int k = 0; k < 2; k++) {
            ChunkPos c = new ChunkPos(CHUNK + 24 + k * 2, CHUNK);
            BlockPos base = new BlockPos(c.getMinBlockX() + 8, 101, c.getMinBlockZ() + 8);
            bases[k] = base;
            for (int x = -6; x <= 6; x++) for (int z = -6; z <= 6; z++) {
                nether.setBlock(base.offset(x, -1, z), kinds[k][2].defaultBlockState(), Block.UPDATE_CLIENTS);
                for (int y = 0; y <= 10; y++) nether.setBlock(base.offset(x, y, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
            for (int y = 0; y < 6; y++) nether.setBlock(base.above(y), kinds[k][0].defaultBlockState(), Block.UPDATE_CLIENTS);
            for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) for (int y = 5; y <= 6; y++) {
                if (x == 0 && z == 0 && y == 5) continue;
                nether.setBlock(base.offset(x, y, z), kinds[k][1].defaultBlockState(), Block.UPDATE_CLIENTS);
            }
            nether.setBlock(base.offset(1, 4, 1), Blocks.SHROOMLIGHT.defaultBlockState(), Block.UPDATE_CLIENTS);
            nether.setBlock(base.offset(2, 4, -2), Blocks.WEEPING_VINES_PLANT.defaultBlockState(), Block.UPDATE_CLIENTS);
            nether.setBlock(base.offset(2, 3, -2), Blocks.WEEPING_VINES.defaultBlockState(), Block.UPDATE_CLIENTS);
            // 幹に汚染が来る(深部の一括置換と同じ手順の 1 マス)
            BlockState s = nether.getBlockState(base.above(2));
            BlockState n = CorruptionRegistry.corrupt(nether, base.above(2), s, nether.getRandom());
            if (!CorruptionRegistry.noChange(s, n)) CorruptionRegistry.placeCorrupted(nether, base.above(2), s, n, Block.UPDATE_CLIENTS);
            // 傘のウォートに汚染が来ても、覆われずに残る(倒木が消すまで)
            BlockState w = nether.getBlockState(base.offset(-2, 6, -2));
            if (!CorruptionRegistry.noChange(w, CorruptionRegistry.corrupt(nether, base.offset(-2, 6, -2), w, nether.getRandom()))) {
                helper.fail("ネザーのウォートブロックが汚染で別のブロックに替わった");
                return;
            }
        }
        helper.succeedWhen(() -> {
            for (int k = 0; k < 2; k++) {
                BlockPos base = bases[k];
                BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
                for (int x = -6; x <= 6; x++) for (int z = -6; z <= 6; z++) for (int y = 0; y <= 10; y++) {
                    BlockState s = nether.getBlockState(p.set(base.getX() + x, base.getY() + y, base.getZ() + z));
                    helper.assertTrue(!s.is(net.minecraft.tags.BlockTags.WART_BLOCKS) && !s.is(Blocks.SHROOMLIGHT), "ウォートかシュルームライトが残った @ " + p);
                    helper.assertTrue(!s.is(Blocks.WEEPING_VINES) && !s.is(Blocks.WEEPING_VINES_PLANT), "垂れるツタが残った @ " + p);
                    if (s.is(kinds[k][0])) {
                        boolean stump = x == 0 && z == 0 && y == 0;
                        boolean lying = y <= 1 && s.getValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS) != Direction.Axis.Y;
                        helper.assertTrue(stump || lying, "立ったままの幹が残った @ " + p);
                    }
                }
            }
        });
    }

    /** 林の中で立ったままの幹(縦向きで真上も幹)の位置。調べ用。 */
    private static String standing(ServerLevel level, BlockPos center) {
        StringBuilder b = new StringBuilder();
        for (int x = -12; x <= 10; x++) for (int z = -12; z <= 12; z++) for (int y = 0; y <= 40; y++) {
            BlockPos p = center.offset(x, y, z);
            BlockState s = level.getBlockState(p);
            if ((s.is(net.minecraft.tags.BlockTags.CRIMSON_STEMS) || s.is(net.minecraft.tags.BlockTags.WARPED_STEMS))
                    && level.getBlockState(p.above()).is(s.getBlock())) {
                b.append(x).append(',').append(y).append(',').append(z).append(' ');
            }
        }
        return b.toString();
    }

    /** その列の上 12 段のブロック。調べ用。 */
    private static String column(ServerLevel level, BlockPos pos) {
        StringBuilder b = new StringBuilder();
        for (int y = -1; y <= 12; y++) b.append(y).append('=').append(level.getBlockState(pos.above(y))).append(' ');
        return b.toString();
    }

    /** 失敗したマスのまわり(26 方向)の空気でないブロック。調べ用。 */
    private static String around(ServerLevel level, BlockPos pos) {
        StringBuilder b = new StringBuilder();
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dy == 0 && dz == 0) continue;
            BlockState s = level.getBlockState(pos.offset(dx, dy, dz));
            if (s.isAir()) continue;
            b.append('(').append(dx).append(',').append(dy).append(',').append(dz).append(')').append(s).append(' ');
        }
        return b.toString();
    }

    /**
     * 自然の菌の木(バニラの地物)を 3×3 本、傘がつながるほど密に並べて幹を全部汚染すると、林の中にウォートとシュルームライトが残らない
     * (2026-11-10 追記 12。前は傘の下の縁と、先に倒れた木に途中を消された隣の傘が宙に残った)。
     * 汚染していない木を 1 本そばに立て、その傘は残ることも確かめる(汚染の境目の外の木)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 1600, batch = "heat_forest_dense")
    public static void denseFungusForestLeavesNoFloatingCaps(GameTestHelper helper) {
        ServerLevel nether = nether(helper);
        if (nether == null) return;
        HeatForestAdaptation.forceAdapted(nether);
        var features = nether.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.CONFIGURED_FEATURE);
        Object[][] kinds = {
                {net.minecraft.data.worldgen.features.TreeFeatures.CRIMSON_FUNGUS, Blocks.CRIMSON_NYLIUM},
                {net.minecraft.data.worldgen.features.TreeFeatures.WARPED_FUNGUS, Blocks.WARPED_NYLIUM}};
        BlockPos[] centers = new BlockPos[2];
        BlockPos[] keepers = new BlockPos[2];
        net.minecraft.util.RandomSource random = net.minecraft.util.RandomSource.create(20261110L);
        for (int k = 0; k < 2; k++) {
            ChunkPos c = new ChunkPos(CHUNK + 24 + k * 4, CHUNK + 6);
            BlockPos center = new BlockPos(c.getMinBlockX() + 8, 101, c.getMinBlockZ() + 8);
            centers[k] = center;
            for (int x = -20; x <= 20; x++) for (int z = -16; z <= 16; z++) {
                nether.setBlock(center.offset(x, -1, z), ((Block) kinds[k][1]).defaultBlockState(), Block.UPDATE_CLIENTS);
                for (int y = 0; y <= 40; y++) nether.setBlock(center.offset(x, y, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
            @SuppressWarnings("unchecked")
            var key = (net.minecraft.resources.ResourceKey<net.minecraft.world.level.levelgen.feature.ConfiguredFeature<?, ?>>) kinds[k][0];
            var feature = features.getHolderOrThrow(key).value();
            int placed = 0;
            for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++) {
                if (feature.place(nether, nether.getChunkSource().getGenerator(), random, center.offset(i * 5, 0, j * 5))) placed++;
            }
            if (placed < 7) {
                helper.fail("菌の木が " + placed + " 本しか生えなかった");
                return;
            }
            // 汚染しない木(林の東の外。傘が林の傘とつながらない間を空ける)
            keepers[k] = center.offset(16, 0, 0);
            feature.place(nether, nether.getChunkSource().getGenerator(), random, keepers[k]);
            // 林の幹(x ≤ +7)を全部汚染する
            for (int x = -12; x <= 7; x++) for (int z = -12; z <= 12; z++) for (int y = 0; y <= 40; y++) {
                BlockPos p = center.offset(x, y, z);
                BlockState s = nether.getBlockState(p);
                if (!s.is(net.minecraft.tags.BlockTags.CRIMSON_STEMS) && !s.is(net.minecraft.tags.BlockTags.WARPED_STEMS)) continue;
                BlockState n = CorruptionRegistry.corrupt(nether, p, s, nether.getRandom());
                if (!CorruptionRegistry.noChange(s, n)) CorruptionRegistry.placeCorrupted(nether, p, s, n, Block.UPDATE_CLIENTS);
            }
        }
        helper.succeedWhen(() -> {
            for (int k = 0; k < 2; k++) {
                BlockPos center = centers[k];
                BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
                for (int x = -12; x <= 10; x++) for (int z = -12; z <= 12; z++) for (int y = 0; y <= 40; y++) {
                    BlockState s = nether.getBlockState(p.set(center.getX() + x, center.getY() + y, center.getZ() + z));
                    if (x <= 10) { // 汚染しない木(x = 16)の傘が届かない所まで見る
                        if (s.is(net.minecraft.tags.BlockTags.WART_BLOCKS) || s.is(Blocks.SHROOMLIGHT)) throw new net.minecraft.gametest.framework.GameTestAssertException(
                                "ウォートかシュルームライトが残った @ " + p.immutable() + " まわり " + around(nether, p.immutable()) + " 立った幹 " + standing(nether, center)); // 文字列は失敗したときだけ作る(毎マス作ると止まる)
                    }
                    if (s.is(net.minecraft.tags.BlockTags.CRIMSON_STEMS) || s.is(net.minecraft.tags.BlockTags.WARPED_STEMS)) {
                        boolean lying = s.getValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS) != Direction.Axis.Y;
                        BlockState up = nether.getBlockState(p.above()); // 切り株の上を隣の倒木(横向き)が渡ることはある
                        boolean stump = !(up.is(s.getBlock()) && up.getValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS) == Direction.Axis.Y);
                        if (!lying && !stump) throw new net.minecraft.gametest.framework.GameTestAssertException("立ったままの幹が残った @ " + p.immutable() + " まわり " + around(nether, p.immutable()) + " 立った幹 " + standing(nether, center) + " 列 " + column(nether, p.immutable()));
                    }
                }
                // 汚染しない木は立ったままで、傘も残っている
                BlockPos keeper = keepers[k];
                helper.assertTrue(nether.getBlockState(keeper.above(2)).is(net.minecraft.tags.BlockTags.CRIMSON_STEMS)
                        || nether.getBlockState(keeper.above(2)).is(net.minecraft.tags.BlockTags.WARPED_STEMS), "汚染していない木の幹が消えた");
                int caps = 0;
                for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) for (int y = 0; y <= 40; y++) {
                    BlockState s = nether.getBlockState(p.set(keeper.getX() + x, keeper.getY() + y, keeper.getZ() + z));
                    if (s.is(net.minecraft.tags.BlockTags.WART_BLOCKS)) caps++;
                }
                helper.assertTrue(caps > 10, "汚染していない木の傘が消えた(" + caps + ")");
            }
        });
    }

    // ------------------------------------------------------------------ 苗木方式(11-10 ユーザー決定)

    private static void grow(ServerLevel nether, BlockPos pos) {
        BlockState s = nether.getBlockState(pos);
        if (s.getBlock() instanceof com.yayuy.overgrownvegetation.block.HeatSproutBlock sprout) {
            sprout.growNow(nether, pos, s, nether.getRandom());
        }
    }

    /** 芽が育って木になり、何本か育てると高さが 1 種類にならない。管の樹の芽・若い煙突も育つ。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 300, batch = "heat_forest")
    public static void sproutsGrowIntoVariedTrees(GameTestHelper helper) {
        ServerLevel nether = nether(helper);
        if (nether == null) return;
        HeatForestAdaptation.forceAdapted(nether);
        int x0 = new ChunkPos(CHUNK + 30, CHUNK).getMinBlockX(), z0 = new ChunkPos(CHUNK + 30, CHUNK).getMinBlockZ();
        for (int x = x0 - 8; x <= x0 + 8 + 13 * 9; x++) for (int z = z0 - 8; z <= z0 + 24; z++) {
            nether.setBlock(new BlockPos(x, 100, z), ModBlocks.HEAT_MAT.get().defaultBlockState(), Block.UPDATE_CLIENTS);
            for (int y = 101; y <= 120; y++) nether.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        Set<Integer> heights = new HashSet<>();
        for (int i = 0; i < 10; i++) {
            BlockPos p = new BlockPos(x0 + i * 13, 101, z0);
            nether.setBlock(p, ModBlocks.HEAT_TREE_SPROUT.get().defaultBlockState(), Block.UPDATE_CLIENTS);
            grow(nether, p);
            int h = 0;
            while (nether.getBlockState(p.above(h)).is(ModBlocks.HEAT_BARK.get())) h++;
            if (h == 0) { helper.fail("芽が木に育たない: " + nether.getBlockState(p)); return; }
            heights.add(h);
        }
        if (heights.size() < 2) { helper.fail("高さが 1 種類しかない: " + heights); return; }
        BlockPos tube = new BlockPos(x0, 101, z0 + 16);
        nether.setBlock(tube, ModBlocks.HEAT_TUBE_SPROUT.get().defaultBlockState(), Block.UPDATE_CLIENTS);
        grow(nether, tube);
        BlockState t = nether.getBlockState(tube);
        if (!t.is(ModBlocks.HEAT_TUBE.get()) && !t.is(ModBlocks.HEAT_TUBE_STEM.get())) { helper.fail("管の樹の芽が育たない: " + t); return; }
        BlockPos mound = new BlockPos(x0 + 40, 101, z0 + 16);
        nether.setBlock(mound, ModBlocks.HEAT_CHIMNEY_SPROUT.get().defaultBlockState(), Block.UPDATE_CLIENTS);
        grow(nether, mound);
        BlockState m = nether.getBlockState(mound);
        if (!m.is(ModBlocks.HEAT_CHIMNEY_BASE.get()) && !m.is(ModBlocks.HEAT_CHIMNEY.get())) { helper.fail("若い煙突が育たない: " + m); return; }
        helper.succeed();
    }

    /** 逆さの木の芽は、決まり(天井 24 以上など)を満たさない所では芽のまま。満たす所では育つ。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 300, batch = "heat_forest")
    public static void skySproutGrowsOnlyUnderHighCeiling(GameTestHelper helper) {
        ServerLevel nether = nether(helper);
        if (nether == null) return;
        HeatForestAdaptation.forceAdapted(nether);
        ChunkPos low = new ChunkPos(CHUNK + 40, CHUNK), high = new ChunkPos(CHUNK + 42, CHUNK);
        for (ChunkPos c : new ChunkPos[]{low, high}) {
            int ceil = c == low ? 120 : 140;
            for (int x = c.getMinBlockX() - 4; x <= c.getMaxBlockX() + 4; x++) for (int z = c.getMinBlockZ() - 4; z <= c.getMaxBlockZ() + 4; z++) {
                nether.setBlock(new BlockPos(x, 100, z), ModBlocks.HEAT_MAT.get().defaultBlockState(), Block.UPDATE_CLIENTS);
                for (int y = 101; y < ceil; y++) nether.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                nether.setBlock(new BlockPos(x, ceil, z), ModBlocks.HEAT_CEILING_GLOW_MOSS.get().defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
        BlockPos lowAt = new BlockPos(low.getMinBlockX() + 8, 119, low.getMinBlockZ() + 8);
        nether.setBlock(lowAt, ModBlocks.HEAT_SKY_SPROUT.get().defaultBlockState(), Block.UPDATE_CLIENTS);
        grow(nether, lowAt);
        if (!nether.getBlockState(lowAt).is(ModBlocks.HEAT_SKY_SPROUT.get())) { helper.fail("低い天井で逆さの木の芽が芽のままでない: " + nether.getBlockState(lowAt)); return; }
        BlockPos highAt = new BlockPos(high.getMinBlockX() + 8, 139, high.getMinBlockZ() + 8);
        nether.setBlock(highAt, ModBlocks.HEAT_SKY_SPROUT.get().defaultBlockState(), Block.UPDATE_CLIENTS);
        grow(nether, highAt);
        if (!nether.getBlockState(highAt).is(ModBlocks.HEAT_BARK.get())) { helper.fail("高い天井で逆さの木の芽が育たない: " + nether.getBlockState(highAt)); return; }
        helper.succeed();
    }

    /**
     * 葉が幹から 10 マス広がるオーバーワールドの木を置き換えると、宙に葉が残らない(2026-11-10 追記 13、ユーザー決定: 範囲 8 → 12・
     * つながりの切れた同じ木の葉も消す)。傘のつながった隣の木(立ったまま)の幹と、その木の側の葉は残る。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 300, batch = "heat_forest")
    public static void wideOverworldTreeLeavesNoFloatingLeaves(GameTestHelper helper) {
        ServerLevel nether = nether(helper);
        if (nether == null) return;
        HeatForestAdaptation.forceAdapted(nether);
        ChunkPos c = new ChunkPos(CHUNK + 50, CHUNK);
        BlockPos base = new BlockPos(c.getMinBlockX() + 8, 101, c.getMinBlockZ() + 8);
        BlockPos other = base.offset(16, 0, 0);
        for (int x = -14; x <= 24; x++) for (int z = -14; z <= 14; z++) {
            nether.setBlock(base.offset(x, -1, z), ModBlocks.HEAT_MAT.get().defaultBlockState(), Block.UPDATE_CLIENTS);
            for (int y = 0; y <= 14; y++) nether.setBlock(base.offset(x, y, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        BlockState log = ModBlocks.OVERGROWN_LOG.get().defaultBlockState(), leaves = ModBlocks.OVERGROWN_LEAVES.get().defaultBlockState();
        for (int y = 0; y < 6; y++) {
            nether.setBlock(base.above(y), log, Block.UPDATE_CLIENTS);
            nether.setBlock(other.above(y), log, Block.UPDATE_CLIENTS);
        }
        // この木: 6 段目に半径 10 の円盤の葉と、その下の端に垂れた葉(下の段の葉は円盤にだけつながる)
        for (int x = -10; x <= 10; x++) for (int z = -10; z <= 10; z++) {
            if (x * x + z * z > 100) continue;
            nether.setBlock(base.offset(x, 6, z), leaves, Block.UPDATE_CLIENTS);
            if (x * x + z * z > 81) nether.setBlock(base.offset(x, 5, z), leaves, Block.UPDATE_CLIENTS);
        }
        // 隣の木: 幹から半径 5 の円盤(この木の円盤と縁でつながる)
        for (int x = -5; x <= 5; x++) for (int z = -5; z <= 5; z++) {
            if (x * x + z * z <= 25) nether.setBlock(other.offset(x, 6, z), leaves, Block.UPDATE_CLIENTS);
        }
        if (!com.yayuy.overgrownvegetation.corruption.HeatForestTrees.convertOverworldTree(nether, base.above(2), 0)) { helper.fail("置き換わらない"); return; }
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int otherLeaves = 0;
        for (int x = -14; x <= 24; x++) for (int z = -14; z <= 14; z++) for (int y = 0; y <= 14; y++) {
            BlockState s = nether.getBlockState(p.set(base.getX() + x, base.getY() + y, base.getZ() + z));
            if (!s.is(ModBlocks.OVERGROWN_LEAVES.get())) continue;
            if (x <= 7) { helper.fail("この木の葉が残った @ " + x + "," + y + "," + z); return; }
            if (x >= 11) otherLeaves++;
        }
        if (!nether.getBlockState(other.above(3)).is(ModBlocks.OVERGROWN_LOG.get())) { helper.fail("隣の木の幹が消えた"); return; }
        if (otherLeaves < 60) { helper.fail("隣の木の葉が消えた(" + otherLeaves + ")"); return; }
        helper.succeed();
    }

    /**
     * そばで木が倒れていない所の、ナイリウムの上に菌の幹で立てた柱(傘なし)は、汚染が来ても倒れない
     * (2026-11-10 追記 13、ユーザー決定: 傘の無い幹は、近くで木が倒れたときだけ倒す)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 300, batch = "heat_forest")
    public static void fungusStemPillarWithoutFallNearbyStays(GameTestHelper helper) {
        ServerLevel nether = nether(helper);
        if (nether == null) return;
        HeatForestAdaptation.forceAdapted(nether);
        ChunkPos c = new ChunkPos(CHUNK + 54, CHUNK);
        BlockPos base = new BlockPos(c.getMinBlockX() + 8, 101, c.getMinBlockZ() + 8);
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) {
            nether.setBlock(base.offset(x, -1, z), Blocks.CRIMSON_NYLIUM.defaultBlockState(), Block.UPDATE_CLIENTS);
            for (int y = 0; y <= 8; y++) nether.setBlock(base.offset(x, y, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        for (int y = 0; y < 5; y++) nether.setBlock(base.above(y), Blocks.CRIMSON_STEM.defaultBlockState(), Block.UPDATE_CLIENTS);
        for (int y = 0; y < 5; y++) {
            BlockPos at = base.above(y);
            BlockState s = nether.getBlockState(at);
            BlockState n = CorruptionRegistry.corrupt(nether, at, s, nether.getRandom());
            if (!CorruptionRegistry.noChange(s, n)) CorruptionRegistry.placeCorrupted(nether, at, s, n, Block.UPDATE_CLIENTS);
        }
        helper.runAfterDelay(200, () -> {
            for (int y = 0; y < 5; y++) {
                BlockState s = nether.getBlockState(base.above(y));
                if (!s.is(Blocks.CRIMSON_STEM) && !s.is(ModBlocks.OVERGROWN_TRUNK_BLOCK.get())) {
                    helper.fail("菌の幹の柱が倒れた @ " + y + " " + s);
                    return;
                }
            }
            helper.succeed();
        });
    }

    /** 適応の後、オーバーワールドの木は普通の木 B に置き換わる(15% は残す = 決。ここでは残す割合 0 と 100 で両方を見る)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 300, batch = "heat_forest")
    public static void overworldTreeBecomesHeatTree(GameTestHelper helper) {
        ServerLevel nether = nether(helper);
        if (nether == null) return;
        HeatForestAdaptation.forceAdapted(nether);
        ChunkPos c = new ChunkPos(CHUNK + 22, CHUNK);
        flatMat(nether, c, 130);
        BlockPos base = new BlockPos(c.getMinBlockX() + 7, 101, c.getMinBlockZ() + 7);
        for (int i = 0; i < 6; i++) nether.setBlock(base.above(i), ModBlocks.OVERGROWN_LOG.get().defaultBlockState(), Block.UPDATE_CLIENTS);
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) for (int y = 5; y <= 7; y++) {
            BlockPos p = base.offset(x, y, z);
            if (nether.getBlockState(p).isAir()) nether.setBlock(p, ModBlocks.OVERGROWN_LEAVES.get().defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        if (com.yayuy.overgrownvegetation.corruption.HeatForestTrees.convertOverworldTree(nether, base.above(3), 100)) { helper.fail("残す割合 100 なのに置き換わった"); return; }
        if (!com.yayuy.overgrownvegetation.corruption.HeatForestTrees.convertOverworldTree(nether, base.above(3), 0)) { helper.fail("置き換わらない"); return; }
        if (count(nether, c, 100, 130, ModBlocks.OVERGROWN_LOG.get()) + count(nether, c, 100, 130, ModBlocks.OVERGROWN_LEAVES.get()) > 0) { helper.fail("オーバーワールドの木が残った"); return; }
        if (count(nether, c, 101, 104, ModBlocks.HEAT_BARK.get()) != 16) { helper.fail("普通の木 B が植わらない"); return; }
        helper.succeed();
    }

    // ------------------------------------------------------------------

    /** 深部の一括置換(OvergrownBiomeCreep#deepenChunk)と同じ手順を、チャンクの y0〜y1 に上から回す。 */
    static void corruptTopDown(ServerLevel level, ChunkPos c, int y0, int y1) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int y = y1; y >= y0; y--) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    p.set(c.getMinBlockX() + x, y, c.getMinBlockZ() + z);
                    BlockState s = level.getBlockState(p);
                    if (s.isAir() || s.is(ModTags.CORRUPTED) || !CorruptionRegistry.canCorrupt(level, p, s)) continue;
                    BlockState n = CorruptionRegistry.corrupt(level, p, s, level.getRandom());
                    if (CorruptionRegistry.noChange(s, n)) continue;
                    BlockPos hit = p.immutable();
                    CorruptionRegistry.placeCorrupted(level, hit, s, n, Block.UPDATE_CLIENTS);
                    boolean air = false;
                    for (Direction d : Direction.values()) air |= level.getBlockState(hit.relative(d)).isAir();
                    if (air) CorruptionSpreader.growSurfacePlants(level, hit, n, 0.97D);
                }
            }
        }
    }

    static Set<Block> heatBlocks() {
        Set<Block> s = new HashSet<>();
        for (var b : ModBlocks.heatForestBlocks()) s.add(b.get());
        return s;
    }

    @javax.annotation.Nullable
    private static ServerLevel nether(GameTestHelper helper) {
        ServerLevel nether = helper.getLevel().getServer().getLevel(Level.NETHER);
        if (nether == null) helper.fail("テスト用サーバーにネザーが無い");
        return nether;
    }

    private HeatForestGameTests() {
    }
}
