package com.yayuy.overgrownvegetation.client.atmosphere;

import com.yayuy.overgrownvegetation.network.GiantTreesPayload;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * <b>巨大樹の一覧の読み取り口</b>(2026-11-06 巨大樹の改善案。① 胞子の柱 {@link SporeColumns}・③ 夜の地脈の光 {@link LeyPulses} が使い、
 * ② 傘の雨だれ・⑤ 生き物と音のセッションにも開いている)。読むのは {@link #all()}(変わるたびに作り直す読み取り専用の一覧)か {@link #nearest}。
 *
 * <h2>2 つの出どころ</h2>
 * <ul>
 *     <li><b>形</b>: {@link GiantSilhouettes} が描画距離の中の巨大樹を写し取り終えるたび({@link #fromScan})。幹の軸・根元・幹の先・傘の半径と、
 *     幹の太さの断面({@link Profile})。<b>一度写した木は描画距離の外へ出ても覚えている</b>(同じワールドの間・{@value #FORGET} ブロックまで)。
 *     シルエットは霧の品質(VisualQuality.fog)が切れていると見回らないので、そのときは形が入らない。</li>
 *     <li><b>サーバーの記録</b>: {@code GiantTreesPayload}(5 秒ごと・変わったとき)。根元の x,z・胞子の出す量・地脈の貯蓄(仮の物差し)。
 *     ハイヴノードを倒された木・止めたワールド({@code /ov corruption false})の木は spore = ley = 0。この MOD の無いサーバーでは届かず NaN。</li>
 * </ul>
 * 2 つは根元の近さ({@value #MATCH} ブロック。幹の一番下の中心とサーバーの根元)で同じ木とみなす。形がまだ無い木は {@link Info#scanned()} が false
 * (軸はサーバーの根元・高さは不明 = {@link Integer#MIN_VALUE})。
 */
public final class GiantTrees {

    /** サーバーの根元と写した幹の一番下の中心を同じ木とみなす水平距離(ブロック。胞子の場の killRadius と同じ 48。仮決め)。 */
    static final double MATCH = 48.0;
    /** 写した同士を同じ木とみなす幹の先の軸の水平距離(ブロック)。 */
    private static final double SAME_AXIS = 24.0;
    /** これより遠くなった木は忘れる(ブロック)。 */
    static final double FORGET = 2048.0;

    /**
     * 1 本の巨大樹(読み取り専用の写し)。
     *
     * @param originX      サーバーの根元(届いていなければ写した幹の一番下の中心)
     * @param fromServer   サーバーの記録と結びついているか
     * @param scanned      形があるか(false なら axis はサーバーの根元・baseY / topY / crownBottomY は MIN_VALUE・profile は null)
     * @param axisX        幹の先(一番上 6 ブロック)の中心
     * @param baseY        根元(軸から 14 ブロック以内の、地上の木部の一番下)
     * @param topY         幹の先(木部の一番上)
     * @param crownRadius  傘の半径(枝の届く水平距離 + 10、16〜120)
     * @param crownBottomY 傘の下端の目安(topY − 半径 × 0.55 − 12。シルエットが傘の葉を探し始める高さ)
     * @param spore        胞子の 1 日の放出 ÷ 成木の放出(朝の上乗せ無し・発生源から外れた木は 0。届いていなければ NaN)
     * @param ley          地脈の貯蓄 0〜1(仮の物差し。届いていなければ NaN)
     */
    public record Info(int originX, int originZ, boolean fromServer, boolean scanned,
                       double axisX, double axisZ, int baseY, int topY, double crownRadius, int crownBottomY,
                       Profile profile, float spore, float ley) {
    }

    /**
     * 幹の断面(根元から幹の先まで {@value #STEP} ブロックごと)。高さごとの幹の中心と、{@value #VEINS} 方向の樹皮までの距離(中心から、外に面した木部の外側まで)。
     * 傾いた幹・板根に沿わせるのに使う。木部の途切れた高さ・方向は NaN。
     */
    public static final class Profile {
        public static final int VEINS = 8;
        public static final int STEP = 2;
        final int baseY;
        final float[] cx;
        final float[] cz;
        final float[][] radius;

        Profile(int baseY, float[] cx, float[] cz, float[][] radius) {
            this.baseY = baseY;
            this.cx = cx;
            this.cz = cz;
            this.radius = radius;
        }

        public int bands() {
            return this.cx.length;
        }

        /** 高さ y の幹の中心 x(帯の間は線形)。 */
        public double centerX(double y) {
            return sample(this.cx, y);
        }

        public double centerZ(double y) {
            return sample(this.cz, y);
        }

        /** 方向 vein(0〜{@value #VEINS}−1、角度 = 2π × vein / VEINS、x 軸から z 軸へ)の、高さ y の樹皮までの距離。無ければ NaN。 */
        public double radius(int vein, double y) {
            return sample(this.radius[Math.floorMod(vein, VEINS)], y);
        }

        /** 方向の角度(ラジアン)。 */
        public static double angle(int vein) {
            return 2.0 * Math.PI * vein / VEINS;
        }

        private double sample(float[] values, double y) {
            double f = (y - this.baseY) / STEP;
            if (f <= 0.0) {
                return values[0];
            }
            int i = (int) f;
            if (i >= values.length - 1) {
                return values[values.length - 1];
            }
            double t = f - i;
            return values[i] + (values[i + 1] - values[i]) * t;
        }
    }

    /** 中で持つ 1 本(書き換える)。 */
    private static final class Entry {
        int originX;
        int originZ;
        boolean fromServer;
        boolean scanned;
        double axisX;
        double axisZ;
        int baseY = Integer.MIN_VALUE;
        int topY = Integer.MIN_VALUE;
        double crownRadius;
        Profile profile;
        float spore = Float.NaN;
        float ley = Float.NaN;

        double baseX() {
            return this.profile != null ? this.profile.cx[0] : this.axisX;
        }

        double baseZ() {
            return this.profile != null ? this.profile.cz[0] : this.axisZ;
        }

        Info info() {
            int crownBottom = this.scanned ? (int) Math.floor(this.topY - this.crownRadius * 0.55 - 12.0) : Integer.MIN_VALUE;
            return new Info(this.originX, this.originZ, this.fromServer, this.scanned, this.axisX, this.axisZ, this.baseY, this.topY,
                    this.crownRadius, crownBottom, this.profile, this.spore, this.ley);
        }
    }

    private static final List<Entry> ENTRIES = new ArrayList<>();
    private static ClientLevel level;
    private static volatile List<Info> snapshot = List.of();
    /** 胞子の場が動いているか(最後に届いた一覧の。届いていなければ false)。 */
    private static boolean running;
    private static boolean heard;

    private GiantTrees() {
    }

    /** 今わかっている巨大樹(読み取り専用。変わるたびに作り直すので、持ち続けずに毎回呼ぶ)。 */
    public static List<Info> all() {
        checkLevel();
        return snapshot;
    }

    /** 水平距離 maxDistance 以内でいちばん近い巨大樹(幹の先の軸で測る)。無ければ null。 */
    public static Info nearest(double x, double z, double maxDistance) {
        Info best = null;
        double bestD = maxDistance * maxDistance;
        for (Info t : all()) {
            double dx = t.axisX() - x;
            double dz = t.axisZ() - z;
            double d = dx * dx + dz * dz;
            if (d <= bestD) {
                bestD = d;
                best = t;
            }
        }
        return best;
    }

    /** サーバーから一覧が届いていて、胞子の場が動いているか。 */
    public static boolean fieldRunning() {
        checkLevel();
        return heard && running;
    }

    private static void checkLevel() {
        ClientLevel now = Minecraft.getInstance().level;
        if (now != level) {
            level = now;
            ENTRIES.clear();
            snapshot = List.of();
            heard = false;
            running = false;
        }
    }

    /** サーバーの一覧({@code GiantTreesPayload})。通信はワールドに入る途中に届くことがあるので、ここでワールドの替わりを見る。 */
    public static void accept(GiantTreesPayload p) {
        checkLevel();
        heard = true;
        running = p.running();
        boolean[] matched = new boolean[p.x().length];
        // 前に結びついていた木は、一覧に無ければ(倒された・止めた・遠い)サーバーの値を 0 にする。形の無い木は捨てる。
        for (int e = ENTRIES.size() - 1; e >= 0; e--) {
            Entry entry = ENTRIES.get(e);
            int found = -1;
            for (int i = 0; i < p.x().length; i++) {
                if (!matched[i] && entry.fromServer && entry.originX == p.x()[i] && entry.originZ == p.z()[i]) {
                    found = i;
                    break;
                }
            }
            if (found < 0 && !entry.fromServer && entry.scanned) {
                found = closest(p, matched, entry.baseX(), entry.baseZ());
            }
            if (found >= 0) {
                matched[found] = true;
                entry.fromServer = true;
                entry.originX = p.x()[found];
                entry.originZ = p.z()[found];
                entry.spore = p.spore()[found];
                entry.ley = p.ley()[found];
            } else if (entry.scanned) {
                entry.fromServer = false;
                entry.spore = 0.0F;
                entry.ley = 0.0F;
            } else {
                ENTRIES.remove(e);
            }
        }
        for (int i = 0; i < p.x().length; i++) {
            if (matched[i]) {
                continue;
            }
            Entry entry = new Entry();
            entry.fromServer = true;
            entry.originX = p.x()[i];
            entry.originZ = p.z()[i];
            entry.axisX = entry.originX + 0.5;
            entry.axisZ = entry.originZ + 0.5;
            entry.spore = p.spore()[i];
            entry.ley = p.ley()[i];
            ENTRIES.add(entry);
        }
        publish();
    }

    private static int closest(GiantTreesPayload p, boolean[] matched, double x, double z) {
        int best = -1;
        double bestD = MATCH * MATCH;
        for (int i = 0; i < p.x().length; i++) {
            if (matched[i]) {
                continue;
            }
            double dx = p.x()[i] + 0.5 - x;
            double dz = p.z()[i] + 0.5 - z;
            double d = dx * dx + dz * dz;
            if (d <= bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    /**
     * {@link GiantSilhouettes} が 1 本を写し終えた(木部は地中の分を外した後)。voxels は区画ごとの中身(0 = 空・1 = 木部・2 = 葉、添字 (y &lt;&lt; 8) | (z &lt;&lt; 4) | x)。
     */
    static void fromScan(double axisX, double axisZ, int baseY, int topY, double crownRadius, Long2ObjectOpenHashMap<byte[]> voxels) {
        checkLevel();
        if (topY == Integer.MIN_VALUE || topY - baseY < 16) {
            return;
        }
        Profile profile = measure(axisX, axisZ, baseY, topY, voxels);
        Entry target = null;
        double best = SAME_AXIS * SAME_AXIS;
        for (Entry e : ENTRIES) {
            if (!e.scanned) {
                continue;
            }
            double dx = e.axisX - axisX;
            double dz = e.axisZ - axisZ;
            if (dx * dx + dz * dz <= best) {
                best = dx * dx + dz * dz;
                target = e;
            }
        }
        if (target == null) {
            // サーバーだけで知っている木(形が無い)の根元が、写した幹の一番下の近くにあればそれ
            best = MATCH * MATCH;
            for (Entry e : ENTRIES) {
                if (e.scanned || !e.fromServer) {
                    continue;
                }
                double dx = e.originX + 0.5 - profile.cx[0];
                double dz = e.originZ + 0.5 - profile.cz[0];
                if (dx * dx + dz * dz <= best) {
                    best = dx * dx + dz * dz;
                    target = e;
                }
            }
        }
        if (target == null) {
            target = new Entry();
            target.originX = (int) Math.floor(profile.cx[0]);
            target.originZ = (int) Math.floor(profile.cz[0]);
            // 一覧が届いている(この MOD のサーバー)のに結びつかない木は、胞子も地脈も 0(倒された・発生源の外)。届いていなければ NaN のまま。
            if (heard) {
                target.spore = 0.0F;
                target.ley = 0.0F;
            }
            ENTRIES.add(target);
        }
        target.scanned = true;
        target.axisX = axisX;
        target.axisZ = axisZ;
        target.baseY = baseY;
        target.topY = topY;
        target.crownRadius = crownRadius;
        target.profile = profile;
        forgetFar();
        publish();
    }

    private static void forgetFar() {
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        double f2 = FORGET * FORGET;
        ENTRIES.removeIf(e -> {
            double dx = e.axisX - player.getX();
            double dz = e.axisZ - player.getZ();
            return dx * dx + dz * dz > f2;
        });
    }

    private static void publish() {
        List<Info> list = new ArrayList<>(ENTRIES.size());
        for (Entry e : ENTRIES) {
            list.add(e.info());
        }
        snapshot = Collections.unmodifiableList(list);
    }

    // ------------------------------------------------------------------ 幹の断面

    private static boolean wood(Long2ObjectOpenHashMap<byte[]> voxels, int x, int y, int z) {
        byte[] data = voxels.get(SectionPos.asLong(x >> 4, y >> 4, z >> 4));
        return data != null && data[((y & 15) << 8) | ((z & 15) << 4) | (x & 15)] == 1;
    }

    /** 幹の先から下へ、高さごとの幹の中心(前の中心から 12 ブロック以内の木部の平均)と、方向ごとの樹皮までの距離を測る。 */
    private static Profile measure(double axisX, double axisZ, int baseY, int topY, Long2ObjectOpenHashMap<byte[]> voxels) {
        int bands = (topY - baseY) / Profile.STEP + 1;
        float[] cx = new float[bands];
        float[] cz = new float[bands];
        float[][] radius = new float[Profile.VEINS][bands];
        double centerX = axisX;
        double centerZ = axisZ;
        final int reach = 12;
        for (int b = bands - 1; b >= 0; b--) {
            int y = baseY + b * Profile.STEP;
            double sx = 0.0;
            double sz = 0.0;
            int n = 0;
            int bx = (int) Math.floor(centerX);
            int bz = (int) Math.floor(centerZ);
            for (int dz = -reach; dz <= reach; dz++) {
                for (int dx = -reach; dx <= reach; dx++) {
                    if (dx * dx + dz * dz <= reach * reach && wood(voxels, bx + dx, y, bz + dz)) {
                        sx += bx + dx + 0.5;
                        sz += bz + dz + 0.5;
                        n++;
                    }
                }
            }
            if (n > 0) {
                centerX = sx / n;
                centerZ = sz / n;
            }
            cx[b] = (float) centerX;
            cz[b] = (float) centerZ;
            for (int v = 0; v < Profile.VEINS; v++) {
                double a = Profile.angle(v);
                double ux = Math.cos(a);
                double uz = Math.sin(a);
                float outer = Float.NaN;
                boolean inWood = false;
                for (double d = 0.0; d <= 20.0; d += 0.5) {
                    boolean w = wood(voxels, (int) Math.floor(centerX + ux * d), y, (int) Math.floor(centerZ + uz * d));
                    if (w) {
                        inWood = true;
                    } else if (inWood) {
                        outer = (float) d;
                        break;
                    }
                }
                radius[v][b] = outer;
            }
        }
        return new Profile(baseY, cx, cz, radius);
    }
}
