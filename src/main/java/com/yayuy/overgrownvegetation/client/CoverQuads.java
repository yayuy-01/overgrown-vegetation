package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.corruption.CoverLook;
import com.yayuy.overgrownvegetation.data.CoverTable;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;

/**
 * 元の模型の面 1 枚を写して、<b>面全体に覆いの絵を貼った面</b>を作る(2026-10-30、作り直し側の {@code CoverRenderer.CoverModel#overlay})。
 * 記録で覆ったブロック({@link CoverRenderer})と、中身ごと止めたブロック({@link OvergrownHuskModel})が使う。
 *
 * <ul>
 *     <li>絵の座標は面の位置(ブロックの中の 0〜1)から取り直すので、元の絵の切り抜きに依らず面全体に貼る(部分的な覆いは採らない、
 *     2026-10-29 ユーザー決定)。色付けなし・頂点の色は白(草の側面の色が移らない)</li>
 *     <li>絵の番号と回転は座標と面のハッシュ({@link CoverLook})。いつも同じ</li>
 *     <li>奥行き: 元の面が固い層・cutout_mipped なら同じ頂点に重ねる(後から描くので「以下」の比べで勝つ。浮かせると 40 ブロックほどで
 *     奥行きの精度が足りず元の面と競る)。cutout・半透明の層(覆いより後に描かれる)のときだけ {@code cover.json} の {@code push} 外へ出す</li>
 * </ul>
 */
public final class CoverQuads {

    private CoverQuads() {
    }

    /** 元の面が覆い({@code cutout_mipped})より先に描かれる層か(そうなら押し出さずに重ねる)。 */
    public static boolean drawnBeforeCover(RenderType layer) {
        return layer == RenderType.solid() || layer == RenderType.cutoutMipped();
    }

    /**
     * @param flush 元の面が {@link #drawnBeforeCover} の層か
     * @param x     ブロックの座標(絵と回転を選ぶ)
     */
    public static BakedQuad overlay(BakedQuad q, boolean flush, int x, int y, int z, CoverSprites.Set sprites, CoverTable design) {
        Direction d = q.getDirection();
        int picked = CoverLook.pick(x, y, z, d, sprites.side().length, design.rotate());
        int index = CoverLook.index(picked);
        TextureAtlasSprite sprite = d == Direction.UP ? sprites.top()[index] : sprites.side()[index];
        int rot = CoverLook.rotation(picked);
        float push = flush ? 0 : (float) design.push();
        int[] v = q.getVertices().clone();
        int stride = v.length / 4;
        for (int i = 0; i < 4; i++) {
            int o = i * stride;
            float px = Float.intBitsToFloat(v[o]);
            float py = Float.intBitsToFloat(v[o + 1]);
            float pz = Float.intBitsToFloat(v[o + 2]);
            float u;
            float w;
            switch (d) {
                case UP -> {
                    u = px;
                    w = pz;
                }
                case DOWN -> {
                    u = px;
                    w = 1 - pz;
                }
                case NORTH -> {
                    u = 1 - px;
                    w = 1 - py;
                }
                case SOUTH -> {
                    u = px;
                    w = 1 - py;
                }
                case WEST -> {
                    u = pz;
                    w = 1 - py;
                }
                default -> {
                    u = 1 - pz;
                    w = 1 - py;
                }
            }
            for (int r = 0; r < rot; r++) {
                float t = u;
                u = 1 - w;
                w = t;
            }
            u = Math.clamp(u, 0, 1);
            w = Math.clamp(w, 0, 1);
            if (push > 0) {
                v[o] = Float.floatToRawIntBits(px + d.getStepX() * push);
                v[o + 1] = Float.floatToRawIntBits(py + d.getStepY() * push);
                v[o + 2] = Float.floatToRawIntBits(pz + d.getStepZ() * push);
            }
            v[o + 3] = -1;
            v[o + 4] = Float.floatToRawIntBits(sprite.getU(u));
            v[o + 5] = Float.floatToRawIntBits(sprite.getV(w));
        }
        return new BakedQuad(v, -1, d, sprite, q.isShade(), q.hasAmbientOcclusion());
    }
}
