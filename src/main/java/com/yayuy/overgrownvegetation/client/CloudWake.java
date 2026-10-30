package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.skyboss.ClientSkyBoss;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.sky.CloudField;
import com.yayuy.overgrownvegetation.skyboss.SkyBossHitboxEntity;
import com.yayuy.overgrownvegetation.skyboss.SkyBossPose;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 雲を引く(2026-10-19。空の世界の順番 7「ボスの雲」。ユーザー「ボスによる雲引き(ボス以外もある程度速度が出ている状態で雲から出た際、雲を引くようにして欲しい)」、
 * 10-18 の一番の場面「ボスが雲を引きながらゆっくり巨体をあらわにする」)。クライアントだけの見た目(通信なし)。
 *
 * <ul>
 *   <li>雲から出たもの: 雲の中にいた間は「抱えた雲」が 1、出ると 1 tick に {@link #CARRY_DECAY} 倍で減る。抱えた雲が残っている間、後ろに雲のかけら(箱)を残す
 *       (数は抱えた雲 × 速さ)。かけらは出たものの速さの 3〜6 割で追いかけてから空気に止まり(雲の場の座標 = 風で流れる)、少し膨らみながら薄れて消える。
 *       雲の中かは {@link CloudInterior#solidAt}(雲の海・積乱雲・入道雲・吐いた雲・穴を数える)。</li>
 *   <li>ボス以外: カメラから {@link #ENTITY_RANGE} の中の、速さ {@link #ENTITY_MIN_SPEED} ブロック/tick 以上で動くもの(エリトラ・落ちるプレイヤー・ほかの MOD の飛行機など。
 *       観戦モードは除く)。かけらの大きさは体の幅の 1.2 倍(1.5〜8)。</li>
 *   <li>ボス: 空の巨大生物の体の輪切り({@link SkyBossPose} の slice。形は世界の座標なので、風のずれを引いて雲の場の座標へ)ごとに。速さの下限は無し(ゆっくり出てくる場面のため)。
 *       かけらは体の表面のすぐ外から外へ押し出され(体は頭の道をなぞって進むので、通り道のまわりに残る)、大きさは輪切りの半径の 0.3〜0.6 倍。</li>
 *   <li>描く: 雲と同じ遠く・近くの 2 回の描画の中で、雲を塗った後に奥行きを書かずに重ねる(すぐ後ろの雲の壁に消されない)。かけらは軸に沿った箱(ボクセルの雲と同じ)で、
 *       上の面が明るく下が暗い。シェーダー {@code cloud_wake}。</li>
 * </ul>
 * 数値はすべて仮決め。設定 {@code cloudWake}(0〜1)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class CloudWake {
    static final int MAX = 3000;
    static final double ENTITY_RANGE = 512.0;
    static final double ENTITY_MIN_SPEED = 0.4;
    static final float CARRY_DECAY = 0.95F;
    /** かけらが空気に止まっていく割合(1 tick ごとに速さに掛ける)。 */
    static final double DRAG = 0.93;

    private static final class Wisp {
        double x;
        double y;
        double z;
        double vx;
        double vy;
        double vz;
        float size;
        int age;
        int life;
        boolean boss;
    }

    private static final List<Wisp> WISPS = new ArrayList<>();
    private static final Map<Integer, float[]> CARRY = new HashMap<>();
    private static final RandomSource RANDOM = RandomSource.create();
    private static float[] bossCarry = new float[0];
    private static double[] bossPrev = new double[0];
    private static long bossPrevTick = Long.MIN_VALUE;
    private static List<CloudField.Storm> nearStorms = List.of();
    private static List<CloudField.Storm> bossStorms = List.of();
    private static long nearAt = Long.MIN_VALUE / 4;
    private static long bossAt = Long.MIN_VALUE / 4;
    private static ClientLevel lastLevel;
    private static double builtAt = Double.NaN;
    private static int quads;
    private static VertexBuffer vbo;
    private static int bossQuads;
    private static VertexBuffer bossVbo;
    private static int quadCount;
    private static boolean broken;
    private static int emitters;
    private static long ticks;
    private static final boolean DEV_LOG = Boolean.getBoolean("ov.devbridge");

    static float setting() {
        return OvergrownVegetationConfig.COMMON.cloudWake.get().floatValue();
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level != lastLevel) {
            lastLevel = level;
            WISPS.clear();
            CARRY.clear();
            bossPrevTick = Long.MIN_VALUE;
        }
        if (level == null || mc.isPaused() || broken) {
            return;
        }
        try {
            tick(mc, level);
        } catch (RuntimeException e) {
            broken = true;
            WISPS.clear();
            OvergrownVegetation.LOGGER.error("雲を引く演出で例外が出たので、このセッションでは使わない", e);
        }
    }

    private static void tick(Minecraft mc, ClientLevel level) {
        ticks++;
        for (Iterator<Wisp> it = WISPS.iterator(); it.hasNext(); ) {
            Wisp w = it.next();
            w.age++;
            if (w.age > w.life) {
                it.remove();
                continue;
            }
            w.x += w.vx;
            w.y += w.vy;
            w.z += w.vz;
            w.vx *= DRAG;
            w.vy *= DRAG;
            w.vz *= DRAG;
        }
        float setting = setting();
        if (setting <= 0.0F || !VoxelClouds.active() || !level.dimensionType().hasSkyLight()) {
            WISPS.clear();
            CARRY.clear();
            return;
        }
        long now = level.getGameTime();
        float coverage = VoxelClouds.coverage(level, 1.0F);
        float threshold = CloudField.threshold(coverage);
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        emitters = 0;
        // ボス以外: カメラのまわりの積乱雲で引く(20 tick ごとに引き直す)。
        double cfx = CloudField.fieldX(cam.x, now, 1.0F);
        double cfz = CloudField.fieldZ(cam.z, now, 1.0F);
        if (now - nearAt >= 20 || now < nearAt) {
            nearAt = now;
            double r = ENTITY_RANGE + 400.0;
            nearStorms = VoxelClouds.storms(level, 1.0F, coverage, cfx - r, cfz - r, cfx + r, cfz + r);
        }
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (Entity e : level.entitiesForRendering()) {
            if (e.isRemoved() || e.isSpectator() || e instanceof SkyBossHitboxEntity) {
                continue;
            }
            double y = e.getY() + e.getBbHeight() * 0.5;
            if (y < CloudField.LOW_BASE - 20 || y > CloudField.GIANT_TOP + 100 || e.distanceToSqr(cam) > ENTITY_RANGE * ENTITY_RANGE) {
                continue;
            }
            double vx = e.getX() - e.xo;
            double vy = e.getY() - e.yo;
            double vz = e.getZ() - e.zo;
            double speed = Math.sqrt(vx * vx + vy * vy + vz * vz);
            float[] carry = CARRY.get(e.getId());
            if (speed > 10.0 || speed < ENTITY_MIN_SPEED && carry == null) {
                continue;
            }
            seen.add(e.getId());
            double fx = CloudField.fieldX(e.getX(), now, 1.0F);
            double fz = CloudField.fieldZ(e.getZ(), now, 1.0F);
            boolean inside = CloudInterior.solidAt(nearStorms, threshold, coverage, fx, y, fz) > 0;
            if (carry == null) {
                carry = new float[1];
                CARRY.put(e.getId(), carry);
            }
            carry[0] = inside ? 1.0F : carry[0] * CARRY_DECAY;
            if (inside || carry[0] < 0.05F || speed < ENTITY_MIN_SPEED) {
                continue;
            }
            emitters++;
            // 空気に対する速さ(雲の場は風で動くので、風の分を引く)。
            double wx = CloudField.windX(now);
            double wz = CloudField.windZ(now);
            float size = Mth.clamp(e.getBbWidth() * 1.2F, 1.5F, 8.0F);
            int n = count(carry[0] * 2.6F * (float) Math.min(speed / 1.5, 2.0) * setting);
            for (int k = 0; k < n; k++) {
                double back = 0.3 + 0.6 * RANDOM.nextDouble();
                double px = fx - vx * back + (RANDOM.nextDouble() - 0.5) * e.getBbWidth();
                double py = y - vy * back + (RANDOM.nextDouble() - 0.5) * e.getBbHeight();
                double pz = fz - vz * back + (RANDOM.nextDouble() - 0.5) * e.getBbWidth();
                spawn(px, py, pz, (vx - wx), vy, (vz - wz), 0.3 + 0.3 * RANDOM.nextDouble(), size * (0.6F + 0.6F * RANDOM.nextFloat()),
                        40 + RANDOM.nextInt(50), size * 0.15, false);
            }
        }
        CARRY.keySet().retainAll(seen);
        // ボス: 体の輪切りごと。
        SkyBossPose pose = ClientSkyBoss.isActive() ? ClientSkyBoss.pose() : null;
        if (pose == null || pose.sliceCount <= 0) {
            bossPrevTick = Long.MIN_VALUE;
            return;
        }
        int ns = pose.sliceCount;
        if (bossCarry.length != ns) {
            bossCarry = new float[ns];
            bossPrev = new double[ns * 3];
            bossPrevTick = Long.MIN_VALUE;
        }
        long bt = ClientSkyBoss.flight().tick;
        if (bt == bossPrevTick) {
            return;
        }
        double hx = ClientSkyBoss.flight().x;
        double hz = ClientSkyBoss.flight().z;
        if (now - bossAt >= 30 || now < bossAt) {
            bossAt = now;
            double r = 2600.0;
            bossStorms = VoxelClouds.storms(level, 1.0F, coverage, hx - r, hz - r, hx + r, hz + r);
        }
        boolean havePrev = bossPrevTick != Long.MIN_VALUE && bt > bossPrevTick && bt - bossPrevTick < 40;
        // 体の形(SkyBossPose)は世界の座標(空気の座標に、その時刻の風のずれを足してある)。雲の場の座標へ戻す。
        double offX = CloudField.offsetX(bt);
        double offZ = CloudField.offsetZ(bt);
        double dt = havePrev ? bt - bossPrevTick : 1.0;
        for (int i = 0; i < ns; i++) {
            int o = i * 3;
            double half = pose.sLen[i] * 0.5;
            double cx = pose.sA[o] + pose.sEz[o] * half - offX;
            double cy = pose.sA[o + 1] + pose.sEz[o + 1] * half;
            double cz = pose.sA[o + 2] + pose.sEz[o + 2] * half - offZ;
            double vx = havePrev ? (cx - bossPrev[o]) / dt : 0.0;
            double vy = havePrev ? (cy - bossPrev[o + 1]) / dt : 0.0;
            double vz = havePrev ? (cz - bossPrev[o + 2]) / dt : 0.0;
            bossPrev[o] = cx;
            bossPrev[o + 1] = cy;
            bossPrev[o + 2] = cz;
            boolean inside = CloudInterior.solidAt(bossStorms, threshold, coverage, cx, cy, cz) > 0;
            bossCarry[i] = inside ? 1.0F : bossCarry[i] * (float) Math.pow(CARRY_DECAY, dt);
            if (inside || bossCarry[i] < 0.05F || !havePrev) {
                continue;
            }
            emitters++;
            double rx = pose.sRx[i];
            double ry = pose.sRy[i];
            float size = (float) Mth.clamp(Math.max(rx, ry) * 0.45, 6.0, 40.0);
            int n = count(bossCarry[i] * 1.2F * setting);
            double r = Math.max(rx, ry);
            for (int k = 0; k < n; k++) {
                // 体の表面のすぐ外(半径の 1.05〜1.3 倍)から、外へ押し出す(太さの 1.5〜4.5% / tick)。体は頭の道をなぞって進むので、
                // 表面の内側に置くと体が通り過ぎる前に消えて見えなかった(10-19 のテストクライアント)。外へ出たかけらは体の通り道のまわりに残る。
                double a = RANDOM.nextDouble() * Math.PI * 2.0;
                double ca = Math.cos(a);
                double sa = Math.sin(a);
                double out = 1.05 + 0.25 * RANDOM.nextDouble();
                double along = (RANDOM.nextDouble() - 0.5) * pose.sLen[i];
                double nx = pose.sEx[o] * ca + pose.sEy[o] * sa;
                double ny = pose.sEx[o + 1] * ca + pose.sEy[o + 1] * sa;
                double nz = pose.sEx[o + 2] * ca + pose.sEy[o + 2] * sa;
                double px = cx + pose.sEx[o] * ca * rx * out + pose.sEy[o] * sa * ry * out + pose.sEz[o] * along;
                double py = cy + pose.sEx[o + 1] * ca * rx * out + pose.sEy[o + 1] * sa * ry * out + pose.sEz[o + 1] * along;
                double pz = cz + pose.sEx[o + 2] * ca * rx * out + pose.sEy[o + 2] * sa * ry * out + pose.sEz[o + 2] * along;
                double push = r * (0.015 + 0.03 * RANDOM.nextDouble());
                spawn(px, py, pz, nx * push + vx * 0.15, ny * push + vy * 0.15 + r * 0.004, nz * push + vz * 0.15, 1.0,
                        size * (0.65F + 0.7F * RANDOM.nextFloat()), 80 + RANDOM.nextInt(80), size * 0.04, true);
            }
        }
        bossPrevTick = bt;
        if (DEV_LOG && ticks % 100 == 0) {
            Wisp w0 = WISPS.isEmpty() ? null : WISPS.get(WISPS.size() - 1);
            OvergrownVegetation.LOGGER.info("CloudWake かけら {} 引いているもの {} ボスの輪切り {} 新しいかけら(世界) {} 頭(世界) {} {} {}", WISPS.size(), emitters, ns,
                    w0 == null ? "-" : Math.round(w0.x + CloudField.offsetX(now)) + " " + Math.round(w0.y) + " " + Math.round(w0.z + CloudField.offsetZ(now)) + " 大きさ " + Math.round(w0.size),
                    Math.round(hx + CloudField.offsetX(now)), Math.round(ClientSkyBoss.flight().y), Math.round(hz + CloudField.offsetZ(now)));
        }
    }

    /** 期待値 mean の個数(端数は乱数で)。 */
    private static int count(float mean) {
        int n = (int) mean;
        if (RANDOM.nextFloat() < mean - n) {
            n++;
        }
        return n;
    }

    private static void spawn(double x, double y, double z, double vx, double vy, double vz, double follow, float size, int life, double spread,
                              boolean boss) {
        if (WISPS.size() >= MAX) {
            return;
        }
        Wisp w = new Wisp();
        w.x = x;
        w.y = y;
        w.z = z;
        w.vx = vx * follow + (RANDOM.nextDouble() - 0.5) * spread;
        w.vy = vy * follow + (RANDOM.nextDouble() - 0.5) * spread;
        w.vz = vz * follow + (RANDOM.nextDouble() - 0.5) * spread;
        w.size = size;
        w.life = life;
        w.boss = boss;
        WISPS.add(w);
    }

    /** 雲の描画の中から(遠く・近くの 2 回。split = 遠くと近くの境の距離)。ボス以外のかけらだけ。形は描く時刻が変わったときだけ組み直す。 */
    static void draw(ClientLevel level, float partialTick, Matrix4f modelView, Matrix4f projection, double camX, double camY, double camZ,
                     boolean far, float split) {
        ShaderInstance shader = ModShaders.cloudWake;
        if (broken || shader == null || WISPS.isEmpty() || setting() <= 0.0F) {
            return;
        }
        try {
            double t = level.getGameTime() + (double) partialTick;
            if (t != builtAt) {
                builtAt = t;
                quads = build(level, partialTick, t, camX, camY, camZ, false);
            }
            if (quads == 0) {
                return;
            }
            uniforms(shader, level, partialTick, camX, camY, camZ);
            shader.safeGetUniform("Split").set(split, far ? 1.0F : -1.0F, VoxelClouds.FAR_NEAR, VoxelClouds.FAR_FAR);
            shader.safeGetUniform("Curve").set(OvergrownVegetationConfig.COMMON.cloudCurvature.get().floatValue(), VoxelClouds.CURVE_FROM,
                    0.5F / VoxelClouds.CURVE_RADIUS, 0.0F);
            shader.safeGetUniform("Squeeze").set(0.0F, 0.0F, 0.0F, 0.0F);
            drawVbo(shader, vbo, modelView, projection);
        } catch (RuntimeException e) {
            broken = true;
            OvergrownVegetation.LOGGER.error("雲を引く演出(描画)で例外が出たので、このセッションでは描かない", e);
        }
    }

    /**
     * ボスから出たかけらは、ボス({@code client/skyboss/SkyBossRenderer}、AFTER_ENTITIES)を描いた後に描く。遠くの雲の回はボスより先に描いて奥行きを消すので、
     * そこで描くとボスの前にあるかけらまでボスに上書きされて見えなかった(10-19 のテストクライアント)。ボスと同じく、奥のクリップ面の 0.6 倍より遠い頂点を
     * 見える向きのままカメラへ寄せる(ボスと同じ式・同じ値なので、ボスとの前後が合う)。遠くの雲より手前に描かれるのはボスと同じ。
     */
    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || broken || WISPS.isEmpty()) {
            return;
        }
        ShaderInstance shader = ModShaders.cloudWake;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (shader == null || level == null || setting() <= 0.0F) {
            return;
        }
        try {
            float pt = event.getPartialTick().getGameTimeDeltaPartialTick(false);
            Vec3 cam = event.getCamera().getPosition();
            double t = level.getGameTime() + (double) pt;
            bossQuads = build(level, pt, t, cam.x, cam.y, cam.z, true);
            if (bossQuads == 0) {
                return;
            }
            // ボスの寄せ方(SkyBossRenderer と同じ: 奥のクリップ面の 0.6 倍から寄せ始め、ボスのいちばん遠い点 + 扇の余裕を 0.95 倍へ)。
            float depthFar = mc.gameRenderer.getDepthFar();
            double farthest = 0.0;
            SkyBossPose ps = ClientSkyBoss.isActive() ? ClientSkyBoss.pose() : null;
            if (ps != null) {
                for (double[] p : ps.points) {
                    farthest = Math.max(farthest, Math.sqrt((p[0] - cam.x) * (p[0] - cam.x) + (p[1] - cam.y) * (p[1] - cam.y) + (p[2] - cam.z) * (p[2] - cam.z)));
                }
                farthest += 190.0 * com.yayuy.overgrownvegetation.skyboss.SkyBossTuning.BLOCKS_PER_UNIT;
            }
            uniforms(shader, level, pt, cam.x, cam.y, cam.z);
            shader.safeGetUniform("Split").set(0.0F, 0.0F, VoxelClouds.FAR_NEAR, VoxelClouds.FAR_FAR);
            shader.safeGetUniform("Curve").set(0.0F, VoxelClouds.CURVE_FROM, 0.5F / VoxelClouds.CURVE_RADIUS, 0.0F);
            shader.safeGetUniform("Squeeze").set(depthFar * 0.6F, depthFar * 0.95F, (float) Math.max(farthest, depthFar), depthFar * 0.99F);
            drawVbo(shader, bossVbo, event.getModelViewMatrix(), event.getProjectionMatrix());
        } catch (RuntimeException e) {
            broken = true;
            OvergrownVegetation.LOGGER.error("雲を引く演出(ボスの描画)で例外が出たので、このセッションでは描かない", e);
        }
    }

    private static void uniforms(ShaderInstance shader, ClientLevel level, float partialTick, double camX, double camY, double camZ) {
        Vec3 sky = level.getSkyColor(new Vec3(camX, camY, camZ), partialTick);
        float inside = CloudInterior.amount(partialTick) * CloudInterior.setting();
        shader.safeGetUniform("Haze").set(Math.min(1.0F, (float) sky.x * 0.9F), Math.min(1.0F, (float) sky.y * 0.97F), Math.min(1.0F, (float) sky.z * 1.1F),
                OvergrownVegetationConfig.COMMON.cloudBlueHaze.get().floatValue());
        shader.safeGetUniform("Fade").set(Mth.clamp((inside - 0.1F) / 0.5F, 0.0F, 1.0F), 0.0F, 0.0F, 0.0F);
    }

    private static void drawVbo(ShaderInstance shader, VertexBuffer target, Matrix4f modelView, Matrix4f projection) {
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        target.bind();
        target.drawWithShader(modelView, projection, shader);
        VertexBuffer.unbind();
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
    }

    /** かけらの箱(カメラに向いた 3 面)を組んで VBO へ送る(boss = ボスから出たかけらの組か)。面の数を返す。 */
    private static int build(ClientLevel level, float partialTick, double t, double camX, double camY, double camZ, boolean boss) {
        quadCount = 0;
        double ox = CloudField.offsetX(t);
        double oz = CloudField.offsetZ(t);
        Vec3 cloud = SkyLight.cloudColor(level, partialTick, level.getCloudColor(partialTick));
        float cr = (float) cloud.x;
        float cg = (float) cloud.y;
        float cb = (float) cloud.z;
        ByteBufferBuilder buf = new ByteBufferBuilder(WISPS.size() * 3 * 4 * 16 + 256);
        try {
            BufferBuilder b = new BufferBuilder(buf, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            for (Wisp w : WISPS) {
                if (w.boss != boss) {
                    continue;
                }
                float age = w.age + partialTick;
                float f = age / w.life;
                float alpha = Mth.clamp(age / 3.0F, 0.0F, 1.0F) * (1.0F - (float) Math.pow(Mth.clamp(f, 0.0F, 1.0F), 1.5)) * 0.85F;
                if (alpha <= 0.01F) {
                    continue;
                }
                float h = w.size * (1.0F + 0.6F * f) * 0.5F;
                float x = (float) (w.x + w.vx * partialTick + ox - camX);
                float y = (float) (w.y + w.vy * partialTick - camY);
                float z = (float) (w.z + w.vz * partialTick + oz - camZ);
                float x0 = x - h;
                float x1 = x + h;
                float y0 = y - h;
                float y1 = y + h;
                float z0 = z - h;
                float z1 = z + h;
                // カメラは原点。見える側の面だけ(上 1.0・横 0.86 / 0.78・下 0.62)。
                if (y0 > 0.0F) {
                    face(b, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, cr * 0.62F, cg * 0.64F, cb * 0.7F, alpha);
                } else if (y1 < 0.0F) {
                    face(b, x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0, cr, cg, cb, alpha);
                }
                if (x0 > 0.0F) {
                    face(b, x0, y0, z1, x0, y0, z0, x0, y1, z0, x0, y1, z1, cr * 0.78F, cg * 0.8F, cb * 0.86F, alpha);
                } else if (x1 < 0.0F) {
                    face(b, x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0, cr * 0.78F, cg * 0.8F, cb * 0.86F, alpha);
                }
                if (z0 > 0.0F) {
                    face(b, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0, cr * 0.86F, cg * 0.88F, cb * 0.92F, alpha);
                } else if (z1 < 0.0F) {
                    face(b, x1, y0, z1, x0, y0, z1, x0, y1, z1, x1, y1, z1, cr * 0.86F, cg * 0.88F, cb * 0.92F, alpha);
                }
            }
            if (quadCount == 0) {
                return 0;
            }
            MeshData mesh = b.build();
            if (mesh == null) {
                return 0;
            }
            VertexBuffer target;
            if (boss) {
                if (bossVbo == null) {
                    bossVbo = new VertexBuffer(VertexBuffer.Usage.DYNAMIC);
                }
                target = bossVbo;
            } else {
                if (vbo == null) {
                    vbo = new VertexBuffer(VertexBuffer.Usage.DYNAMIC);
                }
                target = vbo;
            }
            target.bind();
            target.upload(mesh);
            VertexBuffer.unbind();
            return quadCount;
        } finally {
            buf.close();
        }
    }

    private static void face(BufferBuilder b, float ax, float ay, float az, float bx, float by, float bz, float cx, float cy, float cz,
                             float dx, float dy, float dz, float r, float g, float bl, float a) {
        b.addVertex(ax, ay, az).setColor(r, g, bl, a);
        b.addVertex(bx, by, bz).setColor(r, g, bl, a);
        b.addVertex(cx, cy, cz).setColor(r, g, bl, a);
        b.addVertex(dx, dy, dz).setColor(r, g, bl, a);
        quadCount++;
    }

    /** 開発用: 今のかけらの数。 */
    static int count() {
        return WISPS.size();
    }

    private CloudWake() {
    }
}
