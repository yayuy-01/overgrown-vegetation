package com.yayuy.overgrownvegetation.client.worldevent;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.atmosphere.CloudHaze;
import com.yayuy.overgrownvegetation.client.atmosphere.DenseFog;
import com.yayuy.overgrownvegetation.client.atmosphere.ForestPresence;
import com.yayuy.overgrownvegetation.client.atmosphere.SunLight;
import com.yayuy.overgrownvegetation.client.shader.ShaderPacks;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.WorldEventLooks;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * <b>森から昇る胞子</b>の 2 つの見た目(2026-11-16。見た目だけ)。
 *
 * <h2>昇る胞子(オーロラの盛り)</h2>
 * ユーザー決定(11-16): オーロラの昇る胞子は<b>弱く匂わせる程度</b>(粒はまばら)。理由(設定): 森が胞子を空へ送っている —— 後の版の「胞子の雪」への匂わせ。
 * オーロラが空に出ている間だけ、カメラのまわりの過繁茂の森の梢の上に、光の粒がまばらに生まれてゆっくり昇り、{@code height} ブロック昇る間に消える。
 * 粒はカメラを向く小さな四角(足し算)。地形と同じ霧で弱める。遠い粒は 1 画素を下回らないよう少し大きく描く。
 *
 * <h2>胞子の柱(フェーズ 7 の印)</h2>
 * ユーザー決定: 一夜だけ、森の方角の地平線に胞子の柱が何本も立ち上がる(森が力を集めている)。描画距離よりずっと遠い物なので、空の段で
 * (星の後・雲と地形の前に)カメラのまわりの決まった距離へ、向きと見かけの大きさだけで描く。1 本ずつ間を置いて伸び上がり、上へ行くほど広がって薄れ、
 * 中をまだらが昇る。地平線のすぐ上は霧の色の輪なので、根元は透明から始める。
 *
 * 値は {@code sky.json} の節 {@code worldEvents.sporeRise}・{@code pillars}(すべて仮決め)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, value = Dist.CLIENT)
public final class SporeRise {

    private SporeRise() {
    }

    private static final class Mote {
        double x, y, z;
        float age, life, phase, size;
    }

    private static final List<Mote> MOTES = new ArrayList<>();
    private static final Random RANDOM = new Random();
    private static boolean broken;
    private static int pillarsDrawn;

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            MOTES.clear();
            return;
        }
        if (mc.isPaused()) {
            return;
        }
        WorldEventLooks.SporeRise p = DesignData.sky().events.sporeRise;
        for (int i = MOTES.size() - 1; i >= 0; i--) {
            Mote m = MOTES.get(i);
            m.age += 0.05F;
            m.y += p.speed * 0.05;
            if (m.age >= m.life) {
                MOTES.remove(i);
            }
        }
        float shown = WorldEventSky.auroraShown();
        if (shown < 0.25F || p.strength <= 0.0F || MOTES.size() >= p.count) {
            return;
        }
        // 1 tick に 2 か所まで試す(過繁茂の森の梢の上だけ)。70 粒が埋まるまで数秒かかる = ぽつぽつと現れる。
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int k = 0; k < 2; k++) {
            double ang = RANDOM.nextDouble() * Math.PI * 2.0;
            double r = Math.sqrt(RANDOM.nextDouble()) * p.range;
            int x = Mth.floor(cam.x + Math.cos(ang) * r);
            int z = Mth.floor(cam.z + Math.sin(ang) * r);
            pos.set(x, Mth.floor(cam.y), z);
            if (!level.hasChunkAt(pos) || !ModBiomes.isOvergrownLike(level.getBiome(pos))) {
                continue;
            }
            int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
            if (top <= level.getMinBuildHeight()) {
                continue;
            }
            Mote m = new Mote();
            m.x = x + RANDOM.nextDouble();
            m.y = top + 0.5 + RANDOM.nextDouble() * 2.0;
            m.z = z + RANDOM.nextDouble();
            m.life = p.height / Math.max(0.05F, p.speed) * (0.7F + 0.3F * RANDOM.nextFloat());
            m.phase = RANDOM.nextFloat() * 6.2832F;
            m.size = p.size * (0.7F + 0.6F * RANDOM.nextFloat());
            MOTES.add(m);
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        MOTES.clear();
    }

    /** 柱は空の段で、遠くの雲より先に({@link WorldEventSky} と同じ理由で順を上げる)。 */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (broken) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || ShaderPacks.active() || !com.yayuy.overgrownvegetation.client.VisualQuality.effects() || event.getCamera().getFluidInCamera() != FogType.NONE) {
            return;
        }
        try {
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_SKY) {
                drawPillars(event, level);
            } else if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
                drawMotes(event);
            }
        } catch (RuntimeException e) {
            broken = true;
            OvergrownVegetation.LOGGER.error("昇る胞子・胞子の柱で例外が出たので、このセッションでは描かない", e);
        }
    }

    private static void drawMotes(RenderLevelStageEvent event) {
        if (MOTES.isEmpty()) {
            return;
        }
        WorldEventLooks.SporeRise p = DesignData.sky().events.sporeRise;
        float shown = Mth.clamp((WorldEventSky.auroraShown() - 0.15F) / 0.35F, 0.0F, 1.0F) * p.strength;
        if (shown <= 0.01F) {
            return;
        }
        Vec3 eye = event.getCamera().getPosition();
        org.joml.Vector3f left = event.getCamera().getLeftVector();
        org.joml.Vector3f up = event.getCamera().getUpVector();
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        float cr = WorldEventSky.rgb(p.color, 0), cg = WorldEventSky.rgb(p.color, 1), cb = WorldEventSky.rgb(p.color, 2);
        boolean main = EventLight.drawToMainTarget();
        MultiBufferSource.BufferSource buffers = EventLight.buffers();
        VertexConsumer c = buffers.getBuffer(EventLight.renderType());
        for (Mote m : MOTES) {
            float age = m.age + partial * 0.05F;
            float u = age / m.life;
            // ふっと現れて、昇りながらゆっくり消える。弱いまたたき。
            float a = SunLight.smooth(Mth.clamp(u / 0.12F, 0.0F, 1.0F)) * (1.0F - u) * (0.75F + 0.25F * Mth.sin(age * 2.3F + m.phase));
            float x = (float) (m.x - eye.x) + Mth.sin(age * 0.6F + m.phase) * 0.7F;
            float y = (float) (m.y - eye.y) + partial * 0.05F * p.speed;
            float z = (float) (m.z - eye.z) + Mth.cos(age * 0.47F + m.phase * 1.7F) * 0.7F;
            float dist = Mth.sqrt(x * x + y * y + z * z);
            a *= shown * EventLight.fogKeep(dist);
            if (a <= 0.01F) {
                continue;
            }
            float s = m.size * Math.max(1.0F, dist / 40.0F);
            quad(c, x, y, z, left, up, s * 2.6F, cr, cg, cb, a * 0.22F);
            quad(c, x, y, z, left, up, s, cr * 0.6F + 0.4F, cg * 0.6F + 0.4F, cb * 0.6F + 0.4F, a);
        }
        buffers.endBatch(EventLight.renderType());
        EventLight.restoreTarget(main);
    }

    private static void quad(VertexConsumer c, float x, float y, float z, org.joml.Vector3f l, org.joml.Vector3f u, float s,
                             float r, float g, float b, float a) {
        c.addVertex(x - l.x() * s - u.x() * s, y - l.y() * s - u.y() * s, z - l.z() * s - u.z() * s).setColor(r, g, b, a);
        c.addVertex(x + l.x() * s - u.x() * s, y + l.y() * s - u.y() * s, z + l.z() * s - u.z() * s).setColor(r, g, b, a);
        c.addVertex(x + l.x() * s + u.x() * s, y + l.y() * s + u.y() * s, z + l.z() * s + u.z() * s).setColor(r, g, b, a);
        c.addVertex(x - l.x() * s + u.x() * s, y - l.y() * s + u.y() * s, z - l.z() * s + u.z() * s).setColor(r, g, b, a);
    }

    private static float hash(int n) {
        n = (n << 13) ^ n;
        return ((n * (n * n * 15731 + 789221) + 1376312589) & 0x7fffffff) / (float) 0x7fffffff;
    }

    /** なめらかな 1 次元の雑音(0〜1)。 */
    private static float noise(float x, int seed) {
        int i = Mth.floor(x);
        float f = x - i;
        f = f * f * (3.0F - 2.0F * f);
        return Mth.lerp(f, hash(i * 57 + seed * 131), hash((i + 1) * 57 + seed * 131));
    }

    private static void drawPillars(RenderLevelStageEvent event, ClientLevel level) {
        pillarsDrawn = 0;
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        float elapsed = WorldEventCues.pillarsElapsed(partial);
        WorldEventLooks.Pillars p = DesignData.sky().events.pillars;
        if (elapsed < 0.0F || p.strength <= 0.0F || !level.dimensionType().hasSkyLight()) {
            return;
        }
        float amount = p.strength * WorldEventCues.pillarsFade(partial) * SunLight.night(level, partial) * (1.0F - level.getRainLevel(partial))
                * (1.0F - DenseFog.skyFillPublic()) * (1.0F - ForestPresence.underground()) * (1.0F - 0.8F * CloudHaze.wall());
        if (amount <= 0.01F) {
            return;
        }
        float cr = WorldEventSky.rgb(p.color, 0), cg = WorldEventSky.rgb(p.color, 1), cb = WorldEventSky.rgb(p.color, 2);
        double base = Math.atan2(WorldEventCues.dirZ(), WorldEventCues.dirX());
        float time = (level.getGameTime() % 1_728_000L + partial) / 20.0F;
        int count = Mth.clamp(p.count, 1, 16);
        final float dist = 100.0F;
        final int seg = 20;
        WorldEventSky.pushSkyView(event);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE,
                GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ONE);
        BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (int k = 0; k < count; k++) {
            // 1 本ずつ間を置いて立ち上がる(8 秒おき)。伸びきるのに 30 秒。
            float grow = SunLight.smooth(Mth.clamp((elapsed - k * 160.0F) / 600.0F, 0.0F, 1.0F));
            if (grow <= 0.001F) {
                continue;
            }
            pillarsDrawn++;
            float spread = (float) Math.toRadians(p.spreadDeg);
            // 並び: 真ん中から左右へ交互に。少しずつ不ぞろいに。
            float slot = ((k + 1) / 2) * ((k % 2 == 0) ? 1.0F : -1.0F) / Math.max(1.0F, count / 2.0F);
            double az = base + slot * spread * 0.5 + (hash(k * 7 + 3) - 0.5) * spread / count * 0.7;
            float tall = (float) Math.toRadians(p.heightDeg) * (0.6F + 0.4F * hash(k * 13 + 1)) * grow;
            float wide = (float) Math.toRadians(p.widthDeg) * (0.7F + 0.6F * hash(k * 17 + 5));
            float lean = (hash(k * 23 + 9) - 0.3F) * 0.25F;
            float bright = 0.6F + 0.4F * hash(k * 29 + 11);
            float[] px = new float[seg + 1], pe = new float[seg + 1], pw = new float[seg + 1], pa = new float[seg + 1];
            for (int i = 0; i <= seg; i++) {
                float u = i / (float) seg;
                // 上へ行くほど風下へ流れ、ゆっくりうねり、広がる。
                px[i] = (lean * u * u + (noise(u * 3.0F - time * 0.02F, k) - 0.5F) * 0.12F * u) * tall;
                pe[i] = 0.012F + u * tall;
                pw[i] = wide * (0.6F + 1.6F * u);
                // 濃さ: 根元は透明から(地平の霧の輪)、すぐ濃くなり、上で消える。中をまだらが昇る。
                float mottle = 0.55F + 0.45F * noise(u * 7.0F - time * 0.12F, k + 40);
                pa[i] = SunLight.smooth(Mth.clamp(u / 0.10F, 0.0F, 1.0F)) * (float) Math.pow(1.0F - u, 0.9) * mottle * bright * amount * 0.75F;
            }
            for (int i = 0; i < seg; i++) {
                for (int side = -1; side <= 1; side += 2) {
                    // 真ん中(濃い)と縁(透明)の間の 1 枚。
                    vertex(b, az, px[i], pe[i], dist, cr, cg, cb, pa[i]);
                    vertex(b, az, px[i] + side * pw[i], pe[i], dist, cr, cg, cb, 0.0F);
                    vertex(b, az, px[i + 1] + side * pw[i + 1], pe[i + 1], dist, cr, cg, cb, 0.0F);
                    vertex(b, az, px[i + 1], pe[i + 1], dist, cr, cg, cb, pa[i + 1]);
                }
            }
        }
        var mesh = b.build();
        if (mesh != null) {
            BufferUploader.drawWithShader(mesh);
        }
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        WorldEventSky.popSkyView();
    }

    /** 方位 az + 横のずれ(ラジアン)・仰角 el の向きへ、距離 dist の頂点。 */
    private static void vertex(BufferBuilder b, double az, float side, float el, float dist, float r, float g, float bl, float a) {
        double yaw = az + side;
        float ce = Mth.cos(el);
        b.addVertex((float) Math.cos(yaw) * ce * dist, Mth.sin(el) * dist, (float) Math.sin(yaw) * ce * dist).setColor(r, g, bl, a);
    }

    /** 確かめ用の 1 行。 */
    public static String describe() {
        return "昇る胞子 " + MOTES.size() + " 粒・胞子の柱 " + pillarsDrawn + " 本" + (broken ? "・例外で止めた" : "");
    }
}
