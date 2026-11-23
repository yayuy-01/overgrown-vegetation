package com.yayuy.overgrownvegetation.corruption.pushback;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.survival.vent.Ventilators;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * <b>換気機が動いている間、その範囲では胞子が発芽しない</b>(押し返し 2026-11-18 夜 ユーザー決定 C の 1 つ目)。
 * 10-22 の「除去装置は場から抜く吸い込み口で、範囲を 0 にはしない」を、発芽についてだけ上書きする。
 *
 * <p>止めるもの: 初めての発芽(台帳に載らない。場の汚染は取り消され、降り積もりも捨てる = 機械が焼き払った扱い)・2 体目の萌芽体・
 * 洞窟の床の発芽・すでにある芽の点の広がり。台帳にすでに載っているチャンクは外さない(押し返しは焼く・薬・掘るで)。
 * 見るのは動いている機械と、留守でもフィルターと FE の残る機械({@link AbsentWards}。11-23 から留守の間も守る)。
 */
public final class VentGuard {

    private static long cacheTick = Long.MIN_VALUE;
    private static long[] cacheChunks = new long[0];

    private VentGuard() {
    }

    /** チャンク (cx, cz) が動いている換気機の範囲か(オーバーワールドだけ)。1 tick に 1 回だけ機械の一覧を作る。 */
    public static boolean covers(ServerLevel level, int cx, int cz) {
        if (level.dimension() != Level.OVERWORLD) return false;
        long now = level.getGameTime();
        if (now != cacheTick) {
            cacheChunks = Ventilators.runningChunks(level);
            cacheTick = now;
        }
        if (cacheChunks.length == 0) return false;
        double r = DesignData.pushBack().vent.noSproutRadiusChunks;
        double r2 = r * r;
        for (long k : cacheChunks) {
            int dx = net.minecraft.world.level.ChunkPos.getX(k) - cx, dz = net.minecraft.world.level.ChunkPos.getZ(k) - cz;
            if (dx * dx + dz * dz <= r2) return true;
        }
        return false;
    }

    public static boolean covers(ServerLevel level, BlockPos pos) {
        return covers(level, pos.getX() >> 4, pos.getZ() >> 4);
    }

    /** テスト用: 一覧の写しを捨てる(同じ tick に機械を置いたとき)。 */
    public static void invalidate() {
        cacheTick = Long.MIN_VALUE;
    }
}
