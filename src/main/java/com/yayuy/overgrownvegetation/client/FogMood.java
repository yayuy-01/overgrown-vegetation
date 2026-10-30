package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.client.light.MoodState;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import com.yayuy.overgrownvegetation.spore.MorningMist;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.phys.Vec3;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * <b>霧と空の色の詰め</b>(2026-10-23 試作。色味(ライトマップ + LUT)の仕上げの続き。Vault「汚染地域の光と霧」)。
 * 新しい描画パスは足さず、霧の距離と色の式だけを変える。4 つを開発用の切り替え(devbridge の {@code fogmood})で個別にオン/オフする。既定は全部オン(2026-10-23 ユーザー採用)。設定の「色味」に連動し、色味がオフなら 4 つとも掛けない(霧は前のまま)。
 *
 * <ol>
 *     <li><b>密度の曲線</b>({@link #curveOn}): バニラの霧は始端 → 終端の smoothstep。始端を汚染度ごとに変え、「近くは透明・ある距離から急に濃く」にする
 *     (繁茂 0.62・過繁茂 0.5・極相 0.22 × 終端。今は 0.3)。終端(96 / 48)は変えない(霧の奥の描画の打ち切りがそのまま効く)。外はバニラのまま。</li>
 *     <li><b>時刻と汚染度の霧と空の色</b>({@link #colorOn}): {@code Biome#getFogColor/getSkyColor} の戻り値を汚染度・時刻の色へ寄せる
 *     (BiomeClearSkyMixin。バニラはまわりのバイオームの色を混ぜてから明るさ・雨・朝焼けを掛けるので、境はなめらかなまま)。夜の霧の下限も青を抜いた緑へ。</li>
 *     <li><b>朝夕の太陽の方の暖色</b>({@link #sunOn}): 太陽が低い間だけ、太陽の方を向いた霧を橙へ寄せる(昼の淡い金の寄せ {@code canopyFogGlow} の延長。極相は半分)。
 *     霧の色だけ(地面の霞 FarGround は空の色でかすむので効かない)。</li>
 *     <li><b>雨後・朝の湿り</b>({@link #wetOn}): 雨が上がった後({@link AfterRain#wetness} × 雨でない)と朝霧の時間({@link MorningMist#timeCurve})は、
 *     霧を少し近く・寒色へ。</li>
 * </ol>
 * 色・数値はすべて仮決め。
 */
public final class FogMood {

    public static volatile boolean curveOn = true;
    public static volatile boolean colorOn = true;
    public static volatile boolean sunOn = true;
    public static volatile boolean wetOn = true;

    // ---- 1. 密度の曲線: 始端 = 終端 × この割合 ----
    static final float NEAR_OVERGROWTH = 0.62F;
    static final float NEAR_OVERGROWN = 0.50F;
    static final float NEAR_CLIMAX = 0.22F;

    // ---- 2. 色(0xRRGGBB。バニラの明るさを掛ける前の色)。[昼, 朝夕, 夜] ----
    static final int[] OVERGROWN_FOG = {0x9FB083, 0xB08A5E, 0x334D29};
    static final int[] OVERGROWN_SKY = {0x8FA996, 0xA88A70, 0x33472E};
    static final int[] CLIMAX_FOG = {0x4D5233, 0x5C472B, 0x1A2414};
    static final int[] CLIMAX_SKY = {0x57613F, 0x66523A, 0x1A2414};
    /** 繁茂は過繁茂の色へこの割合だけ寄せる(外 → 過繁茂のつなぎ)。湿地・菌湿地はその色をこの割合だけ残す。 */
    static final float OVERGROWTH_MIX = 0.5F;
    static final float SWAMP_MIX = 0.5F;
    /** 夜の霧の下限(青を抜いた濃い緑)。過繁茂・極相。今は 0.09 / 0.13 / 0.14。 */
    static final float[] FLOOR_FOREST = {0.075F, 0.12F, 0.065F};
    static final float[] FLOOR_CLIMAX = {0.05F, 0.075F, 0.045F};

    // ---- 3. 朝夕の太陽の方 ----
    static final float SUN_WARM = 0.55F;

    // ---- 4. 湿り ----
    static final float WET_NEAR = 0.45F;
    static final float WET_FAR = 0.12F;
    static final float WET_COOL = 0.3F;

    /** 毎フレーム決める時刻の重み(昼・朝夕・夜。合わせて 1)。 */
    private static float wDay = 1.0F;
    private static float wDusk;
    private static float wNight;
    /** 今の時刻の色(段ごと)。毎フレーム決め、Mixin が読む。 */
    private static int overgrownFog = OVERGROWN_FOG[0];
    private static int overgrownSky = OVERGROWN_SKY[0];
    private static int climaxFog = CLIMAX_FOG[0];
    private static int climaxSky = CLIMAX_SKY[0];
    private static boolean overworld;
    /** 今フレーム効かせるか(切り替え × 設定の色味)。 */
    private static boolean mood = true;
    private static boolean curveE = true;
    private static boolean colorE = true;
    private static boolean sunE = true;
    private static float wet;

    /** バイオーム → 寄せる段(0 寄せない・1 繁茂・2 過繁茂・3 極相)と寄せる割合。ワールドが替わったら作り直す。 */
    private static final Map<Biome, Integer> STAGE = new IdentityHashMap<>();
    private static final Map<Biome, Float> AMOUNT = new IdentityHashMap<>();
    private static ClientLevel stageLevel;

    private static final float[] OUT = new float[3];

    /** 毎フレーム 1 回({@link CorruptionAtmosphere#onComputeFogColor} の頭)。 */
    static void frame(ClientLevel level, float partialTick) {
        if (level != stageLevel) {
            STAGE.clear();
            AMOUNT.clear();
            stageLevel = level;
        }
        if (level == null) {
            return;
        }
        mood = MoodState.colorOwned();
        curveE = curveOn && mood;
        colorE = colorOn && mood;
        sunE = sunOn && mood;
        overworld = level.dimension() == Level.OVERWORLD;
        Vec3 sun = CanopyLight.sunDirection(level, partialTick);
        float y = (float) sun.y;
        wDay = CanopyLight.smooth(Mth.clamp((y - 0.05F) / 0.3F, 0.0F, 1.0F));
        wNight = CanopyLight.smooth(Mth.clamp((-y - 0.02F) / 0.2F, 0.0F, 1.0F));
        wDusk = Math.max(0.0F, 1.0F - wDay - wNight);
        overgrownFog = mix3(OVERGROWN_FOG);
        overgrownSky = mix3(OVERGROWN_SKY);
        climaxFog = mix3(CLIMAX_FOG);
        climaxSky = mix3(CLIMAX_SKY);

        float rain = level.getRainLevel(partialTick);
        float afterRain = AfterRain.wetness() * (1.0F - rain);
        float morning = (float) MorningMist.timeCurve(level.getDayTime());
        wet = wetOn && mood ? Math.max(afterRain, morning) * CorruptionAtmosphere.forestFactor() : 0.0F;
    }

    private static int mix3(int[] c) {
        float r = wDay * (c[0] >> 16 & 255) + wDusk * (c[1] >> 16 & 255) + wNight * (c[2] >> 16 & 255);
        float g = wDay * (c[0] >> 8 & 255) + wDusk * (c[1] >> 8 & 255) + wNight * (c[2] >> 8 & 255);
        float b = wDay * (c[0] & 255) + wDusk * (c[1] & 255) + wNight * (c[2] & 255);
        return Math.round(r) << 16 | Math.round(g) << 8 | Math.round(b);
    }

    // ------------------------------------------------------------------ 2. バイオームの色(BiomeClearSkyMixin、描画のスレッドだけ)

    public static int biomeFog(Biome biome, int color) {
        if (!colorE || !overworld) {
            return color;
        }
        int stage = stageOf(biome);
        return stage == 0 ? color : HighSky.lerpColor(AMOUNT.get(biome), color, stage == 3 ? climaxFog : overgrownFog);
    }

    public static int biomeSky(Biome biome, int color) {
        if (!colorE || !overworld) {
            return color;
        }
        int stage = stageOf(biome);
        return stage == 0 ? color : HighSky.lerpColor(AMOUNT.get(biome), color, stage == 3 ? climaxSky : overgrownSky);
    }

    private static int stageOf(Biome biome) {
        Integer s = STAGE.get(biome);
        if (s != null) {
            return s;
        }
        int stage = 0;
        float amount = 0.0F;
        ClientLevel level = Minecraft.getInstance().level;
        ResourceKey<Biome> key = level == null ? null : level.registryAccess().registryOrThrow(Registries.BIOME).getResourceKey(biome).orElse(null);
        if (key != null && ModBiomes.isOvergrownKey(key)) {
            String path = key.location().getPath();
            if (ModBiomes.isClimaxKey(key)) {
                stage = 3;
                amount = 1.0F;
            } else if (ModBiomes.isFullyOvergrownKey(key)) {
                stage = 2;
                amount = 1.0F;
            } else {
                stage = 1;
                amount = OVERGROWTH_MIX;
            }
            if (path.contains("swamp")) {
                amount *= SWAMP_MIX;
            }
        }
        STAGE.put(biome, stage);
        AMOUNT.put(biome, amount);
        return stage;
    }

    /** 夜の霧の下限(色の切り替えがオフなら今の値 {@code r g b})。結果は {@link #OUT}。 */
    static float[] floor(float r, float g, float b, float climax) {
        if (colorE) {
            OUT[0] = Mth.lerp(climax, FLOOR_FOREST[0], FLOOR_CLIMAX[0]);
            OUT[1] = Mth.lerp(climax, FLOOR_FOREST[1], FLOOR_CLIMAX[1]);
            OUT[2] = Mth.lerp(climax, FLOOR_FOREST[2], FLOOR_CLIMAX[2]);
        } else {
            OUT[0] = r;
            OUT[1] = g;
            OUT[2] = b;
        }
        return OUT;
    }

    // ------------------------------------------------------------------ 1・4. 距離

    /** 始端の割合(終端に掛ける)。曲線がオフなら {@code vanilla}。 */
    static float nearFraction(float vanilla) {
        if (!curveE) {
            return vanilla;
        }
        float a = MoodState.overgrowth();
        float b = MoodState.overgrown();
        float c = MoodState.climax();
        float sum = a + b + c;
        if (sum < 1.0E-3F) {
            return NEAR_OVERGROWN;
        }
        return (a * NEAR_OVERGROWTH + b * NEAR_OVERGROWN + c * NEAR_CLIMAX) / sum;
    }

    static float wetNear(float near) {
        return near * (1.0F - WET_NEAR * wet);
    }

    static float wetFar(float far) {
        return far * (1.0F - WET_FAR * wet);
    }

    // ------------------------------------------------------------------ 3・4. 霧の色(ComputeFogColor の中、太陽の寄せの後・朝霧の前)

    /** 結果は {@link #OUT}。{@code ff} は森の度合い、{@code mist} は朝霧の色へ寄せる割合。 */
    static float[] tint(ClientLevel level, org.joml.Vector3f look, float partialTick, float r, float g, float b, float ff, float climax, float mist) {
        if (sunE && wDusk > 0.001F && level.dimensionType().hasSkyLight()) {
            Vec3 sun = CanopyLight.sunDirection(level, partialTick);
            // 地平線の近くの太陽: 水平の向きで見る(真上・真下を向いても効かない)。
            double hx = sun.x;
            double hz = sun.z;
            double hl = Math.sqrt(hx * hx + hz * hz);
            float lx = look.x();
            float lz = look.z();
            float ll = (float) Math.sqrt(lx * lx + lz * lz);
            if (hl > 1.0E-3 && ll > 1.0E-3F) {
                float toward = Math.max(0.0F, (float) ((lx * hx + lz * hz) / (ll * hl)));
                float lobe = toward * toward * (1.0F - 0.7F * Math.abs(look.y()));
                float rain = level.getRainLevel(partialTick);
                float k = SUN_WARM * wDusk * lobe * ff * (1.0F - 0.5F * climax) * (1.0F - 0.8F * rain) * CloudShadows.sunVisibility();
                if (k > 0.001F) {
                    float luma = Math.max(0.2F, 0.299F * r + 0.587F * g + 0.114F * b) * 1.5F;
                    r = Mth.lerp(k, r, Math.min(1.0F, luma * 1.35F));
                    g = Mth.lerp(k, g, Math.min(1.0F, luma * 0.82F));
                    b = Mth.lerp(k, b, Math.min(1.0F, luma * 0.45F));
                }
            }
        }
        if (wet > 0.001F) {
            // 朝霧の色へ寄せる分には足さない(朝霧が自分の色を持つ)。
            float k = WET_COOL * wet * (1.0F - mist);
            float luma = 0.299F * r + 0.587F * g + 0.114F * b;
            r = Mth.lerp(k, r, Math.min(1.0F, luma * 0.88F));
            g = Mth.lerp(k, g, Math.min(1.0F, luma * 1.0F));
            b = Mth.lerp(k, b, Math.min(1.0F, luma * 1.14F));
        }
        OUT[0] = r;
        OUT[1] = g;
        OUT[2] = b;
        return OUT;
    }

    // ------------------------------------------------------------------ 開発用

    public static String describe() {
        return String.format("曲線 %s・色 %s・太陽 %s・湿り %s(湿り %.2f・昼 %.2f 朝夕 %.2f 夜 %.2f・始端の割合 %.2f)",
                curveOn, colorOn, sunOn, wetOn, wet, wDay, wDusk, wNight, nearFraction(0.3F));
    }

    /** devbridge の {@code fogmood [all|curve|color|sun|wet on|off]}。 */
    public static String devCommand(String arg) {
        String[] a = arg.trim().split("\\s+");
        if (a.length >= 2) {
            boolean on = a[1].equalsIgnoreCase("on");
            switch (a[0]) {
                case "all" -> curveOn = colorOn = sunOn = wetOn = on;
                case "curve" -> curveOn = on;
                case "color" -> colorOn = on;
                case "sun" -> sunOn = on;
                case "wet" -> wetOn = on;
                // fogmood col <ogfog|ogsky|clfog|clsky> <0 昼|1 朝夕|2 夜> <RRGGBB>(撮り比べ用に起動中に色を替える)
                case "col" -> {
                    int[] t = switch (a[1]) {
                        case "ogfog" -> OVERGROWN_FOG;
                        case "ogsky" -> OVERGROWN_SKY;
                        case "clfog" -> CLIMAX_FOG;
                        default -> CLIMAX_SKY;
                    };
                    t[Integer.parseInt(a[2])] = Integer.parseInt(a[3], 16);
                }
                default -> {
                    return "FOGMOOD 知らない項目 " + a[0];
                }
            }
        }
        return "FOGMOOD " + describe();
    }

    private FogMood() {
    }
}
