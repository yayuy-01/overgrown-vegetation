package com.yayuy.overgrownvegetation.client.atmosphere;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.yayuy.overgrownvegetation.client.VisualQuality;
import com.yayuy.overgrownvegetation.client.shader.ModShaders;
import com.yayuy.overgrownvegetation.data.AtmosphereLife;
import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * <b>雨の見え方</b>(2026-11-06 ユーザー決定。Vault「雨の見え方(風に靡く・半透明の白)」)。
 * <ul>
 *     <li><b>風に靡く</b>(理由: 世界に卓越風が 1 つあり、胞子も同じ風に乗る設定。風が目で分かる): バニラの雨と雪の柱
 *     ({@code LevelRenderer#renderSnowAndRain})の頂点を、{@link GustField} の今のカメラの所の風へ高さに比例してずらす
 *     ({@code mixin/client/LevelRendererRainLookMixin})。上が風上・下が風下。傾きは風の強さ ÷ 最も強い風 × {@code windTilt}(ユーザー指定 30°)、
 *     突風で揺れる(風の場が時刻で連続に変わるので、描くたびに読む)。雪は軽いので {@code snowTiltScale} 倍・上限 {@code snowTiltMax}、
 *     柱ごとにゆっくりゆらぐ({@code snowSway}。仮決め)。</li>
 *     <li><b>半透明の白</b>(理由: バニラの雨は空の色と関係なく青く浮く。本物の雨は周りを映して白〜灰): 絵はバニラのまま(10-31 の決定)、
 *     シェーダー {@code rain_white} で色相を抜き、白 × その時刻の空の色を少し({@link SunLight#skyTint} を {@code skyTintShare})、光は明るさだけ
 *     (夜に青く光らない)。水滴は全部(ユーザー指定): 雨の筋・雪・雨の跳ねと水しぶき({@code WaterDropParticle})・水の滴
 *     ({@code DripParticle} の水。葉の滴 {@link RainDrops}・鍾乳石・垂れる根の滴も)。溶岩・蜂蜜の滴はそのまま。</li>
 *     <li>胞子の空気が濃い所では、雨がわずかに黄緑に濁る({@code sporeTint}。11-06 からカメラの所の胞子の空気の度合い {@link SporeAir#level})。</li>
 * </ul>
 * 地形の雰囲気と同じく {@link Atmosphere#enabled}(シェーダーパック使用中は切る)と、設定の「水面の演出」({@code waterLife})で切れる。
 */
public final class RainLook {

    private static final float[] TINT = new float[3];
    /** 今のフレームの雨の色(雪の束で不透明さだけ替えるとき使う)。 */
    private static final float[] tint = {1.0F, 1.0F, 1.0F};
    private static final double[] WIND = new double[2];
    private static final ResourceLocation SNOW = ResourceLocation.withDefaultNamespace("textures/environment/snow.png");

    private static boolean active;
    private static boolean snow;
    private static float rainSx;
    private static float rainSz;
    private static float snowSx;
    private static float snowSz;
    private static float snowSway;
    private static float time;
    private static double camX;
    private static double camZ;

    /** 白い水滴の粒の描き方(バニラの PARTICLE_SHEET_TRANSLUCENT と同じ手順で、シェーダーだけ {@code rain_white})。 */
    public static final ParticleRenderType WHITE_WATER = new ParticleRenderType() {
        @Override
        public BufferBuilder begin(Tesselator tesselator, TextureManager textureManager) {
            RenderSystem.depthMask(true);
            RenderSystem.setShaderTexture(0, TextureAtlas.LOCATION_PARTICLES);
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            ShaderInstance shader = ModShaders.rainWhite();
            if (shader != null) {
                RenderSystem.setShader(() -> shader);
            } else {
                RenderSystem.setShader(GameRenderer::getParticleShader);
            }
            return tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);
        }

        @Override
        public String toString() {
            return "OVERGROWN_WHITE_WATER";
        }

        @Override
        public boolean isTranslucent() {
            return true;
        }
    };

    private static boolean enabled(ClientLevel level) {
        return level != null && DesignData.atmosphere().rainLook().enabled() && Atmosphere.enabled(level) && VisualQuality.waterLife()
                && ModShaders.rainWhite() != null;
    }

    /** 水滴の粒を白で描くか(粒の Mixin から)。 */
    public static boolean whiteParticles() {
        return enabled(Minecraft.getInstance().level);
    }

    /** AFTER_SKY に、白い雨のシェーダーへ色(時刻の色・胞子の濁り・不透明さ)を流す。 */
    static void upload(boolean on) {
        ShaderInstance shader = ModShaders.rainWhite();
        if (shader == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        AtmosphereLife.RainLookParams p = DesignData.atmosphere().rainLook();
        float r = 1.0F;
        float g = 1.0F;
        float b = 1.0F;
        if (on && level != null) {
            float pt = mc.getTimer().getGameTimeDeltaPartialTick(false);
            SunLight.skyTint(level, SunLight.sunDirection(level, pt), pt, TINT);
            r = Mth.lerp(p.skyTintShare(), 1.0F, TINT[0]);
            g = Mth.lerp(p.skyTintShare(), 1.0F, TINT[1]);
            b = Mth.lerp(p.skyTintShare(), 1.0F, TINT[2]);
            // 11-06: 物差しを胞子の空気の度合い(SporeAir.level、0〜1)にそろえた(前は届いた濃度 ÷ 100 で弱すぎて見えなかった)。
            float spore = p.sporeTint() * SporeAir.level();
            float[] s = p.sporeColor();
            r = Mth.lerp(spore, r, r * s[0]);
            g = Mth.lerp(spore, g, g * s[1]);
            b = Mth.lerp(spore, b, b * s[2]);
        }
        tint[0] = r;
        tint[1] = g;
        tint[2] = b;
        shader.safeGetUniform("OvRainTint").set(r, g, b, p.alpha());
    }

    /** 雨と雪を描く直前(Mixin、{@code renderSnowAndRain} の頭): 風を読み、柱をずらす量を決める。 */
    public static void prepare(float partialTick, double x, double z) {
        Minecraft mc = Minecraft.getInstance();
        active = enabled(mc.level);
        snow = false;
        if (!active) {
            return;
        }
        AtmosphereLife.RainLookParams p = DesignData.atmosphere().rainLook();
        camX = x;
        camZ = z;
        double t = Atmosphere.time(partialTick);
        time = (float) (t % 6000.0);
        GustField.INSTANCE.velocity(x, z, t, WIND);
        double len = Math.sqrt(WIND[0] * WIND[0] + WIND[1] * WIND[1]);
        double max = Math.max(1.0e-3, GustField.INSTANCE.maxStrength());
        double frac = Mth.clamp(len / max, 0.0, 1.0);
        double dx = len > 1.0e-6 ? WIND[0] / len : 0.0;
        double dz = len > 1.0e-6 ? WIND[1] / len : 0.0;
        // 上ほど風上へ: x' = x - 風の向き × tan(傾き) × 高さ。
        double rainTan = Math.tan(Math.toRadians(p.windTilt() * frac));
        double snowTan = Math.tan(Math.toRadians(Math.min(p.snowTiltMax(), p.windTilt() * p.snowTiltScale() * frac)));
        rainSx = (float) (-dx * rainTan);
        rainSz = (float) (-dz * rainTan);
        snowSx = (float) (-dx * snowTan);
        snowSz = (float) (-dz * snowTan);
        snowSway = p.snowSway();
    }

    /** バニラが雨・雪の絵を選んだ(どちらの柱を描いているか)。 */
    public static void noteTexture(ResourceLocation texture) {
        snow = SNOW.equals(texture);
        // 雪は絵が白く、前の濃さのまま(半透明にするのは雨だけ)。前の柱の束はもう描き終えているので、次の束の不透明さだけ替える。
        ShaderInstance shader = ModShaders.rainWhite();
        if (active && shader != null) {
            shader.safeGetUniform("OvRainTint").set(tint[0], tint[1], tint[2], snow ? 1.0F : DesignData.atmosphere().rainLook().alpha());
        }
    }

    public static boolean active() {
        return active;
    }

    /** 柱の頂点(カメラからの位置)の x をずらした値。 */
    public static float shiftX(float x, float y, float z) {
        if (!active) {
            return x;
        }
        if (snow) {
            return x + snowSx * y + snowSway * (float) Math.sin(time * 0.03F + columnPhase(x, z));
        }
        return x + rainSx * y;
    }

    /** 柱の頂点の z をずらした値。 */
    public static float shiftZ(float x, float y, float z) {
        if (!active) {
            return z;
        }
        if (snow) {
            return z + snowSz * y + snowSway * (float) Math.cos(time * 0.023F + columnPhase(x, z) * 1.3F);
        }
        return z + rainSz * y;
    }

    /**
     * 雪のゆらぎの位相(世界の位置でなめらかに変わる。柱の 4 頂点はほぼ同じ値になり、隣の柱へ波のように伝わる)。
     * 柱ごとの乱数にすると、頂点が隣の列に入って板がねじれる。
     */
    private static float columnPhase(float x, float z) {
        return (float) ((x + camX) * 0.37 + (z + camZ) * 0.53);
    }

    /** 確かめ用の一行。 */
    static String describe() {
        return String.format("雨の傾き %.2f %.2f 雪 %.2f %.2f 有効 %s", rainSx, rainSz, snowSx, snowSz, active);
    }

    private RainLook() {
    }
}
