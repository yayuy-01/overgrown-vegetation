package com.yayuy.overgrownvegetation.corruption.fissure;

/**
 * 地下の小さな亀裂と亀裂の巣の設計値({@code design/fissure.json}、2026-11-20)。<b>値はどれも【仮】</b>。
 * ユーザーが決めたのは仕組み(司令塔が開く・F4 から・数はフェーズで増やさない・地脈の線の近くの洞窟・汲み上げ器の近くは開けない・
 * プレイヤーの位置を基準にしない・巣に芯・芯を壊すと根が止まり枯れる・口まで届くと地上へあふれる)。数はどれも仮。
 * 正本は Vault「地形に合った汚染の生え方(11-19)」の 11-20 の節と side-session-notes の fissure-underground-impl-report.md。
 *
 * <p>Gson で直に読む(鍵が無ければこの既定値)。変えたら再起動か {@code /ov design reload} で効く。
 */
public final class FissureTable {

    public static final FissureTable DEFAULT = new FissureTable();

    /** 止める(false なら司令塔は開かない。今ある亀裂の根も止まる。コマンドの開く・育てるは動く)。 */
    public boolean enabled = true;

    public Plan plan = new Plan();

    /** いつ・どこに開くか(司令塔)。 */
    public static final class Plan {
        /** 開き始めるフェーズ(ユーザー決定 F4)。 */
        public int minPhase = 4;
        /** 同時に生きている亀裂の数の上限。フェーズが進んでも変えない(ユーザー決定)。開く前(洞窟待ち)の物も数える。 */
        public int maxActive = 3;
        /** 司令塔が考える間(日)。 */
        public double intervalDays = 1.0D;
        /** 開く確率(考えた回ごと)。「いつ来るか分からない」ように。 */
        public double chance = 0.5D;
        /** 値段(森の予算)。器官の小さい版 30 と大きい版 120 の間。 */
        public double price = 40.0D;
        /** 森が知っている地下の範囲: 森の目印(地割れ・ハイヴノード・巨大樹・器官)からこのブロック以内の地脈の線の下。 */
        public int reachBlocks = 768;
        /** 地脈の線からこのブロック以内(場所の候補・開く洞窟のマスとも)。 */
        public int lineBlocks = 16;
        /** 過繁茂の台帳に載ったチャンクには開かない(そこは菌糸の染み下りが受け持つ)。 */
        public boolean avoidOvergrown = true;
        /** ほかの亀裂からこのブロック以上離す。 */
        public int spacingBlocks = 160;
        /** 地脈の汲み上げ器からこのブロック以内には開かない(地脈の力が人類側に吸われている)。 */
        public int pumpClearBlocks = 48;
        /** 場所のくじの回数(1 回考えるごと)。 */
        public int samples = 96;
        /** 洞窟が見つからない・汲み上げ器が近いときに場所を選び直す回数。超えたら取り消して払い戻す。 */
        public int maxRelocations = 4;
    }

    public Cave cave = new Cave();

    /** 開ける洞窟の条件。 */
    public static final class Cave {
        /** 地面からこのブロック以上下の洞窟の床だけ。 */
        public int minDepth = 12;
        /** 世界の底からこのブロック以上上。 */
        public int floorMargin = 6;
        /** 床のまわりの空気の数(つながった空気を数える)がこれ以上の洞窟だけ。 */
        public int minAir = 48;
        /** 空気を数える上限(重さ)。 */
        public int airScanCap = 400;
        /** 根の道が洞窟の口に届く床を先に選ぶ(開くときに 1 回だけ道を試す)。届く床が無ければ届かない床に開く。 */
        public boolean preferMouth = true;
        /** 口に届くか試す床の数(重さ)。 */
        public int mouthTries = 4;
    }

    public Nest nest = new Nest();

    /** 亀裂と巣の形(形そのものは {@link FissureLook})。 */
    public static final class Nest {
        /** 亀裂の深さ(洞窟の床から下へ)。 */
        public int depth = 5;
        /** 亀裂の長さ(床の上の切れ目)。 */
        public int length = 7;
        /** 開いてから根が伸び始めるまで(日)。 */
        public double rootDelayDays = 0.1D;
        /** 巣のまわりを汚染する半径(根が伸び始めたときに一度)。 */
        public int spreadRadius = 6;
    }

    public Roots roots = new Roots();

    /** 巣から伸びる根と、根から広がる地下の繁茂。 */
    public static final class Roots {
        /** 根が 1 マス伸びる時間(tick)。600 = 30 秒 → 100 マスで約 2.5 日。 */
        public int ticksPerCell = 600;
        /** 根の長さの上限(洞窟の空気をたどった道のマス)。 */
        public int maxLength = 160;
        /** 洞窟の口を探す空気の数の上限(重さ)。 */
        public int searchCells = 12000;
        /** 根の道を 1 tick に広げる数(重さ。続きは次の tick)。 */
        public int searchPerTick = 1500;
        /** 根の 1 マスのまわりを汚染する半径(空気に面したブロックだけ)。 */
        public int corruptRadius = 3;
        /** 汚染したマスに植物を生やす割合(菌糸の染み下りの seepPlantShare と同じ考え)。 */
        public double plantShare = 0.25D;
        /** 1 tick に置く根の数の上限(読み込んだときの追いつき)。 */
        public int cellsPerTick = 4;
        /** 枯らすとき 1 tick に替える数。 */
        public int witherPerTick = 32;
    }

    public Mouth mouth = new Mouth();

    /** 洞窟の口から地上へあふれる根。 */
    public static final class Mouth {
        /** 口から地面を這う根の本数。 */
        public int arms = 5;
        /** 這う根の長さの上限(マス)。 */
        public int armLength = 9;
        /** 這う根が 1 マス伸びる時間(tick)。 */
        public int ticksPerArmCell = 400;
        /** 口のまわりの地面の高さの差がこれを超える所へは這わない。 */
        public int maxStep = 4;
        /** 地上の汚染の点(芽の点 SporeSprouts)を足す間(tick)。 */
        public int ticksPerPoint = 2400;
        /** 地上の汚染の点の数の上限。 */
        public int maxPoints = 8;
    }

    public Wither wither = new Wither();

    /** 芯を壊したとき(器官と同じ扱いにするかは報告で聞く。既定は引かない)。 */
    public static final class Wither {
        /** 引くフェーズのポイント。 */
        public long points = 0L;
        /** 引く森の予算(上限に対する割合)。 */
        public double budgetCapShare = 0.0D;
    }

    public Omen omen = new Omen();

    /** 開くときの前触れと、場所の手がかり(全体管理の案【仮】・ユーザー未確認)。 */
    public static final class Omen {
        /** 地響きの音の大きさ(F1 の地割れの音を小さく。0 で鳴らさない)。 */
        public float rumbleVolume = 2.5F;
        /** 胞子の噴き上げを見せるプレイヤーとの距離。 */
        public int puffRange = 40;
        /** 生きている間、亀裂から胞子が漏れる間(tick。0 で出さない)。 */
        public int puffTicks = 30;
    }
}
