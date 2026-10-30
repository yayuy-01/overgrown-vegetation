package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.sky.CloudField;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.concurrent.CompletableFuture;

/**
 * 流れる雲の影(2026-10-17。10-16 にユーザーが採用した「密林をさらに美しく見せる案」(3) 流れる雲の影 —— ボクセルの雲 {@link VoxelClouds} の段取り (1) の続き。
 * ユーザー「次は雲の影を進めてください」)。
 *
 * <ul>
 *   <li>影の地図: {@link CloudField} の柱を 1 升 1 画素で写した {@link #SIZE} × {@link #SIZE} の絵(場の座標、8 ブロック升で 1280 ブロック四方)。
 *       画素の値 = その柱の影の濃さ(薄い雲ほど淡い)。地図の真ん中は「カメラから太陽の向きにたどって雲の高さに着く点」。場は風で流れるので、
 *       地図も描くときの平行移動で流れ、作り直すのは真ん中が {@link #REBUILD_CELLS} 升ずれたときと量が変わったときだけ(別スレッド)。</li>
 *   <li>地形の 4 つのシェーダー({@code include/ov_cloud.glsl})が、面の点から太陽の向きに雲の高さ {@link #SHADOW_Y} までたどった点で地図を引き
 *       (なめらかに補間するので影の縁はぼける)、空の光が届く面を青みがかった灰色へ暗くする。太陽に背を向けた面は弱く、林冠の影の中はもう暗いので
 *       重ねない(二重に暗くしない)。</li>
 *   <li>カメラが雲の影に入ったら、光の筋・木漏れ日・草の斑点・水のきらめき・雨上がりのきらめき・林冠の影の濃さ・太陽の側の霧の明るさを弱める
 *       ({@link #sunVisibility()})。雲が太陽を隠すと森の木漏れ日がすっと消え、抜けるとまた差す。</li>
 * </ul>
 * 昼だけ(太陽が低いと影がとても遠くへ伸びて地図に収まらないので、高さ 7〜18° で入れる)。雨は光の強さで弱まる。ボクセルの雲を描いていないとき
 * (設定でオフ・バニラの雲の設定がオフ)は出さない(見えている雲と影が食い違うので)。数値はすべて仮決め。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class CloudShadows {
    /** 地図の大きさ(画素 = 雲の柱)。 */
    static final int SIZE = 160;
    /** 影を落とす高さ(雲の底 300 の少し上)。 */
    static final float SHADOW_Y = CloudField.LOW_BASE + 8.0F;
    /** 地図の真ん中がこれだけ升ずれたら作り直す。 */
    static final int REBUILD_CELLS = 8;
    /** 地図の縁で影を消していく幅(ブロック)。 */
    static final float EDGE = 32.0F;
    /** カメラが雲の影に入ったとき、光の筋・木漏れ日などを何割まで弱めるか。 */
    static final float SUN_BLOCK = 0.85F;

    /** 影の地図(薄明光線 {@link CrepuscularRays} も雲の切れ目を探すのに読む。作った後は書き換えない)。 */
    static final class Map {
        int originX;
        int originZ;
        float coverage;
        int stormKey;
        float[] values;
    }

    private static Map shown;
    private static CompletableFuture<Map> pending;
    private static DynamicTexture texture;
    private static boolean uploadPending;
    private static boolean broken;
    private static ClientLevel lastLevel;
    private static float visibility = 1.0F;
    private static float lastBase;
    private static long lastBuildTick;

    /** 今の影の地図(無ければ null)。 */
    static Map current() {
        return shown;
    }

    /** 太陽の高さと昼の強さ(設定を掛ける前)。地図があるのはこれが 0 より大きい間だけ。 */
    static float base() {
        return lastBase;
    }

    /** カメラのあたりに日が差しているか(1 = 雲に隠れていない 〜 1 − {@link #SUN_BLOCK} = 厚い雲の影)。光の筋・木漏れ日などが掛ける。 */
    public static float sunVisibility() {
        return visibility;
    }

    /** 太陽の高さと昼の強さ(0〜1、設定は掛けない)。地図は影か薄明光線のどちらかの設定が 0 より大きければ作る。 */
    private static float base(ClientLevel level, Vec3 sun, float partialTick) {
        if (!VoxelClouds.active()) {
            return 0.0F;
        }
        float low = CanopyLight.smooth(Mth.clamp(((float) sun.y - 0.12F) / 0.19F, 0.0F, 1.0F));
        return low * CanopyLight.daylight(level, sun, partialTick);
    }

    private static float shadowSetting() {
        return OvergrownVegetationConfig.COMMON.cloudShadows.get().floatValue();
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.isPaused() || broken) {
            return;
        }
        float target = 1.0F;
        Map m = shown;
        Vec3 sun = CanopyLight.sunDirection(level, 1.0F);
        float s = base(level, sun, 1.0F) * shadowSetting();
        if (m != null && s > 0.0F) {
            Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
            target = 1.0F - SUN_BLOCK * Math.min(1.0F, s * 1.5F) * sample(m, level, cam.x, cam.y, cam.z, sun, 1.0F);
        }
        // 雲の縁はもともと 8 ブロックかけてぼけるので、ここは tick ごとの段だけをならす。
        visibility += (target - visibility) * 0.15F;
    }

    /**
     * 地面の場所ごとの日の差し方(1 = 雲に隠れていない 〜 1 − {@link #SUN_BLOCK})を引く道具。1 フレームに 1 回 {@link #groundSun} で用意し、
     * 場所ごとには地図を補間するだけ。地面の雲の影(シェーダー {@code ov_cloud.glsl})と同じく、その場所から太陽へ伸ばした線で引く。
     *
     * <p>2026-10-22: 木漏れ日の斑点({@link CanopyDapple})は {@link #sunVisibility()}(カメラから太陽への線)で明るさを決めていて、
     * カメラを上下に動かすと雲の層で引く点が横へずれ(高さの差 × 太陽の傾き)、雲の縁の近くでは下りると斑点が一斉に消え・上ると戻った
     * (ユーザーの runClient。DH が入っているとふつうの雲を切っていてもこの MOD の雲が出る({@link VoxelClouds#active()})ので、DH 入りでだけ起きた)。
     */
    static final class GroundSun {
        private Map map;
        private double kx;
        private double kz;
        private double offsetX;
        private double offsetZ;
        private float block;

        /** 地面の (x, y, z) の日の差し方。 */
        float at(double x, double y, double z) {
            Map m = this.map;
            if (m == null || this.block <= 0.0F) {
                return 1.0F;
            }
            double qx = (x + this.kx * (SHADOW_Y - y) - this.offsetX) / CloudField.CELL_XZ - m.originX - 0.5;
            double qz = (z + this.kz * (SHADOW_Y - y) - this.offsetZ) / CloudField.CELL_XZ - m.originZ - 0.5;
            int i = Mth.floor(qx);
            int j = Mth.floor(qz);
            if (i < 0 || j < 0 || i >= SIZE - 1 || j >= SIZE - 1) {
                return 1.0F;
            }
            float fx = (float) (qx - i);
            float fz = (float) (qz - j);
            float a = m.values[j * SIZE + i];
            float b = m.values[j * SIZE + i + 1];
            float c = m.values[(j + 1) * SIZE + i];
            float d = m.values[(j + 1) * SIZE + i + 1];
            return 1.0F - this.block * Mth.lerp(fz, Mth.lerp(fx, a, b), Mth.lerp(fx, c, d));
        }
    }

    private static final GroundSun GROUND_SUN = new GroundSun();

    /** 今のフレームの {@link GroundSun}(描画のスレッドだけで使う。地図・雲の影が無ければどこでも 1)。 */
    static GroundSun groundSun(ClientLevel level, float partialTick) {
        GroundSun g = GROUND_SUN;
        g.map = null;
        Map m = shown;
        if (broken || m == null) {
            return g;
        }
        Vec3 sun = CanopyLight.sunDirection(level, partialTick);
        float s = base(level, sun, partialTick) * shadowSetting();
        if (s <= 0.0F || sun.y <= 0.0) {
            return g;
        }
        double t = (double) level.getGameTime() + partialTick;
        g.map = m;
        g.kx = sun.x / sun.y;
        g.kz = sun.z / sun.y;
        g.offsetX = CloudField.offsetX(t);
        g.offsetZ = CloudField.offsetZ(t);
        g.block = SUN_BLOCK * Math.min(1.0F, s * 1.5F);
        return g;
    }

    /** 地図を CPU で引く(シェーダーと同じ式・同じ補間)。 */
    private static float sample(Map m, ClientLevel level, double x, double y, double z, Vec3 sun, float partialTick) {
        double kx = sun.x / sun.y;
        double kz = sun.z / sun.y;
        long gameTime = level.getGameTime();
        double qx = CloudField.fieldX(x + kx * (SHADOW_Y - y), gameTime, partialTick) / CloudField.CELL_XZ - m.originX - 0.5;
        double qz = CloudField.fieldZ(z + kz * (SHADOW_Y - y), gameTime, partialTick) / CloudField.CELL_XZ - m.originZ - 0.5;
        int i = Mth.floor(qx);
        int j = Mth.floor(qz);
        if (i < 0 || j < 0 || i >= SIZE - 1 || j >= SIZE - 1) {
            return 0.0F;
        }
        float fx = (float) (qx - i);
        float fz = (float) (qz - j);
        float a = m.values[j * SIZE + i];
        float b = m.values[j * SIZE + i + 1];
        float c = m.values[(j + 1) * SIZE + i];
        float d = m.values[(j + 1) * SIZE + i + 1];
        return Mth.lerp(fz, Mth.lerp(fx, a, b), Mth.lerp(fx, c, d));
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY || broken || SodiumTerrain.unsupported()) {
            return;
        }
        ShaderInstance[] shaders = {GameRenderer.getRendertypeSolidShader(), GameRenderer.getRendertypeCutoutShader(),
                GameRenderer.getRendertypeCutoutMippedShader(), GameRenderer.getRendertypeTranslucentShader()};
        try {
            if (!apply(event, shaders)) {
                off(shaders);
            }
        } catch (RuntimeException e) {
            broken = true;
            visibility = 1.0F;
            off(shaders);
            OvergrownVegetation.LOGGER.error("雲の影で例外が出たので、このセッションでは使わない", e);
        }
    }

    private static void off(ShaderInstance[] shaders) {
        for (ShaderInstance shader : shaders) {
            if (shader != null) {
                shader.safeGetUniform("OvCloudA").set(0.0F, 0.0F, 0.0F, 0.0F);
            }
        }
    }

    private static boolean apply(RenderLevelStageEvent event, ShaderInstance[] shaders) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return false;
        }
        if (level != lastLevel) {
            lastLevel = level;
            shown = null;
            pending = null;
            visibility = 1.0F;
        }
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 sun = CanopyLight.sunDirection(level, partialTick);
        float base = base(level, sun, partialTick);
        lastBase = 0.0F;
        float strength = base * shadowSetting();
        float coverage = VoxelClouds.coverage(level, partialTick);
        if (base <= 0.001F || coverage <= 0.0F || strength <= 0.0F && OvergrownVegetationConfig.COMMON.crepuscularRays.get() <= 0.0) {
            return false;
        }
        Vec3 cam = event.getCamera().getPosition();
        long gameTime = level.getGameTime();
        double kx = sun.x / sun.y;
        double kz = sun.z / sun.y;
        double px = CloudField.fieldX(cam.x + kx * (SHADOW_Y - cam.y), gameTime, partialTick);
        double pz = CloudField.fieldZ(cam.z + kz * (SHADOW_Y - cam.y), gameTime, partialTick);
        int ox = Mth.floor(px / CloudField.CELL_XZ) - SIZE / 2;
        int oz = Mth.floor(pz / CloudField.CELL_XZ) - SIZE / 2;

        if (pending != null && pending.isDone()) {
            shown = pending.join();
            pending = null;
            uploadPending = true;
        }
        // 積乱雲も影を落とす: 地図の画素の太陽の線を、高さ 40 ごとに積乱雲の中か確かめる(背の高い雲の影は朝夕にとても遠くへ伸びるので、
        // 地図の外の雲も、太陽の向きにずれる分だけ広げて探す)。巨大な雲のまわりの雲海も下の層の柱として影になる。
        double span0 = SIZE * (double) CloudField.CELL_XZ;
        double pad = Math.min(3000.0, Math.max(Math.abs(kx), Math.abs(kz)) * (CloudField.GIANT_TOP - SHADOW_Y) + 200.0);
        java.util.List<CloudField.Storm> storms = VoxelClouds.storms(level, partialTick, coverage,
                ox * (double) CloudField.CELL_XZ - pad, oz * (double) CloudField.CELL_XZ - pad,
                ox * (double) CloudField.CELL_XZ + span0 + pad, oz * (double) CloudField.CELL_XZ + span0 + pad);
        int key = 0;
        for (CloudField.Storm s : storms) {
            key = key * 31 + Math.round(s.maturity() * 32.0F) + (int) s.id();
        }
        boolean moved = shown == null || Math.abs(ox - shown.originX) > REBUILD_CELLS || Math.abs(oz - shown.originZ) > REBUILD_CELLS
                || Math.abs(coverage - shown.coverage) > VoxelClouds.REBUILD_COVERAGE;
        boolean grown = shown != null && key != shown.stormKey && gameTime - lastBuildTick >= VoxelClouds.STORM_REBUILD_TICKS;
        if (pending == null && (moved || grown)) {
            final float c = coverage;
            final int k = key;
            lastBuildTick = gameTime;
            final double skx = kx;
            final double skz = kz;
            pending = CompletableFuture.supplyAsync(() -> build(ox, oz, c, storms, k, skx, skz), CloudThread.EXECUTOR);
        }
        Map m = shown;
        if (m == null) {
            return false;
        }
        lastBase = base;
        if (texture == null) {
            texture = new DynamicTexture(SIZE, SIZE, false);
            uploadPending = true;
        }
        if (uploadPending) {
            NativeImage image = texture.getPixels();
            if (image != null) {
                for (int j = 0; j < SIZE; j++) {
                    for (int i = 0; i < SIZE; i++) {
                        int v = Math.round(m.values[j * SIZE + i] * 255.0F);
                        image.setPixelRGBA(i, j, 0xFF000000 | v);
                    }
                }
                texture.bind();
                // なめらかな補間(影の縁をぼかす)・縁の外は繰り返さない。
                image.upload(0, 0, 0, 0, 0, SIZE, SIZE, true, true, false, false);
            }
            uploadPending = false;
        }
        // 地図の原点(場の座標)を世界へ戻し(風で流れた分を足す)、カメラからの位置にする。
        double windX = CloudField.offsetX((double) gameTime + partialTick);
        double windZ = CloudField.offsetZ((double) gameTime + partialTick);
        float originX = (float) (m.originX * (double) CloudField.CELL_XZ + windX - cam.x);
        float originZ = (float) (m.originZ * (double) CloudField.CELL_XZ + windZ - cam.z);
        float span = SIZE * CloudField.CELL_XZ;
        for (ShaderInstance shader : shaders) {
            if (shader == null) {
                continue;
            }
            shader.setSampler("OvCloudMap", texture.getId());
            shader.safeGetUniform("OvCloudA").set((float) kx, (float) kz, (float) (SHADOW_Y - cam.y), strength);
            shader.safeGetUniform("OvCloudB").set(originX, originZ, span, EDGE);
            shader.safeGetUniform("OvCloudC").set((float) sun.x, (float) sun.y, (float) sun.z, 0.0F);
        }
        return true;
    }

    /** 場から影の地図を作る(別スレッド)。値 = 柱の影の濃さ(厚さ 4 で 0.55 〜 厚さ 24 以上で 1)。 */
    private static Map build(int originX, int originZ, float coverage, java.util.List<CloudField.Storm> storms, int stormKey, double kx, double kz) {
        Map m = new Map();
        m.originX = originX;
        m.originZ = originZ;
        m.coverage = coverage;
        m.stormKey = stormKey;
        m.values = new float[SIZE * SIZE];
        float threshold = CloudField.threshold(coverage);
        int style = com.yayuy.overgrownvegetation.sky.CloudSky.style();
        for (int j = 0; j < SIZE; j++) {
            for (int i = 0; i < SIZE; i++) {
                CloudField.Column c = CloudField.column(originX + i, originZ + j, threshold, coverage, storms, style);
                if (c.present()) {
                    m.values[j * SIZE + i] = Mth.clamp(0.45F + (c.top() - c.bottom()) / 40.0F, 0.0F, 1.0F);
                }
                if (!storms.isEmpty() && m.values[j * SIZE + i] < 1.0F
                        && CloudField.shadowLine(storms, (originX + i + 0.5) * CloudField.CELL_XZ, (originZ + j + 0.5) * CloudField.CELL_XZ, SHADOW_Y, kx, kz)) {
                    m.values[j * SIZE + i] = 1.0F;
                }
            }
        }
        return m;
    }

    private CloudShadows() {
    }
}
