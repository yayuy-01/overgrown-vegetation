package com.yayuy.overgrownvegetation.client.atmosphere;

import com.yayuy.overgrownvegetation.client.VisualQuality;
import com.mojang.blaze3d.platform.NativeImage;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.shader.SodiumTerrain;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;

/**
 * <b>林冠の影を全部の面に</b>と<b>森の中の青緑</b>(一覧 3 + 4。2026-10-16 風の案 C、10-30 作り直し。元は parked の {@code client/CanopySunMap})。
 * 地形のシェーダー({@code shaders/include/ov_light.glsl})に<b>太陽の当たり方の小さな地図</b>を渡し、地面・草・シダ・幹・葉のどの面にも同じ影を落とす。
 *
 * <h2>太陽の向きに沿った地図</h2>
 * 太陽の方向に沿った 1 本の線の上の点は、どれも同じ (u, v) = (x − y·sun.x/sun.y, z − y·sun.z/sun.y) を持つ。地図の 1 マスは 1 本の線
 * ({@link #SIZE}×{@link #SIZE} マス = カメラの周り ±48 ブロック)で、線をカメラの {@link #BELOW} 下から {@link #ABOVE} 上まで 0.5 ブロック刻みでたどり、
 * いちばん高い固いブロックの上端・いちばん高い葉の上端・葉の層の数・過繁茂の地域か(外は影を落とさない)を 1 画素に書く。
 * 地図は裏のスレッド({@link ScanThread})で 3.2 秒ごとに作り直し、地図ごとにその時の太陽の向きを持つ。
 *
 * <h2>影の色</h2>
 * 全部影のときの明るさは 75%、逆光に慣れきると 58%({@link SunLight#backlight})。色は灰色から青緑(比 0.80 : 1.00 : 1.06、明るさはそろえる)へ
 * {@code shadeTint} の強さで寄せる(2026-10-16 ユーザー選択「影を青緑に寄せ、筋と胞子の金色を浮かせる」)。
 * <b>森の中はいつも青緑</b>(同「日光が差す場所以外でも、過繁茂の森の中にいるときは常に」): 地図が日向・影を知っている所では影だけを、
 * 知らない所(地図の外・太陽の低い朝夕・夜)では全部の面を、森の中にいる度合い × {@code forestTint} で寄せる。松明の明かりで照らされた面は寄せない。
 *
 * <h2>出し入れ</h2>
 * 影の強さは {@code surfaceShade} × 昼の強さ × 覆いの下で空の下にいる度合い({@link ForestPresence#openCover}。11-06 から世界全体 = バニラの森でも影が落ちる)、
 * 青緑・日なたの金は過繁茂の森の中にいる度合い({@link ForestPresence#overgrown})で、どちらも森の縁でなめらかに出入りする。覆いの無い所(度合い 0)では地図を作らない。
 * 地図の a(過繁茂の列 255・外 128)は、シェーダーでは青緑と日なたの金だけを分ける(影・斑点・透過光・月の地図は外でも効く)。Sodium 0.8 には {@link SodiumTerrain} が同じ uniform を写す。
 * Sodium が使えない・Embeddium・シェーダーパック使用中は {@link #active()} が false で、地面の上面だけに落とす({@link CanopyShade})。
 *
 * <h2>草・シダ・幹の斑点</h2>
 * 葉が 1〜3 層だけの所(固いブロックは無い)の下では、地面の上面以外の面に小さな日向の斑点が抜ける(地面の上面は {@link CanopyDapple})。
 * 模様は太陽の向きの座標のなめらかな雑音で、枝の揺れ({@link Wind})で模様ごと行き来し、1 粒ずつはシェーダーの中で明滅する。
 */
public final class CanopySunMap {

    private static final int SIZE = 96;
    private static final int BELOW = 24;
    private static final int ABOVE = 72;
    /** 作り直しの間隔(tick)。 */
    private static final int REBUILD_TICKS = 64;
    /** これより太陽が低いと影を落とさない(線が長くなりすぎる)。 */
    private static final float MIN_SUN_Y = 0.2F;
    /** 斑点の模様の細かさ(シェーダーの {@code uvRel * 1.7} と同じ値)。 */
    private static final double FLECK_SCALE = 1.7;

    private static final class Map {
        final int originU;
        final int originV;
        final int baseY;
        final int topY;
        final double sunX;
        final double sunY;
        final double sunZ;
        final int camY;
        final int[] pixels = new int[SIZE * SIZE];

        Map(int originU, int originV, int baseY, int topY, Vec3 sun, int camY) {
            this.originU = originU;
            this.originV = originV;
            this.baseY = baseY;
            this.topY = topY;
            this.sunX = sun.x;
            this.sunY = sun.y;
            this.sunZ = sun.z;
            this.camY = camY;
        }
    }

    @Nullable
    private static CompletableFuture<Map> building;
    private static long buildStartTick = -1_000_000L;
    private static volatile int buildFailures;
    @Nullable
    private static Map shown;
    private static boolean uploadPending;
    @Nullable
    private static DynamicTexture texture;
    private static boolean broken;
    private static final float[] RGB = new float[3];

    /** 地図を使って全部の面に影を落としているか(false なら {@link CanopyShade} が地面にだけ落とす)。 */
    static boolean active() {
        return !SodiumTerrain.unsupported() && !broken && shown != null;
    }

    /** 全部影のときの色(掛け算、RGB)を {@code out} へ。シェーダーの影と地面の影 {@link CanopyShade} の両方が使う。 */
    static void shadeColor(float[] out) {
        float level = shadeLevel();
        hue(out);
        out[0] *= level;
        out[1] *= level;
        out[2] *= level;
    }

    /** 全部影のときの明るさ(灰色の掛け算。逆光で 75% → 58%)。 */
    private static float shadeLevel() {
        var t = DesignData.atmosphere();
        return Mth.lerp(SunLight.backlight(), t.shade(), t.shadeBacklit());
    }

    /**
     * 影の色合い(明るさ 1 の掛け算)。<b>時刻で少し動かす</b>(提案 4、10-30 ユーザー採用): 昼は青緑(了承済みの比)、太陽が地平線に近い夕方・朝は紫寄り、
     * 夜は月光の銀青。太陽の高さ 0.05〜0.4 で夕方 → 昼、地平線の下 0〜0.2 で夕方 → 夜へ寄せる(仮決め)。強さは {@code shadeTint}。
     */
    private static void hue(float[] out) {
        var t = DesignData.atmosphere();
        float tint = t.shadeTint();
        float sunY = sunHeight();
        float[] dusk = t.duskTint();
        // 昼の影の色の比(A2、10-30 夜 ユーザー採用: 青緑から緑寄りへ。atmosphere.json の light.shadeHue、見本で決める)。
        float[] day = t.shadeHue();
        float r = day[0];
        float g = day[1];
        float b = day[2];
        if (sunY >= 0.0F) {
            float w = SunLight.smooth(Mth.clamp((sunY - 0.05F) / 0.35F, 0.0F, 1.0F));
            r = Mth.lerp(w, dusk[0], r);
            g = Mth.lerp(w, dusk[1], g);
            b = Mth.lerp(w, dusk[2], b);
        } else {
            float[] night = t.nightTint();
            float w = nightWeight();
            r = Mth.lerp(w, dusk[0], night[0]);
            g = Mth.lerp(w, dusk[1], night[1]);
            b = Mth.lerp(w, dusk[2], night[2]);
        }
        out[0] = Mth.lerp(tint, 1.0F, r);
        out[1] = Mth.lerp(tint, 1.0F, g);
        out[2] = Mth.lerp(tint, 1.0F, b);
    }

    /** 今の太陽の高さ(向きの y。ワールドの外は昼)。 */
    private static float sunHeight() {
        ClientLevel level = Minecraft.getInstance().level;
        return level == null ? 1.0F : (float) SunLight.sunDirection(level, 1.0F).y;
    }

    /** 夜の度合い(太陽が地平線の下 0〜0.2 で 0 → 1)。夜の青緑(銀青)は {@code nightTintStrength} 倍に弱める(夜も影は付く)。 */
    private static float nightWeight() {
        float sunY = sunHeight();
        return sunY >= 0.0F ? 0.0F : SunLight.smooth(Mth.clamp(-sunY / 0.2F, 0.0F, 1.0F));
    }

    /** 最後に地図を作るのにかかった時間(マイクロ秒、裏のスレッド。重さの確かめ用)。 */
    static volatile long lastBuildMicros;
    private static int builds;
    /**
     * 月の斑点の強さ(0〜1、11-05 光の点検 4)= 夜の度合い × 月の高さ × 月の満ち欠けの門(月の明るさ {@code moonMapPhaseMin} 以下で 0、満月で 1)× 雨で弱める。
     */
    static float moonMap(ClientLevel level, float partialTick) {
        float min = DesignData.atmosphere().moonMapPhaseMin();
        float gate = SunLight.smooth(Mth.clamp((level.getMoonBrightness() - min) / (1.0F - min), 0.0F, 1.0F));
        return SunLight.night(level, partialTick) * SunLight.moonHeight(level, partialTick) * gate * (1.0F - 0.9F * level.getRainLevel(partialTick));
    }

    static void clear() {
        building = null;
        buildStartTick = -1_000_000L;
        shown = null;
    }

    /** 毎 tick({@link Atmosphere} から)。森の中・昼なら地図を裏のスレッドで作り直す。 */
    static void tick(ClientLevel level, long ticks) {
        if (SodiumTerrain.unsupported() || broken) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        // 夜は月の向きで作る(11-05 光の点検 4「月の斑点」。満月のころだけ: moonMap が 0 なら作らない)。
        Vec3 sun = SunLight.lightDirection(level, 1.0F);
        boolean moon = SunLight.isMoonlight(level, 1.0F);
        if (DesignData.atmosphere().surfaceShade() <= 0.0F || sun.y < MIN_SUN_Y || ForestPresence.openCover() <= 0.001F && (moon || !terrainShadowOn())
                || moon && moonMap(level, 1.0F) <= 0.01F) {
            clear();
            return;
        }
        if (building != null) {
            if (!building.isDone()) {
                return;
            }
            Map done = building.getNow(null);
            building = null;
            if (done != null) {
                shown = done;
                buildFailures = 0;
                uploadPending = true;
            }
        }
        if (ticks - buildStartTick < REBUILD_TICKS) {
            return;
        }
        buildStartTick = ticks;
        Vec3 eye = mc.gameRenderer.getMainCamera().getPosition();
        int camY = Mth.floor(eye.y);
        // 地図の真ん中と下端は、カメラの真下の地面(覆いの下ではカメラ)に合わせる(11-06 バニラ地域の光 1: 村を高い所から見下ろすと、地面がカメラの線の地図の外
        // (太陽が低いと高さの差 × 太陽の傾きだけずれる)と下端より下に出て、地形の影が出なかった)。高さの差は 100 まで(地図の値 0〜255 に収める)。
        int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(eye.x), Mth.floor(eye.z));
        int refY = Math.max(Math.min(camY, ground), camY - 100);
        double camU = eye.x - refY * sun.x / sun.y;
        double camV = eye.z - refY * sun.z / sun.y;
        Map m = new Map(Mth.floor(camU) - SIZE / 2, Mth.floor(camV) - SIZE / 2, refY - BELOW, camY + ABOVE, sun, refY);
        building = CompletableFuture.supplyAsync(() -> {
            long start = System.nanoTime();
            for (int index = 0; index < SIZE * SIZE; index++) {
                m.pixels[index] = trace(level, m, index % SIZE, index / SIZE);
            }
            lastBuildMicros = (System.nanoTime() - start) / 1000L;
            if (++builds % 20 == 1) {
                OvergrownVegetation.LOGGER.info("林冠の地図(裏のスレッド、{}): 1 回 {} µs", m.sunY > 0.0 && SunLight.isMoonlight(level, 1.0F) ? "月" : "太陽", lastBuildMicros);
            }
            return m;
        }, ScanThread.EXECUTOR).exceptionally(t -> {
            if (++buildFailures >= 3) {
                broken = true;
                OvergrownVegetation.LOGGER.error("林冠の日向の地図を 3 回続けて作れなかったので、このセッションでは使わない(地面の影だけに戻す)", t);
            } else {
                OvergrownVegetation.LOGGER.warn("林冠の日向の地図を作れなかった(次の回にもう一度): {}", t.toString());
            }
            return null;
        });
    }

    /** 地図のマス (i, j) の線をたどって、画素(ABGR)を返す。r = 固いブロックの上端、g = 葉の上端、b = 樹冠の層 × 17、a = 過繁茂なら 255・外なら 128。 */
    private static int trace(ClientLevel level, Map m, int i, int j) {
        double u = m.originU + i + 0.5;
        double v = m.originV + j + 0.5;
        double kx = m.sunX / m.sunY;
        double kz = m.sunZ / m.sunY;
        BlockPos at = BlockPos.containing(u + m.camY * kx, m.camY, v + m.camY * kz);
        boolean overgrown = ModBiomes.isOvergrownLike(level.getBiome(at));
        double y = m.baseY;
        double x = u + y * kx;
        double z = v + y * kz;
        double dx = m.sunX * 0.5;
        double dy = m.sunY * 0.5;
        double dz = m.sunZ * 0.5;
        int solidTop = 0;
        int leafTop = 0;
        int crowns = 0;
        int run = 0;
        double lastLeafY = Double.NEGATIVE_INFINITY;
        double gapY = SunTrace.CROWN_GAP * m.sunY;
        long last = Long.MIN_VALUE;
        long lastColumn = Long.MIN_VALUE;
        int columnTop = 0;
        long lastSection = Long.MIN_VALUE;
        boolean sectionAir = false;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        while (y < m.topY) {
            x += dx;
            y += dy;
            z += dz;
            int bx = Mth.floor(x);
            int by = Mth.floor(y);
            int bz = Mth.floor(z);
            long column = ((long) bx << 32) | (bz & 0xFFFFFFFFL);
            if (column != lastColumn) {
                lastColumn = column;
                columnTop = level.getHeight(Heightmap.Types.WORLD_SURFACE, bx, bz);
            }
            if (by >= columnTop) {
                continue;
            }
            long here = BlockPos.asLong(bx, by, bz);
            if (here == last) {
                continue;
            }
            last = here;
            long section = SectionPos.asLong(bx >> 4, by >> 4, bz >> 4);
            if (section != lastSection) {
                lastSection = section;
                sectionAir = SunTrace.sectionIsAir(level, bx, by, bz);
            }
            if (sectionAir) {
                continue;
            }
            BlockState state = level.getBlockState(pos.set(bx, by, bz));
            if (state.isAir() || state.getCollisionShape(level, pos).isEmpty()) {
                continue;
            }
            int top = Mth.clamp(by + 1 - m.baseY, 0, 255);
            if (state.canOcclude()) {
                solidTop = Math.max(solidTop, top);
            } else {
                leafTop = Math.max(leafTop, top);
                if (run > 0 && y - lastLeafY > gapY) {
                    crowns += SunTrace.crownWeight(run);
                    run = 0;
                }
                run++;
                lastLeafY = y;
            }
        }
        if (run > 0) {
            crowns += SunTrace.crownWeight(run);
        }
        int b = Math.min(crowns, 15) * 17;
        int a = overgrown ? 255 : 128;
        return (a << 24) | (b << 16) | (leafTop << 8) | solidTop;
    }

    /** 空を描いた後・地形を描く前に({@link Atmosphere} から)、地図と太陽の向きを地形のシェーダー(固い・草・葉の 3 つ)へ渡す。描かない間は強さ 0 を渡す。 */
    /**
     * 環境の胞子({@link AmbientSpores})が日向かを見るための、今のフレームの地図の受け渡し(10-30 夜 提案 3)。
     * {@code sporeTexture} は地図のテクスチャ(使えないとき −1)、{@code SPORE_SUN_A}・{@code SPORE_MAP_A} は地形のシェーダーの OvSunA・OvMapA と同じ値。
     */
    static int sporeTexture = -1;
    private static final float[] SKY_TINT = {1.0F, 1.0F, 1.0F};
    static final float[] SPORE_SUN_A = new float[4];
    static final float[] SPORE_MAP_A = new float[4];

    static void upload(RenderLevelStageEvent event, boolean enabled) {
        sporeTexture = -1;
        // 登る蔓のシェーダー(ClimbingVineRenderer、11-05 光の点検 6)も地形と同じ値を受ける(読み込めていなければ null で飛ばす)。
        ShaderInstance[] shaders = {GameRenderer.getRendertypeSolidShader(), GameRenderer.getRendertypeCutoutShader(),
                GameRenderer.getRendertypeCutoutMippedShader(), com.yayuy.overgrownvegetation.client.shader.ModShaders.lianaCanopyLit()};
        try {
            Minecraft mc = Minecraft.getInstance();
            Map m = broken || !enabled ? null : shown;
            float amount = 0.0F;
            float mapDay = 0.0F;
            Vec3 sun = null;
            float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
            float night = 0.0F;
            float moonMapK = 0.0F;
            if (mc.level != null) {
                night = SunLight.night(mc.level, partialTick);
                moonMapK = moonMap(mc.level, partialTick);
            }
            if (m != null && mc.level != null) {
                sun = SunLight.lightDirection(mc.level, partialTick);
                // 夜(月の地図)は mapDay = 0 のまま(葉を透かす光・胞子の日向・霧のレイマーチの日向は昼だけ)。強さは月の斑点の強さ。
                boolean moon = SunLight.isMoonlight(mc.level, partialTick);
                mapDay = moon ? 0.0F : SunLight.daylight(mc.level, sun, partialTick);
                amount = VisualQuality.canopyLight() ? DesignData.atmosphere().surfaceShade() * (moon ? moonMapK : mapDay) * ForestPresence.openCover() : 0.0F;
            }
            hue(RGB);
            float forest = enabled && mc.level != null && VisualQuality.canopyLight() ? ForestPresence.overgrown() * DesignData.atmosphere().forestTint()
                    * Mth.lerp(nightWeight(), 1.0F, DesignData.atmosphere().nightTintStrength()) : 0.0F;
            // 空の光の色(11-05 光の点検 3、ov_light.glsl の ovSkyTintAt): 太陽の高さの色を全面に。太陽の地図が無い夕方にも効く。
            // 11-06 から世界全体(覆いが無い平原でも。地下では切るが、過繁茂の地下は前と同じ: ForestPresence.sky)。
            // 日なたの面もこの色が掛かるので OvSunTint は変えない。
            float skyStrength = 0.0F;
            if (enabled && mc.level != null && VisualQuality.canopyLight()) {
                SunLight.skyTint(mc.level, SunLight.sunDirection(mc.level, partialTick), partialTick, SKY_TINT);
                skyStrength = ForestPresence.sky() * DesignData.atmosphere().skyTintStrength();
            }
            // 夜の暗さ(11-05 光の点検 4、ov_light.glsl の ovMoonNight): 月の向きの面の明暗・月の当たる所だけ青灰・暗い所は彩度を落とす。
            // 11-06 から世界全体(ForestPresence.sky。覆いが無くても効く)。昼と夕方は night = 0 で何もしない。
            var atm0 = DesignData.atmosphere();
            float nightForest = 0.0F;
            float moonFace = 0.0F;
            float moonGlow = 0.0F;
            Vec3 lightDir = new Vec3(0.0, 1.0, 0.0);
            if (enabled && mc.level != null && VisualQuality.canopyLight()) {
                float forestK = ForestPresence.sky();
                nightForest = night * forestK;
                moonFace = atm0.moonShadeStrength() * SunLight.moonLight(mc.level, partialTick, SunLight.MOON_FLOOR) * forestK;
                moonGlow = SunLight.moonLight(mc.level, partialTick, 0.0F);
                lightDir = SunLight.lightDirection(mc.level, partialTick);
            }
            float[] moonColor = atm0.moonMapColor();
            float warmK = enabled && mc.level != null && VisualQuality.canopyLight() ? ForestPresence.overgrown() : 0.0F;
            // 森の外から見た巨大樹の空の光の下限(11-13、GiantTreeLight・ov_giant.glsl)。
            GiantTreeLight.prepare(mc.level, mc.gameRenderer.getMainCamera().getPosition(), enabled, night);
            for (ShaderInstance shader : shaders) {
                if (shader != null) {
                    // 光の向き(夜は月)。地図が無い間も毎フレーム流す(月の向きの面の明暗が使う)。w = 地図の中の背面の影の強さ(夜は面の明暗に任せて 0 へ)。
                    shader.safeGetUniform("OvSunDir").set((float) lightDir.x, (float) lightDir.y, (float) lightDir.z, 1.0F - night);
                    shader.safeGetUniform("OvMoon").set(nightForest, moonFace, atm0.nightDesaturate(), moonGlow);
                    shader.safeGetUniform("OvMoonB").set(atm0.moonShadeSide(), atm0.moonShadeBack(), atm0.moonShadeDown(), 0.0F);
                    shader.safeGetUniform("OvMoonC").set(moonColor[0], moonColor[1], moonColor[2], moonMapK);
                    shader.safeGetUniform("OvTint").set(RGB[0], RGB[1], RGB[2], forest);
                    shader.safeGetUniform("OvSkyTint").set(SKY_TINT[0], SKY_TINT[1], SKY_TINT[2], skyStrength);
                    shader.safeGetUniform("OvSkyTintB").set(DesignData.atmosphere().skyTintShade(), 0.0F, 0.0F, 0.0F);
                    GiantTreeLight.set(shader);
                    // 点検 21(11-06): 木漏れ日は影の穴(日向の明るさへ戻すだけ)。ライトマップの持ち上げを写して、その面が日向よりどれだけ暗いかを出す。
                    // 幹の影の帯は design の light.trunkShadow*。
                    shader.safeGetUniform("OvLift").set(CanopyLift.skyLift(), CanopyLift.skyLiftDark(), night,
                            mc.options.gamma().get().floatValue());
                    // 地形の影(幹・岩・丘・崖・家。地図があるときだけ下で強さを入れる)。
                    shader.safeGetUniform("OvTrunk").set(0.0F, atm0.terrainShadowReach(), atm0.trunkShadowSoft(), 0.0F);
                    // 日なたの暖かい白〜金(A4、10-30 夜 ユーザー採用)。日の当たる面と木漏れ日の斑点に掛ける(森の中だけ)。
                    float[] warm = DesignData.atmosphere().sunWarm();
                    // 11-06: 過繁茂で強まる層(過繁茂の度合いで寄せる。シェーダーでも過繁茂の列 a = 255 だけ)。
                    shader.safeGetUniform("OvSunTint").set(warm[0], warm[1], warm[2], DesignData.atmosphere().sunWarmStrength() * (1.0F - night) * warmK);
                }
            }
            // 覆いの無い所(平原・村・崖)でも、地形の影のためだけに地図を使う(バニラ地域の光 1、11-06)。林冠の影・斑点は amount 0 のまま。
            boolean terrain = m != null && sun != null && mapDay > 0.0F && terrainShadowOn();
            if (sun == null || amount <= 0.0F && !terrain) {
                for (ShaderInstance shader : shaders) {
                    if (shader != null) {
                        shader.safeGetUniform("OvSunA").set(0.0F, 0.0F, 0.0F, 0.0F);
                        shader.safeGetUniform("OvFleck").set(0.0F, 0.0F, 0.0F, 0.0F);
                    }
                }
                return;
            }
            if (texture == null) {
                texture = new DynamicTexture(SIZE, SIZE, false);
            }
            if (uploadPending) {
                NativeImage image = texture.getPixels();
                if (image != null) {
                    for (int j = 0; j < SIZE; j++) {
                        for (int i = 0; i < SIZE; i++) {
                            image.setPixelRGBA(i, j, m.pixels[j * SIZE + i]);
                        }
                    }
                    texture.upload();
                }
                uploadPending = false;
            }
            Vec3 cam = event.getCamera().getPosition();
            double kx = m.sunX / m.sunY;
            double kz = m.sunZ / m.sunY;
            double camU = cam.x - cam.y * kx;
            double camV = cam.z - cam.y * kz;
            // 斑点の模様は太陽の向きの座標で世界に固定する(カメラの (u, v) を 1.7 倍して 64 で折り返して渡す。模様は 64 ごとに繰り返す)。
            // 枝の揺れで模様ごと風の向きへ行き来し、1 粒ずつはシェーダーの中で明滅する。
            Wind wind = Wind.current();
            double windTime = Atmosphere.time(partialTick);
            double gust = Math.min(1.0, wind.strength(cam.x, cam.z, windTime));
            double branch = (0.02 + 0.22 * gust) * Wind.branch(windTime, 0.0);
            double fleckU = wrap64((camU + branch * wind.dirX()) * FLECK_SCALE);
            double fleckV = wrap64((camV + branch * wind.dirZ()) * FLECK_SCALE);
            float seconds = (float) ((windTime / 20.0) % 1000.0);
            // 夜の月の地図では、月の影を深く(1 − moonMapStrength。昼の影 0.92 では月の当たる床が浮かない)。
            float level = Mth.lerp(night, shadeLevel(), 1.0F - DesignData.atmosphere().moonMapStrength());
            sporeTexture = texture.getId();
            SPORE_SUN_A[0] = (float) kx;
            SPORE_SUN_A[1] = (float) kz;
            SPORE_SUN_A[2] = amount;
            SPORE_SUN_A[3] = mapDay;
            SPORE_MAP_A[0] = (float) (m.originU - camU);
            SPORE_MAP_A[1] = (float) (m.originV - camV);
            SPORE_MAP_A[2] = (float) SIZE;
            SPORE_MAP_A[3] = (float) (m.baseY - cam.y);
            for (ShaderInstance shader : shaders) {
                if (shader == null) {
                    continue;
                }
                shader.setSampler("OvSunMap", texture.getId());
                // w = 地図が日向・影を決める度合い(昼の強さ)。z = 影の強さ。
                shader.safeGetUniform("OvSunA").set((float) kx, (float) kz, amount, mapDay);
                shader.safeGetUniform("OvSunDir").set((float) sun.x, (float) sun.y, (float) sun.z, 1.0F - night);
                shader.safeGetUniform("OvMapA").set((float) (m.originU - camU), (float) (m.originV - camV), (float) SIZE,
                        (float) (m.baseY - cam.y));
                shader.safeGetUniform("OvShadeColor").set(level, level, level, 1.0F);
                if (terrain) {
                    var atmT = DesignData.atmosphere();
                    // w: 1 = 地図あり、2 = 地図あり・重さの段が低い(縁のぼかしを 5 点から 1 点に。11-06)。
                    shader.safeGetUniform("OvTrunk").set(atmT.trunkShadow() * mapDay, atmT.terrainShadowReach(), atmT.trunkShadowSoft(),
                            VisualQuality.atmosphereTier() >= 2 ? 2.0F : 1.0F);
                }
                shader.safeGetUniform("OvFleck").set((float) fleckU, (float) fleckV, seconds, CanopyDapple.FLECKS_ON && VisualQuality.canopyDapple() ? 1.0F : 0.0F);
            }
        } catch (RuntimeException e) {
            broken = true;
            sporeTexture = -1;
            for (ShaderInstance shader : shaders) {
                if (shader != null) {
                    shader.safeGetUniform("OvSunA").set(0.0F, 0.0F, 0.0F, 0.0F);
                    shader.safeGetUniform("OvTint").set(1.0F, 1.0F, 1.0F, 0.0F);
                }
            }
            OvergrownVegetation.LOGGER.error("林冠の影の地図で例外が出たので、このセッションでは使わない(地面の影だけに戻す)", e);
        }
    }

    /** 地形の影(幹・岩・丘・崖・家の影)を出すか。覆いが無くても太陽地図を作る理由(バニラ地域の光 1、11-06)。地下では作らない。 */
    private static boolean terrainShadowOn() {
        return VisualQuality.canopyLight() && DesignData.atmosphere().trunkShadow() > 0.0F && ForestPresence.underground() < 0.5F;
    }

    private static double wrap64(double value) {
        return value - Math.floor(value / 64.0) * 64.0;
    }

    /**
     * 確かめ用(11-05 光の点検 20、devbridge {@code cavedev ray}): ワールドの点 (x, y, z) を太陽の地図で引いた値。
     * 固いブロックの上端・葉の上端・樹冠の層・過繁茂か、その点が地図で「固いブロックの陰」か、上の葉の数。地図が無ければ「なし」。
     */
    static String debugAt(double x, double y, double z) {
        Map m = shown;
        if (m == null || m.sunY <= 0.0) {
            return "太陽の地図 なし";
        }
        double u = x - y * m.sunX / m.sunY;
        double v = z - y * m.sunZ / m.sunY;
        int i = Mth.floor(u - m.originU);
        int j = Mth.floor(v - m.originV);
        if (i < 0 || j < 0 || i >= SIZE || j >= SIZE) {
            return "太陽の地図の外 (" + i + ", " + j + ")";
        }
        int p = m.pixels[j * SIZE + i];
        int solidTop = p & 0xFF;
        int leafTop = (p >> 8) & 0xFF;
        int crowns = (p >> 16) & 0xFF;
        int a = p >>> 24;
        double h = y - m.baseY;
        boolean shadowed = solidTop > 0 && h < solidTop - 1.5;
        return String.format("太陽の地図 (%d, %d) 固い上端 %d 葉の上端 %d 層 %d a %d 高さ %.1f 固いの陰 %s 上の葉 %.1f 縁まで %d",
                i, j, solidTop, leafTop, crowns, a, h, shadowed, Math.min(crowns / 17.0, leafTop - h), Math.min(Math.min(i, j), Math.min(SIZE - 1 - i, SIZE - 1 - j)));
    }

    private CanopySunMap() {
    }
}
