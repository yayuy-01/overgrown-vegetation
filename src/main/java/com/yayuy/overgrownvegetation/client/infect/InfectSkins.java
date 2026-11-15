package com.yayuy.overgrownvegetation.client.infect;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.lwjgl.opengl.GL11;

import javax.annotation.Nullable;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 汎用の感染体の、塗り替えた絵の置き場(元の絵 1 枚につき {@link #VARIANTS} 通り。作るのは初めて要るときだけで、毎フレームの箱は増えない)。
 * 覗いた頂点から「絵のどの画素が体のどこか」({@link FaceMap})を作り、塗りは {@link InfectPaint} に任せる。
 */
final class InfectSkins {

    /** 同じ絵でも個体ごとに塗りを変える数(個体の乱数の種で選ぶ)。 */
    static final int VARIANTS = 3;
    /** 変わる途中の段(0 = 芯だけ・1 = 変わり終えた姿)。それより前は元の絵のまま。 */
    private static final int STAGES = 2;
    private static final double[] STAGE_STRENGTH = {0.45, 1.0};
    /** 進みがここを越えたら 0 段目・1 段目の絵にする(1 段目 = 植物が突き出る瞬間)。【仮】 */
    static final float STAGE0_AT = 0.2F, STAGE1_AT = 0.5F;
    private static final String SKIN_PATH = "dynamic/infect_skin/";
    /** 頂点が 1 つも来なかった絵を、何回覗いたらあきらめるか。 */
    private static final int MAX_TRIES = 3;
    private static final int MAX_TEXELS = 2048 * 2048;

    /** 元の絵 1 枚ぶん。{@code map} が null = 読めなかった・塗れなかった(元のまま描く)。 */
    private static final class Entry {
        @Nullable
        final FaceMap map;
        final ResourceLocation[] skins = new ResourceLocation[VARIANTS * STAGES];
        List<InfectParts.Anchor> anchors = List.of();

        Entry(@Nullable FaceMap map) {
            this.map = map;
        }
    }

    private static final Map<ResourceLocation, Entry> ENTRIES = new HashMap<>();
    private static final Map<ResourceLocation, Integer> TRIES = new HashMap<>();
    /** 塗り替えた絵 → 元の絵の 1 枚ぶん(部品の付け根を引く)。 */
    private static final Map<ResourceLocation, Entry> SKIN_OWNER = new HashMap<>();
    private static int serial;
    /** 確かめ用: ここが null でなければ、塗り替えた絵と、画素ごとの体の位置の絵(赤緑青 = x y z)をこのフォルダへ書き出す。 */
    @Nullable
    static java.nio.file.Path dumpDir;

    private static void dump(String name, NativeImage img) {
        if (dumpDir != null) {
            try {
                java.nio.file.Files.createDirectories(dumpDir);
                img.writeToFile(dumpDir.resolve(name));
            } catch (Exception ex) {
                OvergrownVegetation.LOGGER.warn("InfectSkins 書き出せない: {}", name, ex);
            }
        }
    }

    private static String dumpName(ResourceLocation texture) {
        String p = texture.getPath();
        return p.substring(p.lastIndexOf('/') + 1).replace(".png", "");
    }

    private InfectSkins() {
    }

    static boolean isSkin(ResourceLocation texture) {
        return texture.getPath().startsWith(SKIN_PATH);
    }

    static boolean known(ResourceLocation texture) {
        return ENTRIES.containsKey(texture);
    }

    static ResourceLocation skinFor(ResourceLocation texture, GenericInfectionClient.State state, float progress) {
        Entry e = ENTRIES.get(texture);
        if (e == null || e.map == null) {
            return texture;
        }
        if (progress < STAGE0_AT) {
            return texture;
        }
        int variant = (int) Math.floorMod(state.seed(), (long) VARIANTS);
        int stage = progress < STAGE1_AT ? 0 : 1;
        int v = variant * STAGES + stage;
        ResourceLocation skin = e.skins[v];
        if (skin == null) {
            try {
                skin = bake(texture, e.map, variant, STAGE_STRENGTH[stage]);
            } catch (RuntimeException ex) {
                OvergrownVegetation.LOGGER.warn("InfectSkins 塗れない(元のまま描く): {}", texture, ex);
                skin = texture;
            }
            e.skins[v] = skin;
            SKIN_OWNER.put(skin, e);
        }
        return skin;
    }

    /** その絵(元の絵でも塗り替えた絵でも)の、部品の付け根。無ければ null。 */
    @Nullable
    static List<InfectParts.Anchor> anchorsFor(ResourceLocation texture) {
        Entry e = SKIN_OWNER.get(texture);
        if (e == null) {
            e = ENTRIES.get(texture);
        }
        return e == null ? null : e.anchors;
    }

    static void clear() {
        Minecraft mc = Minecraft.getInstance();
        for (Entry e : ENTRIES.values()) {
            for (ResourceLocation skin : e.skins) {
                if (skin != null) {
                    mc.getTextureManager().release(skin);
                }
            }
        }
        ENTRIES.clear();
        TRIES.clear();
        SKIN_OWNER.clear();
        InfectPaint.unload();
    }

    private static ResourceLocation bake(ResourceLocation texture, FaceMap map, int variant, double strength) {
        long seed = texture.hashCode() * 31L + variant * 7919L;
        int[] argb = InfectPaint.paint(map, seed, strength);
        NativeImage img = new NativeImage(map.w, map.h, false);
        for (int y = 0; y < map.h; y++) {
            for (int x = 0; x < map.w; x++) {
                int c = argb[y * map.w + x];
                img.setPixelRGBA(x, y, (c & 0xFF00FF00) | ((c & 0xFF) << 16) | ((c >> 16) & 0xFF));
            }
        }
        dump(dumpName(texture) + "_v" + variant + "_s" + strength + ".png", img);
        if (dumpDir != null) {
            NativeImage pos = new NativeImage(map.w, map.h, false);
            for (int y = 0; y < map.h; y++) {
                for (int x = 0; x < map.w; x++) {
                    int i = y * map.w + x;
                    if (map.quad[i] < 0) {
                        pos.setPixelRGBA(x, y, 0);
                        continue;
                    }
                    int r = (int) Math.max(0, Math.min(255, 128 + map.px[i] / map.extent * 200));
                    int g = (int) Math.max(0, Math.min(255, map.yRel(i) * 255));
                    int b = (int) Math.max(0, Math.min(255, 128 + map.pz[i] / map.extent * 200));
                    pos.setPixelRGBA(x, y, 0xFF000000 | (b << 16) | (g << 8) | r);
                }
            }
            dump(dumpName(texture) + "_pos.png", pos);
            for (int pass = 0; pass < 2; pass++) {
                float[] src = pass == 0 ? InfectPaint.debugCover : InfectPaint.debugNoise;
                if (src == null) {
                    continue;
                }
                for (int y = 0; y < map.h; y++) {
                    for (int x = 0; x < map.w; x++) {
                        int i = y * map.w + x;
                        float v = src[i];
                        // 数でない値は赤、範囲の外は青
                        int c = map.quad[i] < 0 ? 0 : Float.isNaN(v) ? 0xFF0000FF : (v < 0 || v > 1) ? 0xFFFF0000 | ((int) (Math.max(0, Math.min(1, v)) * 255) << 8)
                                : 0xFF000000 | ((int) (v * 255) * 0x010101);
                        pos.setPixelRGBA(x, y, c);
                    }
                }
                dump(dumpName(texture) + "_v" + variant + (pass == 0 ? "_cover.png" : "_noise.png"), pos);
            }
            pos.close();
        }
        ResourceLocation id = OvergrownVegetation.id(SKIN_PATH + (serial++));
        Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(img));
        return id;
    }

    /** 覗いた頂点から、絵ごとに「どの画素が体のどこか」を出す。{@code requested} = このフレームで工場に入ってきた絵。 */
    static void learn(Entity entity, InfectRender.QuadTap tap, Set<ResourceLocation> requested, @Nullable ResourceLocation bodyTexture) {
        // 体の大きさ(このフレームで通った四角ぜんぶ)
        float minY = Float.POSITIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
        float minX = Float.POSITIVE_INFINITY, maxX = Float.NEGATIVE_INFINITY, minZ = Float.POSITIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
        for (List<InfectRender.Quad> list : tap.quads.values()) {
            for (InfectRender.Quad q : list) {
                for (int i = 0; i < 4; i++) {
                    float x = q.pos()[i * 3], y = q.pos()[i * 3 + 1], z = q.pos()[i * 3 + 2];
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                    minZ = Math.min(minZ, z);
                    maxZ = Math.max(maxZ, z);
                }
            }
        }
        float extent = Math.max(maxY - minY, Math.max(maxX - minX, maxZ - minZ));
        // 子どもは体が縮んで描かれる(絵は大人と同じ)→ 画素の細かさを大人の大きさで見る
        float scale = entity instanceof LivingEntity living ? Math.max(0.05F, living.getScale() * living.getAgeScale()) : 1.0F;

        for (Map.Entry<RenderType, List<InfectRender.Quad>> e : tap.quads.entrySet()) {
            ResourceLocation texture = InfectRender.TYPE_TEXTURE.get(e.getKey());
            if (texture == null || ENTRIES.containsKey(texture) || isSkin(texture) || e.getValue().isEmpty()) {
                continue;
            }
            FaceMap map = null;
            try {
                map = buildMap(texture, e.getValue(), minY, maxY - minY, extent, scale);
            } catch (Exception ex) {
                OvergrownVegetation.LOGGER.warn("InfectSkins 絵を塗り替えられない: {} {}", entity.getType(), texture, ex);
            }
            if (map == null) {
                OvergrownVegetation.LOGGER.info("InfectSkins 絵を読めない(元のまま描く): {} {}", entity.getType(), texture);
            } else {
                OvergrownVegetation.LOGGER.info("InfectSkins 覚えた: {} {} {}x{} 四角 {} 細かさ {}", entity.getType(), texture, map.w, map.h, map.quadCount, map.k);
            }
            Entry entry = new Entry(map);
            if (map != null && texture.equals(bodyTexture)) {
                entry.anchors = InfectParts.choose(map, e.getValue(), texture.hashCode() * 131L);
            }
            ENTRIES.put(texture, entry);
        }
        // 工場には入ったのに頂点が来なかった絵(描き方が器を通らない・その瞬間は出ていない層)は、何回か待ってからあきらめる
        for (ResourceLocation texture : requested) {
            if (!ENTRIES.containsKey(texture) && TRIES.merge(texture, 1, Integer::sum) >= MAX_TRIES) {
                OvergrownVegetation.LOGGER.info("InfectSkins 頂点が来ない(元のまま描く): {} {}", entity.getType(), texture);
                ENTRIES.put(texture, new Entry(null));
            }
        }
    }

    @Nullable
    private static FaceMap buildMap(ResourceLocation texture, List<InfectRender.Quad> quads, float minY, float spanY, float extent, float scale) throws Exception {
        NativeImage img = read(texture);
        if (img == null) {
            return null;
        }
        try {
            int w = img.getWidth(), h = img.getHeight();
            if (w <= 0 || h <= 0 || (long) w * h > MAX_TEXELS) {
                return null;
            }
            FaceMap m = new FaceMap(w, h, quads.size());
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int c = img.getPixelRGBA(x, y);
                    m.argb[y * w + x] = (c & 0xFF00FF00) | ((c & 0xFF) << 16) | ((c >> 16) & 0xFF);
                }
            }
            m.minY = minY;
            m.spanY = Math.max(1.0E-3F, spanY);
            m.extent = Math.max(1.0E-3F, extent);
            List<Float> dens = new ArrayList<>();
            int qi = 0;
            for (InfectRender.Quad q : quads) {
                float d = m.put(qi++, q);
                if (d > 0.0F) {
                    dens.add(d);
                }
            }
            if (dens.isEmpty()) {
                return null;
            }
            // 画素の細かさ = 1/16 ブロックに何画素か(真ん中の値。大人の大きさで)
            dens.sort(null);
            float median = dens.get(dens.size() / 2);
            m.k = Math.max(1, Math.round(median * scale));
            // 1 単位(= バニラの 1 画素ぶん)が、覗いた個体では何ブロックか(子どもは半分)
            m.unit = m.k / (16.0F * median);
            return m;
        } finally {
            img.close();
        }
    }

    /** 元の絵を読む。資源に無い絵(ほかの MOD がその場で作る絵・プレイヤーの皮)は、GPU に載っている物を読み戻す。 */
    @Nullable
    private static NativeImage read(ResourceLocation texture) throws Exception {
        Minecraft mc = Minecraft.getInstance();
        var res = mc.getResourceManager().getResource(texture);
        if (res.isPresent()) {
            try (InputStream in = res.get().open()) {
                return NativeImage.read(in);
            }
        }
        AbstractTexture tex = mc.getTextureManager().getTexture(texture);
        tex.bind();
        int w = GlStateManager._getTexLevelParameter(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
        int h = GlStateManager._getTexLevelParameter(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
        if (w <= 0 || h <= 0 || (long) w * h > MAX_TEXELS) {
            return null;
        }
        NativeImage img = new NativeImage(w, h, false);
        img.downloadTexture(0, false);
        return img;
    }

    /**
     * 絵の画素ごとの「体のどこか」。{@code quad} が −1 の画素は、体のどの面にも使われていない。
     * 位置は覗いた個体の足元から(ブロック単位。向きは覗いたときのまま = 塗りは位置同士の遠さと高さだけを使う)。
     */
    static final class FaceMap {
        final int w, h;
        final int quadCount;
        /** 元の絵(ARGB)。 */
        final int[] argb;
        final int[] quad;
        final float[] ny, px, py, pz;
        /** 四角ごと: 面の向き(上向き成分)・真ん中の高さ(0 = 足元〜1 = てっぺん)。 */
        final float[] quadNy, quadYRel;
        float minY, spanY, extent;
        /** 画素の細かさ(1/16 ブロックに何画素か。バニラは 1)。 */
        int k = 1;
        /** 1 単位(1/16 ブロックの模型の長さ)の、覗いた個体での長さ(ブロック)。 */
        float unit = 1.0F / 16.0F;

        FaceMap(int w, int h, int quadCount) {
            this.w = w;
            this.h = h;
            this.quadCount = quadCount;
            this.argb = new int[w * h];
            this.quad = new int[w * h];
            Arrays.fill(this.quad, -1);
            this.ny = new float[w * h];
            this.px = new float[w * h];
            this.py = new float[w * h];
            this.pz = new float[w * h];
            this.quadNy = new float[quadCount];
            this.quadYRel = new float[quadCount];
        }

        float yRel(int i) {
            return (this.py[i] - this.minY) / this.spanY;
        }

        /** 四角 1 枚を画素へ写す。返り値 = 1/16 ブロックあたりの画素数(分からなければ 0)。 */
        float put(int qi, InfectRender.Quad q) {
            float[] p = q.pos(), t = q.uv();
            float u0 = Float.POSITIVE_INFINITY, v0 = Float.POSITIVE_INFINITY, u1 = Float.NEGATIVE_INFINITY, v1 = Float.NEGATIVE_INFINITY, cy = 0.0F;
            for (int i = 0; i < 4; i++) {
                u0 = Math.min(u0, t[i * 2] * this.w);
                u1 = Math.max(u1, t[i * 2] * this.w);
                v0 = Math.min(v0, t[i * 2 + 1] * this.h);
                v1 = Math.max(v1, t[i * 2 + 1] * this.h);
                cy += p[i * 3 + 1] / 4.0F;
            }
            this.quadNy[qi] = q.ny();
            this.quadYRel[qi] = (cy - this.minY) / this.spanY;
            if (!(u1 > u0) || !(v1 > v0)) {
                return 0.0F;
            }
            // 絵の上の位置 → 体の位置(頂点 0 と、そこから絵の上でいちばん広がる 2 頂点で決める)
            int bj = 1, bk = 2;
            float best = 0.0F;
            for (int j = 1; j < 4; j++) {
                for (int k2 = j + 1; k2 < 4; k2++) {
                    float det = (t[j * 2] - t[0]) * (t[k2 * 2 + 1] - t[1]) - (t[k2 * 2] - t[0]) * (t[j * 2 + 1] - t[1]);
                    if (Math.abs(det) > Math.abs(best)) {
                        best = det;
                        bj = j;
                        bk = k2;
                    }
                }
            }
            float ju = (t[bj * 2] - t[0]) * this.w, jv = (t[bj * 2 + 1] - t[1]) * this.h;
            float ku = (t[bk * 2] - t[0]) * this.w, kv = (t[bk * 2 + 1] - t[1]) * this.h;
            float det = ju * kv - ku * jv;
            boolean flat = Math.abs(det) < 1.0E-6F;
            float ex = p[bj * 3] - p[0], ey = p[bj * 3 + 1] - p[1], ez = p[bj * 3 + 2] - p[2];
            float fx = p[bk * 3] - p[0], fy = p[bk * 3 + 1] - p[1], fz = p[bk * 3 + 2] - p[2];
            int x0 = Math.max(0, (int) Math.floor(u0 + 0.01F)), x1 = Math.min(this.w, (int) Math.ceil(u1 - 0.01F));
            int y0 = Math.max(0, (int) Math.floor(v0 + 0.01F)), y1 = Math.min(this.h, (int) Math.ceil(v1 - 0.01F));
            for (int y = y0; y < y1; y++) {
                for (int x = x0; x < x1; x++) {
                    int i = y * this.w + x;
                    // 同じ画素を使う面が重なったら(左右の脚など)、いちばん上を向く面を採る
                    if (this.quad[i] >= 0 && q.ny() <= this.ny[i]) {
                        continue;
                    }
                    float du = x + 0.5F - t[0] * this.w, dv = y + 0.5F - t[1] * this.h;
                    float a = flat ? 0.0F : (du * kv - ku * dv) / det;
                    float b = flat ? 0.0F : (ju * dv - du * jv) / det;
                    this.quad[i] = qi;
                    this.ny[i] = q.ny();
                    this.px[i] = p[0] + a * ex + b * fx;
                    this.py[i] = p[1] + a * ey + b * fy;
                    this.pz[i] = p[2] + a * ez + b * fz;
                }
            }
            if (flat) {
                return 0.0F;
            }
            // 面の広さ(ブロック²)= 2 辺の外積。絵の上の広さ(画素²)= |det|
            float cx = ey * fz - ez * fy, cyy = ez * fx - ex * fz, cz = ex * fy - ey * fx;
            float area = (float) Math.sqrt(cx * cx + cyy * cyy + cz * cz);
            return area < 1.0E-8F ? 0.0F : (float) Math.sqrt(Math.abs(det) / (area * 256.0F));
        }
    }
}
