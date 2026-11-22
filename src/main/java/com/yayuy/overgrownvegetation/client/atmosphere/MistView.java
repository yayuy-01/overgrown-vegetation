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
 *     色も明るく持ち上げるので、両方を上書きして澄んでいかないようにする。11-01 夕方(ユーザー「川は真っ暗すぎ」、参考画像 水中-2026-11-01)から
 *     森の池(バイオーム overgrown・overgrowth)も入れ、色は設計値の明るい黄緑(川・池)・青緑(海)、距離は 14〜24 ブロック(仮決め)。</li>
 * </ul>
 */
final class MistView {

    private MistView() {
    }

    /**
     * 森の中の胞子の霧の度合い(0〜1。頭上の覆い・地下は除く)。0 = 切った({@code forestFogEnd} 0)。11-01 夜から時間で寄せた
     * {@link ForestPresence#fogForest()}(時定数 transitionForestSeconds)を読む —— 濃い霧の森から薄い所へ出ても霧の距離と色が急に変わらない。
     */
    static float sporeFog() {
        return DesignData.atmosphere().fog().forestFogEnd() > 0.0F && !MistFog.devForestOff ? ForestPresence.fogForest() * MistFog.sporeFade() : 0.0F;
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

    /**
     * 霞の印: バニラの霧の始まりがこれより小さければ、fog.glsl(と Sodium の地形のシェーダー)はそこから霞を読む。印は整数 c = −始まり − 1000 で、
     * c mod 10000 = 胞子の霞の濃さ k × 100000、floor(c / 10000) = A + 51 × S = 空気遠近の強さ A / 50 と始まり S / 20(11-06 から。
     * 整数は float で 2^24 まで崩れない: 最大 9999 + 10000 × (50 + 51 × 20) ≈ 1070 万)。
     */
    private static final float HAZE_MARK = -1000.0F;
    private static final float HAZE_SCALE = 100000.0F;

    /** カメラの所の霞の濃さ(森 1 ブロックあたり。森の度合い {@code forest} を掛ける。0 = 霞なし)。 */
    private static float hazeK(float forest, float reach) {
        float density = MistFog.devForestDensity >= 0.0F ? MistFog.devForestDensity : DesignData.atmosphere().fog().forestFogDensity();
        return forest > 0.001F ? density * forest * reach : 0.0F;
    }

    /** 胞子の霞の濃さ {@code k} と空気遠近(強さ {@code a}・始まり {@code s})の印。どちらも 0 なら NaN(印を入れない)。 */
    private static float encodeMark(float k, float a, float s) {
        int kk = Mth.clamp(Math.round(k * HAZE_SCALE), 0, 9999);
        int aa = Mth.clamp(Math.round(a * 50.0F), 0, 50);
        int ss = aa == 0 ? 0 : Mth.clamp(Math.round(s * 20.0F), 0, 20);
        int code = kk + 10000 * (aa + 51 * ss);
        return code == 0 ? Float.NaN : HAZE_MARK - code;
    }

    /** 印の中の {k, a, s}(印でなければ null)。 */
    private static float[] decodeMark(float start) {
        if (start >= HAZE_MARK) {
            return null;
        }
        float c = HAZE_MARK - start;
        float h = (float) Math.floor((c + 0.5F) / 10000.0F);
        float s = (float) Math.floor((h + 0.5F) / 51.0F);
        return new float[]{(c - h * 10000.0F) / HAZE_SCALE, (h - s * 51.0F) / 50.0F, s / 20.0F};
    }

    /** 今の霧の印の 1 ブロックあたりの霞 k(印が無ければ 0。11-06 夜、巨大樹のシルエットが霞の中の霞み方に使う)。 */
    /** 確かめ用(11-09 追記 9): 直近の地形の霧の内訳(cavedev camstate)。 */
    static String devLast = "";
    /** 確かめ用: devLast を書くか(cavedev camstate を一度呼ぶと立つ。ふだんは書かない)。 */
    static boolean devRecord;

    static float markK() {
        float[] m = decodeMark(RenderSystem.getShaderFogStart());
        return m != null ? m[0] : 0.0F;
    }

    /** 森の中の霞を描いているか(バニラの霧の始まりの印に胞子の霞が入っている。空気遠近だけの印は数えない)。 */
    static boolean hazeActive() {
        float[] m = decodeMark(RenderSystem.getShaderFogStart());
        // 11-06: 印の k には胞子の空気(森の外の薄い霞)も入るので、森の霧が効いているときだけ数える(平原で巨大生物のシルエットを消さない)。
        return m != null && m[0] > 0.0F && sporeFog() > 0.001F;
    }

    /**
     * 今のバニラの霧の始まり(印を入れているときは、壁の始まり = 終わり − clamp(終わり / 10, 4, 64))。霧の始まりを読む自前の描画
     * (シルエット・光る藻・水の中の筋)は {@code RenderSystem.getShaderFogStart()} の代わりにこれを読む。
     */
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
        float[] m = decodeMark(start);
        if (m != null) {
            float wallStart = end - Mth.clamp(end / 10.0F, 4.0F, 64.0F);
            float wall = d <= wallStart ? 0.0F : d >= end ? 1.0F : SunLight.smooth((d - wallStart) / (end - wallStart));
            float q = Mth.clamp((d - m[2] * end) / Math.max(end * (1.0F - m[2]), 1.0F), 0.0F, 1.0F);
            return 1.0F - (1.0F - wall) * (float) Math.exp(-m[0] * d) * (1.0F - m[1] * q * q);
        }
        return d <= start ? 0.0F : d >= end ? 1.0F : SunLight.smooth((d - start) / Math.max(end - start, 0.001F));
    }

    /**
     * 空気遠近の強さ(11-06 バニラ地域の光 3、ユーザー決定「遠くの丘や森が空の色にわずかに溶ける」を世界全体に。値は仮決め)。霧の終わりでの霞の量 0〜1。
     * {@code aerialStrength} × 夜は {@code aerialNight} 倍 × 雨・雷は {@code aerialRain} 倍。地下・地下モード・胞子の霞(過繁茂の森の中)の分だけ弱める
     * (過繁茂の中は既存の霞に譲る)。空の光の無い次元(ネザー・エンド)・盲目・暗闇・Iris のパック使用中は 0。
     */
    /** 大雨で空気遠近の霞を何倍まで上乗せするか(11-12、仮決め)。 */
    static final float HEAVY_RAIN_AERIAL = 0.9F;

    static float aerial(float forest, float cave) {
        var p = DesignData.atmosphere().fog();
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (p.aerialStrength() <= 0.0F || level == null || !Atmosphere.enabled(level) || level.dimensionType().hasCeiling()
                || mc.player != null && (mc.player.hasEffect(MobEffects.BLINDNESS) || mc.player.hasEffect(MobEffects.DARKNESS))) {
            return 0.0F;
        }
        float night = SunLight.night(level, 1.0F);
        float rain = Math.max(level.getRainLevel(1.0F), level.getThunderLevel(1.0F));
        float a = p.aerialStrength() * Mth.lerp(night, 1.0F, p.aerialNight()) * Mth.lerp(rain, 1.0F, p.aerialRain());
        // 11-12 雨の種類(ユーザー選択「大雨ほど遠くが灰色に霞む」): 大雨の度合いで霞を上乗せ(小雨・普通は雨の強さの掛け算だけ)。仮決め。
        a *= 1.0F + HEAVY_RAIN_AERIAL * RainFeel.heavy();
        // 天気(11-16): 凪の日は遠くが澄む(胞子が森のそばに溜まり、遠くへ運ばれない)。
        a *= WeatherLook.aerialScale();
        return Mth.clamp(a * (1.0F - ForestPresence.underground()) * (1.0F - cave) * (1.0F - forest), 0.0F, 1.0F);
    }

    /**
     * 胞子の空気の効き(11-06、節 sporeAir。0〜1): カメラの所の度合い {@link SporeAir#level()} × 夜は {@code night} 倍 × (1 − 森の霧の度合い)
     * (森の中は森の霞に譲る = 森の中の見え方は変えない)× 地上だけ。空の光の無い次元・盲目・暗闇・Iris のパック使用中は 0。
     * カメラの所の霞({@link #renderFog} の k)・霧の色({@link #computeFogColor})・地形シェーダーの視線の先の霞({@link MistFog#upload})がこれを掛ける。
     */
    static float airAmount(float forest, float cave) {
        return SporeAir.level() * airGate(forest, cave);
    }

    /** {@link #airAmount} のうち度合いを除いた分(夜・森・地上。視線の先の霞の濃さにも掛ける —— カメラの所が澄んでいても遠くの濁りは見える)。 */
    static float airGate(float forest, float cave) {
        var p = DesignData.atmosphere().sporeAir();
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (!p.enabled() || level == null || !Atmosphere.enabled(level) || level.dimensionType().hasCeiling()
                || mc.player != null && (mc.player.hasEffect(MobEffects.BLINDNESS) || mc.player.hasEffect(MobEffects.DARKNESS))) {
            return 0.0F;
        }
        float night = SunLight.night(level, 1.0F);
        return Mth.clamp(Mth.lerp(night, 1.0F, p.night()) * (1.0F - forest) * (1.0F - ForestPresence.underground()) * (1.0F - cave), 0.0F, 1.0F);
    }

    /** 胞子の空気の霞の色: 節 sporeAir の color を、霧の色 {@code base} と同じ明るさにした色(夜は暗く、昼は明るく)。 */
    static float[] airColor(float r, float g, float b) {
        float[] c = DesignData.atmosphere().sporeAir().color();
        float lum = 0.299F * r + 0.587F * g + 0.114F * b;
        float cl = Math.max(0.001F, 0.299F * c[0] + 0.587F * c[1] + 0.114F * c[2]);
        float s = lum / cl;
        return new float[]{Math.min(1.0F, c[0] * s), Math.min(1.0F, c[1] * s), Math.min(1.0F, c[2] * s)};
    }

    /** 雲に掛ける分(11-13、{@link CloudHaze}): 空の丸天井を霧の色で満たしている割合(0〜1)。 */
    static float cloudWall() {
        return whiteSky();
    }

    /** 雲に掛ける分(11-13、{@link CloudHaze}): カメラの所の森の霞の濃さ(1 ブロックあたり。胞子の空気・空気遠近は入れない)。 */
    static float cloudK() {
        return hazeK(sporeFog(), MistFog.forestReach());
    }

    /** 森の中で空を白く飛ばす度合い(0〜1)。 */
    private static float whiteSky() {
        // 濃霧(天気。11-16)の日は、森の外でも空が霧の色で満ちる。
        return Math.max(ForestPresence.fogForest() * DesignData.atmosphere().fog().forestSky(), DenseFog.skyFill());
    }

    /** 潜っている所のバイオーム(海・川・森の池の 6 つで、盲目・暗闇でないとき。ほかは null)。 */
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

    /** 汚染されていない水の中か(盲目・暗闇でない。11-13 水まわり ⑤)。 */
    static boolean clearWater(Camera cam) {
        Minecraft mc = Minecraft.getInstance();
        return cam.getFluidInCamera() == FogType.WATER && mc.level != null && murkyWater(cam) == null
                && (mc.player == null || !mc.player.hasEffect(MobEffects.BLINDNESS) && !mc.player.hasEffect(MobEffects.DARKNESS));
    }

    /** バニラの水の見通しの上限(ブロック。11-13 ユーザー選択「バニラの水も少し霞む」= 澄んだまま少し底が霞む。バニラは潜るほど 96 まで澄む)。仮決め。 */
    static final float CLEAR_WATER_FOG_END = 48.0F;

    /** カメラが過繁茂の海・川・森の池の水の中にいるか(水中の光の筋を強める。{@link UnderwaterShafts})。 */
    static boolean inMurkyWater(Camera cam) {
        return VisualQuality.fog() && murkyWater(cam) != null;
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
        if (biome.is(ModBiomes.OVERGROWTH_RIVER)) {
            return p.waterFogOvergrowthRiver();
        }
        if (biome.is(ModBiomes.OVERGROWN)) {
            return p.waterFogOvergrown();
        }
        return biome.is(ModBiomes.OVERGROWTH) ? p.waterFogOvergrowth() : 0.0F;
    }

    /** そのバイオームの水中の霧の色(waterFogEnd が 0 でないバイオームだけ呼ぶ)。 */
    private static float[] waterFogColor(Holder<Biome> biome) {
        var p = DesignData.atmosphere().fog();
        if (biome.is(ModBiomes.OVERGROWN_OCEAN)) {
            return p.waterOvergrownOceanColor();
        }
        if (biome.is(ModBiomes.OVERGROWTH_OCEAN)) {
            return p.waterOvergrowthOceanColor();
        }
        if (biome.is(ModBiomes.OVERGROWN_RIVER)) {
            return p.waterOvergrownRiverColor();
        }
        if (biome.is(ModBiomes.OVERGROWTH_RIVER)) {
            return p.waterOvergrowthRiverColor();
        }
        return biome.is(ModBiomes.OVERGROWN) ? p.waterOvergrownColor() : p.waterOvergrowthColor();
    }

    /**
     * 水中の霧の終わりと色の今の値(11-01 夜 ユーザー「川 → 海 → 池で急に変わる」)。バイオームの値へ時定数 {@code transitionWaterSeconds}(5 秒、
     * バニラの水の色の混ざりと同じ長さ)で寄せる。潜った瞬間(前のフレームで濁った水の外)は寄せずにそのバイオームの値から始める。
     */
    private static float waterEnd;
    private static final float[] WATER_COLOR = new float[3];
    private static boolean waterSeen;
    private static long waterMillis;

    /** 水面を探す上限(ブロック)。これより深ければこの深さとみなす。 */
    private static final int DEPTH_SCAN = 96;

    /**
     * カメラの所の水面からの深さ(ブロック、水の外・水面より上は 0)。真上へ水の続く限りたどる(高さ地図は梢の下で当てにならない)。
     * 11-01 夜 見え方の直し 5。
     */
    static float waterDepth(ClientLevel level, net.minecraft.world.phys.Vec3 cam) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(Mth.floor(cam.x), Mth.floor(cam.y), Mth.floor(cam.z));
        if (!level.getFluidState(pos).is(net.minecraft.tags.FluidTags.WATER)) {
            return 0.0F;
        }
        int y0 = pos.getY();
        for (int i = 0; i < DEPTH_SCAN; i++) {
            pos.setY(y0 + i + 1);
            var fluid = level.getFluidState(pos);
            if (!fluid.is(net.minecraft.tags.FluidTags.WATER)) {
                pos.setY(y0 + i);
                float surface = y0 + i + level.getFluidState(pos).getHeight(level, pos);
                return Math.max(0.0F, surface - (float) cam.y);
            }
        }
        return DEPTH_SCAN;
    }

    /** 水面からの深さで水中の霧の色・光の筋に掛ける倍率(深さ 0 で 1、waterDepthDarkenBlocks で waterDepthDarkenMin)。 */
    static float depthFactor(ClientLevel level, net.minecraft.world.phys.Vec3 cam) {
        var p = DesignData.atmosphere().fog();
        if (p.waterDepthDarkenBlocks() <= 0.0F) {
            return 1.0F;
        }
        float t = Mth.clamp(waterDepth(level, cam) / p.waterDepthDarkenBlocks(), 0.0F, 1.0F);
        return Mth.lerp(t, 1.0F, p.waterDepthDarkenMin());
    }

    /** 濁った水の中の今の霧の値を、そのバイオームの値へ寄せる(フレームに 1 回、{@link #computeFogColor} から)。 */
    private static void followWater(Holder<Biome> biome) {
        float end = waterFogEnd(biome);
        float[] c = waterFogColor(biome);
        long now = net.minecraft.Util.getMillis();
        if (!waterSeen) {
            waterEnd = end;
            System.arraycopy(c, 0, WATER_COLOR, 0, 3);
        } else {
            float elapsed = Math.min((now - waterMillis) / 1000.0F, 0.5F);
            float seconds = DesignData.atmosphere().fog().transitionWaterSeconds();
            waterEnd = ForestPresence.approach(waterEnd, end, elapsed, seconds);
            for (int i = 0; i < 3; i++) {
                WATER_COLOR[i] = ForestPresence.approach(WATER_COLOR[i], c[i], elapsed, seconds);
            }
        }
        waterSeen = true;
        waterMillis = now;
    }

    static void computeFogColor(ViewportEvent.ComputeFogColor event) {
        // フレームの初め(地形・雲より前): 地下の黒い霧の印を下ろす(renderFog の FOG_TERRAIN で立て直す)。
        cloudPass = false;
        caveFogOn = false;
        forestDimOn = false;
        aerialOn = false;
        if (!VisualQuality.fog()) {
            waterSeen = false;
            return;
        }
        Holder<Biome> water = murkyWater(event.getCamera());
        if (water == null) {
            waterSeen = false;
        }
        if (water == null && clearWater(event.getCamera())) {
            // 11-13(水まわり ⑤ ユーザー選択「深いほど暗く」): バニラの水も水面からの深さで霧の色を暗く(汚染の水と同じ式)。
            float dark = depthFactor(Minecraft.getInstance().level, event.getCamera().getPosition());
            if (CaveLights.mix() > 0.001F) {
                dark *= caveWaterFactor(event.getCamera().getPosition()); // 地下の水は光の届く分だけ(下の地下モードと同じ)
            }
            event.setRed(event.getRed() * dark);
            event.setGreen(event.getGreen() * dark);
            event.setBlue(event.getBlue() * dark);
            return;
        }
        if (water != null) {
            // 水中の濁り: 設計値の色に固定する(バニラが潜っている時間で明るくする分を外す)。深い所の暗さ(空の色の暗さ)はバニラと同じ式。
            // バイオームが替わったときは時間で寄せる(followWater)。
            followWater(water);
            float[] c = WATER_COLOR;
            ClientLevel lvl = Minecraft.getInstance().level;
            float dark = (float) (event.getCamera().getPosition().y - lvl.getMinBuildHeight()) * lvl.getLevelData().getClearColorScale();
            dark = dark < 1.0F ? Math.max(0.0F, dark) * Math.max(0.0F, dark) : 1.0F;
            // 11-01 夜(見え方の直し 5、参考画像 水中-2026-11-01 の海 5・6 = 上が明るく下が暗い): 水面からの深さでも暗くする。
            dark *= depthFactor(lvl, event.getCamera().getPosition());
            // 11-04(洞窟の直し 2): 地下モードの水の中は、点光源の届く分だけの明るさにする(光の無い地底湖は暗く、光源 A の下は明るい)。
            dark *= caveWaterFactor(event.getCamera().getPosition());
            event.setRed(c[0] * dark);
            event.setGreen(c[1] * dark);
            event.setBlue(c[2] * dark);
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
        // 胞子の空気(11-06): 風下の薄い黄緑。空の地平線と画面の消し色も少し寄る(ユーザー了承)。森の中は森の霧の色のまま(airAmount が譲る)。
        float air = airAmount(spore, CaveLights.mix()) * DesignData.atmosphere().sporeAir().colorMix();
        if (air > 0.001F) {
            float[] s = airColor(r, g, b);
            r = Mth.lerp(air, r, s[0]);
            g = Mth.lerp(air, g, s[1]);
            b = Mth.lerp(air, b, s[2]);
        }
        float glow = MistFog.viewGlow(event.getCamera().getLookVector());
        if (glow > 0.001F) {
            float[] n = MistFog.nearColor();
            r = Mth.lerp(glow, r, Math.min(1.0F, n[0] * 1.15F + 0.06F));
            g = Mth.lerp(glow, g, Math.min(1.0F, n[1] * 1.08F + 0.05F));
            b = Mth.lerp(glow, b, Math.min(1.0F, n[2] * 0.95F + 0.02F));
        }
        // 地下モード(11-01 試作、{@link CaveLights}): 黒い霧(#050604 に近い黒、ユーザー指定)。
        // 11-04(洞窟の直し 1): ここで黒にすると、この色が空の丸天井・画面の消し色・雲にも使われ、洞窟の中から見た口の外の空が黒くなった。
        // 地上の色のまま返し、黒は地形を描く前(renderFog の FOG_TERRAIN)にシェーダーの霧の色だけへ入れる。
        // 水・溶岩の中は空が見えないので前と同じにここで寄せる(水は caveWaterFactor で光の届く分だけ明るく)。
        // 濃霧(天気。11-16): 霧の色を濃霧の色へ(前方散乱込み)。水・溶岩の中では掛けない。
        if (event.getCamera().getFluidInCamera() == FogType.NONE && DenseFog.amount() > 0.001F && Minecraft.getInstance().level != null) {
            float[] rgb = {r, g, b};
            DenseFog.tint(Minecraft.getInstance().level, (float) event.getPartialTick(), event.getCamera().getLookVector(), rgb);
            r = rgb[0];
            g = rgb[1];
            b = rgb[2];
        }
        float cave = CaveLights.mix();
        FogType fluid = event.getCamera().getFluidInCamera();
        if (cave > 0.001F && fluid == FogType.WATER) {
            float k = caveWaterFactor(event.getCamera().getPosition());
            r *= k;
            g *= k;
            b *= k;
        } else if (cave > 0.001F && fluid != FogType.NONE) {
            var c = DesignData.atmosphere().cave();
            r = Mth.lerp(cave, r, c.r());
            g = Mth.lerp(cave, g, c.g());
            b = Mth.lerp(cave, b, c.b());
        } else if (fluid == FogType.NONE && CaveLights.dhCaveColor() > 0.001F) {
            // 11-07: Distant Horizons は遠景の霧の色をこの色(RenderSystem の霧の色)から取る。洞窟の奥(口から離れた所)では洞窟の霧の色へ寄せる。
            // 前は地上の空の灰色のまま、遠くの空洞の壁(DH の遠景)が薄い灰色に浮いた。口の近くは寄せない(口の外の空は明るいまま。11-04)。
            float sd = CaveLights.dhCaveColor();
            r = Mth.lerp(sd, r, CaveLights.fogColor(0));
            g = Mth.lerp(sd, g, CaveLights.fogColor(1));
            b = Mth.lerp(sd, b, CaveLights.fogColor(2));
        }
        event.setRed(r);
        event.setGreen(g);
        event.setBlue(b);
    }

    static void renderFog(ViewportEvent.RenderFog event) {
        Holder<Biome> water = VisualQuality.fog() ? murkyWater(event.getCamera()) : null;
        if (water != null) {
            // 水中の濁り: 霧の距離を潜っている時間によらず固定する(地形・空とも)。
            float end = waterSeen ? waterEnd : waterFogEnd(water); // 色と一緒に寄せた値(followWater)
            event.setFarPlaneDistance(Math.min(event.getFarPlaneDistance(), end));
            event.setNearPlaneDistance(end * DesignData.atmosphere().fog().waterFogNearFraction());
            event.setCanceled(true);
            return;
        }
        if (VisualQuality.fog() && clearWater(event.getCamera()) && event.getFarPlaneDistance() > CLEAR_WATER_FOG_END) {
            // バニラの水(11-13 水まわり ⑤): 潜っている時間で 96 まで澄みきらないよう、見通しを止める。
            event.setFarPlaneDistance(CLEAR_WATER_FOG_END);
            event.setNearPlaneDistance(Math.min(event.getNearPlaneDistance(), CLEAR_WATER_FOG_END * DesignData.atmosphere().fog().waterFogNearFraction()));
            event.setCanceled(true);
            return;
        }
        if (event.getType() != FogType.NONE || !VisualQuality.fog()) {
            return;
        }
        if (event.getMode() == FogRenderer.FogMode.FOG_SKY) {
            // 空の丸天井を霧の色(森の中は明るい白)で満たす: 空の霧の終わりを手前へ。
            // 地下モード(11-01 試作)は前は黒で満たしていた(洞窟の壁の継ぎ目や描かない区画の向こうに空が見えないように)が、11-04(洞窟の直し 1)から
            // 満たさない: 洞窟の中から口・縦穴の外を見たとき、空がバニラの昼・夕方の空のままに見える。
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
        float cave = CaveLights.mix();
        // 11-08 Q1: Mob・粒の黒い霧は地形と同じ 0.3 秒でならした場所の値(CaveLights.fogMix)。cave(4 秒)は空気遠近・霞の門だけ
        float caveK = CaveLights.fogMix();
        if (event.getMode() != FogRenderer.FogMode.FOG_TERRAIN) {
            return;
        }
        // 空気遠近(11-06): 世界全体の遠くの霞。Distant Horizons がふつうの霧を切っている(終わりが巨大)間は掛けない。
        // 雲を描く間(cloudPass)は掛けない(雲の霧はバニラのまま。印の壁は雲の霧の始まりより遠いので、雲が遠くまで見えてしまった)。
        float aerial = !cloudPass && event.getFarPlaneDistance() < 1.0E9F ? aerial(forest, cave) : 0.0F;
        // 胞子の空気(11-06): カメラの所の分。森の中は森の霞に譲る(airAmount の中で × (1 − 森の霧の度合い))。
        float airK = !cloudPass && event.getFarPlaneDistance() < 1.0E9F ? DesignData.atmosphere().sporeAir().hazeDensity() * airAmount(forest, cave) : 0.0F;
        // G4 胞子の風(11-08): 前線がカメラを通る間、霞を一瞬濃く(SporeGale.fogK。地下では足さない)。
        if (!cloudPass && event.getFarPlaneDistance() < 1.0E9F) {
            airK += SporeGale.fogK() * (1.0F - cave);
        }
        // 濃霧(天気。11-16): 世界中の距離の霞。森の中でも足す(airAmount の外)。洞窟の中は足さない。雲を描く間も入れる(雲も霧に沈む)。
        if (event.getFarPlaneDistance() < 1.0E9F) {
            // 岩の屋根の下(洞窟の中)では足さない。地下の度合いだけだと、天井の穴のそばで霧が洞窟の中へ漏れた(灰色の膜)。
            airK += DenseFog.hazeK() * (1.0F - Math.max(cave, ForestPresence.underground()));
        }
        if (inside <= 0.001F && storm <= 0.001F && forest <= 0.001F && cave <= 0.001F && caveK <= 0.001F && aerial <= 0.001F && airK <= 0.0F) {
            if (!cloudPass) {
                float[] sc = com.mojang.blaze3d.systems.RenderSystem.getShaderFogColor();
                CaveLights.setSurfaceFog(event.getNearPlaneDistance(), event.getFarPlaneDistance(), sc[0], sc[1], sc[2]);
            }
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
        // 地下モード(11-01 試作、{@link CaveLights}): 黒い霧で見通し 12〜16(ユーザー指定)。霞の印が入っているときは壁の始まりが
        // 終わり − clamp(終わり / 10, 4, 64) なので、終わり 16 で 12〜16 になる。
        // 雲を描く間(cloudPass。Sodium の雲はここを FOG_TERRAIN でもう一度呼ぶ)は地下の黒い霧を入れない(11-04 洞窟の直し 1)。
        boolean caveFog = caveK > 0.001F && !cloudPass;
        float k = hazeK(forest, reach) + airK;
        // 印(胞子の霞 k と空気遠近)。どちらも無ければ NaN。
        float mark = encodeMark(k, aerial, p.aerialStart());
        // 雲を描く間に戻す霧の始まり(空気遠近を除いた印か、印の無い始まり。beginClouds)。
        float markNoAerial = encodeMark(k, 0.0F, 0.0F);
        float cloudStart = !Float.isNaN(markNoAerial) ? markNoAerial : near;
        if (aerial > 0.0F && !Float.isNaN(mark)) {
            surfaceEnd = far;
            surfaceStart = cloudStart;
            aerialOn = true;
        }
        // 地上の霧(黒い霧を入れる前)。印があれば印を始まりにする(下の near = mark と同じ)。
        float surfEnd = far;
        float surfStart = !Float.isNaN(mark) ? mark : near;
        if (caveFog) {
            // 地上のままの霧の距離を控える(雲を描く間だけ戻す、beginClouds)。
            surfaceEnd = far;
            surfaceStart = cloudStart;
            var c = DesignData.atmosphere().cave();
            far = Mth.lerp(caveK, far, Math.min(far, CaveLights.fogEnd()));
            near = Mth.lerp(caveK, near, Math.min(near, CaveLights.fogStart()));
        }
        if (!Float.isNaN(mark)) {
            near = mark;
        }
        if (devRecord && !cloudPass) {
            devLast = String.format(" 霧の内訳 中 %.3f 嵐 %.3f 森 %.3f 洞 %.3f/%.3f 遠 %.3f 胞子 %.4f k %.5f 終わり %.1f 始まり %.1f", inside, storm, forest, cave, caveK, aerial, airK, k, far, near);
        }
        event.setFarPlaneDistance(far);
        event.setNearPlaneDistance(near);
        event.setCanceled(true);
        // 11-05(光の点検 2): 林冠の下の胞子の霞は、林冠の陰の暗さに合わせて霧の色を落とす(霞が葉より明るく光って見えた)。
        // 地形・Mob・粒が同じ色になるよう、ここ(地形の霧の色)で落とす。係数はカメラの所の空の明るさ(MistFog.cameraSky)で
        // 林冠の陰の霧(ovFogShaded)と同じ式 shadeFloor〜1。空の丸天井・画面の消し色・雲は落とさない(林冠の隙間の空は白いまま)。
        // 洞窟の中は落とさない(洞窟から見た口の外は明るいまま、11-04 の 14)。地形はシェーダーで近くだけさらに頂点の空の光で落とす(ov_cave.glsl)。
        float dim = Mth.lerp(forest * (1.0F - cave), 1.0F, Mth.lerp(MistFog.cameraSky(), DesignData.atmosphere().fog().shadeFloor(), 1.0F));
        if (k > 0.0F && dim < 0.999F && !cloudPass && DesignData.atmosphere().fog().forestDim()) {
            if (!caveFog) {
                surfaceEnd = far;
                surfaceStart = cloudStart; // 11-06: 空気遠近を除いた印(前は near = 胞子の霞だけの印で同じ値)
            }
            // 色は computeFogColor の値から掛け直す(同じフレームに何度呼ばれても重ならない)。
            FogRenderer.levelFogColor();
            float[] fc = com.mojang.blaze3d.systems.RenderSystem.getShaderFogColor();
            com.mojang.blaze3d.systems.RenderSystem.setShaderFogColor(fc[0] * dim, fc[1] * dim, fc[2] * dim, fc[3]);
            forestDimOn = true;
        }
        // 11-07(カメラ切り替えの廃止): 黒い霧を入れる前の地上の霧を地形のシェーダーへ(雲を描く間は除く)。地形は場所ごとに黒い霧を掛ける。
        if (!cloudPass) {
            float[] sc = com.mojang.blaze3d.systems.RenderSystem.getShaderFogColor();
            CaveLights.setSurfaceFog(surfStart, surfEnd, sc[0], sc[1], sc[2]);
        }
        if (caveFog) {
            // 11-04(洞窟の直し 1): 黒い霧の色はここで地形の霧の色だけに入れる(空・画面の消し色・雲は地上の色のまま。computeFogColor)。
            // これより後に描く地形・水・Mob・粒は黒い霧、雲の前で地上の色へ戻す(beginClouds)。
            // Sodium は霧の色が不透明(透明度 1)なら霧の終わりより先の区画を描かない(fog occlusion)。地下モードの霧は 16 で終わるので、
            // 天井の光る玉(20 ブロック先)まで消えて穴から空が見えた。透明度を 0.999 にしてこの間引きを止める(霧の効き目は 0.1% しか変わらない)。
            var c = DesignData.atmosphere().cave();
            float[] fc = com.mojang.blaze3d.systems.RenderSystem.getShaderFogColor();
            com.mojang.blaze3d.systems.RenderSystem.setShaderFogColor(Mth.lerp(caveK, fc[0], CaveLights.fogColor(0)), Mth.lerp(caveK, fc[1], CaveLights.fogColor(1)),
                    Mth.lerp(caveK, fc[2], CaveLights.fogColor(2)), Math.min(fc[3], 0.999F));
            caveFogOn = true;
        }
    }

    /** このフレームの地形の霧に地下の黒い霧を入れたか(computeFogColor で下ろし、renderFog の FOG_TERRAIN で立てる)。 */
    private static boolean caveFogOn;
    /** このフレームの地形の霧の色を林冠の陰で落としたか(11-05 光の点検 2。computeFogColor で下ろし、renderFog の FOG_TERRAIN で立てる)。 */
    private static boolean forestDimOn;
    /** このフレームの地形の霧に空気遠近の印を入れたか(雲の間は外す、beginClouds)。 */
    private static boolean aerialOn;
    /** 雲を描いている間({@link #beginClouds}〜{@link #endClouds})。 */
    private static boolean cloudPass;
    /** 地下の黒い霧を入れる前の、地上のままの霧の始まりと終わり(雲を描く間に戻す)。 */
    private static float surfaceStart;
    private static float surfaceEnd;
    /** 雲の前の黒い霧の値(雲の後に戻す)。 */
    private static float savedStart;
    private static float savedEnd;
    private static final float[] SAVED_COLOR = new float[4];

    /**
     * 雲を描く直前(LevelRenderer#renderLevel の renderClouds の呼び出しの前、Mixin から)。11-04(洞窟の直し 1): 地下モードの間は霧が黒・12〜16 で、
     * 雲(口の向こうの空の雲)が黒く塗りつぶされた。雲の間だけ霧を地上のまま(距離と色)に戻す。バニラの雲は今の霧の距離を使い、色は
     * {@code FogRenderer.levelFogColor()}(= computeFogColor の地上の色)。Sodium の雲は FOG_TERRAIN で霧を組み直す(cloudPass の間は黒を入れない)。
     */
    static void beginClouds() {
        if (!caveFogOn && !forestDimOn && !aerialOn) {
            return;
        }
        cloudPass = true;
        savedStart = RenderSystem.getShaderFogStart();
        savedEnd = RenderSystem.getShaderFogEnd();
        System.arraycopy(RenderSystem.getShaderFogColor(), 0, SAVED_COLOR, 0, 4);
        RenderSystem.setShaderFogStart(surfaceStart);
        RenderSystem.setShaderFogEnd(surfaceEnd);
        FogRenderer.levelFogColor();
    }

    /** 雲を描いた直後: 黒い霧へ戻す(この後の雨・この MOD の描画・手は前と同じ霧)。 */
    static void endClouds() {
        if (!cloudPass) {
            return;
        }
        cloudPass = false;
        RenderSystem.setShaderFogStart(savedStart);
        RenderSystem.setShaderFogEnd(savedEnd);
        RenderSystem.setShaderFogColor(SAVED_COLOR[0], SAVED_COLOR[1], SAVED_COLOR[2], SAVED_COLOR[3]);
    }

    /** 光の無い地下の水の中の霧の明るさの下限(11-04 洞窟の直し 2。仮決め)。 */
    private static final float CAVE_WATER_MIN = 0.15F;

    /**
     * 地下モードの水の中の霧の色に掛ける倍率(1 = 地上のまま)。カメラの所に届く点光源の強さ(Mob の明るさと同じ {@link CaveLights#intensityAt})と
     * 床の持ち上げで、光の無い地底湖は {@value #CAVE_WATER_MIN} 倍まで暗く、光源のそばは元の明るさまで(11-04 洞窟の直し 2)。
     */
    static float caveWaterFactor(net.minecraft.world.phys.Vec3 cam) {
        float cave = CaveLights.mix();
        if (cave <= 0.001F) {
            return 1.0F;
        }
        var p = DesignData.atmosphere().cave();
        // intensityAt は地下の係数を掛け済みなので、係数で割り戻して「地下の中の明るさ」にしてから係数で寄せる
        float lit = Mth.clamp(CAVE_WATER_MIN + p.ambient() + CaveLights.intensityAt(cam.x, cam.y, cam.z) / cave * p.lightGain(), 0.0F, 1.0F);
        return Mth.lerp(cave, 1.0F, lit);
    }
}
