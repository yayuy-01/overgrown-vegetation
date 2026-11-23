package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yayuy.overgrownvegetation.entity.FalcimnesRiftEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * 地走りの見た目(2026-11-05、同じ日の夜に描き直し)。ユーザーの像は<b>「エネルギーを地面に注ぎ込んで噴出させる」</b>
 * (最初の版は長方形の光る壁で、そう見えなかった)。
 * <ol>
 *     <li><b>注ぎ込む</b>: 刺した所に光が溜まり、まわりの輪が中へ縮んでいく(地面へ吸い込まれる)。帯が立つまで続く。</li>
 *     <li><b>地中を走る</b>: 地面の割れ目が光る。筋の形では、刺した所から相手の方へジグザグの割れ目が伸び、左右へ枝分かれする
 *     (先端は明るい点)。円の形(近くの相手)では、刺した所から放射状の割れ目が広がる。</li>
 *     <li><b>膨らむ</b>: 伸びきってから噴き出すまで、当たる場所(縁は不揃い)がじわじわ明るくなり脈打つ(「来る」の合図)。
 *     土がぱらぱら跳ねるのは粒(Entity 側)。</li>
 *     <li><b>噴き出す</b>: 当たる場所のあちこちから、根元が太く先が細い光の柱が高さも太さもばらばらに噴き上がる
 *     (筋は刺した側から、円は真ん中から順に 0〜3 tick ずれて)。揺らぎながら縮んで消える。土の塊と光の粒も噴き上がる(粒)。</li>
 * </ol>
 * 当たる場所(筋の幅・円の半径)は変えていない —— 光の柱と割れ目はその中に収まる。加算の光る板。色は鎌の光の筋と同じ地脈の黄緑。
 * 形のばらつきは Entity の id から決める(毎フレーム同じ形)。
 */
public class FalcimnesRiftRenderer extends EntityRenderer<FalcimnesRiftEntity> {

    static final int CORE = 0xD4FF3A, MID = 0xA8E62A, GLOW = 0x7CB51E, HOT = 0xF4FFC8;
    /** 光の柱どうしの間(筋の形、ブロック)。 */
    static final float JET_STEP = 0.5F;
    /** 噴き出しの時間差の最大(tick)。 */
    static final float JET_STAGGER = 3.0F;

    public FalcimnesRiftRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(FalcimnesRiftEntity e, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
        float t = e.tickCount + partialTick;
        float reached = e.reached(partialTick);
        if (reached <= 0.01F) return;
        int lineTicks = e.lineTicks(), bandStart = e.bandStart(), bandEnd = e.bandEnd();
        float fadeOut = t < bandEnd ? 1.0F : Mth.clamp(1.0F - (t - bandEnd) / FalcimnesRiftEntity.FADE_TICKS, 0.0F, 1.0F);
        if (fadeOut <= 0.01F) return;
        VertexConsumer vc = buffers.getBuffer(GlowRenderTypes.ADDITIVE);
        PoseStack.Pose p = pose.last();
        Ground g = new Ground(e);
        int seed = e.getId();

        // 割れ目の明るさ: 走る間 0.85、待つ間は脈打ちながら明るく、噴き出すと最大、そのあと薄れる。
        float crackA;
        float warn;   // 当たる場所の塗りの明るさ
        if (t < lineTicks) {
            crackA = 0.85F;
            warn = 0.06F;
        } else if (t < bandStart) {
            float k = (t - lineTicks) / Math.max(1.0F, bandStart - lineTicks);
            float pulse = 0.5F + 0.5F * Mth.sin(t * (1.2F + 1.4F * k));
            crackA = 0.7F + 0.3F * pulse;
            warn = 0.06F + 0.22F * k * (0.6F + 0.4F * pulse);
        } else {
            crackA = fadeOut;
            float since = t - bandStart;
            warn = (0.15F + 0.45F * Mth.clamp(1.0F - since / 4.0F, 0.0F, 1.0F)) * fadeOut;
        }

        // 1. 注ぎ込む(刺した所)。
        if (t < bandStart + 2) {
            float inA = t < bandStart ? 1.0F : Mth.clamp(1.0F - (t - bandStart) / 2.0F, 0.0F, 1.0F);
            float gy = g.y(0.0F, 0.0F);
            for (int k = 0; k < 2; k++) {
                float ph = t / 10.0F + k * 0.5F;
                float fr = ph - Mth.floor(ph);
                float rr = 1.8F * (1.0F - fr) + 0.2F;
                ring(vc, p, 0.0F, gy + 0.03F, 0.0F, rr, 0.06F + 0.05F * fr, MID, 0.75F * fr * inA);
            }
            float pulse = 0.5F + 0.5F * Mth.sin(t * 0.9F);
            disc(vc, p, 0.0F, gy + 0.035F, 0.0F, 0.55F + 0.15F * pulse, HOT, 0.8F * inA, 0.0F);
            disc(vc, p, 0.0F, gy + 0.03F, 0.0F, 1.2F + 0.2F * pulse, GLOW, 0.4F * inA, 0.0F);
        }

        if (e.burst()) {
            renderBurst(e, vc, p, g, seed, t, reached, bandStart, bandEnd, crackA, warn, fadeOut);
        } else {
            renderLine(e, vc, p, g, seed, t, reached, lineTicks, bandStart, bandEnd, crackA, warn, fadeOut);
        }
    }

    // ---------------------------------------------------------------- 筋の形

    private void renderLine(FalcimnesRiftEntity e, VertexConsumer vc, PoseStack.Pose p, Ground g, int seed, float t, float reached,
                            int lineTicks, int bandStart, int bandEnd, float crackA, float warn, float fadeOut) {
        Vec3 d = e.dir();
        float dx = (float) d.x, dz = (float) d.z, sx = -dz, sz = dx;
        float w = e.width(), half = w * 0.5F;
        // 当たる場所の塗り(縁は不揃い)。
        int n = Math.max(1, Mth.ceil(reached / 0.5F));
        for (int i = 0; i < n; i++) {
            float s0 = i * 0.5F, s1 = Math.min(reached, (i + 1) * 0.5F);
            float h0 = half * (0.8F + 0.2F * noise(seed + 9, s0)), h1 = half * (0.8F + 0.2F * noise(seed + 9, s1));
            float l0 = 0.15F * noise(seed + 3, s0) * half, l1 = 0.15F * noise(seed + 3, s1) * half;
            float y0 = g.yLine(e, s0, l0) + 0.02F, y1 = g.yLine(e, s1, l1) + 0.02F;
            ribbon(vc, p, dx * s0 + sx * l0, y0, dz * s0 + sz * l0, dx * s1 + sx * l1, y1, dz * s1 + sz * l1, sx, sz, h0, h1, GLOW, warn);
        }
        // 割れ目(本筋): ジグザグに、先端は細く明るい点。
        float step = 0.35F;
        int m = Math.max(1, Mth.ceil(reached / step));
        float px = 0, pz = 0, py = g.yLine(e, 0.0F, 0.0F) + 0.03F;
        for (int i = 1; i <= m; i++) {
            float s = Math.min(reached, i * step);
            float lat = half * 0.3F * noise(seed, s);
            float x = dx * s + sx * lat, z = dz * s + sz * lat, y = g.yLine(e, s, lat) + 0.03F;
            float taper = Mth.clamp((reached - s) / 1.2F, 0.35F, 1.0F) * (0.75F + 0.25F * noise(seed + 5, s * 2.0F));
            segment(vc, p, px, py, pz, x, y, z, 0.14F * taper, GLOW, 0.4F * crackA);
            segment(vc, p, px, py + 0.003F, pz, x, y + 0.003F, z, 0.045F * taper, CORE, 0.95F * crackA);
            px = x; pz = z; py = y;
        }
        if (t < lineTicks) disc(vc, p, px, py + 0.005F, pz, 0.28F, HOT, 0.9F, 0.0F);
        // 枝分かれ: 0.9 ブロックごとに、左右へ斜めに。当たる場所の縁まで。
        for (int k = 0; k * 0.9F < e.length(); k++) {
            float s0 = k * 0.9F + 0.4F * hash(seed, k * 3);
            if (s0 + 0.05F >= reached) break;
            int side = hash(seed, k * 3 + 1) < 0.5F ? -1 : 1;
            float len = Math.min(0.6F + 1.0F * hash(seed, k * 3 + 2), (reached - s0) * 1.5F);
            float lat0 = half * 0.3F * noise(seed, s0);
            float ang = (35.0F + 30.0F * hash(seed, k * 5 + 7)) * Mth.DEG_TO_RAD;
            float fx = Mth.cos(ang), fs = Mth.sin(ang) * side;
            float ax = s0, al = lat0;
            for (int j = 1; j <= 3; j++) {
                float kink = (j == 2 ? 0.25F * (hash(seed, k * 7 + j) - 0.5F) : 0.0F);
                float bs = s0 + fx * len * j / 3.0F, bl = Mth.clamp(lat0 + (fs + kink) * len * j / 3.0F, -half * 0.95F, half * 0.95F);
                float thin = 1.0F - 0.25F * j;
                float yA = g.yLine(e, ax, al) + 0.025F, yB = g.yLine(e, bs, bl) + 0.025F;
                segment(vc, p, dx * ax + sx * al, yA, dz * ax + sz * al, dx * bs + sx * bl, yB, dz * bs + sz * bl, 0.09F * thin, GLOW, 0.35F * crackA);
                segment(vc, p, dx * ax + sx * al, yA + 0.003F, dz * ax + sz * al, dx * bs + sx * bl, yB + 0.003F, dz * bs + sz * bl,
                        0.03F * thin, MID, 0.85F * crackA);
                ax = bs; al = bl;
            }
        }
        // 噴き出し。
        if (t >= bandStart) {
            float H = e.bandHeight();
            int jets = Math.max(1, Mth.ceil(e.length() / JET_STEP));
            for (int i = 0; i < jets; i++) {
                float s = Math.min(e.length(), (i + 0.5F) * JET_STEP + (hash(seed, i * 11) - 0.5F) * 0.3F);
                float lat = (hash(seed, i * 11 + 1) - 0.5F) * w * 0.8F;
                float delay = JET_STAGGER * s / Math.max(1.0F, e.length()) + hash(seed, i * 11 + 2) * 0.8F;
                float hgt = H * (0.5F + 0.65F * hash(seed, i * 11 + 3));
                float bw = w * (0.16F + 0.14F * hash(seed, i * 11 + 4));
                float leanS = (hash(seed, i * 11 + 5) - 0.5F) * 0.25F, leanL = (hash(seed, i * 11 + 6) - 0.5F) * 0.25F;
                jet(vc, p, dx * s + sx * lat, g.yLine(e, s, lat), dz * s + sz * lat, dx * leanS + sx * leanL, dz * leanS + sz * leanL,
                        hgt, bw, hash(seed, i * 11 + 7) * Mth.PI, t - bandStart - delay, bandEnd - bandStart, fadeOut, seed + i);
            }
        }
    }

    // ---------------------------------------------------------------- 円の形

    private void renderBurst(FalcimnesRiftEntity e, VertexConsumer vc, PoseStack.Pose p, Ground g, int seed, float t, float reached,
                             int bandStart, int bandEnd, float crackA, float warn, float fadeOut) {
        float r = e.length();
        float cy = g.y(0.0F, 0.0F);
        // 当たる場所の塗り(縁は不揃いな円)。
        int sec = 28;
        for (int i = 0; i < sec; i++) {
            float a0 = Mth.TWO_PI * i / sec, a1 = Mth.TWO_PI * (i + 1) / sec;
            float r0 = reached * (0.88F + 0.12F * noise(seed + 9, a0 * 2.0F)), r1 = reached * (0.88F + 0.12F * noise(seed + 9, a1 * 2.0F));
            float x0 = Mth.cos(a0) * r0, z0 = Mth.sin(a0) * r0, x1 = Mth.cos(a1) * r1, z1 = Mth.sin(a1) * r1;
            int al = a(warn), ae = a(warn * 0.5F);
            int cr = GLOW >> 16 & 255, cg = GLOW >> 8 & 255, cb = GLOW & 255;
            float y0 = g.y(x0, z0) + 0.02F, y1 = g.y(x1, z1) + 0.02F;
            quadColors(vc, p, 0.0F, cy + 0.02F, 0.0F, al, x0, y0, z0, ae, x1, y1, z1, ae, x1, y1, z1, ae, cr, cg, cb);
        }
        // 放射状の割れ目(9 本)と、それぞれ 1 本の枝。
        int cracks = 9;
        for (int c = 0; c < cracks; c++) {
            float base = Mth.TWO_PI * c / cracks + (hash(seed, c * 13) - 0.5F) * 0.5F;
            float len = r * (0.75F + 0.25F * hash(seed, c * 13 + 1));
            float upTo = Math.min(len, reached);
            float px = 0, pz = 0, py = cy + 0.03F;
            float step = 0.35F;
            float forkAt = len * (0.35F + 0.3F * hash(seed, c * 13 + 2));
            float fx = 0, fz = 0, fy = 0, fAng = 0;
            boolean forkSet = false;
            for (int i = 1; i * step <= upTo + step - 1.0E-3F; i++) {
                float s = Math.min(upTo, i * step);
                float ang = base + 0.22F * noise(seed + c * 17, s * 1.3F) / (0.6F + s * 0.3F);
                float x = Mth.cos(ang) * s, z = Mth.sin(ang) * s, y = g.y(x, z) + 0.03F;
                float taper = Mth.clamp((upTo - s) / 1.0F, 0.35F, 1.0F) * Mth.clamp(1.2F - s / len * 0.6F, 0.4F, 1.0F);
                segment(vc, p, px, py, pz, x, y, z, 0.13F * taper, GLOW, 0.4F * crackA);
                segment(vc, p, px, py + 0.003F, pz, x, y + 0.003F, z, 0.042F * taper, CORE, 0.95F * crackA);
                if (!forkSet && s >= forkAt) {
                    forkSet = true;
                    fx = x; fz = z; fy = y; fAng = ang + (hash(seed, c * 13 + 3) < 0.5F ? -1.0F : 1.0F) * (0.5F + 0.4F * hash(seed, c * 13 + 4));
                }
                px = x; pz = z; py = y;
            }
            if (reached < r) disc(vc, p, px, py + 0.005F, pz, 0.2F, HOT, 0.85F, 0.0F);
            if (forkSet) {
                float fl = Math.min(0.8F + 0.8F * hash(seed, c * 13 + 5), Math.max(0.0F, reached - forkAt) * 1.2F);
                fl = Math.min(fl, Math.max(0.0F, r - Mth.sqrt(fx * fx + fz * fz)) + 0.3F);
                if (fl > 0.05F) {
                    float bx = fx + Mth.cos(fAng) * fl, bz = fz + Mth.sin(fAng) * fl, by = g.y(bx, bz) + 0.025F;
                    segment(vc, p, fx, fy, fz, bx, by, bz, 0.08F, GLOW, 0.35F * crackA);
                    segment(vc, p, fx, fy + 0.003F, fz, bx, by + 0.003F, bz, 0.028F, MID, 0.85F * crackA);
                }
            }
        }
        // 噴き出し: 真ん中から外へ、0.85 ブロックおきの輪に並べる。外の柱は少し外へ傾ける。
        if (t >= bandStart) {
            float H = e.bandHeight();
            int idx = 0;
            for (float rr = 0.0F; rr <= r - 0.2F; rr += 0.85F) {
                int count = rr < 0.1F ? 1 : Math.max(5, Mth.floor(Mth.TWO_PI * rr / 0.85F));
                for (int k = 0; k < count; k++, idx++) {
                    float ang = Mth.TWO_PI * (k + hash(seed, idx * 11) * 0.6F) / count;
                    float rad = Math.min(r - 0.15F, rr + (hash(seed, idx * 11 + 1) - 0.5F) * 0.5F);
                    if (rr < 0.1F) rad = 0.0F;
                    float x = Mth.cos(ang) * rad, z = Mth.sin(ang) * rad;
                    float delay = JET_STAGGER * rad / Math.max(1.0F, r) + hash(seed, idx * 11 + 2) * 0.8F;
                    float centre = 1.0F - 0.35F * rad / r;
                    float hgt = H * centre * (0.55F + 0.6F * hash(seed, idx * 11 + 3));
                    float bw = 0.22F + 0.2F * hash(seed, idx * 11 + 4) + (rr < 0.1F ? 0.25F : 0.0F);
                    float lean = 0.35F * rad / r;
                    jet(vc, p, x, g.y(x, z), z, Mth.cos(ang) * lean, Mth.sin(ang) * lean, hgt, bw, hash(seed, idx * 11 + 7) * Mth.PI,
                            t - bandStart - delay, bandEnd - bandStart, fadeOut, seed + idx);
                }
            }
        }
    }

    // ---------------------------------------------------------------- 部品

    /**
     * 光の柱を 1 本。根元(太さ {@code bw})から上へ細くなる 3 枚の交差した板(上の辺は透明)+ 内側の明るい芯。
     * {@code age} は噴き出してからの tick(負ならまだ)。2 tick で 1.15 倍まで伸び、揺らぎながら少し縮み、帯が消えると縮んで薄れる。
     */
    static void jet(VertexConsumer vc, PoseStack.Pose p, float x, float y, float z, float leanX, float leanZ, float height, float bw,
                    float rot, float age, int bandTicks, float fadeOut, int seed) {
        if (age <= 0.0F) return;
        float rise = age < 2.0F ? age / 2.0F * 1.15F : 1.15F - 0.15F * Mth.clamp((age - 2.0F) / 3.0F, 0.0F, 1.0F);
        float flicker = 0.88F + 0.12F * Mth.sin(age * 2.3F + seed * 1.7F);
        float h = height * rise * flicker * (0.6F + 0.4F * fadeOut);
        float a = fadeOut * (age < 1.0F ? 0.6F + 0.4F * age : 1.0F);
        if (h <= 0.05F || a <= 0.01F) return;
        float flash = Mth.clamp(1.0F - age / 3.0F, 0.0F, 1.0F);
        for (int k = 0; k < 3; k++) {
            float ang = rot + k * Mth.PI / 3.0F;
            float cx = Mth.cos(ang), cz = Mth.sin(ang);
            taperedPlane(vc, p, x, y, z, cx, cz, bw, bw * 0.18F, h, leanX * h, leanZ * h, GLOW, 0.32F * a);
            taperedPlane(vc, p, x, y, z, cx, cz, bw * 0.45F, bw * 0.06F, h * 0.85F, leanX * h * 0.85F, leanZ * h * 0.85F, CORE, 0.55F * a);
            taperedPlane(vc, p, x, y, z, cx, cz, bw * 0.2F, 0.0F, h * 0.45F, leanX * h * 0.45F, leanZ * h * 0.45F, HOT, (0.45F + 0.4F * flash) * a);
        }
        disc(vc, p, x, y + 0.04F, z, bw * 1.3F, CORE, 0.5F * a * (0.5F + 0.5F * flash), 0.0F);
    }

    /** 縦の板(根元の半幅 {@code b0}、上の半幅 {@code b1}、上は透明)。上の辺は {@code (ox, oz)} ずれる(傾き)。両面。 */
    static void taperedPlane(VertexConsumer vc, PoseStack.Pose p, float x, float y, float z, float cx, float cz, float b0, float b1,
                             float h, float ox, float oz, int rgb, float alpha) {
        int r = rgb >> 16 & 255, g = rgb >> 8 & 255, b = rgb & 255, al = a(alpha);
        if (al <= 0) return;
        quadColors(vc, p, x - cx * b0, y, z - cz * b0, al, x + cx * b0, y, z + cz * b0, al,
                x + ox + cx * b1, y + h, z + oz + cz * b1, 0, x + ox - cx * b1, y + h, z + oz - cz * b1, 0, r, g, b);
    }

    /** 地面に平らに置く円(真ん中が濃く縁が透明)。{@code hole} は真ん中の抜き(0 なら塗りつぶし)。 */
    static void disc(VertexConsumer vc, PoseStack.Pose p, float x, float y, float z, float radius, int rgb, float alpha, float hole) {
        int r = rgb >> 16 & 255, g = rgb >> 8 & 255, b = rgb & 255, al = a(alpha);
        if (al <= 0) return;
        int sec = 12;
        for (int i = 0; i < sec; i++) {
            float a0 = Mth.TWO_PI * i / sec, a1 = Mth.TWO_PI * (i + 1) / sec;
            float x0 = x + Mth.cos(a0) * radius, z0 = z + Mth.sin(a0) * radius, x1 = x + Mth.cos(a1) * radius, z1 = z + Mth.sin(a1) * radius;
            quadColors(vc, p, x, y, z, al, x0, y, z0, 0, x1, y, z1, 0, x1, y, z1, 0, r, g, b);
        }
    }

    /** 地面に平らに置く輪(半径 {@code radius}、太さ {@code half} × 2。外と内の縁は透明)。 */
    static void ring(VertexConsumer vc, PoseStack.Pose p, float x, float y, float z, float radius, float half, int rgb, float alpha) {
        int r = rgb >> 16 & 255, g = rgb >> 8 & 255, b = rgb & 255, al = a(alpha);
        if (al <= 0) return;
        int sec = 24;
        for (int i = 0; i < sec; i++) {
            float a0 = Mth.TWO_PI * i / sec, a1 = Mth.TWO_PI * (i + 1) / sec;
            float c0 = Mth.cos(a0), s0 = Mth.sin(a0), c1 = Mth.cos(a1), s1 = Mth.sin(a1);
            float ri = Math.max(0.0F, radius - half), ro = radius + half;
            quadColors(vc, p, x + c0 * ri, y, z + s0 * ri, 0, x + c1 * ri, y, z + s1 * ri, 0, x + c1 * radius, y, z + s1 * radius, al,
                    x + c0 * radius, y, z + s0 * radius, al, r, g, b);
            quadColors(vc, p, x + c0 * radius, y, z + s0 * radius, al, x + c1 * radius, y, z + s1 * radius, al, x + c1 * ro, y, z + s1 * ro, 0,
                    x + c0 * ro, y, z + s0 * ro, 0, r, g, b);
        }
    }

    /** 地面に沿う帯の 1 区間(中心線 a → b、横 {@code (sx, sz)} へ半幅 {@code h0}・{@code h1}、縁は透明)。 */
    static void ribbon(VertexConsumer vc, PoseStack.Pose p, float ax, float ay, float az, float bx, float by, float bz, float sx, float sz,
                       float h0, float h1, int rgb, float alpha) {
        int r = rgb >> 16 & 255, g = rgb >> 8 & 255, b = rgb & 255, al = a(alpha);
        if (al <= 0) return;
        quadColors(vc, p, ax, ay, az, al, bx, by, bz, al, bx + sx * h1, by, bz + sz * h1, 0, ax + sx * h0, ay, az + sz * h0, 0, r, g, b);
        quadColors(vc, p, ax - sx * h0, ay, az - sz * h0, 0, bx - sx * h1, by, bz - sz * h1, 0, bx, by, bz, al, ax, ay, az, al, r, g, b);
    }

    /** 細い平らな線(幅 {@code half} × 2)。 */
    static void segment(VertexConsumer vc, PoseStack.Pose p, float x0, float y0, float z0, float x1, float y1, float z1, float half, int rgb, float alpha) {
        float lx = x1 - x0, lz = z1 - z0, l = Mth.sqrt(lx * lx + lz * lz);
        if (l < 1.0E-4F) return;
        float nx = -lz / l * half, nz = lx / l * half;
        FalcimnesWaveRenderer.quad(vc, p, x0 + nx, y0, z0 + nz, x1 + nx, y1, z1 + nz, x1 - nx, y1, z1 - nz, x0 - nx, y0, z0 - nz,
                rgb >> 16 & 255, rgb >> 8 & 255, rgb & 255, a(alpha));
    }

    /** 頂点ごとに透明度の違う四角(両面)。 */
    static void quadColors(VertexConsumer vc, PoseStack.Pose p, float ax, float ay, float az, int aa, float bx, float by, float bz, int ba,
                           float cx, float cy, float cz, int ca, float dx, float dy, float dz, int da, int r, int g, int b) {
        vc.vertex(p.pose(), ax, ay, az).color(r, g, b, aa).endVertex();
        vc.vertex(p.pose(), bx, by, bz).color(r, g, b, ba).endVertex();
        vc.vertex(p.pose(), cx, cy, cz).color(r, g, b, ca).endVertex();
        vc.vertex(p.pose(), dx, dy, dz).color(r, g, b, da).endVertex();
        vc.vertex(p.pose(), dx, dy, dz).color(r, g, b, da).endVertex();
        vc.vertex(p.pose(), cx, cy, cz).color(r, g, b, ca).endVertex();
        vc.vertex(p.pose(), bx, by, bz).color(r, g, b, ba).endVertex();
        vc.vertex(p.pose(), ax, ay, az).color(r, g, b, aa).endVertex();
    }

    static int a(float alpha) {
        return (int) (Mth.clamp(alpha, 0.0F, 1.0F) * 255.0F);
    }

    /** 0〜1 の決まった乱数(同じ引数なら毎フレーム同じ)。 */
    static float hash(int a, int b) {
        int h = a * 0x9E3779B1 ^ b * 0x85EBCA77;
        h ^= h >>> 15;
        h *= 0x2C1B3C6D;
        h ^= h >>> 12;
        h *= 0x297A2D39;
        h ^= h >>> 15;
        return (h & 0xFFFFFF) / 16777216.0F;
    }

    /** なめらかな -1〜1 のゆらぎ。 */
    static float noise(int seed, float x) {
        float p1 = hash(seed, 101) * Mth.TWO_PI, p2 = hash(seed, 202) * Mth.TWO_PI;
        return 0.6F * Mth.sin(x * 1.7F + p1) + 0.4F * Mth.sin(x * 3.9F + p2);
    }

    /** 地面の高さ(Entity の高さから測る)。同じフレームで何度も引くので、ブロックの列ごとに覚える。 */
    static final class Ground {
        private final FalcimnesRiftEntity e;
        private final Level level;
        private final double ex, ey, ez;
        private final it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap cache = new it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap();

        Ground(FalcimnesRiftEntity e) {
            this.e = e;
            this.level = e.level();
            this.ex = e.getX();
            this.ey = e.getY();
            this.ez = e.getZ();
            this.cache.defaultReturnValue(Float.NaN);
        }

        /** Entity からの {@code (x, z)} の地面の高さ(Entity の高さから)。 */
        float y(float x, float z) {
            double wx = this.ex + x, wz = this.ez + z;
            long key = ((long) Mth.floor(wx) << 32) ^ (Mth.floor(wz) & 0xFFFFFFFFL);
            float v = this.cache.get(key);
            if (Float.isNaN(v)) {
                v = (float) (this.e.groundAt(this.level, wx, wz) - this.ey);
                this.cache.put(key, v);
            }
            return v;
        }

        /** 筋の {@code s} ブロック先・横 {@code lat} の地面の高さ。 */
        float yLine(FalcimnesRiftEntity e, float s, float lat) {
            Vec3 d = e.dir();
            return y((float) (d.x * s - d.z * lat), (float) (d.z * s + d.x * lat));
        }
    }

    @Override
    public boolean shouldRender(FalcimnesRiftEntity e, net.minecraft.client.renderer.culling.Frustum frustum, double x, double y, double z) {
        return frustum.isVisible(e.getBoundingBoxForCulling());
    }

    @Override
    public ResourceLocation getTextureLocation(FalcimnesRiftEntity entity) {
        return TextureAtlas.LOCATION_BLOCKS;
    }
}
