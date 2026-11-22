package com.yayuy.overgrownvegetation.client.atmosphere;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.yayuy.overgrownvegetation.client.VisualQuality;
import com.yayuy.overgrownvegetation.data.AtmosphereWater;
import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>潜ったときの光の筋</b>(2026-10-31 夜 ユーザー「地上と全く同じにせず水中用に新しく」)。地上の筋({@link LightShafts})は林冠の穴から落ちる
 * 少数の太い帯だが、水の中では波立つ水面がレンズの列になり、<b>細い筋がたくさん、屈折した太陽の向きへ斜めに</b>降りる。筋は水面のすぐ下が明るく、
 * 下へ行くほど水に吸われて消え、水面の揺れで横に揺らぎ明滅する。
 *
 * <h2>どこに出すか</h2>
 * カメラが水の中にいる間だけ。周り {@code radius} の列から、水面の地図({@link WaterCaustics})で<b>上に葉も何も無い水面</b>を探し、
 * そこから 1 本ずつ置く(寿命 {@code lifeMin}〜{@code lifeMax} 秒で入れ替わる)。向きは水の中の太陽の向き(屈折率 1.33、{@link WaterCaustics#refracted})。
 * 水底より下は地形の奥行きで隠れる。
 *
 * <h2>描き方</h2>
 * カメラを向く帯(芯と、広い薄いにじみの 2 重)を足し算で。横のふちは透明。この描き方は霧を受けないので、遠い筋は水中の霧の距離で頂点ごとに薄める。
 * 色は {@code color} と水中の霧の色を {@code fogMix} で混ぜる(乳白の青緑の水に溶ける)。値は節 {@code underwaterShafts}(すべて仮決め)。
 */
final class UnderwaterShafts {

    private static final RandomSource RANDOM = RandomSource.create();
    private static final List<Shaft> SHAFTS = new ArrayList<>();
    /** カメラが水の中にいる度合い(出入りでなめらかに)。 */
    private static float under;

    /** 1 本。水面の上端の位置・幅・明滅の種・生まれた時刻と寿命(tick)。 */
    private record Shaft(double x, double y, double z, float width, float seed, long born, int life) {
    }

    private UnderwaterShafts() {
    }

    static void clear() {
        SHAFTS.clear();
        under = 0.0F;
    }

    static String describe() {
        return String.format("水中の筋 %d (%.2f)", SHAFTS.size(), under);
    }

    static void tick(ClientLevel level) {
        if (!VisualQuality.underwater()) {
            clear();
            return;
        }
        AtmosphereWater.ShaftsParams p = DesignData.atmosphere().underwaterShafts();
        Minecraft mc = Minecraft.getInstance();
        boolean submerged = mc.gameRenderer.getMainCamera().getFluidInCamera() == FogType.WATER;
        under += ((submerged ? 1.0F : 0.0F) - under) * 0.15F;
        long now = level.getGameTime();
        Vec3 eye = mc.gameRenderer.getMainCamera().getPosition();
        double r2 = p.radius() * p.radius() * 1.5;
        SHAFTS.removeIf(s -> now - s.born > s.life || (s.x - eye.x) * (s.x - eye.x) + (s.z - eye.z) * (s.z - eye.z) > r2);
        if (!submerged) {
            if (under < 0.01F) {
                SHAFTS.clear();
            }
            return;
        }
        boolean murky = MistView.inMurkyWater(mc.gameRenderer.getMainCamera());
        int cap = Math.round(p.counts()[Math.min(2, VisualQuality.atmosphereTier())] * (murky ? p.murkyCount() : 1.0F));
        for (int tries = 0; tries < 6 && SHAFTS.size() < cap; tries++) {
            int x = Mth.floor(eye.x + (RANDOM.nextDouble() * 2.0 - 1.0) * p.radius());
            int z = Mth.floor(eye.z + (RANDOM.nextDouble() * 2.0 - 1.0) * p.radius());
            double surface = WaterCaustics.surfaceAt(x, z);
            // 同じ水の中の筋だけ(カメラより下の別の水面からは出さない)。
            if (Double.isNaN(surface) || surface < eye.y - 1.0 || !WaterCaustics.openAt(x, z)) {
                continue;
            }
            int life = Math.round((p.lifeMin() + RANDOM.nextFloat() * (p.lifeMax() - p.lifeMin())) * 20.0F);
            SHAFTS.add(new Shaft(x + RANDOM.nextDouble(), surface - 0.02, z + RANDOM.nextDouble(),
                    Mth.lerp(RANDOM.nextFloat() * RANDOM.nextFloat(), p.widthMin(), p.widthMax()), RANDOM.nextFloat() * 100.0F, now, life));
        }
    }

    /** AFTER_PARTICLES、カメラが水の中のとき({@link Atmosphere} から)。 */
    static void render(RenderLevelStageEvent event) {
        if (!VisualQuality.underwater()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (SHAFTS.isEmpty() || under <= 0.01F || level == null) {
            return;
        }
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 sun = SunLight.sunDirection(level, partialTick);
        if (sun.y <= 0.03) {
            return;
        }
        AtmosphereWater.ShaftsParams p = DesignData.atmosphere().underwaterShafts();
        float high = SunLight.smooth(Mth.clamp(((float) sun.y - 0.03F) / 0.3F, 0.0F, 1.0F));
        // 過繁茂の濁った水の中: 筋を濃く、霧で薄れる距離を伸ばす(霧が短くても水面からの筋が見えるように)。
        boolean murky = MistView.inMurkyWater(event.getCamera());
        float strength = p.alpha() * (murky ? p.murkyAlpha() : 1.0F) * SunLight.daylight(level, sun, partialTick) * high * under;
        // 11-01 夜(見え方の直し 5): 深いほど弱く(水中の霧の色と同じ倍率)。
        strength *= MistView.depthFactor(level, event.getCamera().getPosition());
        if (strength <= 0.002F) {
            return;
        }
        float[] k = WaterCaustics.refracted(sun);
        float dx = -k[0];
        float dy = -k[1];
        float dz = -k[2];
        Vec3 cam = event.getCamera().getPosition();
        float fogStart = MistView.fogStart(); // 森の中の霞の印を外した始まり
        float fogEnd = Math.max(fogStart + 1.0F, RenderSystem.getShaderFogEnd());
        if (murky) {
            fogEnd *= p.murkyFogReach();
        }
        float[] fog = RenderSystem.getShaderFogColor();
        float cr = Mth.lerp(p.fogMix(), p.r(), Math.min(1.0F, fog[0] * 1.8F + 0.15F));
        float cg = Mth.lerp(p.fogMix(), p.g(), Math.min(1.0F, fog[1] * 1.8F + 0.15F));
        float cb = Mth.lerp(p.fogMix(), p.b(), Math.min(1.0F, fog[2] * 1.8F + 0.15F));
        double now = level.getGameTime() + partialTick;
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        boolean any = false;
        for (Shaft s : SHAFTS) {
            double age = (now - s.born) / 20.0;
            double lifeSec = s.life / 20.0;
            if (age < 0.0 || age > lifeSec) {
                continue;
            }
            float fade = (float) (Mth.clamp(age / 1.5, 0.0, 1.0) * Mth.clamp((lifeSec - age) / 1.5, 0.0, 1.0));
            // 水面の揺れで、1 本ずつ別々にゆっくり明滅し横へ揺らぐ。
            float flicker = 0.6F + 0.4F * Mth.sin((float) age * 0.9F + s.seed) * Mth.sin((float) age * 0.37F + s.seed * 1.7F);
            float alpha = strength * fade * flicker;
            if (alpha <= 0.002F) {
                continue;
            }
            double tx = s.x + Math.sin(age * 0.35 + s.seed) * p.sway();
            double tz = s.z + Math.cos(age * 0.29 + s.seed * 1.3) * p.sway();
            double bx = tx + dx * p.length();
            double by = s.y + dy * p.length();
            double bz = tz + dz * p.length();
            any = true;
            beam(builder, cam, tx, s.y, tz, bx, by, bz, s.width, cr, cg, cb, alpha, fogStart, fogEnd);
            beam(builder, cam, tx, s.y, tz, bx, by, bz, s.width * 2.6F, cr, cg, cb, alpha * 0.3F, fogStart, fogEnd);
        }
        MeshData mesh = builder.build();
        if (mesh == null) {
            return;
        }
        ShaderInstance shader = GameRenderer.getPositionColorShader();
        if (!any || shader == null) {
            mesh.close();
            return;
        }
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        shader.setDefaultUniforms(VertexFormat.Mode.TRIANGLES, new Matrix4f(event.getModelViewMatrix()), new Matrix4f(event.getProjectionMatrix()),
                mc.getWindow());
        shader.apply();
        BufferUploader.draw(mesh);
        shader.clear();
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
    }

    /**
     * カメラを向く帯 1 本(軸は上端 → 下端)。縦は 3 段: 水面のすぐ下 0.6 → 長さの 12% で 1 → 下端で 0。横は真ん中の線が明るく両ふちが 0。
     * 遠い頂点は水中の霧の距離で薄める。
     */
    private static void beam(BufferBuilder b, Vec3 cam, double x0, double y0, double z0, double x1, double y1, double z1, float width,
                             float r, float g, float bl, float alpha, float fogStart, float fogEnd) {
        double ax = x1 - x0;
        double ay = y1 - y0;
        double az = z1 - z0;
        double mx = (x0 + x1) * 0.5 - cam.x;
        double my = (y0 + y1) * 0.5 - cam.y;
        double mz = (z0 + z1) * 0.5 - cam.z;
        // 横の向き = 軸 × カメラへの向き。
        double sx = ay * mz - az * my;
        double sy = az * mx - ax * mz;
        double sz = ax * my - ay * mx;
        double sl = Math.sqrt(sx * sx + sy * sy + sz * sz);
        if (sl < 1.0E-6) {
            return;
        }
        double h = width * 0.5 / sl;
        sx *= h;
        sy *= h;
        sz *= h;
        double[] ts = {0.0, 0.12, 1.0};
        float[] as = {0.6F, 1.0F, 0.0F};
        for (int row = 0; row < 2; row++) {
            double ta = ts[row];
            double tb = ts[row + 1];
            double pax = x0 + ax * ta;
            double pay = y0 + ay * ta;
            double paz = z0 + az * ta;
            double pbx = x0 + ax * tb;
            double pby = y0 + ay * tb;
            double pbz = z0 + az * tb;
            float aa = alpha * as[row] * fogFade(pax, pay, paz, cam, fogStart, fogEnd);
            float ab = alpha * as[row + 1] * fogFade(pbx, pby, pbz, cam, fogStart, fogEnd);
            for (int side = -1; side <= 1; side += 2) {
                // 真ん中の線(明るい)と、片側のふち(透明)の間の帯。
                vertex(b, cam, pax, pay, paz, r, g, bl, aa);
                vertex(b, cam, pax + sx * side, pay + sy * side, paz + sz * side, r, g, bl, 0.0F);
                vertex(b, cam, pbx + sx * side, pby + sy * side, pbz + sz * side, r, g, bl, 0.0F);
                vertex(b, cam, pax, pay, paz, r, g, bl, aa);
                vertex(b, cam, pbx + sx * side, pby + sy * side, pbz + sz * side, r, g, bl, 0.0F);
                vertex(b, cam, pbx, pby, pbz, r, g, bl, ab);
            }
        }
    }

    private static float fogFade(double x, double y, double z, Vec3 cam, float fogStart, float fogEnd) {
        double dx = x - cam.x;
        double dy = y - cam.y;
        double dz = z - cam.z;
        float d = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        float t = Mth.clamp((d - fogStart) / (fogEnd - fogStart), 0.0F, 1.0F);
        return 1.0F - t * t * (3.0F - 2.0F * t);
    }

    private static void vertex(BufferBuilder b, Vec3 cam, double x, double y, double z, float r, float g, float bl, float a) {
        b.addVertex((float) (x - cam.x), (float) (y - cam.y), (float) (z - cam.z)).setColor(r, g, bl, a);
    }
}
