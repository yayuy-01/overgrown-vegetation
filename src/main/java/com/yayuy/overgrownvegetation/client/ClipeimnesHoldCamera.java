package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.ClipeimnesEntity;
import com.yayuy.overgrownvegetation.network.ClipeimnesHoldPayload;
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
 * クリペイムネスに抱え込まれた本人のカメラ(2026-11-15。サルティムネスの押さえ込みのカメラ {@link SaltimnesPinCamera} にそろえた【仮・全体管理の案】)。
 * <ul>
 *   <li>抱えられた瞬間に一度だけ視線を頭(口。前 {@link #HEAD_FRONT}・高さ {@link #HEAD_UP})へ向ける。その後は自分で視線を動かせる(殴り返せる)。
 *       押し倒しはしない(鉤の輪の中に立ったまま抱えられる)。</li>
 *   <li>締められる(噛まれる)たびに画面を小さく揺らす({@link #SHAKE_TICKS} tick で収まる)【仮】。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class ClipeimnesHoldCamera {

    static final int SHAKE_TICKS = 6;
    static final float SHAKE_PITCH = 2.0F, SHAKE_ROLL = 3.5F;
    /** 頭の位置: 体の中心から前へ・足元から上へ(ブロック。アニメの hold の頭 = 前 2.5・高さ 2.5)。 */
    static final double HEAD_FRONT = 2.5D, HEAD_UP = 2.5D;

    private static long shakeUntil = Long.MIN_VALUE;
    private static float shakeSign = 1.0F;

    private ClipeimnesHoldCamera() {
    }

    public static void receive(ClipeimnesHoldPayload p) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return;
        Entity e = mc.level.getEntity(p.mob());
        if (p.event() == ClipeimnesHoldPayload.START && e instanceof ClipeimnesEntity c) {
            float r = c.getYRot() * Mth.DEG_TO_RAD;
            Vec3 head = new Vec3(c.getX() - Mth.sin(r) * HEAD_FRONT * c.getScale(), c.getY() + HEAD_UP * c.getScale(), c.getZ() + Mth.cos(r) * HEAD_FRONT * c.getScale());
            Vec3 d = head.subtract(player.getEyePosition());
            float yaw = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90.0F;
            float pitch = (float) -(Mth.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)) * Mth.RAD_TO_DEG);
            player.setYRot(yaw);
            player.setXRot(Mth.clamp(pitch, -90.0F, 90.0F));
            player.yRotO = yaw;
            player.xRotO = player.getXRot();
            player.setYHeadRot(yaw);
        } else if (p.event() == ClipeimnesHoldPayload.SQUEEZE) {
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
        float k = (float) (left / SHAKE_TICKS);
        float wob = Mth.sin((float) (left * 2.6D)) * k;
        event.setPitch(event.getPitch() + SHAKE_PITCH * wob);
        event.setRoll(event.getRoll() + SHAKE_ROLL * wob * shakeSign);
    }
}
