package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.SaltimnesEntity;
import com.yayuy.overgrownvegetation.network.SaltimnesPinPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * サルティムネスに押さえ込まれた本人のカメラ(2026-11-15、案 b = ユーザー決定)。
 * <ul>
 *   <li>押さえ込まれた瞬間に地面へ押し倒し(泳ぐ姿勢。立たせると目が首の中に入る)、一度だけ視線を真上の頭(口)へ向ける。その後は自分で視線を動かせる(殴り返せる)【仮・全体管理】。</li>
 *   <li>放されたら押し倒しを戻す。</li>
 *   <li>噛まれるたびに、画面を小さく揺らす({@link #SHAKE_TICKS} tick で収まる。上下 {@link #SHAKE_PITCH}・横の傾き {@link #SHAKE_ROLL} 度)【仮】。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class SaltimnesPinCamera {

    static final int SHAKE_TICKS = 6;
    static final float SHAKE_PITCH = 2.5F, SHAKE_ROLL = 3.0F;
    /** 頭(口)の高さ: サルティムネスの足元から(ブロック)。もたげた口先 1.3〜2.2 の真ん中【仮】。 */
    static final double HEAD_UP = 1.6D;
    /** 押し倒された(泳ぐ姿勢の)目の高さ(ブロック)。 */
    static final double PRONE_EYE = 0.4D;

    private static long shakeUntil = Long.MIN_VALUE;
    private static float shakeSign = 1.0F;

    private SaltimnesPinCamera() {
    }

    public static void receive(SaltimnesPinPayload p) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return;
        Entity e = mc.level.getEntity(p.mob());
        if (p.event() == SaltimnesPinPayload.END) {
            player.setForcedPose(null);
            return;
        }
        if (p.event() == SaltimnesPinPayload.START && e instanceof SaltimnesEntity s) {
            player.setForcedPose(net.minecraft.world.entity.Pose.SWIMMING);   // サーバーと同じく地面へ押し倒す(体の高さ・目の高さを揃える)
            float r = s.getYRot() * Mth.DEG_TO_RAD;
            double front = SaltimnesEntity.LEAP.bodyFront() * s.getScale();
            Vec3 head = new Vec3(s.getX() - Mth.sin(r) * front, s.getY() + HEAD_UP * s.getScale(), s.getZ() + Mth.cos(r) * front);
            Vec3 d = head.subtract(player.position().add(0.0D, PRONE_EYE, 0.0D));   // 姿勢はこの後の tick で変わるので、伏せた目の高さで向ける
            float yaw = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90.0F;
            float pitch = (float) -(Mth.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)) * Mth.RAD_TO_DEG);
            player.setYRot(yaw);
            player.setXRot(Mth.clamp(pitch, -90.0F, 90.0F));
            player.yRotO = yaw;
            player.xRotO = player.getXRot();
            player.setYHeadRot(yaw);
        } else if (p.event() == SaltimnesPinPayload.BITE) {
            shakeUntil = mc.level.getGameTime() + SHAKE_TICKS;
            shakeSign = -shakeSign;
        }
    }

    @SubscribeEvent
    public static void onCamera(ViewportEvent.ComputeCameraAngles event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        double left = shakeUntil - (mc.level.getGameTime() + event.getPartialTick());
        if (left <= 0.0D) return;
        float k = (float) (left / SHAKE_TICKS);   // 1 → 0 で収まる
        float wob = Mth.sin((float) (left * 2.6D)) * k;
        event.setPitch(event.getPitch() + SHAKE_PITCH * wob);
        event.setRoll(event.getRoll() + SHAKE_ROLL * wob * shakeSign);
    }
}
