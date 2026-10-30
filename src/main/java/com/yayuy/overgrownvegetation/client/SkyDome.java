package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.platform.GlStateManager;
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
import com.yayuy.overgrownvegetation.sky.CloudSky;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.List;

/**
 * 空の光(2026-10-18。ユーザーが選んだ「天頂の深い青」「太陽の光輪とまぶしさ」「雲の上の星空」「朝焼けの空の色の帯」「夜明け前の青い時間」)。
 * バニラの空(空の色の円盤・朝焼けの扇・太陽・月・星)の後に、カメラのまわりの半球を自分のシェーダー {@code sky_dome} で 2 回重ねる。
 *
 * <ul>
 *   <li>掛け算の回(昼): 天頂ほど深い群青へ(地平はバニラのまま)。太陽のまわり 10° は掛けない(太陽を暗くしない)。夜は掛けない(星を暗くしない)。</li>
 *   <li>足し算の回: 太陽の光輪(芯のまわりの強い光と広い淡い光)・朝焼けの帯(朝夕、地平の空を太陽の側ほど広く橙に、その上を薄い紫に)・
 *       青い時間(日の入りの後・日の出の前に空へ深い青を足す。{@link SkyLight#blueHour})・雲の上の星空(雲より高い所で、空と一緒に回る細かい星と淡い天の川)。</li>
 *   <li>まぶしさ: 太陽が画面に入ると、雲・地形の上からも光を足す(天気の後に、奥行きを見ないで)。雲・地形・積乱雲に隠れると消える(tick ごとに確かめてならす)。</li>
 * </ul>
 * 強さ: 天頂の青と朝焼けの帯は高空の晴天({@link HighSky})で全部、その下は森の霧の濃さの分だけ弱く、雨で消える。星は雲の上だけ。数値はすべて仮決め。
 * 設定 {@code skyLight}・{@code sunGlare}・{@code highStars}。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class SkyDome {
    private static VertexBuffer dome;
    /**
     * 掛け算の回(天頂の群青)は地平の少し下(見上げ sin −0.06)より上でしか色を変えないので、緯度の 7 段目(−11.25°)から上だけの球で描く
     * (2026-10-24、半透明の重なりの削減。全天の球を 2 回、地形より先に塗っていた。見下ろすと上の帯は画面の外で、走る画素が減る)。
     */
    private static VertexBuffer upperDome;
    private static final int UPPER_FROM_BAND = 7;
    private static boolean broken;
    private static float glare;
    private static float glareTarget;
    private static long ticks;

    private static float skySetting() {
        return OvergrownVegetationConfig.COMMON.skyLight.get().floatValue();
    }

    private static float glareSetting() {
        return OvergrownVegetationConfig.COMMON.sunGlare.get().floatValue();
    }

    private static boolean skyVisible(Minecraft mc, ClientLevel level, Camera camera) {
        return level.dimensionType().hasSkyLight() && level.effects().skyType() == DimensionSpecialEffects.SkyType.NORMAL
                && camera.getFluidInCamera() == FogType.NONE && !CaveMist.hideSky() && !CloudInterior.hideSky()
                && ModShaders.skyDome != null;
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRenderLevel(RenderLevelStageEvent event) {
        boolean sky = event.getStage() == RenderLevelStageEvent.Stage.AFTER_SKY;
        // まぶしさは天気の後。ただし「最高(ファビュラス)」では天気の段階の描き先が天気の層で、全天に α 1 で足すと合成で空が黒く塗られる
        // (層の α を不透明として重ねる)ので、合成の済んだ主画面へ描く AFTER_LEVEL に回す(10-23)。
        boolean weather = event.getStage() == (Minecraft.useShaderTransparency()
                ? RenderLevelStageEvent.Stage.AFTER_LEVEL : RenderLevelStageEvent.Stage.AFTER_WEATHER);
        if (!sky && !weather || broken) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || !skyVisible(mc, level, event.getCamera())) {
            return;
        }
        try {
            float pt = event.getPartialTick().getGameTimeDeltaPartialTick(false);
            if (sky) {
                drawSky(level, pt, event.getModelViewMatrix(), event.getProjectionMatrix(), event.getCamera());
                drawHorizon(level, pt, event.getModelViewMatrix(), event.getProjectionMatrix(), event.getCamera());
            } else {
                drawGlare(level, pt, event.getModelViewMatrix(), event.getProjectionMatrix());
            }
        } catch (RuntimeException e) {
            broken = true;
            OvergrownVegetation.LOGGER.error("空の光で例外が出たので、このセッションでは使わない", e);
        }
    }

    private static void drawSky(ClientLevel level, float pt, Matrix4f modelView, Matrix4f projection, Camera camera) {
        float setting = skySetting();
        float glareSet = glareSetting();
        float stars = OvergrownVegetationConfig.COMMON.highStars.get().floatValue();
        if (setting <= 0.0F && glareSet <= 0.0F && stars <= 0.0F) {
            return;
        }
        Vec3 sun = CanopyLight.sunDirection(level, pt);
        float rain = CloudSky.rawRain(level, pt);
        float clarity = HighSky.clarity();
        // 天頂の青・朝焼けの帯・青い時間の強さ(雲の上で全部、下は森の霧の分だけ弱く、雨で消える)。
        float scope = Math.max(clarity, 0.75F * (1.0F - CorruptionAtmosphere.forestFactor()));
        float sky = setting * scope * (1.0F - rain);
        float day = SkyLight.smooth(Mth.clamp((float) sun.y / 0.2F, 0.0F, 1.0F));
        float halo = glareSet * SkyLight.smooth(Mth.clamp(((float) sun.y + 0.04F) / 0.12F, 0.0F, 1.0F)) * (1.0F - rain);
        float starLight = stars * level.getStarBrightness(pt) * (1.0F - rain) * clarity;
        float[] sunrise = level.effects().getSunriseColor(level.getTimeOfDay(pt), pt);
        float blue = SkyLight.blueHour(level, pt) * sky;
        ShaderInstance shader = ModShaders.skyDome;
        Vec3 skyColor = level.getSkyColor(camera.getPosition(), pt);
        shader.safeGetUniform("SunDir").set((float) sun.x, (float) sun.y, (float) sun.z, halo);
        shader.safeGetUniform("SkyColor").set((float) skyColor.x, (float) skyColor.y, (float) skyColor.z, 1.0F);
        if (sunrise != null) {
            shader.safeGetUniform("Glow").set(sunrise[0], sunrise[1], sunrise[2], sunrise[3] * sky);
        } else {
            shader.safeGetUniform("Glow").set(0.0F, 0.0F, 0.0F, 0.0F);
        }
        shader.safeGetUniform("Light").set(sky * day, blue, starLight, 0.0F);
        // 高く上がるほど天頂の群青を濃く・低い所から(10-18 ユーザー選択)。
        shader.safeGetUniform("High").set(HighSky.altitude(), 0.0F, 0.0F, 0.0F);
        // 星は空と一緒に回る(バニラの星と同じ回し方の逆を、見る向きに掛ける)。
        float angle = level.getTimeOfDay(pt) * Mth.TWO_PI;
        shader.safeGetUniform("StarMat").set(new Matrix4f().rotateX(-angle).rotateY((float) Math.toRadians(90.0)));
        // 雲の上で見下ろしたとき、地平より下は雲の海に隠れるので描いてもよいが、地平の下は暗い円盤があるので少し下まで。
        if (sky * day > 0.001F) {
            draw(shader, modelView, projection, 0.0F, GlStateManager.SourceFactor.DST_COLOR, GlStateManager.DestFactor.ZERO);
        }
        if (halo > 0.001F || blue > 0.001F || starLight > 0.001F || sunrise != null && sky > 0.001F) {
            draw(shader, modelView, projection, 1.0F, GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE);
        }
    }

    /** 雲の海の天辺のだいたいの高さ(地平線の書き割りの高さを決める)。 */
    static final double SEA_REF_Y = 420.0;

    /**
     * 地平線の書き割り(2026-10-18、ユーザー選択「A+E と D」の D): 雲の上(高度 400〜460 で現れる)で、遠くの雲の先の地平線に、描いた雲の海と遠くの積乱雲の頭の帯を置く。
     * 向きだけで描く(無限に遠い)。高さは雲の海の丸み({@link VoxelClouds} の CURVE_*)から出した「雲の海の地平線」に合わせる。ボクセルの雲(遠景)はこの後に描くので、
     * 帯が見えるのは遠景の端の先と遠くの切れ目だけ。設定 {@code cloudHorizon}。数値は仮決め。
     */
    private static void drawHorizon(ClientLevel level, float pt, Matrix4f modelView, Matrix4f projection, Camera camera) {
        float setting = OvergrownVegetationConfig.COMMON.cloudHorizon.get().floatValue();
        double camY = camera.getPosition().y;
        float strength = setting * SkyLight.smooth(Mth.clamp((float) ((camY - 400.0) / 60.0), 0.0F, 1.0F));
        if (strength <= 0.002F || !VoxelClouds.active()) {
            return;
        }
        // 雲の海の地平線: 丸めた雲の海の天辺を見下ろす角度がいちばん浅くなる所(見上げ・見下ろしは y = tan / √(1 + tan²))。
        double h = Math.max(20.0, camY - SEA_REF_Y);
        float curve = OvergrownVegetationConfig.COMMON.cloudCurvature.get().floatValue();
        double best = -1.0E9;
        double bestD = 40000.0;
        for (double d = 1500.0; d <= 40000.0; d += 250.0) {
            double past = Math.max(d - VoxelClouds.CURVE_FROM, 0.0);
            double e = -(h + curve * past * past * 0.5 / VoxelClouds.CURVE_RADIUS) / d;
            if (e > best) {
                best = e;
                bestD = d;
            }
        }
        float horizonY = (float) (best / Math.sqrt(1.0 + best * best));
        // 遠くの積乱雲の頭の高さ(天辺 y 1100 ほどの塔が地平線の距離に立ったときの見かけの高さの 6 割)。
        float heads = (float) Math.min(0.12, 0.6 * (1500.0 - SEA_REF_Y) / bestD);
        Vec3 cloud = SkyLight.cloudColor(level, pt, level.getCloudColor(pt));
        Vec3 sun = CanopyLight.sunDirection(level, pt);
        float[] sunrise = level.effects().getSunriseColor(level.getTimeOfDay(pt), pt);
        ShaderInstance shader = ModShaders.skyDome;
        // かすみは地平の空の色(霧の色)へ。最初は空の色(天頂寄りの濃い青)にしたら、夕方に帯が濃い青の海のように浮いた。
        float[] fog = RenderSystem.getShaderFogColor();
        shader.safeGetUniform("SunDir").set((float) sun.x, (float) sun.y, (float) sun.z, 0.0F);
        shader.safeGetUniform("SkyColor").set(fog[0], fog[1], fog[2], 1.0F);
        if (sunrise != null) {
            shader.safeGetUniform("Glow").set(sunrise[0], sunrise[1], sunrise[2], sunrise[3]);
        } else {
            shader.safeGetUniform("Glow").set(0.0F, 0.0F, 0.0F, 0.0F);
        }
        shader.safeGetUniform("Band").set(horizonY, 0.12F, heads, strength);
        shader.safeGetUniform("BandColor").set((float) cloud.x, (float) cloud.y, (float) cloud.z, 0.35F);
        // 地平線の向きごとの雲の多さ(10-18、地域ごとの雲の多さ {@link CloudField#region}): 9000 ブロック先の地域を 16 方向で引き、晴れた地域の方は帯を薄く・頭を少なく。
        long gameTime = level.getGameTime();
        double fx = CloudField.fieldX(camera.getPosition().x, gameTime, pt);
        double fz = CloudField.fieldZ(camera.getPosition().z, gameTime, pt);
        float coverage = CloudSky.coverage(level, pt);
        float[] cov = new float[16];
        for (int i = 0; i < 16; i++) {
            double a = (i / 16.0 - 0.5) * Math.PI * 2.0;
            // 10-18 夜から その場所の量(0〜1、床の割合 × 0.9)で引く。
            float c = CloudField.regional(coverage, fx + Math.cos(a) * 9000.0, fz + Math.sin(a) * 9000.0);
            cov[i] = SkyLight.smooth(Mth.clamp(c / 0.75F, 0.0F, 1.0F));
        }
        for (int k = 0; k < 4; k++) {
            shader.safeGetUniform("BandCover" + k).set(cov[k * 4], cov[k * 4 + 1], cov[k * 4 + 2], cov[k * 4 + 3]);
        }
        // 10-18 夜(空の世界の順番 6): 高い所では、描いた雲の海を丸い雲の海の地平線で止める(地平線から目の高さの近くまでを雲の海で塗っていたので、
        // y 3300 では視野の 15° ほどの平らな白い帯になった)。地平線の上は空。遠景の外の輪(y 950 から)が地平線まで届くので、y 950〜2000 で移す。
        shader.safeGetUniform("BandLine").set(SkyLight.smooth(Mth.clamp((float) ((camY - 950.0) / 1050.0), 0.0F, 1.0F)), 0.0F, 0.0F, 0.0F);
        draw(shader, modelView, projection, 3.0F, GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
    }

    private static void drawGlare(ClientLevel level, float pt, Matrix4f modelView, Matrix4f projection) {
        float g = Mth.lerp(pt, glare, glare) * glareSetting();
        if (g <= 0.002F) {
            return;
        }
        Vec3 sun = CanopyLight.sunDirection(level, pt);
        ShaderInstance shader = ModShaders.skyDome;
        shader.safeGetUniform("SunDir").set((float) sun.x, (float) sun.y, (float) sun.z, g);
        draw(shader, modelView, projection, 2.0F, GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE);
    }

    private static void draw(ShaderInstance shader, Matrix4f modelView, Matrix4f projection, float mode, GlStateManager.SourceFactor src,
                             GlStateManager.DestFactor dst) {
        if (dome == null) {
            build();
        }
        shader.safeGetUniform("Mode").set(mode, 0.0F, 0.0F, 0.0F);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(src, dst);
        // 位置はカメラからの向きだけ(回転だけの行列で描く)。
        Matrix4f rotation = new Matrix4f(modelView).setTranslation(0.0F, 0.0F, 0.0F);
        VertexBuffer mesh = mode < 0.5F ? upperDome : dome;
        mesh.bind();
        mesh.drawWithShader(rotation, projection, shader);
        VertexBuffer.unbind();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
    }

    /** 球(半径 64、緯度 16 × 経度 32)と、掛け算の回に使う上の帯だけの球({@link #UPPER_FROM_BAND} 段目から)。 */
    private static void build() {
        dome = buildBands(0);
        upperDome = buildBands(UPPER_FROM_BAND);
    }

    private static VertexBuffer buildBands(int fromBand) {
        int lat = 16;
        int lon = 32;
        float r = 64.0F;
        ByteBufferBuilder bytes = new ByteBufferBuilder(lat * lon * 4 * 12 + 64);
        try {
            BufferBuilder b = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
            for (int i = fromBand; i < lat; i++) {
                double t0 = Math.PI * i / lat - Math.PI / 2.0;
                double t1 = Math.PI * (i + 1) / lat - Math.PI / 2.0;
                for (int j = 0; j < lon; j++) {
                    double p0 = Math.PI * 2.0 * j / lon;
                    double p1 = Math.PI * 2.0 * (j + 1) / lon;
                    point(b, r, t0, p0);
                    point(b, r, t0, p1);
                    point(b, r, t1, p1);
                    point(b, r, t1, p0);
                }
            }
            MeshData mesh = b.build();
            VertexBuffer vbo = new VertexBuffer(VertexBuffer.Usage.STATIC);
            vbo.bind();
            vbo.upload(mesh);
            VertexBuffer.unbind();
            return vbo;
        } finally {
            bytes.close();
        }
    }

    private static void point(BufferBuilder b, float r, double lat, double lon) {
        b.addVertex((float) (Math.cos(lat) * Math.cos(lon) * r), (float) (Math.sin(lat) * r), (float) (Math.cos(lat) * Math.sin(lon) * r));
    }

    /** まぶしさの見え方(雲・地形・積乱雲に隠れていないか)を tick ごとに確かめ、ならす。 */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.isPaused() || broken) {
            return;
        }
        ticks++;
        if (ticks % 5 == 0) {
            glareTarget = visibility(mc, level);
        }
        glare += (glareTarget - glare) * 0.15F;
    }

    private static float visibility(Minecraft mc, ClientLevel level) {
        if (glareSetting() <= 0.0F || !level.dimensionType().hasSkyLight()) {
            return 0.0F;
        }
        Vec3 sun = CanopyLight.sunDirection(level, 1.0F);
        float up = SkyLight.smooth(Mth.clamp(((float) sun.y + 0.02F) / 0.1F, 0.0F, 1.0F));
        float rain = CloudSky.rawRain(level, 1.0F);
        float v = up * (1.0F - rain) * (1.0F - Math.min(1.0F, CloudInterior.amount(1.0F) * 2.0F));
        if (v <= 0.0F) {
            return 0.0F;
        }
        Vec3 eye = mc.gameRenderer.getMainCamera().getPosition();
        // 地形・木に隠れているか。
        if (mc.player != null) {
            HitResult hit = level.clip(new ClipContext(eye, eye.add(sun.scale(192.0)), ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, mc.player));
            if (hit.getType() != HitResult.Type.MISS) {
                return 0.0F;
            }
        }
        // 雲の下にいるときは、雲の影の地図(カメラが雲の影の中か)。
        if (eye.y < CloudField.LOW_BASE) {
            float sv = CloudShadows.sunVisibility();
            v *= Mth.clamp((sv - (1.0F - CloudShadows.SUN_BLOCK)) / CloudShadows.SUN_BLOCK, 0.0F, 1.0F);
        }
        // 太陽の方へ雲の箱をたどる(積乱雲・雲の海。上へ 2000 まで)。
        List<com.yayuy.overgrownvegetation.sky.CloudField.Storm> storms = CloudInterior.storms();
        if (!storms.isEmpty() || eye.y >= CloudField.LOW_BASE - 40) {
            long now = level.getGameTime();
            float coverage = VoxelClouds.coverage(level, 1.0F);
            float threshold = CloudField.threshold(coverage);
            for (double d = 16.0; d < 3000.0; d += 24.0) {
                Vec3 p = eye.add(sun.scale(d));
                if (p.y > CloudField.GIANT_TOP + 40.0) {
                    break;
                }
                if (p.y < CloudField.LOW_BASE) {
                    continue;
                }
                if (CloudInterior.solidAt(storms, threshold, coverage, CloudField.fieldX(p.x, now, 1.0F), p.y, CloudField.fieldZ(p.z, now, 1.0F)) != 0) {
                    return 0.0F;
                }
            }
        }
        return v;
    }

    private SkyDome() {
    }
}
