package com.yayuy.overgrownvegetation.skyboss;

/**
 * 空の巨大生物の頭(サーバーだけ)。区間ごとに次の操作を決める。状態(今の旋回)は {@link SkyBossData} が保存する。
 *
 * <p>1 段目(2026-10-18)は渡りだけ: 世界の風下へ(指定「風任せ」)、風下をはさんで左右へ振れながら、ふだんの高さを保つ。
 * 雷雲に寄る・汚染されていない土地を目指す・雷雲の中で背を上にする・潜伏と飛び出し・戦闘は次の段で足す。
 *
 * <h2>旋回(10-18 午後の直し)</h2>
 * まっすぐ飛ぶ → 1 回の旋回 → まっすぐ、を繰り返す。旋回は始めるときに「どれだけ向きを変えるか(Δ)」と「半径」を決め、
 * 曲がる速さ = 速さ ÷ 半径 で決まった区間の数だけ曲がって、ちょうど目標の向きで終わる。曲がる速さは始めと終わりの数区間で段々に上げ下げする。
 * <ul>
 *   <li>向き: 小さな向き直し(8〜22°)か、風下をはさんで反対の側へ振れる(風下から 25〜80°。差は 50〜160° ほど)。風上へは回らない。</li>
 *   <li>半径: 大きく向きを変えるときほど大きい半径({@link SkyBossTuning#TURN_RADIUS_MIN}〜{@link SkyBossTuning#TURN_RADIUS_MAX})。</li>
 *   <li>傾き: 指定「旋回半径が大きいかつ、向きを変える先が向きを変える前の向いていた方向との差が大きいほど傾く」→
 *       {@link SkyBossTuning#MAX_BANK} × 向きの差の分 × 半径の分。区間ごとの目標 = それ × その区間の曲がる速さの割合(始めと終わりは浅い)。
 *       前(10-18 午前)は曲がる速さに比べて傾けていたので、12° の小さな向き直しでも 70° 傾いた。</li>
 * </ul>
 * Minecraft のクラスを使わない(素の Java で道を流して確かめられるように)。
 */
public final class SkyBossBrain {

    /** 今の旋回の区間の数(0 = 旋回していない)と、そのうち何区間進んだか。 */
    int turnSegs;
    int turnStep;
    /** 曲がる速さが段々に変わる区間の数(始めと終わりのそれぞれ)。 */
    int turnRamp;
    /** いちばん速いときの曲がる速さ(度 / tick、+ でヨーが増える = 世界の右)と傾き(度、− で世界の右が下)。 */
    float turnRate;
    float turnBank;
    /** 今の旋回で変える向き(度)と半径(ブロック)。確かめ用(ログ)。 */
    float turnDelta;
    float turnRadius;
    /** まっすぐ飛ぶ残りの区間。 */
    int straightLeft;
    /** 何回目の旋回か(乱数に混ぜる)。 */
    long maneuver;

    /** 出し直したとき: 1 区間まっすぐ飛んでから最初の旋回を決める。 */
    public void reset() {
        turnSegs = 0;
        turnStep = 0;
        turnRamp = 0;
        turnRate = 0.0F;
        turnBank = 0.0F;
        turnDelta = 0.0F;
        turnRadius = 0.0F;
        straightLeft = 1;
        maneuver = 0L;
    }

    /** 次の区間の操作を {@code f} に書く。{@code index} = 何番目の区間か、{@code seed} = この個体の決まった乱数の種。 */
    public void next(SkyBossFlight f, long index, long seed) {
        double t = f.tick;
        float downwind = (float) Math.toDegrees(Math.atan2(-SkyBossWorld.windX(t), SkyBossWorld.windZ(t)));
        next(f, index, seed, downwind);
    }

    /** 寄り道の指示つき(10-19)。{@code o.coast} = 曲がらない、{@code o.seek} = {@code o.heading} へ寄る、どちらでもなければ渡り(風下)。速さは {@code o.speed}。 */
    public void next(SkyBossFlight f, long index, long seed, SkyBossFeeding.Orders o) {
        double t = f.tick;
        float downwind = (float) Math.toDegrees(Math.atan2(-SkyBossWorld.windX(t), SkyBossWorld.windZ(t)));
        next(f, index, seed, downwind, o);
    }

    /** {@code downwind} = 今の風下の向き(ヨー、度)。 */
    void next(SkyBossFlight f, long index, long seed, float downwind) {
        next(f, index, seed, downwind, null);
    }

    /** 寄っているか(前の区間)。寄り始めたら渡りの旋回をやめて決め直す。 */
    private boolean seeking;

    void next(SkyBossFlight f, long index, long seed, float downwind, SkyBossFeeding.Orders o) {
        int n = SkyBossTuning.SEGMENT_TICKS;
        boolean coast = o != null && o.coast;
        boolean seek = o != null && o.seek && !coast;
        if (coast || (seek && !seeking)) {
            // 曲がらない・寄り始め: 今の旋回をやめる(傾きは両側の式でなめらかに戻る)
            turnSegs = 0;
            turnStep = 0;
            straightLeft = 0;
        }
        seeking = seek;
        if (!coast && !seek && turnStep >= turnSegs && straightLeft <= 0) {
            plan(f, seed, downwind);
        }
        if (seek) {
            pursue(f, wrapDegrees(o.heading - f.yaw), o.dist);
        } else if (turnStep < turnSegs) {
            float share = rateShare(turnStep, turnSegs, turnRamp);
            f.yawRate = turnRate * share;
            f.bankTarget = turnBank * share;
            turnStep++;
        } else {
            f.yawRate = 0.0F;
            f.bankTarget = 0.0F;
            straightLeft--;
        }

        double targetY = SkyBossTuning.CRUISE_Y + SkyBossTuning.CRUISE_SWING * Math.sin(index * 0.21 + (seed >> 20) % 1000);
        // 10-19 夕方: 雲海に潜る・上がる間は、指示の高さへ深い角度で
        float maxPitch = SkyBossTuning.MAX_PITCH;
        float maxRate = SkyBossTuning.MAX_PITCH_RATE;
        if (o != null && !Double.isNaN(o.altitude)) {
            targetY = o.altitude;
            maxPitch = o.maxPitch > 0.0F ? o.maxPitch : maxPitch;
            maxRate = o.maxPitchRate > 0.0F ? o.maxPitchRate : maxRate;
        }
        // ピッチは + で機首が下。低ければ機首を上げる
        float targetPitch = (float) clamp((f.y - targetY) * 0.08, -maxPitch, maxPitch);
        f.pitchRate = (float) clamp((targetPitch - f.pitch) / n, -maxRate, maxRate);

        // 速さは目標だけ決める(両側の式が一定の加速で近づける。10-19)
        f.speedTarget = (float) (o != null ? o.speed : SkyBossTuning.AIRSPEED);
        f.segmentEnd = f.tick + n;
    }

    /** 次の旋回(と、その後にまっすぐ飛ぶ区間の数)を決める。 */
    private void plan(SkyBossFlight f, long seed, float downwind) {
        maneuver++;
        double u1 = rand(seed, maneuver, 1);
        double u2 = rand(seed, maneuver, 2);
        double u3 = rand(seed, maneuver, 3);
        double u4 = rand(seed, maneuver, 4);
        double u5 = rand(seed, maneuver, 5);
        float meander = SkyBossTuning.MEANDER;
        // 今の向きの風下からの振れ(+ で風下より右)
        float dev = wrapDegrees(f.yaw - downwind);
        float target;
        if (u1 < 0.35) {
            // 小さな向き直し(振れすぎていたら風下の方へ)
            float d = (float) (8.0 + 14.0 * u2);
            float s = Math.abs(dev) > 35.0F ? -Math.signum(dev) : (u3 < 0.5 ? -1.0F : 1.0F);
            target = dev + s * d;
        } else {
            // 風下をはさんで反対の側へ振れる
            float side = dev > 1.0F ? -1.0F : dev < -1.0F ? 1.0F : (u3 < 0.5 ? -1.0F : 1.0F);
            target = side * (float) (25.0 + 55.0 * u2);
        }
        target = (float) clamp(target, -meander, meander);
        float delta = wrapDegrees(target - dev);
        straightLeft = SkyBossTuning.STRAIGHT_SEGS_MIN
                + (int) (u5 * (SkyBossTuning.STRAIGHT_SEGS_MAX - SkyBossTuning.STRAIGHT_SEGS_MIN + 1));
        turnStep = 0;
        turn(delta, u4);
    }

    /**
     * 雷雲へ寄る(10-19): 区間ごとに、今の向きに接して目標を通る円の曲がり方で曲がる(追いかけの定番の決め方)。曲がり = 2 sin(向きの差) ÷ 距離。
     * 半径は {@link SkyBossTuning#SEEK_RADIUS_MIN} より小さくしない(目標が後ろ・近すぎるときはいちばんきつく曲がって回り込む)。
     * 最初は渡りと同じ長い旋回で決めていたが、雲の真ん中が旋回の円の内側に入って雲のまわりを回り続けた(10-19 のテストクライアント)。
     * 傾きは渡りの旋回と同じ考え(向きの差が大きく・半径が大きいほど深い)。
     */
    private void pursue(SkyBossFlight f, float delta, double dist) {
        turnSegs = 0;
        turnStep = 0;
        straightLeft = 0;
        double v = Math.max(f.speed, 0.3);
        double kMax = 1.0 / SkyBossTuning.SEEK_RADIUS_MIN;
        double k = 2.0 * Math.sin(Math.toRadians(delta)) / Math.max(dist, 1.0);
        if (Math.abs(delta) > 90.0F) {
            k = Math.signum(delta) * kMax;
        }
        k = clamp(k, -kMax, kMax);
        f.yawRate = (float) Math.toDegrees(v * k);
        double ad = Math.abs(delta);
        if (ad < 2.0 || Math.abs(k) < 1.0E-7) {
            f.bankTarget = 0.0F;
            return;
        }
        double r = 1.0 / Math.abs(k);
        double full = Math.min(1.0, ad / SkyBossTuning.TURN_FULL);
        double radiusShare = clamp((r - SkyBossTuning.TURN_RADIUS_MIN) / (SkyBossTuning.TURN_RADIUS_MAX - SkyBossTuning.TURN_RADIUS_MIN), 0.0, 1.0);
        double bank = SkyBossTuning.MAX_BANK * Math.sin(full * Math.PI / 2.0)
                * (SkyBossTuning.BANK_RADIUS_MIN_WEIGHT + (1.0 - SkyBossTuning.BANK_RADIUS_MIN_WEIGHT) * radiusShare);
        f.bankTarget = (float) (-Math.signum(delta) * bank);
    }

    /** 向きを {@code delta} 変える旋回を決める({@code u4} = 半径の気まぐれ)。 */
    private void turn(float delta, double u4) {
        if (Math.abs(delta) < 3.0F) {
            turnSegs = 0;
            turnDelta = 0.0F;
            turnRadius = 0.0F;
            return;
        }
        double ad = Math.abs(delta);
        double full = Math.min(1.0, ad / SkyBossTuning.TURN_FULL);
        double rMin = SkyBossTuning.TURN_RADIUS_MIN;
        double rMax = SkyBossTuning.TURN_RADIUS_MAX;
        // 大きく向きを変えるときほど大きい半径
        double radius = rMin + (rMax - rMin) * clamp(0.6 * full + 0.4 * u4, 0.0, 1.0);
        int n = SkyBossTuning.SEGMENT_TICKS;
        double rate0 = Math.toDegrees(SkyBossTuning.AIRSPEED / radius);
        // 曲がる速さの割合の和 = 段々の区間の数 + いっぱいの区間の数(段々の区間は平均で半分)
        double need = ad / (n * rate0);
        int ramp = (int) clamp(Math.floor(need / 4.0), 1, 3);
        // 切り上げ(区間の数で割り切れない分は少しゆっくり曲がる = 半径が決めたものより小さくならない)
        int hold = (int) Math.max(0.0, Math.ceil(need - ramp));
        double rate = ad / (n * (double) (ramp + hold));
        double r = SkyBossTuning.AIRSPEED / Math.toRadians(rate);
        double radiusShare = clamp((r - rMin) / (rMax - rMin), 0.0, 1.0);
        double bank = SkyBossTuning.MAX_BANK * Math.sin(full * Math.PI / 2.0)
                * (SkyBossTuning.BANK_RADIUS_MIN_WEIGHT + (1.0 - SkyBossTuning.BANK_RADIUS_MIN_WEIGHT) * radiusShare);
        turnSegs = 2 * ramp + hold;
        turnRamp = ramp;
        turnRate = (float) (Math.signum(delta) * rate);
        turnBank = (float) (-Math.signum(delta) * bank);
        turnDelta = delta;
        turnRadius = (float) r;
    }

    /** 旋回の j 区間目の曲がる速さの割合(始めと終わりの {@code ramp} 区間は 1/(ramp+1) ずつ上げ下げ)。 */
    static float rateShare(int j, int segs, int ramp) {
        if (j < ramp) {
            return (j + 1) / (float) (ramp + 1);
        }
        if (j >= segs - ramp) {
            return (segs - j) / (float) (ramp + 1);
        }
        return 1.0F;
    }

    /** 種・旋回の回数・用途から決まる 0〜1 の乱数(SplitMix64 の混ぜ方)。 */
    static double rand(long seed, long maneuver, int salt) {
        long z = seed + maneuver * 0x9E3779B97F4A7C15L + salt * 0xD1B54A32D192ED03L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z = z ^ (z >>> 31);
        return (z >>> 11) * 0x1.0p-53;
    }

    static float wrapDegrees(float v) {
        float w = v % 360.0F;
        if (w >= 180.0F) {
            w -= 360.0F;
        }
        if (w < -180.0F) {
            w += 360.0F;
        }
        return w;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : Math.min(v, hi);
    }
}
