package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.StiltPhyllosensBrain;
import com.yayuy.overgrownvegetation.entity.StiltPhyllosensEntity;
import com.yayuy.overgrownvegetation.entity.stilt.StiltColumns;
import com.yayuy.overgrownvegetation.entity.stilt.StiltLegIK;
import com.yayuy.overgrownvegetation.entity.stilt.StiltNeck;
import com.yayuy.overgrownvegetation.entity.stilt.StiltPlanner;
import com.yayuy.overgrownvegetation.entity.stilt.StiltSight;
import com.yayuy.overgrownvegetation.entity.stilt.StiltWalker;
import com.yayuy.overgrownvegetation.registry.ModPhyllosens;
import com.yayuy.overgrownvegetation.registry.ModStilt;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 竹馬型 段 2「首の動き」(2026-11-14、フィロセンス)のテスト。見える・見えないの決まり(ガラス・板ガラス・柵・鉄格子は通す、葉・石は遮る)・
 * 首の計算の検算・化ける・見張りで見つけて印・森で幹を回り込んで覗く・隠れ直すと付かない・屋根の下を開口から覗く・塞ぐと付かない・
 * ガラス窓ごしは付く・下りた頭を叩くと引っ込む。
 *
 * <p>GameTest の偽プレイヤー({@code makeMockPlayer})は世界のプレイヤーの一覧に入らないので、相手は {@link StiltPhyllosensBrain#forcedTarget} で渡す
 * (見えるか・印・覗きの流れは本物の処理を通る)。場は段 1 と同じく、強制読み込みの遠いチャンクの y 99 の床。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class StiltNeckGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(StiltNeckGameTests.class);
        }

        private Registration() {
        }
    }

    private StiltNeckGameTests() {
    }

    static final int FLOOR = StiltGameTests.FLOOR;

    // ------------------------------------------------------------------ 見える・見えない(関数を直に。Mob を出さない)

    private static void sightThrough(GameTestHelper helper, BlockState between, boolean expect) {
        ServerLevel level = helper.getLevel();
        BlockPos mid = helper.absolutePos(new BlockPos(3, 3, 3));
        level.setBlock(mid, between, 3);
        Vec3 a = helper.absoluteVec(new Vec3(0.5D, 3.5D, 3.5D)), b = helper.absoluteVec(new Vec3(6.5D, 3.5D, 3.5D));
        boolean got = StiltSight.canSee(level, a, b);
        if (got != expect) helper.fail(between.getBlock() + " ごしに見える = " + got + "(" + expect + " のはず)");
        // 何も無ければ見える(場の確かめ)
        level.setBlock(mid, Blocks.AIR.defaultBlockState(), 3);
        if (!StiltSight.canSee(level, a, b)) helper.fail("何も無いのに見えない");
        helper.succeed();
    }

    @GameTest(template = "gametest_empty7", timeoutTicks = 10, batch = "stilt_neck")
    public static void seesThroughGlass(GameTestHelper helper) {
        sightThrough(helper, Blocks.GLASS.defaultBlockState(), true);
    }

    @GameTest(template = "gametest_empty7", timeoutTicks = 10, batch = "stilt_neck")
    public static void seesThroughGlassPane(GameTestHelper helper) {
        sightThrough(helper, Blocks.GLASS_PANE.defaultBlockState(), true);
    }

    @GameTest(template = "gametest_empty7", timeoutTicks = 10, batch = "stilt_neck")
    public static void seesThroughFence(GameTestHelper helper) {
        sightThrough(helper, Blocks.OAK_FENCE.defaultBlockState(), true);
    }

    @GameTest(template = "gametest_empty7", timeoutTicks = 10, batch = "stilt_neck")
    public static void seesThroughIronBars(GameTestHelper helper) {
        sightThrough(helper, Blocks.IRON_BARS.defaultBlockState(), true);
    }

    @GameTest(template = "gametest_empty7", timeoutTicks = 10, batch = "stilt_neck")
    public static void leavesBlockSight(GameTestHelper helper) {
        sightThrough(helper, StiltGameTests.leaves(), false);
    }

    @GameTest(template = "gametest_empty7", timeoutTicks = 10, batch = "stilt_neck")
    public static void stoneBlocksSight(GameTestHelper helper) {
        sightThrough(helper, Blocks.STONE.defaultBlockState(), false);
    }

    /** 首の計算: 静止の頭を与えると回り 0・伸び 1。届く点へは頭がぴったり来る。ワールドとの行き来が一致する。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 10, batch = "stilt_neck")
    public static void neckSolvesRestAndReachablePoints(GameTestHelper helper) {
        StiltNeck.Shape sh = StiltNeck.PHYLLOSENS;
        double[] h0 = {0, 207, -1};
        StiltNeck.Pose rest = StiltNeck.solve(sh, h0, StiltLegIK.add(h0, new double[]{0, 20, -20}));
        double[] q = rest.qEuler();
        if (Math.abs(q[0]) + Math.abs(q[1]) + Math.abs(q[2]) > 1.0E-3D || rest.s() < 0.999D) helper.fail("静止で回りが 0 でない " + rest.s());
        for (double[] w : new double[][]{{0, 180, -30}, {-40, 170, -1}, {20, 190, 10}}) {
            StiltNeck.Pose p = StiltNeck.solve(sh, w, new double[]{w[0], w[1] - 20, w[2] - 20});
            if (StiltLegIK.dist(p.head(), w) > 0.5D) helper.fail(String.format(Locale.ROOT, "頭が %s から %.2f ずれた", java.util.Arrays.toString(w), StiltLegIK.dist(p.head(), w)));
        }
        Vec3 pos = new Vec3(10, 64, 20), wpt = new Vec3(12, 70, 25);
        Vec3 back = StiltNeck.toWorld(sh, StiltNeck.toBody(sh, wpt, pos, 30.0F, 10.0D), pos, 30.0F, 10.0D);
        if (back.distanceTo(wpt) > 1.0E-6D) helper.fail("ワールドとの行き来がずれる " + back);
        helper.succeed();
    }

    // ------------------------------------------------------------------ 行動(遠いチャンクの場)

    /**
     * 場を組み、フィロセンスを (sx, sz) に置く(歩かない・うろつかない)。setup は置いた後に 1 回、each は毎 tick、end は until の tick。
     */
    private static void runBrain(GameTestHelper helper, Consumer<StiltGameTests.Arena> build, double sx, double sz, float yaw,
                                 BiConsumer<StiltGameTests.Arena, StiltPhyllosensEntity> setup,
                                 BiConsumer<StiltGameTests.Arena, StiltPhyllosensEntity> each, int until,
                                 BiConsumer<StiltGameTests.Arena, StiltPhyllosensEntity> end) {
        ServerLevel level = helper.getLevel();
        StiltWalker.alwaysNear = true;
        ChunkPos c = StiltGameTests.fresh();
        StiltGameTests.force(level, c, true);
        StiltGameTests.Arena a = new StiltGameTests.Arena(level, c, c.getMinBlockX(), c.getMinBlockZ());
        StiltPhyllosensEntity[] mob = new StiltPhyllosensEntity[1];
        helper.runAfterDelay(20, () -> {
            StiltGameTests.fill(level, a.x(-4), 88, a.z(-4), a.x(StiltGameTests.SIZE + 3), 124, a.z(StiltGameTests.SIZE + 3), Blocks.AIR.defaultBlockState());
            StiltGameTests.fill(level, a.x(0), 99, a.z(0), a.x(StiltGameTests.SIZE - 1), 99, a.z(StiltGameTests.SIZE - 1), Blocks.STONE.defaultBlockState());
            build.accept(a);
            StiltColumns.of(level).clear();
            StiltPlanner.forget(level);
            StiltWalker.forgetUnreachable();
            StiltPhyllosensEntity e = ModStilt.PHYLLOSENS_STILT.get().create(level);
            if (e == null) {
                helper.fail("作れない");
                return;
            }
            e.moveTo(a.x0() + sx, FLOOR, a.z0() + sz, yaw, 0.0F);
            e.setCustomName(Component.literal("nostroll"));
            e.setPersistenceRequired();
            level.addFreshEntity(e);
            mob[0] = e;
        });
        helper.runAfterDelay(30, () -> {
            if (mob[0] != null) setup.accept(a, mob[0]);
        });
        helper.onEachTick(() -> {
            if (mob[0] == null || helper.getTick() < 31) return;
            try {
                each.accept(a, mob[0]);
            } catch (RuntimeException ex) {
                StiltGameTests.cleanup(level, c, mob[0]);
                throw ex;
            }
        });
        helper.runAtTickTime(until, () -> {
            try {
                end.accept(a, mob[0]);
            } finally {
                StiltGameTests.cleanup(level, c, mob[0]);
            }
            helper.succeed();
        });
    }

    private static Player player(GameTestHelper helper, StiltGameTests.Arena a, double x, double y, double z) {
        Player pl = GtCompat.mockPlayer(helper, GameType.SURVIVAL);
        pl.moveTo(a.x0() + x, y, a.z0() + z, 0.0F, 0.0F);
        return pl;
    }

    private static boolean marked(Player pl) {
        return pl.hasEffect(ModPhyllosens.PHYLLOSENS_WATCH.get());
    }

    private static String why(StiltGameTests.Arena a, StiltPhyllosensEntity e) {
        StiltPhyllosensBrain b = e.brain();
        net.minecraft.world.phys.Vec3 hw = e.gaze().headWorld(e), ew = e.gaze().eyeWorld(e);
        return String.format(Locale.ROOT, "状態 %s 見える %s 見え始め %d 開口 %s 記録 [%s] 頭 (%.1f %.1f %.1f) 目 (%.1f %.1f %.1f) 効き %.2f %s", b.mode(), b.seesTarget(), b.seenSince(), b.openingCell(), b.note,
                hw.x - a.x0(), hw.y, hw.z - a.z0(), ew.x - a.x0(), ew.y, ew.z - a.z0(), e.gaze().weight(1.0F), StiltGameTests.at(a, e));
    }

    /** 止まって少しすると枯れ木に化ける(足を寄せる)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 260, batch = "stilt_neck")
    public static void disguisesWhenIdle(GameTestHelper helper) {
        runBrain(helper, a -> {
        }, 16.5, 16.5, 0.0F, (a, e) -> {
        }, (a, e) -> {
        }, 220, (a, e) -> {
            if (e.brain().mode() != StiltPhyllosensBrain.Mode.DISGUISE) helper.fail("化けていない " + why(a, e));
            StiltWalker w = e.stiltWalker();
            for (int i = 0; i < 4; i++) {
                Vec3 f = w.foot(i);
                double h = Math.hypot(f.x - e.getX(), f.z - e.getZ());
                if (h > 1.7D) helper.fail(String.format(Locale.ROOT, "足 %d が寄っていない(胴から %.2f)", i, h));
            }
        });
    }

    /** 見張りで、開けた所の人を見つけ、見え続けると印が付く。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 260, batch = "stilt_neck")
    public static void watchSpotsAndMarks(GameTestHelper helper) {
        Player[] pl = new Player[1];
        runBrain(helper, a -> {
        }, 8.5, 16.5, -90.0F, (a, e) -> {
            pl[0] = player(helper, a, 26.5D, FLOOR, 16.5D);
            e.brain().forcedTarget = pl[0];
            e.brain().forceWatch();
        }, (a, e) -> {
        }, 220, (a, e) -> {
            if (!marked(pl[0])) helper.fail("印が付かない " + why(a, e));
        });
    }

    /** 森で: 幹(2 × 2 の原木、相手と真っすぐ並ぶ)の向こうの人を、首を曲げて覗き込み、印を付ける。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 420, batch = "stilt_neck")
    public static void peeksAroundATrunkAndMarks(GameTestHelper helper) {
        Player[] pl = new Player[1];
        runBrain(helper, a -> StiltGameTests.fill(a.level(), a.x(13), 100, a.z(16), a.x(14), 114, a.z(17), Blocks.OAK_LOG.defaultBlockState()),
                8.5, 16.5, -90.0F, (a, e) -> {
                    pl[0] = player(helper, a, 19.5D, FLOOR, 16.5D);
                    e.brain().forcedTarget = pl[0];
                }, (a, e) -> {
                }, 380, (a, e) -> {
                    if (!marked(pl[0])) helper.fail("覗き込んで印を付けていない " + why(a, e));
                });
    }

    /** 見え始めてから印が付く前に、相手が囲いの中へ隠れ直すと印は付かない。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 520, batch = "stilt_neck")
    public static void hidingAgainBeforeTheMarkPreventsIt(GameTestHelper helper) {
        Player[] pl = new Player[1];
        boolean[] hidden = {false};
        runBrain(helper, a -> {
            // 隠れ場所: 石の箱(中 1 × 2 × 1、どこも開いていない)
            StiltGameTests.fill(a.level(), a.x(24), 100, a.z(24), a.x(26), 103, a.z(26), Blocks.STONE.defaultBlockState());
            StiltGameTests.fill(a.level(), a.x(25), 100, a.z(25), a.x(25), 101, a.z(25), Blocks.AIR.defaultBlockState());
        }, 8.5, 16.5, -90.0F, (a, e) -> {
            pl[0] = player(helper, a, 20.5D, FLOOR, 16.5D);
            e.brain().forcedTarget = pl[0];
        }, (a, e) -> {
            if (!hidden[0] && e.brain().seenSince() >= 0L) {
                hidden[0] = true;
                pl[0].moveTo(a.x0() + 25.5D, FLOOR, a.z0() + 25.5D, 0.0F, 0.0F);
            }
            if (marked(pl[0])) throw new GameTestAssertException("隠れ直したのに印が付いた " + why(a, e));
        }, 480, (a, e) -> {
            if (!hidden[0]) helper.fail("一度も見えなかった(場の確かめ) " + why(a, e));
        });
    }

    /** 屋根の下の相手を、入口から覗き込んで印を付ける。windowGlass = 入口でなくガラス窓。sealed = 入口を石で塞ぐ。 */
    private static void houseCase(GameTestHelper helper, boolean windowGlass, boolean sealed, boolean expectMark, int until) {
        Player[] pl = new Player[1];
        runBrain(helper, a -> {
            ServerLevel lv = a.level();
            // 家: 外 x 18〜24・z 13〜19、壁の高さ 3(y 100〜102)、屋根 y 103。入口は西の壁(x 18)の z 16、高さ 2
            StiltGameTests.fill(lv, a.x(18), 100, a.z(13), a.x(24), 103, a.z(19), Blocks.STONE.defaultBlockState());
            StiltGameTests.fill(lv, a.x(19), 100, a.z(14), a.x(23), 102, a.z(18), Blocks.AIR.defaultBlockState());
            if (windowGlass) {
                lv.setBlock(BlockPos.containing(a.x(18), 101, a.z(16)), Blocks.GLASS.defaultBlockState(), 3);
            } else if (!sealed) {
                StiltGameTests.fill(lv, a.x(18), 100, a.z(16), a.x(18), 101, a.z(16), Blocks.AIR.defaultBlockState());
            }
        }, 7.5, 16.5, -90.0F, (a, e) -> {
            pl[0] = player(helper, a, 20.5D, FLOOR, 16.5D);
            e.brain().forcedTarget = pl[0];
            e.brain().noteSeen(pl[0].position(), a.level().getGameTime());
        }, (a, e) -> {
            if (!expectMark && marked(pl[0])) throw new GameTestAssertException("塞いだのに印が付いた " + why(a, e));
        }, until, (a, e) -> {
            if (expectMark && !marked(pl[0])) helper.fail("覗いて印を付けていない " + why(a, e));
        });
    }

    @GameTest(template = "gametest_empty7", timeoutTicks = 900, batch = "stilt_neck")
    public static void peersIntoADoorwayAndMarks(GameTestHelper helper) {
        houseCase(helper, false, false, true, 860);
    }

    @GameTest(template = "gametest_empty7", timeoutTicks = 700, batch = "stilt_neck")
    public static void sealedDoorwayPreventsTheMark(GameTestHelper helper) {
        houseCase(helper, false, true, false, 660);
    }

    @GameTest(template = "gametest_empty7", timeoutTicks = 900, batch = "stilt_neck")
    public static void peersThroughAGlassWindowAndMarks(GameTestHelper helper) {
        houseCase(helper, true, false, true, 860);
    }

    /** 覗いて下ろした頭を叩くと、頭を引っ込めて(体力は胴から減る)しばらく覗かない。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 900, batch = "stilt_neck")
    public static void hittingTheLoweredHeadMakesItRetract(GameTestHelper helper) {
        Player[] pl = new Player[1];
        boolean[] hit = {false};
        float[] hp = {0.0F};
        long[] hitAt = {0L};
        runBrain(helper, a -> {
            ServerLevel lv = a.level();
            StiltGameTests.fill(lv, a.x(18), 100, a.z(13), a.x(24), 103, a.z(19), Blocks.STONE.defaultBlockState());
            StiltGameTests.fill(lv, a.x(19), 100, a.z(14), a.x(23), 102, a.z(18), Blocks.AIR.defaultBlockState());
            StiltGameTests.fill(lv, a.x(18), 100, a.z(16), a.x(18), 101, a.z(16), Blocks.AIR.defaultBlockState());
        }, 7.5, 16.5, -90.0F, (a, e) -> {
            pl[0] = player(helper, a, 22.5D, FLOOR, 14.5D);   // 入口から見えない隅(印が付く前に叩けるように)
            e.brain().forcedTarget = pl[0];
            e.brain().noteSeen(new Vec3(a.x0() + 20.5D, FLOOR, a.z0() + 16.5D), a.level().getGameTime());
        }, (a, e) -> {
            if (!hit[0] && e.brain().mode() == StiltPhyllosensBrain.Mode.PEER && e.gaze().weight(1.0F) > 0.9F) {
                hit[0] = true;
                hitAt[0] = helper.getTick();
                hp[0] = e.getHealth();
                e.hitParts().part(0).hurt(a.level().damageSources().generic(), 2.0F);
                if (e.brain().mode() != StiltPhyllosensBrain.Mode.RETRACT) throw new GameTestAssertException("叩いても引っ込めない " + why(a, e));
                if (e.getHealth() >= hp[0]) throw new GameTestAssertException("胴の体力が減らない");
            }
            if (hit[0] && helper.getTick() < hitAt[0] + 180 && (e.brain().mode() == StiltPhyllosensBrain.Mode.PEER || e.brain().mode() == StiltPhyllosensBrain.Mode.PEER_GO))
                throw new GameTestAssertException("叩かれてすぐまた覗いた " + why(a, e));
        }, 860, (a, e) -> {
            if (!hit[0]) helper.fail("覗かなかった " + why(a, e));
        });
    }

    /** 未使用の参照を残さない(Block の import)。 */
    @SuppressWarnings("unused")
    private static Block unused() {
        return Blocks.AIR;
    }
}
