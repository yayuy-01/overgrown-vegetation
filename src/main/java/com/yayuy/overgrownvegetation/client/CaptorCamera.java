package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.ClipeimnesEntity;
import com.yayuy.overgrownvegetation.entity.SaltimnesEntity;
import com.yayuy.overgrownvegetation.network.CaptorPayload;
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
 * 捕まえて乗せる Mob に捕まった本人のカメラ(2026-11-16 コードの見直し ①14 で SaltimnesPinCamera・ClipeimnesHoldCamera をまとめた。数値は元のまま)。
 * <ul>
 *   <li>捕まった瞬間に一度だけ視線を頭(口)へ向ける。その後は自分で視線を動かせる(殴り返せる)。</li>
 *   <li>サルティムネスの押さえ込み(2026-11-15、案 b = ユーザー決定): 地面へ押し倒す(泳ぐ姿勢。立たせると目が首の中に入る)。
 *       頭は鼻先の高さ {@link #SALT_HEAD_UP}。向けるのは伏せた目の高さ {@link #PRONE_EYE} から。放されたら押し倒しを戻す。</li>
 *   <li>クリペイムネスの抱え込み【仮・全体管理】: 押し倒さない(鉤の輪の中に立ったまま)。頭は前 {@link #CLIP_HEAD_FRONT}・高さ {@link #CLIP_HEAD_UP}。</li>
 *   <li>噛まれる・締められるたびに画面を小さく揺らす({@link #SHAKE_TICKS} tick で収まる)【仮】。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class CaptorCamera {

    static final int SHAKE_TICKS = 6;
    /** 揺れの上下・横の傾き(度): サルティムネス・クリペイムネス。 */
    static final float SALT_SHAKE_PITCH = 2.5F, SALT_SHAKE_ROLL = 3.0F, CLIP_SHAKE_PITCH = 2.0F, CLIP_SHAKE_ROLL = 3.5F;
    /** サルティムネスの頭(口)の高さ: 足元から(ブロック)。もたげた口先 1.3〜2.2 の真ん中【仮】。 */
    static final double SALT_HEAD_UP = 1.6D;
    /** 押し倒された(泳ぐ姿勢の)目の高さ(ブロック)。 */
    static final double PRONE_EYE = 0.4D;
    /** クリペイムネスの頭の位置: 体の中心から前へ・足元から上へ(ブロック。アニメの hold の頭 = 前 2.5・高さ 2.5)。 */
    static final double CLIP_HEAD_FRONT = 2.5D, CLIP_HEAD_UP = 2.5D;

    private static long shakeUntil = Long.MIN_VALUE;
    private static float shakeSign = 1.0F, shakePitch, shakeRoll;

    private CaptorCamera() {
    }

    public static void receive(CaptorPayload p) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return;
        Entity e = mc.level.getEntity(p.mob());
        if (p.event() == CaptorPayload.END) {
            player.setForcedPose(null);   // 押し倒しを戻す(押し倒していなければ何も変わらない)
            return;
        }
        if (p.event() == CaptorPayload.START) {
            Vec3 head, eye;
            if (e instanceof SaltimnesEntity s) {
                player.setForcedPose(net.minecraft.world.entity.Pose.SWIMMING);   // サーバーと同じく地面へ押し倒す(体の高さ・目の高さを揃える)
                float r = s.getYRot() * Mth.DEG_TO_RAD;
                double front = SaltimnesEntity.LEAP.bodyFront() * s.getScale();
                head = new Vec3(s.getX() - Mth.sin(r) * front, s.getY() + SALT_HEAD_UP * s.getScale(), s.getZ() + Mth.cos(r) * front);
                eye = player.position().add(0.0D, PRONE_EYE, 0.0D);   // 姿勢はこの後の tick で変わるので、伏せた目の高さで向ける
            } else if (e instanceof ClipeimnesEntity c) {
                float r = c.getYRot() * Mth.DEG_TO_RAD;
                head = new Vec3(c.getX() - Mth.sin(r) * CLIP_HEAD_FRONT * c.getScale(), c.getY() + CLIP_HEAD_UP * c.getScale(),
                        c.getZ() + Mth.cos(r) * CLIP_HEAD_FRONT * c.getScale());
                eye = player.getEyePosition();
            } else {
                return;
            }
            Vec3 d = head.subtract(eye);
            float yaw = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90.0F;
            float pitch = (float) -(Mth.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)) * Mth.RAD_TO_DEG);
            player.setYRot(yaw);
            player.setXRot(Mth.clamp(pitch, -90.0F, 90.0F));
            player.yRotO = yaw;
            player.xRotO = player.getXRot();
            player.setYHeadRot(yaw);
        } else if (p.event() == CaptorPayload.HIT) {
            boolean salt = e instanceof SaltimnesEntity;
            shakePitch = salt ? SALT_SHAKE_PITCH : CLIP_SHAKE_PITCH;
            shakeRoll = salt ? SALT_SHAKE_ROLL : CLIP_SHAKE_ROLL;
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
        event.setPitch(event.getPitch() + shakePitch * wob);
        event.setRoll(event.getRoll() + shakeRoll * wob * shakeSign);
    }
}
