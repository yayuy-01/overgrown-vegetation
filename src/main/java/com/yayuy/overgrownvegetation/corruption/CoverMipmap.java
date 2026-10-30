package com.yayuy.overgrownvegetation.corruption;

import java.util.Arrays;

/**
 * 覆いの絵のミップマップ(遠くで使う縮めた絵)を、<b>近くと同じ不透明の割合</b>で作る(D2)。
 *
 * <p>バニラのミップ({@code MipmapGenerator.alphaBlend})は 2×2 に 1 画素でも色があれば切り捨ての 0.5 を超える不透明にするので、
 * 透ける所が段ごとに埋まり、ユーザーの 5 枚(近くの不透明 62〜88%)は 2 段目(4×4)で全部 100% 不透明になる = 遠くの覆いが面全体の緑になる
 * (古い作りの「遠くで緑に塗りつぶし」。古い作りはさらに 1 段目から全部不透明にしていた)。ここでは各段で、元の絵(0 段目)の不透明の割合 ×
 * {@code cover.json} の {@code mipCoverage} だけの画素を、色のある画素の多い所から順に不透明にする。残りは透明(色は段の平均色にして縁がにじまないように)。
 * 遠くでも元のブロックの色が近くと同じ割合で見える。倍率を 1 より下げれば遠いほど薄くなる(距離で薄くする)。
 *
 * <p>画素は {@code NativeImage} の 1 画素の int(上位 8 bit が不透明度。残り 3 色は並びを問わない)。クライアントの
 * {@code mixin/client/SpriteContentsCoverMipMixin} が呼ぶ。ゲームのクラスを使わないので GameTest で確かめられる。
 * 2026-10-30 に作り直し側 overgrown_vegetation/mod から持ってきた(古い作りの {@code SpriteContentsMossMipMixin} = 1 段目から全部不透明、の置き換え)。
 */
public final class CoverMipmap {

    private CoverMipmap() {
    }

    /** {@code textures/block/cover/} の絵か(ブロックのアトラスの名前 {@code block/cover/…})。 */
    public static boolean isCoverSprite(String namespace, String path) {
        return com.yayuy.overgrownvegetation.OvergrownVegetation.MODID.equals(namespace) && path.startsWith("block/cover/");
    }

    /** 0 段目の不透明の割合(不透明度の平均)。 */
    public static double coverage(int[] pixels) {
        double sum = 0;
        for (int p : pixels) {
            sum += (p >>> 24) / 255.0;
        }
        return pixels.length == 0 ? 0 : sum / pixels.length;
    }

    /**
     * {@code level} 段目(1 以上)の絵を 0 段目から作る。
     *
     * @param base       0 段目の画素(幅 {@code w}・高さ {@code h})
     * @param multiplier 不透明の割合の倍率({@code cover.json} の {@code mipCoverage})
     * @return 幅 {@code max(1, w >> level)}・高さ {@code max(1, h >> level)} の画素
     */
    public static int[] level(int[] base, int w, int h, int level, double multiplier) {
        int sw = Math.max(1, w >> level);
        int sh = Math.max(1, h >> level);
        int bw = w / sw;
        int bh = h / sh;
        int n = sw * sh;
        double[] cov = new double[n];
        int[] color = new int[n];
        double[] all = new double[3];
        double allWeight = 0;
        for (int y = 0; y < sh; y++) {
            for (int x = 0; x < sw; x++) {
                double a = 0;
                double[] c = new double[3];
                for (int dy = 0; dy < bh; dy++) {
                    for (int dx = 0; dx < bw; dx++) {
                        int p = base[(y * bh + dy) * w + x * bw + dx];
                        double wgt = (p >>> 24) / 255.0;
                        if (wgt <= 0) {
                            continue;
                        }
                        a += wgt;
                        for (int k = 0; k < 3; k++) {
                            c[k] += wgt * lin((p >>> (k * 8)) & 255);
                        }
                    }
                }
                int i = y * sw + x;
                cov[i] = a / (bw * bh);
                color[i] = a > 0 ? pack(c, a) : 0;
                for (int k = 0; k < 3; k++) {
                    all[k] += c[k];
                }
                allWeight += a;
            }
        }
        int fill = allWeight > 0 ? pack(all, allWeight) : 0;
        int keep = (int) Math.round(coverage(base) * multiplier * n);
        keep = Math.max(0, Math.min(keep, (int) Arrays.stream(cov).filter(v -> v > 0).count()));
        // 色のある画素の多い順(同じなら位置のハッシュで決まった順に。並び順で左上へ寄らないように)
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (p, q) -> {
            int c = Double.compare(cov[q], cov[p]);
            return c != 0 ? c : Integer.compare(tie(p, level), tie(q, level));
        });
        int[] out = new int[n];
        for (int r = 0; r < n; r++) {
            int i = order[r];
            out[i] = r < keep ? (0xFF << 24) | color[i] : fill;
        }
        return out;
    }

    /** 不透明の画素の割合(0.5 を切り捨ての境に。cutout_mipped の層と同じ)。 */
    public static double opaqueFraction(int[] pixels) {
        int n = 0;
        for (int p : pixels) {
            if ((p >>> 24) >= 128) {
                n++;
            }
        }
        return pixels.length == 0 ? 0 : n / (double) pixels.length;
    }

    /** バニラのミップの 1 段(確かめ用。{@code MipmapGenerator.alphaBlend} の透ける絵の枝の不透明度だけ)。 */
    public static int[] vanillaLevel(int[] prev, int w, int h) {
        int sw = Math.max(1, w >> 1);
        int sh = Math.max(1, h >> 1);
        int[] out = new int[sw * sh];
        for (int y = 0; y < sh; y++) {
            for (int x = 0; x < sw; x++) {
                double f = 0;
                for (int d = 0; d < 4; d++) {
                    int px = Math.min(w - 1, x * 2 + (d & 1));
                    int py = Math.min(h - 1, y * 2 + (d >> 1));
                    int a = prev[py * w + px] >>> 24;
                    if (a != 0) {
                        f += Math.pow(a / 255.0, 2.2);
                    }
                }
                int a = (int) (Math.pow(f / 4, 1 / 2.2) * 255);
                out[y * sw + x] = (a < 96 ? 0 : a) << 24;
            }
        }
        return out;
    }

    private static int tie(int i, int level) {
        int h = i * 0x9E3779B1 + level * 0x85EBCA6B;
        h ^= h >>> 15;
        h *= 0x2C1B3C6D;
        return h ^ (h >>> 12);
    }

    private static double lin(int c) {
        return Math.pow(c / 255.0, 2.2);
    }

    private static int pack(double[] c, double weight) {
        int out = 0;
        for (int k = 0; k < 3; k++) {
            int v = (int) Math.round(Math.pow(c[k] / weight, 1 / 2.2) * 255);
            out |= Math.max(0, Math.min(255, v)) << (k * 8);
        }
        return out;
    }
}
