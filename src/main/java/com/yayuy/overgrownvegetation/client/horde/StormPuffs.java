package com.yayuy.overgrownvegetation.client.horde;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 地平線の<b>胞子の嵐</b>(遠景の身代わりの 4 段目。{@link HordeStormRenderer})の中身。Minecraft のクラスに触らない素の計算だけにしてある
 * ({@code tools/horde-storm} が起動せずに同じ計算で PNG を描いて確かめる)。描く板はすべて {@link Sink} へ渡す(描き方は呼ぶ側)。
 *
 * <ul>
 *     <li><b>雲の塊の絵</b>: 柔らかい塊 4 種と、芯の詰まった濃い塊 4 種(値ノイズの重ね合わせで縁をちぎった円。色は白〜灰の陰影 = 上が
 *     明るい、濃さは透明度)を {@value #ATLAS_W} × {@value #ATLAS_H} 画素に並べる。起動後に作るので素材ファイルは増えない。</li>
 *     <li><b>群れの嵐の柱</b>({@link #storms}): 描く奥行き(描画距離と霧の終わりの近い方)の外の地上の群れごとに、頭数に合わせた大きさの
 *     塊を、下ほど多く・大きく・濃く並べる(群れの後ろへ流れる)。</li>
 *     <li><b>地平線の嵐の壁</b>({@link #wall}): 描く奥行きの {@value #WALL_DISTANCE} 倍の輪に、方角ごと({@value #WALL_BINS} 区切り)の
 *     横長の塊を積んだ壁(下は濃い塊、上ほど柔らかく薄い)。濃さは<b>その方角の群れの頭数</b>(左右 {@value #WALL_KERNEL_DEGREES} 度に
 *     重みを付けて足す。{@value #WALL_SATURATION} 体で最大)で、群れが全方位にいれば地平線がぐるりと隠れる(2026-09-25 のユーザー
 *     「地平線の胞子の嵐の量を増やせますか?地平線が見えないレベルにまで上げてもらって構わない」)。壁の上端は目の高さより上(地平線を
 *     越える)。濃さは時間で寄せる(群れが実体になって一覧から消えても、ぱっと消えない)。</li>
 *     <li><b>動き</b>: 塊は 1 つずつ、周期(7〜13 秒)の間に少しずつ昇って膨らみ、濃くなって薄れる。位相がばらばらなので湧き返り続ける。</li>
 * </ul>
 * 数値はすべて仮決め 2026-09-25。
 */
public final class StormPuffs {

    /** 塊の絵 1 枚の画素数(一辺)。 */
    public static final int TILE = 64;
    /** 柔らかい塊の絵の数(番号 0〜3)。 */
    public static final int SOFT_TILES = 4;
    /** 芯の詰まった濃い塊の絵の数(番号 4〜7)。 */
    public static final int DENSE_TILES = 4;
    /** 並べた絵全体の画素数(横 4 枚 × 縦 2 枚)。 */
    public static final int ATLAS_W = TILE * 4;
    public static final int ATLAS_H = TILE * 2;
    /** 絵 1 枚の u・v の幅(0〜1)。 */
    public static final float TILE_U = 0.25F;
    public static final float TILE_V = 0.5F;

    /** 壁の方角の区切りの数(1 区切り 7.5 度)。 */
    public static final int WALL_BINS = 48;
    /** 壁の 1 区切りの塊の数(高さを 6 段に分けて 1 つずつ)。 */
    public static final int WALL_PUFFS_PER_BIN = 6;
    /** 壁の遠さ(描く奥行きの何倍か)。 */
    public static final double WALL_DISTANCE = 1.3D;
    /** 群れが壁を濃くする角度の幅(片側、度)。 */
    public static final double WALL_KERNEL_DEGREES = 40.0D;
    /** この頭数(方角の重みを付けた和)で壁が最大の濃さ。 */
    public static final float WALL_SATURATION = 10.0F;
    /** 壁の高さの最低(地面から、ブロック)。 */
    private static final double WALL_MIN_HEIGHT = 56.0D;
    /** 壁の上端は、目の高さから壁までの距離 × これより上(約 13.5 度。地平線を必ず越える)。 */
    private static final double WALL_ELEVATION = 0.24D;
    /** 壁の下端は地面よりこれだけ下(地平線の下の霧との境目を隠す)。 */
    private static final double WALL_BELOW = 8.0D;
    /** 壁の塊の横幅は、1 区切りの弧の長さのこれ倍以上(隣と重ねて隙間を作らない)。 */
    private static final double WALL_MIN_WIDTH_ARCS = 4.0D;
    /** 壁が濃くなる速さ(秒。この時間で約 63%)。 */
    private static final double WALL_RISE_SECONDS = 1.5D;
    /** 壁が薄れる速さ(秒)。ゆっくり(一斉投入の群れが実体になって控えが生まれるまでの 1 秒ほど、群れが一覧から消えても壁は残る)。 */
    private static final double WALL_FALL_SECONDS = 5.0D;
    /** これより薄い塊は描かない。 */
    private static final float MIN_ALPHA = 0.004F;

    /**
     * 描く板 1 枚を受け取る口: カメラからの相対位置・半分の横幅と高さ・面内の回り(ラジアン)・絵の番号・色(0〜1)・濃さ(0〜1)。
     */
    public interface Sink {
        void puff(double x, double y, double z, float halfWidth, float halfHeight, float spin, int tile, float r, float g, float b,
                  float alpha);
    }

    /**
     * 地上の群れ 1 つ(カメラからの相対位置)。
     *
     * @param id       群れの番号(塊の並びの種)
     * @param heads    頭数
     * @param dx       中心の x − カメラの x
     * @param groundDy 群れの足元(地面)の y − カメラの y
     * @param dz       中心の z − カメラの z
     * @param forwardX 群れの向き(単位ベクトル)
     * @param forwardZ 同じく z
     */
    public record SwarmView(int id, int heads, double dx, double groundDy, double dz, float forwardX, float forwardZ) {
    }

    /**
     * 1 フレームの条件。
     *
     * @param limit      描く奥行き(描画距離と霧の終わりの近い方、ブロック)
     * @param maxDraw    投影の奥の面に収まる遠さ(これより遠い板は同じ視角のまま近くへ寄せて縮める)
     * @param seconds    時刻(秒。湧き返りの位相)
     * @param fog        霧の色(0〜1 の RGB)
     * @param brightness 昼夜の明るさ(0.2〜1)
     * @param density    濃さの掛け率(設定 {@code hordeStormDensity}、既定 1)
     */
    public record Frame(double limit, double maxDraw, double seconds, float[] fog, float brightness, float density) {
    }

    /** 壁の方角ごとの濃さ(時間で寄せる)。描く側が 1 つ持ち続ける。 */
    public static final class WallState {
        final float[] shown = new float[WALL_BINS];
        double lastSeconds = Double.NaN;

        /** 全部 0 に戻す(ディメンションが替わった)。 */
        public void reset() {
            java.util.Arrays.fill(this.shown, 0.0F);
            this.lastSeconds = Double.NaN;
        }

        /** いま描いている濃さの合計(0 なら何も描いていない)。 */
        public float total() {
            float sum = 0.0F;
            for (float v : this.shown) {
                sum += v;
            }
            return sum;
        }
    }

    /**
     * 群れの嵐の柱の塊 1 つ(嵐の中心・群れの向きに対して。ブロック)。
     *
     * @param forward   前(群れの進む向き)へのずれ
     * @param side      横へのずれ
     * @param rise      地面からの高さ(塊の中心)
     * @param size      大きさ(一辺)
     * @param alpha     濃さ(0〜1)
     * @param shade     明るさ(0 = 下の暗い色、1 = 上の明るい色)
     * @param period    湧き返りの周期(秒)
     * @param phase     周期の位相(0〜1)
     * @param spin      回りの向き(ラジアン)
     * @param spinSpeed 回る速さ(ラジアン / 秒)
     * @param tile      絵の番号(0〜7)
     */
    public record Puff(float forward, float side, float rise, float size, float alpha, float shade, float period, float phase,
                       float spin, float spinSpeed, int tile) {
    }

    /**
     * 壁の塊 1 つ(方角の区切りに対して)。
     *
     * @param angle  区切りの真ん中からの角度のずれ(ラジアン)
     * @param depth  壁の遠さに掛ける(0.95〜1.3)
     * @param height 壁の下端から上端までのどこか(0〜1、塊の中心)
     * @param size   高さ(壁の高さに掛ける)
     * @param aspect 横幅 ÷ 高さ(下ほど横長。負なら左右を裏返す)
     * @param spin   傾き(ラジアン。横長のまま小さく揺れる)
     */
    public record WallPuff(float angle, float depth, float height, float size, float aspect, float alpha, float shade, float period,
                           float phase, float spin, int tile) {
    }

    /**
     * 塊 1 つの、時刻 {@code seconds} の姿。
     *
     * @param lift  高さに足す(ブロック)
     * @param size  大きさ(ブロック、一辺)
     * @param alpha 濃さ(0〜1)
     * @param spin  回りの向き(ラジアン)
     */
    public record Pose(float lift, float size, float alpha, float spin) {
    }

    // ------------------------------------------------------------------ 群れの嵐の柱

    /** 嵐の横の広がり(ブロック、半径)= 16 + 4 × √頭数(6 体で約 26)。 */
    public static float radius(int heads) {
        return 16.0F + 4.0F * (float) Math.sqrt(Math.max(1, heads));
    }

    /** 嵐の高さ(ブロック)= 半径 × 2.6(6 体で約 67。地平線の壁の上にそびえる)。 */
    public static float height(int heads) {
        return radius(heads) * 2.6F;
    }

    /** 群れ 1 つぶんの塊の並び(群れの番号 {@code id} で決まる = どのフレームでも同じ)。数は 24 + 3 × 頭数(24〜64)。 */
    public static List<Puff> layout(int id, int heads) {
        float r = radius(heads);
        float h = height(heads);
        int n = Math.max(24, Math.min(64, 24 + 3 * heads));
        Random random = new Random(id * 0x9E3779B97F4A7C15L ^ 0x5DEECE66DL);
        List<Puff> puffs = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            // 高さの割合は下に寄せる(裾が厚い)。上の塊ほど小さく・薄く・明るく、細く(柱に)なり、群れの後ろへ流れる。裾は濃い塊。
            float t = (float) Math.pow(random.nextFloat(), 1.3D);
            double angle = random.nextDouble() * Math.PI * 2.0D;
            float reach = (float) Math.sqrt(random.nextDouble()) * (1.0F - 0.45F * t);
            float forward = (float) Math.cos(angle) * reach * r * 1.1F - t * r * 0.35F;
            float side = (float) Math.sin(angle) * reach * r;
            float size = (0.5F - 0.22F * t) * h * (0.75F + 0.5F * random.nextFloat());
            float rise = t * h * 0.75F + size * 0.3F;
            float alpha = 0.9F - 0.45F * t;
            float shade = Math.min(1.0F, 0.15F + 0.85F * t + 0.15F * random.nextFloat());
            float period = 7.0F + 6.0F * random.nextFloat();
            float phase = random.nextFloat();
            float spin = random.nextFloat() * (float) (Math.PI * 2.0D);
            float spinSpeed = (random.nextFloat() - 0.5F) * 0.5F;
            int tile = t < 0.3F ? SOFT_TILES + random.nextInt(DENSE_TILES) : random.nextInt(SOFT_TILES);
            puffs.add(new Puff(forward, side, rise, size, alpha, shade, period, phase, spin, spinSpeed, tile));
        }
        return puffs;
    }

    /** 塊の、時刻 {@code seconds} の姿: 周期の間に嵐の高さの 1/4 ほど昇って 1.4 倍へ膨らみ、現れて消える。 */
    public static Pose pose(Puff puff, float height, double seconds) {
        float c = cycle(seconds, puff.period(), puff.phase());
        float envelope = (float) Math.pow(Math.sin(Math.PI * c), 0.6D);
        return new Pose(c * height * 0.25F, puff.size() * (0.8F + 0.6F * c), puff.alpha() * envelope,
                puff.spin() + puff.spinSpeed() * (float) seconds);
    }

    /** 群れの並びの控え(描画スレッドだけが触る)。 */
    private static final Map<Long, List<Puff>> LAYOUTS = new HashMap<>();

    private static List<Puff> cachedLayout(int id, int heads) {
        if (LAYOUTS.size() > 512) {
            LAYOUTS.clear(); // 消えた群れの並びを溜めない
        }
        return LAYOUTS.computeIfAbsent(((long) id << 16) ^ heads, k -> layout(id, heads));
    }

    /**
     * 群れの嵐の柱を描く: 描く奥行きの外の群れだけ(奥行きから 1.15 倍までで濃さ 0 → 1。内側は霧の中の絵に任せる)。
     * 投影の奥の面より遠ければ同じ視角のまま近くへ寄せて縮める。
     */
    public static void storms(List<SwarmView> swarms, Frame frame, Sink sink) {
        float fogLuminance = luminance(frame.fog()[0], frame.fog()[1], frame.fog()[2]);
        for (SwarmView s : swarms) {
            if (s.heads() <= 0) {
                continue;
            }
            double horizontal = Math.sqrt(s.dx() * s.dx() + s.dz() * s.dz());
            float fade = fadeIn(horizontal, frame.limit());
            if (fade <= 0.0F) {
                continue;
            }
            float haze = haze(horizontal, frame.limit());
            float height = height(s.heads());
            double mid = s.groundDy() + height * 0.5D;
            double far = Math.sqrt(horizontal * horizontal + mid * mid);
            double shrink = far > frame.maxDraw() ? frame.maxDraw() / far : 1.0D;
            float fx = s.forwardX();
            float fz = s.forwardZ();
            for (Puff p : cachedLayout(s.id(), s.heads())) {
                Pose pose = pose(p, height, frame.seconds());
                float alpha = Math.min(1.0F, pose.alpha() * fade * frame.density());
                if (alpha < MIN_ALPHA) {
                    continue;
                }
                double px = s.dx() + fx * p.forward() - fz * p.side();
                double py = s.groundDy() + p.rise() + pose.lift();
                double pz = s.dz() + fz * p.forward() + fx * p.side();
                float half = (float) (pose.size() * shrink * 0.5D);
                emit(sink, px * shrink, py * shrink, pz * shrink, half, half, pose.spin(), p.tile(),
                        color(p.shade(), fogLuminance, frame.brightness()), frame.fog(), haze, alpha);
            }
        }
    }

    // ------------------------------------------------------------------ 地平線の嵐の壁

    /**
     * 壁の 1 区切りの塊の並び(区切りの番号で決まる)。高さは 6 段に 1 つずつ(縦に隙間ができない)。下ほど濃い塊・横長・濃く暗く、
     * 上ほど柔らかい塊・丸く・薄く明るい。
     */
    public static List<WallPuff> wallLayout(int bin) {
        Random random = new Random(0x51F15EEDL ^ bin * 0x9E3779B97F4A7C15L);
        double binWidth = Math.PI * 2.0D / WALL_BINS;
        List<WallPuff> puffs = new ArrayList<>(WALL_PUFFS_PER_BIN);
        for (int i = 0; i < WALL_PUFFS_PER_BIN; i++) {
            float t = (i + 0.2F + 0.6F * random.nextFloat()) / WALL_PUFFS_PER_BIN;
            float angle = (float) ((random.nextDouble() - 0.5D) * binWidth);
            float depth = 0.95F + 0.35F * random.nextFloat();
            float size = (0.5F - 0.2F * t) * (0.9F + 0.2F * random.nextFloat());
            float aspect = (2.4F - 1.3F * t) * (0.9F + 0.2F * random.nextFloat());
            float alpha = 1.0F - 0.4F * t;
            float shade = Math.min(1.0F, 0.08F + 0.85F * t + 0.12F * random.nextFloat());
            float period = 7.0F + 6.0F * random.nextFloat();
            float phase = random.nextFloat();
            float spin = (float) ((random.nextDouble() - 0.5D) * 0.4D);
            // 左右の裏返し(横幅の符号)で塊の形をばらす。上下は返さない(上が明るい陰影が逆になる)。
            if (random.nextBoolean()) {
                aspect = -aspect;
            }
            int tile = t < 0.4F ? SOFT_TILES + random.nextInt(DENSE_TILES) : random.nextInt(SOFT_TILES);
            puffs.add(new WallPuff(angle, depth, t, size, aspect, alpha, shade, period, phase, spin, tile));
        }
        return puffs;
    }

    private static final List<List<WallPuff>> WALL_LAYOUTS = new ArrayList<>(WALL_BINS);

    static {
        for (int b = 0; b < WALL_BINS; b++) {
            WALL_LAYOUTS.add(wallLayout(b));
        }
    }

    /** 群れの向きとの角度の差 {@code delta}(ラジアン)の重み(0〜1。{@value #WALL_KERNEL_DEGREES} 度で 0 になる cos²)。 */
    public static float wallKernel(double delta) {
        double half = Math.toRadians(WALL_KERNEL_DEGREES);
        double d = Math.abs(delta);
        if (d >= half) {
            return 0.0F;
        }
        double c = Math.cos(d / half * Math.PI * 0.5D);
        return (float) (c * c);
    }

    /** 壁の頂の高さの揺らぎ(0.8〜1.2)。方角と時刻でゆっくり変わる(嵐の稜線が波打つ)。 */
    public static float wallCrest(double angle, double seconds) {
        double v = Math.sin(angle * 3.0D + seconds * 0.07D) * 0.55D + Math.sin(angle * 7.0D - seconds * 0.11D + 1.3D) * 0.3D
                + Math.sin(angle * 13.0D + seconds * 0.05D + 2.1D) * 0.15D;
        return (float) (1.0D + 0.2D * v);
    }

    /**
     * 地平線の嵐の壁を描く。方角ごとに、群れの頭数を角度の重みで足した量から濃さを決め({@code density} を掛けて最大 1)、
     * 時間で寄せる。壁の遠さは描く奥行きの {@value #WALL_DISTANCE} 倍(投影の奥の面を越えない)、下端は群れの地面の高さ(重み付きの平均)
     * − {@value #WALL_BELOW}、上端は地面 + {@value #WALL_MIN_HEIGHT} と「目の高さ + 壁までの距離 × {@value #WALL_ELEVATION}」の高い方。
     * 空の群れは渡さない(ほこりを立てない)。
     */
    public static void wall(List<SwarmView> swarms, Frame frame, WallState state, Sink sink) {
        float[] target = new float[WALL_BINS];
        double[] baseSum = new double[WALL_BINS];
        double binWidth = Math.PI * 2.0D / WALL_BINS;
        for (SwarmView s : swarms) {
            if (s.heads() <= 0) {
                continue;
            }
            double angle = Math.atan2(s.dz(), s.dx());
            for (int b = 0; b < WALL_BINS; b++) {
                float w = wallKernel(wrap((b + 0.5D) * binWidth - angle)) * s.heads();
                if (w <= 0.0F) {
                    continue;
                }
                target[b] += w;
                baseSum[b] += w * s.groundDy();
            }
        }
        // 時間で寄せる(濃くなるのは速く、薄れるのはゆっくり。初めて・長く止まっていたらそのまま)。
        double dt = Double.isNaN(state.lastSeconds) ? Double.POSITIVE_INFINITY : frame.seconds() - state.lastSeconds;
        state.lastSeconds = frame.seconds();
        boolean jump = dt < 0.0D || dt > 10.0D;
        float rise = jump ? 1.0F : (float) (1.0D - Math.exp(-dt / WALL_RISE_SECONDS));
        float fall = jump ? 1.0F : (float) (1.0D - Math.exp(-dt / WALL_FALL_SECONDS));
        float[] base = new float[WALL_BINS];
        for (int b = 0; b < WALL_BINS; b++) {
            float goal = Math.min(1.0F, target[b] / WALL_SATURATION * frame.density());
            state.shown[b] += (goal - state.shown[b]) * (goal > state.shown[b] ? rise : fall);
            base[b] = target[b] > 0.0F ? (float) (baseSum[b] / target[b]) : Float.NaN;
        }
        float fogLuminance = luminance(frame.fog()[0], frame.fog()[1], frame.fog()[2]);
        double distance = Math.min(frame.limit() * WALL_DISTANCE, frame.maxDraw() * 0.75D);
        double arc = distance * binWidth;
        for (int b = 0; b < WALL_BINS; b++) {
            float shown = state.shown[b];
            if (shown < 0.01F) {
                continue;
            }
            // 群れが遠ざかった方角は、隣の区切りの地面の高さを借りる(無ければ目の 8 ブロック下)。
            double ground = !Float.isNaN(base[b]) ? base[b] : nearestBase(base, b, -8.0D);
            double top = Math.max(ground + WALL_MIN_HEIGHT, distance * WALL_ELEVATION);
            double bottom = ground - WALL_BELOW;
            // 薄い方角(群れが少ない)は、壁を低くして上の段から消す(全部を薄くすると塊の形が透けて見える)。裾は濃いまま。
            double span = (top - bottom) * (0.45D + 0.55D * shown);
            double center = (b + 0.5D) * binWidth;
            for (WallPuff p : WALL_LAYOUTS.get(b)) {
                float c = cycle(frame.seconds(), p.period(), p.phase());
                // 壁の塊は消えきらない(0.6〜1 で揺らす。消えると地平線が透ける)。
                float envelope = 0.6F + 0.4F * (float) Math.pow(Math.sin(Math.PI * c), 0.6D);
                float strength = Math.max(0.0F, Math.min(1.0F, shown * 1.8F - p.height() * 0.7F));
                float alpha = Math.min(1.0F, p.alpha() * envelope * strength);
                if (alpha < MIN_ALPHA) {
                    continue;
                }
                double theta = center + p.angle();
                double h = p.height() > 0.5F ? p.height() * wallCrest(theta, frame.seconds()) : p.height();
                double height = p.size() * span * (0.9D + 0.25D * c);
                double width = Math.max(height * Math.abs(p.aspect()), arc * WALL_MIN_WIDTH_ARCS) * Math.signum(p.aspect());
                double d = distance * p.depth();
                double y = bottom + h * span + c * span * 0.05D;
                float spin = p.spin() + 0.12F * (float) Math.sin(frame.seconds() * (0.2D + 0.1D * p.phase()) + p.phase() * 6.0D);
                emit(sink, Math.cos(theta) * d, y, Math.sin(theta) * d, (float) (width * 0.5D), (float) (height * 0.5D), spin,
                        p.tile(), color(p.shade(), fogLuminance, frame.brightness()), frame.fog(), haze(d, frame.limit()), alpha);
            }
        }
    }

    private static double nearestBase(float[] base, int bin, double fallback) {
        for (int step = 1; step <= WALL_BINS / 2; step++) {
            float a = base[Math.floorMod(bin - step, WALL_BINS)];
            if (!Float.isNaN(a)) {
                return a;
            }
            float b = base[Math.floorMod(bin + step, WALL_BINS)];
            if (!Float.isNaN(b)) {
                return b;
            }
        }
        return fallback;
    }

    // ------------------------------------------------------------------ 色

    /** 明るい霧(昼の平原など)の前で使う色: 胞子の緑を土ぼこりでくすませた暗い色。裾・上。 */
    private static final float[] DARK_LOW = {0.18F, 0.25F, 0.12F};
    private static final float[] DARK_HIGH = {0.48F, 0.58F, 0.33F};
    /** 暗い霧(過繁茂の霧・夜)の前で使う色: 胞子の明るい黄緑。裾・上。 */
    private static final float[] LIGHT_LOW = {0.42F, 0.52F, 0.28F};
    private static final float[] LIGHT_HIGH = {0.72F, 0.82F, 0.52F};
    /** 暗くても残るかすかな光(胞子の粒子の色 × 0.1)。夜・濃い霧でも地平線に嵐がうっすら見えるように。 */
    private static final float[] GLOW = {0.034F, 0.072F, 0.027F};

    /**
     * 嵐の色(0〜1 の RGB、霞を掛ける前)。{@code shade} 0 = 裾、1 = 上。霧が明るいほど暗い色、暗いほど明るい色にする
     * (霧と同じ明るさだと溶けて見えない。過繁茂の霧は暗い緑)。昼夜の明るさ {@code brightness}(0.2〜1)を掛け、かすかな光を足す。
     *
     * @param fogLuminance 霧の色の明るさ(0〜1)
     */
    public static float[] color(float shade, float fogLuminance, float brightness) {
        float s = Math.max(0.0F, Math.min(1.0F, shade));
        float dark = (float) smoothstep(0.25D, 0.6D, fogLuminance);
        float[] out = new float[3];
        for (int k = 0; k < 3; k++) {
            float onDark = LIGHT_LOW[k] + (LIGHT_HIGH[k] - LIGHT_LOW[k]) * s;
            float onBright = DARK_LOW[k] + (DARK_HIGH[k] - DARK_LOW[k]) * s;
            out[k] = (onDark + (onBright - onDark) * dark) * brightness + GLOW[k];
        }
        return out;
    }

    /** 色の明るさ(0〜1)。 */
    public static float luminance(float r, float g, float b) {
        return 0.3F * r + 0.59F * g + 0.11F * b;
    }

    /**
     * 描く奥行きの外へ出てからの濃さの掛け率(0〜1): 描く奥行き {@code limit}(描画距離と霧の終わりの近い方)で 0、その 1.15 倍で 1。
     * 奥行きの手前は身代わりの絵(霧で消えていく)に任せ、嵐は霧の向こうからだけ見せる。
     */
    public static float fadeIn(double distance, double limit) {
        return (float) smoothstep(limit, limit * 1.15D, distance);
    }

    /** 霞(霧の色へ寄せる割合 0〜1): 描く奥行きのすぐ外で 0.45、遠くなるほど 0.7 まで(霧を切って描くぶんの大気遠近)。 */
    public static float haze(double distance, double limit) {
        return (float) (0.45D + 0.25D * clamp((distance - limit) / Math.max(1.0D, limit * 2.0D), 0.0D, 1.0D));
    }

    /** 絵の番号 {@code tile} の左上の u・v(0〜1)。 */
    public static float tileU(int tile) {
        return (tile & 3) * TILE_U;
    }

    public static float tileV(int tile) {
        return ((tile >> 2) & 1) * TILE_V;
    }

    private static void emit(Sink sink, double x, double y, double z, float halfWidth, float halfHeight, float spin, int tile,
                             float[] rgb, float[] fog, float haze, float alpha) {
        sink.puff(x, y, z, halfWidth, halfHeight, spin, tile, rgb[0] + (fog[0] - rgb[0]) * haze, rgb[1] + (fog[1] - rgb[1]) * haze,
                rgb[2] + (fog[2] - rgb[2]) * haze, alpha);
    }

    /** 湧き返りの周期の中の位置(0〜1)。 */
    private static float cycle(double seconds, float period, float phase) {
        double cycle = seconds / period + phase;
        return (float) (cycle - Math.floor(cycle));
    }

    /** 角度を −π〜π へ。 */
    private static double wrap(double a) {
        double d = a % (Math.PI * 2.0D);
        if (d > Math.PI) {
            d -= Math.PI * 2.0D;
        } else if (d <= -Math.PI) {
            d += Math.PI * 2.0D;
        }
        return d;
    }

    // ------------------------------------------------------------------ 雲の塊の絵

    /**
     * 並べた絵の画素(ARGB、{@value #ATLAS_W} × {@value #ATLAS_H}、行ごと)。番号 0〜3 は柔らかい塊、4〜7 は芯の詰まった濃い塊。
     * 色は白〜灰(上から光が当たったような陰影)、濃さは透明度。透明な画素にも色を入れておく(線形補間で縁が黒ずまないように)。
     */
    public static int[] atlas() {
        int[] out = new int[ATLAS_W * ATLAS_H];
        for (int tile = 0; tile < SOFT_TILES + DENSE_TILES; tile++) {
            boolean dense = tile >= SOFT_TILES;
            int ox = (tile & 3) * TILE;
            int oy = ((tile >> 2) & 1) * TILE;
            double seed = 13.37D * (tile + 1);
            for (int y = 0; y < TILE; y++) {
                for (int x = 0; x < TILE; x++) {
                    // −1〜1(1 画素ぶん内側に寄せて、隣の絵へにじまないように縁を必ず透明にする)。
                    double nx = ((x + 0.5D) / TILE * 2.0D - 1.0D) * 1.04D;
                    double ny = ((y + 0.5D) / TILE * 2.0D - 1.0D) * 1.04D;
                    double r = Math.sqrt(nx * nx + ny * ny);
                    double edgeNoise = fbm(nx * 2.1D + seed, ny * 2.1D - seed, 4);
                    double body = fbm(nx * 3.7D - seed * 0.7D, ny * 3.7D + seed * 1.3D, 4);
                    double alpha;
                    if (dense) {
                        // 芯が広く詰まった塊(壁の下の段・柱の裾。重ねると向こうが透けない)。縁はちぎる(横に伸ばしても楕円に見えない)。
                        double edge = 0.8D + 0.26D * (edgeNoise - 0.5D) * 2.0D;
                        double inside = 1.0D - smoothstep(edge * 0.6D, edge, r);
                        alpha = clamp(inside * (0.8D + 0.2D * body) * 1.35D, 0.0D, 1.0D);
                    } else {
                        // 縁をちぎった柔らかい塊: 半径の閾値をノイズで揺らす。
                        double edge = 0.66D + 0.34D * (edgeNoise - 0.5D) * 2.0D;
                        double inside = 1.0D - smoothstep(edge * 0.45D, edge, r);
                        alpha = clamp(inside * (0.55D + 0.45D * body) * 1.25D, 0.0D, 1.0D);
                    }
                    // 上が明るく下が暗い陰影 + むら。
                    double shade = clamp(0.80D - 0.16D * ny + 0.22D * (body - 0.5D), 0.45D, 1.0D);
                    int a = (int) Math.round(alpha * 255.0D);
                    int g = (int) Math.round(shade * 255.0D);
                    out[(oy + y) * ATLAS_W + ox + x] = a << 24 | g << 16 | g << 8 | g;
                }
            }
        }
        return out;
    }

    private static double smoothstep(double edge0, double edge1, double x) {
        double t = clamp((x - edge0) / (edge1 - edge0), 0.0D, 1.0D);
        return t * t * (3.0D - 2.0D * t);
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    /** 値ノイズの重ね合わせ(0〜1)。 */
    private static double fbm(double x, double y, int octaves) {
        double sum = 0.0D;
        double amp = 0.5D;
        double total = 0.0D;
        for (int i = 0; i < octaves; i++) {
            sum += valueNoise(x, y) * amp;
            total += amp;
            x = x * 2.03D + 17.1D;
            y = y * 2.03D - 9.3D;
            amp *= 0.5D;
        }
        return sum / total;
    }

    private static double valueNoise(double x, double y) {
        int ix = (int) Math.floor(x);
        int iy = (int) Math.floor(y);
        double fx = x - ix;
        double fy = y - iy;
        double sx = fx * fx * (3.0D - 2.0D * fx);
        double sy = fy * fy * (3.0D - 2.0D * fy);
        double a = hash(ix, iy);
        double b = hash(ix + 1, iy);
        double c = hash(ix, iy + 1);
        double d = hash(ix + 1, iy + 1);
        return (a + (b - a) * sx) + ((c + (d - c) * sx) - (a + (b - a) * sx)) * sy;
    }

    private static double hash(int x, int y) {
        long h = x * 0x27D4EB2DL + y * 0x165667B1L;
        h = (h ^ (h >>> 15)) * 0x85EBCA6BL;
        h = (h ^ (h >>> 13)) * 0xC2B2AE35L;
        h ^= h >>> 16;
        return (h & 0xFFFFFFL) / (double) 0xFFFFFFL;
    }

    private StormPuffs() {
    }
}
