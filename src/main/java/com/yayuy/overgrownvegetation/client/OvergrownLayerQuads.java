package com.yayuy.overgrownvegetation.client;

import com.google.common.collect.MapMaker;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * <b>面 1 枚を「苔の面」に作り替える</b>ための共通処理(2026-09-22)。
 *
 * <p>ブロックの焼き上がった模型の面をそのまま借りて、UV だけを {@code overgrown_layer} に
 * 差し替えた面を作り直す。これで<b>立方体でなくても</b> —— 階段でも尖った鍾乳石でも金床でも ——
 * その形のまま苔に覆われる。
 *
 * <p>使うのは 2 か所。{@link OvergrownLayerRenderer}(置き換えずに描画だけ被せるマス)と
 * {@link OvergrownHuskModel} —— 汚染で差し替えた「覆われて動かなくなったブロック」が
 * 元の模型を借りて描くところ。
 *
 * <h2>テクスチャの空白に苔を乗せない(2026-09-23、報告)</h2>
 * <p>松明や尖った鍾乳石の模型は<b>16x16 の板</b>で、そこに<b>ほとんど透明な</b>テクスチャが
 * 貼ってある。面をそのままなぞると<b>何も無い空中に苔の板</b>が浮く。
 * そこで {@code mask} を立てて呼ぶと、<b>元のテクスチャで色が付いている画素の上だけ</b>に
 * 苔を置く —— 透明な画素を抜いた形({@link #silhouette})を長方形に切り分け、
 * その長方形ぶんだけ面を作る。
 *
 * <p><b>立方体には掛けない。</b> ガラスや葉は「向こうが透けて見える」ために透明なのであって、
 * 「そこに何も無い」わけではない。抜いてしまうとガラスが覆われなくなるので、
 * {@code mask} を立てるかどうかは<b>形</b>で決める
 * ({@link com.yayuy.overgrownvegetation.corruption.OvergrownLayerField#isPlainCube})。
 * ガラス板や鉄格子は立方体ではないので<b>枠の部分にだけ</b>苔が付く(窓は透けたまま)。
 *
 * <h2>作った面は使い回す</h2>
 * <p>出来上がる面は<b>元の面だけで決まる</b> —— 苔の UV はブロック内の座標(0〜1)から
 * 貼り直しているので、世界のどこに置かれた何番目のブロックかに左右されない。
 * だから元の面({@link BakedQuad} のインスタンス)をそのまま鍵にして覚えておける。
 * 透明画素の判定は 1 面につき 1 回で済む。
 *
 * <p><b>鍵は弱参照で持つ</b>(2026-09-24)。他 MOD の模型には {@code getQuads} のたびに
 * 新しい面を作って返すものがあり(つながるテクスチャなど)、強く持つとチャンクを積み直すたびに
 * 表が際限なく増える。弱参照なら、誰も持っていない面(= 二度と引かれない面)の分だけが消える。
 * 模型が持ち続けている面は今までどおり覚えたまま。{@link BakedQuad} は equals を持たないので、
 * 鍵は以前から同一性で比べていた({@code weakKeys} も同一性で比べる)。
 */
public final class OvergrownLayerQuads {

    /** 覆うのに使うテクスチャ(ユーザー提供)。 */
    public static final ResourceLocation LAYER_TEXTURE = OvergrownVegetation.id("block/overgrown_layer");

    /**
     * 面を法線方向へ押し出す量(ブロック)。元のテクスチャとの z ファイティング除け。
     *
     * <p><b>押し出しは遠くで効かない</b>(2026-10-07、ユーザー報告「覆われたブロックを遠くから見るとちかちかする」): 奥行きの精度は
     * 距離の 2 乗で粗くなり(近くの面 0.05・24 ビットで、1 目盛り ≒ 距離² / 84 万ブロック)、0.002 は約 40 ブロックより遠いと見分けられない。
     * そこで元の面とまったく同じ位置に重ねられるところは押し出さない({@code flush})—— 同じ頂点の面は同じ奥行きになり、
     * 後から描いた方が勝つ(深さの比べ方が「以下」)。バニラの草ブロックの側面の色付けと同じやり方。
     * 押し出すのは、元の面を後から描く場合(元のブロックが {@code cutout}・{@code translucent} の描画層)と、
     * 切り抜いた小さい面({@link Patch#slice}。頂点が違うので同じ平面でも奥行きが 1 目盛りずれうる)だけ。
     * なお、このときテスト用クライアントで目に見えたちらつきの本体はこちらではなく、苔のテクスチャを縮小した絵に残る穴だった
     * ({@link com.yayuy.overgrownvegetation.mixin.client.SpriteContentsMossMipMixin})。押し出しをやめたのは、
     * 浅い角度で見る床や画面の端など、精度がもっと粗くなる所でも苔が必ず上に出るようにするため。
     */
    private static final float PUSH = 0.002F;

    /** 透明画素を抜くときの目の細かさの上限(縦横のマス数)。16x16 のテクスチャなら画素そのまま。 */
    private static final int MASK_CELLS = 32;

    /** 覚えておいた出来上がりの面。アトラスが焼き直されたら({@code sprite} が変わったら)捨てる。 */
    private record Cache(TextureAtlasSprite sprite,
                         Map<BakedQuad, List<BakedQuad>> plain,
                         Map<BakedQuad, List<BakedQuad>> masked,
                         Map<BakedQuad, List<BakedQuad>> plainFlush,
                         Map<BakedQuad, List<BakedQuad>> maskedFlush) {
    }

    private static volatile Cache cache;

    private OvergrownLayerQuads() {
    }

    /**
     * ブロックアトラスから苔のスプライトを引く。
     *
     * <p>このテクスチャは<b>どのブロックモデルからも参照されていない</b>が、
     * {@code textures/block/} に置いてあるのでアトラスには入る ——
     * バニラの {@code blocks.json} が {@code textures/block/} を
     * <b>名前空間を問わずディレクトリごと</b>拾うため。
     * {@code atlases/blocks.json} に書き足す必要は無い。
     */
    public static TextureAtlasSprite sprite() {
        return Minecraft.getInstance().getModelManager()
                .getAtlas(InventoryMenu.BLOCK_ATLAS).getSprite(LAYER_TEXTURE);
    }

    /**
     * 面 1 枚を「苔の面」に作り替える。
     *
     * @param mask 元のテクスチャで<b>色が付いている画素の上だけ</b>に苔を置くか。
     *             立方体でないブロック(松明・鍾乳石・鉄格子)で立てる。
     * @return 0 枚以上の面。{@code mask} を立てて元のテクスチャが<b>全部透明</b>だったときは空。
     */
    public static List<BakedQuad> dress(BakedQuad quad, boolean mask) {
        return dress(cache(), quad, mask, false);
    }

    /** 面の並びをまとめて苔の面に作り替え、{@code out} へ足す(押し出す。{@link #PUSH})。 */
    public static void dressAll(List<BakedQuad> quads, boolean mask, List<BakedQuad> out) {
        dressAll(quads, mask, false, out);
    }

    /**
     * 面の並びをまとめて苔の面に作り替え、{@code out} へ足す。
     *
     * @param flush 元の面と同じ位置に重ねる(押し出さない)か。元の面が<b>同じ描画層の先か、先に描かれる層</b>
     *              ({@code solid}・{@code cutoutMipped})にあるときだけ立てる。切り抜いた小さい面は立てても押し出す。
     */
    public static void dressAll(List<BakedQuad> quads, boolean mask, boolean flush, List<BakedQuad> out) {
        if (quads.isEmpty()) {
            return;
        }
        Cache c = cache();
        for (BakedQuad quad : quads) {
            out.addAll(dress(c, quad, mask, flush));
        }
    }

    private static List<BakedQuad> dress(Cache c, BakedQuad quad, boolean mask, boolean flush) {
        Map<BakedQuad, List<BakedQuad>> map = flush ? (mask ? c.maskedFlush() : c.plainFlush()) : (mask ? c.masked() : c.plain());
        List<BakedQuad> done = map.get(quad);
        if (done == null) {
            done = build(quad, c.sprite(), mask, flush);
            map.put(quad, done);
        }
        return done;
    }

    private static Cache cache() {
        TextureAtlasSprite sprite = sprite();
        Cache c = cache;
        if (c == null || c.sprite() != sprite) {
            c = new Cache(sprite, weakQuadMap(), weakQuadMap(), weakQuadMap(), weakQuadMap());
            cache = c;
        }
        return c;
    }

    /**
     * 元の面 → 出来上がりの面の表。ワーカースレッドから同時に引かれるので並行に使える表にする。
     * 鍵は弱参照・同一性で比べる。値(作った苔の面)は元の面を指していないので、鍵を生かし続けることは無い。
     */
    private static Map<BakedQuad, List<BakedQuad>> weakQuadMap() {
        return new MapMaker().weakKeys().makeMap();
    }

    // --- 面を作る ---

    private static List<BakedQuad> build(BakedQuad quad, TextureAtlasSprite sprite, boolean mask, boolean flush) {
        float push = flush ? 0.0F : PUSH;
        if (!mask) {
            return List.of(whole(quad, sprite, push));
        }
        Patch patch = Patch.of(quad);
        if (patch == null) {
            return List.of(whole(quad, sprite, push)); // UV が読めない面。そのまま覆う。
        }
        List<int[]> rects = silhouette(patch);
        if (rects == null) {
            return List.of(whole(quad, sprite, push)); // 全部不透明。切り分ける意味が無い。
        }
        if (rects.isEmpty()) {
            return List.of();                    // 全部透明。苔を置く場所が無い。
        }
        List<BakedQuad> out = new ArrayList<>(rects.size());
        for (int[] rect : rects) {
            out.add(patch.slice(quad, sprite, rect));
        }
        return List.copyOf(out);
    }

    /** 元の面をまるごと 1 枚の苔の面にする({@code push} = 法線方向へ押し出す量。0 なら元の面と同じ頂点)。 */
    private static BakedQuad whole(BakedQuad quad, TextureAtlasSprite sprite, float push) {
        int[] src = quad.getVertices();
        int[] out = src.clone();
        int stride = out.length / 4;
        Direction direction = quad.getDirection();

        for (int i = 0; i < 4; i++) {
            int base = i * stride;
            float x = Float.intBitsToFloat(src[base]);
            float y = Float.intBitsToFloat(src[base + 1]);
            float z = Float.intBitsToFloat(src[base + 2]);
            write(out, base, direction, sprite, x, y, z, push);
        }
        return new BakedQuad(out, -1, direction, sprite, quad.isShade(), quad.hasAmbientOcclusion());
    }

    /**
     * 頂点 1 つを書き出す。
     *
     * <ul>
     *     <li><b>UV は位置から貼り直す</b> —— 元の模型の UV は見ない。面の向きに垂直な 2 軸の
     *     ブロック内座標(0〜1)をそのまま苔テクスチャの 0〜1 に写す。元テクスチャの向き・回転・
     *     切り出し位置に左右されず、どのブロックにも同じ向きで苔が乗る。
     *     切り分けた小さい面でも、位置から引くので隣の面と模様がつながる。</li>
     *     <li><b>色は捨てる</b>(白に戻す) —— 草ブロックの側面のような色付けの面を借りると、
     *     苔まで一緒にバイオーム色に染まってしまう。{@code tintIndex} も -1 にする。</li>
     *     <li><b>法線方向へ {@code push} だけ押し出す</b>({@link #PUSH} の説明。0 なら位置はそのまま)。</li>
     * </ul>
     */
    private static void write(int[] out, int base, Direction direction, TextureAtlasSprite sprite,
                              float x, float y, float z, float push) {
        out[base] = Float.floatToRawIntBits(push == 0.0F ? x : x + direction.getStepX() * push);
        out[base + 1] = Float.floatToRawIntBits(push == 0.0F ? y : y + direction.getStepY() * push);
        out[base + 2] = Float.floatToRawIntBits(push == 0.0F ? z : z + direction.getStepZ() * push);
        out[base + 3] = 0xFFFFFFFF;

        float u;
        float v;
        switch (direction.getAxis()) {
            case X -> {
                u = z;
                v = 1.0F - y;
            }
            case Z -> {
                u = x;
                v = 1.0F - y;
            }
            default -> {
                u = x;
                v = z;
            }
        }
        out[base + 4] = Float.floatToRawIntBits(lerp(sprite.getU0(), sprite.getU1(), u));
        out[base + 5] = Float.floatToRawIntBits(lerp(sprite.getV0(), sprite.getV1(), v));
    }

    private static float lerp(float from, float to, float t) {
        float f = Math.max(0.0F, Math.min(1.0F, t));
        return from + f * (to - from);
    }

    // --- 透明画素を抜く ---

    /**
     * 元のテクスチャで<b>色が付いている</b>ところを長方形の並びにする。
     *
     * <p>画素 1 つずつに面を作ると 16x16 の板 1 枚で 256 面になるので、
     * 隣り合う画素を<b>できるだけ大きな長方形へまとめる</b>(横へ伸ばしてから下へ伸ばす)。
     * 松明なら 1〜2 枚、尖った鍾乳石でも 10 枚程度に収まる。
     *
     * @return 長方形の並び([x0, y0, x1, y1] をテクスチャの画素で)。
     *         <b>全部不透明なら {@code null}</b>(切り分ける必要が無い合図)、全部透明なら空。
     */
    private static List<int[]> silhouette(Patch patch) {
        SpriteContents contents = patch.quadSprite().contents();
        int w = patch.x1() - patch.x0();
        int h = patch.y1() - patch.y0();
        // 高解像度のテクスチャでも面が増えすぎないよう、目を粗くする。16x16 なら画素そのまま。
        int stepX = Math.max(1, (w + MASK_CELLS - 1) / MASK_CELLS);
        int stepY = Math.max(1, (h + MASK_CELLS - 1) / MASK_CELLS);
        int cw = (w + stepX - 1) / stepX;
        int ch = (h + stepY - 1) / stepY;

        boolean[] opaque = new boolean[cw * ch];
        boolean any = false;
        boolean all = true;
        for (int cy = 0; cy < ch; cy++) {
            for (int cx = 0; cx < cw; cx++) {
                boolean hit = cellIsOpaque(contents, patch, cx * stepX, cy * stepY, stepX, stepY, w, h);
                opaque[cy * cw + cx] = hit;
                any |= hit;
                all &= hit;
            }
        }
        if (all) {
            return null;
        }
        if (!any) {
            return List.of();
        }

        List<int[]> rects = new ArrayList<>();
        for (int cy = 0; cy < ch; cy++) {
            for (int cx = 0; cx < cw; ) {
                if (!opaque[cy * cw + cx]) {
                    cx++;
                    continue;
                }
                int width = 1;
                while (cx + width < cw && opaque[cy * cw + cx + width]) {
                    width++;
                }
                int height = 1;
                grow:
                while (cy + height < ch) {
                    for (int k = 0; k < width; k++) {
                        if (!opaque[(cy + height) * cw + cx + k]) {
                            break grow;
                        }
                    }
                    height++;
                }
                for (int b = 0; b < height; b++) {
                    for (int k = 0; k < width; k++) {
                        opaque[(cy + b) * cw + cx + k] = false;
                    }
                }
                rects.add(new int[]{
                        patch.x0() + cx * stepX,
                        patch.y0() + cy * stepY,
                        patch.x0() + Math.min(w, (cx + width) * stepX),
                        patch.y0() + Math.min(h, (cy + height) * stepY)});
                cx += width;
            }
        }
        return rects;
    }

    /** 目を粗くしたときの 1 マス。中に不透明な画素が 1 つでもあれば不透明とみなす。 */
    private static boolean cellIsOpaque(SpriteContents contents, Patch patch,
                                        int ox, int oy, int stepX, int stepY, int w, int h) {
        // 目が粗いときは全部見ずに間引く(最大 4x4 点)。16x16 のテクスチャでは step=1 なので厳密。
        int sx = Math.max(1, stepX / 4);
        int sy = Math.max(1, stepY / 4);
        for (int y = oy; y < Math.min(h, oy + stepY); y += sy) {
            for (int x = ox; x < Math.min(w, ox + stepX); x += sx) {
                if (!contents.isTransparent(0, patch.x0() + x, patch.y0() + y)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 面 1 枚を<b>テクスチャの画素の座標で切り分ける</b>ための下ごしらえ。
     *
     * <p>持っているのは「元テクスチャの画素の位置 → ブロック内の位置」の写し方。
     * 面は必ず長方形なので、頂点 3 つぶんの差から 2 本の基底({@code dPdU} / {@code dPdV})が取れる。
     * 回っていても裏返っていても同じ式で扱える。
     */
    private record Patch(TextureAtlasSprite quadSprite, int x0, int y0, int x1, int y1,
                         float u0, float v0, float[] origin, float[] dPdU, float[] dPdV,
                         boolean[] atMaxU, boolean[] atMaxV) {

        /** 読めない面(UV がつぶれている・基底が取れない)なら {@code null}。 */
        static Patch of(BakedQuad quad) {
            TextureAtlasSprite sprite = quad.getSprite();
            float spanU = sprite.getU1() - sprite.getU0();
            float spanV = sprite.getV1() - sprite.getV0();
            if (spanU == 0.0F || spanV == 0.0F) {
                return null;
            }
            SpriteContents contents = sprite.contents();
            int w = contents.width();
            int h = contents.height();

            int[] src = quad.getVertices();
            int stride = src.length / 4;
            float[] tu = new float[4];
            float[] tv = new float[4];
            float[][] pos = new float[4][3];
            for (int i = 0; i < 4; i++) {
                int base = i * stride;
                pos[i][0] = Float.intBitsToFloat(src[base]);
                pos[i][1] = Float.intBitsToFloat(src[base + 1]);
                pos[i][2] = Float.intBitsToFloat(src[base + 2]);
                tu[i] = (Float.intBitsToFloat(src[base + 4]) - sprite.getU0()) / spanU * w;
                tv[i] = (Float.intBitsToFloat(src[base + 5]) - sprite.getV0()) / spanV * h;
            }

            float minU = Math.min(Math.min(tu[0], tu[1]), Math.min(tu[2], tu[3]));
            float maxU = Math.max(Math.max(tu[0], tu[1]), Math.max(tu[2], tu[3]));
            float minV = Math.min(Math.min(tv[0], tv[1]), Math.min(tv[2], tv[3]));
            float maxV = Math.max(Math.max(tv[0], tv[1]), Math.max(tv[2], tv[3]));
            int x0 = clamp((int) Math.floor(minU + 1.0E-4F), 0, w - 1);
            int y0 = clamp((int) Math.floor(minV + 1.0E-4F), 0, h - 1);
            int x1 = clamp((int) Math.ceil(maxU - 1.0E-4F), x0 + 1, w);
            int y1 = clamp((int) Math.ceil(maxV - 1.0E-4F), y0 + 1, h);

            // 頂点 0 を原点に、独立な 2 本を選んで基底を解く。
            float[] dPdU = null;
            float[] dPdV = null;
            float best = 1.0E-3F;
            for (int i = 1; i < 4; i++) {
                for (int j = i + 1; j < 4; j++) {
                    float du1 = tu[i] - tu[0];
                    float dv1 = tv[i] - tv[0];
                    float du2 = tu[j] - tu[0];
                    float dv2 = tv[j] - tv[0];
                    float det = du1 * dv2 - du2 * dv1;
                    if (Math.abs(det) <= best) {
                        continue;
                    }
                    best = Math.abs(det);
                    dPdU = new float[3];
                    dPdV = new float[3];
                    for (int a = 0; a < 3; a++) {
                        float dp1 = pos[i][a] - pos[0][a];
                        float dp2 = pos[j][a] - pos[0][a];
                        dPdU[a] = (dp1 * dv2 - dp2 * dv1) / det;
                        dPdV[a] = (dp2 * du1 - dp1 * du2) / det;
                    }
                }
            }
            if (dPdU == null) {
                return null;
            }

            float midU = (minU + maxU) * 0.5F;
            float midV = (minV + maxV) * 0.5F;
            boolean[] atMaxU = new boolean[4];
            boolean[] atMaxV = new boolean[4];
            for (int i = 0; i < 4; i++) {
                atMaxU[i] = tu[i] > midU;
                atMaxV[i] = tv[i] > midV;
            }
            return new Patch(sprite, x0, y0, x1, y1, tu[0], tv[0], pos[0], dPdU, dPdV, atMaxU, atMaxV);
        }

        /**
         * 長方形 1 つぶんの苔の面を作る。
         *
         * <p>頂点の並び順は<b>元の面のまま</b>にする —— 各頂点が長方形の「どの角」に当たるかを
         * UV の大小で決めて置き換えるので、表裏が引っくり返らない。
         */
        BakedQuad slice(BakedQuad quad, TextureAtlasSprite sprite, int[] rect) {
            int[] out = quad.getVertices().clone();
            int stride = out.length / 4;
            Direction direction = quad.getDirection();
            for (int i = 0; i < 4; i++) {
                float u = atMaxU[i] ? rect[2] : rect[0];
                float v = atMaxV[i] ? rect[3] : rect[1];
                float du = u - u0;
                float dv = v - v0;
                write(out, i * stride, direction, sprite,
                        origin[0] + du * dPdU[0] + dv * dPdV[0],
                        origin[1] + du * dPdU[1] + dv * dPdV[1],
                        origin[2] + du * dPdU[2] + dv * dPdV[2], PUSH);
            }
            return new BakedQuad(out, -1, direction, sprite, quad.isShade(), quad.hasAmbientOcclusion());
        }

        private static int clamp(int value, int min, int max) {
            return Math.max(min, Math.min(max, value));
        }
    }
}
