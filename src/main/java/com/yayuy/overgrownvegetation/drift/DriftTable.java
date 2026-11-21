package com.yayuy.overgrownvegetation.drift;

/**
 * 漂流する擬態 Mob と腫れた島の設計値({@code design/drift.json}、2026-11-20)。<b>値はどれも【仮・Claude】</b>。
 * ユーザーが決めたのは仕組み(Mob にする・卓越風に流される・近くの島や岸に寄る・岸に根付いて汚染の出発点(芯なし)・
 * 島は育って腫れた島・寄生種を乗せて運ぶ・出すときと乗せる分に森がバイオマスを払う・筏は作らない)。数はどれも仮。
 * 正本は Vault「地形に合った汚染の生え方(11-19)」の 11-20 の節と side-session-notes の drift-mimic-impl-report.md。
 *
 * <p>Gson で直に読む(鍵が無ければこの既定値)。変えたら再起動で効く。
 */
public final class DriftTable {

    public static final DriftTable DEFAULT = new DriftTable();

    /** 止める(false なら司令塔の口から流さない・腫れた島は育たない。卵・コマンドで出した個体は動く)。 */
    public boolean enabled = true;

    public Drift drift = new Drift();

    /** 海の上の動き。 */
    public static final class Drift {
        /** 地上の風の強さ 1 のときの流れる速さ(ブロック/tick)。0.03 = 秒速 0.6(ふだんの午後の風で 100 ブロックを 3 分弱)。 */
        public double blocksPerWind = 0.03D;
        /** 風の強さの上限(嵐でも流れすぎない)。 */
        public double windCap = 2.0D;
        /** 岸へ寄るときの、水の中の根で漕ぐ速さ(ブロック/tick)。秒速 1.2。 */
        public double paddleSpeed = 0.06D;
        /** 速さが目標へ近づく割合(1 tick)。小さいほど重たく流れる。 */
        public double inertia = 0.06D;
        /** 岸を探す半径(ブロック)。 */
        public int seekRadius = 32;
        /** 岸を探す間隔(tick)。 */
        public int seekEvery = 40;
        /** 探す輪の間(ブロック)。輪ごとに 2 ブロックおきに点を見る。 */
        public int seekRingStep = 3;
        /** 流れ始めてからこの tick の間は、出てきた岸(汚染されたチャンク)のそばに戻らないよう汚染された陸を岸に選ばない。 */
        public int ignoreHomeTicks = 1200;
        /** 陸に打ち上げられて水に戻れないまま、この tick たったらその場に根付く。 */
        public int strandedRootTicks = 60;
        /**
         * 体に要る水の深さ(ブロック)。形 B 改 3/4 は水中に 約 10 の房を垂らす(ユーザー決定 11-20: 3 倍の大きさ)。
         * これより浅い所へは進まない(浅瀬の手前で止まる)。流す所・渡し船の出す所もこの深さの水だけ。
         */
        public int minDepth = 10;
        /** 体の半径(ブロック)。浅いかは体の真ん中からこの先 + 1 の水で見る(形の幅 約 15 の半分)。 */
        public double bodyRadius = 7.5D;
        /** 岸をつかむ鉤の房が届く距離(体の真ん中から岸の列まで。形の鉤を伸ばした長さ 約 18.9 から)。届けば浅瀬の手前からつかんで根付く。 */
        public double gripReach = 11.0D;
        /** 正体を見せる距離(ブロック): 観戦でないプレイヤー(植物側の者は除く)がこの中に来ると、胞子嚢の塊が膨らんで光り、鉤が上がる(11-22 全体管理２の了承・値は【仮】)。 */
        public double revealDistance = 24.0D;
        /** 一度も岸に着けずに海の上にいる上限(日)。過ぎたら沈んで消える。 */
        public double lifetimeDays = 4.0D;
    }

    public Root root = new Root();

    /** 岸に根付く。 */
    public static final class Root {
        /** 着いた岸のチャンクへ降らせる胞子の量(場の降り積もりの単位)。発芽のしきい値の目安 1 と比べて多めに。 */
        public double deposit = 1.5D;
        /** 根付いた萌芽体の株のほかに、陸の奥へ足す芽の点の数。 */
        public int extraPoints = 3;
        /** 芽の点を置く、岸から陸の奥への距離の上限(ブロック)。 */
        public int extraPointReach = 6;
        /** 島とみなす陸の面積の上限(列の数)。これより大きければ本土の岸。 */
        public int islandMaxColumns = 900;
        /** 島を測るときに見る、着いた所からの半径の上限(ブロック)。 */
        public int islandScanRadius = 40;
    }

    public Swell swell = new Swell();

    /** 腫れた島(Fable の案「島2」。肉が島を包み、水ぶくれの山 + 枯れ木の簾。芯なし)。 */
    public static final class Swell {
        /** 腫れた島へ育つのにかかる日数(置くマスの数をこの時間で配る)。 */
        public double growDays = 2.0D;
        /** 水面からの山の高さの上限(模型は 14)。 */
        public int maxHeight = 14;
        /** 1 回(20 tick)に置くマスの上限。 */
        public int blocksPerStep = 24;
        /** 模型の島の半径(ブロック)。島の大きさをこれで割った倍率で形を伸び縮みさせる。 */
        public double modelRadius = 8.8D;
        /** 倍率の下限と上限。 */
        public double minScale = 0.35D, maxScale = 1.4D;
        /** 同時に育つ島の上限(世界ごと)。 */
        public int maxIslands = 16;
    }

    public Launch launch = new Launch();

    /** 司令塔の口から流す(汚染された岸から、風下に陸がある所を選ぶ)。 */
    public static final class Launch {
        /** 流し始めるフェーズ。 */
        public int minPhase = 4;
        /** 流すかを考える間(日)。 */
        public double intervalDays = 0.5D;
        /** 考えた回ごとに流す確率。 */
        public double chance = 0.6D;
        /** 1 回に流す数。 */
        public int perLaunch = 1;
        /** 海の上にいる数の上限(読み込まれた個体 + 読み込みの外を流れている分)。 */
        public int maxAlive = 6;
        /** 汚染された岸を探す、プレイヤーからの半径(チャンク)。 */
        public int scanChunks = 8;
        /** 岸を探すくじの回数。 */
        public int samples = 48;
        /** 風下に陸を探す距離(ブロック)。読み込まれていない所に着いたら「先は分からない」として選んでよい。 */
        public int landRange = 192;
        /** 風下の水がこのブロック以上続く岸だけ(入り江の奥から流さない)。 */
        public int minOpenWater = 8;
    }

    public Ferry ferry = new Ferry();

    /** 寄生種を乗せて運ぶ(島の拠点への襲撃)。値段は司令塔の表(director.json の economy.driftCost・driftPassengerFactor)。 */
    public static final class Ferry {
        /** 1 体に乗せる数(ユーザー決定 11-20: 6 体まで)。 */
        public int seats = 6;
    }
}
