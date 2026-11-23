package com.yayuy.overgrownvegetation.client.worldevent;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.atmosphere.DenseFog;
import com.yayuy.overgrownvegetation.client.atmosphere.ForestPresence;
import com.yayuy.overgrownvegetation.client.shader.ShaderPacks;
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
import net.minecraftforge.client.event.RenderLevelStageEvent;

import java.util.Random;

/**
 * <b>地平線を歩く影</b>(2026-11-16。ユーザー決定: 霞んだ地平線の向こうを、山より大きな影がゆっくり横切る。<b>監視塔(フィロセンス)に似せる</b>
 * = 植物側の巨大な何かを匂わせる。近づいてもたどり着けない。効き目なし。正体は説明しない)。
 *
 * <p>形: 監視塔(蔦の太い塔・根元の板根・てっぺんから伸びる長い首と花の頭)が、長い脚で歩いている影絵。平らな 1 色(その時の霧の色を暗くした色)で、
 * 細部は無い —— 霧の向こうの影なので。
 *
 * <p>描き方: 空の段(空の後・雲と地形の前)で、<b>向きと見かけの大きさだけ</b>で描く(距離を持たない)。だからどれだけ歩いても同じ大きさで地平線の向こうにいて、
 * たどり着けない。地形・雲には隠れる。足元は地平線の下(霧の色の輪)へ消える。
 *
 * <p>動き: とても遅い歩み(1 歩 {@code stepSeconds} 秒)。{@code stepsPerWalk} 歩ごとに立ち止まり({@code pauseSeconds} 秒)、首をゆっくり回す。
 * 歩いた分だけ方位が進み、約 10 分で空の {@code 40} 度ほどを横切る。方位と歩く向きはその日ごとに決まる。
 * 霧が薄れると一緒に消える(濃霧の度合いを掛ける。サーバーも霧が晴れたらイベントを終える)。値は {@code worldEvents.shadow}(仮決め)。
 * 確かめは {@code /ov weather fog} の後に {@code /ov event start horizon_shadow <進み>}。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, value = Dist.CLIENT)
public final class HorizonShadow {

    private HorizonShadow() {
    }

    private static boolean broken;
    private static float lastShown;
    private static float lastAz;

    // 描いている間だけ使う(1 体ぶんの向きと大きさ)。
    private static BufferBuilder buf;
    private static double azC;
    private static float el0, scale, flip;
    private static float cr, cg, cb, ca;

    /** 空の段で、遠くの雲より先に。 */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY || broken) {
            return;
        }
        lastShown = 0.0F;
        if (!WorldEventClient.is(WorldEventKind.HORIZON_SHADOW)) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || ShaderPacks.active() || !com.yayuy.overgrownvegetation.client.VisualQuality.effects() || event.getCamera().getFluidInCamera() != FogType.NONE || !level.dimensionType().hasSkyLight()) {
            return;
        }
        try {
            draw(event, level);
        } catch (RuntimeException e) {
            broken = true;
            OvergrownVegetation.LOGGER.error("地平線を歩く影で例外が出たので、このセッションでは描かない", e);
        }
    }

    private static void draw(RenderLevelStageEvent event, ClientLevel level) {
        float partial = event.getPartialTick();
        WorldEventLooks.Shadow p = DesignData.sky().events.shadow;
        float elapsed = WorldEventClient.elapsedTicks(partial);
        float len = WorldEventClient.lengthTicks();
        // 霧と一緒に現れて消える: 濃霧の度合い fogFrom〜fogFull で 0 → 1。
        float fog = Mth.clamp((DenseFog.level() - p.fogFrom) / Math.max(0.01F, p.fogFull - p.fogFrom), 0.0F, 1.0F);
        float shown = WorldEventSky.envelope(elapsed, len, 400.0F, 400.0F) * fog * p.strength * (1.0F - ForestPresence.underground())
                * (1.0F - 0.6F * level.getRainLevel(partial));
        if (shown <= 0.01F) {
            return;
        }
        lastShown = shown;
        // その日ごとの方位と歩く向き。
        long day = Math.floorDiv(level.getDayTime(), 24000L);
        Random r = new Random(day * 0x9E3779B97F4A7C15L + 4242L);
        double az0 = r.nextDouble() * Math.PI * 2.0;
        flip = r.nextBoolean() ? 1.0F : -1.0F;

        // 歩み: stepsPerWalk 歩いて pauseSeconds 止まる、の繰り返し。walked = これまでに歩いた歩数(小数)。
        float t = elapsed / 20.0F;
        float step = Math.max(2.0F, p.stepSeconds);
        float walkTime = step * Math.max(1, p.stepsPerWalk);
        float period = walkTime + Math.max(0.0F, p.pauseSeconds);
        float inPeriod = t % period;
        float cycles = (float) Math.floor(t / period) * Math.max(1, p.stepsPerWalk);
        // 歩き始めと歩き終わりをなめらかに。
        float w = Mth.clamp(inPeriod / walkTime, 0.0F, 1.0F);
        w = w * w * (3.0F - 2.0F * w);
        float walked = cycles + w * Math.max(1, p.stepsPerWalk);
        float pausing = inPeriod > walkTime ? (inPeriod - walkTime) / Math.max(0.01F, period - walkTime) : 0.0F;

        scale = (float) Math.toRadians(Mth.clamp(p.heightDeg, 3.0F, 40.0F));
        // 1 歩で進む幅 = 体の高さの stride 倍。方位はその分だけ進む。
        azC = az0 + flip * walked * p.stride * scale;
        lastAz = (float) Math.toDegrees(azC % (Math.PI * 2.0));
        el0 = (float) Math.toRadians(p.baseDeg);
        float[] fogColor = RenderSystem.getShaderFogColor();
        float dk = Mth.clamp(p.darken, 0.0F, 1.0F);
        cr = fogColor[0] * (1.0F - dk);
        cg = fogColor[1] * (1.0F - dk);
        cb = fogColor[2] * (1.0F - dk);
        ca = Mth.clamp(shown * p.opacity, 0.0F, 1.0F);

        WorldEventSky.pushSkyView(event);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ONE);
        buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        figure(walked, pausing, t);
        var mesh = buf.endOrDiscardIfEmpty();
        buf = null;
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

    /**
     * 影絵(体の高さを 1 とした平面。x = 進む向きが正、y = 上)。脚 4 本(腰 0.50)・塔の胴(0.44〜0.74。下が板根で広がる)・首(8 節、0.74 から前上へ)・花の頭。
     */
    private static void figure(float walked, float pausing, float t) {
        float phase = walked * Mth.TWO_PI;
        // 体の上下: 1 歩に 2 回、わずかに沈む。
        float bob = -0.006F * (0.5F + 0.5F * Mth.cos(phase * 2.0F));
        float hip = 0.50F + bob;
        // 脚: 前の組と後ろの組、それぞれ手前と奥。対角の脚が一緒に動く。
        leg(0.120F, hip, phase, 0.0F);
        leg(0.085F, hip, phase + Mth.PI, 0.012F);
        leg(-0.085F, hip, phase + Mth.PI, 0.0F);
        leg(-0.120F, hip, phase, 0.012F);
        // 胴: 板根で広がった裾 → くびれ → 塔のてっぺん。
        float y0 = 0.44F + bob, y1 = 0.52F + bob, y2 = 0.62F + bob, y3 = 0.745F + bob;
        quad(-0.175F, y0, 0.175F, y0, 0.110F, y1, -0.110F, y1);
        quad(-0.110F, y1, 0.110F, y1, 0.080F, y2, -0.080F, y2);
        quad(-0.080F, y2, 0.080F, y2, 0.066F, y3, -0.066F, y3);
        // 板根のひだ(裾の両脇に小さな三角)。
        quad(-0.175F, y0, -0.110F, y1, -0.145F, y1 + 0.03F, -0.210F, y0 + 0.01F);
        quad(0.175F, y0, 0.110F, y1, 0.145F, y1 + 0.03F, 0.210F, y0 + 0.01F);
        // 首: 歩く間は前へ傾けてゆっくり揺らし、立ち止まると起こして左右を見回す。
        float look = pausing > 0.0F ? Mth.sin(pausing * Mth.PI) : 0.0F;
        float lean = Mth.lerp(look, 0.55F + 0.10F * Mth.sin(t * 0.21F), 0.12F + 0.5F * Mth.sin(pausing * Mth.TWO_PI * 1.0F));
        float x = 0.0F, y = y3;
        float ang = Mth.HALF_PI;
        final int n = 8;
        for (int i = 0; i < n; i++) {
            float u = i / (float) n;
            // 根元はまっすぐ上、先へ行くほど前へ曲がる。
            ang = Mth.HALF_PI - lean * (u * 1.5F) - 0.10F * Mth.sin(t * 0.33F + u * 3.0F);
            float nx = x + Mth.cos(ang) * 0.042F;
            float ny = y + Mth.sin(ang) * 0.042F;
            seg(x, y, nx, ny, Mth.lerp(u, 0.052F, 0.020F), Mth.lerp((i + 1) / (float) n, 0.052F, 0.020F));
            x = nx;
            y = ny;
        }
        // 花の頭: 丸い芯と、まわりの花びら 6 枚。首の先の向きへ少し突き出す。
        float hx = x + Mth.cos(ang) * 0.020F, hy = y + Mth.sin(ang) * 0.020F;
        for (int i = 0; i < 8; i++) {
            float a0 = i * Mth.TWO_PI / 8.0F, a1 = (i + 1) * Mth.TWO_PI / 8.0F;
            quad(hx, hy, hx + Mth.cos(a0) * 0.030F, hy + Mth.sin(a0) * 0.030F, hx + Mth.cos(a1) * 0.030F, hy + Mth.sin(a1) * 0.030F, hx, hy);
        }
        for (int i = 0; i < 6; i++) {
            float a = ang + i * Mth.TWO_PI / 6.0F + 0.2F * Mth.sin(t * 0.4F);
            seg(hx + Mth.cos(a) * 0.022F, hy + Mth.sin(a) * 0.022F, hx + Mth.cos(a) * 0.058F, hy + Mth.sin(a) * 0.058F, 0.022F, 0.004F);
        }
    }

    /** 脚 1 本: 腰 → 膝 → 足先。足先は地面で後ろへ流れ、上がって前へ戻る。 */
    private static void leg(float hipX, float hipY, float phase, float thin) {
        float s = Mth.sin(phase);
        float c = Mth.cos(phase);
        // 足先: 着いている間(c < 0)は後ろへ、上がっている間は前へ。
        float footX = hipX * 1.6F + 0.055F * s;
        float footY = Math.max(0.0F, c) * 0.045F;
        // 膝: 腰と足先の間を外へ(前の脚は前へ、後ろの脚は後ろへ)張り出す。
        float kneeX = (hipX + footX) * 0.5F + Math.signum(hipX) * (0.035F + 0.02F * Math.max(0.0F, c));
        float kneeY = (hipY + footY) * 0.5F + 0.03F;
        seg(hipX, hipY + 0.02F, kneeX, kneeY, 0.042F - thin, 0.028F - thin * 0.6F);
        seg(kneeX, kneeY, footX, footY, 0.028F - thin * 0.6F, 0.016F);
    }

    /** 太さのある線分(両端の太さ w0, w1)。 */
    private static void seg(float x0, float y0, float x1, float y1, float w0, float w1) {
        float dx = x1 - x0, dy = y1 - y0;
        float l = Mth.sqrt(dx * dx + dy * dy);
        if (l < 1.0e-5F) {
            return;
        }
        float nx = -dy / l * 0.5F, ny = dx / l * 0.5F;
        quad(x0 - nx * w0, y0 - ny * w0, x0 + nx * w0, y0 + ny * w0, x1 + nx * w1, y1 + ny * w1, x1 - nx * w1, y1 - ny * w1);
    }

    private static void quad(float x0, float y0, float x1, float y1, float x2, float y2, float x3, float y3) {
        vertex(x0, y0);
        vertex(x1, y1);
        vertex(x2, y2);
        vertex(x3, y3);
    }

    /** 影絵の平面の点 → 空の向き(方位 azC + x、仰角 el0 + y)。足元(地平線の下)へ向けて透明に。 */
    private static void vertex(float x, float y) {
        double az = azC + flip * x * scale;
        float el = el0 + y * scale;
        float ce = Mth.cos(el);
        float a = ca * Mth.clamp((el + 0.075F) / 0.03F, 0.0F, 1.0F);
        buf.vertex((float) Math.cos(az) * ce * 100.0F, Mth.sin(el) * 100.0F, (float) Math.sin(az) * ce * 100.0F).color(cr, cg, cb, a).endVertex();
    }

    /** 確かめ用の 1 行(見えている度合いと、いる方位。方位は度で、+X が 0・+Z が 90)。 */
    public static String describe() {
        return String.format("地平線を歩く影: %.2f・方位 %.0f 度%s", lastShown, lastAz, broken ? "・例外で止めた" : "");
    }
}
