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

    /**
     * 自動で湧くかどうか。{ false} にすると、この Mob は
     * <b>自然発生の経路すべて</b>(過繁茂バイオームの生態系スポーン・レイドの増援ウェーブ・
     * 拡大種のうずくまり増援・自然感染による置き換え・胞子濃度による汚染シダの発生)から外れる。
     * スポーンエッグ・{ /summon}・スポナー・繁殖といった<b>意図的に出す湧きは対象外</b>なので、
     * テスト用の Mob を「勝手には湧かないが召喚はできる」状態にできる。省略時は { true}。
     *
     *  com.yayuy.overgrownvegetation.phase.OvergrownSpawnProfile
     */
    public Boolean naturalSpawn;

    /**
     * ビーム攻撃の設定(今のところフィロセンスだけが使う)。
     * 以前は {@code overgrown_vegetation-common.toml} の {@code phyllosensBeam*} だったが、
     * 調整のたびに toml を手で開くのが面倒なので mobs.json 側へ移した(2026-09-16)。
     */
    public Beam beam;

    /** ビーム攻撃の数値。null のフィールドは {@link #DEFAULT_BEAM} の値になる。 */
    public static final class Beam {
        /** 当たった相手へのダメージ。防具は効く。 */
        public Double damage;
        /** 溜め(tick)。この間は頭が相手を追い、狙点も動く。60 = 3 秒。 */
        public Integer chargeTicks;
        /** 狙点を固定してから発射するまで(tick)。この間に横へ動けば避けられる。6 = 0.3 秒。 */
        public Integer lockTicks;
        /** 撃ったあと次に撃てるまで(tick)。 */
        public Integer cooldownTicks;
        /** 射程(ブロック)。これより遠い相手には近づいてから撃つ。{@code followRange} と揃えること。 */
        public Double range;
        /** 当たり判定の太さ(ブロック)。 */
        public Double radius;
        /** 見た目の太さ(ブロック)。当たり判定とは別。 */
        public Double drawRadius;

        public double damage() { return damage != null ? damage : 20.0D; }
        public int chargeTicks() { return chargeTicks != null ? Math.max(1, chargeTicks) : 60; }
        public int lockTicks() { return lockTicks != null ? Math.max(0, lockTicks) : 6; }
        public int cooldownTicks() { return cooldownTicks != null ? Math.max(0, cooldownTicks) : 80; }
        public double range() { return range != null ? range : 80.0D; }
        public double radius() { return radius != null ? radius : 0.45D; }
        public double drawRadius() { return drawRadius != null ? drawRadius : 0.28D; }
    }

    /**
     * 投下爆弾の設定(今のところヴェロフィルだけが使う)。
     * 以前は {@code overgrown_vegetation-common.toml} の {@code velophyllBomb*} だったが、
     * ビームと同じく mobs.json 側へ移した(2026-09-17)。
     */
    public Bomb bomb;

    /**
     * この種が<b>襲いに行く相手</b>の id(名前空間は省略可 = {@code overgrown_vegetation})。
     * 以前は {@code predation.json} の {@code table} に「捕食者 → 被食者のリスト」としてまとめて
     * 書いていたが、Mob ごとに見た方が分かりやすいのでこちらへ移した(2026-09-21)。
     * 1 体倒すごとに攻撃力・体力が上がる率は {@code predation.json} の
     * {@code preyKillBonusPerKill} のまま(全種共通なので)。
     *
     * @see com.yayuy.overgrownvegetation.entity.Predation
     */
    public java.util.List<String> preys;

    /**
     * 自然に湧く条件(2026-09-21)。以前は {@code phases.json} 側に
     * 「そのフェーズに湧くグループ番号({@code spawnGroups})」「外に湧かせる種
     * ({@code outsideSpawns.types} / {@code carrierSpawns.types})」として書いていたが、
     * 「この Mob はどのフェーズのどこに湧くか」を 1 か所で見られるようにこちらへ移した。
     */
    public Spawns spawns;

    /**
     * 湧く場所。2026-09-21 は「過繫茂バイオーム / その外」の 2 択だったが、湿地と密林で湧く種を
     * 変えられるように環境ごとに分けた(2026-09-21 夜)。海だけは環境(密林/湿地/菌湿地)を
     * 持たないバイオームなので独立した枠。
     */
    public enum SpawnPlace {
        /** 過繫茂した密林(陸・川)。 */
        JUNGLE("jungle"),
        /** 過繫茂した湿地・菌湿地(陸・川)。 */
        SWAMP("swamp"),
        /** 過繫茂した海。水生種だけが使う。 */
        OCEAN("ocean"),
        /** 繫茂系ではない普通のバイオーム({@code OutsideInvaderSpawner})。 */
        OUTSIDE("outside");

        private final String key;

        SpawnPlace(String key) {
            this.key = key;
        }

        /** {@code mobs.json} の {@code spawns} の中でのキー名。 */
        public String key() {
            return key;
        }
    }

    /** 湧く場所ごとの、湧くフェーズ番号(1〜10)の一覧。 */
    public static final class Spawns {
        /** 過繫茂した密林(陸・川)に湧くフェーズ({@code OvergrownBiomeSpawner})。 */
        public java.util.List<Integer> jungle;
        /** 過繫茂した湿地・菌湿地(陸・川)に湧くフェーズ。 */
        public java.util.List<Integer> swamp;
        /** 過繫茂した海に湧くフェーズ(水生種向け)。 */
        public java.util.List<Integer> ocean;
        /**
         * 過繫茂系バイオームの<b>外</b>(普通のバイオーム)に湧くフェーズ
         * ({@code OutsideInvaderSpawner})。頭数の上限・確率・距離はフェーズ側の設定を使い、
         * 媒介種(グループ1)とそれ以外で枠を分けて数える。
         */
        public java.util.List<Integer> outside;

        /** その場所に湧くフェーズ番号(未設定なら空)。 */
        public java.util.List<Integer> of(SpawnPlace place) {
            java.util.List<Integer> list = switch (place) {
                case JUNGLE -> jungle;
                case SWAMP -> swamp;
                case OCEAN -> ocean;
                case OUTSIDE -> outside;
            };
            return list != null ? list : java.util.List.of();
        }
    }

    /** 投下爆弾の数値。null のフィールドは既定値になる。 */
    public static final class Bomb {
        /** 1 回の爆撃で落とす数。まとめてではなく 1 発ずつ順に落とす。 */
        public Integer perSalvo;
        /** 1 発ずつ落とす間隔(tick)。 */
        public Integer intervalTicks;
        /** 爆撃したあと次の爆撃までの間隔(tick)。 */
        public Integer cooldownTicks;
        /** 爆風のダメージ(中心。距離で減衰する)。 */
        public Double blastDamage;
        /** 直撃した相手への追加ダメージ。 */
        public Double directDamage;
        /** 爆風の半径(ブロック)。 */
        public Double blastRadius;
        /** 爆風のノックバック。 */
        public Double knockback;
        /** 胞子感染を与える半径(ブロック)。 */
        public Double infectRadius;
        /** 着弾地点を汚染する半径(ブロック)。 */
        public Double corruptionRadius;
        /** 汚染の縁のゆらぎ(ブロック)。 */
        public Double corruptionNoise;
        /** 着弾で葉を吹き飛ばす半径(ブロック)。 */
        public Double leafClearRadius;

        public int perSalvo() { return perSalvo != null ? Math.max(1, perSalvo) : 4; }
        public int intervalTicks() { return intervalTicks != null ? Math.max(1, intervalTicks) : 4; }
        public int cooldownTicks() { return cooldownTicks != null ? Math.max(0, cooldownTicks) : 400; }
        public double blastDamage() { return blastDamage != null ? blastDamage : 12.0D; }
        public double directDamage() { return directDamage != null ? directDamage : 45.0D; }
        public double blastRadius() { return blastRadius != null ? blastRadius : 6.0D; }
        public double knockback() { return knockback != null ? knockback : 0.9D; }
        public double infectRadius() { return infectRadius != null ? infectRadius : 4.0D; }
        public double corruptionRadius() { return corruptionRadius != null ? corruptionRadius : 2.0D; }
        public double corruptionNoise() { return corruptionNoise != null ? corruptionNoise : 0.7D; }
        public double leafClearRadius() { return leafClearRadius != null ? leafClearRadius : 5.0D; }
    }

    /** {@code bomb} が書かれていないときに使う既定値。 */
    public static final Bomb DEFAULT_BOMB = new Bomb();

    /** このMobの爆弾設定(未指定なら既定値)。 */
    public Bomb bomb() {
        return bomb != null ? bomb : DEFAULT_BOMB;
    }

    /** 既定値の爆弾設定に、json 側の非 null フィールドだけを重ねる。 */
    private static Bomb mergeBomb(Bomb base, Bomb over) {
        if (over == null) {
            return base;
        }
        if (base == null) {
            return over;
        }
        Bomb r = new Bomb();
        r.perSalvo = over.perSalvo != null ? over.perSalvo : base.perSalvo;
        r.intervalTicks = over.intervalTicks != null ? over.intervalTicks : base.intervalTicks;
        r.cooldownTicks = over.cooldownTicks != null ? over.cooldownTicks : base.cooldownTicks;
        r.blastDamage = pick(over.blastDamage, base.blastDamage);
        r.directDamage = pick(over.directDamage, base.directDamage);
        r.blastRadius = pick(over.blastRadius, base.blastRadius);
        r.knockback = pick(over.knockback, base.knockback);
        r.infectRadius = pick(over.infectRadius, base.infectRadius);
        r.corruptionRadius = pick(over.corruptionRadius, base.corruptionRadius);
        r.corruptionNoise = pick(over.corruptionNoise, base.corruptionNoise);
        r.leafClearRadius = pick(over.leafClearRadius, base.leafClearRadius);
        return r;
    }

    /** {@code beam} が書かれていないときに使う既定値。 */
    public static final Beam DEFAULT_BEAM = new Beam();

    /** 既定値のビーム設定に、json 側の非 null フィールドだけを重ねる。 */
    private static Beam mergeBeam(Beam base, Beam over) {
        if (over == null) {
            return base;
        }
        if (base == null) {
            return over;
        }
        Beam r = new Beam();
        r.damage = pick(over.damage, base.damage);
        r.chargeTicks = over.chargeTicks != null ? over.chargeTicks : base.chargeTicks;
        r.lockTicks = over.lockTicks != null ? over.lockTicks : base.lockTicks;
        r.cooldownTicks = over.cooldownTicks != null ? over.cooldownTicks : base.cooldownTicks;
        r.range = pick(over.range, base.range);
        r.radius = pick(over.radius, base.radius);
        r.drawRadius = pick(over.drawRadius, base.drawRadius);
        return r;
    }

    /** このMobのビーム設定(未指定なら既定値)。 */
    public Beam beam() {
        return beam != null ? beam : DEFAULT_BEAM;
    }

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
        r.naturalSpawn = over.naturalSpawn != null ? over.naturalSpawn : naturalSpawn;
        r.beam = mergeBeam(beam, over.beam);
        r.bomb = mergeBomb(bomb, over.bomb);
        // 一覧は「差分」ではなく「書いてあればそれが全部」— 既定から 1 件だけ消したいときに
        // 消せなくなるため、要素単位のマージはしない。
        r.preys = over.preys != null ? over.preys : preys;
        r.spawns = mergeSpawns(spawns, over.spawns);
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

    /** 自動で湧くか(未設定なら true = 湧く)。 */
    public boolean naturalSpawn() {
        return naturalSpawn == null || naturalSpawn;
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

    /** 襲いに行く相手の一覧(未設定なら空)。 */
    public java.util.List<String> preys() {
        return preys != null ? preys : java.util.List.of();
    }

    /** 湧く条件(未設定でも null にはならない)。 */
    public Spawns spawns() {
        return spawns != null ? spawns : new Spawns();
    }

    private static Spawns mergeSpawns(Spawns base, Spawns over) {
        if (over == null) {
            return base;
        }
        if (base == null) {
            return over;
        }
        Spawns r = new Spawns();
        r.jungle = over.jungle != null ? over.jungle : base.jungle;
        r.swamp = over.swamp != null ? over.swamp : base.swamp;
        r.ocean = over.ocean != null ? over.ocean : base.ocean;
        r.outside = over.outside != null ? over.outside : base.outside;
        return r;
    }

    private static Double pick(Double a, Double b) {
        return a != null ? a : b;
    }
}
