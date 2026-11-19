package com.yayuy.overgrownvegetation.organ;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.CorruptionRegistry;
import com.yayuy.overgrownvegetation.corruption.OvergrownLayerData;
import com.yayuy.overgrownvegetation.corruption.pushback.RingWards;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import com.yayuy.overgrownvegetation.registry.ModPushBack;
import com.yayuy.overgrownvegetation.worldgen.fusion.StructureFusion;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 乗っ取り(2026-11-19 夜 構造物と森の融合 段 3)。テスト場は {@link StructureFusionGameTests} と同じ(遠くの空き地に家を建て、構造物の記録を直に足す)。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class TakeoverGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(TakeoverGameTests.class);
        }

        private Registration() {
        }
    }

    private TakeoverGameTests() {
    }

    private static boolean modBlock(BlockState s) {
        return !s.isAir() && OvergrownVegetation.MODID.equals(BuiltInRegistries.BLOCK.getKey(s.getBlock()).getNamespace());
    }

    private static List<BlockPos> body(ForestOrgans.Organ o) {
        List<BlockPos> out = new ArrayList<>();
        if (o.fusion == null) return out;
        BlockPos a = o.anchor();
        for (long e : o.fusion) {
            int m = OrganBlueprint.material(e);
            if (m == OrganBlueprint.M_CARVE || m == OrganBlueprint.M_COVER) continue;
            out.add(a.offset(OrganBlueprint.dx(e), OrganBlueprint.dy(e), OrganBlueprint.dz(e)));
        }
        return out;
    }

    /** 二段構え: 記録の中は自然のブロック以外が人工、記録の外は建材だけ。自然のタグの石(バイオーム追加の石の代わりに凝灰岩)・原木・器官は人工でない。 */
    @GameTest(template = "gametest_empty7", batch = "takeover", timeoutTicks = 100)
    public static void artificialIsTwoTier(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        StructureFusionGameTests.Site s = StructureFusionGameTests.prepare(level, 6_405_200, 6_400_000);
        List<BlockPos> placed = new ArrayList<>();
        try {
            StructureFusionGameTests.register(s, BuiltinStructures.VILLAGE_PLAINS, List.of(s.house));
            BlockPos wall = new BlockPos(s.bx + 2, s.fy + 2, s.bz), corner = new BlockPos(s.bx, s.fy + 2, s.bz);
            BlockPos tuffIn = new BlockPos(s.bx + 4, s.fy + 1, s.bz + 3), floorIn = new BlockPos(s.bx + 3, s.fy, s.bz + 3);
            level.setBlock(tuffIn, Blocks.TUFF.defaultBlockState(), 2);
            placed.add(tuffIn);
            helper.assertTrue(Takeover.isArtificial(level, wall, level.getBlockState(wall)), "記録の中の丸石の壁が人工でない");
            helper.assertTrue(!Takeover.isArtificial(level, corner, level.getBlockState(corner)), "記録の中の原木の柱を人工と数えた(原木は自然)");
            helper.assertTrue(!Takeover.isArtificial(level, tuffIn, level.getBlockState(tuffIn)), "記録の中の自然の石(凝灰岩)を人工と数えた");
            helper.assertTrue(!Takeover.isArtificial(level, floorIn, level.getBlockState(floorIn)), "記録の中の石の床(地形)を人工と数えた");
            BlockPos out1 = new BlockPos(s.bx - 15, s.fy + 1, s.bz - 15), out2 = out1.east(2), out3 = out1.east(4), out4 = out1.east(6);
            level.setBlock(out1, Blocks.OAK_PLANKS.defaultBlockState(), 2);
            level.setBlock(out2, Blocks.STONE_BRICKS.defaultBlockState(), 2);
            level.setBlock(out3, Blocks.TUFF.defaultBlockState(), 2);
            level.setBlock(out4, Blocks.SMOOTH_BASALT.defaultBlockState(), 2);
            placed.addAll(List.of(out1, out2, out3, out4));
            helper.assertTrue(Takeover.isArtificial(level, out1, level.getBlockState(out1)), "記録の外の板(建材)が人工でない");
            helper.assertTrue(Takeover.isArtificial(level, out2, level.getBlockState(out2)), "記録の外の石レンガ(建材)が人工でない");
            helper.assertTrue(!Takeover.isArtificial(level, out3, level.getBlockState(out3)), "記録の外の自然の石を人工と数えた");
            helper.assertTrue(!Takeover.isArtificial(level, out4, level.getBlockState(out4)), "記録の外の建材でない石を人工と数えた(記録の外は建材のタグだけ)");
            BlockState sac = OrganBlocks.ORGAN_SAC.get().defaultBlockState();
            helper.assertTrue(!Takeover.maybeArtificial(sac), "器官の嚢を人工と数えた");
        } finally {
            for (BlockPos p : placed) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
            s.cleanup();
        }
        helper.succeed();
    }

    /** 器官は壁・屋根から斜め・上へ飛び出し、芯は外の空気に触れる。家の扉は塞いでよい。建たないうちに建物のブロックは穴以外変わらない。 */
    @GameTest(template = "gametest_empty7", batch = "takeover", timeoutTicks = 200)
    public static void organSproutsFromWallWithReachableCore(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        StructureFusionGameTests.Site s = StructureFusionGameTests.prepare(level, 6_405_600, 6_400_000);
        ForestOrgans data = ForestOrgans.get(level);
        List<Integer> planted = new ArrayList<>();
        try {
            StructureFusionGameTests.register(s, BuiltinStructures.VILLAGE_PLAINS, List.of(s.house));
            Random rnd = new Random(11L);
            Takeover.Face wall = new Takeover.Face(s.bx, s.fy + 3, s.bz + 3, -1, 0, 0);
            Takeover.Face roof = new Takeover.Face(s.bx + 4, s.fy + 5, s.bz + 3, 0, 1, 0);
            for (Takeover.Face f : List.of(wall, roof)) {
                for (boolean big : new boolean[]{false, true}) {
                    ForestOrgans.Organ o = Takeover.plant(level, data, f, big, rnd);
                    planted.add(o.id);
                    int n = OrganWorld.buildNow(level, data, o);
                    helper.assertTrue(n > 0 && o.fusion != null, "乗っ取りの器官が建たない(" + (big ? "大" : "小") + "・面 " + f + ")");
                    List<BlockPos> cells = body(o);
                    boolean reach = false;
                    int up = 0;
                    BlockPos a = o.anchor();
                    for (long e : o.fusion) {
                        if (OrganBlueprint.material(e) != OrganBlueprint.M_CORE) continue;
                        BlockPos q = a.offset(OrganBlueprint.dx(e), OrganBlueprint.dy(e), OrganBlueprint.dz(e));
                        if (!level.getBlockState(q).is(OrganBlocks.ORGAN_CORE.get())) continue;
                        if (q.getY() > f.y()) up++;
                        for (Direction d : Direction.values()) if (level.getBlockState(q.relative(d)).isAir()) reach = true;
                    }
                    helper.assertTrue(reach, "芯が外の空気に触れない(" + (big ? "大" : "小") + ")");
                    helper.assertTrue(up > 0, "芯が根元より上に無い(斜め・上へ飛び出していない)");
                    helper.assertTrue(!cells.isEmpty(), "形が空");
                }
            }
        } finally {
            for (int id : planted) data.remove(id);
            StructureFusionGameTests.clearBox(level, new BoundingBox(s.bx - 30, s.fy + 1, s.bz - 30, s.bx + 40, s.fy + 60, s.bz + 40));
            s.cleanup();
        }
        helper.succeed();
    }

    /** 芯を壊すと、その器官だけが枯れ、伸びた物(茎・嚢・蔦)が剥がれる。すぐそばの別の器官は枯れない。 */
    @GameTest(template = "gametest_empty7", batch = "takeover", timeoutTicks = 300)
    public static void breakingCoreWithersOnlyItsOrgan(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        StructureFusionGameTests.Site s = StructureFusionGameTests.prepare(level, 6_406_000, 6_400_000);
        ForestOrgans data = ForestOrgans.get(level);
        List<Integer> planted = new ArrayList<>();
        try {
            StructureFusionGameTests.register(s, BuiltinStructures.VILLAGE_PLAINS, List.of(s.house));
            Random rnd = new Random(5L);
            ForestOrgans.Organ a = Takeover.plant(level, data, new Takeover.Face(s.bx + 2, s.fy + 5, s.bz + 2, 0, 1, 0), false, rnd);
            ForestOrgans.Organ b = Takeover.plant(level, data, new Takeover.Face(s.bx + 7, s.fy + 5, s.bz + 4, 0, 1, 0), false, rnd);
            planted.add(a.id);
            planted.add(b.id);
            helper.assertTrue(OrganWorld.buildNow(level, data, a) > 0 && OrganWorld.buildNow(level, data, b) > 0, "器官が建たない");
            BlockPos core = null;
            BlockPos an = a.anchor();
            for (long e : a.fusion) {
                if (OrganBlueprint.material(e) != OrganBlueprint.M_CORE) continue;
                BlockPos q = an.offset(OrganBlueprint.dx(e), OrganBlueprint.dy(e), OrganBlueprint.dz(e));
                if (level.getBlockState(q).is(OrganBlocks.ORGAN_CORE.get())) {
                    core = q;
                    break;
                }
            }
            helper.assertTrue(core != null, "芯が無い");
            level.destroyBlock(core, false);
            helper.assertTrue(a.state == ForestOrgans.State.WITHERED, "芯を壊しても枯れない");
            helper.assertTrue(b.state != ForestOrgans.State.WITHERED, "そばの別の器官まで枯れた");
            a.witheredAt = PhaseData.clockNow(level) - (long) (StructureFusion.table().vineFadeSeconds * 20.0D) - 40L;
            for (int k = 0; k < 600 && !a.fusionCleaned; k++) OrganWorld.step(level, data, a, PhaseData.clockNow(level), 100000);
            helper.assertTrue(a.fusionCleaned, "枯れた後に片付けない");
            int left = 0;
            for (BlockPos q : body(a)) if (modBlock(level.getBlockState(q)) && !body(b).contains(q)) left++;
            helper.assertTrue(left == 0, "枯れた器官から伸びた物が残る: " + left);
        } finally {
            for (int id : planted) data.remove(id);
            StructureFusionGameTests.clearBox(level, new BoundingBox(s.bx - 30, s.fy + 1, s.bz - 30, s.bx + 40, s.fy + 60, s.bz + 40));
            s.cleanup();
        }
        helper.succeed();
    }

    /** 押し返す道具(帯)の効く所では芽吹かない。チャンクの人工の面が全部帯の列なら、器官を植えない。 */
    @GameTest(template = "gametest_empty7", batch = "takeover", timeoutTicks = 100)
    public static void noSproutWherePushBackGuards(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        StructureFusionGameTests.Site s = StructureFusionGameTests.prepare(level, 6_406_400, 6_400_000);
        ForestOrgans data = ForestOrgans.get(level);
        List<BlockPos> placed = new ArrayList<>();
        int before = data.organs().size();
        try {
            StructureFusionGameTests.clearBox(level, new BoundingBox(s.house.minX() - 1, s.fy + 1, s.house.minZ() - 1, s.house.maxX() + 1, s.house.maxY() + 1, s.house.maxZ() + 1));
            BlockPos p = new BlockPos(s.bx + 4, s.fy + 1, s.bz + 3);
            level.setBlock(p, Blocks.OAK_PLANKS.defaultBlockState(), 2);
            placed.add(p);
            BlockState band = ModPushBack.ASH_LIME_BAND.get().defaultBlockState();
            for (BlockPos q : List.of(p.above(), p.offset(1, 1, 0), p.offset(-1, 1, 0), p.offset(0, 1, 1), p.offset(0, 1, -1))) {
                level.setBlock(q, band, 2);
                placed.add(q);
            }
            RingWards.resetCooldownForTests(level);
            Takeover.Visit v = Takeover.visit(level, p.getX() >> 4, p.getZ() >> 4, new Random(3L), StructureFusion.table(), true);
            helper.assertTrue(v.planted() == null, "帯の効く所に器官を植えた: " + v.why());
            helper.assertTrue(data.organs().size() == before, "器官が増えた");
        } finally {
            for (BlockPos q : placed) level.setBlock(q, Blocks.AIR.defaultBlockState(), 2);
            RingWards.clearForTests(level);
            s.cleanup();
        }
        helper.succeed();
    }

    /** 構造物の中もふつうの汚染(覆いだけの決め A を取り消した)。中身のあるブロック(チェスト)は今どおり殻にして止める。 */
    @GameTest(template = "gametest_empty7", batch = "takeover", timeoutTicks = 100)
    public static void structureCorruptsNormallyAndHusksChests(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        StructureFusionGameTests.Site s = StructureFusionGameTests.prepare(level, 6_406_800, 6_400_000);
        try {
            StructureFusionGameTests.register(s, BuiltinStructures.VILLAGE_PLAINS, List.of(s.house));
            RandomSource r = RandomSource.create(2L);
            BlockPos dirt = new BlockPos(s.bx + 3, s.fy + 1, s.bz + 3), chest = new BlockPos(s.bx + 5, s.fy + 1, s.bz + 3);
            level.setBlock(dirt, Blocks.DIRT.defaultBlockState(), 2);
            level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 2);
            BlockState d0 = level.getBlockState(dirt);
            helper.assertTrue(CorruptionRegistry.corrupt(level, dirt, d0, r) != d0, "構造物の中の土が汚染されない");
            BlockState c0 = level.getBlockState(chest);
            helper.assertTrue(CorruptionRegistry.corrupt(level, chest, c0, r) != c0, "構造物の中のチェストが殻にならない");
            BlockPos plank = new BlockPos(s.bx + 3, s.fy + 5, s.bz + 3);
            BlockState p0 = level.getBlockState(plank);
            BlockState p1 = CorruptionRegistry.corrupt(level, plank, p0, r);
            helper.assertTrue(p1 != p0 || OvergrownLayerData.isCovered(level, plank), "構造物の中の板が汚染されない(置き換えも覆いもない)");
            OvergrownLayerData.uncover(level, plank);
        } finally {
            s.cleanup();
        }
        helper.succeed();
    }
}
