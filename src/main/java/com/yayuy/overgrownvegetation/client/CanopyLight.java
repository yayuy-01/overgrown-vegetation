package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 汚染地域の<b>林冠の隙間から差す光の筋</b>と、<b>その中で光る胞子</b>(2026-10-15 試作。ユーザー「ジャングルの中のライティングに
 * こだわる: 茂った森でも光が入る・胞子が光に当たって光る・木漏れ日」「汚染地域を美しくも危険な場所に」)。
 *
 * <h2>光の筋の見つけ方(2026-10-15 夜に作り直し)</h2>
 * シェーダーを使わない演出なので、<b>本物の林冠の穴の下でしか出さない</b>(出し過ぎは浮く。迷ったら出さない側へ)。
 * 最初の版は「周りの 8 方向のうち 3 方向が 5 ブロック以上高い列」を穴とみなし、筋を周りの林冠の一番高い所から描いていたので、
 * 一本だけ立つ木の横の何も無い空から筋が降りていた(ユーザー指摘「葉の下以外からも発生している」「木が一本だけの場所でも発生する」)。
 * 今はプレイヤーの周り {@link #RADIUS} ブロックの地面を毎 tick {@link #SCAN_PER_TICK} か所ずつ見て、次を<b>全部</b>満たすときだけ置く(安い順):
 * <ol>
 *     <li><b>じかに日が当たる地面</b>: 地面から太陽の方へたどって、葉にも何にも当たらずに空へ抜ける({@link #traceSun})。</li>
 *     <li><b>葉の穴を通ってくる</b>: その光の道を上へたどり、水平 1〜3 ブロック以内の 8 方向のうち {@link #HOLE_RING_MIN} 方向以上に葉がある
 *     高さ(= 林冠の下面)がある。無ければ穴ではない(一本の木の横・林の縁の空)。</li>
 *     <li><b>落ちた所の周りが葉の影</b>: 周り 3・5 ブロックの地面 16 か所のうち、高さの近い所の {@link #SHADE_RING_MIN} 以上が
 *     葉を通らないと日が当たらない(崖・幹だけの影は数えない)。日だまりの中・林の外では出ない。</li>
 * </ol>
 * 筋は林冠の下面(葉の穴)から落ちる所までで、林冠より上には描かない。幅は穴の大きさ(その高さで 8 方向に葉までの距離)に合わせる。
 * 巨大樹の傘(地面から 100 ブロック以上上)はどの列にも葉が何枚も重なっていて穴が無いので、最初の葉が 48 ブロックより上なら
 * 葉 1〜4 枚の薄い所も穴とみなし、薄く描く({@link #highCanopyShaftAllowance}。2026-10-15 夜、ユーザー「巨大樹の下では発生しない」)。
 * 筋は {@link #MAX_SHAFTS} 本まで、20〜45 秒で入れ替わる。太陽が動いて穴から光が通らなくなったら消える(1 秒ごとに見直す)。
 *
 * <h2>描き方</h2>
 * 加算(足し算)の半透明。深さは読むが書かない。カメラを向く帯を 2 重(芯と、広い薄いにじみ)にし、上端は林冠から湧くように薄く、
 * 落ちる所には地面のにじみを置く。太陽の方を向くほど明るい(光が霧に散る向き)。昼だけ(太陽の高さで寄せる)・雨で弱まる・
 * 水中と盲目の間は描かない。光の量(ブロックの明るさ)は変えない —— 見た目だけ。
 *
 * <h2>光る胞子</h2>
 * 筋の中に湧く小さな光の粒。ゆっくり漂い、筋の中にいる間だけ明るく、外へ出ると暗くなる(胞子が光に当たって光る)。
 * パーティクルではなく、ここで同じ型で描く(登録も素材も要らない)。{@link #MAX_MOTES} 個まで。
 *
 * <p>設定 {@code canopyLightEnabled}(シェーダーと二重になるときに切る)・{@code canopyLightIntensity}。数値はすべて仮決め。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class CanopyLight {

    /** 光の筋を探す半径(ブロック)。 */
    private static final int RADIUS = 28;
    /** 1 tick に調べる地面の数(1 か所の判定が重いので少なめ。ほとんどは最初の「じかに日が当たるか」で落ちる)。 */
    private static final int SCAN_PER_TICK = 10;
    /** 光の筋の上限。 */
    private static final int MAX_SHAFTS = 14;
    /** 光る胞子の上限。 */
    private static final int MAX_MOTES = 420;
    /** 光る胞子が湧く、筋の下端からの長さ(ブロック)。 */
    private static final double MOTE_SPAN = 30.0;
    /** 筋の長さ(林冠の下面から地面まで)の下限(ブロック)。 */
    private static final int MIN_DROP = 4;
    /** 林冠の下面とみなすのに、光の道の周り(水平 1〜3 ブロック以内)の 8 方向のうち葉が要る方向の数。 */
    private static final int HOLE_RING_MIN = 4;
    /** 落ちた所の周りの地面のうち、葉の影でなければならない割合。 */
    private static final float SHADE_RING_MIN = 0.75F;
    /**
     * 林冠の穴を探す道のりの上限(ブロック)。巨大樹の傘は地面から 120〜240 ブロック上にあり、前の 48 では
     * 巨大樹の下で筋が 1 本も出なかった(2026-10-15 夜、ユーザー「巨大樹の下では発生しない」)。
     */
    private static final int HOLE_MAX_K = 400;
    /** {@link #traceSun} の 0.5 ブロック刻みの歩数の上限(360 ブロック。前は 96 ブロックで、巨大樹の傘まで届かず「空へ抜けた」になっていた)。 */
    private static final int TRACE_STEPS = 720;

    private static final int[][] RING_DIRS = {{1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}};

    private static final List<Shaft> SHAFTS = new ArrayList<>();
    private static final List<Mote> MOTES = new ArrayList<>();
    private static final RandomSource RANDOM = RandomSource.create();
    private static ClientLevel shaftLevel;
    private static long ticks;
    /** 直近の tick の {@link #softness(Vec3)}(胞子の湧く幅・光っているかの判定に使う)。 */
    private static float softness;
    /** 直近の tick の昼の強さ({@link #daylight})。環境の胞子が筋の中で光るかに使う。 */
    private static float lastDay;
    private static RenderType renderType;
    /** 描画中に例外が出たら、このセッションの間は描かない({@link com.yayuy.overgrownvegetation.client.horde.HordeStormRenderer} と同じ保険)。 */
    private static boolean broken;
    /**
     * 開発用: 隠す部品(テストクライアントの {@code wind hide <数>} で撮り比べる)。1 = 筋の芯・2 = 筋のにじみ・4 = 筋の足元のにじみ・
     * 8 = 光る胞子・16 = 木漏れ日の筋({@link CanopyDapple})・32 = 木漏れ日の斑点・64 = 全部の面の林冠の影({@link CanopySunMap})・128 = 草・幹の斑点・
     * 256 = 逆光で影を深くしない({@link #backlight()})・512 = 空気の中の光の向きの差を前の掛け方に戻す({@link #phase})・
     * 1024 = 木漏れ日・斑点を前の葉のブロックの数で数える({@link #lastCrowns})・2048 = 林冠の影と森の中の青緑を灰色に戻す({@link CanopySunMap#shadeColor})。ふつうは 0。
     */
    public static volatile int debugHide;

    /**
     * 1 本の光の筋。上端(林冠の穴)は動かず、下端は<b>その時の太陽の向きで毎フレーム決める</b>
     * (上端から太陽と逆へ、光が落ちる高さまで)。最初は落ちる所を 2 秒ごとの当たり判定で決め直していたので、
     * 太陽が動くと筋が 2 秒おきに飛び、ずれが大きいと消えて別の筋に替わってぎこちなかった(2026-10-15 ユーザー指摘)。
     * 今は当たり判定は「まだ光が通るか」と「落ちる高さ」だけを見て、高さはなめらかに寄せる。
     */
    private static final class Shaft {
        final Vec3 top;
        /** 光が落ちる高さ(なめらかに寄せる今の値と、当たり判定で決めた行き先)。 */
        double groundY;
        double targetGroundY;
        /** 直近の tick の太陽で求めた下端(胞子・重なりの判定用)。描くときはフレームの太陽で求め直す。 */
        Vec3 bottom;
        final float width;
        final float phase;
        final int life;
        int age;
        /** 消え始めた tick(-1 = まだ)。 */
        int dyingAt = -1;
        /** 高い林冠(巨大樹の傘)の、穴ではなく薄い所から漏れる筋(薄く描く)。 */
        boolean thin;
        /** 光る胞子の束({@link GpuSpores})の中のこの筋の組(-1 = まだ)。筋が消えるまで変えない(変えると粒が別の筋へ飛ぶ)。 */
        int moteGroup = -1;
        /** 洞窟の口・天井の穴から洞窟の床へ差す筋(地面の高さは洞窟の床で探す)。 */
        boolean cave;
        /** 足元のにじみを置くマスの地面の高さ(マスごとに初めて描くときに調べて覚える。NaN = 地面が無い)。 */
        final java.util.HashMap<Long, Float> glowHeights = new java.util.HashMap<>();

        float glowHeight(ClientLevel level, int x, int z) {
            long key = BlockPos.asLong(x, 0, z);
            Float known = this.glowHeights.get(key);
            if (known != null) {
                return known;
            }
            if (this.glowHeights.size() > 600) {
                this.glowHeights.clear();
            }
            float h = this.cave ? caveFloorAt(level, x, z, Mth.floor(this.targetGroundY)) : groundAt(level, x, z, Mth.floor(this.targetGroundY) + 2);
            this.glowHeights.put(key, h);
            return h;
        }

        Shaft(Vec3 top, Vec3 bottom, float width, float phase, int life) {
            this.top = top;
            this.bottom = bottom;
            this.groundY = bottom.y;
            this.targetGroundY = bottom.y;
            this.width = width;
            this.phase = phase;
            this.life = life;
        }

        /** 太陽の向き {@code sun} のときの下端。 */
        Vec3 bottomFor(Vec3 sun) {
            double drop = Math.max(0.0, this.top.y - this.groundY);
            return this.top.subtract(sun.scale(drop / Math.max(sun.y, 0.05)));
        }

        /** 0〜1 の出方(湧き始め・消え際のなめらかな出入り)。 */
        float presence() {
            float in = Mth.clamp(this.age / 60.0F, 0.0F, 1.0F);
            float out = this.dyingAt < 0 ? 1.0F : 1.0F - Mth.clamp((this.age - this.dyingAt) / 50.0F, 0.0F, 1.0F);
            return smooth(in) * smooth(out);
        }

        boolean gone() {
            return this.dyingAt >= 0 && this.age - this.dyingAt >= 50;
        }

        /** 筋の中心線までの距離(ブロック)と、その位置での筋の半分の幅。中にいれば 1、外へ 0.6 ブロックで 0。 */
        float litAt(double x, double y, double z) {
            double ax = this.bottom.x - this.top.x;
            double ay = this.bottom.y - this.top.y;
            double az = this.bottom.z - this.top.z;
            double len2 = ax * ax + ay * ay + az * az;
            double t = len2 <= 1.0E-6 ? 0.0 : ((x - this.top.x) * ax + (y - this.top.y) * ay + (z - this.top.z) * az) / len2;
            if (t < 0.0 || t > 1.0) {
                return 0.0F;
            }
            double dx = x - (this.top.x + ax * t);
            double dy = y - (this.top.y + ay * t);
            double dz = z - (this.top.z + az * t);
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double half = halfWidth(this.width, (float) t) * widthScale(softness);
            return (float) Mth.clamp(1.0 - (d - half) / 0.6, 0.0, 1.0);
        }
    }

    /** 光る胞子 1 粒。 */
    private static final class Mote {
        double x;
        double y;
        double z;
        /** 速度 {x, y, z}(ブロック/tick)。 */
        final double[] v = new double[3];
        final Shaft shaft;
        final int life;
        final float size;
        final float phase;
        int age;
        float lit;

        Mote(double x, double y, double z, Shaft shaft, int life, float size, float phase) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.shaft = shaft;
            this.life = life;
            this.size = size;
            this.phase = phase;
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        if (level != shaftLevel) {
            SHAFTS.clear();
            MOTES.clear();
            shaftLevel = level;
        }
        if (level == null || player == null || mc.isPaused()) {
            return;
        }
        if (!OvergrownVegetationConfig.COMMON.canopyLightEnabled.get() || !level.dimensionType().hasSkyLight()) {
            SHAFTS.clear();
            MOTES.clear();
            lastDay = 0.0F;
            return;
        }
        ticks++;
        // 風は CanopyWindDriver が tick の初めに進めている(木漏れ日・落ち葉・環境の胞子・風の音も同じ風を読む)。
        // 光の向きと強さ: 昼は太陽、夜は月(月光の筋、2026-10-16 ユーザー指定)。名前は sun のまま(夜は月の向きが入る)。
        Vec3 sun = lightDirection(level, 1.0F);
        float day = lightStrength(level, sun, 1.0F);  // 雲が太陽を隠している間は弱める(CloudShadows、2026-10-17)
        lastDay = day;
        softness = softness(sun);

        // 今ある筋を進める。遠ざかった・夜になった・見直しで当たらなくなったものは消し始める。
        Vec3 center = player.position();
        for (Iterator<Shaft> it = SHAFTS.iterator(); it.hasNext(); ) {
            Shaft s = it.next();
            s.age++;
            s.groundY += (s.targetGroundY - s.groundY) * 0.08;
            s.bottom = s.bottomFor(sun);
            if (s.dyingAt < 0) {
                boolean far = horizontalDist(s.bottom, center) > RADIUS + 6;
                boolean recheck = (s.age + (int) (s.phase * 20.0F)) % 20 == 0;
                if (far || day <= 0.0F || s.age >= s.life || recheck && !revalidate(level, player, s, sun)) {
                    s.dyingAt = s.age;
                }
            }
            if (s.gone()) {
                it.remove();
            }
        }

        if (day > 0.0F) {
            // 岩の天井の下(洞窟)では、洞窟の口・天井の穴から床へ差す筋を探す(地下の光、2026-10-16 ユーザー指定)。
            boolean underground = ForestPresence.underground() > 0.5F;
            for (int i = 0; i < SCAN_PER_TICK && SHAFTS.size() < VisualQuality.shaftCap(MAX_SHAFTS); i++) {
                if (underground) {
                    tryFindCaveShaft(level, player, sun);
                } else {
                    tryFindShaft(level, player, sun);
                }
            }
        }

        tickMotes();
        if (DEV_LOG && ticks % 100 == 0) {
            OvergrownVegetation.LOGGER.info("CanopyLight shafts={} motes={} soft={} 落ちた理由: 日なたでない={} 穴が無い={} 周りが影でない={} {}",
                    SHAFTS.size(), MOTES.size(), softness, rejectNotLit, rejectNoHole, rejectNotShaded, CanopyWind.describe(player.getX(), player.getZ()));
            rejectNotLit = 0;
            rejectNoHole = 0;
            rejectNotShaded = 0;
            BlockPos feet = player.blockPosition();
            OvergrownVegetation.LOGGER.info("CanopyLight 足元の光 空 {} ブロック {} 森 {} 地下 {} 持ち上げ {}",
                    player.level().getBrightness(LightLayer.SKY, feet), player.level().getBrightness(LightLayer.BLOCK, feet),
                    ForestPresence.forestFactor(), ForestPresence.underground(), skyLift());
        }
    }

    /**
     * (x, y, z) がどれかの光の筋の中にあるか(0〜1。筋の中心で 1、縁の外 0.6 ブロックで 0。昼の強さを掛けたもの)。
     * バイオームの環境の胞子({@link DriftingSporeParticle})が、筋に入ったときだけ光る(同じ胞子なので、光の中では同じに光る)。
     */
    static float litAtAny(double x, double y, double z) {
        if (lastDay <= 0.0F || SHAFTS.isEmpty()) {
            return 0.0F;
        }
        float best = 0.0F;
        for (Shaft s : SHAFTS) {
            float presence = s.presence();
            if (presence > best) {
                best = Math.max(best, s.litAt(x, y, z) * presence);
            }
        }
        return best * lastDay;
    }

    /**
     * 漂う胞子の束({@link GpuSpores})へ、{@link #litAtAny} と同じ判定をシェーダーでするための筋の一覧。1 本につき 8 個
     * (上端 − カメラ、半分の幅の倍率 = 幅 × ぼかし、下端 − カメラ、出ている度合い)を {@code out} へ。戻り値は書いた本数。
     * 昼の強さは {@link #daylightNow}。
     */
    static int fillShafts(float[] out, double camX, double camY, double camZ) {
        if (lastDay <= 0.0F) {
            return 0;
        }
        int n = 0;
        float scale = widthScale(softness);
        for (Shaft s : SHAFTS) {
            if (n * 8 + 8 > out.length) {
                break;
            }
            int i = n * 8;
            out[i] = (float) (s.top.x - camX);
            out[i + 1] = (float) (s.top.y - camY);
            out[i + 2] = (float) (s.top.z - camZ);
            out[i + 3] = s.width * scale;
            out[i + 4] = (float) (s.bottom.x - camX);
            out[i + 5] = (float) (s.bottom.y - camY);
            out[i + 6] = (float) (s.bottom.z - camZ);
            out[i + 7] = s.presence();
            n++;
        }
        return n;
    }

    /**
     * 光る胞子の束({@link GpuSpores})へ: 筋ごとの組に 12 個ずつ(上端 − カメラ、湧く幅の半分、下端 − カメラ、出ている度合い、
     * 湧き始める位置 tMin、出す枠の割合、光る判定の幅の倍率、薄い筋なら 1)。組の番号は筋が消えるまで変えない。
     * 出す枠の割合は CPU の湧き方(長さ・幅・朝の胞子の量・粒の量に比例、寿命の平均 145 tick)から求めた「生きている数」÷ 組の枠の数で、
     * 全体を {@link #MAX_MOTES} × 粒の量で抑える。戻り値は使っている組の番号の最大 + 1。
     */
    static int fillMoteGroups(float[] out, double camX, double camY, double camZ, int perGroup, int maxGroups) {
        java.util.Arrays.fill(out, 0.0F);
        boolean[] used = new boolean[maxGroups];
        for (Shaft s : SHAFTS) {
            if (s.moteGroup >= 0 && s.moteGroup < maxGroups) {
                used[s.moteGroup] = true;
            }
        }
        net.minecraft.client.multiplayer.ClientLevel airLevel = Minecraft.getInstance().level;
        float airSpores = airLevel == null ? 1.0F : 1.0F;
        float ws = widthScale(softness);
        float total = 0.0F;
        int top = 0;
        float[] want = new float[maxGroups];
        for (Shaft s : SHAFTS) {
            if (s.moteGroup < 0) {
                for (int g = 0; g < maxGroups; g++) {
                    if (!used[g]) {
                        used[g] = true;
                        s.moteGroup = g;
                        break;
                    }
                }
                if (s.moteGroup < 0) {
                    continue;
                }
            }
            int g = s.moteGroup;
            Vec3 bottom = s.bottom == null ? s.top : s.bottom;
            double length = s.top.distanceTo(bottom);
            float presence = s.presence();
            float rate = presence * (float) (Math.min(length, MOTE_SPAN) * 0.03) * (0.6F + s.width) * (s.thin ? 0.6F : 1.0F)
                    * airSpores * VisualQuality.particles();
            want[g] = Math.min(perGroup, rate * 145.0F);
            total += want[g];
            int i = g * 12;
            out[i] = (float) (s.top.x - camX);
            out[i + 1] = (float) (s.top.y - camY);
            out[i + 2] = (float) (s.top.z - camZ);
            out[i + 3] = s.width * ws * 0.9F; // 湧く幅(CPU の halfWidth の t の分はシェーダーで掛ける)
            out[i + 4] = (float) (bottom.x - camX);
            out[i + 5] = (float) (bottom.y - camY);
            out[i + 6] = (float) (bottom.z - camZ);
            out[i + 7] = presence;
            out[i + 8] = (float) Math.max(0.15, 1.0 - MOTE_SPAN / Math.max(length, 1.0));
            out[i + 10] = s.width * ws;
            out[i + 11] = s.thin ? 1.0F : 0.0F;
            top = Math.max(top, g + 1);
        }
        float cap = VisualQuality.particleCap(MAX_MOTES);
        float k = total > cap ? cap / total : 1.0F;
        for (int g = 0; g < top; g++) {
            out[g * 12 + 9] = want[g] * k / perGroup;
        }
        return top;
    }

    /**
     * 光る胞子の束({@link GpuSpores})へ: 色と明るさ。{@code out} = {光の向き x, y, z, 昼の強さ, r, g, b, 強さの設定}。
     * CPU の描き方と同じく、水の中・盲目・暗闇・設定 0・筋の設定オフのときは false(描かない)。
     */
    static boolean moteLight(ClientLevel level, Camera camera, float partialTick, float[] out) {
        Minecraft mc = Minecraft.getInstance();
        if (!OvergrownVegetationConfig.COMMON.canopyLightEnabled.get() || camera.getFluidInCamera() != FogType.NONE
                || mc.player != null && (mc.player.hasEffect(MobEffects.BLINDNESS) || mc.player.hasEffect(MobEffects.DARKNESS))) {
            return false;
        }
        float intensity = OvergrownVegetationConfig.COMMON.canopyLightIntensity.get().floatValue();
        if (intensity <= 0.0F || (debugHide & 8) != 0) {
            return false;
        }
        Vec3 sun = lightDirection(level, partialTick);
        boolean moon = isMoonlight(level, partialTick);
        float warm = 1.0F - Mth.clamp((float) sun.y / 0.5F, 0.0F, 1.0F);
        out[0] = (float) sun.x;
        out[1] = (float) sun.y;
        out[2] = (float) sun.z;
        out[3] = lightStrength(level, sun, partialTick);
        out[4] = moon ? MOON_R : 1.0F;
        out[5] = Math.min(1.0F, (moon ? MOON_G : Mth.lerp(warm, 0.95F, 0.72F)) + 0.05F);
        out[6] = (moon ? MOON_B : Mth.lerp(warm, 0.68F, 0.42F)) + 0.1F;
        out[7] = intensity;
        return true;
    }

    /** 直近の tick の昼の強さ(筋の中で胞子が光る度合いに掛ける)。 */
    static float daylightNow() {
        return lastDay;
    }

    /** 開発用(テストクライアントの {@code wind lookshaft}・{@code wind gotoshaft}): いちばん近い光の筋の {上端, 下端}。無ければ null。 */
    public static Vec3[] nearestShaftEnds(Vec3 from) {
        Vec3[] best = null;
        double bestDist = Double.MAX_VALUE;
        for (Shaft s : SHAFTS) {
            Vec3 mid = s.top.add(s.bottom).scale(0.5);
            double d = mid.distanceToSqr(from);
            if (s.dyingAt < 0 && d < bestDist) {
                bestDist = d;
                best = new Vec3[]{s.top, s.bottom};
            }
        }
        return best;
    }

    /** テストクライアントのログ用: 直近 5 秒で筋の候補が落ちた理由ごとの数。 */
    private static int rejectNotLit;
    private static int rejectNoHole;
    private static int rejectNotShaded;

    /** Claude のテストクライアント(-Dov.devbridge=true)だけ、筋と粒の数を 5 秒ごとにログへ出す。 */
    private static final boolean DEV_LOG = Boolean.getBoolean("ov.devbridge");

    /** 地面 1 か所を調べて、林冠の穴から光が落ちる所なら筋を足す(条件はクラスの説明の 3 つ)。 */
    private static void tryFindShaft(ClientLevel level, LocalPlayer player, Vec3 sun) {
        if (sun.y < 0.08) {
            return;
        }
        int x = Mth.floor(player.getX()) + RANDOM.nextInt(RADIUS * 2 + 1) - RADIUS;
        int z = Mth.floor(player.getZ()) + RANDOM.nextInt(RADIUS * 2 + 1) - RADIUS;
        if (!level.hasChunkAt(new BlockPos(x, 0, z))) {
            return;
        }
        float ground = groundAt(level, x, z, Mth.floor(player.getY()));
        if (Float.isNaN(ground) || !ModBiomes.isOvergrownLike(level.getBiome(new BlockPos(x, Mth.floor(ground), z)))) {
            return;
        }
        // 1. じかに日が当たる地面か(高い林冠 = 巨大樹の傘なら、薄い所から漏れる光も筋にする。highCanopyAllowance の説明)。
        double gx = x + 0.5;
        double gz = z + 0.5;
        int trace = traceSun(level, gx, ground + 0.05, gz, sun);
        boolean thin = trace > 0 && trace <= highCanopyShaftAllowance();
        if (trace != 0 && !thin) {
            rejectNotLit++;
            return;
        }
        for (Shaft s : SHAFTS) {
            double dx = s.bottom.x - gx;
            double dz = s.bottom.z - gz;
            if (dx * dx + dz * dz < 3.0 * 3.0) {
                return;
            }
        }
        // 2. その光が葉の穴を通ってくるか(穴の下面の高さと、穴の大きさ)。
        float[] hole = findHole(level, gx, ground, gz, sun);
        if (hole == null) {
            rejectNoHole++;
            return;
        }
        // 3. 落ちた所の周りが葉の影か。
        if (!shadedAround(level, x, ground, z, sun)) {
            rejectNotShaded++;
            return;
        }
        double up = hole[0];
        // 林冠の下面から 1 ブロックだけ葉の中へ入れた所を上端にする(葉の中から湧いて見える。林冠より上には描かない)。
        Vec3 top = new Vec3(gx + sun.x * (up + 1.0), ground + sun.y * (up + 1.0), gz + sun.z * (up + 1.0));
        Vec3 bottom = new Vec3(gx, ground, gz);
        Shaft shaft = new Shaft(top, bottom, hole[1], RANDOM.nextFloat(), 400 + RANDOM.nextInt(500));
        shaft.thin = thin;
        SHAFTS.add(shaft);
    }

    /**
     * 洞窟の床 1 か所を調べて、洞窟の口・天井の穴から光が落ちる所なら筋を足す(地下の光、2026-10-16 ユーザー指定「洞窟の口から差す光の筋」)。
     * 条件: (1) 床にじかに日(夜は月)が当たる、(2) 光の道が地形の上へ抜ける所のまわりが岩(= 岩の天井の穴・崖の洞窟の口を通る。開けた谷底では出さない)、
     * (3) 落ちた所の周りの床の半分以上が岩の影。上端は光の道が地形の上へ抜ける所。数値は仮決め。
     */
    private static void tryFindCaveShaft(ClientLevel level, LocalPlayer player, Vec3 sun) {
        if (sun.y < 0.08) {
            return;
        }
        int x = Mth.floor(player.getX()) + RANDOM.nextInt(CAVE_RADIUS * 2 + 1) - CAVE_RADIUS;
        int z = Mth.floor(player.getZ()) + RANDOM.nextInt(CAVE_RADIUS * 2 + 1) - CAVE_RADIUS;
        if (!level.hasChunkAt(new BlockPos(x, 0, z))) {
            return;
        }
        float floor = caveFloorAt(level, x, z, Mth.floor(player.getY()));
        if (Float.isNaN(floor) || !ModBiomes.isOvergrownLike(level.getBiome(new BlockPos(x, Mth.floor(floor), z)))) {
            return;
        }
        double gx = x + 0.5;
        double gz = z + 0.5;
        if (traceSun(level, gx, floor + 0.05, gz, sun) != 0) {
            rejectNotLit++;
            return;
        }
        for (Shaft s : SHAFTS) {
            double dx = s.bottom.x - gx;
            double dz = s.bottom.z - gz;
            if (dx * dx + dz * dz < 3.0 * 3.0) {
                return;
            }
        }
        // 光の道をたどり、洞窟の外へ抜ける所(洞窟の口・天井の穴)を探す: 道の上か周り 2 ブロックの列に、上 16 ブロック以内の岩が
        // あるうちは洞窟の中(高さ地図は使えない —— 天井の穴の真下の列は床がいちばん上になる)。抜ける手前の高さで、道のまわり
        // 1〜3 ブロックの 8 方向のうち {@link #CAVE_RIM_MIN} 方向以上が岩なら、岩の天井・崖の口を通ってきた光(開けた谷底では出さない)。
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int mouth = -1;
        for (int k = 2; k <= HOLE_MAX_K; k++) {
            double px = gx + sun.x * k;
            double py = floor + sun.y * k;
            double pz = gz + sun.z * k;
            boolean inside = rockAbove(level, pos, Mth.floor(px), Mth.floor(py), Mth.floor(pz));
            for (int i = 0; i < RING_DIRS.length && !inside; i++) {
                inside = rockAbove(level, pos, Mth.floor(px + RING_DIRS[i][0] * 2.0), Mth.floor(py), Mth.floor(pz + RING_DIRS[i][1] * 2.0));
            }
            if (!inside) {
                mouth = k == 2 ? -1 : k;
                break;
            }
        }
        int rim = 0;
        if (mouth > 0) {
            double px = gx + sun.x * (mouth - 1);
            double pz = gz + sun.z * (mouth - 1);
            int by = Mth.floor(floor + sun.y * (mouth - 1));
            for (int[] d : RING_DIRS) {
                double len = d[0] != 0 && d[1] != 0 ? Math.sqrt(2.0) : 1.0;
                boolean rock = false;
                for (int r = 1; r <= 3 && !rock; r++) {
                    for (int dy = -1; dy <= 1 && !rock; dy++) {
                        BlockState state = level.getBlockState(pos.set(Mth.floor(px + d[0] * r / len), by + dy, Mth.floor(pz + d[1] * r / len)));
                        rock = state.canOcclude() && !com.yayuy.overgrownvegetation.phase.TreeCanopyGroundFinder.isTreePart(state);
                    }
                }
                if (rock) {
                    rim++;
                }
            }
        }
        if (mouth < 0 || rim < CAVE_RIM_MIN) {
            rejectNoHole++;
            return;
        }
        // 落ちた所の周りの床が岩の影か(床の高さが近い所だけ数える)。
        int counted = 0;
        int dark = 0;
        for (int radius : new int[]{3, 5}) {
            for (int[] d : RING_DIRS) {
                double len = d[0] != 0 && d[1] != 0 ? Math.sqrt(2.0) : 1.0;
                int sx = x + (int) Math.round(d[0] * radius / len);
                int sz = z + (int) Math.round(d[1] * radius / len);
                float g = caveFloorAt(level, sx, sz, Mth.floor(floor));
                if (Float.isNaN(g) || Math.abs(g - floor) > 3.0F) {
                    continue;
                }
                counted++;
                if (traceSun(level, sx + 0.5, g + 0.05, sz + 0.5, sun) < 0) {
                    dark++;
                }
            }
        }
        if (counted < 8 || dark < counted * CAVE_SHADE_MIN) {
            rejectNotShaded++;
            return;
        }
        Vec3 top = new Vec3(gx + sun.x * (mouth + 1.0), floor + sun.y * (mouth + 1.0), gz + sun.z * (mouth + 1.0));
        Shaft shaft = new Shaft(top, new Vec3(gx, floor, gz), CAVE_WIDTH, RANDOM.nextFloat(), 400 + RANDOM.nextInt(500));
        shaft.cave = true;
        SHAFTS.add(shaft);
    }

    /** 列 (x, z) の y より上 16 ブロック以内に、木でない不透明なブロック(岩の天井)があるか。 */
    private static boolean rockAbove(ClientLevel level, BlockPos.MutableBlockPos pos, int x, int y, int z) {
        for (int dy = 1; dy <= 16; dy++) {
            BlockState state = level.getBlockState(pos.set(x, y + dy, z));
            if (state.canOcclude() && !com.yayuy.overgrownvegetation.phase.TreeCanopyGroundFinder.isTreePart(state)) {
                return true;
            }
        }
        return false;
    }

    /** 洞窟の筋を探す半径・太さ・周りの床が岩の影である割合。仮決め。 */
    private static final int CAVE_RADIUS = 20;
    private static final float CAVE_WIDTH = 1.2F;
    private static final float CAVE_SHADE_MIN = 0.5F;
    private static final int CAVE_RIM_MIN = 3;

    /**
     * 洞窟の床: {@code refY} の 3 ブロック上から下へ 20 ブロックまで、空気(当たり判定の無いもの)の下の最初の当たり判定のある
     * 不透明なブロックの上面。水面・無ければ NaN。
     */
    static float caveFloorAt(ClientLevel level, int x, int z, int refY) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, refY + 3, z);
        boolean airAbove = false;
        for (int y = refY + 3; y > refY - 20; y--) {
            pos.setY(y);
            BlockState state = level.getBlockState(pos);
            if (!state.getFluidState().isEmpty()) {
                return Float.NaN;
            }
            net.minecraft.world.phys.shapes.VoxelShape shape = state.getCollisionShape(level, pos);
            if (state.isAir() || shape.isEmpty() || !state.canOcclude()) {
                airAbove = true;
                continue;
            }
            if (airAbove) {
                return y + (float) shape.max(net.minecraft.core.Direction.Axis.Y);
            }
        }
        return Float.NaN;
    }

    /**
     * 地面 (gx, ground, gz) から太陽の方へたどって、林冠の穴を探す。光の道の上の点ごとに、水平 1〜3 ブロック以内の 8 方向の葉を数え、
     * {@link #HOLE_RING_MIN} 方向以上が葉になる最初の高さを林冠の下面とする。見つかれば {道のりの長さ(ブロック), 穴の半径}、
     * 無ければ null(一本の木の横・林の縁の空など、葉に囲まれていない所)。穴の半径はその高さで 8 方向に葉までの距離の平均。
     */
    private static float[] findHole(ClientLevel level, double gx, float ground, double gz, Vec3 sun) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        // 道のりは HOLE_MAX_K まで(巨大樹の傘は地面から 120〜240 ブロック上。前は 48 で打ち切っていて、巨大樹の下では筋が出なかった)。
        for (int k = MIN_DROP; k <= HOLE_MAX_K; k++) {
            double px = gx + sun.x * k;
            double py = ground + sun.y * k;
            double pz = gz + sun.z * k;
            int by = Mth.floor(py);
            // 周り 3 ブロックの列の一番高い所より上へ抜けたら、そこまでに穴が無かった(光の道の真上の列は空いているので、その列の高さでは見ない)。
            int ringTop = level.getHeight(Heightmap.Types.WORLD_SURFACE, Mth.floor(px), Mth.floor(pz));
            for (int[] d : RING_DIRS) {
                ringTop = Math.max(ringTop, level.getHeight(Heightmap.Types.WORLD_SURFACE, Mth.floor(px + d[0] * 3.0), Mth.floor(pz + d[1] * 3.0)));
            }
            if (by > ringTop + 1) {
                return null;
            }
            // 周り 3 ブロック(この高さとその 1 つ上)が空気だけの区画に収まっていれば、葉は無い(巨大樹の幹の下の広い空間を速く抜ける)。
            if (boxIsAir(level, Mth.floor(px - 3.0), by, Mth.floor(pz - 3.0), Mth.floor(px + 3.0), by + 1, Mth.floor(pz + 3.0))) {
                continue;
            }
            // 8 方向それぞれ、光の道から 1〜3 ブロック以内(この高さかその 1 つ上)に葉があるか。
            // 最初は「ちょうど 2 ブロック先」だけを見て 5 方向を求めていたが、樹冠が丸く分かれている森では穴に数えられず、
            // 密な森でも筋が 1 本も出なかった(2026-10-15 のテストクライアント)。
            int leafy = 0;
            for (int[] d : RING_DIRS) {
                double len = d[0] != 0 && d[1] != 0 ? Math.sqrt(2.0) : 1.0;
                boolean found = false;
                for (int r = 1; r <= 3 && !found; r++) {
                    int lx = Mth.floor(px + d[0] * r / len);
                    int lz = Mth.floor(pz + d[1] * r / len);
                    found = isLeafLike(level, pos.set(lx, by, lz)) || isLeafLike(level, pos.set(lx, by + 1, lz));
                }
                if (found) {
                    leafy++;
                }
            }
            if (leafy < HOLE_RING_MIN) {
                continue;
            }
            float sum = 0.0F;
            for (int[] d : RING_DIRS) {
                double len = d[0] != 0 && d[1] != 0 ? Math.sqrt(2.0) : 1.0;
                float reach = 4.0F;
                for (int r = 1; r <= 4; r++) {
                    pos.set(Mth.floor(px + d[0] * r / len), by, Mth.floor(pz + d[1] * r / len));
                    if (isLeafLike(level, pos)) {
                        reach = r;
                        break;
                    }
                }
                sum += reach;
            }
            float radius = Mth.clamp(sum / RING_DIRS.length - 0.5F, 0.5F, 2.0F);
            return new float[]{k, radius};
        }
        return null;
    }

    /**
     * 落ちた所 (x, ground, z) の周り 3・5 ブロックの地面 16 か所のうち、高さの近い所(±4)の {@link #SHADE_RING_MIN} 以上が
     * 葉を通らないと日が当たらない(= 葉の影)か。崖・幹だけの影は葉の影に数えない(林の外の崖の下では出さない)。
     */
    private static boolean shadedAround(ClientLevel level, int x, float ground, int z, Vec3 sun) {
        int counted = 0;
        int leafShade = 0;
        for (int radius : new int[]{3, 5}) {
            for (int[] d : RING_DIRS) {
                double len = d[0] != 0 && d[1] != 0 ? Math.sqrt(2.0) : 1.0;
                int sx = x + (int) Math.round(d[0] * radius / len);
                int sz = z + (int) Math.round(d[1] * radius / len);
                float g = groundAt(level, sx, sz, Mth.floor(ground));
                if (Float.isNaN(g) || Math.abs(g - ground) > 4.0F) {
                    continue;
                }
                counted++;
                int trace = traceSun(level, sx + 0.5, g + 0.05, sz + 0.5, sun);
                if (trace > 0 || trace < -1) {
                    leafShade++;
                }
            }
        }
        return counted >= 10 && leafShade >= counted * SHADE_RING_MIN;
    }

    /**
     * 列 (x, z) の地面の上面の高さ。一番上から下へ、空気・当たり判定の無い植物・葉のようなブロックを通り抜け、最初の不透明な
     * 当たり判定のあるブロックの上面(幹・枝の上のこともある)。64 ブロック下りても無ければ NaN。
     * 列の一番上が {@code refY}(探している人の高さ)より 40 ブロック以上も上にあるとき(巨大樹の傘の下)は、{@code refY} の 40 ブロック上から探す
     * —— 傘の下面から地面まで 100 ブロック以上あり、上から 64 ブロックでは地面に届かなかった(2026-10-15 夜)。
     */
    static float groundAt(ClientLevel level, int x, int z, int refY) {
        int top = Math.min(level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1, refY + 40);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, top, z);
        for (int y = top; y > top - 64; y--) {
            pos.setY(y);
            BlockState state = level.getBlockState(pos);
            if (!state.getFluidState().isEmpty()) {
                return Float.NaN; // 水面・水の中には落とさない
            }
            if (state.isAir() || !state.canOcclude()) {
                continue;
            }
            net.minecraft.world.phys.shapes.VoxelShape shape = state.getCollisionShape(level, pos);
            if (shape.isEmpty()) {
                continue;
            }
            return y + (float) shape.max(net.minecraft.core.Direction.Axis.Y);
        }
        return Float.NaN;
    }

    /** 葉のようなブロック(当たり判定はあるが不透明でない。過繁茂の葉はバニラの葉のタグを持たないので形で見る)。 */
    static boolean isLeafLike(ClientLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return !state.isAir() && !state.canOcclude() && state.getFluidState().isEmpty()
                && !state.getCollisionShape(level, pos).isEmpty();
    }

    /**
     * (x, y, z) から太陽の方へたどる。空へ抜けたら通り抜けた葉の枚数(0 = じかに日が当たる)、不透明なブロックに当たったら
     * −1 − それまでの葉の枚数(−1 = 葉を通らずに幹・崖に当たった)。
     */
    static int traceSun(ClientLevel level, double x, double y, double z, Vec3 sun) {
        lastFirstLeafRise = Double.NaN;
        lastCrowns = 0;
        crownRun = 0;
        crownLastStep = Integer.MIN_VALUE;
        if (sun.y < 0.02) {
            return -1;
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        long last = Long.MIN_VALUE;
        long lastColumn = Long.MIN_VALUE;
        int columnTop = 0;
        long lastSection = Long.MIN_VALUE;
        boolean sectionAir = false;
        int leaves = 0;
        double startY = y;
        int maxY = level.getMaxBuildHeight();
        // 空へ抜けたとみなす: 今いる列の一番上より上で、かつ出発点より 30 ブロック以上高い(斜めの光は、真上が空いていても
        // 先の樹冠に入ることがあるので、列の一番上を越えただけでは止めない。最初はそうしていて、朝夕の光が隣の木を素通りした)。
        // 道のりは TRACE_STEPS / 2 ブロックまで(それより先の物の影は数えない)。巨大樹の傘(地面から 120〜240 ブロック上)まで届くよう
        // 2026-10-15 夜に 96 から延ばし、そのぶん空気だけの区画(16×16×16)ではブロックを読まないようにした(たどり方・結果は前と同じ)。
        for (int step = 0; step < TRACE_STEPS; step++) {
            x += sun.x * 0.5;
            y += sun.y * 0.5;
            z += sun.z * 0.5;
            int bx = Mth.floor(x);
            int by = Mth.floor(y);
            int bz = Mth.floor(z);
            if (by >= maxY) {
                closeCrown();
                return leaves;
            }
            long column = ((long) bx << 32) | (bz & 0xFFFFFFFFL);
            if (column != lastColumn) {
                lastColumn = column;
                columnTop = level.getHeight(Heightmap.Types.WORLD_SURFACE, bx, bz);
            }
            if (by >= columnTop) {
                if (y - startY >= 30.0) {
                    closeCrown();
                    return leaves;
                }
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
                sectionAir = sectionIsAir(level, bx, by, bz);
            }
            if (sectionAir) {
                continue;
            }
            pos.set(bx, by, bz);
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || state.getCollisionShape(level, pos).isEmpty()) {
                continue;
            }
            if (state.canOcclude()) {
                closeCrown();
                return -1 - leaves;
            }
            if (leaves == 0) {
                lastFirstLeafRise = y - startY;
            }
            leaves = Math.min(leaves + 1, 64);
            countCrown(step);
        }
        closeCrown();
        return leaves; // 360 ブロック先まで当たらなかった(それより先の影は数えない)
    }

    /**
     * <b>樹冠の数え方</b>(2026-10-16、ユーザー選択「濃い森の光 = 森に穴を残す + 葉を樹冠の数で数える」の後者)。
     * 葉のブロックの数のままでは、厚い樹冠を 1 つ通るだけで 4 枚を超えて木漏れ日・斑点が消え、森を濃くすると林床に光の模様が無くなる。
     * 本物の樹冠は葉の塊ごとに細かい隙間があるので、線が通る<b>葉の塊(間に {@link #CROWN_GAP} ブロック以上の空気をはさんだ葉の続き)</b>を
     * 1 層と数え、厚い塊は {@link #CROWN_DEPTH} ブロックごとに 1 層足す(巨大樹の傘のような何十枚もの塊は今までどおり暗いまま)。
     * {@link #traceSun} の戻り値(葉のブロックの数)は光の筋などがそのまま使い、樹冠の数は {@link #lastCrowns} で渡す。
     * 使うのは木漏れ日({@link CanopyDapple#leavesOnSunPath})と影の地図({@link CanopySunMap}。草・幹の斑点)。数値は仮決め。
     */
    static final double CROWN_GAP = 1.5;
    static final int CROWN_DEPTH = 3;
    /** 直近の {@link #traceSun} が通った樹冠の数(厚さで重み付け)。描画のスレッドだけが使う。 */
    static int lastCrowns;
    private static int crownRun;
    private static int crownLastStep;

    private static void countCrown(int step) {
        if (crownRun > 0 && (step - crownLastStep) * 0.5 > CROWN_GAP) {
            closeCrown();
        }
        crownRun++;
        crownLastStep = step;
    }

    private static void closeCrown() {
        if (crownRun > 0) {
            lastCrowns += crownWeight(crownRun);
            crownRun = 0;
        }
    }

    /** 厚さ {@code blocks} の葉の塊の層の数({@link #CROWN_DEPTH} ごとに 1)。 */
    static int crownWeight(int blocks) {
        return (blocks + CROWN_DEPTH - 1) / CROWN_DEPTH;
    }

    /**
     * 開発用(テストクライアントの {@code wind leafhist}): プレイヤーの周り (2r+1)² マスの地面から今の太陽へたどり、
     * 通った葉のブロックの数と樹冠の層の数の分布、木漏れ日が出られるマスの数(前の数え方・今の数え方)を返す。
     */
    public static String leafHistogram(int radius) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        if (level == null || player == null) {
            return "ワールドの外";
        }
        Vec3 sun = sunDirection(level, 0.0F);
        int[] blocks = new int[8];
        int[] layers = new int[8];
        int cells = 0;
        int oldDapple = 0;
        int newDapple = 0;
        int px = Mth.floor(player.getX());
        int pz = Mth.floor(player.getZ());
        int py = Mth.floor(player.getY());
        for (int z = pz - radius; z <= pz + radius; z++) {
            for (int x = px - radius; x <= px + radius; x++) {
                float ground = groundAt(level, x, z, py);
                if (Float.isNaN(ground)) {
                    continue;
                }
                cells++;
                int trace = traceSun(level, x + 0.5, ground + 0.05, z + 0.5, sun);
                int crowns = lastCrowns;
                blocks[bucket(trace)]++;
                layers[trace < 0 ? 7 : bucket(crowns)]++;
                if (trace >= 1 && trace <= 3) {
                    oldDapple++;
                }
                if (trace >= 1 && crowns <= 3) {
                    newDapple++;
                }
            }
        }
        return "太陽の高さ " + String.format(java.util.Locale.ROOT, "%.2f", sun.y) + " 地面 " + cells
                + " | 葉のブロック " + describe(blocks) + " | 樹冠の層 " + describe(layers)
                + " | 木漏れ日に出られる 前 " + oldDapple + "・今 " + newDapple;
    }

    private static int bucket(int n) {
        if (n < 0) {
            return 7;
        }
        if (n <= 3) {
            return n;
        }
        return n <= 6 ? 4 : n <= 12 ? 5 : 6;
    }

    private static String describe(int[] b) {
        return "日向 " + b[0] + "・1:" + b[1] + "・2:" + b[2] + "・3:" + b[3] + "・4-6:" + b[4] + "・7-12:" + b[5] + "・13+:" + b[6]
                + "・幹など " + b[7];
    }

    /**
     * 直近の {@link #traceSun} で最初に通った葉の、出発点からの高さ(ブロック。葉を通らなかったら NaN)。描画のスレッドだけが使う。
     * 高い林冠(巨大樹の傘)かどうかを見るのに使う({@link #highCanopyAllowance})。
     */
    static double lastFirstLeafRise = Double.NaN;

    /**
     * 高い林冠の葉を薄く数える分(枚)。直近の {@link #traceSun} の最初の葉が地面から高いほど多い(32 ブロックで 0、そこから
     * 14 ブロックごとに 1 枚ずつ増やし、6 枚まで)。
     *
     * <p>巨大樹の傘(地面から 100 ブロック以上上)は、どの列にも葉が 4〜26 枚重なっていて、ブロック単位では隙間が 1 つも無い
     * (2026-10-15 夜にテストクライアントで数えた)。そのままでは木漏れ日も光の筋も 1 つも出ない(ユーザー「巨大樹の下では発生しない」)。
     * 本物の葉の塊はブロックより細かい隙間だらけで、高い所の葉の影ほど地面ではぼやけて薄くなる(太陽が点ではないので、
     * 小さな物の影ははっきりしなくなる)ので、高い林冠の葉はこの枚数ぶん少なく数える —— 傘の薄い所(外側)からだけ光が漏れる。
     */
    static int highCanopyAllowance() {
        double rise = lastFirstLeafRise;
        if (Double.isNaN(rise) || rise < 32.0) {
            return 0;
        }
        return (int) Math.min(6, Math.round((rise - 32.0) / 14.0));
    }

    /** 光の筋として、高い林冠の薄い所を「穴」とみなす葉の枚数の上限(最初の葉が 48 ブロックより上のときだけ。1〜4 枚)。 */
    static int highCanopyShaftAllowance() {
        double rise = lastFirstLeafRise;
        if (Double.isNaN(rise) || rise < 48.0) {
            return 0;
        }
        return (int) Math.min(4, 1 + Math.round((rise - 48.0) / 20.0));
    }

    /** (x, y, z) を含む区画(16×16×16)が空気だけか。読み込まれていない所・高さの外も空気とみなす。 */
    public static boolean sectionIsAir(ClientLevel level, int x, int y, int z) {
        int index = level.getSectionIndex(y);
        if (index < 0 || index >= level.getSectionsCount()) {
            return true;
        }
        return level.getChunk(x >> 4, z >> 4).getSection(index).hasOnlyAir();
    }

    /** 箱 (minX..maxX, minY..maxY, minZ..maxZ)(両端を含む)が、空気だけの区画にすっかり収まっているか。 */
    static boolean boxIsAir(ClientLevel level, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        for (int sx = minX >> 4; sx <= maxX >> 4; sx++) {
            for (int sz = minZ >> 4; sz <= maxZ >> 4; sz++) {
                for (int sy = minY >> 4; sy <= maxY >> 4; sy++) {
                    if (!sectionIsAir(level, sx << 4, sy << 4, sz << 4)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * 太陽が動いた後も同じ穴から光が落ちるか(今の太陽で下端を求め、そこからじかに日が当たるか)。落ちなければ消し始める
     * (50 tick かけて薄れる)。落ちるなら、落ちる高さの行き先だけ替える(今の高さはそこへ毎 tick 寄せる。筋は飛ばない)。
     */
    private static boolean revalidate(ClientLevel level, LocalPlayer player, Shaft s, Vec3 sun) {
        if (sun.y < 0.08) {
            return false;
        }
        Vec3 bottom = s.bottomFor(sun);
        float ground = s.cave ? caveFloorAt(level, Mth.floor(bottom.x), Mth.floor(bottom.z), Mth.floor(s.targetGroundY))
                : groundAt(level, Mth.floor(bottom.x), Mth.floor(bottom.z), Mth.floor(s.targetGroundY));
        if (Float.isNaN(ground) || ground > s.top.y - MIN_DROP) {
            return false;
        }
        int trace = traceSun(level, bottom.x, ground + 0.05, bottom.z, sun);
        if (trace != 0 && (s.cave || !(s.thin && trace > 0 && trace <= highCanopyShaftAllowance()))) {
            return false;
        }
        s.targetGroundY = ground;
        return true;
    }

    private static void tickMotes() {
        // GPU の束で描いている間は CPU では持たない(GpuSpores、2026-10-23。Iris のパック使用中などは今まで通り)。
        if (GpuSpores.replacesMotes()) {
            MOTES.clear();
            return;
        }
        net.minecraft.client.multiplayer.ClientLevel airLevel = Minecraft.getInstance().level;
        float airSpores = airLevel == null ? 1.0F : 1.0F;
        // 筋の中に湧く。長い筋ほど多い。ただし下の 30 ブロックまで(巨大樹の傘から落ちる 100 ブロックを超える筋では、
        // 上の方は霧の奥で見えないので湧かせない)。
        for (Shaft s : SHAFTS) {
            if (MOTES.size() >= VisualQuality.particleCap(MAX_MOTES)) {
                break;
            }
            float presence = s.presence();
            double length = s.top.distanceTo(s.bottom);
            float tMin = (float) Math.max(0.15, 1.0 - MOTE_SPAN / Math.max(length, 1.0));
            float chance = presence * (float) (Math.min(length, MOTE_SPAN) * 0.03) * (0.6F + s.width) * (s.thin ? 0.6F : 1.0F)
                    * airSpores * VisualQuality.particles();
            while (chance > 0.0F && MOTES.size() < VisualQuality.particleCap(MAX_MOTES)) {
                if (RANDOM.nextFloat() >= chance) {
                    break;
                }
                chance -= 1.0F;
                float t = tMin + RANDOM.nextFloat() * (1.0F - tMin);
                double half = halfWidth(s.width, t) * widthScale(softness) * 0.9;
                double ox = (RANDOM.nextDouble() * 2.0 - 1.0) * half;
                double oy = (RANDOM.nextDouble() * 2.0 - 1.0) * half;
                double oz = (RANDOM.nextDouble() * 2.0 - 1.0) * half;
                Mote m = new Mote(
                        Mth.lerp(t, s.top.x, s.bottom.x) + ox,
                        Mth.lerp(t, s.top.y, s.bottom.y) + oy,
                        Mth.lerp(t, s.top.z, s.bottom.z) + oz,
                        s, 90 + RANDOM.nextInt(110), 0.025F + RANDOM.nextFloat() * 0.04F, RANDOM.nextFloat() * Mth.TWO_PI);
                // 生まれた時から周りの空気と同じ速さで流れる(止まった所から動き出すと、周りの環境の胞子とずれて見える)。
                SporeDrift.matchAir(m.x, m.y, m.z, m.v, CanopyWind.time(0.0F), m.phase);
                MOTES.add(m);
            }
        }
        // 漂う: 動き方はバイオームの環境の胞子と同じ({@link SporeDrift})。風下へ流れ、突風が来ると周りの胞子と一斉にその突風の向きへ流され、
        // ゆっくり形を変える渦に乗って揺らぎ、ほんの少し沈む。
        double windTime = CanopyWind.time(0.0F);
        for (Iterator<Mote> it = MOTES.iterator(); it.hasNext(); ) {
            Mote m = it.next();
            m.age++;
            if (m.age >= m.life) {
                it.remove();
                continue;
            }
            SporeDrift.step(m.x, m.y, m.z, m.v, windTime, m.phase, RANDOM);
            m.x += m.v[0];
            m.y += m.v[1];
            m.z += m.v[2];
            m.lit = m.shaft.litAt(m.x, m.y, m.z) * m.shaft.presence();
        }
    }

    /**
     * <b>空気の中の光の向きの差</b>(2026-10-16、ユーザー選択「昼の光の改善案 (1)」: 光が差して胞子が光って見えるのは周りとの明るさの差と見る向き次第。
     * 太陽の方を向くと数倍明るく、背にするとほぼ消える)。光の筋・木漏れ日の筋の 1 段ごと、光る胞子の 1 粒ごとに、<b>目からその点への向き</b>と
     * 太陽の向きのなす角 c(cos)で決める(霧・胞子は光を前へ強く散らす)。q = (1 + c) / 2 として 0.06 + 2.94 × q^2.2:
     * 太陽の方 3 倍・真横 0.7(前の 0.65〜1.35 の下の端ほど)・背 0.06。数値は仮決め。
     * 太陽が真上の正午は、水平に見るとどの向きでも真横(c ≒ 0)になるので、差は見上げる・見下ろすの間にだけ出る(不具合ではない)。
     * 前は視線(画面の真ん中)の向きだけで画面全体に 0.65〜1.35 倍を掛けていた({@link #lookScatter}。地面のにじみには今もこれを使う。
     * 地面に落ちた光は空気に散った光ではないので、背を向けて消えると昼に見下ろした木漏れ日まで消える)。
     * 開発用: {@link #debugHide} の 512 で前の掛け方に戻す(撮り比べ)。
     */
    static float phase(double dx, double dy, double dz, Vec3 sun) {
        if ((debugHide & 512) != 0) {
            return lookScatter;
        }
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0E-5) {
            return 1.0F;
        }
        double c = (dx * sun.x + dy * sun.y + dz * sun.z) / len;
        double q = Mth.clamp((1.0 + c) * 0.5, 0.0, 1.0);
        return (float) (PHASE_MIN + (PHASE_MAX - PHASE_MIN) * Math.pow(q, PHASE_POWER));
    }

    private static final double PHASE_MIN = 0.06;
    private static final double PHASE_MAX = 3.0;
    private static final double PHASE_POWER = 2.2;

    /** 視線(画面の真ん中)と太陽の向きだけで決める前の明るさ(0.65〜1.35)。毎フレーム {@link #onRenderSky} で決める。 */
    static float lookScatter = 1.0F;
    /** 逆光の度合い(0〜1)。目が慣れるようにゆっくり寄せる。{@link #backlight()}。 */
    private static float backlight;
    private static long backlightMillis;
    /** 逆光に慣れきるまでの時定数(秒)。仮決め。 */
    private static final float BACKLIGHT_SECONDS = 0.8F;

    /**
     * <b>逆光のときだけ影を深くする</b>(2026-10-16、ユーザー選択「昼の光の改善案 (2)」: 明るい筋に目が慣れて、まわりの影が暗く見える見え方。
     * 林床のふだんの明るさは変えない)。視線と太陽の向きの cos が 0.35 から 0.85 へ上がるにつれ 0 → 1、昼の強さを掛け、
     * 実時間で 0.8 秒の時定数で寄せる(振り向くと少しして戻る)。画面に本当に明るい物が映っているかは見ず、向きと昼だけで決める(簡単にした)。
     * 使うのは林冠の影の濃さ({@link CanopySunMap} のシェーダーへの影の色と、Sodium などのときの地面の影 {@link CanopyShade})。
     * 開発用: {@link #debugHide} の 256 で止める。
     */
    static float backlight() {
        return backlight;
    }

    /** 空を描いた後(地形の前)に、視線の向きから {@link #lookScatter} と {@link #backlight} を決める。光の筋が無くても影はあるので、筋の有無で抜けない。 */
    @SubscribeEvent
    public static void onRenderSky(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        long now = net.minecraft.Util.getMillis();
        float dt = Mth.clamp((now - backlightMillis) / 1000.0F, 0.0F, 0.25F);
        backlightMillis = now;
        float target = 0.0F;
        if (level != null) {
            float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
            Vec3 sun = sunDirection(level, partialTick);
            Vector3f look = event.getCamera().getLookVector();
            float toward = look.x() * (float) sun.x + look.y() * (float) sun.y + look.z() * (float) sun.z;
            float front = Math.max(0.0F, toward);
            lookScatter = 0.65F + 0.7F * front * front;
            if ((debugHide & 256) == 0) {
                target = smooth(Mth.clamp((toward - 0.35F) / 0.5F, 0.0F, 1.0F)) * daylight(level, sun, partialTick);
            }
        }
        backlight += (target - backlight) * (1.0F - (float) Math.exp(-dt / BACKLIGHT_SECONDS));
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || broken) {
            return;
        }
        if (SHAFTS.isEmpty() && MOTES.isEmpty()) {
            return;
        }
        boolean main = drawToMainTarget();
        try {
            render(event);
        } catch (RuntimeException e) {
            broken = true;
            OvergrownVegetation.LOGGER.error("林冠の光の筋の描画で例外が出たので、このセッションでは描かない", e);
        } finally {
            restoreTarget(main);
        }
    }

    private static void render(RenderLevelStageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || level != shaftLevel) {
            return;
        }
        Camera camera = event.getCamera();
        if (camera.getFluidInCamera() != FogType.NONE || mc.player != null
                && (mc.player.hasEffect(MobEffects.BLINDNESS) || mc.player.hasEffect(MobEffects.DARKNESS))) {
            return;
        }
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 sun = lightDirection(level, partialTick);
        float day = lightStrength(level, sun, partialTick);  // 雲が太陽を隠している間は弱める(CloudShadows、2026-10-17)
        boolean moon = isMoonlight(level, partialTick);
        float intensity = OvergrownVegetationConfig.COMMON.canopyLightIntensity.get().floatValue();
        if (intensity <= 0.0F) {
            return;
        }
        Vec3 eye = camera.getPosition();
        // 空気の中の光(筋・胞子)の明るさは、目からその点への向きで 1 段・1 粒ごとに決める({@link #phase})。地面のにじみだけ前の視線の向きの値。
        float scatter = lookScatter;
        // 朝夕は橙へ、昼は葉を透かした淡い黄緑の金へ。
        float warm = 1.0F - Mth.clamp((float) sun.y / 0.5F, 0.0F, 1.0F);
        float r = moon ? MOON_R : 1.0F;
        float g = moon ? MOON_G : Mth.lerp(warm, 0.95F, 0.72F);
        float b = moon ? MOON_B : Mth.lerp(warm, 0.68F, 0.42F);
        float fogEnd = RenderSystem.getShaderFogEnd();
        double time = ticks + partialTick;
        // 空気中の胞子の量(朝霧の朝は濃く、乾いた真昼は薄い。{@link MorningMistView#airSpores}、ユーザーの (4))。
        float air = 1.0F;

        MultiBufferSource.BufferSource buffers = immediateBuffers();  // Iris のまとめ描きを避ける(immediateBuffers の説明)
        VertexConsumer consumer = buffers.getBuffer(renderType());
        // 太陽が真上に近いほど、筋を太く・薄く・輪郭をぼかす(葉の間からぼんやり漏れる光)。斜めの朝夕はくっきりのまま。
        float soft = softness(sun);
        float coreWidth = widthScale(soft);
        // 薄めすぎると昼は見えなくなった(芯 −62%・にじみ −25% のとき)ので、太くしたぶんの半分ほどだけ薄める。
        float coreAlpha = 0.28F * (1.0F - 0.35F * soft);
        float haloWidth = 2.6F + 1.4F * soft;
        float haloAlpha = 0.09F;
        double windTime = CanopyWind.time(partialTick);
        double dirX = CanopyWind.dirX();
        double dirZ = CanopyWind.dirZ();
        for (Shaft s : SHAFTS) {
            Vec3 bottom = s.bottomFor(sun);
            // 風: 穴のまわりの葉が揺れるので、筋は明滅し(突風のさなかは深く速く、穴がふさがって一瞬薄れる)、
            // 枝の揺れに合わせて風の向きへ少し行き来する。行き来は描く位置だけ(保存した穴の位置 top は動かさない。
            // 動かすと太陽を追う見直しがずれる)。カメラ側を逆へずらして描くのと同じ。
            double w = CanopyWind.strength(bottom.x, bottom.z, windTime);
            double gust = Math.min(w, 1.0);
            // 明滅は木漏れ日(毎秒 3〜4 回)より遅く浅く(毎秒 0.7・1.6 回、最大 3 割)。筋は画面を広く覆うので、木漏れ日と同じ
            // 速さ・深さにすると点滅して見えた(2026-10-15 の撮影: 筋の真下では 1 コマごとに画面の 2 割が変わった)。
            double seconds = windTime / 20.0;
            float flicker = (float) ((0.97 + 0.03 * Math.sin(2.0 * Math.PI * 0.2 * seconds + s.phase * 5.0))
                    * (1.0 - 0.3 * gust * (0.5 + 0.5 * Math.sin(2.0 * Math.PI * 0.7 * seconds + s.phase * 6.0))
                    * (0.6 + 0.4 * Math.sin(2.0 * Math.PI * 1.6 * seconds + s.phase * 11.0))));
            double sway = (0.04 + 0.22 * gust) * CanopyWind.branch(windTime, s.phase * 9.0)
                    + 0.06 * gust * Math.sin(2.0 * Math.PI * 0.9 * windTime / 20.0 + s.phase * 4.0);
            Vec3 view = eye.subtract(dirX * sway, 0.0, dirZ * sway);
            // 高い林冠の薄い所から漏れる筋は、穴から差す筋より薄い。
            float alpha = s.presence() * day * intensity * flicker * fade(bottom, eye, fogEnd) * (s.thin ? 0.6F : 1.0F) * air;
            if (alpha <= 0.003F) {
                continue;
            }
            int hide = debugHide;
            if ((hide & 1) == 0) {
                ribbon(consumer, s, bottom, view, sun, coreWidth, coreAlpha * alpha, soft, fogEnd, r, g, b);
            }
            if ((hide & 2) == 0) {
                ribbon(consumer, s, bottom, view, sun, haloWidth, haloAlpha * alpha, soft, fogEnd, r, g, b);
            }
            if ((hide & 4) == 0) {
                groundGlow(consumer, level, s, bottom, view, 1.0F + 0.8F * soft, 0.30F * (1.0F - 0.4F * soft) * alpha * scatter * (1.0F - 0.0F), r, g, b);
            }
        }
        if ((debugHide & 8) != 0) {
            buffers.endBatch(renderType());
            return;
        }
        Vector3f left = camera.getLeftVector();
        Vector3f up = camera.getUpVector();
        for (Mote m : MOTES) {
            float life = Math.min(1.0F, m.age / 15.0F) * Math.min(1.0F, (m.life - m.age) / 30.0F);
            float twinkle = 0.7F + 0.3F * (float) Math.sin(time * 0.25 + m.phase);
            double px = Mth.lerp(partialTick, m.x - m.v[0], m.x) - eye.x;
            double py = Mth.lerp(partialTick, m.y - m.v[1], m.y) - eye.y;
            double pz = Mth.lerp(partialTick, m.z - m.v[2], m.z) - eye.z;
            // 光の中で光る分だけ、見る向きで変わる(太陽の方の粒は明るく、背にした粒はほぼ消える。{@link #phase})。
            float glow = (0.04F + 0.96F * m.lit * (m.lit > 0.0F ? phase(px, py, pz, sun) : 1.0F)) * life * twinkle * intensity * Math.max(day, 0.15F);
            if (glow <= 0.01F || px * px + py * py + pz * pz > 40.0 * 40.0) {
                continue;
            }
            // 光の中の粒は少し大きく見える(にじむ)。
            mote(consumer, (float) px, (float) py, (float) pz, m.size * (0.7F + 0.6F * m.lit), left, up, Math.min(1.0F, 0.95F * glow),
                    r, Math.min(1.0F, g + 0.05F), b + 0.1F);
        }
        buffers.endBatch(renderType());
    }

    /**
     * カメラを向く帯(筋の中心線のまわりで回す)。横は 縁 0 → 中ほど → 中心 の 5 点で山なりに(中ほどの重みはぼかすほど高く、
     * 輪郭が溶ける)、縦は上端(林冠)で 0 から湧き、下端の手前で弱まる。ぼかすほど湧き出しを長く・下端の弱まりを強く
     * (葉の間からにじみ出て、地面へ向かって薄れる)。
     */
    private static void ribbon(VertexConsumer c, Shaft s, Vec3 bottom, Vec3 eye, Vec3 sun, float widthScale, float alpha, float soft,
                               float fogEnd, float r, float g, float b) {
        final int rows = 8;
        Vec3 axis = bottom.subtract(s.top);
        float mid = Mth.lerp(soft, 0.55F, 0.8F);
        float emerge = 0.22F + 0.15F * soft;
        float sink = 0.45F + 0.2F * soft;
        float[] prev = null;
        for (int i = 0; i <= rows; i++) {
            float t = i / (float) rows;
            Vec3 p = s.top.add(axis.scale(t));
            Vec3 toEye = eye.subtract(p);
            Vec3 side = axis.cross(toEye);
            double len = side.length();
            if (len < 1.0E-5) {
                prev = null;
                continue;
            }
            float half = halfWidth(s.width, t) * widthScale;
            // 筋の軸に沿って見ている(真下から見上げる・真上から見下ろす)ときは、カメラを向く帯の向きが少しの揺れで大きく回り、
            // 画面いっぱいのにじみがちらつく。軸と視線のなす角が小さいほど薄くする(sin 0.12〜0.45 で 0 → 1)。
            double sinAngle = len / (axis.length() * toEye.length());
            float facing = smooth(Mth.clamp((float) ((sinAngle - 0.12) / 0.33), 0.0F, 1.0F));
            side = side.scale(half / len);
            float a = alpha * facing * smooth(Mth.clamp(t / emerge, 0.0F, 1.0F)) * (1.0F - sink * smooth(Mth.clamp((t - 0.6F) / 0.4F, 0.0F, 1.0F)))
                    * longFade(p, eye, fogEnd)
                    * phase(-toEye.x, -toEye.y, -toEye.z, sun);
            // 1 を超えると頂点の色の 1 バイトがあふれて暗くなる(太陽の方では芯 0.28 × 3 倍で超える)。
            a = Math.min(a, 1.0F);
            float cx = (float) (p.x - eye.x);
            float cy = (float) (p.y - eye.y);
            float cz = (float) (p.z - eye.z);
            float sx = (float) side.x;
            float sy = (float) side.y;
            float sz = (float) side.z;
            // 左の縁・左の中ほど・中心・右の中ほど・右の縁、と濃さ。
            float[] row = {
                    cx - sx, cy - sy, cz - sz,
                    cx - sx * 0.5F, cy - sy * 0.5F, cz - sz * 0.5F,
                    cx, cy, cz,
                    cx + sx * 0.5F, cy + sy * 0.5F, cz + sz * 0.5F,
                    cx + sx, cy + sy, cz + sz,
                    a};
            if (prev != null) {
                float pa = prev[15];
                quad(c, prev, 0, 0.0F, row, 0, 0.0F, prev, 3, pa * mid, row, 3, a * mid, r, g, b);
                quad(c, prev, 3, pa * mid, row, 3, a * mid, prev, 6, pa, row, 6, a, r, g, b);
                quad(c, prev, 6, pa, row, 6, a, prev, 9, pa * mid, row, 9, a * mid, r, g, b);
                quad(c, prev, 9, pa * mid, row, 9, a * mid, prev, 12, 0.0F, row, 12, 0.0F, r, g, b);
            }
            prev = row;
        }
    }

    /** 2 つの行の間の四角(左の列の上下と右の列の上下)。 */
    private static void quad(VertexConsumer c, float[] p0, int i0, float a0, float[] p1, int i1, float a1,
                             float[] p2, int i2, float a2, float[] p3, int i3, float a3, float r, float g, float b) {
        c.addVertex(p0[i0], p0[i0 + 1], p0[i0 + 2]).setColor(r, g, b, a0);
        c.addVertex(p1[i1], p1[i1 + 1], p1[i1 + 2]).setColor(r, g, b, a1);
        c.addVertex(p3[i3], p3[i3 + 1], p3[i3 + 2]).setColor(r, g, b, a3);
        c.addVertex(p2[i2], p2[i2 + 1], p2[i2 + 2]).setColor(r, g, b, a2);
    }

    /**
     * 光が落ちた所の地面のにじみ(中心が明るく、半径 rad で 0 になる丸)。<b>マスごとに、そのマスの地面の上面に</b>置き、
     * 落ちた所の地面と高さが 0.6 ブロック以上違うマス(段の下・上)には置かない。
     * 前は落ちた所の高さの水平な板 1 枚で描いていて、段々の地形では段の外へはみ出した板が宙に浮き、縁のくっきりした薄い板の明るみに見えた
     * (2026-10-16、正午のにじみが強すぎる件で撮り比べて分かった。正午ほど丸が大きいので目立った)。
     */
    private static void groundGlow(VertexConsumer c, ClientLevel level, Shaft s, Vec3 bottom, Vec3 eye, float radScale, float alpha,
                                   float r, float g, float b) {
        float rad = halfWidth(s.width, 1.0F) * 2.2F * radScale;
        int x0 = Mth.floor(bottom.x - rad);
        int x1 = Mth.floor(bottom.x + rad);
        int z0 = Mth.floor(bottom.z - rad);
        int z1 = Mth.floor(bottom.z + rad);
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                float a00 = glowAt(x, z, bottom, rad);
                float a01 = glowAt(x, z + 1, bottom, rad);
                float a11 = glowAt(x + 1, z + 1, bottom, rad);
                float a10 = glowAt(x + 1, z, bottom, rad);
                if (a00 + a01 + a11 + a10 <= 0.0F) {
                    continue;
                }
                float h = s.glowHeight(level, x, z);
                if (Float.isNaN(h) || Math.abs(h - s.groundY) > 0.6) {
                    continue;
                }
                float y = (float) (h + 0.04 - eye.y);
                float fx0 = (float) (x - eye.x);
                float fz0 = (float) (z - eye.z);
                c.addVertex(fx0, y, fz0).setColor(r, g, b, alpha * a00);
                c.addVertex(fx0, y, fz0 + 1.0F).setColor(r, g, b, alpha * a01);
                c.addVertex(fx0 + 1.0F, y, fz0 + 1.0F).setColor(r, g, b, alpha * a11);
                c.addVertex(fx0 + 1.0F, y, fz0).setColor(r, g, b, alpha * a10);
            }
        }
    }

    /** 地面のにじみの濃さ(0〜1)。マスの角 (x, z) と落ちた所の水平の距離で、中心 1 → 半径 rad で 0(2 乗で柔らかく)。 */
    private static float glowAt(int x, int z, Vec3 bottom, float rad) {
        double dx = x - bottom.x;
        double dz = z - bottom.z;
        float d = (float) (Math.sqrt(dx * dx + dz * dz) / rad);
        float f = Math.max(0.0F, 1.0F - d);
        return f * f;
    }

    /** 光る胞子の丸の縁(8 角形)の向き。 */
    private static final float[] RIM_COS = new float[8];
    private static final float[] RIM_SIN = new float[8];

    static {
        for (int i = 0; i < 8; i++) {
            RIM_COS[i] = Mth.cos(i * Mth.TWO_PI / 8.0F);
            RIM_SIN[i] = Mth.sin(i * Mth.TWO_PI / 8.0F);
        }
    }

    /**
     * 光る胞子 1 粒(カメラを向くぼやけた丸。中心が明るく縁が 0 の 8 枚の三角を四角で描く)。
     * 最初は 4 枚の菱形にしたが、十字の星のように見えた(2026-10-15 の撮影)。
     */
    static void mote(VertexConsumer c, float x, float y, float z, float size, Vector3f left, Vector3f up,
                             float alpha, float r, float g, float b) {
        for (int i = 0; i < 8; i++) {
            int j = (i + 1) & 7;
            float px = (left.x() * RIM_COS[i] + up.x() * RIM_SIN[i]) * size;
            float py = (left.y() * RIM_COS[i] + up.y() * RIM_SIN[i]) * size;
            float pz = (left.z() * RIM_COS[i] + up.z() * RIM_SIN[i]) * size;
            float qx = (left.x() * RIM_COS[j] + up.x() * RIM_SIN[j]) * size;
            float qy = (left.y() * RIM_COS[j] + up.y() * RIM_SIN[j]) * size;
            float qz = (left.z() * RIM_COS[j] + up.z() * RIM_SIN[j]) * size;
            // 中心を 2 回打った四角 = 三角。
            c.addVertex(x, y, z).setColor(r, g, b, alpha);
            c.addVertex(x + px, y + py, z + pz).setColor(r, g, b, 0.0F);
            c.addVertex(x + qx, y + qy, z + qz).setColor(r, g, b, 0.0F);
            c.addVertex(x, y, z).setColor(r, g, b, alpha);
        }
    }

    /**
     * 筋のぼかし具合(0 = 斜めの朝夕のくっきりした筋、1 = 真上からのぼんやりした光)。太陽の高さで寄せる。
     * 0〜1 の間は時刻とともに連続して変わるので、朝の筋から昼のにじみへ途切れずに移る(2026-10-15 ユーザー
     * 「真上に近いほど太く、にじむように輪郭がわからない感じに。朝方の斜めの光はそのまま、シームレスに移行」)。
     * 時刻 2000(太陽の高さ 0.66)ではまだ 0、時刻 4000(0.93)で {@link #MAX_SOFTNESS} の約 0.9 倍、正午で {@link #MAX_SOFTNESS}。
     */
    static float softness(Vec3 sun) {
        return MAX_SOFTNESS * smooth(Mth.clamp(((float) sun.y - 0.7F) / 0.27F, 0.0F, 1.0F));
    }

    /**
     * 正午のぼかしの強さ。2026-10-16、ユーザー「正午あたりの太陽が真上に来た際の差し込む光の拡散度合いが強すぎた。もっと弱く」で 1 → 0.4(仮決め)。
     * 正午の芯の幅は 2.6 倍 → 1.64 倍、にじみの帯は 4.0 → 3.16、地面のにじみの広さは 1.8 倍 → 1.32 倍、輪郭のぼけ(帯の中ほどの濃さ)0.8 → 0.65、
     * 芯の薄め方は 35% → 14%(朝夕のくっきりした筋は変わらない)。
     */
    private static final float MAX_SOFTNESS = 0.4F;

    /** 芯の帯の幅の倍率。ぼかすほど太い。 */
    private static float widthScale(float soft) {
        return 1.0F + 1.6F * soft;
    }

    /** 筋の半分の幅。地面へ向かうほど少し広がる。 */
    private static float halfWidth(float width, float t) {
        return width * (0.75F + 0.5F * t);
    }

    /**
     * 長い筋(巨大樹の傘の穴から 100 ブロック以上落ちる)の、目から遠い部分を霧の中へ消す。霧の色へ寄せる代わりに薄くする(加算なので)。
     * ふつうの林の筋(目から 30 ブロックほどまで)は変わらない。
     */
    private static float longFade(Vec3 p, Vec3 eye, float fogEnd) {
        double d = Math.sqrt(p.distanceToSqr(eye));
        float far = Math.max(56.0F, Math.min(fogEnd * 0.9F, 110.0F));
        return 1.0F - smooth(Mth.clamp((float) ((d - far * 0.55) / (far * 0.45)), 0.0F, 1.0F));
    }

    /** 遠い筋を霧の手前で消す(加算なので、霧の色へ寄せるより消す方が自然)。 */
    private static float fade(Vec3 p, Vec3 eye, float fogEnd) {
        double d = Math.sqrt(p.distanceToSqr(eye));
        float far = Math.max(16.0F, Math.min(fogEnd, RADIUS + 10.0F));
        return 1.0F - smooth(Mth.clamp((float) ((d - far * 0.6) / (far * 0.4)), 0.0F, 1.0F));
    }

    /**
     * 太陽の向き(単位ベクトル)。空の描画と同じく、Y 軸で −90°・X 軸で時刻の角度だけ回した真上 = (−sin θ, cos θ, 0)。
     * 東(+X)から昇り西へ沈む。
     */
    static Vec3 sunDirection(ClientLevel level, float partialTick) {
        float angle = level.getSunAngle(partialTick);
        return new Vec3(-Mth.sin(angle), Mth.cos(angle), 0.0);
    }

    /**
     * 光の筋を落とす光の向き: 昼は太陽、夜は月(太陽の反対。2026-10-16 ユーザー指定「月光の筋」)。日没で太陽も月も地平線にいるので、
     * 入れ替わる所では強さがどちらも 0 で途切れない(太陽で見つけた筋は日没で消え、月が昇ると月の向きで見つけ直す)。
     */
    static Vec3 lightDirection(ClientLevel level, float partialTick) {
        Vec3 sun = sunDirection(level, partialTick);
        return sun.y >= 0.0 ? sun : sun.scale(-1.0);
    }

    /** 今の光の筋が月の光か(太陽が地平線より下)。 */
    static boolean isMoonlight(ClientLevel level, float partialTick) {
        return sunDirection(level, partialTick).y < 0.0;
    }

    /**
     * 光の筋の強さ(0〜1)。昼は {@link #daylight}。夜は月の高さで寄せ、{@link #MOON_STRENGTH} × 月の満ち欠け(新月でも {@link #MOON_FLOOR})、
     * 雨は雲で月が隠れるので強く弱める。仮決め。
     */
    static float lightStrength(ClientLevel level, Vec3 dir, float partialTick) {
        if (!isMoonlight(level, partialTick)) {
            return daylight(level, dir, partialTick);
        }
        float height = smooth(Mth.clamp(((float) dir.y - 0.06F) / 0.3F, 0.0F, 1.0F));
        float phase = MOON_FLOOR + (1.0F - MOON_FLOOR) * level.getMoonBrightness();
        float rain = level.getRainLevel(partialTick);
        return height * MOON_STRENGTH * phase * (1.0F - 0.9F * rain);
    }

    /** 月光の筋の強さ(満月で昼の何倍か)・新月の夜の残り・色(青白)。仮決め。 */
    static final float MOON_STRENGTH = 0.5F;
    static final float MOON_FLOOR = 0.3F;
    static final float MOON_R = 0.62F;
    static final float MOON_G = 0.76F;
    static final float MOON_B = 1.0F;

    /** 昼の強さ(0〜1)。太陽の高さで寄せ、雨・雷雨で弱める。 */
    static float daylight(ClientLevel level, Vec3 sun, float partialTick) {
        float height = smooth(Mth.clamp(((float) sun.y - 0.06F) / 0.3F, 0.0F, 1.0F));
        float rain = level.getRainLevel(partialTick);
        float thunder = level.getThunderLevel(partialTick);
        return height * (1.0F - 0.75F * rain) * (1.0F - 0.2F * thunder);
    }

    private static double horizontalDist(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    static float smooth(float t) {
        return t * t * (3.0F - 2.0F * t);
    }

    /**
     * <b>林冠の下の空の光の持ち上げ</b>の強さ(0〜1。2026-10-17 ユーザー「木の密度を少しだけ上げて、その状態で木漏れ日や差し込む光が美しく見えれば完璧」)。
     * 林床はバニラの空の光の減り(葉 1 枚ごとに 1 段)と林冠の影({@link CanopySunMap}・{@link CanopyShade})の両方で暗くなり、森を濃くすると
     * 木漏れ日・光の筋の当たる所まで闇に沈んだ。影の暗さは林冠の影が受け持つので、空の光の減りの方を設定 {@code canopySkyLift} の割合だけ戻す。
     * 過繁茂の地域にいる度合い × (1 − 地下の度合い)。読み手は {@code LightTextureCanopyLiftMixin}(ライトマップ = 地形・Mob・粒が同じ明るさ)。
     */
    /** 空の光がほとんど届かない段(0 段)での寄せ方の倍率(7 段で 1)。仮決め。 */
    static final float LIFT_DARK = 0.45F;

    public static float skyLift() {
        float lift = OvergrownVegetationConfig.COMMON.canopySkyLift.get().floatValue();
        if (lift <= 0.0F) {
            return 0.0F;
        }
        return lift * ForestPresence.forestFactor() * (1.0F - ForestPresence.underground());
    }

    /**
     * 空の光の段 {@code level}(0〜15)の明るさを、15 との差の {@code lift} の割合だけ明るい方へ寄せて返す(バニラの {@code LightTexture#getBrightness} と
     * 同じ曲線を小数の段で)。空の光がほとんど届かない所(0〜3 段)は寄せ方を {@value #LIFT_DARK} 倍までに抑え、7 段から全部寄せる
     * (2026-10-17: 最初は 3 段以下を寄せなかったが、間隔 5・空き地の少ない森では林床の多くが 0〜1 段で真っ暗のままだった。
     * 0 段は持ち上げ 0.6 でバニラの 4 段ほど = 薄暗い。洞窟・家の中にいるときは {@link #skyLift} が地下の度合いで 0 にする)。
     */
    public static float liftedSkyBrightness(DimensionType dimension, int level, float lift) {
        float gate = Mth.lerp(smooth(Mth.clamp(level / 7.0F, 0.0F, 1.0F)), LIFT_DARK, 1.0F);
        float f = (level + lift * gate * (15 - level)) / 15.0F;
        return Mth.lerp(dimension.ambientLight(), f / (4.0F - 3.0F * f), 1.0F);
    }

    /**
     * 光の型: 色だけの頂点・加算(雷と同じ足し方)・両面・深さは読むが書かない(粒や半透明の葉を切らない)。
     * 光の量はブロックの明るさに関係なく一定(加算なので暗い所ほど目立つ)。
     */
    /**
     * 光の筋・木漏れ日・林冠の影の板を描く、その場で描き切る自前の器(2026-10-22)。{@code mc.renderBuffers().bufferSource()} は Iris が入っていると
     * (シェーダーパック無しでも)ワールドを描いている間 Iris のまとめ描きの器に替わり、{@code endBatch} しても後でまとめて描かれる。
     * こちらはカメラからの位置で頂点を作るので、カメラが動いている間はその分ずれ、地面すれすれの木漏れ日が下りると地面に沈んで消え・上ると浮いた
     * (ユーザーの runClient、Sodium + Iris + DH。テストクライアントで Iris だけを足すと起き、Sodium だけでは起きない)。
     */
    static MultiBufferSource.BufferSource immediateBuffers() {
        if (immediateBuffers == null) {
            immediateBuffers = MultiBufferSource.immediate(new com.mojang.blaze3d.vertex.ByteBufferBuilder(1 << 18));
        }
        return immediateBuffers;
    }

    private static MultiBufferSource.BufferSource immediateBuffers;

    /**
     * 「最高(ファビュラス)」で加算の光(光の筋・木漏れ日・光る胞子)を主画面へ直に描く(2026-10-24)。ファビュラスの AFTER_PARTICLES は
     * 粒の層(particlesTarget)が描き先で、合成(transparency.fsh)はその層の画素を「不透明度 a が 0 なら丸ごと捨てる・それ以外は背景を (1 − a) 倍して rgb を足す」。
     * 加算(SRC_ALPHA, ONE)は不透明度に a² しか積まないので、8 ビットで 0 に丸まる薄い所の光は消え、越えた所から急に足される
     * → 帯の多角形の輪郭が明るい八角形の縁に見えた(DEV_NOTES「ファビュラスの光の筋の八角形の縁」)。主画面の奥行きは粒の層と同じ(写しの元)なので、
     * 幹に隠れるのは同じ。ファンシー以下・シェーダーパック使用中は何もしない。開発用: {@link #debugHide} の 4096 で前の描き先(粒の層)に戻す。
     *
     * @return 描き先を替えたら true({@link #restoreTarget} に渡す)
     */
    static boolean drawToMainTarget() {
        if (!Minecraft.useShaderTransparency() || ShaderPacks.active() || (debugHide & 4096) != 0) {
            return false;
        }
        Minecraft.getInstance().getMainRenderTarget().bindWrite(false);
        return true;
    }

    /** {@link #drawToMainTarget} で替えた描き先を粒の層へ戻す(この後のほかの MOD の AFTER_PARTICLES のため)。 */
    static void restoreTarget(boolean switched) {
        if (switched) {
            RenderStateShard.PARTICLES_TARGET.setupRenderState();
        }
    }

    /**
     * AFTER_WEATHER で {@link #drawToMainTarget} を使ったときの戻し(天気の層へ)。「最高」の天気の層は消すだけで奥行きを主画面から写さないので、
     * そこへ描いた帯は地形に隠れず、合成で奥行き 1(空)扱いになって空の所にしか出なかった。主画面へ描けば地形と正しく前後する(2026-10-24)。
     */
    static void restoreWeatherTarget(boolean switched) {
        if (switched) {
            RenderStateShard.WEATHER_TARGET.setupRenderState();
        }
    }

    /** シェーダーパック使用中の型(雷のシェーダー。下の {@link #renderType} の説明)。 */
    private static RenderType packRenderType;

    /**
     * 光の筋・風の帯などの加算の型。パック使用中({@link ShaderPacks#active})は、シェーダーだけバニラの雷のもの({@code RENDERTYPE_LIGHTNING_SHADER})にする
     * (2026-10-23)。Iris はバニラのシェーダーをパックのものに替えるが、Complementary の {@code gbuffers_basic}(POSITION_COLOR の替わり)は頂点の色を
     * {@code flat}(三角ごとに 1 色)で受けるので、端が透明・中が白の帯が三角ごとに白と透明の市松になった。雷の替わり {@code gbuffers_lightning} は色をなめらかに受ける。
     */
    static RenderType renderType() {
        if (ShaderPacks.active()) {
            if (packRenderType == null) {
                packRenderType = RenderType.create(
                        OvergrownVegetation.MODID + "_canopy_light_pack",
                        DefaultVertexFormat.POSITION_COLOR,
                        VertexFormat.Mode.QUADS,
                        16384,
                        false,
                        false,
                        RenderType.CompositeState.builder()
                                .setShaderState(RenderStateShard.RENDERTYPE_LIGHTNING_SHADER)
                                .setTransparencyState(RenderStateShard.LIGHTNING_TRANSPARENCY)
                                .setCullState(RenderStateShard.NO_CULL)
                                .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                                .createCompositeState(false));
            }
            return packRenderType;
        }
        if (renderType == null) {
            renderType = RenderType.create(
                    OvergrownVegetation.MODID + "_canopy_light",
                    DefaultVertexFormat.POSITION_COLOR,
                    VertexFormat.Mode.QUADS,
                    16384,
                    false,
                    false,
                    RenderType.CompositeState.builder()
                            .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
                            .setTransparencyState(RenderStateShard.LIGHTNING_TRANSPARENCY)
                            .setCullState(RenderStateShard.NO_CULL)
                            .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                            .createCompositeState(false));
        }
        return renderType;
    }

    private CanopyLight() {
    }
}
