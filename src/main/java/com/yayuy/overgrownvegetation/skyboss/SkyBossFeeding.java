package com.yayuy.overgrownvegetation.skyboss;

import java.util.List;

/**
 * 雷雲に寄る・背を上にして留まる・出ていく(2026-10-19 から。サーバーだけ。Minecraft のクラスを使わない = 素の Java で流して確かめられる)。
 *
 * <p>区間ごとに {@link #segment} が今の状態を進め、頭({@link SkyBossBrain})への指示({@link Orders})と、飛び方の見た目の状態
 * (転がり・扇を頭が決める動き・頭を先に向ける角度。{@link SkyBossFlight} のもので、両側で同じ式で進む)を決める。
 * 雲は呼ぶ側が探して渡す({@link Storm}。巨大な積乱雲だけ、空気の座標 = 雲は風と一緒に流れるので、この座標では止まっている)。
 *
 * <ul>
 *   <li>渡り(CRUISE): 指定(10-19)の 5 km より近い巨大な積乱雲(育ちきったもの)に気づくと寄り始める。雲を出てからしばらく・同じ雲には寄らない(仮)。</li>
 *   <li>寄る(APPROACH): 決定「遠くの雷雲に気づくと頭をそちらへ向け、少し遅れて体が向きを変える」→ 最初の区間は頭だけ向けてまっすぐ、それから雲の真ん中へ向きを変える。
 *       決定「雷雲を見つけると漕ぐのが速くなる」→ 速さ 1.5 倍(仮)・扇は漕ぎ続ける。頭が雲の真ん中の近くまで来たらもう曲がらず、
 *       <b>腕の付いた所</b>(体の真ん中)が真ん中に来たら留まる(頭は 約 1 km 先 = まだ雲の中)。</li>
 *   <li>留まる(FEED): 決定「雷雲の中だけ背を上」「扇を小刻みに左右交互に打って留まる」→ 止まって(雲と一緒に風に流れるだけ)、背を上へ転がり、扇は hover。
 *       背が上になったら避雷針へ雷が落ちる(呼ぶ側。{@link #strikeDue})。指定(10-19)「数分たつか雲が崩れたら」→ 4 分(仮)か、雲の育ちが下がり始めたら出る。</li>
 *   <li>出ていく(LEAVE): 腹を上へ転がって戻りながら漕いで速さを戻す。転がり終えたら渡りへ。</li>
 * </ul>
 * 高さは変えない(ふだんの y 950 は巨大な積乱雲の胴 y 300〜3000 の中。仮)。戦いの間も寄り道はする(決定「近くの雷雲へ自分から入る寄り道はする」)。
 */
public final class SkyBossFeeding {

    public static final int CRUISE = 0;
    public static final int APPROACH = 1;
    public static final int FEED = 2;
    public static final int LEAVE = 3;
    private static final String[] NAMES = {"cruise", "approach", "feed", "leave"};

    /** 巨大な積乱雲 1 つ(空気の座標の真ん中・今の胴の半径・育ち)。 */
    public record Storm(long id, double x, double z, float radius, float maturity) {
    }

    /** 頭への指示。 */
    public static final class Orders {
        /** 進む向き(ヨー、度。{@link #seek} のとき)。 */
        public float heading;
        /** その向きへまっすぐ寄る(気まぐれの振れなし)。 */
        public boolean seek;
        /** 曲がらない(まっすぐ飛ぶ・止まる)。 */
        public boolean coast;
        /** 目標までの距離(ブロック。{@link #seek} のとき)。 */
        public double dist;
        /** 速さの目標(空気に対して、ブロック / tick)。 */
        public double speed;
        /** 漕ぎ続ける扇(曲がって傾いている間は旋回の扇)。 */
        public boolean eager;
        /** 高さの目標(頭の y。NaN = ふだんの高さ)と、機首の上げ下げの上限(度)・速さの上限(度 / tick。0 = ふだん)。10-19 夕方の雲海から。 */
        public double altitude = Double.NaN;
        public float maxPitch;
        public float maxPitchRate;
    }

    public int mode = CRUISE;
    public long modeSince;
    /** 寄っている・留まっている雲。 */
    public long stormId;
    public double tx;
    public double tz;
    public float tRadius;
    public float lastMaturity;
    /** これより前は雲に寄らない tick。 */
    public long cooldownUntil = Long.MIN_VALUE / 2;
    /** 寄り終えた雲(同じ雲には戻らない)。 */
    public final long[] fed = new long[SkyBossTuning.FED_MEMORY];
    public int fedNext;
    /** 頭だけ先に向ける残りの区間。 */
    public int lookLead;
    /** 腕の付いた所と雲の真ん中の距離(一つ前の区間。いちばん近づいたかを見る)。 */
    public double lastArmDist = Double.MAX_VALUE;
    /** 次に雷が落ちる tick。 */
    public long nextStrike = SkyBossFlight.NONE;
    /** 出ていくとき転がり戻る向き(度。留まり始めたときの向き。10-19 から保存)。 */
    public float leaveRoll = 180.0F;
    /**
     * 雷雲の中で放電している間(予告と 30 秒)は true(呼ぶ側が毎 tick 決める。保存しない)。その間は出ていかず、避雷針へ雷も落とさない
     * (放電の光と雷を混ぜない。留まる時間が過ぎていたら、放電を終えてすぐ出ていく)。
     */
    public boolean hold;

    private final double[][] arm = {new double[3]};

    public SkyBossFeeding() {
        reset();
    }

    public void reset() {
        mode = CRUISE;
        modeSince = 0L;
        stormId = 0L;
        tx = tz = 0.0;
        tRadius = 0.0F;
        lastMaturity = 0.0F;
        cooldownUntil = Long.MIN_VALUE / 2;
        java.util.Arrays.fill(fed, Long.MIN_VALUE);
        fedNext = 0;
        lookLead = 0;
        lastArmDist = Double.MAX_VALUE;
        nextStrike = SkyBossFlight.NONE;
        leaveRoll = 180.0F;
        hold = false;
    }

    public static String name(int mode) {
        return mode >= 0 && mode < NAMES.length ? NAMES[mode] : "?";
    }

    /** 区間の頭: 状態を進め、{@code o} に頭への指示を、{@code f} に見た目の状態を書く。変わったら true。 */
    public boolean segment(SkyBossFlight f, long now, List<Storm> storms, Orders o) {
        int before = mode;
        o.heading = 0.0F;
        o.seek = false;
        o.coast = false;
        o.speed = SkyBossTuning.AIRSPEED;
        o.eager = false;
        f.lookTarget = 0.0F;
        f.fanOverride = -1;
        Storm cur = mode == CRUISE ? null : find(storms, stormId);
        if (mode == CRUISE && now >= cooldownUntil) {
            Storm s = nearest(f, storms);
            if (s != null) {
                stormId = s.id();
                tx = s.x();
                tz = s.z();
                tRadius = s.radius();
                lastMaturity = s.maturity();
                lastArmDist = Double.MAX_VALUE;
                lookLead = SkyBossTuning.LOOK_LEAD_SEGS;
                mode = APPROACH;
                modeSince = now;
                cur = s;
            }
        }
        if (mode == APPROACH) {
            approach(f, now, cur, o);
        }
        if (mode == FEED) {
            feed(f, now, cur, o, before != FEED);
        }
        if (mode == LEAVE) {
            leave(f, now, o);
        }
        return mode != before;
    }

    private void approach(SkyBossFlight f, long now, Storm cur, Orders o) {
        double headDist = Math.hypot(tx - f.x, tz - f.z);
        if (cur == null || cur.maturity() < SkyBossTuning.STORM_MIN_MATURITY * 0.8F || headDist > SkyBossTuning.STORM_DETECT * 1.6) {
            // 雲が崩れた・見えなくなった: 寄るのをやめる(しばらくは寄らない)
            remember(stormId);
            mode = CRUISE;
            modeSince = now;
            cooldownUntil = now + SkyBossTuning.FEED_COOLDOWN / 4;
            return;
        }
        tRadius = cur.radius();
        lastMaturity = cur.maturity();
        float bearing = (float) Math.toDegrees(Math.atan2(-(tx - f.x), tz - f.z));
        float off = SkyBossBrain.wrapDegrees(bearing - f.yaw);
        f.lookTarget = Math.max(-SkyBossTuning.LOOK_MAX, Math.min(SkyBossTuning.LOOK_MAX, off));
        o.speed = SkyBossTuning.EAGER_SPEED;
        o.eager = true;
        if (lookLead > 0) {
            // 頭だけ先に向けて、体はまだまっすぐ
            lookLead--;
            o.coast = true;
            return;
        }
        f.trail.pointsBehind(f.x, f.y, f.z, new double[]{SkyBossBody.ARM_CENTER}, arm);
        double armDist = Math.hypot(tx - arm[0][0], tz - arm[0][2]);
        if (headDist < tRadius * 0.5) {
            // 頭が真ん中の近く: もう曲がらない(真ん中を過ぎると向きが逆になるため)
            o.coast = true;
            f.lookTarget = 0.0F;
        } else {
            o.seek = true;
            o.heading = bearing;
            o.dist = headDist;
        }
        boolean closest = armDist > lastArmDist && armDist < tRadius * 0.7;
        lastArmDist = armDist;
        if (armDist < tRadius * 0.35 || closest) {
            mode = FEED;
            modeSince = now;
        }
    }

    private void feed(SkyBossFlight f, long now, Storm cur, Orders o, boolean entered) {
        if (entered) {
            // 背を上へ: 傾いていた側へ転がる(どちら向きでも同じ形になる)
            // 10-19 から行き先は「背が上」の決まった向き(前は今 ± 180 で、もう背が上なら腹が上へ戻ってしまった)
            float r = (float) f.rollAt(now);
            float to = SkyBossFlight.backUpRoll(r, f.bank);
            leaveRoll = Math.abs(to - r) < 1.0F ? to + (f.bank >= 0.0F ? -180.0F : 180.0F) : r;
            f.startRoll(to);
            nextStrike = now + SkyBossTuning.ROLL_TICKS + SkyBossTuning.ROLL_LAG_TICKS + SkyBossTuning.STRIKE_INTERVAL_MIN;
        }
        o.coast = true;
        o.speed = SkyBossTuning.HOVER_SPEED;
        f.fanOverride = SkyBossFlight.FAN_HOVER;
        boolean decaying = cur == null || cur.maturity() < SkyBossTuning.STORM_MIN_MATURITY * 0.8F || cur.maturity() < lastMaturity - 1.0E-3F;
        if (cur != null) {
            lastMaturity = cur.maturity();
        }
        if (!hold && (now - modeSince >= SkyBossTuning.FEED_TICKS || decaying)) {
            remember(stormId);
            mode = LEAVE;
            modeSince = now;
            nextStrike = SkyBossFlight.NONE;
            // 腹を上へ戻る(来た向きへ転がり戻る。転がっている途中で出るときも、入ったときの向きへ)
            f.startRoll(leaveRoll);
            leave(f, now, o);
        }
    }

    private void leave(SkyBossFlight f, long now, Orders o) {
        boolean rolling = now - modeSince < SkyBossTuning.ROLL_TICKS + SkyBossTuning.ROLL_LAG_TICKS;
        o.speed = SkyBossTuning.AIRSPEED;
        o.coast = rolling;
        o.eager = rolling;
        f.fanOverride = -1;
        if (!rolling) {
            mode = CRUISE;
            modeSince = now;
            cooldownUntil = now + SkyBossTuning.FEED_COOLDOWN;
        }
    }

    /** 確かめ用: 雲 {@code s} へ今すぐ寄り始める(気づく距離・寄らない間を無視)。 */
    public void force(Storm s, long now) {
        stormId = s.id();
        tx = s.x();
        tz = s.z();
        tRadius = s.radius();
        lastMaturity = s.maturity();
        lastArmDist = Double.MAX_VALUE;
        lookLead = SkyBossTuning.LOOK_LEAD_SEGS;
        mode = APPROACH;
        modeSince = now;
    }

    /** 雷が落ちる時刻か(背が上になり終え、尾まで転がり終えている間)。 */
    public boolean strikeDue(SkyBossFlight f, long now) {
        return mode == FEED && !hold && nextStrike != SkyBossFlight.NONE && now >= nextStrike
                && f.backUp(now - SkyBossTuning.ROLL_LAG_TICKS) && f.backUp(now);
    }

    /** 雷を落とした: 次の雷を決める({@code charge} = 雷が届くまでの tick)。 */
    public void struck(long now, int charge, double u) {
        int gap = SkyBossTuning.STRIKE_INTERVAL_MIN
                + (int) (u * (SkyBossTuning.STRIKE_INTERVAL_MAX - SkyBossTuning.STRIKE_INTERVAL_MIN));
        nextStrike = now + charge + gap;
    }

    private Storm nearest(SkyBossFlight f, List<Storm> storms) {
        Storm best = null;
        double bd = SkyBossTuning.STORM_DETECT;
        for (Storm s : storms) {
            if (s.maturity() < SkyBossTuning.STORM_MIN_MATURITY || fedBefore(s.id())) {
                continue;
            }
            double d = Math.hypot(s.x() - f.x, s.z() - f.z);
            if (d < bd) {
                bd = d;
                best = s;
            }
        }
        return best;
    }

    private static Storm find(List<Storm> storms, long id) {
        for (Storm s : storms) {
            if (s.id() == id) {
                return s;
            }
        }
        return null;
    }

    private boolean fedBefore(long id) {
        for (long v : fed) {
            if (v == id) {
                return true;
            }
        }
        return false;
    }

    private void remember(long id) {
        if (!fedBefore(id)) {
            fed[fedNext] = id;
            fedNext = (fedNext + 1) % fed.length;
        }
    }
}
