package com.yayuy.overgrownvegetation.organ;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * 森の器官の形(大きい版。2026-11-17)。organs セッション(Fable)が絵を描いた {@code Organs.java} の形の手順を、ほぼそのまま移したもの
 * (曲線・かたまり・ノイズ)。描画・書き出しの部分は外した。
 *
 * <p>移すときに変えた所:
 * <ul>
 *   <li>AIR を書く手順(部屋・通路・口)は {@link #CARVE}(掘る)として残す。{@link Model#get} は CARVE を AIR と答えるので、元の「空か」の判定はそのまま。
 *       世界に置くときに、掘る印の所だけブロックを消す(地下の部屋・縦穴のため)。</li>
 *   <li>地面({@code ground})は世界の地面を使うので、何もしない。人の印({@code player})も置かない。</li>
 *   <li>ノイズの種は題の hash ではなく、器官ごとの種({@link #build} の引数)。</li>
 * </ul>
 * どれも純粋な計算(世界を読まない)なので、背景のスレッドで作ってよい。
 */
public final class OrganShapes {
    private OrganShapes() {
    }

    static final int CARVE = 22;
    static final int AIR = 0, DIRT = 1, GRASS = 2, MOSS = 3, BARK = 4, BARK2 = 5, LIANA = 6, SAC = 7, SACW = 8,
            GLOW = 9, LEAF = 10, FERN = 11, PITH = 12, CAP = 13, CORE = 14, ROOT = 15, EGG = 16, CRYSTAL = 17, STONE = 18, PLAYER = 19, BURST = 20, MOSS2 = 21;
    private static final ThreadLocal<Integer> SEED = new ThreadLocal<>();

    static int h3(int x, int y, int z) { int h = x * 374761393 + y * 668265263 + z * 1274126177; h = (h ^ (h >>> 13)) * 1274126177; return (h ^ (h >>> 16)) & 0x7fffffff; }
    static double r3(int x, int y, int z) { return h3(x, y, z) / (double) 0x7fffffff; }
    static double n3(double x, double y, double z) {
        int xi = (int) Math.floor(x), yi = (int) Math.floor(y), zi = (int) Math.floor(z);
        double fx = x - xi, fy = y - yi, fz = z - zi; fx = fx * fx * (3 - 2 * fx); fy = fy * fy * (3 - 2 * fy); fz = fz * fz * (3 - 2 * fz);
        double v = 0;
        for (int i = 0; i < 8; i++) { int dx = i & 1, dy = (i >> 1) & 1, dz = (i >> 2) & 1; v += r3(xi + dx, yi + dy, zi + dz) * (dx == 1 ? fx : 1 - fx) * (dy == 1 ? fy : 1 - fy) * (dz == 1 ? fz : 1 - fz); }
        return v;
    }

    // ---------- model ----------
    static class Marker { double x, y, z; String n; int panel; Marker(double x, double y, double z, String n, int p) { this.x = x; this.y = y; this.z = z; this.n = n; panel = p; } }
    static class Model {
        int W, H, D, G; byte[][][] v; String title, sub; int seed;
        List<double[][]> inPaths = new ArrayList<>(), outPaths = new ArrayList<>();
        List<Marker> marks = new ArrayList<>(); List<String> legend = new ArrayList<>();
        List<double[]> plumes = new ArrayList<>(); // x,y,z,strength
        double[] core; double scale = 8; int cutX, cutZ; boolean dimGround;
        Model(int w, int h, int d, int g, String t, String s) { W = w; H = h; D = d; G = g; v = new byte[w][h][d]; title = t; sub = s; cutX = w / 2; cutZ = d / 2; seed = SEED.get() != null ? SEED.get() : (t.hashCode() & 0xffff); }
        boolean in(int x, int y, int z) { return x >= 0 && y >= 0 && z >= 0 && x < W && y < H && z < D; }
        int get(int x, int y, int z) { if (!in(x, y, z)) return AIR; int m = v[x][y][z]; return m == CARVE ? AIR : m; }
        /** 掘る印もそのまま返す。 */
        int raw(int x, int y, int z) { return in(x, y, z) ? v[x][y][z] : AIR; }
        void set(int x, int y, int z, int m) { if (in(x, y, z)) v[x][y][z] = (byte) (m == AIR ? CARVE : m); }
        void ground(double R) {
            // 世界の地面を使うので置かない
        }
        void blob(double cx, double cy, double cz, double rx, double ry, double rz, int m, double amp) { blob(cx, cy, cz, rx, ry, rz, m, amp, false); }
        void blob(double cx, double cy, double cz, double rx, double ry, double rz, int m, double amp, boolean onlySolid) {
            int R = (int) (Math.max(rx, Math.max(ry, rz)) * (1 + amp) + 2);
            for (int x = (int) cx - R; x <= cx + R; x++) for (int y = (int) cy - R; y <= cy + R; y++) for (int z = (int) cz - R; z <= cz + R; z++) {
                double dx = (x - cx) / rx, dy = (y - cy) / ry, dz = (z - cz) / rz;
                double k = 1 + amp * (n3(x * .22 + seed, y * .22, z * .22) - .5) * 2;
                if (dx * dx + dy * dy + dz * dz <= k * k) { if (onlySolid && get(x, y, z) == AIR) continue; set(x, y, z, m); }
            }
        }
        void tube(double[][] p, double r0, double r1, int m) {
            for (int i = 0; i + 1 < p.length; i++) {
                double len = Math.sqrt(sq(p[i + 1][0] - p[i][0]) + sq(p[i + 1][1] - p[i][1]) + sq(p[i + 1][2] - p[i][2]));
                int n = (int) (len * 2) + 1;
                for (int k = 0; k <= n; k++) {
                    double t = k / (double) n, T = (i + t) / (p.length - 1), r = r0 + (r1 - r0) * T;
                    double x = p[i][0] + (p[i + 1][0] - p[i][0]) * t, y = p[i][1] + (p[i + 1][1] - p[i][1]) * t, z = p[i][2] + (p[i + 1][2] - p[i][2]) * t;
                    for (int a = (int) Math.floor(x - r); a <= x + r; a++) for (int b = (int) Math.floor(y - r); b <= y + r; b++) for (int c = (int) Math.floor(z - r); c <= z + r; c++)
                        if (sq(a + .5 - x) + sq(b + .5 - y) + sq(c + .5 - z) <= r * r + .2) set(a, b, c, m);
                }
            }
        }
        /** らせん・曲線の点列。fn(t) -> {x,y,z} */
        double[][] curve(int n, java.util.function.DoubleFunction<double[]> f) { double[][] p = new double[n + 1][]; for (int i = 0; i <= n; i++) p[i] = f.apply(i / (double) n); return p; }
        boolean surface(int x, int y, int z) { return get(x + 1, y, z) == AIR || get(x - 1, y, z) == AIR || get(x, y + 1, z) == AIR || get(x, y - 1, z) == AIR || get(x, y, z + 1) == AIR || get(x, y, z - 1) == AIR; }
        /** 表面の from を確率 p で to に(かたまり状)。yMin..yMax の間 */
        void patch(int from, int to, double thr, double freq, int y0, int y1) {
            for (int x = 0; x < W; x++) for (int y = Math.max(0, y0); y < Math.min(H, y1); y++) for (int z = 0; z < D; z++)
                if (v[x][y][z] == from && surface(x, y, z) && n3(x * freq + to * 7, y * freq, z * freq) > thr) v[x][y][z] = (byte) to;
        }
        /** 表面に粒(胞子嚢など)を置く */
        void stud(int on, int m, double p, int y0, int y1, double r) {
            List<int[]> pts = new ArrayList<>();
            for (int x = 0; x < W; x++) for (int y = Math.max(0, y0); y < Math.min(H, y1); y++) for (int z = 0; z < D; z++)
                if ((on < 0 ? v[x][y][z] != AIR && v[x][y][z] != CARVE && v[x][y][z] != GRASS && v[x][y][z] != DIRT && v[x][y][z] != STONE : v[x][y][z] == on) && surface(x, y, z) && r3(x + m * 31, y, z + seed) < p) pts.add(new int[]{x, y, z});
            for (int[] q : pts) blob(q[0], q[1], q[2], r, r, r, m, 0);
        }
        void mark(double x, double y, double z, String n, int panel, String text) { marks.add(new Marker(x, y, z, n, panel)); if (text != null) legend.add(n + "  " + text); }
        void player(int x, int z) {
            // 人の印は置かない
        }
    }
    static double sq(double a) { return a * a; }

    // =====================================================================
    // 胞子の塔
    // =====================================================================
    static Model towerA() {
        Model m = new Model(50, 68, 50, 7, "胞子の塔 A ―「束ねた煙突」", "太い蔦の幹が 5 本、ねじれながら束になって立つ。先がそれぞれ口を開けて胞子を噴く。高さ 約 52【仮】");
        double c = 25; m.ground(23);
        m.blob(c, 11, c, 9.5, 7, 9.5, MOSS, .25);                       // 根元の苔の塊
        for (int i = 0; i < 5; i++) { double a0 = i * Math.PI * 2 / 5; int mat = i % 2 == 0 ? BARK : BARK2; double top = 44 + (i * 7 % 5) * 3;
            m.tube(m.curve(40, t -> { double R = 3.2 + 9.5 * Math.pow(1 - t, 1.6) + (t > .85 ? (t - .85) * 22 : 0), a = a0 + t * 2.6; return new double[]{c + R * Math.cos(a), 5 + t * top, c + R * Math.sin(a)}; }), 3.6, 2.2, mat);
        }
        m.patch(BARK, MOSS, .62, .16, 0, 68); m.patch(BARK2, MOSS, .66, .16, 0, 68); m.patch(MOSS, MOSS2, .7, .3, 0, 68);
        // 中: 根元の部屋と、束の真ん中を上がる煙道
        m.blob(c, 12, c, 6.5, 5, 6.5, AIR, .1);
        m.tube(new double[][]{{c, 12, c}, {c, 46, c}}, 2.4, 2.0, AIR);
        m.tube(new double[][]{{c + 15, 9, c + 10}, {c + 5, 10, c + 3}}, 1.9, 1.9, AIR);   // 入口
        for (int i = 0; i < 5; i++) { double a0 = i * Math.PI * 2 / 5, top = 44 + (i * 7 % 5) * 3, a = a0 + 2.6, R = 3.2 + .15 * 22;  // 口
            double x = c + R * Math.cos(a), z = c + R * Math.sin(a), y = 5 + top;
            m.blob(x, y + 1, z, 1.3, 2.5, 1.3, AIR, 0); m.blob(x, y - 1, z, 2.6, 1.2, 2.6, SAC, 0, true); m.blob(x, y + 1, z, 1.3, 2.5, 1.3, AIR, 0);
            m.plumes.add(new double[]{x, y + 2, z, .8});
        }
        // 煙道の中の足場(棚きのこ)
        for (int y = 15; y < 44; y += 4) { double a = y * .9; m.blob(c + 1.6 * Math.cos(a), y, c + 1.6 * Math.sin(a), 1.6, .5, 1.6, CAP, 0); }
        // 光る筋・触手・胞子嚢
        for (int i = 0; i < 5; i += 2) { double a0 = i * Math.PI * 2 / 5 + .25; m.tube(m.curve(60, t -> { double R = 3.2 + 9.5 * Math.pow(1 - t, 1.6) + 3.3, a = a0 + t * 2.6; return new double[]{c + R * Math.cos(a), 6 + t * 40, c + R * Math.sin(a)}; }), .01, .01, GLOW); }
        for (int i = 0; i < 4; i++) { double a = .5 + i * 1.7, y0 = 30 + i * 4; m.tube(m.curve(14, t -> new double[]{c + (5 + t * 11) * Math.cos(a + t * .5), y0 - t * t * 14 + Math.sin(t * 6) * 1.5, c + (5 + t * 11) * Math.sin(a + t * .5)}), 1.0, .5, LIANA); }
        m.stud(-1, SAC, .012, 14, 44, 1.5);
        m.blob(c, 40, c, 1.6, 2.2, 1.6, CORE, 0); m.core = new double[]{c, 40, c};
        m.tube(new double[][]{{c, 42, c}, {c, 45, c}}, .5, .5, LIANA);
        m.player((int) c + 17, (int) c + 11);
        m.inPaths.add(new double[][]{{c + 16, 9, c + 10.5}, {c + 5, 10, c + 3}, {c, 10, c}, {c - .5, 20, c - .5}, {c, 30, c}, {c, 37, c}});
        m.outPaths.add(m.curve(30, t -> { double R = 7 + 9.5 * Math.pow(1 - t, 1.6), a = .9 - t * 2.2; return new double[]{c + R * Math.cos(a) + 2, 8 + t * 38, c + R * Math.sin(a) + 2}; }));
        m.mark(c + 15, 10.5, c + 10, "1", 2, "入口。幹と幹のすき間。横に人(白・2 ブロック)");
        m.mark(c - 2, 12, c - 2, "2", 1, "根元の部屋。守りの Mob がいる");
        m.mark(c - 1.5, 26, c - 1.5, "3", 1, "煙道。棚きのこの足場をらせんに登る。下から胞子が吹き上がる");
        m.mark(c + 1, 40, c - 3, "芯", 1, "煙道の上、喉に吊られた光る塊。登り切らないと届かない(外の蔦からも行ける)");
        m.mark(c + 12, 28, c + 9, "4", 0, "動く蔦の触手。登る人を払い落とす");
        m.mark(c + 6, 46, c + 8, "5", 0, "噴き出し口。胞子嚢の縁取り。触れると破裂");
        m.mark(c + 9, 20, c + 5, "6", 0, "光る筋。根元から口へ明滅が上っていく(呼吸)");
        return m;
    }

    static Model towerB() {
        Model m = new Model(52, 66, 52, 7, "胞子の塔 B ―「掲げられた大嚢」", "苔の丘から 3 本の茎が伸び、巨大な胞子嚢を持ち上げている。嚢のてっぺんの口から噴く。高さ 約 50【仮】");
        double c = 26; m.ground(24);
        m.blob(c, 8, c, 15, 4.5, 15, MOSS, .3);
        double[][] feet = {{c + 13, c + 4}, {c - 9, c + 11}, {c - 5, c - 13}};
        for (int i = 0; i < 3; i++) { double fx = feet[i][0], fz = feet[i][1]; int k = i;
            m.tube(m.curve(24, t -> new double[]{fx + (c - fx) * (t * t * .75) + Math.sin(t * 5 + k) * 1.5, 7 + t * 27, fz + (c - fz) * (t * t * .75) + Math.cos(t * 4 + k) * 1.5}), i == 0 ? 3.8 : 3.0, i == 0 ? 3.4 : 2.6, i == 1 ? BARK2 : BARK); }
        m.blob(c, 41, c, 12, 13, 12, SAC, .12);                       // 大嚢
        m.patch(SAC, SACW, .6, .2, 28, 60); m.blob(c, 30, c, 9, 4, 9, BARK, .2, true);   // 嚢の底を抱える樹皮の萼
        m.blob(c, 41, c, 9, 10, 9, AIR, .05);                         // 嚢の中
        m.blob(c, 54, c, 4.2, 2.2, 4.2, BARK2, .1); m.tube(new double[][]{{c, 49, c}, {c, 58, c}}, 2.0, 2.4, AIR);   // 口
        m.plumes.add(new double[]{c, 56, c, 1.5});
        // 太い茎(0)の中が通り道
        double fx = feet[0][0], fz = feet[0][1];
        double[][] stalk = m.curve(24, t -> new double[]{fx + (c - fx) * (t * t * .75) + Math.sin(t * 5) * 1.5, 7 + t * 27, fz + (c - fz) * (t * t * .75) + Math.cos(t * 4) * 1.5});
        m.tube(stalk, 1.7, 1.7, AIR);
        m.tube(new double[][]{{c + 19, 9, c + 9}, {fx, 9, fz}}, 1.8, 1.8, AIR);
        m.patch(BARK, MOSS, .64, .16, 0, 34); m.patch(BARK2, MOSS, .64, .16, 0, 34); m.patch(MOSS, MOSS2, .7, .3, 0, 20);
        // 嚢の内側: 光る筋と、真ん中に吊られた芯
        for (int i = 0; i < 6; i++) { double a = i * Math.PI / 3; m.tube(m.curve(20, t -> { double ph = -1.2 + t * 2.5, R = 12.3 * Math.cos(ph); return new double[]{c + R * Math.cos(a), 41 + 13.2 * Math.sin(ph), c + R * Math.sin(a)}; }), .01, .01, GLOW); }
        m.blob(c, 42, c, 2, 2.6, 2, CORE, 0); m.core = new double[]{c, 42, c};
        for (int i = 0; i < 4; i++) { double a = i * Math.PI / 2 + .4; m.tube(new double[][]{{c, 44, c}, {c + 8 * Math.cos(a), 49, c + 8 * Math.sin(a)}}, .5, .5, LIANA); }
        m.blob(c, 33.5, c, 6, 1, 6, CAP, .1, false);                  // 嚢の中の床(棚きのこ)
        m.tube(new double[][]{{c + 3, 32, c + 1}, {c + 3, 35, c + 1}}, 1.5, 1.5, AIR);
        for (int i = 0; i < 5; i++) { double a = 1 + i * 1.3, y0 = 30; m.tube(m.curve(14, t -> new double[]{c + (8 + t * 5) * Math.cos(a + t * .4), y0 - t * 15 + Math.sin(t * 7) * 1.2, c + (8 + t * 5) * Math.sin(a + t * .4)}), .9, .5, LIANA); }
        m.stud(MOSS, SAC, .012, 8, 18, 1.6);
        m.player((int) c + 21, (int) c + 10);
        List<double[]> ip = new ArrayList<>(); ip.add(new double[]{c + 20, 9, c + 9.5}); for (double[] q : stalk) ip.add(q); ip.add(new double[]{c + 3, 35, c + 1}); ip.add(new double[]{c + 1, 39, c});
        m.inPaths.add(ip.toArray(new double[0][]));
        m.outPaths.add(m.curve(20, t -> new double[]{feet[1][0] + (c - feet[1][0]) * t * t * .75 - 2 + t * 2, 8 + t * 24, feet[1][1] + (c - feet[1][1]) * t * t * .75 + 3}));
        m.outPaths.add(m.curve(20, t -> { double ph = -1.0 + t * 2.3, R = 13 * Math.cos(ph); return new double[]{c - R * .2 + 3, 41 + 14 * Math.sin(ph), c + R}; }));
        m.mark(c + 19, 10.5, c + 9, "1", 2, "入口。いちばん太い茎の根元の裂け目");
        m.mark(fx - 2, 20, fz, "2", 1, "茎の中を登る(幅 3 の管。蔦のはしご)");
        m.mark(c - 4, 35, c - 3, "3", 1, "嚢の中の広間。壁が呼吸でふくらみ、光る筋が明滅する。守りの Mob");
        m.mark(c - 3, 43, c - 2, "芯", 1, "広間の真ん中に蔦で吊られた塊。吊り蔦を切るか、足場から撃つ");
        m.mark(c + 11, 22, c + 9, "4", 0, "垂れる蔦の触手(動く)。外を登る人の手がかりにもなる");
        m.mark(c + 5, 55, c + 5, "5", 0, "噴き出し口。芯を壊すと嚢がしぼんで垂れる");
        m.mark(c + 10, 9, c + 16, "6", 0, "丘の小さな胞子嚢。踏むと破裂");
        return m;
    }

    static Model towerC() {
        Model m = new Model(52, 72, 52, 14, "胞子の塔 C ―「棚の尖塔」", "傾いた苔と樹皮の尖塔に、棚きのこがらせんに付く。段ごとの穴から噴く。芯は地下の球根。高さ 約 55【仮】");
        m.cutX = 21; m.cutZ = 24; double c = 26; m.ground(24);
        double[][] axis = m.curve(30, t -> new double[]{c - 5 + t * 9 + Math.sin(t * 3.5) * 2.5, 9 + t * 59, c - 2 + t * 4 + Math.cos(t * 3) * 2});
        m.tube(axis, 10, 2.2, MOSS);
        m.patch(MOSS, BARK, .5, .1, 0, 72); m.patch(MOSS, BARK2, .6, .13, 0, 72); m.patch(MOSS, MOSS2, .68, .3, 0, 72);
        m.blob(c - 5, 6, c - 2, 7, 5.5, 7, ROOT, .2);                 // 地下の球根
        for (int i = 0; i < 6; i++) { double a = i * 1.05 + .3; m.tube(m.curve(12, t -> new double[]{c - 5 + (6 + t * 13) * Math.cos(a), 12 - t * 6 + Math.sin(t * 3) * 2.5, c - 2 + (6 + t * 13) * Math.sin(a)}), 2.0, .9, ROOT); }

        // 中: らせんの煙道
        double[][] flue = m.curve(80, t -> { double[] q = axis[(int) Math.min(30, t * 26)]; double R = Math.max(0, 4.5 * (1 - t * 1.05)), a = t * 14; return new double[]{q[0] + R * Math.cos(a), 16 + t * 44, q[2] + R * Math.sin(a)}; });
        m.tube(flue, 1.9, 1.5, AIR);
        m.blob(c - 5, 6.5, c - 2, 4.6, 3.6, 4.6, AIR, .05);           // 球根の部屋
        m.tube(new double[][]{{c - 5, 8, c - 2}, flue[0]}, 1.6, 1.6, AIR);
        m.tube(new double[][]{{c + 9, 16, c + 9}, {c - 1, 16.5, c + 1}, flue[2]}, 1.8, 1.8, AIR);
        // 棚きのこ(外の階段)と噴き出し穴
        for (int i = 0; i < 15; i++) { double t = .1 + i * .058; double[] q = axis[(int) (t * 30)]; double r = 10 - 7.8 * t, a = i * .75 + .2;
            double x = q[0] + (r + 1) * Math.cos(a), z = q[2] + (r + 1) * Math.sin(a), y = 9 + t * 59;
            m.blob(x, y, z, 3.6, .6, 3.6, CAP, 0);
            if (i % 3 == 1) { double a2 = a + 2.2, x2 = q[0] + r * Math.cos(a2), z2 = q[2] + r * Math.sin(a2); m.blob(x2, y, z2, 2.2, 2.2, 2.2, SAC, 0, true); m.blob(x2 + Math.cos(a2), y, z2 + Math.sin(a2), 1.2, 1.2, 1.2, AIR, 0); m.plumes.add(new double[]{x2 + 2 * Math.cos(a2), y, z2 + 2 * Math.sin(a2), .45}); }
        }
        m.plumes.add(new double[]{axis[30][0], 69, axis[30][2], .9});
        for (int i = 0; i < 3; i++) { double a0 = i * 2.1; m.tube(m.curve(60, t -> { double[] q = axis[(int) (t * 28)]; double r = 10.3 - 7.8 * t, a = a0 - t * 4; return new double[]{q[0] + r * Math.cos(a), 12 + t * 52, q[2] + r * Math.sin(a)}; }), .01, .01, GLOW); }
        for (int i = 0; i < 3; i++) { double a = .2 + i * 1.4, y0 = 38 + i * 6; double[] q = axis[(int) ((y0 - 9) * 30 / 59)]; m.tube(m.curve(14, t -> new double[]{q[0] + (3 + t * 10) * Math.cos(a), y0 - t * t * 12 + Math.sin(t * 6) * 1.5, q[2] + (3 + t * 10) * Math.sin(a)}), 1.0, .5, LIANA); }
        m.stud(MOSS, SAC, .006, 18, 60, 1.4);
        m.blob(c - 5, 6, c - 2, 1.8, 2.2, 1.8, CORE, 0); m.core = new double[]{c - 5, 6, c - 2};
        for (int i = 0; i < 4; i++) { double a = i * Math.PI / 2; m.tube(new double[][]{{c - 5, 6, c - 2}, {c - 5 + 4.5 * Math.cos(a), 5, c - 2 + 4.5 * Math.sin(a)}}, .5, .5, GLOW); }
        m.player((int) c + 11, (int) c + 10);
        m.inPaths.add(new double[][]{{c + 10, 16, c + 9.5}, {c - 1, 16.5, c + 1}, flue[2], flue[0], {c - 5, 9, c - 2}});
        m.inPaths.add(Arrays.copyOfRange(flue, 2, 60));
        m.outPaths.add(m.curve(40, t -> { double tt = .1 + t * .8; double[] q = axis[(int) (tt * 30)]; double r = 12 - 7.8 * tt, a = .2 + t * 14 * .75; return new double[]{q[0] + r * Math.cos(a), 9.5 + tt * 59, q[2] + r * Math.sin(a)}; }));
        m.mark(c + 10, 17.5, c + 9, "1", 2, "入口。尖塔の根元のうろ");
        m.mark(c - 9, 6.5, c - 5, "芯", 1, "地下の球根の中。入口から下へ降りる。光る根が四方へ伸びる");
        m.mark(c - 2, 34, c + 1, "2", 1, "らせんの煙道(上へ)。素材と宝は上の段に。芯とは逆向きなので「登るか・降りるか」を選ぶ");
        m.mark(c + 7, 27, c + 6, "3", 0, "棚きのこ = 外の階段。らせんに頂上まで");
        m.mark(c + 4, 47, c + 7, "4", 0, "段ごとの噴き出し穴(胞子嚢の縁)。順番に噴くので、止んだ隙に通る");
        m.mark(c + 12, 36, c + 3, "5", 0, "動く蔦の触手");
        m.mark(c + 12, 15, c - 6, "6", 0, "地面へ食い込む根。芯を壊すと尖塔が枯れて灰色になる");
        return m;
    }
    // =====================================================================
    static Model towerD() {
        Model m = new Model(58, 66, 58, 7, "胞子の塔 D ―「崩れた塔」(もっと崩れた・不気味な版)", "一度倒れかけて、そのまま育ち続けた塔。大きく傾き、腹が裂け、片側に嚢のこぶが寄り、口は曲がってばらばらの向き。高さ 約 50【仮】");
        double c = 29; m.ground(27);
        double[][] axis = m.curve(30, t -> new double[]{c - 11 + t * 22 + Math.sin(t * 5) * 4, 7 + t * 46 - Math.sin(t * 3.14) * 3, c + 3 - t * 8 + Math.cos(t * 4) * 3});
        for (int i = 0; i <= 30; i += 2) { double t = i / 30.0, r = 8.5 - 5.5 * t + Math.sin(i * 1.7) * 1.8; m.blob(axis[i][0], axis[i][1], axis[i][2], r, r * 1.1, r, i % 3 == 0 ? BARK2 : MOSS, .45); }
        m.patch(MOSS, BARK, .52, .1, 0, 66); m.patch(MOSS, MOSS2, .64, .3, 8, 66);
        m.tube(new double[][]{{c + 20, 7, c + 14}, {c + 12, 20, c + 6}, {axis[16][0] + 2, axis[16][1] - 2, axis[16][2] + 2}}, 2.6, 1.8, BARK2);   // つっかえの枯れ幹
        m.tube(new double[][]{{c - 20, 8, c - 8}, {c - 12, 14, c - 2}, {axis[8][0], axis[8][1], axis[8][2]}}, 2.2, 1.6, ROOT);
        double[] b = axis[11];
        m.blob(b[0] + 3, b[1], b[2] + 3, 7.5, 6.5, 7.5, AIR, .3);                                  // 裂けた腹
        for (int i = 0; i < 5; i++) { double yy = b[1] - 5 + i * 2.6; m.tube(m.curve(10, t -> new double[]{b[0] - 2 + t * 11, yy + Math.sin(t * 3.14) * 1.5, b[2] + 8 - t * 2 + Math.sin(t * 3.14) * 2.5}), .7, .7, i % 2 == 0 ? BARK : ROOT); }   // むき出しの肋
        m.blob(b[0] + 1, b[1] + .5, b[2] + 1, 2, 2.6, 2, CORE, 0); m.core = new double[]{b[0] + 1, b[1] + .5, b[2] + 1};
        for (int i = 0; i < 4; i++) { double a = i * 1.6; m.tube(new double[][]{{b[0] + 1, b[1] + 2, b[2] + 1}, {b[0] + 1 + 5 * Math.cos(a), b[1] + 6, b[2] + 1 + 5 * Math.sin(a)}}, .01, .01, LIANA); }
        double[][] tum = {{6, 5.5}, {12, 6.5}, {17, 5}, {21, 6}, {25, 4}};                         // 片側に寄った嚢のこぶ
        for (double[] q : tum) { double[] p = axis[(int) q[0]]; m.blob(p[0] + 4, p[1] - 1, p[2] + 6, q[1], q[1] * 1.15, q[1], q[0] % 2 == 0 ? SAC : SACW, .3); }
        int[] mouths = {14, 20, 26, 30}; double[][] dir = {{-9, 6, -6}, {8, 7, 7}, {-7, 6, 5}, {4, 8, -3}};
        for (int i = 0; i < 4; i++) { double[] p = axis[mouths[i]], d = dir[i]; double[] e = {p[0] + d[0], p[1] + d[1], p[2] + d[2]};
            m.tube(new double[][]{p, {p[0] + d[0] * .6, p[1] + d[1] * .3, p[2] + d[2] * .6}, e}, 2.6, 1.9, i % 2 == 0 ? BARK : BARK2);
            m.blob(e[0], e[1], e[2], 2.6, 1.3, 2.6, SAC, .2); m.blob(e[0], e[1] + .5, e[2], 1.3, 2, 1.3, AIR, 0); m.plumes.add(new double[]{e[0], e[1] + 1, e[2], .7}); }
        for (int i = 0; i < 12; i++) { double[] p = axis[8 + i * 2 > 30 ? 30 : 8 + i * 2]; double ox = (i * 7 % 9) - 4, oz = (i * 5 % 9) - 3, len = 8 + (i * 11 % 13); m.tube(m.curve(8, t -> new double[]{p[0] + ox + Math.sin(t * 4) * .8, p[1] - 3 - t * len, p[2] + oz + 3}), .6, .4, LIANA); if (i % 3 == 0) m.blob(p[0] + ox, p[1] - 4 - len, p[2] + oz + 3, 1.3, 1.7, 1.3, SAC, 0); }
        for (int i = 0; i < 3; i++) { int k = i; m.tube(m.curve(60, t -> { double[] p = axis[(int) (t * 29)]; double r = 9 - 5.5 * t, a = k * 2.1 + t * 3 + Math.sin(t * 20) * .3; return new double[]{p[0] + r * Math.cos(a), p[1], p[2] + r * Math.sin(a)}; }), .01, .01, GLOW); }
        m.tube(new double[][]{{b[0] + 3, b[1] - 2, b[2] + 3}, {axis[5][0] + 2, axis[5][1], axis[5][2] + 2}, {c + 2, 9, c + 12}}, 1.8, 1.9, AIR);
        m.stud(MOSS, SAC, .008, 12, 55, 1.3);
        m.player((int) c + 3, (int) c + 15);
        m.cutX = (int) (b[0] + 1); m.cutZ = (int) (b[2] + 1);
        m.inPaths.add(new double[][]{{c + 2, 9, c + 14}, {axis[5][0] + 2, axis[5][1], axis[5][2] + 2}, {b[0] + 3, b[1] - 2, b[2] + 3}});
        m.outPaths.add(new double[][]{{c + 20, 9, c + 15}, {c + 12, 21, c + 7}, {axis[16][0] + 3, axis[16][1], axis[16][2] + 3}, {axis[26][0] + 2, axis[26][1], axis[26][2] + 3}});
        m.mark(c + 2, 10.5, c + 13, "1", 2, "入口。根元の裂け目。ほかに、裂けた腹へ外から直接入れる");
        m.mark(b[0] + 6, b[1] + 3, b[2] + 6, "2", 0, "裂けた腹。むき出しの肋の奥に芯が見えている(見えるが、肋と触手が邪魔で近づきにくい)");
        m.mark(b[0] - 2, b[1] + 1, b[2] - 2, "芯", 1, "腹の中に蔦で吊られた塊。外から光が漏れて、遠くからも脈打つのが見える");
        m.mark(axis[17][0] + 6, axis[17][1], axis[17][2] + 9, "3", 0, "片側に寄った嚢のこぶ(腫れ物のよう)。重みで塔がそちらへ傾く。触れると破裂");
        m.mark(axis[20][0] + 8, axis[20][1] + 8, axis[20][2] + 7, "4", 0, "ばらばらの向きの噴き出し口。噴くたびに塔全体がよじれる");
        m.mark(axis[14][0] + 1, axis[14][1] - 12, axis[14][2] + 5, "5", 0, "だらりと垂れた蔦の束(動く)。先に嚢。くぐると絡みつく");
        m.mark(c + 15, 16, c + 10, "6", 0, "つっかえ棒になった枯れ幹。外から登る道");
        return m;
    }
    /** 崩れた版: 元の形を傾け・うねらせ、かじられた穴・腫れた嚢・垂れた蔦・枯れを足す */
    static Model ruin(Model s, String title, String sub, double lx, double lz, int seed, String extra) {
        int P = 8; Model m = new Model(s.W + 2 * P, s.H, s.D + 2 * P, s.G, title, sub); m.scale = s.scale;
        java.util.function.IntFunction<double[]> sh = y -> { double h = Math.max(0, y - s.G - 1); return new double[]{P + lx * h + Math.sin(h * .16 + seed) * 2.6, P + lz * h + Math.cos(h * .13 + seed) * 2.2}; };
        for (int y = 0; y < m.H; y++) { double[] d = sh.apply(y); for (int x = 0; x < m.W; x++) for (int z = 0; z < m.D; z++) { int v = s.raw((int) Math.round(x - d[0]), y, (int) Math.round(z - d[1])); if (v != AIR && v != PLAYER) m.set(x, y, z, v); } }
        java.util.function.Function<double[], double[]> f = p -> { double[] d = sh.apply((int) p[1]); return new double[]{p[0] + d[0], p[1], p[2] + d[1]}; };
        m.core = f.apply(s.core); m.cutX = (int) Math.round(m.core[0]); m.cutZ = (int) Math.round(m.core[2]);
        Random r = new Random(seed);
        List<int[]> surf = new ArrayList<>();
        for (int x = 0; x < m.W; x++) for (int y = s.G + 5; y < m.H; y++) for (int z = 0; z < m.D; z++) if (m.get(x, y, z) != AIR && m.surface(x, y, z)) surf.add(new int[]{x, y, z});
        if (!surf.isEmpty()) {
            for (int i = 0, n = 0; i < 400 && n < 4; i++) { int[] q = surf.get(r.nextInt(surf.size())); if (Math.abs(q[0] - m.core[0]) + Math.abs(q[1] - m.core[1]) + Math.abs(q[2] - m.core[2]) < 14) continue; double rr = 4.5 + r.nextDouble() * 3; m.blob(q[0], q[1], q[2], rr, rr * 1.2, rr, AIR, .45); n++; }
            for (int i = 0, n = 0; i < 400 && n < 6; i++) { int[] q = surf.get(r.nextInt(surf.size())); if (m.get(q[0], q[1], q[2]) == AIR || q[0] + q[2] < m.core[0] + m.core[2]) continue; double rr = 2.6 + r.nextDouble() * 2.6; m.blob(q[0] + 1, q[1], q[2] + 1, rr, rr * 1.15, rr, n % 3 == 2 ? SACW : SAC, .3); n++; }
            for (int i = 0, n = 0; i < 3000 && n < 16; i++) { int[] q = surf.get(r.nextInt(surf.size())); if (m.get(q[0], q[1], q[2]) == AIR) continue; boolean free = true; for (int k = 1; k <= 6; k++) if (m.get(q[0], q[1] - k, q[2]) != AIR) free = false; if (!free) continue;
                int len = 5 + r.nextInt(10); for (int k = 1; k <= len && m.get(q[0], q[1] - k, q[2]) == AIR; k++) m.set(q[0] + (k % 5 == 4 ? 1 : 0), q[1] - k, q[2], LIANA); if (n % 4 == 0) m.blob(q[0], q[1] - len - 1, q[2], 1.2, 1.6, 1.2, SAC, 0); n++; }
        }
        m.patch(SAC, BURST, .66, .12, 0, m.H); m.patch(MOSS, BURST, .74, .1, s.G + 2, m.H); m.patch(BARK, PITH, .72, .2, s.G + 2, m.H); m.patch(BARK2, PITH, .74, .2, s.G + 2, m.H);
        m.blob(m.core[0], m.core[1], m.core[2], 2, 2.4, 2, CORE, 0);
        for (double[][] p : s.inPaths) m.inPaths.add(Arrays.stream(p).map(f).toArray(double[][]::new));
        for (double[][] p : s.outPaths) m.outPaths.add(Arrays.stream(p).map(f).toArray(double[][]::new));
        for (double[] p : s.plumes) { double[] q = f.apply(p); m.plumes.add(new double[]{q[0], q[1], q[2], p[3]}); }
        for (Marker k : s.marks) { double[] q = f.apply(new double[]{k.x, k.y, k.z}); m.marks.add(new Marker(q[0], q[1], q[2], k.n, k.panel)); }
        m.legend.add("崩  " + extra); m.legend.addAll(s.legend);
        m.player((int) (s.W / 2 + P + 16), (int) (s.D / 2 + P + 16));
        return m;
    }
    // 共通の小道具
    // =====================================================================
    static void hang(Model m, double x, double yTop, double z, double len, int mat, double r) {
        m.tube(new double[][]{{x, yTop, z}, {x, yTop - len, z}}, .01, .01, LIANA); m.blob(x, yTop - len - r, z, r, r * 1.25, r, mat, 0);
    }
    static void shell(Model m, double cx, double cy, double cz, double rx, double ry, double rz, double th, int mat, double thr, double freq) {
        for (int x = 0; x < m.W; x++) for (int y = (int) cy; y < m.H; y++) for (int z = 0; z < m.D; z++) {
            double d = Math.sqrt(sq((x - cx) / rx) + sq((y - cy) / ry) + sq((z - cz) / rz));
            if (d <= 1 && d >= 1 - th / rx && m.get(x, y, z) == AIR && n3(x * freq + 3, y * freq, z * freq) > thr) m.set(x, y, z, mat);
        }
    }

    // =====================================================================
    // フェーズを進める器官
    // =====================================================================
    static Model phaseA() {
        Model m = new Model(58, 52, 58, 7, "フェーズを進める器官 A ―「標本の籠」", "根の肋で編んだ丸屋根。天井から、取り込んだ生き物を包んだ標本の嚢が下がる。真ん中に髄の柱。高さ 約 30+触角・幅 約 42【仮】");
        double c = 29; m.ground(27);
        shell(m, c, 7, c, 21, 28, 21, 2.2, MOSS, .5, .12);
        for (int i = 0; i < 10; i++) { double a = i * Math.PI / 5 + .15; m.tube(m.curve(24, t -> { double ph = t * Math.PI / 2; return new double[]{c + 21 * Math.cos(ph) * Math.cos(a), 7 + 28 * Math.sin(ph), c + 21 * Math.cos(ph) * Math.sin(a)}; }), 2.0, 1.3, i % 2 == 0 ? BARK : BARK2); }
        m.patch(MOSS, MOSS2, .68, .3, 8, 52); m.patch(BARK, MOSS, .66, .16, 0, 52);
        m.tube(new double[][]{{c, 6, c}, {c, 34, c}}, 2.6, 1.8, PITH);
        m.tube(m.curve(60, t -> new double[]{c + 2.9 * Math.cos(t * 16), 7 + t * 27, c + 2.9 * Math.sin(t * 16)}), .01, .01, GLOW);
        m.blob(c, 19, c, 3.6, 3.6, 3.6, PITH, .1); m.blob(c, 19, c, 2.2, 2.6, 2.2, CORE, 0); m.core = new double[]{c, 19, c};
        for (int i = 0; i < 18; i++) { double a = i * 2.4, R = 7 + (i * 5 % 9); double top = 7 + 28 * Math.sqrt(Math.max(0, 1 - R * R / (21 * 21))) - 1; hang(m, c + R * Math.cos(a), top, c + R * Math.sin(a), 4 + (i * 3 % 7), EGG, 1.7); }
        for (int i = 0; i < 6; i++) { double a = 2.2 + i * .9; m.blob(c + 5.5 * Math.cos(a), 9 + i * 2, c + 5.5 * Math.sin(a), 2.3, .5, 2.3, CAP, 0); }
        m.tube(new double[][]{{c + 21, 9, c + 13}, {c + 12, 9, c + 8}}, 2.2, 2.2, AIR);
        for (int i = 0; i < 5; i++) { double a = i * 1.3; m.tube(m.curve(12, t -> new double[]{c + (2 + t * 8) * Math.cos(a), 34 + t * 13 - t * t * 4, c + (2 + t * 8) * Math.sin(a)}), .9, .5, LIANA); m.blob(c + 10 * Math.cos(a), 43, c + 10 * Math.sin(a), 1.1, 1.1, 1.1, GLOW, 0); }
        m.stud(MOSS, SAC, .006, 10, 30, 1.4);
        m.player((int) c + 23, (int) c + 14);
        m.inPaths.add(new double[][]{{c + 22, 9, c + 13.5}, {c + 12, 9, c + 8}, {c + 6, 9, c - 2}, {c - 3, 12, c - 5}, {c - 5, 16, c + 1}, {c - 3, 19, c + 2}});
        m.outPaths.add(m.curve(20, t -> { double ph = t * Math.PI / 2 * .95; return new double[]{c + 22 * Math.cos(ph) * Math.cos(.9), 8 + 28 * Math.sin(ph), c + 22 * Math.cos(ph) * Math.sin(.9)}; }));
        m.mark(c + 21, 10.5, c + 13, "1", 2, "入口。肋と肋のすき間(屋根は穴だらけで、外から中の光が見える)");
        m.mark(c - 9, 20, c + 3, "2", 1, "標本の嚢。取り込んだ動物・村人・道具を包んで調べている(= 研究施設)。割ると段 3 の素材と標本");
        m.mark(c - 4, 13, c - 6, "3", 1, "柱を巻く棚きのこの足場。守りの Mob は床と足場に");
        m.mark(c + 1, 19, c - 4, "芯", 1, "髄の柱のふくらみの中。光る筋が柱を巻いて上り、ここで明滅する");
        m.mark(c + 9, 44, c + 4, "4", 0, "屋根の上の触角(動く蔦)。先が光り、空を探るようにゆっくり揺れる");
        m.mark(c + 14, 22, c + 12, "5", 0, "肋は外から登れる。屋根の穴から中へ降りられる");
        return m;
    }
    static Model phaseB() {
        Model m = new Model(60, 70, 60, 7, "フェーズを進める器官 B ―「髄の樹」", "太い幹の上に脳のような節があり、そこから枝が広がって先に光る芽を付ける。光が枝から節へ集まる。高さ 約 55【仮】");
        double c = 30; m.ground(27);
        m.tube(new double[][]{{c, 6, c}, {c + 1, 20, c - 1}, {c, 32, c}}, 6.5, 4.5, BARK2);
        for (int i = 0; i < 7; i++) { double a = i * .9 + .2; m.tube(m.curve(12, t -> new double[]{c + (5 + t * 15) * Math.cos(a), 12 - t * 6 + Math.sin(t * 3) * 2.5, c + (5 + t * 15) * Math.sin(a)}), 2.2, .9, ROOT); }
        m.ground(27);
        m.blob(c, 35, c, 9.5, 7, 9.5, PITH, .28);
        m.patch(PITH, MOSS, .6, .14, 0, 70);
        double[][] tips = new double[7][];
        for (int i = 0; i < 7; i++) { double a = i * Math.PI * 2 / 7 + .3, R = 15 + (i * 3 % 5) * 1.6, top = 50 + (i * 5 % 7) * 1.6; int k = i;
            m.tube(m.curve(20, t -> new double[]{c + (5 + (R - 5) * Math.pow(t, .8)) * Math.cos(a + t * .4), 37 + (top - 37) * t * t + Math.sin(t * 5 + k) * 1.2, c + (5 + (R - 5) * Math.pow(t, .8)) * Math.sin(a + t * .4)}), 2.6, 1.1, i % 2 == 0 ? BARK : BARK2);
            m.tube(m.curve(40, t -> new double[]{c + (5 + (R - 5) * Math.pow(t, .8)) * Math.cos(a + t * .4), 39 + (top - 37) * t * t + Math.sin(t * 5 + k) * 1.2, c + (5 + (R - 5) * Math.pow(t, .8)) * Math.sin(a + t * .4)}), .01, .01, GLOW);
            tips[i] = new double[]{c + R * Math.cos(a + .4), top + 2, c + R * Math.sin(a + .4)};
            m.blob(tips[i][0], tips[i][1], tips[i][2], 2.2, 2.6, 2.2, CRYSTAL, 0); m.blob(tips[i][0], tips[i][1] - 2.4, tips[i][2], 2.6, 1, 2.6, SAC, 0);
            hang(m, c + R * .6 * Math.cos(a + .2), 37 + (top - 37) * .3, c + R * .6 * Math.sin(a + .2), 4 + i % 3 * 2, EGG, 1.5);
        }
        m.patch(BARK2, MOSS, .64, .16, 0, 70); m.patch(BARK, MOSS, .64, .16, 0, 70); m.patch(MOSS, MOSS2, .7, .3, 8, 70);
        m.tube(new double[][]{{c, 10, c}, {c + 1, 20, c - 1}, {c, 31, c}}, 2.4, 2.0, AIR);
        m.blob(c, 35, c, 5.5, 3.6, 5.5, AIR, .05);
        m.tube(new double[][]{{c + 12, 9.5, c + 8}, {c, 10, c}}, 1.9, 1.9, AIR);
        for (int y = 12; y < 31; y += 3) { double a = y * .8; m.blob(c + 1.6 * Math.cos(a), y, c + 1.6 * Math.sin(a), 1.5, .5, 1.5, CAP, 0); }
        m.blob(c, 35.5, c, 1.8, 2.2, 1.8, CORE, 0); m.core = new double[]{c, 35.5, c};
        for (int i = 0; i < 4; i++) { double a = i * Math.PI / 2 + .8; m.tube(new double[][]{{c, 36, c}, {c + 5 * Math.cos(a), 37, c + 5 * Math.sin(a)}}, .01, .01, GLOW); }
        for (int i = 0; i < 3; i++) { double a = .6 + i * 2; m.tube(m.curve(14, t -> new double[]{c + (8 + t * 6) * Math.cos(a), 32 - t * 16 + Math.sin(t * 6) * 1.4, c + (8 + t * 6) * Math.sin(a)}), .9, .5, LIANA); }
        m.player((int) c + 14, (int) c + 9);
        m.inPaths.add(new double[][]{{c + 13, 9.5, c + 8.5}, {c, 10, c}, {c + 1, 20, c - 1}, {c, 31, c}, {c - 2, 34, c - 1}});
        m.outPaths.add(new double[][]{{c + 8, 8, c + 3}, {c + 6, 20, c + 3}, {c + 7, 32, c + 5}, {tips[0][0], tips[0][1] - 3, tips[0][2]}});
        m.mark(c + 12, 11, c + 8, "1", 2, "入口。根と根の間のうろ");
        m.mark(c - 1, 22, c - 2, "2", 1, "幹の中の縦穴。棚きのこの足場をらせんに登る");
        m.mark(c - 4, 35, c + 2, "芯", 1, "節(脳のようなかたまり)の中の部屋の真ん中。枝から来た光がここに集まって脈打つ");
        m.mark(tips[1][0], tips[1][1], tips[1][2], "3", 0, "枝先の光る芽。順番に灯り、光が枝を伝って節へ流れる(考えているように見える)。段 3 の素材");
        m.mark(c + 9, 30, c + 9, "4", 0, "枝から下がる標本の嚢と、動く蔦の触手");
        m.mark(c + 6, 20, c + 5, "5", 0, "幹の蔦を登って節の上へ。枝は橋のように渡れる");
        return m;
    }

    // =====================================================================
    // 蓄えの嚢
    // =====================================================================
    static Model storA() {
        Model m = new Model(62, 60, 62, 7, "蓄えの嚢 A ―「房なりの嚢」", "3 本の幹が上で結び合い、その結び目から重い嚢が房になって下がる。いちばん大きい嚢の中が部屋。高さ 約 48【仮】");
        double c = 31; m.ground(28);
        for (int i = 0; i < 3; i++) { double a = i * Math.PI * 2 / 3 + 1.83; int k = i; m.tube(m.curve(24, t -> new double[]{c + 24 * (1 - Math.pow(t, 1.5)) * Math.cos(a + t * .3), 6 + 44 * Math.pow(t, .8), c + 24 * (1 - Math.pow(t, 1.5)) * Math.sin(a + t * .3)}), 3.8, 2.6, k == 1 ? BARK : BARK2); }
        m.blob(c, 49, c, 5.5, 4, 5.5, BARK2, .2);                     // 結び目(へた)
        m.blob(c, 29, c, 10, 12.5, 10, SAC, .1);                      // 大嚢
        m.tube(new double[][]{{c, 40, c}, {c, 48, c}}, 3, 2.4, BARK);
        double[][] subs = {{10, .2, 30, 5.5}, {11, 1.6, 34, 5}, {12, 2.9, 27, 6}, {11, 4.2, 35, 4.5}, {12, 5.4, 30, 5.5}};
        for (double[] s : subs) { double x = c + (s[0] + 3) * Math.cos(s[1]), z = c + (s[0] + 3) * Math.sin(s[1]); m.tube(new double[][]{{c, 48, c}, {x, s[2] + s[3] + 3, z}, {x, s[2], z}}, 1.2, 1.2, LIANA); m.blob(x, s[2], z, s[3], s[3] * 1.25, s[3], SAC, .1); }
        m.patch(SAC, SACW, .6, .2, 0, 60); m.patch(BARK2, MOSS, .64, .16, 0, 60); m.patch(BARK, MOSS, .64, .16, 0, 60); m.patch(MOSS, MOSS2, .7, .3, 8, 60);
        for (int i = 0; i < 6; i++) { double a = i * Math.PI / 3; m.tube(m.curve(20, t -> { double ph = -1.3 + t * 2.6; return new double[]{c + 10.3 * Math.cos(ph) * Math.cos(a), 29 + 12.8 * Math.sin(ph), c + 10.3 * Math.cos(ph) * Math.sin(a)}; }), .01, .01, GLOW); }
        m.blob(c, 29, c, 7.5, 10, 7.5, AIR, .05);
        for (int x = (int) c - 8; x <= c + 8; x++) for (int z = (int) c - 8; z <= c + 8; z++) for (int y = 18; y <= 23; y++) if (m.get(x, y, z) == AIR && sq((x - c) / 7.5) + sq((y - 29) / 10.0) + sq((z - c) / 7.5) < 1) m.set(x, y, z, PITH);   // 溜まった蜜
        m.tube(new double[][]{{c, 16, c}, {c, 24.5, c}}, 1.6, 1.6, LIANA); m.tube(new double[][]{{c, 8, c}, {c, 24.5, c}}, 1.0, 1.0, AIR);     // 底の管(蔦のはしご)
        m.tube(new double[][]{{c, 39, c}, {c, 47, c}}, 1.4, 1.4, AIR); m.blob(c, 49, c, 3, 2.2, 3, AIR, 0);
        m.blob(c, 49.5, c, 1.6, 1.8, 1.6, CORE, 0); m.core = new double[]{c, 49.5, c};
        for (int i = 0; i < 5; i++) { double a = 1 + i * 1.2; m.blob(c + 6 * Math.cos(a), 26 + i * 2.6, c + 6 * Math.sin(a), 2, .5, 2, CAP, 0); }
        for (int i = 0; i < 4; i++) { double a = .9 + i * 1.6; m.tube(m.curve(14, t -> new double[]{c + (4 + t * 5) * Math.cos(a), 47 - t * 14 + Math.sin(t * 6) * 1.3, c + (4 + t * 5) * Math.sin(a)}), .8, .5, LIANA); }
        m.player((int) c + 9, (int) c + 9);
        m.inPaths.add(new double[][]{{c + 2, 8, c + 2}, {c, 10, c}, {c, 24, c}, {c - 5, 27, c - 2}, {c - 3, 34, c - 5}, {c, 39, c}, {c, 47, c}});
        m.outPaths.add(m.curve(24, t -> new double[]{c + 25 * (1 - Math.pow(t, 1.5)) * Math.cos(-.26 + t * .3), 8 + 44 * Math.pow(t, .8), c + 25 * (1 - Math.pow(t, 1.5)) * Math.sin(-.26 + t * .3)}));
        m.mark(c + 1, 15, c + 1, "1", 2, "入口。大嚢の底から垂れる管を、蔦のはしごで登る(嚢は地面から浮いている)");
        m.mark(c - 5, 24, c + 1, "2", 1, "嚢の中。底に蜜(バイオマス)が溜まっている。壁の棚を登る。壁が呼吸でふくらむ");
        m.mark(c - 3, 49.5, c - 2, "芯", 1, "幹の結び目(へた)の中。嚢の天井の管を抜けた先。外の幹を登っても行ける");
        m.mark(c + 14, 30, c + 8, "3", 0, "まわりの小さい嚢。叩くと破裂して蜜と胞子が落ちる。足場にもなるが揺れる");
        m.mark(c + 20, 14, c - 5, "4", 0, "幹は外から登れる。上から動く蔦の触手が下がる");
        m.mark(c + 9, 35, c + 4, "5", 0, "光る筋。蓄えが多いほど嚢が大きく・明るくなる(芯を壊すとしぼむ)");
        return m;
    }
    static Model storB() {
        Model m = new Model(62, 46, 62, 16, "蓄えの嚢 B ―「埋もれた大袋」", "半分地面に埋まった、ずんぐりした巨大な袋。根の網が締め付けている。中は蜜の池の広間。高さ 約 18・幅 約 40【仮】(低くて広い)");
        double c = 31; m.ground(29);
        m.blob(c, 19, c, 20, 15, 20, SAC, .1); m.patch(SAC, SACW, .58, .14, 0, 46);
        for (int i = 0; i < 5; i++) { double a = i * Math.PI / 5 + .3; m.tube(m.curve(40, t -> { double ph = t * Math.PI; return new double[]{c + 20.6 * Math.cos(ph) * Math.cos(a), 19 + 15.6 * Math.sin(ph), c + 20.6 * Math.cos(ph) * Math.sin(a)}; }), 1.3, 1.3, i % 2 == 0 ? BARK : ROOT); }
        m.tube(m.curve(50, t -> new double[]{c + 19 * Math.cos(t * 6.3), 26 + Math.sin(t * 19) * 1.5, c + 19 * Math.sin(t * 6.3)}), 1.1, 1.1, BARK2);
        for (int i = 0; i < 6; i++) { double a = i * 1.05; m.tube(m.curve(40, t -> { double ph = .15 + t * 1.3; return new double[]{c + 20.9 * Math.cos(ph) * Math.cos(a + .5), 19 + 15.9 * Math.sin(ph), c + 20.9 * Math.cos(ph) * Math.sin(a + .5)}; }), .01, .01, GLOW); }
        m.blob(c, 19, c, 16.5, 12, 16.5, AIR, .04);
        for (int x = 0; x < m.W; x++) for (int z = 0; z < m.D; z++) for (int y = 6; y <= 13; y++) if (m.get(x, y, z) == AIR && sq((x - c) / 16.5) + sq((y - 19) / 12.0) + sq((z - c) / 16.5) < 1) m.set(x, y, z, PITH);
        m.tube(new double[][]{{c, 7, c}, {c, 16, c}}, 3.2, 2.2, ROOT); m.blob(c, 18, c, 2, 2.4, 2, CORE, 0); m.core = new double[]{c, 18, c};
        for (int i = 0; i < 9; i++) { double a = i * .7; m.blob(c + 13.5 * Math.cos(a), 14.5, c + 13.5 * Math.sin(a), 2.6, .5, 2.6, CAP, 0); }
        m.blob(c - 6, 14, c + 5, 1.8, .5, 1.8, CAP, 0); m.blob(c + 7, 14, c - 5, 1.8, .5, 1.8, CAP, 0); m.blob(c + 5, 14, c + 6, 1.8, .5, 1.8, CAP, 0);
        m.tube(new double[][]{{c, 29, c}, {c, 36, c}}, 2.6, 3, AIR);                         // てっぺんのへそ
        m.tube(new double[][]{{c + 22, 18, c + 12}, {c + 13, 16.5, c + 7}}, 2, 2, AIR);        // 脇の裂け目
        for (int i = 0; i < 5; i++) hang(m, c + 6 * Math.cos(i * 1.3), 30, c + 6 * Math.sin(i * 1.3), 4 + i % 3 * 2, SAC, 1.4);
        for (int i = 0; i < 7; i++) { double a = i * .9 + .1; m.blob(c + 25 * Math.cos(a), 16.5, c + 25 * Math.sin(a), 2.2, 2.4, 2.2, i % 3 == 0 ? SACW : SAC, 0); }
        m.patch(BARK, MOSS, .62, .2, 0, 46); m.stud(GRASS, FERN, .0, 0, 0, 1);
        m.player((int) c + 24, (int) c + 13);
        m.inPaths.add(new double[][]{{c + 23, 18, c + 12.5}, {c + 13, 16, c + 7}, {c + 13, 15, c - 3}, {c + 7, 15, c - 5}, {c + 2, 17, c - 1}});
        m.outPaths.add(m.curve(20, t -> { double ph = .1 + t * 1.45; return new double[]{c + 21.5 * Math.cos(ph) * Math.cos(.9), 19 + 16.5 * Math.sin(ph), c + 21.5 * Math.cos(ph) * Math.sin(.9)}; }));
        m.mark(c + 22, 19.5, c + 12, "1", 2, "入口。袋の脇の裂け目");
        m.mark(c - 8, 14, c + 2, "2", 1, "蜜の池(バイオマス)。落ちると動きが鈍る。棚きのこの飛び石を渡る");
        m.mark(c - 3, 18, c - 2, "芯", 1, "池の真ん中に立つ根の柱の上。飛び石で渡るか、縁から撃つ");
        m.mark(c - 6, 26, c - 2, "3", 1, "天井から下がる胞子嚢。守りの Mob は池の縁と天井に");
        m.mark(c + 2, 35, c + 2, "4", 0, "てっぺんのへそ。根の網を登ってここから中へ飛び降りられる");
        m.mark(c + 16, 28, c + 10, "5", 0, "締め付ける根の網。袋は呼吸でゆっくりふくらみ、網がきしむ。芯を壊すとしぼんで地面にへこみが残る");
        return m;
    }

    // =====================================================================
    // 苗床の群体
    // =====================================================================
    static Model nursA() {
        Model m = new Model(62, 44, 62, 12, "苗床の群体 A ―「蜂の巣の丘」", "一面に小部屋の穴が開いた丘。穴ごとに卵のような粒が入り、てっぺんに芽が出る。中は蟻の巣のような通路。高さ 約 20・幅 約 44【仮】");
        double c = 31; m.ground(29);
        m.blob(c, 14, c, 22, 15, 22, MOSS, .28); m.blob(c - 9, 24, c - 6, 7, 9, 7, MOSS, .25); m.blob(c + 8, 22, c - 9, 6, 8, 6, MOSS, .25);
        m.patch(MOSS, BARK, .56, .1, 0, 44); m.patch(MOSS, BARK2, .64, .13, 0, 44); m.patch(MOSS, MOSS2, .68, .3, 13, 44);
        List<int[]> cells = new ArrayList<>();
        for (int x = 0; x < m.W; x++) for (int y = 15; y < 40; y++) for (int z = 0; z < m.D; z++) if (m.get(x, y, z) != AIR && m.surface(x, y, z) && (x % 5 == (y / 4 % 2) * 2) && y % 4 == 0 && z % 5 == (y / 4 % 2) * 2) cells.add(new int[]{x, y, z});
        for (int x = 0; x < m.W; x++) for (int y = 15; y < 40; y++) for (int z = 0; z < m.D; z++) if (m.get(x, y, z) != AIR && m.surface(x, y, z) && r3(x, y + 900, z) < .02) cells.add(new int[]{x, y, z});
        m.blob(c, 13, c, 7, 4.5, 7, AIR, .05);                         // 女王の間
        double[][] tun = m.curve(50, t -> new double[]{c + (17 - 13 * t) * Math.cos(.6 + t * 7), 15 + 6 * Math.sin(t * 3.1) - t * 2, c + (17 - 13 * t) * Math.sin(.6 + t * 7)});
        m.tube(tun, 1.8, 1.8, AIR); m.tube(new double[][]{{c + 24, 15, c + 14}, tun[0]}, 1.9, 1.9, AIR);
        m.tube(new double[][]{{c - 9, 18, c - 6}, {c - 9, 34, c - 6}}, 1.6, 1.4, AIR); m.tube(new double[][]{{c - 9, 18, c - 6}, {c - 3, 14, c - 2}}, 1.6, 1.6, AIR);
        for (int[] q : cells) { if (m.get(q[0], q[1], q[2]) == AIR) continue; m.blob(q[0], q[1], q[2], 2.3, 2.3, 2.3, BARK2, 0, true); }
        for (int[] q : cells) m.blob(q[0], q[1], q[2], 1.2, 1.2, 1.2, EGG, 0);
        m.blob(c, 11.5, c, 3, 2.6, 3, ROOT, .1); m.blob(c, 12.5, c, 1.8, 2, 1.8, CORE, 0); m.core = new double[]{c, 12.5, c};
        for (int i = 0; i < 9; i++) { double a = i * .7; m.blob(c + 5 * Math.cos(a), 9.6, c + 5 * Math.sin(a), 1, 1.2, 1, EGG, 0); }
        for (int i = 0; i < 5; i++) { double a = i * 1.26; m.tube(new double[][]{{c, 13, c}, {c + 6.5 * Math.cos(a), 12, c + 6.5 * Math.sin(a)}}, .01, .01, GLOW); }
        m.stud(MOSS, LEAF, .012, 24, 44, 1.3); m.stud(MOSS2, FERN, .05, 20, 44, .6);
        for (int i = 0; i < 3; i++) { double a = .7 + i * 2; m.tube(m.curve(14, t -> new double[]{c + (15 + t * 9) * Math.cos(a), 24 - t * 9 + Math.sin(t * 6) * 1.8, c + (15 + t * 9) * Math.sin(a)}), .9, .5, LIANA); }
        m.player((int) c + 26, (int) c + 15);
        List<double[]> ip = new ArrayList<>(); ip.add(new double[]{c + 25, 15, c + 14.5}); for (int i = 0; i < tun.length; i += 3) ip.add(tun[i]); ip.add(new double[]{c + 2, 12, c + 2});
        m.inPaths.add(ip.toArray(new double[0][]));
        m.outPaths.add(new double[][]{{c + 20, 15, c + 16}, {c + 12, 24, c + 12}, {c + 2, 29, c + 6}, {c - 7, 33, c - 3}});
        m.mark(c + 24, 16.5, c + 14, "1", 2, "入口。丘のふもとの大きめの穴(小部屋の穴は人が入れない)");
        m.mark(c - 12, 15, c + 2, "2", 1, "通路。蟻の巣のように渦を巻いて奥へ。壁の小部屋から手先が孵って出てくる");
        m.mark(c - 4, 13, c + 3, "芯", 1, "いちばん奥の「女王の間」の母の球根。まわりの床に大きな卵。光る筋が部屋中へ伸びる");
        m.mark(c + 10, 24, c + 14, "3", 0, "小部屋と卵のような粒。脈打ち、割れると手先(グレガリアなど)が出る。穴は登る足がかりになる");
        m.mark(c - 8, 35, c - 4, "4", 0, "てっぺんの煙突。芽が出ている。ここから中へ降りる近道(女王の間の真上)");
        m.mark(c + 20, 20, c + 6, "5", 0, "動く蔦の触手。育った苗床をゲスタトリクスが背負って出ていく");
        return m;
    }
    static Model nursB() {
        Model m = new Model(62, 58, 62, 14, "苗床の群体 B ―「肋の揺り籠」", "あばら骨のような太い根が輪になって内へ反り、その間に卵の房が吊られる。真ん中の穴に母の球根。高さ 約 38【仮】(屋根がなく開いている)");
        double c = 31; m.ground(29);
        m.blob(c, 13, c, 10, 7, 10, AIR, .08);                          // 真ん中の穴
        for (int x = 0; x < m.W; x++) for (int z = 0; z < m.D; z++) for (int y = 14; y < 24; y++) if (Math.hypot(x - c, z - c) < 11) m.set(x, y, z, AIR);
        double[][][] ribs = new double[9][][];
        for (int i = 0; i < 9; i++) { double a = i * Math.PI * 2 / 9 + .2, hgt = 34 + (i * 4 % 5) * 2; int k = i;
            ribs[i] = m.curve(24, t -> new double[]{c + (22 - 16 * Math.pow(t, 2.2) + 3 * Math.sin(t * 3.14)) * Math.cos(a + Math.sin(t * 3 + k) * .05), 10 + hgt * Math.pow(t, .85), c + (22 - 16 * Math.pow(t, 2.2) + 3 * Math.sin(t * 3.14)) * Math.sin(a + Math.sin(t * 3 + k) * .05)});
            m.tube(ribs[i], i == 4 ? 3.6 : 2.6, 1.0, i % 2 == 0 ? BARK2 : BARK); }
        for (int i = 0; i < 9; i++) { double[][] A = ribs[i], B = ribs[(i + 1) % 9];
            for (int lv = 9; lv <= 19; lv += 5) { if ((i + lv) % 2 == 0) continue; double[] p = A[lv], q = B[lv];
                m.tube(m.curve(10, t -> new double[]{p[0] + (q[0] - p[0]) * t, p[1] + (q[1] - p[1]) * t - Math.sin(t * Math.PI) * 4, p[2] + (q[2] - p[2]) * t}), .6, .6, LIANA);
                double mx = (p[0] + q[0]) / 2, my = (p[1] + q[1]) / 2 - 5.5, mz = (p[2] + q[2]) / 2;
                m.blob(mx, my, mz, 1.8, 2.2, 1.8, EGG, 0); m.blob(mx + 1.6, my + 1, mz - 1, 1.3, 1.6, 1.3, EGG, 0); m.blob(mx - 1.2, my + .5, mz + 1.4, 1.2, 1.5, 1.2, EGG, 0); } }
        for (int i = 0; i < 9; i += 2) { double[][] r = ribs[i]; for (int k = 3; k < 20; k += 3) m.blob(r[k][0] + (r[k][0] - c) * .12, r[k][1], r[k][2] + (r[k][2] - c) * .12, 2.4, .5, 2.4, CAP, 0); }
        for (int i = 0; i < 9; i += 3) { List<double[]> g = new ArrayList<>(); for (double[] p : ribs[i]) g.add(new double[]{p[0] + (p[0] - c) * .13, p[1] + .5, p[2] + (p[2] - c) * .13}); m.tube(g.toArray(new double[0][]), .01, .01, GLOW); }
        m.patch(BARK2, MOSS, .62, .16, 0, 58); m.patch(BARK, MOSS, .62, .16, 0, 58); m.patch(MOSS, MOSS2, .7, .3, 16, 58);
        m.blob(c, 10, c, 5, 5, 5, ROOT, .15); m.blob(c, 13, c, 2.2, 2.6, 2.2, CORE, 0); m.core = new double[]{c, 13, c};
        for (int i = 0; i < 8; i++) { double a = i * .8; m.blob(c + 7 * Math.cos(a), 8, c + 7 * Math.sin(a), 1.2, 1.4, 1.2, EGG, 0); m.tube(new double[][]{{c, 11, c}, {c + 9 * Math.cos(a), 9 + (i % 2) * 3, c + 9 * Math.sin(a)}}, .01, .01, GLOW); }
        for (int i = 0; i < 6; i++) { double a = .3 + i * 1.05; m.blob(c + 8.5 * Math.cos(a), 14.5 - i * .9, c + 8.5 * Math.sin(a), 2, .5, 2, CAP, 0); }
        m.player((int) c + 24, (int) c + 12);
        m.inPaths.add(new double[][]{{c + 23, 15, c + 12}, {c + 13, 15, c + 7}, {c + 8, 15, c + 3}, {c + 7, 12, c - 5}, {c + 3, 10, c - 6}, {c + 1, 11, c - 3}});
        m.outPaths.add(Arrays.copyOfRange(ribs[1], 1, 22));
        m.mark(c + 18, 16.5, c + 10, "1", 2, "入口は肋の間のどこからでも(壁がない)。そのぶん守りの Mob が多い");
        m.mark(c - 8, 11, c + 2, "芯", 1, "真ん中の穴の底、母の球根のてっぺん。むき出しだが、穴の中は卵と手先だらけ");
        m.mark(c - 9, 26, c - 3, "2", 1, "吊られた卵の房。脈打ち、熟すと落ちて割れ、手先が出る。上から撃ち落とせる");
        m.mark(c + 12, 36, c + 10, "3", 0, "肋は棚きのこを足場に外から登れる。上から芯を狙い撃てる");
        m.mark(c + 20, 22, c + 2, "4", 0, "肋そのものが呼吸でゆっくり開閉する(動く触手の役)。光る筋が根元から先へ");
        return m;
    }

    // =====================================================================
    // 地脈を吸う根
    // =====================================================================
    static Model leyA() {
        Model m = new Model(58, 56, 58, 30, "地脈を吸う根 A ―「沈む根の井戸」", "地上は根のこぶと、弧を描いて地面へ潜る根だけ。その下に、地脈の線まで届く縦穴がある。地上の高さ 約 14・地下の深さ 約 25【仮】");
        double c = 29; m.ground(27);
        m.tube(new double[][]{{c, 5, c}, {c, 31, c}}, 6, 6, AIR);                              // 縦穴
        m.blob(c, 37, c, 9, 6.5, 9, BARK2, .25);
        for (int i = 0; i < 8; i++) { double a = i * Math.PI / 4 + .2; int k = i;
            m.tube(m.curve(30, t -> { double R = 6 + 17 * t, y = t < .75 ? 37 + 8 * Math.sin(t / .75 * Math.PI) - t * 6 : 32.5 - (t - .75) * 30; return new double[]{c + R * Math.cos(a + Math.sin(t * 4 + k) * .1), y, c + R * Math.sin(a + Math.sin(t * 4 + k) * .1)}; }), 2.4, 1.6, i % 2 == 0 ? ROOT : BARK);
            m.tube(m.curve(40, t -> { double R = 6 + 17 * t, y = t < .75 ? 39.2 + 8 * Math.sin(t / .75 * Math.PI) - t * 6 : 34 - (t - .75) * 30; return new double[]{c + R * Math.cos(a), y, c + R * Math.sin(a)}; }), .01, .01, GLOW);
        }
        m.patch(BARK2, MOSS, .6, .16, 30, 56); m.patch(BARK, MOSS, .64, .16, 30, 56); m.patch(ROOT, MOSS, .7, .16, 30, 56);
        m.tube(new double[][]{{c, 36, c}, {c + .5, 20, c - .5}, {c, 8, c}}, 2.6, 1.8, ROOT);    // 主根
        m.tube(m.curve(60, t -> new double[]{c + 2.7 * Math.cos(t * 20), 8 + t * 27, c + 2.7 * Math.sin(t * 20)}), .01, .01, GLOW);
        m.tube(new double[][]{{2, 6, c - 6}, {c, 6, c}, {56, 6, c + 5}}, 1.6, 1.6, GLOW);         // 地脈の線
        m.blob(c, 8, c, 2.4, 2.6, 2.4, CORE, 0); m.core = new double[]{c, 8, c};
        for (int i = 0; i < 16; i++) { double a = i * 2.1, y = 8 + i * 1.3; double r = 2.4 - i * .1; m.blob(c + 6 * Math.cos(a), y, c + 6 * Math.sin(a), r, r * 1.5, r, CRYSTAL, 0); }
        for (int i = 0; i < 12; i++) { double a = i * .85; m.blob(c + 4.6 * Math.cos(a), 29 - i * 1.8, c + 4.6 * Math.sin(a), 1.8, .5, 1.8, CAP, 0); }
        m.tube(new double[][]{{c + 12, 32.5, c + 9}, {c + 5, 31, c + 3}}, 1.8, 1.8, AIR);
        m.player((int) c + 14, (int) c + 10);
        m.inPaths.add(m.curve(40, t -> new double[]{c + 4.6 * Math.cos(.6 + t * 9.3), 31 - t * 21, c + 4.6 * Math.sin(.6 + t * 9.3)}));
        m.outPaths.add(new double[][]{{c + 16, 32, c + 12}, {c + 11, 38, c + 9}, {c + 5, 42, c + 5}});
        m.mark(c + 12, 34, c + 9, "1", 2, "入口。根のこぶの下、根と根の間から縦穴へ");
        m.mark(c - 5, 22, c + 3, "2", 1, "縦穴。壁の棚きのこをらせんに降りる。真ん中の主根を光が上っていく(吸っている)");
        m.mark(c - 6, 12, c - 3, "3", 1, "段 3 の結晶。深いほど大きい。採ると根が気づいて守りの Mob が来る");
        m.mark(c + 3, 9, c - 3, "芯", 1, "底で主根が地脈の線(光る帯)をつかんでいる所。壊すと線が自由になり、近くの汲み上げ器が元に戻る");
        m.mark(c + 18, 36, c + 4, "4", 0, "弧を描いて地面へ潜る根。光る筋が地面からこぶへ流れる。根は登れる");
        m.mark(c + 2, 44, c + 2, "5", 0, "根のこぶ。地上は低いので、森の中では光る筋と地面の盛り上がりで見つける");
        return m;
    }
    static Model leyB() {
        Model m = new Model(54, 66, 54, 10, "地脈を吸う根 B ―「締め上げる岩柱」", "地脈の真上の岩を根が持ち上げ、巻き付いて締め割っている。割れ目から結晶が噴き出す。高さ 約 45【仮】");
        double c = 27; m.ground(25);
        m.tube(new double[][]{{c, 6, c}, {c + 1, 28, c - 1}, {c + 3, 52, c - 2}}, 8.5, 2.6, STONE);
        m.blob(c - 6, 14, c + 5, 5, 6, 5, STONE, .2); m.blob(c + 7, 13, c - 5, 4.5, 5, 4.5, STONE, .2);
        m.patch(STONE, MOSS, .62, .14, 10, 66);
        for (int y = 14; y < 46; y++) for (int z = 0; z < m.D; z++) for (int x = (int) c; x <= c + 2; x++) if (m.get(x, y, z) != AIR && Math.abs(z - c) < 9 - (y - 14) * .16 && n3(x * .3, y * .2, z * .3) > .18) m.set(x, y, z, AIR);   // 割れ目
        for (int i = 0; i < 5; i++) { double a0 = i * Math.PI * 2 / 5; int k = i;
            m.tube(m.curve(50, t -> { double R = 10.5 - 7.5 * t + Math.sin(t * 9 + k) * .8, a = a0 + t * 5.2; return new double[]{c + 1 + t * 2 + R * Math.cos(a), 8 + t * 44, c - 1 - t + R * Math.sin(a)}; }), 2.0, .9, i % 2 == 0 ? ROOT : BARK2);
            m.tube(m.curve(90, t -> { double R = 12.4 - 8 * t + Math.sin(t * 9 + k) * .8, a = a0 + t * 5.2 + .05; return new double[]{c + 1 + t * 2 + R * Math.cos(a), 8.5 + t * 44, c - 1 - t + R * Math.sin(a)}; }), .01, .01, GLOW);
        }
        m.patch(BARK2, MOSS, .66, .16, 10, 66);
        for (int i = 0; i < 14; i++) { double y = 16 + i * 2.2, z = c + ((i * 7) % 11 - 5) * (1 - i / 20.0); double r = .7 + (i * 3 % 4) * .3; if (i % 2 == 0) m.blob(c + 3.2, y, z, r, r * 1.7, r, CRYSTAL, 0); m.blob(c - 1.2, y + 1, z + 1.5, r * .8, r * 1.4, r * .8, CRYSTAL, 0); }
        for (int i = 0; i < 5; i++) { double a = i * 1.26 + .5; m.tube(m.curve(20, t -> new double[]{c + (9 + t * 15) * Math.cos(a + Math.sin(t * 5) * .1), 11.2, c + (9 + t * 15) * Math.sin(a + Math.sin(t * 5) * .1)}), .01, .01, GLOW); }
        m.blob(c + 1, 29, c, 2, 2.6, 2.4, CORE, 0); m.core = new double[]{c + 1, 29, c};
        for (int i = 0; i < 7; i++) m.blob(c + 1, 16 + i * 2, c + 5 - i * 1.4 + (i % 2) * 2, 1.4, .5, 1.6, CAP, 0);
        m.player((int) c + 3, (int) c + 13);
        m.cutX = (int) c + 1;
        m.inPaths.add(new double[][]{{c + 1, 12, c + 12}, {c + 1, 14, c + 8}, {c + 1, 18, c + 5}, {c + 1, 24, c + 3}, {c + 1, 28, c + 2}});
        m.outPaths.add(m.curve(40, t -> { double R = 11.5 - 7.5 * t, a = 1.2 + t * 5.2; return new double[]{c + 1 + t * 2 + R * Math.cos(a), 9 + t * 44, c - 1 - t + R * Math.sin(a)}; }));
        m.mark(c + 2, 15, c + 9, "1", 2, "入口。岩柱の根元の割れ目(縦に長いすき間)");
        m.mark(c - 2, 22, c + 4, "2", 1, "割れ目の中を棚きのこの足場で登る。両側の壁から結晶が生えている(段 3 の結晶)");
        m.mark(c - 2, 30, c - 2, "芯", 1, "割れ目の真ん中、岩の間に挟まった光る塊。根がここから岩を割っている");
        m.mark(c + 9, 30, c + 5, "3", 0, "巻き付く根と光る筋。光が地面から上へ流れる(吸っている)。根はらせんの道として登れる");
        m.mark(c + 14, 12, c + 12, "4", 0, "地面を走る光るひび。地脈の線の向き。近くの汲み上げ器はここへ力を取られる");
        m.mark(c + 4, 44, c + 2, "5", 0, "岩の先から噴き出した結晶。根が締めるたびに岩がきしみ、かけらが落ちる");
        return m;
    }

    // =====================================================================
    // 入口
    // =====================================================================

    /** 形の数(崩れた版を含む)。最後の 1 つが崩れた版。 */
    public static int variants(OrganKind kind) {
        return kind == OrganKind.TOWER ? 4 : 3;
    }

    /** 崩れた版か。 */
    public static boolean isRuin(OrganKind kind, int variant) {
        return variant == variants(kind) - 1;
    }

    /** 形の名前(A・B・C・D)。 */
    public static String variantName(int variant) {
        return String.valueOf((char) ('A' + variant));
    }

    /**
     * 器官の形を作る。{@code seed} が同じなら同じ形。純粋な計算(背景のスレッドで呼んでよい)。
     * 崩れた版の傾きは organs セッションの絵の値。種で崩れ方(穴・こぶ・垂れる蔦の場所)だけが変わる。
     */
    static Model build(OrganKind kind, int variant, int seed) {
        Model m = buildRaw(kind, variant, seed);
        ensureCore(m);
        return m;
    }

    /** 芯が後の手順で削られて小さくなりすぎた形(崩れた塔など)に、芯の真ん中へ芯の塊を置き直す。 */
    static void ensureCore(Model m) {
        if (m.core == null) return;
        int n = histogram(m)[CORE];
        if (n >= 8) return;
        m.blob(m.core[0], m.core[1], m.core[2], 1.6, 2.0, 1.6, CORE, 0);
    }

    private static Model buildRaw(OrganKind kind, int variant, int seed) {
        SEED.set(seed & 0xffff);
        try {
            int rs = Math.floorMod(seed, 997);
            return switch (kind) {
                case TOWER -> switch (variant) {
                    case 0 -> towerA();
                    case 1 -> towerB();
                    case 2 -> towerC();
                    default -> towerD();
                };
                case PHASE -> switch (variant) {
                    case 0 -> phaseA();
                    case 1 -> phaseB();
                    default -> ruin(phaseA(), "C", "", .16, .10, 3 + rs, "");
                };
                case SAC -> switch (variant) {
                    case 0 -> storA();
                    case 1 -> storB();
                    default -> ruin(storA(), "C", "", .20, -.08, 5 + rs, "");
                };
                case NURSERY -> switch (variant) {
                    case 0 -> nursA();
                    case 1 -> nursB();
                    default -> ruin(nursB(), "C", "", .18, .14, 8 + rs, "");
                };
                case LEY -> switch (variant) {
                    case 0 -> leyA();
                    case 1 -> leyB();
                    default -> ruin(leyB(), "C", "", .24, .06, 11 + rs, "");
                };
            };
        } finally {
            SEED.remove();
        }
    }

    /** 確かめ用: 材料ごとの数(添字 = 材料。掘る印を含む)。 */
    static int[] histogram(Model m) {
        int[] h = new int[32];
        for (int x = 0; x < m.W; x++) for (int y = 0; y < m.H; y++) for (int z = 0; z < m.D; z++) h[m.v[x][y][z]]++;
        return h;
    }

    // =====================================================================
    // 小さい版(2026-11-17 決定。a・b を 1 枚の絵に並べてあるので、切り分けて使う)
    // =====================================================================
    static Model towerSmall() {
        Model m = new Model(40, 22, 22, 4, "胞子の塔・小 ― 林床にある家〜小屋の大きさ", "左 a「噴き出しこぶ」高さ 約 8 / 右 b「嚢の房の切り株」高さ 約 10【仮】。どちらも 1 部屋で、芯はすぐ奥");
        m.scale = 20; m.cutX = 0; m.cutZ = 11; m.dimGround = true;
        // 地面は世界のものを使う(絵の地面は置かない)
        // a: こぶ
        double ax = 10, az = 10;
        m.blob(ax, 6, az, 6, 4.5, 6, MOSS, .25); m.patch(MOSS, BARK, .6, .2, 4, 22); m.patch(MOSS, MOSS2, .68, .3, 4, 22);
        double[][] st = {{ax - 2, az - 2, 12}, {ax + 2.5, az - 1, 10}, {ax - .5, az + 2.5, 9}};
        for (double[] q : st) { m.tube(new double[][]{{q[0], 8, q[1]}, {q[0], q[2], q[1]}}, 1.6, 1.3, BARK); m.set((int) q[0], (int) q[2], (int) q[1], SAC); m.plumes.add(new double[]{q[0] + .5, q[2] + 1, q[1] + .5, .3}); }
        m.blob(ax, 6.5, az, 3.4, 2.2, 3.4, AIR, 0); m.tube(new double[][]{{ax, 5.5, az + 7}, {ax, 6, az + 2}}, 1.2, 1.2, AIR);
        m.set((int) ax, 7, (int) az - 2, CORE); m.set((int) ax, 6, (int) az - 2, CORE);
        m.tube(new double[][]{{ax + 4, 7, az + 3}, {ax + 7, 9, az + 5}, {ax + 8, 6, az + 6}}, .6, .5, LIANA);
        m.stud(MOSS, SAC, .02, 5, 10, .8);
        // b: 切り株と嚢の房
        double bx = 29, bz = 10;
        m.tube(new double[][]{{bx, 4, bz}, {bx, 10, bz}}, 3.6, 3.0, BARK2); m.patch(BARK2, MOSS, .6, .2, 4, 22);
        m.tube(new double[][]{{bx, 5, bz}, {bx, 9, bz}}, 2.0, 1.8, AIR); m.tube(new double[][]{{bx, 5.5, bz + 5}, {bx, 5.5, bz}}, 1.1, 1.1, AIR);
        m.blob(bx, 12, bz, 3.2, 2.6, 3.2, SAC, .2); m.blob(bx - 3, 10, bz + 2, 1.8, 1.8, 1.8, SAC, 0); m.blob(bx + 3, 11, bz + 1, 1.6, 1.6, 1.6, SACW, 0);
        m.tube(new double[][]{{bx, 9, bz}, {bx, 16, bz}}, .8, .8, AIR); m.plumes.add(new double[]{bx + .5, 15, bz + .5, .45});
        m.set((int) bx, 8, (int) bz, CORE); m.set((int) bx, 9, (int) bz, CORE);
        for (int i = 0; i < 4; i++) { double a = i * 1.6 + .5; m.tube(new double[][]{{bx + 3 * Math.cos(a), 6, bz + 3 * Math.sin(a)}, {bx + 6 * Math.cos(a), 4.5, bz + 6 * Math.sin(a)}}, .9, .6, ROOT); }
        m.tube(new double[][]{{bx - 3, 9, bz + 3}, {bx - 6, 11, bz + 5}, {bx - 7, 7, bz + 6}}, .6, .5, LIANA);
        m.player(19, 17); m.player((int) bx + 5, 17);
        m.mark(ax, 9.5, az - 3, "芯", 1, "a はこぶの奥の壁、b は切り株の中の天井(嚢の付け根)。2 ブロックほどの塊");
        m.mark(bx + 1.5, 10, bz, "芯", 1, null);
        m.mark(ax, 6.5, az + 7.5, "1", 0, "入口(かがまずに入れる 2 ブロックの穴)");
        m.mark(bx, 6.5, bz + 5.5, "1", 0, null);
        m.mark(ax + 8, 9, az + 5, "2", 0, "蔦の触手が 1 本だけ動く");
        m.mark(bx + 4, 13, bz + 2, "3", 0, "b は嚢の房そのものが噴き出し口。叩くと破裂して胞子をかぶる");
        return m;
    }
    static Model sm(String title, String sub) {
        Model m = new Model(38, 20, 20, 3, title, sub); m.scale = 20; m.cutX = 0; m.cutZ = 10; m.dimGround = true;
        // 地面は世界のものを使う(絵の地面は置かない)
        return m;
    }
    static Model phaseSmall() {
        Model m = sm("フェーズを進める器官・小", "左 a「標本のこぶ」高さ 約 7 / 右 b「髄の芽」高さ 約 10【仮】");
        double ax = 10, az = 10, bx = 28, bz = 10;
        for (int i = 0; i < 7; i++) { double a = i * .9; m.tube(m.curve(10, t -> { double ph = t * Math.PI / 2; return new double[]{ax + 5 * Math.cos(ph) * Math.cos(a), 3 + 6.5 * Math.sin(ph), az + 5 * Math.cos(ph) * Math.sin(a)}; }), .8, .7, i % 2 == 0 ? BARK : BARK2); }
        shell(m, ax, 3, az, 5, 6.5, 5, 1.2, MOSS, .55, .25);
        hang(m, ax - 1.5, 8, az - 1, 1, EGG, 1.3); hang(m, ax + 2, 8, az - 2, 2, EGG, 1.1);
        m.set((int) ax, 4, (int) az - 1, CORE); m.set((int) ax, 5, (int) az - 1, CORE); m.set((int) ax, 3, (int) az - 1, PITH);
        m.tube(new double[][]{{ax, 9.5, az}, {ax + 2, 12, az + 1}}, .5, .5, LIANA); m.set((int) ax + 2, 13, (int) az + 1, GLOW);
        m.tube(new double[][]{{bx, 3, bz}, {bx, 9, bz}}, 2.8, 2.0, BARK2); m.blob(bx, 10, bz, 3.2, 2.2, 3.2, PITH, .2); m.patch(PITH, MOSS, .6, .2, 0, 20); m.patch(BARK2, MOSS, .62, .2, 4, 20);
        for (int i = 0; i < 4; i++) { double a = i * 1.6 + .4; m.tube(new double[][]{{bx, 10, bz}, {bx + 4 * Math.cos(a), 13, bz + 4 * Math.sin(a)}, {bx + 5.5 * Math.cos(a), 15, bz + 5.5 * Math.sin(a)}}, .8, .6, BARK); m.set((int) (bx + 5.5 * Math.cos(a)), 16, (int) (bz + 5.5 * Math.sin(a)), CRYSTAL); }
        m.tube(new double[][]{{bx, 4, bz}, {bx, 8, bz}}, 1.3, 1.3, AIR); m.tube(new double[][]{{bx, 4.5, bz + 4}, {bx, 4.5, bz}}, 1.1, 1.1, AIR);
        m.set((int) bx, 9, (int) bz, CORE); m.set((int) bx, 10, (int) bz, CORE);
        m.player(19, 16); m.player((int) bx + 5, 16);
        m.mark(ax + 1, 5, az - 2, "芯", 1, "a は籠の床の光る芽、b は節の付け根(うろの天井)");
        m.mark(bx + 1.5, 10, bz - 1, "芯", 1, null);
        m.mark(ax - 2, 6.5, az - 1, "1", 1, "標本の嚢(a)。小さな動物を包んでいる");
        m.mark(bx + 4, 16, bz + 3, "2", 0, "光る芽(b)。夜は遠くからこれが見えて見つかる");
        return m;
    }
    static Model storSmall() {
        Model m = sm("蓄えの嚢・小", "左 a「根に抱かれた嚢」高さ 約 7 / 右 b「幹から下がる房」高さ 約 11【仮】");
        double ax = 10, az = 10, bx = 28, bz = 10;
        m.blob(ax, 6.5, az, 4.6, 4, 4.6, SAC, .1); m.patch(SAC, SACW, .6, .25, 0, 20);
        for (int i = 0; i < 4; i++) { double a = i * Math.PI / 4 + .4; m.tube(m.curve(20, t -> { double ph = t * Math.PI; return new double[]{ax + 5 * Math.cos(ph) * Math.cos(a), 5 + 6 * Math.sin(ph), az + 5 * Math.cos(ph) * Math.sin(a)}; }), .6, .6, i % 2 == 0 ? BARK : ROOT); }
        m.blob(ax, 6.5, az, 3, 2.6, 3, AIR, 0); m.tube(new double[][]{{ax, 5, az + 6}, {ax, 5.5, az + 2}}, 1.1, 1.1, AIR);
        for (int x = (int) ax - 3; x <= ax + 3; x++) for (int z = (int) az - 3; z <= az + 3; z++) if (m.get(x, 4, z) == AIR) m.set(x, 4, z, PITH);
        m.set((int) ax, 5, (int) az - 2, CORE); m.set((int) ax, 6, (int) az - 2, CORE);
        m.tube(m.curve(12, t -> new double[]{bx - 6 + t * 9, 3 + 11 * Math.sin(t * 2.2), bz - 1}), 1.5, 1.1, BARK2); m.patch(BARK2, MOSS, .62, .2, 4, 20);
        m.blob(bx, 8, bz, 2.6, 3, 2.6, SAC, 0); m.blob(bx - 3.5, 9.5, bz + 1, 1.7, 2, 1.7, SACW, 0); m.blob(bx + 3, 7, bz + 1, 1.8, 2.2, 1.8, SAC, 0);
        m.tube(new double[][]{{bx, 11, bz}, {bx, 13, bz - 1}}, .6, .6, LIANA); m.blob(bx, 8, bz, 1.5, 1.8, 1.5, AIR, 0);
        m.set((int) bx, 13, (int) bz - 1, CORE); m.set((int) bx, 12, (int) bz - 1, CORE);
        m.player(18, 16); m.player((int) bx + 6, 16);
        m.mark(ax + 1, 6, az - 3, "芯", 1, "a は嚢の中の奥の壁(床に蜜)、b は幹と房の付け根(登って叩くか、下から撃つ)");
        m.mark(bx + 1.5, 13, bz - 1, "芯", 1, null);
        m.mark(ax + 4, 9, az + 2, "1", 0, "締め付ける根。呼吸でふくらむ");
        m.mark(bx + 4, 7, bz + 2, "2", 0, "房の嚢は叩くと破裂");
        return m;
    }
    static Model nursSmall() {
        Model m = sm("苗床の群体・小", "左 a「卵の巣」高さ 約 5 / 右 b「蜂の巣の切り株」高さ 約 9【仮】");
        double ax = 10, az = 10, bx = 28, bz = 10;
        for (int i = 0; i < 6; i++) { double a = i * 1.05 + .3; m.tube(m.curve(10, t -> new double[]{ax + (5 - 3 * t * t) * Math.cos(a), 3 + 6 * t, az + (5 - 3 * t * t) * Math.sin(a)}), .9, .5, i % 2 == 0 ? BARK : BARK2); }
        m.tube(m.curve(20, t -> new double[]{ax + 4.2 * Math.cos(t * 6.3), 3.6, az + 4.2 * Math.sin(t * 6.3)}), 1, 1, MOSS);
        for (int i = 0; i < 6; i++) { double a = i * 1.1; m.blob(ax + 2.3 * Math.cos(a), 4, az + 2.3 * Math.sin(a), 1, 1.3, 1, EGG, 0); }
        m.set((int) ax, 3, (int) az, ROOT); m.set((int) ax, 4, (int) az - 1, CORE); m.set((int) ax, 5, (int) az - 1, CORE);
        m.tube(new double[][]{{bx, 3, bz}, {bx, 10, bz}}, 3.6, 2.8, BARK2); m.patch(BARK2, MOSS, .6, .2, 4, 20);
        m.tube(new double[][]{{bx, 4, bz}, {bx, 8, bz}}, 1.6, 1.4, AIR); m.tube(new double[][]{{bx, 4.5, bz + 5}, {bx, 4.5, bz}}, 1.1, 1.1, AIR);
        for (int y = 5; y <= 9; y += 2) for (int k = 0; k < 6; k++) { double a = k * 1.05 + y; int x = (int) Math.round(bx + 3.2 * Math.cos(a)), z = (int) Math.round(bz + 3.2 * Math.sin(a)); m.set(x, y, z, AIR); m.set((int) Math.round(bx + 2.4 * Math.cos(a)), y, (int) Math.round(bz + 2.4 * Math.sin(a)), EGG); }
        m.blob(bx, 11.5, bz, 1.6, 1.2, 1.6, LEAF, 0); m.set((int) bx, 4, (int) bz - 1, CORE); m.set((int) bx, 5, (int) bz - 1, CORE);
        m.player(18, 16); m.player((int) bx + 6, 16);
        m.mark(ax + 1, 5, az - 2, "芯", 1, "a は卵に囲まれた真ん中の球根、b は切り株のうろの奥");
        m.mark(bx + 1.5, 5, bz - 2, "芯", 1, null);
        m.mark(ax - 3, 5, az - 1, "1", 1, "卵のような粒。近づくと割れて手先が出る");
        m.mark(bx + 3.5, 8, bz + 2, "2", 0, "小部屋の穴と粒(b)。てっぺんに芽");
        return m;
    }
    static Model leySmall() {
        Model m = sm("地脈を吸う根・小", "左 a「光るひびの根の門」高さ 約 7 / 右 b「岩をつかむ根」高さ 約 8【仮】");
        double ax = 10, az = 10, bx = 28, bz = 10;
        m.tube(m.curve(14, t -> new double[]{ax - 5 + t * 10, 3 + 7 * Math.sin(t * Math.PI), az - 1}), 1.4, 1.4, ROOT);
        m.tube(m.curve(14, t -> new double[]{ax - 1 + t * 1, 3 + 7 * Math.sin(t * Math.PI * .5), az - 5 + t * 4}), 1.1, 1.1, BARK2);
        m.tube(new double[][]{{ax - 7, 3, az + 1}, {ax, 3, az}, {ax + 8, 3, az - 2}}, .01, .01, GLOW);
        m.blob(ax + 1, 4.5, az, 1, 1.8, 1, CRYSTAL, 0); m.set((int) ax - 2, 4, (int) az, CRYSTAL);
        m.set((int) ax, 10, (int) az - 1, CORE); m.set((int) ax, 9, (int) az - 1, CORE);
        m.blob(bx, 6, bz, 3.6, 4, 3.6, STONE, .2); m.patch(STONE, MOSS, .62, .2, 4, 20);
        for (int i = 0; i < 4; i++) { double a = i * 1.6 + .3; m.tube(m.curve(14, t -> new double[]{bx + (6 - 5.5 * t) * Math.cos(a + t), 3 + 8 * t, bz + (6 - 5.5 * t) * Math.sin(a + t)}), 1.0, .7, i % 2 == 0 ? ROOT : BARK2); }
        for (int y = 4; y < 10; y++) for (int z = (int) bz - 2; z <= bz + 4; z++) m.set((int) bx, y, z, AIR);
        m.set((int) bx, 5, (int) bz - 1, CORE); m.set((int) bx, 6, (int) bz - 1, CORE);
        m.blob(bx + 3, 9, bz + 1, .9, 1.6, .9, CRYSTAL, 0); m.set((int) bx - 1, 7, (int) bz + 3, CRYSTAL);
        m.tube(new double[][]{{bx - 8, 3, bz + 2}, {bx, 3, bz + 1}, {bx + 8, 3, bz + 3}}, .01, .01, GLOW);
        m.player(18, 16); m.player((int) bx + 6, 16);
        m.mark(ax + 1.5, 9.5, az - 1, "芯", 1, "a は根の門のてっぺんの結び目、b は割れた岩の間");
        m.mark(bx + 1.5, 6, bz - 2, "芯", 1, null);
        m.mark(ax - 5, 4, az + 2, "1", 0, "地面の光るひび(地脈の線)。小さな結晶の芽が生える");
        m.mark(bx + 4, 9, bz + 2, "2", 0, "岩から出た結晶。段 3 の結晶の小さい産地");
        return m;
    }

    /** 小さい版の形の数(a・b)。 */
    public static final int SMALL_VARIANTS = 2;

    /**
     * 小さい版を作る(a = 0・b = 1)。絵は a と b を左右に並べた 1 枚なので、真ん中で切り分けて片方だけにする。
     * 芯の真ん中は、切り出した側の芯のブロックの平均。
     */
    static Model buildSmall(OrganKind kind, int variant, int seed) {
        SEED.set(seed & 0xffff);
        try {
            Model both = switch (kind) {
                case TOWER -> towerSmall();
                case PHASE -> phaseSmall();
                case SAC -> storSmall();
                case NURSERY -> nursSmall();
                case LEY -> leySmall();
            };
            int split = both.W / 2;
            int x0 = variant == 0 ? 0 : split, x1 = variant == 0 ? split : both.W;
            Model m = new Model(x1 - x0, both.H, both.D, both.G, both.title, both.sub);
            double sx = 0, sy = 0, sz = 0;
            int n = 0;
            for (int x = x0; x < x1; x++) for (int y = 0; y < both.H; y++) for (int z = 0; z < both.D; z++) {
                int v = both.raw(x, y, z);
                if (v == AIR || v == PLAYER) continue;
                m.v[x - x0][y][z] = (byte) v;
                if (v == CORE) { sx += x - x0; sy += y; sz += z; n++; }
            }
            if (n > 0) m.core = new double[]{sx / n, sy / n, sz / n};
            for (double[] p : both.plumes) if (p[0] >= x0 && p[0] < x1) m.plumes.add(new double[]{p[0] - x0, p[1], p[2], p[3]});
            return m;
        } finally {
            SEED.remove();
        }
    }
}
