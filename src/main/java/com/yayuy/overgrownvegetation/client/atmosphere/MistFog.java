package com.yayuy.overgrownvegetation.client.atmosphere;

import com.yayuy.overgrownvegetation.client.VisualQuality;
import com.yayuy.overgrownvegetation.data.AtmosphereTable;
import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * <b>過繁茂の森の高さの霧</b>(2026-10-30 作り直しの第 3 段「霧」、方式 B の土台。一覧 11 二色の霧・12 朝霧・13 林冠の雲海)。
 * 板は使わない(10-30 ユーザー「昔の板は横から見ると板と分かり、上から見ても奥行きが無い」)。地形シェーダー({@code ov_fog.glsl})が
 * 視線が霧の層を通る量を閉じた式で積み、霧の色へ寄せる。層の高さは {@link MistMap}、式は {@link MistLayer}。品質 HIGH では近くを
 * {@link MistMarch} が受け持つ(地形シェーダーは {@code fogMarch.fadeFrom}〜{@code distance} から先だけを積む)。
 *
 * <ul>
 *     <li><b>いつ</b>: 朝 5〜8 時がいちばん濃く 9 時に晴れる(10-16 了承の 5〜9 時)。濃さと厚さは朝ごとに薄い〜濃い(ワールドの日数から)。
 *     雨上がり(雨が止んでから {@code mistAfterRainMinutes} 分)は濃い朝へ寄せ、昼でも層が残る({@code afterRainLinger})。雷雨(胞子の嵐)は層をいちばん濃く厚く・色を暗く。
 *     昼と夜は地面近くの薄い霞だけ(朝の {@code noonDensity} 倍)で、胞子濃度が高いほど少し濃い({@code sporeHaze})。</li>
 *     <li><b>どこに</b>: 過繁茂の地域の上だけ(地図の近さ)。森の度合い({@link ForestPresence})では出し入れしない —— 汚染の外の丘から見下ろしても谷の霧が見える。
 *     林冠の雲海は朝と雨上がり(梢の少し上まで層を持ち上げる)。カメラが岩の天井の下にいる間は出さない。</li>
 *     <li><b>色</b>: 近くは日の当たった暖かい色、遠くは青緑(二色の霧、10-17 了承)。夜は暖かい濃い緑。太陽の方は明るく。森の中では遠い色を
 *     {@code forestFogDark} まで暗く、林冠の陰の霧はカメラと頂点の空の明るさの暗い方で {@code shadeFloor} まで暗く(葉を光らせない)。色は全部
 *     {@code atmosphere.json} で動かせる(色味は後で参考画像から詰める、10-30 ユーザー)。</li>
 *     <li><b>中にいるとき</b>: バニラの霧を 64 ブロックへ({@link MistView}。Mob・粒・空もこれで霞む)。</li>
 *     <li><b>胞子の霧</b>(10-31 ユーザー): 森の列(地図の a)を通った視線の長さで、描画距離 6 チャンクと同じ 86.4〜96 ブロックで見えなくなる
 *     ({@code ov_fog.glsl} の {@code ovForestFog}。林冠の陰でも暗くしない)。森の中ではバニラの霧も同じ距離へ({@link MistView})。</li>
 * </ul>
 * 品質 LOW はくぼみのまだら(ノイズ)なし。数値は {@code atmosphere.json} の節 {@code fog}(64 と 5〜9 時のほかは仮決め)。
 */
public final class MistFog {

    /** 今の濃さ(1 ブロックあたり。0 = 出さない)・谷の層の厚さ・雲海の入り方・層の強さ(朝霧・雨上がり・嵐)・カメラが層の中にいる度合い。 */
    private static float density;
    private static float depth;
    private static float seaWeight;
    private static float mistLevel;
    private static float cameraInside;
    /** カメラの所の層のてっぺんからの高さ(ブロック、負 = 中。分からなければ +∞)。 */
    private static double cameraAbove = Double.POSITIVE_INFINITY;
    /** カメラの所の空の明るさ(0〜1、なめらかに寄せた値)。林冠の陰の霧を暗くする。 */
    private static float cameraSky = 1.0F;
    /** 雨が止んでからの tick(雨の間は 0。一度も降っていなければ大きい値)。 */
    private static long sinceRain = Long.MAX_VALUE / 2;
    /** 物の濡れの時計({@link #wetAfter})。雨が強い(0.9 より上)間だけ 0 に戻す —— 降り始めで湯気が立ったり照りが跳ねたりしないように。 */
    private static long sinceSoaked = Long.MAX_VALUE / 2;
    private static float wetShown;
    private static float stormShown;
    private static float sporeShown;
    private static float cameraProx;
    /** くぼみのまだらの模様のずれ(ブロック。風で毎 tick 積む。向き × 時刻で作らない)。 */
    private static double driftX;
    private static double driftZ;
    /** 近い色・遠い色(今の明るさを掛けた後)。 */
    private static final float[] NEAR = new float[3];
    private static final float[] FAR = {0.6F, 0.7F, 0.7F};
    private static final float[] AIR = new float[3];
    /** 森の中の林冠の隙間の空の色(A5。今の明るさを掛けた後)。 */
    private static final float[] SKY = {0.9F, 0.9F, 0.9F};
    /** 森の中の胞子の霧の色(11-01 ユーザー: 昼は黄緑寄りの白・朝夕は朝霧の白緑・夜は暗く。今の明るさを掛けた後)。 */
    private static final float[] SPORE = {0.9F, 0.95F, 0.8F};
    private static final double[] AT = new double[4];
    /** 開発用: 胞子の霧を止める(撮り比べ、{@link MistDev})。 */
    static boolean devForestOff;
    /** 開発用: 胞子の霧の壁の手前の霞の濃さ(負 = 設計値)。 */
    static float devForestDensity = -1.0F;
    /** カメラの所の胞子の霧の森の度合い(地図の a。開発用のログ)。 */
    private static float cameraForest;

    private MistFog() {
    }

    static void clear() {
        density = 0.0F;
        cameraInside = 0.0F;
        cameraAbove = Double.POSITIVE_INFINITY;
        sinceRain = Long.MAX_VALUE / 2;
        sinceSoaked = Long.MAX_VALUE / 2;
        MistMap.clear();
    }

    /** 雷雨の度合い(0〜1、なめらかに寄せた値)と、カメラの所の過繁茂の近さ(0〜1)。嵐でバニラの霧を近づける量({@link MistView})。 */
    static float stormNear() {
        return stormShown * cameraProx;
    }

    /** バニラの霧の色を太陽の方へ暖かい白に寄せる量(B1。見ている向き {@code look}、過繁茂の地域の近くだけ)。 */
    static float viewGlow(org.joml.Vector3f look) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || !active()) {
            return 0.0F;
        }
        Vec3 sun = SunLight.sunDirection(level, 1.0F);
        float toward = Math.max(0.0F, (float) (look.x() * sun.x + look.y() * sun.y + look.z() * sun.z));
        float up = Mth.clamp((float) (sun.y + 0.1) / 0.2F, 0.0F, 1.0F);
        float t2 = toward * toward;
        float t8 = t2 * t2 * t2 * t2;
        return Math.min(0.8F, 0.6F * t8 * DesignData.atmosphere().fog().sunGlow() * up * Math.max(cameraProx, cameraInside));
    }

    /**
     * 第 1 段の光の筋に掛ける倍率(提案 1、10-30 夜 ユーザー採用): 品質 HIGH で霧の細部が光の筋を描く間は、霧の層が濃い朝ほど第 1 段の筋を
     * {@code fogMarch.shaftYield} まで弱めて霧の筋に任せる。
     */
    static float shaftScale() {
        return MistMarch.willDraw() ? 1.0F - DesignData.atmosphere().fogMarch().shaftYield() * mistLevel * cameraProx : 1.0F;
    }

    /**
     * 胞子の霧の見える距離を縮める倍率(1 以上): 胞子濃度が高いほど昼の霞と同じ割合({@code sporeHaze})だけ濃い。
     * 地形シェーダーは森を通った長さにこれを掛け、バニラの霧({@link MistView})は 86.4〜96 をこれで割る。
     */
    static float forestReach() {
        return 1.0F + DesignData.atmosphere().fog().sporeHaze() * sporeShown;
    }

    /** 霧の雨上がりの度合い(0〜1。雨が止んでから fog.mistAfterRainMinutes 分かけて 0 へ。雨の間は 0)。 */
    static float afterRain() {
        return wetShown;
    }

    /**
     * 物の濡れ(0〜1。雨の間は 1 か雨の強さ、止んでから {@code minutes} 分かけてなめらかに 0 へ)。照り {@link WetSheen}・水滴 {@link RainDrops}・
     * 湯気 {@link SteamWisps} が使う(10-31 ユーザー「Minecraft の一日は短いから 3 分程度で良い」で霧の 20 分から切り離した)。
     */
    static float wetAfter(float minutes, float rain) {
        if (minutes <= 0.0F || sinceSoaked >= Long.MAX_VALUE / 2) {
            return rain;
        }
        return Math.max(rain, 1.0F - SunLight.smooth(Mth.clamp(sinceSoaked / (minutes * 1200.0F), 0.0F, 1.0F)));
    }

    /** 霧を出しているか(濃さ > 0 で地図がある)。 */
    static boolean active() {
        return density > 0.0F && MistMap.ready();
    }

    /** カメラが霧の層の中にいる度合い(0〜1)。バニラの霧を 64 へ寄せる量。 */
    static float cameraInside() {
        return cameraInside;
    }

    /** 遠い色(バニラの霧の色を寄せる先)。 */
    static float[] farColor() {
        return FAR;
    }

    /** 森の中の林冠の隙間の空の色(A5、{@link MistView} が読む)。 */
    static float[] forestSkyColor() {
        return SKY;
    }

    /** 胞子の霧の色({@link MistView} と地形シェーダーが同じ色を使う)。 */
    static float[] sporeColor() {
        return SPORE;
    }

    static float[] nearColor() {
        return NEAR;
    }

    static float density() {
        return density;
    }

    static float depth() {
        return depth;
    }

    static float seaWeight() {
        return seaWeight;
    }

    static float cameraSky() {
        return cameraSky;
    }

    /** 上から見ると濃く見せる倍率(層の強さが無い昼の霞は 1)。 */
    static float boost() {
        return Mth.lerp(mistLevel, 1.0F, DesignData.atmosphere().fog().aboveBoost());
    }

    /** まだらの模様の原点(ブロック。カメラの位置 − 風のずれ)。 */
    static double driftX() {
        return driftX;
    }

    static double driftZ() {
        return driftZ;
    }

    /** 設定で霧を切っている間か(切った最初の tick だけ片付ける。MistMap の片付けは器を作り直すので毎 tick はしない)。2026-11-01。 */
    private static boolean switchedOff;

    /** 毎 tick({@link Atmosphere} から)。 */
    static void tick(ClientLevel level, Vec3 eye, long ticks) {
        if (!VisualQuality.fog()) {
            if (!switchedOff) {
                clear();
                switchedOff = true;
            }
            return;
        }
        switchedOff = false;
        AtmosphereTable.FogParams p = DesignData.atmosphere().fog();
        AtmosphereTable t = DesignData.atmosphere();
        long dayTime = level.getDayTime();
        float rain = level.getRainLevel(1.0F);
        float thunder = level.dimension() == Level.OVERWORLD ? level.getThunderLevel(1.0F) : 0.0F;
        if (rain > 0.2F) {
            sinceRain = 0L;
        } else if (sinceRain < Long.MAX_VALUE / 2) {
            sinceRain++;
        }
        if (rain > 0.9F) {
            sinceSoaked = 0L;
        } else if (sinceSoaked < Long.MAX_VALUE / 2) {
            sinceSoaked++;
        }
        float wetTarget = p.mistAfterRainMinutes() <= 0.0F || sinceRain >= Long.MAX_VALUE / 2 ? 0.0F
                : (1.0F - SunLight.smooth(Mth.clamp(sinceRain / (p.mistAfterRainMinutes() * 1200.0F), 0.0F, 1.0F))) * (1.0F - rain);
        wetShown += (wetTarget - wetShown) * 0.05F;
        stormShown += (thunder - stormShown) * 0.05F;
        float spore = SunLight.smooth(Mth.clamp((AirHaze.sporePercent() - t.sporeHazeFrom()) / (t.sporeHazeFull() - t.sporeHazeFrom()), 0.0F, 1.0F));
        sporeShown += (spore - sporeShown) * 0.02F;

        float morning = (float) MistLayer.morning(dayTime) * (1.0F - rain);
        float amount = Math.max((float) MistLayer.morningAmount(MistLayer.morningIndex(dayTime), p.weakMorning()), wetShown);
        float morningMist = morning * amount;
        mistLevel = Math.max(morningMist, Math.max(wetShown * p.afterRainLinger(), stormShown));
        float factor = Math.max(p.noonDensity() * (1.0F + p.sporeHaze() * sporeShown), mistLevel)
                * (1.0F + (p.afterRainDensity() - 1.0F) * wetShown) * (1.0F + (p.stormDensity() - 1.0F) * stormShown);
        density = p.density() * factor * (1.0F - ForestPresence.underground());
        depth = Mth.lerp(mistLevel, p.noonDepth(), p.depthMin() + p.depthRange() * amount) + p.afterRainDepth() * wetShown
                + p.stormDepth() * stormShown;
        seaWeight = Math.max(morningMist, wetShown * p.afterRainLinger());

        MistMap.tick(level, eye, ticks, density > 0.0F, p);

        Wind wind = Wind.current();
        double gust = Math.min(1.0, wind.strength(eye.x, eye.z, Atmosphere.time(0.0F)));
        double speed = (0.3 + 1.2 * gust) / 20.0;
        driftX += wind.dirX() * speed;
        driftZ += wind.dirZ() * speed;
        double period = 64.0 * 24.0 * 10.0;
        driftX = wrap(driftX, period);
        driftZ = wrap(driftZ, period);

        float inside = 0.0F;
        cameraAbove = Double.POSITIVE_INFINITY;
        cameraProx = 0.0F;
        cameraForest = 0.0F;
        if (density > 0.0F && MistMap.at(eye.x, eye.z, AT)) {
            double top = AT[0] + depth;
            top = Mth.lerp(seaWeight, top, Math.max(top, AT[1] + p.seaAbove()));
            cameraAbove = eye.y - top;
            cameraProx = (float) AT[2];
            cameraForest = (float) AT[3];
            inside = (float) (MistLayer.inside(cameraAbove, p.insideSoft()) * AT[2]) * mistLevel * (1.0F - ForestPresence.underground());
        }
        cameraInside = inside > cameraInside ? Math.min(inside, cameraInside + 0.02F) : Math.max(inside, cameraInside - 0.02F);
        float sky = level.getBrightness(LightLayer.SKY, BlockPos.containing(eye)) / 15.0F;
        cameraSky += (sky - cameraSky) * 0.1F;

        colors(level, p, rain);
    }

    private static void colors(ClientLevel level, AtmosphereTable.FogParams p, float rain) {
        // 明るさ: 空の暗さ(夜 0.2 〜 昼 1)から。日の出の 6 時でも半分ほど明るい(朝霧の見せ場)。嵐は暗く。
        float light = Mth.clamp((level.getSkyDarken(1.0F) - 0.2F) / 0.8F, 0.0F, 1.0F) * (1.0F - 0.3F * rain)
                * Mth.lerp(stormShown, 1.0F, p.stormDarken());
        Vec3 sun = SunLight.sunDirection(level, 1.0F);
        SunLight.airColor(level, sun, 1.0F, AIR);
        float[] n = p.nearColor();
        float[] f = p.farColor();
        float[] haze = p.hazeColor();
        float[] night = p.nightColor();
        float[] sky = p.forestSkyColor();
        float[] sporeDay = p.forestFogDayColor();
        float[] sporeDawn = p.forestFogDawnColor();
        // 昼の色は太陽が高いほど(朝夕 = 太陽の高さ 0.1 から昼 = 0.4 で入れ替わる)。
        float noon = SunLight.smooth(Mth.clamp((float) (sun.y - 0.1) / 0.3F, 0.0F, 1.0F));
        // 森の中では遠い色(地形の霧の奥)を暗く: 幹が明るい背景の影絵にならないように。
        float forestDark = Mth.lerp(ForestPresence.openForest(), 1.0F, p.forestFogDark());
        for (int i = 0; i < 3; i++) {
            // 層がある(朝霧・雨上がり・嵐)ときは白〜灰緑、昼の霞だけのときは薄い黄緑がかった灰(A1、参考画像)。
            float near = Mth.lerp(mistLevel, haze[i], n[i]);
            float far = Mth.lerp(mistLevel, haze[i], f[i]);
            // 近い側は日の色(朝夕は金・橙)を 2 割 5 分だけ映す(白い朝霧を保つ)。
            float warm = near * Mth.lerp(0.25F, 1.0F, AIR[i]);
            NEAR[i] = Mth.lerp(light, night[i], warm);
            FAR[i] = Mth.lerp(light, night[i] * 0.9F, far) * forestDark;
            SKY[i] = Mth.lerp(light, night[i], sky[i]);
            SPORE[i] = Mth.lerp(light, night[i], Mth.lerp(noon, sporeDawn[i], sporeDay[i]));
        }
        // 嵐の霧は灰色へ半分寄せる(暗い空の下で緑の霧が光って見えないように。10-30 の「緑の霧」はユーザーが嫌った)。
        if (stormShown > 0.001F) {
            greyen(NEAR, 0.5F * stormShown);
            greyen(FAR, 0.5F * stormShown);
            greyen(SPORE, 0.5F * stormShown);
        }
    }

    private static void greyen(float[] c, float k) {
        float luma = 0.299F * c[0] + 0.587F * c[1] + 0.114F * c[2];
        for (int i = 0; i < 3; i++) {
            c[i] = Mth.lerp(k, c[i], luma);
        }
    }

    /** 描いたフレームの数(AFTER_SKY ごとに 1 つ)。 */
    private static long frames;

    static long frame() {
        return frames;
    }

    /** 地形シェーダーへ渡す(AFTER_SKY、{@link Atmosphere} から)。{@code on} が偽・霧が無いときは濃さ 0。 */
    static void upload(RenderLevelStageEvent event, boolean on) {
        on = on && VisualQuality.fog();
        frames++;
        ShaderInstance[] shaders = {GameRenderer.getRendertypeSolidShader(), GameRenderer.getRendertypeCutoutShader(),
                GameRenderer.getRendertypeCutoutMippedShader(), GameRenderer.getRendertypeTranslucentShader()};
        boolean active = on && density > 0.0F;
        if (active) {
            MistMap.upload();
            active = MistMap.ready();
        }
        if (!active) {
            for (ShaderInstance shader : shaders) {
                if (shader != null) {
                    shader.safeGetUniform("OvFogB").set(0.0F, 0.35F, 0.0F, 0.0F);
                    shader.safeGetUniform("OvFogJ").set(0.0F, 0.0F, 0.0F, 0.0F);
                }
            }
            return;
        }
        Vec3 cam = event.getCamera().getPosition();
        for (ShaderInstance shader : shaders) {
            if (shader != null) {
                setUniforms(shader, cam);
            }
        }
    }

    /** 霧の uniform({@code ov_fog.glsl} の OvFog*)と地図を {@code shader} へ。地形シェーダーと霧の細部({@link MistMarch})が同じ値を使う。 */
    static void setUniforms(ShaderInstance shader, Vec3 cam) {
        AtmosphereTable.FogParams p = DesignData.atmosphere().fog();
        ClientLevel level = Minecraft.getInstance().level;
        Vec3 sun = level != null ? SunLight.sunDirection(level, 1.0F) : new Vec3(0.0, 1.0, 0.0);
        float lobe = p.sunLobe() * Mth.clamp((float) (sun.y + 0.1) / 0.2F, 0.0F, 1.0F);
        float noise = VisualQuality.atmosphereTier() >= 2 ? 0.0F : p.noise();
        float nx = (float) wrap((cam.x - driftX) / p.noiseScale(), 64.0);
        float nz = (float) wrap((cam.z - driftZ) / p.noiseScale(), 64.0);
        // 品質 HIGH でレイマーチが近くを受け持つ間は、地形シェーダーは fadeFrom〜distance で受け渡してその先だけ。
        AtmosphereTable.MarchParams mp = DesignData.atmosphere().fogMarch();
        boolean march = MistMarch.willDraw();
        shader.setSampler("OvFogMap", MistMap.textureId());
        shader.safeGetUniform("OvFogA").set((float) (MistMap.originX() - cam.x), (float) (MistMap.originZ() - cam.z),
                (float) MistMap.TEXEL, (float) MistMap.SIZE);
        shader.safeGetUniform("OvFogB").set(density, p.falloff(), (float) (MistMap.base() - cam.y), depth);
        shader.safeGetUniform("OvFogC").set(NEAR[0], NEAR[1], NEAR[2], seaWeight);
        shader.safeGetUniform("OvFogD").set(FAR[0], FAR[1], FAR[2], p.colorDistance());
        shader.safeGetUniform("OvFogE").set((float) sun.x, (float) sun.y, (float) sun.z, lobe);
        shader.safeGetUniform("OvFogF").set(nx, nz, noise, p.noiseScale());
        // 上から見ると濃く見せるのは層があるときだけ(昼の地面近くの霞まで白く見えないように)。w = カメラの所の空の明るさ。
        shader.safeGetUniform("OvFogG").set(p.seaAbove(), boost(), p.aboveRamp(), cameraSky);
        // w = 品質 MEDIUM だけ近くのまだらを細かく(提案 4。HIGH はレイマーチ、LOW はまだら無し)。
        shader.safeGetUniform("OvFogH").set(p.shadeFloor(), march ? mp.fadeFrom() : 0.0F, march ? mp.distance() : 0.0F,
                VisualQuality.atmosphereTier() == 1 ? p.noise() : 0.0F);
        // 遠くの彩度を少し落とす(A6)・太陽の方の霧が光る(B1)。
        shader.safeGetUniform("OvFogI").set(p.farDesat(), p.desatFrom(), p.desatTo(), p.sunGlow());
        // 胞子の霧(10-31): 色は森の中のバニラの霧と同じ胞子の色にして、森の中で 2 色にならないように。地下では切る。
        float forestEnd = ForestPresence.underground() > 0.5F || devForestOff ? 0.0F : p.forestFogEnd();
        shader.safeGetUniform("OvFogJ").set(SPORE[0], SPORE[1], SPORE[2], forestEnd);
        // カメラが森の中にいる分はバニラの霧(fog.glsl の霞)が地形にも Mob にも同じに掛けるので、ここの分はその残り(1 − 森の度合い)だけ。
        shader.safeGetUniform("OvFogL").set(MistView.vanillaTerrainFog() ? 1.0F - MistView.sporeFog() : 1.0F, 0.0F, 0.0F, 0.0F);
        shader.safeGetUniform("OvFogK").set(Math.min(p.forestFogStart(), forestEnd), p.forestFogAbove(), forestReach(),
                devForestDensity >= 0.0F ? devForestDensity : p.forestFogDensity());
    }

    /** この mod の粒(環境の胞子・光る胞子)の霧の地図の unit。粒は 5〜7 番を使う。 */
    private static final int PARTICLE_UNIT = 4;

    /**
     * この mod の粒のシェーダーへ霧の uniform を渡す({@code shader.apply()} の前)。粒は奥行きを書かないので、レイマーチの受け渡しは使わず
     * 視線の全部を土台の式で積む。霧が無ければ濃さ 0。
     */
    static void setParticleUniforms(ShaderInstance shader, Vec3 cam) {
        if (!active()) {
            shader.safeGetUniform("OvFogB").set(0.0F, 0.35F, 0.0F, 0.0F);
            return;
        }
        setUniforms(shader, cam);
        shader.safeGetUniform("OvFogH").set(DesignData.atmosphere().fog().shadeFloor(), 0.0F, 0.0F, VisualQuality.atmosphereTier() == 1 ? DesignData.atmosphere().fog().noise() : 0.0F);
    }

    /** 粒のシェーダーへ霧の地図を結ぶ({@code shader.apply()} の後。json に書かない自前の sampler)。描き終えたら {@link #unbindParticleMap}。 */
    static void bindParticleMap(ShaderInstance shader) {
        int loc = org.lwjgl.opengl.GL20.glGetUniformLocation(shader.getId(), "OvFogMap");
        if (loc < 0) {
            return;
        }
        org.lwjgl.opengl.GL20.glUniform1i(loc, PARTICLE_UNIT);
        int previous = com.mojang.blaze3d.platform.GlStateManager._getActiveTexture();
        com.mojang.blaze3d.systems.RenderSystem.activeTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0 + PARTICLE_UNIT);
        org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, active() ? MistMap.textureId() : 0);
        com.mojang.blaze3d.systems.RenderSystem.activeTexture(previous);
    }

    static void unbindParticleMap() {
        int previous = com.mojang.blaze3d.platform.GlStateManager._getActiveTexture();
        com.mojang.blaze3d.systems.RenderSystem.activeTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0 + PARTICLE_UNIT);
        org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0);
        com.mojang.blaze3d.systems.RenderSystem.activeTexture(previous);
    }

    private static double wrap(double v, double period) {
        return v - Math.floor(v / period) * period;
    }

    /** 開発用のログ。 */
    static String describe() {
        return String.format("霧 濃さ %.4f 厚さ %.1f 層 %.2f 雲海 %.2f 雨上がり %.2f 嵐 %.2f 胞子 %.2f 中 %.2f 層の上 %.1f 空 %.2f 森(地図 %.2f 頭上 %.2f%s) %s", density, depth,
                mistLevel, seaWeight, wetShown, stormShown, sporeShown, cameraInside, cameraAbove, cameraSky, cameraForest, MistView.sporeFog(),
                devForestOff ? " 止めた" : "", MistMap.describe());
    }
}
