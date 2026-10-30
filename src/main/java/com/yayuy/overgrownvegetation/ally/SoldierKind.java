package com.yayuy.overgrownvegetation.ally;

/**
 * 同盟軍の兵種と、兵種ごとの数値(2026-10-01、すべて仮決め)。
 *
 * <p>ユーザー指定の強さの目安: グループ 1〜4 には 1 対 1 で勝てる / グループ 5 は相性 / グループ 6 以降は複数要る。
 * 銃は当たり判定を線で取る(弾は飛ばさない)。1 回の撃ち方 = {@link #burst} 発を {@link #shotInterval} tick おきに撃ち、
 * {@link #cooldown} tick 休む。狙いのぶれは {@link #spread} 度(正規分布の標準偏差)。
 *
 * <p>砲兵は離れた相手({@link #MORTAR_MIN} マス以上)へ迫撃砲を撃つ(着弾の周りの過繁茂の Mob だけ傷つく。地形は壊さない)。
 * 工兵はいまは小銃を持つだけ(基地を広げる・直す役は後で)。
 */
public enum SoldierKind {
    /** 歩兵: OCP 迷彩・プレートキャリア。 */
    RIFLEMAN("rifleman", 30.0, 6.0, 0.30, 5.0F, 3, 3, 30, 2.5F, 40),
    /** 砲兵: 迷彩の帽子・耳当て。小銃は弱め、離れた群れには迫撃砲。 */
    ARTILLERY("artillery", 30.0, 4.0, 0.28, 4.0F, 2, 4, 40, 3.0F, 36),
    /** 工兵: 黄色の反射ベルト。短い小銃。 */
    ENGINEER("engineer", 30.0, 6.0, 0.30, 4.0F, 3, 3, 36, 3.0F, 32),
    /** エリート兵: 黒の特殊部隊(ユーザー指定)。体力・装甲・連射・狙いが上。 */
    ELITE("elite", 50.0, 12.0, 0.33, 7.0F, 4, 2, 22, 1.4F, 48);

    /** 迫撃砲を撃つ相手までの距離の下限・上限と、撃つ間隔・着弾までの時間・半径・着弾の中心での傷。 */
    public static final double MORTAR_MIN = 12.0;
    public static final double MORTAR_MAX = 48.0;
    public static final int MORTAR_COOLDOWN = 120;
    public static final int MORTAR_FLIGHT = 30;
    public static final double MORTAR_RADIUS = 3.5;
    public static final float MORTAR_DAMAGE = 12.0F;

    /** 登録名とテクスチャの名前({@code textures/entity/soldier/<name>.png})。 */
    public final String id;
    public final double maxHealth;
    public final double armor;
    public final double speed;
    /** 1 発の傷。 */
    public final float damage;
    public final int burst;
    public final int shotInterval;
    public final int cooldown;
    /** 狙いのぶれ(度)。 */
    public final float spread;
    /** 撃つ距離の上限(マス)。 */
    public final int range;

    SoldierKind(String id, double maxHealth, double armor, double speed, float damage, int burst, int shotInterval,
                int cooldown, float spread, int range) {
        this.id = id;
        this.maxHealth = maxHealth;
        this.armor = armor;
        this.speed = speed;
        this.damage = damage;
        this.burst = burst;
        this.shotInterval = shotInterval;
        this.cooldown = cooldown;
        this.spread = spread;
        this.range = range;
    }

    /** 登録名 {@code id} の兵種(無ければ null。基地の名簿の読み込み)。 */
    public static SoldierKind byId(String id) {
        for (SoldierKind k : values()) {
            if (k.id.equals(id)) {
                return k;
            }
        }
        return null;
    }

    /** 1 秒あたりの傷のおよその量(全部当たったとき)。 */
    public double dps() {
        int cycle = (burst - 1) * shotInterval + cooldown;
        return damage * burst * 20.0 / cycle;
    }
}
