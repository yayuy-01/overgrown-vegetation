package com.yayuy.overgrownvegetation.skyboss;

/**
 * 頭が通った道(空気の座標)。頭が {@link #STEP} ブロック進むごとに 1 点を足す輪の列で、新しい点から古い点へたどって
 * 「頭から道に沿って s ブロック後ろの点」を出す(体の節をそこへ置く = 頭の道をなぞる曲がり)。サーバーとクライアントで同じ作り。
 */
public final class SkyBossTrail {

    /** 点の間隔(ブロック)。 */
    public static final double STEP = 8.0;

    private final double[] xs;
    private final double[] ys;
    private final double[] zs;
    /** いちばん新しい点の添字(輪の中)。 */
    private int newest = -1;
    private int size;

    public SkyBossTrail() {
        this((int) Math.ceil(SkyBossTuning.TRAIL_KEEP / STEP) + 4);
    }

    public SkyBossTrail(int capacity) {
        xs = new double[capacity];
        ys = new double[capacity];
        zs = new double[capacity];
    }

    public int capacity() {
        return xs.length;
    }

    public int size() {
        return size;
    }

    public void clear() {
        newest = -1;
        size = 0;
    }

    /** 頭が (x, y, z) へ来た: いちばん新しい点から {@link #STEP} 以上離れていたら点を足す。 */
    public void follow(double x, double y, double z) {
        if (size == 0) {
            push(x, y, z);
            return;
        }
        double dx = x - xs[newest];
        double dy = y - ys[newest];
        double dz = z - zs[newest];
        if (dx * dx + dy * dy + dz * dz >= STEP * STEP) {
            push(x, y, z);
        }
    }

    /** 点を足す(いちばん新しい点になる。満杯ならいちばん古い点が消える)。 */
    public void push(double x, double y, double z) {
        newest = (newest + 1) % xs.length;
        xs[newest] = x;
        ys[newest] = y;
        zs[newest] = z;
        size = Math.min(size + 1, xs.length);
    }

    /** i 番目に新しい点(0 = いちばん新しい)。 */
    public double x(int i) {
        return xs[index(i)];
    }

    public double y(int i) {
        return ys[index(i)];
    }

    public double z(int i) {
        return zs[index(i)];
    }

    private int index(int i) {
        return ((newest - i) % xs.length + xs.length) % xs.length;
    }

    /**
     * 頭 (hx, hy, hz) から道に沿って {@code dist[k]} ブロック後ろの点を {@code out[k]} = {x, y, z} へ書く({@code dist} は小さい順)。
     * 道より後ろは、いちばん古い区間の向きのまままっすぐ伸ばす。
     */
    public void pointsBehind(double hx, double hy, double hz, double[] dist, double[][] out) {
        double ax = hx;
        double ay = hy;
        double az = hz;
        double walked = 0.0;
        int k = 0;
        // 最後の区間の向き(道の外へ伸ばすとき用)
        double lx = 0.0;
        double ly = 0.0;
        double lz = 1.0;
        for (int i = 0; i < size && k < dist.length; i++) {
            double bx = x(i);
            double by = y(i);
            double bz = z(i);
            double dx = bx - ax;
            double dy = by - ay;
            double dz = bz - az;
            double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len < 1.0E-9) {
                continue;
            }
            lx = dx / len;
            ly = dy / len;
            lz = dz / len;
            while (k < dist.length && dist[k] <= walked + len) {
                double f = (dist[k] - walked) / len;
                out[k][0] = ax + dx * f;
                out[k][1] = ay + dy * f;
                out[k][2] = az + dz * f;
                k++;
            }
            walked += len;
            ax = bx;
            ay = by;
            az = bz;
        }
        while (k < dist.length) {
            double extra = dist[k] - walked;
            out[k][0] = ax + lx * extra;
            out[k][1] = ay + ly * extra;
            out[k][2] = az + lz * extra;
            k++;
        }
    }
}
