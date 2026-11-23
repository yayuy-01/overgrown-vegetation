package com.yayuy.overgrownvegetation.client.atmosphere;

import com.mojang.blaze3d.platform.GlStateManager;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.LeyRootBlock;
import com.yayuy.overgrownvegetation.block.OvergrownGlowVines;
import com.yayuy.overgrownvegetation.client.VisualQuality;
import com.yayuy.overgrownvegetation.data.AtmosphereCave;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * <b>地下モード</b>(2026-11-01 試作。仕様は Vault の「地下モード(洞窟のシェーダーと光)」)。過繁茂の地域の地下を、地上の森(明るい・黄緑の胞子の霧)とは
 * 別の見え方にする: 黒い霧(見通し 12〜16、ユーザー指定)の中に植物の光だけが点々と浮かぶ。
 *
 * <ul>
 *     <li><b>地上と混ぜる係数 {@link #mix()}</b>(0〜1): まわりの 17 列のうち岩の天井がある割合(真上 1 列の {@link ForestPresence#underground()} だと口の 1 歩で跳んだ)× 過繁茂の地域(カメラの所か、その列の地表の
 *     バイオーム)× 空の光の暗さ(カメラの所の空の光が {@code skyFrom} から下がり始め {@code skyTo} 以下で 1。洞窟の口から奥へ歩くと少しずつ地下になる。仮決め)。
 *     目標へは時定数 transitionCaveSeconds(11-01 夜から 4 秒、前は 1 秒の直線)で寄る。黒い霧はバニラの霧({@link MistView})で出すので Mob・粒・水にも同じに掛かる。地上の朝霧と胞子の霧はこの分だけ引く({@link MistFog})。</li>
 *     <li><b>点光源</b>: カメラのまわり {@code radius}(48)の読み込み済みの区画から「光を出すブロック」({@link #lookup})を 2 秒おきに集める
 *     (区画のパレットに光源が無ければ中を読まない。作業スレッド {@link ScanThread})。毎フレーム近い順に最大 {@code maxLights}(24)個を選び、
 *     24×2 の小さな浮動小数のテクスチャ {@code OvCaveLights}(1 行目 = カメラからの位置と半径、2 行目 = 色と強さ)で地形シェーダーへ渡す
 *     (バニラのシェーダーの json は配列の uniform を宣言できないため。Sodium へは {@code SodiumTerrain} が名前で写す)。
 *     面の色には {@code 絵の色 × 光の色 × 強さ / (1 + (d/r)²)} を足し、r〜1.5r で 0 へ落とす(霧より前。{@code ov_cave.glsl})。</li>
 *     <li><b>Mob・手に持った物・粒</b>: 同じ光源の表から {@code client/light/DynamicLights} がライトマップのブロックの光を持ち上げる。</li>
 * </ul>
 * 壁は光を遮らない(描画だけの光。試作では影を付けない)。湧き・植物の判定は本物の光量(0〜3)だけ。
 */
public final class CaveLights {

    /** シェーダーへ渡す光源の上限(テクスチャの幅。設計値の maxLights はこれ以下)。 */
    public static final int MAX = 24;
    /** テクスチャの列(光源 {@value #MAX} + 床の持ち上げ・飽和の抑えの 1 列)。 */
    private static final int COLS = MAX + 1;
    private static final int SCAN_TICKS = 40;

    /** 光源の種類: 半径・色・強さ(仕様の表。仮決め)。vanilla = バニラ・他の MOD の光源(11-04。シェーダーへは半径を負で渡し、ブロックの光の強い所で足し分を抑える)。 */
    public record Kind(float radius, float r, float g, float b, float strength, boolean vanilla, int family, boolean flame) {
        static Kind of(float radius, int rgb, float strength) {
            return of(radius, rgb, strength, false, -1, false);
        }

        /** family = バニラの色の系統(この MOD の光源は −1)、flame = 炎のゆらぎを付ける(11-06 光源 7)。 */
        static Kind of(float radius, int rgb, float strength, boolean vanilla, int family, boolean flame) {
            return new Kind(radius, (rgb >> 16 & 0xFF) / 255.0F, (rgb >> 8 & 0xFF) / 255.0F, (rgb & 0xFF) / 255.0F, strength, vanilla, family, flame);
        }
    }

    static final Kind TASSEL = Kind.of(22.0F, 0xACE300, 1.0F);
    static final Kind FOXFIRE = Kind.of(4.0F, 0x6FD08A, 0.5F);
    static final Kind LEY_NEAR = Kind.of(6.0F, 0xACE300, 0.6F);
    static final Kind LEY_FAR = Kind.of(3.0F, 0x5F7D00, 0.25F);
    static final Kind VINE = Kind.of(6.0F, 0x9FC23A, 0.5F);
    /** 光る根の網の結び目(11-03 地下の植生 1。地下モードの仕様書の表)。 */
    static final Kind NET = Kind.of(6.0F, 0x7FA600, 0.4F);
    /** 網のマスのうち光源にする割合の逆数({@link #scan})。 */
    static final int NET_EVERY = 4;

    /** 1 つの光源(ブロックの真ん中)。 */
    public record Light(double x, double y, double z, Kind kind) {
    }

    private static float mix;
    private static float mixTarget;
    private static long scannedAt = -1_000_000L;
    @Nullable
    private static CompletableFuture<Light[]> scanning;
    /** 集めた光源(作業スレッドの結果を丸ごと差し替える)。 */
    private static volatile Light[] found = new Light[0];
    /** このフレームにシェーダーへ渡した光源(近い順)。Mob の明るさもこれを読む。 */
    private static volatile Light[] shown = new Light[0];
    /** 確かめ用: 渡す数の上限を上書き(-1 = 設計値)。 */
    static int devMaxLights = -1;
    /** 確かめ用: 地下の係数を赤で塗る(シェーダーの OvCaveFogD.w)。 */
    static boolean devTint;
    /** 確かめ用: 口からの沈み(ovCaveSink)を青で塗る(11-04。devTint より先)。 */
    static boolean devTintSink;
    /** 確かめ用: このセッションだけ設計値を上書き(負で設計値。devbridge の {@code wind cavedev falloff|ambient|mouth|lightsink}、11-04)。 */
    static float devFalloff = -1.0F;
    static float devAmbient = -1.0F;
    static float devMouthFrom = -1.0F;
    static float devMouthTo = -1.0F;
    static float devLightSink = -1.0F;

    /** 点光源が半分の明るさになる距離 ÷ 半径(11-04 地下の光の直し 7)。 */
    static float falloff(AtmosphereCave.CaveParams p) {
        return devFalloff > 0.0F ? devFalloff : p.falloff();
    }

    /** 地下モードの床の持ち上げ(確かめ用の上書き込み)。 */
    public static float ambient() {
        // 11-07 目の慣れ: 洞窟へ入った直後は床の持ち上げを絞る(光源だけが見え、数秒で岩の輪郭が見えてくる)
        return (devAmbient >= 0.0F ? devAmbient : DesignData.atmosphere().cave().ambient()) * EyeAdaptation.ambientScale();
    }

    private static int texture;
    private static FloatBuffer buffer;

    private CaveLights() {
    }

    /** 地上と混ぜる係数(0 = 地上のまま、1 = 地下モード)。 */
    public static float mix() {
        return mix;
    }

    /** このフレームに渡した光源(近い順)。 */
    public static Light[] shown() {
        return shown;
    }

    /** 雲を描く直前・直後(Mixin {@code LevelRendererCaveCloudsMixin} から)。雲の間だけ霧を地上のままに戻す({@link MistView#beginClouds}。11-04 洞窟の直し 1)。 */
    public static void beginClouds() {
        MistView.beginClouds();
    }

    public static void endClouds() {
        MistView.endClouds();
    }

    /** ライトマップのブロックの光の列を寄せる色(明るさを除いた色。毎 tick {@link #updateTint})。 */
    private static final float[] TINT = {0.84F, 0.92F, 0.25F};
    /** ブロックの光の段がこれ以上なら寄せきる(弱い点光源で持ち上げた Mob も色が付くように。11-04 仮決め)。 */
    private static final float TINT_FULL_LEVEL = 6.0F;
    /** カメラの所に届く点光源の強さ(色を掛ける前の和)がこれ以上なら寄せきる(11-04 仮決め)。 */
    private static final float TINT_FULL_INTENSITY = 0.3F;
    /** 寄せる強さ(0〜1。カメラの所に点光源が届いている分)。 */
    private static float tintStrength;

    /**
     * 寄せる色: カメラの所に届く点光源の色(白へ寄せた後、シェーダーと同じ)を届く強さで重み付けし、床の持ち上げの色(ambientColor × ambient)を足したもの。
     * Mob ごとに色を変えることはできない(ライトマップは 1 枚)ので、カメラの所の色で代える(11-04 洞窟の直し 3)。
     */
    private static void updateTint(Vec3 eye, AtmosphereCave.CaveParams p) {
        float[] ac = ambientColor();
        float amb = ambient();
        float r = ac[0] * amb;
        float g = ac[1] * amb;
        float b = ac[2] * amb;
        float w = p.lightWhiten();
        float sum = 0.0F;
        float fo = falloff(p);
        Light[] sh = shown;
        float[] wts = shownWeight;
        for (int i = 0; i < sh.length; i++) {
            Light l = sh[i];
            float a = reach(l.kind, Math.sqrt(dist2(l, eye)), fo) * (i < wts.length ? wts[i] : 1.0F);
            if (a <= 0.0F) {
                continue;
            }
            r += Mth.lerp(w, l.kind.r, 1.0F) * a;
            g += Mth.lerp(w, l.kind.g, 1.0F) * a;
            b += Mth.lerp(w, l.kind.b, 1.0F) * a;
            sum += a;
        }
        // 点光源の届かない暗い奥では寄せない(溶岩・松明に照らされた床まで灰緑になった。11-04 撮影で直した)
        tintStrength = Mth.clamp(sum / TINT_FULL_INTENSITY, 0.0F, 1.0F);
        float lum = 0.2126F * r + 0.7152F * g + 0.0722F * b;
        if (lum > 1.0E-4F) {
            TINT[0] = r / lum;
            TINT[1] = g / lum;
            TINT[2] = b / lum;
        }
    }

    /**
     * ライトマップの 1 マス({@code block} = ブロックの光の段、{@code sky} = 空の光の段、色は ABGR)を、地下モードの間だけ点光源の色へ寄せる(明るさは保つ)。
     * 寄せる量 = 地下の係数 × ブロックの光の段({@value #TINT_FULL_LEVEL} で全部)× 空の光の暗さ(11-04 洞窟の直し 3、Mixin {@code LightTextureCaveTintMixin} から)。
     */
    public static int tintLightmap(int abgr, int block, int sky) {
        float m = mix;
        if (m <= 0.001F || block <= 0) {
            return abgr;
        }
        float w = m * tintStrength * Mth.lerp(openShare(), 1.0F, DesignData.atmosphere().caveOpen().tint()) * Math.min(1.0F, block / TINT_FULL_LEVEL) * (1.0F - sky / 15.0F);
        if (w <= 0.001F) {
            return abgr;
        }
        float r = (abgr & 0xFF) / 255.0F;
        float g = (abgr >> 8 & 0xFF) / 255.0F;
        float b = (abgr >> 16 & 0xFF) / 255.0F;
        float lum = 0.2126F * r + 0.7152F * g + 0.0722F * b;
        float tr = TINT[0] * lum;
        float tg = TINT[1] * lum;
        float tb = TINT[2] * lum;
        float top = Math.max(tr, Math.max(tg, tb));
        if (top > 1.0F) {
            tr /= top;
            tg /= top;
            tb /= top;
        }
        int nr = Mth.clamp(Math.round(Mth.lerp(w, r, tr) * 255.0F), 0, 255);
        int ng = Mth.clamp(Math.round(Mth.lerp(w, g, tg) * 255.0F), 0, 255);
        int nb = Mth.clamp(Math.round(Mth.lerp(w, b, tb) * 255.0F), 0, 255);
        return (abgr & 0xFF000000) | nb << 16 | ng << 8 | nr;
    }

    static void clear() {
        mix = 0.0F;
        night = 0.0F;
        mixTarget = 0.0F;
        scanning = null;
        found = new Light[0];
        shown = new Light[0];
        shownWeight = new float[0];
        fading.clear();
        scannedAt = -1_000_000L;
        ogBlend = Float.NaN;
        camLocal = 0.0F;
        CaveMap.clear();
    }

    /** 光を出すブロックなら、その種類(無ければ null)。作業スレッドからも呼ぶ。 */
    @Nullable
    static Kind lookup(BlockState state) {
        // 地下の植生 1(11-03): 房は先だけが光る玉(中間は光源にしない)、網は結び目の弱い光
        if (state.is(ModBlocks.GLOW_TASSEL_TIP.get())) {
            return TASSEL;
        }
        if (state.is(ModBlocks.CEILING_ROOT_NET.get())) {
            return NET;
        }
        if (state.is(ModBlocks.FOXFIRE.get())) {
            return FOXFIRE;
        }
        if (state.is(ModBlocks.LEY_ROOT.get())) {
            return switch (state.getValue(LeyRootBlock.GLOW)) {
                case NEAR -> LEY_NEAR;
                case FAR -> LEY_FAR;
                case NONE -> null;
            };
        }
        if ((state.is(ModBlocks.OVERGROWN_GLOW_VINES.get()) || state.is(ModBlocks.OVERGROWN_GLOW_VINES_PLANT.get()))
                && OvergrownGlowVines.hasGlowBerries(state)) {
            return VINE;
        }
        return vanilla(state);
    }

    // ---- バニラ(と他の MOD)の光源(11-04 地下の光の直し 8。溶岩・松明・グロウストーンが周りを照らさず、溶岩の隣の床が緑のままだった) ----

    /** 色の系統: 0 = 炎(溶岩・マグマ・火・焚き火)、1 = 灯り(松明・ランタン・ろうそく)、2 = 淡い黄(グロウストーン・シュルームライト・ジャック・オ・ランタン)、3 = 冷たい光(シーランタン・エンドロッド・魂の火)、4 = 地衣・スカルクの青緑(光る地衣類・スカルクカタリスト)、5 = アメジストの紫(11-07 世界全体へ: 普通の洞窟の光源。前はどちらも淡い黄。色は仮決め)。 */
    private static final int[] FAMILY_RGB = {0xFF7A1E, 0xFFC46A, 0xFFE3A0, 0xA8DCFF, 0x8FE0C8, 0xC79BFF};
    /** 強さ(仮決め。発光 15 のとき)。 */
    private static final float[] FAMILY_STRENGTH = {0.6F, 0.6F, 0.5F, 0.5F, 0.35F, 0.35F};
    /** 系統 × 発光の段(1〜15)の種類(作業スレッドからも読むので先に作る)。 */
    private static final Kind[][] VANILLA = vanillaKinds(false);
    /** 同じ表の、炎のゆらぎを付ける種類(11-06 光源 7。松明・焚き火・溶岩・マグマ・火・ジャック・オ・ランタン・ろうそく)。 */
    private static final Kind[][] VANILLA_FLAME = vanillaKinds(true);

    private static Kind[][] vanillaKinds(boolean flame) {
        Kind[][] out = new Kind[FAMILY_RGB.length][16];
        for (int f = 0; f < FAMILY_RGB.length; f++) {
            for (int e = 1; e < 16; e++) {
                // 半径は発光の段 × 0.7(発光 15 で 10.5 ブロック。バニラの光が届く 15 より狭く、点光源の玉が見える)・強さは段に比例(仮決め)
                out[f][e] = Kind.of(0.7F * e, FAMILY_RGB[f], FAMILY_STRENGTH[f] * e / 15.0F, true, f, flame);
            }
        }
        return out;
    }

    /** 溶岩の種類か(区画の走査で間引く。溶岩は 11-06 からゆらぐ方の表)。 */
    static boolean isLavaKind(Kind k) {
        return k == VANILLA_FLAME[0][15] || k == VANILLA[0][15];
    }

    /**
     * 光を出すバニラ・他の MOD のブロックなら、その種類。この MOD のブロックは上の表だけ(房の中間・光らない地脈の根など、わざと光源にしないものがある)。
     * 発光の段 {@code getLightEmission()} が 5 未満のもの(マグマ 3・苔むした光る地衣類 7 未満など)は光源にしない(弱い光で 24 の枠を食わないように。仮決め)。
     */
    @Nullable
    static Kind vanilla(BlockState state) {
        int e = state.getLightEmission();
        if (e < 5) {
            return null;
        }
        String path = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        if (path.startsWith(OvergrownVegetation.MODID + ":")) {
            return null;
        }
        int family;
        boolean flame = false;
        if (path.contains("soul") || path.contains("sea_lantern") || path.contains("end_rod") || path.contains("beacon") || path.contains("conduit")) {
            family = 3;
        } else if (path.contains("jack_o_lantern")) {
            // 11-06 光源 6: 前は "lantern" で灯りの系統だった。中は蝋燭の火なので炎の橙・ゆらぎあり(仮決め)
            family = 0;
            flame = true;
        } else if (path.contains("lava") || path.contains("magma") || path.contains("fire") || path.contains("furnace") || path.contains("smoker")) {
            family = 0;
            // かまど・燻製器は炎の色だけでゆらがない(仮決め)
            flame = !path.contains("furnace") && !path.contains("smoker");
        } else if (path.contains("torch") || path.contains("lantern") || path.contains("candle")) {
            family = 1;
            // 松明・ろうそくだけゆらぐ。ランタン(ガラスの中)とレッドストーンの松明はゆらがない(仮決め)
            flame = path.contains("candle") || path.contains("torch") && !path.contains("redstone");
        } else if (!devOvergrownOnly && (path.contains("glow_lichen") || path.contains("sculk"))) {
            family = 4;
        } else if (!devOvergrownOnly && path.contains("amethyst")) {
            family = 5;
        } else {
            family = 2;
        }
        return (flame ? VANILLA_FLAME : VANILLA)[family][Math.min(15, e)];
    }

    /** 毎 tick({@link Atmosphere} から)。 */
    static void tick(ClientLevel level, Vec3 eye, long ticks) {
        if (!VisualQuality.caveLight()) {
            clear();
            return;
        }
        AtmosphereCave.CaveParams p = DesignData.atmosphere().cave();
        // 11-07(カメラ切り替えの廃止): カメラの所の値は場所ごとの判定の一つとして地形のシェーダーが読むので、0.5 秒おきでは歩くと段が出る。毎 2 tick にした。
        if (ticks % 2 == 1) {
            // 11-09 ユーザー決定「案 1」: カメラの 1 点だけで決めると、構造物の下で隣の列へ動くだけで跳び、そばの面がチカチカした。5 点の平均にする
            float spread = devCamSpread >= 0.0F ? devCamSpread : p.camSpread();
            long t0 = System.nanoTime();
            mixTarget = spread > 0.0F ? spreadTarget(level, eye, p, spread) : target(level, eye, p);
            targetNanos += System.nanoTime() - t0;
            targetCount++;
        }
        // 11-01 夜(ユーザー「地上 → 洞窟の変わり目をもっとゆっくり」): 1 秒の直線 → 時定数 transitionCaveSeconds(4 秒)の指数。
        // 黒い霧・点光源の強さ(シェーダーで × mix)・Mob の明るさがどれもこの値なので、まとめて滑らかになる。
        // 11-07 目の慣れ: 白く飛んでいる(暗く沈んでいる)間は早めに寄せて、つなぎ目を隠す
        mix = ForestPresence.approach(mix, mixTarget, 0.05F, EyeAdaptation.transitionSeconds(DesignData.atmosphere().fog().transitionCaveSeconds()));
        // カメラの所の場所の値(地形のシェーダーの OvCaveLocal.y)。段だけ均す 0.3 秒(仮決め)。
        camLocal = ForestPresence.approach(camLocal, mixTarget, 0.05F, devCamSeconds >= 0.0F ? devCamSeconds : 0.3F);
        // 11-07: カメラが洞窟の奥(いちばん近い口から 8〜24 列)にいる度合い。DH の遠景の霧の色を洞窟の色へ寄せるのに使う(MistView)
        float[] ct = CaveMap.texel(Mth.floor(eye.x), Mth.floor(eye.z));
        float deepGoal = ct == null || ct[0] < -9000.0F ? 0.0F : SunLight.smooth(Mth.clamp((ct[2] - 8.0F) / 16.0F, 0.0F, 1.0F));
        camDeep = ForestPresence.approach(camDeep, deepGoal, 0.05F, 1.0F);
        if (mix > 0.001F) {
            updateTint(eye, p);
        }
        night = nightFactor(level);
        // 11-07 判断の後の直し: 過繁茂の度合い(過繁茂の外の洞窟の値と混ぜる)
        blendOpen(eye);
        if (scanning != null && scanning.isDone()) {
            Light[] done = scanning.getNow(null);
            scanning = null;
            if (done != null) {
                found = done;
            }
        }
        // 11-01 夜(見え方の直し 4): 断片の所で地下を決める地図。カメラが外でも、まわりに過繁茂の地下があれば光源を集める(外から洞窟の中が照らされて見える)。
        // 11-07: 世界全体へ。空の光の無い次元(ネザー)は地図も作らない(岩盤の天井の下が全部「地下」になる)。
        if (level.dimensionType().hasSkyLight()) {
            CaveMap.tick(level, eye, ticks);
        } else {
            CaveMap.clear();
        }
        // 11-06 光源 6: 地上の夜も集める(バニラの光量はそのまま、面の色と向きだけ光源に寄せる。ov_cave.glsl の ovCaveLight)
        if (mix <= 0.0F && mixTarget <= 0.0F && !CaveMap.nearRoof() && night <= 0.0F) {
            found = new Light[0];
            return;
        }
        if (scanning == null && ticks - scannedAt >= SCAN_TICKS) {
            scannedAt = ticks;
            int cx = Mth.floor(eye.x);
            int cy = Mth.floor(eye.y);
            int cz = Mth.floor(eye.z);
            int radius = Math.round(p.radius());
            scanning = CompletableFuture.supplyAsync(() -> scan(level, cx, cy, cz, radius), ScanThread.EXECUTOR).exceptionally(t -> {
                OvergrownVegetation.LOGGER.warn("地下の光源を集められなかった", t);
                return null;
            });
        }
    }

    // ---- 地上の夜(11-06 光源 6。値は仮決め) ----

    /** 地上の夜の係数(0〜1。毎 tick {@link #nightFactor})。 */
    private static float night;
    /** 前のフレームに書いた夜の係数(0 に戻った後も 1 回は書いて、テクスチャに古い値を残さない)。 */
    private static float nightWritten;
    /** 夕方: dayTime 12500 で 0 → 13500 で 1。朝: 22500 で 1 → 23500 で 0(仮決め)。 */
    private static final float DUSK_FROM = 12500.0F;
    private static final float DUSK_TO = 13500.0F;
    private static final float DAWN_FROM = 22500.0F;
    private static final float DAWN_TO = 23500.0F;

    /** 空のある次元(地上)の時刻からの夜の係数。空の光の無い次元(ネザー・エンド)は 0。 */
    static float nightFactor(ClientLevel level) {
        if (!level.dimensionType().hasSkyLight()) {
            return 0.0F;
        }
        float t = Math.floorMod(level.getDayTime(), 24000L);
        float in = Mth.clamp((t - DUSK_FROM) / (DUSK_TO - DUSK_FROM), 0.0F, 1.0F);
        float out = 1.0F - Mth.clamp((t - DAWN_FROM) / (DAWN_TO - DAWN_FROM), 0.0F, 1.0F);
        return SunLight.smooth(Math.min(in, out));
    }

    /** 地上の夜の係数(確かめ用)。 */
    public static float night() {
        return night;
    }

    // ---- 炎のゆらぎ(11-06 光源 7。値は仮決め) ----

    /** ゆらぎの 2 つの周期(秒)と雑音の刻み(秒)。0.5 秒より速い成分は入れない(ちかちか防止)。 */
    private static final double FLICKER_A = 0.9;
    private static final double FLICKER_B = 2.3;
    private static final double FLICKER_NOISE = 0.7;
    /** 確かめ用: ゆらぎの強さの上書き(負で設計値)。 */
    static float devFlicker = -1.0F;

    /** 光源の強さに掛ける倍率(1 ± amp)。位相は座標から。sin 2 つ(0.6・0.4)と滑らかな雑音(0.3)の和を ±1 に収める。 */
    static float flicker(Light l, double sec, float amp) {
        if (!l.kind.flame() || amp <= 0.0F) {
            return 1.0F;
        }
        int h = Mth.murmurHash3Mixer((int) Math.floor(l.x) * 73856093 ^ (int) Math.floor(l.y) * 19349663 ^ (int) Math.floor(l.z) * 83492791);
        double pa = (h & 0xFFFF) / 65536.0 * Math.PI * 2.0;
        double pb = (h >>> 16 & 0xFFFF) / 65536.0 * Math.PI * 2.0;
        double wave = 0.6 * Math.sin(2.0 * Math.PI * sec / FLICKER_A + pa) + 0.4 * Math.sin(2.0 * Math.PI * sec / FLICKER_B + pb);
        // 雑音: FLICKER_NOISE 秒ごとの乱数を smoothstep でつなぐ(折れ目が無く、刻みより速い揺れを作らない)
        double u = sec / FLICKER_NOISE + (h & 0xFF) / 256.0;
        long k = (long) Math.floor(u);
        double f = u - k;
        f = f * f * (3.0 - 2.0 * f);
        double n0 = noise(h, k);
        double n1 = noise(h, k + 1);
        wave = (wave + 0.3 * (n0 + (n1 - n0) * f)) / 1.3;
        return (float) (1.0 + amp * Mth.clamp(wave, -1.0, 1.0));
    }

    /** −1〜1 の決まった乱数(光源 h と刻み k から)。 */
    private static double noise(int h, long k) {
        int v = Mth.murmurHash3Mixer(h ^ (int) (k * 0x9E3779B1L));
        return (v & 0xFFFF) / 32767.5 - 1.0;
    }

    /** 天井を見る所: {半径, 重さ}(ForestPresence の覆いと同じ並び)。真ん中 1 か所・半径 4 と 9 の 8 方向ずつ。 */
    private static final float[][] RINGS = {{0.0F, 3.0F}, {4.0F, 1.5F}, {9.0F, 1.0F}};
    private static final int[][] DIRS = {{1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}};

    /**
     * まわりの列のうち岩の天井({@link CaveMap#thickRoofAbove})がある割合(0〜1、真ん中ほど重く)。真上 1 列だけだと洞窟の口の 1 歩で 0 → 1 に跳んだ(11-01 撮影)ので、
     * 口から奥へ数ブロックかけて上がるようにまわりの 17 列で見る。
     */
    static float roofFraction(ClientLevel level, Vec3 eye) {
        float roofed = 0.0F;
        float total = 0.0F;
        for (float[] ring : RINGS) {
            for (int i = 0; i < (ring[0] == 0.0F ? 1 : DIRS.length); i++) {
                double len = DIRS[i][0] != 0 && DIRS[i][1] != 0 ? Math.sqrt(2.0) : 1.0;
                Vec3 at = new Vec3(eye.x + DIRS[i][0] * ring[0] / len, eye.y, eye.z + DIRS[i][1] * ring[0] / len);
                total += ring[1];
                // 11-02: 地下の地図と同じ数え方(3 段以上続く岩)。1 段の不透明なブロック(巨大樹の幹の上の土など)では天井にしない。
                if (CaveMap.thickRoofAbove(level, Mth.floor(at.x), Mth.floor(at.y) + 1, Mth.floor(at.z), 4096)) {
                    roofed += ring[1];
                }
            }
        }
        return total > 0.0F ? roofed / total : 0.0F;
    }

    /**
     * 地下モードの目標(岩の天井の下・空の光の暗さ)。11-07 ユーザー決定「洞窟のシェーダーも他と同じく過繁茂したバイオーム以外でも効くように」で
     * 過繁茂の地域の縛りを外した(世界全体の層。過繁茂かどうかは {@link #overgrownHere} に分け、洞窟の音だけが読む)。
     * 空の光の無い次元(ネザー・エンド)は 0 のまま({@link CaveMap#thickRoofAbove} はそこで常に天井なので、ここで切らないと全部が地下になる)。
     */
    private static float target(ClientLevel level, Vec3 eye, AtmosphereCave.CaveParams p) {
        if (!level.dimensionType().hasSkyLight()) {
            overgrownHere = false;
            return 0.0F;
        }
        BlockPos at = BlockPos.containing(eye);
        float sky = level.getBrightness(LightLayer.SKY, at);
        float dark = Mth.clamp((p.skyFrom() - sky) / (p.skyFrom() - p.skyTo()), 0.0F, 1.0F);
        // 11-07(天井に穴の開いた大きな洞窟): 17 列のうち穴の下の列は天井が無いので、前の式(割合 0.3〜0.9)では、空の光 0 の暗い所でも
        // 地下モードが半分ほどで止まり、霧が中途半端に掛かった。カメラの所が暗いほど、少ない天井で地下とみなす(暗さ 1 で割合 0.05〜0.35。仮決め)。
        float roofLo = devHalfRoof ? 0.3F : Mth.lerp(dark, 0.3F, 0.05F);
        float roofHi = devHalfRoof ? 0.9F : Mth.lerp(dark, 0.9F, 0.35F);
        float roof = SunLight.smooth(Mth.clamp((roofFraction(level, eye) - roofLo) / (roofHi - roofLo), 0.0F, 1.0F));
        // 11-09: 空に浮かぶ構造物・大きな張り出しの下(まわりが開けている列。CaveMap#markOverhangs)は地下にしない(前は黒い霧と洞窟の色・目の慣れが掛かった)
        if (devCamOverhang ? CaveMap.overhangAt(at.getX(), at.getZ()) || CaveMap.overhangShare(at.getX(), at.getZ(), 2) >= 0.5F
                : CaveMap.cameraBelowOverhang(at.getX(), at.getZ(), eye.y)) {
            roof = 0.0F; // まわり 5×5 の半分以上が印なら(柱のそば)も
        }
        boolean overgrown = ModBiomes.isOvergrownLike(level.getBiome(at));
        if (!overgrown) {
            int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, at.getX(), at.getZ()) - 1;
            overgrown = top > at.getY() && ModBiomes.isOvergrownLike(level.getBiome(new BlockPos(at.getX(), top, at.getZ())));
        }
        overgrownHere = overgrown;
        if (roof <= 0.0F || devOvergrownOnly && !overgrown) {
            return 0.0F;
        }
        return roof * SunLight.smooth(dark);
    }

    /**
     * 確かめ用: 真なら前の形(カメラの列が開けた張り出しの印なら、中でも地下モードにしない。11-09 の最初の直し。cavedev overhang cam on)。
     * 既定は偽: 構造物の<b>下</b>(浮いた岩の下面より下)にいるときだけ地下モードにしない({@link CaveMap#cameraBelowOverhang})。中で外扱いにすると、
     * 面が自分の空の光だけで地下かどうか決まり、葉の面が明るい緑と真っ暗にくっきり分かれた(ユーザーの写真 28〜30)。
     */
    static boolean devCamOverhang;
    /** 確かめ用(11-09 追記 7 のチカチカ): カメラの所の値の寄せる時間(秒。負で 0.3)・目標をまわり ±r ブロックの 5 点の平均にする r(負で設計値 camSpread、0 で 1 点)。 */
    static float devCamSeconds = -1.0F;
    static float devCamSpread = -1.0F;
    /** 確かめ用: 目標の計算にかかった時間の合計(ナノ秒)と回数(cavedev fogstate で出す・cavedev targetreset で 0 に戻す)。 */
    static long targetNanos;
    static long targetCount;
    /** 確かめ用(11-09 追記 9): 直近の 5 点の目標(カメラ・+x・−x・+z・−z)。 */
    static final float[] lastSamples = new float[5];

    /** 確かめ用(11-09 追記 9): カメラの位置で決まる値を一行に(cavedev camstate)。 */
    static String camState() {
        Light[] l = shown;
        float[] w = shownWeight;
        long h = 0L;
        float ws = 0.0F;
        for (int i = 0; i < l.length; i++) {
            h = h * 31 + (long) Math.floor(l[i].x()) * 73856093L + (long) Math.floor(l[i].y()) * 19349663L + (long) Math.floor(l[i].z()) * 83492791L;
            ws += i < w.length ? w[i] : 0.0F;
        }
        return String.format("CAMSTATE 場所 %.3f 目標 %.3f mix %.3f 5点 %.2f %.2f %.2f %.2f %.2f 光 %d 重み %.2f 組 %x 目 %.3f/%.3f 霧 %.1f〜%.1f",
                camLocal, mixTarget, mix, lastSamples[0], lastSamples[1], lastSamples[2], lastSamples[3], lastSamples[4], l.length, ws, h & 0xffffff,
                EyeAdaptation.glare(), EyeAdaptation.dark(), fogStart(), fogEnd()) + uniformState();
    }

    /** 確かめ用: 固い層のシェーダーに今入っている OvCaveLocal・OvCaveMouth・OvCave(Sodium はここから写す)。 */
    private static String uniformState() {
        ShaderInstance s = GameRenderer.getRendertypeSolidShader();
        if (s == null) {
            return " uniform -";
        }
        StringBuilder b = new StringBuilder();
        for (String n : new String[]{"OvCaveLocal", "OvCaveMouth", "OvCave"}) {
            var u = s.getUniform(n);
            if (u != null) {
                java.nio.FloatBuffer f = u.getFloatBuffer();
                b.append(String.format(" %s %.3f/%.3f/%.1f/%.1f", n, f.get(0), f.get(1), f.get(2), f.get(3)));
            }
        }
        return b.toString();
    }

    /** 目標をカメラと前後左右 r ブロックの 5 点の平均に(1 ブロック動いただけで跳ばないように)。overgrownHere はカメラの所の値を残す。 */
    private static float spreadTarget(ClientLevel level, Vec3 eye, AtmosphereCave.CaveParams p, float r) {
        float center = target(level, eye, p);
        lastSamples[0] = center;
        boolean here = overgrownHere;
        float sum = center;
        for (int i = 0; i < 4; i++) {
            Vec3 at = eye.add(i == 0 ? r : i == 1 ? -r : 0.0, 0.0, i == 2 ? r : i == 3 ? -r : 0.0);
            // 壁・岩の中の点は空の光が 0 で「地下」と読めるので、カメラの所の値で代える(崖や構造物の壁ぎわで外が地下寄りにならないように)
            BlockPos bp = BlockPos.containing(at);
            float v = level.getBlockState(bp).isSolidRender(level, bp) ? center : target(level, at, p);
            lastSamples[i + 1] = v;
            sum += v;
        }
        overgrownHere = here;
        return sum / 5.0F;
    }
    /** 構造物の中の葉の面のむらの案の確かめ用の上書き(11-09。cavedev foliage <効き> / cavedev overhanglight <点光源> <床の持ち上げ>。負 = 設計値 cave.foliageFacing など)。 */
    static float devFoliageFacing = -1.0F;
    static float devOverhangLights = -1.0F;
    static float devOverhangLift = -1.0F;

    /** カメラの所(か、その列の地表)が過繁茂の地域か({@link #target} が 0.5 秒おきに書く)。 */
    private static boolean overgrownHere;
    /** 確かめ用: 真なら前の形(過繁茂の地域の地下だけ)。devbridge の {@code wind cavedev worldwide off}(11-07)。 */
    static boolean devOvergrownOnly;
    /** 確かめ用: 真なら前の天井の割合の閾値(0.3〜0.9)。wind cavedev roof off(11-07 天井の穴)。 */
    static boolean devHalfRoof;
    /** 確かめ用: 真なら天井の穴の下の面も外の霞のまま(前の形)。wind cavedev cavern off(11-07)。 */
    static boolean devNoCavern;

    /**
     * 過繁茂の地域の地下の度合い(0〜1 = 地下モードの係数 × 過繁茂の地域か)。洞窟の音 {@code CaveSound} はこれで鳴る(11-07: 地下モードは世界全体へ広げたが、
     * 滴・根の軋みは過繁茂の洞窟の音として残す。水の音などと同じ扱い)。
     */
    public static float overgrownMix() {
        return overgrownHere ? mix : 0.0F;
    }


    // ---- 手に持った明かり(11-07 ユーザー決定、設計値 heldLights。値は仮決め) ----
    // たいまつ・ランタン・光る植物など光るブロックのアイテムを手(メインかオフ)に持つと、その人の手の所に点光源を 1 つ足す。地形のシェーダーの表の先頭と
    // 動く光(DynamicLights)に入る。見た目だけ(湧き・作物には効かない)。地下でも地上の夜でも効き、明るい所(空の光 × 昼)ほど弱める。

    /** 手に持った明かりの上限(表の先頭の枠)。 */
    static final int HELD_MAX = 4;
    /** このフレームの表の先頭のうち、手に持った明かりの数。 */
    private static volatile int shownHeld;
    private static boolean heldWritten;
    /** 手に持った明かりのゆらぎの位相(持ち主ごと)。 */
    private static final int[] heldSeeds = new int[HELD_MAX];

    public static int heldCount() {
        return shownHeld;
    }

    /** 光を出すブロックのアイテムなら、その種類(この MOD の光る植物も)。 */
    @Nullable
    private static Kind heldKind(net.minecraft.world.item.ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof net.minecraft.world.item.BlockItem bi)) {
            return null;
        }
        BlockState state = bi.getBlock().defaultBlockState();
        Kind k = lookup(state);
        if (k == null && state.getLightEmission() >= 5) {
            k = vanilla(state);
        }
        if (k == null && state.is(ModBlocks.GLOW_TASSEL.get())) {
            k = TASSEL;
        }
        return k;
    }

    /** このフレームの手に持った明かり(近い順、最大 {@link #HELD_MAX})。 */
    private static Light[] heldLights(RenderLevelStageEvent event, AtmosphereCave.CaveParams p) {
        var hp = DesignData.atmosphere().heldLights();
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (!hp.enabled() || devNoHeld || level == null || mc.player == null || VisualQuality.atmosphereTier() >= 2) {
            return new Light[0];
        }
        float partial = event.getPartialTick();
        Vec3 cam = event.getCamera().getPosition();
        float day = level.dimensionType().hasSkyLight() ? Mth.clamp((level.getSkyDarken(1.0F) - 0.2F) / 0.8F, 0.0F, 1.0F) : 0.0F;
        List<Light> out = new ArrayList<>();
        List<Integer> seeds = new ArrayList<>();
        for (net.minecraft.world.entity.player.Player pl : level.players()) {
            boolean self = pl == mc.player;
            if (!self && (!hp.otherPlayers() || pl.distanceToSqr(cam) > (double) hp.otherRange() * hp.otherRange() || pl.isInvisible())) {
                continue;
            }
            Kind k = heldKind(pl.getMainHandItem());
            if (k == null) {
                k = heldKind(pl.getOffhandItem());
            }
            if (k == null) {
                continue;
            }
            // 手の所: 目の 0.45 下・見ている向きへ水平に 0.35
            Vec3 at = pl.getPosition(partial);
            Vec3 look = pl.getViewVector(partial);
            double hl = Math.sqrt(look.x * look.x + look.z * look.z);
            double fx = hl > 1.0E-4 ? look.x / hl * 0.35 : 0.0;
            double fz = hl > 1.0E-4 ? look.z / hl * 0.35 : 0.0;
            double y = at.y + pl.getEyeHeight() - 0.45;
            BlockPos bp = BlockPos.containing(at.x, y, at.z);
            // 明るい所ほど弱く: 空の光 × 昼の明るさ(地下・夜は 1)
            float sky = level.getBrightness(LightLayer.SKY, bp) / 15.0F;
            float dark = 1.0F - Mth.clamp(sky * day, 0.0F, 1.0F) * hp.daylightCut();
            float strength = k.strength() * hp.strength() * dark;
            if (strength < 0.01F) {
                continue;
            }
            Kind hk = new Kind(k.radius() * hp.radiusScale(), k.r(), k.g(), k.b(), strength, false, k.family(), k.flame());
            out.add(new Light(at.x + fx, y, at.z + fz, hk));
            seeds.add(pl.getId());
        }
        // 近い順に上限まで(自分は距離 0 なので先頭)
        Integer[] idx = new Integer[out.size()];
        for (int i = 0; i < idx.length; i++) {
            idx[i] = i;
        }
        Arrays.sort(idx, (a, b) -> Double.compare(dist2(out.get(a), cam), dist2(out.get(b), cam)));
        int n = Math.min(HELD_MAX, idx.length);
        Light[] res = new Light[n];
        for (int i = 0; i < n; i++) {
            res[i] = out.get(idx[i]);
            heldSeeds[i] = seeds.get(idx[i]);
        }
        return res;
    }

    /** 手に持った明かりの炎のゆらぎ(持ち主ごとの決まった位相。位置で決めると歩くたびに位相が跳ぶ)。 */
    private static float heldFlicker(Light l, int seed, double sec, float amp) {
        if (!l.kind.flame() || amp <= 0.0F) {
            return 1.0F;
        }
        int h = Mth.murmurHash3Mixer(seed * 0x9E3779B1);
        double pa = (h & 0xFFFF) / 65536.0 * Math.PI * 2.0;
        double pb = (h >>> 16 & 0xFFFF) / 65536.0 * Math.PI * 2.0;
        double wave = 0.6 * Math.sin(2.0 * Math.PI * sec / FLICKER_A + pa) + 0.4 * Math.sin(2.0 * Math.PI * sec / FLICKER_B + pb);
        double u = sec / FLICKER_NOISE + (h & 0xFF) / 256.0;
        long k = (long) Math.floor(u);
        double f = u - k;
        f = f * f * (3.0 - 2.0 * f);
        wave = (wave + 0.3 * (noise(h, k) + (noise(h, k + 1) - noise(h, k)) * f)) / 1.3;
        return (float) (1.0 + amp * Mth.clamp(wave, -1.0, 1.0));
    }

    /** 確かめ用: 手に持った明かりを切る(wind cavedev held off)。 */
    static boolean devNoHeld;
    // ---- カメラ切り替えの廃止(11-07 ユーザー決定。地下モードの見た目を場所ごとに決める) ----

    /** カメラの所の場所の値(target を 0.3 秒で寄せたもの。4 秒の mix とは別)。 */
    private static float camLocal;
    /** カメラが洞窟の奥にいる度合い(0〜1)。 */
    private static float camDeep;

    /** DH の遠景の霧の色を洞窟の霧の色へ寄せる割合(地下モード × 洞窟の奥。DH が無ければ 0)。 */
    public static float dhCaveColor() {
        return DistantHorizonsFog.loaded() && !devNoLocal ? camLocal * camDeep : 0.0F;
    }
    /** 確かめ用: 真なら前の形(カメラの所の mix で画面全体を切り替える)。wind cavedev local off。 */
    static boolean devNoLocal;

    /**
     * {@code MistView} が地形の霧(FOG_TERRAIN、雲の間を除く)を決めたときに呼ぶ: 地下モードの黒い霧を入れる前の地上の霧の始まり・終わり・色を地形のシェーダーへ渡し、
     * 場所ごとの判定を立てる。地形は地上の霧で描いてから、視線が洞窟の空気を通る割合で黒い霧を掛ける({@code ov_cave.glsl} の ovCaveFogLocal)。
     * バニラの霧はカメラの所の値の黒い霧のまま(Mob・粒・水の外の物が読む)。
     */
    public static void setSurfaceFog(float start, float end, float r, float g, float b) {
        if (devNoLocal || !VisualQuality.caveLight() || !CaveMap.ready()) {
            return;
        }
        ShaderInstance[] shaders = {GameRenderer.getRendertypeSolidShader(), GameRenderer.getRendertypeCutoutShader(),
                GameRenderer.getRendertypeCutoutMippedShader(), GameRenderer.getRendertypeTranslucentShader()};
        for (ShaderInstance shader : shaders) {
            if (shader == null) {
                continue;
            }
            shader.safeGetUniform("OvCaveLocal").set(1.0F, camLocal, start, end);
            // w = 1: 軽い形(画質の段 LOW。天井の穴の下の面の判定 ovCaveCavern の 16 か所の読みを省く)
            shader.safeGetUniform("OvCaveSurfColor").set(r, g, b, VisualQuality.atmosphereTier() >= 2 ? 1.0F : 0.0F);
        }
    }

    /** カメラの所の場所の値(確かめ用)。 */
    static float camLocal() {
        return camLocal;
    }

    /**
     * 11-08 Q1: バニラの霧(Mob・粒・水の外の物が読む)に入れる黒い霧の割合。場所ごとの判定が効いている間は地形と同じ 0.3 秒でならした
     * camLocal(洞窟を出た直後に外の Mob だけ数秒黒い霧が残っていた)。効いていなければ前のまま 4 秒の mix。
     */
    public static float fogMix() {
        return devNoLocal || devMobFogMix || !CaveMap.ready() ? mix : camLocal;
    }
    /** 確かめ用: 真なら Mob・粒の黒い霧を前の形(4 秒の mix)に戻す。wind cavedev mobfog off。 */
    static boolean devMobFogMix;

    // ---- 過繁茂の外の洞窟(11-07 判断の後の直し、設計値 caveOpen。値はすべて仮決め) ----

    /** 過繁茂の度合い(0 = 過繁茂の外の洞窟の値、1 = 前のまま)。カメラの所の判定を blendSeconds で寄せる(境目で跳ばない)。 */
    private static float ogBlend = Float.NaN;

    private static void blendOpen(Vec3 eye) {
        var o = DesignData.atmosphere().caveOpen();
        float goal = devOvergrownOnly || overgrownHere ? 1.0F : 0.0F;
        ogBlend = Float.isNaN(ogBlend) ? goal : ForestPresence.approach(ogBlend, goal, 0.05F, o.blendSeconds());
    }

    /** 過繁茂の外の分(0〜1)。 */
    public static float openShare() {
        return devNoOpen || Float.isNaN(ogBlend) ? 0.0F : 1.0F - ogBlend;
    }

    /** 黒い霧の始まり・終わり(過繁茂の外では fogScale 倍)。 */
    public static float fogStart() {
        return DesignData.atmosphere().cave().fogStart() * Mth.lerp(openShare(), 1.0F, DesignData.atmosphere().caveOpen().fogScale());
    }

    public static float fogEnd() {
        return DesignData.atmosphere().cave().fogEnd() * Mth.lerp(openShare(), 1.0F, DesignData.atmosphere().caveOpen().fogScale());
    }

    /** 黒い霧の色(0 = r、1 = g、2 = b。過繁茂の外では fogDarken 倍)。 */
    public static float fogColor(int i) {
        var p = DesignData.atmosphere().cave();
        float c = i == 0 ? p.r() : i == 1 ? p.g() : p.b();
        return c * Mth.lerp(openShare(), 1.0F, DesignData.atmosphere().caveOpen().fogDarken());
    }

    /** 点光源の足し分(過繁茂の外では lightGain 倍)。 */
    public static float lightGain() {
        return DesignData.atmosphere().cave().lightGain() * Mth.lerp(openShare(), 1.0F, DesignData.atmosphere().caveOpen().lightGain());
    }

    /** 床の持ち上げの色(過繁茂の外では灰色寄り)。 */
    static float[] ambientColor() {
        float[] a = DesignData.atmosphere().cave().ambientColor();
        float[] b = DesignData.atmosphere().caveOpen().ambientColor();
        float s = openShare();
        return new float[]{Mth.lerp(s, a[0], b[0]), Mth.lerp(s, a[1], b[1]), Mth.lerp(s, a[2], b[2])};
    }

    /** 確かめ用: 過繁茂の外の洞窟の値を使わない(判断の前の形。wind cavedev open off)。 */
    static boolean devNoOpen;

    /** 確かめ用: 最後の光源集めにかかった時間(µs、作業スレッド。11-07 世界全体へ広げた重さの確かめ)。 */
    private static volatile long scanMicros;

    /** 作業スレッド: カメラのまわりの区画から光源を集める(パレットに光源が無い区画は中を読まない)。 */
    private static Light[] scan(ClientLevel level, int cx, int cy, int cz, int radius) {
        long t0 = System.nanoTime();
        List<Light> out = new ArrayList<>();
        int r2 = radius * radius;
        int minSx = SectionPos.blockToSectionCoord(cx - radius);
        int maxSx = SectionPos.blockToSectionCoord(cx + radius);
        int minSz = SectionPos.blockToSectionCoord(cz - radius);
        int maxSz = SectionPos.blockToSectionCoord(cz + radius);
        int minSy = Math.max(level.getMinSection(), SectionPos.blockToSectionCoord(cy - radius));
        int maxSy = Math.min(level.getMaxSection() - 1, SectionPos.blockToSectionCoord(cy + radius));
        for (int sx = minSx; sx <= maxSx; sx++) {
            for (int sz = minSz; sz <= maxSz; sz++) {
                LevelChunk chunk = level.getChunkSource().getChunk(sx, sz, false);
                if (chunk == null) {
                    continue;
                }
                LevelChunkSection[] sections = chunk.getSections();
                for (int sy = minSy; sy <= maxSy; sy++) {
                    LevelChunkSection section = sections[level.getSectionIndexFromSectionY(sy)];
                    if (section == null || section.hasOnlyAir() || !section.maybeHas(s -> lookup(s) != null)) {
                        continue;
                    }
                    int bx = SectionPos.sectionToBlockCoord(sx);
                    int by = SectionPos.sectionToBlockCoord(sy);
                    int bz = SectionPos.sectionToBlockCoord(sz);
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            for (int x = 0; x < 16; x++) {
                                BlockState state = section.getBlockState(x, y, z);
                                Kind kind = lookup(state);
                                if (kind == null) {
                                    continue;
                                }
                                int wx = bx + x;
                                int wy = by + y;
                                int wz = bz + z;
                                int dx = wx - cx;
                                int dy = wy - cy;
                                int dz = wz - cz;
                                if (dx * dx + dy * dy + dz * dz > r2) {
                                    continue;
                                }
                                // 光る根の網は 1 枚 30 マスほどあるので、座標のハッシュで 4 マスに 1 つだけ光源にする(近い順 24 の枠を網が食わない。11-03 仮決め)。
                                if (kind == NET && Math.floorMod(wx * 7 + wz * 13 + wy * 3, NET_EVERY) != 0) {
                                    continue;
                                }
                                // 溶岩は面が多いので、上が溶岩でない面だけを、網と同じ割合で間引いて光源にする(ほかは thin で 5 ブロックごとに 1 つ。11-04 仮決め)
                                if (state.is(net.minecraft.world.level.block.Blocks.LAVA)
                                        && (Math.floorMod(wx * 7 + wz * 13 + wy * 3, NET_EVERY) != 0
                                        || y < 15 && section.getBlockState(x, y + 1, z).is(net.minecraft.world.level.block.Blocks.LAVA))) {
                                    continue;
                                }
                                out.add(new Light(wx + 0.5, wy + 0.5, wz + 0.5, kind));
                            }
                        }
                    }
                }
            }
        }
        Light[] result = thin(out).toArray(new Light[0]);
        scanMicros = (System.nanoTime() - t0) / 1000L;
        return result;
    }

    /**
     * 同じ種類の光源が {@link #spacing} より近くに並ぶときは 1 つにまとめる(11-03 地下の植生 1: 光源 A の房の先は 1 か所に 1〜3 本、地脈の根は縦に 2〜4 段続くので、
     * 1 マスごとに光源にすると強さが本数だけ重なって洞窟全体が明るく飛んだ)。
     */
    static List<Light> thin(List<Light> all) {
        List<Light> kept = new ArrayList<>(all.size());
        for (Light l : all) {
            double s = spacing(l.kind);
            boolean near = false;
            if (s > 0) {
                for (Light k : kept) {
                    if (k.kind == l.kind && sqDist(k, l) < s * s) {
                        near = true;
                        break;
                    }
                }
            }
            if (!near) {
                kept.add(l);
            }
        }
        return kept;
    }

    /** まとめる距離(仮決め: 房の先 6・地脈の根 4・狐火茸 3・炎の系統の発光 15(溶岩・火)5。網はハッシュで間引き済み、蔓・ほかのバニラの光源は前のまま)。 */
    private static double spacing(Kind k) {
        // 11-07: 光る地衣類は壁一面に広がるので 4 ブロックにまとめる(仮決め)
        return k == TASSEL ? 6.0 : k == LEY_NEAR || k == LEY_FAR ? 4.0 : k == FOXFIRE ? 3.0 : isLavaKind(k) ? 5.0 : k.family() == 4 && !devOvergrownOnly ? 4.0 : 0.0;
    }

    private static double sqDist(Light a, Light b) {
        double dx = a.x - b.x;
        double dy = a.y - b.y;
        double dz = a.z - b.z;
        return dx * dx + dy * dy + dz * dz;
    }

    /** 近い順に {@code max} 個(カメラからの距離)。 */
    static Light[] nearest(Light[] all, Vec3 cam, int max) {
        if (all.length == 0 || max <= 0) {
            return new Light[0];
        }
        Light[] sorted = all.clone();
        // 距離を光の半径で割った順(11-03: 房の先 r22 が網 r6・蔓 r6 の群れに押し出されないように)
        Arrays.sort(sorted, (a, b) -> Double.compare(dist2(a, cam) / sq(a.kind.radius()), dist2(b, cam) / sq(b.kind.radius())));
        return sorted.length <= max ? sorted : Arrays.copyOf(sorted, max);
    }

    private static double sq(float v) {
        return (double) v * v;
    }

    private static double dist2(Light l, Vec3 c) {
        double dx = l.x - c.x;
        double dy = l.y - c.y;
        double dz = l.z - c.z;
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * 1 つの光源が距離 d に届ける強さ(色を掛ける前。ov_cave.glsl と同じ式)。{@code falloff} = 半分の明るさになる距離 ÷ 半径
     * (11-04 地下の光の直し 7: 前は 1 = 房の先 r22 で真下 0.98・12 ブロック先 0.77 と差がほぼ無く、洞窟が一様な明るさだった)。
     */
    static float reach(Kind k, double d, float falloff) {
        double q = d / (k.radius() * falloff);
        double fade = 1.0 - SunLight.smooth((float) Mth.clamp((d - k.radius()) / (0.5 * k.radius()), 0.0, 1.0));
        return (float) (k.strength() / (1.0 + q * q) * fade);
    }

    /** {@code (x, y, z)} に届く光の強さの和(地下モードの係数込み。Mob の明るさ)。 */
    public static float intensityAt(double x, double y, double z) {
        Light[] lights = shown;
        float[] wts = shownWeight;
        if (lights.length == 0 || mix <= 0.0F && shownHeld == 0) {
            return 0.0F;
        }
        float sum = 0.0F;
        float heldSum = 0.0F;
        float fo = falloff(DesignData.atmosphere().cave());
        for (int i = 0; i < lights.length; i++) {
            Light l = lights[i];
            float lw = i < wts.length ? wts[i] : 1.0F;
            boolean isHeld = i < shownHeld;
            double dx = x - l.x;
            double dy = y - l.y;
            double dz = z - l.z;
            double d2 = dx * dx + dy * dy + dz * dz;
            double reach = 1.5 * l.kind.radius();
            if (d2 >= reach * reach) {
                continue;
            }
            float add = reach(l.kind, Math.sqrt(d2), fo) * Math.max(l.kind.r, Math.max(l.kind.g, l.kind.b)) * lw;
            // 手に持った明かりは地下モードの係数を掛けない(地上の夜にも効く。11-07)
            if (isHeld) {
                heldSum += add;
            } else {
                sum += add;
            }
        }
        return sum * mix + heldSum;
    }

    // ---- 表の出入りをなめらかに(11-06 ユーザー「近づいたら初めて植物が光り始める」。値は仮決め) ----
    // 光源は百個近く集まるが、シェーダーへは近い順に 24 個しか渡せない。前は表に入った光源がその瞬間に全部の強さで周りを照らし、
    // 光る画素も表の中の光源にしか付かなかったので、歩くと光源がぽんと灯った。

    /** 表に入った・出た光源の強さを寄せる時間(秒。0 → 1 をこの時間で)。 */
    static final float FADE_SECONDS = 0.7F;
    /** 上限の境の手前で重みを落とし始める割合(順位の鍵 = 距離 ÷ 半径 が、表の 1 つ外の鍵のこの割合から境まで 1 → 0)。 */
    static final float RANK_FADE_FROM = 0.75F;
    /** 集める半径の端で重みを落とし始める割合。 */
    static final float EDGE_FADE_FROM = 0.75F;
    /** 光る画素の一覧(この MOD の光源だけ、近い順。表とは別。テクスチャの 4〜6 行目)。 */
    static final int GLOW_MAX = 72;
    private static final int GLOW_ROWS = 3;
    private static final int ROWS = 3 + GLOW_ROWS;

    /** 今の重み(表に載っている光源と、出て行く途中の光源)。 */
    private static final java.util.LinkedHashMap<Light, float[]> fading = new java.util.LinkedHashMap<>();
    /** {@link #shown} と同じ並びの重み。 */
    private static volatile float[] shownWeight = new float[0];
    private static long lastFadeNanos;
    /** 確かめ用: 真なら前の形(重み 1・すぐ入れ替え・光る画素は表の中の光源だけ)。devbridge の wind cavedev fade off。 */
    static boolean devNoFade;
    /** 確かめ用: 1 秒あたりに表へ入った光源の数(直近)。 */
    private static int enteredCount;
    private static int leftCount;

    /** 順位の鍵(距離 ÷ 半径)。 */
    private static double key(Light l, Vec3 cam) {
        return Math.sqrt(dist2(l, cam)) / l.kind.radius();
    }

    /** 表に載る光源の目標の重み: 上限の境(鍵 cut)の手前と、集める半径の端で 0 へ。 */
    private static float goalWeight(Light l, Vec3 cam, double cut, float radius) {
        float w = 1.0F;
        if (Double.isFinite(cut)) {
            w = 1.0F - SunLight.smooth((float) Mth.clamp((key(l, cam) - RANK_FADE_FROM * cut) / ((1.0 - RANK_FADE_FROM) * cut), 0.0, 1.0));
        }
        double d = Math.sqrt(dist2(l, cam));
        return w * (1.0F - SunLight.smooth((float) Mth.clamp((d - EDGE_FADE_FROM * radius) / ((1.0 - EDGE_FADE_FROM) * radius), 0.0, 1.0)));
    }

    /**
     * 目標の表へ今の重みを寄せ、渡す光源(重みの大きい順に最大 {@value #MAX} 個)を返す({@link #shownWeight} も書く)。
     * 新しく入った光源は 0 から、出た光源は今の重みから {@link #FADE_SECONDS} かけて寄せる。枠が足りないときは出て行く途中の光源を先に残し、
     * 入ってくる光源は枠が空くまで 0 のまま待つ(待っている間に強くなって、入った瞬間に灯らないように)。
     */
    private static Light[] fade(Light[] goals, float[] goalWeights, int cap) {
        long now = System.nanoTime();
        float dt = lastFadeNanos == 0L ? 0.0F : Mth.clamp((now - lastFadeNanos) / 1.0E9F, 0.0F, 0.1F);
        lastFadeNanos = now;
        float step = devNoFade ? 1.0F : dt / FADE_SECONDS;
        java.util.HashMap<Light, Float> goal = new java.util.HashMap<>(goals.length * 2);
        for (int i = 0; i < goals.length; i++) {
            if (goalWeights[i] <= 0.0F) {
                continue;
            }
            goal.put(goals[i], goalWeights[i]);
            if (!fading.containsKey(goals[i])) {
                fading.put(goals[i], new float[]{0.0F, 0.0F});
                enteredCount++;
            }
        }
        // [0] = 今の重み、[1] = 今の目標
        List<java.util.Map.Entry<Light, float[]>> list = new ArrayList<>(fading.entrySet());
        for (java.util.Map.Entry<Light, float[]> e : list) {
            float[] w = e.getValue();
            w[1] = goal.getOrDefault(e.getKey(), 0.0F);
        }
        list.sort((a, b) -> Float.compare(b.getValue()[0], a.getValue()[0]));
        int n = 0;
        Light[] out = new Light[Math.max(0, Math.min(cap, list.size()))];
        float[] weights = new float[out.length];
        for (java.util.Map.Entry<Light, float[]> e : list) {
            float[] w = e.getValue();
            boolean room = n < cap;
            if (room) {
                w[0] = w[0] < w[1] ? Math.min(w[1], w[0] + step) : Math.max(w[1], w[0] - step);
            } else {
                w[0] = 0.0F;
            }
            if (w[0] <= 0.0F && w[1] <= 0.0F) {
                fading.remove(e.getKey());
                leftCount++;
                continue;
            }
            if (room && w[0] > 0.0F) {
                out[n] = e.getKey();
                weights[n] = w[0];
                n++;
            }
        }
        shownWeight = Arrays.copyOf(weights, n);
        return Arrays.copyOf(out, n);
    }

    /** 光る画素の一覧: この MOD の光源を近い順に {@value #GLOW_MAX} 個まで、重み = 一覧の境と集める半径の端で 0 へ。 */
    private static Light[] glowLights = new Light[0];
    private static float[] glowWeights = new float[0];

    private static int glowList(Vec3 cam, float radius) {
        Light[] all = devNoFade ? shown : found;
        List<Light> own = new ArrayList<>();
        for (Light l : all) {
            if (!l.kind.vanilla()) {
                own.add(l);
            }
        }
        own.sort((a, b) -> Double.compare(dist2(a, cam), dist2(b, cam)));
        int n = Math.min(GLOW_MAX, own.size());
        double cut = own.size() > GLOW_MAX ? Math.sqrt(dist2(own.get(GLOW_MAX), cam)) : Double.POSITIVE_INFINITY;
        glowLights = own.subList(0, n).toArray(new Light[0]);
        glowWeights = new float[n];
        for (int i = 0; i < n; i++) {
            double d = Math.sqrt(dist2(glowLights[i], cam));
            float w = Double.isFinite(cut) ? 1.0F - SunLight.smooth((float) Mth.clamp((d - RANK_FADE_FROM * cut) / ((1.0 - RANK_FADE_FROM) * cut), 0.0, 1.0)) : 1.0F;
            glowWeights[i] = w * (1.0F - SunLight.smooth((float) Mth.clamp((d - EDGE_FADE_FROM * radius) / ((1.0 - EDGE_FADE_FROM) * radius), 0.0, 1.0)));
        }
        return n;
    }

    /** 地形シェーダーへ渡す(AFTER_SKY、{@link Atmosphere} から)。{@code on} が偽・地下でないときは 0 個。 */
    static void upload(RenderLevelStageEvent event, boolean on) {
        ShaderInstance[] shaders = {GameRenderer.getRendertypeSolidShader(), GameRenderer.getRendertypeCutoutShader(),
                GameRenderer.getRendertypeCutoutMippedShader(), GameRenderer.getRendertypeTranslucentShader()};
        AtmosphereCave.CaveParams p = DesignData.atmosphere().cave();
        Light[] pick = new Light[0];
        Vec3 cam = event.getCamera().getPosition();
        boolean mapOn = on && VisualQuality.caveLight();
        if (mapOn) {
            CaveMap.upload();
        }
        boolean useMap = mapOn && CaveMap.ready();
        boolean cave = mix > 0.0F || useMap && CaveMap.nearRoof();
        // 11-06 光源 6: 地上の夜は近い surfaceLights 個まで(画面じゅうの断片がループを回るので地下より少なく)
        float nightNow = on && VisualQuality.caveLight() ? night : 0.0F;
        Light[] goals = new Light[0];
        float[] goalWeights = new float[0];
        if (on && VisualQuality.caveLight() && (cave || nightNow > 0.0F)) {
            int limit = Math.min(MAX - HELD_MAX, devMaxLights >= 0 ? devMaxLights : cave ? p.maxLights() : Math.min(p.maxLights(), p.surfaceLights()));
            // 11-06(光が急に灯る): 上限の 1 つ外まで取り、上限の境と集める半径の端へ向けて重みを滑らかに 0 へ落とす
            Light[] sorted = nearest(found, cam, limit + 1);
            double cut = sorted.length > limit ? key(sorted[limit], cam) : Double.POSITIVE_INFINITY;
            goals = sorted.length > limit ? Arrays.copyOf(sorted, limit) : sorted;
            goalWeights = new float[goals.length];
            for (int i = 0; i < goals.length; i++) {
                goalWeights[i] = devNoFade ? 1.0F : goalWeight(goals[i], cam, cut, p.radius());
            }
        }
        if (!(on && VisualQuality.caveLight())) {
            fading.clear();
        }
        // 11-07 手に持った明かり: 表の先頭に毎フレーム入れる(寄せない)。地形の光源はその分だけ枠を減らす
        Light[] held = on && VisualQuality.caveLight() ? heldLights(event, p) : new Light[0];
        pick = fade(goals, goalWeights, MAX - held.length);
        if (held.length > 0) {
            Light[] all = new Light[held.length + pick.length];
            float[] w = new float[all.length];
            System.arraycopy(held, 0, all, 0, held.length);
            System.arraycopy(pick, 0, all, held.length, pick.length);
            Arrays.fill(w, 0, held.length, 1.0F);
            System.arraycopy(shownWeight, 0, w, held.length, pick.length);
            pick = all;
            shownWeight = w;
        }
        shownHeld = held.length;
        shown = pick;
        // 光源が 0 個でも、地下モードの間は床の持ち上げ(ambient)と飽和の抑え(clipKnee)を渡す(11-01 夜)。
        float shaderMix = on && VisualQuality.caveLight() ? mix : 0.0F;
        if (shaderMix > 0.0F || useMap || nightNow > 0.0F || nightWritten > 0.0F || shownHeld > 0 || heldWritten) {
            heldWritten = shownHeld > 0;
            write(pick, shownWeight, glowList(cam, p.radius()), cam, p, nightNow);
        }
        float mapX = useMap ? (float) (CaveMap.originX() - cam.x) : 0.0F;
        float mapZ = useMap ? (float) (CaveMap.originZ() - cam.z) : 0.0F;
        boolean inFluid = event.getCamera().getFluidInCamera() != net.minecraft.world.level.material.FogType.NONE;
        float[] surf = MistFog.sporeColor();
        // 11-07 世界全体へ: 森の胞子の霞は過繁茂の地域の洞窟だけ(普通の洞窟の口の外に黄緑の霞を掛けない)。過繁茂の判定は前の地下モードの縛りと同じ
        // overgrownHere(カメラの所か列の地表。地下のバイオームがバニラのままの過繁茂の洞窟も入る)を優先し、覆いの度合いで補う。確かめ用の前の形では常に掛ける。
        float surfK = DesignData.atmosphere().fog().forestFogDensity() * (devOvergrownOnly || overgrownHere ? 1.0F : ForestPresence.overgrown());
        for (ShaderInstance shader : shaders) {
            if (shader == null) {
                continue;
            }
            if (texture != 0) {
                shader.setSampler("OvCaveLights", texture);
            }
            shader.safeGetUniform("OvCave").set((float) pick.length, shaderMix, lightGain(), p.glowThreshold());
            if (useMap) {
                shader.setSampler("OvCaveMap", CaveMap.texture());
            }
            // w: 0 = 地図なし、1 = 地図あり、2 = 地図にまわりの過繁茂の列がある(地図の外の空の光の無い断片を洞窟とみなす。11-04)
            shader.safeGetUniform("OvCaveMapA").set(mapX, mapZ, (float) -cam.y, useMap ? (CaveMap.nearRoof() ? 2.0F : 1.0F) : 0.0F);
            shader.safeGetUniform("OvCaveMapB").set(p.roofFrom(), p.roofTo(), p.skyFrom() / 15.0F, p.skyTo() / 15.0F);
            shader.safeGetUniform("OvCaveFog").set(fogColor(0), fogColor(1), fogColor(2), 0.0F);
            // z: カメラが水(など液体)の中なら 1。水中の霧が地下の霧に勝つ(11-01 ユーザー: 海から崖の洞窟を見ると中だけ澄んでいた。
            // 断片の所の地下の霧 20〜28 が、水の霧を掛けた色を差し替えていた)。
            shader.safeGetUniform("OvCaveFogD").set(fogStart(), fogEnd(), inFluid ? 1.0F : 0.0F, (devTintSink ? 2.0F : devTint ? 1.0F : 0.0F) + (devNoCavern ? 10.0F : 0.0F));
            // 洞窟の中から見た口の外: カメラの黒い霧を外した代わりに森の胞子の霞(地上の霧と同じ色・濃さ)を距離で掛ける。
            // 11-16: 外に掛ける霞は、シェーダーの側で OV_CAVE_OUTSIDE_HAZE(ov_cave.glsl)を掛けて薄くする(洞窟から見た外が平らな黄緑の塊になった)。
            shader.safeGetUniform("OvCaveSurf").set(surf[0], surf[1], surf[2], surfK);
            // カメラが外にいるとき、洞窟の中を口からの距離で黒へ沈める(11-04 地下の光の直し 12。前はカメラからの距離で、遠くから覗くと奥がまるごと黒だった)。
            float mFrom = devMouthFrom >= 0.0F ? devMouthFrom : p.mouthFrom();
            float mTo = devMouthTo > 0.0F ? Math.max(mFrom + 0.5F, devMouthTo) : p.mouthTo();
            // w(11-05 光の点検 20): なめらかにする前の地下モードの目標。シェーダーは洞窟から見た外の霞(toSurface)を min(mix, 目標) で掛ける。
            // 11-07: 場所ごとの判定は MistView が地形の霧を決めたとき(setSurfaceFog)に立てる。ここでは毎フレーム下ろす(水の中・霧を切った時は前の形)。
            shader.safeGetUniform("OvCaveLocal").set(0.0F, camLocal, 0.0F, 1000.0F);
            shader.safeGetUniform("OvCaveMouth").set(mFrom, mTo, devLightSink >= 0.0F ? devLightSink : p.mouthLightSink(), shaderMix > 0.0F ? mixTarget : 0.0F);
        }
    }

    /**
     * 25×2 の浮動小数のテクスチャへ書く(1 行目 = カメラからの位置 + 半径、2 行目 = 色 + 強さ)。25 列目の 1 行目は光源でなく
     * (床の持ち上げの色 × ambient、飽和の抑え clipKnee)、2 行目は(持ち上げの彩度の抜き、抑えた所の彩度の抜き、0、0)(11-01 夜。uniform の OvCave は 4 つとも使っているので、ここに相乗りさせた)。
     */
    private static void write(Light[] lights, float[] weights, int glowCount, Vec3 cam, AtmosphereCave.CaveParams p, float nightNow) {
        if (buffer == null) {
            buffer = MemoryUtil.memAllocFloat(COLS * ROWS * 4);
        }
        nightWritten = nightNow;
        // 炎のゆらぎ(11-06 光源 7): 光源ごとの強さの倍率。毎フレーム書くので、ここで時間を入れる
        double sec = (net.minecraft.Util.getMillis() % 3_600_000L) / 1000.0;
        float amp = devFlicker >= 0.0F ? devFlicker : p.flameFlicker();
        float[] flick = new float[lights.length];
        for (int i = 0; i < lights.length; i++) {
            // 手に持った明かり(先頭 shownHeld 個)は位置が毎フレーム動くので、持ち主ごとの決まった位相でゆらす
            flick[i] = i < shownHeld ? heldFlicker(lights[i], heldSeeds[i], sec, amp) : flicker(lights[i], sec, amp);
        }
        buffer.clear();
        for (int i = 0; i < MAX; i++) {
            if (i < lights.length) {
                Light l = lights[i];
                buffer.put((float) (l.x - cam.x)).put((float) (l.y - cam.y)).put((float) (l.z - cam.z)).put(l.kind.vanilla() ? -l.kind.radius() : l.kind.radius());
            } else {
                buffer.put(0.0F).put(-10000.0F).put(0.0F).put(1.0F);
            }
        }
        float[] ac = ambientColor();
        float amb = ambient();
        buffer.put(ac[0] * amb).put(ac[1] * amb).put(ac[2] * amb).put(p.clipKnee());
        for (int i = 0; i < MAX; i++) {
            if (i < lights.length) {
                Kind k = lights[i].kind;
                // 11-01 夜(見え方の直し 3): 光の色を白へ寄せる(飽和した面がライム一色でなく白っぽい緑になる)。表の色(仕様)はそのまま。
                float w = p.lightWhiten();
                buffer.put(Mth.lerp(w, k.r, 1.0F)).put(Mth.lerp(w, k.g, 1.0F)).put(Mth.lerp(w, k.b, 1.0F)).put(k.strength() * flick[i] * (i < weights.length ? weights[i] : 1.0F));
            } else {
                buffer.put(0.0F).put(0.0F).put(0.0F).put(0.0F);
            }
        }
        // z = 点光源の減り方(半分になる距離 ÷ 半径、11-04 地下の光の直し 7)
        // w = 地上の夜に面の色を光源へ寄せる強さ(夜の係数 × surfaceTint。11-06 光源 6)
        buffer.put(p.ambientDesaturate()).put(p.clipDesaturate()).put(falloff(p)).put(nightNow * p.surfaceTint());
        // 3 行目(11-06 光の点検 10): 光源の列 = (色の系統 0〜3・この MOD は −1、ゆらぎの倍率、炎なら 1、0)(11-06 光源 7)、
        // 25 列目 = 洞窟の水(底を水深で暗くする割合、その水深、水面の鏡面の強さ、鋭さ)。
        for (int i = 0; i < MAX; i++) {
            if (i < lights.length) {
                Kind k = lights[i].kind;
                buffer.put((float) k.family()).put(flick[i]).put(k.flame() ? 1.0F : 0.0F).put(0.0F);
            } else {
                buffer.put(0.0F).put(1.0F).put(0.0F).put(0.0F);
            }
        }
        buffer.put(p.waterDepthDarken()).put(p.waterDepthFold()).put(p.waterSpecular()).put(p.waterShininess());
        // 4〜6 行目(11-06 光が急に灯る): 光る画素の一覧(カメラからの位置、重み)。4 行目の 25 列目 = (一覧の数, 0, 0, 0)。
        for (int row = 0; row < GLOW_ROWS; row++) {
            for (int c = 0; c < MAX; c++) {
                int i = row * MAX + c;
                if (i < glowCount) {
                    Light l = glowLights[i];
                    buffer.put((float) (l.x - cam.x)).put((float) (l.y - cam.y)).put((float) (l.z - cam.z)).put(glowWeights[i]);
                } else {
                    buffer.put(0.0F).put(-10000.0F).put(0.0F).put(0.0F);
                }
            }
            if (row == 2) {
                // 6 行目の 25 列目(11-09 構造物の中の葉の面のむらの案): x = 葉・茂み(cutout_mipped)の面の向きの効き(1 = 岩と同じ)、
                // y = 開けた張り出しの印の列の点光源の倍率、z = 同じ列の床の持ち上げの倍率
                buffer.put(devFoliageFacing >= 0.0F ? devFoliageFacing : p.foliageFacing()).put(devOverhangLights >= 0.0F ? devOverhangLights : p.overhangLights())
                        .put(devOverhangLift >= 0.0F ? devOverhangLift : p.overhangLift()).put(1.0F); // w = 1: この行が書かれている印
            } else {
                buffer.put(row == 0 ? (float) glowCount : (float) shownHeld).put(0.0F).put(0.0F).put(0.0F);
            }
        }
        buffer.flip();
        int previous = GlStateManager._getInteger(GL11.GL_TEXTURE_BINDING_2D);
        if (texture == 0) {
            texture = GlStateManager._genTexture();
            GlStateManager._bindTexture(texture);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL30.GL_TEXTURE_MAX_LEVEL, 0);
        } else {
            GlStateManager._bindTexture(texture);
        }
        GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, COLS, ROWS, 0, GL11.GL_RGBA, GL11.GL_FLOAT, buffer);
        GlStateManager._bindTexture(previous);
    }

    /** 確かめ用の一行。 */
    static String describe() {
        float[] w = shownWeight;
        int full = 0;
        for (float v : w) {
            if (v >= 0.999F) {
                full++;
            }
        }
        return String.format("地下 %.2f(目標 %.2f)夜 %.2f 光源 %d 個 / 渡した %d 個(重み 1 は %d 個)/ 光る画素 %d 個 / 入った %d・出た %d / 集め %d µs / 過繁茂 %s(度合い %.2f・霧 %.0f〜%.0f)/ ",
                mix, mixTarget, night, found.length, shown.length, full, glowLights.length, enteredCount, leftCount, scanMicros, overgrownHere, 1.0F - openShare(), fogStart(), fogEnd()) + EyeAdaptation.describe() + " / " + CaveMap.describe();
    }
}
