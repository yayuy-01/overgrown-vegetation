package com.yayuy.overgrownvegetation.skyboss;

/**
 * 放電(大技 2。2026-10-19 ユーザー「放電頼めますか」。サーバーだけ。Minecraft のクラスを使わない = 素の Java で流して確かめられる)。
 *
 * <p>決まっていること(行動パターンの「大技 2: 放電」): 雷雲の中で背を上にして避雷針で雷を吸って溜める → 雷雲を出ても持ち歩く(電紋がうっすら光る)→
 * 溜まりきったらボスの好きなときに、背を上にして 30 秒、体のまわり半径 200(10-19 から 2000。{@link SkyBossTuning#DISCHARGE_RADIUS})へプラズマボールのような雷 → 放電中は蕾が閉じ、電紋と避雷針が激しく光る。
 * 10-19 ユーザー: ショートは「避雷針の破壊で体が下に沈み、そこから一定時間攻撃がストップするだけ」。
 *
 * <p>状態の進め方(値は {@link SkyBossFlight} に書く: 両側が同じ時刻で光らせ・アニメを流し・雷を出すため):
 * <ul>
 *   <li>溜め: 雷が避雷針に届くたび {@link #struck} で {@link SkyBossFlight#charge} を 1 / {@link SkyBossTuning#CHARGE_STRIKES} ずつ。</li>
 *   <li>始める(IDLE → PREP): 溜まりきり・避雷針が壊れていない・隙でない・前の放電から間がある・転がっていない・始めたい({@code want}: 呼ぶ側が決める
 *       = 戦いの中で段階 {@link SkyBossTuning#DISCHARGE_STAGE} から)。腹が上なら背を上へ転がり(尾まで 26 秒 = 予告)、背が上(雷雲の中)なら
 *       {@link SkyBossTuning#DISCHARGE_TELEGRAPH} の予告の後に。</li>
 *   <li>放電(ACTIVE): {@link SkyBossTuning#DISCHARGE_TICKS}。終わると溜めは 0、雷雲の外なら腹を上へ転がり戻る(RECOVER)。</li>
 *   <li>ショート({@link #shorted}): 避雷針が壊れたら(いつでも)溜めは 0、隙 {@link SkyBossTuning#STUN_TICKS}。予告・放電の途中なら止め、雷雲の外なら
 *       体が沈んで跳ね返るのを待ってから({@link SkyBossTuning#SHORT_ROLL_DELAY})腹を上へ戻る。体が沈むのは避雷針の爆発の反動({@link SkyBossRecoil}。背が上なら下へ)。</li>
 * </ul>
 * 渡りの途中で放電する間は曲がらず遅く飛び、雷雲には寄らない(呼ぶ側が {@link #blocksFeeding})。雷雲の中では寄り道が留まり続ける({@link SkyBossFeeding#hold})。
 */
public final class SkyBossDischarge {

    public static final int IDLE = 0;
    public static final int PREP = 1;
    public static final int ACTIVE = 2;
    public static final int RECOVER = 3;
    private static final String[] NAMES = {"idle", "prep", "active", "recover"};

    public int mode = IDLE;
    public long modeSince;
    /** 雷雲の中(背が上のまま)で始めたか(終わっても転がらない)。 */
    public boolean inStorm;
    /** 雷雲の外で始めたとき、終わって戻る向き(度)。 */
    public float returnRoll = 180.0F;
    /** 腹を上へ戻り始める tick(ショートの後。{@link SkyBossFlight#NONE} = 無い / もう始めた)。 */
    public long rollBackAt = SkyBossFlight.NONE;
    /** これより前は始めない tick。 */
    public long cooldownUntil = Long.MIN_VALUE / 2;
    /** 溜めに数えた最後の雷の tick(同じ雷を 2 回数えない)。 */
    public long lastStrike = SkyBossFlight.NONE;

    public void reset() {
        mode = IDLE;
        modeSince = 0L;
        inStorm = false;
        returnRoll = 180.0F;
        rollBackAt = SkyBossFlight.NONE;
        cooldownUntil = Long.MIN_VALUE / 2;
        lastStrike = SkyBossFlight.NONE;
    }

    public static String name(int mode) {
        return mode >= 0 && mode < NAMES.length ? NAMES[mode] : "?";
    }

    /** 予告・放電・戻る途中か。 */
    public boolean busy() {
        return mode != IDLE;
    }

    /** 雷雲に寄るのを止めるか(雷雲の外で放電している間)。 */
    public boolean blocksFeeding() {
        return busy() && !inStorm;
    }

    /** 雷雲の中の寄り道を留まらせ、雷を止めるか(雷雲の中で予告・放電している間)。 */
    public boolean holdsFeeding() {
        return (mode == PREP || mode == ACTIVE) && inStorm;
    }

    /** 避雷針へ雷が届いた(届いた tick {@code at})。溜めを増やしたら true。 */
    public boolean struck(SkyBossFlight f, long at) {
        if (at == lastStrike) {
            return false;
        }
        lastStrike = at;
        f.charge = Math.min(1.0F, f.charge + 1.0F / SkyBossTuning.CHARGE_STRIKES);
        return true;
    }

    /**
     * 1 tick 進める。{@code want} = 始めたい(戦いの中など)、{@code feeding} = 雷雲の中で留まっている、{@code rodOk} = 避雷針が壊れていない。
     * 状態が変わった(頭への指示を出し直す・全員へ送る)なら true。
     */
    public boolean tick(SkyBossFlight f, long now, boolean want, boolean feeding, boolean rodOk) {
        switch (mode) {
            case IDLE -> {
                if (want && canStart(f, now, rodOk)) {
                    start(f, now, feeding);
                    return true;
                }
            }
            case PREP -> {
                if (now >= f.dischargeAt) {
                    mode = ACTIVE;
                    modeSince = now;
                    return true;
                }
            }
            case ACTIVE -> {
                if (now >= f.dischargeEnd) {
                    finish(f, now);
                    return true;
                }
            }
            case RECOVER -> {
                if (rollBackAt != SkyBossFlight.NONE && now >= rollBackAt) {
                    rollBackAt = SkyBossFlight.NONE;
                    // 雷雲の外なら来た向きへ(予告の途中のショートで、まだ背を上へ転がっている最中でも、今の向きから戻る)
                    if (!inStorm && Math.abs(f.rollTo - returnRoll) > 1.0F) {
                        f.startRoll(returnRoll);
                    }
                    return true;
                }
                if (rollBackAt == SkyBossFlight.NONE && !f.rolling(now)) {
                    mode = IDLE;
                    modeSince = now;
                    return true;
                }
            }
            default -> {
            }
        }
        return false;
    }

    /** 今始められるか({@code want} を除く)。 */
    public boolean canStart(SkyBossFlight f, long now, boolean rodOk) {
        return mode == IDLE && rodOk && f.charge >= 1.0F && (f.stunUntil == SkyBossFlight.NONE || now >= f.stunUntil)
                && now >= cooldownUntil && !f.rolling(now);
    }

    /** 始める(確かめ用の devbridge も呼ぶ。条件は見ない)。 */
    public void start(SkyBossFlight f, long now, boolean feeding) {
        inStorm = feeding;
        mode = PREP;
        modeSince = now;
        rollBackAt = SkyBossFlight.NONE;
        f.chargeUpAt = now;
        if (f.backUp(now)) {
            // もう背が上(雷雲の中。確かめ用に渡りで背が上のときは、終わったら腹が上へ)
            returnRoll = (float) f.rollAt(now) + 180.0F;
            f.dischargeAt = now + SkyBossTuning.DISCHARGE_TELEGRAPH;
        } else {
            float r = (float) f.rollAt(now);
            returnRoll = r;
            f.startRoll(SkyBossFlight.backUpRoll(r, f.bank));
            f.dischargeAt = now + SkyBossTuning.ROLL_TICKS + SkyBossTuning.ROLL_LAG_TICKS;
        }
        f.dischargeEnd = f.dischargeAt + SkyBossTuning.DISCHARGE_TICKS;
    }

    private void finish(SkyBossFlight f, long now) {
        f.charge = 0.0F;
        cooldownUntil = now + SkyBossTuning.DISCHARGE_COOLDOWN;
        mode = RECOVER;
        modeSince = now;
        rollBackAt = now;
    }

    /** ショート: 避雷針が壊れた(いつでも)。溜めは 0、隙を付け、予告・放電の途中なら止める。 */
    public void shorted(SkyBossFlight f, long now) {
        f.charge = 0.0F;
        f.stunUntil = now + SkyBossTuning.STUN_TICKS;
        if (mode == PREP || mode == ACTIVE) {
            if (f.dischargeAt == SkyBossFlight.NONE || now < f.dischargeAt) {
                f.dischargeAt = now;
            }
            f.dischargeEnd = now;
            cooldownUntil = now + SkyBossTuning.DISCHARGE_COOLDOWN;
            mode = RECOVER;
            modeSince = now;
            rollBackAt = now + SkyBossTuning.SHORT_ROLL_DELAY;
        }
    }

    /**
     * 頭への指示を書き足す(区間の頭。寄り道の指示の後)。雷雲の外で予告・放電している間は曲がらず遅く、戻る間は漕ぎ続けて速さを戻す。
     * 雷雲の中では寄り道の指示のまま(留まってホバリング。放電の扇は {@link SkyBossFlight#step} が出す)。
     */
    public void orders(SkyBossFlight f, SkyBossFeeding.Orders o) {
        if (!busy() || inStorm) {
            return;
        }
        o.seek = false;
        o.coast = true;
        if (mode == RECOVER) {
            o.speed = SkyBossTuning.AIRSPEED;
            o.eager = true;
            f.fanOverride = -1;
        } else {
            o.speed = SkyBossTuning.DISCHARGE_SPEED;
            o.eager = false;
            // 転がって背を上へ向ける間は雷雲の中と同じホバリングの扇(背が上の向きで作った扇の動き)
            f.fanOverride = SkyBossFlight.FAN_HOVER;
        }
        f.lookTarget = 0.0F;
    }
}
