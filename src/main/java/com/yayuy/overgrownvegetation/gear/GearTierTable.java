package com.yayuy.overgrownvegetation.gear;

/**
 * 装備・鉱石の設計値({@code gear_tiers.json})。<b>値はどれも【仮】</b>。正本は Vault「強くなる道(素材・装備・武器・探索)(11-17)」の
 * 11-21 の節(鉱石は 4 種・装備のグレード)。
 *
 * <p>11-21 に 11-17〜18 の段 1〜3 の装備を消した。残るのは菌集鉱のでき方({@link #ore})と、グレード 1 の導晶({@link #conduit})。
 * Gson で直に読む(鍵が無ければこの既定値)。道具の硬さ・速さ・壊れにくさは登録の時に読むので、変えたら再起動。ほかは毎回読む。
 * 名前の {@code gear.json} は 10-30 に退避した前の装備の表が run/config に残っているので使わない。
 */
public final class GearTierTable {

    public static final GearTierTable DEFAULT = new GearTierTable();

    /** 菌集鉱(汚染された岩にできる。グレード 2 の菌鉄の元)。 */
    public Ore ore = new Ore();
    /** 導晶(グレード 1 の地脈技術の金属)の鉱石と道具。 */
    public Conduit conduit = new Conduit();

    public static final class Ore {
        /** 地脈の線からこの距離(ブロック)以内の、汚染された岩(過繁茂の石・深層岩)だけ。 */
        public double leyReach = 8.0D;
        /** 汚染された岩 1 つが鉱脈の芯になる確率。 */
        public double chance = 0.03D;
        /** 芯のまわりに広げる数(芯を含めた鉱脈の大きさ)。 */
        public int veinMin = 1;
        public int veinMax = 4;
    }

    /** 導晶(11-21 ユーザー決定: 電子の代わりに地脈の力で結びついた金属。熱・電気・地脈の力をよく通す)。 */
    public static final class Conduit {
        // ---- 導晶鉱の世界生成(地脈の線の近くの地下に最初からある) ----
        /** 鉱脈の芯が地脈の線からこの距離(ブロック)以内のときだけ置く。 */
        public double leyReach = 12.0D;
        /** 1 チャンクで鉱脈を試す回数(芯が線の近くに入らなければ置かない)。 */
        public int veinsPerChunk = 8;
        /** 1 つの鉱脈の大きさ(置く数の上限)。 */
        public int veinSize = 8;
        /** 高さの範囲(この中で一様)。0 より下は深層岩版になる。 */
        public int minY = -48;
        public int maxY = 48;

        // ---- 汚染に飲み込まれた導晶鉱(緑の版・表面に小さな根晶) ----
        /** 飲み込まれた導晶鉱 1 つにつき、空気に面した 1 面ごとに小さな根晶が生える確率。 */
        public double budChance = 0.3D;
        /** 1 つの鉱石に生える小さな根晶の上限。 */
        public int budMax = 2;

        // ---- 道具(つるはし・斧・シャベル・クワ。硬さはダイヤと同じ) ----
        public int durability = 1200;
        public float speed = 7.0F;
        /** 攻撃の上乗せ(ダイヤは 3)。 */
        public float attackBonus = 3.0F;
        public int enchantability = 14;

        // ---- 充電(地脈の力。電池と同じ口) ----
        /** 道具 1 本にためられる FE。 */
        public int capacity = 50000;
        /** 汲み上げ器・鉄塔の電池の口から 1 tick に受け取れる FE。 */
        public int maxReceive = 1000;
        /** 持ち物の画面で電池を右クリックで重ねたとき、1 回で移す上限。 */
        public int pourPerClick = 50000;
        /** 力が残っている間の掘る速さの倍率。 */
        public double chargedSpeedMultiplier = 1.5D;
        /** 力が残っている間、1 ブロック掘るごとに使う FE(足りなければ速くならない)。 */
        public int fePerBlock = 100;
    }
}
