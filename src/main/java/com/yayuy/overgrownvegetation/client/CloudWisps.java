package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.sky.CloudField;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 流れるちぎれ雲(2026-10-17 深夜。ユーザーが選んだ「流れるちぎれ雲」= 雲の中を小さな雲のかけらが風で流れていく)。
 *
 * <ul>
 *   <li>カメラのまわり 6〜36 ブロック(向いている方・動いている方に多め)の、雲の箱の中か、面のすぐ外(8 ブロック以内)に、ふわっとしたかけらを置く
 *       ({@link CloudInterior#solidWorld})。面の外のかけらは、外から見ると雲の縁からちぎれて流れる小さな雲になる。</li>
 *   <li>かけらは雲と一緒に風で流れ、さらに風下へ少し速く(雲の箱の流れ 1 秒 1 ブロックほどは遅すぎて動きが見えないので、1 秒 1〜2.5 ブロック足す)。
 *       積乱雲の中は上昇気流で上へ。4〜10 秒で生まれて消える。速く飛ぶと、置かれたかけらの間をすり抜けていく(速さが分かる)。</li>
 *   <li>絵はその場で作る(白いもやもやの丸 4 種。ファイルは無い)。色は雲の中の霧の色(外では雲の色)、明るめ・暗めを混ぜる。
 *       カメラのすぐ前(5 ブロック以内)は薄く(画面を覆わない)、霧で見えなくなる距離の先も消す。</li>
 * </ul>
 * 数・大きさ・速さはすべて仮決め。設定 {@code cloudInterior} の強さで数と濃さが変わる。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class CloudWisps {
    static final int MAX = 70;
    /** 1 tick に置き場所を試す数。 */
    static final int TRIES = 5;
    static final float ALPHA = 0.32F;

    private static final RandomSource RANDOM = RandomSource.create();
    private static final List<Wisp> WISPS = new ArrayList<>();
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, "cloud_wisp");
    private static boolean textureReady;
    private static RenderType type;
    private static boolean broken;
    private static ClientLevel lastLevel;

    private static final class Wisp {
        double x;
        double y;
        double z;
        double px;
        double py;
        double pz;
        double vx;
        double vy;
        double vz;
        float size;
        float rot;
        float spin;
        float shade;
        int variant;
        int age;
        int life;
        boolean storm;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level != lastLevel) {
            lastLevel = level;
            WISPS.clear();
        }
        if (level == null || mc.isPaused() || broken) {
            return;
        }
        try {
            tick(mc, level);
        } catch (RuntimeException e) {
            broken = true;
            WISPS.clear();
            OvergrownVegetation.LOGGER.error("ちぎれ雲で例外が出たので、このセッションでは使わない", e);
        }
    }

    private static void tick(Minecraft mc, ClientLevel level) {
        for (Iterator<Wisp> it = WISPS.iterator(); it.hasNext(); ) {
            Wisp w = it.next();
            w.px = w.x;
            w.py = w.y;
            w.pz = w.z;
            w.x += w.vx;
            w.y += w.vy;
            w.z += w.vz;
            w.rot += w.spin;
            if (++w.age >= w.life) {
                it.remove();
            }
        }
        float setting = CloudInterior.setting();
        if (setting <= 0.0F || !CloudInterior.near()) {
            return;
        }
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 cam = camera.getPosition();
        Vector3f look = camera.getLookVector();
        Vec3 motion = mc.player != null ? mc.player.getDeltaMovement() : Vec3.ZERO;
        int max = Math.round(MAX * setting);
        // 今の世界の風(10-18 から時刻で向きと強さが変わる)。
        double nowWindX = CloudField.windX((double) mc.level.getGameTime());
        double nowWindZ = CloudField.windZ((double) mc.level.getGameTime());
        double wl = Math.max(1.0E-4, Math.sqrt(nowWindX * nowWindX + nowWindZ * nowWindZ));
        for (int i = 0; i < TRIES && WISPS.size() < max; i++) {
            // 向いている方・動いている方に多め(前に置いたかけらの間を抜けていく)。
            double r = 6.0 + 30.0 * Math.sqrt(RANDOM.nextDouble());
            double dx = RANDOM.nextGaussian();
            double dy = RANDOM.nextGaussian() * 0.6;
            double dz = RANDOM.nextGaussian();
            double l = Math.sqrt(dx * dx + dy * dy + dz * dz) + 1.0E-6;
            dx = dx / l + look.x() * 0.8 + motion.x * 0.6;
            dy = dy / l + look.y() * 0.8 + motion.y * 0.6;
            dz = dz / l + look.z() * 0.8 + motion.z * 0.6;
            l = Math.sqrt(dx * dx + dy * dy + dz * dz) + 1.0E-6;
            double x = cam.x + dx / l * r;
            double y = cam.y + dy / l * r;
            double z = cam.z + dz / l * r;
            int hit = CloudInterior.solidWorld(level, x, y, z);
            if (hit == 0) {
                // 面のすぐ外なら置く(雲の縁からちぎれたかけら)。
                double ox = RANDOM.nextGaussian();
                double oy = RANDOM.nextGaussian() * 0.5;
                double oz = RANDOM.nextGaussian();
                double ol = Math.sqrt(ox * ox + oy * oy + oz * oz) + 1.0E-6;
                hit = CloudInterior.solidWorld(level, x + ox / ol * 8.0, y + oy / ol * 8.0, z + oz / ol * 8.0);
                if (hit == 0 || RANDOM.nextFloat() < 0.4F) {
                    continue;
                }
            }
            Wisp w = new Wisp();
            w.x = w.px = x;
            w.y = w.py = y;
            w.z = w.pz = z;
            w.storm = hit == 2;
            double extra = 0.05 + 0.075 * RANDOM.nextDouble();
            w.vx = nowWindX + nowWindX / wl * extra + RANDOM.nextGaussian() * 0.015;
            w.vz = nowWindZ + nowWindZ / wl * extra + RANDOM.nextGaussian() * 0.015;
            w.vy = (w.storm ? 0.04 + 0.06 * RANDOM.nextDouble() : 0.0) + RANDOM.nextGaussian() * 0.008;
            w.size = (3.5F + 7.0F * RANDOM.nextFloat()) * (w.storm ? 1.4F : 1.0F);
            w.rot = RANDOM.nextFloat() * Mth.TWO_PI;
            w.spin = (RANDOM.nextFloat() - 0.5F) * 0.01F;
            w.shade = RANDOM.nextFloat() < 0.5F ? 1.07F : 0.9F;
            w.variant = RANDOM.nextInt(4);
            w.life = 80 + RANDOM.nextInt(120);
            WISPS.add(w);
        }
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        // 近くの雲(renderClouds)は粒の後に描かれるので、その後(天気の後)に描く。前に描くと、霧の中の雲の面に塗りつぶされる。
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_WEATHER || broken || WISPS.isEmpty()) {
            return;
        }
        boolean main = CanopyLight.drawToMainTarget();  // 「最高」では天気の層でなく主画面へ(CanopyLight#restoreWeatherTarget)
        try {
            render(event);
        } catch (RuntimeException e) {
            broken = true;
            OvergrownVegetation.LOGGER.error("ちぎれ雲(描画)で例外が出たので、このセッションでは使わない", e);
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
        ensureTexture();
        float pt = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Camera camera = event.getCamera();
        Vec3 eye = camera.getPosition();
        Vector3f left = camera.getLeftVector();
        Vector3f up = camera.getUpVector();
        float inside = CloudInterior.amount(pt) * CloudInterior.setting();
        float[] fog = CloudInterior.fogColor(level, pt, eye);
        Vec3 cloud = SkyLight.cloudColor(level, pt, level.getCloudColor(pt));
        float far = Math.max(40.0F, CloudInterior.fogFar(mc.gameRenderer.getRenderDistance(), pt) * 1.3F);
        float strength = Math.min(1.0F, CloudInterior.setting());
        VertexConsumer c = mc.renderBuffers().bufferSource().getBuffer(renderType());
        for (Wisp w : WISPS) {
            double x = Mth.lerp(pt, w.px, w.x) - eye.x;
            double y = Mth.lerp(pt, w.py, w.y) - eye.y;
            double z = Mth.lerp(pt, w.pz, w.z) - eye.z;
            float d = (float) Math.sqrt(x * x + y * y + z * z);
            float t = w.age + pt;
            float env = Mth.clamp(t / 20.0F, 0.0F, 1.0F) * Mth.clamp((w.life - t) / 30.0F, 0.0F, 1.0F);
            float a = ALPHA * strength * env * smooth(Mth.clamp((d - 1.5F) / 4.0F, 0.0F, 1.0F))
                    * (1.0F - smooth(Mth.clamp((d - far * 0.6F) / (far * 0.4F), 0.0F, 1.0F)));
            if (a <= 0.004F) {
                continue;
            }
            float r = Mth.lerp(inside, (float) cloud.x, fog[0]) * w.shade;
            float g = Mth.lerp(inside, (float) cloud.y, fog[1]) * w.shade;
            float b = Mth.lerp(inside, (float) cloud.z, fog[2]) * w.shade;
            float rot = w.rot + w.spin * pt;
            float cs = Mth.cos(rot) * w.size;
            float sn = Mth.sin(rot) * w.size;
            // 横に 1.5 倍のばした板(流れる向きに長いかけら)。
            float ax = (left.x() * cs * 1.5F + up.x() * sn);
            float ay = (left.y() * cs * 1.5F + up.y() * sn);
            float az = (left.z() * cs * 1.5F + up.z() * sn);
            float bx = (-left.x() * sn * 1.5F + up.x() * cs);
            float by = (-left.y() * sn * 1.5F + up.y() * cs);
            float bz = (-left.z() * sn * 1.5F + up.z() * cs);
            float u0 = (w.variant & 1) * 0.5F;
            float v0 = (w.variant >> 1) * 0.5F;
            float fx = (float) x;
            float fy = (float) y;
            float fz = (float) z;
            int rr = Mth.clamp((int) (r * 255.0F), 0, 255);
            int gg = Mth.clamp((int) (g * 255.0F), 0, 255);
            int bb = Mth.clamp((int) (b * 255.0F), 0, 255);
            int aa = Mth.clamp((int) (a * 255.0F), 0, 255);
            c.addVertex(fx - ax - bx, fy - ay - by, fz - az - bz).setUv(u0, v0 + 0.5F).setColor(rr, gg, bb, aa);
            c.addVertex(fx + ax - bx, fy + ay - by, fz + az - bz).setUv(u0 + 0.5F, v0 + 0.5F).setColor(rr, gg, bb, aa);
            c.addVertex(fx + ax + bx, fy + ay + by, fz + az + bz).setUv(u0 + 0.5F, v0).setColor(rr, gg, bb, aa);
            c.addVertex(fx - ax + bx, fy - ay + by, fz - az + bz).setUv(u0, v0).setColor(rr, gg, bb, aa);
        }
        mc.renderBuffers().bufferSource().endBatch(renderType());
    }

    private static float smooth(float t) {
        return t * t * (3.0F - 2.0F * t);
    }

    /** かけらの絵(128 × 128 に 64 × 64 のもやもや 4 つ。白、透明度が形)。 */
    private static void ensureTexture() {
        if (textureReady) {
            return;
        }
        textureReady = true;
        NativeImage image = new NativeImage(128, 128, true);
        RandomSource r = RandomSource.create(0xC10D5L);
        for (int v = 0; v < 4; v++) {
            int ox = (v & 1) * 64;
            int oy = (v >> 1) * 64;
            float[] grid = new float[9 * 9];
            for (int i = 0; i < grid.length; i++) {
                grid[i] = r.nextFloat();
            }
            float[] fine = new float[17 * 17];
            for (int i = 0; i < fine.length; i++) {
                fine[i] = r.nextFloat();
            }
            // 丸からずらしたいびつな形(中心を少しずらし、横長・縦長を混ぜる)。
            float cx = 32.0F + (r.nextFloat() - 0.5F) * 6.0F;
            float cy = 32.0F + (r.nextFloat() - 0.5F) * 6.0F;
            float sx = 0.8F + 0.4F * r.nextFloat();
            for (int py = 0; py < 64; py++) {
                for (int px = 0; px < 64; px++) {
                    float dx = (px + 0.5F - cx) / (30.0F * sx);
                    float dy = (py + 0.5F - cy) / 30.0F;
                    float rad = (float) Math.sqrt(dx * dx + dy * dy);
                    float n = 0.65F * lattice(grid, 9, px / 64.0F * 8.0F, py / 64.0F * 8.0F) + 0.35F * lattice(fine, 17, px / 64.0F * 16.0F, py / 64.0F * 16.0F);
                    float edge = 1.0F - smooth(Mth.clamp((rad - 0.25F - 0.45F * n) / 0.35F, 0.0F, 1.0F));
                    float alpha = edge * (0.55F + 0.45F * n);
                    int a = Mth.clamp((int) (alpha * 255.0F), 0, 255);
                    image.setPixelRGBA(ox + px, oy + py, (a << 24) | 0xFFFFFF);
                }
            }
        }
        DynamicTexture texture = new DynamicTexture(image);
        Minecraft.getInstance().getTextureManager().register(TEXTURE, texture);
    }

    private static float lattice(float[] grid, int n, float x, float y) {
        int i = Math.min(n - 2, (int) x);
        int j = Math.min(n - 2, (int) y);
        float fx = smooth(x - i);
        float fy = smooth(y - j);
        float a = grid[j * n + i];
        float b = grid[j * n + i + 1];
        float c = grid[(j + 1) * n + i];
        float d = grid[(j + 1) * n + i + 1];
        return Mth.lerp(fy, Mth.lerp(fx, a, b), Mth.lerp(fx, c, d));
    }

    private static RenderType renderType() {
        if (type == null) {
            type = RenderType.create(OvergrownVegetation.MODID + "_cloud_wisp", DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS, 4096,
                    false, true,
                    RenderType.CompositeState.builder()
                            .setShaderState(new RenderStateShard.ShaderStateShard(net.minecraft.client.renderer.GameRenderer::getPositionTexColorShader))
                            .setTextureState(new RenderStateShard.TextureStateShard(TEXTURE, true, false))
                            .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                            .setCullState(RenderStateShard.NO_CULL)
                            .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                            .createCompositeState(false));
        }
        return type;
    }

    private CloudWisps() {
    }
}
