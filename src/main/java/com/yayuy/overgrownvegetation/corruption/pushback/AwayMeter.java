package com.yayuy.overgrownvegetation.corruption.pushback;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * 留守の見積もりのための計り(杭・換気機の中身が 1 つずつ持つ。保存しない。11-23 {@link AbsentWards})。
 *
 * <ul>
 *   <li><b>足りている</b>({@link #supplied}): 網・隣に発電機があり、{@code absent.supplyWindowSeconds} の窓の間にためた FE が減らなかった
 *   (または器がほぼ満ちている = 器 − {@code slack}。slack は 1 回に減りうる量の 2 倍)。受け取りの量そのものは使う量を上回れない(器が満ちると使った分しか入らない)ので、
 *   「減らないか」で余裕を見る。足りている機械は留守の間も FE が減らない。</li>
 *   <li>受け取った FE/t も数える(表示用)。</li>
 *   <li>網・隣に発電機があるか({@link com.yayuy.overgrownvegetation.ley.LeyGrid#producerFeeds})は 10 秒に 1 回見る。</li>
 * </ul>
 */
public final class AwayMeter {

    private static final int LINK_CHECK_TICKS = 200;
    /** テスト用: 窓の長さ(tick)を短くする(0 なら design の値)。 */
    public static int windowOverrideForTests;
    /** テスト用: 発電機のつながりを決め打ちする(null なら本当に見る)。 */
    public static Boolean linkedOverrideForTests;

    private long windowStart = Long.MIN_VALUE;
    private int windowStartEnergy;
    private long received;
    private double lastInflow = Double.NaN;
    private boolean linked;
    private long nextLinkCheck = Long.MIN_VALUE;
    /** 最後に埋まった窓の答え(まだ無ければ null)。 */
    private Boolean lastSupplied;

    /** FE を受け取った(口の receiveEnergy の本番から)。 */
    public void received(int fe) {
        if (fe > 0) received += fe;
    }

    /** 毎 tick({@code energy} = 今ためている FE、{@code buffer} = 器)。 */
    public void tick(ServerLevel level, int energy, int buffer, int slack, BlockPos... ports) {
        long now = level.getGameTime();
        if (now >= nextLinkCheck || now < nextLinkCheck - LINK_CHECK_TICKS) {
            nextLinkCheck = now + LINK_CHECK_TICKS;
            linked = linkedOverrideForTests != null ? linkedOverrideForTests : com.yayuy.overgrownvegetation.ley.LeyGrid.producerFeeds(level, ports);
        }
        if (windowStart == Long.MIN_VALUE || now < windowStart) {
            startWindow(now, energy);
            return;
        }
        double window = windowOverrideForTests > 0 ? windowOverrideForTests : AbsentWards.designWindowTicks();
        if (now - windowStart >= window) {
            lastInflow = received / (double) (now - windowStart);
            lastSupplied = linked && (energy >= windowStartEnergy || energy >= buffer - slack);
            startWindow(now, energy);
        }
    }

    private void startWindow(long now, int energy) {
        windowStart = now;
        windowStartEnergy = energy;
        received = 0;
    }

    /** 受け取っていた FE/t(最後の窓。まだ窓が埋まっていなければ途中までの値)。表示用。 */
    public double inflow(long now) {
        if (!Double.isNaN(lastInflow)) return lastInflow;
        long span = now - windowStart;
        return windowStart == Long.MIN_VALUE || span < 20 ? 0.0D : received / (double) span;
    }

    /** 足りているか(最後に埋まった窓。まだ無ければ「発電機があり器がほぼ満ちている」)。 */
    public boolean supplied(int energy, int buffer, int slack) {
        if (lastSupplied != null) return lastSupplied && linked;
        return linked && energy >= buffer - slack;
    }

    public boolean linked() {
        return linked;
    }

    /** 精算・読み込み直しのとき、窓を最初から数え直す。 */
    public void reset() {
        windowStart = Long.MIN_VALUE;
        received = 0;
        lastInflow = Double.NaN;
        lastSupplied = null;
        nextLinkCheck = Long.MIN_VALUE;
    }
}
