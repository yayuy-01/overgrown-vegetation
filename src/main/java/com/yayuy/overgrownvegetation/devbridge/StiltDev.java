package com.yayuy.overgrownvegetation.devbridge;

import com.yayuy.overgrownvegetation.entity.StiltPhyllosensEntity;
import com.yayuy.overgrownvegetation.entity.stilt.StiltColumns;
import com.yayuy.overgrownvegetation.entity.stilt.StiltPlanner;
import com.yayuy.overgrownvegetation.entity.stilt.StiltWalker;
import com.yayuy.overgrownvegetation.registry.ModPhyllosens;
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
    /**
     * {@code stiltlook x y z [倒す角] [光る 0/1]} / {@code stiltlook off}(11-14、段 2 の首の確かめ): 一番近いフィロセンスの頭を (x y z) へ寄せ、そこを見させる。
     * 行動はその間首を触らない(StiltPhyllosensGaze#devHold)。
     */
    static void look(ServerLevel level, net.minecraft.world.phys.Vec3 me, String[] p, Consumer<String> log) {
        StiltPhyllosensEntity best = null;
        for (StiltPhyllosensEntity e : level.getEntitiesOfClass(StiltPhyllosensEntity.class, new net.minecraft.world.phys.AABB(me, me).inflate(64.0D))) {
            if (best == null || e.distanceToSqr(me) < best.distanceToSqr(me)) best = e;
        }
        if (best == null) {
            log.accept("STILTLOOK なし");
            return;
        }
        var gz = best.gaze();
        if (p[0].equals("off")) {
            gz.devHold = false;
            gz.aim(null, null);
            gz.lean(0.0D);
            gz.glow(false);
            log.accept("STILTLOOK 戻した");
            return;
        }
        net.minecraft.world.phys.Vec3 t = new net.minecraft.world.phys.Vec3(Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2]));
        double lean = p.length > 3 ? Double.parseDouble(p[3]) : 0.0D;
        net.minecraft.world.phys.Vec3 base = com.yayuy.overgrownvegetation.entity.stilt.StiltNeck.baseWorld(gz.shape, best.position(), best.getYRot(), lean);
        net.minecraft.world.phys.Vec3 d = t.subtract(base);
        double reach = com.yayuy.overgrownvegetation.entity.stilt.StiltNeck.reach(gz.shape);
        net.minecraft.world.phys.Vec3 head = base.add(d.normalize().scale(Math.min(reach, Math.max(0.5D, d.length() - 0.6D))));
        gz.devHold = true;
        gz.aim(head, t);
        gz.lean(lean);
        gz.glow(p.length > 4 && p[4].equals("1"));
        log.accept(String.format(Locale.ROOT, "STILTLOOK 頭 %.2f %.2f %.2f を %.2f %.2f %.2f へ(付け根 %.2f %.2f %.2f)", head.x, head.y, head.z, t.x, t.y, t.z, base.x, base.y, base.z));
    }

    // ------------------------------------------------------------------ 段 2(11-14): 見張り・覗きの重さ

    private static List<StiltPhyllosensEntity> lookBench;
    private static long lookStart, lookTicks, lookSum0, lookTicks0;
    private static int lookLen;

    /**
     * {@code stiltbrainbench watch|peer <n> <tick>}: プレイヤー(サバイバルで立つ)のまわりにフィロセンスを n 体出す。watch = 30〜60 ブロックの
     * 扇に置いてすぐ見張り、peer = 12〜20 ブロックに置いて「さっきここで見た」を入れる(屋根の下に隠れていれば開口を覗きに来る)。
     * tick の間、歩きと見る行動の 1 tick の合計の最大・平均と、最悪の tick の内訳を返す。
     */
    static void lookBench(ServerLevel level, net.minecraft.world.entity.player.Player me, String mode, int n, int ticks, Consumer<String> log) {
        lookBench = new ArrayList<>();
        StiltColumns.of(level).clear();
        StiltPlanner.forget(level);
        StiltWalker.forgetUnreachable();
        boolean watch = mode.equals("watch");
        for (int i = 0; i < n; i++) {
            StiltPhyllosensEntity e = ModStilt.PHYLLOSENS_STILT.get().create(level);
            if (e == null) continue;
            double a = Math.PI * (0.25D + 0.5D * i / Math.max(1, n - 1)) + Math.PI;
            double r = watch ? 30.0D + 3.0D * i : 12.0D + (i % 3) * 3.0D;
            e.moveTo(me.getX() + Math.cos(a) * r, me.getY(), me.getZ() + Math.sin(a) * r, 0.0F, 0.0F);
            e.setCustomName(net.minecraft.network.chat.Component.literal("nostroll" + i));
            e.setPersistenceRequired();
            level.addFreshEntity(e);
            lookBench.add(e);
        }
        lookStart = level.getGameTime();
        lookLen = ticks;
        lookSum0 = StiltWalker.tickNanos;
        log.accept("STILTBRAINBENCH 始め " + mode + " " + lookBench.size() + " 体");
        lookMode = mode;
        lookMe = me;
    }

    private static String lookMode;
    private static net.minecraft.world.entity.player.Player lookMe;
    private static boolean lookGo;

    private static void lookStep(ServerLevel level, long now, Consumer<String> log) {
        long t = now - lookStart;
        if (t >= 20L && !lookGo) {
            lookGo = true;
            for (StiltPhyllosensEntity e : lookBench) {
                if (lookMode.equals("watch")) e.brain().forceWatch();
                else e.brain().noteSeen(lookMe.position(), now);
            }
            StiltWalker.resetStats();
            com.yayuy.overgrownvegetation.entity.StiltPhyllosensBrain.nanos = 0L;
            com.yayuy.overgrownvegetation.entity.StiltPhyllosensBrain.calls = 0L;
            lookSum0 = StiltWalker.tickNanos;
            lookTicks0 = now;
        }
        if (!lookGo || t < lookLen) return;
        int marked = lookMe.hasEffect(ModPhyllosens.PHYLLOSENS_WATCH.get()) ? 1 : 0;
        StringBuilder modes = new StringBuilder();
        for (StiltPhyllosensEntity e : lookBench) modes.append(' ').append(e.brain().mode());
        long span = Math.max(1L, now - lookTicks0);
        double avg = (StiltWalker.tickNanos - lookSum0 + com.yayuy.overgrownvegetation.entity.StiltPhyllosensBrain.nanos) / 1.0e6 / span;
        log.accept(String.format(Locale.ROOT, "STILTBRAINBENCH %s 体 %d tick %d 1tickの合計の最大 %.2f ms 平均 %.3f ms 見る %.3f ms/体/tick 印 %d 状態%s",
                lookMode, lookBench.size(), span, StiltWalker.maxTickSum / 1.0e6, avg,
                com.yayuy.overgrownvegetation.entity.StiltPhyllosensBrain.nanos / 1.0e6 / Math.max(1L, com.yayuy.overgrownvegetation.entity.StiltPhyllosensBrain.calls), marked, modes));
        log.accept("STILTBRAINBENCH 最悪の tick " + StiltWalker.worstTick);
        for (StiltPhyllosensEntity e : lookBench) e.discard();
        lookBench = null;
        lookGo = false;
    }

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
        if (lookBench != null) lookStep(level, now, log);
    }

    // ------------------------------------------------------------------ 重さの測り

    private static List<StiltPhyllosensEntity> bench;
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
            StiltPhyllosensEntity e = ModStilt.PHYLLOSENS_STILT.get().create(level);
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
            for (StiltPhyllosensEntity e : bench) e.stiltWalker().goTo(Double.isNaN(benchSameX) ? e.getX() : benchSameX, e.getY(), benchZ, 1.0D);
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
            StiltPhyllosensEntity e = bench.get(i);
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
            for (StiltPhyllosensEntity e : bench) e.discard();
            bench = null;
        }
    }
}
