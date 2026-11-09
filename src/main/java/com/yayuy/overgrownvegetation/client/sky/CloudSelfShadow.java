package com.yayuy.overgrownvegetation.client.sky;

import com.mojang.blaze3d.platform.NativeImage;
import com.yayuy.overgrownvegetation.client.atmosphere.SunLight;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.sky.CloudField;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 雲が雲に落とす影(2026-10-17 深夜。ユーザーが選んだ「雲が雲に落とす影」= 積乱雲の影が雲の海に落ちる)。
 *
 * <p>太陽の向きの影の地図: 高さ {@link #REF} の平面の 1 画素(場の座標で {@link #TEXEL} ブロック)ごとに、そこを通る太陽の向きの線を上からたどり、
 * 最初に積乱雲の塊に入る高さ(いちばん太陽に近い所)を覚える。雲のシェーダー({@code voxel_clouds.fsh})は面の点から同じ線で地図を引き、
 * 覚えた高さより下なら影(その点より上で日の光が積乱雲に遮られている)。下の層・雲の海・雲の海の地形と、積乱雲の自分の日の当たらない側に落ちる。
 *
 * <p>地図は別スレッドで作り、太陽の向きが少し変わる・カメラが離れる・積乱雲が育つたびに作り直す(作った時の太陽の向きで引くので、作り直すまで影は少し遅れる)。
 * 昼だけ(太陽の高さ 6° 以上)。数値はすべて仮決め。強さは地面の雲の影と同じ {@code sky.json shadows.strength}(前の版の設定 {@code cloudSelfShadows} の代わり)。
 * 2026-11-09 雲の段階 2a で parked の {@code client/CloudSelfShadow} から戻した(中身はそのまま)。
 */
final class CloudSelfShadow {
    static final int SIZE = 96;
    static final double TEXEL = 48.0;
    /** 地図の平面の高さ。 */
    static final double REF = 420.0;
    /** 線をたどる刻み(ブロック)。 */
    static final double STEP = 32.0;
    /** 覚える高さの幅(LOW_BASE から)。 */
    static final double RANGE = 2800.0;

    static final class Map {
        double originX;
        double originZ;
        double kx;
        double kz;
        int stormKey;
        float[] values;
    }

    private static Map shown;
    private static CompletableFuture<Map> pending;
    private static DynamicTexture texture;
    private static boolean uploadPending;
    private static long lastBuildTick;
    private static ClientLevel lastLevel;

    /** 今の地図(無ければ null)。 */
    static Map current() {
        return shown;
    }

    /** GPU の絵(地図が無ければ null)。 */
    static DynamicTexture texture() {
        return shown == null ? null : texture;
    }

    static float setting() {
        return DesignData.sky().shadows;
    }

    /** 毎フレーム(遠くの雲を描くとき)。場の座標の積乱雲と、カメラの場の座標。 */
    static void update(ClientLevel level, float partialTick, double fx, double fz, double camY, List<CloudField.Storm> storms, int stormKey) {
        if (level != lastLevel) {
            lastLevel = level;
            shown = null;
            pending = null;
        }
        if (pending != null && pending.isDone()) {
            shown = pending.join();
            pending = null;
            uploadPending = true;
        }
        Vec3 sun = SunLight.sunDirection(level, partialTick);
        if (setting() <= 0.0F || sun.y < 0.1 || storms.isEmpty()) {
            shown = null;
            return;
        }
        double kx = Mth.clamp(sun.x / sun.y, -4.0, 4.0);
        double kz = Mth.clamp(sun.z / sun.y, -4.0, 4.0);
        double cx = fx + kx * (REF - camY);
        double cz = fz + kz * (REF - camY);
        double half = SIZE * TEXEL * 0.5;
        Map m = shown;
        long gameTime = level.getGameTime();
        boolean stale = m == null || Math.abs(kx - m.kx) > 0.015 || Math.abs(kz - m.kz) > 0.015
                || Math.abs(cx - (m.originX + half)) > 600.0 || Math.abs(cz - (m.originZ + half)) > 600.0
                || m.stormKey != stormKey && gameTime - lastBuildTick >= VoxelClouds.STORM_REBUILD_TICKS;
        if (pending == null && stale) {
            lastBuildTick = gameTime;
            final double ox = Math.floor((cx - half) / TEXEL) * TEXEL;
            final double oz = Math.floor((cz - half) / TEXEL) * TEXEL;
            final List<CloudField.Storm> list = List.copyOf(storms);
            pending = CompletableFuture.supplyAsync(() -> build(ox, oz, kx, kz, list, stormKey), CloudThread.EXECUTOR);
        }
        if (uploadPending && shown != null) {
            if (texture == null) {
                texture = new DynamicTexture(SIZE, SIZE, false);
            }
            NativeImage image = texture.getPixels();
            if (image != null) {
                for (int j = 0; j < SIZE; j++) {
                    for (int i = 0; i < SIZE; i++) {
                        int v = Math.round(Mth.clamp(shown.values[j * SIZE + i], 0.0F, 1.0F) * 255.0F);
                        image.setPixelRGBA(i, j, 0xFF000000 | v);
                    }
                }
                texture.bind();
                image.upload(0, 0, 0, 0, 0, SIZE, SIZE, true, true, false, false);
            }
            uploadPending = false;
        }
    }

    private static Map build(double ox, double oz, double kx, double kz, List<CloudField.Storm> storms, int stormKey) {
        Map m = new Map();
        m.originX = ox;
        m.originZ = oz;
        m.kx = kx;
        m.kz = kz;
        m.stormKey = stormKey;
        m.values = new float[SIZE * SIZE];
        int top = CloudField.LOW_BASE;
        for (CloudField.Storm s : storms) {
            top = Math.max(top, s.topY());
        }
        for (int j = 0; j < SIZE; j++) {
            for (int i = 0; i < SIZE; i++) {
                double px = ox + (i + 0.5) * TEXEL;
                double pz = oz + (j + 0.5) * TEXEL;
                float value = 0.0F;
                outer:
                for (double y = top; y > CloudField.LOW_BASE + 4.0; y -= STEP) {
                    double x = px + kx * (y - REF);
                    double z = pz + kz * (y - REF);
                    for (CloudField.Storm s : storms) {
                        double r = s.reach();
                        if (y > s.topY() + 8 || Math.abs(x - s.x()) > r || Math.abs(z - s.z()) > r) {
                            continue;
                        }
                        if (CloudField.inside(CloudField.puffs(s), x, y, z)) {
                            value = (float) ((y - CloudField.LOW_BASE) / RANGE);
                            break outer;
                        }
                    }
                }
                m.values[j * SIZE + i] = value;
            }
        }
        return m;
    }

    private CloudSelfShadow() {
    }
}
