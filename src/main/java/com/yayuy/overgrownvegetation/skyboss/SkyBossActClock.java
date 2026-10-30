package com.yayuy.overgrownvegetation.skyboss;

/**
 * 体の層と腹の触手の層のアニメの時刻表(2026-10-19 の技から)。扇の {@link SkyBossFanClock} と同じく、サーバー(当たり判定の形)とクライアント
 * (GeckoLib の body・tentacles のコントローラ)が<b>同じ時刻表</b>で流すので、見えている体・触手と当たり判定が合う。
 *
 * <ul>
 *   <li>体: ふだんは undulate をくり返す。雲を吐く({@link SkyBossFlight#spitAt})と spit_body を 1 回流し、終わるとまた undulate。</li>
 *   <li>触手: ふだんは drift をくり返す。技({@link SkyBossFlight#tentAt}・{@link SkyBossFlight#tentKind})で tentacle_lash / tentacle_grab を 1 回。</li>
 *   <li>区切り(アニメ・始まりの tick)が変わると、補間({@link SkyBossTuning#BODY_TRANSITION}・{@link SkyBossTuning#TENT_TRANSITION})の間に
 *       前の区切りのその時の形から次のアニメの 0 秒目の形へ移り(GeckoLib のコントローラの補間と同じ)、その後 0 秒目から流す。
 *       くり返しのアニメは区切りの始まり + 補間から数える(技の後のうねりは、技が終わった所から数え直す = GeckoLib と同じ)。</li>
 * </ul>
 * 区切りの前のくり返しの始まりは {@link SkyBossFlight#undulateFrom}・{@link SkyBossFlight#driftFrom}(技を始めるとき {@link #startSpit}・
 * {@link #startTentacle} が書く)。時刻はすべて tick。アニメの長さは {@link SkyBossShapes} の値(道具が測った秒 × 20)。
 */
public final class SkyBossActClock {

    public static final int UNDULATE = 0;
    public static final int SPIT = 1;
    /** 生き物らしさ(10-19 夕方): 伸び(stretch_body)と雲海からの飛び出し(breach_body)。どちらも 1 回。 */
    public static final int STRETCH = 2;
    public static final int BREACH = 3;
    public static final String[] BODY_NAMES = {"undulate", "spit_body", "stretch_body", "breach_body"};

    public static final int DRIFT = 0;
    public static final int LASH = 1;
    public static final int GRAB = 2;
    public static final String[] TENT_NAMES = {"drift", "tentacle_lash", "tentacle_grab"};

    /** 体のアニメの長さ(tick)。形が無いときは道具の値(うねり 43.2 秒・雲を吐く 10.8 秒)。 */
    public static double bodyLength(int anim) {
        SkyBossShapes s = SkyBossShapes.get();
        if (s != null) {
            if (anim == UNDULATE) {
                return s.undulateLength * 20.0;
            }
            SkyBossShapes.BodyAnim a = s.bodyAnims.get(BODY_NAMES[anim]);
            if (a != null) {
                return a.length * 20.0;
            }
        }
        return anim == UNDULATE ? 864.0 : anim == BREACH ? 648.0 : 216.0;
    }

    /** 触手のアニメの長さ(tick)。形が無いときは道具の値(たなびき・打つ 6.3 秒・絡め取る 9 秒)。 */
    public static double tentLength(int anim) {
        SkyBossShapes s = SkyBossShapes.get();
        if (s != null) {
            if (anim == DRIFT) {
                return s.driftLength * 20.0;
            }
            SkyBossShapes.TentAnim a = s.tentAnims.get(TENT_NAMES[anim]);
            if (a != null) {
                return a.length * 20.0;
            }
        }
        return anim == DRIFT ? 288.0 : anim == LASH ? 126.0 : 180.0;
    }

    /** 雲を吐く体の動きが終わる tick(補間 + spit_body)。 */
    public static double spitEnd(SkyBossFlight f) {
        return f.spitAt + SkyBossTuning.BODY_TRANSITION + bodyLength(SPIT);
    }

    /** 触手の技が終わる tick。 */
    public static double tentEnd(SkyBossFlight f) {
        return f.tentAt + SkyBossTuning.TENT_TRANSITION + tentLength(f.tentKind == 1 ? GRAB : LASH);
    }

    /**
     * 時刻 t の体の区切り: {@code out} = {アニメ, 始まりの tick, 前のアニメ, 前の始まりの tick}(前が無ければ前のアニメ = -1)。
     */
    public static void body(SkyBossFlight f, double t, double[] out) {
        // いちばん新しく始まった体の動き(雲を吐く・伸び・飛び出し。重ならないように始める側が見る)
        int act = -1;
        long at = SkyBossFlight.NONE;
        long[] starts = {f.spitAt, f.stretchAt, f.breachAt};
        int[] acts = {SPIT, STRETCH, BREACH};
        for (int i = 0; i < starts.length; i++) {
            if (starts[i] != SkyBossFlight.NONE && t >= starts[i] && (act < 0 || starts[i] > at)) {
                act = acts[i];
                at = starts[i];
            }
        }
        if (act >= 0) {
            double end = at + SkyBossTuning.BODY_TRANSITION + bodyLength(act);
            if (t < end) {
                set(out, act, at, UNDULATE, f.undulateFrom);
            } else {
                set(out, UNDULATE, end, act, at);
            }
            return;
        }
        set(out, UNDULATE, f.undulateFrom, -1, 0.0);
    }

    /** 時刻 t に体の層が 1 回の動き(雲を吐く・伸び・飛び出し)の中か。 */
    public static boolean bodyActing(SkyBossFlight f, double t) {
        double[] seg = new double[4];
        body(f, t, seg);
        return seg[0] != UNDULATE;
    }

    /** 時刻 t の触手の区切り(並びは {@link #body} と同じ)。 */
    public static void tentacles(SkyBossFlight f, double t, double[] out) {
        if (f.tentAt != SkyBossFlight.NONE && t >= f.tentAt) {
            double end = tentEnd(f);
            int act = f.tentKind == 1 ? GRAB : LASH;
            if (t < end) {
                set(out, act, f.tentAt, DRIFT, f.driftFrom);
            } else {
                set(out, DRIFT, end, act, f.tentAt);
            }
            return;
        }
        set(out, DRIFT, f.driftFrom, -1, 0.0);
    }

    private static void set(double[] out, int anim, double start, int prev, double prevStart) {
        out[0] = anim;
        out[1] = start;
        out[2] = prev;
        out[3] = prevStart;
    }

    /** 区切りの始まり {@code start} から時刻 t に、アニメが何 tick 目か(補間の間は 0)。くり返しは長さで割った余り、技は最後の形で止める。 */
    public static double animTime(boolean loop, double length, double transition, double start, double t) {
        double run = t - start - transition;
        if (run <= 0.0) {
            return 0.0;
        }
        return loop ? run % length : Math.min(run, length - 1.0E-3);
    }

    /** 補間の進み(0〜1)。始まりが 0 のふだんのくり返しは補間しない(読み込んだ時からずっと流れている)。 */
    public static double blend(double start, double transition, double t) {
        if (start <= 0.0) {
            return 1.0;
        }
        return Math.max(0.0, Math.min(1.0, (t - start) / transition));
    }

    /** 雲を吐き始める(今のうねりの区切りの始まりを覚えてから)。 */
    public static void startSpit(SkyBossFlight f, long now) {
        rememberUndulate(f, now);
        f.spitAt = now;
    }

    /** 伸びとあくびを始める(扇の stretch・口の yawn も同じ tick から)。 */
    public static void startStretch(SkyBossFlight f, long now) {
        rememberUndulate(f, now);
        f.stretchAt = now;
    }

    /** 雲海からの飛び出しを始める(扇・口・目も同じ tick から)。 */
    public static void startBreach(SkyBossFlight f, long now) {
        rememberUndulate(f, now);
        f.breachAt = now;
    }

    private static void rememberUndulate(SkyBossFlight f, long now) {
        double[] seg = new double[4];
        body(f, now, seg);
        f.undulateFrom = (long) Math.round(seg[1]);
    }

    /** 触手の技を始める({@code kind} = 0 打つ・1 絡め取る)。 */
    public static void startTentacle(SkyBossFlight f, long now, int kind) {
        double[] seg = new double[4];
        tentacles(f, now, seg);
        f.driftFrom = (long) Math.round(seg[1]);
        f.tentKind = kind;
        f.tentAt = now;
    }

    /** 時刻 t に雲を吐く体の動きの中か。 */
    public static boolean spitting(SkyBossFlight f, double t) {
        return f.spitAt != SkyBossFlight.NONE && t >= f.spitAt && t < spitEnd(f);
    }

    /** 時刻 t に触手の技の中か。 */
    public static boolean tentacleActing(SkyBossFlight f, double t) {
        return f.tentAt != SkyBossFlight.NONE && t >= f.tentAt && t < tentEnd(f);
    }

    private SkyBossActClock() {
    }
}
