package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.corruption.NoisyCorruptionBurst;
import com.yayuy.overgrownvegetation.spore.SporeDeviceData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.List;

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

    private final List<BlockPos> candidates = new ArrayList<>();
    private final int intervalTicks;

    private int cursor;
    private int timer;
    private boolean finished;

    /**
     * @param origin        中心(ふつうは Mob の足元)
     * @param radius        水平方向の半径(ブロック)。0 以下なら最初から「汚し尽くした」扱い
     * @param intervalTicks 1 マス汚すごとの間隔(tick)
     */
    public InvaderSporeField(BlockPos origin, int radius, int intervalTicks, RandomSource random) {
        this.intervalTicks = Math.max(1, intervalTicks);
        if (radius <= 0) {
            this.finished = true;
            return;
        }
        record Ranked(BlockPos pos, double score) {
        }
        List<Ranked> ranked = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                double flat = Math.sqrt(dx * dx + dz * dz);
                if (flat > radius + 0.5D) {
                    continue;
                }
                for (int dy = -DEPTH_BELOW; dy <= HEIGHT_ABOVE; dy++) {
                    // 同じ距離の中では順番をばらつかせて、綺麗な同心円にならないようにする。
                    double score = flat + Math.abs(dy) * 0.5D + random.nextDouble() * 1.5D;
                    ranked.add(new Ranked(origin.offset(dx, dy, dz), score));
                }
            }
        }
        ranked.sort((a, b) -> Double.compare(a.score(), b.score()));
        for (Ranked r : ranked) {
            this.candidates.add(r.pos());
        }
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
        if (this.candidates.isEmpty()) {
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
            if (this.cursor >= this.candidates.size()) {
                this.finished = true;
                return;
            }
            BlockPos target = this.candidates.get(this.cursor++);
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
