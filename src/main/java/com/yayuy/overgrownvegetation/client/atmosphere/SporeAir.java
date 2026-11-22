package com.yayuy.overgrownvegetation.client.atmosphere;

import com.mojang.blaze3d.platform.NativeImage;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.AtmosphereAir;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.network.SporeAirMapPayload;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import com.yayuy.overgrownvegetation.port.event.tick.ClientTickEvent;

/**
 * <b>胞子の空気</b>(2026-11-06。ユーザー決定「空気そのものが汚染を語る」: 風下の普通の平原にも、森に着く前から薄い黄緑の霞が流れてくる。
 * 胞子の無い所は澄んだ青のまま)。サーバーが 5 秒ごとに送る、まわり ±24 チャンクの<b>浮遊</b>の濃度の升({@link SporeAirMapPayload})を持ち、
 * <ul>
 *     <li>カメラの所の度合い {@link #level()}(0〜1。節 sporeAir の from〜full を平方根の物差しで。時定数 smoothSeconds で寄せる)
 *     —— {@link MistView} の霞の濃さと霧の色、{@link AmbientSpores} の森の外の升、{@link RainLook} の雨の濁りが読む。</li>
 *     <li>升の度合いのテクスチャ(1 チャンク 1 画素・線形補間。r = 度合い × 255)—— 地形シェーダー({@code ov_fog.glsl} の ovAirFog)が視線上 8 点で読み、
 *     カメラの所より濁った空気を通る分だけ霞を足す(森が見える前に、遠くの風下の空気が濁って見える)。</li>
 * </ul>
 * 森の中の見え方は変えない: 読む側が × (1 − 森の霧の度合い {@link ForestPresence#fogForest()}) で森の霞に譲る。
 * 届く前・オーバーワールドの外・場が止まっている世界は升が全部 0 = 何もしない。
 *
 * <p>通信はワールドに入る途中(最初の tick より前)に届くことがあるので、受け取る所でワールドの替わりを見る(memory client-payload-before-first-tick)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, value = Dist.CLIENT)
public final class SporeAir {

    private static float[] percent = new float[0];
    private static int minCx;
    private static int minCz;
    private static int size;
    private static boolean any;
    private static ClientLevel level;

    private static DynamicTexture texture;
    private static boolean dirty;
    private static int texMinCx;
    private static int texMinCz;
    private static int texSize;
    private static AtmosphereAir.SporeAirParams texParams;

    private static float shown;
    private static long lastMillis;

    /** 開発用の上書き(−1 = しない)。カメラの所の度合いを決めて撮り比べる。 */
    static float devLevel = -1.0F;

    private SporeAir() {
    }

    public static void accept(SporeAirMapPayload p) {
        level = Minecraft.getInstance().level;
        int n = p.size() * p.size();
        float[] next = new float[n];
        boolean found = false;
        for (int i = 0; i < n && i < p.levels().length; i++) {
            next[i] = SporeAirMapPayload.decode(p.levels()[i]);
            found |= next[i] > 0.0F;
        }
        percent = next;
        minCx = p.minCx();
        minCz = p.minCz();
        size = p.size();
        any = found;
        dirty = true;
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        percent = new float[0];
        size = 0;
        any = false;
        level = null;
        shown = 0.0F;
        dirty = true;
    }

    private static boolean current(ClientLevel now) {
        return any && now != null && now == level && DesignData.atmosphere().sporeAir().enabled();
    }

    /** 升の濃度 %(チャンクの真ん中の値を双一次で混ぜる。升の外・届いていなければ 0)。 */
    public static float percentAt(double x, double z) {
        ClientLevel now = Minecraft.getInstance().level;
        if (!current(now)) {
            return 0.0F;
        }
        double fx = x / 16.0 - 0.5 - minCx;
        double fz = z / 16.0 - 0.5 - minCz;
        int i0 = Mth.floor(fx);
        int j0 = Mth.floor(fz);
        float tx = (float) (fx - i0);
        float tz = (float) (fz - j0);
        float a = cell(i0, j0);
        float b = cell(i0 + 1, j0);
        float c = cell(i0, j0 + 1);
        float d = cell(i0 + 1, j0 + 1);
        return (a * (1 - tx) + b * tx) * (1 - tz) + (c * (1 - tx) + d * tx) * tz;
    }

    private static float cell(int i, int j) {
        return i < 0 || j < 0 || i >= size || j >= size ? 0.0F : percent[j * size + i];
    }

    /** その所の度合い(0〜1。平方根の物差し)。 */
    public static float levelAt(double x, double z) {
        return DesignData.atmosphere().sporeAir().level(percentAt(x, z));
    }

    /** カメラの所の胞子の空気の度合い(0〜1。寄せた値。森の中でも 1 になる —— 読む側が森の霞に譲る)。 */
    public static float level() {
        return devLevel >= 0.0F ? devLevel : shown;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.isPaused()) {
            lastMillis = 0L;
            if (mc.level == null) {
                shown = 0.0F;
            }
            return;
        }
        long now = Util.getMillis();
        float dt = lastMillis == 0L ? 0.0F : Math.min((now - lastMillis) / 1000.0F, 0.5F);
        lastMillis = now;
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        float target = levelAt(cam.x, cam.z);
        shown = ForestPresence.approach(shown, target, dt, DesignData.atmosphere().sporeAir().smoothSeconds());
    }

    // ---------------------------------------------------------------- 地形シェーダーへ(ov_fog.glsl の OvAir*)

    /** 升のテクスチャを作り直す(値・設計値が変わったときだけ)。描画のスレッドから。 */
    private static boolean upload() {
        ClientLevel now = Minecraft.getInstance().level;
        if (!current(now) || size <= 0) {
            return false;
        }
        AtmosphereAir.SporeAirParams p = DesignData.atmosphere().sporeAir();
        if (texture == null || texSize != size) {
            if (texture != null) {
                texture.close();
            }
            texture = new DynamicTexture(size, size, false);
            texture.setFilter(true, false);
            texSize = size;
            dirty = true;
        }
        if (!dirty && texParams == p) {
            return true;
        }
        NativeImage image = texture.getPixels();
        if (image == null) {
            return false;
        }
        for (int j = 0; j < size; j++) {
            for (int i = 0; i < size; i++) {
                int r = Mth.clamp(Math.round(p.level(percent[j * size + i]) * 255.0F), 0, 255);
                image.setPixelRGBA(i, j, 0xFF000000 | r); // ABGR の並び: r が下の 8 ビット
            }
        }
        texture.upload();
        texMinCx = minCx;
        texMinCz = minCz;
        texParams = p;
        dirty = false;
        return true;
    }

    /**
     * 地形シェーダーへ OvAir* を渡す({@link MistFog#upload} から、霧の地図の有無に関わらず毎フレーム)。
     * OvAirA = (升の原点 x・z をカメラからの相対で, 1 升のブロック 16, 升の数)、OvAirB = (1 ブロックあたりの霞(0 = 切る), カメラの所の度合い, -, -)、
     * OvAirC = (霞の色 rgb, -)。濃さ {@code density} は呼び手が森・夜・地下の分を掛けた値。
     */
    static void setUniforms(ShaderInstance shader, Vec3 cam, float density, float[] color) {
        boolean ok = density > 0.0F && upload();
        if (!ok) {
            shader.safeGetUniform("OvAirB").set(0.0F, 0.0F, 0.0F, 0.0F);
            return;
        }
        shader.setSampler("OvAirMap", texture.getId());
        shader.safeGetUniform("OvAirA").set((float) (texMinCx * 16.0 - cam.x), (float) (texMinCz * 16.0 - cam.z), 16.0F, (float) texSize);
        shader.safeGetUniform("OvAirB").set(density, level(), 0.0F, 0.0F);
        shader.safeGetUniform("OvAirC").set(color[0], color[1], color[2], 0.0F);
    }

    /** 開発用の 1 行。 */
    static String describe() {
        Minecraft mc = Minecraft.getInstance();
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        return String.format("胞子の空気 %.1f%% → %.2f(升 %d%s)", percentAt(cam.x, cam.z), level(), size, any ? "" : "・全部 0");
    }
}
