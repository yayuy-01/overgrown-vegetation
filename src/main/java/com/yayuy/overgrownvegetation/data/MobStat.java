package com.yayuy.overgrownvegetation.data;

/**
 * 1 種の Mob のステータス。{@code null} のフィールドは「未指定」を表し、
 * その属性は基底クラス({@code Mob.createMobAttributes()} 等)の値のまま残す
 * (= その属性に対する {@code .add(...)} を呼ばない)。
 *
 * <p>Gson でそのままデシリアライズされる DTO。フィールド名が {@code mobs.json} のキーになる。
 */
public final class MobStat {

    public Double maxHealth;
    public Double movementSpeed;
    public Double attackDamage;
    public Double attackKnockback;
    public Double armor;
    public Double armorToughness;
    public Double knockbackResistance;
    public Double followRange;
    public Double stepHeight;
    public Double flyingSpeed;
    /** {@code SCALE} 属性の基礎値(固定の体格倍率)。省略時は 1.0 相当。 */
    public Double scale;
    /** 湧いた個体ごとにランダムに掛かる体格倍率の下限。省略時は 1.0(個体差なし)。 */
    public Double scaleMin;
    /** 個体差の体格倍率の上限。省略時は 1.0。実効スケール = scale × ランダム(scaleMin〜scaleMax)。 */
    public Double scaleMax;
    /** ヒットボックスの幅(ブロック)。{@code ModEntities} の {@code .sized(w, h)} に渡る。 */
    public Double width;
    /** ヒットボックスの高さ(ブロック)。 */
    public Double height;
    public Integer xpReward;
    /**
     * スポーンエッグの絵として本体モデルを描くときに、アイテム枠へ収める大きさ(ブロック)。
     * <b>当たり判定ではなくモデルの見た目の最大辺</b>を入れる — 尾や脚が当たり判定から
     * はみ出しているモデルだと、当たり判定で縮めても枠からあふれるため。
     * 省略時は当たり判定の最大辺({ max(width, height)})。
     * 数値は { tools/anim-preview}(モーションビューア)の「モデルの大きさ」表示から取る。
     *
     *  com.yayuy.overgrownvegetation.client.SpawnEggEntityItemRenderer
     */
    public Double eggFit;

    public MobStat() {
    }

    /**
     * このインスタンス(既定値)を土台に、{@code over} の非 null フィールドで上書きした新インスタンスを返す。
     * {@code over} が null ならこのインスタンスをそのまま返す。
     */
    public MobStat withOverrides(MobStat over) {
        if (over == null) {
            return this;
        }
        MobStat r = new MobStat();
        r.maxHealth = pick(over.maxHealth, maxHealth);
        r.movementSpeed = pick(over.movementSpeed, movementSpeed);
        r.attackDamage = pick(over.attackDamage, attackDamage);
        r.attackKnockback = pick(over.attackKnockback, attackKnockback);
        r.armor = pick(over.armor, armor);
        r.armorToughness = pick(over.armorToughness, armorToughness);
        r.knockbackResistance = pick(over.knockbackResistance, knockbackResistance);
        r.followRange = pick(over.followRange, followRange);
        r.stepHeight = pick(over.stepHeight, stepHeight);
        r.flyingSpeed = pick(over.flyingSpeed, flyingSpeed);
        r.scale = pick(over.scale, scale);
        r.scaleMin = pick(over.scaleMin, scaleMin);
        r.scaleMax = pick(over.scaleMax, scaleMax);
        r.width = pick(over.width, width);
        r.height = pick(over.height, height);
        r.xpReward = over.xpReward != null ? over.xpReward : xpReward;
        r.eggFit = pick(over.eggFit, eggFit);
        return r;
    }

    public float width(float fallback) {
        return width != null ? width.floatValue() : fallback;
    }

    public float height(float fallback) {
        return height != null ? height.floatValue() : fallback;
    }

    public int xpReward(int fallback) {
        return xpReward != null ? xpReward : fallback;
    }

    /** スポーンエッグで枠に収める大きさ(ブロック)。未設定なら {@code fallback}(＝当たり判定の最大辺)。 */
    public float eggFit(float fallback) {
        return eggFit != null && eggFit > 0.0D ? eggFit.floatValue() : fallback;
    }

    /** 個体差の下限(省略時 1.0)。 */
    public double scaleMin() {
        return scaleMin != null ? scaleMin : 1.0D;
    }

    /** 個体差の上限(省略時 1.0、下限未満なら下限に丸める)。 */
    public double scaleMax() {
        double lo = scaleMin();
        double hi = scaleMax != null ? scaleMax : 1.0D;
        return Math.max(lo, hi);
    }

    private static Double pick(Double a, Double b) {
        return a != null ? a : b;
    }
}
