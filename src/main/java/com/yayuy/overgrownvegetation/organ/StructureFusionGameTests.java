package com.yayuy.overgrownvegetation.organ;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.CorruptionArch;
import com.yayuy.overgrownvegetation.corruption.OvergrownLayerData;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import com.yayuy.overgrownvegetation.worldgen.fusion.FusionTable;
import com.yayuy.overgrownvegetation.worldgen.fusion.StructureFusion;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 構造物と森の融合 段 1(2026-11-19)。テスト場は狭いので、遠くの空き地に小さな家(丸石の壁・原木の柱・板の屋根・扉・窓)を建て、
 * その家を 1 つの部品とする構造物の記録(バニラの村の構造物)をチャンクに直に足す({@code /place structure} は記録を残さないため)。
 * どのテストも 1 tick の中で作って確かめて片付ける(読み込んだチャンクが降りる前に)。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class StructureFusionGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(StructureFusionGameTests.class);
        }

        private Registration() {
        }
    }

    private StructureFusionGameTests() {
    }

    // ---------------------------------------------------------------- 場の用意

    /** 箱だけを持つ部品(テスト用。置く処理は無い)。 */
    static final class TestPiece extends StructurePiece {
        TestPiece(BoundingBox box) {
            super(StructurePieceType.NETHER_FOSSIL, 0, box);
        }

        @Override
        protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) {
        }

        @Override
        public void postProcess(WorldGenLevel level, StructureManager structureManager, ChunkGenerator generator, RandomSource random,
                                BoundingBox box, ChunkPos chunkPos, BlockPos pos) {
        }
    }

    /** 作った場(片付けに使う)。 */
    static final class Site {
        final ServerLevel level;
        final int bx, bz, fy;
        final BoundingBox house;
        final BlockPos door;
        final List<Runnable> undo = new ArrayList<>();

        Site(ServerLevel level, int bx, int bz, int fy) {
            this.level = level;
            this.bx = bx;
            this.bz = bz;
            this.fy = fy;
            this.house = new BoundingBox(bx, fy + 1, bz, bx + 8, fy + 5, bz + 6);
            this.door = new BlockPos(bx + 4, fy + 1, bz + 6);
        }

        void cleanup() {
            for (int i = undo.size() - 1; i >= 0; i--) undo.get(i).run();
            StructureFusion.invalidate(level);
        }
    }

    static final int R = 34;

    /** 空き地(石の床 y=fy、上は空気)と家を作る。構造物の記録はまだ足さない。 */
    static Site prepare(ServerLevel level, int bx, int bz) {
        int fy = 120;
        Site s = new Site(level, bx, bz, fy);
        for (int cx = (bx - R - 24) >> 4; cx <= (bx + R + 24) >> 4; cx++) {
            for (int cz = (bz - R - 24) >> 4; cz <= (bz + R + 24) >> 4; cz++) level.getChunk(cx, cz); // 融合の判定は器官のまわり 40 ブロック弱の読み込みを待つ
        }
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = bx - R; x <= bx + R; x++) {
            for (int z = bz - R; z <= bz + R; z++) {
                level.setBlock(p.set(x, fy, z), Blocks.STONE.defaultBlockState(), 2);
                level.setBlock(p.set(x, fy - 1, z), Blocks.STONE.defaultBlockState(), 2);
                for (int y = fy + 1; y <= fy + 30; y++) {
                    OvergrownLayerData.uncover(level, p.set(x, y, z)); // 前の回(途中で落ちた回)の覆いが残っていることがある
                    if (!level.getBlockState(p.set(x, y, z)).isAir()) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
        BoundingBox h = s.house;
        for (int x = h.minX(); x <= h.maxX(); x++) {
            for (int z = h.minZ(); z <= h.maxZ(); z++) {
                boolean edgeX = x == h.minX() || x == h.maxX(), edgeZ = z == h.minZ() || z == h.maxZ();
                for (int y = h.minY(); y <= h.maxY() - 1; y++) {
                    if (!edgeX && !edgeZ) continue;
                    BlockState b = edgeX && edgeZ ? Blocks.OAK_LOG.defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState();
                    level.setBlock(p.set(x, y, z), b, 2);
                }
                level.setBlock(p.set(x, h.maxY(), z), Blocks.OAK_PLANKS.defaultBlockState(), 2);
            }
        }
        level.setBlock(new BlockPos(h.minX(), fy + 3, bz + 3), Blocks.GLASS.defaultBlockState(), 2); // 窓
        BlockState door = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.SOUTH);
        level.setBlock(s.door, door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER), 2 | 16);
        level.setBlock(s.door.above(), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 2 | 16);
        return s;
    }

    /** 構造物の記録を直に足す(開始のチャンクに開始、箱にかかるチャンクに参照)。片付けも積む。 */
    static void register(Site s, net.minecraft.resources.ResourceKey<Structure> key, List<BoundingBox> pieces) {
        ServerLevel level = s.level;
        Structure structure = level.registryAccess().registryOrThrow(Registries.STRUCTURE).getOrThrow(key);
        BoundingBox first = pieces.get(0);
        ChunkPos start = new ChunkPos(first.minX() >> 4, first.minZ() >> 4);
        List<StructurePiece> ps = new ArrayList<>();
        for (BoundingBox b : pieces) ps.add(new TestPiece(b));
        StructureStart ss = new StructureStart(structure, start, 0, new PiecesContainer(ps));
        LevelChunk sc = level.getChunk(start.x, start.z);
        sc.setStartForStructure(structure, ss);
        List<ChunkAccess> refd = new ArrayList<>();
        BoundingBox all = ss.getBoundingBox();
        for (int cx = all.minX() >> 4; cx <= all.maxX() >> 4; cx++) {
            for (int cz = all.minZ() >> 4; cz <= all.maxZ() >> 4; cz++) {
                ChunkAccess c = level.getChunk(cx, cz);
                c.addReferenceForStructure(structure, start.toLong());
                refd.add(c);
            }
        }
        s.undo.add(() -> {
            Map<Structure, StructureStart> starts = new HashMap<>(sc.getAllStarts());
            starts.remove(structure);
            sc.setAllStarts(starts);
            for (ChunkAccess c : refd) {
                Map<Structure, it.unimi.dsi.fastutil.longs.LongSet> refs = new HashMap<>(c.getAllReferences());
                refs.remove(structure);
                c.setAllReferences(refs);
            }
        });
        StructureFusion.invalidate(level);
    }

    static Map<BlockPos, BlockState> snapshot(ServerLevel level, BoundingBox b) {
        Map<BlockPos, BlockState> m = new HashMap<>();
        for (BlockPos p : BlockPos.betweenClosed(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ())) {
            m.put(p.immutable(), level.getBlockState(p));
        }
        return m;
    }

    /** 箱の中で、前に空気でなかったマスが変わっていないか(変わった最初のマスを返す。無ければ null)。 */
    static String changedSolid(ServerLevel level, Map<BlockPos, BlockState> before) {
        for (Map.Entry<BlockPos, BlockState> e : before.entrySet()) {
            if (e.getValue().isAir()) continue;
            BlockState now = level.getBlockState(e.getKey());
            if (now.getBlock() != e.getValue().getBlock()) return e.getKey() + " " + e.getValue() + " → " + now;
        }
        return null;
    }

    // ---------------------------------------------------------------- 判定の口

    /** 地表の部品は守る・タグで外した構造物と地下の部品は守らない・箱の外は守らない。 */
    @GameTest(template = "gametest_empty7", batch = "fusion", timeoutTicks = 100)
    public static void lookupHonoursTagAndUnderground(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Site s = prepare(level, 6_400_000, 6_400_000);
        try {
            register(s, BuiltinStructures.VILLAGE_PLAINS, List.of(s.house, new BoundingBox(s.bx + 20, s.fy - 20, s.bz, s.bx + 26, s.fy - 15, s.bz + 6)));
            BoundingBox strong = new BoundingBox(s.bx - 20, s.fy + 1, s.bz, s.bx - 14, s.fy + 4, s.bz + 6);
            register(s, BuiltinStructures.STRONGHOLD, List.of(strong));
            helper.assertTrue(StructureFusion.isProtected(level, new BlockPos(s.bx, s.fy + 2, s.bz)), "地表の部品(家の柱)を守らない");
            helper.assertTrue(StructureFusion.isProtected(level, new BlockPos(s.bx - 1, s.fy + 2, s.bz)), "部品の箱の余白 1 を守らない");
            helper.assertTrue(StructureFusion.state(level, new BlockPos(s.bx - 3, s.fy + 2, s.bz)) == StructureFusion.NONE, "箱の外を守る");
            helper.assertTrue(StructureFusion.state(level, new BlockPos(s.bx + 22, s.fy - 17, s.bz + 3)) == StructureFusion.NONE, "地下の部品を守る");
            helper.assertTrue(StructureFusion.state(level, new BlockPos(s.bx - 17, s.fy + 2, s.bz + 3)) == StructureFusion.NONE, "タグで外した要塞を守る");
            StructureFusion.Hit hit = StructureFusion.at(level, new BlockPos(s.bx + 1, s.fy + 2, s.bz + 1));
            helper.assertTrue(hit != null, "家の部品が引けない");
            // 全体の箱は地表の部品だけで測る(地下の部品で水増ししない)
            helper.assertTrue(hit.site().surfaceWhole().equals(s.house), "地表の全体の箱が家でない: " + hit.site().surfaceWhole());
            helper.assertTrue(hit.pieceClass() == StructureFusion.pieceClass(s.house) && hit.wholeClass() == StructureFusion.wholeClass(s.house),
                    "大きさの段が箱から測った値と違う");
            helper.assertTrue(StructureFusion.anyNear(level, s.bx - 12, s.bz + 3, 20.0D), "そばの構造物を巨大樹の避けで拾わない");
            helper.assertTrue(!StructureFusion.anyNear(level, s.bx - 12, s.bz - 20, 4.0D), "構造物の無い所で巨大樹を避ける");
        } finally {
            s.cleanup();
        }
        helper.succeed();
    }

    /** 大きさの段: 長い辺 = 横の長い辺か高さ。境目は design の値から引く。全体は床面積でも大。 */
    @GameTest(template = "gametest_empty7", batch = "fusion_pure", timeoutTicks = 20)
    public static void sizeClassesFollowDesign(GameTestHelper helper) {
        FusionTable t = StructureFusion.table();
        helper.assertTrue(StructureFusion.pieceClass(new BoundingBox(0, 0, 0, t.pieceMediumSpan - 2, 2, 2)) == StructureFusion.SizeClass.SMALL, "小さい部品が小でない");
        helper.assertTrue(StructureFusion.pieceClass(new BoundingBox(0, 0, 0, t.pieceMediumSpan - 1, 2, 2)) == StructureFusion.SizeClass.MEDIUM, "境目ちょうどが中でない");
        // 塔: 横は細いが高い → 高さで段が上がる
        helper.assertTrue(StructureFusion.pieceClass(new BoundingBox(0, 0, 0, 3, t.pieceLargeSpan - 1, 3)) == StructureFusion.SizeClass.LARGE, "高い塔が大でない");
        helper.assertTrue(StructureFusion.wholeClass(new BoundingBox(0, 0, 0, t.wholeMediumSpan - 2, 3, 3)) == StructureFusion.SizeClass.SMALL, "小さい構造物が小でない");
        helper.assertTrue(StructureFusion.wholeClass(new BoundingBox(0, 0, 0, t.wholeLargeSpan - 1, 3, 3)) == StructureFusion.SizeClass.LARGE, "長い構造物が大でない");
        int side = (int) Math.ceil(Math.sqrt(t.wholeLargeArea));
        if (side < t.wholeLargeSpan) {
            helper.assertTrue(StructureFusion.wholeClass(new BoundingBox(0, 0, 0, side - 1, 3, side - 1)) == StructureFusion.SizeClass.LARGE, "広い構造物が床面積で大にならない");
        }
        helper.succeed();
    }

    // ---------------------------------------------------------------- 壊さない

    /** 根のアーチ・器官の置き方・木払いが、構造物の部品の箱のブロックを変えない(空気のマスには置ける)。 */
    @GameTest(template = "gametest_empty7", batch = "fusion", timeoutTicks = 100)
    public static void forestDoesNotReplaceStructureBlocks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Site s = prepare(level, 6_400_400, 6_400_000);
        ForestOrgans data = ForestOrgans.get(level);
        List<Integer> planted = new ArrayList<>();
        try {
            register(s, BuiltinStructures.VILLAGE_PLAINS, List.of(s.house));
            Map<BlockPos, BlockState> before = snapshot(level, s.house.inflatedBy(1));
            // 根のアーチ: 家をまたぐ弧は架けない
            boolean arch = CorruptionArch.build(level, new BlockPos(s.bx - 4, s.fy + 1, s.bz + 3), new BlockPos(s.bx + 12, s.fy + 1, s.bz + 3), 2, 0,
                    Blocks.OAK_LOG.defaultBlockState(), new LongOpenHashSet(), null, null);
            helper.assertTrue(!arch, "家をまたぐ根のアーチを架けた");
            // 器官の置き方: 壁に苔・掘る は置かない、部屋の中の空気には置ける(決まりは「空気だけ」)
            BlockPos wall = new BlockPos(s.bx + 2, s.fy + 2, s.bz);
            OrganWorld.place(level, wall, OrganBlueprint.pack(0, 0, 0, OrganBlueprint.M_MOSS, 0), true);
            OrganWorld.place(level, wall.above(), OrganBlueprint.pack(0, 0, 0, OrganBlueprint.M_CARVE, 0), true);
            BlockPos room = new BlockPos(s.bx + 3, s.fy + 2, s.bz + 3);
            OrganWorld.place(level, room, OrganBlueprint.pack(0, 0, 0, OrganBlueprint.M_MOSS, 0), true);
            helper.assertTrue(!level.getBlockState(room).isAir(), "構造物の箱の空気のマスに置けない");
            level.setBlock(room, Blocks.AIR.defaultBlockState(), 2);
            // 木払い: 家のそばの器官は家の原木の柱を払わない
            ForestOrgans.Organ o = data.plant(level, OrganKind.SAC, true, 0, 5, 0, s.bx - 9, s.bz + 3);
            planted.add(o.id);
            o.y = s.fy;
            o.fusionChecked = true; // ふつうの形のまま木払いだけ見る
            OrganClearing.clearNow(level, o, OrganWorld.blueprintNow(o));
            String changed = changedSolid(level, before);
            helper.assertTrue(changed == null, "構造物のブロックが変わった: " + changed);
        } finally {
            for (int id : planted) data.remove(id);
            s.cleanup();
        }
        helper.succeed();
    }

    /**
     * 汚染は構造物の部品の箱の中の建材を置き換えず、記録で覆う(11-19 決め A)。地面(土・石)は箱の中でも外と同じく置き換わる(11-19 ユーザー決定「地面は汚染する」)。中身のあるチェストは今まで通り止める。
     * 箱の外の石は今まで通り置き換わる。
     */
    @GameTest(template = "gametest_empty7", batch = "fusion", timeoutTicks = 100)
    public static void corruptionOnlyCoversInsideStructure(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Site s = prepare(level, 6_401_600, 6_400_000);
        try {
            register(s, BuiltinStructures.VILLAGE_PLAINS, List.of(s.house));
            RandomSource r = RandomSource.create(1L);
            BlockPos wall = new BlockPos(s.bx + 2, s.fy + 2, s.bz), plank = new BlockPos(s.bx + 3, s.fy + 5, s.bz + 3);
            BlockPos dirt = new BlockPos(s.bx + 3, s.fy + 1, s.bz + 3), chest = new BlockPos(s.bx + 5, s.fy + 1, s.bz + 3);
            BlockPos stoneIn = new BlockPos(s.bx + 4, s.fy + 1, s.bz + 3);
            level.setBlock(dirt, Blocks.DIRT.defaultBlockState(), 2);
            level.setBlock(stoneIn, Blocks.STONE.defaultBlockState(), 2);
            helper.assertTrue(StructureFusion.isProtected(level, dirt) && StructureFusion.isProtected(level, stoneIn), "土・石の置き場が守る範囲の中でない(テストの作りの誤り)");
            level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 2);
            for (BlockPos p : List.of(wall, plank)) {
                BlockState before = level.getBlockState(p);
                BlockState after = com.yayuy.overgrownvegetation.corruption.CorruptionRegistry.corrupt(level, p, before, r);
                helper.assertTrue(after == before, "構造物の中の建材 " + before + " を置き換えようとした: " + after);
                helper.assertTrue(OvergrownLayerData.isCovered(level, p), "構造物の中の建材 " + before + " が覆われない");
            }
            for (BlockPos p : List.of(dirt, stoneIn)) {
                BlockState before = level.getBlockState(p);
                BlockState after = com.yayuy.overgrownvegetation.corruption.CorruptionRegistry.corrupt(level, p, before, r);
                helper.assertTrue(after != before, "構造物の中の地面 " + before + " が置き換わらない(地面は汚染する)");
                helper.assertTrue(!OvergrownLayerData.isCovered(level, p), "構造物の中の地面 " + before + " を覆った(覆わず置き換える)");
            }
            BlockState chestAfter = com.yayuy.overgrownvegetation.corruption.CorruptionRegistry.corrupt(level, chest, level.getBlockState(chest), r);
            helper.assertTrue(chestAfter != level.getBlockState(chest), "構造物の中のチェストが今まで通り止まらない");
            BlockPos outside = new BlockPos(s.bx - 10, s.fy, s.bz - 10);
            BlockState stone = level.getBlockState(outside);
            BlockState stoneAfter = com.yayuy.overgrownvegetation.corruption.CorruptionRegistry.corrupt(level, outside, stone, r);
            helper.assertTrue(stoneAfter != stone, "構造物の外の石が置き換わらない");
            for (BlockPos p : List.of(wall, plank, dirt, stoneIn)) OvergrownLayerData.uncover(level, p);
        } finally {
            s.cleanup();
        }
        helper.succeed();
    }

    // ---------------------------------------------------------------- 器官の融合

    /** 壁の根元から斜めに生える(柄の付け根は壁に接し、芯は壁から離れて高い所)。建物のブロックは変わらない。 */
    @GameTest(template = "gametest_empty7", batch = "fusion", timeoutTicks = 100)
    public static void fusedOrganGrowsAslantFromWall(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Site s = prepare(level, 6_400_800, 6_400_000);
        try {
            register(s, BuiltinStructures.VILLAGE_PLAINS, List.of(s.house));
            FusionTable.Json j = new FusionTable.Json();
            j.anchors = new FusionTable.AnchorJson();
            j.anchors.small = new double[]{1, 0, 0};
            j.anchors.medium = new double[]{1, 0, 0};
            j.anchors.large = new double[]{1, 0, 0};
            FusionTable wallOnly = FusionTable.fromJson(j);
            ForestOrgans.Organ o = new ForestOrgans.Organ(-77, OrganKind.TOWER, true, 0, 4242, 0, s.bx - 6, s.bz + 3, 0L);
            o.y = s.fy;
            StructureFusion.Query q = StructureFusion.near(level, s.house.inflatedBy(20));
            long[] plan = OrganFusion.plan(level, o, q.hits(), wallOnly);
            helper.assertTrue(plan != null && plan.length > 0, "壁の根元で器官を作れない");
            OrganBlueprint bp = OrganBlueprint.fromEntries(plan, o.seed);
            BlockPos a = o.anchor();
            BlockPos root = a.offset(bp.entrance);
            helper.assertTrue(s.house.inflatedBy(1).isInside(root), "柄の付け根が壁に接していない: " + root);
            BlockPos core = a.offset(bp.coreCenter);
            double hr = Math.hypot(core.getX() - root.getX(), core.getZ() - root.getZ());
            helper.assertTrue(hr >= 2.0D, "芯が付け根の真上(まっすぐ)で斜めでない: 横 " + hr);
            helper.assertTrue(core.getY() > root.getY(), "芯が付け根より高くない");
            helper.assertTrue(!s.house.isInside(core), "芯が家の中に入った");
            for (long e : plan) {
                if (OrganBlueprint.material(e) == OrganBlueprint.M_COVER) continue;
                BlockPos p = a.offset(OrganBlueprint.dx(e), OrganBlueprint.dy(e), OrganBlueprint.dz(e));
                helper.assertTrue(level.getBlockState(p).isAir(), "空気でないマスに置く計画: " + p + " " + level.getBlockState(p));
            }
        } finally {
            s.cleanup();
        }
        helper.succeed();
    }

    /**
     * 構造物のそばの小さい版は融合の形で建つ: 触手は空気のマスだけ・壁を覆う・入口から中へ歩いて入れる・建物のブロックは変わらない。
     * 芯を壊して枯れると、しばらくして触手と脈が消え、器官が覆ったマスが剥がれる。
     */
    @GameTest(template = "gametest_empty7", batch = "fusion", timeoutTicks = 100)
    public static void fusedOrganWrapsKeepsDoorAndFades(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Site s = prepare(level, 6_401_200, 6_400_000);
        ForestOrgans data = ForestOrgans.get(level);
        List<Integer> planted = new ArrayList<>();
        try {
            register(s, BuiltinStructures.VILLAGE_PLAINS, List.of(s.house));
            Map<BlockPos, BlockState> before = snapshot(level, s.house.inflatedBy(1));
            ForestOrgans.Organ o = data.plant(level, OrganKind.SAC, true, 0, 9001, 0, s.bx - 7, s.bz + 3);
            planted.add(o.id);
            int n = OrganWorld.buildNow(level, data, o);
            helper.assertTrue(n > 0 && o.fusion != null, "構造物のそばの小さい版が融合の形にならない(置いた " + n + ")");
            String changed = changedSolid(level, before);
            helper.assertTrue(changed == null, "構造物のブロックが変わった: " + changed);
            BlockPos a = o.anchor();
            int vines = 0;
            List<BlockPos> vinePos = new ArrayList<>();
            for (long e : o.fusion) {
                int m = OrganBlueprint.material(e);
                if (m == OrganBlueprint.M_COVER) continue;
                BlockPos p = a.offset(OrganBlueprint.dx(e), OrganBlueprint.dy(e), OrganBlueprint.dz(e));
                helper.assertTrue(!(before.containsKey(p) && !before.get(p).isAir()), "建物のマスに置く計画: " + p);
                if (m == OrganBlueprint.M_VINE) {
                    vines++;
                    vinePos.add(p);
                }
            }
            helper.assertTrue(vines > 0, "蔦の触手が無い");
            int coverMarks = 0, alreadyCovered = 0;
            for (long e : o.fusion) {
                if (OrganBlueprint.material(e) != OrganBlueprint.M_COVER) continue;
                coverMarks++;
                if (OvergrownLayerData.isCovered(level, a.offset(OrganBlueprint.dx(e), OrganBlueprint.dy(e), OrganBlueprint.dz(e)))) alreadyCovered++;
            }
            helper.assertTrue(!o.covered.isEmpty(), "触手・脈が壁を覆わない(覆いの印 " + coverMarks + "・覆われている " + alreadyCovered + "・触手 " + vines + "・列 " + o.fusion.length + ")");
            helper.assertTrue(walkIn(level, s), "入口から中へ歩いて入れない");
            int housedBefore = 0;
            for (Map.Entry<BlockPos, BlockState> e : before.entrySet()) {
                if (!e.getValue().isAir() && OvergrownLayerData.isCovered(level, e.getKey())) housedBefore++;
            }
            int mine = o.covered.size();
            StringBuilder strangers = new StringBuilder();
            for (Map.Entry<BlockPos, BlockState> e : before.entrySet()) {
                if (!e.getValue().isAir() && OvergrownLayerData.isCovered(level, e.getKey()) && !o.covered.contains(e.getKey().asLong()) && strangers.length() < 400) {
                    strangers.append(" ").append(e.getKey().getX() - s.bx).append(",").append(e.getKey().getY() - s.fy).append(",").append(e.getKey().getZ() - s.bz)
                            .append(" ").append(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(e.getValue().getBlock()).getPath());
                }
            }
            // 枯らす → 時間を進めた扱いで片付け
            data.wither(level, o, "テスト");
            o.witheredAt = PhaseData.clockNow(level) - (long) (StructureFusion.table().vineFadeSeconds * 20.0D) - 40L;
            for (int k = 0; k < 200 && !o.fusionCleaned; k++) OrganWorld.step(level, data, o, PhaseData.clockNow(level), 100000);
            helper.assertTrue(o.fusionCleaned, "枯れた後に触手と覆いを片付けない");
            for (BlockPos p : vinePos) helper.assertTrue(!level.getBlockState(p).is(OrganBlocks.ORGAN_VINE.get()), "枯れた後に触手が残る: " + p);
            helper.assertTrue(o.covered.isEmpty(), "覆いの記録が残る");
            int still = 0;
            for (Map.Entry<BlockPos, BlockState> e : before.entrySet()) {
                if (!e.getValue().isAir() && OvergrownLayerData.isCovered(level, e.getKey())) still++;
            }
            helper.assertTrue(still == 0, "枯れた後も家が覆われている: " + still + "(枯れる前 " + housedBefore + "・器官が覚えた " + mine + ")。覚えていない覆い:" + strangers);
            helper.assertTrue(changedSolid(level, before) == null, "片付けで構造物のブロックが変わった");
        } finally {
            for (int id : planted) {
                level.getEntities(OrganEntities.TENTACLE.get(), new net.minecraft.world.phys.AABB(s.bx - R, s.fy - 5, s.bz - R, s.bx + R, s.fy + 40, s.bz + R), e -> true)
                        .forEach(net.minecraft.world.entity.Entity::discard);
                data.remove(id);
            }
            s.cleanup();
        }
        helper.succeed();
    }

    // ---------------------------------------------------------------- 構造物の木(段 2)

    /** 計画で置いた木のマスを空気に戻す(テストの片付け)。 */
    static void clearTree(ServerLevel level, @javax.annotation.Nullable StructureTree.Plan plan) {
        if (plan == null) return;
        for (long p : plan.pos()) {
            BlockPos q = BlockPos.of(p);
            BlockState st = level.getBlockState(q);
            if (st.is(com.yayuy.overgrownvegetation.registry.ModBlocks.OVERGROWN_WOOD.get()) || st.is(com.yayuy.overgrownvegetation.registry.ModBlocks.OVERGROWN_LEAVES.get())) {
                level.setBlock(q, Blocks.AIR.defaultBlockState(), 2 | 16);
            }
        }
    }

    /** 箱の中を空気に。 */
    static void clearBox(ServerLevel level, BoundingBox b) {
        for (BlockPos p : BlockPos.betweenClosed(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ())) {
            if (!level.getBlockState(p).isAir()) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
        }
    }

    /** 屋根のある家(煙突つき)には、てっぺんから生える。根元は煙突でなく屋根の天辺。建物は変わらず、根は地面まで垂れ、扉から入れる。 */
    @GameTest(template = "gametest_empty7", batch = "fusion", timeoutTicks = 100)
    public static void structureTreeGrowsFromRoofTop(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Site s = prepare(level, 6_402_000, 6_400_000);
        StructureTree.Plan plan = null;
        BlockPos chimney = new BlockPos(s.bx + 1, s.fy + 6, s.bz + 1);
        try {
            for (int y = 0; y < 3; y++) level.setBlock(chimney.above(y), Blocks.COBBLESTONE.defaultBlockState(), 2);
            BoundingBox piece = new BoundingBox(s.bx, s.fy + 1, s.bz, s.bx + 8, s.fy + 8, s.bz + 6);
            register(s, BuiltinStructures.VILLAGE_PLAINS, List.of(piece));
            StructureFusion.Site site = StructureFusion.at(level, new BlockPos(s.bx + 1, s.fy + 2, s.bz + 1)).site();
            helper.assertTrue(site.wholeClass() != StructureFusion.SizeClass.LARGE, "家 1 軒が大になった");
            Map<BlockPos, BlockState> before = snapshot(level, piece.inflatedBy(1));
            StructureTree.Result r = StructureTree.plan(level, site, 77L, StructureFusion.table(), null);
            plan = r.plan();
            helper.assertTrue(plan != null, "構造物の木の計画ができない: " + r.why());
            helper.assertTrue("top".equals(plan.mode()), "屋根のある家がてっぺんからにならない: " + plan.mode());
            helper.assertTrue(plan.anchor().getY() == s.fy + 6, "根元が屋根の天辺の上でない: " + plan.anchor());
            helper.assertTrue(!(plan.anchor().getX() == chimney.getX() && plan.anchor().getZ() == chimney.getZ()), "煙突に根を下ろした");
            int trunk = StructureTree.count(plan, StructureTree.TRUNK), roots = StructureTree.count(plan, StructureTree.ROOT);
            helper.assertTrue(trunk > 0 && roots > 0 && StructureTree.count(plan, StructureTree.LEAF) > 0, "幹・根・葉のどれかが無い: 幹 " + trunk + " 根 " + roots);
            int topY = Integer.MIN_VALUE;
            boolean rootOnGround = false;
            for (int i = 0; i < plan.pos().length; i++) {
                BlockPos p = BlockPos.of(plan.pos()[i]);
                byte m = plan.mat()[i];
                if (m == StructureTree.TRUNK) topY = Math.max(topY, p.getY());
                if (m == StructureTree.ROOT && p.getY() <= s.fy + 2 && !piece.isInside(p)) rootOnGround = true;
                if (m == StructureTree.LEAF) {
                    helper.assertTrue(!(p.getX() > s.bx && p.getX() < s.bx + 8 && p.getZ() > s.bz && p.getZ() < s.bz + 6 && p.getY() <= s.fy + 4), "部屋の中に葉: " + p);
                }
            }
            helper.assertTrue(topY - plan.anchor().getY() >= StructureFusion.table().treeTrunkMin, "幹が短い: " + (topY - plan.anchor().getY()));
            helper.assertTrue(rootOnGround, "根が地面まで垂れない");
            int placed = StructureTrees.placeAllNow(level, plan);
            helper.assertTrue(placed > 0, "1 つも置けない");
            String changed = changedSolid(level, before);
            helper.assertTrue(changed == null, "構造物のブロックが変わった: " + changed);
            helper.assertTrue(walkIn(level, s), "入口から中へ歩いて入れない");
        } finally {
            clearTree(level, plan);
            clearBox(level, new BoundingBox(chimney.getX(), chimney.getY(), chimney.getZ(), chimney.getX(), chimney.getY() + 2, chimney.getZ()));
            s.cleanup();
        }
        helper.succeed();
    }

    /** 屋根の無い廃墟(四方の壁だけ)には、中から生えて天辺を抜ける。穴の中の幹は穴に収まり、建物は変わらず、扉から入れる。 */
    @GameTest(template = "gametest_empty7", batch = "fusion", timeoutTicks = 100)
    public static void structureTreeGrowsUpThroughRuin(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Site s = prepare(level, 6_402_400, 6_400_000);
        StructureTree.Plan plan = null;
        // 13×13 の壁だけの廃墟。南の壁の真ん中が家と同じ扉(s.door)
        BoundingBox ruin = new BoundingBox(s.bx - 2, s.fy + 1, s.bz - 6, s.bx + 10, s.fy + 5, s.bz + 6);
        try {
            clearBox(level, new BoundingBox(s.house.minX() - 1, s.fy + 1, s.house.minZ() - 1, s.house.maxX() + 1, s.house.maxY() + 1, s.house.maxZ() + 1)); // 床(fy)は残す
            BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
            for (int x = ruin.minX(); x <= ruin.maxX(); x++) {
                for (int z = ruin.minZ(); z <= ruin.maxZ(); z++) {
                    if (x != ruin.minX() && x != ruin.maxX() && z != ruin.minZ() && z != ruin.maxZ()) continue;
                    for (int y = ruin.minY(); y <= ruin.maxY(); y++) level.setBlock(p.set(x, y, z), Blocks.COBBLESTONE.defaultBlockState(), 2);
                }
            }
            BlockState door = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.SOUTH);
            level.setBlock(s.door, door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER), 2 | 16);
            level.setBlock(s.door.above(), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 2 | 16);
            register(s, BuiltinStructures.VILLAGE_PLAINS, List.of(ruin));
            StructureFusion.Site site = StructureFusion.at(level, new BlockPos(ruin.minX(), s.fy + 2, ruin.minZ())).site();
            Map<BlockPos, BlockState> before = snapshot(level, ruin.inflatedBy(1));
            StructureTree.Result r = StructureTree.plan(level, site, 78L, StructureFusion.table(), null);
            plan = r.plan();
            helper.assertTrue(plan != null, "構造物の木の計画ができない: " + r.why());
            helper.assertTrue("inside".equals(plan.mode()), "屋根の無い廃墟が中からにならない: " + plan.mode());
            BlockPos a = plan.anchor();
            helper.assertTrue(a.getX() > ruin.minX() && a.getX() < ruin.maxX() && a.getZ() > ruin.minZ() && a.getZ() < ruin.maxZ() && a.getY() == s.fy + 1,
                    "根元が廃墟の中の床の上でない: " + a);
            int inHole = 0, above = 0;
            for (int i = 0; i < plan.pos().length; i++) {
                if (plan.mat()[i] != StructureTree.TRUNK) continue;
                BlockPos q = BlockPos.of(plan.pos()[i]);
                if (q.getY() <= ruin.maxY()) {
                    helper.assertTrue(q.getX() > ruin.minX() && q.getX() < ruin.maxX() && q.getZ() > ruin.minZ() && q.getZ() < ruin.maxZ(), "穴の中の幹が壁の外へ出た: " + q);
                    inHole++;
                } else {
                    above++;
                }
            }
            helper.assertTrue(inHole > 0 && above > 0, "幹が穴を抜けない: 穴の中 " + inHole + "・上 " + above);
            StructureTrees.placeAllNow(level, plan);
            String changed = changedSolid(level, before);
            helper.assertTrue(changed == null, "構造物のブロックが変わった: " + changed);
            helper.assertTrue(walkIn(level, s), "入口から中へ歩いて入れない");
        } finally {
            clearTree(level, plan);
            clearBox(level, new BoundingBox(ruin.minX() - 1, s.fy + 1, ruin.minZ() - 1, ruin.maxX() + 1, ruin.maxY() + 1, ruin.maxZ() + 1));
            s.cleanup();
        }
        helper.succeed();
    }

    /** 屋根の上の苗は、構造物に合わせた木になる(幹は屋根から切れずに伸び、根は壁を伝って地面へ。建物は変わらず、扉から入れる)。 */
    @GameTest(template = "gametest_empty7", batch = "fusion", timeoutTicks = 100)
    public static void saplingOnRoofGrowsWholeTree(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Site s = prepare(level, 6_402_800, 6_400_000);
        StructureFusion.Query q = null;
        BlockPos origin = new BlockPos(s.bx + 4, s.fy + 6, s.bz + 3);
        StructureTree.Plan plan = null;
        try {
            register(s, BuiltinStructures.VILLAGE_PLAINS, List.of(s.house));
            Map<BlockPos, BlockState> before = snapshot(level, s.house.inflatedBy(1));
            q = StructureFusion.near(level, new BoundingBox(origin).inflatedBy(14));
            plan = StructureTree.saplingTree(level, origin, q.hits(), 91L, StructureFusion.table());
            helper.assertTrue(plan != null, "屋根の上の苗が育たない");
            // 幹は苗の場所から上へ切れずに続く(浮かない)
            java.util.Set<Integer> trunkYs = new java.util.HashSet<>();
            boolean rootLow = false;
            for (int i = 0; i < plan.pos().length; i++) {
                BlockPos p = BlockPos.of(plan.pos()[i]);
                if (plan.mat()[i] == StructureTree.TRUNK) trunkYs.add(p.getY());
                if (plan.mat()[i] == StructureTree.ROOT && p.getY() <= s.fy + 2 && !s.house.isInside(p)) rootLow = true;
            }
            helper.assertTrue(trunkYs.contains(origin.getY()), "幹が苗の場所から始まらない");
            for (int y = origin.getY(); y <= origin.getY() + plan.height(); y++) helper.assertTrue(trunkYs.contains(y), "幹が途中で切れている: y=" + y);
            helper.assertTrue(rootLow, "根が壁を伝って地面まで垂れない");
            StructureTrees.placeAllNow(level, plan);
            helper.assertTrue(changedSolid(level, before) == null, "建物のブロックが変わった: " + changedSolid(level, before));
            helper.assertTrue(walkIn(level, s), "入口から中へ歩いて入れない");
        } finally {
            clearTree(level, plan);
            s.cleanup();
        }
        helper.succeed();
    }

    /** 部屋の中の苗で天井まで低いなら育てない(欠けて浮いた木を作らない)。木の機能の入口(tryGrow)から確かめる。 */
    @GameTest(template = "gametest_empty7", batch = "fusion", timeoutTicks = 100)
    public static void saplingUnderLowCeilingStaysSapling(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Site s = prepare(level, 6_403_200, 6_400_000);
        BlockPos origin = new BlockPos(s.bx + 2, s.fy + 1, s.bz + 2);
        try {
            register(s, BuiltinStructures.VILLAGE_PLAINS, List.of(s.house));
            Map<BlockPos, BlockState> before = snapshot(level, s.house.inflatedBy(3));
            Boolean r = StructureSaplingTrees.tryGrow(level, origin, RandomSource.create(5L));
            helper.assertTrue(Boolean.FALSE.equals(r), "天井の低い部屋の苗が育った: " + r);
            for (Map.Entry<BlockPos, BlockState> e : before.entrySet()) {
                helper.assertTrue(level.getBlockState(e.getKey()).getBlock() == e.getValue().getBlock(), "育てないはずが何か置いた: " + e.getKey());
            }
        } finally {
            s.cleanup();
        }
        helper.succeed();
    }

    /** 段 2c・2d: 大きい構造物(家 + 小屋 3 つ)には巨大樹を植えず、一番大きい建物に大きい器官、ほかの建物に小さい器官を 1 つずつ。中くらいの木が 1 本。 */
    @GameTest(template = "gametest_empty7", batch = "fusion", timeoutTicks = 200)
    public static void largeStructureGetsOrgansAndMediumTree(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Site s = prepare(level, 6_403_600, 6_400_000);
        List<BoundingBox> pieces = new ArrayList<>();
        ForestOrgans data = ForestOrgans.get(level);
        StructureTrees trees = StructureTrees.get(level);
        java.util.Set<Integer> before = new java.util.HashSet<>();
        for (ForestOrgans.Organ o : data.organs()) before.add(o.id);
        try {
            pieces.add(s.house);
            BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
            int[][] huts = {{-26, -4}, {20, -4}, {-4, 22}};
            for (int[] c : huts) {
                BoundingBox h = new BoundingBox(s.bx + c[0], s.fy + 1, s.bz + c[1], s.bx + c[0] + 5, s.fy + 4, s.bz + c[1] + 5);
                pieces.add(h);
                for (BlockPos q : BlockPos.betweenClosed(h.minX(), h.minY(), h.minZ(), h.maxX(), h.maxY(), h.maxZ())) {
                    boolean edge = q.getX() == h.minX() || q.getX() == h.maxX() || q.getZ() == h.minZ() || q.getZ() == h.maxZ() || q.getY() == h.maxY();
                    if (edge) level.setBlock(p.set(q), Blocks.STONE_BRICKS.defaultBlockState(), 2);
                }
            }
            register(s, BuiltinStructures.VILLAGE_PLAINS, pieces);
            StructureFusion.Site site = StructureFusion.at(level, new BlockPos(s.bx + 1, s.fy + 2, s.bz + 1)).site();
            helper.assertTrue(site.wholeClass() == StructureFusion.SizeClass.LARGE, "村が大でない: " + site.wholeClass());
            int giantsBefore = com.yayuy.overgrownvegetation.worldgen.GiantTreeGrowthQueue.pendingJobs(level);
            int jobsBefore = trees.pendingJobs();
            BoundingBox area = site.surfaceWhole().inflatedBy(StructureFusion.table().treeCrownMax + 26);
            for (int cx = area.minX() >> 4; cx <= area.maxX() >> 4; cx++) {
                for (int cz = area.minZ() >> 4; cz <= area.maxZ() >> 4; cz++) level.getChunk(cx, cz); // 生やし始めはまわりの読み込みを待つ
            }
            StructureTrees.Start r = trees.start(level, site, true);
            helper.assertTrue(r == StructureTrees.Start.STARTED, "生やし始めない: " + r);
            helper.assertTrue(com.yayuy.overgrownvegetation.worldgen.GiantTreeGrowthQueue.pendingJobs(level) == giantsBefore, "大きい構造物に巨大樹を植えた");
            int bigCount = 0, smallCount = 0;
            boolean bigOnHouse = false;
            for (ForestOrgans.Organ o : data.organs()) {
                if (before.contains(o.id)) continue;
                helper.assertTrue(o.onPiece != null, "構造物の器官が建物を覚えていない: #" + o.id);
                helper.assertTrue(o.small || !(o.kind == OrganKind.LEY && o.variant == 0), "大きい器官に井戸が出た");
                if (o.small) smallCount++;
                else {
                    bigCount++;
                    bigOnHouse = s.house.inflatedBy(1).isInside(new BlockPos(o.x, s.house.minY(), o.z));
                }
            }
            helper.assertTrue(bigCount == 1 && bigOnHouse, "一番大きい建物に大きい器官が 1 つ付かない(大 " + bigCount + "・家の上 " + bigOnHouse + ")");
            helper.assertTrue(smallCount == 3, "ほかの建物に小さい器官が 1 つずつ付かない: " + smallCount);
            helper.assertTrue(trees.pendingJobs() - jobsBefore == 1, "中くらいの木が 1 本でない: " + (trees.pendingJobs() - jobsBefore));
        } finally {
            for (ForestOrgans.Organ o : new ArrayList<>(data.organs())) if (!before.contains(o.id)) data.remove(o.id);
            trees.clearForTests();
            for (BoundingBox b : pieces.subList(Math.min(1, pieces.size()), pieces.size())) clearBox(level, b);
            s.cleanup();
        }
        helper.succeed();
    }

    /**
     * 段 2d: 構造物の器官はふつうの形(井戸を除く大きい版 11 形)を建物に重ねる。どの形でも、建物のマス・部屋の中・入口の通り道に置かず、
     * 芯は外の空気に触れる。1 つ(胞子の塔 A)を建てて、建物は変わらず扉から入れ、枯れると本体・蔦・覆いが剥がれる。
     */
    @GameTest(template = "gametest_empty7", batch = "fusion", timeoutTicks = 300)
    public static void structureOrgansOverlayNormalShapes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Site s = prepare(level, 6_404_400, 6_400_000);
        ForestOrgans data = ForestOrgans.get(level);
        List<Integer> planted = new ArrayList<>();
        try {
            register(s, BuiltinStructures.VILLAGE_PLAINS, List.of(s.house));
            for (int[] f : StructureTrees.LARGE_SHAPES) {
                helper.assertTrue(!(f[0] == OrganKind.LEY.ordinal() && f[1] == 0), "井戸(地脈を吸う根 A)が候補にある");
            }
            helper.assertTrue(StructureTrees.LARGE_SHAPES.length == 11 && StructureTrees.SMALL_SHAPES.length == 10, "候補の数が違う");
            Map<BlockPos, BlockState> before = snapshot(level, s.house.inflatedBy(1));
            int[] house = {s.house.minX(), s.house.minY(), s.house.minZ(), s.house.maxX(), s.house.maxY(), s.house.maxZ()};
            List<StructureFusion.Hit> hits = StructureFusion.near(level, s.house.inflatedBy(40)).hits();
            OrganFusion.Ctx c = new OrganFusion.Ctx(level, hits, StructureFusion.table());
            c.reserveEntrances();
            int ok = 0;
            for (int[][] pool : new int[][][]{StructureTrees.LARGE_SHAPES, StructureTrees.SMALL_SHAPES}) {
                for (int[] f : pool) {
                    boolean small = pool == StructureTrees.SMALL_SHAPES;
                    ForestOrgans.Organ o = new ForestOrgans.Organ(-500 - ok, OrganKind.values()[f[0]], small, f[1], 777 + ok, 0, s.bx + 4, s.bz + 3, 0L);
                    o.onPiece = house;
                    long[] plan = OrganFusion.planOverlay(level, o, hits, StructureFusion.table());
                    String name = OrganKind.values()[f[0]] + (small ? "・小 " : " ") + f[1];
                    helper.assertTrue(plan != null, "建物に重ねられない: " + name);
                    BlockPos a = o.anchor();
                    java.util.Set<Long> body = new java.util.HashSet<>();
                    List<BlockPos> cores = new ArrayList<>();
                    for (long e : plan) {
                        int m = OrganBlueprint.material(e);
                        if (m == OrganBlueprint.M_COVER) continue;
                        BlockPos q = a.offset(OrganBlueprint.dx(e), OrganBlueprint.dy(e), OrganBlueprint.dz(e));
                        helper.assertTrue(!(before.containsKey(q) && !before.get(q).isAir()), name + " が建物のマスに置く: " + q);
                        helper.assertTrue(!c.interior(q.getX(), q.getY(), q.getZ()) && !c.reserved.contains(q.asLong()), name + " が部屋の中か入口に置く: " + q);
                        body.add(q.asLong());
                        if (m == OrganBlueprint.M_CORE) cores.add(q);
                    }
                    // 芯から、器官のブロック(掘れる)か外の空気(部屋の中でない)をたどって、家の外(箱 + 2 の外)へ出られるか
                    boolean reach = false;
                    BoundingBox out = s.house.inflatedBy(2);
                    java.util.ArrayDeque<BlockPos> bq = new java.util.ArrayDeque<>(cores);
                    java.util.Set<Long> seenC = new java.util.HashSet<>();
                    for (BlockPos q : cores) seenC.add(q.asLong());
                    while (!bq.isEmpty() && !reach && seenC.size() < 20000) {
                        BlockPos q = bq.poll();
                        if (!out.isInside(q)) {
                            reach = true;
                            break;
                        }
                        for (Direction d : Direction.values()) {
                            BlockPos nb = q.relative(d);
                            if (!seenC.add(nb.asLong())) continue;
                            boolean pass = body.contains(nb.asLong()) || level.getBlockState(nb).isAir() && !c.interior(nb.getX(), nb.getY(), nb.getZ());
                            if (pass) bq.add(nb);
                        }
                    }
                    helper.assertTrue(!cores.isEmpty() && reach, name + " の芯に外から手が届かない(芯 " + cores.size() + ")");
                    ok++;
                }
            }
            // 1 つ建てて、扉・枯れを見る
            ForestOrgans.Organ o = data.plant(level, OrganKind.TOWER, false, 0, 4321, 0, s.bx + 4, s.bz + 3);
            o.onPiece = house;
            planted.add(o.id);
            int n = OrganWorld.buildNow(level, data, o);
            helper.assertTrue(n > 0 && o.fusion != null, "構造物の器官が建たない(置いた " + n + ")");
            helper.assertTrue(changedSolid(level, before) == null, "建物のブロックが変わった: " + changedSolid(level, before));
            helper.assertTrue(walkIn(level, s), "入口から中へ歩いて入れない");
            List<BlockPos> placed = new ArrayList<>();
            BlockPos a = o.anchor();
            for (long e : o.fusion) {
                if (OrganBlueprint.material(e) == OrganBlueprint.M_COVER) continue;
                placed.add(a.offset(OrganBlueprint.dx(e), OrganBlueprint.dy(e), OrganBlueprint.dz(e)));
            }
            data.wither(level, o, "テスト");
            o.witheredAt = PhaseData.clockNow(level) - (long) (StructureFusion.table().vineFadeSeconds * 20.0D) - 40L;
            for (int k = 0; k < 600 && !o.fusionCleaned; k++) OrganWorld.step(level, data, o, PhaseData.clockNow(level), 100000);
            helper.assertTrue(o.fusionCleaned, "枯れた後に片付けない");
            int left = 0;
            for (BlockPos q : placed) {
                BlockState st = level.getBlockState(q);
                if (!st.isAir() && OvergrownVegetation.MODID.equals(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).getNamespace())) left++;
            }
            helper.assertTrue(left == 0, "枯れた後も器官のブロックが残る: " + left);
            helper.assertTrue(o.covered.isEmpty(), "覆いの記録が残る");
            helper.assertTrue(changedSolid(level, before) == null, "片付けで建物のブロックが変わった");
        } finally {
            for (int id : planted) {
                level.getEntities(OrganEntities.TENTACLE.get(), new net.minecraft.world.phys.AABB(s.bx - R, s.fy - 5, s.bz - R, s.bx + R, s.fy + 60, s.bz + R), e -> true)
                        .forEach(net.minecraft.world.entity.Entity::discard);
                data.remove(id);
            }
            clearBox(level, new BoundingBox(s.bx - 20, s.fy + 1, s.bz - 20, s.bx + 28, s.fy + 50, s.bz + 26));
            s.cleanup();
        }
        helper.succeed();
    }

    /** 段 2d: 木は構造物の守る範囲の中でも、空気のマスには置く(箱の形で四角く欠けない)。部屋の中・建物のブロック・まだ分からない所は飛ばす。 */
    @GameTest(template = "gametest_empty7", batch = "fusion", timeoutTicks = 100)
    public static void treesSkipOnlyBuildingAndRooms(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Site s = prepare(level, 6_404_800, 6_400_000);
        try {
            register(s, BuiltinStructures.VILLAGE_PLAINS, List.of(new BoundingBox(s.bx - 6, s.fy + 1, s.bz - 6, s.bx + 14, s.fy + 20, s.bz + 12)));
            BlockPos yard = new BlockPos(s.bx - 3, s.fy + 3, s.bz + 3), room = new BlockPos(s.bx + 3, s.fy + 2, s.bz + 3), wall = new BlockPos(s.bx + 2, s.fy + 2, s.bz);
            BlockPos aboveRoof = new BlockPos(s.bx + 4, s.fy + 12, s.bz + 3);
            helper.assertTrue(StructureFusion.isProtected(level, yard), "庭が守る範囲でない(テストの作りの誤り)");
            helper.assertTrue(!StructureFusion.keepsOut(level, yard), "箱の中の庭の空気に木を置かない(四角く欠ける)");
            helper.assertTrue(!StructureFusion.keepsOut(level, aboveRoof), "屋根の上の空気に木を置かない");
            helper.assertTrue(StructureFusion.keepsOut(level, room), "部屋の中に木を置く");
            helper.assertTrue(StructureFusion.keepsOut(level, wall), "建物のブロックに木を置く");
        } finally {
            s.cleanup();
        }
        helper.succeed();
    }

    /** 片付けのコマンド: 構造物のそばの浮いた木の塊は消し、地面・建物に支えられた木は消さない。 */
    @GameTest(template = "gametest_empty7", batch = "fusion", timeoutTicks = 100)
    public static void cleanupRemovesOnlyFloatingTrees(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Site s = prepare(level, 6_404_000, 6_400_000);
        BlockState wood = com.yayuy.overgrownvegetation.registry.ModBlocks.OVERGROWN_WOOD.get().defaultBlockState();
        BlockState leaf = com.yayuy.overgrownvegetation.registry.ModBlocks.OVERGROWN_LEAVES.get().defaultBlockState();
        BlockPos floatAt = new BlockPos(s.bx + 4, s.fy + 14, s.bz + 3), groundAt = new BlockPos(s.bx - 6, s.fy + 1, s.bz + 3), roofAt = new BlockPos(s.bx + 2, s.fy + 6, s.bz + 2);
        try {
            register(s, BuiltinStructures.VILLAGE_PLAINS, List.of(s.house));
            for (int y = 0; y < 3; y++) level.setBlock(floatAt.above(y), wood, 2);
            level.setBlock(floatAt.above(3), leaf, 2);
            level.setBlock(floatAt.above(2).east(), leaf, 2);
            for (int y = 0; y < 4; y++) level.setBlock(groundAt.above(y), wood, 2);
            level.setBlock(groundAt.above(4), leaf, 2);
            for (int y = 0; y < 3; y++) level.setBlock(roofAt.above(y), wood, 2);
            FloatingTreeCleanup.Result check = FloatingTreeCleanup.run(level, new BlockPos(s.bx, s.fy, s.bz), 24, true);
            helper.assertTrue(check.clusters() == 1, "数えただけの回で浮いた塊が 1 つでない: " + check);
            helper.assertTrue(level.getBlockState(floatAt).is(wood.getBlock()), "数えただけの回で消した");
            FloatingTreeCleanup.Result r = FloatingTreeCleanup.run(level, new BlockPos(s.bx, s.fy, s.bz), 24, false);
            helper.assertTrue(r.clusters() == 1 && r.blocks() == 5, "浮いた塊を消さない: " + r);
            helper.assertTrue(level.getBlockState(floatAt).isAir() && level.getBlockState(floatAt.above(3)).isAir(), "浮いた木が残る");
            helper.assertTrue(level.getBlockState(groundAt).is(wood.getBlock()) && level.getBlockState(groundAt.above(4)).is(leaf.getBlock()), "地面の木を消した");
            helper.assertTrue(level.getBlockState(roofAt).is(wood.getBlock()), "屋根の上の木を消した");
        } finally {
            for (BlockPos b : List.of(floatAt, groundAt, roofAt)) clearBox(level, new BoundingBox(b.getX() - 1, b.getY(), b.getZ() - 1, b.getX() + 1, b.getY() + 4, b.getZ() + 1));
            s.cleanup();
        }
        helper.succeed();
    }

    /** 扉の外 3 マスから中 3 マスへ、2 マスの高さで歩いて行けるか(1 マスの段は乗り越えられる。扉は通れる)。 */
    static boolean walkIn(ServerLevel level, Site s) {
        BlockPos start = s.door.offset(0, 0, 3), goal = s.door.offset(0, 0, -3);
        ArrayDeque<BlockPos> q = new ArrayDeque<>();
        java.util.Set<BlockPos> seen = new java.util.HashSet<>();
        BlockPos f0 = foot(level, start.getX(), start.getZ(), s.fy);
        if (f0 == null) return false;
        q.add(f0);
        seen.add(f0);
        while (!q.isEmpty() && seen.size() < 4000) {
            BlockPos p = q.poll();
            if (p.getX() == goal.getX() && p.getZ() == goal.getZ()) return true;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                int nx = p.getX() + d.getStepX(), nz = p.getZ() + d.getStepZ();
                if (Math.abs(nx - s.door.getX()) > 14 || Math.abs(nz - s.door.getZ()) > 14) continue;
                BlockPos n = foot(level, nx, nz, s.fy);
                if (n == null || Math.abs(n.getY() - p.getY()) > 1 || !seen.add(n)) continue;
                q.add(n);
            }
        }
        return false;
    }

    private static boolean passable(BlockState st) {
        return st.isAir() || !st.blocksMotion() || st.getBlock() instanceof DoorBlock;
    }

    /** 足を置ける高さ(床の上 4 マスまで。上に 2 マス通れる)。 */
    private static BlockPos foot(ServerLevel level, int x, int z, int fy) {
        for (int y = fy + 1; y <= fy + 4; y++) {
            BlockPos p = new BlockPos(x, y, z);
            if (passable(level.getBlockState(p)) && passable(level.getBlockState(p.above())) && !passable(level.getBlockState(p.below()))) return p;
        }
        return null;
    }
}
