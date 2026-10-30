package com.yayuy.overgrownvegetation.client.skyboss;

import com.yayuy.overgrownvegetation.skyboss.SkyBossActClock;
import com.yayuy.overgrownvegetation.skyboss.SkyBossFanClock;
import com.yayuy.overgrownvegetation.skyboss.SkyBossParts;
import com.yayuy.overgrownvegetation.skyboss.SkyBossShapes;
import com.yayuy.overgrownvegetation.skyboss.SkyBossTuning;
import software.bernie.geckolib.animatable.GeoAnimatable;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * 空の巨大生物を描くための、エンティティではないクライアント側だけの GeckoLib アニマタブル(攻城戦のボスの {@code TitanicaImpostor} と同じ作り)。
 * 状態はすべて {@link ClientSkyBoss} から読む。
 *
 * <h2>層(コントローラ)</h2>
 * アニメの層は {@code tools/g5-models/mobs/SkyGlaucusAnims#layerOf} の分け方で、GeckoLib は骨の回転・位置・倍率ごとに、そのキーがあるコントローラの値を当てる
 * (後に登録したものが勝つ)。1 段目で流すのは body・fans・mouth・eyes・breath・tentacles の 6 つ(骨 946 本 × 動いているコントローラの数だけ毎フレーム手間が掛かるので、
 * 使わない層は登録しない。蕾・避雷針・腹の袋・背の腺・垂れ草は技を作るときに足して、使わない間は STOP を返す)。
 * <b>body は止めない</b>: 体の骨を毎フレームキーしているので、{@link SkyBossModel} が道をなぞる曲がりを足しても積み上がらない。
 *
 * <h2>時刻(10-18 夕方から)</h2>
 * body・fans・tentacles は当たり判定の形と合わせるため、ゲームの時刻で流す({@link SkyBossAnimController})。
 * body と tentacles はゲームの時刻 0 から数えたくり返し、fans は {@link SkyBossFanClock} の時刻表(3 回漕いで滑空・旋回)。
 * mouth・eyes・breath は当たり判定に効かないので GeckoLib のふつうの数え方。
 *
 * <h2>避雷針(10-18 夜)</h2>
 * 避雷針が壊れたら rod 層で rod_short(爆発して壊れた形で止まる)→ 生え直しが始まったら rod_regrow。時刻はどちらも壊れた・生え直し始めた tick から
 * ({@link SkyBossTuning#ROD_TRANSITION} の補間の後に頭から。体の反動 {@code SkyBossRecoil} も同じ時刻から数える)。壊れていない間は STOP(静止の形)。
 * 10-19 から放電の予告の間は rod_charge、放電中は rod_discharge(くり返し)。扇の層は放電中 discharge_fans({@link SkyBossFanClock#DISCHARGE})。
 */
public final class SkyBossImpostor implements GeoAnimatable {

    private static final RawAnimation UNDULATE = RawAnimation.begin().thenLoop("undulate");
    /** 扇の層: {@link SkyBossFanClock} の並び(glide・stroke・turn_left・turn_right)。 */
    private static final RawAnimation[] FANS = {
            RawAnimation.begin().thenLoop("glide"),
            RawAnimation.begin().thenLoop("stroke"),
            RawAnimation.begin().thenLoop("turn_left"),
            RawAnimation.begin().thenLoop("turn_right"),
            // 10-19: 雷雲の中で留まる・雷が落ちたときの身震い(1 回、終わりの形で止める = 時刻表の SHIVER と同じ)
            RawAnimation.begin().thenLoop("hover"),
            RawAnimation.begin().thenPlayAndHold("shiver"),
            // 10-19: 放電中(扇を広げ、電気でひきつるように震える。くり返し)
            RawAnimation.begin().thenLoop("discharge_fans"),
            // 10-19 の技: 羽ばたきの下降気流(1 回、終わりの形で止める)・転がりのビームの構え(くり返し)
            RawAnimation.begin().thenPlayAndHold("downdraft"),
            RawAnimation.begin().thenLoop("brace"),
            // 10-19 夕方の生き物らしさ: 眠り・日光浴(くり返し)・伸び(1 回)・すぼめ(くり返し)・雲海からの飛び出し(1 回)
            RawAnimation.begin().thenLoop("sleep"),
            RawAnimation.begin().thenLoop("bask"),
            RawAnimation.begin().thenPlayAndHold("stretch"),
            RawAnimation.begin().thenLoop("tuck"),
            RawAnimation.begin().thenPlayAndHold("breach_fans")};
    /** 体の層(10-19 の技から {@link SkyBossActClock} の並び): うねり・雲を吐く体の動き(1 回)。 */
    private static final RawAnimation[] BODY = {
            RawAnimation.begin().thenLoop("undulate"),
            RawAnimation.begin().thenPlayAndHold("spit_body"),
            RawAnimation.begin().thenPlayAndHold("stretch_body"),
            RawAnimation.begin().thenPlayAndHold("breach_body")};
    /** 腹の触手の層: たなびき・打つ・絡め取る(技は 1 回)。 */
    private static final RawAnimation[] TENTS = {
            RawAnimation.begin().thenLoop("drift"),
            RawAnimation.begin().thenPlayAndHold("tentacle_lash"),
            RawAnimation.begin().thenPlayAndHold("tentacle_grab")};
    /** 蕾: 開いてから撃ち続ける(腹の側の蕾・全部の蕾)・閉じる。背の腺: 樹液を撃つ(1 回)。10-19 の技。 */
    private static final RawAnimation BUDS_SOME = RawAnimation.begin().thenPlay("buds_some_open").thenLoop("buds_some_fire");
    private static final RawAnimation BUDS_ALL = RawAnimation.begin().thenPlay("buds_all_open").thenLoop("buds_all_fire");
    private static final RawAnimation BUDS_CLOSE = RawAnimation.begin().thenPlay("buds_close");
    private static final RawAnimation SAP = RawAnimation.begin().thenPlay("sap_shot");
    /** 転がりのビーム: 頭から尾へ順に開いて、開いたまま(ビームの間)。腹の袋: 種の爆弾を打ち上げる・綿毛の袋が破れる(1 回)。 */
    private static final RawAnimation BUDS_WAVE = RawAnimation.begin().thenPlayAndHold("buds_wave_open");
    private static final RawAnimation SEED = RawAnimation.begin().thenPlay("seed_launch");
    private static final RawAnimation SAC = RawAnimation.begin().thenPlay("sac_burst");
    /** 腹の袋の層のアニメの長さ(tick。道具の seed_launch 3 秒・sac_burst 5 秒 × 時間の倍率)。 */
    private static final double SEED_ANIM_TICKS = 3.0 * SkyBossTuning.ANIM_SLOW * 20.0;
    private static final double SAC_ANIM_TICKS = 5.0 * SkyBossTuning.ANIM_SLOW * 20.0;
    /** 口: 雲を吐く(1 回)。 */
    private static final RawAnimation SPIT = RawAnimation.begin().thenPlay("spit");
    private static final RawAnimation PROBE = RawAnimation.begin().thenLoop("probe");
    /** 口: あくび(伸びと同時に 1 回)。目: 眠りの間はまぶたを閉じたまま。10-19 夕方。 */
    private static final RawAnimation YAWN = RawAnimation.begin().thenPlay("yawn");
    private static final RawAnimation SLEEP_EYES = RawAnimation.begin().thenLoop("sleep_eyes");
    /** 雲海(10-19 夕方): 飛び出しの口・目(1 回)、潮吹きの口と息(1 回)。 */
    private static final RawAnimation BREACH_MOUTH = RawAnimation.begin().thenPlayAndHold("breach_mouth");
    private static final RawAnimation BREACH_EYES = RawAnimation.begin().thenPlayAndHold("breach_eyes");
    private static final RawAnimation SPOUT = RawAnimation.begin().thenPlay("spout");
    private static final RawAnimation SPOUT_BREATH = RawAnimation.begin().thenPlay("spout_breath");
    /** 潮吹きの口・息の長さ(tick。道具の spout 4.5 秒・spout_breath 9 秒 × 時間の倍率)と、飛び出しの中で息を始める所(2.7 秒 × 倍率 + 扇の補間)。 */
    private static final double SPOUT_TICKS = 4.5 * SkyBossTuning.ANIM_SLOW * 20.0;
    private static final double SPOUT_BREATH_TICKS = 9.0 * SkyBossTuning.ANIM_SLOW * 20.0;
    private static final double BREACH_BREATH_AT = 2.7 * 20.0 + 40.0;
    private static final RawAnimation BLINK = RawAnimation.begin().thenPlay("blink");
    /** まばたきの間隔(tick)。目がプレイヤーを追う・瞳がばらばらに動くのは次の段(コード)。 */
    private static final int BLINK_TICKS = 240;
    private static final RawAnimation BREATHE = RawAnimation.begin().thenLoop("breathe");
    /** 腹の触手がたなびく(10-18 ユーザー「移動している間ずっと腹の触手がぴんと張っているのはおかしい」)。触手で打つ・絡め取るは技を作るときにこの層で切り替える。 */
    private static final RawAnimation DRIFT = RawAnimation.begin().thenLoop("drift");
    private static final RawAnimation ROD_SHORT = RawAnimation.begin().thenPlayAndHold("rod_short");
    private static final RawAnimation ROD_REGROW = RawAnimation.begin().thenPlay("rod_regrow");
    /** 10-19: 放電の予告(鞘が息をするように開く)と放電中(鞘が大きく開き、尖塔が震える)。どちらもくり返し。当たり判定に効かないので GeckoLib の数え方。 */
    private static final RawAnimation ROD_CHARGE = RawAnimation.begin().thenLoop("rod_charge");
    private static final RawAnimation ROD_DISCHARGE = RawAnimation.begin().thenLoop("rod_discharge");

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private final double[] seg = new double[2];
    /** 扇の層で今流しているアニメと、その区切りの始まり。 */
    private int fanAnim = -1;
    private double fanStart = Double.NaN;
    private long blinkCycle = -1;
    /** 避雷針の層で今流しているアニメの始まりの tick(壊れ直したら頭から)。 */
    private long rodKey = SkyBossParts.NONE;

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new SkyBossAnimController(this, "body", SkyBossTuning.BODY_TRANSITION, this::body, out -> actTiming(out, true)));
        controllers.add(new SkyBossAnimController(this, "fans", SkyBossFanClock.TRANSITION, this::fans, this::fanTiming));
        controllers.add(new AnimationController<>(this, "mouth", 20, this::mouth));
        controllers.add(new AnimationController<>(this, "eyes", 0, this::eyes));
        controllers.add(new AnimationController<>(this, "breath", 20, this::breath));
        controllers.add(new SkyBossAnimController(this, "tentacles", SkyBossTuning.TENT_TRANSITION, this::tentacles, out -> actTiming(out, false)));
        controllers.add(new SkyBossAnimController(this, "rod", SkyBossTuning.ROD_TRANSITION, this::rod, this::rodTiming));
        // 10-19 の技: 蕾・背の腺(当たり判定に効かないので GeckoLib の数え方)
        controllers.add(new AnimationController<>(this, "buds", 4, this::buds));
        controllers.add(new AnimationController<>(this, "glands", 4, this::glands));
        controllers.add(new AnimationController<>(this, "belly", 4, this::belly));
    }

    /** 体(true)・触手(false)のくり返し: ゲームの時刻 0 から。 */
    private final double[] act = new double[4];
    /** 体・触手の層で今流しているアニメと区切りの始まり(同じアニメのまま区切りが変わったら頭から)。 */
    private int bodyAnim = -1;
    private double bodyStart = Double.NaN;
    private int tentAnim = -1;
    private double tentStart = Double.NaN;
    private long mouthKey = Long.MIN_VALUE;
    private long eyesKey = Long.MIN_VALUE;
    private long breathKey = Long.MIN_VALUE;

    /** 息: ふだんは呼吸、潮吹き・飛び出しの潮吹きの間は spout_breath を 1 回(10-19 夕方)。 */
    private PlayState breath(AnimationState<SkyBossImpostor> state) {
        com.yayuy.overgrownvegetation.skyboss.SkyBossFlight f = ClientSkyBoss.flight();
        double t = ClientSkyBoss.animClock();
        long none = com.yayuy.overgrownvegetation.skyboss.SkyBossFlight.NONE;
        long start = none;
        if (f.spoutAt != none && t >= f.spoutAt && t < f.spoutAt + SPOUT_BREATH_TICKS) {
            start = f.spoutAt;
        } else if (f.breachAt != none && t >= f.breachAt + BREACH_BREATH_AT && t < f.breachAt + BREACH_BREATH_AT + SPOUT_BREATH_TICKS) {
            start = f.breachAt;
        }
        if (start == none) {
            return state.setAndContinue(BREATHE);
        }
        if (start != breathKey) {
            state.getController().forceAnimationReset();
            breathKey = start;
        }
        return state.setAndContinue(SPOUT_BREATH);
    }

    /** 体の層(10-19 から): うねり ⇄ 雲を吐く体の動き。 */
    private PlayState body(AnimationState<SkyBossImpostor> state) {
        SkyBossActClock.body(ClientSkyBoss.flight(), ClientSkyBoss.animClock(), act);
        int anim = (int) act[0];
        if (anim == bodyAnim && act[1] != bodyStart && bodyAnim >= 0) {
            state.getController().forceAnimationReset();
        }
        bodyAnim = anim;
        bodyStart = act[1];
        return state.setAndContinue(BODY[anim]);
    }

    /** 腹の触手の層(10-19 から): たなびき ⇄ 打つ・絡め取る。 */
    private PlayState tentacles(AnimationState<SkyBossImpostor> state) {
        SkyBossActClock.tentacles(ClientSkyBoss.flight(), ClientSkyBoss.animClock(), act);
        int anim = (int) act[0];
        if (anim == tentAnim && act[1] != tentStart && tentAnim >= 0) {
            state.getController().forceAnimationReset();
        }
        tentAnim = anim;
        tentStart = act[1];
        return state.setAndContinue(TENTS[anim]);
    }

    /** 体・触手の区切りの {始まり, 補間, くり返しの長さ(技は 0)}。 */
    private boolean actTiming(double[] out, boolean body) {
        if (SkyBossShapes.get() == null) {
            return false;
        }
        double t = ClientSkyBoss.animClock();
        if (body) {
            SkyBossActClock.body(ClientSkyBoss.flight(), t, act);
        } else {
            SkyBossActClock.tentacles(ClientSkyBoss.flight(), t, act);
        }
        int anim = (int) act[0];
        out[0] = act[1];
        out[1] = body ? SkyBossTuning.BODY_TRANSITION : SkyBossTuning.TENT_TRANSITION;
        boolean loop = anim == 0;
        out[2] = loop ? (body ? SkyBossActClock.bodyLength(anim) : SkyBossActClock.tentLength(anim)) : 0.0;
        return true;
    }

    private long budsKey = Long.MIN_VALUE;
    private int budsShown;
    private long sapKey = Long.MIN_VALUE;

    /** 蕾: 一斉射の区切りで開く → 撃つ → 閉じる(芯が光る間は閉じたまま。光は光る層)。 */
    private PlayState buds(AnimationState<SkyBossImpostor> state) {
        com.yayuy.overgrownvegetation.skyboss.SkyBossFlight f = ClientSkyBoss.flight();
        int phase = f.budsPhase(ClientSkyBoss.animClock());
        int show = phase == 2 || phase == 3 ? 1 : phase == 4 ? 2 : 0;
        if (show == 0) {
            budsShown = 0;
            return PlayState.STOP;
        }
        if (f.budsAt != budsKey || show != budsShown) {
            state.getController().forceAnimationReset();
            budsKey = f.budsAt;
            budsShown = show;
        }
        return state.setAndContinue(show == 2 ? BUDS_CLOSE : f.budsKind == com.yayuy.overgrownvegetation.skyboss.SkyBossBeam.BUDS_KIND ? BUDS_WAVE
                : f.budsKind == 1 ? BUDS_ALL : BUDS_SOME);
    }

    /** 背の腺: 樹液を撃つ間だけ sap_shot。 */
    private PlayState glands(AnimationState<SkyBossImpostor> state) {
        com.yayuy.overgrownvegetation.skyboss.SkyBossFlight f = ClientSkyBoss.flight();
        double t = ClientSkyBoss.animClock();
        if (f.sapAt == com.yayuy.overgrownvegetation.skyboss.SkyBossFlight.NONE || t < f.sapAt || t > f.sapAt + SkyBossTuning.SAP_ANIM_TICKS) {
            return PlayState.STOP;
        }
        if (f.sapAt != sapKey) {
            state.getController().forceAnimationReset();
            sapKey = f.sapAt;
        }
        return state.setAndContinue(SAP);
    }

    private long bellyKey = Long.MIN_VALUE;

    /** 腹の袋: 種の爆弾を打ち上げる間だけ seed_launch、綿毛の袋が破れる間だけ sac_burst(新しく始まった方)。 */
    private PlayState belly(AnimationState<SkyBossImpostor> state) {
        com.yayuy.overgrownvegetation.skyboss.SkyBossFlight f = ClientSkyBoss.flight();
        double t = ClientSkyBoss.animClock();
        long none = com.yayuy.overgrownvegetation.skyboss.SkyBossFlight.NONE;
        boolean seed = f.seedAt != none && t >= f.seedAt && t <= f.seedAt + SEED_ANIM_TICKS;
        boolean sac = f.sacAt != none && t >= f.sacAt && t <= f.sacAt + SAC_ANIM_TICKS;
        if (seed && sac) {
            seed = f.seedAt > f.sacAt;
            sac = !seed;
        }
        if (!seed && !sac) {
            return PlayState.STOP;
        }
        long key = seed ? f.seedAt : f.sacAt;
        if (key != bellyKey) {
            state.getController().forceAnimationReset();
            bellyKey = key;
        }
        return state.setAndContinue(seed ? SEED : SAC);
    }

    /** 口: ふだんは空気を探り、雲を吐くときは spit を 1 回(当たり判定に効かないので GeckoLib の数え方)。 */
    private PlayState mouth(AnimationState<SkyBossImpostor> state) {
        com.yayuy.overgrownvegetation.skyboss.SkyBossFlight f = ClientSkyBoss.flight();
        if (SkyBossActClock.spitting(f, ClientSkyBoss.animClock())) {
            if (f.spitAt != mouthKey) {
                state.getController().forceAnimationReset();
                mouthKey = f.spitAt;
            }
            return state.setAndContinue(SPIT);
        }
        // 雲海からの飛び出し・潮吹き(10-19 夕方)
        double ta = ClientSkyBoss.animClock();
        if (f.breaching(ta)) {
            if (f.breachAt != mouthKey) {
                state.getController().forceAnimationReset();
                mouthKey = f.breachAt;
            }
            return state.setAndContinue(BREACH_MOUTH);
        }
        if (f.spoutAt != com.yayuy.overgrownvegetation.skyboss.SkyBossFlight.NONE && ta >= f.spoutAt && ta < f.spoutAt + SPOUT_TICKS) {
            if (f.spoutAt != mouthKey) {
                state.getController().forceAnimationReset();
                mouthKey = f.spoutAt;
            }
            return state.setAndContinue(SPOUT);
        }
        // 伸びとあくび(10-19 夕方。体・扇と同じ tick から)
        if (f.stretching(ClientSkyBoss.animClock())) {
            if (f.stretchAt != mouthKey) {
                state.getController().forceAnimationReset();
                mouthKey = f.stretchAt;
            }
            return state.setAndContinue(YAWN);
        }
        return state.setAndContinue(PROBE);
    }

    private static boolean loop(double[] out, boolean body) {
        SkyBossShapes s = SkyBossShapes.get();
        if (s == null) {
            return false;
        }
        out[0] = 0.0;
        out[1] = 0.0;
        out[2] = (body ? s.undulateLength : s.driftLength) * 20.0;
        return true;
    }

    private boolean fanTiming(double[] out) {
        if (SkyBossShapes.get() == null) {
            return false;
        }
        SkyBossFanClock.segment(ClientSkyBoss.flight(), ClientSkyBoss.animClock(), seg);
        out[0] = seg[1];
        out[1] = SkyBossFanClock.TRANSITION;
        // 身震いは 1 回だけ(くり返さない = 始まりからの時刻のまま)
        out[2] = SkyBossFanClock.once((int) seg[0]) ? 0.0 : SkyBossFanClock.length((int) seg[0]);
        return true;
    }

    private PlayState fans(AnimationState<SkyBossImpostor> state) {
        SkyBossFanClock.segment(ClientSkyBoss.flight(), ClientSkyBoss.animClock(), seg);
        int anim = (int) seg[0];
        double start = seg[1];
        // 同じアニメのまま区切りが変わった(1 回りの頭から漕ぎ直す)ときは頭から(前の形から補間して)
        if (anim == fanAnim && start != fanStart && fanAnim >= 0) {
            state.getController().forceAnimationReset();
        }
        fanAnim = anim;
        fanStart = start;
        return state.setAndContinue(FANS[anim]);
    }

    /** 避雷針: 壊れたら rod_short、生え直しが始まったら rod_regrow、放電中は rod_discharge、予告の間は rod_charge、ほかは止める(10-19)。 */
    private PlayState rod(AnimationState<SkyBossImpostor> state) {
        SkyBossParts p = ClientSkyBoss.parts();
        if (p.rodBrokenAt == SkyBossParts.NONE) {
            rodKey = SkyBossParts.NONE;
            com.yayuy.overgrownvegetation.skyboss.SkyBossFlight f = ClientSkyBoss.flight();
            double t = ClientSkyBoss.animClock();
            if (f.discharging(t)) {
                return state.setAndContinue(ROD_DISCHARGE);
            }
            if (f.chargingUp(t)) {
                return state.setAndContinue(ROD_CHARGE);
            }
            return PlayState.STOP;
        }
        boolean regrow = rodRegrowing(p);
        long key = regrow ? p.rodRegrowAt : p.rodBrokenAt;
        // GeckoLib は止めた後も前のアニメの名前を覚えているので、壊れ直したとき同じ rod_short を頭から流すには明示して戻す
        if (key != rodKey) {
            state.getController().forceAnimationReset();
            rodKey = key;
        }
        return state.setAndContinue(regrow ? ROD_REGROW : ROD_SHORT);
    }

    private boolean rodTiming(double[] out) {
        SkyBossParts p = ClientSkyBoss.parts();
        if (p.rodBrokenAt == SkyBossParts.NONE) {
            return false;
        }
        out[0] = rodRegrowing(p) ? p.rodRegrowAt : p.rodBrokenAt;
        out[1] = SkyBossTuning.ROD_TRANSITION;
        // 1 回だけ(くり返さない): アニメの時刻 = 始まりからの tick
        out[2] = 0.0;
        return true;
    }

    private static boolean rodRegrowing(SkyBossParts p) {
        return p.rodRegrowAt != SkyBossParts.NONE && ClientSkyBoss.animClock() >= p.rodRegrowAt;
    }

    /** ときどきまばたき(1 回流して開いた形で止まる。回りが変わったら頭から)。 */
    private PlayState eyes(AnimationState<SkyBossImpostor> state) {
        // 雲海からの飛び出し(10-19 夕方): 雲の中は細め、抜けたら見開いて見回す
        com.yayuy.overgrownvegetation.skyboss.SkyBossFlight fe = ClientSkyBoss.flight();
        if (fe.breaching(ClientSkyBoss.animClock())) {
            if (fe.breachAt != eyesKey) {
                state.getController().forceAnimationReset();
                eyesKey = fe.breachAt;
            }
            blinkCycle = -1;
            return state.setAndContinue(BREACH_EYES);
        }
        // 眠っている間はまぶたを閉じたまま(10-19 夕方)
        if (ClientSkyBoss.flight().restKind == com.yayuy.overgrownvegetation.skyboss.SkyBossFlight.REST_SLEEP) {
            blinkCycle = -1;
            return state.setAndContinue(SLEEP_EYES);
        }
        long cycle = (long) Math.floor(ClientSkyBoss.animClock() / BLINK_TICKS);
        if (cycle != blinkCycle) {
            if (blinkCycle >= 0) {
                state.getController().forceAnimationReset();
            }
            blinkCycle = cycle;
        }
        return state.setAndContinue(BLINK);
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }

    @Override
    public double getTick(Object object) {
        return ClientSkyBoss.animClock();
    }
}
