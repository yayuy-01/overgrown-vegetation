package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.sky.CloudField;
import com.yayuy.overgrownvegetation.sky.CloudSky;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 雲の上の光芒(2026-10-18。ユーザーが選んだ「雲の上の光芒」= 雲の切れ目や積乱雲の縁から、太陽の向きへ伸びる光の筋)。
 *
 * <ul>
 *   <li>カメラが雲の上(高空の晴天の高さ)にいる昼、近くの積乱雲(2500 ブロックまで)の上の方のこぶの縁から、日の光の進む向き(太陽と反対の向き)へ
 *       長さ 900 の淡い光の帯を伸ばす(こぶの縁を光がかすめて通る筋)。帯はカメラの方を向き、太陽の方を見るほど明るい(光は前へ散る)。</li>
 *   <li>こぶは積乱雲ごとに上の 6 つ。場の座標で覚える(雲と一緒に流れる)。20 tick ごとに選び直す。</li>
 *   <li>足し算の光で、奥行きは書かない。描画距離の奥の面より遠い頂点は、向きを変えずにカメラの方へ寄せる(稲妻と同じ)。近くの雲より後に描く(天気の後)。</li>
 * </ul>
 * 数値はすべて仮決め。設定 {@code skyRays}。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class HighRays {
    static final int PER_STORM = 6;
    static final double RANGE = 2500.0;
    static final double LENGTH = 900.0;
    static final float ALPHA = 0.11F;

    private record Ray(double fx, double y, double fz, float width) {
    }

    private static final List<Ray> RAYS = new ArrayList<>();
    private static long ticks;
    private static boolean broken;
    private static double reach = 400.0;

    static float setting() {
        return OvergrownVegetationConfig.COMMON.skyRays.get().floatValue();
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.isPaused() || broken || ++ticks % 20 != 0) {
            return;
        }
        RAYS.clear();
        if (setting() <= 0.0F || HighSky.clarity() < 0.05F || !VoxelClouds.active()) {
            return;
        }
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        long now = level.getGameTime();
        double fx = CloudField.fieldX(cam.x, now, 1.0F);
        double fz = CloudField.fieldZ(cam.z, now, 1.0F);
        Vec3 sun = CanopyLight.sunDirection(level, 1.0F);
        for (CloudField.Storm s : CloudInterior.storms()) {
            if (Math.hypot(s.x() - fx, s.z() - fz) > RANGE || s.maturity() < 0.5F) {
                continue;
            }
            List<CloudField.Puff> puffs = new ArrayList<>(CloudField.puffs(s));
            puffs.sort(Comparator.comparingDouble((CloudField.Puff p) -> p.y() + p.r() * p.squash()).reversed());
            int n = 0;
            for (CloudField.Puff p : puffs) {
                if (n >= PER_STORM) {
                    break;
                }
                if (p.r() < 30.0F) {
                    continue;
                }
                // こぶの縁(太陽の向きに垂直な方へ半径ぶんずらした所)を光がかすめる。左右どちらかは puff ごとに決まる。
                Vec3 side = sun.cross(new Vec3(0.0, 1.0, 0.0));
                if (side.lengthSqr() < 1.0E-4) {
                    side = new Vec3(1.0, 0.0, 0.0);
                }
                side = side.normalize().scale(((n & 1) == 0 ? 1.0 : -1.0) * p.r() * 0.95);
                RAYS.add(new Ray(p.x() + side.x, p.y() + p.r() * p.squash() * 0.6 + side.y, p.z() + side.z, p.r() * 0.95F));
                n++;
            }
        }
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_WEATHER || broken || RAYS.isEmpty()) {
            return;
        }
        boolean main = CanopyLight.drawToMainTarget();  // 「最高」では天気の層でなく主画面へ(CanopyLight#restoreWeatherTarget)
        try {
            render(event);
        } catch (RuntimeException e) {
            broken = true;
            OvergrownVegetation.LOGGER.error("雲の上の光芒で例外が出たので、このセッションでは使わない", e);
        } finally {
            CanopyLight.restoreWeatherTarget(main);
        }
    }

    private static void render(RenderLevelStageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            return;
        }
        float pt = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 sun = CanopyLight.sunDirection(level, pt);
        float day = SkyLight.smooth(Mth.clamp(((float) sun.y - 0.05F) / 0.2F, 0.0F, 1.0F));
        float strength = setting() * day * HighSky.clarity() * (1.0F - CloudSky.rawRain(level, pt))
                * (1.0F - CloudInterior.amount(pt) * CloudInterior.setting());
        if (strength <= 0.003F) {
            return;
        }
        Vec3 eye = event.getCamera().getPosition();
        reach = mc.gameRenderer.getDepthFar() * 0.9;
        long now = level.getGameTime();
        double windX = CloudField.offsetX((double) now + pt);
        double windZ = CloudField.offsetZ((double) now + pt);
        float warm = 1.0F - SkyLight.smooth(Mth.clamp(((float) sun.y - 0.15F) / 0.5F, 0.0F, 1.0F));
        float r = 1.0F;
        float g = Mth.lerp(warm, 0.96F, 0.8F);
        float b = Mth.lerp(warm, 0.88F, 0.55F);
        Vec3 down = sun.scale(-1.0);
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer c = buffers.getBuffer(CanopyLight.renderType());
        for (Ray ray : RAYS) {
            Vec3 top = new Vec3(ray.fx() + windX, ray.y(), ray.fz() + windZ);
            ribbon(c, top, down, ray.width(), eye, sun, ALPHA * strength, r, g, b);
        }
        buffers.endBatch(CanopyLight.renderType());
    }

    /** 帯 1 本(カメラの方を向き、芯が濃く縁が 0。始まりと先は薄れる。太陽の方を見るほど明るい)。 */
    private static void ribbon(VertexConsumer c, Vec3 top, Vec3 dir, float width, Vec3 eye, Vec3 sun, float alpha, float r, float g, float b) {
        final int rows = 8;
        Vec3[] prev = null;
        float prevA = 0.0F;
        for (int i = 0; i <= rows; i++) {
            float t = i / (float) rows;
            Vec3 p = top.add(dir.scale(LENGTH * t));
            Vec3 toEye = eye.subtract(p);
            Vec3 side = dir.cross(toEye);
            double len = side.length();
            if (len < 1.0E-5) {
                prev = null;
                continue;
            }
            side = side.scale(width * (1.0 + 0.6 * t) * 0.5 / len);
            Vec3 view = p.subtract(eye).normalize();
            float forward = (float) Math.pow(Math.max(0.0, view.dot(sun)), 3.0);
            float a = alpha * forward * SkyLight.smooth(Mth.clamp(t / 0.1F, 0.0F, 1.0F)) * (1.0F - SkyLight.smooth(Mth.clamp((t - 0.45F) / 0.55F, 0.0F, 1.0F)));
            Vec3[] row = {p.subtract(side).subtract(eye), p.subtract(eye), p.add(side).subtract(eye)};
            if (prev != null) {
                quad(c, prev[0], 0.0F, row[0], 0.0F, row[1], a, prev[1], prevA, r, g, b);
                quad(c, prev[1], prevA, row[1], a, row[2], 0.0F, prev[2], 0.0F, r, g, b);
            }
            prev = row;
            prevA = a;
        }
    }

    private static void quad(VertexConsumer c, Vec3 p0, float a0, Vec3 p1, float a1, Vec3 p2, float a2, Vec3 p3, float a3, float r, float g, float b) {
        vertex(c, p0, r, g, b, a0);
        vertex(c, p1, r, g, b, a1);
        vertex(c, p2, r, g, b, a2);
        vertex(c, p3, r, g, b, a3);
    }

    private static void vertex(VertexConsumer c, Vec3 p, float r, float g, float b, float a) {
        double l = p.length();
        if (l > reach) {
            p = p.scale(reach / l);
        }
        c.addVertex((float) p.x, (float) p.y, (float) p.z).setColor(r, g, b, Math.min(1.0F, a));
    }

    private HighRays() {
    }
}
