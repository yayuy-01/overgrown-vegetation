package com.yayuy.overgrownvegetation.devbridge;

import com.yayuy.overgrownvegetation.entity.PhyllosensEntity;
import com.yayuy.overgrownvegetation.entity.stilt.StiltColumns;
import com.yayuy.overgrownvegetation.entity.stilt.StiltPlanner;
import com.yayuy.overgrownvegetation.entity.stilt.StiltWalker;
import com.yayuy.overgrownvegetation.registry.ModStilt;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * テストクライアントの竹馬型の命令(2026-11-10、フィロセンス段 1)。{@link DevBridge} から呼ぶ(サーバーのスレッドで)。
 * <ul>
 *   <li>{@code navto x y z [速さ]} の行き先が竹馬型なら、ここで {@link StiltWalker#goTo} へ渡し、経路の答えと着いた / 止まったを返す。</li>
 *   <li>{@code stiltbench n cx y z0 z1}: n 体を x = cx のまわりに 3 ブロックおきに z0 へ並べ(名前 nostroll)、全員を z1 へ歩かせて、
 *       竹馬の歩き(経路を含む)に使った 1 tick の最大・平均と、着いた数を返す。</li>
 * </ul>
 */
final class StiltDev {

    private StiltDev() {
    }

    private static Mob navMob;
    private static double[] navTarget;
    private static long navUntil;
    private static boolean planLogged;

    /** 竹馬型なら行き先を渡して true。 */
    static boolean navto(Mob best, double tx, double ty, double tz, double speed, long now, Consumer<String> log) {
        if (!(best instanceof StiltWalker.Holder h)) return false;
        h.stiltWalker().goTo(tx, ty, tz, speed);
        h.stiltWalker().lastPlan = null;
        navMob = best;
        navTarget = new double[]{tx, ty, tz};
        navUntil = now + 2400L;
        planLogged = false;
        log.accept(String.format(Locale.ROOT, "NAVTO 竹馬 %s から %.1f %.1f %.1f へ", best.getType().getDescriptionId(), best.getX(), best.getY(), best.getZ()));
        return true;
    }

    /** 毎 tick(サーバーのスレッドで)。 */
    static void tick(ServerLevel level, long now, Consumer<String> log) {
        if (navMob != null) {
            Mob m = navMob;
            StiltWalker w = ((StiltWalker.Holder) m).stiltWalker();
            if (!planLogged && w.lastPlan != null) {
                planLogged = true;
                StiltPlanner.Plan p = w.lastPlan;
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < p.nodes().size(); i += Math.max(1, p.nodes().size() / 12)) {
                    StiltPlanner.Node nd = p.nodes().get(i);
                    sb.append(String.format(Locale.ROOT, " (%d,%d b%.2f g%.1f)", nd.x(), nd.z(), nd.b(), nd.ground()));
                }
                log.accept(String.format(Locale.ROOT, "NAVTO 竹馬の経路 節 %d 届く %s 調べた %d %.2f ms%s",
                        p.nodes().size(), p.reaches(), p.expanded(), p.nanos() / 1.0e6, sb));
            }
            double dx = m.getX() - navTarget[0], dz = m.getZ() - navTarget[2];
            if (now % 40L == 0L) {
                log.accept(String.format(Locale.ROOT, "NAVTO 途中 %.2f %.2f %.2f yaw %.0f %s", m.getX(), m.getY(), m.getZ(), m.getYRot(), w.debug()));
            }
            if (!m.isAlive() || now > navUntil) {
                navMob = null;
            } else if (dx * dx + dz * dz < 1.5D * 1.5D) {
                log.accept(String.format(Locale.ROOT, "NAVTO 着いた %.2f %.2f %.2f", m.getX(), m.getY(), m.getZ()));
                navMob = null;
            } else if (!w.moving()) {
                log.accept(String.format(Locale.ROOT, "NAVTO 止まった %.2f %.2f %.2f(目標まで %.1f)", m.getX(), m.getY(), m.getZ(), Math.sqrt(dx * dx + dz * dz)));
                navMob = null;
            }
        }
        if (bench != null) benchStep(level, now, log);
    }

    // ------------------------------------------------------------------ 重さの測り

    private static List<PhyllosensEntity> bench;
    private static long[] arrive;
    private static long benchStart, lastNanos, maxNanos, sumNanos, benchTicks, plan0, planMax0;
    private static double benchZ;
    private static boolean benchGo;
    private static int benchFixed;
    private static double benchSameX = Double.NaN;
    private static long gc0;

    private static long gcMillis() {
        long s = 0L;
        for (java.lang.management.GarbageCollectorMXBean b : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
            s += Math.max(0L, b.getCollectionTime());
        }
        return s;
    }

    static void bench(ServerLevel level, int n, int cx, int y, int z0, int z1, long now, Consumer<String> log) {
        bench(level, n, cx, y, z0, z1, 0, false, now, log);
    }

    /** fixed > 0 なら、着いても fixed tick まで測る(届かない目標の探し直しを測る、11-11)。 */
    static void bench(ServerLevel level, int n, int cx, int y, int z0, int z1, int fixed, boolean same, long now, Consumer<String> log) {
        benchFixed = fixed;
        benchSameX = same ? cx + 0.5D : Double.NaN;   // same = 全員が同じ目標 (cx, z1) へ(逃げたプレイヤーを群れで追う形、11-11)
        bench = new ArrayList<>();
        arrive = new long[n];
        java.util.Arrays.fill(arrive, -1L);
        StiltColumns.of(level).clear();
        StiltPlanner.forget(level);
        StiltWalker.forgetUnreachable();
        for (int i = 0; i < n; i++) {
            PhyllosensEntity e = ModStilt.PHYLLOSENS.get().create(level);
            if (e == null) continue;
            double x = cx - (n - 1) * 1.5D + 3 * i + 0.5D;
            e.moveTo(x, y, z0 + 0.5D, 0.0F, 0.0F);
            e.setCustomName(net.minecraft.network.chat.Component.literal("nostroll" + i));
            e.setPersistenceRequired();
            level.addFreshEntity(e);
            bench.add(e);
        }
        benchZ = z1 + 0.5D;
        benchGo = false;
        benchStart = now;
        lastNanos = StiltWalker.tickNanos;
        maxNanos = sumNanos = benchTicks = 0L;
        plan0 = StiltPlanner.calls;
        StiltPlanner.maxNanos = 0L;
        log.accept("STILTBENCH 始め " + bench.size() + " 体");
    }

    private static void benchStep(ServerLevel level, long now, Consumer<String> log) {
        long t = now - benchStart;
        if (t >= 5L && !benchGo) {
            benchGo = true;
            for (PhyllosensEntity e : bench) e.stiltWalker().goTo(Double.isNaN(benchSameX) ? e.getX() : benchSameX, e.getY(), benchZ, 1.0D);
            lastNanos = StiltWalker.tickNanos;
            StiltWalker.resetStats();
            gc0 = gcMillis();
        }
        long cur = StiltWalker.tickNanos;
        long d = cur - lastNanos;
        lastNanos = cur;
        if (benchGo && t > 5L) {
            maxNanos = Math.max(maxNanos, d);
            sumNanos += d;
            benchTicks++;
        }
        int done = 0;
        StringBuilder at = new StringBuilder();
        for (int i = 0; i < bench.size(); i++) {
            PhyllosensEntity e = bench.get(i);
            if (arrive[i] < 0 && Math.abs(e.getZ() - benchZ) < 1.5D) arrive[i] = t;
            if (arrive[i] >= 0) done++;
            at.append(' ').append(arrive[i]);
        }
        if ((benchFixed > 0 ? t > benchFixed : done == bench.size()) || t > 2400L) {
            log.accept(String.format(Locale.ROOT, "STILTBENCH 体 %d 着いた %d tick %d サーバー1tickの合計の最大 %.2f ms(クライアント側の差分 %.2f)平均 %.3f ms 1体1tickの最大 %.2f ms うち足 %.2f ms 経路 %d 回(1 回の最大 %.2f ms) GC %d ms 着いた tick%s",
                    bench.size(), done, t, StiltWalker.maxTickSum / 1.0e6, maxNanos / 1.0e6, sumNanos / 1.0e6 / Math.max(1, benchTicks),
                    StiltWalker.maxOneNanos / 1.0e6, StiltWalker.maxFeetNanos / 1.0e6,
                    StiltPlanner.calls - plan0, StiltPlanner.maxNanos / 1.0e6, gcMillis() - gc0, at));
            log.accept("STILTBENCH 最悪の tick " + StiltWalker.worstTick + String.format(Locale.ROOT, " / 経路 %d 回・打ち切り %d・覚えていて省いた %d", StiltWalker.plans, StiltWalker.cappedPlans, StiltWalker.skippedPlans));
            for (PhyllosensEntity e : bench) e.discard();
            bench = null;
        }
    }
}
