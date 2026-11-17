package com.yayuy.overgrownvegetation.gear;

/**
 * 強くなる道 段 1・段 2 の物の設計値({@code gear_tiers.json}、2026-11-17)。<b>値はどれも【仮】</b>(ユーザーが決めたのは役目だけ:
 * 段 1 = 胞子に強い軽装・鉄より低い防御・山刀は蔦や下草を切り開く・胞子弾の弓は森の敵に効く。段 2 = ダイヤ並みの防御で胞子への強さは段 1 より下・
 * 両手剣は前の半円を薙ぐ・クロスボウは一列を貫き装填が遅い)。正本は Vault「強くなる道(素材・装備・武器・探索)(11-17)」の決定 17・18。
 *
 * <p>Gson で直に読む(鍵が無ければこの既定値)。防具の防御・耐久・武器の攻撃は登録の時に読むので、変えたら再起動。暴露・刈り・薙ぎ・貫き・鉱石は毎回読む。
 * 名前の {@code gear.json} は 10-30 に退避した前の装備の表が run/config に残っているので使わない。
 */
public final class GearTierTable {

    public static final GearTierTable DEFAULT = new GearTierTable();

    /** 段 1 の遠征服(頭巾・上衣・脚衣・足袋)。 */
    public Armor suit = Armor.of(new int[]{2, 4, 3, 1}, 12, 0.0F, 0.0F, 15, 0.125D, 0.6D);
    /** 段 2 の札鎧(兜・胴・草摺・脛当て)。 */
    public Armor plate = Armor.of(new int[]{3, 8, 6, 3}, 30, 2.0F, 0.0F, 12, 0.05D, 0.85D);

    public Machete machete = new Machete();
    public SporeShot sporeShot = new SporeShot();
    public Greatsword greatsword = new Greatsword();
    public Crossbow crossbow = new Crossbow();
    public FiberGrass fiberGrass = new FiberGrass();
    public Ore ore = new Ore();

    /** 防具 1 そろい。{@code defense} は頭・胴・脚・足の順。 */
    public static final class Armor {
        public int[] defense = {1, 1, 1, 1};
        /** 耐久の倍率(バニラの革 5・鉄 15・ダイヤ 33)。 */
        public int durability = 15;
        public float toughness = 0.0F;
        public float knockbackResistance = 0.0F;
        public int enchantability = 10;
        /** 1 部位ごとに暴露のたまり方を下げる割合(4 部位で足す。0.125 なら 4 部位で半分)。マスクが効いている間は元から 0。 */
        public double exposureCutPerPiece = 0.0D;
        /** 頭(フィルターを使う)のフィルターの減りの倍率(防護マスクは 1)。 */
        public double filterWear = 1.0D;

        static Armor of(int[] defense, int durability, float toughness, float kb, int ench, double cut, double filterWear) {
            Armor a = new Armor();
            a.defense = defense;
            a.durability = durability;
            a.toughness = toughness;
            a.knockbackResistance = kb;
            a.enchantability = ench;
            a.exposureCutPerPiece = cut;
            a.filterWear = filterWear;
            return a;
        }

        int defense(int index) {
            return defense != null && index < defense.length ? defense[index] : 1;
        }
    }

    /** 山刀。攻撃は剣より少し弱く速い。 */
    public static final class Machete {
        /** 攻撃(素手 1 を含めた合計)と 1 秒の振り。 */
        public float attackDamage = 5.0F;
        public float attackSpeed = 1.8F;
        public int durability = 300;
        /** タグ {@code machete_clearable} の植物を刈る速さ(剣が蜘蛛の巣を切る 15 と同じ)。 */
        public float clearSpeed = 15.0F;
        /** 刈った所のまわり(水平 ±radius・上下 ±1)の同じタグの植物をまとめて刈る数の上限(0 で切る)。しゃがむとまとめない。 */
        public int clearRadius = 1;
        public int clearMax = 8;
    }

    /** 胞子弾(焼いた胞子を付けた矢)と、胞子弾の弓。 */
    public static final class SporeShot {
        /** 当たった相手を燃やす秒数(どの弓で撃っても)。 */
        public int igniteSeconds = 3;
        /** 当たった所で燻る半径と、その中の植物側の Mob を燃やす秒数。 */
        public double smolderRadius = 1.5D;
        public int smolderSeconds = 2;
        /** 胞子弾の弓で撃ったときの上乗せ: 燃やす秒数・燻る半径・植物側へのダメージの倍率。 */
        public int bowIgniteSeconds = 5;
        public double bowSmolderRadius = 2.5D;
        public int bowSmolderSeconds = 4;
        public double bowPlantDamageMultiplier = 1.5D;
        public int bowDurability = 450;
    }

    /** 両手剣。 */
    public static final class Greatsword {
        public float attackDamage = 10.0F;
        public float attackSpeed = 0.8F;
        public int durability = 900;
        /** 振りがこれ以上たまっていれば薙ぐ(0〜1)。 */
        public double sweepMinCharge = 0.9D;
        /** 薙ぐ範囲: 自分からの距離・前の半円(前向きとの内積がこれ以上。0 なら真横まで)。 */
        public double sweepRange = 3.0D;
        public double sweepMinDot = 0.0D;
        /** 狙った相手のほかに斬る数と、そのダメージ(攻撃の何倍)。 */
        public int sweepMaxTargets = 5;
        public double sweepDamageRatio = 0.75D;
    }

    /** 貫きのクロスボウ。 */
    public static final class Crossbow {
        /** 装填にかかる tick(バニラ 25)。 */
        public int chargeTicks = 50;
        /** 貫く数(バニラの貫通のエンチャントの段と同じ数え方。4 なら 5 体目で止まる)。 */
        public int pierce = 4;
        /** 太い矢のダメージの倍率(矢の基本 2 に掛ける)。 */
        public double damageMultiplier = 1.6D;
        public int durability = 600;
    }

    /** 繊維の草(縁 = 繁茂のバイオームに、プレイヤーのまわりで少しずつ生える。胞子喰い茸と同じ作り)。 */
    public static final class FiberGrass {
        public int intervalTicks = 200;
        public int radius = 32;
        public int tries = 4;
        /** まわり crowdRadius に crowdMax 本あれば生やさない。 */
        public int crowdRadius = 4;
        public int crowdMax = 4;
        // 刈ったときの繊維の数(1〜2)はルートテーブル loot_table/blocks/fiber_grass.json が正本(mod マネージャー 2 のルートの画面で変える)
    }

    /** 菌が集めた金属の鉱石(汚染が岩に来たとき、地脈の線の近くなら岩が変わる)。 */
    public static final class Ore {
        /** 地脈の線からこの距離(ブロック)以内の、汚染された岩(過繁茂の石・深層岩)だけ。 */
        public double leyReach = 8.0D;
        /** 汚染された岩 1 つが鉱脈の芯になる確率。 */
        public double chance = 0.03D;
        /** 芯のまわりに広げる数(芯を含めた鉱脈の大きさ)。 */
        public int veinMin = 1;
        public int veinMax = 4;
    }
}
