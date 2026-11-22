package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.g1.skill.G1SporeCloud;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * G1 の胞子の煙の中の「緑の霞み」(2026-11-12、能力の第 1 回。チビゾンビの 1 段目の (c)「目くらまし + 暴露量」11-09 ユーザー)。
 * カメラが霞みの付いた煙({@link G1SporeCloud#haze()})の中にいる度合いだけ、霧を {@link #FOG} ブロックまで縮めて黄緑に染める
 * (近くしか見えない。煙を出ればすぐ戻る)。作りは雲の中の霧({@code client/sky/CloudInterior})と同じ(霧の後段に入る)。
 * 数値は【仮】。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class G1SporeHaze {

    /** 中にいるとき見える距離(ブロック)。【仮】 */
    static final float FOG = 3.5F;
    /** 霞みの色(胞子の黄緑、暗め)。【仮】 */
    static final float R = 0.42F, G = 0.52F, B = 0.24F;
    /** 出入りをならす速さ(1 tick に寄せる割合)。出るときは速く。【仮】 */
    static final float IN = 0.25F, OUT = 0.5F;

    private static float amount;
    private static float prev;

    private G1SporeHaze() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        prev = amount;
        if (level == null || mc.isPaused() || mc.player == null) {
            if (level == null) amount = prev = 0.0F;
            return;
        }
        float target = 0.0F;
        if (!mc.player.isSpectator()) {
            Vec3 eye = mc.gameRenderer.getMainCamera().getPosition();
            for (G1SporeCloud c : level.getEntitiesOfClass(G1SporeCloud.class, new AABB(eye, eye).inflate(6.0D))) {
                if (c.haze() && c.contains(eye)) {
                    target = Math.max(target, c.thickness());
                }
            }
        }
        amount += (target - amount) * (target > amount ? IN : OUT);
        if (amount < 0.002F) amount = 0.0F;
    }

    private static float amount(float partialTick) {
        return Mth.lerp(partialTick, prev, amount);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onRenderFog(ViewportEvent.RenderFog event) {
        float a = amount((float) event.getPartialTick());
        if (a <= 0.002F || event.getType() != FogType.NONE) return;
        float far = Mth.lerp(a, event.getFarPlaneDistance(), Math.min(FOG, event.getFarPlaneDistance()));
        float near = Mth.lerp(a, event.getNearPlaneDistance(), Math.min(0.0F, event.getNearPlaneDistance()));
        event.setFarPlaneDistance(far);
        event.setNearPlaneDistance(Math.min(near, far * 0.3F));
        event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onFogColor(ViewportEvent.ComputeFogColor event) {
        float a = amount((float) event.getPartialTick());
        if (a <= 0.002F || event.getCamera().getFluidInCamera() != FogType.NONE) return;
        event.setRed(Mth.lerp(a, event.getRed(), R));
        event.setGreen(Mth.lerp(a, event.getGreen(), G));
        event.setBlue(Mth.lerp(a, event.getBlue(), B));
    }
}
