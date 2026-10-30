package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * 乱気流(2026-10-17 深夜。ユーザーが選んだ「乱気流」= 積乱雲の中で視点やエリトラが揺さぶられる)。積乱雲の中の度合い({@link CloudInterior#storm})だけで効く
 * (下の層・雲の海では揺れない)。
 *
 * <ul>
 *   <li>視点: 傾き(ロール)・上下・左右をゆっくりした雑音(1〜3 Hz の正弦の和)で揺らす。バニラの設定「画面のゆがみ効果」を掛ける。</li>
 *   <li>押される: 上昇気流と下降気流(上下が主、横は少し)。エリトラで滑空中は強く、クリエイティブで飛んでいるときと観戦モードは押さない。
 *       プレイヤーの動きはクライアントが決めるので、手元のプレイヤーの速さに足す。</li>
 * </ul>
 * 数値はすべて仮決め。設定 {@code cloudTurbulence}(0〜1)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class CloudTurbulence {
    /** 視点の揺れの大きさ(度。傾き・上下・左右)。 */
    static final float ROLL = 3.0F;
    static final float PITCH = 1.4F;
    static final float YAW = 0.9F;
    /** 押す強さ(1 tick に足す速さ、ブロック/tick。上下・横)。滑空中はさらに倍。 */
    static final double PUSH_UP = 0.018;
    static final double PUSH_SIDE = 0.008;

    private static long ticks;

    static float strength(float partialTick) {
        return CloudInterior.storm(partialTick) * CloudInterior.setting()
                * OvergrownVegetationConfig.COMMON.cloudTurbulence.get().floatValue();
    }

    @SubscribeEvent
    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        float pt = (float) event.getPartialTick();
        float s = strength(pt);
        Minecraft mc = Minecraft.getInstance();
        if (s <= 0.001F || mc.isPaused()) {
            return;
        }
        s *= mc.options.screenEffectScale().get().floatValue();
        double t = (ticks + pt) / 20.0;
        float roll = (float) (Math.sin(t * 2.1) * 0.55 + Math.sin(t * 5.3 + 1.0) * 0.3 + Math.sin(t * 11.7 + 2.0) * 0.15);
        float pitch = (float) (Math.sin(t * 3.1 + 0.5) * 0.6 + Math.sin(t * 8.9 + 2.5) * 0.4);
        float yaw = (float) (Math.sin(t * 1.7 + 4.0) * 0.6 + Math.sin(t * 6.1) * 0.4);
        event.setRoll(event.getRoll() + roll * ROLL * s);
        event.setPitch(Mth.clamp(event.getPitch() + pitch * PITCH * s, -90.0F, 90.0F));
        event.setYaw(event.getYaw() + yaw * YAW * s);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.isPaused()) {
            return;
        }
        ticks++;
        float s = strength(1.0F);
        if (s <= 0.001F || player.isSpectator() || player.getAbilities().flying || player.isPassenger()) {
            return;
        }
        double t = ticks / 20.0;
        // 上下は 2〜4 秒でうねる気流 + 細かいがたつき。横は少し。
        double up = Math.sin(t * 1.9) * 0.6 + Math.sin(t * 4.7 + 1.3) * 0.25 + Math.sin(t * 13.1 + 0.4) * 0.15;
        double sx = Math.sin(t * 2.3 + 2.0) * 0.7 + Math.sin(t * 7.9) * 0.3;
        double sz = Math.sin(t * 2.9 + 4.0) * 0.7 + Math.sin(t * 6.7 + 1.0) * 0.3;
        double k = s * (player.isFallFlying() ? 2.0 : 1.0);
        Vec3 v = player.getDeltaMovement();
        player.setDeltaMovement(v.x + sx * PUSH_SIDE * k, v.y + up * PUSH_UP * k, v.z + sz * PUSH_SIDE * k);
    }

    private CloudTurbulence() {
    }
}
