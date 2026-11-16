package com.yayuy.overgrownvegetation.spore.field;

/**
 * 世界に 1 つの卓越風(胞子の場と風・第 1 段、2026-10-21)。
 *
 * <p>サーバーもクライアントも同じ式で読む<b>決定的な関数</b>。状態を持たず、乱数は {@link #hash} だけ
 * (呼ぶ順番で答えが変わる {@code java.util.Random} は使わない)。net.minecraft を import しない素の Java ——
 * 第 2 段でゲームの {@code spore/} へそのまま写す。
 *
 * <p>入力: ワールドのシード・暦の tick({@code PhaseData#clock} = 睡眠込み)・天気の印 {@link Weather}・升の地形 {@link Terrain}。
 * 出力: 風が<b>吹いていく向き</b>(単位ベクトル。x = 東、z = 南)と強さ(凪 0・ふだんの午後 1・嵐 {@link #STORM})。
 *
 * <p>【決定】(Vault「胞子の場と風による汚染の広がり」追記 2): 土台の向きはシードで固定 / 3〜7 日周期のゆっくりした回転 /
 * 雨の始まり = 前線の通過で半日かけて 90〜180 度(時計回り)回って強まり、雨が終わると数日で土台へ戻る / 昼夜 ±15 度 /
 * 強さは午後が強く夜は凪、雷雨で嵐 / 海陸風・山谷風 / 風の強い日。
 * それ以外の数値(振幅・時定数・確率・強さの倍率)はすべて<b>仮決め</b>で、{@code P_} の定数にまとめた。
 */
public final class PrevailingWind {
    public static final int DAY = 24000;
    /** 「なし」を表す時刻。 */
    public static final long NONE = Long.MIN_VALUE;

    // ---- 仮決めの数値 ----
    /** ゆっくりした回転の 2 つの波の振幅(度)。合わせて最大 ±60 度。 */
    static final double P_SLOW_AMP_DEG = 30;
    /** 土台の向きが一周する日数(シードで決める範囲)と、揺らぎの大きさ(度)・揺らぎの雑音の間隔(日)。 */
    static final double P_BASE_PERIOD_MIN = 30, P_BASE_PERIOD_MAX = 60, P_BASE_WOBBLE_DEG = 40, P_BASE_WOBBLE_DAYS = 8;
    /** 前線の回転にかける時間(tick)。【決定】半日。 */
    static final int P_FRONT_RAMP = DAY / 2;
    /** 雨が終わってから土台へ戻る時定数(tick)。3 倍 = 3 日でほぼ戻る(【決定】数日)。 */
    static final double P_FRONT_DECAY = DAY;
    /** 前線の通過中・直後の強さの倍率。 */
    static final double P_FRONT_GAIN = 0.6;
    /** 昼夜の振れ(度)。【決定】±15。 */
    static final double P_DIURNAL_DEG = 15;
    /** 夜の凪の強さと午後の山の強さ。 */
    static final double P_NIGHT = 0.15, P_AFTERNOON = 1.0;
    /** 日ごとの強さのばらつき(倍率の幅、0.75〜1.25)。 */
    static final double P_DAY_VAR = 0.25;
    /** 雨の間の強さの下限。 */
    static final double P_RAIN_FLOOR = 0.6;
    /** 嵐(雷雨)の強さ。 */
    public static final double STORM = 3.0;
    /** 嵐の風の強さに掛ける倍率(既定 1。フェーズが F4 に上がった直後の「弱い」胞子の嵐の間だけ下げる。sky/CloudWeather#forceStorm が書く。2026-11-16)。 */
    public static volatile double stormScale = 1.0D;
    /** 嵐の向きの乱れ(度)と乱れの周期(tick)。 */
    static final double P_STORM_TURB_DEG = 50, P_STORM_TURB_PERIOD = 1500;
    /** 風の強い日: 強さは嵐の何割か(【案】6〜7 割)、雨の終わりの翌日になる確率、それ以外の日の確率。 */
    static final double P_STRONG_OF_STORM = 0.65, P_STRONG_AFTER_RAIN = 0.4, P_STRONG_BASE = 0.045;
    /** 海陸風・山谷風の強さ(午後の卓越風 1 に対して)と、海風が届く距離の目安(チャンク)。 */
    static final double P_SEA_BREEZE = 0.4, P_SEA_REACH = 4, P_VALLEY_BREEZE = 0.3;

    private PrevailingWind() {
    }

    /**
     * 天気の印。ゲームは今の雨・雷と、直近 2 回の雨の始まり・終わりを覚えておく(第 2 段で SavedData に)。
     * 降っている最中の雨は {@code lastRainEnd} が {@code lastRainStart} より前(または NONE)。
     */
    public record Weather(boolean raining, boolean thundering,
                          long lastRainStart, long lastRainEnd, long prevRainStart, long prevRainEnd) {
        public static final Weather CLEAR = new Weather(false, false, NONE, NONE, NONE, NONE);
    }

    /**
     * 升の地形(海陸風・山谷風に使う。値の取り方はゲーム側で後から埋める)。
     *
     * @param seaDist 海までの距離(チャンク)。海が無ければ +∞
     * @param seaDirX 海の方向の単位ベクトル
     * @param upX     斜面を上る向き × 傾き(0〜1)。平らなら 0
     */
    public record Terrain(double seaDist, double seaDirX, double seaDirZ, double upX, double upZ) {
        public static final Terrain FLAT = new Terrain(Double.POSITIVE_INFINITY, 0, 0, 0, 0);
    }

    /** 答え。使い回してよい。 */
    public static final class Sample {
        /** 風が吹いていく向き(単位ベクトル)。 */
        public double dirX, dirZ;
        /** 強さ。 */
        public double strength;
        /** 嵐・風の強い日。 */
        public boolean storm, strongDay;

        /** 向き(度。0 = 東へ、90 = 南へ、時計回り)。 */
        public double angleDeg() {
            double a = Math.toDegrees(Math.atan2(dirZ, dirX));
            return a < 0 ? a + 360 : a;
        }
    }

    /** 卓越風(地形の足し算なし)。升に依らないので 1 回の更新で 1 回だけ呼べばよい。 */
    public static void sample(long seed, long t, Weather w, Sample out) {
        sample(seed, t, w, Terrain.FLAT, out);
    }

    public static void sample(long seed, long t, Weather w, Terrain ter, Sample out) {
        long day = Math.floorDiv(t, DAY);
        double tod = Math.floorMod(t, DAY); // 0 = 6 時(日の出)、6000 = 正午、12000 = 18 時、18000 = 0 時

        double angle = synopticAngle(seed, t, w);
        double s = diurnalStrength(tod) * dayFactor(seed, t);
        // 前線: 雨の始まりから強まり、雨の後に戻る(向きと同じ時定数)
        double fg = frontGain(t, w);
        s *= 1 + P_FRONT_GAIN * fg;
        if (w.raining) s = Math.max(s, P_RAIN_FLOOR);
        angle += Math.toRadians(P_DIURNAL_DEG) * Math.sin(2 * Math.PI * tod / DAY);

        boolean storm = w.raining && w.thundering;
        boolean strong = false;
        if (storm) {
            s = STORM * stormScale;
            angle += Math.toRadians(P_STORM_TURB_DEG) * smoothNoise(seed ^ 0x5707L, t / P_STORM_TURB_PERIOD);
        } else if (isStrongDay(seed, day, w)) {
            double env = strongEnvelope(tod);
            if (env > 0) {
                double fixed = synopticAngle(seed, day * DAY - 1000, w); // 夜明けの卓越風で固定
                double ss = STORM * P_STRONG_OF_STORM * env;
                if (ss > s) {
                    // 夜明け・日没の境目は向きも滑らかに寄せる
                    angle = lerpAngle(angle, fixed, env);
                    s = ss;
                }
                strong = true;
            }
        }

        double vx = s * Math.cos(angle), vz = s * Math.sin(angle);
        // 海陸風・山谷風: 昼は海 → 陸・谷を上る、夜は逆(sin が正 = 昼)
        double sun = Math.sin(2 * Math.PI * tod / DAY);
        if (ter.seaDist() < Double.POSITIVE_INFINITY) {
            double m = P_SEA_BREEZE * Math.exp(-ter.seaDist() / P_SEA_REACH) * sun;
            vx -= m * ter.seaDirX();
            vz -= m * ter.seaDirZ();
        }
        vx += P_VALLEY_BREEZE * sun * ter.upX();
        vz += P_VALLEY_BREEZE * sun * ter.upZ();

        double len = Math.hypot(vx, vz);
        if (len < 1e-9) {
            out.dirX = Math.cos(angle);
            out.dirZ = Math.sin(angle);
        } else {
            out.dirX = vx / len;
            out.dirZ = vz / len;
        }
        out.strength = len;
        out.storm = storm;
        out.strongDay = strong;
    }

    /** 土台 + ゆっくりした回転 + 前線(昼夜・嵐の乱れは含まない)。ラジアン。 */
    static double synopticAngle(long seed, long t, Weather w) {
        double days = t / (double) DAY;
        double base = baseAngle(seed, days);
        double slow = 0;
        for (int i = 0; i < 2; i++) {
            double period = 3 + 4 * unit(hash(seed, 10 + i)); // 3〜7 日
            double phase = 2 * Math.PI * unit(hash(seed, 20 + i));
            slow += Math.sin(2 * Math.PI * days / period + phase);
        }
        return base + Math.toRadians(P_SLOW_AMP_DEG) * slow + frontOffset(seed, t, w);
    }

    /**
     * 土台の向き(ラジアン)。シードで決まる向きから、{@link #P_BASE_PERIOD_MIN}〜{@link #P_BASE_PERIOD_MAX} 日で一周する速さで
     * ゆっくり回り(回る向きもシードで決まる)、{@link #P_BASE_WOBBLE_DAYS} 日おきの雑音で ±{@link #P_BASE_WOBBLE_DEG} 度揺らぐ
     * (一定の速さにしない)。【決定】10-23 追記 11「土台も回していろんな方向に汚染が広がるように」。数値は仮決め。
     */
    public static double baseAngle(long seed, double days) {
        double start = 2 * Math.PI * unit(hash(seed, 1));
        double period = P_BASE_PERIOD_MIN + (P_BASE_PERIOD_MAX - P_BASE_PERIOD_MIN) * unit(hash(seed, 2));
        double sign = unit(hash(seed, 3)) < 0.5 ? -1 : 1;
        double wobble = Math.toRadians(P_BASE_WOBBLE_DEG) * smoothNoise(seed ^ 0xBA5EL, days / P_BASE_WOBBLE_DAYS);
        return start + sign * 2 * Math.PI * days / period + wobble;
    }

    /**
     * 前線の回り。直近の雨の始まり S から半日で「その時点の残り + Δ」(Δ = 90〜180 度、時計回り)まで回し、
     * 雨が終わったら(回し終える前に終わったら回し終えてから)いちばん近い 1 周の倍数へ指数で戻す。
     * 1 つ前の雨の残りは、その前の残りを 0 とみなして求める(数日で消えるので差は小さい)。
     */
    static double frontOffset(long seed, long t, Weather w) {
        if (w.lastRainStart == NONE || t < w.lastRainStart) return 0;
        double res = 0;
        if (w.prevRainStart != NONE) res = oneFront(seed, w.lastRainStart, w.prevRainStart, w.prevRainEnd, 0);
        long end = w.lastRainEnd != NONE && w.lastRainEnd >= w.lastRainStart ? w.lastRainEnd : NONE;
        return oneFront(seed, t, w.lastRainStart, end, res);
    }

    private static double oneFront(long seed, long t, long start, long end, double res) {
        double delta = Math.toRadians(90 + 90 * unit(hash(seed, start)));
        double target = res + delta;
        if (t < start + P_FRONT_RAMP) return res + (target - res) * smooth((t - start) / (double) P_FRONT_RAMP);
        if (end == NONE || t <= end) return target;
        long decayFrom = Math.max(end, start + P_FRONT_RAMP);
        double home = 2 * Math.PI * Math.round(target / (2 * Math.PI));
        return home + (target - home) * Math.exp(-(t - decayFrom) / P_FRONT_DECAY);
    }

    /** 前線の通過で強まる分(0〜1)。回している間に 1 まで上がり、雨の後に戻る。 */
    static double frontGain(long t, Weather w) {
        if (w.lastRainStart == NONE || t < w.lastRainStart) return 0;
        long start = w.lastRainStart;
        if (t < start + P_FRONT_RAMP) return smooth((t - start) / (double) P_FRONT_RAMP);
        long end = w.lastRainEnd != NONE && w.lastRainEnd >= start ? w.lastRainEnd : NONE;
        if (end == NONE || t <= end) return 1;
        return Math.exp(-(t - Math.max(end, start + P_FRONT_RAMP)) / P_FRONT_DECAY);
    }

    /** 昼夜の強さ: 14 時(tod 8000)に山、夜は凪。 */
    static double diurnalStrength(double tod) {
        double c = Math.cos(2 * Math.PI * (tod - 8000) / DAY);
        double bump = c > 0 ? Math.pow(c, 1.5) : 0;
        return P_NIGHT + (P_AFTERNOON - P_NIGHT) * bump;
    }

    /** 日ごとのばらつき(隣の日と滑らかにつなぐ)。 */
    static double dayFactor(long seed, long t) {
        return 1 + P_DAY_VAR * smoothNoise(seed ^ 0xDA7L, t / (double) DAY);
    }

    /**
     * 風の強い日か。雨の終わりの翌日(夜明けが雨の終わりより後の最初の日)は {@link #P_STRONG_AFTER_RAIN}、
     * ほかの日は {@link #P_STRONG_BASE}。直近 2 回の雨の終わりを見る(その日のうちに短い雨が降って止んでも答えが変わらない)。
     */
    public static boolean isStrongDay(long seed, long day, Weather w) {
        double p = P_STRONG_BASE;
        if (dawnDayAfter(w.lastRainEnd, w.lastRainStart) == day || dawnDayAfter(w.prevRainEnd, w.prevRainStart) == day) {
            p = P_STRONG_AFTER_RAIN;
        }
        return unit(hash(seed ^ 0x57A0L, day)) < p;
    }

    private static long dawnDayAfter(long end, long start) {
        if (end == NONE || end < start) return Long.MIN_VALUE;
        return Math.floorDiv(end + 1000 + DAY - 1, DAY); // 夜明け = 日の 1000 tick 前(5 時)
    }

    /** 風の強い日の包絡: 夜明け(5 時)から 2 時間で強まり、日没(18 時)前後 2 時間で弱まる。 */
    static double strongEnvelope(double tod) {
        double x = tod >= 23000 ? tod - DAY : tod; // -1000 = 5 時
        if (x < -1000 || x > 14000) return 0;
        if (x < 1000) return smooth((x + 1000) / 2000.0);
        if (x > 12000) return smooth((14000 - x) / 2000.0);
        return 1;
    }

    // ---- 決定的な乱数 ----

    /** splitmix64 で (seed, key) を混ぜる。 */
    public static long hash(long seed, long key) {
        long z = seed + 0x9E3779B97F4A7C15L * (key + 0x632BE59BD9B4E019L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** [0, 1)。 */
    public static double unit(long h) {
        return (h >>> 11) * 0x1.0p-53;
    }

    /** 整数点に乱数を置いて滑らかにつないだ -1〜1 の揺れ。 */
    static double smoothNoise(long seed, double x) {
        long i = (long) Math.floor(x);
        double f = x - i;
        double a = unit(hash(seed, i)) * 2 - 1, b = unit(hash(seed, i + 1)) * 2 - 1;
        return a + (b - a) * smooth(f);
    }

    static double smooth(double x) {
        x = Math.max(0, Math.min(1, x));
        return x * x * (3 - 2 * x);
    }

    static double lerpAngle(double a, double b, double f) {
        double d = Math.IEEEremainder(b - a, 2 * Math.PI);
        return a + d * f;
    }
}
