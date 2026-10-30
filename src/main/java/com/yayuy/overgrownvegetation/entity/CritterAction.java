package com.yayuy.overgrownvegetation.entity;

import software.bernie.geckolib.animation.RawAnimation;

import java.util.Locale;

/**
 * G2・G3 の「今している行動」のアニメ(2026-10-22、B: 食べる・休む・驚いて逃げ出す)。
 *
 * <p>行動(Goal)はまだ作らない(棲み処 → 食べる・狩る・休む → 群れ の段で作る)。今はアニメを先に用意し、
 * その再生の口だけを置く: エンティティが同期する値を 1 つ持ち({@link Holder})、アニメの切り替えはそれが {@link #NONE} 以外なら
 * その名前のアニメを流す。後の Goal はこの値を書くだけでよい。
 *
 * <p>確かめるときは {@code /data merge entity @e[type=…,limit=1] {OvAction:"eat"}}(名前は小文字)、戻すのは {@code "none"}。
 * その種にアニメが無い行動を入れると GeckoLib が警告を出して何も流れない。
 *
 * <p>種ごとに持っているアニメ(2026-10-22): 全種 eat・rest(魚・メガラクネ・ファランギオターブス・メガネウラ・ステノディクティアは種の表どおり)、
 * flee はプロトファスマ・スペラエオプレウラ・リトプレウラ・トカゲ 3 種、threat はアースロプレウラ、bask はペトロラコサウルス・イアンタサウルス、
 * hide はプロトファスマ・ヒロノムス、burst は魚 3 種、takeoff はステノディクティア、run はイアンタサウルス・コエルロサウラウスほか、attack はイアンタサウルス。
 */
public enum CritterAction {
    NONE(null, true),
    EAT("eat", true),
    REST("rest", true),
    FLEE("flee", false),
    THREAT("threat", true),
    BASK("bask", true),
    HIDE("hide", true),
    BURST("burst", false),
    TAKEOFF("takeoff", false),
    ATTACK("attack", true),
    RUN("run", true);

    private final String anim;
    private final RawAnimation raw;

    CritterAction(String anim, boolean loop) {
        this.anim = anim;
        this.raw = anim == null ? null : loop ? RawAnimation.begin().thenLoop(anim) : RawAnimation.begin().thenPlayAndHold(anim);
    }

    public String animationName() {
        return anim;
    }

    public static CritterAction byId(int id) {
        CritterAction[] v = values();
        return id >= 0 && id < v.length ? v[id] : NONE;
    }

    public static CritterAction byName(String name) {
        try {
            return valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return NONE;
        }
    }

    /** 流すアニメ。行動が無ければ null(いつもの待機・歩きへ)。 */
    public static RawAnimation animation(Holder holder) {
        return holder.getCritterAction().raw;
    }

    /** 行動の値を持つエンティティ(G2・G3 の土台のクラス)。NBT のキーは {@link #NBT}。 */
    public interface Holder {
        CritterAction getCritterAction();

        void setCritterAction(CritterAction action);
    }

    public static final String NBT = "OvAction";
}
