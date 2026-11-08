package com.yayuy.overgrownvegetation.client.sky;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.VisualQuality;
import com.yayuy.overgrownvegetation.client.atmosphere.SunLight;
import com.yayuy.overgrownvegetation.client.shader.ShaderPacks;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.SkyTable;
import com.yayuy.overgrownvegetation.sky.CloudField;
import com.yayuy.overgrownvegetation.sky.CloudSky;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * ボクセル調の雲(2026-10-17 の土台を 2026-11-08 雲の段階 1 で parked から戻した。元は parked の {@code client/VoxelClouds}、1851 行)。
 * バニラの雲の代わりに、{@link CloudField} の下の層(y 300〜350。ユーザー指定)を 8 × 4 × 8 ブロックの箱の柱で描く。
 * 雲の形と量は {@link CloudField} だけが決める(ここは描き方だけ。{@link CloudRenderer} を差し替えれば、将来のリアルな雲に替えられる)。
 *
 * <ul>
 *   <li>カメラのまわり(高 80・中 64・低 48 升 = 640・512・384 ブロック)の柱を別スレッド({@link CloudThread})で場から引き、外から見える面だけを
 *       形にする(STATIC の VBO)。形は場の座標で持ち、風で流れた分とカメラの位置は描くときの平行移動(uniform)で足す。作り直すのはカメラ
 *       (場の上での位置)が {@link #REBUILD_CELLS} 升動いたときと、量が変わったときだけ。</li>
 *   <li>頂点には太陽と関係ない値だけを焼く(色の r = 面の向き、g = 層の中の高さ、b = 薄さ)。光はシェーダー({@code voxel_clouds})が決める。</li>
 *   <li>バニラと同じ 2 回描き(奥行きだけ → 色)で、一番手前の面だけが空と混ざる(並べ替えが要らない)。</li>
 *   <li>雲の上(y {@link #FAR_ON_Y} より上)では、近くの窓の外に 7.7 km まで(y 950 より上は 18 km まで)雲の海を粗い柱で描く(遠景)。
 *       遠景は空と一緒に地形より先に、雲だけの奥行きの幅で描く({@link #onRenderLevel})。</li>
 * </ul>
 *
 * <p>段階 1 で外したもの(元の版に残っている。段階 2・3 で戻す): 積乱雲・入道雲・乳房雲(塔の形と雷の光)・雲の中の霧・雲の穴・
 * 雲を引く・吐いた雲・巻雲・尾流雲・雲の切れ目の下の霞んだ地面(FarGround)・雲が雲に落とす影(CloudSelfShadow。影を落とすのは積乱雲だけ)・
 * 森の中で遠くの雲を地形の後に描く近道(cloud_sky_mask)。シェーダーは前のまま(使わない uniform は既定値)。
 *
 * <p>シェーダーパック使用中は描かない(パックの雲に任せる。10-23 決定)。Distant Horizons があるときはバニラの雲の設定を見ない
 * (DH が自分のために切るので)。数値はユーザー指定の高さ以外すべて仮決め。
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = net.neoforged.fml.common.EventBusSubscriber.Bus.GAME,
        value = net.neoforged.api.distmarker.Dist.CLIENT)
public final class VoxelClouds implements CloudRenderer {

    public static final VoxelClouds INSTANCE = new VoxelClouds();

    /** 重さの段ごとのカメラのまわりの柱の数(片側、升。高・中・低)。 */
    static final int[] RADIUS_BY_TIER = {80, 64, 48};
    /** カメラ(場の上)がこれだけ升を動いたら作り直す。 */
    static final int REBUILD_CELLS = 6;
    /** 量がこれだけ変わったら作り直す。 */
    public static final float REBUILD_COVERAGE = 0.01F;

    private static final class Window {
        int originX;
        int originZ;
        int centerX;
        int centerZ;
        int radius;
        float coverage;
        int style;
        int quads;
        long seed = CloudField.seed();
        com.yayuy.overgrownvegetation.sky.CloudShape shape = com.yayuy.overgrownvegetation.sky.CloudShape.current();
        ByteBufferBuilder bytes;
        MeshData mesh;
        /** 遠景の窓: 番号と穴の真ん中(場の座標、ブロック)。近くの窓: 作ったときの遠景の番号(0 = 遠景なし)。 */
        long id;
        int holeX;
        int holeZ;
        long farId;
        boolean outer;
        boolean coarse;
    }

    private static ClientLevel lastLevel;
    private static Window shown;
    private static CompletableFuture<Window> pending;
    private static VertexBuffer vbo;

    /*
     * 遠景(2026-10-18、ユーザー「高度 400 を超えて雲より上に入ったとき、読み込み範囲外の雲も見えるように」):
     * 雲の上(高さ FAR_ON_Y より上)で、近くの窓の外に 7680 ブロックまでの雲の海を、遠いほど大きな柱で描く(別の VBO・同じシェーダー)。
     * 遠景には真ん中に四角い穴(FAR_HOLE)があり、その中は近くの窓が受け持つ(近くの雲の床は穴の外をシェーダーで捨てる)。
     * 持ち分が食い違わないよう、遠景を作り直すときは近くの窓も同じ遠景で一緒に作り、2 つ同時に差し替える。数値は仮決め。
     */
    static final double FAR_ON_Y = 410.0;
    static final double FAR_OFF_Y = 395.0;
    static final int FAR_HOLE = 1344;
    static final int FAR_MID = 3840;
    static final int FAR_RANGE = 7680;
    static final int FAR_SNAP = 192;
    static final int FAR_CELL_1 = 48;
    static final int FAR_CELL_2 = 96;
    /** 外の輪(y 950 より上、2026-10-18 夜): FAR3_RANGE まで FAR_CELL_3 の柱の雲の海(雲の海の地平線が FAR_RANGE より遠くなるので)。低の段では作らない。 */
    static final int FAR3_RANGE = 18432;
    static final int FAR_CELL_3 = 192;
    static final double FAR3_ON_Y = 950.0;
    static final double FAR3_OFF_Y = 850.0;
    static final double FAR_MOVE = 240.0;
    static final float FAR_FADE_FROM = 6400.0F;
    static final float FAR_FADE_TO = 7600.0F;
    /** 遠景の切れ目と谷を閉じ始める・閉じ終わる距離(穴の真ん中から。地面は描画距離の外には無いので、遠くの切れ目の下は空の色になる)。 */
    static final double FAR_CLOSE_FROM = 3000.0;
    static final double FAR_CLOSE_TO = 6000.0;
    private static final float FAR_SEA_TAG = 0.3F;
    /** 丸み: この距離より遠い雲を (d − 始まり)² / (2 R) 下げる。 */
    static final float CURVE_FROM = 1200.0F;
    static final float CURVE_RADIUS = 40000.0F;
    /** 頂点の色の a: 下の層(シェーダーが消え方と陰の付け方を分ける)。 */
    private static final float LOW_TAG = 0.5F;

    private static Window farShown;
    private static VertexBuffer farVbo;
    private static CompletableFuture<Window[]> pendingPair;
    private static boolean farWanted;
    private static float farAlpha;
    private static boolean outerWanted;
    private static float farReach = FAR_FADE_TO;
    private static double farAlphaTime;
    private static long farIdSeq;
    private static boolean broken;
    private static long lastLog;

    /** 遠くの雲を描くときの雲だけの奥行きの幅(ブロック)。 */
    static final float FAR_NEAR = 64.0F;
    static final float FAR_FAR = 28000.0F;

    /** 風の筋の模様をシェーダーへ渡すときの場の座標の折り返し(float の細かさを保つ)。 */
    static final double FIELD_PERIOD = 32768.0;

    private static final boolean DH_LOADED = ModList.get().isLoaded("distanthorizons");
    private static final boolean DEV_LOG = Boolean.getBoolean("ov.devbridge");

    /** 夜の雲の色の下限(満月のとき)・新月の割合・青い時間の雲の色と寄せる割合(元は parked の {@code SkyLight}。10-18 ユーザー選択)。 */
    static final float[] NIGHT_CLOUD = {0.17F, 0.21F, 0.36F};
    static final float NEW_MOON = 0.55F;
    static final float[] BLUE_CLOUD = {0.36F, 0.44F, 0.74F};
    static final float BLUE_CLOUD_MIX = 0.6F;

    private VoxelClouds() {
    }

    // ---------------------------------------------------------------- 公開の口

    @Override
    public boolean active() {
        return isActive();
    }

    /**
     * ボクセルの雲を描いているか(設定・シェーダー・バニラの雲の設定)。雲の影・薄明光線もこれに従う(見えている雲と影が食い違わないように)。
     * 11-08 改善 ⑲: 空の色・雨の強さの Mixin から 1 フレームに何百回も呼ばれ、Iris があると毎回 Iris へ名前で問い合わせるので、
     * フレームの初めに 1 回だけ求めて覚える({@link #onRenderFrame})。
     */
    public static boolean isActive() {
        return activeCache;
    }

    private static volatile boolean activeCache;

    @SubscribeEvent
    public static void onRenderFrame(net.neoforged.neoforge.client.event.RenderFrameEvent.Pre event) {
        activeCache = computeActive();
        HighSky.refresh();
    }

    private static boolean computeActive() {
        return !broken && VisualQuality.clouds() && SkyShaders.voxelClouds() != null && !ShaderPacks.active()
                && (DH_LOADED || Minecraft.getInstance().options.getCloudsType() != net.minecraft.client.CloudStatus.OFF);
    }

    /** 今の雲の量({@link CloudSky})。 */
    public static float coverage(ClientLevel level, float partialTick) {
        return CloudSky.coverage(level, partialTick);
    }

    /** 重さの段(0 = 高、1 = 中、2 = 低)。 */
    static int tier() {
        return Mth.clamp(VisualQuality.atmosphereTier(), 0, 2);
    }

    /** 荒天の度合い(0〜1。本当の雨は 6 割・雷雨で 1 × 設計値 roughWeather)。雲の上では雨を見せないが、雲の色は本当の天気で決める。 */
    static float roughness(ClientLevel level, float partialTick) {
        float rain = CloudSky.rawRain(level, partialTick);
        float thunder = CloudSky.rawThunder(level, partialTick);
        return Math.max(rain * 0.6F, thunder) * DesignData.sky().roughWeather;
    }

    /** 天気を入れない時刻だけの雲の色(バニラの {@code ClientLevel#getCloudColor} から雨・雷を除いた式)。 */
    static float[] clearCloudColor(ClientLevel level, float partialTick) {
        float f = Mth.clamp(Mth.cos(level.getTimeOfDay(partialTick) * Mth.TWO_PI) * 2.0F + 0.5F, 0.0F, 1.0F);
        return new float[]{f * 0.9F + 0.1F, f * 0.9F + 0.1F, f * 0.85F + 0.15F};
    }

    /** 青い時間の度合い(0〜1)。太陽の高さ(向きの y)から。 */
    static float blueHour(ClientLevel level, float partialTick) {
        float e = (float) SunLight.sunDirection(level, partialTick).y;
        float in = SunLight.smooth(Mth.clamp((e + 0.24F) / 0.18F, 0.0F, 1.0F));
        float out = 1.0F - SunLight.smooth(Mth.clamp(e / 0.12F, 0.0F, 1.0F));
        return in * out;
    }

    /** 雲の色(バニラの {@code getCloudColor})に、月明かりの夜の下限と青い時間を入れる(10-18 ユーザー選択)。 */
    static Vec3 cloudColor(ClientLevel level, float partialTick, Vec3 cloud) {
        float night = DesignData.sky().nightClouds;
        if (night <= 0.0F) {
            return cloud;
        }
        float rain = CloudSky.rawRain(level, partialTick);
        float moon = NEW_MOON + (1.0F - NEW_MOON) * level.getMoonBrightness();
        float k = night * moon * (1.0F - 0.5F * rain);
        double r = Math.max(cloud.x, NIGHT_CLOUD[0] * k);
        double g = Math.max(cloud.y, NIGHT_CLOUD[1] * k);
        double b = Math.max(cloud.z, NIGHT_CLOUD[2] * k);
        float blue = blueHour(level, partialTick) * BLUE_CLOUD_MIX * night * (1.0F - 0.6F * rain);
        if (blue > 0.0F) {
            r = Mth.lerp(blue, r, BLUE_CLOUD[0]);
            g = Mth.lerp(blue, g, BLUE_CLOUD[1]);
            b = Mth.lerp(blue, b, BLUE_CLOUD[2]);
        }
        return new Vec3(r, g, b);
    }

    /** 雲を描く({@link OverworldCloudEffects#renderClouds} から。近くの雲)。false ならバニラの雲を描かせる。 */
    @Override
    public boolean render(ClientLevel level, float partialTick, Matrix4f modelView, Matrix4f projection, double camX, double camY, double camZ) {
        if (!isActive()) {
            return false;
        }
        try {
            draw(level, partialTick, modelView, projection, camX, camY, camZ, false);
            return true;
        } catch (RuntimeException e) {
            broken = true;
            OvergrownVegetation.LOGGER.error("ボクセルの雲で例外が出たので、このセッションではバニラの雲に戻す", e);
            return false;
        }
    }

    /**
     * 遠くの雲(奥の面の 0.9 倍より遠い所)は空と一緒に、地形より先に描く(雲だけの奥行きの幅 {@link #FAR_NEAR}〜{@link #FAR_FAR} で 2 回描き、
     * 描いたら奥行きを消す)。近くの雲は {@link #render} でいつもの奥行きのまま地形の後に描く。
     */
    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY || CloudRenderers.current() != INSTANCE || !isActive()) {
            return;
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || !level.dimensionType().hasSkyLight()) {
            return;
        }
        Vec3 cam = event.getCamera().getPosition();
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        try {
            // 雲の海の下の霞の床(改善 ①、{@link HazeFloor})。遠くの雲より先に、背景として。
            HazeFloor.draw(level, partialTick, event.getModelViewMatrix(), event.getProjectionMatrix(), cam.x, cam.y, cam.z);
            draw(level, partialTick, event.getModelViewMatrix(), event.getProjectionMatrix(), cam.x, cam.y, cam.z, true);
        } catch (RuntimeException e) {
            broken = true;
            OvergrownVegetation.LOGGER.error("ボクセルの雲(遠く)で例外が出たので、このセッションではバニラの雲に戻す", e);
        }
    }

    // ---------------------------------------------------------------- 描く

    private static void draw(ClientLevel level, float partialTick, Matrix4f modelView, Matrix4f projection, double camX, double camY, double camZ,
                             boolean far) {
        if (level != lastLevel) {
            lastLevel = level;
            shown = null;
            discardPending();
        }
        SkyTable sky = DesignData.sky();
        long gameTime = level.getGameTime();
        float coverage = coverage(level, partialTick);
        int style = CloudSky.style();
        int radius = RADIUS_BY_TIER[tier()];
        double fx = CloudField.fieldX(camX, gameTime, partialTick);
        double fz = CloudField.fieldZ(camZ, gameTime, partialTick);
        int cx = Mth.floor(fx / CloudField.CELL_XZ);
        int cz = Mth.floor(fz / CloudField.CELL_XZ);

        if (pending != null && pending.isDone()) {
            Window w = pending.join();
            pending = null;
            upload(w);
        }
        if (pendingPair != null && pendingPair.isDone()) {
            Window[] pair = pendingPair.join();
            pendingPair = null;
            uploadFar(pair[0]);
            upload(pair[1]);
        }
        if (far) {
            updateFar(gameTime + partialTick, camY);
        }
        Window farNow = farShown != null && (farWanted || farAlpha > 0.0F) ? farShown : null;
        long farId = farNow == null ? 0L : farNow.id;
        boolean moved = shown == null || Math.abs(cx - shown.centerX) > REBUILD_CELLS || Math.abs(cz - shown.centerZ) > REBUILD_CELLS
                || Math.abs(coverage - shown.coverage) > REBUILD_COVERAGE || shown.farId != farId || shown.radius != radius || shown.style != style || shown.seed != CloudField.seed()
                || !shown.shape.equals(com.yayuy.overgrownvegetation.sky.CloudShape.current());
        boolean busy = pending != null || pendingPair != null;
        if (!busy && far && farWanted && needFar(fx, fz, coverage, style)) {
            final float c = coverage;
            final int hx = Math.round((float) (fx / FAR_SNAP)) * FAR_SNAP;
            final int hz = Math.round((float) (fz / FAR_SNAP)) * FAR_SNAP;
            final boolean outer = outerWanted;
            final boolean coarse = tier() >= 1;
            final long id = ++farIdSeq;
            pendingPair = CompletableFuture.supplyAsync(() -> {
                Window fw = buildFar(hx, hz, c, style, id, outer, coarse);
                Window nw = build(cx, cz, radius, c, style, id);
                return new Window[]{fw, nw};
            }, CloudThread.EXECUTOR);
        } else if (!busy && moved) {
            final float c = coverage;
            pending = CompletableFuture.supplyAsync(() -> build(cx, cz, radius, c, style, farId), CloudThread.EXECUTOR);
        }
        if (DEV_LOG && far && gameTime - lastLog >= 200) {
            lastLog = gameTime;
            OvergrownVegetation.LOGGER.info("VoxelClouds 量 {} 並び {} 面 {} 遠景 {}(見え {}) 段 {} 風 {} {}", String.format("%.2f", coverage), style,
                    shown == null ? -1 : shown.quads, farShown == null ? -1 : farShown.quads, String.format("%.2f", farAlpha), tier(),
                    String.format("%.3f", CloudDriftClient.velX()), String.format("%.3f", CloudDriftClient.velZ()));
        }
        boolean nearOk = shown != null && shown.quads > 0 && vbo != null;
        float farShow = sky.farClouds * farAlpha;
        boolean farOk = farShown != null && farShown.quads > 0 && farVbo != null && farShow > 0.002F;
        boolean cut = farOk && shown != null && shown.farId == farShown.id;
        if (!nearOk && !farOk) {
            return;
        }

        ShaderInstance shader = SkyShaders.voxelClouds();
        double dy = CloudField.LOW_BASE - camY;
        float depthFar = Minecraft.getInstance().gameRenderer.getDepthFar();
        int nearRadius = shown != null ? shown.radius : radius;
        float fadeEnd = nearRadius * CloudField.CELL_XZ - 24.0F;
        float fadeStart = fadeEnd * 0.7F;
        float split = depthFar * 0.9F;
        shader.safeGetUniform("Split").set(split, far ? 1.0F : -1.0F, FAR_NEAR, FAR_FAR);
        // 雲の海(SEA_TAG)は StormFade.zw で消える。遠景が続きを描く間は消さない(穴の外の床はシェーダーが捨てる)。
        if (cut) {
            shader.safeGetUniform("StormFade").set(30000.0F, 30100.0F, 30000.0F, 30100.0F);
        } else {
            shader.safeGetUniform("StormFade").set(SEA_FADE_FROM, SEA_FADE_TO, SEA_FADE_FROM, SEA_FADE_TO);
        }
        shader.safeGetUniform("Puff").set(1.0F, 1.0F, 0.0F, 0.0F);
        shader.safeGetUniform("Far").set(farFadeFrom(), farFadeTo(), farShow, cut ? 1.0F : 0.0F);
        if (farShown != null) {
            shader.safeGetUniform("FarHole").set((float) (farShown.holeX - FAR_HOLE - fx), (float) (farShown.holeZ - FAR_HOLE - fz),
                    (float) (farShown.holeX + FAR_HOLE - fx), (float) (farShown.holeZ + FAR_HOLE - fz));
        }
        shader.safeGetUniform("Curve").set(sky.curvature, CURVE_FROM, 0.5F / CURVE_RADIUS, 0.0F);
        for (int i = 0; i < 3; i++) {
            shader.safeGetUniform("Flash" + i).set(0.0F, 0.0F, 0.0F, 0.0F);
        }
        // 雲の中(改善 ②、{@link CloudInterior}): 雲の箱も同じ霧に沈め、内側の面は描かない。
        float inside = CloudInterior.amount(partialTick);
        if (inside > 0.001F) {
            float[] ic = CloudInterior.fogColor(level, partialTick);
            shader.safeGetUniform("InsideFog").set(CloudInterior.FOG, inside, 0.0F, 0.0F);
            shader.safeGetUniform("InsideColor").set(ic[0], ic[1], ic[2], 1.0F);
        } else {
            shader.safeGetUniform("InsideFog").set(1000.0F, 0.0F, 0.0F, 0.0F);
        }
        Vec3 cloud = cloudColor(level, partialTick, level.getCloudColor(partialTick));
        Vec3 skyColor = level.getSkyColor(new Vec3(camX, camY, camZ), partialTick);
        Vec3 sunDir = SunLight.sunDirection(level, partialTick);
        boolean moon = sunDir.y < 0.0;
        Vec3 light = moon ? sunDir.scale(-1.0) : sunDir;
        float strength = SunLight.lightStrength(level, light, partialTick);
        float warm = moon ? 0.0F : 1.0F - SunLight.smooth(Mth.clamp((float) sunDir.y / 0.35F, 0.0F, 1.0F));
        float[] moonColor = new float[3];
        if (moon) {
            SunLight.airColor(level, light, partialTick, moonColor);
        }
        float sr = moon ? moonColor[0] : 1.0F;
        float sg = moon ? moonColor[1] : Mth.lerp(warm, 0.97F, 0.62F);
        float sb = moon ? moonColor[2] : Mth.lerp(warm, 0.9F, 0.35F);
        shader.safeGetUniform("CloudColor").set((float) cloud.x, (float) cloud.y, (float) cloud.z, 1.0F);
        shader.safeGetUniform("SunDir").set((float) light.x, (float) light.y, (float) light.z, strength);
        shader.safeGetUniform("SunColor").set(sr, sg, sb, 1.0F);
        shader.safeGetUniform("SkyColor").set((float) skyColor.x, (float) skyColor.y, (float) skyColor.z, 1.0F);
        shader.safeGetUniform("Fade").set(fadeStart, fadeEnd, (float) sunDir.x, (float) sunDir.z);
        float[] sunrise = level.effects().getSunriseColor(level.getTimeOfDay(partialTick), partialTick);
        if (sunrise != null) {
            shader.safeGetUniform("Glow").set(sunrise[0], sunrise[1], sunrise[2], sunrise[3]);
        } else {
            shader.safeGetUniform("Glow").set(0.0F, 0.0F, 0.0F, 0.0F);
        }
        shader.safeGetUniform("World").set((float) camY, coverage, sky.floorFlatness, 0.0F);
        // 時間帯の色(11-08、design の値。仮決め): 昼 → 夕方(日が低いほど)→ 夜。
        float duskK = moon ? 0.0F : 1.0F - SunLight.smooth(Mth.clamp((float) sunDir.y / 0.35F, 0.0F, 1.0F));
        float nightK = SunLight.night(level, partialTick);
        float[] lit = tint(sky.litDay, sky.litDusk, sky.litNight, duskK, nightK);
        float[] shadeT = tint(sky.shadeDay, sky.shadeDusk, sky.shadeNight, duskK, nightK);
        shader.safeGetUniform("LitTint").set(lit[0], lit[1], lit[2], 1.0F);
        shader.safeGetUniform("ShadeTint").set(shadeT[0], shadeT[1], shadeT[2], 1.0F);
        // 地平の霧の色(改善 ③④): 遠くの雲・曇りの天井・夜の遠い雲の底をここへ溶かす。
        float[] horizon = RenderSystem.getShaderFogColor();
        shader.safeGetUniform("HorizonColor").set(horizon[0], horizon[1], horizon[2], 1.0F);
        float[] clear = clearCloudColor(level, partialTick);
        shader.safeGetUniform("Rough").set(clear[0], clear[1], clear[2], roughness(level, partialTick));
        // 風の筋の向き = ふだんの向きの風(固定)。今の風の向きにすると、場の座標の原点のまわりで模様ごと回って速く流れて見えた(10-18)。
        double wl = Math.sqrt(CloudField.WIND_X * CloudField.WIND_X + CloudField.WIND_Z * CloudField.WIND_Z);
        shader.safeGetUniform("Field").set((float) (fx - Math.floor(fx / FIELD_PERIOD) * FIELD_PERIOD),
                (float) (fz - Math.floor(fz / FIELD_PERIOD) * FIELD_PERIOD), (float) (CloudField.WIND_X / wl), (float) (CloudField.WIND_Z / wl));
        shader.safeGetUniform("Look2").set(sky.windStreaks, sky.blueHaze, sky.texture, 0.0F);
        shader.safeGetUniform("ShadowA").set(0.0F, 0.0F, 1.0F, 0.0F);

        RenderTarget cloudsTarget = far ? null : Minecraft.getInstance().levelRenderer.getCloudsTarget();
        if (cloudsTarget != null) {
            cloudsTarget.bindWrite(false);
        }
        RenderSystem.enableDepthTest();
        RenderSystem.disableCull();
        // 1 回目: 奥行きだけ(透けない所だけ)。2 回目: 色(奥行きを書かずに重ねる。輪郭のやわらかさ)。バニラの雲と同じ。
        RenderSystem.colorMask(false, false, false, false);
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        shader.safeGetUniform("Look").set(sky.celShading, sky.softEdges, 1.0F, 1.0F);
        drawMeshes(shader, modelView, projection, nearOk, farOk, fx, fz, dy);
        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        shader.safeGetUniform("Look").set(sky.celShading, sky.softEdges, 1.0F, 0.0F);
        drawMeshes(shader, modelView, projection, nearOk, farOk, fx, fz, dy);
        RenderSystem.disableBlend();
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        if (far) {
            // 地形はこの後にいつもの奥行きで描くので、雲だけの奥行きを消す(空は奥行きを書かないので消しても困らない)。
            RenderSystem.clear(org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
        }
        if (cloudsTarget != null) {
            Minecraft.getInstance().getMainRenderTarget().bindWrite(false);
        }
    }

    /** 昼・夕方・夜の色を混ぜる。 */
    private static float[] tint(float[] day, float[] dusk, float[] night, float duskK, float nightK) {
        float[] out = new float[3];
        for (int i = 0; i < 3; i++) {
            out[i] = Mth.lerp(nightK, Mth.lerp(duskK, day[i], dusk[i]), night[i]);
        }
        return out;
    }

    private static void drawMeshes(ShaderInstance shader, Matrix4f modelView, Matrix4f projection, boolean nearOk, boolean farOk,
                                   double fx, double fz, double dy) {
        if (nearOk) {
            shader.safeGetUniform("Offset").set((float) (shown.originX * (double) CloudField.CELL_XZ - fx), (float) dy,
                    (float) (shown.originZ * (double) CloudField.CELL_XZ - fz));
            vbo.bind();
            vbo.drawWithShader(modelView, projection, shader);
        }
        if (farOk) {
            shader.safeGetUniform("Offset").set((float) (farShown.originX * (double) CloudField.CELL_XZ - fx), (float) dy,
                    (float) (farShown.originZ * (double) CloudField.CELL_XZ - fz));
            farVbo.bind();
            farVbo.drawWithShader(modelView, projection, shader);
        }
        VertexBuffer.unbind();
    }

    // ---------------------------------------------------------------- 遠景

    /** 高さで遠景を使うかを決め(行き来しないよう 395〜410 で切り替える)、見え方を 1 秒かけて寄せる。下りて消えきったら遠景を手放す。 */
    private static void updateFar(double now, double camY) {
        if (DesignData.sky().farClouds <= 0.0F) {
            farWanted = false;
        } else if (camY >= FAR_ON_Y) {
            farWanted = true;
        } else if (camY < FAR_OFF_Y) {
            farWanted = false;
        }
        double dt = Mth.clamp(now - farAlphaTime, 0.0, 5.0);
        farAlphaTime = now;
        boolean ready = farWanted && farShown != null && shown != null && shown.farId == farShown.id;
        float step = (float) dt * 0.05F;
        farAlpha = ready ? Math.min(1.0F, farAlpha + step) : Math.max(0.0F, farAlpha - step);
        if (!farWanted && farAlpha <= 0.0F && farShown != null && pendingPair == null) {
            farShown = null;
        }
        // 外の輪(行き来しないよう 850〜950 で切り替える。低の段では使わない)。
        // 11-08 改善 ⑭: 外の輪は高の段だけ(曇りの日に 19 万面・GPU +2.8 ms だった。前は中でも作った)。
        if (camY >= FAR3_ON_Y && tier() == 0) {
            outerWanted = true;
        } else if (camY < FAR3_OFF_Y || tier() > 0) {
            outerWanted = false;
        }
        float reachTo = farShown != null && farShown.outer ? FAR3_RANGE - 400.0F : FAR_FADE_TO;
        float move = (float) dt * 200.0F;
        farReach = farReach < reachTo ? Math.min(reachTo, farReach + move) : Math.max(reachTo, farReach - move);
    }

    static float farFadeFrom() {
        return farReach - (FAR_FADE_TO - FAR_FADE_FROM);
    }

    static float farFadeTo() {
        return farReach;
    }

    /** 遠景を作り直すか(無い・穴の真ん中から離れた・雲の量が変わった・段が変わった)。 */
    private static boolean needFar(double fx, double fz, float coverage, int style) {
        if (farShown == null || farShown.outer != outerWanted || farShown.coarse != (tier() >= 1) || farShown.style != style || farShown.seed != CloudField.seed()
                || !farShown.shape.equals(com.yayuy.overgrownvegetation.sky.CloudShape.current())) {
            return true;
        }
        return Math.abs(fx - farShown.holeX) > FAR_MOVE || Math.abs(fz - farShown.holeZ) > FAR_MOVE || Math.abs(coverage - farShown.coverage) > 0.02F;
    }

    private static void uploadFar(Window w) {
        try {
            if (w.mesh != null) {
                if (farVbo == null) {
                    farVbo = new VertexBuffer(VertexBuffer.Usage.STATIC);
                }
                farVbo.bind();
                farVbo.upload(w.mesh);
                VertexBuffer.unbind();
                w.mesh = null;
            }
        } finally {
            if (w.bytes != null) {
                w.bytes.close();
                w.bytes = null;
            }
        }
        farShown = w;
    }

    /**
     * 遠景を作る(別スレッド)。雲の海: 穴(FAR_HOLE)〜FAR_MID を 48 ブロック、その外 FAR_RANGE までを 96 ブロックの柱で(中・低の段は倍)。
     * 遠くほど切れ目と谷を閉じる(FAR_CLOSE_FROM〜TO)。outer(高い所)なら外の輪 FAR3_RANGE までを FAR_CELL_3 の柱で足す。
     */
    private static Window buildFar(int holeX, int holeZ, float coverage, int style, long id, boolean outer, boolean coarse) {
        Window w = new Window();
        w.id = id;
        w.outer = outer;
        w.coarse = coarse;
        w.style = style;
        int cell1 = coarse ? FAR_CELL_1 * 2 : FAR_CELL_1;
        int cell2 = coarse ? FAR_CELL_2 * 2 : FAR_CELL_2;
        w.holeX = holeX;
        w.holeZ = holeZ;
        w.centerX = holeX / CloudField.CELL_XZ;
        w.centerZ = holeZ / CloudField.CELL_XZ;
        w.originX = (holeX - FAR_RANGE) / CloudField.CELL_XZ;
        w.originZ = (holeZ - FAR_RANGE) / CloudField.CELL_XZ;
        w.coverage = coverage;
        float threshold = CloudField.threshold(coverage);
        ByteBufferBuilder bytes = new ByteBufferBuilder(1 << 21);
        BufferBuilder b = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        int quads = 0;
        quads += farGrid(b, threshold, coverage, style, w, Math.floorDiv(holeX - FAR_MID, cell1), Math.floorDiv(holeZ - FAR_MID, cell1), 2 * FAR_MID / cell1,
                cell1, Math.floorDiv(holeX - FAR_HOLE, cell1), Math.floorDiv(holeZ - FAR_HOLE, cell1), 2 * FAR_HOLE / cell1);
        quads += farGrid(b, threshold, coverage, style, w, Math.floorDiv(holeX - FAR_RANGE, cell2), Math.floorDiv(holeZ - FAR_RANGE, cell2), 2 * FAR_RANGE / cell2,
                cell2, Math.floorDiv(holeX - FAR_MID, cell2), Math.floorDiv(holeZ - FAR_MID, cell2), 2 * FAR_MID / cell2);
        if (outer) {
            quads += farGrid(b, threshold, coverage, style, w, Math.floorDiv(holeX - FAR3_RANGE, FAR_CELL_3), Math.floorDiv(holeZ - FAR3_RANGE, FAR_CELL_3),
                    2 * FAR3_RANGE / FAR_CELL_3, FAR_CELL_3, Math.floorDiv(holeX - FAR_RANGE, FAR_CELL_3), Math.floorDiv(holeZ - FAR_RANGE, FAR_CELL_3),
                    2 * FAR_RANGE / FAR_CELL_3);
        }
        w.quads = quads;
        w.mesh = quads > 0 ? b.build() : null;
        if (w.mesh == null) {
            w.quads = 0;
            bytes.close();
        } else {
            w.bytes = bytes;
        }
        return w;
    }

    /** 遠景の雲の海の格子 1 段(遠いほど切れ目と谷を閉じる)。 */
    private static int farGrid(BufferBuilder b, float threshold, float coverage, int style, Window w,
                               int gx0, int gz0, int n, int size, int skipX, int skipZ, int skipN) {
        int[] bottom = new int[n * n];
        int[] top = new int[n * n];
        int per = size / CloudField.CELL_XZ;
        boolean floor = style >= CloudField.STYLE_VALLEY;
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                int k = j * n + i;
                bottom[k] = 1;
                top[k] = 0;
                int gx = gx0 + i;
                int gz = gz0 + j;
                if (gx >= skipX && gx < skipX + skipN && gz >= skipZ && gz < skipZ + skipN) {
                    continue;
                }
                double px = (gx + 0.5) * size;
                double pz = (gz + 0.5) * size;
                double d = Math.hypot(px - w.holeX, pz - w.holeZ);
                float close = SunLight.smooth((float) Mth.clamp((d - FAR_CLOSE_FROM) / (FAR_CLOSE_TO - FAR_CLOSE_FROM), 0.0, 1.0));
                // 晴れの日の小さい積雲は遠景では作らない(粗い柱では点々の箱になる)。
                CloudField.Column c = CloudField.column(gx * per + per / 2, gz * per + per / 2, threshold, Mth.lerp(close, coverage, 1.0F), List.of(), style, false);
                if (c.present()) {
                    // 外の輪(192)は 32 に丸める(段が減り横の面が減る。11-08 改善 ⑭)。
                    int q = size >= FAR_CELL_3 ? 32 : (size >= FAR_CELL_2 ? 16 : 8);
                    bottom[k] = c.bottom() - CloudField.LOW_BASE;
                    top[k] = Math.max(bottom[k] + q, Math.round((c.top() - CloudField.LOW_BASE) / (float) q) * q);
                } else if (floor && close > 0.5F) {
                    bottom[k] = 0;
                    top[k] = 2 * CloudField.CELL_Y;
                }
            }
        }
        float offX = gx0 * (float) size - w.originX * (float) CloudField.CELL_XZ;
        float offZ = gz0 * (float) size - w.originZ * (float) CloudField.CELL_XZ;
        return layer(b, bottom, top, n, n, offX, offZ, FAR_SEA_TAG, size, false);
    }

    // ---------------------------------------------------------------- 近くの窓

    private static void upload(Window w) {
        try {
            if (w.mesh != null) {
                if (vbo == null) {
                    vbo = new VertexBuffer(VertexBuffer.Usage.STATIC);
                }
                vbo.bind();
                vbo.upload(w.mesh);
                VertexBuffer.unbind();
                w.mesh = null;
            }
        } finally {
            if (w.bytes != null) {
                w.bytes.close();
                w.bytes = null;
            }
        }
        shown = w;
    }

    private static void discardPending() {
        farShown = null;
        farAlpha = 0.0F;
        if (pendingPair != null) {
            CompletableFuture<Window[]> p = pendingPair;
            pendingPair = null;
            p.thenAccept(pair -> {
                for (Window w : pair) {
                    close(w);
                }
            });
        }
        if (pending != null) {
            CompletableFuture<Window> p = pending;
            pending = null;
            p.thenAccept(VoxelClouds::close);
        }
    }

    private static void close(Window w) {
        if (w.mesh != null) {
            w.mesh.close();
        }
        if (w.bytes != null) {
            w.bytes.close();
        }
    }

    /** 場から柱を引いて、外から見える面の形を作る(別スレッド)。 */
    private static Window build(int centerX, int centerZ, int radius, float coverage, int style, long farId) {
        Window w = new Window();
        int size = radius * 2 + 1;
        w.centerX = centerX;
        w.centerZ = centerZ;
        w.radius = radius;
        w.originX = centerX - radius;
        w.originZ = centerZ - radius;
        w.coverage = coverage;
        w.style = style;
        w.farId = farId;
        float threshold = CloudField.threshold(coverage);
        ByteBufferBuilder bytes = new ByteBufferBuilder(1 << 20);
        BufferBuilder b = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        int quads;
        if (style >= CloudField.STYLE_VALLEY) {
            // 雲の海の地形(谷の並び方。既定): 窓の真ん中のまわり FLOOR_RANGE を FLOOR_CELL の柱でひと続きに(元の版の seaMesh/floorGrid と同じ)。
            // 8 ブロックの窓(640)だけにすると、地上からは雲の海の端が輪に見え、雲の上からは遠景の穴(1344)が空いた(11-08 の撮影)。
            double cx0 = centerX * (double) CloudField.CELL_XZ;
            double cz0 = centerZ * (double) CloudField.CELL_XZ;
            int g0x = Mth.floor((cx0 - FLOOR_RANGE) / FLOOR_CELL);
            int g0z = Mth.floor((cz0 - FLOOR_RANGE) / FLOOR_CELL);
            int n = Mth.ceil(2.0 * FLOOR_RANGE / FLOOR_CELL) + 1;
            quads = floorGrid(b, threshold, coverage, style, w, g0x, g0z, n, FLOOR_CELL);
        } else {
            int[] bottom = new int[size * size];
            int[] top = new int[size * size];
            for (int j = 0; j < size; j++) {
                for (int i = 0; i < size; i++) {
                    int k = j * size + i;
                    CloudField.Column c = CloudField.column(w.originX + i, w.originZ + j, threshold, coverage, List.of(), style);
                    bottom[k] = c.present() ? c.bottom() - CloudField.LOW_BASE : 1;
                    top[k] = c.present() ? c.top() - CloudField.LOW_BASE : 0;
                }
            }
            quads = layer(b, bottom, top, size, size, 0.0F, 0.0F, LOW_TAG, CloudField.CELL_XZ);
        }
        w.quads = quads;
        w.mesh = quads > 0 ? b.build() : null;
        if (w.mesh == null) {
            w.quads = 0;
            bytes.close();
        } else {
            w.bytes = bytes;
        }
        return w;
    }

    /** 雲の海の地形の柱の大きさ(ブロック。箱 3 つ分)と、形にする範囲(窓の真ん中から。遠景の穴 FAR_HOLE + 動きを覆う)。元の版の値。 */
    static final int FLOOR_CELL = CloudSky.FLOOR_CELL;
    static final double FLOOR_RANGE = 1800.0;
    /** 頂点の色の a: 雲の海(シェーダーは StormFade.zw で消す)。 */
    private static final float SEA_TAG = 0.65F;
    /** 雲の海の消え始めと消え終わり(水平距離)。元の版の値。 */
    static final float SEA_FADE_FROM = 1450.0F;
    static final float SEA_FADE_TO = 1780.0F;

    /** 雲の海の地形の柱の格子 1 つ(柱の大きさ size、柱の番号 gx0..gx0+n)。 */
    private static int floorGrid(BufferBuilder b, float threshold, float coverage, int style, Window w, int gx0, int gz0, int n, int size) {
        int[] bottom = new int[n * n];
        int[] top = new int[n * n];
        int per = size / CloudField.CELL_XZ;
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                int k = j * n + i;
                CloudField.Column c = CloudField.column((gx0 + i) * per + per / 2, (gz0 + j) * per + per / 2, threshold, coverage, List.of(), style);
                bottom[k] = c.present() ? c.bottom() - CloudField.LOW_BASE : 1;
                top[k] = c.present() ? c.top() - CloudField.LOW_BASE : 0;
            }
        }
        float offX = gx0 * (float) size - w.originX * (float) CloudField.CELL_XZ;
        float offZ = gz0 * (float) size - w.originZ * (float) CloudField.CELL_XZ;
        return layer(b, bottom, top, n, n, offX, offZ, SEA_TAG, size);
    }

    /** 開発用の要約(テストクライアントの {@code clouds status})。 */
    public static String describe() {
        return String.format("雲 有効 %s 量 %.2f 並び %d 面 %d 遠景 %d(見え %.2f・外の輪 %s) 段 %d 速さ %.3f %.3f 種 %x 雲の中 %.2f", isActive(),
                lastLevel == null ? -1.0F : coverage(lastLevel, 0.0F), CloudSky.style(), shown == null ? -1 : shown.quads,
                farShown == null ? -1 : farShown.quads, farAlpha, outerWanted, tier(), CloudDriftClient.velX(), CloudDriftClient.velZ(), CloudField.seed(), CloudInterior.amount(1.0F));
    }

    // ---------------------------------------------------------------- 形の補助(元の版のまま)

    /**
     * 1 段の柱(n × m の底・天辺の配列、(offX, offZ) から並ぶ)の外から見える面を足す。横の面は同じ段の隣の柱が覆っていない高さだけ。
     * tag = 頂点の色の a(下の層か積乱雲か)。
     */
    private static int layer(BufferBuilder b, int[] bottom, int[] top, int n, int m, float offX, float offZ, float tag, int s) {
        return layer(b, bottom, top, n, m, offX, offZ, tag, s, true);
    }

    /** bottoms = false なら下面を作らない(遠景はカメラが雲より上にあるときだけ描くので、雲の海の下面は見えない)。 */
    private static int layer(BufferBuilder b, int[] bottom, int[] top, int n, int m, float offX, float offZ, float tag, int s, boolean bottoms) {
        int quads = 0;
        float layer = CloudField.LOW_TOP - CloudField.LOW_BASE;
        for (int j = 0; j < m; j++) {
            for (int i = 0; i < n; i++) {
                int k = j * n + i;
                int y0 = bottom[k];
                int y1 = top[k];
                if (y0 >= y1) {
                    continue;
                }
                // 角は番号から同じ式で(隣の柱と少しでもずれると、平らな雲の海にすき間の線が格子に見えた)。
                float x0 = (float) (offX + (double) i * s);
                float x1 = (float) (offX + (double) (i + 1) * s);
                float z0 = (float) (offZ + (double) j * s);
                float z1 = (float) (offZ + (double) (j + 1) * s);
                int open = 0;
                for (int d = 0; d < 4; d++) {
                    int ni = i + (d == 2 ? -1 : d == 3 ? 1 : 0);
                    int nj = j + (d == 0 ? -1 : d == 1 ? 1 : 0);
                    if (ni < 0 || nj < 0 || ni >= n || nj >= m || bottom[nj * n + ni] >= top[nj * n + ni]) {
                        open++;
                    }
                }
                float thin = Math.max(1.0F - Mth.clamp((y1 - y0) / 24.0F, 0.0F, 1.0F), open * 0.15F);
                float h0 = y0 / layer;
                float h1 = y1 / layer;
                // 下面(辺: 北・東・南・西)・上面(辺: 南・東・北・西)。出っ張った辺 = 隣の柱がその高さのすぐ上下とも空いている。
                int downBits = (colEmpty2(bottom, top, n, m, i, j - 1, y0 - 1, y0) ? 1 : 0) | (colEmpty2(bottom, top, n, m, i + 1, j, y0 - 1, y0) ? 2 : 0)
                        | (colEmpty2(bottom, top, n, m, i, j + 1, y0 - 1, y0) ? 4 : 0) | (colEmpty2(bottom, top, n, m, i - 1, j, y0 - 1, y0) ? 8 : 0);
                int upBits = (colEmpty2(bottom, top, n, m, i, j + 1, y1 - 1, y1) ? 1 : 0) | (colEmpty2(bottom, top, n, m, i + 1, j, y1 - 1, y1) ? 2 : 0)
                        | (colEmpty2(bottom, top, n, m, i, j - 1, y1 - 1, y1) ? 4 : 0) | (colEmpty2(bottom, top, n, m, i - 1, j, y1 - 1, y1) ? 8 : 0);
                if (bottoms) {
                    quad(b, 0, thin, tag, downBits, x0, y0, z0, h0, x1, y0, z0, h0, x1, y0, z1, h0, x0, y0, z1, h0);
                    quads++;
                }
                quad(b, 1, thin, tag, upBits, x0, y1, z1, h1, x1, y1, z1, h1, x1, y1, z0, h1, x0, y1, z0, h1);
                quads++;
                // 横の面: 隣の柱が覆っていない高さだけ(北 −z・南 +z・西 −x・東 +x)。
                for (int d = 0; d < 4; d++) {
                    int ni = i + (d == 2 ? -1 : d == 3 ? 1 : 0);
                    int nj = j + (d == 0 ? -1 : d == 1 ? 1 : 0);
                    int nb = 1;
                    int nt = 0;
                    if (ni >= 0 && nj >= 0 && ni < n && nj < m) {
                        nb = bottom[nj * n + ni];
                        nt = top[nj * n + ni];
                    }
                    if (nb >= nt) {
                        quads += side(b, d, thin, tag, layer, x0, x1, z0, z1, y0, y1, bottom, top, n, m, i, j);
                    } else {
                        if (y0 < nb) {
                            quads += side(b, d, thin, tag, layer, x0, x1, z0, z1, y0, Math.min(y1, nb), bottom, top, n, m, i, j);
                        }
                        if (y1 > nt) {
                            quads += side(b, d, thin, tag, layer, x0, x1, z0, z1, Math.max(y0, nt), y1, bottom, top, n, m, i, j);
                        }
                    }
                }
            }
        }
        return quads;
    }

    /**
     * 柱の横の面 1 枚(d: 0 北・1 南・2 西・3 東、高さ ya〜yb)。辺の順は 下・(頂点の順で)横・上・横。出っ張った辺: 下は自分の柱と向かいの柱が ya のすぐ下で空、
     * 上は yb のすぐ上で空、横はその向きの隣の柱と斜めの柱が真ん中の高さで空。
     */
    private static int side(BufferBuilder b, int d, float thin, float tag, float layer, float x0, float x1, float z0, float z1, int ya, int yb,
                            int[] bottom, int[] top, int n, int m, int i, int j) {
        if (yb <= ya) {
            return 0;
        }
        float ha = ya / layer;
        float hb = yb / layer;
        int fi = d == 2 ? -1 : d == 3 ? 1 : 0;
        int fj = d == 0 ? -1 : d == 1 ? 1 : 0;
        int mid = (ya + yb) / 2;
        boolean downOpen = !colFilled(bottom, top, n, m, i, j, ya - 1) && !colFilled(bottom, top, n, m, i + fi, j + fj, ya - 1);
        boolean upOpen = !colFilled(bottom, top, n, m, i, j, yb) && !colFilled(bottom, top, n, m, i + fi, j + fj, yb);
        // 頂点の順で 2 番目の辺(b→c)と 4 番目の辺(d→a)の向きの隣。
        int si = d == 0 ? -1 : d == 1 ? 1 : 0;
        int sj = d == 2 ? 1 : d == 3 ? -1 : 0;
        boolean sideB = !colFilled(bottom, top, n, m, i + si, j + sj, mid) && !colFilled(bottom, top, n, m, i + si + fi, j + sj + fj, mid);
        boolean sideD = !colFilled(bottom, top, n, m, i - si, j - sj, mid) && !colFilled(bottom, top, n, m, i - si + fi, j - sj + fj, mid);
        int bits = (downOpen ? 1 : 0) | (sideB ? 2 : 0) | (upOpen ? 4 : 0) | (sideD ? 8 : 0);
        switch (d) {
            case 0 -> quad(b, 2, thin, tag, bits, x1, ya, z0, ha, x0, ya, z0, ha, x0, yb, z0, hb, x1, yb, z0, hb);
            case 1 -> quad(b, 3, thin, tag, bits, x0, ya, z1, ha, x1, ya, z1, ha, x1, yb, z1, hb, x0, yb, z1, hb);
            case 2 -> quad(b, 4, thin, tag, bits, x0, ya, z0, ha, x0, ya, z1, ha, x0, yb, z1, hb, x0, yb, z0, hb);
            default -> quad(b, 5, thin, tag, bits, x1, ya, z1, ha, x1, ya, z0, ha, x1, yb, z0, hb, x1, yb, z1, hb);
        }
        return 1;
    }

    /** 柱 (i, j) が高さ y(その 1 ブロック)を埋めているか(格子の外は空)。 */
    private static boolean colFilled(int[] bottom, int[] top, int n, int m, int i, int j, int y) {
        if (i < 0 || j < 0 || i >= n || j >= m) {
            return false;
        }
        int k = j * n + i;
        return bottom[k] <= y && y < top[k];
    }

    private static boolean colEmpty2(int[] bottom, int[] top, int n, int m, int i, int j, int ya, int yb) {
        return !colFilled(bottom, top, n, m, i, j, ya) && !colFilled(bottom, top, n, m, i, j, yb);
    }

    /**
     * 四角 1 枚。色の r = (面の向き(0 下・1 上・2 北・3 南・4 西・5 東)× 16 + 出っ張った辺の印)/ 255、g = 層の中の高さ(積乱雲は焼いた明るさ)、
     * b = 薄さ、a = 下の層か積乱雲か。UV = 面の中の位置(頂点の順に (0,0)・(1,0)・(1,1)・(0,1))。
     * 出っ張った辺の印(10-17 深夜、輪郭のやわらかさ): 頂点の順の辺 a→b・b→c・c→d・d→a のうち、その向きの隣の箱(と面の外側の斜めの箱)が空いている辺 = 1・2・4・8。
     * シェーダーがその辺の近くを丸く透かして、箱の輪郭をやわらかく見せる。最初は角ごとの開きを頂点に持たせて補間したが、四角を割った三角の向きに
     * 斜めのにじみが出た(面ごとに同じ値と面の中の位置なら、三角の割り方に左右されない)。
     */
    private static void quad(BufferBuilder b, int face, float thin, float tag, int bits,
                             float ax, float ay, float az, float ah, float bx, float by, float bz, float bh,
                             float cx, float cy, float cz, float ch, float dx, float dy, float dz, float dh) {
        // 半目盛り足す(色は float から 8 ビットへ切り捨てで写るので、ちょうどの値だと 1 つ下になり、向きと印が崩れた)。
        float r = (face * 16 + (bits & 15) + 0.5F) / 255.0F;
        b.addVertex(ax, ay, az).setUv(0.0F, 0.0F).setColor(r, ah, thin, tag);
        b.addVertex(bx, by, bz).setUv(1.0F, 0.0F).setColor(r, bh, thin, tag);
        b.addVertex(cx, cy, cz).setUv(1.0F, 1.0F).setColor(r, ch, thin, tag);
        b.addVertex(dx, dy, dz).setUv(0.0F, 1.0F).setColor(r, dh, thin, tag);
    }

}
