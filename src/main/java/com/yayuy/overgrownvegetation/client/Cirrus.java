package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.sky.CloudField;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * 巻雲(2026-10-18。ideas.voxel_clouds の段取り (2)。ユーザー 10-16「y 700 には現実の高い空の筋のような細い雲(巻雲)を」、10-18「巻雲は y 1600 あたりに変更」、
 * 続けて 10-18「積乱雲の拡大に合わせて出る高度を上げる」「常に出ているわけではなく場所によって出たりでなかったりするように」)。
 *
 * <ul>
 *   <li>高さ {@link #HEIGHT} の、ボクセルにしない薄い板 1 枚(カメラのまわり {@link #RADIUS} ブロック四方の格子。自分のシェーダー {@code cirrus})。
 *       模様はシェーダーで描く: 巻雲の出る地域(大きさ 14000 の雑音で、半分ほどの地域には出ない)の中の大きなまとまり(日ごとの量で広さが変わる)の中に、風の向きに長く横に細い筋(大きさ 900 × 30・330 × 11・120 × 5)、
 *       筋はゆっくり曲がり(大きな雑音で横にずらす)、流れの先で淡くなる(馬の尾のような巻雲)。濃さは 2 段に寄せた手描きの塗り。</li>
 *   <li>高い所の風: 下の雲と同じ速さで(10-18 に 3 倍 → 1.5 倍 → 1 倍へ下げた)、向きは 20° ずらす(場の座標を別に持つ)。</li>
 *   <li>量は日ごと(無い日 3 割・少し 3 割 5 分・ふつう 2 割 5 分・多い日 1 割。日の境で 6 時間かけて移る)。設定 {@code cirrusAmount} で固定できる。</li>
 *   <li>色: 雲の色(夜は暗い)、太陽の方は明るく(前へ散る光)、朝焼け・夕焼けは強く染まる、荒天は灰色寄り、遠くは空の色へ溶けて消える(高さを上げた 10-18 から水平 12500 ブロックほどまで)。</li>
 *   <li>描く順: 積乱雲と同じ奥行きの仕組み(遠くは雲だけの奥行き、近くはいつもの奥行き。{@link VoxelClouds})の中で、雲の色を塗った後に奥行きを書かずに重ねる。
 *       手前の積乱雲には隠れ、上から見下ろすと積乱雲の手前に見える。雲の中では霧に沈む。</li>
 * </ul>
 * 高さ以外の数値はすべて仮決め。
 */
final class Cirrus {
    /**
     * 巻雲の高さ。10-18 ユーザー指定「y 1600 あたり」から、同じ日のユーザー依頼「積乱雲の拡大に合わせて出る高度を上げる」で、巨大な積乱雲の天辺(y 3000)と
     * 金床より上の y 3500 へ(値は仮決め。現実の巻雲も積乱雲の金床が広がる高さ = 対流圏の天井のあたり)。
     */
    static final float HEIGHT = 3500.0F;
    /** 板の半分の幅(ブロック)と格子の数(高さを上げたので、地平の方まで届くよう 6500 → 13000)。 */
    static final float RADIUS = 13000.0F;
    static final int GRID = 32;
    /** 高い所の風(下の雲に対する倍率と向きのずれ)。10-18 ユーザー「上空の巻雲の移動速度が速すぎる、もう少し遅く」で 3 → 1.5、続けて「もっと遅く」(空の巨大生物のセッション経由)で 1.0(値は仮決め)。 */
    static final double WIND_SCALE = 1.0;
    static final double WIND_TURN = Math.toRadians(20.0);
    /** 場の座標を float で渡すための折り返し(折り返す所で模様が一度だけずれる。巻雲の場は 1 秒 3 ブロックほどで流れる)。 */
    static final double PERIOD = 65536.0;

    private static VertexBuffer vbo;
    private static boolean broken;

    static float strength() {
        return OvergrownVegetationConfig.COMMON.cirrus.get().floatValue();
    }

    /** 今の巻雲の量(0〜1)。 */
    static float amount(ClientLevel level) {
        double override = OvergrownVegetationConfig.COMMON.cirrusAmount.get();
        if (override >= 0.0) {
            return (float) override;
        }
        double t = (level.getDayTime() + 6000.0) / 24000.0;
        long day = Mth.floor(t);
        double frac = t - day;
        if (frac < 0.125) {
            return Mth.lerp(smooth((float) (0.5 + frac / 0.25)), dayAmount(day - 1), dayAmount(day));
        }
        if (frac > 0.875) {
            return Mth.lerp(smooth((float) ((frac - 0.875) / 0.25)), dayAmount(day), dayAmount(day + 1));
        }
        return dayAmount(day);
    }

    private static float dayAmount(long day) {
        long h = day * 0x9E3779B97F4A7C15L + 0x0C1A5C1AL;
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= h >>> 31;
        float u = (h >>> 40) / (float) (1 << 24);
        float v = ((h >>> 16) & 0xFFFFFF) / (float) (1 << 24);
        if (u < 0.3F) {
            return 0.0F;
        }
        if (u < 0.65F) {
            return 0.25F + 0.2F * v;
        }
        if (u < 0.9F) {
            return 0.45F + 0.25F * v;
        }
        return 0.7F + 0.2F * v;
    }

    private static float smooth(float t) {
        return t * t * (3.0F - 2.0F * t);
    }

    /**
     * 巻雲を描く({@link VoxelClouds} の遠く・近くの描画の中から。split = 遠くと近くの境の距離)。奥行きは書かない。
     */
    static void draw(ClientLevel level, float partialTick, Matrix4f modelView, Matrix4f projection, double camX, double camY, double camZ,
                     boolean far, float split) {
        ShaderInstance shader = ModShaders.cirrus;
        float strength = strength();
        if (broken || shader == null || strength <= 0.0F) {
            return;
        }
        float amount = amount(level);
        // 巻雲と同じ高さでは板を真横から見て、目の高さに白い線が出る(10-18 遠景の確認で見つけた)ので、上下 60 ブロックで薄める。
        strength *= CanopyLight.smooth(Mth.clamp((float) (Math.abs(camY - HEIGHT) / 60.0), 0.0F, 1.0F));
        if (amount <= 0.001F || strength <= 0.001F) {
            return;
        }
        try {
            if (vbo == null) {
                build();
            }
            long gameTime = level.getGameTime();
            // 高い所の風 = 世界の風(10-18 から時刻で変わる)の WIND_SCALE 倍を 20° 回したもの。場のずれも同じ倍率と回転(閉じた式なので積み上げない)。
            // 筋の向き(模様の向き)はふだんの向きの風で固定する: 今の風の向きで模様を回すと、場の座標の原点(数万ブロック先)のまわりで模様ごと回り、
            // 向きが少し変わるだけで秒速数十ブロックで流れて見えた(10-18 ユーザー「上空の巻雲の移動速度が速すぎる」の本当の原因)。
            double t = (double) gameTime + partialTick;
            double cs = Math.cos(WIND_TURN);
            double sn = Math.sin(WIND_TURN);
            double cwx = CloudField.WIND_X * cs - CloudField.WIND_Z * sn;
            double cwz = CloudField.WIND_X * sn + CloudField.WIND_Z * cs;
            double ox = CloudField.offsetX(t) * WIND_SCALE;
            double oz = CloudField.offsetZ(t) * WIND_SCALE;
            double fx = camX - (ox * cs - oz * sn);
            double fz = camZ - (ox * sn + oz * cs);
            double wl = Math.sqrt(cwx * cwx + cwz * cwz);

            Vec3 cloud = SkyLight.cloudColor(level, partialTick, level.getCloudColor(partialTick));
            Vec3 sky = level.getSkyColor(new Vec3(camX, camY, camZ), partialTick);
            Vec3 sunDir = CanopyLight.sunDirection(level, partialTick);
            boolean moon = sunDir.y < 0.0;
            Vec3 light = moon ? sunDir.scale(-1.0) : sunDir;
            float lightStrength = CanopyLight.lightStrength(level, light, partialTick);
            float warm = moon ? 0.0F : 1.0F - CanopyLight.smooth(Mth.clamp((float) sunDir.y / 0.35F, 0.0F, 1.0F));
            float sr = moon ? CanopyLight.MOON_R : 1.0F;
            float sg = moon ? CanopyLight.MOON_G : Mth.lerp(warm, 0.97F, 0.62F);
            float sb = moon ? CanopyLight.MOON_B : Mth.lerp(warm, 0.9F, 0.35F);
            float[] sunrise = level.effects().getSunriseColor(level.getTimeOfDay(partialTick), partialTick);
            float inside = CloudInterior.amount(partialTick) * CloudInterior.setting();

            shader.safeGetUniform("Offset").set(0.0F, (float) (HEIGHT - camY), 0.0F);
            shader.safeGetUniform("Split").set(split, far ? 1.0F : -1.0F, VoxelClouds.FAR_NEAR, VoxelClouds.FAR_FAR);
            shader.safeGetUniform("Field").set((float) (fx - Math.floor(fx / PERIOD) * PERIOD), (float) (fz - Math.floor(fz / PERIOD) * PERIOD),
                    (float) (cwx / wl), (float) (cwz / wl));
            shader.safeGetUniform("CloudColor").set((float) cloud.x, (float) cloud.y, (float) cloud.z, 1.0F);
            shader.safeGetUniform("SunDir").set((float) light.x, (float) light.y, (float) light.z, lightStrength);
            shader.safeGetUniform("SunColor").set(sr, sg, sb, 1.0F);
            shader.safeGetUniform("SkyColor").set((float) sky.x, (float) sky.y, (float) sky.z, 1.0F);
            shader.safeGetUniform("SunFlat").set((float) sunDir.x, (float) sunDir.z, 0.0F, 0.0F);
            if (sunrise != null) {
                shader.safeGetUniform("Glow").set(sunrise[0], sunrise[1], sunrise[2], sunrise[3]);
            } else {
                shader.safeGetUniform("Glow").set(0.0F, 0.0F, 0.0F, 0.0F);
            }
            shader.safeGetUniform("Cirrus").set(amount, strength, Mth.clamp((inside - 0.1F) / 0.5F, 0.0F, 1.0F),
                    VoxelClouds.roughness(level, partialTick));

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
            com.yayuy.overgrownvegetation.OvergrownVegetation.LOGGER.error("巻雲で例外が出たので、このセッションでは描かない", e);
        }
    }

    /** 板の格子(カメラのまわり、高さ 0。シェーダーで高さとカメラからの位置に直す)。 */
    private static void build() {
        ByteBufferBuilder bytes = new ByteBufferBuilder(GRID * GRID * 4 * 12 + 64);
        try {
            BufferBuilder b = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
            float step = RADIUS * 2.0F / GRID;
            for (int j = 0; j < GRID; j++) {
                for (int i = 0; i < GRID; i++) {
                    float x0 = -RADIUS + i * step;
                    float z0 = -RADIUS + j * step;
                    b.addVertex(x0, 0.0F, z0);
                    b.addVertex(x0, 0.0F, z0 + step);
                    b.addVertex(x0 + step, 0.0F, z0 + step);
                    b.addVertex(x0 + step, 0.0F, z0);
                }
            }
            MeshData mesh = b.build();
            vbo = new VertexBuffer(VertexBuffer.Usage.STATIC);
            vbo.bind();
            vbo.upload(mesh);
            VertexBuffer.unbind();
        } finally {
            bytes.close();
        }
    }

    private Cirrus() {
    }
}
