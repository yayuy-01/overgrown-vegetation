package com.yayuy.overgrownvegetation.spore.field;

import com.yayuy.overgrownvegetation.data.NetherSpreadTable;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;

import java.util.Map;

/**
 * <b>ネザーの升ごとの温度</b>(2026-11-11 胞子の仕組み・寒暖差の風)。{@link SporeField.Thermal} の中身。
 *
 * <p>裏のスレッド(胞子の場の計算)から呼ぶので、ワールドは読まない。読むのは更新の始めにサーバーで取った<b>写し</b>
 * (根の台帳の汚染された時刻・海/陸の覚え・起点)と、チャンクを読み込んだときに測った溶岩の割合({@code LAVA}、並行に読める表)だけ。
 *
 * <p>仮決め(値は {@code nether_spread.json} の {@code thermal}):
 * <ul>
 *     <li>熱さ = 下地 + 溶岩の割合で上がる({@code base} + (1 − base) × min(1, 溶岩の割合 × lavaGain))。溶岩の割合を測っていないチャンクは
 *     台帳の覚えで、海 {@code unknownSea}・陸 {@code base}・わからない {@code unknownLand}</li>
 *     <li>森が冷やす: 段階ごとに熱さに掛ける {@code cool[段階]}(森でない = 1。殻・水・湖ほど冷たい)</li>
 * </ul>
 * 隣へのにじみは {@link SporeField} が 1 回する。
 */
public final class NetherThermal implements SporeField.Thermal {

    /** 更新の始めに取る台帳の写し。 */
    public record Snapshot(Long2LongOpenHashMap arrival, Long2ByteOpenHashMap terrain, long[] origins, long gameTime,
                           it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap lakeCells,
                           /** 管の樹を描き終えたチャンクの樹の数(無ければ -1)。 */
                           it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap tubes) {
    }

    private final Snapshot snap;
    private final Map<Long, Byte> lava;
    private final NetherSpreadTable t;

    NetherThermal(Snapshot snap, Map<Long, Byte> lava, NetherSpreadTable t) {
        this.snap = snap;
        this.lava = lava;
        this.t = t;
    }

    /** 森が冷やす前の熱さ(0〜1)。 */
    public double heat(int cx, int cz) {
        long key = (cx & 0xFFFFFFFFL) | ((long) cz << 32);
        Byte pct = lava.get(key);
        double base = t.thermalBase;
        if (pct != null) return base + (1 - base) * Math.min(1.0, pct / 100.0 * t.thermalLavaGain);
        byte known = snap.terrain().get(key);
        if (known == 1) return t.thermalUnknownSea;
        if (known == 0) return base;
        return t.thermalUnknownLand;
    }

    /** 台帳の段階(0 = 森でない)。 */
    public int stage(int cx, int cz) {
        long key = (cx & 0xFFFFFFFFL) | ((long) cz << 32);
        long at = snap.arrival().get(key);
        if (at == Long.MIN_VALUE) return 0;
        return t.stageAt(snap.gameTime() - at);
    }

    @Override
    public float temperature(int cx, int cz) {
        return (float) (heat(cx, cz) * t.thermalCool[stage(cx, cz)]);
    }

    /**
     * 放出の倍率: 湖のチャンク(段階 4 以上で湖の水面がある)は lakeEmitMul 倍(2026-11-11 相談役のおすすめ = ユーザー決定:
     * 湖 = いちばん冷たい所 = 風の元。小さな森でも湖ができてから 1 日ほどで速さに乗るように)。森が lakeBoostMaxChunks チャンクに
     * なったら止める(湖はやがて海の側のどこにもできるので、ずっと効かせると森が大きくなってからも速くなる)。
     */
    @Override
    public float emitFactor(int cx, int cz) {
        if (snap.arrival().size() >= t.lakeBoostMaxChunks) return 1f; // 一押しは若い森だけ(大きな森の速さは変えない)
        long key = (cx & 0xFFFFFFFFL) | ((long) cz << 32);
        if (snap.lakeCells().get(key) <= 0 || stage(cx, cz) < 4) return 1f;
        return (float) t.lakeEmitMul;
    }

    /**
     * 管の樹の多さ(0〜1。2026-11-12): 描き終えたチャンクは立てた樹の数 ÷ tubeFullTrees。まだ描いていない(読み込まれていない)チャンクは、
     * 段階 2 からの熱さ(溶岩の多さ)で見立てる(管の樹は溶岩の上の殻に密に生えるため)。段階 1 は 0。
     */
    @Override
    public float tubeFactor(int cx, int cz) {
        if (stage(cx, cz) < 2) return 0f;
        long key = (cx & 0xFFFFFFFFL) | ((long) cz << 32);
        int n = snap.tubes().get(key);
        if (n >= 0) return (float) Math.min(1.0, n / (double) t.tubeFullTrees);
        double h = (heat(cx, cz) - t.thermalBase) / Math.max(1e-6, 1 - t.thermalBase);
        return (float) Math.max(0.0, Math.min(1.0, h));
    }

    /** 起点から上限の半径の中か(0 = 上限なし)。 */
    public boolean withinRadius(int cx, int cz) {
        if (t.maxRadiusChunks <= 0 || snap.origins().length == 0) return true;
        long r2 = (long) t.maxRadiusChunks * t.maxRadiusChunks;
        for (long o : snap.origins()) {
            long dx = cx - (int) o, dz = cz - (int) (o >> 32);
            if (dx * dx + dz * dz <= r2) return true;
        }
        return false;
    }

    static long[] originsOf(LongArrayList origins) {
        return origins.toLongArray();
    }
}
