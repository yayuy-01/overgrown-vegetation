package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.StiltPhyllosensEntity;
import com.yayuy.overgrownvegetation.entity.stilt.StiltColumns;
import com.yayuy.overgrownvegetation.entity.stilt.StiltLegIK;
import com.yayuy.overgrownvegetation.entity.stilt.StiltWalker;
import com.yayuy.overgrownvegetation.registry.ModStilt;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * 竹馬型の移動(フィロセンス段 1、2026-11-10)のテスト。決定 11-10: 段差は 5 まで・水は深さ 5 まで・胴の高さの葉はかがんでくぐる・
 * くぐれない壁は回り込む・屋根の上にも足を置く・屋根の下(かがめる低さより低い天井)へは入らない・足先は空いた所。
 *
 * <p>体が大きい(脚の先まで約 7 ブロック)ので、7 × 7 のテスト場ではなく、強制読み込みにした遠いチャンクの空中(y = 99 の床)に
 * 32 × 32 の場を組む。遠いチャンクは実行をまたいで残るので、毎回ちがう所を使い、組む前に更地にする。終わりに強制読み込みを外す。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class StiltGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterStiltGameTests(RegisterGameTestsEvent event) // 1.20.1: Forge の受け手の包みは名前で使い回すので、クラスごとに名前を変える {
            event.register(StiltGameTests.class);
        }

        private Registration() {
        }
    }

    private StiltGameTests() {
    }

    private static final int RUN_SALT = new java.util.Random().nextInt(1 << 16);
    private static final java.util.concurrent.atomic.AtomicInteger NEXT = new java.util.concurrent.atomic.AtomicInteger();
    /** 床の上面の高さ(床のブロックは y = 99)。 */
    static final int FLOOR = 100;
    static final int SIZE = 32;

    /** 実行ごとに違う、遠いチャンク(2 × 2 チャンク = 32 × 32 の場 + まわり 1 チャンク)。 */
    static ChunkPos fresh() {
        int base = 1_200_000 + (RUN_SALT % 2000) * 64 + NEXT.getAndIncrement() * 6;
        return new ChunkPos(base, base / 2);
    }

    static void force(ServerLevel level, ChunkPos a, boolean on) {
        for (int dx = -1; dx <= 2; dx++) for (int dz = -1; dz <= 2; dz++) level.setChunkForced(a.x + dx, a.z + dz, on);
    }

    static void fill(ServerLevel level, int x0, int y0, int z0, int x1, int y1, int z1, BlockState s) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++)
            for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++)
                for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++)
                    level.setBlock(p.set(x, y, z), s, 2 | 16);
    }

    static BlockState leaves() {
        return Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
    }

    /** 場: (x0, z0) が角。床 y = 99(石)。場の上 y 100〜124 と下 y 88〜98 を更地にしてから組む。 */
    record Arena(ServerLevel level, ChunkPos chunk, int x0, int z0) {
        int x(int dx) {
            return x0 + dx;
        }

        int z(int dz) {
            return z0 + dz;
        }
    }

    /**
     * 場を組んで(build)、フィロセンスを (sx, sz) に置き、(gx, gz) へ歩かせる。毎 tick {@code each} を呼び(失敗は例外)、
     * {@code until} の tick で {@code end} を呼ぶ(成功で抜ける)。
     */
    private static void run(GameTestHelper helper, Consumer<Arena> build, double sx, double sz, double gx, double gz,
                            int until, java.util.function.BiConsumer<Arena, StiltPhyllosensEntity> each,
                            java.util.function.BiConsumer<Arena, StiltPhyllosensEntity> end) {
        ServerLevel level = helper.getLevel();
        StiltWalker.alwaysNear = true;   // テストのサーバーにはプレイヤーがいない(人がいないと足場探しを省く作り)
        ChunkPos c = fresh();
        force(level, c, true);
        Arena a = new Arena(level, c, c.getMinBlockX(), c.getMinBlockZ());
        StiltPhyllosensEntity[] mob = new StiltPhyllosensEntity[1];
        helper.runAfterDelay(20, () -> {
            fill(level, a.x(-4), 88, a.z(-4), a.x(SIZE + 3), 124, a.z(SIZE + 3), Blocks.AIR.defaultBlockState());
            fill(level, a.x(0), 99, a.z(0), a.x(SIZE - 1), 99, a.z(SIZE - 1), Blocks.STONE.defaultBlockState());
            build.accept(a);
            StiltColumns.of(level).clear();
            com.yayuy.overgrownvegetation.entity.stilt.StiltPlanner.forget(level);
            StiltWalker.forgetUnreachable();
            StiltPhyllosensEntity e = ModStilt.PHYLLOSENS_STILT.get().create(level);
            if (e == null) {
                helper.fail("作れない");
                return;
            }
            e.moveTo(a.x0 + sx, FLOOR, a.z0 + sz, 0.0F, 0.0F);
            e.setCustomName(Component.literal("nostroll"));
            e.setPersistenceRequired();
            level.addFreshEntity(e);
            mob[0] = e;
        });
        helper.runAfterDelay(30, () -> {
            if (mob[0] == null) return;
            if (!level.isPositionEntityTicking(mob[0].blockPosition())) {
                helper.fail("遠いチャンクでエンティティが動かない(テスト場の都合)");
                return;
            }
            mob[0].stiltWalker().goTo(a.x0 + gx, FLOOR, a.z0 + gz, 1.0D);
        });
        helper.onEachTick(() -> {
            if (mob[0] == null || helper.getTick() < 31) return;
            try {
                each.accept(a, mob[0]);
            } catch (RuntimeException ex) {
                cleanup(level, c, mob[0]);
                throw ex;
            }
        });
        helper.runAtTickTime(until, () -> {
            try {
                end.accept(a, mob[0]);
            } finally {
                cleanup(level, c, mob[0]);
            }
            helper.succeed();
        });
    }

    static void cleanup(ServerLevel level, ChunkPos c, StiltPhyllosensEntity e) {
        if (e != null) e.discard();
        force(level, c, false);
    }

    private static double center(StiltPhyllosensEntity e) {
        return e.getY() + 0.75D;
    }

    static String at(Arena a, StiltPhyllosensEntity e) {
        return String.format(Locale.ROOT, "(%.2f, %.2f, %.2f) 胴の高さ %.2f", e.getX() - a.x0, e.getY() - FLOOR, e.getZ() - a.z0, center(e) - FLOOR);
    }

    /** 着いている足先が全部、当たりの無いマス(か葉)にあるか。 */
    private static void feetInOpen(Arena a, StiltPhyllosensEntity e) {
        StiltWalker w = e.stiltWalker();
        if (!w.ready()) return;
        for (int i = 0; i < 4; i++) {
            if (i == w.swingLeg()) continue;
            Vec3 f = w.foot(i);
            BlockPos p = BlockPos.containing(f.x, f.y + 0.01D, f.z);
            BlockState s = a.level.getBlockState(p);
            if (!s.isAir() && !(s.getBlock() instanceof LeavesBlock) && !s.getCollisionShape(a.level, p).isEmpty()) {
                throw new net.minecraft.gametest.framework.GameTestAssertException("足先 " + i + " が固いブロックの中 " + s + " " + at(a, e));
            }
        }
    }

    private static void arrived(Arena a, StiltPhyllosensEntity e, double gx, double gz) {
        double dx = e.getX() - (a.x0 + gx), dz = e.getZ() - (a.z0 + gz);
        if (dx * dx + dz * dz > 2.0D * 2.0D) {
            throw new net.minecraft.gametest.framework.GameTestAssertException("着いていない " + at(a, e) + " 動いている " + e.stiltWalker().moving() + " " + e.stiltWalker().debug());
        }
    }

    // ------------------------------------------------------------------ テスト

    /** IK の検算: 解いた角度で足先を順に回すと、届く目標にぴったり来る。静止の足先を与えると角度は小さい(膝の向きの決め方の差だけ)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 10, batch = "stilt")
    public static void legIkReachesTheTarget(GameTestHelper helper) {
        double[] f = {-8, 98, -7}, k = {-13, 58, -6}, s = {-17, 8, -26}, tip = {-18, 0, -27}, down = {0, -1, 0};
        double[][] targets = {{-18, 0, -27}, {-30, 20, -10}, {-10, 40, -30}, {-24, 10, 6}};
        for (double[] t : targets) {
            double[] r = StiltLegIK.solve(f, k, s, tip, t, down);
            double[] q = StiltLegIK.forward(f, k, s, tip, r);
            double d = StiltLegIK.dist(q, t);
            if (d > 0.05D) helper.fail(String.format(Locale.ROOT, "足先が目標から %.3f ずれた %s", d, java.util.Arrays.toString(t)));
            // 膝は後ろ(+Z)へ: 腿の先は、付け根 → 足首の線より後ろ
            double[] knee = StiltLegIK.add(f, StiltLegIK.mul(StiltLegIK.matrix(r[0], r[1], r[2]), StiltLegIK.sub(k, f)));
            double[] ankle = {t[0], t[1] + 8, t[2]};
            double u = (knee[1] - f[1]) / (ankle[1] - f[1]);
            double lineZ = f[2] + (ankle[2] - f[2]) * u;
            if (knee[2] <= lineZ) helper.fail(String.format(Locale.ROOT, "膝が前へ折れた 膝 z %.1f 線 %.1f", knee[2], lineZ));
        }
        double[] r = StiltLegIK.solve(f, k, s, tip, tip, down);
        for (int i = 0; i < 9; i++) {
            if (Math.abs(Math.toDegrees(r[i])) > 12.0D) helper.fail("静止の足先で角度が大きい " + i + " " + Math.toDegrees(r[i]));
        }
        helper.succeed();
    }

    /** 平地を歩く: 胴は地面から 2.5〜6.5 の高さを保ち、足先は空いた所に置き、着く。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 900, batch = "stilt")
    public static void walksOnFlatGround(GameTestHelper helper) {
        run(helper, a -> {
        }, 4.5, 16.5, 27.0, 16.0, 850, (a, e) -> {
            feetInOpen(a, e);
            if (helper.getTick() > 60) {
                double h = center(e) - FLOOR;
                if (h < 2.4D || h > 6.6D) throw new net.minecraft.gametest.framework.GameTestAssertException("胴の高さが帯の外 " + at(a, e));
            }
        }, (a, e) -> arrived(a, e, 27.0, 16.0));
    }

    /** 5 段の段差を上る(段の上の目標へ着く)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 1300, batch = "stilt")
    public static void climbsAFiveBlockStep(GameTestHelper helper) {
        run(helper, a -> fill(a.level, a.x(16), 100, a.z(0), a.x(SIZE - 1), 104, a.z(SIZE - 1), Blocks.STONE.defaultBlockState()),
                4.5, 16.5, 26.0, 16.0, 1250, StiltGameTests::feetInOpen, (a, e) -> {
                    arrived(a, e, 26.0, 16.0);
                    if (e.getY() < 105.5D) helper.fail("段の上に立っていない " + at(a, e));
                });
    }

    /** 6 段の段差は上らない(段の手前で止まる)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 700, batch = "stilt")
    public static void doesNotClimbASixBlockStep(GameTestHelper helper) {
        run(helper, a -> fill(a.level, a.x(16), 100, a.z(0), a.x(SIZE - 1), 105, a.z(SIZE - 1), Blocks.STONE.defaultBlockState()),
                4.5, 16.5, 26.0, 16.0, 650, (a, e) -> {
                    if (e.getX() - a.x0 > 16.5D) throw new net.minecraft.gametest.framework.GameTestAssertException("6 段の上へ出た " + at(a, e));
                }, (a, e) -> {
                });
    }

    /** 胴の高さの葉の天井(高さ 4〜5)をかがんでくぐる。くぐる間、胴の中心は地面から 4 より低い。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 1000, batch = "stilt")
    public static void crouchesUnderALeafCeiling(GameTestHelper helper) {
        double[] lowest = {99.0D};
        boolean[] ducked = {false};
        run(helper, a -> fill(a.level, a.x(13), 104, a.z(0), a.x(19), 105, a.z(SIZE - 1), leaves()),
                4.5, 16.5, 27.0, 16.0, 950, (a, e) -> {
                    feetInOpen(a, e);
                    double x = e.getX() - a.x0;
                    if (x > 13.5D && x < 18.5D) {
                        lowest[0] = Math.min(lowest[0], center(e) - FLOOR);
                        // 段 2(11-14): 葉の下では首を前・下へ逃がす(頭の付け根が葉の層の下にあること)
                        if (e.gaze().aiming() && e.gaze().headWorld(e).y < 104.0D) ducked[0] = true;
                    }
                }, (a, e) -> {
                    arrived(a, e, 27.0, 16.0);
                    if (lowest[0] > 4.0D) helper.fail(String.format(Locale.ROOT, "かがんでいない(葉の下の胴の高さ %.2f)", lowest[0]));
                    if (!ducked[0]) helper.fail("葉の下で首を逃がしていない(頭が葉の層に入る)");
                });
    }

    /** くぐれない葉の壁(床から高さ 9 まで)は回り込む(壁の切れ目を通って向こうの目標へ)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 1400, batch = "stilt")
    public static void walksAroundAWallItCannotPassUnder(GameTestHelper helper) {
        run(helper, a -> fill(a.level, a.x(15), 100, a.z(0), a.x(16), 108, a.z(21), leaves()),
                5.0, 8.0, 27.0, 8.0, 1350, StiltGameTests::feetInOpen, (a, e) -> arrived(a, e, 27.0, 8.0));
    }

    /**
     * 届かない目標(場の幅いっぱいの葉の壁の向こう)は覚えて待ち、230 tick 目に壁に切れ目が開いたら数秒で気づいて回り込む(11-11)。
     * 開くまでは壁を越えない。開いてから着くまでの上限は、覚える長さ(StiltParams#unreachableTicks)+ 回り道を歩く分。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 1500, batch = "stilt")
    public static void noticesWhenABlockedWayOpens(GameTestHelper helper) {
        run(helper, a -> fill(a.level, a.x(15), 100, a.z(0), a.x(16), 108, a.z(SIZE - 1), leaves()),
                5.0, 8.0, 27.0, 8.0, 1450, (a, e) -> {
                    if (helper.getTick() == 230) {
                        fill(a.level, a.x(15), 100, a.z(22), a.x(16), 108, a.z(SIZE - 1), Blocks.AIR.defaultBlockState());
                    }
                    if (helper.getTick() < 230 && e.getX() - a.x0 > 14.5D)
                        throw new net.minecraft.gametest.framework.GameTestAssertException("塞がった壁を越えた " + at(a, e));
                }, (a, e) -> arrived(a, e, 27.0, 8.0));
    }

    /** 深さ 5 の水を渡る(底に足を着いて向こう岸へ)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 1100, batch = "stilt")
    public static void wadesThroughWaterFiveDeep(GameTestHelper helper) {
        run(helper, a -> pool(a, 5), 4.5, 16.5, 27.0, 16.0, 1050, StiltGameTests::feetInOpen, (a, e) -> arrived(a, e, 27.0, 16.0));
    }

    /** 底に届かない水(深さ 8・幅 9)は渡らない。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 700, batch = "stilt")
    public static void doesNotCrossWaterTooDeep(GameTestHelper helper) {
        run(helper, a -> pool(a, 8), 4.5, 16.5, 27.0, 16.0, 650, (a, e) -> {
            if (e.getX() - a.x0 > 14.0D) throw new net.minecraft.gametest.framework.GameTestAssertException("深い水の上へ出た " + at(a, e));
        }, (a, e) -> {
        });
    }

    /** 池: 場の全体を y 99 から下へ深さ+1 段の石で埋め、x 12〜20 を水にする(場の幅いっぱい。z の両端は石の壁)。 */
    private static void pool(Arena a, int depth) {
        BlockState stone = Blocks.STONE.defaultBlockState();
        fill(a.level, a.x(0), 99 - depth, a.z(0), a.x(SIZE - 1), 99, a.z(SIZE - 1), stone);
        fill(a.level, a.x(12), 99 - depth, a.z(-1), a.x(20), 99, a.z(-1), stone);
        fill(a.level, a.x(12), 99 - depth, a.z(SIZE), a.x(20), 99, a.z(SIZE), stone);
        fill(a.level, a.x(12), 100 - depth, a.z(0), a.x(20), 99, a.z(SIZE - 1), Blocks.WATER.defaultBlockState());
    }

    /** 建物(高さ 4 の壁と屋根、場の幅いっぱい)を跨ぐ: 屋根の上に足を置いて向こうへ。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 1500, batch = "stilt")
    public static void straddlesAHouseWithFeetOnTheRoof(GameTestHelper helper) {
        boolean[] onRoof = {false};
        run(helper, a -> {
            BlockState planks = Blocks.SPRUCE_PLANKS.defaultBlockState();
            fill(a.level, a.x(13), 100, a.z(0), a.x(19), 103, a.z(SIZE - 1), planks);
            fill(a.level, a.x(14), 100, a.z(0), a.x(18), 103, a.z(SIZE - 1), Blocks.AIR.defaultBlockState());
            fill(a.level, a.x(13), 104, a.z(0), a.x(19), 104, a.z(SIZE - 1), planks);
        }, 4.5, 16.5, 27.0, 16.0, 1450, (a, e) -> {
            feetInOpen(a, e);
            StiltWalker w = e.stiltWalker();
            for (int i = 0; i < 4; i++) {
                if (i != w.swingLeg() && w.foot(i) != null && w.foot(i).y > 104.9D) onRoof[0] = true;
            }
            double x = e.getX() - a.x0;
            if (x > 13.0D && x < 20.0D && e.getY() < 105.0D)
                throw new net.minecraft.gametest.framework.GameTestAssertException("建物の中へ胴が入った " + at(a, e));
        }, (a, e) -> {
            arrived(a, e, 27.0, 16.0);
            if (!onRoof[0]) helper.fail("屋根に足を置かなかった");
        });
    }

    /** 低い天井(床から 3 の屋根。柱 4 本)の下へは入らない。目標が屋根の下でも、胴は屋根より下へ行かない。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 800, batch = "stilt")
    public static void staysOutFromUnderALowRoof(GameTestHelper helper) {
        run(helper, a -> {
            BlockState planks = Blocks.SPRUCE_PLANKS.defaultBlockState();
            fill(a.level, a.x(12), 103, a.z(10), a.x(22), 103, a.z(22), planks);
            for (int[] c : new int[][]{{12, 10}, {22, 10}, {12, 22}, {22, 22}}) fill(a.level, a.x(c[0]), 100, a.z(c[1]), a.x(c[0]), 102, a.z(c[1]), planks);
        }, 4.5, 16.5, 17.0, 16.0, 750, (a, e) -> {
            double x = e.getX() - a.x0, z = e.getZ() - a.z0;
            if (x > 11.5D && x < 23.5D && z > 9.5D && z < 23.5D && e.getY() < 103.0D)
                throw new net.minecraft.gametest.framework.GameTestAssertException("屋根の下へ胴が入った " + at(a, e));
        }, (a, e) -> {
        });
    }
}
