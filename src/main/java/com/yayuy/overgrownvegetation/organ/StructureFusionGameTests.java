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
