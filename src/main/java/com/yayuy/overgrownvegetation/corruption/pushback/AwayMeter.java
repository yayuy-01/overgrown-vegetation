package com.yayuy.overgrownvegetation.corruption.pushback;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * 留守の見積もりのための計り(杭・換気機の中身が 1 つずつ持つ。保存しない。11-23 {@link AbsentWards})。
 *
 * <ul>
 *   <li>受け取った FE を {@code absent.supplyWindowSeconds} の窓で数え、最後の窓の FE/t を覚える(窓が埋まる前は途中までの値)。</li>
 *   <li>網・隣に発電機があるか({@link com.yayuy.overgrownvegetation.ley.LeyGrid#producerFeeds})を 10 秒に 1 回見る。</li>
 * </ul>
 */
public final class AwayMeter {

    private static final int LINK_CHECK_TICKS = 200;

    private long windowStart = Long.MIN_VALUE;
    private long received;
    private double lastInflow = Double.NaN;
    private boolean linked;
    private long nextLinkCheck = Long.MIN_VALUE;

    /** FE を受け取った(口の receiveEnergy の本番から)。 */
    public void received(int fe) {
        if (fe > 0) received += fe;
    }

    /** 毎 tick。 */
    public void tick(ServerLevel level, BlockPos... ports) {
        long now = level.getGameTime();
        if (windowStart == Long.MIN_VALUE || now < windowStart) {
            windowStart = now;
            received = 0;
        }
        double window = AbsentWards.designWindowTicks();
        if (now - windowStart >= window) {
            lastInflow = received / (double) (now - windowStart);
            received = 0;
            windowStart = now;
        }
        if (now >= nextLinkCheck) {
            nextLinkCheck = now + LINK_CHECK_TICKS;
            linked = com.yayuy.overgrownvegetation.ley.LeyGrid.producerFeeds(level, ports);
        }
    }

    /** 受け取っていた FE/t(最後の窓。まだ窓が埋まっていなければ途中までの値)。 */
    public double inflow(long now) {
        if (!Double.isNaN(lastInflow)) return lastInflow;
        long span = now - windowStart;
        return windowStart == Long.MIN_VALUE || span < 20 ? 0.0D : received / (double) span;
    }

    public boolean linked() {
        return linked;
    }

    /** 精算・読み込み直しのとき、窓を最初から数え直す。 */
    public void reset() {
        windowStart = Long.MIN_VALUE;
        received = 0;
        lastInflow = Double.NaN;
        nextLinkCheck = Long.MIN_VALUE;
    }
}
