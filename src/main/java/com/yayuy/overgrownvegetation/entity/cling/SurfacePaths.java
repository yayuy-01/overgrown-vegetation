package com.yayuy.overgrownvegetation.entity.cling;

import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;

import java.util.concurrent.CompletableFuture;

/**
 * 経路を<b>裏のスレッドで</b>引く口(2026-11-05)。サーバーのスレッドで箱の中の世界を写し({@link WorldSnapshot})、
 * A*({@link SurfacePathfinder})はバニラの裏仕事の束({@link Util#backgroundExecutor()})で回す。
 * 答えは {@link CompletableFuture} で返り、呼び出し側({@link ClingMover})が次の tick 以降に拾う。
 */
public final class SurfacePaths {

    /** 調べる点の数の上限。【仮 11-05 Claude】1 体あたり 1 回 3000 点 ≒ 箱 20³ の面を一通り。 */
    public static final int MAX_NODES = 3000;
    /** 写す箱の、出発点と行き先を囲む余白(横・縦、ブロック)。遠回りの分。【仮】 */
    private static final int MARGIN_XZ = 8;
    private static final int MARGIN_Y = 8;
    /** 写す箱の、出発点からの最大の広がり(横・縦)。行き先がこれより遠ければ途中まで引く。【仮】 */
    private static final int SPAN_XZ = 40;
    private static final int SPAN_Y = 24;

    private SurfacePaths() {
    }

    /** サーバーのスレッドで呼ぶ。写しを作って裏で引く。 */
    public static CompletableFuture<SurfacePathfinder.Result> request(ServerLevel level, SurfaceNode start,
                                                                    SurfacePathfinder.Goal goal) {
        WorldSnapshot snap = snapshotFor(level, start, goal);
        return CompletableFuture.supplyAsync(
                () -> SurfacePathfinder.find(snap, start, goal, MAX_NODES, snap), Util.backgroundExecutor());
    }

    /** その場で引く(GameTest・調べもの用)。 */
    public static SurfacePathfinder.Result findNow(ServerLevel level, SurfaceNode start, SurfacePathfinder.Goal goal) {
        WorldSnapshot snap = snapshotFor(level, start, goal);
        return SurfacePathfinder.find(snap, start, goal, MAX_NODES, snap);
    }

    static WorldSnapshot snapshotFor(ServerLevel level, SurfaceNode start, SurfacePathfinder.Goal goal) {
        BlockPos s = start.cell();
        int gx = Mth.floor(goal.box().getCenter().x);
        int gy = Mth.floor(goal.box().getCenter().y);
        int gz = Mth.floor(goal.box().getCenter().z);
        int x0 = clamp(Math.min(s.getX(), gx) - MARGIN_XZ, s.getX() - SPAN_XZ, s.getX());
        int x1 = clamp(Math.max(s.getX(), gx) + MARGIN_XZ, s.getX(), s.getX() + SPAN_XZ);
        int y0 = clamp(Math.min(s.getY(), gy) - MARGIN_Y, s.getY() - SPAN_Y, s.getY());
        int y1 = clamp(Math.max(s.getY(), gy) + MARGIN_Y, s.getY(), s.getY() + SPAN_Y);
        int z0 = clamp(Math.min(s.getZ(), gz) - MARGIN_XZ, s.getZ() - SPAN_XZ, s.getZ());
        int z1 = clamp(Math.max(s.getZ(), gz) + MARGIN_XZ, s.getZ(), s.getZ() + SPAN_XZ);
        return WorldSnapshot.capture(level, new BlockPos(x0, y0, z0), new BlockPos(x1, y1, z1));
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
