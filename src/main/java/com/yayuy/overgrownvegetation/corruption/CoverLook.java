package com.yayuy.overgrownvegetation.corruption;

import net.minecraft.core.Direction;

/**
 * 覆いの見た目の選び方(D2)。ブロックと面ごとに、座標と面のハッシュで絵の番号と回転(4 向き)を選ぶ。同じ座標・面ならいつも同じ
 * (チャンクを作り直しても絵が変わらない)。絵の縁 2 px は全部の絵で共通・左右対称なので、どの絵をどの向きで隣に置いても繋がる
 * (作り直し側の {@code tools/cover-draft/README.md}。2026-10-30 に作り直し側 overgrown_vegetation/mod から持ってきた)。描画はクライアント({@code client/CoverRenderer})、ここはサーバーの GameTest でも確かめられるように共通に置く。
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

    public static int index(int picked) {
        return picked >>> 2;
    }

    public static int rotation(int picked) {
        return picked & 3;
    }
}
