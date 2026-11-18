package com.yayuy.overgrownvegetation.ecosystem.life;

/**
 * G2・G3 の行動の段(2026-11-18)で流す「その場の動作」。サーバーが決めて同期し、クライアントは種のアニメを流す。
 * アニメの名前は既定で {@link #clip}。種に無いアニメは {@link EcoLife#ecoClip} が null を返し、歩き・待機のままになる。
 */
public enum EcoAct {
    NONE(null, true),
    /** 食べる(虫・葉・倒した獲物)。止まって流す。 */
    EAT("eat", true),
    /** 休む(起きている時間の外・満腹の後)。 */
    REST("rest", true),
    /** 驚く(びくっとして向きを変える。1 回)。flee の無い種は startle を頼んである(モデル制作)。 */
    STARTLE("startle", false),
    /** 隠れる・紛れる(プロトファスマ・ヒロノムス)。 */
    HIDE("hide", true),
    /** 威嚇(アースロプレウラ・サソリ)。 */
    THREAT("threat", true),
    /** 日向ぼっこ(トカゲ)。 */
    BASK("bask", true),
    /** 洞へ入る(頭から潜る。終わるとコードが消す)・洞から出る。モデル制作に頼んである(11-18)。 */
    ENTER_DEN("enter_den", false),
    EXIT_DEN("exit_den", false),
    /** 幼虫が繭を作る(モデル制作に頼んである)。 */
    COCOON("cocoon", false);

    public final String clip;
    /** くり返して流すか(false = 1 回)。 */
    public final boolean loop;

    EcoAct(String clip, boolean loop) {
        this.clip = clip;
        this.loop = loop;
    }

    public static EcoAct of(int ordinal) {
        EcoAct[] v = values();
        return ordinal >= 0 && ordinal < v.length ? v[ordinal] : NONE;
    }
}
