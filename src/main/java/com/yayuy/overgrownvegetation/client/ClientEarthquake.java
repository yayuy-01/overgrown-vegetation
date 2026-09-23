package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.network.EarthquakeShakePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * 地割れ(地震)の画面揺れのクライアント側の状態。
 *
 * <p>{@link EarthquakeShakePayload} を受け取るたびに「震央・経過 tick・タイムライン」を丸ごと差し替える
 * (パケット取りこぼしに強い)。クライアント tick で経過 tick を自前で進め、
 * {@link com.yayuy.overgrownvegetation.mixin.client.CameraShakeMixin} が毎フレーム
 * {@link #shakeIntensity} を読んでカメラを揺らす。
 *
 * <p>強度のエンベロープ:
 * <ul>
 *   <li>0 〜 preTicks: 0 →(最大の 30%)へ線形に立ち上がる(「徐々に揺れ始める」)。</li>
 *   <li>preTicks 〜 preTicks+formTicks: 30% → 100% へ(「大きく揺れながら地割れが走る」)。</li>
 *   <li>preTicks+formTicks 〜 +fadeTicks: 100% → 0% へ(「地割れ後、ゆっくり収まる」)。</li>
 * </ul>
 * さらに震央からの距離で減衰する(下限 0.3 倍 — 遠くのプレイヤーにも地震は伝わる)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class ClientEarthquake {

    /** 揺れが 0.30 → 最大 へ立ち上がるのにかける最大 tick 数(形成がこれより長くても、あとは最大維持)。 */
    private static final double RAMP_TICKS = 300.0D;

    private static boolean active;
    private static double x;
    private static double y;
    private static double z;
    private static double elapsed;
    private static int preTicks;
    private static int formTicks;
    private static int fadeTicks;
    private static float maxIntensity;
    private static int radius;

    public static void onPayload(EarthquakeShakePayload payload) {
        x = payload.x();
        y = payload.y();
        z = payload.z();
        elapsed = payload.elapsed();
        preTicks = payload.preTicks();
        formTicks = payload.formTicks();
        fadeTicks = payload.fadeTicks();
        maxIntensity = payload.intensity();
        radius = Math.max(1, payload.radius());
        active = maxIntensity > 0.0F && elapsed < totalTicks();
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!active) {
            return;
        }
        if (Minecraft.getInstance().level == null) {
            clear();
            return;
        }
        if (Minecraft.getInstance().isPaused()) {
            return;
        }
        elapsed += 1.0D;
        if (elapsed >= totalTicks()) {
            clear();
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
    }

    public static void clear() {
        active = false;
    }

    public static boolean isActive() {
        return active;
    }

    private static int totalTicks() {
        return preTicks + formTicks + fadeTicks;
    }

    /**
     * 指定したカメラ位置での現在の揺れ強度(0 なら揺らさない)。{@code partialTick} でサブ tick 補間する。
     */
    public static float shakeIntensity(double camX, double camZ, float partialTick) {
        if (!active) {
            return 0.0F;
        }
        double e = elapsed + partialTick;
        // 形成が長い(45秒など)場合でも延々とゆっくり強くなり続けないよう、揺れは形成開始から
        // 最大 RAMP_TICKS かけて最大へ達し、あとは形成完了まで最大を維持する。
        double rampTicks = Math.min(formTicks, RAMP_TICKS);
        double env;
        if (e < preTicks) {
            env = preTicks <= 0 ? 0.30D : 0.30D * (e / preTicks);
        } else if (e < preTicks + formTicks) {
            double t = rampTicks <= 0 ? 1.0D : Math.min(1.0D, (e - preTicks) / rampTicks);
            env = Mth.lerp(t, 0.30D, 1.0D);
        } else {
            double t = fadeTicks <= 0 ? 1.0D : (e - preTicks - formTicks) / fadeTicks;
            env = Math.max(0.0D, 1.0D - t);
        }
        env *= maxIntensity;

        double dx = camX - x;
        double dz = camZ - z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        double atten = Mth.clamp(1.15D - dist / radius, 0.30D, 1.0D);
        return (float) (env * atten);
    }

    private ClientEarthquake() {
    }
}
