package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.corruption.CoverCreep;
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
        return retexture(q, q.getVertices().clone(), flush, x, y, z, sprites, design, 0, false);
    }

    /**
     * 水に接した面には水中の絵を貼る版(2026-11-01)。{@code waterMask} は水に接した面の向きのビット(1 << {@link Direction#ordinal})、
     * {@code sea} なら海底の藻の丘の絵、でなければ川・池の水中の藻の覆いの絵。
     */
    public static BakedQuad overlay(BakedQuad q, boolean flush, int x, int y, int z, CoverSprites.Set sprites, CoverTable design,
                                    int waterMask, boolean sea) {
        return retexture(q, q.getVertices().clone(), flush, x, y, z, sprites, design, waterMask, sea);
    }

    /**
     * 覆いの下の元の面を暗くした写し(2026-10-31 夜、ユーザー決定)。置き換えブロック(overgrown_stone など)は絵の中で「苔の間から見える元の面」を
     * {@code cover.json} の {@code baseDarken} 倍に暗くして描くので、覆い(元の面をゲームが描く)も同じ明るさに揃える。元の面と同じ sprite・UV・頂点・色付けで、
     * 頂点の色だけ倍率を掛ける。覆いの絵より先に同じ層へ足す(後から描くので「以下」の比べで元の面に勝ち、その上に覆いの絵が乗る)。
     * 明るさ・AO は元の面と同じ計算({@code tesselateBlock})。元の面が押し出す層(cutout)なら押し出しの半分だけ出す(覆いの絵より奥)。
     * 倍率が 1 以上、または元の面が半透明の層なら null(足さない)。
     */
    @org.jetbrains.annotations.Nullable
    public static BakedQuad darkened(BakedQuad q, RenderType layer, CoverTable design) {
        float k = (float) design.baseDarken();
        if (k >= 1.0F || layer == RenderType.translucent()) {
            return null;
        }
        Direction d = q.getDirection();
        float push = drawnBeforeCover(layer) ? 0 : (float) design.push() * 0.5F;
        int[] v = q.getVertices().clone();
        int stride = v.length / 4;
        for (int i = 0; i < 4; i++) {
            int o = i * stride;
            if (push > 0) {
                v[o] = Float.floatToRawIntBits(Float.intBitsToFloat(v[o]) + d.getStepX() * push);
                v[o + 1] = Float.floatToRawIntBits(Float.intBitsToFloat(v[o + 1]) + d.getStepY() * push);
                v[o + 2] = Float.floatToRawIntBits(Float.intBitsToFloat(v[o + 2]) + d.getStepZ() * push);
            }
            int c = v[o + 3]; // ABGR
            int r = Math.round((c & 255) * k);
            int g = Math.round((c >> 8 & 255) * k);
            int b = Math.round((c >> 16 & 255) * k);
            v[o + 3] = c & 0xFF000000 | b << 16 | g << 8 | r;
        }
        return new BakedQuad(v, q.getTintIndex(), d, q.getSprite(), q.isShade(), q.hasAmbientOcclusion());
    }

    /**
     * 這い上がる苔(28、2026-11-01 に段階の絵へ作り直し): 面 {@code q} の形のまま、段階の絵 {@code block/cover/creep_<変種>_<段階>} を面全体に貼る。
     * 絵の下の辺が覆われた隣({@code from})に接する辺に来るように回す(透過で苔の形が出る。前のように覆いの絵の縁を切り出さない)。
     * 面は {@code creepPush}(角で 2 枚目なら +0.001)だけ外へ出し、元の面と同じ頂点で競わせない(11-01 のちらつきの直し)。絵が無ければ null。
     */
    @org.jetbrains.annotations.Nullable
    public static BakedQuad creep(BakedQuad q, int x, int y, int z, CoverSprites.Set sprites, CoverTable design, Direction from, int stage,
                                  int layerIndex) {
        TextureAtlasSprite[][] all = sprites.creep();
        TextureAtlasSprite sprite = all[CoverCreep.variant(x, y, z) - 1][Math.clamp(stage, 1, CoverCreep.STAGES) - 1];
        if (sprite == null) {
            return null;
        }
        Direction d = q.getDirection();
        // 接している辺の真ん中が絵の下の辺(w = 1)に来る回し方を探す。
        float[] e = faceUV(d, 0.5F + 0.5F * from.getStepX(), 0.5F + 0.5F * from.getStepY(), 0.5F + 0.5F * from.getStepZ());
        int rot = 0;
        while (rot < 4 && Math.abs(e[1] - 1.0F) > 0.01F) {
            float t = e[0];
            e[0] = 1 - e[1];
            e[1] = t;
            rot++;
        }
        if (rot == 4) {
            return null;
        }
        float push = (float) design.creepPush() + 0.001F * layerIndex;
        int[] v = q.getVertices().clone();
        int stride = v.length / 4;
        for (int i = 0; i < 4; i++) {
            int o = i * stride;
            float px = Float.intBitsToFloat(v[o]);
            float py = Float.intBitsToFloat(v[o + 1]);
            float pz = Float.intBitsToFloat(v[o + 2]);
            float[] uv = faceUV(d, px, py, pz);
            for (int r = 0; r < rot; r++) {
                float t = uv[0];
                uv[0] = 1 - uv[1];
                uv[1] = t;
            }
            v[o] = Float.floatToRawIntBits(px + d.getStepX() * push);
            v[o + 1] = Float.floatToRawIntBits(py + d.getStepY() * push);
            v[o + 2] = Float.floatToRawIntBits(pz + d.getStepZ() * push);
            v[o + 3] = -1;
            v[o + 4] = Float.floatToRawIntBits(sprite.getU(Math.clamp(uv[0], 0, 1)));
            v[o + 5] = Float.floatToRawIntBits(sprite.getV(Math.clamp(uv[1], 0, 1)));
        }
        return new BakedQuad(v, -1, d, sprite, q.isShade(), q.hasAmbientOcclusion());
    }

    /** 面 {@code d} の上の位置(ブロックの中の 0〜1)から、面の絵の座標 {u, w}(w = 1 が側面の下)。 */
    static float[] faceUV(Direction d, float px, float py, float pz) {
        return switch (d) {
            case UP -> new float[]{px, pz};
            case DOWN -> new float[]{px, 1 - pz};
            case NORTH -> new float[]{1 - px, 1 - py};
            case SOUTH -> new float[]{px, 1 - py};
            case WEST -> new float[]{pz, 1 - py};
            default -> new float[]{1 - pz, 1 - py};
        };
    }

    private static BakedQuad retexture(BakedQuad q, int[] v, boolean flush, int x, int y, int z, CoverSprites.Set sprites, CoverTable design,
                                       int waterMask, boolean sea) {
        Direction d = q.getDirection();
        TextureAtlasSprite[] wet = (waterMask & 1 << d.ordinal()) == 0 ? null : sea ? sprites.sea() : sprites.water();
        TextureAtlasSprite sprite;
        int picked;
        if (wet != null && wet.length > 0) {
            picked = CoverLook.pick(x, y, z, d, wet.length, design.rotate());
            sprite = wet[CoverLook.index(picked)];
        } else {
            picked = CoverLook.pick(x, y, z, d, sprites.side().length, design.rotate());
            int index = CoverLook.index(picked);
            sprite = d == Direction.UP ? sprites.top()[index] : sprites.side()[index];
        }
        int rot = CoverLook.rotation(picked);
        float push = flush ? 0 : (float) design.push();
        int stride = v.length / 4;
        for (int i = 0; i < 4; i++) {
            int o = i * stride;
            float px = Float.intBitsToFloat(v[o]);
            float py = Float.intBitsToFloat(v[o + 1]);
            float pz = Float.intBitsToFloat(v[o + 2]);
            float[] fw = faceUV(d, px, py, pz);
            float u = fw[0];
            float w = fw[1];
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
