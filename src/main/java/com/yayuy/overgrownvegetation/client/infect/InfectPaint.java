package com.yayuy.overgrownvegetation.client.infect;

import com.mojang.blaze3d.platform.NativeImage;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.io.InputStream;

/**
 * 汎用の感染体の<b>塗りの決まり</b>(G1 段階 0 の 11 種から集めた決まり。元は {@code tools/parasite-parts/InfectPaint.java} と {@code Infect.java})。
 * 元の絵を 1 画素ずつ塗り替える。箱や骨の名前は使わず、{@link InfectSkins.FaceMap}(画素ごとの体の位置・面の向き)だけを見る。
 * <ul>
 *   <li><b>名残は覆わない</b>: とても暗い画素・小さな面で周りよりずっと暗い画素(目・口・鼻の穴)・絵の中で少しだけの鮮やかな色(赤い目など)。
 *   名残のある横向きの小さな面(顔)は、苔をごく薄くする。</li>
 *   <li><b>死体の色</b>: 覆われていない皮も灰緑へ寄せて暗く(明るい毛ほど強く。白い毛が苔の間に残ると牛の模様に見えた)。</li>
 *   <li><b>苔</b>: {@code overgrown_block}(35% は {@code cave_moss_clump})の画素そのまま。2×2 の塊。寄生の中心ほど厚く、上を向く面と高い所に多く、腹には少ない。</li>
 *   <li><b>縁</b>: 暗い影が基本。赤黒は 2×2 の塊の 15% だけ、髄の白はまれに 1 画素。その外に汚れ。</li>
 * </ul>
 * 根の筋・裂け目・胞子の袋は、部品を付けるときに足す(まだ無い)。
 */
final class InfectPaint {

    private InfectPaint() {
    }

    private static final int[] SHADOW = {0x1E1D12, 0x232118, 0x1A1A10, 0x2A2718};
    private static final int[] FLESH = {0x3A1D17, 0x4A261E};
    private static final int PITH_WHITE = 0x9C9576;
    private static final double KEEP_RED = 0.15;

    /** 苔のかたまりのむらの大きさ(単位 = バニラの画素)。【仮】 */
    private static final double BLOB_UNITS = 3.0;
    /**
     * 確かめ用にその場で変えられる数(`geninfect set <番号> <値>`): 0 下地の覆い・1 大きなむらの大きさ(単位)・2 小さなむらの大きさ・
     * 3 小さなむらの重み・4 寄生の中心の半径の倍率・5 むらの濃淡の強さ・6 塊ごとの粒の乱れ。【仮】
     */
    static final double[] TUNE = {0.12, 6.0, 2.0, 0.45, 1.45, 3.2, 0.15};
    /** 根の筋のむらの大きさ(単位)と、筋の太さ(むらの値の幅)。【仮】 */
    private static final double ROOT_UNITS = 9.0, ROOT_BAND = 0.03;
    /** 下地の覆い(寄生の中心から遠い所)。 */
    private static final double BASE_COVER = 0.06;
    /** 寄生の中心の半径(体のいちばん長い辺に対して)。主・反対側・おまけ。 */
    private static final double[] CENTER_RADIUS = {0.42, 0.24, 0.16};

    @Nullable
    private static Tile moss, caveMoss, bark, cambium;
    /** 根の筋の色(暗い樹皮)。 */
    private static final int[] ROOT = {0x2A2414, 0x332B17, 0x3B3219, 0x252012};
    /** 確かめ用(書き出しのときだけ): 直前に塗った絵の、画素ごとの覆いの濃さとむらの値。 */
    @Nullable
    static float[] debugCover, debugNoise;

    private record Tile(int w, int h, int[] argb) {
        int at(int x, int y) {
            return this.argb[Math.floorMod(y, this.h) * this.w + Math.floorMod(x, this.w)];
        }
    }

    static void unload() {
        moss = null;
        caveMoss = null;
        bark = null;
        cambium = null;
    }

    private static void load() {
        if (moss == null) {
            moss = tile("textures/block/overgrown_block.png", 0xFF3E5A2A);
            caveMoss = tile("textures/block/cave_moss_clump.png", 0x00000000);
            bark = tile("textures/block/overgrown_log.png", 0xFF3A3020);
            cambium = tile("textures/block/giant_overgrown_cambium.png", 0xFF8A8060);
        }
    }

    private static Tile tile(String path, int fallback) {
        ResourceLocation id = OvergrownVegetation.id(path);
        try {
            var res = Minecraft.getInstance().getResourceManager().getResource(id);
            if (res.isPresent()) {
                try (InputStream in = res.get().open(); NativeImage img = NativeImage.read(in)) {
                    int w = img.getWidth(), h = Math.min(img.getHeight(), img.getWidth());
                    int[] px = new int[w * h];
                    for (int y = 0; y < h; y++) {
                        for (int x = 0; x < w; x++) {
                            int c = img.getPixelRGBA(x, y);
                            px[y * w + x] = (c & 0xFF00FF00) | ((c & 0xFF) << 16) | ((c >> 16) & 0xFF);
                        }
                    }
                    return new Tile(w, h, px);
                }
            }
        } catch (Exception ex) {
            OvergrownVegetation.LOGGER.warn("InfectPaint 苔の絵を読めない: {}", id, ex);
        }
        return new Tile(1, 1, new int[]{fallback});
    }

    /** 塗り替えた絵(ARGB)を返す。{@code seed} = 絵と通り番号から。 */
    /**
     * {@code strength} = 感染の深さ(1 = 変わり終えた姿。変わる途中は小さい値 = 寄生の中心の芯だけ苔が出て、死体の色も半分)。
     * 同じ {@code seed} なら寄生の中心と塊の並びは同じ = 段を上げると同じ所から広がって見える。
     */
    static int[] paint(InfectSkins.FaceMap m, long seed, double strength, float[] tears) {
        load();
        int w = m.w, h = m.h, n = w * h, k = m.k;
        int[] out = m.argb.clone();
        boolean[] used = new boolean[n];
        int usedCount = 0;
        for (int i = 0; i < n; i++) {
            used[i] = m.quad[i] >= 0 && (m.argb[i] >>> 24) >= 16;
            if (used[i]) {
                usedCount++;
            }
        }
        if (usedCount == 0) {
            return out;
        }

        // ---- 名残(覆わない画素)
        float[] qLum = new float[m.quadCount];
        int[] qCount = new int[m.quadCount];
        int vivid = 0;
        for (int i = 0; i < n; i++) {
            if (used[i]) {
                qLum[m.quad[i]] += lum(m.argb[i]);
                qCount[m.quad[i]]++;
                if (isVivid(m.argb[i])) {
                    vivid++;
                }
            }
        }
        boolean vividIsRare = vivid < usedCount * 0.08;
        // 体じゅうが暗い Mob(エンダーマン)は、暗い画素を名残にしない(苔が 1 つも乗らなくなる)
        int darkCount = 0;
        for (int i = 0; i < n; i++) {
            if (used[i] && lum(m.argb[i]) < 28) {
                darkCount++;
            }
        }
        boolean darkIsRare = darkCount < usedCount * 0.3;
        boolean[] keep = new boolean[n];
        int[] qKeep = new int[m.quadCount];
        // 小さな面 = 16 単位²(4×4)〜100 単位²(10×10)まで。大きな面の暗い模様(牛のぶち)は名残にしない
        int smallFace = 100 * k * k;
        for (int i = 0; i < n; i++) {
            if (!used[i]) {
                continue;
            }
            int q = m.quad[i];
            int l = lum(m.argb[i]);
            boolean small = qCount[q] <= smallFace;
            if ((darkIsRare && l < 28) || (vividIsRare && isVivid(m.argb[i])) || (small && l < 0.55F * qLum[q] / qCount[q])) {
                keep[i] = true;
                qKeep[q]++;
            }
        }
        // 顔 = 名残のある、横向きの、高い所の小さな面
        boolean[] face = new boolean[m.quadCount];
        for (int q = 0; q < m.quadCount; q++) {
            face[q] = qCount[q] > 0 && qCount[q] <= smallFace && Math.abs(m.quadNy[q]) < 0.5F && m.quadYRel[q] >= 0.4F
                    && qKeep[q] >= 2 * k * k && qKeep[q] < qCount[q] * 0.4;
        }

        // ---- 寄生の中心(体の位置で。左右差が出るよう、実際の面の上の点から選ぶ)
        int[] centers = new int[CENTER_RADIUS.length];
        int centerCount = 0;
        // 主の中心は胴(いちばん広い面の半分より広い面)に置く。頭や脚が丸ごと緑にならないように
        int[] gCount = new int[m.quadCount];
        for (int i = 0; i < n; i++) {
            if (m.quad[i] >= 0) {
                gCount[m.quad[i]]++;
            }
        }
        int maxCount = 0;
        for (int c : gCount) {
            maxCount = Math.max(maxCount, c);
        }
        final int torso = maxCount / 2;
        // 中心は体の形だけから選ぶ(絵の透明・色に依らない = 同じ体の別の絵でも同じ所になる)
        boolean[] geom = new boolean[n];
        for (int i = 0; i < n; i++) {
            geom[i] = m.quad[i] >= 0;
        }
        int main = pick(m, geom, seed + 1, i -> m.ny[i] > 0.3F && m.yRel(i) > 0.5F && gCount[m.quad[i]] >= torso);
        if (main < 0) {
            main = pick(m, geom, seed + 7, i -> m.yRel(i) > 0.4F && gCount[m.quad[i]] >= torso);
        }
        if (main < 0) {
            main = pick(m, geom, seed + 2, i -> m.yRel(i) > 0.4F);
        }
        if (main < 0) {
            main = pick(m, geom, seed + 3, i -> true);
        }
        centers[centerCount++] = main;
        final int mainF = main;
        int far = pick(m, geom, seed + 4, i -> dist(m, i, mainF) > 0.55 * m.extent);
        if (far >= 0) {
            centers[centerCount++] = far;
        }
        if (hash(5, 5, seed + 5) < 0.6) {
            int extra = pick(m, geom, seed + 6, i -> dist(m, i, mainF) > 0.3 * m.extent);
            if (extra >= 0) {
                centers[centerCount++] = extra;
            }
        }

        // ---- 苔の塊
        int cs = 2 * k;
        boolean[] mossy = new boolean[n];
        float[] coverAt = new float[n];
        debugCover = InfectSkins.dumpDir != null ? new float[n] : null;
        debugNoise = InfectSkins.dumpDir != null ? new float[n] : null;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                if (!used[i] || keep[i]) {
                    continue;
                }
                float ny = m.ny[i];
                double cover = TUNE[0] + (ny > 0.5F ? 0.14 : ny < -0.5F ? -0.25 : 0.0) + 0.12 * (m.yRel(i) - 0.5);
                for (int c = 0; c < centerCount; c++) {
                    cover = Math.max(cover, 1.05 - dist(m, i, centers[c]) / (CENTER_RADIUS[c] * TUNE[4] * m.extent));
                }
                if (face[m.quad[i]]) {
                    cover = Math.min(cover * 0.3, 0.2);
                }
                cover -= (1.0 - strength) * 0.7;
                // 塊ごとに(同じ面の、塊の最初の画素の)体の位置で、立体のむらを引く = 箱の辺・箱の境目をまたいで苔のかたまりが続く
                int rep = i;
                int bx = Math.floorDiv(x, cs) * cs, by = Math.floorDiv(y, cs) * cs;
                find:
                for (int yy = by; yy < Math.min(h, by + cs); yy++) {
                    for (int xx = bx; xx < Math.min(w, bx + cs); xx++) {
                        int j = yy * w + xx;
                        if (used[j] && m.quad[j] == m.quad[i]) {
                            rep = j;
                            break find;
                        }
                    }
                }
                double big = m.unit * TUNE[1], small = m.unit * TUNE[2];
                double nz = (1.0 - TUNE[3]) * noise3(m.px[rep] / big, m.py[rep] / big, m.pz[rep] / big, seed + 11)
                        + TUNE[3] * noise3(m.px[rep] / small, m.py[rep] / small, m.pz[rep] / small, seed + 12);
                nz = Math.max(0.0, Math.min(1.0, (nz - 0.5) * TUNE[5] + 0.5));
                nz = nz * (1.0 - TUNE[6]) + TUNE[6] * hash(bx, by, seed + 13);
                mossy[i] = nz < cover;
                coverAt[i] = (float) cover;
                if (debugCover != null) {
                    debugCover[i] = (float) cover;
                    debugNoise[i] = (float) nz;
                }
            }
        }

        // ---- 根の筋(変わり終えた姿だけ): 寄生の中心のまわりから外へ、体の表面を這う暗い筋。
        // 立体のむらの等しい値の面が体の表面と交わる線を使う = 面や箱をまたいでも 1 本につながる
        boolean[] root = new boolean[n];
        if (strength >= 1.0) {
            double cell = m.unit * ROOT_UNITS;
            for (int i = 0; i < n; i++) {
                if (!used[i] || keep[i] || face[m.quad[i]] || coverAt[i] < 0.2F || coverAt[i] > 0.97F) {
                    continue;
                }
                double v = noise3(m.px[i] / cell, m.py[i] / cell, m.pz[i] / cell, seed + 21);
                root[i] = Math.abs(v - 0.5) < ROOT_BAND;
            }
        }

        // ---- 色を置く
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                if (!used[i] || keep[i]) {
                    continue;
                }
                int a = Math.floorDiv(x, k), b = Math.floorDiv(y, k);
                double hsh = hash(a, b, seed + 17);
                int alpha = m.argb[i] & 0xFF000000;
                int raw = m.argb[i] & 0xFFFFFF;
                int orig = strength >= 1.0 ? deaden(raw) : mix(raw, deaden(raw), strength);
                int rgb;
                // 裂け目(胞子の袋のまわり): 中 = 暗い繊維、縁 = 破れた皮
                double tearE = Double.MAX_VALUE;
                if (strength >= 1.0) {
                    for (int t = 0; t + 3 < tears.length; t += 4) {
                        double dx = m.px[i] - tears[t], dy = m.py[i] - tears[t + 1], dz = m.pz[i] - tears[t + 2];
                        tearE = Math.min(tearE, Math.sqrt(dx * dx + dy * dy + dz * dz) / tears[t + 3]);
                    }
                }
                if (tearE < 2.1 && !face[m.quad[i]]) {
                    rgb = tearPx(mossy[i] ? mossPx(a, b, hsh, seed) : orig, tearE, a, b, hsh, seed);
                } else if (root[i]) {
                    rgb = ROOT[(int) (hash(a, b, seed + 57) * ROOT.length) % ROOT.length];
                } else if (!mossy[i] && near(root, used, w, h, x, y, k)) {
                    // 根の筋のまわりは苔がにじむ
                    rgb = mix(orig, scale(moss.at(a + 5, b) & 0xFFFFFF, 0.7), 0.6);
                } else if (mossy[i]) {
                    rgb = mossPx(a, b, hsh, seed);
                } else if (near(mossy, used, w, h, x, y, k)) {
                    rgb = edgePx(orig, a, b, hsh, seed);
                } else if (near(mossy, used, w, h, x, y, 2 * k) && hsh < 0.75) {
                    rgb = mix(scale(orig, 0.75), 0x2A2A18, 0.3);
                } else {
                    rgb = orig;
                }
                out[i] = alpha | rgb;
            }
        }
        return out;
    }

    private interface TexelTest {
        boolean ok(int i);
    }

    /** 使われている画素から、条件に合う 1 つを乱数で選ぶ(無ければ −1)。画素の数で選ぶので、広い面ほど選ばれやすい。 */
    private static int pick(InfectSkins.FaceMap m, boolean[] used, long seed, TexelTest test) {
        int n = m.w * m.h, count = 0;
        for (int i = 0; i < n; i++) {
            if (used[i] && test.ok(i)) {
                count++;
            }
        }
        if (count == 0) {
            return -1;
        }
        int target = Math.min(count - 1, (int) (hash(count, 77, seed) * count));
        for (int i = 0; i < n; i++) {
            if (used[i] && test.ok(i) && target-- == 0) {
                return i;
            }
        }
        return -1;
    }

    private static double dist(InfectSkins.FaceMap m, int i, int j) {
        double dx = m.px[i] - m.px[j], dy = m.py[i] - m.py[j], dz = m.pz[i] - m.pz[j];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** 上下左右に {@code d} 画素までの所に苔があるか。 */
    private static boolean near(boolean[] mossy, boolean[] used, int w, int h, int x, int y, int d) {
        for (int s = 1; s <= d; s++) {
            if ((x - s >= 0 && mossy[y * w + x - s]) || (x + s < w && mossy[y * w + x + s])
                    || (y - s >= 0 && mossy[(y - s) * w + x]) || (y + s < h && mossy[(y + s) * w + x])) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ 1 画素の色

    private static int mossPx(int a, int b, double h, long seed) {
        int c = moss.at(a + 5, b) & 0xFFFFFF;
        if (hash(a + 80, b + 80, seed + 29) < 0.35) {
            int c2 = caveMoss.at(a + 3, b + 7);
            if ((c2 >>> 24) >= 128) {
                c = c2 & 0xFFFFFF;
            }
        }
        return scale(c, 0.78 + 0.12 * h);
    }

    /** 覆いの縁: 暗い影。2×2 の塊の KEEP_RED だけ赤黒、まれに髄の白。 */
    private static int edgePx(int orig, int a, int b, double h, long seed) {
        if (h > 0.985) {
            return PITH_WHITE;
        }
        if (hash(Math.floorDiv(a, 2) + 300, Math.floorDiv(b, 2) + 300, seed + 41) < KEEP_RED) {
            return mix(scale(orig, 0.45), FLESH[h < 0.5 ? 0 : 1], 0.65);
        }
        int s = SHADOW[(int) (hash(a, b, seed + 79) * SHADOW.length) % SHADOW.length];
        return mix(s, scale(orig, 0.6), 0.25);
    }

    /** 裂け目: e < 1 は中身、1〜1.45 は破れた皮の縁(暗い影、赤黒は少し)、〜2.1 はくすむ。e = 裂け目の中心からの遠さ(半径で割った値)。 */
    private static int tearPx(int orig, double e, int a, int b, double h, long seed) {
        e += (h - 0.5) * 0.45;
        if (e < 1) {
            // 中: 暗い繊維(樹皮の暗い所)に、形成層の淡い筋。まれに髄の白
            int c = scale(bark.at(a, b) & 0xFFFFFF, 0.55);
            if (h > 0.82) {
                c = scale(cambium.at(a + 3, b + 5) & 0xFFFFFF, 0.75);
            }
            if (h > 0.97) {
                c = h > 0.985 ? 0xCFC9AE : PITH_WHITE;
            }
            return c;
        }
        if (e < 1.45) {
            return edgePx(orig, a, b, h, seed + 7);
        }
        if (e < 2.1) {
            return scale(orig, 0.82);
        }
        return orig;
    }

    /** 死体の色へくすませる(灰緑 #868C70 へ、明るさを合わせて寄せる)。明るい画素ほど強く暗く。 */
    private static int deaden(int c) {
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        int l = (r * 3 + g * 6 + b) / 10;
        double kk = 0.30 + 0.35 * Math.min(1, Math.max(0, (l - 90) / 130.0));
        double dark = l > 110 ? 0.86 - 0.36 * Math.min(1, (l - 110) / 120.0) : 0.9;
        double s = l / 135.0;
        int tr = (int) Math.min(255, 0x86 * s), tg = (int) Math.min(255, 0x8C * s), tb = (int) Math.min(255, 0x70 * s);
        r = (int) ((r * (1 - kk) + tr * kk) * dark);
        g = (int) ((g * (1 - kk) + tg * kk) * dark);
        b = (int) ((b * (1 - kk) + tb * kk) * dark);
        return (r << 16) | (g << 8) | b;
    }

    // ------------------------------------------------------------------ 色の道具

    private static boolean isVivid(int c) {
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        return max >= 90 && (max - min) > max * 0.55;
    }

    private static int lum(int c) {
        return (((c >> 16) & 0xFF) * 3 + ((c >> 8) & 0xFF) * 6 + (c & 0xFF)) / 10;
    }

    private static int scale(int rgb, double k) {
        int r = (int) Math.max(0, Math.min(255, ((rgb >> 16) & 0xFF) * k));
        int g = (int) Math.max(0, Math.min(255, ((rgb >> 8) & 0xFF) * k));
        int b = (int) Math.max(0, Math.min(255, (rgb & 0xFF) * k));
        return (r << 16) | (g << 8) | b;
    }

    private static int mix(int a, int b, double t) {
        int r = (int) (((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = (int) (((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bb = (int) ((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return (r << 16) | (g << 8) | bb;
    }

    /**
     * 立体のむら(0〜1。格子の乱数をなめらかにつないだ物)。近い位置は近い値になるので、面や箱が違っても隣り合う所は同じかたまりに入る。
     * 真ん中に寄る値を広げて、しきい値(覆いの濃さ)と割合がだいたい合うようにしてある。
     */
    private static double noise3(double x, double y, double z, long seed) {
        int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y), z0 = (int) Math.floor(z);
        double fx = smooth(x - x0), fy = smooth(y - y0), fz = smooth(z - z0);
        double v = 0.0;
        for (int c = 0; c < 8; c++) {
            int dx = c & 1, dy = (c >> 1) & 1, dz = (c >> 2) & 1;
            double wgt = (dx == 1 ? fx : 1 - fx) * (dy == 1 ? fy : 1 - fy) * (dz == 1 ? fz : 1 - fz);
            v += wgt * hash(x0 + dx, (y0 + dy) * 7919 + (z0 + dz), seed);
        }
        return v;
    }

    private static double smooth(double t) {
        return t * t * (3 - 2 * t);
    }

    /** 0〜1 の乱数(同じ引数なら同じ値)。 */
    static double hash(int a, int b, long seed) {
        long x = a * 0x9E3779B97F4A7C15L ^ b * 0xC2B2AE3D27D4EB4FL ^ seed * 0x165667B19E3779F9L;
        x ^= x >>> 32;
        x *= 0xD6E8FEB86659FD93L;
        x ^= x >>> 32;
        x *= 0xD6E8FEB86659FD93L;
        x ^= x >>> 32;
        return (x >>> 11) * 0x1.0p-53;
    }
}
