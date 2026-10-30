package com.yayuy.overgrownvegetation.skyboss;

/**
 * 空の巨大生物の一日のリズムと、ときどきの身震い(2026-10-19 夕方。ユーザー「とりあえず生き物らしさと雲海の方を実装していきましょう」)。
 * 決まりは行動パターン「生き物らしさ」:
 * <ul>
 *   <li>決定「夜は扇を半分たたんで眠り、電紋がゆっくり脈打つ」→ 夜(dayTime {@link SkyBossTuning#SLEEP_FROM}〜{@link SkyBossTuning#SLEEP_TO})は
 *       {@link SkyBossFlight#REST_SLEEP}(扇 sleep・ゆっくり飛ぶ。電紋の光はクライアントの {@code SkyBossGlow})。</li>
 *   <li>決定「潜伏や眠りから覚めたとき、扇を目一杯広げて体を反らせ、顎を大きく開く」→ 夜明けに起きると伸びとあくび({@link SkyBossActClock#startStretch})。</li>
 *   <li>決定「晴れた朝は腹を日の昇る方へ少し傾けて扇を広げて日光浴」→ 晴れた朝(夜明け 〜 {@link SkyBossTuning#BASK_TO})は {@link SkyBossFlight#REST_BASK}
 *       (扇 bask・腹を東へ傾ける = {@link #baskRoll})。</li>
 *   <li>決定「ときどき扇の刃を前から後ろへ波打たせ、雨粒や枯れ葉を振り落とす」→ 起きている渡りの間、3〜6 分(仮)おきに
 *       {@link SkyBossFlight#SHAKE_CASUAL}(扇の身震い + 体の小さな震え。雨粒・枯れ葉の粒はクライアント)。</li>
 * </ul>
 * 戦いの中・雷雲に寄る・留まる・出ていく途中は休まない(戦いになったらすぐ起きる。伸びはしない)。放電・ビーム・技の体の動きの間は休み方を変えない。
 * Minecraft のクラスを使わない(GameTest で素で流せる)。時刻・数値はすべて仮。
 */
public final class SkyBossRhythm {

    /** 日光浴をやめて傾きを戻している途中({@link SkyBossFlight#restKind} の値。扇には休み方なしと同じ)。 */
    public static final int REST_BASK_END = 3;

    /** 次のときどきの身震い(サーバーだけ。保存しない)。 */
    private long nextShiver = SkyBossFlight.NONE;

    /**
     * 1 tick 進める。{@code dayTime} = ワールドの時刻(0〜24000 でくり返す)、{@code clear} = 雨でない、{@code calm} = 戦いの外で渡っている
     * (雷雲に寄る・留まる・出ていく途中でない)、{@code busy} = 放電・ビーム・技・体の動きの途中、{@code roll} = 乱数(0〜1)。
     * 飛び方の値を変えたら true(通信して保存する)。
     */
    public boolean tick(SkyBossFlight f, long now, long dayTime, boolean clear, boolean calm, boolean busy, double roll) {
        int day = (int) Math.floorMod(dayTime, 24000L);
        boolean night = day >= SkyBossTuning.SLEEP_FROM && day < SkyBossTuning.SLEEP_TO;
        boolean morning = day >= SkyBossTuning.SLEEP_TO || day < SkyBossTuning.BASK_TO;
        if (!calm) {
            // 戦い・雷雲: すぐ起きる(日光浴なら傾きを戻す)
            nextShiver = SkyBossFlight.NONE;
            return wake(f, now, false);
        }
        if (busy) {
            return false;
        }
        boolean changed = false;
        int kind = f.restKind;
        if (kind == SkyBossFlight.REST_SLEEP) {
            if (!night) {
                // 夜明け: 起きて伸びとあくび
                changed = wake(f, now, true);
            }
            return changed;
        }
        if (kind == SkyBossFlight.REST_BASK) {
            if (!morning || !clear || night) {
                f.restKind = REST_BASK_END;
                f.restSince = now;
                return true;
            }
            return false;
        }
        // 起きている(REST_NONE か、日光浴の傾きを戻している途中)
        if (night) {
            f.restKind = SkyBossFlight.REST_SLEEP;
            f.restSince = now;
            return true;
        }
        if (morning && clear && !f.stretching(now) && kind != REST_BASK_END) {
            f.restKind = SkyBossFlight.REST_BASK;
            f.restSince = now;
            return true;
        }
        if (kind == REST_BASK_END && now - f.restSince > SkyBossTuning.BASK_EASE) {
            f.restKind = SkyBossFlight.REST_NONE;
            changed = true;
        }
        // ときどきの身震い
        if (nextShiver == SkyBossFlight.NONE) {
            nextShiver = now + SkyBossTuning.CASUAL_SHIVER_MIN + (long) (roll * (SkyBossTuning.CASUAL_SHIVER_MAX - SkyBossTuning.CASUAL_SHIVER_MIN));
        } else if (now >= nextShiver && (f.shakeAt == SkyBossFlight.NONE || now - f.shakeAt > SkyBossTuning.CASUAL_SHAKE_TICKS)) {
            f.shakeAt = now;
            f.shakeKind = SkyBossFlight.SHAKE_CASUAL;
            nextShiver = SkyBossFlight.NONE;
            changed = true;
        }
        return changed;
    }

    /** 休みをやめる({@code stretch} = 伸びとあくびをする)。変えたら true。 */
    private static boolean wake(SkyBossFlight f, long now, boolean stretch) {
        int kind = f.restKind;
        if (kind == SkyBossFlight.REST_NONE || kind == REST_BASK_END) {
            return false;
        }
        f.restKind = kind == SkyBossFlight.REST_BASK ? REST_BASK_END : SkyBossFlight.REST_NONE;
        f.restSince = now;
        if (stretch) {
            SkyBossActClock.startStretch(f, now);
        }
        return true;
    }

    /** 確かめ用(devbridge): 次の tick にときどきの身震い。 */
    public void debugShiverNow(long now) {
        nextShiver = now;
    }

    /**
     * 日光浴の傾き(度。体の軸のまわりの向きに足す)。腹を東(+x。日の昇る方)へ傾ける: 体の横の向きと東の向きの重なりに比例(東が真横なら最大、
     * 東へ向かって飛んでいれば 0)。始めと終わりは {@link SkyBossTuning#BASK_EASE} tick かけてなめらかに。両側が同じ式。向きは GameTest で確かめた(腹の扉の外向きが東へ傾く)。
     */
    public static double baskRoll(SkyBossFlight f, double t) {
        int kind = f.restKind;
        if ((kind != SkyBossFlight.REST_BASK && kind != REST_BASK_END) || f.restSince == SkyBossFlight.NONE) {
            return 0.0;
        }
        double u = Math.max(0.0, Math.min(1.0, (t - f.restSince) / SkyBossTuning.BASK_EASE));
        double k = u * u * (3.0 - 2.0 * u);
        if (kind == REST_BASK_END) {
            k = 1.0 - k;
        }
        // 東 (1, 0, 0) が体の横のどちら側か(頭の前 = (−sin yaw, 0, cos yaw))。符号は GameTest(腹の扉の外向きが東へ傾く)で決めた
        double side = Math.cos(Math.toRadians(f.yaw));
        return SkyBossTuning.BASK_TILT_DEG * k * side;
    }
}
