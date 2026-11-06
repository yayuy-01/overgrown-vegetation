package com.yayuy.overgrownvegetation.client.atmosphere;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yayuy.overgrownvegetation.client.VisualQuality;
import com.yayuy.overgrownvegetation.data.DesignData;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 地面の<b>木漏れ日</b>(一覧 2。2026-10-15 試作、10-30 作り直し。元は parked の {@code client/CanopyDapple})。草・シダ・幹に落ちる斑点は
 * 地形のシェーダーが受け持つ({@link CanopySunMap} の {@code OvFleck})。観察の教科書(Vault 光と風の演出/木漏れ日と光の筋の教科書):
 * 太陽の向きに伸びた楕円・大小が重なる・縁がぼやける・風で 1 秒未満〜数秒で明滅。
 *
 * <h2>どこに出すか</h2>
 * プレイヤーの周り {@link #RADIUS} ブロックの地面を毎 tick {@link #SCAN_PER_TICK} か所ずつ見て(近くほど多く)、地面から太陽の方へ線をたどり、
 * 通り抜けたのが葉だけで 1〜{@link #MAX_LAYERS} 層(樹冠の数)なら、そのマスの上面に斑点を置く(葉が薄いほど明るい)。じかに日が当たる所は
 * 林冠の穴 = 光の筋({@link LightShafts})の落ちる所なので置かない。高い林冠(巨大樹の傘)の葉は少なく数え、斑点を大きく薄くする
 * (葉の隙間が地面に映す太陽の像は、隙間の高さに比例して大きくぼやける)。低い茂みの隙間の斑点は小さくくっきり、高い梢ほど大きくぼける(提案 3)。
 *
 * <h2>描き方</h2>
 * 1 マスに葉の隙間の雑音から光の小さな塊を 4〜9 個({@link #blobs}、10-31 ユーザー「円形で木漏れ日に見えない」で楕円 1〜3 個から替えた)。隣のマスと
 * 同じ雑音なので塊が繋がって不揃いのまだらになる(周りが平らならマスをまたいでよい)。太陽が低いほど塊が太陽の向きに並び、少し伸びる。
 * 斑点ごとに葉のはためき({@link Wind#flutter})で明滅し、同じ枝の下の斑点はまとめて枝の揺れ({@link Wind#branch})で行き来する。
 * 太陽が動いて日が届かなくなったマスは 2.5 秒かけて消え、新しく届くマスに 3 秒かけて現れる。光の筋より金色寄りの加算({@link AddLight})。
 * 木漏れ日のマスの半分には、斑点から太陽の方へ葉までの細く薄い筋を立てる(2026-10-16 ユーザー「木漏れ日にも光の筋がうっすらと出るように」)。
 * 強さは森の中で空の下にいる度合い({@link ForestPresence#openForest})を掛けて森の縁でなめらかに出入りする。数値はすべて仮決め(上限は {@code maxDapples})。
 */
final class CanopyDapple {

    /** 草・シダ・幹の斑点(地形のシェーダー)を出すか。 */
    static final boolean FLECKS_ON = true;
    /** 通り抜けてよい葉の層(樹冠の数)。これより厚い林冠の下には届かない。 */
    static final int MAX_LAYERS = 3;

    private static final int RADIUS = 20;
    private static final int SCAN_PER_TICK = 32;
    /** 葉の層ごとの明るさ(1 層・2 層・3 層)。 */
    private static final float[] LAYER_BRIGHTNESS = {1.0F, 0.8F, 0.55F};
    /** 条件を満たしたマスのうち、斑点を置く割合(全部に置くと林床が一様に明るくなり、模様に見えない)。 */
    private static final float PATCH_SHARE = 0.8F;
    /** 木漏れ日のマスのうち、うっすらした筋を立てる割合・描く距離・長さの上限・芯の濃さ(林冠の穴の筋の芯は 0.28)。 */
    private static final float RAY_SHARE = 0.5F;
    private static final float RAY_RANGE = 16.0F;
    private static final double RAY_MAX_LENGTH = 24.0;
    private static final float RAY_ALPHA = 0.045F;
    /** 光の塊: 残す葉の隙間の雑音の下限・1 マスの最少の数・塊 1 つの角の数。仮決め。 */
    private static final double BLOB_KEEP = 0.5;
    private static final int MIN_BLOBS = 4;
    private static final int BLOB_SEGMENTS = 10;

    /**
     * 日向の地面の明るさ(加算の板の色の倍率。点検 21、11-06)。足す光は「これ × 地面の影の深さ」まで({@link #shadeDepth})。
     * 加算の板は下の色を読めないので、日向の草の上面くらいの明るさとして前の斑点の強さ 0.55 をそのまま使う(仮決め)。
     */
    private static final float SUN_GROUND = 0.55F;
    private static final float[] SHADE_RGB = new float[3];
    private static final List<Patch> PATCHES = new ArrayList<>();
    private static final LongSet TAKEN = new LongOpenHashSet();
    private static final RandomSource RANDOM = RandomSource.create();

    private static final class Patch {
        final int x;
        final int z;
        final float y;
        final float brightness;
        /** 光の塊ごとの (dx, dz, 半径, 位相, 強さ)。dx・dz はマスの中心からの世界の向きのずれ({@link #blobs})。 */
        final float[] spots;
        /** 斑点がマスの中心からはみ出してよい範囲(0.5 = マスの中だけ、1 = 平らな周りのマスまで)。 */
        final float bound;
        final int life;
        /** 地面の真上の空の光の段(0〜15。影の深さの見積もり {@link #shadeDepth} に使う)。 */
        int sky = 15;
        /** うっすらした筋の、地面から最初に通った葉までの高さと半分の幅。筋を立てないマスは NaN。 */
        float rayRise = Float.NaN;
        float rayHalf;
        /** 斑点の縁のくっきりさ(0 = 中心から縁までなめらかにぼける、0.7 = 半径の 7 割まで明るさが一定)。 */
        float sharp;
        int age;
        int dyingAt = -1;

        Patch(int x, int z, float y, float brightness, float[] spots, float bound, int life) {
            this.x = x;
            this.z = z;
            this.y = y;
            this.brightness = brightness;
            this.spots = spots;
            this.bound = bound;
            this.life = life;
        }

        float presence() {
            float in = Mth.clamp(this.age / 60.0F, 0.0F, 1.0F);
            float out = this.dyingAt < 0 ? 1.0F : 1.0F - Mth.clamp((this.age - this.dyingAt) / 50.0F, 0.0F, 1.0F);
            return SunLight.smooth(in) * SunLight.smooth(out);
        }

        boolean gone() {
            return this.dyingAt >= 0 && this.age - this.dyingAt >= 50;
        }
    }

    static void clear() {
        PATCHES.clear();
        TAKEN.clear();
    }

    static int count() {
        return PATCHES.size();
    }

    static void tick(ClientLevel level, LocalPlayer player) {
        if (!VisualQuality.canopyDapple()) {
            clear();
            return;
        }
        Vec3 sun = SunLight.sunDirection(level, 1.0F);
        float day = SunLight.daylight(level, sun, 1.0F);
        boolean inForest = ForestPresence.openCover() > 0.001F;
        Wind wind = Wind.current();
        double time = Atmosphere.time(0.0F);
        for (Iterator<Patch> it = PATCHES.iterator(); it.hasNext(); ) {
            Patch p = it.next();
            p.age++;
            if (p.dyingAt < 0) {
                double dx = p.x + 0.5 - player.getX();
                double dz = p.z + 0.5 - player.getZ();
                boolean far = dx * dx + dz * dz > (RADIUS + 4) * (RADIUS + 4);
                boolean recheck = (p.age + (p.x * 7 + p.z * 13 & 31)) % 32 == 0;
                // 強い風のさなかは模様が入れ替わる(葉が大きく動いて、日の通り道が開いたりふさがったりする)。
                double w = wind.strength(p.x + 0.5, p.z + 0.5, time);
                boolean blownAway = w > 0.5 && RANDOM.nextFloat() < 0.012F * (float) (w - 0.5);
                if (far || day <= 0.0F || !inForest || p.age >= p.life || blownAway
                        || recheck && SunTrace.leafLayersOnSunPath(level, p.x, p.y, p.z, sun, MAX_LAYERS) < 1) {
                    p.dyingAt = p.age;
                }
            }
            if (p.gone()) {
                TAKEN.remove(BlockPos.asLong(p.x, 0, p.z));
                it.remove();
            }
        }
        if (day > 0.0F && inForest) {
            int cap = VisualQuality.shaftCap(DesignData.atmosphere().maxDapples());
            for (int i = 0; i < SCAN_PER_TICK && PATCHES.size() < cap; i++) {
                tryFindPatch(level, player, sun);
            }
        }
    }

    private static void tryFindPatch(ClientLevel level, LocalPlayer player, Vec3 sun) {
        // 近くほど多く探す(半径を 1.6 乗で手前へ寄せる。一様だと閉じた林冠の下では上限が遠くのマスで埋まり、足元に斑点がほとんど出なかった)。
        double dist = RADIUS * Math.pow(RANDOM.nextDouble(), 1.6);
        double angle = RANDOM.nextDouble() * Mth.TWO_PI;
        int x = Mth.floor(player.getX() + Math.cos(angle) * dist);
        int z = Mth.floor(player.getZ() + Math.sin(angle) * dist);
        long key = BlockPos.asLong(x, 0, z);
        if (TAKEN.contains(key) || !level.hasChunkAt(new BlockPos(x, 0, z))) {
            return;
        }
        int py = Mth.floor(player.getY());
        float ground = SunTrace.findGround(level, x, py, z);
        if (Float.isNaN(ground)) { // 11-06 から世界全体(バイオームを問わない)
            return;
        }
        int layers = SunTrace.leafLayersOnSunPath(level, x, ground, z, sun, MAX_LAYERS);
        if (layers < 1 || RANDOM.nextFloat() >= PATCH_SHARE) {
            return;
        }
        // 葉の隙間が高い所にあるほど、斑点(太陽のピンホールの像)は大きく、縁がぼやけて薄い。低い茂みの影はくっきり小さく
        // (提案 3、10-30 ユーザー採用。隙間の高さ 2 → dappleHighRise ブロックで、大きさ dappleLowScale → dappleHighScale・縁のくっきりさ dappleLowSharpness → 0)。
        var t = DesignData.atmosphere();
        double rise = SunTrace.lastFirstLeafRise;
        float high = Double.isNaN(rise) ? 0.0F : SunLight.smooth(Mth.clamp((float) ((rise - 2.0) / (t.dappleHighRise() - 2.0)), 0.0F, 1.0F));
        float spotScale = Mth.lerp(high, t.dappleLowScale(), t.dappleHighScale());
        float dim = 1.0F - 0.3F * high;
        // 周り 4 マスの地面が同じ高さなら、斑点がマスをまたいでよい(大きな日だまりになる)。
        boolean flat = ground == SunTrace.findGround(level, x + 1, py, z) && ground == SunTrace.findGround(level, x - 1, py, z)
                && ground == SunTrace.findGround(level, x, py, z + 1) && ground == SunTrace.findGround(level, x, py, z - 1);
        float bound = flat ? 1.0F : 0.5F;
        float[] spots = blobs(x, z, sun, spotScale);
        float widest = 0.0F;
        for (int i = 0; i < spots.length; i += 5) {
            widest = Math.max(widest, spots[i + 2]);
        }
        Patch patch = new Patch(x, z, ground, LAYER_BRIGHTNESS[layers - 1] * dim, spots, bound, 500 + RANDOM.nextInt(700));
        patch.sharp = t.dappleLowSharpness() * (1.0F - high);
        patch.sky = level.getBrightness(net.minecraft.world.level.LightLayer.SKY, BlockPos.containing(x + 0.5, ground + 0.01, z + 0.5));
        if (!Double.isNaN(rise) && rise >= 2.0 && RANDOM.nextFloat() < RAY_SHARE) {
            patch.rayRise = (float) rise;
            patch.rayHalf = Mth.clamp(widest * 0.6F, 0.08F, 0.3F);
        }
        PATCHES.add(patch);
        TAKEN.add(key);
    }

    /**
     * マスの中の光の塊(4〜9 個)を {dx, dz(マスの中心からの世界の向きのずれ), 半径, 位相, 強さ} の並びで返す(10-31 ユーザー「光の跡が円形で木漏れ日に見えない」)。
     * 現実の木漏れ日は葉の隙間の形が映った不揃いのまだら: 1 マスを 3×3 の点に分け(点は世界の格子に固定して少しずらす)、葉の隙間の雑音
     * ({@link #gapNoise}、世界の座標で引く)が高い点ほど大きく明るい塊にし、低い点は捨てる。隣のマスも同じ雑音から取るので、塊は継ぎ目で
     * 切れずに大きな斑へ繋がり、縁はぎざぎざに途切れる。太陽が低いときは雑音を太陽の向きへ引き伸ばして、塊が太陽の向きに並ぶようにする。
     * 大きさは隙間の高さの倍率 {@code scale}(高い梢ほど大きくぼける)を掛ける。数値は仮決め。
     */
    private static float[] blobs(int x, int z, Vec3 sun, float scale) {
        double hLen = Math.sqrt(sun.x * sun.x + sun.z * sun.z);
        double ax = hLen > 1.0E-3 ? sun.x / hLen : 1.0;
        double az = hLen > 1.0E-3 ? sun.z / hLen : 0.0;
        double squash = Math.sqrt(Mth.clamp(1.0 / Math.max(sun.y, 0.05), 1.0, 2.2));
        float[] all = new float[9 * 5];
        double[] score = new double[9];
        int n = 0;
        for (int j = 0; j < 3; j++) {
            for (int i = 0; i < 3; i++) {
                int gi = x * 3 + i;
                int gj = z * 3 + j;
                double px = (gi + 0.5 + (hash(gi, gj, 1) - 0.5) * 0.9) / 3.0;
                double pz = (gj + 0.5 + (hash(gi, gj, 2) - 0.5) * 0.9) / 3.0;
                double g = gapNoise((px * ax + pz * az) / squash, -px * az + pz * ax);
                double k = Mth.clamp((g - 0.35) / 0.45, 0.0, 1.0);
                all[n * 5] = (float) (px - (x + 0.5));
                all[n * 5 + 1] = (float) (pz - (z + 0.5));
                // 雑音の高い点は隣の点(1/3 ブロック)と重なる大きさにして、塊どうしが繋がった斑にする。
                all[n * 5 + 2] = (float) ((0.07 + 0.30 * k * Math.sqrt(k)) * scale);
                all[n * 5 + 3] = (float) (hash(gi, gj, 3) * Mth.TWO_PI);
                all[n * 5 + 4] = (float) (0.45 + 0.55 * k);
                score[n++] = g;
            }
        }
        // 雑音が 0.5 を超える点を残す。少なくとも 4 個(マスごとに光が無くならないように、高い順に)。
        double[] sorted = score.clone();
        java.util.Arrays.sort(sorted);
        double cut = Math.min(BLOB_KEEP, sorted[9 - MIN_BLOBS]);
        int kept = 0;
        for (int i = 0; i < 9; i++) {
            if (score[i] >= cut) {
                kept++;
            }
        }
        float[] out = new float[kept * 5];
        int o = 0;
        for (int i = 0; i < 9; i++) {
            if (score[i] >= cut) {
                System.arraycopy(all, i * 5, out, o, 5);
                o += 5;
            }
        }
        return out;
    }

    /** 葉の隙間の雑音(0〜1): 1.3 ブロックの塊と 0.55 ブロックの細かいぎざぎざの値雑音を重ねる。 */
    private static double gapNoise(double u, double v) {
        return 0.65 * valueNoise(u / 1.3, v / 1.3) + 0.35 * valueNoise(u / 0.55 + 17.3, v / 0.55 + 5.1);
    }

    private static double valueNoise(double u, double v) {
        int iu = Mth.floor(u);
        int iv = Mth.floor(v);
        double fu = u - iu;
        double fv = v - iv;
        fu = fu * fu * (3.0 - 2.0 * fu);
        fv = fv * fv * (3.0 - 2.0 * fv);
        double a = hash(iu, iv, 7);
        double b = hash(iu + 1, iv, 7);
        double c = hash(iu, iv + 1, 7);
        double d = hash(iu + 1, iv + 1, 7);
        return Mth.lerp(fv, Mth.lerp(fu, a, b), Mth.lerp(fu, c, d));
    }

    /** 整数の格子点の決まった乱数(0〜1)。 */
    private static double hash(int i, int j, int salt) {
        long h = i * 0x9E3779B97F4A7C15L ^ j * 0xC2B2AE3D27D4EB4FL ^ salt * 0x165667B19E3779F9L;
        h = (h ^ h >>> 31) * 0xBF58476D1CE4E5B9L;
        h = (h ^ h >>> 29) * 0x94D049BB133111EBL;
        return ((h ^ h >>> 32) >>> 11) * 0x1.0p-53;
    }

    static void render(RenderLevelStageEvent event) {
        if (!VisualQuality.canopyDapple()) {
            return;
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (PATCHES.isEmpty() || level == null) {
            return;
        }
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 sun = SunLight.sunDirection(level, partialTick);
        float day = SunLight.daylight(level, sun, partialTick);
        float intensity = DesignData.atmosphere().intensity() * ForestPresence.openCover();
        if (intensity <= 0.0F || day <= 0.0F) {
            return;
        }
        // 太陽の水平の向き(楕円の長い軸)と伸び。真上からは丸く、低いほど長い。
        double hLen = Math.sqrt(sun.x * sun.x + sun.z * sun.z);
        float ax = hLen > 1.0E-3 ? (float) (sun.x / hLen) : 1.0F;
        float az = hLen > 1.0E-3 ? (float) (sun.z / hLen) : 0.0F;
        float stretch = Mth.clamp(1.0F / (float) Math.max(sun.y, 0.05), 1.0F, 2.2F);
        // 伸びの半分は塊の並び(葉の隙間の雑音を太陽の向きに引き伸ばす、{@link #blobs})で出すので、塊 1 つは平方根だけ伸ばす。
        float elongate = (float) Math.sqrt(stretch);
        float warm = 1.0F - Mth.clamp((float) sun.y / 0.5F, 0.0F, 1.0F);
        // 斑点は光の筋より金色寄り(白っぽいと緑の地面の上で色が抜けて見えた)。うっすらした筋は光の筋と同じ淡い金(空気の中の光)。
        float r = 1.0F;
        float g = Mth.lerp(warm, 0.86F, 0.66F);
        float b = Mth.lerp(warm, 0.48F, 0.32F);
        float rayG = Mth.lerp(warm, 0.95F, 0.72F);
        float rayB = Mth.lerp(warm, 0.68F, 0.42F);
        Vec3 eye = event.getCamera().getPosition();
        float far = Math.max(12.0F, Math.min(RenderSystem.getShaderFogEnd(), RADIUS + 4.0F));
        Wind wind = Wind.current();
        double windTime = Atmosphere.time(partialTick);
        // 林冠の影の掛け算(地形のシェーダーの ovCanopyLight と同じ値: 影の明るさ・色 × 影の強さ)と、日向の暖かい色の明るさ(過繁茂だけ)。
        CanopySunMap.shadeColor(SHADE_RGB);
        float shadeLum = 0.299F * SHADE_RGB[0] + 0.587F * SHADE_RGB[1] + 0.114F * SHADE_RGB[2];
        float shadeK = Mth.lerp(VisualQuality.canopyLight() ? DesignData.atmosphere().surfaceShade() * day * ForestPresence.openCover() : 0.0F,
                1.0F, shadeLum);
        float[] sunWarm = DesignData.atmosphere().sunWarm();
        float warmLum = Mth.lerp(Math.min(1.0F, DesignData.atmosphere().sunWarmStrength()) * ForestPresence.overgrown(), 1.0F,
                0.299F * sunWarm[0] + 0.587F * sunWarm[1] + 0.114F * sunWarm[2]);

        MultiBufferSource.BufferSource buffers = AddLight.buffers();
        VertexConsumer c = buffers.getBuffer(AddLight.renderType());
        for (Patch p : PATCHES) {
            double cx = p.x + 0.5 - eye.x;
            double cy = p.y + 0.02 - eye.y;
            double cz = p.z + 0.5 - eye.z;
            double dist = Math.sqrt(cx * cx + cy * cy + cz * cz);
            float fade = 1.0F - SunLight.smooth(Mth.clamp((float) ((dist - far * 0.6) / (far * 0.4)), 0.0F, 1.0F));
            // 斑点は影の穴(点検 21、11-06): 足す光は「日向の地面の明るさ SUN_GROUND × その地面が日向よりどれだけ暗いか」まで。
            float depth = shadeDepth(level, p.sky, shadeK, warmLum);
            float base = SUN_GROUND * depth * p.brightness * p.presence() * day * intensity * fade
                    * (1.0F - MistView.fogAmount((float) dist));
            if (base <= 0.003F) {
                continue;
            }
            double w = wind.strength(p.x + 0.5, p.z + 0.5, windTime);
            double gust = Math.min(w, 1.0);
            double patchPhase = (p.x * 12.9898 + p.z * 78.233) % (Math.PI * 2.0);
            double branch = (0.02 + 0.22 * gust) * Wind.branch(windTime, patchPhase);
            for (int i = 0; i < p.spots.length; i += 5) {
                float phase = p.spots[i + 3];
                float flicker = (float) Wind.flutter(windTime, phase + patchPhase, w);
                double jitter = 0.06 * gust * Math.sin(2.0 * Math.PI * 1.3 * windTime / 20.0 + phase * 2.0);
                // 枝の揺れは風の向きへ、葉の小刻みな揺れはその直角へ(世界の向き)。
                float shiftX = (float) (branch * wind.dirX() - jitter * wind.dirZ());
                float shiftZ = (float) (branch * wind.dirZ() + jitter * wind.dirX());
                float rad = Math.min(p.spots[i + 2], p.bound * 0.95F);
                float major = Math.min(rad * elongate, p.bound);
                float room = Math.max(0.0F, p.bound - major);
                float dx = Mth.clamp(p.spots[i] + shiftX, -room, room);
                float dz = Mth.clamp(p.spots[i + 1] + shiftZ, -room, room);
                // 塊は縁を半分ぼかす(重なった所がなめらかに繋がり、硬貨を並べたように見えないように)。
                spot(c, (float) cx + dx, (float) cy, (float) cz + dz, major, rad, ax, az, p.sharp * 0.5F,
                        base * Math.max(0.0F, flicker) * p.spots[i + 4], r, g, b, BLOB_SEGMENTS);
            }
            if (!Float.isNaN(p.rayRise) && cx * cx + cz * cz < RAY_RANGE * RAY_RANGE) {
                // 下端は枝の揺れで斑点と一緒に行き来し、明るさは斑点と一緒に明滅する。上端は今の太陽の向きで葉の高さまで。
                float flicker = (float) Wind.flutter(windTime, patchPhase, w);
                double length = Math.min(p.rayRise / Math.max(sun.y, 0.05), RAY_MAX_LENGTH);
                float edgeFade = 1.0F - SunLight.smooth(Mth.clamp((float) ((Math.sqrt(cx * cx + cz * cz) - RAY_RANGE * 0.65) / (RAY_RANGE * 0.35)), 0.0F, 1.0F));
                // うっすらした筋も影の深さで(影の無い所の筋は浮く。11-06 点検 21 の続き)。
                float alpha = RAY_ALPHA * depth * p.brightness * p.presence() * day * intensity * edgeFade * Math.max(0.0F, flicker) * AirHaze.haze();
                if (alpha > 0.002F) {
                    ray(c, cx + branch * wind.dirX(), cy, cz + branch * wind.dirZ(), sun, length, p.rayHalf, alpha, r, rayG, rayB);
                }
            }
        }
        buffers.endBatch(AddLight.renderType());
    }

    /**
     * 地面が日向よりどれだけ暗いか(0〜1。点検 21、11-06)。1 − 今の明るさ ÷ 日向の明るさ。今 = 持ち上げた空の光の段 {@code sky} の明るさ(ライトマップと同じ曲線・
     * ガンマ)× 林冠の影 {@code shadeK}、日向 = 空の光 15・影なし × 日なたの暖かい色の明るさ {@code warmLum}。地形のシェーダーの斑点(ov_light.glsl)と同じ考えの近似
     * (霞・森の色合いは明るさ 1 なので入れない)。斑点のマスは葉の下なので、林冠の影はいつも全部掛かっている扱い。
     */
    static float shadeDepth(ClientLevel level, int sky, float shadeK, float warmLum) {
        float lift = CanopyLift.skyLift();
        float b = CanopyLift.liftedSkyBrightness(level.dimensionType(), sky, lift);
        float gamma = Mth.clamp(Minecraft.getInstance().options.gamma().get().floatValue(), 0.0F, 1.0F);
        float inv = 1.0F - b;
        b = Mth.lerp(gamma, b, 1.0F - inv * inv * inv * inv);
        return Mth.clamp(1.0F - b * shadeK / Math.max(warmLum, 0.01F), 0.0F, 1.0F);
    }

    /**
     * 地面の (gx, gy, gz)(目からの位置)から太陽の方へ {@code length} ブロックの、カメラを向く細い帯(縁 0・中心 {@code alpha})。
     * 地面の手前でわずかに立ち上がり、上の 3 割で葉の中へ溶ける。筋の軸に沿って見る(真下から見上げる)ほど薄くする。
     */
    private static void ray(VertexConsumer c, double gx, double gy, double gz, Vec3 sun, double length, float half, float alpha,
                            float r, float g, float b) {
        final int rows = 4;
        float pX = 0.0F;
        float pY = 0.0F;
        float pZ = 0.0F;
        float pSX = 0.0F;
        float pSY = 0.0F;
        float pSZ = 0.0F;
        float pA = 0.0F;
        boolean havePrev = false;
        for (int i = 0; i <= rows; i++) {
            float t = i / (float) rows;
            double x = gx + sun.x * length * t;
            double y = gy + sun.y * length * t;
            double z = gz + sun.z * length * t;
            // 帯の横 = 軸 × 目への向き(目は原点)。
            double sx = sun.y * -z - sun.z * -y;
            double sy = sun.z * -x - sun.x * -z;
            double sz = sun.x * -y - sun.y * -x;
            double len = Math.sqrt(sx * sx + sy * sy + sz * sz);
            double toEye = Math.sqrt(x * x + y * y + z * z);
            if (len < 1.0E-5 || toEye < 1.0E-5) {
                havePrev = false;
                continue;
            }
            float facing = SunLight.smooth(Mth.clamp((float) ((len / toEye - 0.12) / 0.33), 0.0F, 1.0F));
            float a = Math.min(1.0F, alpha * facing * SunLight.smooth(Mth.clamp(t / 0.1F, 0.0F, 1.0F))
                    * (1.0F - SunLight.smooth(Mth.clamp((t - 0.7F) / 0.3F, 0.0F, 1.0F)))
                    * SunLight.phase(x, y, z, sun));
            float k = (float) (half / len);
            float cX = (float) x;
            float cY = (float) y;
            float cZ = (float) z;
            float sX = (float) sx * k;
            float sY = (float) sy * k;
            float sZ = (float) sz * k;
            if (havePrev) {
                // 左の縁 → 中心、中心 → 右の縁 の 2 枚。
                c.addVertex(pX - pSX, pY - pSY, pZ - pSZ).setColor(r, g, b, 0.0F);
                c.addVertex(cX - sX, cY - sY, cZ - sZ).setColor(r, g, b, 0.0F);
                c.addVertex(cX, cY, cZ).setColor(r, g, b, a);
                c.addVertex(pX, pY, pZ).setColor(r, g, b, pA);
                c.addVertex(pX, pY, pZ).setColor(r, g, b, pA);
                c.addVertex(cX, cY, cZ).setColor(r, g, b, a);
                c.addVertex(cX + sX, cY + sY, cZ + sZ).setColor(r, g, b, 0.0F);
                c.addVertex(pX + pSX, pY + pSY, pZ + pSZ).setColor(r, g, b, 0.0F);
            }
            pX = cX;
            pY = cY;
            pZ = cZ;
            pSX = sX;
            pSY = sY;
            pSZ = sZ;
            pA = a;
            havePrev = true;
        }
    }

    /**
     * 地面に寝かせた楕円 1 つ。長い軸は (ax, az) の向き。半径の {@code sharp} 倍までは明るさ一定({@code segments} 枚の三角)、そこから縁で 0 へ(同じ枚数の帯。
     * 大きな楕円を 8 角でくっきりさせると角が見えた。光の塊は小さいので 10 枚)。
     * sharp 0 は中心から縁までなめらかにぼける(高い梢の隙間の像)。
     */
    private static void spot(VertexConsumer c, float x, float y, float z, float major, float minor, float ax, float az,
                             float sharp, float alpha, float r, float g, float b, int segments) {
        for (int i = 0; i < segments; i++) {
            float a0 = i * Mth.TWO_PI / segments;
            float a1 = (i + 1) * Mth.TWO_PI / segments;
            float u0 = Mth.cos(a0) * major;
            float v0 = Mth.sin(a0) * minor;
            float u1 = Mth.cos(a1) * major;
            float v1 = Mth.sin(a1) * minor;
            float ox0 = u0 * ax - v0 * az;
            float oz0 = u0 * az + v0 * ax;
            float ox1 = u1 * ax - v1 * az;
            float oz1 = u1 * az + v1 * ax;
            if (sharp > 0.01F) {
                c.addVertex(x, y, z).setColor(r, g, b, alpha);
                c.addVertex(x + ox0 * sharp, y, z + oz0 * sharp).setColor(r, g, b, alpha);
                c.addVertex(x + ox1 * sharp, y, z + oz1 * sharp).setColor(r, g, b, alpha);
                c.addVertex(x, y, z).setColor(r, g, b, alpha);
            }
            c.addVertex(x + ox0 * sharp, y, z + oz0 * sharp).setColor(r, g, b, alpha);
            c.addVertex(x + ox0, y, z + oz0).setColor(r, g, b, 0.0F);
            c.addVertex(x + ox1, y, z + oz1).setColor(r, g, b, 0.0F);
            c.addVertex(x + ox1 * sharp, y, z + oz1 * sharp).setColor(r, g, b, alpha);
        }
    }

    private CanopyDapple() {
    }
}
