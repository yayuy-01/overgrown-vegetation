package com.yayuy.overgrownvegetation.client.atmosphere;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yayuy.overgrownvegetation.data.WeatherLookEye;
import com.yayuy.overgrownvegetation.network.CloudStrikePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * <b>スプライト</b>(雷雨の強化の残り。2026-11-16。ユーザー決定: 積乱雲のはるか上に一瞬広がる赤いクラゲのような光)。
 *
 * <p>現実のスプライトは、強い落雷の直後に雷雲のはるか上で一瞬だけ光る赤い放電で、上は丸くにじみ、下へ細い筋が何本も垂れる(下ほど青紫)。
 * 夜に遠くの雷雲を横から見たときだけ見える。ここでも<b>夜だけ・遠い雲だけ</b>: 遠い雷({@link HorizonLightning})と遠い落雷
 * ({@link CloudStrikePayload})のうち少しの割合で、その雲の天辺の上に出す。光るのは {@code spriteLife} 秒(最初の一瞬で広がり、筋が下へ伸びて消える)。
 * 加算の光。描画距離の外なので、落雷と同じくカメラの方へ寄せて同じ見かけの大きさで描く。数は節 {@code stormExtras} の sprite*(仮決め)。
 */
final class Sprites {

    private Sprites() {
    }

    private static final class Sprite {
        double x, z;
        float y;
        float size;
        long seed;
        long startNanos;
    }

    private static final List<Sprite> LIVE = new ArrayList<>();
    private static final Random RANDOM = new Random();
    private static int seen;

    static void clear() {
        LIVE.clear();
    }

    /** 遠い雷・遠い落雷のたびに呼ぶ。夜で、十分遠く、割合に当たれば出す。{@code cloudTop} = その雲の天辺の高さ。 */
    static void maybeSpawn(ClientLevel level, LocalPlayer player, double x, double z, float cloudTop) {
        WeatherLookEye.Params p = WeatherLook.storm();
        if (SunLight.night(level, 1.0F) < 0.3F || RANDOM.nextFloat() >= p.f("spriteChance")) {
            return;
        }
        if (Math.hypot(x - player.getX(), z - player.getZ()) < p.f("spriteMinDistance")) {
            return;
        }
        spawn(x, z, cloudTop);
    }

    static void onStrike(CloudStrikePayload s) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.player != null) {
            // 落雷の知らせには雲の天辺が無いので、ふつうの積乱雲の高さで。
            maybeSpawn(mc.level, mc.player, s.x(), s.z(), 1200.0F);
        }
    }

    static void spawn(double x, double z, float cloudTop) {
        WeatherLookEye.Params p = WeatherLook.storm();
        Sprite s = new Sprite();
        s.x = x;
        s.z = z;
        s.size = p.f("spriteSize") * (0.75F + 0.5F * RANDOM.nextFloat());
        s.y = cloudTop + p.f("spriteAbove") * (0.8F + 0.4F * RANDOM.nextFloat());
        s.seed = RANDOM.nextLong();
        s.startNanos = System.nanoTime();
        LIVE.add(s);
        if (LIVE.size() > 4) {
            LIVE.remove(0);
        }
        seen++;
    }

    /** {@link Atmosphere} の加算の光の所から。 */
    static void render(RenderLevelStageEvent event, VertexConsumer c) {
        if (LIVE.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            return;
        }
        WeatherLookEye.Params p = WeatherLook.storm();
        float life = p.f("spriteLife");
        long now = System.nanoTime();
        LIVE.removeIf(s -> (now - s.startNanos) / 1.0e9 > life);
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        float gate = p.f("spriteAlpha") * SunLight.night(level, partial) * (1.0F - DenseFog.amount()) * (1.0F - ForestPresence.underground());
        if (gate <= 0.004F) {
            return;
        }
        Vec3 eye = event.getCamera().getPosition();
        Vector3f camLeft = event.getCamera().getLeftVector();
        Vector3f camUp = event.getCamera().getUpVector();
        float drawAt = mc.gameRenderer.getDepthFar() * 0.8F;
        float[] head = p.rgb("spriteColor");
        float[] tail = p.rgb("spriteTailColor");
        for (Sprite s : LIVE) {
            float age = (float) ((now - s.startNanos) / 1.0e9);
            // 一瞬で広がり、ゆっくり消える。筋は最初の 0.1 秒で下へ伸びる。
            float env = age < 0.03F ? age / 0.03F : (float) Math.pow(Math.max(0.0F, 1.0F - (age - 0.03F) / Math.max(0.05F, life - 0.03F)), 1.4);
            float reach = Mth.clamp(age / 0.1F, 0.15F, 1.0F);
            float a = gate * env;
            if (a <= 0.004F) {
                continue;
            }
            double dx = s.x - eye.x;
            double dy = s.y - eye.y;
            double dz = s.z - eye.z;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            float k = (float) Math.min(1.0, drawAt / Math.max(dist, 1.0));
            float cx = (float) dx * k, cy = (float) dy * k, cz = (float) dz * k;
            float size = s.size * k;
            // 横の向き = カメラから見て左右(水平)。
            double hl = Math.max(1.0e-6, Math.hypot(dx, dz));
            float rx = (float) (-dz / hl), rz = (float) (dx / hl);
            Random r = new Random(s.seed);
            // 上のにじみ(大きく薄い丸と、中の明るい丸)。
            WeatherShapes.glowDisc(c, cx, cy + size * 0.22F, cz, size * 0.62F, 0.62F, camLeft, camUp, head[0], head[1], head[2], a * 0.5F);
            WeatherShapes.glowDisc(c, cx, cy + size * 0.2F, cz, size * 0.3F, 0.7F, camLeft, camUp, head[0], head[1] + 0.1F, head[2] + 0.06F, a * 0.6F);
            // 柱と、垂れる筋。
            int columns = 6 + r.nextInt(5);
            for (int i = 0; i < columns; i++) {
                float u = (r.nextFloat() - 0.5F) * 0.95F * size;
                float w = (r.nextFloat() - 0.5F) * 0.2F * size;
                float topY = cy + size * (0.18F + 0.22F * r.nextFloat()) * (1.0F - Math.abs(u) / size);
                float len = size * (0.45F + 0.75F * r.nextFloat()) * reach;
                float lean = (r.nextFloat() - 0.5F) * 0.12F * size;
                float bright = 0.55F + 0.45F * r.nextFloat();
                float ax = cx + rx * u, az = cz + rz * u;
                float mx = cx + rx * (u + lean * 0.4F), mz = cz + rz * (u + lean * 0.4F);
                float bx = cx + rx * (u + lean), bz = cz + rz * (u + lean);
                float midY = topY - len * 0.4F;
                float botY = topY - len;
                float wide = size * (0.035F + 0.03F * r.nextFloat());
                float[] ca = {head[0], head[1], head[2], a * bright};
                float mixR = (head[0] + tail[0]) * 0.5F, mixG = (head[1] + tail[1]) * 0.5F, mixB = (head[2] + tail[2]) * 0.5F;
                float[] cm = {mixR, mixG, mixB, a * bright * 0.7F};
                float[] cb = {tail[0], tail[1], tail[2], 0.0F};
                // 太い柱(上)→ 細い筋(下)。にじみを 1 枚重ねる。
                WeatherShapes.glowLine(c, ax, topY, az, mx, midY, mz, wide, wide * 0.55F, ca, cm);
                WeatherShapes.glowLine(c, mx, midY, mz, bx, botY, bz, wide * 0.55F, wide * 0.15F, cm, cb);
                float[] halo = {head[0], head[1], head[2], a * bright * 0.2F};
                float[] halo0 = {mixR, mixG, mixB, 0.0F};
                WeatherShapes.glowLine(c, ax, topY, az, mx, midY, mz, wide * 3.2F, wide * 2.0F, halo, halo0);
                // 柱の頭の小さな玉と、上へ伸びる細い毛。
                WeatherShapes.glowDisc(c, ax, topY, az, wide * 1.6F, 1.0F, camLeft, camUp, head[0], head[1] + 0.12F, head[2] + 0.08F, a * bright * 0.55F);
                if (r.nextFloat() < 0.7F) {
                    float up = size * (0.1F + 0.16F * r.nextFloat());
                    float[] c0 = {head[0], head[1], head[2], 0.0F};
                    float[] c1 = {head[0], head[1], head[2], a * bright * 0.45F};
                    WeatherShapes.glowLine(c, ax + rx * w, topY + up, az + rz * w, ax, topY, az, wide * 0.2F, wide * 0.5F, c0, c1);
                }
            }
        }
    }

    static String describe() {
        return "スプライト いま " + LIVE.size() + "・これまで " + seen;
    }
}
