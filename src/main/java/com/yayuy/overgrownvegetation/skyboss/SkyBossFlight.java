package com.yayuy.overgrownvegetation.skyboss;

/**
 * 空の巨大生物の頭の動き(空気の座標)。サーバーとクライアントが<b>同じ式で 1 tick ずつ</b>進める。
 *
 * <p>動きは「区間」ごとに決まる: サーバーの頭({@link SkyBossBrain})が {@link SkyBossTuning#SEGMENT_TICKS} ごとに
 * 速さ・曲がる速さ・機首の上げ下げの速さを決め、その間は同じ操作で進む。区間の始めの姿勢・操作・道を送れば、クライアントは
 * 同じ道を自分で引ける(座標を毎 tick 送らない。攻城戦のボス {@code SiegeBossMotion} の「式を共有する」考え方を、AI で決まる動きへ広げたもの)。
 * 計算は {@link StrictMath} で、どの機械でも同じ答えになる。区間が終わっても次が届くまでは同じ操作で進め続ける(届いたら合わせ直す)。
 */
public final class SkyBossFlight {

    /** 今の tick(ゲームの時刻 = この姿勢になった時刻)。 */
    public long tick;
    public double x;
    public double y;
    public double z;
    /** 向き(Minecraft のヨー・ピッチ、度。ピッチは + で機首が下)。 */
    public float yaw;
    public float pitch;
    /** 一つ前の tick の頭(描画の補間用)。 */
    public double px;
    public double py;
    public double pz;

    /** 今の区間の操作。 */
    public float speed;
    public float yawRate;
    public float pitchRate;
    /**
     * 今の区間の体の傾きの目標(度、見た目だけ。− で世界の右が下 = 右へ曲がるとき)。頭が旋回ごとに決め、サーバーとクライアントが同じ式でなめらかに追う({@link #bank})。
     * 動き(道)には効かない。
     */
    public float bankTarget;
    /** 今の区間が終わる tick。 */
    public long segmentEnd;

    /**
     * 体の傾き(度、− で世界の右が下)と、その 2 段のなめらかな追い方の 1 段目。{@link #bankTarget} を追う。
     * 10-18 夕方からサーバーとクライアントが同じ式で進める(当たり判定の形も傾けるため。前はクライアントだけ)。
     */
    public float bank;
    public float bankMid;
    /** 一つ前の tick の傾き(描画の補間用)。 */
    public float pbank;
    /** 扇の層の動き({@link #FAN_CRUISE} など)と、それに変わった tick({@link SkyBossFanClock})。 */
    public int fanMode;
    public long fanModeSince;
    /** 一つ前の扇の層の動きと、それに変わった tick(切り替わりの補間で前の形を出すため)。 */
    public int prevFanMode;
    public long prevFanModeSince;

    // ------------------------------------------------------------------ 雷雲に寄る・背を上にする(10-19 から)
    /** 扇の層の動き: 0 = 渡りの漕ぎと滑空、1 = turn_left、2 = turn_right、3 = ホバリング、4 = 漕ぎ続ける、5 = 身震い(雷が落ちたとき)。 */
    public static final int FAN_CRUISE = 0;
    public static final int FAN_TURN_LEFT = 1;
    public static final int FAN_TURN_RIGHT = 2;
    public static final int FAN_HOVER = 3;
    public static final int FAN_EAGER = 4;
    public static final int FAN_SHIVER = 5;
    /** 放電中(背が上。扇を広げ、電気でひきつるように震える)。10-19 から。{@link #discharging} の間は扇の層がこれになる。 */
    public static final int FAN_DISCHARGE = 6;
    /** 羽ばたきの下降気流(1 回)と、転がりのビームの構え(くり返し)。10-19 の技から。 */
    public static final int FAN_DOWNDRAFT = 7;
    public static final int FAN_BRACE = 8;
    /** 生き物らしさ(10-19 夕方): 眠り(扇を半分たたむ)・日光浴(扇を広げて止める)・伸び(1 回)・すぼめ・雲海からの飛び出し(1 回)。 */
    public static final int FAN_SLEEP = 9;
    public static final int FAN_BASK = 10;
    public static final int FAN_STRETCH = 11;
    public static final int FAN_TUCK = 12;
    public static final int FAN_BREACH = 13;
    public static final long NONE = Long.MIN_VALUE;

    // ------------------------------------------------------------------ 放電(10-19 から。状態の進め方は SkyBossDischarge、ここは両側が描く・当たりを出すための値)
    /** 溜まった雷(0〜1。1 で放電できる)。電紋の光の満ち方。 */
    public float charge;
    /** 放電の予告が始まった tick(背が上へ転がり始めた・雷雲の中で光り始めた)。{@link #NONE} = 無い。 */
    public long chargeUpAt = NONE;
    /** 雷が飛び始める tick と、終わる tick(ショートで止まったらその tick)。 */
    public long dischargeAt = NONE;
    public long dischargeEnd = NONE;
    /** ショートの隙が終わる tick(この前は技を出さない)。 */
    public long stunUntil = NONE;

    // ------------------------------------------------------------------ 技(10-19 から。始めるのは SkyBossAttacks、両側が同じ時刻でアニメを流し・当たりを出す)
    /** 羽ばたきの下降気流を始めた tick(扇の層の downdraft を 1 回)。 */
    public long downdraftAt = NONE;
    /** 転がりのビームの始まりと終わり(扇は brace、体は転がる)。 */
    public long beamAt = NONE;
    public long beamEnd = NONE;
    /** 雲を吐いた tick(体の層の spit_body・口の spit。{@link SkyBossActClock})と、その前のうねりの区切りの始まり。 */
    public long spitAt = NONE;
    public long undulateFrom;
    /** 触手の技を始めた tick と種類(0 = 打つ tentacle_lash、1 = 絡め取る tentacle_grab)、その前のたなびきの区切りの始まり。 */
    public long tentAt = NONE;
    public int tentKind;
    public long driftFrom;
    /** 蕾の一斉射: 芯が光り始めた tick・種類(0 = 腹の側の蕾、1 = 全部の蕾)・閉じ終わる tick。開く・撃つ・閉じるの区切りは {@link #budsPhase}。 */
    public long budsAt = NONE;
    public int budsKind;
    public long budsEnd = NONE;
    /** 背の腺が樹液を撃つ(sap_shot を 1 回)・腹の袋が種の爆弾を打ち上げる(seed_launch)・綿毛の袋が破れる(sac_burst)を始めた tick。 */
    public long sapAt = NONE;
    public long seedAt = NONE;
    public long sacAt = NONE;
    /**
     * 体の震え(雷の身震いとは別。{@link #shudderAt} は放電の溜めに数えるので使わない)を始めた tick と種類:
     * {@link #SHAKE_SEED} = 種の爆弾の反動(小さく短い)、{@link #SHAKE_STAGE} = 段階が変わる(体じゅう・扇も身震い)。
     */
    public long shakeAt = NONE;
    public int shakeKind;
    public static final int SHAKE_SEED = 0;
    public static final int SHAKE_STAGE = 1;
    /** ときどきの身震い(10-19 夕方。決定「ときどき扇の刃を前から後ろへ波打たせ、雨粒や枯れ葉を振り落とす」)。扇も身震い、体は小さく。 */
    public static final int SHAKE_CASUAL = 2;

    // ------------------------------------------------------------------ 一日のリズム(10-19 夕方。決めるのは SkyBossRhythm、両側が同じ時刻でアニメを流す)
    /** 休み方: {@link #REST_NONE}・{@link #REST_SLEEP}(夜の眠り)・{@link #REST_BASK}(晴れた朝の日光浴)と、それを始めた tick。 */
    public int restKind;
    public long restSince = NONE;
    public static final int REST_NONE = 0;
    public static final int REST_SLEEP = 1;
    public static final int REST_BASK = 2;
    /** 伸びとあくびを始めた tick(扇 stretch・体 stretch_body・口 yawn を 1 回。眠り・潜伏から覚めたとき)。 */
    public long stretchAt = NONE;
    /** 雲海からの飛び出しを始めた tick(扇 breach_fans・体 breach_body・口 breach_mouth・目 breach_eyes を 1 回。雲海の作業で使う)。 */
    public long breachAt = NONE;
    /** 潮吹きを始めた tick(口 spout・息 spout_breath・霧。雲海から上がって雲を抜けたとき)。 */
    public long spoutAt = NONE;
    /** 転がりのビームで転がる回数(始めたときの段階で決める。途中で段階が変わっても変えない)。 */
    public int beamTurns = 2;
    /** 溜まった日の光(0〜1。1 で転がりのビームを出せる。蕾の芯がうっすら光る)。 */
    public float light;

    /** 速さの目標(ブロック / tick)。速さはこれへ 1 tick に {@link SkyBossTuning#SPEED_EASE} ずつ近づく(前は区間の頭で一度に変わった)。 */
    public float speedTarget = (float) SkyBossTuning.AIRSPEED;
    /**
     * 体の軸のまわりの向き(度): 180 = 腹が上(渡り)、0 / 360 = 背が上(雷雲の中)。{@link #rollStart} から {@link SkyBossTuning#ROLL_TICKS} かけて
     * {@link #rollFrom} から {@link #rollTo} へ。決まった式なので途中から来たクライアントも同じ向き。体は頭から尾へ遅れて転がる({@link #rollAt})。
     */
    public float rollFrom = 180.0F;
    public float rollTo = 180.0F;
    public long rollStart = NONE;
    /** 扇の層を頭が決めるとき(-1 = 傾きで決める)。 */
    public int fanOverride = -1;
    /** 雷が避雷針に落ちる tick(身震い・体の震え。{@link #NONE} = 無い)。 */
    public long shudderAt = NONE;
    /** 頭を先に向ける(度、+ でヨーが増える向き = 進路の変わる先)の目標と、2 段のなめらかな追い方。決定「口の触手で空気を探る: 頭をそちらへ向け、少し遅れて体が向きを変える」。 */
    public float lookTarget;
    public float lookMid;
    public float look;
    public float plook;

    public final SkyBossTrail trail = new SkyBossTrail();

    /** 時刻 t の向き(度)。 */
    public double rollAt(double t) {
        if (rollStart == NONE || t <= rollStart) {
            return rollStart == NONE ? rollTo : rollFrom;
        }
        double u = (t - rollStart) / SkyBossTuning.ROLL_TICKS;
        if (u >= 1.0) {
            return rollTo;
        }
        return rollFrom + (rollTo - rollFrom) * u * u * (3.0 - 2.0 * u);
    }

    /** 時刻 t に転がっている途中か。 */
    public boolean rolling(double t) {
        return rollStart != NONE && t > rollStart && t < rollStart + SkyBossTuning.ROLL_TICKS + SkyBossTuning.ROLL_LAG_TICKS;
    }

    /** 時刻 t に背が上(転がり終えている)か。 */
    public boolean backUp(double t) {
        double r = rollAt(t);
        return Math.abs(Math.IEEEremainder(r, 360.0)) < 20.0;
    }

    /** {@code to} へ転がり始める(今の向きから)。 */
    public void startRoll(float to) {
        rollFrom = (float) rollAt(tick);
        rollTo = to;
        rollStart = tick;
    }

    /**
     * 向き {@code r}(度)から背が上(0 / 360 の倍数)へ転がるときの行き先。もう背が上ならそのまま。傾き {@code bank} が + なら + の向きへ、− なら − へ
     * (傾いていた側へ転がる。どちら向きでも同じ形)。10-19: 前は「今 ± 180」で、背が上のまま呼ぶと腹が上へ戻ってしまった。
     */
    public static float backUpRoll(double r, float bank) {
        double k = Math.round(r / 360.0) * 360.0;
        if (Math.abs(r - k) < 1.0) {
            return (float) k;
        }
        return (float) (bank >= 0.0F ? Math.ceil(r / 360.0) * 360.0 : Math.floor(r / 360.0) * 360.0);
    }

    /** 時刻 t に雷が飛んでいる(放電の 30 秒の中)か。 */
    public boolean discharging(double t) {
        return dischargeAt != NONE && t >= dischargeAt && t < dischargeEnd;
    }

    /** 時刻 t に放電の予告の中(転がって背を上へ・光が強まる)か。 */
    public boolean chargingUp(double t) {
        return chargeUpAt != NONE && t >= chargeUpAt && (dischargeAt == NONE || t < dischargeAt) && (dischargeEnd == NONE || t < dischargeEnd);
    }

    /** 通信・保存で、姿勢と区間の操作の外に送るもの(10-19)。並びは {@link #setExtra} と同じ。 */
    public float[] extraFloats() {
        return new float[]{speedTarget, rollFrom, rollTo, fanOverride, lookTarget, lookMid, look, charge, light};
    }

    public long[] extraLongs() {
        return new long[]{rollStart, shudderAt, chargeUpAt, dischargeAt, dischargeEnd, stunUntil,
                downdraftAt, beamAt, beamEnd, spitAt, undulateFrom, tentAt, tentKind, driftFrom,
                budsAt, budsKind, budsEnd, sapAt, seedAt, sacAt, shakeAt, shakeKind, beamTurns, restKind, restSince, stretchAt, breachAt, spoutAt};
    }

    /** {@link #extraFloats}・{@link #extraLongs} から戻す(前の版で短ければ、足りない分は今のまま)。 */
    public void setExtra(float[] fl, long[] lg) {
        if (fl.length >= 7) {
            speedTarget = fl[0];
            rollFrom = fl[1];
            rollTo = fl[2];
            fanOverride = (int) fl[3];
            lookTarget = fl[4];
            lookMid = fl[5];
            look = plook = fl[6];
        }
        if (lg.length >= 2) {
            rollStart = lg[0];
            shudderAt = lg[1];
        }
        if (fl.length >= 8) {
            charge = fl[7];
        }
        if (lg.length >= 6) {
            chargeUpAt = lg[2];
            dischargeAt = lg[3];
            dischargeEnd = lg[4];
            stunUntil = lg[5];
        }
        if (lg.length >= 14) {
            downdraftAt = lg[6];
            beamAt = lg[7];
            beamEnd = lg[8];
            spitAt = lg[9];
            undulateFrom = lg[10];
            tentAt = lg[11];
            tentKind = (int) lg[12];
            driftFrom = lg[13];
        }
        if (lg.length >= 20) {
            budsAt = lg[14];
            budsKind = (int) lg[15];
            budsEnd = lg[16];
            sapAt = lg[17];
            seedAt = lg[18];
            sacAt = lg[19];
        }
        if (lg.length >= 23) {
            shakeAt = lg[20];
            shakeKind = (int) lg[21];
            beamTurns = (int) lg[22];
        }
        if (fl.length >= 9) {
            light = fl[8];
        }
        if (lg.length >= 27) {
            restKind = (int) lg[23];
            restSince = lg[24];
            stretchAt = lg[25];
            breachAt = lg[26];
        }
        if (lg.length >= 28) {
            spoutAt = lg[27];
        }
    }

    /** 時刻 t に羽ばたきの下降気流の扇の動きの中か。 */
    public boolean downdrafting(double t) {
        return downdraftAt != NONE && t >= downdraftAt
                && t < downdraftAt + SkyBossFanClock.TRANSITION + SkyBossFanClock.length(SkyBossFanClock.DOWNDRAFT);
    }

    /** 蕾の一斉射の区切り: 0 = 無い、1 = 芯が光る(閉じたまま)、2 = 開く、3 = 撃つ、4 = 閉じる。 */
    public int budsPhase(double t) {
        if (budsAt == NONE || t < budsAt || t >= budsEnd) {
            return 0;
        }
        double open = budsAt + SkyBossTuning.AA_GLOW_TICKS;
        // 転がりのビーム(種類 2)は頭から尾へ順に開く分だけ長い
        double fire = open + (budsKind == SkyBossBeam.BUDS_KIND ? SkyBossTuning.BEAM_WAVE_TICKS : SkyBossTuning.BUDS_OPEN_TICKS);
        double close = budsEnd - SkyBossTuning.BUDS_CLOSE_TICKS;
        return t < open ? 1 : t < fire ? 2 : t < close ? 3 : 4;
    }

    /** 時刻 t に伸びとあくびの中か(扇の補間 + stretch)。 */
    public boolean stretching(double t) {
        return stretchAt != NONE && t >= stretchAt && t < stretchAt + SkyBossFanClock.TRANSITION + SkyBossFanClock.length(SkyBossFanClock.STRETCH);
    }

    /** 時刻 t に雲海からの飛び出しの中か(扇の補間 + breach_fans)。 */
    public boolean breaching(double t) {
        return breachAt != NONE && t >= breachAt && t < breachAt + SkyBossFanClock.TRANSITION + SkyBossFanClock.length(SkyBossFanClock.BREACH);
    }

    /** 時刻 t に転がりのビームの中か。 */
    public boolean beaming(double t) {
        return beamAt != NONE && t >= beamAt && t < beamEnd;
    }

    /** 1 tick 進める。 */
    public void step() {
        px = x;
        py = y;
        pz = z;
        yaw += yawRate;
        if (yaw > 180.0F) {
            yaw -= 360.0F;
        } else if (yaw < -180.0F) {
            yaw += 360.0F;
        }
        pitch = Math.max(-70.0F, Math.min(70.0F, pitch + pitchRate));
        // 速さ: 目標へ一定の加速で(区間の頭で一度に変えない)
        // 眠っている間はゆっくり(10-19 夕方。仮)
        float target = restKind == REST_SLEEP ? speedTarget * (float) SkyBossTuning.SLEEP_SPEED : speedTarget;
        float ds = target - speed;
        float acc = (float) SkyBossTuning.SPEED_EASE;
        speed += ds > acc ? acc : ds < -acc ? -acc : ds;
        // 10-19 夕方: 雲海からの飛び出しの間は、頭の道を決まった式で(機首の角度と速さ。両側が同じ式)
        if (breaching(tick)) {
            double tau = tick - breachAt;
            pitch = (float) SkyBossCloudSea.breachPitch(tau);
            pitchRate = 0.0F;
            speed = (float) (speedTarget * SkyBossCloudSea.breachSpeed(tau));
        }
        double yr = Math.toRadians(yaw);
        double pr = Math.toRadians(pitch);
        double c = StrictMath.cos(pr);
        x += -StrictMath.sin(yr) * c * speed;
        y += -StrictMath.sin(pr) * speed;
        z += StrictMath.cos(yr) * c * speed;
        tick++;
        trail.follow(x, y, z);
        // 傾き: 区間ごとの目標を 2 段でなめらかに追う
        pbank = bank;
        float a = 1.0F / SkyBossTuning.BANK_EASE_TICKS;
        bankMid += (bankTarget - bankMid) * a;
        bank += (bankMid - bank) * a;
        plook = look;
        float la = 1.0F / SkyBossTuning.LOOK_EASE_TICKS;
        lookMid += (lookTarget - lookMid) * la;
        look += (lookMid - look) * la;
        // 扇の層: 見えている傾きで決める(入るのは 20° より深いとき、戻るのは 15° より浅いとき)。傾きが − = 世界の右が下 = 右へ曲がっている。
        // 10-18 ユーザー「内側の手が漕いでしまっていた」: 世界の右へ曲がるときは turn_right(腹が上の向きから考えた逆の対応はゲームで内側が漕いだ)
        int mode = fanMode == FAN_SHIVER ? (fanOverride >= 0 ? fanOverride : FAN_CRUISE) : fanMode;
        float on = SkyBossTuning.TURN_ANIM_BANK;
        float off = SkyBossTuning.TURN_ANIM_BANK_OFF;
        if (fanOverride >= 0) {
            // 頭が決めた動き(ホバリング・漕ぎ続ける・転がるときの扇)。傾きの決め方は腹が上の向きのものなので、背が上の間は使わない
            mode = fanOverride;
        } else if (bank < -on) {
            mode = FAN_TURN_RIGHT;
        } else if (bank > on) {
            mode = FAN_TURN_LEFT;
        } else if (Math.abs(bank) < off) {
            mode = FAN_CRUISE;
        } else if (fanMode == FAN_SHIVER || fanMode >= FAN_HOVER) {
            mode = FAN_CRUISE;
        }
        // 休み方(眠り・日光浴)は渡りの扇の代わり(頭が決めた動き・旋回より強い。身震い・技・放電はこれより強い)
        if (fanOverride < 0 && restKind == REST_SLEEP) {
            mode = FAN_SLEEP;
        } else if (fanOverride < 0 && restKind == REST_BASK) {
            mode = FAN_BASK;
        } else if (fanMode == FAN_SLEEP || fanMode == FAN_BASK) {
            mode = fanOverride >= 0 ? fanOverride : FAN_CRUISE;
        }
        // 伸び(1 回)を流し終えるまで
        if (stretching(tick)) {
            mode = FAN_STRETCH;
        } else if (fanMode == FAN_STRETCH && mode == FAN_STRETCH) {
            mode = FAN_CRUISE;
        }
        // 雷が避雷針に落ちたら・段階が変わったら・ときどき、身震い(1 回)を流し終えるまで
        if (shudderAt != NONE && tick >= shudderAt
                && tick < shudderAt + SkyBossFanClock.TRANSITION + SkyBossFanClock.length(SkyBossFanClock.SHIVER)) {
            mode = FAN_SHIVER;
        }
        if ((shakeKind == SHAKE_STAGE || shakeKind == SHAKE_CASUAL) && shakeAt != NONE && tick >= shakeAt
                && tick < shakeAt + SkyBossFanClock.TRANSITION + SkyBossFanClock.length(SkyBossFanClock.SHIVER)) {
            mode = FAN_SHIVER;
        }
        // 10-19 の技: 下降気流は 1 回流し終えるまで、ビームの間は構え
        if (downdrafting(tick)) {
            mode = FAN_DOWNDRAFT;
        } else if (fanMode == FAN_DOWNDRAFT && mode == FAN_DOWNDRAFT) {
            mode = FAN_CRUISE;
        }
        if (beaming(tick)) {
            mode = FAN_BRACE;
        } else if (fanMode == FAN_BRACE && mode == FAN_BRACE) {
            mode = FAN_CRUISE;
        }
        // 雲海からの飛び出し(1 回。技より弱く、放電より弱い = 飛び出しは戦いの外だけ)
        if (breaching(tick)) {
            mode = FAN_BREACH;
        } else if (fanMode == FAN_BREACH && mode == FAN_BREACH) {
            mode = FAN_CRUISE;
        }
        // 放電の 30 秒は扇を広げてひきつる(10-19。身震いより強い)
        if (discharging(tick)) {
            mode = FAN_DISCHARGE;
        } else if (fanMode == FAN_DISCHARGE && mode == FAN_DISCHARGE) {
            mode = FAN_CRUISE;
        }
        if (mode != fanMode) {
            prevFanMode = fanMode;
            prevFanModeSince = fanModeSince;
            fanMode = mode;
            fanModeSince = tick;
        }
    }

    /** {@code now} まで進める(1 回に最大 {@code cap} tick。遅れすぎたらそこで打ち切って時刻だけ合わせる)。 */
    public void advanceTo(long now, int cap) {
        int n = 0;
        while (tick < now && n < cap) {
            step();
            n++;
        }
        if (tick < now) {
            tick = now;
        }
    }

    /** 頭の前の向き(単位ベクトル)。 */
    public double[] forward() {
        double yr = Math.toRadians(yaw);
        double pr = Math.toRadians(pitch);
        double c = StrictMath.cos(pr);
        return new double[]{-StrictMath.sin(yr) * c, -StrictMath.sin(pr), StrictMath.cos(yr) * c};
    }

    /** 頭の後ろへまっすぐ {@code length} ブロックの道を作り直す(呼び出した直後に、ずっとこの向きで飛んできたことにする)。 */
    public void straightTrail(double length) {
        trail.clear();
        double[] f = forward();
        int n = (int) Math.ceil(length / SkyBossTrail.STEP);
        for (int i = n; i >= 1; i--) {
            double d = i * SkyBossTrail.STEP;
            trail.push(x - f[0] * d, y - f[1] * d, z - f[2] * d);
        }
        trail.push(x, y, z);
        px = x;
        py = y;
        pz = z;
        bank = bankMid = pbank = 0.0F;
        fanMode = prevFanMode = 0;
        fanModeSince = prevFanModeSince = tick;
        rollFrom = rollTo = 180.0F;
        rollStart = NONE;
        fanOverride = -1;
        shudderAt = NONE;
        lookTarget = lookMid = look = plook = 0.0F;
        speedTarget = speed;
        charge = 0.0F;
        chargeUpAt = dischargeAt = dischargeEnd = stunUntil = NONE;
        downdraftAt = beamAt = beamEnd = spitAt = tentAt = NONE;
        budsAt = budsEnd = sapAt = seedAt = sacAt = shakeAt = NONE;
        restKind = REST_NONE;
        restSince = stretchAt = breachAt = spoutAt = NONE;
        budsKind = 0;
        shakeKind = 0;
        beamTurns = 2;
        light = 0.0F;
        undulateFrom = driftFrom = 0L;
        tentKind = 0;
    }
}
