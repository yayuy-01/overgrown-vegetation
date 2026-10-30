package com.yayuy.overgrownvegetation.client;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * 汚染地域の<b>風</b>(2026-10-15 夜。ユーザー「葉が風に揺られるのが無いので、木漏れ日や差し込む光がずっと同じ場所に
 * い続けておかしく見える。ランダムに風が吹いて葉を揺らしているような感じに」→ 案 A「風の場で光と粒を一緒に動かす」)。
 *
 * <p>風は 1 つだけで、木漏れ日({@link CanopyDapple})・光の筋と光る胞子({@link CanopyLight})・落ち葉({@link CanopyLeaves})・
 * バイオームの環境の胞子({@link DriftingSporeParticle})・風の音({@link com.yayuy.overgrownvegetation.client.sound.CanopyWindSound})が
 * みな同じ風を読む(進めるのは {@link CanopyWindDriver})。ばらばらに揺れると機械的に見え、同じ風に一緒に反応すると風が見える(Vault の 光と風の演出/ の教科書)。
 * 葉のブロックそのものは動かさない(それは案 B、描画の上書き)。
 *
 * <h2>中身</h2>
 * <ul>
 *     <li><b>凪の空気の動き</b>: 強さ 0.07〜0.17(天気によらない)。向きはゆっくりさまよう。凪いでいても 0 にはしない
 *     (止まって見えないように、木漏れ日のかすかなきらめきを残す)。風の音はこの強さでは鳴らず
 *     ({@link com.yayuy.overgrownvegetation.client.sound.WindSoundModel})、植物も揺れない({@link LeafSway} は風の音が鳴る間だけ揺らす)。
 *     前は雨・雷で 0.25 ほど強まり、雨の間は風の音が鳴り続け、植物も揺れ続けていた(2026-10-16 に外した)。</li>
 *     <li><b>突風</b>: 時折(60〜120 秒おき。雨・雷では間が 3 割縮み(7 割に)、少し強い)、カメラの 35〜55 ブロック
 *     風上で生まれ(3 秒かけて強まる)、毎秒 3〜6 ブロックで林を渡る幅 36〜80 ブロックの風の塊。来るときは速く強まり、去るときはゆっくり弱まる
 *     (前は短く後ろは長い山)。1 か所で 2〜5 秒ほど続く。最初は平均 8 秒おき(いつも風が吹いている)だったが、ユーザー「風は常にあるものではなく、
 *     時折発生する感じに」(2026-10-15 夜)で 25〜75 秒おき(4 回に 1 回はすぐ次が続く)に延ばし、さらにユーザー「大体 1〜2 分おきに風の音声が
 *     流れるように」(2026-10-16)で 60〜120 秒おき・続けて吹くのは無しにした。</li>
 *     <li>場所ごとの細かいむら(弱い揺らぎ)。</li>
 * </ul>
 * 強さは時刻(tick + 描画の途中の割合)で連続に決まるので、描画のたびに読んでもかくつかない。
 * ほかのクライアントとは揃えない(見た目だけ。マルチプレイで人ごとに突風が違っても困らない)。
 *
 * <p><b>Minecraft の型を使わない</b>(素の java で数値を検算できるように。memory client-code-check-without-minecraft)。
 * 数値はすべて仮決め(現実の観察: 葉のはためき毎秒 3〜5 回・枝の揺れ毎秒 0.25 回前後・突風の間隔は推定)。
 */
public final class CanopyWind {

    /** 突風の上限(同時に吹いている数)。 */
    private static final int MAX_GUSTS = 4;
    /** 突風の強さの上限(弱い風と重なっても、ここで頭打ち)。 */
    private static final double MAX_STRENGTH = 1.2;

    private static final Random RANDOM = new Random();
    private static final List<Gust> GUSTS = new ArrayList<>();

    /** 進めた tick 数(ポーズ中は進まない)。 */
    private static long ticks;
    /** 弱い風の向き(ラジアン。x-z 平面で、風が流れていく向き)と、そのさまよう速さ。 */
    private static double baseAngle = RANDOM.nextDouble() * Math.PI * 2.0;
    private static double baseAngleVel;
    /** 卓越風の向き(サーバーと同じ式。NaN = 受け取っていない → 今までどおりさまよう。2026-10-23 胞子の場と風 第 2 段 B)。 */
    private static double prevailingAngle = Double.NaN;
    private static double baseStrength = 0.12;
    private static double baseTarget = 0.12;
    private static int baseRetargetIn = 400;
    private static int nextGustIn = 60;
    /** 外から掛ける倍率(設定 canopyWindStrength。0 = 無風)。 */
    private static double scale = 1.0;

    /** 1 つの突風(風の塊)。生まれた所から向きへ一定の速さで進む。 */
    static final class Gust {
        final double startX;
        final double startZ;
        final double dirX;
        final double dirZ;
        /** 毎 tick 進むブロック数。 */
        final double speed;
        final double strength;
        /** 塊の山の前(来る側)と後ろ(去る側)の長さ、横の半分の幅(ブロック)。 */
        final double lenAhead;
        final double lenBehind;
        final double halfWidth;
        final long born;

        Gust(double startX, double startZ, double dirX, double dirZ, double speed, double strength,
             double lenAhead, double lenBehind, double halfWidth, long born) {
            this.startX = startX;
            this.startZ = startZ;
            this.dirX = dirX;
            this.dirZ = dirZ;
            this.speed = speed;
            this.strength = strength;
            this.lenAhead = lenAhead;
            this.lenBehind = lenBehind;
            this.halfWidth = halfWidth;
            this.born = born;
        }

        double centerX(double time) {
            return this.startX + this.dirX * this.speed * (time - this.born);
        }

        double centerZ(double time) {
            return this.startZ + this.dirZ * this.speed * (time - this.born);
        }

        /**
         * (x, z) での強さ。来る側は短く速く強まり、去る側は長くゆっくり弱まる。生まれてから 3 秒かけて強まる
         * (生まれる所はカメラから 35〜55 ブロック風上で目には見えないが、風の音では「遠くの梢が鳴り出す」のが聞こえるので、
         * いきなり全力で生まれると、遠くの音がぷつっと鳴り出した。2026-10-15 夜)。
         */
        double at(double x, double z, double time) {
            double rx = x - centerX(time);
            double rz = z - centerZ(time);
            double along = rx * this.dirX + rz * this.dirZ;
            double side = -rx * this.dirZ + rz * this.dirX;
            double len = along > 0.0 ? this.lenAhead : this.lenBehind;
            double a = along / len;
            double s = side / this.halfWidth;
            double exponent = a * a + s * s;
            if (exponent > 12.0) {
                return 0.0;
            }
            double grow = Math.min(1.0, Math.max(0.0, (time - this.born) / 60.0));
            return this.strength * grow * grow * (3.0 - 2.0 * grow) * Math.exp(-exponent);
        }
    }

    /**
     * 1 tick 進める。{@code camX}・{@code camZ} はカメラの位置(突風はその風上で生まれる)、{@code rain}・{@code thunder} は 0〜1。
     * {@code strengthScale} は設定の倍率。
     */
    public static void tick(double camX, double camZ, float rain, float thunder, double strengthScale) {
        ticks++;
        scale = Math.max(0.0, strengthScale);
        double weather = Math.min(1.0, rain + 0.5 * thunder);

        // 弱い風: 強さは数十秒おきに行き先を決め直してゆっくり寄せる。向きはさまよう(急には変わらない)。
        // 雨でも強めない(風の音が鳴り出す強さ 0.22 を超えると、雨の間ずっと鳴り続け、植物も揺れ続ける)。
        if (--baseRetargetIn <= 0) {
            baseTarget = 0.07 + RANDOM.nextDouble() * 0.10;
            baseRetargetIn = 400 + RANDOM.nextInt(500);
        }
        baseStrength += (baseTarget - baseStrength) * 0.004;
        if (Double.isNaN(prevailingAngle)) {
            baseAngleVel = baseAngleVel * 0.985 + RANDOM.nextGaussian() * 0.00025;
            baseAngle += baseAngleVel;
        } else {
            // 卓越風を土台に: 向きはいちばん近い回り方で毎 tick 2% ずつ寄せる(前線の通過で回るときも急には振れない)
            baseAngle += Math.IEEEremainder(prevailingAngle - baseAngle, Math.PI * 2.0) * 0.02;
            baseAngleVel = 0.0;
        }

        // 突風を生む。
        if (--nextGustIn <= 0) {
            spawnGust(camX, camZ, weather);
            nextGustIn = nextInterval(weather);
        }

        // 通り過ぎた突風を捨てる(カメラの風下へ塊の後ろの長さの 3 倍 + 20 ブロック抜けたら)。
        double time = ticks;
        for (Iterator<Gust> it = GUSTS.iterator(); it.hasNext(); ) {
            Gust g = it.next();
            double behind = (camX - g.centerX(time)) * g.dirX + (camZ - g.centerZ(time)) * g.dirZ;
            if (behind < -(g.lenBehind * 3.0 + 20.0) || time - g.born > 2400) {
                it.remove();
            }
        }
    }

    /**
     * 次の突風までの tick。60〜120 秒おき(一様)。雨・雷では間が 3 割縮む(7 割の 42〜84 秒)。
     * 2026-10-16、ユーザー「常に音声を流し続けるのではなく、大体 1〜2 分おきに風の音声が流れるように」。
     * その前は 25〜75 秒おきで 4 回に 1 回は 4〜10 秒後にもう 1 つ続いた(10-15 夜)、最初は 3〜18 秒おき。
     * 1 つの突風の音は 15〜25 秒ほど鳴る(風上 30 ブロックで鳴り出し、頭の上を通って風下へ遠ざかる)。
     */
    static int nextInterval(double weather) {
        double interval = 1200 + RANDOM.nextDouble() * 1200;
        return (int) (interval * (1.0 - 0.3 * weather));
    }

    private static void spawnGust(double camX, double camZ, double weather) {
        if (GUSTS.size() >= MAX_GUSTS) {
            return;
        }
        double angle = baseAngle + (RANDOM.nextDouble() - 0.5) * 0.7;
        double dx = Math.cos(angle);
        double dz = Math.sin(angle);
        double upwind = 35.0 + RANDOM.nextDouble() * 20.0;
        double lateral = (RANDOM.nextDouble() - 0.5) * 16.0;
        double startX = camX - dx * upwind - dz * lateral;
        double startZ = camZ - dz * upwind + dx * lateral;
        double speed = (3.0 + RANDOM.nextDouble() * 3.0) / 20.0;
        double strength = 0.3 + RANDOM.nextDouble() * 0.45 + 0.2 * weather;
        GUSTS.add(new Gust(startX, startZ, dx, dz, speed, strength,
                3.0 + RANDOM.nextDouble() * 2.0, 8.0 + RANDOM.nextDouble() * 8.0, 18.0 + RANDOM.nextDouble() * 22.0, ticks));
    }

    /**
     * 卓越風の向き(ラジアン。風が流れていく向き。x = 東、z = 南)を渡す。{@code NaN} で外す(さまよう風に戻る)。
     * 突風はこの向き ±20 度で生まれる(【決定】近距離の突風は卓越風 ±45 度の中で人ごと)。強さ・音・揺れは今のまま。
     */
    public static void setPrevailing(double angle) {
        if (Double.isNaN(prevailingAngle) && !Double.isNaN(angle)) {
            baseAngle = angle; // 初めて受け取ったときはその向きから
        }
        prevailingAngle = angle;
    }

    /** 開発用: カメラの風上に強めの突風を 1 つ生む(テストクライアントの {@code wind gust})。 */
    public static void forceGust(double camX, double camZ) {
        double angle = baseAngle;
        double dx = Math.cos(angle);
        double dz = Math.sin(angle);
        GUSTS.add(new Gust(camX - dx * 30.0, camZ - dz * 30.0, dx, dz, 4.5 / 20.0, 0.9, 5.0, 16.0, 30.0, ticks));
        while (GUSTS.size() > MAX_GUSTS) {
            GUSTS.remove(0);
        }
    }

    /** 開発用: 突風を消して、しばらく凪にする(テストクライアントの {@code wind calm})。 */
    public static void calm() {
        GUSTS.clear();
        nextGustIn = 1200;
    }

    /** 今の時刻(tick + 描画の途中の割合)。 */
    public static double time(float partialTick) {
        return ticks + partialTick;
    }

    /** (x, z) の風の強さ(0〜1.2。凪で 0.1〜0.3、突風のさなかで 0.6〜1)。設定の倍率を掛けたもの。 */
    public static double strength(double x, double z, double time) {
        double w = baseStrength * (1.0 + 0.25 * Math.sin(x * 0.13 + time * 0.031) * Math.sin(z * 0.11 - time * 0.027));
        for (Gust g : GUSTS) {
            w += g.at(x, z, time);
        }
        return Math.min(MAX_STRENGTH, w) * scale;
    }

    /**
     * (x, z) の風を向きつきで {@code out} = {x, z} へ書く(大きさは {@link #strength} と同じ尺度。向きが揃わないぶん少し小さい)。
     * 弱い風は今の向き、突風はそれぞれ自分の向きへ吹く —— 胞子は突風ごとに違う向きへ一斉に流される
     * (2026-10-15 夜、ユーザー「突風が吹いた際は胞子がランダムな方向に一斉に動くように」)。
     */
    public static void velocity(double x, double z, double time, double[] out) {
        double base = baseStrength * (1.0 + 0.25 * Math.sin(x * 0.13 + time * 0.031) * Math.sin(z * 0.11 - time * 0.027));
        double vx = base * Math.cos(baseAngle);
        double vz = base * Math.sin(baseAngle);
        for (Gust g : GUSTS) {
            double a = g.at(x, z, time);
            vx += a * g.dirX;
            vz += a * g.dirZ;
        }
        double len = Math.sqrt(vx * vx + vz * vz);
        double k = len > MAX_STRENGTH ? MAX_STRENGTH / len : 1.0;
        out[0] = vx * k * scale;
        out[1] = vz * k * scale;
    }

    /** 今吹いている突風の数(風の音の見積もり・開発用)。 */
    public static int gustCount() {
        return GUSTS.size();
    }

    /** 凪の空気の動きの強さ(設定の倍率を掛けたもの。場所ごとのむらの前)。葉の揺れのシェーダーへ渡す。 */
    public static double calmStrength() {
        return baseStrength * scale;
    }

    /**
     * 葉の揺れのシェーダー({@link LeafSway})へ渡す突風。突風 1 つにつき 8 個 ——
     * (中心 x・中心 z(カメラ {@code camX}・{@code camZ} からの差)、向き x・z、強さ(生まれてからの強まりと設定の倍率を掛けた)、
     * 前の長さ・後ろの長さ・横の半分の幅)を {@code out} へ。最大 {@value #MAX_GUSTS} 個、足りない分は強さ 0。
     */
    static void fillGusts(double time, double camX, double camZ, float[] out) {
        java.util.Arrays.fill(out, 0.0F);
        int i = 0;
        for (Gust g : GUSTS) {
            if (i >= MAX_GUSTS || out.length < (i + 1) * 8) {
                break;
            }
            double grow = Math.min(1.0, Math.max(0.0, (time - g.born) / 60.0));
            int at = i * 8;
            out[at] = (float) (g.centerX(time) - camX);
            out[at + 1] = (float) (g.centerZ(time) - camZ);
            out[at + 2] = (float) g.dirX;
            out[at + 3] = (float) g.dirZ;
            out[at + 4] = (float) (g.strength * grow * grow * (3.0 - 2.0 * grow) * scale);
            out[at + 5] = (float) g.lenAhead;
            out[at + 6] = (float) g.lenBehind;
            out[at + 7] = (float) g.halfWidth;
            i++;
        }
    }

    /** 風が流れていく向き(単位ベクトルの x・z)。突風の向きは少しずつ違うが、揺れの向きはこれで揃える。 */
    public static double dirX() {
        return Math.cos(baseAngle);
    }

    public static double dirZ() {
        return Math.sin(baseAngle);
    }

    /**
     * 枝の揺れ(−1〜1)。毎秒 0.25 回を中心に、少し速い揺れを重ねる。{@code phase} は物ごとのずらし。
     * 斑点・光の筋の行き来に使う(強さは呼び手が掛ける)。
     */
    public static double branch(double time, double phase) {
        double s = time / 20.0;
        return 0.7 * Math.sin(2.0 * Math.PI * 0.25 * s + phase) + 0.3 * Math.sin(2.0 * Math.PI * 0.61 * s + phase * 1.7);
    }

    /**
     * 葉のはためきによる明るさ(0〜1)。凪では 1 近くでかすかにきらめき、風が強いほど速く(毎秒 1.5〜4 回)深く明滅し、
     * 強い突風のさなかには一瞬消える。{@code w} は {@link #strength} の値。
     */
    public static double flutter(double time, double phase, double w) {
        double s = time / 20.0;
        double amount = Math.min(1.0, w);
        double hz = 1.5 + 2.5 * amount;
        double fast = 0.5 + 0.5 * Math.sin(2.0 * Math.PI * hz * s + phase * 7.0);
        double slow = 0.5 + 0.5 * Math.sin(2.0 * Math.PI * 0.53 * s + phase * 3.0);
        double shimmer = 0.9 + 0.1 * Math.sin(2.0 * Math.PI * 0.2 * s + phase);
        // 速い明滅と遅いうねりの積を、風の強さぶんだけ混ぜる(凪ではほぼ shimmer だけ)。
        double blink = Math.pow(fast * (0.4 + 0.6 * slow), 0.7);
        return shimmer * (1.0 - amount * 0.85) + blink * amount * 0.85;
    }

    /** ワールドを出た・入り直したとき(入ってすぐには吹かせない。20〜60 秒後に最初の突風)。 */
    public static void reset() {
        GUSTS.clear();
        prevailingAngle = Double.NaN;
        nextGustIn = 400 + RANDOM.nextInt(800);
    }

    /** 開発用のログに出す要約。 */
    public static String describe(double x, double z) {
        return String.format("風 %.2f(弱い風 %.2f・突風 %d)向き %.0f°", strength(x, z, ticks), baseStrength * scale, GUSTS.size(),
                Math.toDegrees(baseAngle) % 360.0);
    }

    private CanopyWind() {
    }
}
