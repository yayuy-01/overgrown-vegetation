package com.yayuy.overgrownvegetation.client.worldevent;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.atmosphere.CloudHaze;
import com.yayuy.overgrownvegetation.client.atmosphere.DenseFog;
import com.yayuy.overgrownvegetation.client.atmosphere.ForestPresence;
import com.yayuy.overgrownvegetation.client.atmosphere.SunLight;
import com.yayuy.overgrownvegetation.client.shader.ShaderPacks;
import com.yayuy.overgrownvegetation.client.sky.CloudInterior;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.WorldEventLooks;
import com.yayuy.overgrownvegetation.worldevent.WorldEventKind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.material.FogType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import com.yayuy.overgrownvegetation.port.event.tick.ClientTickEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * <b>流星群</b>(2026-11-16。ユーザー決定: 一晩じゅう流れ星、たまに大きな火球が地平線の向こうへ落ちる。<b>音は付けない</b>。効き目なし)。
 * 理由(設定): 植物と関係ない、ただの空の出来事(星系の匂わせ)。
 *
 * <p>空の一点(放射点。その夜ごとに決まる方角と高さ)から四方へ流れる。流れ星は空の段で(星の後・雲と地形の前に)、向きだけで描く細い光の筋
 * (頭が明るく、尾が細く消える。足し算)。数は真夜中(進み 0.5)が盛り。火球はたまに 1 つ: 太く長く、ゆっくり地平線へ向かって落ち、通った跡が少し残る。
 * 雲・地形に隠れる。雨・濃霧・雲の中・森の霞の中では薄くなる。値は {@code worldEvents.meteors}(仮決め)。確かめは {@code /ov event start meteor_shower <進み>}。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, value = Dist.CLIENT)
public final class MeteorShower {

    private MeteorShower() {
    }

    private static final class Meteor {
        /** 放射点から回す軸・始まりの角度・進む角度(ラジアン)。 */
        final Vector3f axis = new Vector3f();
        float from, travel;
        float age, life;
        /** 尾の長さ(ラジアン)・太さ(ラジアン)・明るさ。 */
        float tail, width, bright;
        boolean fireball;
    }

    private static final List<Meteor> METEORS = new ArrayList<>();
    private static final Random RANDOM = new Random();
    private static final Vector3f RADIANT = new Vector3f(0.0F, 1.0F, 0.0F);
    private static long radiantDay = Long.MIN_VALUE;
    private static float nextFireball = -1.0F;
    private static boolean broken;
    private static int spawned;

    /** いまの流れ星の出やすさ(0〜1。真夜中が盛り)。起きていなければ 0。 */
    private static float rate(float partialTick) {
        if (!WorldEventClient.is(WorldEventKind.METEOR_SHOWER)) {
            return 0.0F;
        }
        float e = WorldEventClient.elapsedTicks(partialTick);
        float len = WorldEventClient.lengthTicks();
        float p = len > 0.0F ? e / len : 0.0F;
        float peak = 0.35F + 0.65F * (float) Math.exp(-Math.pow((p - 0.5F) / 0.22F, 2.0));
        return WorldEventSky.envelope(e, len, 600.0F, 600.0F) * peak;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            METEORS.clear();
            return;
        }
        if (mc.isPaused()) {
            return;
        }
        for (int i = METEORS.size() - 1; i >= 0; i--) {
            Meteor m = METEORS.get(i);
            m.age += 0.05F;
            if (m.age > m.life + (m.fireball ? 2.0F : 0.0F)) {
                METEORS.remove(i);
            }
        }
        float rate = rate(0.0F);
        WorldEventLooks.Meteors p = DesignData.sky().events.meteors;
        if (rate <= 0.0F || p.strength <= 0.0F) {
            nextFireball = -1.0F;
            return;
        }
        // 放射点: その夜ごとに決まる(日の番号から)。高さ 35〜60 度。
        long day = Math.floorDiv(level.getDayTime() + 6000L, 24000L);
        if (day != radiantDay) {
            radiantDay = day;
            Random r = new Random(day * 0x9E3779B97F4A7C15L + 77L);
            double az = r.nextDouble() * Math.PI * 2.0;
            double el = Math.toRadians(35.0 + r.nextDouble() * 25.0);
            RADIANT.set((float) (Math.cos(az) * Math.cos(el)), (float) Math.sin(el), (float) (Math.sin(az) * Math.cos(el)));
        }
        // 流れ星: 1 分に perMinute 本(盛りのとき)。1 tick の確率へ直す。
        if (METEORS.size() < 24 && RANDOM.nextFloat() < p.perMinute * rate / 1200.0F) {
            METEORS.add(make(false));
            spawned++;
        }
        // 火球: 平均 fireballMinutes 分に 1 つ(盛りのとき)。
        if (nextFireball < 0.0F) {
            nextFireball = nextGap(p);
        }
        nextFireball -= 0.05F * rate;
        if (nextFireball <= 0.0F) {
            nextFireball = nextGap(p);
            METEORS.add(make(true));
            spawned++;
        }
    }

    private static float nextGap(WorldEventLooks.Meteors p) {
        return Math.max(5.0F, p.fireballMinutes * 60.0F * (0.5F + RANDOM.nextFloat()));
    }

    private static Meteor make(boolean fireball) {
        Meteor m = new Meteor();
        m.fireball = fireball;
        // 放射点に直交する向きを 1 つ選び、それを軸に回す = 放射点から外へ向かう大円。
        Vector3f any = new Vector3f(RANDOM.nextFloat() - 0.5F, RANDOM.nextFloat() - 0.5F, RANDOM.nextFloat() - 0.5F);
        if (fireball) {
            // 火球は地平線へ落ちていく向き: 軸を水平にする(放射点 × 上 を少し振る)。
            any.set(0.0F, 1.0F, 0.0F).add((RANDOM.nextFloat() - 0.5F) * 0.5F, 0.0F, (RANDOM.nextFloat() - 0.5F) * 0.5F);
        }
        m.axis.set(RADIANT).cross(any);
        if (m.axis.lengthSquared() < 1.0e-4F) {
            m.axis.set(1.0F, 0.0F, 0.0F);
        }
        m.axis.normalize();
        if (fireball) {
            if (RANDOM.nextBoolean()) {
                m.axis.negate();
            }
            m.from = (float) Math.toRadians(8.0 + RANDOM.nextDouble() * 20.0);
            m.travel = (float) Math.toRadians(55.0 + RANDOM.nextDouble() * 35.0);
            m.life = 2.6F + RANDOM.nextFloat() * 1.6F;
            m.tail = (float) Math.toRadians(16.0);
            m.width = (float) Math.toRadians(0.6);
            m.bright = 1.0F;
        } else {
            m.from = (float) Math.toRadians(6.0 + RANDOM.nextDouble() * 60.0);
            m.travel = (float) Math.toRadians(6.0 + RANDOM.nextDouble() * 20.0);
            m.life = 0.35F + RANDOM.nextFloat() * 0.7F;
            m.tail = (float) Math.toRadians(4.0 + RANDOM.nextDouble() * 5.0);
            m.width = (float) Math.toRadians(0.17 + RANDOM.nextDouble() * 0.13);
            m.bright = 0.45F + 0.55F * RANDOM.nextFloat() * RANDOM.nextFloat();
        }
        return m;
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        METEORS.clear();
        nextFireball = -1.0F;
    }

    /** 空の段で、遠くの雲より先に。 */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY || broken || METEORS.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || ShaderPacks.active() || event.getCamera().getFluidInCamera() != FogType.NONE || !level.dimensionType().hasSkyLight()) {
            return;
        }
        try {
            draw(event, level);
        } catch (RuntimeException e) {
            broken = true;
            OvergrownVegetation.LOGGER.error("流星群で例外が出たので、このセッションでは描かない", e);
        }
    }

    private static void draw(RenderLevelStageEvent event, ClientLevel level) {
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        WorldEventLooks.Meteors p = DesignData.sky().events.meteors;
        float amount = p.strength * SunLight.night(level, partial) * (1.0F - level.getRainLevel(partial)) * (1.0F - DenseFog.skyFillPublic())
                * (1.0F - Mth.clamp(CloudInterior.amount(partial) * 2.0F, 0.0F, 1.0F)) * (1.0F - ForestPresence.underground())
                * (1.0F - 0.6F * CloudHaze.wall());
        if (amount <= 0.01F) {
            return;
        }
        Vector3f look = event.getCamera().getLookVector();
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
        Vector3f head = new Vector3f();
        Vector3f a = new Vector3f();
        Vector3f c = new Vector3f();
        Vector3f side = new Vector3f();
        final float dist = 100.0F;
        final int seg = 8;
        for (Meteor m : METEORS) {
            float age = m.age + partial * 0.05F;
            float u = Mth.clamp(age / m.life, 0.0F, 1.0F);
            // 明るさ: すっと出て、終わりで消える。火球は終わりにいちど明るくなる(砕ける)。
            float life = SunLight.smooth(Mth.clamp(u / 0.15F, 0.0F, 1.0F)) * (1.0F - SunLight.smooth(Mth.clamp((u - 0.75F) / 0.25F, 0.0F, 1.0F)));
            if (m.fireball) {
                life *= 1.0F + 0.8F * (float) Math.exp(-Math.pow((u - 0.72F) / 0.07F, 2.0));
            }
            // 火球の跡: 消えた後も 2 秒ほど、通った道が淡く残る。
            float trail = m.fireball ? 0.22F * Mth.clamp(1.0F - (age - m.life * 0.5F) / (m.life * 0.5F + 2.0F), 0.0F, 1.0F) : 0.0F;
            float headAng = m.from + m.travel * u;
            float tailLen = m.fireball && age > m.life * 0.3F ? Math.max(m.tail, m.travel * u) : Math.min(m.tail, m.travel * u);
            float r = p.color[0], g = p.color[1], bl = p.color[2];
            if (m.fireball) {
                r = p.fireballColor[0];
                g = p.fireballColor[1];
                bl = p.fireballColor[2];
            }
            for (int i = 0; i < seg; i++) {
                float t0 = i / (float) seg;
                float t1 = (i + 1) / (float) seg;
                point(m, headAng - tailLen * t0, a);
                point(m, headAng - tailLen * t1, c);
                if (a.y < 0.02F && c.y < 0.02F) {
                    continue;
                }
                // 帯の横の向き = 進む向き × 視線。
                side.set(a).sub(c).cross(a).normalize();
                float w0 = m.width * (1.0F - 0.8F * t0) * dist * 0.5F;
                float w1 = m.width * (1.0F - 0.8F * t1) * dist * 0.5F;
                float near = m.tail / Math.max(tailLen, 1.0e-4F);
                float a0 = alpha(t0, near, life, trail) * m.bright * amount * horizon(a.y);
                float a1 = alpha(t1, near, life, trail) * m.bright * amount * horizon(c.y);
                b.addVertex(a.x * dist - side.x * w0, a.y * dist - side.y * w0, a.z * dist - side.z * w0).setColor(r, g, bl, a0);
                b.addVertex(a.x * dist + side.x * w0, a.y * dist + side.y * w0, a.z * dist + side.z * w0).setColor(r, g, bl, a0);
                b.addVertex(c.x * dist + side.x * w1, c.y * dist + side.y * w1, c.z * dist + side.z * w1).setColor(r, g, bl, a1);
                b.addVertex(c.x * dist - side.x * w1, c.y * dist - side.y * w1, c.z * dist - side.z * w1).setColor(r, g, bl, a1);
            }
            if (m.fireball && life > 0.01F) {
                // 火球の頭: やわらかい光の丸(8 角形)。
                point(m, headAng, head);
                if (head.y > 0.02F) {
                    glow(b, head, look, dist, m.width * dist * 5.0F, r, g, bl, 0.55F * life * amount * horizon(head.y));
                    glow(b, head, look, dist, m.width * dist * 1.6F, 1.0F, 1.0F, 0.95F, 0.9F * life * amount * horizon(head.y));
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

    /** 尾の濃さ: 頭(t = 0)が明るく、尾の長さ(near = ふつうの尾 ÷ 今の尾)の先は淡い跡だけ。 */
    private static float alpha(float t, float near, float life, float trail) {
        float core = Math.max(0.0F, 1.0F - t / Math.min(1.0F, near));
        return core * core * life + trail * (1.0F - t);
    }

    private static float horizon(float y) {
        return Mth.clamp((y - 0.02F) / 0.10F, 0.0F, 1.0F);
    }

    /** 放射点を軸のまわりに angle 回した向き。 */
    private static void point(Meteor m, float angle, Vector3f out) {
        out.set(RADIANT).rotateAxis(angle, m.axis.x, m.axis.y, m.axis.z);
    }

    private static void glow(BufferBuilder b, Vector3f at, Vector3f look, float dist, float radius, float r, float g, float bl, float alpha) {
        float ux = -look.z(), uz = look.x();
        float ul = Mth.sqrt(ux * ux + uz * uz);
        if (ul < 1.0e-4F) {
            ux = 1.0F;
            uz = 0.0F;
            ul = 1.0F;
        }
        ux /= ul;
        uz /= ul;
        float vx = -look.y() * uz, vy = look.z() * ux - look.x() * uz, vz = look.y() * ux;
        float x = at.x * dist, y = at.y * dist, z = at.z * dist;
        for (int i = 0; i < 8; i++) {
            double a0 = i * Math.PI / 4.0;
            double a1 = (i + 1) * Math.PI / 4.0;
            float c0 = (float) Math.cos(a0) * radius, s0 = (float) Math.sin(a0) * radius;
            float c1 = (float) Math.cos(a1) * radius, s1 = (float) Math.sin(a1) * radius;
            b.addVertex(x, y, z).setColor(r, g, bl, alpha);
            b.addVertex(x + ux * c0 + vx * s0, y + vy * s0, z + uz * c0 + vz * s0).setColor(r, g, bl, 0.0F);
            b.addVertex(x + ux * c1 + vx * s1, y + vy * s1, z + uz * c1 + vz * s1).setColor(r, g, bl, 0.0F);
            b.addVertex(x, y, z).setColor(r, g, bl, alpha);
        }
    }

    /** 確かめ用の 1 行。 */
    public static String describe() {
        return "流星群: いま " + METEORS.size() + " 本・これまで " + spawned + " 本" + (broken ? "・例外で止めた" : "");
    }
}
