package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.CorruptedLianaBlockEntity;
import com.yayuy.overgrownvegetation.client.atmosphere.Atmosphere;
import com.yayuy.overgrownvegetation.client.atmosphere.LeafSway;
import com.yayuy.overgrownvegetation.worldgen.ClimbingVine;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 登る蔓(2026-11-03 試作)の描き手。{@link CorruptedLianaRenderer} が {@link CorruptedLianaBlockEntity#hasClimb()} の蔦をここへ回す。
 *
 * <p>経路の点(サーバーが {@code worldgen.ClimbingVine} で決めた)に沿って、6 角の管を描く。管の向きは平行移動の枠
 * (最初の点の幹の外向きから、接線に直交させながら送る)なので、角を回り込んでも絵がねじれて飛ばない。
 * 縒りの絵(2〜3 本の筋が斜めに走る)を周に 1 回巻くので、筋は管を螺旋に回る。光の向きの陰りは頂点の色で付ける
 * (文字のシェーダーは法線を持たないため、上ほど明るく下ほど暗く)。
 *
 * <p>節(3〜5 ブロックおき)に小さな葉 2〜3 枚と苔の塊を付ける。幹に沿う区間は揺れず、渡る区間だけ今の蔦と同じ風で揺れる
 * (両端の幹に付いた所は止める)。形は印の位置を種にした決定計算で、1 回組んだら使い回す(光と揺れだけ毎フレーム)。
 */
public final class ClimbingVineRenderer {

    private static final int SIDES = 6;
    /** 縒りの絵の縦 1 回がワールドで何ブロックか(絵は 16×32)。 */
    private static final float TILE_LENGTH = 2.0F;
    /** 節の間隔(ユーザー指定 3〜5 ブロック)。 */
    private static final float NODE_MIN = 3.0F;
    private static final float NODE_MAX = 5.0F;
    private static final float LEAF_SIZE = 0.34F;
    private static final float MOSS_SIZE = 0.5F;
    /** 揺れの倍率(今の蔦の ROPE_SWAY と同じ)と、幹に付いた端から全開になるまでの長さ。 */
    private static final float ROPE_SWAY = 2.0F;
    private static final float PINNED_RAMP = 1.5F;
    /** 光を測る点の間隔(点の数)。 */
    private static final int LIGHT_STRIDE = 4;

    static final ResourceLocation BODY_TEXTURE = OvergrownVegetation.id("textures/entity/overgrown_liana_climb.png");
    /** 32×16: 左 = 葉、右 = 苔の塊。 */
    static final ResourceLocation TRIM_TEXTURE = OvergrownVegetation.id("textures/entity/overgrown_liana_climb_trim.png");
    /**
     * 林冠の光のシェーダー(2026-11-05 光の点検 6「登る蔓の葉が浮く」)。文字のシェーダーのままだと地形の林冠の影・森の青緑・空の光の色・夜の色を
     * 通らず、蔓だけ素の赤茶で浮いていた。地形の草・シダと同じ {@code ovCanopyLight}(背面の影なし)を掛ける。読み込めない・シェーダーパック使用中は文字のシェーダー。
     */
    private static final RenderStateShard.ShaderStateShard CANOPY_LIT_SHADER = CorruptedLianaRenderer.CANOPY_LIT_SHADER;
    static final RenderType BODY_RENDER_TYPE = CorruptedLianaRenderer.createRenderType("overgrown_vegetation_liana_climb", BODY_TEXTURE, CANOPY_LIT_SHADER);
    static final RenderType TRIM_RENDER_TYPE = CorruptedLianaRenderer.createRenderType("overgrown_vegetation_liana_climb_trim", TRIM_TEXTURE, CANOPY_LIT_SHADER);
    /** 林冠の細い蔓の羽状の小葉(2026-11-03。16×16 の 1 枚。候補 1〜3 のうち 1 を使う。ユーザーが番号で選ぶ)。 */
    static final ResourceLocation CANOPY_LEAF_TEXTURE = OvergrownVegetation.id("textures/entity/overgrown_liana_climb_leaf_1.png");
    static final RenderType CANOPY_LEAF_RENDER_TYPE = CorruptedLianaRenderer.createRenderType("overgrown_vegetation_liana_climb_leaf", CANOPY_LEAF_TEXTURE, CANOPY_LIT_SHADER);

    private static final Map<CorruptedLianaBlockEntity, Geometry> CACHE = new WeakHashMap<>();
    /** 林冠の細い蔓の描画距離と、その手前で薄れる幅(ブロック)。描画のスレッドだけが使う透明度。 */
    public static final float CANOPY_VIEW_DISTANCE = 20.0F;
    private static final float CANOPY_FADE = 2.0F;
    private static float alpha = 1.0F;

    /**
     * @param ring   点ごと・周ごとの管の頂点(印のマスの角からの相対、静止時)。[(i * (SIDES+1) + k) * 3]
     * @param shade  周ごとの陰り(頂点の色)
     * @param v      点ごとの V
     * @param sway   点ごとの揺れの重み(幹に沿う所は 0)
     * @param lightPos 光を測る位置(LIGHT_STRIDE 点おき)
     * @param trims  葉・苔の四角形(4 頂点 × (x,y,z,u,v) + 点の添字)
     * @param face   管の頂点ごとの面の明暗({@link #faceShade}。ring と同じ並び、3 つおきでなく 1 つずつ)
     */
    private record Geometry(int count, int sides, float[] ring, float[] shade, float[] v, float[] sway, float[] center,
            BlockPos[] lightPos, List<float[]> trims, List<float[]> canopyLeaves, AABB box, float[] face) {
    }

    /**
     * 面の明暗(2026-11-06 光の点検 6 の続き): 地形が頂点の色に焼き込む面の明るさ(上 1.0・下 0.5・南北 0.8・東西 0.6 を法線の 2 乗で混ぜる。
     * Sodium が斜めの面にする混ぜ方と同じ)。登る蔓は幹と同じ太さの「面のある管」なので、幹と同じ係数を掛ける(掛けないと昼 +21%・夕方 +31%)。
     * 垂れ蔦などの薄い帯には掛けない(下から見た帯が 0.5 になり、幹より 2 割以上暗くなった)。{@code up} が真なら上下の向きを問わず上向きとして扱う(葉の両面)。
     */
    private static float faceShade(float x, float y, float z, boolean up) {
        float l = x * x + y * y + z * z;
        if (l < 1.0E-8F) {
            return 1.0F;
        }
        float yy = y * y / l;
        return (x * x * 0.6F + z * z * 0.8F) / l + yy * (up || y > 0.0F ? 1.0F : 0.5F);
    }

    /** 葉・苔の四角形の面の明暗を、その明るさ(q[21])に掛けておく(頂点 0・1・2 の外積の向き。両面なので上向き扱い)。 */
    private static void applyFaceShade(List<float[]> quads) {
        for (float[] q : quads) {
            float ax = q[5] - q[0], ay = q[6] - q[1], az = q[7] - q[2];
            float bx = q[10] - q[0], by = q[11] - q[1], bz = q[12] - q[2];
            q[21] *= faceShade(ay * bz - az * by, az * bx - ax * bz, ax * by - ay * bx, true);
        }
    }

    private static final float[] SWAY = new float[3];
    private static final double[] SWAY_TMP = new double[3];

    private ClimbingVineRenderer() {
    }

    public static AABB box(CorruptedLianaBlockEntity be) {
        Geometry g = geometry(be);
        return g != null ? g.box() : new AABB(be.getBlockPos());
    }

    private static Geometry geometry(CorruptedLianaBlockEntity be) {
        Geometry g = CACHE.get(be);
        if (g == null) {
            g = build(be);
            CACHE.put(be, g);
        }
        return g;
    }

    public static void render(CorruptedLianaBlockEntity be, float partialTick, PoseStack poseStack, MultiBufferSource buffers) {
        Level level = be.getLevel();
        if (level == null) {
            return;
        }
        Geometry g = geometry(be);
        // 林冠の細い蔓は 20 ブロックまで、18〜20 で薄れて消える(2026-11-03 軽量化、ユーザー決定「遠くでは消えてよい」)。ほかは不透明のまま
        alpha = 1.0F;
        if (be.isClimbCanopy()) {
            net.minecraft.world.phys.Vec3 cam = net.minecraft.client.Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
            AABB b = g.box();
            double ddx = Math.max(0.0D, Math.max(b.minX - cam.x, cam.x - b.maxX));
            double ddy = Math.max(0.0D, Math.max(b.minY - cam.y, cam.y - b.maxY));
            double ddz = Math.max(0.0D, Math.max(b.minZ - cam.z, cam.z - b.maxZ));
            alpha = Mth.clamp((CANOPY_VIEW_DISTANCE - (float) Math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz)) / CANOPY_FADE, 0.0F, 1.0F);
            if (alpha <= 0.0F) {
                return;
            }
        }
        int n = g.count();
        BlockPos self = be.getBlockPos();

        // 光: 何点かおきに測って間を補う。
        int[] lights = new int[n];
        int samples = g.lightPos().length;
        int[] sampled = new int[samples];
        for (int j = 0; j < samples; j++) {
            BlockPos p = g.lightPos()[j];
            sampled[j] = LightTexture.pack(level.getBrightness(LightLayer.BLOCK, p), level.getBrightness(LightLayer.SKY, p));
        }
        for (int i = 0; i < n; i++) {
            int j = Math.min(samples - 1, i / LIGHT_STRIDE);
            int j1 = Math.min(samples - 1, j + 1);
            float f = (i - j * LIGHT_STRIDE) / (float) LIGHT_STRIDE;
            int b = Math.round(Mth.lerp(f, LightTexture.block(sampled[j]), LightTexture.block(sampled[j1])));
            int s = Math.round(Mth.lerp(f, LightTexture.sky(sampled[j]), LightTexture.sky(sampled[j1])));
            lights[i] = LightTexture.pack(b, s);
        }

        // 揺れ: 渡る区間だけ。点ごとに 1 つのずれを、その輪の全頂点に足す。
        float[] dx = new float[n];
        float[] dy = new float[n];
        float[] dz = new float[n];
        float amount = LeafSway.amount(partialTick);
        if (amount > 0.0F) {
            double time = Atmosphere.time(partialTick);
            for (int i = 0; i < n; i++) {
                float w = g.sway()[i];
                if (w <= 0.0F) {
                    continue;
                }
                LeafSway.displacement(self.getX() + (double) g.center()[i * 3], self.getY() + (double) g.center()[i * 3 + 1],
                        self.getZ() + (double) g.center()[i * 3 + 2], time, amount, SWAY_TMP);
                float k = ROPE_SWAY * w;
                dx[i] = (float) SWAY_TMP[0] * k;
                dy[i] = (float) SWAY_TMP[1] * k;
                dz[i] = (float) SWAY_TMP[2] * k;
            }
        }

        poseStack.pushPose();
        Matrix4f pose = poseStack.last().pose();
        VertexConsumer body = buffers.getBuffer(BODY_RENDER_TYPE);
        float[] r = g.ring();
        int sides = g.sides();
        for (int i = 0; i < n - 1; i++) {
            for (int k = 0; k < sides; k++) {
                int a = (i * (sides + 1) + k) * 3;
                int b = (i * (sides + 1) + k + 1) * 3;
                int c = ((i + 1) * (sides + 1) + k + 1) * 3;
                int d = ((i + 1) * (sides + 1) + k) * 3;
                float u0 = k / (float) sides;
                float u1 = (k + 1) / (float) sides;
                float[] f = g.face();
                int fa = i * (sides + 1) + k;
                int fd = (i + 1) * (sides + 1) + k;
                vertex(body, pose, r[a] + dx[i], r[a + 1] + dy[i], r[a + 2] + dz[i], u0, g.v()[i], g.shade()[k] * f[fa], lights[i]);
                vertex(body, pose, r[b] + dx[i], r[b + 1] + dy[i], r[b + 2] + dz[i], u1, g.v()[i], g.shade()[k + 1] * f[fa + 1], lights[i]);
                vertex(body, pose, r[c] + dx[i + 1], r[c + 1] + dy[i + 1], r[c + 2] + dz[i + 1], u1, g.v()[i + 1], g.shade()[k + 1] * f[fd + 1], lights[i + 1]);
                vertex(body, pose, r[d] + dx[i + 1], r[d + 1] + dy[i + 1], r[d + 2] + dz[i + 1], u0, g.v()[i + 1], g.shade()[k] * f[fd], lights[i + 1]);
            }
        }
        if (!g.trims().isEmpty()) {
            VertexConsumer trim = buffers.getBuffer(TRIM_RENDER_TYPE);
            for (float[] q : g.trims()) {
                int i = (int) q[20];
                // 葉の先(3・4 頂点目)は揺れを 1.6 倍(今の蔦の垂れ蔦と同じ)
                for (int vtx = 0; vtx < 4; vtx++) {
                    float m = vtx >= 2 ? 1.6F : 1.0F;
                    int o = vtx * 5;
                    vertex(trim, pose, q[o] + dx[i] * m, q[o + 1] + dy[i] * m, q[o + 2] + dz[i] * m, q[o + 3], q[o + 4],
                            q[21], lights[i]);
                }
            }
        }
        if (!g.canopyLeaves().isEmpty()) {
            VertexConsumer leaf = buffers.getBuffer(CANOPY_LEAF_RENDER_TYPE);
            for (float[] q : g.canopyLeaves()) {
                int i = (int) q[20];
                for (int vtx = 0; vtx < 4; vtx++) {
                    float m = vtx >= 2 ? 1.6F : 1.0F;
                    int o = vtx * 5;
                    vertex(leaf, pose, q[o] + dx[i] * m, q[o + 1] + dy[i] * m, q[o + 2] + dz[i] * m, q[o + 3], q[o + 4],
                            q[21], lights[i]);
                }
            }
        }
        poseStack.popPose();
    }

    private static void vertex(VertexConsumer buffer, Matrix4f pose, float x, float y, float z, float u, float v, float shade, int light) {
        buffer.addVertex(pose, x, y, z).setColor(shade, shade, shade, alpha).setUv(u, v).setLight(light);
    }

    private static Geometry build(CorruptedLianaBlockEntity be) {
        float[] path = be.getClimbPath();
        int stride = CorruptedLianaBlockEntity.CLIMB_STRIDE;
        int n = path.length / stride;
        float[] cx = new float[n];
        float[] cy = new float[n];
        float[] cz = new float[n];
        float[] s = new float[n];
        for (int i = 0; i < n; i++) {
            cx[i] = path[i * stride];
            cy[i] = path[i * stride + 1];
            cz[i] = path[i * stride + 2];
            if (i > 0) {
                s[i] = s[i - 1] + Mth.sqrt(sq(cx[i] - cx[i - 1]) + sq(cy[i] - cy[i - 1]) + sq(cz[i] - cz[i - 1]));
            }
        }
        float total = s[n - 1];
        // 細い蔓・垂れる綱は 4 角(本体は 6 角)。
        float scale = be.getClimbScale();
        // 本体 6 角、束の細い蔓・綱 4 角、林冠の細い蔓 3 角(2026-11-03 軽量化、ユーザー決定)
        int sides = be.isClimbMain() ? SIDES : be.isClimbCanopy() ? 3 : 4;

        // 平行移動の枠: 最初の法線は幹の外向き。
        float[] ring = new float[n * (sides + 1) * 3];
        float[] center = new float[n * 3];
        float nX = path[3];
        float nY = 0.0F;
        float nZ = path[4];
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        float[] tx = new float[n];
        float[] ty = new float[n];
        float[] tz = new float[n];
        float[] nxs = new float[n];
        float[] nys = new float[n];
        float[] nzs = new float[n];
        for (int i = 0; i < n; i++) {
            int a = Math.max(0, i - 1);
            int b = Math.min(n - 1, i + 1);
            float ttx = cx[b] - cx[a];
            float tty = cy[b] - cy[a];
            float ttz = cz[b] - cz[a];
            float tl = Mth.sqrt(ttx * ttx + tty * tty + ttz * ttz);
            if (tl < 1.0E-5F) {
                ttx = 0.0F;
                tty = 1.0F;
                ttz = 0.0F;
                tl = 1.0F;
            }
            ttx /= tl;
            tty /= tl;
            ttz /= tl;
            // N から接線の成分を除く
            float dot = nX * ttx + nY * tty + nZ * ttz;
            nX -= dot * ttx;
            nY -= dot * tty;
            nZ -= dot * ttz;
            float nl = Mth.sqrt(nX * nX + nY * nY + nZ * nZ);
            if (nl < 1.0E-4F) {
                // 接線と重なった: 何か直交するものを取り直す
                nX = -ttz;
                nY = 0.0F;
                nZ = ttx;
                nl = Mth.sqrt(nX * nX + nZ * nZ);
                if (nl < 1.0E-4F) {
                    nX = 1.0F;
                    nl = 1.0F;
                }
            }
            nX /= nl;
            nY /= nl;
            nZ /= nl;
            float bX = tty * nZ - ttz * nY;
            float bY = ttz * nX - ttx * nZ;
            float bZ = ttx * nY - tty * nX;
            float rad = ClimbingVine.radiusAt(s[i], total) * scale;
            for (int k = 0; k <= sides; k++) {
                double ang = Math.PI * 2.0D * k / sides;
                float co = (float) Math.cos(ang);
                float si = (float) Math.sin(ang);
                int o = (i * (sides + 1) + k) * 3;
                ring[o] = cx[i] + (nX * co + bX * si) * rad;
                ring[o + 1] = cy[i] + (nY * co + bY * si) * rad;
                ring[o + 2] = cz[i] + (nZ * co + bZ * si) * rad;
            }
            center[i * 3] = cx[i];
            center[i * 3 + 1] = cy[i];
            center[i * 3 + 2] = cz[i];
            tx[i] = ttx;
            ty[i] = tty;
            tz[i] = ttz;
            nxs[i] = nX;
            nys[i] = nY;
            nzs[i] = nZ;
            minX = Math.min(minX, cx[i]);
            minY = Math.min(minY, cy[i]);
            minZ = Math.min(minZ, cz[i]);
            maxX = Math.max(maxX, cx[i]);
            maxY = Math.max(maxY, cy[i]);
            maxZ = Math.max(maxZ, cz[i]);
        }
        // 陰り: 周の向きごとの固定値(管がどちらへ向いても、枠の N 側を明るく B の反対側を暗く…ではなく、
        // 周ごとに 0.62〜1.0 を山なりに配る。N は最初「幹の外向き」なので、幹に沿う区間では外側が明るく幹側が暗い)。
        float[] shade = new float[sides + 1];
        for (int k = 0; k <= sides; k++) {
            double ang = Math.PI * 2.0D * k / sides;
            shade[k] = 0.62F + 0.38F * (float) (0.5D + 0.5D * Math.cos(ang - 0.6D));
        }
        float[] v = new float[n];
        for (int i = 0; i < n; i++) {
            v[i] = s[i] / TILE_LENGTH;
        }

        // 揺れの重み: 渡る区間の中だけ、両端から PINNED_RAMP かけて 0 → 1。
        float[] sway = new float[n];
        int bs = be.getClimbBridgeStart();
        int bend = be.getClimbBridgeEnd();
        if (be.isClimbHanging()) {
            // 垂れる綱: 上の端は吊った所と同じ重み、先へ行くほど大きく(先は 1.6 倍。今の蔦の垂れ蔦と同じ)
            float top = be.getClimbHangTop();
            for (int i = 0; i < n; i++) {
                if (be.isClimbCanopy() && top <= 0.0F) {
                    // 林冠から垂れる細い蔓: 揺れるのは先の 2.5 ブロックだけ(上は葉の中で止まっている。区画に焼く次の段の前提)
                    float fromTip = total - s[i];
                    sway[i] = 1.6F * smooth(Mth.clamp(1.0F - fromTip / 2.5F, 0.0F, 1.0F));
                } else {
                    sway[i] = top + (1.6F - top) * (s[i] / Math.max(total, 1.0E-3F));
                }
            }
        } else if (bs >= 0 && bend > bs && bend < n) {
            for (int i = bs + 1; i < bend; i++) {
                float fromStart = s[i] - s[bs];
                float fromEnd = s[bend] - s[i];
                sway[i] = smooth(Mth.clamp(Math.min(fromStart, fromEnd) / PINNED_RAMP, 0.0F, 1.0F));
            }
        }

        // 光を測る位置: 点から外向きへ 0.4(幹の中を測らない)。
        BlockPos self = be.getBlockPos();
        int samples = (n - 1) / LIGHT_STRIDE + 1;
        BlockPos[] lightPos = new BlockPos[samples];
        for (int j = 0; j < samples; j++) {
            int i = Math.min(n - 1, j * LIGHT_STRIDE);
            lightPos[j] = BlockPos.containing(self.getX() + (double) (cx[i] + path[i * stride + 3] * 0.4F),
                    self.getY() + (double) (cy[i] + 0.1F), self.getZ() + (double) (cz[i] + path[i * stride + 4] * 0.4F));
            // 測る所が固いブロックの中(太い蔦の芯に巻き付く蔓の下側など。2026-11-07)なら、点のマス → 上下・横の空いたマスへ寄せる(中は光 0 で黒くなる)
            Level lv = be.getLevel();
            if (lv != null && lv.getBlockState(lightPos[j]).isSolidRender(lv, lightPos[j])) {
                BlockPos own = BlockPos.containing(self.getX() + (double) cx[i], self.getY() + (double) cy[i], self.getZ() + (double) cz[i]);
                BlockPos[] tries = {own, own.below(), own.above(), own.north(), own.south(), own.east(), own.west()};
                for (BlockPos q : tries) {
                    if (!lv.getBlockState(q).isSolidRender(lv, q)) {
                        lightPos[j] = q;
                        break;
                    }
                }
            }
        }

        // 節の葉と苔(印の位置を種に)
        List<float[]> trims = new ArrayList<>();
        List<float[]> canopyLeaves = new ArrayList<>();
        RandomSource random = RandomSource.create(self.asLong() * 0x9E3779B97F4A7C15L + 0xC11B);
        // 本体: 3〜5 ブロックの節(ユーザー指定)に、管を包む苔の塊(板 3 枚)と小さな葉 2〜3 枚。節の間にも 1〜1.8 ブロックおきに小さな苔の房
        // (2026-11-03 ユーザー「苔と葉を厚く」)。細い蔓・綱は苔の房だけ(ときどき葉 1 枚)。
        boolean main = be.isClimbMain();
        boolean hanging = be.isClimbHanging();
        float next = 1.2F + random.nextFloat() * 1.5F;
        float nextTuft = 0.6F + random.nextFloat();
        if (be.isClimbCanopy()) {
            // 林冠の細い蔓(2026-11-03 採用): 2〜4 ブロックおきに羽状の小葉 1〜2 枚(苔の房は付けない。花・実は無し。ユーザー指定)。
            // 葉から真下に垂れる蔓(上の端が揺れない = 林冠から)の先には 3〜4 枚の房(仮決め)。絵は別(CANOPY_LEAF_TEXTURE)。
            float nextLeaf = 0.8F + random.nextFloat() * 1.5F;
            for (int i = 1; i < n - 1; i++) {
                if (s[i] < nextLeaf) {
                    continue;
                }
                nextLeaf = s[i] + 2.0F + random.nextFloat() * 2.0F;
                float ox = path[i * stride + 3];
                float oz = path[i * stride + 4];
                float rad = ClimbingVine.radiusAt(s[i], total) * scale;
                int leaves = 1 + random.nextInt(2);
                for (int l = 0; l < leaves; l++) {
                    addLeaf(canopyLeaves, i, cx[i], cy[i], cz[i], ox, oz, tx[i], ty[i], tz[i], rad, true, random, 1.0F, 1.0F);
                }
            }
            if (hanging && be.getClimbHangTop() <= 0.0F && n > 3) {
                int tip = n - 2;
                float rad = ClimbingVine.radiusAt(s[tip], total) * scale;
                int tuft = 3 + random.nextInt(2);
                for (int l = 0; l < tuft; l++) {
                    addLeaf(canopyLeaves, tip, cx[tip], cy[tip], cz[tip], path[tip * stride + 3], path[tip * stride + 4],
                            tx[tip], ty[tip], tz[tip], rad, true, random, 1.0F, 1.1F);
                }
            }
        } else
        for (int i = 1; i < n - 2; i++) {
            float ox = path[i * stride + 3];
            float oz = path[i * stride + 4];
            boolean onBridge = hanging || sway[i] > 0.0F || (bs >= 0 && i > bs && i < bend);
            float rad = ClimbingVine.radiusAt(s[i], total) * scale;
            if (main && s[i] >= next) {
                next = s[i] + NODE_MIN + random.nextFloat() * (NODE_MAX - NODE_MIN);
                nextTuft = s[i] + 0.8F + random.nextFloat() * 0.6F;
                float bX = ty[i] * nzs[i] - tz[i] * nys[i];
                float bY = tz[i] * nxs[i] - tx[i] * nzs[i];
                float bZ = tx[i] * nys[i] - ty[i] * nxs[i];
                addMossSleeve(trims, i, cx[i], cy[i], cz[i], tx[i], ty[i], tz[i], nxs[i], nys[i], nzs[i], bX, bY, bZ,
                        ox, oz, rad, onBridge, random);
                int leaves = 2 + random.nextInt(2);
                for (int l = 0; l < leaves; l++) {
                    addLeaf(trims, i, cx[i], cy[i], cz[i], ox, oz, tx[i], ty[i], tz[i], rad, onBridge, random);
                }
            } else if (s[i] >= nextTuft) {
                nextTuft = s[i] + (main ? 1.0F + random.nextFloat() * 0.8F : 1.5F + random.nextFloat() * 1.0F);
                addMoss(trims, i, cx[i], cy[i], cz[i], ox, oz, tx[i], ty[i], tz[i], rad, onBridge, random,
                        main ? 0.55F : 0.4F);
                if (!main && random.nextFloat() < 0.3F) {
                    addLeaf(trims, i, cx[i], cy[i], cz[i], ox, oz, tx[i], ty[i], tz[i], rad, onBridge, random);
                }
            }
        }

        AABB box = new AABB(minX, minY, minZ, maxX, maxY, maxZ).move(self.getX(), self.getY(), self.getZ()).inflate(1.0D);
        // 面の明暗(11-06): 管は周の外向き(輪の点 − 中心)、葉・苔は四角形の向き。
        float[] face = new float[n * (sides + 1)];
        for (int i = 0; i < n; i++) {
            for (int k = 0; k <= sides; k++) {
                int o = (i * (sides + 1) + k) * 3;
                face[i * (sides + 1) + k] = faceShade(ring[o] - center[i * 3], ring[o + 1] - center[i * 3 + 1],
                        ring[o + 2] - center[i * 3 + 2], false);
            }
        }
        applyFaceShade(trims);
        applyFaceShade(canopyLeaves);
        return new Geometry(n, sides, ring, shade, v, sway, center, lightPos, trims, canopyLeaves, box, face);
    }

    /**
     * 葉 1 枚: 付け根を管の外側の面に置き、外向きと下向きの間へ垂らす小さな四角形。管に沿って少しずつ回し、どの向きから見ても何枚かは面で見える。
     * 渡る区間では外向き = 水平の横向きなので、左右どちらかへ垂れる。
     */
    private static void addLeaf(List<float[]> out, int index, float x, float y, float z, float ox, float oz,
            float tx, float ty, float tz, float rad, boolean onBridge, RandomSource random) {
        addLeaf(out, index, x, y, z, ox, oz, tx, ty, tz, rad, onBridge, random, 0.5F, 1.0F);
    }

    /** {@code u1}: 絵の右端の U(節の葉は 32×16 の左半分 = 0.5、林冠の細い蔓の羽状の葉は 1 枚の絵 = 1)。{@code sizeScale}: 大きさの倍率。 */
    private static void addLeaf(List<float[]> out, int index, float x, float y, float z, float ox, float oz,
            float tx, float ty, float tz, float rad, boolean onBridge, RandomSource random, float u1, float sizeScale) {
        float side = random.nextBoolean() ? 1.0F : -1.0F;
        if (onBridge) {
            ox *= side;
            oz *= side;
        }
        // 付け根: 管の中心から外向きに半径ぶん、管に沿って ±0.15
        float along = (random.nextFloat() - 0.5F) * 0.3F;
        float bx = x + ox * rad * 0.8F + tx * along;
        float by = y + ty * along;
        float bz = z + oz * rad * 0.8F + tz * along;
        // 葉の幅の向き: 接線と外向きの間を回す
        float ang = (random.nextFloat() - 0.5F) * 1.6F;
        float wx = tx * (float) Math.cos(ang) + (-oz) * (float) Math.sin(ang);
        float wy = ty * (float) Math.cos(ang);
        float wz = tz * (float) Math.cos(ang) + ox * (float) Math.sin(ang);
        float wl = Mth.sqrt(wx * wx + wy * wy + wz * wz);
        float size = LEAF_SIZE * sizeScale * (0.8F + random.nextFloat() * 0.4F);
        float half = size / 2.0F;
        wx = wx / Math.max(wl, 1.0E-4F) * half;
        wy = wy / Math.max(wl, 1.0E-4F) * half;
        wz = wz / Math.max(wl, 1.0E-4F) * half;
        // 先: 外向きへ 0.55・下へ 0.75 の割合で size ぶん
        float outward = 0.45F + random.nextFloat() * 0.3F;
        float down = Mth.sqrt(Math.max(0.0F, 1.0F - outward * outward));
        float px = ox * outward * size;
        float py = -down * size;
        float pz = oz * outward * size;
        float shade = 0.82F + random.nextFloat() * 0.18F;
        out.add(new float[]{
                bx - wx, by - wy, bz - wz, 0.0F, 0.0F,
                bx + wx, by + wy, bz + wz, u1, 0.0F,
                bx + wx + px, by + wy + py, bz + wz + pz, u1, 1.0F,
                bx - wx + px, by - wy + py, bz - wz + pz, 0.0F, 1.0F,
                index, shade});
    }

    /** 苔の塊: 管を覆う板(外向きに半径 + 0.03)と、それと交差して起きた板。 */
    /**
     * 節の苔の塊(2026-11-03 ユーザー「苔と葉を厚く」): 管のまわり 3 方向に、管に沿う板を少し浮かせて重ね、下の縁を外へ反らせる。
     * 1 枚の平たい板が紙に見えたので、包む形にした。幹に沿う区間では幹側を向く板は省く(幹に刺さる)。
     */
    private static void addMossSleeve(List<float[]> out, int index, float x, float y, float z, float tx, float ty, float tz,
            float nX, float nY, float nZ, float bX, float bY, float bZ, float ox, float oz, float rad, boolean onBridge,
            RandomSource random) {
        float len = 0.55F + random.nextFloat() * 0.3F;
        float a0 = random.nextFloat() * (float) Math.PI * 2.0F;
        for (int k = 0; k < 3; k++) {
            float a = a0 + k * (float) Math.PI * 2.0F / 3.0F;
            float vx = nX * Mth.cos(a) + bX * Mth.sin(a);
            float vy = nY * Mth.cos(a) + bY * Mth.sin(a);
            float vz = nZ * Mth.cos(a) + bZ * Mth.sin(a);
            if (!onBridge && vx * ox + vz * oz < -0.35F) {
                continue;
            }
            // 板の横の向き = 接線 × v
            float wx = ty * vz - tz * vy;
            float wy = tz * vx - tx * vz;
            float wz = tx * vy - ty * vx;
            float wid = (rad * 2.2F + 0.12F) / 2.0F;
            float off = rad + 0.025F;
            float mx = x + vx * off;
            float my = y + vy * off;
            float mz = z + vz * off;
            float hl = len / 2.0F;
            float fluff = 0.06F + random.nextFloat() * 0.06F;
            float shade = 0.8F + random.nextFloat() * 0.2F;
            // 上の縁(接線の +)は管に付け、下の縁(接線の −)は外へ fluff だけ反らせる
            out.add(new float[]{
                    mx - wx * wid - tx * hl + vx * fluff, my - wy * wid - ty * hl + vy * fluff, mz - wz * wid - tz * hl + vz * fluff, 0.5F, 1.0F,
                    mx + wx * wid - tx * hl + vx * fluff, my + wy * wid - ty * hl + vy * fluff, mz + wz * wid - tz * hl + vz * fluff, 1.0F, 1.0F,
                    mx + wx * wid + tx * hl, my + wy * wid + ty * hl, mz + wz * wid + tz * hl, 1.0F, 0.0F,
                    mx - wx * wid + tx * hl, my - wy * wid + ty * hl, mz - wz * wid + tz * hl, 0.5F, 0.0F,
                    index, shade});
        }
        // 外へ起きた房を 1 枚
        addMoss(out, index, x, y, z, ox, oz, tx, ty, tz, rad, onBridge, random, 0.7F);
    }

    private static void addMoss(List<float[]> out, int index, float x, float y, float z, float ox, float oz,
            float tx, float ty, float tz, float rad, boolean onBridge, RandomSource random, float sizeScale) {
        float size = MOSS_SIZE * sizeScale * (0.8F + random.nextFloat() * 0.4F);
        float half = size / 2.0F;
        // 幅の向き = 外向きと接線の両方に直交(水平の外向き n と接線 t の外積)
        float wx = ty * oz;
        float wy = tz * ox - tx * oz;
        float wz = -ty * ox;
        float wl = Mth.sqrt(wx * wx + wy * wy + wz * wz);
        if (wl < 1.0E-4F) {
            wx = -oz;
            wy = 0.0F;
            wz = ox;
            wl = 1.0F;
        }
        wx = wx / wl * half;
        wy = wy / wl * half;
        wz = wz / wl * half;
        float lx = tx * half;
        float ly = ty * half;
        float lz = tz * half;
        float lift = onBridge ? 0.0F : rad + 0.03F;
        float mx = x + ox * lift;
        float my = y + (onBridge ? rad + 0.02F : 0.0F);
        float mz = z + oz * lift;
        if (onBridge) {
            // 渡る区間では上に乗る板(幅は水平の横向き)
            wx = -tz;
            wy = 0.0F;
            wz = tx;
            wl = Math.max(1.0E-4F, Mth.sqrt(wx * wx + wz * wz));
            wx = wx / wl * half;
            wz = wz / wl * half;
        }
        float shade = 0.85F + random.nextFloat() * 0.15F;
        out.add(new float[]{
                mx - wx - lx, my - wy - ly, mz - wz - lz, 0.5F, 1.0F,
                mx + wx - lx, my + wy - ly, mz + wz - lz, 1.0F, 1.0F,
                mx + wx + lx, my + wy + ly, mz + wz + lz, 1.0F, 0.0F,
                mx - wx + lx, my - wy + ly, mz - wz + lz, 0.5F, 0.0F,
                index, shade});
        // 起きた板: 中心から外向き(渡る区間では上)へ
        float ux = onBridge ? 0.0F : ox * size * 0.6F;
        float uy = onBridge ? size * 0.5F : size * 0.15F;
        float uz = onBridge ? 0.0F : oz * size * 0.6F;
        out.add(new float[]{
                mx - lx, my - ly, mz - lz, 0.5F, 1.0F,
                mx + lx, my + ly, mz + lz, 1.0F, 1.0F,
                mx + lx + ux, my + ly + uy, mz + lz + uz, 1.0F, 0.0F,
                mx - lx + ux, my - ly + uy, mz - lz + uz, 0.5F, 0.0F,
                index, shade * 0.95F});
    }

    private static float sq(float v) {
        return v * v;
    }

    private static float smooth(float t) {
        return t * t * (3.0F - 2.0F * t);
    }
}
