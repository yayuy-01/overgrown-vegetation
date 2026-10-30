package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.sky.CloudField;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 尾流雲(2026-10-18。空の世界の順番 4、ユーザー選択「乳房雲・尾流雲・入道雲」)。雲の底から垂れて、地面に届く前に消える雨や氷の筋。
 *
 * <ul>
 *   <li>金床の下: 育った金床({@link CloudField#anvil})の張り出しの下の面から、金床の 0.25〜0.5 倍の長さで垂れる(ふつうの金床で 100〜350、巨大な金床で 400〜1000)。
 *       雲ごとに 9〜18 本({@link #anvilStreaks})。塔のすぐ脇からは出さない。</li>
 *   <li>雲の底: 育ちかけの積乱雲(育ち 0.45〜0.8。本当の雨の柱 {@link Thunderstorms} が出る前)と、育ちきった入道雲の 3 割の底(y 300)から、50〜140 垂れる。
 *       雲の海の下(地上)から見える。</li>
 *   <li>筋は下ほど風上へ遅れて曲がる(下の空気の方が遅い = 鉤の形)。下へ行くほど細い糸に分かれ、糸ごとに違う長さで消える(シェーダー {@code virga})。</li>
 *   <li>雲と同じ遠く・近くの 2 回の描画の中で、雲を塗った後に奥行きを書かずに重ねる(手前の雲には隠れ、奥の雲の手前に見える)。雲と同じく地平の丸みで沈め、
 *       雲と同じ距離で薄れる(遠景が見えていない間は積乱雲が見える距離まで)。</li>
 * </ul>
 * 数値はすべて仮決め。設定 {@code virga}(0〜1)。
 */
final class Virga {
    /** 積乱雲を探す範囲(場の座標、カメラから。遠景の端まで)。 */
    static final double RANGE = 8000.0;
    /** 筋 1 本の縦の段の数(曲がりを出す)。 */
    static final int ROWS = 10;
    static final float ANVIL_ALPHA = 0.5F;
    static final float BASE_ALPHA = 0.3F;

    private static List<CloudField.Storm> storms = List.of();
    private static long lastQuery = Long.MIN_VALUE / 4;
    private static double builtAt = Double.NaN;
    private static int quads;
    private static VertexBuffer vbo;
    private static boolean broken;
    private static final boolean DEV_LOG = Boolean.getBoolean("ov.devbridge");

    static float setting() {
        return OvergrownVegetationConfig.COMMON.virga.get().floatValue();
    }

    /** 雲の描画の中から(遠く・近くの 2 回。split = 遠くと近くの境の距離)。形は描く時刻が変わったときだけ組み直す。 */
    static void draw(ClientLevel level, float partialTick, Matrix4f modelView, Matrix4f projection, double camX, double camY, double camZ,
                     boolean far, float split) {
        ShaderInstance shader = ModShaders.virga;
        float setting = setting();
        if (broken || shader == null || setting <= 0.0F) {
            return;
        }
        try {
            long now = level.getGameTime();
            if (now - lastQuery >= 40 || now < lastQuery) {
                lastQuery = now;
                float coverage = VoxelClouds.coverage(level, 1.0F);
                double fx = CloudField.fieldX(camX, now, 1.0F);
                double fz = CloudField.fieldZ(camZ, now, 1.0F);
                storms = VoxelClouds.storms(level, 1.0F, coverage, fx - RANGE, fz - RANGE, fx + RANGE, fz + RANGE);
            }
            double t = now + (double) partialTick;
            if (t != builtAt) {
                builtAt = t;
                build(level, partialTick, t, camX, camY, camZ, setting);
            }
            if (DEV_LOG && now % 100 == 0 && partialTick < 0.5F && far) {
                OvergrownVegetation.LOGGER.info("Virga 筋の面 {} 積乱雲 {} 金床の筋の覚え {}", quads, storms.size(), STREAKS.size());
            }
            if (quads == 0) {
                return;
            }
            Vec3 sky = level.getSkyColor(new Vec3(camX, camY, camZ), partialTick);
            Vec3 sunDir = CanopyLight.sunDirection(level, partialTick);
            boolean moon = sunDir.y < 0.0;
            Vec3 light = moon ? sunDir.scale(-1.0) : sunDir;
            float strength = CanopyLight.lightStrength(level, light, partialTick);
            float warm = moon ? 0.0F : 1.0F - CanopyLight.smooth(Mth.clamp((float) sunDir.y / 0.35F, 0.0F, 1.0F));
            float inside = CloudInterior.amount(partialTick) * CloudInterior.setting();
            shader.safeGetUniform("Split").set(split, far ? 1.0F : -1.0F, VoxelClouds.FAR_NEAR, VoxelClouds.FAR_FAR);
            shader.safeGetUniform("Curve").set(OvergrownVegetationConfig.COMMON.cloudCurvature.get().floatValue(), VoxelClouds.CURVE_FROM,
                    0.5F / VoxelClouds.CURVE_RADIUS, 0.0F);
            shader.safeGetUniform("Haze").set(Math.min(1.0F, (float) sky.x * 0.9F), Math.min(1.0F, (float) sky.y * 0.97F), Math.min(1.0F, (float) sky.z * 1.1F),
                    OvergrownVegetationConfig.COMMON.cloudBlueHaze.get().floatValue());
            shader.safeGetUniform("SunDir").set((float) light.x, (float) light.y, (float) light.z, strength);
            shader.safeGetUniform("SunColor").set(moon ? CanopyLight.MOON_R : 1.0F, moon ? CanopyLight.MOON_G : Mth.lerp(warm, 0.97F, 0.62F),
                    moon ? CanopyLight.MOON_B : Mth.lerp(warm, 0.9F, 0.35F), 1.0F);
            shader.safeGetUniform("Fade").set(Mth.clamp((inside - 0.1F) / 0.5F, 0.0F, 1.0F), 0.0F, 0.0F, 0.0F);
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.disableCull();
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            vbo.bind();
            vbo.drawWithShader(modelView, projection, shader);
            VertexBuffer.unbind();
            RenderSystem.enableCull();
            RenderSystem.depthMask(true);
            RenderSystem.disableBlend();
        } catch (RuntimeException e) {
            broken = true;
            OvergrownVegetation.LOGGER.error("尾流雲で例外が出たので、このセッションでは描かない", e);
        }
    }

    /** 筋を組んで VBO へ送る(頂点はカメラからの位置)。 */
    private static void build(ClientLevel level, float partialTick, double t, double camX, double camY, double camZ, float setting) {
        quads = 0;
        if (storms.isEmpty()) {
            return;
        }
        double ox = CloudField.offsetX(t);
        double oz = CloudField.offsetZ(t);
        double wx = CloudField.windX(t);
        double wz = CloudField.windZ(t);
        double wl = Math.max(1.0E-6, Math.sqrt(wx * wx + wz * wz));
        wx /= wl;
        wz /= wl;
        Vec3 cloud = SkyLight.cloudColor(level, partialTick, level.getCloudColor(partialTick));
        double fx = camX - ox;
        double fz = camZ - oz;
        ByteBufferBuilder buf = new ByteBufferBuilder(16384);
        try {
            BufferBuilder b = new BufferBuilder(buf, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            int seed = 0;
            for (CloudField.Storm s : storms) {
                if (s.kind() == 1) {
                    continue;
                }
                RandomSource r = RandomSource.create(s.id() ^ 0x71A6AL);
                CloudField.Anvil an = CloudField.anvil(s);
                if (an != null && an.growth() > 0.3F) {
                    float grow = Mth.clamp((an.growth() - 0.3F) / 0.4F, 0.0F, 1.0F);
                    float c = 0.86F;
                    for (Streak k : anvilStreaks(s, an)) {
                        float shown = shown(Math.hypot(k.x - fx, k.z - fz), s.giant());
                        if (shown <= 0.01F) {
                            continue;
                        }
                        strip(b, k.x + ox - camX, k.y - camY, k.z + oz - camZ, k.len, k.width, -wx * k.hook, -wz * k.hook, seed++ & 255,
                                (float) cloud.x * c + 0.02F, (float) cloud.y * c + 0.03F, (float) cloud.z * c + 0.06F, k.alpha * grow * shown * setting);
                    }
                }
                // 雲の底: 育ちかけの積乱雲と、育ちきった入道雲の 3 割。
                float w;
                if (s.congestus()) {
                    w = r.nextFloat() < 0.35F ? CanopyLight.smooth(Mth.clamp((s.maturity() - 0.85F) / 0.1F, 0.0F, 1.0F)) : 0.0F;
                } else {
                    w = CanopyLight.smooth(Mth.clamp((s.maturity() - 0.45F) / 0.1F, 0.0F, 1.0F))
                            * (1.0F - CanopyLight.smooth(Mth.clamp((s.maturity() - 0.7F) / 0.1F, 0.0F, 1.0F)));
                }
                if (w <= 0.01F) {
                    continue;
                }
                float body = s.bodyRadius();
                int n = 3 + r.nextInt(4);
                for (int i = 0; i < n; i++) {
                    double a = r.nextDouble() * Math.PI * 2.0;
                    double d = body * 0.6 * Math.sqrt(r.nextDouble());
                    double px = s.x() + Math.cos(a) * d;
                    double pz = s.z() + Math.sin(a) * d;
                    double len = 50.0 + 90.0 * r.nextDouble();
                    float width = body * (0.25F + 0.15F * r.nextFloat());
                    double hook = len * (0.2 + 0.25 * r.nextDouble());
                    float shown = shown(Math.hypot(px - fx, pz - fz), s.giant());
                    if (shown <= 0.01F) {
                        continue;
                    }
                    // 雲の底の陰の色に合わせて暗めに(地上から見上げると、明るい筋が暗い底の上の引っかき傷に見えた)。
                    float c = 0.58F;
                    strip(b, px + ox - camX, CloudField.LOW_BASE + 4.0 - camY, pz + oz - camZ, len, width, -wx * hook, -wz * hook, seed++ & 255,
                            (float) cloud.x * c, (float) cloud.y * c + 0.01F, (float) cloud.z * c + 0.03F, BASE_ALPHA * w * shown * setting);
                }
            }
            if (quads == 0) {
                return;
            }
            MeshData built = b.build();
            if (built == null) {
                quads = 0;
                return;
            }
            if (vbo == null) {
                vbo = new VertexBuffer(VertexBuffer.Usage.DYNAMIC);
            }
            vbo.bind();
            vbo.upload(built);
            VertexBuffer.unbind();
        } finally {
            buf.close();
        }
    }

    /** 金床の下の筋 1 本(場の座標。y = 垂れ始める高さ = 金床の下の面)。 */
    private record Streak(double x, double y, double z, double len, float width, double hook, float alpha) {
    }

    private static final Map<Long, List<Streak>> STREAKS = new HashMap<>();

    /**
     * 金床の下の筋(雲の id と育ちで決まるので覚えておく): 金床の真ん中から 0.45〜0.92 倍の所(張り出し)に 7〜16 本。垂れ始めは金床の下の面
     * ({@link CloudField#underside}。最初は平らな底の高さから垂らしたが、金床の下は塔から広がる漏斗の斜面で、筋のほとんどが雲の中に埋もれた)。
     * 漏斗の下の 45%(塔の横腹)からは出さない。
     */
    private static List<Streak> anvilStreaks(CloudField.Storm s, CloudField.Anvil an) {
        long key = s.id() * 31L + Math.round(s.maturity() * 32.0F);
        List<Streak> cached = STREAKS.get(key);
        if (cached != null) {
            return cached;
        }
        if (STREAKS.size() > 256) {
            STREAKS.clear();
        }
        RandomSource r = RandomSource.create(s.id() ^ 0x71A6AL);
        List<CloudField.Puff> puffs = CloudField.puffs(s);
        float big = an.radius();
        double neckFrom = CloudField.LOW_BASE + (s.topY() - CloudField.LOW_BASE) * 0.55;
        double minY = neckFrom + 0.45 * (an.floor() + 8.0 - neckFrom);
        double step = Math.max(6.0, big * 0.012);
        int n = (s.giant() ? 13 : 9) + r.nextInt(6);
        List<Streak> list = new ArrayList<>();
        for (int i = 0; i < n * 3 && list.size() < n; i++) {
            double a = r.nextDouble() * Math.PI * 2.0;
            double d = big * (0.45 + 0.47 * Math.sqrt(r.nextDouble()));
            double px = an.x() + Math.cos(a) * d;
            double pz = an.z() + Math.sin(a) * d;
            double len = big * (0.25 + 0.25 * r.nextDouble());
            if (s.giant()) {
                len = Math.min(len, 1000.0);
            }
            float width = big * (0.08F + 0.06F * r.nextFloat());
            double hook = len * (0.25 + 0.25 * r.nextDouble());
            float alpha = ANVIL_ALPHA * (0.7F + 0.3F * r.nextFloat());
            double under = CloudField.underside(puffs, px, pz, an.y(), minY, step);
            if (Double.isNaN(under)) {
                continue;
            }
            list.add(new Streak(px, under + 4.0, pz, len, width, hook, alpha));
        }
        List<Streak> done = List.copyOf(list);
        STREAKS.put(key, done);
        return done;
    }

    /**
     * 雲の見える度合い(雲と同じ距離で薄れる): 遠景が見えている間は遠景の端まで、見えていない間は積乱雲が見える距離まで
     * ({@link VoxelClouds} の StormFade・Far と揃える)。
     */
    private static float shown(double d, boolean giant) {
        boolean floorShown = OvergrownVegetationConfig.COMMON.cumulonimbusStyle.get() >= CloudField.STYLE_VALLEY;
        float from = giant && !floorShown ? VoxelClouds.GIANT_FADE_FROM : VoxelClouds.STORM_FADE_FROM;
        float to = giant && !floorShown ? VoxelClouds.GIANT_FADE_TO : VoxelClouds.STORM_FADE_TO;
        float near = 1.0F - CanopyLight.smooth(Mth.clamp((float) ((d - from) / (to - from)), 0.0F, 1.0F));
        float farShow = VoxelClouds.farSetting() * VoxelClouds.farAlpha();
        float farFade = farShow * (1.0F - CanopyLight.smooth(Mth.clamp((float) ((d - VoxelClouds.farFadeFrom())
                / (VoxelClouds.farFadeTo() - VoxelClouds.farFadeFrom())), 0.0F, 1.0F)));
        return Math.max(near, farFade);
    }

    /**
     * 筋 1 本: 上の点 (x, y, z)(カメラから)から下へ len、下ほど (hookX, hookZ) へ t² で遅れる曲線を軸に、カメラへ向けた帯。
     * 幅は下ほど少し広がる(落ちながら散る)。UV = (seed + 横 0〜1, 縦 0〜1)。
     */
    private static void strip(BufferBuilder b, double x, double y, double z, double len, float width, double hookX, double hookZ, int seed,
                              float r, float g, float bl, float alpha) {
        if (alpha <= 0.003F) {
            return;
        }
        double[] lx = new double[ROWS + 1];
        double[] ly = new double[ROWS + 1];
        double[] lz = new double[ROWS + 1];
        double[] rx = new double[ROWS + 1];
        double[] ry = new double[ROWS + 1];
        double[] rz = new double[ROWS + 1];
        for (int i = 0; i <= ROWS; i++) {
            double t = i / (double) ROWS;
            double px = x + hookX * t * t;
            double py = y - len * t;
            double pz = z + hookZ * t * t;
            // 軸の向き(曲線の接線)とカメラへの向きの外積 = 横。
            double tx = 2.0 * hookX * t;
            double ty = -len;
            double tz = 2.0 * hookZ * t;
            double sx = ty * -pz - tz * -py;
            double sy = tz * -px - tx * -pz;
            double sz = tx * -py - ty * -px;
            double sl = Math.sqrt(sx * sx + sy * sy + sz * sz);
            if (sl < 1.0E-6) {
                return;
            }
            double half = width * 0.5 * (1.0 + 0.35 * t) / sl;
            lx[i] = px - sx * half;
            ly[i] = py - sy * half;
            lz[i] = pz - sz * half;
            rx[i] = px + sx * half;
            ry[i] = py + sy * half;
            rz[i] = pz + sz * half;
        }
        for (int i = 0; i < ROWS; i++) {
            float v0 = i / (float) ROWS;
            float v1 = (i + 1) / (float) ROWS;
            b.addVertex((float) lx[i], (float) ly[i], (float) lz[i]).setUv(seed, v0).setColor(r, g, bl, alpha);
            b.addVertex((float) rx[i], (float) ry[i], (float) rz[i]).setUv(seed + 0.999F, v0).setColor(r, g, bl, alpha);
            b.addVertex((float) rx[i + 1], (float) ry[i + 1], (float) rz[i + 1]).setUv(seed + 0.999F, v1).setColor(r, g, bl, alpha);
            b.addVertex((float) lx[i + 1], (float) ly[i + 1], (float) lz[i + 1]).setUv(seed, v1).setColor(r, g, bl, alpha);
            quads++;
        }
    }

    private Virga() {
    }
}
