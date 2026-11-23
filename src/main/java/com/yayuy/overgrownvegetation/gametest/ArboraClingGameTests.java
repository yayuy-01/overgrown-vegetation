package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.ArboraEntity;
import com.yayuy.overgrownvegetation.entity.cling.ClingMover;
import com.yayuy.overgrownvegetation.entity.cling.SurfaceNode;
import com.yayuy.overgrownvegetation.entity.cling.SurfacePathfinder;
import com.yayuy.overgrownvegetation.entity.cling.SurfacePaths;
import com.yayuy.overgrownvegetation.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * 全面に張り付く移動(層 1 の型 ①、{@code entity/cling})とアルボラ(2026-11-05)。
 * <ul>
 *     <li>{@link #pathWallCeilingWall} / {@link #crawlsWallCeilingWall} … 壁 → 天井 → 向かいの壁。床へ下りずに天井を渡る(経路と体の両方)。</li>
 *     <li>{@link #wrapsAroundEave} … 庇の裏に張り付いた所から、庇の縁を回って庇の上の相手へ(前の貪欲な 1 歩は庇の裏で行き詰まった)。</li>
 *     <li>{@link #reachesCabinOnPillar} … 柱 2 段の上の小屋(入口は南の 1×2)の中の相手。柱を登り、小屋の床下を回って入口から入る。</li>
 *     <li>{@link #wrapsAroundThinTrunk} … 幅 1 の幹の北の面から、巻いて南の面の高い所へ(床へ下りない・落ちない)。</li>
 *     <li>{@link #burrowsThroughLeaves} … 葉の壁(抜け道なし)の向こうの相手へ、葉の中を潜って抜ける。葉は壊さない。</li>
 *     <li>{@link #neverClingsInWater} / {@link #letsGoWhenWaterComes} … 水の中では張り付かない。張り付いているマスに水が来たら離れる。</li>
 *     <li>{@link #knockedOffByStrongHit} … 弱いノックバックでは剥がれず、強いノックバックで剥がれて落ちる。</li>
 * </ul>
 * テスト場(gametest_empty7)は床が相対 y = 1、中は y = 2〜7、y = 8 と外周はバリア(張り付ける面として数わる)。
 * 的は日に焼けない NoAI のハスク。標的はテストが毎 tick 付け直す。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class ArboraClingGameTests {

    private static final String TEMPLATE = "gametest_empty7";
    private static final String BATCH = "arbora_cling";

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterArboraClingGameTests(RegisterGameTestsEvent event) // 1.20.1: Forge の受け手の包みは名前で使い回すので、クラスごとに名前を変える {
            event.register(ArboraClingGameTests.class);
        }

        private Registration() {
        }
    }

    // ------------------------------------------------------------------ 壁 → 天井 → 壁

    /** 西の壁(x = 1)・東の壁(x = 5)・天井(y = 6)の通路。中は x = 2〜4、y = 2〜5。 */
    private static void buildArch(GameTestHelper helper) {
        BlockState stone = Blocks.STONE.defaultBlockState();
        for (int z = 0; z <= 6; z++) {
            for (int y = 2; y <= 6; y++) {
                helper.setBlock(new BlockPos(1, y, z), stone);
                helper.setBlock(new BlockPos(5, y, z), stone);
            }
            for (int x = 2; x <= 4; x++) {
                helper.setBlock(new BlockPos(x, 6, z), stone);
            }
        }
    }

    @GameTest(template = TEMPLATE, batch = BATCH)
    public static void pathWallCeilingWall(GameTestHelper helper) {
        buildArch(helper);
        ServerLevel level = helper.getLevel();
        SurfaceNode start = SurfaceNode.of(helper.absolutePos(new BlockPos(2, 4, 3)), Direction.WEST);
        Vec3 goal = helper.absoluteVec(new Vec3(4.5D, 4.5D, 3.5D));
        SurfacePathfinder.Result r = SurfacePaths.findNow(level, start,
                new SurfacePathfinder.Goal(new AABB(goal, goal).inflate(0.05D), 0.2D));
        Set<Direction> faces = EnumSet.noneOf(Direction.class);
        r.steps().forEach(s -> {
            if (s.node().face() != null) faces.add(s.node().face());
        });
        String trace = r.steps().toString();
        helper.assertTrue(r.reached(), "向かいの壁へ届かない: " + trace);
        helper.assertTrue(faces.contains(Direction.UP), "天井を通らない: " + trace);
        helper.assertFalse(faces.contains(Direction.DOWN), "床へ下りている(天井の方が近い): " + trace);
        helper.assertTrue(r.last().face() == Direction.EAST, "着いた所が東の壁でない: " + trace);
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 300, batch = BATCH)
    public static void crawlsWallCeilingWall(GameTestHelper helper) {
        buildArch(helper);
        // 西の壁の (2, 4, 3) に張り付いた所から。
        ArboraEntity a = spawn(helper, new Vec3(2.0D + 0.49D + 0.01D, 4.01D, 3.5D));
        Vec3 goal = helper.absoluteVec(new Vec3(4.5D, 4.5D, 3.5D));
        BlockPos goalCell = helper.absolutePos(new BlockPos(4, 4, 3));
        Set<Integer> seen = new java.util.HashSet<>();
        ArrayDeque<String> recent = new ArrayDeque<>();
        boolean[] done = {false};
        helper.onEachTick(() -> {
            if (done[0]) {
                return;
            }
            a.setTarget(null);
            a.mover().moveTo(goal, 1.0D, 0.2D);
            seen.add(a.mover().anchor());
            note(helper, a, recent);
            SurfaceNode n = a.mover().node();
            if (n != null && n.cell().equals(goalCell) && n.face() == Direction.EAST && a.mover().isIdle()) {
                done[0] = true;
                boolean ceiling = seen.contains(Direction.UP.get3DDataValue());
                boolean floor = seen.contains(Direction.DOWN.get3DDataValue());
                a.discard();
                if (!ceiling || floor) {
                    helper.fail("天井を渡っていない(天井 " + ceiling + "・床 " + floor + "): " + String.join(" ## ", recent));
                } else {
                    helper.succeed();
                }
                return;
            }
            if (helper.getTick() >= 280L) {
                done[0] = true;
                a.discard();
                helper.fail("向かいの壁に着かない: " + String.join(" ## ", recent));
            }
        });
    }

    // ------------------------------------------------------------------ 庇の裏

    /** 西の壁(x = 1、y = 2〜7)から庇(x = 2〜3、y = 5)が出ている。庇の裏から、縁を回って庇の上の相手へ。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 500, batch = BATCH)
    public static void wrapsAroundEave(GameTestHelper helper) {
        BlockState stone = Blocks.STONE.defaultBlockState();
        for (int z = 0; z <= 6; z++) {
            for (int y = 2; y <= 7; y++) {
                helper.setBlock(new BlockPos(1, y, z), stone);
            }
            helper.setBlock(new BlockPos(2, 5, z), stone);
            helper.setBlock(new BlockPos(3, 5, z), stone);
        }
        // 庇の裏 (2, 4, 3) の天井に張り付いた所から。
        chase(helper, "庇の上", new Vec3(2.5D, 5.0D - 0.98D - 0.01D, 3.5D), new Vec3(2.5D, 6.0D, 3.5D), 460L);
    }

    // ------------------------------------------------------------------ 柱の上の小屋

    /** 柱 (3, 2〜3, 3) の上に 3×3×4 の箱(x, z = 2〜4、y = 4〜7)。中 (3, 5〜6, 3) と南の入口 (3, 5〜6, 4) を空ける。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 900, batch = BATCH)
    public static void reachesCabinOnPillar(GameTestHelper helper) {
        BlockState stone = Blocks.STONE.defaultBlockState();
        helper.setBlock(new BlockPos(3, 2, 3), stone);
        helper.setBlock(new BlockPos(3, 3, 3), stone);
        for (int x = 2; x <= 4; x++) {
            for (int z = 2; z <= 4; z++) {
                for (int y = 4; y <= 7; y++) {
                    boolean hollow = x == 3 && (z == 3 || z == 4) && (y == 5 || y == 6);
                    if (!hollow) {
                        helper.setBlock(new BlockPos(x, y, z), stone);
                    }
                }
            }
        }
        chase(helper, "柱の上の小屋の中", new Vec3(0.5D, 2.0D, 0.5D), new Vec3(3.5D, 5.0D, 3.5D), 860L);
    }

    // ------------------------------------------------------------------ 幅 1 の幹

    /** 幅 1 の幹 (3, 2〜7, 3)。北の面の (3, 3, 2) から、南の面の (3, 6, 4) へ。床へ下りず、東か西の面を通って巻く。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 300, batch = BATCH)
    public static void wrapsAroundThinTrunk(GameTestHelper helper) {
        BlockState log = Blocks.OAK_LOG.defaultBlockState();
        for (int y = 2; y <= 7; y++) {
            helper.setBlock(new BlockPos(3, y, 3), log);
        }
        ArboraEntity a = spawn(helper, new Vec3(3.5D, 3.01D, 3.0D - 0.49D - 0.01D));
        Vec3 goal = helper.absoluteVec(new Vec3(3.5D, 6.5D, 4.5D));
        BlockPos goalCell = helper.absolutePos(new BlockPos(3, 6, 4));
        Set<Integer> seen = new java.util.HashSet<>();
        ArrayDeque<String> recent = new ArrayDeque<>();
        boolean[] done = {false};
        boolean[] fell = {false};
        helper.onEachTick(() -> {
            if (done[0]) {
                return;
            }
            a.setTarget(null);
            a.mover().moveTo(goal, 1.0D, 0.2D);
            if (helper.getTick() > 2L) {
                seen.add(a.mover().anchor());
                if (a.mover().mode() == ClingMover.Mode.FREE) {
                    fell[0] = true;
                }
            }
            note(helper, a, recent);
            SurfaceNode n = a.mover().node();
            if (n != null && n.cell().equals(goalCell) && n.face() == Direction.NORTH && a.mover().isIdle()) {
                done[0] = true;
                boolean side = seen.contains(Direction.EAST.get3DDataValue()) || seen.contains(Direction.WEST.get3DDataValue());
                boolean floor = seen.contains(Direction.DOWN.get3DDataValue());
                a.discard();
                if (!side || floor || fell[0]) {
                    helper.fail("巻いて登っていない(横の面 " + side + "・床 " + floor + "・落ちた " + fell[0] + "): "
                            + String.join(" ## ", recent));
                } else {
                    helper.succeed();
                }
                return;
            }
            if (helper.getTick() >= 280L) {
                done[0] = true;
                a.discard();
                helper.fail("南の面の上に着かない: " + String.join(" ## ", recent));
            }
        });
    }

    // ------------------------------------------------------------------ 葉の塊

    /** x = 3 の葉の壁(y = 2〜7、z 全部。天井のバリアまで届くので越えられない)の向こうの相手へ、葉の中を潜って抜ける。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 500, batch = BATCH)
    public static void burrowsThroughLeaves(GameTestHelper helper) {
        BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
        for (int z = 0; z <= 6; z++) {
            for (int y = 2; y <= 7; y++) {
                helper.setBlock(new BlockPos(3, y, z), leaves);
            }
        }
        boolean[] inFoliage = {false};
        chaseWith(helper, "葉の壁の向こう", new Vec3(0.5D, 2.0D, 3.5D), new Vec3(5.5D, 2.0D, 3.5D), 460L,
                a -> {
                    if (a.mover().inFoliage()) inFoliage[0] = true;
                },
                () -> {
                    for (int z = 0; z <= 6; z++) {
                        for (int y = 2; y <= 7; y++) {
                            if (!helper.getBlockState(new BlockPos(3, y, z)).is(Blocks.OAK_LEAVES)) {
                                return "葉が壊れた: " + new BlockPos(3, y, z);
                            }
                        }
                    }
                    return inFoliage[0] ? null : "葉の中を通っていない";
                });
    }

    // ------------------------------------------------------------------ 水

    /** 石で囲った 1×1 の水の柱(y = 2〜4)。中に入れたアルボラは、水の中にいる間いちども張り付かない。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 120, batch = BATCH)
    public static void neverClingsInWater(GameTestHelper helper) {
        BlockState stone = Blocks.STONE.defaultBlockState();
        for (int y = 2; y <= 5; y++) {
            helper.setBlock(new BlockPos(2, y, 3), stone);
            helper.setBlock(new BlockPos(4, y, 3), stone);
            helper.setBlock(new BlockPos(3, y, 2), stone);
            helper.setBlock(new BlockPos(3, y, 4), stone);
        }
        for (int y = 2; y <= 4; y++) {
            helper.setBlock(new BlockPos(3, y, 3), Blocks.WATER.defaultBlockState());
        }
        ArboraEntity a = spawn(helper, new Vec3(3.5D, 2.2D, 3.5D));
        int[] wet = {0};
        helper.onEachTick(() -> {
            a.setTarget(null);
            if (a.isInWater()) {
                wet[0]++;
                if (a.isClinging()) {
                    helper.fail("水の中で張り付いた: " + a.mover().debugState());
                }
            }
        });
        helper.runAtTickTime(100L, () -> {
            boolean ok = wet[0] > 20;
            a.discard();
            if (ok) {
                helper.succeed();
            } else {
                helper.fail("水に入っていない(" + wet[0] + " tick)");
            }
        });
    }

    /** 西の壁に張り付いている所へ水を流し込むと、離れる。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 120, batch = BATCH)
    public static void letsGoWhenWaterComes(GameTestHelper helper) {
        BlockState stone = Blocks.STONE.defaultBlockState();
        for (int z = 0; z <= 6; z++) {
            for (int y = 2; y <= 7; y++) {
                helper.setBlock(new BlockPos(1, y, z), stone);
            }
        }
        ArboraEntity a = spawn(helper, new Vec3(2.0D + 0.49D + 0.01D, 4.01D, 3.5D));
        helper.onEachTick(() -> a.setTarget(null));
        helper.runAtTickTime(10L, () -> {
            if (!a.isClinging()) {
                helper.fail("はじめに壁へ張り付いていない: " + a.mover().debugState());
                return;
            }
            helper.setBlock(new BlockPos(2, 4, 3), Blocks.WATER.defaultBlockState());
        });
        helper.runAtTickTime(25L, () -> {
            boolean clinging = a.isClinging();
            String st = a.mover().debugState();
            a.discard();
            if (clinging) {
                helper.fail("水が来ても張り付いたまま: " + st);
            } else {
                helper.succeed();
            }
        });
    }

    // ------------------------------------------------------------------ 叩き落とし

    /** 西の壁の高い所 (2, 6, 3) に張り付いたアルボラ。弱いノックバック(0.4)では剥がれず、強いの(1.0)で剥がれて落ちる。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 120, batch = BATCH)
    public static void knockedOffByStrongHit(GameTestHelper helper) {
        BlockState stone = Blocks.STONE.defaultBlockState();
        for (int z = 0; z <= 6; z++) {
            for (int y = 2; y <= 7; y++) {
                helper.setBlock(new BlockPos(1, y, z), stone);
            }
        }
        ArboraEntity a = spawn(helper, new Vec3(2.0D + 0.49D + 0.01D, 6.01D, 3.5D));
        double[] y0 = {0.0D};
        helper.onEachTick(() -> a.setTarget(null));
        helper.runAtTickTime(10L, () -> {
            if (!a.isClinging()) {
                helper.fail("はじめに壁へ張り付いていない: " + a.mover().debugState());
                return;
            }
            a.knockback(0.4D, -1.0D, 0.0D);
        });
        helper.runAtTickTime(14L, () -> {
            if (!a.isClinging()) {
                helper.fail("弱いノックバックで剥がれた: " + a.mover().debugState());
                return;
            }
            y0[0] = a.getY();
            a.knockback(1.0D, -1.0D, 0.0D);
            if (a.mover().mode() != ClingMover.Mode.FREE) {
                helper.fail("強いノックバックで剥がれない: " + a.mover().debugState());
            }
        });
        helper.runAtTickTime(40L, () -> {
            double y = a.getY();
            String st = a.mover().debugState();
            a.discard();
            if (y > y0[0] - 2.0D) {
                helper.fail(String.format(Locale.ROOT, "落ちていない: y %.2f → %.2f / %s", y0[0], y, st));
            } else {
                helper.succeed();
            }
        });
    }

    // ------------------------------------------------------------------ 低い天井(11-06 ユーザー報告)

    /** テスト場いっぱいに天井(y = {@code ceilingY})を張る。中の高さは ceilingY − 2。 */
    private static void ceiling(GameTestHelper helper, int ceilingY) {
        BlockState stone = Blocks.STONE.defaultBlockState();
        for (int x = 0; x <= 6; x++) {
            for (int z = 0; z <= 6; z++) {
                helper.setBlock(new BlockPos(x, ceilingY, z), stone);
            }
        }
    }

    /**
     * 中の高さ 3 の部屋で飛びつく。いつもの弧(頂 約 2.0 + 体 0.98)は天井に当たる高さ —— 前は天井に張り付いて攻撃しなかった
     * (11-06 ユーザー報告「天井の低いところでジャンプすると天井に引っ付いちゃって敵を攻撃できていない」)。
     * 相手は対角の約 7.8 ブロック先(近いと弧の頂より前に当たってしまい、天井に付く所まで行かない)。
     * 弧を下げて跳び、跳んでいる間いちども天井に付かず、相手に一撃入れば成功。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 300, batch = BATCH)
    public static void leapsUnderCeilingOf3(GameTestHelper helper) {
        lowCeilingLeap(helper, 5, true);
    }

    /** 中の高さ 4 の部屋。いつもの弧のまま跳べて、天井に付かずに一撃入る。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 300, batch = BATCH)
    public static void leapsUnderCeilingOf4(GameTestHelper helper) {
        lowCeilingLeap(helper, 6, false);
    }

    private static void lowCeilingLeap(GameTestHelper helper, int ceilingY, boolean fullArcHits) {
        ceiling(helper, ceilingY);
        double room = ceilingY - 2;
        double peak = com.yayuy.overgrownvegetation.entity.LeapArc.reach(1.15D, 0.55D, 24)[1];
        if ((peak + 0.98D > room) != fullArcHits) {
            helper.fail(String.format(Locale.ROOT, "場面の前提が違う: 頂 %.2f + 体 0.98 と中の高さ %.0f", peak, room));
            return;
        }
        ServerLevel level = helper.getLevel();
        Husk husk = EntityType.HUSK.create(level);
        Vec3 h = helper.absoluteVec(new Vec3(6.0D, 2.0D, 6.0D));
        husk.moveTo(h.x, h.y, h.z, 0.0F, 0.0F);
        husk.setNoAi(true);
        level.addFreshEntity(husk);
        ArboraEntity a = spawn(helper, new Vec3(0.5D, 2.0D, 0.5D));
        float max = husk.getMaxHealth();
        boolean[] started = {false};
        boolean[] done = {false};
        ArrayDeque<String> recent = new ArrayDeque<>();
        helper.onEachTick(() -> {
            if (done[0]) {
                return;
            }
            a.setTarget(husk);
            note(helper, a, recent);
            if (!started[0] && helper.getTick() >= 3L) {
                started[0] = a.startLeapForTests(husk);
            }
            String bad = null;
            if (a.isLeaping() && a.mover().anchor() == Direction.UP.get3DDataValue()) {
                bad = "跳んでいる間に天井へ張り付いた";
            } else if (husk.getHealth() < max - 0.01F || !husk.isAlive()) {
                bad = started[0] ? "" : "飛びつかずに当たった(跳べなかった)";
            } else if (helper.getTick() >= 200L) {
                bad = started[0] ? "跳んだが一撃も入らない" : "跳べない";
            }
            if (bad == null) {
                return;
            }
            done[0] = true;
            a.discard();
            husk.discard();
            if (bad.isEmpty()) {
                helper.succeed();
            } else {
                helper.fail(bad + " / 直近: " + String.join(" ## ", recent));
            }
        });
    }

    /** 中の高さ 3 の部屋の天井で、真下の相手に鎌が届く —— 天井に張り付いたまま振る(落ちて襲わない)。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 200, batch = BATCH)
    public static void scythesFromLowCeiling(GameTestHelper helper) {
        ceiling(helper, 5);
        ceilingChase(helper, 5, 160L);
    }

    /**
     * 中の高さ 4 の部屋の天井で、真下の相手に鎌は届かない。天井から落ちず(張り付いたまま壁を回って)下りてきて一撃入れる。
     * 「届かないまま天井で何もしない」にならないこと。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 600, batch = BATCH)
    public static void comesDownFromHighCeiling(GameTestHelper helper) {
        ceiling(helper, 6);
        ceilingChase(helper, 6, 560L);
    }

    private static void ceilingChase(GameTestHelper helper, int ceilingY, long limit) {
        boolean[] fell = {false};
        chaseWith(helper, "天井(y " + ceilingY + ")の真下", new Vec3(3.5D, ceilingY - 0.98D - 0.01D, 3.5D), new Vec3(3.5D, 2.0D, 3.5D), limit,
                a -> {
                    if (helper.getTick() > 2L && a.mover().mode() == ClingMover.Mode.FREE && !a.isLeaping()) fell[0] = true;
                },
                () -> fell[0] ? "天井から落ちた" : null);
    }

    // ------------------------------------------------------------------ 道具

    private static ArboraEntity spawn(GameTestHelper helper, Vec3 rel) {
        ServerLevel level = helper.getLevel();
        ArboraEntity a = ModEntities.ARBORA.get().create(level);
        if (a == null) {
            throw new IllegalStateException("アルボラを作れない");
        }
        Vec3 p = helper.absoluteVec(rel);
        a.moveTo(p.x, p.y, p.z, 0.0F, 0.0F);
        level.addFreshEntity(a);
        return a;
    }

    private static void note(GameTestHelper helper, ArboraEntity a, ArrayDeque<String> recent) {
        if (helper.getTick() % 4L != 0L) {
            return;
        }
        Vec3 p = helper.relativeVec(a.position());
        recent.addLast(String.format(Locale.ROOT, "t=%d (%.2f,%.2f,%.2f) %s", helper.getTick(), p.x, p.y, p.z, a.mover().debugState()));
        while (recent.size() > 40) {
            recent.removeFirst();
        }
    }

    private static void chase(GameTestHelper helper, String what, Vec3 arboraAt, Vec3 huskAt, long limit) {
        chaseWith(helper, what, arboraAt, huskAt, limit, a -> {
        }, () -> null);
    }

    /**
     * {@code arboraAt} のアルボラに {@code huskAt} の NoAI のハスクを追わせ、{@code limit} tick のうちに一撃入れば({@code after} が
     * null を返せば)成功。終わったら両方消す(噛み殺されたハスクがテスト場を汚さないように)。
     */
    private static void chaseWith(GameTestHelper helper, String what, Vec3 arboraAt, Vec3 huskAt, long limit,
                                  java.util.function.Consumer<ArboraEntity> each, java.util.function.Supplier<String> after) {
        ServerLevel level = helper.getLevel();
        Husk husk = EntityType.HUSK.create(level);
        if (husk == null) {
            helper.fail("ハスクを作れない");
            return;
        }
        Vec3 h = helper.absoluteVec(huskAt);
        husk.moveTo(h.x, h.y, h.z, 0.0F, 0.0F);
        husk.setNoAi(true);
        level.addFreshEntity(husk);
        ArboraEntity a = spawn(helper, arboraAt);
        float max = husk.getMaxHealth();
        ArrayDeque<String> recent = new ArrayDeque<>();
        boolean[] done = {false};
        helper.onEachTick(() -> {
            if (done[0]) {
                return;
            }
            if (a.isAlive() && husk.isAlive()) {
                a.setTarget(husk);
            }
            each.accept(a);
            note(helper, a, recent);
            if (!husk.isAlive() || husk.getHealth() < max - 0.01F) {
                done[0] = true;
                a.discard();
                husk.discard();
                String bad = after.get();
                if (bad != null) {
                    helper.fail(what + ": " + bad + " / 直近: " + String.join(" ## ", recent));
                } else {
                    helper.succeed();
                }
                return;
            }
            if (helper.getTick() >= limit) {
                done[0] = true;
                a.discard();
                husk.discard();
                helper.fail(what + "の相手へ " + limit + " tick のうちに一撃も入らない / 直近: " + String.join(" ## ", recent));
            }
        });
    }

    private ArboraClingGameTests() {
    }
}
