package com.yayuy.overgrownvegetation.data;

import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code mobs.json}(設計データ)から読み込んだ Mob ステータスの束。
 *
 * <p>現行のハードコード値は {@link #DEFAULTS} に写経してあり、これが常にフォールバックになる。
 * {@code mobs.json} は起動時にこの既定値のコピーとして書き出され、ユーザーが編集する。
 * {@link #forId(String)} は「既定値 + json での上書き」をマージした最終値を返す。
 *
 * <p>2026-11-16 見直し ④: 呼び出し元の無くなった旧い湧き・捕食の表(naturalSpawn・spawns・preys・tdCost・
 * beam・bomb・drift)を消した。湧きの正本は {@code spawns.json}({@code spawn/SpawnTable})。
 *
 * @see DesignData
 */
public final class MobStats {

    /** 現行ハードコード値(唯一の真実のフォールバック)。キーは {@code overgrown_vegetation} 名前空間内のパス。 */
    public static final Map<String, MobStat> DEFAULTS = buildDefaults();

    private final Map<String, MobStat> loaded;

    MobStats(Map<String, MobStat> loaded) {
        this.loaded = loaded != null ? loaded : Map.of();
    }

    /** {@code mobs.json} が読めなかったときのフォールバック(既定値のみ)。 */
    public static MobStats defaultsOnly() {
        return new MobStats(Map.of());
    }

    /**
     * {@link #forId} の答えの控え。毎 tick・毎フレーム引かれうるので、
     * マージした {@link MobStat} を id ごとに 1 回だけ作って使い回す。{@code loaded} も {@link #DEFAULTS} も
     * 作った後は変わらず、返した {@link MobStat} を書き換える呼び出し元も無い(読むだけ)ので中身は毎回と同じ。
     * design reload では MobStats ごと作り直されるので、控えも一緒に捨てられる。
     * サーバーと描画のスレッドから同時に引かれるので ConcurrentHashMap。
     */
    private final Map<String, MobStat> resolved = new java.util.concurrent.ConcurrentHashMap<>();

    /** 既定値に json の上書きを重ねた最終ステータス。未知の id でも空の {@link MobStat} を返す(null にはならない)。 */
    public MobStat forId(String path) {
        // 作る側は resolved に触らないので、computeIfAbsent の入れ子(Recursive update)にはならない。
        return resolved.computeIfAbsent(path,
                p -> DEFAULTS.getOrDefault(p, new MobStat()).withOverrides(loaded.get(p)));
    }

    // --- AttributeSupplier への適用 ---

    /**
     * {@code builder} に {@code path} の Mob の各属性を {@code .add(...)} する。
     * 非 null のフィールドだけを追加するので、その Mob が本来持たない属性を勝手に生やさない
     * (ただし json で明示的に値を入れれば追加される)。
     */
    public static AttributeSupplier.Builder apply(AttributeSupplier.Builder builder, String path) {
        MobStat s = DesignData.mobs().forId(path);
        addIf(builder, Attributes.MAX_HEALTH, s.maxHealth);
        addIf(builder, Attributes.MOVEMENT_SPEED, s.movementSpeed);
        addIf(builder, Attributes.ATTACK_DAMAGE, s.attackDamage);
        addIf(builder, Attributes.ATTACK_KNOCKBACK, s.attackKnockback);
        addIf(builder, Attributes.ARMOR, s.armor);
        addIf(builder, Attributes.ARMOR_TOUGHNESS, s.armorToughness);
        addIf(builder, Attributes.KNOCKBACK_RESISTANCE, s.knockbackResistance);
        addIf(builder, Attributes.FOLLOW_RANGE, s.followRange);
        addIf(builder, Attributes.STEP_HEIGHT, s.stepHeight);
        addIf(builder, Attributes.FLYING_SPEED, s.flyingSpeed);
        addIf(builder, Attributes.SCALE, s.scale);
        return builder;
    }

    private static void addIf(AttributeSupplier.Builder builder,
                              net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attr,
                              Double value) {
        if (value != null) {
            builder.add(attr, value);
        }
    }

    /** {@code ModEntities} のヒットボックス指定用。 */
    public static float width(String path, float fallback) {
        return DesignData.mobs().forId(path).width(fallback);
    }

    public static float height(String path, float fallback) {
        return DesignData.mobs().forId(path).height(fallback);
    }

    /**
     * {@code ModEntities} の湧き判定の箱の倍率({@code EntityType.Builder#spawnDimensionsScale})。
     * その種が<b>育ちうるいちばん大きな体</b> = mobs.json の {@code scale} × 個体差の上限 {@code scaleMax}。
     *
     * <p>SCALE 属性が当たり判定に掛かるのは湧いた後の<b>最初の tick の終わり</b>
     * ({@code LivingEntity#tick} の {@code appliedScale} の比較)なので、湧く場所の空きを確かめる箱
     * ({@code EntityType#getSpawnAABB})にも掛けておかないと、素の大きさの隙間に湧いてから育って丸太や壁に埋まる。
     * 個体差({@link com.yayuy.overgrownvegetation.entity.MobSizeVariance})は湧かせる前にはまだ抽選していないので上限で見る。
     * 1 未満には縮めない —— 最初の tick までは素の大きさの箱のまま世界にいるので(scale が 1 未満の種)。
     */
    public static float spawnScale(String path) {
        MobStat s = DesignData.mobs().forId(path);
        double scale = s.scale != null ? s.scale : 1.0D;
        return (float) Math.max(1.0D, scale * s.scaleMax());
    }

    /** エンティティのコンストラクタでの {@code this.xpReward} 上書き用。 */
    public static int xpReward(String path, int fallback) {
        return DesignData.mobs().forId(path).xpReward(fallback);
    }

    // --- 既定値表 ---

    private static MobStat stat(java.util.function.Consumer<MobStat> init) {
        MobStat s = new MobStat();
        init.accept(s);
        return s;
    }

    private static Map<String, MobStat> buildDefaults() {
        Map<String, MobStat> m = new LinkedHashMap<>();

        m.put("blight_fern", stat(s -> {
            // 2026-10-23 胞子の場 第 2 段 F: 萌芽体は歩いて湿った所で根付くので、遅いが動ける(仮決め)
            s.maxHealth = 80.0; s.movementSpeed = 0.18; s.knockbackResistance = 1.0;
            s.armor = 4.0; s.followRange = 16.0; s.scale = 1.0;
            // 10-23 萌芽体のモデル(ユーザーのラフ、見た目の背丈 3.65)で体格 1。当たり判定は低く 1 × 2(ユーザー指定: 頭上に 3.6 を使わない = 森の下を歩ける)
            s.width = 1.0; s.height = 2.0;
            s.eggFit = 5.12;
        }));

        // ハイヴノード — 蔦で吊られた球体。動かない。蔦を 7 割切るまでは 99% のダメージカットが
        // かかっているので、体力 10000 は「直接殴っても倒せない」ことを表すための値。
        // ハイヴノード。体力 1000・防御 20 はユーザー指定(2026-10-23 E1、前は 10000・99% カット)。
        m.put("hive_node", stat(s -> {
            s.maxHealth = 1000.0; s.movementSpeed = 0.0; s.knockbackResistance = 1.0;
            s.armor = 20.0; s.armorToughness = 0.0; s.followRange = 0.0;
            s.width = 3.0; s.height = 3.0; s.xpReward = 200;
            s.eggFit = 6.8;
        }));

        // 巣の蔦 — ハイヴノードを吊る蔦 1 本。プレイヤーが 1 本ずつ切り落とす。
        // スカトゥリウム。HP 50 はユーザー指定(2026-09-25、100 から半分に)。
        m.put("overgrown_shielder", stat(s -> {
            s.maxHealth = 50.0; s.movementSpeed = 0.2; s.knockbackResistance = 0.9;
            s.armor = 16.0; s.armorToughness = 6.0; s.attackDamage = 12.0; s.attackKnockback = 1.0;
            s.followRange = 28.0; s.stepHeight = 1.5;
            s.width = 2.5; s.height = 2.0;
            s.eggFit = 4.7;
        }));

        // グループ5(適応種)。HP 50・攻撃 16・防御 14 はユーザー指定(2026-09-23)。
        m.put("falcimnes", stat(s -> {
            s.maxHealth = 50.0; s.attackDamage = 16.0; s.armor = 14.0; s.movementSpeed = 0.27;
            s.knockbackResistance = 0.8; s.attackKnockback = 0.6; s.followRange = 32.0; s.stepHeight = 1.5;
            // 体格 1.8 倍(ユーザー指定 2026-09-23: 1.5 倍 → さらに 1.2 倍)。SCALE は見た目と当たり判定の両方に
            // 掛かるので、素の幅を 2.6 → 1.65 に下げて実効 2.97 に収める(3 を超えると経路探索が通らない。
            // フィロセンスの注記と同じ)。実効 2.97 x 3.06、全長は 11.5 ブロック。首と尾は判定からはみ出す。
            // eggFit は待機アニメ中の画面上の差し渡し(SpawnEggEntityItemRenderer#fitSizeOf の式で測った値、全長ではない)。
            // 11-05: 描画倍率 1.8 を geo に焼き込んだので体格は 1、判定は焼き込む前の実効値をそのまま書く(1.65 x 1.7 の 1.8 倍)。
            s.scale = 1.0;
            s.width = 2.97; s.height = 3.06; s.xpReward = 40;
            s.eggFit = 6.45;
        }));

        // グループ5(適応種)の盾役。HP 150・防御 20・攻撃 8・体格 1.6 はユーザー指定(2026-09-23)。
        // 移動速度はユーザー指定「ファルキムネス(0.27)より少し速い」→ 0.30(仮)。盾を下ろすと −35%(ScutimnesEntity が属性に掛ける)。
        // ノックバック耐性 0.9・攻撃のノックバック 2.0(バッシュが強く押す)・段差 1.2・経験値 40 は仮。防具強度はファルキムネスと同じく無し。
        m.put("scutimnes", stat(s -> {
            s.maxHealth = 150.0; s.attackDamage = 8.0; s.armor = 20.0; s.movementSpeed = 0.30;
            s.knockbackResistance = 0.9; s.attackKnockback = 2.0; s.followRange = 32.0; s.stepHeight = 1.2;
            // 体格 2.88 倍(ユーザー指定: 初版の 1.6 をさらに 1.8 倍、2026-09-24)。全長は約 13 ブロック。
            // 素の幅 1.03 で実効 2.97(3 を超えると経路探索が通らない)。脚の幅(約 4.8)も盾の前面(原点の 2.3 前)も判定からはみ出す
            // —— ファルキムネスと同じ割り切り。高さ 0.9 → 実効 2.59 = 盾を上げた姿勢の外接箱の高さ(2.5)。尾(原点の 11.3 後ろ)も外。
            // eggFit は待機アニメ(盾を上げた姿勢)の画面上の差し渡し(SpawnEggEntityItemRenderer#fitSizeOf の式、描画のずらし込み)。
            // 体格 1.6 のとき最終の idle(3 秒を 0.1 秒刻み)で 6.35 と測ったものを、体格に比例させて 1.8 倍した。
            s.scale = 2.88;
            s.width = 1.03; s.height = 0.9; s.xpReward = 40;
            s.eggFit = 11.43;
        }));

        // グループ5(適応種)の空の狙撃役(2026-09-28)。体格 1.8 倍はユーザー指定「ファルキムネスやスクティムネスと同じくらいのサイズ感」
        // (ファルキムネスと同じ倍率。全長 10.6・翅の幅 5.4・体 4.1 ブロック)。それ以外はすべて仮:
        // 体力 40・防御 8(撃ち合いの的になるので打たれ弱め)・攻撃 12(= 棘のダメージ、防具を貫通)・飛ぶ速さ 0.6(最高 0.3 ブロック/tick)・
        // 追跡 48(上空から撃つ)・ノックバック耐性 0.5・経験値 40。素の判定 1.4 x 1.8 → 実効 2.52 x 3.24(胸・腹の前半・垂らした脚)。
        // eggFit は待機の姿の画面上の差し渡しの見当(針が長いので全長ではなく 10)。
        // 2026-11-15 段 1(止まっては飛ぶ・移るまで。技は段 2)で作り直し: 形は re_acumnes_r8(描画倍率 1 で全長 10.6・翅の幅 5.5・高さ 3.4。
        // 旧版の体格 1.8 はやめた)。当たりの芯は胸と垂らした脚(1.2 × 3.0【仮】。止まり木の上の空き 1.3 幅に収まる)。翅・頭・腹は叩ける部位。
        // 体力・防御・攻撃・追跡は旧版のまま【仮】。動きの速さの本体は entity/flyer/DartHover(鋭く飛ぶ 1.0 ブロック/tick)。
        m.put("acumnes", stat(s -> {
            s.maxHealth = 40.0; s.attackDamage = 12.0; s.armor = 8.0; s.movementSpeed = 0.3; s.flyingSpeed = 1.0;
            s.knockbackResistance = 0.5; s.followRange = 64.0;   // 11-16 ユーザー「射程を最大 60 に」: 狙撃 60 に届くよう 48 → 64【仮】
            s.width = 1.2; s.height = 3.0; s.xpReward = 40;
            s.eggFit = 7.2;
        }));

        // グループ5 の低キューブ 4 種(2026-10-01 足場)。体格(scale)は NOTES の「描画倍率」、素の判定は NOTES の目安 ÷ 体格。
        // それ以外の数値はすべて仮(G5 の釣り合い: ファルキムネス 50/16/14・スクティムネス 150/8/20・アクムネス 40/12/8 を見て、
        // 大型 3 種は体力 130〜160、フォディムネスは 90)。実装係・ユーザーが詰める。
        // バティムネス — 海のカメロケラス(長さ 11)。泳ぐ速さ 0.9 は水生の種(0.6〜1.15)の間。陸ではほぼ動けない(実装側で絞る)。
        m.put("bathymnes", stat(s -> {
            s.maxHealth = 160.0; s.attackDamage = 14.0; s.armor = 16.0; s.movementSpeed = 0.9;
            s.knockbackResistance = 1.0; s.followRange = 32.0;
            s.scale = 2.0;
            s.width = 1.25; s.height = 1.25; s.xpReward = 60;
            s.eggFit = 9.0;
        }));
        // エレボムネス — 巨大な洞窟の空中をゆっくり泳ぐアノマロカリス類(長さ 8.6)。
        m.put("erebomnes", stat(s -> {
            s.maxHealth = 130.0; s.attackDamage = 10.0; s.armor = 10.0; s.movementSpeed = 0.2; s.flyingSpeed = 0.3;
            s.knockbackResistance = 0.9; s.followRange = 32.0;
            s.scale = 2.0;
            s.width = 1.75; s.height = 1.05; s.xpReward = 50;
            s.eggFit = 7.5;
        }));
        // ヴォラムネス — 地中を泳ぐ大型の蠕虫(地上に出ると高さ 8.7)。
        m.put("voramnes", stat(s -> {
            s.maxHealth = 150.0; s.attackDamage = 12.0; s.armor = 12.0; s.movementSpeed = 0.3;
            s.knockbackResistance = 1.0; s.followRange = 32.0;
            s.scale = 2.0;
            s.width = 1.25; s.height = 4.25; s.xpReward = 60;
            s.eggFit = 9.0;
        }));
        // フォディムネス — 地中の砂虫型のワーム(頭の判定。節と尾は部位の判定で体力は共通 = ユーザー指定)。追跡 24 は「聞こえる距離」の仮。
        // 体格 2 は 2026-10-01 の仮(ユーザー「サイズが小さすぎる」。1 では太さ 0.53 ブロック)。節の間隔・速さ・噛む届きも体格に比例する。
        // eggFit は頭 + 前の 2 節(スポーンエッグの絵に描く所)の見かけの差し渡し ≒ 2.3 × 体格。
        m.put("fodimnes", stat(s -> {
            s.maxHealth = 90.0; s.attackDamage = 14.0; s.armor = 10.0; s.movementSpeed = 0.35;
            s.knockbackResistance = 1.0; s.followRange = 24.0;
            s.scale = 2.0;
            s.width = 0.65; s.height = 0.66; s.xpReward = 40;
            s.eggFit = 4.6;
        }));

        m.put("phalangiotarbus", stat(s -> {
            s.maxHealth = 12.0; s.attackDamage = 8.0; s.movementSpeed = 0.39;
            s.knockbackResistance = 0.3; s.attackKnockback = 0.0; s.followRange = 24.0; s.stepHeight = 1.0;
            s.width = 1.0; s.height = 0.9; s.xpReward = 8;
            s.scaleMin = 0.6; s.scaleMax = 1.0; // 湧いた個体ごとの体格の個体差
            s.eggFit = 1.82;
        }));

        m.put("protophasma", stat(s -> {
            s.maxHealth = 6.0; s.movementSpeed = 0.27; s.followRange = 12.0; s.stepHeight = 1.0;
            s.width = 0.5; s.height = 0.5;
            s.eggFit = 1.52;
        }));

        m.put("arthropleura", stat(s -> {
            s.maxHealth = 34.0; s.movementSpeed = 0.17; s.armor = 16.0; s.armorToughness = 8.0;
            s.knockbackResistance = 0.8; s.followRange = 16.0; s.stepHeight = 1.0;
            s.width = 1.1; s.height = 0.55;
            s.eggFit = 2.3;
        }));

        // 岩に擬態するヤスデ。アースロプレウラより小さく、殻はそこそこ硬い。
        // 脱皮で抜け殻を落とすので、倒さなくても素材が採れる。
        m.put("lithopleura", stat(s -> {
            s.maxHealth = 16.0; s.movementSpeed = 0.16; s.armor = 10.0; s.armorToughness = 4.0;
            s.knockbackResistance = 0.6; s.followRange = 14.0; s.stepHeight = 1.0;
            s.width = 0.9; s.height = 0.5;
            s.eggFit = 3.5;
        }));

        // 滑空する爬虫類。<b>地上は遅くていい</b> —— 移動の本体は滑空で、歩くのは木へ戻るときだけ。
        // 体力はファランギオターブス(12)より少し上、攻撃力はメガネウラ(9)より下。
        // 急降下の一撃で仕留める種ではなく、何度も降り直して削る側。
        m.put("coelurosauravus", stat(s -> {
            s.maxHealth = 14.0; s.attackDamage = 7.0; s.movementSpeed = 0.24;
            s.knockbackResistance = 0.1; s.followRange = 20.0; s.stepHeight = 1.0;
            s.width = 0.7; s.height = 0.4;
            s.xpReward = 8; s.eggFit = 2.6;
        }));

        // 洞窟のヤスデ。アースロプレウラより細く、殻も薄い(armor 16 → 8)ぶん少し速い。
        // 全長 2 ブロックだが当たり判定は幅 0.7 —— 狭い坑道を通れないと洞窟に棲めないので、
        // 見た目の長さではなく<b>通れる太さ</b>に合わせてある(アースロプレウラと同じ考え方)。
        m.put("spelaeopleura", stat(s -> {
            s.maxHealth = 20.0; s.movementSpeed = 0.21; s.armor = 8.0; s.armorToughness = 3.0;
            s.knockbackResistance = 0.4; s.followRange = 16.0; s.stepHeight = 1.0;
            s.width = 0.7; s.height = 0.35;
            s.eggFit = 2.15;
        }));

        m.put("stenodictya_lobata", stat(s -> {
            // 2026-11-16 段 1(飛ぶ型)で戻した。飛ぶ速さは飛ぶ型の巡航 0.35 に揃えた(前は 0.8)【仮】
            s.maxHealth = 4.0; s.movementSpeed = 0.28; s.flyingSpeed = 0.35; s.followRange = 16.0;
            s.width = 0.6; s.height = 0.4; s.xpReward = 3;
            s.eggFit = 1.28;
        }));

        m.put("meganeura", stat(s -> {
            // 2026-11-16 段 1(飛ぶ型)で戻した。飛ぶ速さは巡航 0.5(急ぐ 1.0)に揃えた(前は 1.2)【仮】。攻撃は狩りの段まで使わない
            s.maxHealth = 10.0; s.attackDamage = 9.0; s.movementSpeed = 0.32; s.flyingSpeed = 0.5;
            s.armor = 0.0; s.followRange = 32.0; s.attackKnockback = 0.0;
            s.width = 1.0; s.height = 0.5; s.xpReward = 8;
            s.eggFit = 2.35;
        }));

        // グループ4(拡大種)。移動速度 0.36 は「プレイヤーのダッシュジャンプと同じくらい」(≒ 6.7 ブロック/秒)。
        m.put("arbora", stat(s -> {
            // 体力 8・防御 4(11-05 戻したとき design の値に揃えた。前の既定 22・5 は design に上書きされて使われていなかった)。
            s.maxHealth = 8.0; s.attackDamage = 8.0; s.movementSpeed = 0.36; s.armor = 4.0;
            s.knockbackResistance = 0.0; s.followRange = 32.0; s.stepHeight = 1.0;
            // 当たり判定は<b>1 ブロックの隙間を通れる大きさ</b>(ユーザー要望 2026-09-17:
            // 「アルボラを1マスの隙間も通れるようにしてほしい」)。1.0 ちょうどではなく 0.98 なのは、
            // 経路探索が体の大きさを枡目に直すとき floor(大きさ + 1.0) を使うため ——
            // 1.0 だと floor(2.0) = 2 で <b>2x2 の通路</b>を要求する。0.98 なら floor(1.98) = 1 で
            // 1x1 の穴を通る経路が引ける。物理的にも 1 ブロックちょうどだと角で挟まるので、
            // わずかに小さくしておく必要がある。
            //
            // 以前は 1.4 x 1.0(クモと同じ「胴体に合わせる」考え方)で、2 マス幅 2 マス高の
            // 通路しか通れなかった。狭い洞窟や自分たちが掘った坑道の隅で詰まる原因になっていた。
            // 攻撃の間合いは当たり判定 + 2.0(バニラ固定)なので、縮んだぶんは 0.2 ブロックだけ。
            //
            // モデルは脚を広げると 1.75 x 1.62 x 2.45(幅 x 高さ x 奥行き)あるので、
            // 脚と体は当たり判定からはみ出す(クモと同じ)。
            // eggFit は待機アニメーション中の「画面上の差し渡し」の実測値(2026-09-15 に測り直し)。
            s.width = 0.98; s.height = 0.98; s.eggFit = 2.47; s.xpReward = 10;
        }));

        // フィロセンス(竹馬型の 1 体目、2026-11-10 作り直し)。数値は全部【仮・依頼 11-10】: 胴の体力 40・防御 4・ノックバック耐性 1.0・
        // 胴の当たり判定 1.5 × 1.5(エンティティの位置は胴。脚・首・花ははみ出す)・速さは開けた所の 0.28(森 0.12 との間は
        // entity/stilt/StiltParams)。描画倍率は掛けない(画素の大きさを揃える決まり)。eggFit は全高(脚の先〜花)の約 13.5。
        // 旧(長い首の小さな多脚・scale 2.27・ビーム)は parked/ にあり、洞窟の目になる予定。
        // 11-14: 竹馬の体は内部用の id phyllosens_stilt へ(卵・タグ・自然湧き無し。後の竹馬型の Mob 用に残す)。数値は前の phyllosens のまま。
        m.put("phyllosens_stilt", stat(s -> {
            s.maxHealth = 40.0; s.movementSpeed = 0.28; s.armor = 4.0; s.armorToughness = 0.0;
            s.knockbackResistance = 1.0; s.followRange = 32.0; s.stepHeight = 0.0;
            s.scale = 1.0;
            s.width = 1.5; s.height = 1.5; s.eggFit = 13.5; s.xpReward = 12;
        }));
        // フィロセンス = 監視塔(2026-11-14 作り直し)。数値は【仮・Claude 11-14】: 体力は竹馬の胴の 40 を塔の本体へ(板根 4 枚は別に 16 ずつ。
        // entity/PhyllosensEntity)・防御 4・押されない(1.0)・動かない(速さ 0)。当たり判定は塔の幹 2.6 × 13(閉じた立ち姿の全高 約 13。
        // 首・花・板根ははみ出す)。描画倍率は掛けない。eggFit は全高 約 14。
        m.put("phyllosens", stat(s -> {
            s.maxHealth = 40.0; s.movementSpeed = 0.0; s.armor = 4.0; s.armorToughness = 0.0;
            s.knockbackResistance = 1.0; s.followRange = 32.0; s.stepHeight = 0.0;
            s.scale = 1.0;
            s.width = 2.6; s.height = 13.0; s.eggFit = 14.0; s.xpReward = 12;
        }));

        // ヴェナムネス(G5 の原型・② 大型の地上を走る型の 1 体目、2026-11-14 段 1)。数値は全部【仮・Claude 11-14】:
        // 「ファルキムネスより脆い・足が速い・長く追う」→ 体力 34(ファルキムネス 50)・防御 6(14)・速さ 0.30(0.27)・追う距離 48(32)。
        // 当たりは胴の芯 1.2 × 2.4(首・尾ははみ出す。全体 2.2 × 3.1 × 8.1)。描画倍率 2.5 は描画で掛ける(体格は 1)。攻撃は次の段。
        m.put("venamnes", stat(s -> {
            s.maxHealth = 34.0; s.attackDamage = 10.0; s.armor = 6.0; s.armorToughness = 0.0; s.movementSpeed = 0.30;
            s.knockbackResistance = 0.4; s.followRange = 48.0; s.stepHeight = 1.5;
            s.scale = 1.0;
            s.width = 1.2; s.height = 2.4; s.eggFit = 5.61; s.xpReward = 30;
        }));

        // クリペイムネス(G5・腕の盾の前衛。② 大型の地上を走る型の 2 体目、2026-11-15 段 1)。数値は全部【仮・Claude 11-15】:
        // 「遅めで硬い盾持ちの前衛。スクティムネスほど重い戦車ではなく、小回りが利く」→ 体力 70(ファルキムネス 50・スクティムネス 150)・
        // 防御 16 + 防具強度 4(14 / 20)・速さ 0.24(ヴェナムネス 0.30)・ノックバック耐性 0.8。前からの矢・剣を弾く盾は次の段の技。
        // 当たりは胴の芯 1.4 × 2.6(盾の腕・脚・尾ははみ出す。全体 4.45 × 3.43 × 8.58)。描画倍率 2.0 は描画で掛ける(体格は 1)。
        m.put("clipeimnes", stat(s -> {
            s.maxHealth = 70.0; s.attackDamage = 12.0; s.armor = 16.0; s.armorToughness = 4.0; s.movementSpeed = 0.24;
            s.knockbackResistance = 0.8; s.attackKnockback = 1.0; s.followRange = 32.0; s.stepHeight = 1.5;
            s.scale = 1.0;
            s.width = 1.4; s.height = 2.6; s.eggFit = 5.61; s.xpReward = 35;
        }));

        // サルティムネス(G5・跳ぶ型・待ち伏せ。「跳ぶ型」の 1 体目、2026-11-14 段 1 = 歩く・伏せる・跳ぶまで)。数値は全部【仮・Claude 11-14】:
        // 「伏せて待ち、一跳びで詰める」→ 体力 30・防御 4(脆い。跳ぶ前に見つければ勝てる)・地上の速さ 0.20(這い歩き。地面で約 0.045 ブロック/tick)。
        // 当たりは胴の芯 1.8 × 1.0(伏せた姿。脚・翅・頭・腹ははみ出す。全体 4.5 × 2.3 × 7.2)。描画倍率 2.5 は描画で掛ける。段差 1.0(より高い段差・柵・塀は跳ぶ)。攻撃は次の段。
        m.put("saltimnes", stat(s -> {
            s.maxHealth = 30.0; s.attackDamage = 9.0; s.armor = 4.0; s.armorToughness = 0.0; s.movementSpeed = 0.20;
            s.knockbackResistance = 0.6; s.followRange = 32.0; s.stepHeight = 1.0;
            s.scale = 1.0;
            s.width = 1.8; s.height = 1.0; s.eggFit = 5.75; s.xpReward = 30;
        }));

        // ピリムネス(G5・地上の遠距離。トカゲ型の 1 体目、2026-11-15 段 1)。数値は全部【仮・Claude 11-15】:
        // 「棘で足を止めて口で仕留める撃ち手」→ 体力 30(ヴェナムネス 34)・防御 4・速さ 0.30(走っては止まるので平均は遅い)・追う距離 40。
        // 段差 1(2 段は跳び上がる。跳ぶ力は PilimnesEntity#JUMP_STRENGTH)。当たりは胴の芯 1.3 × 1.6(首・尾・横へ張った脚・背の棘ははみ出す。
        // 全体 3.7 × 3.4 × 9.5)。描画倍率 2.0 は描画で掛ける(体格は 1)。攻撃は能力の段。
        m.put("pilimnes", stat(s -> {
            s.maxHealth = 30.0; s.attackDamage = 6.0; s.armor = 4.0; s.armorToughness = 0.0; s.movementSpeed = 0.30;
            s.knockbackResistance = 0.5; s.followRange = 48.0; s.stepHeight = 1.0;   // 11-16 弾きの射程 44 に合わせて 40 → 48
            s.scale = 1.0;
            s.width = 1.3; s.height = 1.6; s.eggFit = 5.17; s.xpReward = 30;
        }));

        // ヴェシカリア(G4・自爆特攻。小さな地這いの虫の型、2026-11-15 段 1 = うろつく・急ぐまで。突撃・破裂・連鎖は次の段)。数値は全部【仮・Claude 11-15】:
        // 袋が重いぶんグレガリアよりもたつく。急ぎは walk(1 倍で地面 0.03)を 2.5 倍で流せる 約 0.075 ブロック/tick で止める
        // → 地面の速さ ≈ 2.16 × 速さ² から 0.186(テストクライアントで測って合わせる)。体力 6・防御 0(破裂の袋は脆い)。
        // 段差 1.0。当たりは腹の袋の芯 0.8 × 0.6(脚・前脚ははみ出す)。破裂のダメージは次の段(攻撃の属性は使わない)。
        m.put("vesicaria", stat(s -> {
            s.maxHealth = 6.0; s.attackDamage = 2.0; s.armor = 0.0; s.armorToughness = 0.0; s.movementSpeed = 0.186;
            s.knockbackResistance = 0.0; s.followRange = 24.0; s.stepHeight = 1.0;
            s.scale = 1.0;
            s.width = 0.8; s.height = 0.6; s.eggFit = 2.65; s.xpReward = 2;
        }));

        // グレガリア(G4・大群の先触れ。小さな地這いの虫の型の 1 体目、2026-11-15 段 1)。数値は全部【仮・Claude 11-15】:
        // 「一体はすぐ死ぬ」→ 体力 4(アルボラ 8)・防御 0。急ぎの地面の速さ 約 0.24 ブロック/tick(プレイヤーの歩き 0.216 より少し速い。11-15 ユーザー)。
        // 段 1 は walk のまま 0.285(地面 0.175)に下げていたが、11-15 に走り run ができたので戻した。
        // → 地面の速さ ≈ 2.16 × 速さ² から 0.33。段差 1.0(1 ブロックは這い上がる・柵と 2 段の壁は越えない)。
        // 当たりは胴の芯 0.7 × 0.45(脚・触角ははみ出す)。攻撃(足首を噛む)は次の段。
        m.put("gregaria", stat(s -> {
            s.maxHealth = 4.0; s.attackDamage = 2.0; s.armor = 0.0; s.armorToughness = 0.0; s.movementSpeed = 0.33;
            s.knockbackResistance = 0.0; s.followRange = 24.0; s.stepHeight = 1.0;
            s.scale = 1.0;
            s.width = 0.7; s.height = 0.45; s.eggFit = 2.0; s.xpReward = 1;
        }));

        // ストロビラ(G4・軽い投手。小さな地這いの虫の型の 2 体目、2026-11-15 段 1)。数値は全部【仮・Claude 11-15】:
        // 「G4 の中で少し丈夫・詰められると弱い」→ 体力 12(グレガリア 4・アルボラ 8)・防御 4(背板)。攻撃 2 は小さな大顎(技はまだ)。
        // 速さ 0.19: walk(1 倍で約 0.03 ブロック/tick)を 2.5 倍まで = 急ぎ 約 0.075(地面の速さ ≈ 2.16 × 速さ²)。歩幅が短いので遅い。
        // 当たりは胴と背板の芯 0.95 × 1.2(脚・触角・尾ははみ出す。全体 3.6 × 2.8 × 5.5)。段差 1.0。
        m.put("strobila", stat(s -> {
            s.maxHealth = 12.0; s.attackDamage = 2.0; s.armor = 4.0; s.armorToughness = 0.0; s.movementSpeed = 0.36;   // 急ぎで床 約 0.28 = アルボラの床の速さ(11-15 段 2。前は 0.19)
            s.knockbackResistance = 0.2; s.followRange = 48.0; s.stepHeight = 1.0;   // 気付く距離 32 → 48(射程 32 より先で気付いて寄る。11-16)
            s.scale = 1.0;
            s.width = 0.95; s.height = 1.2; s.eggFit = 5.4; s.xpReward = 3;
        }));

        // スポロフィル — 支援役。体はフィロセンスの流用で、頭が花ではなくキノコの傘。
        // 自分では攻撃せず(attackDamage を持たない)、半径 32 のバフ/デバフ/胞子濃度上昇で戦う
        // (SporophyllAura)。居座られると厄介なので体力は高め。
        m.put("sporophyll", stat(s -> {
            s.maxHealth = 70.0; s.movementSpeed = 0.18; s.armor = 5.0; s.armorToughness = 2.0;
            s.knockbackResistance = 0.5; s.followRange = 32.0; s.stepHeight = 1.5;
            // 「今のフィロセンスくらいの大きさ」(ユーザー指定 2026-09-14)。素のモデルが
            // 3.59 x 5.42 x 14.19 なので 1.35 倍で 4.85 x 7.32 x 19.16 ≒ 旧フィロセンス(4.67 x 7.26 x 19.89)。
            s.scale = 1.35;
            // 当たり判定は胴体に合わせる(首と尾ははみ出す)。実効 2.70 x 4.86。
            // eggFit は待機アニメーション中の画面上の差し渡しの実測値(2026-09-15 に測り直し)。
            s.width = 2.0; s.height = 3.6; s.eggFit = 13.78; s.xpReward = 14;
        }));

        // ヴェロフィル(G4・空の爆撃。V 字の群れ、2026-11-15 段 1 = 飛ぶ・群れ・止まり木まで。爆撃は次の段)。数値は【仮・Claude】、
        // 巡航 0.7 ブロック/tick と当たりの作り(胴の芯 + 翼の叩ける部位)はユーザー決定(11-15)。速さの本体は entity/flyer の FlightBody、
        // flyingSpeed は巡航の速さとしてそこから読む。体力 20 = 弓で落とせる(溜めきりの矢 3 本ほど)。
        // 当たりの芯は胴だけ(幅 1.2 × 高さ 0.8)。翼(翼幅 5.8)は叩ける部位(VelophyllEntity の WING_*)で、地形とはぶつからない。
        m.put("velophyll", stat(s -> {
            s.maxHealth = 20.0; s.attackDamage = 4.0;
            s.movementSpeed = 0.12; s.flyingSpeed = 0.7; s.armor = 0.0;
            s.knockbackResistance = 0.0; s.followRange = 48.0; s.stepHeight = 1.0;
            s.width = 1.2; s.height = 0.8; s.eggFit = 6.3; s.xpReward = 10;
        }));

        // セミナリア(G4・空の斥候兼種まき、2026-11-15 段 1)。値はどれも【仮】。攻撃しない(attackDamage 0)。
        // 当たりの芯は胸だけ(0.8 × 1.0)。翅(幅 4.7)と腹の松かさは叩ける部位(SeminariaEntity の PARTS)。射落とせば飛び地を防げるので体力は低め。
        m.put("seminaria", stat(s -> {
            s.maxHealth = 8.0; s.attackDamage = 0.0;
            s.movementSpeed = 0.1; s.flyingSpeed = 0.3; s.armor = 0.0;
            s.knockbackResistance = 0.0; s.followRange = 32.0; s.stepHeight = 1.0;
            s.width = 0.8; s.height = 1.0; s.eggFit = 4.95; s.xpReward = 5;
        }));

        // ペディキュラ — 上空を漂うだけの巨大な胞子の塊(2026-09-23)。攻撃はしない。
        // 体力 0.1・防御 0 はユーザー指定。体力は Minecraft の下限(1 = ハート半分)に丸められるが、
        // どの一撃でも落ちるのは同じ。歩かないので movementSpeed は 0(亜種の抽選からも外れる)。
        // 当たり判定は main の大きな立方体がどの向きに回っても収まる立方体(外接球の半径 1.73 → 一辺 3.46)。
        // 中心の高さ 1.8 がモデルの回転の中心と一致する。sub / sub2 / sub3 は判定からはみ出す。
        // eggFit は sub まで含めた外接球の直径(半径 2.67)。回りながら描くので向きを選ばない値にしてある。
        // 2026-11-15 段 1(漂う型。形は作り直し案 r6、上下のある塊)で戻した。値は旧版のまま(当たり 3.6 角の中心 1.8 = r6 の main の付け根)。
        // 動き(風任せ・高さの帯)は PediculaEntity・entity/flyer/BalloonDrift の【仮】の値。段 1 は自然には湧かない。
        m.put("pedicula", stat(s -> {
            s.maxHealth = 0.1; s.armor = 0.0; s.movementSpeed = 0.0; s.knockbackResistance = 0.0;
            s.width = 3.6; s.height = 3.6; s.eggFit = 6.85; s.xpReward = 5;
        }));

        // トカゲ 3 種の体格(scale)は 2026-10-10 のユーザー指定「2〜2.5 倍、だいたい 1 ブロックに。小さすぎて見当たらない」。
        // 倍率は仮決め: 胴の長さが 1 ブロックに近いほう(ヒロノムス 0.42→1.05 で 2.5、ペトロラコサウルス 0.63→1.26・イアンタサウルス 0.79→1.58 で 2)。
        // 判定 width/height は体格 1 のモデルに合わせた素の値のまま(SCALE が掛かる)。eggFit は SCALE 込みなので旧値 × 体格。
        m.put("petrolacosaurus", stat(s -> {
            s.maxHealth = 10.0; s.attackDamage = 4.0; s.movementSpeed = 0.34; s.armor = 1.0;
            s.knockbackResistance = 0.0; s.followRange = 18.0; s.stepHeight = 1.0;
            s.scale = 2.0;
            s.width = 0.6; s.height = 0.4; s.xpReward = 3;
            s.eggFit = 3.32;
        }));

        m.put("ianthasaurus", stat(s -> {
            s.maxHealth = 18.0; s.attackDamage = 5.0; s.movementSpeed = 0.24; s.armor = 3.0;
            s.knockbackResistance = 0.2; s.followRange = 18.0; s.stepHeight = 1.0;
            s.scale = 2.0;
            s.width = 0.7; s.height = 0.6; s.xpReward = 4;
            s.eggFit = 2.92;
        }));

        m.put("hylonomus", stat(s -> {
            s.maxHealth = 6.0; s.attackDamage = 3.0; s.movementSpeed = 0.3; s.armor = 0.0;
            s.knockbackResistance = 0.0; s.followRange = 14.0; s.stepHeight = 1.0;
            s.scale = 2.0;   // 11-16 ユーザー決定: 2.5 → 2(画素が 1/16 ブロックにそろう)。全長 2.26・高さ 0.58
            s.width = 0.4; s.height = 0.3; s.xpReward = 2;
            s.eggFit = 1.42;   // 体格 2 に合わせて 1.78 × 2 / 2.5
        }));

        m.put("rhizodus", stat(s -> {
            s.maxHealth = 60.0; s.attackDamage = 4.0; s.movementSpeed = 0.6; s.armor = 4.0;
            s.knockbackResistance = 0.6; s.followRange = 16.0;
            s.width = 1.2; s.height = 1.2; s.xpReward = 5;
            s.scaleMin = 0.8; s.scaleMax = 1.4;
            s.eggFit = 2.0;
        }));

        m.put("xenacanthus", stat(s -> {
            s.maxHealth = 14.0; s.movementSpeed = 1.0; s.followRange = 16.0;
            s.width = 0.8; s.height = 0.7; s.xpReward = 3;
            s.scaleMin = 0.85; s.scaleMax = 1.25;
            s.eggFit = 1.45;
        }));

        m.put("orthacanthus", stat(s -> {
            s.maxHealth = 30.0; s.attackDamage = 7.0; s.movementSpeed = 1.15; s.armor = 3.0;
            s.knockbackResistance = 0.3; s.followRange = 26.0; s.attackKnockback = 0.0;
            s.width = 1.0; s.height = 0.9; s.xpReward = 8;
            s.scaleMin = 0.85; s.scaleMax = 1.3;
            s.eggFit = 1.95;
        }));

        m.put("megarachne", stat(s -> {
            s.maxHealth = 22.0; s.attackDamage = 6.0; s.movementSpeed = 0.9; s.armor = 6.0;
            s.armorToughness = 2.0; s.knockbackResistance = 0.4; s.followRange = 22.0;
            s.stepHeight = 1.0; s.width = 1.0; s.height = 0.7; s.xpReward = 6;
            s.scaleMin = 0.85; s.scaleMax = 1.3;
            s.eggFit = 2.35;
        }));

        // G2・G3 の新しい 8 種(2026-10-22)。数値はみな仮決め(大きさはユーザー了承「仮の大きさのままでよい」)。
        // eggFit は生成器のモデルの待機を EggFit の式で測った値。水生の movementSpeed は水中で × 0.02(SmoothSwimmingMoveControl)。
        m.put("diplocaulus", stat(s -> {
            s.maxHealth = 10.0; s.movementSpeed = 0.9; s.followRange = 12.0;
            s.width = 0.9; s.height = 0.3; s.xpReward = 2;
            s.scaleMin = 0.85; s.scaleMax = 1.2;
            s.eggFit = 1.02;
        }));
        m.put("lungfish", stat(s -> {
            s.maxHealth = 12.0; s.movementSpeed = 0.6; s.followRange = 12.0;
            s.width = 0.6; s.height = 0.4; s.xpReward = 2;
            s.scaleMin = 0.85; s.scaleMax = 1.25;
            s.eggFit = 1.07;
        }));
        m.put("hibbertopterus", stat(s -> {
            s.maxHealth = 30.0; s.movementSpeed = 0.6; s.armor = 4.0; s.knockbackResistance = 0.4;
            s.followRange = 14.0; s.stepHeight = 1.0;
            s.width = 1.3; s.height = 0.5; s.xpReward = 4;
            s.scaleMin = 0.9; s.scaleMax = 1.2;
            s.eggFit = 1.83;
        }));
        m.put("giant_snail", stat(s -> {
            s.maxHealth = 14.0; s.movementSpeed = 0.05; s.armor = 6.0; s.knockbackResistance = 0.5;
            s.followRange = 10.0; s.stepHeight = 1.0;
            s.width = 0.7; s.height = 0.9; s.xpReward = 1;
            s.scaleMin = 0.8; s.scaleMax = 1.2;
            s.eggFit = 1.17;
        }));
        m.put("stenodictya_nymph", stat(s -> {
            s.maxHealth = 6.0; s.movementSpeed = 0.22; s.followRange = 10.0; s.stepHeight = 1.0;
            s.width = 0.6; s.height = 0.45; s.xpReward = 1;
            s.scaleMin = 0.85; s.scaleMax = 1.15;
            s.eggFit = 1.03;
        }));
        m.put("eryops", stat(s -> {
            s.maxHealth = 32.0; s.attackDamage = 6.0; s.movementSpeed = 0.1; s.armor = 2.0;
            s.knockbackResistance = 0.5; s.followRange = 16.0; s.stepHeight = 1.0;
            s.width = 1.2; s.height = 0.5; s.xpReward = 6;
            s.scaleMin = 0.9; s.scaleMax = 1.2;
            s.eggFit = 1.47;
        }));
        m.put("pulmonoscorpius", stat(s -> {
            s.maxHealth = 20.0; s.attackDamage = 5.0; s.movementSpeed = 0.28; s.armor = 4.0;
            s.knockbackResistance = 0.3; s.followRange = 16.0; s.stepHeight = 1.0;
            s.width = 1.0; s.height = 0.6; s.xpReward = 5;
            s.scaleMin = 0.85; s.scaleMax = 1.2;
            s.eggFit = 1.98;
        }));
        m.put("crassigyrinus", stat(s -> {
            s.maxHealth = 26.0; s.attackDamage = 6.0; s.movementSpeed = 1.1; s.armor = 2.0;
            s.knockbackResistance = 0.3; s.followRange = 20.0;
            s.width = 1.0; s.height = 0.45; s.xpReward = 7;
            s.scaleMin = 0.85; s.scaleMax = 1.2;
            s.eggFit = 1.65;
        }));

        m.put("overgrown_nexus", stat(s -> {
            s.maxHealth = 400.0; s.movementSpeed = 0.0; s.knockbackResistance = 1.0; s.armor = 10.0;
            s.attackDamage = 8.0; s.attackKnockback = 1.0; s.followRange = 40.0; s.scale = 3.0;
            s.width = 3.0; s.height = 6.5; s.xpReward = 0;
            s.eggFit = 29.18;
        }));

        // 「過繁茂した(MOB名)」— バニラ属性への上書き分のみ(牛/羊/豚は ATTACK_DAMAGE を明示追加)。
        // 2026-11-08 G1 段階 0: チビゾンビ 0.345・クモ 0.3 はバニラと同じ速さ(11-05 ユーザー)。ほかは旧のまま(元 +0.03〜0.05)。
        // グループ1(媒介種)は<b>もとの Mob より足が速い</b>(ユーザー指定 2026-09-21)。
        // 寄生されて痛みも疲れも感じなくなった、という想定で一律 +0.05。
        // それ以外(体力・攻撃力・防御)はバニラのまま —— 変えるのは足だけ。
        m.put("parasitized_cow", stat(s -> { s.attackDamage = 3.0; s.movementSpeed = 0.25; s.width = 0.9; s.height = 1.4; s.eggFit = 3.6; }));
        m.put("parasitized_sheep", stat(s -> { s.movementSpeed = 0.28; s.attackDamage = 3.0; s.width = 0.9; s.height = 1.3; s.eggFit = 2.6; }));
        m.put("parasitized_pig", stat(s -> { s.movementSpeed = 0.30; s.attackDamage = 3.0; s.width = 0.9; s.height = 0.9; s.eggFit = 3.7; }));
        m.put("parasitized_zombie", stat(s -> { s.movementSpeed = 0.28; s.width = 0.6; s.height = 1.95; s.eggFit = 3.25; }));
        m.put("parasitized_villager", stat(s -> { s.movementSpeed = 0.28; s.width = 0.6; s.height = 1.95; s.eggFit = 3.5; }));
        m.put("parasitized_chicken", stat(s -> { s.attackDamage = 2.0; s.movementSpeed = 0.3; s.width = 0.4; s.height = 0.7; s.eggFit = 1.4; }));
        m.put("parasitized_skeleton", stat(s -> { s.movementSpeed = 0.3; s.width = 0.6; s.height = 1.99; s.eggFit = 2.25; }));
        m.put("parasitized_enderman", stat(s -> { s.movementSpeed = 0.3; s.width = 0.6; s.height = 2.9; s.eggFit = 3.6; }));
        m.put("parasitized_baby_zombie", stat(s -> { s.movementSpeed = 0.345; s.width = 0.3; s.height = 0.975; s.eggFit = 2.2; }));
        m.put("parasitized_creeper", stat(s -> { s.movementSpeed = 0.30; s.width = 0.6; s.height = 1.7; s.eggFit = 3.3; }));
        m.put("parasitized_spider", stat(s -> { s.movementSpeed = 0.3; s.width = 1.4; s.height = 0.9; s.eggFit = 2.95; }));
        // ネザーの媒介種(2026-10-09)。足は上と同じく元より +0.05(仮)。ガスト・ブレイズの movementSpeed は行き先へ足す加速
        // (バニラのガストは 0.1)。
        m.put("parasitized_piglin", stat(s -> { s.movementSpeed = 0.4; s.width = 0.6; s.height = 1.95; s.eggFit = 2.5; }));
        m.put("parasitized_piglin_brute", stat(s -> { s.movementSpeed = 0.4; s.width = 0.6; s.height = 1.95; s.eggFit = 2.4; }));
        m.put("parasitized_zombified_piglin", stat(s -> { s.movementSpeed = 0.28; s.width = 0.6; s.height = 1.95; s.eggFit = 2.9; }));
        m.put("parasitized_wither_skeleton", stat(s -> { s.movementSpeed = 0.3; s.width = 0.7; s.height = 2.4; s.eggFit = 3.0; }));
        m.put("parasitized_hoglin", stat(s -> { s.movementSpeed = 0.35; s.width = 1.3964844; s.height = 1.4; s.eggFit = 3.6; }));
        m.put("parasitized_zoglin", stat(s -> { s.movementSpeed = 0.35; s.width = 1.3964844; s.height = 1.4; s.eggFit = 3.1; }));
        m.put("parasitized_strider", stat(s -> { s.movementSpeed = 0.225; s.width = 0.9; s.height = 1.7; s.eggFit = 3.4; }));
        m.put("parasitized_ghast", stat(s -> {
            s.movementSpeed = 0.1; s.width = 4.0; s.height = 4.0; s.eggFit = 13.0;
        }));
        m.put("parasitized_blaze", stat(s -> {
            s.movementSpeed = 0.06; s.width = 0.6; s.height = 1.8; s.eggFit = 3.6;
        }));
        // 2026-10-10: アイアンゴーレム・ウォーデン。自然には湧かない(ユーザー指定: 胞子感染で死んだ個体の死体からだけ)。
        m.put("parasitized_iron_golem", stat(s -> { s.movementSpeed = 0.25; // 11-09 G1 段階 0: バニラと同じ 0.25(歩きの ref 0.135 はこの速さから)
            s.width = 1.4; s.height = 2.7; s.eggFit = 3.4; }));
        m.put("parasitized_warden", stat(s -> { s.movementSpeed = 0.3; // 11-09 G1 段階 0: 歩きの ref 0.194 はこの速さから
            s.width = 0.9; s.height = 2.9; s.eggFit = 4.25; }));
        // G1 1 段目(2026-11-12、第 1 段): 発達した寄生された○○ 11 種。卵とコマンドだけ(11-09)。速さ・大きさは種ごとの仕様書【仮】。体力・攻撃は entity/g1/G1Developed
        m.put("developed_parasitized_zombie", stat(s -> { s.movementSpeed = 0.25; s.width = 0.6; s.height = 1.95; s.eggFit = 3.15; }));
        m.put("developed_parasitized_baby_zombie", stat(s -> { s.movementSpeed = 0.36; s.width = 0.6; s.height = 0.8; s.eggFit = 2.05; }));
        m.put("developed_parasitized_villager", stat(s -> { s.movementSpeed = 0.28; s.width = 0.8; s.height = 1.9; s.eggFit = 3.6; }));
        m.put("developed_parasitized_cow", stat(s -> { s.movementSpeed = 0.25; s.width = 1.0; s.height = 1.5; s.eggFit = 4.0; }));
        m.put("developed_parasitized_pig", stat(s -> { s.movementSpeed = 0.3; s.width = 1.0; s.height = 1.0; s.eggFit = 2.75; }));
        m.put("developed_parasitized_sheep", stat(s -> { s.movementSpeed = 0.28; s.width = 0.9; s.height = 1.7; s.eggFit = 2.85; }));
        m.put("developed_parasitized_chicken", stat(s -> { s.movementSpeed = 0.3; s.width = 0.5; s.height = 1.0; s.eggFit = 1.8; }));
        m.put("developed_parasitized_creeper", stat(s -> { s.movementSpeed = 0.3; s.width = 0.6; s.height = 1.7; s.eggFit = 3.05; }));
        m.put("developed_parasitized_skeleton", stat(s -> { s.movementSpeed = 0.28; s.width = 0.6; s.height = 1.99; s.eggFit = 3.0; }));
        m.put("developed_parasitized_spider", stat(s -> { s.movementSpeed = 0.3; s.width = 1.4; s.height = 1.2; s.eggFit = 3.5; }));
        m.put("developed_parasitized_enderman", stat(s -> { s.movementSpeed = 0.3; s.width = 0.6; s.height = 3.0; s.eggFit = 3.45; }));
        m.put("rooted_parasitized_zombie", stat(s -> { s.movementSpeed = 0.0; s.width = 1.0; s.height = 4.4; s.eggFit = 5.55; })); // 根付き(2026-11-13)
        m.put("rooted_parasitized_baby_zombie", stat(s -> { s.movementSpeed = 0.0; s.width = 0.8; s.height = 2.5; s.eggFit = 3.2; }));
        m.put("rooted_parasitized_villager", stat(s -> { s.movementSpeed = 0.0; s.width = 1.0; s.height = 4.6; s.eggFit = 5.6; }));
        m.put("rooted_parasitized_enderman", stat(s -> { s.movementSpeed = 0.0; s.width = 1.0; s.height = 4.6; s.eggFit = 5.55; }));
        m.put("rooted_parasitized_skeleton", stat(s -> { s.movementSpeed = 0.0; s.width = 1.0; s.height = 5.2; s.eggFit = 5.6; }));
        m.put("root_guardian", stat(s -> { s.movementSpeed = 0.25; s.width = 1.3; s.height = 1.9; s.eggFit = 2.95; }));
        m.put("rooted_parasitized_cow", stat(s -> { s.movementSpeed = 0.0; s.width = 1.4; s.height = 1.6; s.eggFit = 4.95; }));
        m.put("rooted_parasitized_pig", stat(s -> { s.movementSpeed = 0.0; s.width = 1.2; s.height = 1.2; s.eggFit = 3.05; }));
        m.put("rooted_parasitized_sheep", stat(s -> { s.movementSpeed = 0.0; s.width = 1.3; s.height = 1.6; s.eggFit = 5.15; }));
        m.put("rooted_parasitized_chicken", stat(s -> { s.movementSpeed = 0.0; s.width = 0.7; s.height = 0.9; s.eggFit = 2.25; }));
        m.put("rooted_parasitized_spider", stat(s -> { s.movementSpeed = 0.0; s.width = 1.0; s.height = 1.8; s.eggFit = 5.25; }));
        m.put("rooted_parasitized_creeper", stat(s -> { s.movementSpeed = 0.0; s.width = 0.8; s.height = 1.8; s.eggFit = 3.2; }));
        m.put("nursery_parasitized_zombie", stat(s -> { s.movementSpeed = 0.0; s.width = 1.2; s.height = 1.0; s.eggFit = 3.8; }));
        m.put("nursery_parasitized_baby_zombie", stat(s -> { s.movementSpeed = 0.0; s.width = 0.9; s.height = 0.7; s.eggFit = 2.65; }));
        m.put("nursery_parasitized_villager", stat(s -> { s.movementSpeed = 0.0; s.width = 1.2; s.height = 1.1; s.eggFit = 3.9; }));
        m.put("nursery_parasitized_skeleton", stat(s -> { s.movementSpeed = 0.0; s.width = 1.2; s.height = 1.0; s.eggFit = 3.8; }));
        m.put("nursery_parasitized_enderman", stat(s -> { s.movementSpeed = 0.0; s.width = 1.2; s.height = 1.0; s.eggFit = 4.7; }));
        m.put("nursery_parasitized_cow", stat(s -> { s.movementSpeed = 0.0; s.width = 1.3; s.height = 1.2; s.eggFit = 4.1; }));
        m.put("nursery_parasitized_pig", stat(s -> { s.movementSpeed = 0.0; s.width = 1.1; s.height = 1.0; s.eggFit = 3.25; }));
        m.put("nursery_parasitized_sheep", stat(s -> { s.movementSpeed = 0.0; s.width = 1.2; s.height = 1.1; s.eggFit = 3.9; }));
        m.put("nursery_parasitized_chicken", stat(s -> { s.movementSpeed = 0.0; s.width = 0.8; s.height = 0.6; s.eggFit = 2.45; }));
        m.put("nursery_parasitized_spider", stat(s -> { s.movementSpeed = 0.0; s.width = 1.2; s.height = 1.0; s.eggFit = 3.85; }));
        m.put("nursery_parasitized_creeper", stat(s -> { s.movementSpeed = 0.0; s.width = 1.2; s.height = 1.0; s.eggFit = 4.05; }));
        // G1 の作り直し 第 1 段(2026-10-22): 進化体 8 種。G1 の一生(司令塔の「進化」)からだけ現れる。数値は仮決め
        m.put("vinearm_zombie", stat(s -> { s.maxHealth = 26.0; s.movementSpeed = 0.28; s.width = 0.6; s.height = 1.95; s.eggFit = 2.07; }));
        m.put("hollow_villager", stat(s -> { s.maxHealth = 24.0; s.attackDamage = 5.0; s.movementSpeed = 0.29; s.width = 0.6; s.height = 1.95; s.eggFit = 2.07; }));
        m.put("sporesac_cow", stat(s -> { s.maxHealth = 16.0; s.attackDamage = 3.0; s.movementSpeed = 0.23; s.width = 0.9; s.height = 1.4; s.eggFit = 2.1; }));
        m.put("podded_creeper", stat(s -> { s.maxHealth = 24.0; s.movementSpeed = 0.28; s.width = 0.6; s.height = 1.7; s.eggFit = 2.17; }));
        m.put("vine_executioner", stat(s -> { s.maxHealth = 60.0; s.attackDamage = 8.0; s.movementSpeed = 0.28; s.armor = 4.0; s.scale = 1.0; s.width = 1.3; s.height = 3.0; s.eggFit = 3.2; }));   // 10-23 夜: ユーザーの bbmodel(全体 3.16 ブロック)のまま、当たり判定も大きく(ユーザー決定)
        m.put("root_striker", stat(s -> { s.maxHealth = 60.0; s.attackDamage = 8.0; s.movementSpeed = 0.28; s.armor = 4.0; s.width = 0.7; s.height = 2.2; s.eggFit = 2.3; }));
        m.put("walking_sporangium", stat(s -> { s.maxHealth = 40.0; s.attackDamage = 6.0; s.movementSpeed = 0.23; s.scale = 1.2; s.width = 1.3; s.height = 1.85; s.eggFit = 3.6; }));   // 10-23 仕上げ: 一回り大きく 1.2 倍(ユーザー了承)
        m.put("burst_pod", stat(s -> { s.maxHealth = 24.0; s.attackDamage = 3.0; s.movementSpeed = 0.27; s.width = 0.8; s.height = 1.6; s.eggFit = 2.4; }));

        return new HashMap<>(m);
    }
}
