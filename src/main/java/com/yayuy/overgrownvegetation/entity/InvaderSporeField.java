package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.corruption.NoisyCorruptionBurst;
import com.yayuy.overgrownvegetation.spore.SporeDeviceData;
import it.unimi.dsi.fastutil.ints.IntHeapPriorityQueue;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;

/**
 * グループ4(拡大種)が<b>その場に留まって周囲を汚染していく</b>ときの、汚染の広がり方そのもの。
 *
 * <p>始めた地点を中心に半径 {@code radius} の中の汚染できるマスを先に全部数え上げ、
 * <b>近い順</b>(同じ距離の中ではばらつかせる)に並べておく。あとは
 * {@link #spread(ServerLevel, BlockPos)} を毎 tick 呼ぶと、決められた間隔で 1 マスずつ
 * 汚していく。候補を使い切ったら {@link #isFinished()} が立つ = 「届く範囲はもう全部汚れている」。
 *
 * <p>アルボラの {@link InvaderSporeCrouchGoal}(うずくまり)と、フィロセンスの
 * {@link PhyllosensScoutGoal}(索敵)が同じものを使う。違うのは<b>半径だけ</b>で、
 * 半径は {@link AbstractOvergrownInvaderEntity#sporeCorruptionRadius()} が種ごとに返す。
 */
public final class InvaderSporeField {

    /** 足元から下へ何マスまで汚すか(足元の土・その下の石まで)。 */
    private static final int DEPTH_BELOW = 3;
    /** 足元から上へ何マスまで汚すか(草・低木の高さ)。 */
    private static final int HEIGHT_ABOVE = 1;
    /** 1 tick に候補を試す回数の上限(空気や汚染済みを素通りするぶん)。 */
    private static final int TRIES_PER_TICK = 48;

    private static final int[] NO_OFFSETS = new int[0];

    /*
     * 2026-09-24 軽量化(監査 960 / 971 / 1824): 候補の持ち方を変えた。
     *
     * 以前は候補ごとに BlockPos と record を作って全件(半径 8 で約 1,100 件)を List#sort で並べ、
     * 別の List へ写していた。歩くスポロフィルは 4 ブロック歩くたびに作り直すので、
     * 実際に使うのは先頭の十数件だけで、残りは作って並べて捨てていた。
     *
     * いまは中心からの相対位置と点数を配列に入れ、添字のヒープにしておいて
     * spread が要るぶんだけ取り出す。比べ方は「点数 → 数え上げた順」なので、
     * 安定ソートの List#sort で並べていたときとまったく同じ順に出てくる。
     * 乱数の引き方(同じループ順で候補 1 つにつき nextDouble 1 回)も以前と同じ。
     */

    /** 中心。候補は ここ + 相対位置(以前と同じ {@code origin.offset(dx, dy, dz)})。 */
    private final BlockPos origin;
    /** 候補の相対位置。添字は数え上げた順(= 乱数を引いた順)。 */
    private final int[] offsetX;
    private final int[] offsetY;
    private final int[] offsetZ;
    /** まだ試していない候補の添字。近い順(点数の小さい順)に取り出せる。 */
    private final IntHeapPriorityQueue queue;
    /** 候補の総数。0 なら以前の「候補一覧が空」と同じ扱い。 */
    private final int total;
    private final int intervalTicks;

    private int timer;
    private boolean finished;

    /**
     * @param origin        中心(ふつうは Mob の足元)
     * @param radius        水平方向の半径(ブロック)。0 以下なら最初から「汚し尽くした」扱い
     * @param intervalTicks 1 マス汚すごとの間隔(tick)
     */
    public InvaderSporeField(BlockPos origin, int radius, int intervalTicks, RandomSource random) {
        this.intervalTicks = Math.max(1, intervalTicks);
        this.origin = origin.immutable();
        if (radius <= 0) {
            this.finished = true;
            this.offsetX = NO_OFFSETS;
            this.offsetY = NO_OFFSETS;
            this.offsetZ = NO_OFFSETS;
            this.queue = new IntHeapPriorityQueue(0);
            this.total = 0;
            return;
        }
        // 先に円に入る列を数えて配列の大きさを決める(判定は下のループと同じ式。乱数は引かない)。
        int columns = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (!(Math.sqrt(dx * dx + dz * dz) > radius + 0.5D)) {
                    columns++;
                }
            }
        }
        int count = columns * (DEPTH_BELOW + HEIGHT_ABOVE + 1);
        int[] ox = new int[count];
        int[] oy = new int[count];
        int[] oz = new int[count];
        double[] score = new double[count];
        int n = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                double flat = Math.sqrt(dx * dx + dz * dz);
                if (flat > radius + 0.5D) {
                    continue;
                }
                for (int dy = -DEPTH_BELOW; dy <= HEIGHT_ABOVE; dy++) {
                    // 同じ距離の中では順番をばらつかせて、綺麗な同心円にならないようにする。
                    score[n] = flat + Math.abs(dy) * 0.5D + random.nextDouble() * 1.5D;
                    ox[n] = dx;
                    oy[n] = dy;
                    oz[n] = dz;
                    n++;
                }
            }
        }
        this.offsetX = ox;
        this.offsetY = oy;
        this.offsetZ = oz;
        this.total = n;
        int[] heap = new int[n];
        for (int i = 0; i < n; i++) {
            heap[i] = i;
        }
        // 点数の小さい順、同点なら数え上げた順(以前の安定ソートと同じ並び)。
        this.queue = new IntHeapPriorityQueue(heap, n, (a, b) -> {
            int c = Double.compare(score[a], score[b]);
            return c != 0 ? c : Integer.compare(a, b);
        });
    }

    /** 届く範囲を汚し尽くした(= もうここに居座る意味が無い)。 */
    public boolean isFinished() {
        return this.finished;
    }

    /** これまでに汚したマス数。 */
    public int corruptedCount() {
        return this.corrupted;
    }

    private int corrupted;

    /**
     * 1 tick ぶん進める。{@code intervalTicks} に 1 回だけ実際に 1 マス汚す。
     *
     * @param deviceCheckPos 胞子除去装置の効果範囲内かを見る地点(ふつうは Mob の足元)
     */
    public void spread(ServerLevel level, BlockPos deviceCheckPos) {
        if (this.finished) {
            return;
        }
        // 「最初から候補が 1 つも無い」だけを見る(取り出して減った残りの数ではない —— 以前の candidates.isEmpty() と同じ)。
        if (this.total == 0) {
            this.finished = true;
            return;
        }
        if (--this.timer > 0) {
            return;
        }
        this.timer = this.intervalTicks;
        if (SporeDeviceData.inhibits(level, deviceCheckPos, SporeDeviceData.Inhibit.CORRUPTION)) {
            // 稼働中の胞子除去装置の効果範囲内では汚染できない = ここに居座っても無駄なので切り上げる。
            this.finished = true;
            return;
        }
        for (int tries = 0; tries < TRIES_PER_TICK; tries++) {
            if (this.queue.isEmpty()) {
                this.finished = true;
                return;
            }
            int next = this.queue.dequeueInt();
            BlockPos target = this.origin.offset(this.offsetX[next], this.offsetY[next], this.offsetZ[next]);
            if (NoisyCorruptionBurst.single(level, target)) {
                this.corrupted++;
                return;
            }
        }
    }

    /** 既定の 1 マスあたりの間隔(tick)。 */
    public static int defaultInterval() {
        return OvergrownVegetationConfig.COMMON.invaderSporeCorruptionIntervalTicks.get();
    }
}
