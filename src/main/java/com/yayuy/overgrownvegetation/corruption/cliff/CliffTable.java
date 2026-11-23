package com.yayuy.overgrownvegetation.corruption.cliff;

/**
 * 崖・急斜面の汚染(A1 太い縄の滝 + B2 嚢の房)の設計値({@code design/cliff.json}、2026-11-20)。<b>値はどれも【仮】</b>。
 * 形の正本は side-session-notes の cliff-corruption-look-report.md「2 回目」と Vault「地形に合った汚染の生え方(11-19)」の 11-20 の節。
 * ユーザーが決めたのは形(縄 2 マス幅・崖の上で 3 本に分かれて這う・4〜6 割で二股・先は垂れる根・急斜面は這う網・嚢の房に芯なし)と、
 * 「プレイヤーも登れるが植物側の Mob のほうが速い」「嚢は壊すと破裂して霧を出す」。数はどれも仮。
 *
 * <p>Gson で直に読む(鍵が無ければこの既定値)。変えたら再起動で効く。
 */
public final class CliffTable {

    public static final CliffTable DEFAULT = new CliffTable();

    /** 止める(false なら巡回しない。コマンドの {@code /ov perf cliff grow} は動く)。 */
    public boolean enabled = true;

    public Detect detect = new Detect();

    /** 崖・急斜面の見分け(地面の高さ = 木・葉・下草・自分の根を除いた高さ)。 */
    public static final class Detect {
        /** 隣の列との段差がこれ以上なら「崖の縁」。 */
        public int cliffDrop = 6;
        /** {@link #slopeRun} マス先までの下りがこれ以上なら「急斜面」(崖でなくても)。既定 6 / 3 = 平均 2 段 / 1 マス ≒ 63 度。 */
        public int slopeDrop = 6;
        public int slopeRun = 3;
        /** 面をなぞるとき、1 段下りるごとに外へ探す最大のマス数(これより遠い棚・地面で縄は止まる)。 */
        public int maxStepOut = 4;
    }

    public Rope rope = new Rope();

    /** A1 太い縄の滝。 */
    public static final class Rope {
        /** 縄の間隔(縁に沿ってこのマスおき。ずれは座標のハッシュで ±{@link #spacingJitter})。 */
        public int spacing = 5;
        public int spacingJitter = 1;
        /** 縄の場所に縄が実際に垂れる割合(11-20 ユーザー決定で 0.8 → 1.0・間隔 6 → 5、同じ日の夕方「少し多い」で 0.8 に戻した【仮】。縄の間の細い根もこの割合)。 */
        public double chance = 0.8D;
        /** 縄の長さの上限(段)。崖がこれより高いと途中で終わる。 */
        public int maxLength = 40;
        /** 左右のゆらぎ(マス)と、1 段あたりの位相(約 20 段で 1 往復 = 0.3)。 */
        public double sway = 1.7D;
        public double swayPhasePerStep = 0.3D;
        /** 二股になる高さ(縄の長さの割合)の下限と幅: 0.4 + 0.2 × 乱数 = 4〜6 割。 */
        public double forkFrom = 0.4D;
        public double forkSpread = 0.2D;
        /** 股の先が 1 マス離れる段の間。 */
        public int forkDriftEvery = 4;
        /** 短いほうの股の長さ(縄の長さの割合)。 */
        public double shortBranch = 0.82D;
        /** 崖の上で奥へ這う根の長さ(マス): min + 乱数 × spread(4〜7)。 */
        public int crawlMin = 4;
        public int crawlSpread = 4;
        /** 先に付ける垂れる根の段数。 */
        public int tipDangle = 2;
        /** 縄の間の細い根(1 マス幅)の長さ(崖の割合)の下限と幅: 0.25〜0.5。 */
        public double thinFrom = 0.25D;
        public double thinSpread = 0.25D;
        /** 縁の垂れる根の房: 縁の列のうちこの割合に付ける。 */
        public double edgeTuftChance = 0.35D;
        /** 急斜面の横の根: 縄の上からこの段おきに、次の縄まで横に根を渡して網にする(11-20 ユーザー決定)。0 で渡さない。 */
        public int slopeLinkEvery = 4;
        /** 床まで届く縄の割合(11-23 ユーザー決定「一部が床から 2 段以内まで」。チャンクの同じ向きの縁で最初の 1 本は必ず届き、残りはこの割合)【仮】。 */
        public double floorShare = 0.34D;
    }

    public Sacs sacs = new Sacs();

    /** B2 嚢の房(芯なし)。 */
    public static final class Sacs {
        /** 割れ目の間隔(縁に沿って。縄の間に来るよう、縄と半分ずらす)。 */
        public int spacing = 8;
        /** 割れ目の場所に実際に割れ目ができる割合。 */
        public double chance = 0.6D;
        /** 割れ目のうねり(マス)。 */
        public double wiggle = 1.3D;
        /** 嚢の房の間(段)。 */
        public int clusterEvery = 6;
        /** 割れ目の上と下に空ける段(縁のすぐ下と地面のすぐ上には付けない)。 */
        public int marginTop = 2;
        public int marginBottom = 1;
    }

    public Climb climb = new Climb();

    /**
     * 絡む根を登る速さ(バニラのはしご = 正味 約 0.118 ブロック / tick を 1 とした倍率)。
     * ユーザー決定: 植物側の Mob のほうが速い。プレイヤーははしご並みか少し遅い。
     */
    public static final class Climb {
        public double plantSide = 1.75D;
        public double player = 0.85D;
        /** 植物側でない Mob(バニラの動物・村人など)。 */
        public double other = 0.85D;
    }

    public Burst burst = new Burst();

    /** 崖の嚢を壊したときの霧(破裂)。 */
    public static final class Burst {
        /** 霧の残る長さ(tick)・脈の間(tick)・届く半径(ブロック)。 */
        public int durationTicks = 200;
        public int pulseTicks = 10;
        public double radius = 4.0D;
        /** 脈ごとに、霧の中の生き物が浴びる暴露量(息で浴びる = 防護マスクで防げる)。 */
        public double dosePerPulse = 1.5D;
        /** 霧全体で升の浮遊を上げる量(%。100% = spore_field.json の fullAt)。脈ごとに等分して放つ。 */
        public double airPercent = 15.0D;
        /** 霧全体で降り積もりに足す量(胞子の場の単位)。 */
        public double deposit = 0.05D;
        /** 房の隣の嚢も続けて割れるか(連鎖)。 */
        public boolean chain = false;
    }

    public Sweep sweep = new Sweep();

    /** 巡回(汚染された崖を探して生やす)。 */
    public static final class Sweep {
        /** 巡回の間(tick)。 */
        public int intervalTicks = 40;
        /** 1 回に調べるチャンクの上限。 */
        public int chunksPerPass = 2;
        /** プレイヤーのまわりの半径(チャンク)。 */
        public int radiusChunks = 6;
        /** 縄 = この段から(1 = 繁茂・2 = 過繁茂)。 */
        public int ropeStage = 1;
        /** 嚢 = この段から。 */
        public int sacStage = 2;
    }
}
