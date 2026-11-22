package com.yayuy.overgrownvegetation.corruption;

import net.minecraft.core.Direction;

/**
 * 覆いの見た目の選び方(D2)。ブロックと面ごとに、座標と面のハッシュで絵の番号と回転(4 向き)を選ぶ。同じ座標・面ならいつも同じ
 * (チャンクを作り直しても絵が変わらない)。どの絵もいちばん外の 1 行は全部不透明なので、どの絵をどの向きで隣に置いても繋がる
 * (2026-10-31 から。前は縁 2 px を共通・左右対称にしていたが、縁の穴が継ぎ目に点線で並んだ。絵は {@code tools/cover-art} が描く)。
 * 2026-10-30 に作り直し側 overgrown_vegetation/mod から持ってきた。描画はクライアント({@code client/CoverRenderer})、ここはサーバーの GameTest でも確かめられるように共通に置く。
 */
public final class CoverLook {

    private CoverLook() {
    }

    /**
     * @param count  絵の数({@code cover.json} の {@code textures} の長さ)
     * @param rotate 回すか
     * @return {@code 番号 << 2 | 回転}(回転は 0〜3、90° 単位)
     */
    public static int pick(int x, int y, int z, Direction face, int count, boolean rotate) {
        long h = x * 0x9E3779B97F4A7C15L ^ y * 0xC2B2AE3D27D4EB4FL ^ z * 0x165667B19E3779F9L ^ (face.ordinal() + 1) * 0xD6E8FEB86659FD93L;
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        int index = (int) Long.remainderUnsigned(h >>> 2, Math.max(1, count));
        int rot = rotate ? (int) (h & 3) : 0;
        return index << 2 | rot;
    }

    /** 透けるブロック(ガラス板・鉄格子)の覆いの高さの割合の真ん中と揺れ幅(2026-11-03 仮決め: 面の 5〜6 割)。 */
    public static final float SEE_THROUGH_MOSS = 0.55F, SEE_THROUGH_JITTER = 0.10F;

    /**
     * 透けるブロック(ガラス板・鉄格子)の覆い(2026-11-03、相談役の依頼「ガラス板は面の 5〜6 割を苔、残りはガラス」)は、横向きの面の
     * <b>下から</b>この割合の高さまで(苔が窓枠から這い上がった形)。ブロックごとに ±{@link #SEE_THROUGH_JITTER}/2 揺らして、窓が一列に揃わないようにする。
     */
    public static float seeThroughMossHeight(int x, int y, int z) {
        int h = x * 0x27D4EB2D ^ y * 0x165667B1 ^ z * 0x61C88647;
        h ^= h >>> 15;
        h *= 0x2C1B3C6D;
        h ^= h >>> 12;
        return SEE_THROUGH_MOSS + ((h & 1023) / 1023.0F - 0.5F) * SEE_THROUGH_JITTER;
    }

    /**
     * 下に重なった透けるブロックの段数 {@code depth} で帯の厚さを変える版(2026-11-03 ユーザー指定「一番下の板だけ厚く、上は薄く」):
     * 0(下が透けるブロックでない = 窓の一番下)は {@link #seeThroughMossHeight(int, int, int)} の 5〜6 割、1(2 段目)は 1〜2 割、
     * 2 以上(3 段目から)は 1 割。窓枠から這い上がった帯が 1 本に見えるように。
     */
    public static float seeThroughMossHeight(int x, int y, int z, int depth) {
        if (depth <= 0) {
            return seeThroughMossHeight(x, y, z);
        }
        if (depth == 1) {
            return 0.15F + (seeThroughMossHeight(x, y, z) - SEE_THROUGH_MOSS) * 0.5F; // 揺れ幅は半分(0.125〜0.175)
        }
        return 0.10F;
    }

    /** 面の下の高さ {@code minY}・上の高さ {@code maxY} のとき、覆いの上の端の高さ({@link #seeThroughMossHeight} の割合)。 */
    public static float clipTop(float minY, float maxY, float fraction) {
        return minY + (maxY - minY) * net.minecraft.util.Mth.clamp(fraction, 0.0F, 1.0F);
    }

    public static int index(int picked) {
        return picked >>> 2;
    }

    public static int rotation(int picked) {
        return picked & 3;
    }
}
