package com.yayuy.overgrownvegetation.client.atmosphere;

import com.mojang.blaze3d.systems.RenderSystem;
import com.yayuy.overgrownvegetation.client.VisualQuality;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.material.FogType;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * <b>バニラの霧と空</b>(第 3 段「霧」、10-30 夜。元は parked の {@code CorruptionAtmosphere} の朝霧の分)。
 *
 * <ul>
 *     <li><b>朝霧の中</b>: 地形シェーダーの霧({@link MistFog})は地形と水と粒にしか掛からないので、カメラが層の中にいる間はバニラの霧の終わりを
 *     {@code insideFogEnd}(64 ブロック、10-16 了承)・始まりを × {@code insideNearFraction} へ寄せ、色を霧の遠い色へ寄せる。
 *     Mob・バニラの粒・空の縁もこれで霞む(霧に紛れる Mob)。層の上から見下ろしている間はバニラのまま(空気は澄んでいる)。
 *     過繁茂の中の一日中の霧(parked の 96 ブロック)は戻さない(10-30 夜 相談セッションの答え)。</li>
 *     <li><b>林冠の隙間の空を白く飛ばす</b>(A5、10-30 夜 ユーザー採用。参考画像「林冠の隙間の空は白く飛ぶ」): 森の中(頭上の覆い
 *     {@link ForestPresence#openForest})にいる間は、霧の色を明るい白({@code forestSkyColor})へ寄せ、空の霧をすぐ満ちるようにして
 *     空の丸天井まで霧の色にする。朝霧の中でも(10-30 夜 ユーザー「白い霧の中の幹の影絵」= 参考画像 4)。森の外では今まで通り。</li>
 *     <li><b>太陽の方の霧が光る</b>(B1): 霧の色を、見ている向きが太陽に近いほど暖かい白へ。</li>
 *     <li><b>胞子の霧</b>(10-31 ユーザー「森の中にいるときだけ、描画距離 6 チャンクと同じくらい」): 森の中({@link ForestPresence#openForest})では
 *     地形の霧を {@code forestFogStart}〜{@code forestFogEnd}(86.4〜96)へ、色を隙間の空の色へ。地形シェーダーの胞子の霧({@code ov_fog.glsl})と
 *     同じ距離・同じ色なので、森の中では 2 つが重なり、Mob・粒・雲もこれで隠れる。森の外から縁越しに見る分は地形シェーダーだけ。</li>
 *     <li><b>嵐</b>(提案 2、10-30 夜 ユーザー採用): 雷雨の間、過繁茂の地域ではバニラの霧を {@code stormFogEnd}(仮 40)まで近づけて遠くを早く隠す。</li>
 *     <li><b>水中の濁り</b>(11-01 ユーザー「水中が透き通りすぎ」): 海・川の 4 バイオームで潜っている間は、霧の終わりを {@code waterFogOvergrownOcean} など
 *     (仮 8〜16 ブロック)に固定し、色をそのバイオームの {@code water_fog_color} に固定する。バニラは潜っている時間(waterVision)で霧を 96 まで伸ばし、
 *     色も明るく持ち上げるので、両方を上書きして澄んでいかないようにする。</li>
 * </ul>
 */
final class MistView {

    private MistView() {
    }

    /** 森の中の胞子の霧の度合い(0〜1。頭上の覆い・地下は除く)。0 = 切った({@code forestFogEnd} 0)。 */
    static float sporeFog() {
        return DesignData.atmosphere().fog().forestFogEnd() > 0.0F && !MistFog.devForestOff ? ForestPresence.openForest() : 0.0F;
    }

    /** 地形を描いた後のバニラの霧が効いていたフレーム(Distant Horizons はふつうの霧を切ると、設定の後で値を 4e19 などの巨大な値に書き換える)。 */
    private static long terrainFogSeen = -10L;

    /** 地形を描いた後(AFTER_CUTOUT_BLOCKS、{@link Atmosphere} から)。 */
    static void noteTerrainFog() {
        if (RenderSystem.getShaderFogEnd() < 1.0E9F) {
            terrainFogSeen = MistFog.frame();
        }
    }

    /**
     * バニラの地形の霧が今効いているか(直近のフレームで地形を描いた後の霧の値が普通だったか)。効いていなければ(DH がふつうの霧を切っている)、森の中の霞は
     * 地形シェーダーの分を残す({@link MistFog#setUniforms})。そのとき Mob・粒には霞が掛からない。
     */
    static boolean vanillaTerrainFog() {
        return MistFog.frame() - terrainFogSeen <= 2L;
    }

    /** 霞の印: バニラの霧の始まりがこれより小さければ、fog.glsl はそこから霞の濃さを読む。 */
    private static final float HAZE_MARK = -1000.0F;
    private static final float HAZE_SCALE = 100000.0F;

    /** カメラの所の霞の濃さ(森 1 ブロックあたり。森の度合い {@code forest} を掛ける。0 = 霞なし)。 */
    private static float hazeK(float forest, float reach) {
        float density = MistFog.devForestDensity >= 0.0F ? MistFog.devForestDensity : DesignData.atmosphere().fog().forestFogDensity();
        return forest > 0.001F ? density * forest * reach : 0.0F;
    }

    private static float encodeHaze(float k) {
        return HAZE_MARK - k * HAZE_SCALE;
    }

    /**
     * 今のバニラの霧の始まり(霞の印を入れているときは、壁の始まり = 終わり − clamp(終わり / 10, 4, 64))。霧の始まりを読む自前の描画
     * (シルエット・光る藻・水の中の筋)は {@code RenderSystem.getShaderFogStart()} の代わりにこれを読む。
     */
    /** 森の中の霞を描いているか(バニラの霧の始まりに霞の印)。 */
    static boolean hazeActive() {
        return RenderSystem.getShaderFogStart() < HAZE_MARK;
    }

    static float fogStart() {
        float start = RenderSystem.getShaderFogStart();
        if (start >= HAZE_MARK) {
            return start;
        }
        float end = RenderSystem.getShaderFogEnd();
        return end - Mth.clamp(end / 10.0F, 4.0F, 64.0F);
    }

    /** 距離 {@code d} の所が今のバニラの霧で消える量(0〜1。fog.glsl と同じ式)。霧を受けない足し算の光が掛ける(1 − これ)。 */
    static float fogAmount(float d) {
        float start = RenderSystem.getShaderFogStart();
        float end = RenderSystem.getShaderFogEnd();
        if (start < HAZE_MARK) {
            float k = (HAZE_MARK - start) / HAZE_SCALE;
            float wallStart = end - Mth.clamp(end / 10.0F, 4.0F, 64.0F);
            float wall = d <= wallStart ? 0.0F : d >= end ? 1.0F : SunLight.smooth((d - wallStart) / (end - wallStart));
            return 1.0F - (1.0F - wall) * (float) Math.exp(-k * d);
        }
        return d <= start ? 0.0F : d >= end ? 1.0F : SunLight.smooth((d - start) / Math.max(end - start, 0.001F));
    }

    /** 森の中で空を白く飛ばす度合い(0〜1)。 */
    private static float whiteSky() {
        return ForestPresence.openForest() * DesignData.atmosphere().fog().forestSky();
    }

    /** 潜っている所のバイオーム(海・川の 4 つで、盲目・暗闇でないとき。ほかは null)。 */
    private static Holder<Biome> murkyWater(Camera cam) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel lvl = mc.level;
        if (cam.getFluidInCamera() != FogType.WATER || lvl == null || mc.player != null
                && (mc.player.hasEffect(MobEffects.BLINDNESS) || mc.player.hasEffect(MobEffects.DARKNESS))) {
            return null;
        }
        Holder<Biome> biome = lvl.getBiome(BlockPos.containing(cam.getPosition()));
        return waterFogEnd(biome) > 0.0F ? biome : null;
    }

    /** そのバイオームの水中の霧の終わり(0 = バニラのまま)。 */
    private static float waterFogEnd(Holder<Biome> biome) {
        var p = DesignData.atmosphere().fog();
        if (biome.is(ModBiomes.OVERGROWN_OCEAN)) {
            return p.waterFogOvergrownOcean();
        }
        if (biome.is(ModBiomes.OVERGROWTH_OCEAN)) {
            return p.waterFogOvergrowthOcean();
        }
        if (biome.is(ModBiomes.OVERGROWN_RIVER)) {
            return p.waterFogOvergrownRiver();
        }
        return biome.is(ModBiomes.OVERGROWTH_RIVER) ? p.waterFogOvergrowthRiver() : 0.0F;
    }

    static void computeFogColor(ViewportEvent.ComputeFogColor event) {
        if (!VisualQuality.fog()) {
            return;
        }
        Holder<Biome> water = murkyWater(event.getCamera());
        if (water != null) {
            // 水中の濁り: バニラの水の霧の色(water_fog_color)から、潜っている時間で明るくする分だけを外す。深い所の暗さ(空の色の暗さ)はバニラと同じ。
            int c = water.value().getWaterFogColor();
            ClientLevel lvl = Minecraft.getInstance().level;
            float dark = (float) (event.getCamera().getPosition().y - lvl.getMinBuildHeight()) * lvl.getLevelData().getClearColorScale();
            dark = dark < 1.0F ? Math.max(0.0F, dark) * Math.max(0.0F, dark) : 1.0F;
            event.setRed((c >> 16 & 0xFF) / 255.0F * dark);
            event.setGreen((c >> 8 & 0xFF) / 255.0F * dark);
            event.setBlue((c & 0xFF) / 255.0F * dark);
            return;
        }
        float inside = MistFog.cameraInside();
        float r = event.getRed();
        float g = event.getGreen();
        float b = event.getBlue();
        if (inside > 0.001F) {
            float[] c = MistFog.farColor();
            r = Mth.lerp(inside, r, c[0]);
            g = Mth.lerp(inside, g, c[1]);
            b = Mth.lerp(inside, b, c[2]);
        }
        float white = whiteSky();
        if (white > 0.001F) {
            float[] s = MistFog.forestSkyColor();
            r = Mth.lerp(white, r, s[0]);
            g = Mth.lerp(white, g, s[1]);
            b = Mth.lerp(white, b, s[2]);
        }
        // 胞子の霧の中は胞子の色へ(地形シェーダーの胞子の霧と同じ色。昼は黄緑寄りの白・朝夕は白緑・夜は暗く、11-01 ユーザー)。
        float spore = sporeFog();
        if (spore > 0.001F) {
            float[] s = MistFog.sporeColor();
            r = Mth.lerp(spore, r, s[0]);
            g = Mth.lerp(spore, g, s[1]);
            b = Mth.lerp(spore, b, s[2]);
        }
        float glow = MistFog.viewGlow(event.getCamera().getLookVector());
        if (glow > 0.001F) {
            float[] n = MistFog.nearColor();
            r = Mth.lerp(glow, r, Math.min(1.0F, n[0] * 1.15F + 0.06F));
            g = Mth.lerp(glow, g, Math.min(1.0F, n[1] * 1.08F + 0.05F));
            b = Mth.lerp(glow, b, Math.min(1.0F, n[2] * 0.95F + 0.02F));
        }
        event.setRed(r);
        event.setGreen(g);
        event.setBlue(b);
    }

    static void renderFog(ViewportEvent.RenderFog event) {
        Holder<Biome> water = VisualQuality.fog() ? murkyWater(event.getCamera()) : null;
        if (water != null) {
            // 水中の濁り: 霧の距離を潜っている時間によらず固定する(地形・空とも)。
            float end = waterFogEnd(water);
            event.setFarPlaneDistance(Math.min(event.getFarPlaneDistance(), end));
            event.setNearPlaneDistance(end * DesignData.atmosphere().fog().waterFogNearFraction());
            event.setCanceled(true);
            return;
        }
        if (event.getType() != FogType.NONE || !VisualQuality.fog()) {
            return;
        }
        if (event.getMode() == FogRenderer.FogMode.FOG_SKY) {
            // 空の丸天井を霧の色(森の中は明るい白)で満たす: 空の霧の終わりを手前へ。
            float white = whiteSky();
            if (white > 0.001F) {
                event.setFarPlaneDistance(Mth.lerp(white, event.getFarPlaneDistance(), 1.0F));
                event.setNearPlaneDistance(Mth.lerp(white, event.getNearPlaneDistance(), 0.0F));
                event.setCanceled(true);
            }
            return;
        }
        float inside = MistFog.cameraInside();
        float storm = MistFog.stormNear();
        float forest = sporeFog();
        if (inside <= 0.001F && storm <= 0.001F && forest <= 0.001F || event.getMode() != FogRenderer.FogMode.FOG_TERRAIN) {
            return;
        }
        var p = DesignData.atmosphere().fog();
        float vanillaFar = event.getFarPlaneDistance();
        float vanillaNear = event.getNearPlaneDistance();
        // 胞子の霧: 森の中は描画距離 6 チャンクと同じ 86.4〜96(胞子が濃いほど近く)。描画距離がそれより短ければバニラのまま。
        float reach = MistFog.forestReach();
        float forestFar = Mth.lerp(forest, vanillaFar, Math.min(vanillaFar, p.forestFogEnd() / reach));
        float forestNear = Mth.lerp(forest, vanillaNear, Math.min(vanillaNear, Math.min(p.forestFogStart(), p.forestFogEnd()) / reach));
        float far = Mth.lerp(inside, forestFar, Math.min(forestFar, p.insideFogEnd()));
        far = Mth.lerp(storm, far, Math.min(far, p.stormFogEnd()));
        float near = Mth.lerp(Math.max(inside, storm), forestNear, Math.min(forestNear, far * p.insideNearFraction()));
        // 森の中の霞(11-01 ユーザー「Mob だけ霧が掛からず浮く」): バニラの霧の始まりに負の印を入れて、上書きした fog.glsl に
        // 1 − (1 − 壁) × exp(−k × 距離) を描かせる。Mob・粒・雲・この MOD の粒も地形と同じ霞になる。
        float k = hazeK(forest, reach);
        if (k > 0.0F) {
            near = encodeHaze(k);
        }
        event.setFarPlaneDistance(far);
        event.setNearPlaneDistance(near);
        event.setCanceled(true);
    }
}
