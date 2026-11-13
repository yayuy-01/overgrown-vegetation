package com.yayuy.overgrownvegetation.client.sky;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.VisualQuality;
import com.yayuy.overgrownvegetation.client.atmosphere.CloudHaze;
import com.yayuy.overgrownvegetation.client.atmosphere.SunLight;
import com.yayuy.overgrownvegetation.client.shader.ShaderPacks;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.SkyTable;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.util.Mth;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * <b>空の色</b>(2026-11-13 シェーダーの仕上げ A。ユーザー決定: 普通の世界は「鮮やか」、汚染地域の森の中は「太陽がにじむ + 天頂が少し抜ける」)。
 *
 * <p>理由: 空は画面でいちばん広いのにバニラのまま(太陽の方の扇形の朝焼けのほかは 1 色)で、明け方・夕方の風景が平たかった。設定: 普通の世界は澄んだ青、
 * 汚染地域は空まで黄緑に濁る(空気が汚染を語る、を空まで)。
 *
 * <h2>やり方</h2>
 * バニラの空の円盤を描いた直後・太陽と月と星の前に({@code mixin/client/LevelRendererSkyColorMixin})、画面いっぱいの四角を自前のシェーダー
 * ({@code shaders/core/sky_dome})で重ねる。画素ごとに視線の向きを出し、天頂の色 → 地平の色(太陽の側と反対側で別)へ寄せ、太陽のまわりに暈を足す。
 * 色は太陽の高さで決める({@link #palette}): 昼と夜はバニラの今の空の色・霧の色そのまま(バイオーム・雨・雷・高空の晴天が効いたまま)、その間の
 * 金の時間・日の入り・日没後(= 夜明け前)は design の色({@code sky.json skyColor})。切り替わる高さは地形に掛かる空の光の色({@code skyTintHeights})と同じなので、
 * 地面が朝焼けの色のときは空もその色になる。昼と夜はこの空を重ねない(バニラのまま。夜は 11-13 ユーザー「このままでよい」)。
 * バニラの扇形の朝焼けは、この空が出ている分だけ薄くする({@link #fadeFan})。
 *
 * <h2>地平の輪</h2>
 * 地形・Mob・粒はどれもフレームに 1 つの霧の色へ溶ける(遠くの霞 = 空気遠近も同じ色)。空のいちばん下の輪と地平線より下はその霧の色のままにして、
 * 向きごとの色の違いはその上に置く(継ぎ目を作らない)。
 *
 * <h2>森の中</h2>
 * 過繁茂の森の中は空の丸天井が霞の色で満ちている({@code MistView} の forestSky)。そこは霞の色を土台に、霞ごしの太陽のにじみ(夕方は琥珀色)・
 * 太陽の反対側の沈み・真上で霞が薄れて空の色が少し透ける分を足す。
 *
 * <p>描かない場合: 設定で切った・シェーダーパック使用中・水や溶岩の中・空の光の無い次元・雲の中の深い所(空ごと描かない)。高空の晴天(y 300〜350 より上)と
 * 雨の間は、その分だけ薄くしてバニラの空へ戻す。式の見本は {@code tools/sky-color/SkyMock.java}。色と強さは仮決め。
 */
public final class SkyColors {

    private SkyColors() {
    }

    private static boolean broken;
    /** このフレームに空を重ねた濃さ(0〜1。森の霞の分は除く)。扇形の朝焼けを薄める。 */
    private static float shown;
    /** バニラの扇形の朝焼けを薄める割合(0〜1。森の霞の中では薄めない)。 */
    private static float fanFade;
    private static String devLast = "";

    private static final float[] ZENITH = new float[3];
    private static final float[] AWAY = new float[3];
    private static final float[] TOWARD = new float[3];
    private static final float[] GLOW = new float[4];

    /**
     * 太陽の高さ {@code y} の色の組を {@link #ZENITH}・{@link #AWAY}・{@link #TOWARD}・{@link #GLOW}(a = 強さ)へ入れ、この空を重ねる濃さ(0〜1)を返す。
     * {@code sky} = バニラの今の空の色、{@code fog} = 今の霧の色。{@code h} = 切り替わる高さ 5 つ(白の終わり・金・日の入り・日没後・夜の始まり)。
     */
    static float palette(SkyTable t, float y, float[] h, float[] sky, float[] fog) {
        if (y >= h[0] || y <= h[4]) {
            System.arraycopy(sky, 0, ZENITH, 0, 3);
            System.arraycopy(fog, 0, AWAY, 0, 3);
            System.arraycopy(fog, 0, TOWARD, 0, 3);
            GLOW[3] = 0.0F;
            return 0.0F;
        }
        int i = 0;
        while (i < 3 && y < h[i + 1]) {
            i++;
        }
        float k = SunLight.smooth(Mth.clamp((h[i] - y) / (h[i] - h[i + 1]), 0.0F, 1.0F));
        // 区間 i は止まり i(0 = 昼・1 = 金・2 = 日の入り・3 = 日没後)から i + 1(4 = 夜)へ。昼と夜の止まりはバニラの色・暈なし。
        for (int c = 0; c < 3; c++) {
            ZENITH[c] = Mth.lerp(k, stop(t.skyZenith, i, c, sky), stop(t.skyZenith, i + 1, c, sky));
            AWAY[c] = Mth.lerp(k, stop(t.skyAway, i, c, fog), stop(t.skyAway, i + 1, c, fog));
            TOWARD[c] = Mth.lerp(k, stop(t.skyToward, i, c, fog), stop(t.skyToward, i + 1, c, fog));
            GLOW[c] = Mth.lerp(k, stop(t.skyGlow, i, c, WHITE), stop(t.skyGlow, i + 1, c, WHITE));
        }
        GLOW[3] = Mth.lerp(k, glow(t, i), glow(t, i + 1));
        // 昼と夜の端では色がバニラへ寄りきっているが、空の上から下への寄せ方の形が少し違うので、端の区間の中で濃さも 0 へ寄せる(境で跳ばない)。
        return i == 0 ? k : i == 3 ? 1.0F - k : 1.0F;
    }

    private static final float[] WHITE = {1.0F, 1.0F, 1.0F};

    private static float stop(float[][] design, int stop, int channel, float[] vanilla) {
        return stop == 0 || stop == 4 ? vanilla[channel] : design[stop - 1][channel];
    }

    private static float glow(SkyTable t, int stop) {
        return stop == 0 || stop == 4 ? 0.0F : t.skyGlowStrength[stop - 1];
    }

    /** バニラの空の円盤の直後に({@code LevelRendererSkyColorMixin})。 */
    public static void draw(Matrix4f frustumMatrix, Matrix4f projectionMatrix, float partialTick, Camera camera) {
        shown = 0.0F;
        fanFade = 0.0F;
        ShaderInstance shader = SkyShaders.skyDome();
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        SkyTable t = DesignData.sky();
        if (broken || shader == null || level == null || t.skyColorStrength <= 0.0F || !VisualQuality.canopyLight() || ShaderPacks.active()
                || camera.getFluidInCamera() != FogType.NONE || !level.dimensionType().hasSkyLight()
                || level.effects().skyType() != DimensionSpecialEffects.SkyType.NORMAL) {
            return;
        }
        try {
            Vec3 sun = SunLight.sunDirection(level, partialTick);
            Vec3 skyColor = level.getSkyColor(camera.getPosition(), partialTick);
            float[] sky = {(float) skyColor.x, (float) skyColor.y, (float) skyColor.z};
            float[] fog = RenderSystem.getShaderFogColor();
            float amount = palette(t, (float) sun.y, DesignData.atmosphere().skyTintHeights(), sky, fog);
            // 雨はその分だけバニラの灰色の空へ(地形の空の光の色と同じ割合)。高空の晴天・雲の中もバニラ(と雲の霧)の空へ。
            amount *= t.skyColorStrength * (1.0F - 0.75F * level.getRainLevel(partialTick)) * (1.0F - HighSky.clarity())
                    * (1.0F - Mth.clamp(CloudInterior.amount(partialTick) / OverworldCloudEffects.HIDE_SKY, 0.0F, 1.0F));
            float haze = CloudHaze.wall();
            float hazeGlow = t.skyHazeGlow;
            if (amount <= 0.001F && (haze <= 0.001F || hazeGlow <= 0.0F && t.skyHazeZenith <= 0.0F)) {
                return;
            }
            shown = Mth.clamp(amount, 0.0F, 1.0F);
            // 森の霞で満ちた空では、バニラの扇形の朝焼け(霞の上に描かれる橙の帯)を残す(11-13: 消すと森の夕焼けが平たくなった)。
            fanFade = shown * (1.0F - haze);
            // 森の霞ごしの太陽: 日が出ている分だけ(日没後も少し残る)。夕方ほど琥珀色へ、太陽の反対側が沈む。
            float y = (float) sun.y;
            float day = SunLight.smooth(Mth.clamp((y + 0.15F) / 0.5F, 0.0F, 1.0F));
            float up = Math.max(day, 0.25F * SunLight.smooth(Mth.clamp((y + 0.3F) / 0.2F, 0.0F, 1.0F)));
            float dusk = SunLight.smooth(1.0F - Math.min(1.0F, Math.abs(y) / 0.3F));
            shader.safeGetUniform("InvViewProj").set(new Matrix4f(projectionMatrix).mul(frustumMatrix).invert());
            shader.safeGetUniform("SunDir").set((float) sun.x, (float) sun.y, (float) sun.z, 0.0F);
            shader.safeGetUniform("Zenith").set(ZENITH[0], ZENITH[1], ZENITH[2], shown);
            shader.safeGetUniform("HorAway").set(AWAY[0], AWAY[1], AWAY[2], 0.0F);
            shader.safeGetUniform("HorToward").set(TOWARD[0], TOWARD[1], TOWARD[2], 0.0F);
            shader.safeGetUniform("Glow").set(GLOW[0], GLOW[1], GLOW[2], GLOW[3]);
            shader.safeGetUniform("Haze").set(haze, hazeGlow, t.skyHazeZenith, dusk);
            shader.safeGetUniform("HazeWarm").set(1.0F, Mth.lerp(dusk, 0.97F, 0.70F), Mth.lerp(dusk, 0.80F, 0.35F), up);
            devLast = String.format("空の色 濃さ %.2f 太陽の高さ %.2f 天頂 %.2f %.2f %.2f 太陽側 %.2f %.2f %.2f 暈 %.2f 霞 %.2f", shown, y,
                    ZENITH[0], ZENITH[1], ZENITH[2], TOWARD[0], TOWARD[1], TOWARD[2], GLOW[3], haze);

            ShaderInstance before = RenderSystem.getShader();
            RenderSystem.setShader(() -> shader);
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            RenderSystem.disableDepthTest();
            RenderSystem.disableCull();
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
            b.addVertex(-1.0F, -1.0F, 0.0F);
            b.addVertex(1.0F, -1.0F, 0.0F);
            b.addVertex(1.0F, 1.0F, 0.0F);
            b.addVertex(-1.0F, 1.0F, 0.0F);
            BufferUploader.drawWithShader(b.buildOrThrow());
            RenderSystem.disableBlend();
            RenderSystem.enableCull();
            RenderSystem.enableDepthTest();
            // バニラはこの後、空の色を掛けた同じシェーダーで地平線の下の暗い円盤を描くことがある(色は自分で入れ直す)。
            RenderSystem.setShaderColor(sky[0], sky[1], sky[2], 1.0F);
            if (before != null) {
                RenderSystem.setShader(() -> before);
            }
        } catch (RuntimeException e) {
            broken = true;
            shown = 0.0F;
            OvergrownVegetation.LOGGER.error("空の色で例外が出たので、このセッションではバニラの空に戻す", e);
        }
    }

    /**
     * バニラの扇形の朝焼け({@code getSunriseColor} の色。null = 出ない時刻)を、この空が出ている分だけ薄くして返す。
     * もとの配列はほかの読み手(霧の色・雲)と共用なので、書き換えずに写しを返す。
     */
    public static float[] fadeFan(float[] color) {
        if (color == null || fanFade <= 0.001F) {
            return color;
        }
        if (fanFade >= 0.999F) {
            return null;
        }
        return new float[]{color[0], color[1], color[2], color[3] * (1.0F - fanFade)};
    }

    /** 確かめ用。 */
    public static String describe() {
        return devLast;
    }
}
