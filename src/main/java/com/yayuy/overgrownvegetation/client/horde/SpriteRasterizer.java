package com.yayuy.overgrownvegetation.client.horde;

import java.util.Arrays;
import java.util.List;

/**
 * 遠景の身代わり(スプライト)の 1 コマを、モデルの面から <b>CPU で</b>描く(2026-09-25、TD の「読み込み範囲の外から迫る大群」の
 * 1 段目)。GL を使わないので描画の状態を壊さず、Minecraft を起動せずに同じ処理を回して絵を確かめられる
 * (Minecraft の型を一切使っていない。モデルの面は {@link SpriteQuads} が GeckoLib のモデルから作る)。
 *
 * <h2>描き方</h2>
 * <ul>
 *     <li>平行投影。カメラは「モデルの前(-Z)を +Y 回りに {@code viewAngle} 回した方向」から、{@code pitch} だけ見下ろす。
 *     画像の右 = 視線 × 上、画像の上 = 右 × 視線(ワールドで板を立てるときも同じ式で向きが揃う)。</li>
 *     <li>面は裏表とも描く(GeckoLib の Mob は裏面を削らずに描いている)。奥行きの比べ合いで手前の面が勝つ。</li>
 *     <li>テクスチャは最も近い画素を取り、アルファ 0.1 未満は捨てる(Mob の描画と同じ切り抜き)。</li>
 *     <li>陰影は面の向きだけで決める(上 1.0・南北 0.8・東西 0.6・下 0.5 のバニラの面の明るさを、傾いた面では混ぜる)。</li>
 *     <li>{@code ss} 倍の細かさで描いてから平均して縮める(縁を滑らかに。アルファは覆った割合)。透明な画素の色は隣の不透明な色で
 *     埋める(アルファ 0 でも色が黒だと、線形補間・ミップマップで縁に黒がにじむ)。</li>
 * </ul>
 */
public final class SpriteRasterizer {

    /** 捨てるアルファ(0〜255)。Mob の切り抜き描画と同じ 0.1。 */
    private static final int ALPHA_CUTOFF = 26;

    /** 1 枚の面(ブロック単位、モデル空間。原点 = 足元、前 = -Z)。4 頂点の位置({@code xyz} 12 個)・UV({@code uv} 8 個)と法線。 */
    public record Quad(float[] xyz, float[] uv, float nx, float ny, float nz) {
    }

    /** テクスチャ(ARGB、上の行から)。 */
    public record Texture(int width, int height, int[] argb) {
    }

    /** 描いた 1 コマ(ARGB、上の行から)。 */
    public record Frame(int width, int height, int[] argb) {
    }

    /** 画像の範囲(カメラの右・上の軸に投影した座標、ブロック)。どの向きのコマも同じ範囲で描く(足元の画素が揃う)。 */
    public record Bounds(float minX, float maxX, float minY, float maxY) {
        /** 余白 {@code pad} 画素込みの画像の幅(4 の倍数に切り上げ。ミップマップで割り切れるように)。 */
        public int pixelWidth(float ppb, int pad) {
            return roundUp4((int) Math.ceil((this.maxX - this.minX) * ppb) + 2 * pad);
        }

        /** 余白込みの画像の高さ(4 の倍数)。 */
        public int pixelHeight(float ppb, int pad) {
            return roundUp4((int) Math.ceil((this.maxY - this.minY) * ppb) + 2 * pad);
        }

        /** モデルの原点(足元)が来る画素の x(左から)。 */
        public float originX(float ppb, int pad) {
            return -this.minX * ppb + pad;
        }

        /** モデルの原点(足元)が来る画素の y(上から)。 */
        public float originY(float ppb, int pad) {
            return this.maxY * ppb + pad;
        }

        private static int roundUp4(int v) {
            return (v + 3) & ~3;
        }
    }

    /** カメラの軸(右 r・上 u・視線 v。どれも単位ベクトル。視線はカメラからモデルへ)。 */
    record Basis(double rx, double rz, double ux, double uy, double uz, double vx, double vy, double vz) {
        static Basis of(double viewAngle, double pitch) {
            // モデルからカメラへの向き d: 前(-Z)を +Y 回りに viewAngle 回し、pitch だけ上へ。視線 v = -d。
            double cp = Math.cos(pitch);
            double dx = -Math.sin(viewAngle) * cp;
            double dy = Math.sin(pitch);
            double dz = -Math.cos(viewAngle) * cp;
            double vx = -dx;
            double vy = -dy;
            double vz = -dz;
            // 右 r = v × 上(0, 1, 0) = (-v.z, 0, v.x)。
            double rx = -vz;
            double rz = vx;
            double rl = Math.sqrt(rx * rx + rz * rz);
            rx /= rl;
            rz /= rl;
            // 上 u = r × v。
            double ux = -rz * vy;
            double uy = rz * vx - rx * vz;
            double uz = rx * vy;
            return new Basis(rx, rz, ux, uy, uz, vx, vy, vz);
        }

        double right(float x, float z) {
            return x * this.rx + z * this.rz;
        }

        double up(float x, float y, float z) {
            return x * this.ux + y * this.uy + z * this.uz;
        }

        double depth(float x, float y, float z) {
            return x * this.vx + y * this.vy + z * this.vz;
        }
    }

    /** {@code views} 方向(360 度を等分)の画像の範囲を全部合わせたもの。面が無ければ 1 ブロック四方。 */
    public static Bounds bounds(List<Quad> quads, int views, double pitch) {
        float minX = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (int i = 0; i < views; i++) {
            Basis b = Basis.of(viewAngle(i, views), pitch);
            for (Quad q : quads) {
                float[] p = q.xyz();
                for (int k = 0; k < 12; k += 3) {
                    float sx = (float) b.right(p[k], p[k + 2]);
                    float sy = (float) b.up(p[k], p[k + 1], p[k + 2]);
                    minX = Math.min(minX, sx);
                    maxX = Math.max(maxX, sx);
                    minY = Math.min(minY, sy);
                    maxY = Math.max(maxY, sy);
                }
            }
        }
        if (minX > maxX) {
            return new Bounds(-0.5F, 0.5F, 0.0F, 1.0F);
        }
        // 足元(原点)は必ず画像に入れる(板を足元で立てるため)。
        return new Bounds(Math.min(minX, 0.0F), Math.max(maxX, 0.0F), Math.min(minY, 0.0F), Math.max(maxY, 0.0F));
    }

    /** {@code views} 方向のうち {@code index} 番目のカメラの角度(ラジアン。0 = 前から、+Y 回り)。 */
    public static double viewAngle(int index, int views) {
        return index * (Math.PI * 2.0D / views);
    }

    /**
     * 1 コマ描く。
     *
     * @param viewAngle カメラの角度(ラジアン、0 = 前から見る)
     * @param pitch     見下ろす角度(ラジアン)
     * @param ppb       1 ブロックあたりの画素
     * @param pad       四辺の余白(画素)
     * @param ss        超標本化の倍率(1 画素を ss × ss に分けて描いて平均する)
     */
    public static Frame render(List<Quad> quads, Texture texture, double viewAngle, double pitch, Bounds bounds,
                               float ppb, int pad, int ss) {
        int width = bounds.pixelWidth(ppb, pad);
        int height = bounds.pixelHeight(ppb, pad);
        int sw = width * ss;
        int sh = height * ss;
        int[] color = new int[sw * sh];
        float[] depth = new float[sw * sh];
        Arrays.fill(depth, Float.POSITIVE_INFINITY);
        Basis basis = Basis.of(viewAngle, pitch);
        float scale = ppb * ss;
        float ox = bounds.originX(ppb, pad) * ss;
        float oy = bounds.originY(ppb, pad) * ss;

        float[] px = new float[4];
        float[] py = new float[4];
        float[] pz = new float[4];
        for (Quad q : quads) {
            float[] p = q.xyz();
            for (int k = 0; k < 4; k++) {
                float x = p[k * 3];
                float y = p[k * 3 + 1];
                float z = p[k * 3 + 2];
                px[k] = ox + (float) basis.right(x, z) * scale;
                py[k] = oy - (float) basis.up(x, y, z) * scale;
                pz[k] = (float) basis.depth(x, y, z);
            }
            int shade = shade(q.nx(), q.ny(), q.nz());
            float[] uv = q.uv();
            triangle(px, py, pz, uv, 0, 1, 2, texture, shade, color, depth, sw, sh);
            triangle(px, py, pz, uv, 0, 2, 3, texture, shade, color, depth, sw, sh);
        }
        return new Frame(width, height, dilate(downsample(color, sw, sh, ss), width, height));
    }

    /** 面の向きの明るさ(0〜256 の固定小数)。上 1.0・南北 0.8・東西 0.6・下 0.5、傾いた面は混ぜる。 */
    static int shade(float nx, float ny, float nz) {
        double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1.0E-6D) {
            return 205;
        }
        double x = nx / len;
        double y = ny / len;
        double z = nz / len;
        double horizontal = x * x + z * z;
        double side = horizontal < 1.0E-9D ? 0.7D : (0.8D * z * z + 0.6D * x * x) / horizontal;
        double vertical = y >= 0.0D ? 1.0D : 0.5D;
        double s = side * horizontal + vertical * y * y;
        return (int) Math.round(Math.max(0.0D, Math.min(1.0D, s)) * 256.0D);
    }

    private static void triangle(float[] px, float[] py, float[] pz, float[] uv, int a, int b, int c, Texture texture,
                                 int shade, int[] color, float[] depth, int sw, int sh) {
        float x0 = px[a];
        float y0 = py[a];
        float x1 = px[b];
        float y1 = py[b];
        float x2 = px[c];
        float y2 = py[c];
        float area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0);
        if (Math.abs(area) < 1.0E-6F) {
            return; // 画面に対して真横を向いた面(厚み 0 の板を横から見た)・潰れた面
        }
        int minX = Math.max(0, (int) Math.floor(Math.min(x0, Math.min(x1, x2))));
        int maxX = Math.min(sw - 1, (int) Math.ceil(Math.max(x0, Math.max(x1, x2))));
        int minY = Math.max(0, (int) Math.floor(Math.min(y0, Math.min(y1, y2))));
        int maxY = Math.min(sh - 1, (int) Math.ceil(Math.max(y0, Math.max(y1, y2))));
        float inv = 1.0F / area;
        float u0 = uv[a * 2];
        float v0 = uv[a * 2 + 1];
        float u1 = uv[b * 2];
        float v1 = uv[b * 2 + 1];
        float u2 = uv[c * 2];
        float v2 = uv[c * 2 + 1];
        int tw = texture.width();
        int th = texture.height();
        int[] tex = texture.argb();
        for (int y = minY; y <= maxY; y++) {
            float cy = y + 0.5F;
            for (int x = minX; x <= maxX; x++) {
                float cx = x + 0.5F;
                // 重心座標(面の向きがどちらでも拾う = 裏面も描く)。
                float w0 = ((x1 - cx) * (y2 - cy) - (x2 - cx) * (y1 - cy)) * inv;
                float w1 = ((x2 - cx) * (y0 - cy) - (x0 - cx) * (y2 - cy)) * inv;
                float w2 = 1.0F - w0 - w1;
                if (w0 < 0.0F || w1 < 0.0F || w2 < 0.0F) {
                    continue;
                }
                int index = y * sw + x;
                float d = w0 * pz[a] + w1 * pz[b] + w2 * pz[c];
                if (d >= depth[index]) {
                    continue;
                }
                float u = w0 * u0 + w1 * u1 + w2 * u2;
                float v = w0 * v0 + w1 * v1 + w2 * v2;
                int tx = Math.min(tw - 1, Math.max(0, (int) Math.floor(u * tw)));
                int ty = Math.min(th - 1, Math.max(0, (int) Math.floor(v * th)));
                int texel = tex[ty * tw + tx];
                if ((texel >>> 24) < ALPHA_CUTOFF) {
                    continue;
                }
                depth[index] = d;
                int r = ((texel >> 16) & 0xFF) * shade >> 8;
                int g = ((texel >> 8) & 0xFF) * shade >> 8;
                int bl = (texel & 0xFF) * shade >> 8;
                color[index] = 0xFF000000 | (r << 16) | (g << 8) | bl;
            }
        }
    }

    /** ss × ss の細かい画素を 1 画素へ平均する(色は覆った画素だけの平均、アルファは覆った割合)。 */
    private static int[] downsample(int[] fine, int sw, int sh, int ss) {
        int width = sw / ss;
        int height = sh / ss;
        int[] out = new int[width * height];
        int cells = ss * ss;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int count = 0;
                int r = 0;
                int g = 0;
                int b = 0;
                for (int j = 0; j < ss; j++) {
                    int row = (y * ss + j) * sw + x * ss;
                    for (int i = 0; i < ss; i++) {
                        int c = fine[row + i];
                        if ((c >>> 24) != 0) {
                            count++;
                            r += (c >> 16) & 0xFF;
                            g += (c >> 8) & 0xFF;
                            b += c & 0xFF;
                        }
                    }
                }
                if (count > 0) {
                    int a = (count * 255 + cells / 2) / cells;
                    out[y * width + x] = (a << 24) | ((r / count) << 16) | ((g / count) << 8) | (b / count);
                }
            }
        }
        return out;
    }

    /** 透明な画素の色を、隣の不透明な画素の平均で埋める(アルファは 0 のまま)。{@value #DILATE_PASSES} 回繰り返す。 */
    static int[] dilate(int[] argb, int width, int height) {
        int[] cur = argb;
        for (int pass = 0; pass < DILATE_PASSES; pass++) {
            int[] next = cur.clone();
            boolean changed = false;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int c = cur[y * width + x];
                    if ((c >>> 24) != 0 || (c & 0xFFFFFF) != 0) {
                        continue; // 不透明、またはもう色が入っている
                    }
                    int n = 0;
                    int r = 0;
                    int g = 0;
                    int b = 0;
                    for (int dy = -1; dy <= 1; dy++) {
                        int yy = y + dy;
                        if (yy < 0 || yy >= height) {
                            continue;
                        }
                        for (int dx = -1; dx <= 1; dx++) {
                            int xx = x + dx;
                            if ((dx == 0 && dy == 0) || xx < 0 || xx >= width) {
                                continue;
                            }
                            int o = cur[yy * width + xx];
                            if ((o & 0xFFFFFF) != 0 || (o >>> 24) != 0) {
                                n++;
                                r += (o >> 16) & 0xFF;
                                g += (o >> 8) & 0xFF;
                                b += o & 0xFF;
                            }
                        }
                    }
                    if (n > 0) {
                        int rgb = ((r / n) << 16) | ((g / n) << 8) | (b / n);
                        next[y * width + x] = rgb == 0 ? 0x010101 : rgb; // 真っ黒の近所も「色が入った」と分かるように
                        changed = true;
                    }
                }
            }
            cur = next;
            if (!changed) {
                break;
            }
        }
        return cur;
    }

    /** 透明な画素の色を埋める回数(ミップマップ 2 段で混ざる幅ぶん)。 */
    private static final int DILATE_PASSES = 4;

    /** 1 段小さいミップマップ(2 × 2 の平均。色はアルファで重みを付ける)。幅・高さは偶数であること。 */
    public static int[] halve(int[] argb, int width, int height) {
        int w = width / 2;
        int h = height / 2;
        int[] out = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int aSum = 0;
                int r = 0;
                int g = 0;
                int b = 0;
                int rPlain = 0;
                int gPlain = 0;
                int bPlain = 0;
                for (int j = 0; j < 2; j++) {
                    for (int i = 0; i < 2; i++) {
                        int c = argb[(y * 2 + j) * width + x * 2 + i];
                        int a = c >>> 24;
                        aSum += a;
                        r += ((c >> 16) & 0xFF) * a;
                        g += ((c >> 8) & 0xFF) * a;
                        b += (c & 0xFF) * a;
                        rPlain += (c >> 16) & 0xFF;
                        gPlain += (c >> 8) & 0xFF;
                        bPlain += c & 0xFF;
                    }
                }
                int rgb = aSum > 0
                        ? ((r / aSum) << 16) | ((g / aSum) << 8) | (b / aSum)
                        : ((rPlain / 4) << 16) | ((gPlain / 4) << 8) | (bPlain / 4);
                out[y * w + x] = ((aSum / 4) << 24) | rgb;
            }
        }
        return out;
    }

    private SpriteRasterizer() {
    }
}
