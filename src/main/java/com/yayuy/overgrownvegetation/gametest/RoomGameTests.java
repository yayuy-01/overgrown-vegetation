package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.RoomSpec;
import com.yayuy.overgrownvegetation.spore.SporeConcentration;
import com.yayuy.overgrownvegetation.survival.room.AirCells;
import com.yayuy.overgrownvegetation.survival.room.RoomCache;
import com.yayuy.overgrownvegetation.survival.room.RoomFinder;
import com.yayuy.overgrownvegetation.survival.room.RoomScan;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 閉じた部屋の判定(2026-11-13 サバイバルの背骨 段 2a)。
 *
 * <p>形の試し(6×6 の部屋・幅 20 の広間・板ガラス・ドア・穴・前室・地下の深い部屋・自然の洞窟・上限・ハーフ・葉・フェンス・トラップドア・
 * フェンスゲート・鉄格子)は、テスト場(7×7、外はバリア、高さ地図は天井のバリアの上)に入らないので、素の Java の格子({@link Grid})で
 * {@link RoomFinder} を回す。ブロックの状態は本物(当たり判定の形・タグ)。
 * 世界の中では、本物のドアの開け閉め・置く/壊すで覚えが捨てられること・濃度の倍率・覚えの速さを見る(バリア = 外)。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class RoomGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(RoomGameTests.class);
        }

        private Registration() {
        }
    }

    private RoomGameTests() {
    }

    private static final String TEMPLATE = "gametest_empty7";
    private static final String BATCH = "survival_room";

    // ------------------------------------------------------------------ 素の格子

    /** 素の格子: y ≤ groundTop は石(掘れば空気)、それより上は空気。置いた物は上書き。空 = 上に動きをふさぐ物が無い。 */
    static final class Grid implements RoomFinder.World {
        final Long2ObjectOpenHashMap<BlockState> set = new Long2ObjectOpenHashMap<>();
        final int groundTop;
        static final int SKY = 140;

        Grid(int groundTop) {
            this.groundTop = groundTop;
        }

        BlockState get(int x, int y, int z) {
            BlockState s = set.get(BlockPos.asLong(x, y, z));
            if (s != null) return s;
            return y <= groundTop ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
        }

        Grid put(int x, int y, int z, BlockState s) {
            set.put(BlockPos.asLong(x, y, z), s);
            return this;
        }

        Grid fill(int x0, int y0, int z0, int x1, int y1, int z1, BlockState s) {
            for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) put(x, y, z, s);
            return this;
        }

        /** 外壁(と床・天井)を s、中を空気にした箱。中は (x0+1..x1-1, y0+1..y1-1, z0+1..z1-1)。 */
        Grid box(int x0, int y0, int z0, int x1, int y1, int z1, BlockState s) {
            fill(x0, y0, z0, x1, y1, z1, s);
            return fill(x0 + 1, y0 + 1, z0 + 1, x1 - 1, y1 - 1, z1 - 1, Blocks.AIR.defaultBlockState());
        }

        @Override
        public byte cell(int x, int y, int z) {
            return AirCells.of(get(x, y, z));
        }

        @Override
        public boolean openSky(int x, int y, int z) {
            for (int yy = y + 1; yy <= SKY; yy++) {
                BlockState s = get(x, yy, z);
                if ((s.blocksMotion() || !s.getFluidState().isEmpty()) && !s.is(BlockTags.LEAVES)) return false;
            }
            return true;
        }

        RoomScan scan(int x, int y, int z) {
            return RoomFinder.scan(this, new BlockPos(x, y, z), spec(), false);
        }

        RoomScan full(int x, int y, int z) {
            return RoomFinder.scan(this, new BlockPos(x, y, z), spec(), true);
        }
    }

    private static RoomSpec spec() {
        return DesignData.sporeField().room();
    }

    private static final BlockState PLANKS = Blocks.OAK_PLANKS.defaultBlockState();

    private static BlockState door(Direction facing, DoubleBlockHalf half, boolean open) {
        return Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, facing).setValue(DoorBlock.HALF, half).setValue(DoorBlock.OPEN, open);
    }

    /** 東西に並ぶ壁(x 方向につながる)の板ガラス。 */
    private static BlockState paneAlongX() {
        return Blocks.GLASS_PANE.defaultBlockState().setValue(IronBarsBlock.EAST, true).setValue(IronBarsBlock.WEST, true);
    }

    private static void expect(GameTestHelper helper, String what, RoomScan scan, RoomScan.Status want) {
        if (scan.status() != want) {
            helper.fail(what + ": " + want.label + " のはずが " + scan.status().label + "(マス " + scan.volume() + "・人の手 " + scan.built() + ")");
        }
    }

    /** 6×6(中)・高さ 3 の木の部屋 = 部屋。中のマスは 108。床は地面の石。 */
    private static Grid house6() {
        // 地面 y ≤ 0、壁 x 0..7・z 0..7・y 1..4(天井 y 4)、中 x 1..6・z 1..6・y 1..3
        Grid g = new Grid(0);
        g.box(0, 0, 0, 7, 4, 7, PLANKS);
        g.fill(0, 0, 0, 7, 0, 7, Blocks.STONE.defaultBlockState());
        return g;
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void boxRoomAndHall(GameTestHelper helper) {
        Grid g = house6();
        RoomScan r = g.scan(3, 1, 3);
        expect(helper, "6×6 の部屋", r, RoomScan.Status.ROOM);
        if (r.volume() != 6 * 6 * 3) helper.fail("6×6×3 の部屋のマスが " + r.volume());
        // 頭の高さから聞いても同じ部屋
        if (g.scan(3, 2, 3).id() != r.id()) helper.fail("足元と頭で部屋の印が違う");

        // 幅 20 × 奥行 20 × 高さ 4 の広間(石レンガ)
        Grid h = new Grid(0);
        h.box(0, 0, 0, 21, 5, 21, Blocks.STONE_BRICKS.defaultBlockState());
        RoomScan hall = h.scan(10, 2, 10);
        expect(helper, "幅 20 の広間", hall, RoomScan.Status.ROOM);
        if (hall.volume() != 20 * 20 * 4) helper.fail("広間のマスが " + hall.volume());
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void holeAndPanes(GameTestHelper helper) {
        Grid g = house6();
        // 壁に 1 マスの穴 → 外へ抜ける。すき間はその穴のマス
        g.put(0, 2, 3, Blocks.AIR.defaultBlockState());
        RoomScan r = g.full(3, 1, 3);
        expect(helper, "壁に 1 マスの穴", r, RoomScan.Status.OPEN);
        if (!r.gaps().contains(BlockPos.asLong(0, 2, 3))) helper.fail("すき間に穴のマスが無い: " + r.gaps());
        // 穴に板ガラス(壁の向きにつながったもの)→ 部屋に戻る
        g.put(0, 2, 3, Blocks.GLASS_PANE.defaultBlockState().setValue(IronBarsBlock.NORTH, true).setValue(IronBarsBlock.SOUTH, true));
        expect(helper, "穴を板ガラスでふさぐ", g.scan(3, 1, 3), RoomScan.Status.ROOM);
        // 南の壁を全部板ガラスに(角は木)
        Grid p = house6();
        for (int x = 1; x <= 6; x++) for (int y = 1; y <= 3; y++) p.put(x, y, 0, paneAlongX());
        expect(helper, "板ガラスの壁", p.scan(3, 1, 3), RoomScan.Status.ROOM);
        // ガラスのブロックの窓・鉄格子の窓
        g.put(0, 2, 3, Blocks.GLASS.defaultBlockState());
        expect(helper, "ガラスの窓", g.scan(3, 1, 3), RoomScan.Status.ROOM);
        g.put(0, 2, 3, Blocks.IRON_BARS.defaultBlockState().setValue(IronBarsBlock.NORTH, true).setValue(IronBarsBlock.SOUTH, true));
        expect(helper, "鉄格子の窓【仮: 通す】", g.scan(3, 1, 3), RoomScan.Status.OPEN);
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void doorsAndVestibule(GameTestHelper helper) {
        Grid g = house6();
        // 西の壁 x = 0 にドア(z = 3、y = 1・2)
        g.put(0, 1, 3, door(Direction.EAST, DoubleBlockHalf.LOWER, false)).put(0, 2, 3, door(Direction.EAST, DoubleBlockHalf.UPPER, false));
        expect(helper, "ドアを閉じた", g.scan(3, 1, 3), RoomScan.Status.ROOM);
        g.put(0, 1, 3, door(Direction.EAST, DoubleBlockHalf.LOWER, true)).put(0, 2, 3, door(Direction.EAST, DoubleBlockHalf.UPPER, true));
        expect(helper, "ドアを開けた", g.scan(3, 1, 3), RoomScan.Status.OPEN);

        // 前室: 奥の部屋 x 1..6 | 内のドア x = 0 | 前室 x = -1(1 マス × 高さ 2) | 外のドア x = -2 | 外
        Grid v = house6();
        v.fill(-2, 0, 2, -1, 3, 4, PLANKS);
        v.fill(-1, 1, 3, -1, 2, 3, Blocks.AIR.defaultBlockState());
        v.put(0, 1, 3, door(Direction.EAST, DoubleBlockHalf.LOWER, false)).put(0, 2, 3, door(Direction.EAST, DoubleBlockHalf.UPPER, false));
        v.put(-2, 1, 3, door(Direction.EAST, DoubleBlockHalf.LOWER, true)).put(-2, 2, 3, door(Direction.EAST, DoubleBlockHalf.UPPER, true));
        expect(helper, "外のドアを開けても奥", v.scan(3, 1, 3), RoomScan.Status.ROOM);
        expect(helper, "外のドアを開けた前室", v.scan(-1, 1, 3), RoomScan.Status.OPEN);
        v.put(-2, 1, 3, door(Direction.EAST, DoubleBlockHalf.LOWER, false)).put(-2, 2, 3, door(Direction.EAST, DoubleBlockHalf.UPPER, false));
        v.put(0, 1, 3, door(Direction.EAST, DoubleBlockHalf.LOWER, true)).put(0, 2, 3, door(Direction.EAST, DoubleBlockHalf.UPPER, true));
        expect(helper, "外のドアを閉じ内を開けた(前室ごと部屋)", v.scan(3, 1, 3), RoomScan.Status.ROOM);
        v.put(-2, 1, 3, door(Direction.EAST, DoubleBlockHalf.LOWER, true)).put(-2, 2, 3, door(Direction.EAST, DoubleBlockHalf.UPPER, true));
        expect(helper, "両方開けた", v.scan(3, 1, 3), RoomScan.Status.OPEN);

        // トラップドア(天井)とフェンスゲート(壁)【仮: ドアと同じ】
        Grid t = house6();
        t.put(3, 4, 3, Blocks.OAK_TRAPDOOR.defaultBlockState().setValue(TrapDoorBlock.HALF, Half.TOP).setValue(TrapDoorBlock.OPEN, false));
        expect(helper, "閉じたトラップドアの天井", t.scan(3, 1, 3), RoomScan.Status.ROOM);
        t.put(3, 4, 3, Blocks.OAK_TRAPDOOR.defaultBlockState().setValue(TrapDoorBlock.HALF, Half.TOP).setValue(TrapDoorBlock.OPEN, true));
        expect(helper, "開けたトラップドアの天井", t.scan(3, 1, 3), RoomScan.Status.OPEN);
        Grid f = house6();
        f.put(3, 1, 0, Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(FenceGateBlock.FACING, Direction.SOUTH).setValue(FenceGateBlock.OPEN, false));
        expect(helper, "閉じたフェンスゲート", f.scan(3, 1, 3), RoomScan.Status.ROOM);
        f.put(3, 1, 0, Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(FenceGateBlock.FACING, Direction.SOUTH).setValue(FenceGateBlock.OPEN, true));
        expect(helper, "開けたフェンスゲート", f.scan(3, 1, 3), RoomScan.Status.OPEN);
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void slabsLeavesFences(GameTestHelper helper) {
        // 下付きハーフの屋根 = ふさぐ
        Grid s = house6();
        s.fill(1, 4, 1, 6, 4, 6, Blocks.OAK_SLAB.defaultBlockState().setValue(net.minecraft.world.level.block.SlabBlock.TYPE, SlabType.BOTTOM));
        expect(helper, "ハーフの屋根", s.scan(3, 1, 3), RoomScan.Status.ROOM);
        // 壁の 1 マスをハーフに = すき間【仮】
        Grid w = house6();
        w.put(0, 2, 3, Blocks.OAK_SLAB.defaultBlockState());
        expect(helper, "壁のハーフ【仮: すき間】", w.scan(3, 1, 3), RoomScan.Status.OPEN);
        // 階段の屋根 = ふさぐ
        Grid st = house6();
        st.fill(1, 4, 1, 6, 4, 6, Blocks.OAK_STAIRS.defaultBlockState());
        expect(helper, "階段の屋根", st.scan(3, 1, 3), RoomScan.Status.ROOM);
        // 葉の屋根 = 通す【仮】
        Grid l = house6();
        l.fill(1, 4, 1, 6, 4, 6, Blocks.OAK_LEAVES.defaultBlockState());
        expect(helper, "葉の屋根【仮: 通す】", l.scan(3, 1, 3), RoomScan.Status.OPEN);
        // フェンスの壁 = 通す【仮】
        Grid fe = house6();
        for (int x = 1; x <= 6; x++) fe.put(x, 1, 0, Blocks.OAK_FENCE.defaultBlockState()
                .setValue(net.minecraft.world.level.block.FenceBlock.EAST, true).setValue(net.minecraft.world.level.block.FenceBlock.WEST, true));
        expect(helper, "フェンスの壁【仮: 通す】", fe.scan(3, 1, 3), RoomScan.Status.OPEN);
        // 当たり判定の無いもの(松明・看板)を穴に置いても通す
        Grid tor = house6();
        tor.put(0, 2, 3, Blocks.TORCH.defaultBlockState());
        expect(helper, "穴に松明", tor.scan(3, 1, 3), RoomScan.Status.OPEN);
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void undergroundAndCaves(GameTestHelper helper) {
        // 地面 y ≤ 60。y 10..12 に掘った 5×5 の部屋、東の壁に閉じたドア、ドアの先に地上まで縦穴
        Grid g = new Grid(60);
        g.fill(1, 10, 1, 5, 12, 5, Blocks.AIR.defaultBlockState());
        g.put(6, 10, 3, door(Direction.WEST, DoubleBlockHalf.LOWER, false)).put(6, 11, 3, door(Direction.WEST, DoubleBlockHalf.UPPER, false));
        g.fill(7, 10, 3, 7, 60, 3, Blocks.AIR.defaultBlockState());
        RoomScan r = g.scan(3, 10, 3);
        expect(helper, "地下 50 ブロックに掘った部屋", r, RoomScan.Status.ROOM);
        g.put(6, 10, 3, door(Direction.WEST, DoubleBlockHalf.LOWER, true)).put(6, 11, 3, door(Direction.WEST, DoubleBlockHalf.UPPER, true));
        expect(helper, "掘った部屋のドアを開けて縦穴へ", g.scan(3, 10, 3), RoomScan.Status.OPEN);

        // 自然の洞窟の袋(石だけに囲まれた閉じた空洞)= 部屋にしない
        Grid c = new Grid(60);
        c.fill(1, 20, 1, 6, 23, 6, Blocks.AIR.defaultBlockState());
        c.put(3, 19, 3, Blocks.IRON_ORE.defaultBlockState()).put(4, 24, 4, Blocks.DEEPSLATE.defaultBlockState()).put(2, 20, 0, Blocks.GRAVEL.defaultBlockState());
        expect(helper, "自然の洞窟の袋", c.scan(3, 20, 3), RoomScan.Status.NATURAL);
        // 丸石を 1 つ積めば人の手の部屋
        c.put(0, 21, 3, Blocks.COBBLESTONE.defaultBlockState());
        expect(helper, "丸石でふさいだ洞窟", c.scan(3, 20, 3), RoomScan.Status.ROOM);

        // 大きな洞窟(30×30×8 = 7200 マス)= 上限を超えて部屋でない
        Grid big = new Grid(60);
        big.fill(0, 20, 0, 29, 27, 29, Blocks.AIR.defaultBlockState());
        big.put(0, 19, 0, Blocks.COBBLESTONE.defaultBlockState());
        RoomScan b = big.scan(10, 21, 10);
        expect(helper, "大きな洞窟", b, RoomScan.Status.TOO_BIG);
        // 細長いトンネル(差し渡しの上限)
        Grid tun = new Grid(60);
        tun.fill(0, 20, 0, spec().maxSpan() + 5, 21, 0, Blocks.AIR.defaultBlockState());
        tun.put(0, 19, 0, Blocks.COBBLESTONE.defaultBlockState());
        expect(helper, "上限より長いトンネル", tun.scan(2, 20, 0), RoomScan.Status.TOO_BIG);
        // 上限ちょうどの手前(中が maxCells 以下)は部屋
        int side = (int) Math.floor(Math.cbrt(spec().maxCells()));
        Grid fit = new Grid(0);
        fit.box(0, 0, 0, side + 1, side + 1, side + 1, Blocks.BRICKS.defaultBlockState());
        expect(helper, "上限に収まる部屋(" + side + "³)", fit.scan(side / 2, side / 2, side / 2), RoomScan.Status.ROOM);
        helper.succeed();
    }

    // ------------------------------------------------------------------ 世界の中(バリア = 外)

    /** 同じ tick に何度も確かめるので、1 tick にたどる回数の上限を外す。 */
    private static void unlimited(Runnable r) {
        RoomCache.unlimitedForTests = true;
        try {
            r.run();
        } finally {
            RoomCache.unlimitedForTests = false;
        }
    }

    /** テスト場の中に木の箱: 壁 x 1..5・z 1..5・y 2..5(床は場の石 y = 1)、中 x 2..4・z 2..4・y 2..4。南の壁 z = 1 の x = 3 にドア。 */
    private static void buildHut(GameTestHelper helper) {
        for (int x = 1; x <= 5; x++) for (int y = 2; y <= 5; y++) for (int z = 1; z <= 5; z++) {
            boolean inside = x >= 2 && x <= 4 && y <= 4 && z >= 2 && z <= 4;
            helper.setBlock(new BlockPos(x, y, z), inside ? Blocks.AIR.defaultBlockState() : PLANKS);
        }
        helper.setBlock(new BlockPos(3, 2, 1), door(Direction.NORTH, DoubleBlockHalf.LOWER, false));
        helper.setBlock(new BlockPos(3, 3, 1), door(Direction.NORTH, DoubleBlockHalf.UPPER, false));
    }

    private static void setDoor(GameTestHelper helper, BlockPos rel, boolean open) {
        ServerLevel level = helper.getLevel();
        BlockPos at = helper.absolutePos(rel);
        ((DoorBlock) Blocks.OAK_DOOR).setOpen(null, level, level.getBlockState(at), at, open);
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 40)
    public static void worldDoorAndBlocksInvalidate(GameTestHelper helper) {
        buildHut(helper);
        ServerLevel level = helper.getLevel();
        BlockPos in = helper.absolutePos(new BlockPos(3, 2, 3));
        helper.runAfterDelay(2, () -> unlimited(() -> {
            RoomScan r = RoomCache.scanAt(level, in);
            if (r == null || !r.isRoom()) helper.fail("閉じた小屋が部屋でない: " + (r == null ? "null" : r.status().label));
            if (r.volume() != 27) helper.fail("小屋の中のマスが " + r.volume());
            long hits = RoomCache.hits;
            RoomCache.scanAt(level, helper.absolutePos(new BlockPos(2, 4, 4)));
            if (RoomCache.hits != hits + 1) helper.fail("同じ部屋の別のマスで覚えを使っていない");
            double f = SporeConcentration.roomFactorAt(level, in);
            if (Math.abs(f - DesignData.sporeField().roomFactor()) > 1e-9) helper.fail("部屋の倍率が " + f);

            setDoor(helper, new BlockPos(3, 2, 1), true);
            RoomScan open = RoomCache.scanAt(level, in);
            if (open == null || open.isRoom()) helper.fail("ドアを開けても部屋のまま(覚えが捨てられていない)");
            if (SporeConcentration.roomFactorAt(level, in) != 1.0D) helper.fail("ドアを開けたのに倍率が 1 でない");
            setDoor(helper, new BlockPos(3, 2, 1), false);
            if (!RoomCache.scanAt(level, in).isRoom()) helper.fail("ドアを閉じても部屋に戻らない");

            helper.setBlock(new BlockPos(5, 3, 3), Blocks.AIR.defaultBlockState());
            if (RoomCache.scanAt(level, in).isRoom()) helper.fail("壁を壊しても部屋のまま");
            helper.setBlock(new BlockPos(5, 3, 3), Blocks.GLASS.defaultBlockState());
            if (!RoomCache.scanAt(level, in).isRoom()) helper.fail("ガラスでふさいでも部屋に戻らない");
            helper.setBlock(new BlockPos(3, 5, 3), Blocks.AIR.defaultBlockState());
            if (RoomCache.scanAt(level, in).isRoom()) helper.fail("天井を壊しても部屋のまま");
            helper.setBlock(new BlockPos(3, 5, 3), PLANKS);
            if (!RoomCache.scanAt(level, in).isRoom()) helper.fail("天井を戻しても部屋に戻らない");
            helper.succeed();
        }));
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 40)
    public static void worldVestibule(GameTestHelper helper) {
        // x 0..6・z 0..4・y 2..5 を木で埋め、奥の部屋 x 1..3・z 1..3・y 2..4、内のドア x = 4、前室 x = 5(z = 2、y 2..3)、外のドア x = 6、その先 x = 7 はバリア
        for (int x = 0; x <= 6; x++) for (int y = 2; y <= 5; y++) for (int z = 0; z <= 4; z++) helper.setBlock(new BlockPos(x, y, z), PLANKS);
        for (int x = 1; x <= 3; x++) for (int y = 2; y <= 4; y++) for (int z = 1; z <= 3; z++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
        helper.setBlock(new BlockPos(5, 2, 2), Blocks.AIR.defaultBlockState());
        helper.setBlock(new BlockPos(5, 3, 2), Blocks.AIR.defaultBlockState());
        for (int dx : new int[]{4, 6}) {
            helper.setBlock(new BlockPos(dx, 2, 2), door(Direction.EAST, DoubleBlockHalf.LOWER, false));
            helper.setBlock(new BlockPos(dx, 3, 2), door(Direction.EAST, DoubleBlockHalf.UPPER, false));
        }
        ServerLevel level = helper.getLevel();
        BlockPos in = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockPos hall = helper.absolutePos(new BlockPos(5, 2, 2));
        helper.runAfterDelay(2, () -> unlimited(() -> {
            if (!RoomCache.scanAt(level, in).isRoom()) helper.fail("両方閉じて奥が部屋でない: " + RoomCache.scanAt(level, in).status().label);
            setDoor(helper, new BlockPos(6, 2, 2), true);
            if (!RoomCache.scanAt(level, in).isRoom()) helper.fail("外のドアを開けたら奥が部屋でなくなった");
            if (RoomCache.scanAt(level, hall).isRoom()) helper.fail("外のドアを開けた前室が部屋のまま");
            setDoor(helper, new BlockPos(4, 2, 2), true);
            if (RoomCache.scanAt(level, in).isRoom()) helper.fail("両方開けて奥が部屋のまま");
            setDoor(helper, new BlockPos(6, 2, 2), false);
            if (!RoomCache.scanAt(level, in).isRoom()) helper.fail("外を閉じ内を開けて、前室ごと部屋にならない");
            helper.succeed();
        }));
    }

    /**
     * 重さ: 小屋の中のマス 27 か所を、プレイヤー 4 人 + Mob 20 体が 20〜40 tick ごとに読むのを 1 万回まとめて回し、1 回の平均を測る(覚えが効いていること)。
     * 合わせて、たどり直しの費用(幅 20 の広間・上限の大洞窟)を素の格子で測る。数はログに出す。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 40)
    public static void cacheCost(GameTestHelper helper) {
        buildHut(helper);
        ServerLevel level = helper.getLevel();
        helper.runAfterDelay(2, () -> unlimited(() -> {
            BlockPos[] cells = new BlockPos[27];
            int i = 0;
            for (int x = 2; x <= 4; x++) for (int y = 2; y <= 4; y++) for (int z = 2; z <= 4; z++) cells[i++] = helper.absolutePos(new BlockPos(x, y, z));
            long scans0 = RoomCache.scans;
            RoomCache.scanAt(level, cells[0]);
            int n = 10_000;
            long t0 = System.nanoTime();
            int rooms = 0;
            for (int k = 0; k < n; k++) if (RoomCache.roomAt(level, cells[k % 27]) != null) rooms++;
            double perHitUs = (System.nanoTime() - t0) / 1000.0D / n;
            long newScans = RoomCache.scans - scans0;
            if (rooms != n) helper.fail("部屋と読めたのが " + rooms + " / " + n);
            if (newScans > 1) helper.fail("覚えが効かずに " + newScans + " 回たどった");

            Grid h = new Grid(0);
            h.box(0, 0, 0, 21, 5, 21, Blocks.STONE_BRICKS.defaultBlockState());
            long t1 = System.nanoTime();
            for (int k = 0; k < 20; k++) h.scan(10, 2, 10);
            double hallMs = (System.nanoTime() - t1) / 1.0E6D / 20;
            Grid big = new Grid(60);
            big.fill(0, 20, 0, 29, 27, 29, Blocks.AIR.defaultBlockState());
            long t2 = System.nanoTime();
            for (int k = 0; k < 20; k++) big.scan(10, 21, 10);
            double caveMs = (System.nanoTime() - t2) / 1.0E6D / 20;
            OvergrownVegetation.LOGGER.info("[部屋の判定の重さ] 覚えから 1 回 {} µs・広間 1600 マスをたどる {} ms・上限の大洞窟 {} ms(素の格子)",
                    String.format("%.3f", perHitUs), String.format("%.2f", hallMs), String.format("%.2f", caveMs));
            // 24 体が 20〜40 tick ごとに読む = 1 tick に 1 回ほど。覚えから 20 µs を超えたら遅すぎる
            if (perHitUs > 20.0D) helper.fail(String.format("覚えから読むのが遅い: %.2f µs", perHitUs));
            helper.succeed();
        }));
    }

    /** 塀の形(石の塀)は横切る向きをふさぐ・板ガラスの投影・壁の印。AirCells の印の決まりを直接見る。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void airCellClasses(GameTestHelper helper) {
        check(helper, "空気", Blocks.AIR.defaultBlockState(), 0, false);
        check(helper, "石", Blocks.STONE.defaultBlockState(), AirCells.SOLID, true);
        check(helper, "木材", PLANKS, AirCells.SOLID, false);
        check(helper, "ガラス", Blocks.GLASS.defaultBlockState(), AirCells.SOLID, false);
        check(helper, "東西の板ガラス", paneAlongX(), AirCells.BLOCK_Z, false);
        check(helper, "閉じた東向きのドア", door(Direction.EAST, DoubleBlockHalf.LOWER, false), AirCells.BLOCK_X, false);
        check(helper, "開けた東向きのドア", door(Direction.EAST, DoubleBlockHalf.LOWER, true), AirCells.BLOCK_Z, false);
        check(helper, "下付きハーフ", Blocks.OAK_SLAB.defaultBlockState(), AirCells.BLOCK_Y, false);
        check(helper, "葉", Blocks.OAK_LEAVES.defaultBlockState(), 0, false);
        if ((AirCells.of(Blocks.BARRIER.defaultBlockState()) & AirCells.OUTSIDE) == 0) helper.fail("バリアが外でない");
        helper.succeed();
    }

    private static void check(GameTestHelper helper, String what, BlockState s, int wantBlocks, boolean natural) {
        byte c = AirCells.of(s);
        if ((c & AirCells.SOLID) != wantBlocks) helper.fail(what + " のふさぐ向きが " + (c & AirCells.SOLID) + "(期待 " + wantBlocks + ")");
        if (((c & AirCells.NATURAL) != 0) != natural) helper.fail(what + " の自然の印が " + ((c & AirCells.NATURAL) != 0));
    }
}
