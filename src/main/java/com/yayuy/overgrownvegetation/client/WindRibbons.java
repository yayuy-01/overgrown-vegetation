package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.sky.CloudField;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 風の流れの白い帯(2026-10-18。空の世界の順番 2。ユーザー「風の流れを表現するように白い帯のようなエフェクトが漂っているとよい」)。
 *
 * <ul>
 *   <li>雲の上の空(高空の晴天 {@link HighSky#clarity()})で、カメラのまわり {@link #NEAR}〜{@link #FAR} ブロックに細長い白い帯を漂わせる(見ている方に多め)。</li>
 *   <li>帯は今の世界の風({@link CloudField#windX})の向きに伸び、風の 1.6〜3 倍の速さで流れる(アニメの風の線)。帯にそって波がうねりながら流れ、
 *       両端は細く透明に。数秒で現れて消える。</li>
 *   <li>数は風の強さで増減(風が弱いと少なく、強いと多い)。雲の中・洞窟の霧の中では出さない。足し算の光で、奥行きは書かない(地形・雲には隠れる)。</li>
 *   <li>後で「ボスの通った後の流れる胞子」(順番 7)にも、この帯の作りを使い回す。</li>
 * </ul>
 * 数値はすべて仮決め。設定 {@code windRibbons}。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class WindRibbons {
    static final int MAX = 18;
    static final double NEAR = 30.0;
    static final double FAR = 200.0;
    static final int SEGMENTS = 18;

    private static final class Ribbon {
        double x;
        double y;
        double z;
        float length;
        float width;
        float amp;
        float wave;
        float phase;
        float speed;
        float lift;
        int age;
        int life;
    }

    private static final List<Ribbon> RIBBONS = new ArrayList<>();
    private static final RandomSource RANDOM = RandomSource.create();
    private static boolean broken;
    private static long ticks;

    static float setting() {
        return OvergrownVegetationConfig.COMMON.windRibbons.get().floatValue();
    }

    /** 今どれだけ出すか(0〜1)。 */
    private static float strength(float partialTick) {
        float inside = CloudInterior.amount(partialTick) * CloudInterior.setting();
        return setting() * HighSky.clarity() * (1.0F - inside) * (CaveMist.hideSky() ? 0.0F : 1.0F);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            RIBBONS.clear();
            return;
        }
        if (mc.isPaused() || broken) {
            return;
        }
        ticks++;
        double t = level.getGameTime();
        double wx = CloudField.windX(t);
        double wz = CloudField.windZ(t);
        for (Iterator<Ribbon> it = RIBBONS.iterator(); it.hasNext(); ) {
            Ribbon r = it.next();
            r.age++;
            r.x += wx * r.speed;
            r.z += wz * r.speed;
            r.y += r.lift;
            if (r.age >= r.life) {
                it.remove();
            }
        }
        float s = strength(1.0F);
        if (s <= 0.01F) {
            return;
        }
        double speed = Math.sqrt(wx * wx + wz * wz);
        // 風が弱いと少なく、強いと多く(秒速 1 ブロックで 1 倍)。
        // 突風のときは多く(空の気流 {@link SkyAir#gust()} と揃える)。
        float want = MAX * s * Mth.clamp((float) (speed / 0.05), 0.35F, 1.6F) * (1.0F + 1.2F * SkyAir.gust());
        if (RIBBONS.size() < Math.min(MAX * 2.6F, want) && RANDOM.nextFloat() < 0.18F + 0.25F * SkyAir.gust()) {
            spawn(mc, wx, wz);
        }
    }

    private static void spawn(Minecraft mc, double wx, double wz) {
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        Vec3 look = Vec3.directionFromRotation(0.0F, mc.gameRenderer.getMainCamera().getYRot());
        // 見ている方の ±75° に多め(後ろに置いても見えない)。
        double base = Math.atan2(look.z, look.x);
        double a = base + (RANDOM.nextDouble() - 0.5) * Math.toRadians(150.0);
        double d = NEAR + (FAR - NEAR) * Math.sqrt(RANDOM.nextDouble());
        Ribbon r = new Ribbon();
        r.length = 25.0F + 65.0F * RANDOM.nextFloat();
        // 風上の側から入ってくるよう、帯の頭を少し風上へずらして置く。
        double wl = Math.max(1.0E-4, Math.sqrt(wx * wx + wz * wz));
        r.x = cam.x + Math.cos(a) * d - wx / wl * r.length * 0.5;
        r.z = cam.z + Math.sin(a) * d - wz / wl * r.length * 0.5;
        r.y = cam.y + RANDOM.nextGaussian() * 28.0;
        r.width = 1.4F + 2.2F * RANDOM.nextFloat();
        r.amp = 0.6F + 2.0F * RANDOM.nextFloat();
        r.wave = 18.0F + 24.0F * RANDOM.nextFloat();
        r.phase = RANDOM.nextFloat() * Mth.TWO_PI;
        r.speed = (1.6F + 1.4F * RANDOM.nextFloat()) * (1.0F + 0.8F * SkyAir.gust());
        // 上昇気流・下降気流の所では帯も昇り降りする(見えない気流の手がかり)。
        long now = mc.level.getGameTime();
        double air = SkyAir.lift(CloudField.fieldX(r.x, now, 1.0F), r.y, CloudField.fieldZ(r.z, now, 1.0F), wx, wz);
        r.lift = (float) (RANDOM.nextGaussian() * 0.01 + air * 3.0);
        r.life = 70 + RANDOM.nextInt(90);
        RIBBONS.add(r);
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_WEATHER || broken || RIBBONS.isEmpty()) {
            return;
        }
        boolean main = CanopyLight.drawToMainTarget();  // 「最高」では天気の層でなく主画面へ(CanopyLight#restoreWeatherTarget)
        try {
            render(event);
        } catch (RuntimeException e) {
            broken = true;
            OvergrownVegetation.LOGGER.error("風の白い帯で例外が出たので、このセッションでは使わない", e);
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
        float s = strength(pt);
        if (s <= 0.01F) {
            return;
        }
        double t = level.getGameTime() + pt;
        double wx = CloudField.windX(t);
        double wz = CloudField.windZ(t);
        double wl = Math.max(1.0E-4, Math.sqrt(wx * wx + wz * wz));
        Vec3 dir = new Vec3(wx / wl, 0.0, wz / wl);
        Vec3 side = new Vec3(-dir.z, 0.0, dir.x);
        Vec3 eye = event.getCamera().getPosition();
        // 色: 雲の色(夜は暗い青)。昼は白。
        Vec3 cloud = SkyLight.cloudColor(level, pt, level.getCloudColor(pt));
        float cr = (float) Math.min(1.0, cloud.x);
        float cg = (float) Math.min(1.0, cloud.y);
        float cb = (float) Math.min(1.0, cloud.z);
        MultiBufferSource.BufferSource buffers = CanopyLight.immediateBuffers();  // Iris のまとめ描きを避ける(CanopyLight#immediateBuffers、2026-10-22)
        VertexConsumer c = buffers.getBuffer(CanopyLight.renderType());
        for (Ribbon r : RIBBONS) {
            float age = r.age + pt;
            float env = SkyLight.smooth(Mth.clamp(age / (r.life * 0.25F), 0.0F, 1.0F))
                    * (1.0F - SkyLight.smooth(Mth.clamp((age - r.life * 0.6F) / (r.life * 0.4F), 0.0F, 1.0F)));
            double hx = r.x + wx * r.speed * pt;
            double hz = r.z + wz * r.speed * pt;
            double hy = r.y + r.lift * pt;
            double dist = Math.sqrt((hx - eye.x) * (hx - eye.x) + (hy - eye.y) * (hy - eye.y) + (hz - eye.z) * (hz - eye.z));
            float far = 1.0F - SkyLight.smooth(Mth.clamp((float) ((dist - (FAR - 50.0)) / 60.0), 0.0F, 1.0F));
            float near = SkyLight.smooth(Mth.clamp((float) ((dist - 6.0) / 14.0), 0.0F, 1.0F));
            float alpha = 0.32F * s * env * far * near;
            if (alpha <= 0.003F) {
                continue;
            }
            Vec3 head = new Vec3(hx, hy, hz);
            Vec3[] pts = new Vec3[SEGMENTS + 1];
            float[] w = new float[SEGMENTS + 1];
            float[] a = new float[SEGMENTS + 1];
            // 波は帯にそって流れる(頭の方へ)。
            float flow = (float) (t * 0.12) + r.phase;
            for (int i = 0; i <= SEGMENTS; i++) {
                float u = i / (float) SEGMENTS;
                double along = r.length * (1.0 - u);
                float wave = (float) Math.sin(along / r.wave * Mth.TWO_PI - flow);
                float wave2 = (float) Math.cos(along / (r.wave * 0.6) * Mth.TWO_PI - flow * 1.3F);
                pts[i] = head.subtract(dir.scale(along)).add(side.scale(r.amp * wave)).add(0.0, r.amp * 0.35 * wave2, 0.0);
                float taper = (float) Math.pow(Math.sin(Math.PI * u), 0.7);
                w[i] = r.width * taper;
                // 頭の方(u = 0.6〜0.8)がいちばん濃い。
                a[i] = alpha * (float) Math.pow(Math.sin(Math.PI * u), 1.3) * (0.7F + 0.3F * u);
            }
            for (int i = 0; i < SEGMENTS; i++) {
                strip(c, pts[i], pts[i + 1], w[i], w[i + 1], a[i], a[i + 1], eye, cr, cg, cb);
            }
        }
        buffers.endBatch(CanopyLight.renderType());
    }

    /** a から b への帯 1 節(カメラを向き、真ん中が濃く縁が 0)。 */
    private static void strip(VertexConsumer c, Vec3 a, Vec3 b, float wa, float wb, float aa, float ab, Vec3 eye, float r, float g, float bl) {
        Vec3 axis = b.subtract(a);
        Vec3 sa = axis.cross(eye.subtract(a));
        Vec3 sb = axis.cross(eye.subtract(b));
        double la = sa.length();
        double lb = sb.length();
        if (la < 1.0E-6 || lb < 1.0E-6) {
            return;
        }
        sa = sa.scale(wa * 0.5 / la);
        sb = sb.scale(wb * 0.5 / lb);
        Vec3 ra = a.subtract(eye);
        Vec3 rb = b.subtract(eye);
        vertex(c, ra.subtract(sa), r, g, bl, 0.0F);
        vertex(c, rb.subtract(sb), r, g, bl, 0.0F);
        vertex(c, rb, r, g, bl, ab);
        vertex(c, ra, r, g, bl, aa);
        vertex(c, ra, r, g, bl, aa);
        vertex(c, rb, r, g, bl, ab);
        vertex(c, rb.add(sb), r, g, bl, 0.0F);
        vertex(c, ra.add(sa), r, g, bl, 0.0F);
    }

    private static void vertex(VertexConsumer c, Vec3 p, float r, float g, float b, float a) {
        c.addVertex((float) p.x, (float) p.y, (float) p.z).setColor(r, g, b, Math.min(1.0F, a));
    }

    private WindRibbons() {
    }
}
