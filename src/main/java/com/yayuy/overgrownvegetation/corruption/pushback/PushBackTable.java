package com.yayuy.overgrownvegetation.corruption.pushback;

/**
 * 押し返す用の道具と方法の設計値({@code design/push_back.json}、2026-11-18 夜)。<b>値はどれも【仮】</b>。
 * 正本の決定は Vault「汚染を押し返す手段(浄化)」の末尾の節「【決定 11-18 夜 ユーザー】押し返す用の道具と方法」。
 * ユーザーが決めたのは: 過繁茂の割合が 3 割を切ったら台帳から外す(数は【仮】)・換気機の範囲で発芽しない・輪は帯(回数で消える)と杭(FE がある限り)。
 *
 * <p>Gson で直に読む(鍵が無ければこの既定値)。毎回読む(変えたら再起動で効く)。
 */
public final class PushBackTable {

    public static final PushBackTable DEFAULT = new PushBackTable();

    public Shrink shrink = new Shrink();
    public Vent vent = new Vent();

    /** 森の面積が減る(台帳から外す)。 */
    public static final class Shrink {
        /** 地面の汚染の割合がこれを切ったら台帳から外す(ユーザー決定 3 割【仮】)。 */
        public double threshold = 0.3D;
        /** 台帳に載ってからこれだけ(日)は外さない(発芽したての芽はまだ広がる途中)。 */
        public double graceDays = 1.0D;
        /** 同じチャンクを数え直す間(秒)。 */
        public int recheckSeconds = 10;
        /** 1 tick に数え直すチャンクの上限。 */
        public int chunksPerTick = 4;
        /** 分母に入れる列(水面下を除いた地面の列)がこれより少ないチャンクは外さない。 */
        public int minColumns = 32;
    }

    public Ring ring = new Ring();

    /** 侵入を止める物(灰と石灰の帯 = 線・地脈の杭 = 範囲の場。11-18 夜の直し)。 */
    public static final class Ring {
        /** 帯 1 マスが侵入を止められる回数(この回数で消える)。 */
        public int bandHits = 16;
        /** 帯 1 マスを数える間(秒。押し寄せが続いても、この間に 1 回まで)。 */
        public int bandHitCooldownSeconds = 60;
        /** 杭の中にためられる FE。 */
        public int stakeBuffer = 10000;
        /** 杭が立っているだけで使う FE/t。 */
        public int stakeFePerTick = 2;
        /** 止めるたびに、その杭が払う FE(杭ごとに 1 秒 1 回まで)。 */
        public int stakeFePerBlock = 20;
        /** 杭の範囲(水平の半径、ブロック)。中では発芽で始まる汚染も這ってくる汚染も起きない。 */
        public double stakeRadius = 12.0D;
        /** 守る高さ: 帯・杭の y から下へ。 */
        public int below = 8;
        /** 守る高さ: 帯・杭の y から上へ。 */
        public int above = 24;
    }

    public Fire fire = new Fire();

    /** 胞子焼きの筒の火(燻る灰・焼けた荒地)。 */
    public static final class Fire {
        /** 燻る灰と焼けた荒地(防火帯)が残る日数(11-18 夜 ユーザー「散布と同じように 1 日」。数は仮)。 */
        public double burntDays = 1.0D;
    }

    public Spray spray = new Spray();

    /** 散布薬(枯らし薬)。 */
    public static final class Spray {
        /** 割れた所から効く半径(ブロック)。 */
        public double radius = 4.0D;
        /** 枯れた土を見直すまでの日数(台帳に載っていれば汚染の地面へ戻る)。 */
        public double reviveDays = 1.0D;
    }

    /** 換気機の範囲で発芽しない。 */
    public static final class Vent {
        /** 動いている換気機の範囲(チャンク。中心どうしの距離)では胞子が発芽しない。換気機の吸い込みの範囲 {@code spore_field.json vent.radiusChunks} と同じ 2【仮】。 */
        public double noSproutRadiusChunks = 2.0D;
        /** 差してある 1 枚とは別の予備のフィルター(ホッパーで入れる。11-23 ユーザー決定 4)。 */
        public int spareFilters = 4;
        /** 詰まったフィルターを置く場所(下のホッパーで抜く。いっぱいなら詰まった物は差したまま止まる)【仮 4】。 */
        public int cloggedSlots = 4;
    }

    public Absent absent = new Absent();

    /**
     * 留守の間(チャンクが読み込まれていない間)も杭・換気機が守る(11-23 夕方 ユーザー決定)。保存データ {@link AbsentWards} に覚えた状態で範囲を守り、
     * FE は経った時間で減らす(読み込み直したときに実物へ精算)。値はどれも【仮】。
     */
    public static final class Absent {
        /** 受け取った FE を数える窓(秒)。線・隣の発電機から受け取った量 ÷ この窓 = 留守の間も入り続ける扱いの FE/t。 */
        public int supplyWindowSeconds = 60;
        /** 杭が止めるたびに払う FE の、留守の間の見積もり: 最後に読み込まれていた間の平均(FE/t)。読み込まれていた時間がこれより短いと前の見積もりのまま(秒)。 */
        public int stakePayMinSeconds = 30;
    }
}
