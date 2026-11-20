package com.yayuy.overgrownvegetation.corruption.cave;

/**
 * 汚染された洞窟の飾り(柱1 ねじれた太い柱・嚢2 大きな水ぶくれ・縦穴の縄・コの字に宙に垂れる縄)の設計値({@code design/cave.json}、2026-11-20)。
 * <b>値はどれも【仮】</b>。形はユーザーが Fable の案から選んだ物(side-session-notes の cave-structures-look-report.md・模型 Cave.java)。
 * 出す所・密度は全体管理の仮の案(柱と嚢の塊はまばら = 1 チャンクに 0〜1、縄は縦穴とコの字の所だけ)。
 *
 * <p>Gson で直に読む(鍵が無ければこの既定値)。変えたら再起動か {@code /ov design reload} で効く。
 */
public final class CaveTable {

    public static final CaveTable DEFAULT = new CaveTable();

    /** 止める(false なら巡回しない。コマンドの {@code /ov perf cave here} は動く)。 */
    public boolean enabled = true;

    /** 巡回の間(tick)・プレイヤーのまわりのチャンクの半径・1 回に飾るチャンクの数。 */
    public int intervalTicks = 40;
    public int radiusChunks = 6;
    public int chunksPerPass = 1;

    /** 柱を出す割合(チャンクごと)と、柱を立てる洞窟の高さの幅。 */
    public double pillarChance = 0.5D;
    public int pillarMinHeight = 5;
    public int pillarMaxHeight = 14;

    /** 水ぶくれの塊を出す割合(チャンクごと)。天井に出すのは高さがこれ以上の所だけ。 */
    public double blisterChance = 0.5D;
    public int blisterCeilingMinHeight = 7;

    /** 縄の数の上限(チャンクごと)・縦穴とみる高さ・宙に垂れる縄の下の高さ・途中で終わる縄の割合。 */
    public int ropesPerChunk = 2;
    public int shaftMinHeight = 10;
    public int hangMinHeight = 6;
    public double partialRopeChance = 0.33D;

    /** 亀裂の巣・送り込む器官の芯からこのブロック以内には出さない(芯を埋めない)。 */
    public int coreClear = 8;
    /** 地表からこの深さより下だけ(地上には出さない)。 */
    public int surfaceKeep = 4;
    /** 置く列に使う 1 tick の時間(ミリ秒)。 */
    public double placeMillis = 2.0D;

    public Breath breath = new Breath();

    /**
     * 洞窟が胞子を吸い込む・吐き出す(2026-11-20。{@link CaveBreathing})。値はどれも【仮】。吸う・吐くの決まりは見た目の CaveBreath と同じ
     * (卓越風と口の外向きの内積。吹き込む = 吸う、吹き出す = 吐く、{@code flowThreshold} 未満は凪。縦穴は夜に吸い・昼に吐く)。
     */
    public static final class Breath {
        public boolean enabled = true;
        /** 計算の間(tick)。 */
        public int intervalTicks = 200;
        /** 凪とみる内積(見た目の caveBreath.flowThreshold と同じ値にする)。 */
        public double flowThreshold = 0.2D;
        /** 吹き込む口の染み込みの倍率(風がまっすぐ吹き込み、強さ 1 のとき)。今の染み込み(口の多さ × soakPerDay)に掛ける。 */
        public double inhaleGain = 4.0D;
        /** 縦穴が夜に吸う倍率。 */
        public double shaftInhaleGain = 2.0D;
        /** 吸った地下の浮遊のうち、1 回の計算で隣の洞窟のチャンク(見立てで洞窟・口と分かった所)へ渡す割合。 */
        public double inwardShare = 0.25D;
        /** 吐く量(1 日あたり。胞子が満ちた濃さ fullAt に対する割合。中の汚染の度合い 1・風の強さ 1 のとき)。 */
        public double exhalePerDay = 0.6D;
        /** 亀裂の前線が届いてあふれた口(入口 B)の吐く量の倍率(いちばん強い)。 */
        public double outletBoost = 3.0D;
        /** 地下の発芽を早める地脈の線からの距離(ブロック)と、線の真上でのしきい値の割り算。 */
        public double leyNearBlocks = 48.0D;
        public double leyGermMax = 2.5D;
        /** 口を探して 1 tick に測るチャンクの数。 */
        public int measuresPerTick = 1;
    }
}
