package com.yayuy.overgrownvegetation.skyboss;

/**
 * 扇の層のアニメの時刻表(2026-10-18 夕方)。サーバー(当たり判定の形)とクライアント(GeckoLib の扇のコントローラ)が<b>同じ時刻表</b>で
 * 扇を動かすので、見えている扇と当たり判定の扇が合う(漕ぎで扇の先は 約 250 ブロック、旋回で 約 300 ブロック動く)。
 *
 * <ul>
 *   <li>扇の層の動き {@link SkyBossFlight#fanMode} が 1 / 2 の間: turn_left / turn_right をくり返す(変わった tick から)。</li>
 *   <li>3: hover をくり返す・4: stroke をくり返す・5: shiver(1 回。流し終えると {@link SkyBossFlight#step} が前の動きへ戻す)。10-19 から。</li>
 *   <li>6: discharge_fans をくり返す(放電の 30 秒。10-19 から)。</li>
 *   <li>0 の間(渡り): {@link SkyBossTuning#STROKE_CYCLE_TICKS} の区切り(ゲームの時刻で決まる)ごとに、stroke を {@link #STROKES} 回 → 残りは glide。
 *       渡りに戻った tick からも頭から流す。</li>
 *   <li>切り替わりは {@link #TRANSITION} tick かけて前の形から次のアニメの 0 秒目の形へ移る(GeckoLib のコントローラの補間と同じ)。</li>
 * </ul>
 * 時刻はすべて tick(ゲームの時刻)。アニメの長さは {@link SkyBossShapes} の値(モデルの道具が測った秒 × 20)。
 */
public final class SkyBossFanClock {

    public static final int GLIDE = 0;
    public static final int STROKE = 1;
    public static final int TURN_LEFT = 2;
    public static final int TURN_RIGHT = 3;
    /** 雷雲の中で留まる(左右交互に小刻みに打つ。くり返し)と、雷が落ちたときの身震い(1 回)。10-19 から。 */
    public static final int HOVER = 4;
    public static final int SHIVER = 5;
    /** 放電中(扇を広げ、電気でひきつるように震える。くり返し)。10-19 から。 */
    public static final int DISCHARGE = 6;
    /** 羽ばたきの下降気流(1 回、終わりの形で止める)と、転がりのビームの構え(くり返し)。10-19 の技から。 */
    public static final int DOWNDRAFT = 7;
    public static final int BRACE = 8;
    /** 生き物らしさ(10-19 夕方): 眠り・日光浴・すぼめ(くり返し)と、伸び・雲海からの飛び出し(1 回)。 */
    public static final int SLEEP = 9;
    public static final int BASK = 10;
    public static final int STRETCH = 11;
    public static final int TUCK = 12;
    public static final int BREACH = 13;
    public static final String[] NAMES = {"glide", "stroke", "turn_left", "turn_right", "hover", "shiver", "discharge_fans", "downdraft", "brace",
            "sleep", "bask", "stretch", "tuck", "breach_fans"};

    /** 切り替わりの補間(tick)。{@code SkyBossImpostor} の扇のコントローラと同じ値。 */
    public static final int TRANSITION = 40;
    /** 1 回りの漕ぐ回数。 */
    public static final int STROKES = 3;

    /** アニメの長さ(tick)。形が無いときの値は 10-18 のモデル(stroke・turn 9 秒、glide 14.4 秒)。 */
    public static double length(int anim) {
        SkyBossShapes s = SkyBossShapes.get();
        if (s != null) {
            SkyBossShapes.FanAnim a = s.fanAnims.get(NAMES[anim]);
            if (a != null) {
                return a.length * 20.0;
            }
        }
        return switch (anim) {
            case GLIDE -> 288.0;
            case HOVER -> 86.4;
            case SHIVER -> 129.6;
            case DISCHARGE -> 72.0;
            case DOWNDRAFT -> 252.0;
            case BRACE -> 144.0;
            case SLEEP -> 432.0;
            case STRETCH -> 216.0;
            case TUCK -> 288.0;
            case BREACH -> 648.0;
            default -> 180.0;
        };
    }

    /** 時刻 t の扇の区切り: {@code out[0]} = アニメ、{@code out[1]} = 始まった tick(補間の始まり)。今の動きに変わる前の時刻なら一つ前の動きで。 */
    public static void segment(SkyBossFlight f, double t, double[] out) {
        if (t >= f.fanModeSince) {
            segment(f.fanMode, f.fanModeSince, t, out);
        } else {
            segment(f.prevFanMode, f.prevFanModeSince, t, out);
        }
    }

    private static void segment(int fanMode, long fanModeSince, double t, double[] out) {
        out[1] = fanModeSince;
        switch (fanMode) {
            case SkyBossFlight.FAN_TURN_LEFT -> {
                out[0] = TURN_LEFT;
                return;
            }
            case SkyBossFlight.FAN_TURN_RIGHT -> {
                out[0] = TURN_RIGHT;
                return;
            }
            case SkyBossFlight.FAN_HOVER -> {
                out[0] = HOVER;
                return;
            }
            case SkyBossFlight.FAN_EAGER -> {
                // 雷雲へ向かう間は漕ぎ続ける(決定「雷雲を見つけると漕ぐのが速くなる」)
                out[0] = STROKE;
                return;
            }
            case SkyBossFlight.FAN_SHIVER -> {
                out[0] = SHIVER;
                return;
            }
            case SkyBossFlight.FAN_DISCHARGE -> {
                out[0] = DISCHARGE;
                return;
            }
            case SkyBossFlight.FAN_DOWNDRAFT -> {
                out[0] = DOWNDRAFT;
                return;
            }
            case SkyBossFlight.FAN_BRACE -> {
                out[0] = BRACE;
                return;
            }
            case SkyBossFlight.FAN_SLEEP -> {
                out[0] = SLEEP;
                return;
            }
            case SkyBossFlight.FAN_BASK -> {
                out[0] = BASK;
                return;
            }
            case SkyBossFlight.FAN_STRETCH -> {
                out[0] = STRETCH;
                return;
            }
            case SkyBossFlight.FAN_TUCK -> {
                out[0] = TUCK;
                return;
            }
            case SkyBossFlight.FAN_BREACH -> {
                out[0] = BREACH;
                return;
            }
            default -> {
            }
        }
        double cycle = SkyBossTuning.STROKE_CYCLE_TICKS;
        double start = Math.max(fanModeSince, Math.floor(t / cycle) * cycle);
        double strokeEnd = start + TRANSITION + STROKES * length(STROKE);
        if (t < strokeEnd) {
            out[0] = STROKE;
            out[1] = start;
        } else {
            out[0] = GLIDE;
            out[1] = strokeEnd;
        }
    }

    /** 区切りの始まり {@code start} から時刻 t に、アニメが何 tick 目か(補間の間は 0。くり返しのアニメは長さで割った余り)。 */
    public static double animTime(int anim, double start, double t) {
        double run = t - start - TRANSITION;
        if (run <= 0.0) {
            return 0.0;
        }
        // 身震いは 1 回だけ(終わりの形で止める)
        return once(anim) ? Math.min(run, length(anim) - 1.0E-3) : run % length(anim);
    }

    /** 1 回だけ流して終わりの形で止めるアニメか(身震い・下降気流・伸び・飛び出し)。 */
    public static boolean once(int anim) {
        return anim == SHIVER || anim == DOWNDRAFT || anim == STRETCH || anim == BREACH;
    }

    /** 区切りの始まり {@code start} から時刻 t の、補間の進み(0〜1。1 = 補間が終わってアニメが流れている)。 */
    public static double blend(double start, double t) {
        return Math.max(0.0, Math.min(1.0, (t - start) / TRANSITION));
    }

    private SkyBossFanClock() {
    }
}
