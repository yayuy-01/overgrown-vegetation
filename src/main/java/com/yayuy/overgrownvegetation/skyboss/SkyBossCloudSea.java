package com.yayuy.overgrownvegetation.skyboss;

import java.util.ArrayList;
import java.util.List;

/**
 * 雲海に潜って隠れる・鯨のように飛び出す(2026-10-19 夕方。ユーザー「とりあえず生き物らしさと雲海の方を実装していきましょう」)。決まりは行動パターン「潜伏と飛び出し(雲海)」:
 * <ul>
 *   <li>決定「とても深い雲海に潜って潜伏する。潜伏中に雲の上から見えるのは上へなびく触手だけ」→ 体がまるごと雲の海の中に入る深さ(雲の側の
 *       {@code CloudSky.columnAt} の雲の海の底〜天辺の真ん中)へ、扇をすぼめて(tuck)下りる。体の厚みより深い雲の海の上でだけ。</li>
 *   <li>決定「飛び出すのは、ときどき気まぐれに / 近くを飛ぶ相手への奇襲 / 高さを変えるとき」→ 隠れている間に気まぐれ・雲の海の上を相手が飛ぶと飛び出す。
 *       隠れ終わると強く漕いで上がり、雲を抜けたときに潮吹き(案「抜けたときに spout を流す」)。</li>
 *   <li>案の飛び出しの流れ「雲面が盛り上がり低い唸り(予告)→ 頭から斜め上へ突き抜け、体は頭の道をなぞって大きなアーチ → 体じゅうから雲がちぎれて尾を引く →
 *       てっぺんで鯨のようにひねる → 雲海へ戻ると丸い穴が開いてゆっくり塞がる」→ 予告の盛り上がりと穴は雲の側の {@code CloudSurface}、ちぎれる雲は
 *       雲の側の {@code CloudWake}、頭の道の弧とひねりはここの決まった式({@link #breachPitch}・{@link #breachSpeed}・{@link #breachRoll}。両側が同じ式)、
 *       扇・体・口・目は道具のアニメ breach_*(32.4 秒)。</li>
 * </ul>
 * 状態はサーバーだけ(保存する)。飛び方へは区間ごとの指示(高さの目標・機首の上げ下げの速さ・扇)と、飛び出しを始めた tick({@link SkyBossFlight#breachAt})・
 * 潮吹きの tick({@link SkyBossFlight#spoutAt})で伝える。Minecraft のクラスを使わない(雲は {@link Probe} で引く。GameTest で素で流せる)。数値はすべて仮。
 */
public final class SkyBossCloudSea {

    public static final int NONE = 0;
    public static final int DIVE = 1;
    public static final int HIDDEN = 2;
    public static final int BREACH = 3;
    public static final int RISE = 4;

    /** 雲の海を引く口: 世界の点 (x, z) の {雲の海の底, 天辺}(y)。無ければ null。 */
    public interface Probe {
        double[] sea(double worldX, double worldZ);
    }

    /** 雲の側へ頼む演出(サーバーの {@link SkyBossData} が出す)。 */
    public record Event(int kind, double x, double y, double z) {
        public static final int BULGE = 0;
        public static final int HOLE = 1;
        public static final int GROWL = 2;
    }

    public int mode = NONE;
    public long modeSince;
    /** 隠れる高さ(頭の y。雲の海の底と天辺の真ん中)と、その雲の海の天辺。 */
    public double hideY;
    public double seaTop;
    /** 隠れ終える tick・次に潜ろうとする tick。 */
    public long until;
    public long nextTry = Long.MIN_VALUE / 2;
    /** 奇襲の飛び出し(終わったら上がる)。 */
    public boolean ambush;
    /** 上がる途中で雲面を抜けて潮吹きをしたか・穴を開けたか(飛び出しの間)。 */
    public boolean spouted;
    public boolean holed;
    /** 飛び出しの予告(盛り上がり)を出した tick。この後 {@link SkyBossTuning#BREACH_TELEGRAPH} で飛び出す。 */
    public long telegraphAt = Long.MIN_VALUE;

    public final List<Event> events = new ArrayList<>();
    /** 確かめ用(devbridge): 次の区間で、割合と間を見ずに潜ろうとする。 */
    public boolean forceDive;
    /** 確かめ用の命令で潜った(浅い雲の海でも潜り、隠れ続ける。体が少しはみ出す)。 */
    public boolean relaxed;

    // ------------------------------------------------------------------ 区間ごと(頭が次の区間を決める前)
    /**
     * 区間の頭: 状態を進め、区間の指示を書く。{@code calm} = 戦いの外で渡っている(雷雲に寄る・留まる・出ていく・放電の途中でない)、
     * {@code combat} = 戦いの中、{@code roll} = 乱数(0〜1)。
     */
    public void segment(SkyBossFlight f, long now, Probe probe, boolean calm, boolean combat, double roll, SkyBossFeeding.Orders o) {
        double hx = SkyBossWorld.worldX(f.x, now);
        double hz = SkyBossWorld.worldZ(f.z, now);
        switch (mode) {
            case NONE -> {
                if (!calm || (now < nextTry && !forceDive)) {
                    return;
                }
                boolean forced = forceDive;
                forceDive = false;
                nextTry = now + SkyBossTuning.SEA_TRY_EVERY;
                double[] here = forced ? anySea(probe, hx, hz) : deepSea(probe, hx, hz);
                double[] fw = f.forward();
                double[] ahead = deepSea(probe, hx + fw[0] * SkyBossTuning.SEA_AHEAD, hz + fw[2] * SkyBossTuning.SEA_AHEAD);
                // 確かめ用の命令のときは、前方の雲の海と割合を見ない
                if (here == null || (!forced && (ahead == null || roll > SkyBossTuning.SEA_DIVE_CHANCE))) {
                    return;
                }
                set(DIVE, now);
                relaxed = forced;
                hideY = (here[0] + here[1]) * 0.5;
                seaTop = here[1];
            }
            case DIVE, HIDDEN -> {
                if (telegraphAt != Long.MIN_VALUE) {
                    // 飛び出しの予告の後は取り消さない(飛び出してから決める)
                    break;
                }
                if (combat || !calm) {
                    rise(now);
                    break;
                }
                double[] here = relaxed ? anySea(probe, hx, hz) : deepSea(probe, hx, hz);
                if (here == null) {
                    // 雲の海が浅くなった・切れた: 上がる
                    rise(now);
                    break;
                }
                hideY = (here[0] + here[1]) * 0.5;
                seaTop = here[1];
                if (mode == HIDDEN && now >= until) {
                    if (roll < SkyBossTuning.SEA_BREACH_CHANCE) {
                        telegraph(f, now, false);
                    } else {
                        rise(now);
                    }
                }
            }
            default -> {
            }
        }
        // 指示
        switch (mode) {
            case DIVE, HIDDEN -> {
                o.altitude = hideY;
                o.maxPitch = SkyBossTuning.SEA_DIVE_PITCH;
                o.maxPitchRate = SkyBossTuning.SEA_PITCH_RATE;
                f.fanOverride = SkyBossFlight.FAN_TUCK;
            }
            case BREACH -> {
                o.coast = true;
                o.seek = false;
                f.fanOverride = -1;
            }
            case RISE -> {
                o.altitude = SkyBossTuning.CRUISE_Y;
                o.maxPitch = SkyBossTuning.SEA_RISE_PITCH;
                o.maxPitchRate = SkyBossTuning.SEA_PITCH_RATE;
                o.eager = true;
                f.fanOverride = SkyBossFlight.FAN_EAGER;
            }
            default -> {
            }
        }
    }

    /** 確かめ用: 厚さ 40 からの雲の海(無ければ null)。 */
    private static double[] anySea(Probe probe, double x, double z) {
        double[] s = probe.sea(x, z);
        return s == null || s[1] - s[0] < 40.0 ? null : s;
    }

    /** 体がまるごと入る深さの雲の海 {底, 天辺}(無ければ null)。 */
    private static double[] deepSea(Probe probe, double x, double z) {
        double[] s = probe.sea(x, z);
        if (s == null || s[1] - s[0] < SkyBossTuning.BODY_THICKNESS + 2.0 * SkyBossTuning.SEA_MARGIN) {
            return null;
        }
        return s;
    }

    // ------------------------------------------------------------------ 毎 tick
    /**
     * 毎 tick: 下りきったら隠れる・飛び出しの予告の後に飛び出す・飛び出しの終わり・上がる途中で雲面を抜けたら潮吹き・渡りの高さへ戻ったら終わり。
     * {@code playerAbove} = 雲の海の上を、頭の近くでプレイヤーが飛んでいる(奇襲)。飛び方の値を変えたら true(通信する)。
     */
    public boolean tick(SkyBossFlight f, long now, boolean playerAbove, double roll) {
        events.clear();
        boolean changed = false;
        switch (mode) {
            case DIVE -> {
                if (Math.abs(f.y - hideY) < SkyBossTuning.SEA_ARRIVE && Math.abs(f.pitch) < 2.0F) {
                    set(HIDDEN, now);
                    until = now + SkyBossTuning.SEA_HIDE_MIN + (long) (roll * (SkyBossTuning.SEA_HIDE_MAX - SkyBossTuning.SEA_HIDE_MIN));
                    f.segmentEnd = f.tick;
                    changed = true;
                }
            }
            case HIDDEN -> {
                if (playerAbove && telegraphAt < now - SkyBossTuning.BREACH_TELEGRAPH) {
                    telegraph(f, now, true);
                    changed = true;
                }
                if (telegraphAt != Long.MIN_VALUE && now == telegraphAt + SkyBossTuning.BREACH_TELEGRAPH) {
                    SkyBossActClock.startBreach(f, now);
                    set(BREACH, now);
                    spouted = false;
                    holed = false;
                    f.segmentEnd = f.tick;
                    changed = true;
                }
            }
            case BREACH -> {
                double tau = now - f.breachAt;
                if (!holed && tau >= SkyBossTuning.BREACH_REENTER) {
                    // 頭が雲海へ戻る所に丸い穴
                    holed = true;
                    events.add(new Event(Event.HOLE, SkyBossWorld.worldX(f.x, now), seaTop, SkyBossWorld.worldZ(f.z, now)));
                }
                if (!f.breaching(now)) {
                    telegraphAt = Long.MIN_VALUE;
                    if (ambush || roll < 0.5) {
                        rise(now);
                    } else {
                        set(HIDDEN, now);
                        until = now + SkyBossTuning.SEA_HIDE_MIN;
                    }
                    ambush = false;
                    f.pitchRate = 0.0F;
                    f.segmentEnd = f.tick;
                    changed = true;
                }
            }
            case RISE -> {
                if (!spouted && f.y > seaTop + 20.0) {
                    // 雲を抜けた: 潮吹き(口 spout・息 spout_breath・霧)
                    spouted = true;
                    f.spoutAt = now;
                    changed = true;
                }
                if (f.y > SkyBossTuning.CRUISE_Y - SkyBossTuning.SEA_ARRIVE * 2.0) {
                    set(NONE, now);
                    nextTry = now + SkyBossTuning.SEA_COOLDOWN;
                    f.fanOverride = -1;
                    f.segmentEnd = f.tick;
                    changed = true;
                }
            }
            default -> {
            }
        }
        return changed;
    }

    /** 飛び出しの予告: 頭の前の雲面が盛り上がり、低い唸り。{@link SkyBossTuning#BREACH_TELEGRAPH} 後に飛び出す。 */
    private void telegraph(SkyBossFlight f, long now, boolean byAmbush) {
        telegraphAt = now;
        ambush = byAmbush;
        double[] fw = f.forward();
        double d = SkyBossTuning.BREACH_BULGE_AHEAD;
        double x = SkyBossWorld.worldX(f.x + fw[0] * d, now);
        double z = SkyBossWorld.worldZ(f.z + fw[2] * d, now);
        events.add(new Event(Event.BULGE, x, seaTop, z));
        events.add(new Event(Event.GROWL, SkyBossWorld.worldX(f.x, now), f.y, SkyBossWorld.worldZ(f.z, now)));
        if (mode != HIDDEN) {
            set(HIDDEN, now);
        }
    }

    /** 確かめ用(devbridge): 今すぐ飛び出しの予告(隠れていなくても。予告の後に飛び出す)。 */
    public void forceBreach(SkyBossFlight f, long now) {
        if (seaTop <= 0.0) {
            seaTop = f.y - 60.0;
        }
        relaxed = true;
        telegraph(f, now, false);
    }

    private void rise(long now) {
        set(RISE, now);
        relaxed = false;
        spouted = false;
        telegraphAt = Long.MIN_VALUE;
    }

    private void set(int m, long now) {
        mode = m;
        modeSince = now;
    }

    /** 隠れている(下りる途中・隠れている・飛び出し中)か。放電・雷雲に寄るを止める。 */
    public boolean submerged() {
        return mode == DIVE || mode == HIDDEN || mode == BREACH;
    }

    public static String name(int m) {
        return switch (m) {
            case DIVE -> "dive";
            case HIDDEN -> "hidden";
            case BREACH -> "breach";
            case RISE -> "rise";
            default -> "none";
        };
    }

    // ------------------------------------------------------------------ 飛び出しの頭の道(両側が同じ式)
    /** 0 → 1 のなめらかな段(a で 0、b で 1)。 */
    private static double step(double a, double b, double x) {
        double u = Math.max(0.0, Math.min(1.0, (x - a) / (b - a)));
        return u * u * (3.0 - 2.0 * u);
    }

    /** 上りの形(0〜{@link SkyBossTuning#BREACH_HALF}): 0 → 1 → 保つ → 0。 */
    private static double riseShape(double x) {
        return step(SkyBossTuning.BREACH_UP_FROM, SkyBossTuning.BREACH_UP_TO, x) * (1.0 - step(SkyBossTuning.BREACH_HOLD_TO, SkyBossTuning.BREACH_HALF, x));
    }

    /**
     * 飛び出しを始めて {@code tau} tick の機首の角度(度。+ = 機首が下)。真ん中 {@link SkyBossTuning#BREACH_HALF} をはさんで上りと下りが同じ形
     * (速さも同じ形なので、終わると始めの深さへ戻る)。
     */
    public static double breachPitch(double tau) {
        double half = SkyBossTuning.BREACH_HALF;
        if (tau < 0.0 || tau > 2.0 * half) {
            return 0.0;
        }
        return tau <= half ? -SkyBossTuning.BREACH_PITCH * riseShape(tau) : SkyBossTuning.BREACH_PITCH * riseShape(2.0 * half - tau);
    }

    /** 飛び出しの速さの倍率(1 → {@link SkyBossTuning#BREACH_SPEED} → 1。真ん中をはさんで同じ形)。 */
    public static double breachSpeed(double tau) {
        double half = SkyBossTuning.BREACH_HALF;
        if (tau < 0.0 || tau > 2.0 * half) {
            return 1.0;
        }
        double x = tau <= half ? tau : 2.0 * half - tau;
        return 1.0 + (SkyBossTuning.BREACH_SPEED - 1.0) * step(SkyBossTuning.BREACH_UP_FROM, SkyBossTuning.BREACH_UP_TO, x);
    }

    /**
     * てっぺんのひねり(度。体の軸のまわりの向きに、遅れなしで足す。尾の遅れは breach_body のアニメに入っている)。道具の
     * {@code SkyGlaucusBreach.roll} と同じ 5 次の曲線・同じ時刻(元の秒 4.6〜12.6 × 時間の倍率 + 扇の補間)。
     */
    public static double breachRoll(SkyBossFlight f, double t) {
        if (f.breachAt == SkyBossFlight.NONE) {
            return 0.0;
        }
        double tb = (t - f.breachAt - SkyBossFanClock.TRANSITION) / (SkyBossTuning.ANIM_SLOW * 20.0);
        double s = Math.max(0.0, Math.min(1.0, (tb - 4.6) / 8.0));
        if (s <= 0.0 || s >= 1.0) {
            return 0.0;
        }
        return 360.0 * s * s * s * (10.0 - 15.0 * s + 6.0 * s * s);
    }
}
