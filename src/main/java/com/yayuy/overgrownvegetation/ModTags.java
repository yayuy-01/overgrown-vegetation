package com.yayuy.overgrownvegetation;

import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;

public final class ModTags {

    /**
     * 攻城戦ボス(ドルガ=アーケロウス・ティタニカ)の攻撃のダメージタイプ
     * ({@code siege_beam} = 花のビーム・触手ビーム・小ビームの乱射、{@code siege_bomb} = 胞子爆弾)。
     *
     * <p>「ボスの攻撃で死んだ Mob の場所には必ず汚染シダが生える」という規則のためだけに在る
     * ({@link com.yayuy.overgrownvegetation.siege.SiegeKillHandler})。砲台の砲弾 {@code siege_shell} は
     * プレイヤー側の攻撃なので<b>入れない</b>。
     */
    public static final TagKey<DamageType> SIEGE_BOSS_ATTACK =
            TagKey.create(net.minecraft.core.registries.Registries.DAMAGE_TYPE,
                    OvergrownVegetation.id("siege_boss_attack"));

    /**
     * 既定(道具・武器・防具)のほかに「装備の胞子汚染」
     * ({@link com.yayuy.overgrownvegetation.spore.GearContamination})を受けるアイテム。
     * データパックで追加できるよう空のタグを切ってある。
     */
    public static final TagKey<net.minecraft.world.item.Item> SPORE_CONTAMINABLE =
            net.minecraft.tags.ItemTags.create(OvergrownVegetation.id("spore_contaminable"));

    /**
     * 逆に「装備の胞子汚染」を<b>絶対に受けない</b>アイテム。{@link #SPORE_CONTAMINABLE} より優先される。
     */
    public static final TagKey<net.minecraft.world.item.Item> SPORE_CONTAMINATION_IMMUNE =
            net.minecraft.tags.ItemTags.create(OvergrownVegetation.id("spore_contamination_immune"));

    /**
     * <b>汚染特効</b>の武器。このタグのアイテムで殴ると、グループ1〜10 の Mob に入るダメージが
     * {@code overgrowthBaneMultiplier}(既定 1.5)倍になる
     * ({@link com.yayuy.overgrownvegetation.phase.OvergrowthBaneDamage})。
     *
     * <p>既定の中身はクロロフィライトとクロロフィライト合金の剣・斧
     * ({@code data/overgrown_vegetation/tags/item/overgrowth_bane.json})。
     * 対象を足したい/外したいときは Java ではなくこの json を触る。
     */
    public static final TagKey<net.minecraft.world.item.Item> OVERGROWTH_BANE =
            net.minecraft.tags.ItemTags.create(OvergrownVegetation.id("overgrowth_bane"));

    /**
     * 武器を持っている間、当たり判定の無いブロックを透かして奥の Mob を狙う処理
     * ({@link com.yayuy.overgrownvegetation.client.SwingThroughFoliage})で、<b>透かさない例外</b>にする
     * ブロック。
     *
     * <p>あの処理は「当たり判定が無いブロック = 体を止めないブロック」を一律に透かす
     * (下草・花・蔦・苔など、この MOD が増やす草も含めて取りこぼさないため)。ただしレバー・ボタン・
     * 看板・レール のように当たり判定が無くても<b>触りたい</b>ブロックまで透けてしまうと、
     * 剣を持ったまま基地の装置を操作できなくなる。既定ではそれらをこのタグに入れて除外している
     * ({@code data/overgrown_vegetation/tags/block/not_swing_through.json})。
     *
     * <p>逆に「この草にも判定を残したい」ときもここへ足せばよい。
     */
    public static final TagKey<Block> NOT_SWING_THROUGH = BlockTags.create(OvergrownVegetation.id("not_swing_through"));

    /**
     * 既定(剣・斧・トライデント・メイス)のほかに、上記の「草を透かして狙う」対象にしたいアイテム。
     * データパックで追加できるよう空のタグを切ってある。
     *
     * @see com.yayuy.overgrownvegetation.client.SwingThroughFoliage
     */
    public static final TagKey<net.minecraft.world.item.Item> SWINGS_THROUGH_FOLIAGE =
            net.minecraft.tags.ItemTags.create(OvergrownVegetation.id("swings_through_foliage"));

    /**
     * <b>汚染耐性</b>の防具。樹液結晶を打ち込んだ({@code sap_crystal_treated})状態で身に着けていると、
     * グループ1〜10 の Mob から受けるダメージが軽くなる
     * ({@link com.yayuy.overgrownvegetation.phase.OvergrowthWardDamage})。
     *
     * <p>既定の中身はクロロフィライトとクロロフィライト合金の防具 4 部位ずつ
     * ({@code data/overgrown_vegetation/tags/item/overgrowth_ward.json})。
     * タグに入っているだけでは効かない — <b>鍛冶台で樹液結晶を打ち込んであることが条件</b>。
     */
    public static final TagKey<net.minecraft.world.item.Item> OVERGROWTH_WARD =
            net.minecraft.tags.ItemTags.create(OvergrownVegetation.id("overgrowth_ward"));

    /**
     * <b>素材そのものに汚染耐性がある</b>防具。身に着けているだけで、グループ1〜10 の Mob から受ける
     * ダメージが 1 部位につき {@code overgrowthResistantPerPiece}(既定 10%)減る
     * ({@link com.yayuy.overgrownvegetation.phase.OvergrowthWardDamage})。
     *
     * <p>{@link #OVERGROWTH_WARD} と違い<b>樹液結晶の打ち込みは要らない</b>。
     * 既定の中身は強化クロロフィライト合金装備の 4 部位
     * ({@code data/overgrown_vegetation/tags/item/overgrowth_resistant.json})。
     */
    public static final TagKey<net.minecraft.world.item.Item> OVERGROWTH_RESISTANT =
            net.minecraft.tags.ItemTags.create(OvergrownVegetation.id("overgrowth_resistant"));

    /** 既に汚染済みのブロック。二重に汚染されないようにするためのタグ。 */
    public static final TagKey<Block> CORRUPTED = BlockTags.create(OvergrownVegetation.id("corrupted"));

    /** 汚染を受け付けないブロック(岩盤・ポータルなど)。データパックで追加可能。 */

    /**
     * <b>「覆われて動かなくなったブロック」に差し替えない</b>ブロック(2026-09-22)。データパックで追加可能。
     *
     * <p>他 MOD の機械は覆われると {@code overgrown_husk} へ差し替わって機能を失うが、
     * 壊れると困るものをここで逃がす。既定ではスポナー・試練のスポナー・宝物庫 ——
     * 構造物の仕掛けを黙って無効化してしまうため。
     *
     * <p>この MOD 自身のブロックはタグに関係なく除外される(名前空間で弾いている)。
     */
    public static final TagKey<Block> HUSK_IMMUNE = BlockTags.create(OvergrownVegetation.id("husk_immune"));

    /**
     * <b>地脈</b>({@code ley_vein})が置き換えてよいブロック(2026-09-22)。データパックで追加可能。
     *
     * <p>「地表の地面」だけを並べること。地脈を敷く高さは
     * {@link net.minecraft.world.level.levelgen.Heightmap.Types#OCEAN_FLOOR} から探すが、
     * <b>木の幹も葉も「動きを止めるブロック」なので地表として拾われる</b> ——
     * タグで絞らないと<b>地脈が木のてっぺんに生える</b>(実際にそうなった)。
     * 鉱石も入れないこと(潰すと資源が消える)。
     */
    public static final TagKey<Block> LEY_VEIN_REPLACEABLE =
            BlockTags.create(OvergrownVegetation.id("ley_vein_replaceable"));
    public static final TagKey<Block> CORRUPTION_IMMUNE = BlockTags.create(OvergrownVegetation.id("corruption_immune"));

    /**
     * ヴェロフィルの胞子爆弾が<b>撃ち抜いて砕く</b>薄い天蓋ブロック(既定: 葉ブロック各種)。
     *
     * <p>{@code #minecraft:leaves} をそのまま使えないのは、このMODの
     * {@link com.yayuy.overgrownvegetation.registry.ModBlocks#OVERGROWN_LEAVES 汚染された葉}が
     * <b>{@code LeavesBlock} ではなく素の {@code Block}</b>(オーク葉のプロパティを写しただけ)で、
     * バニラの葉タグにも入っていないため。かといってバニラのタグへ足すと葉の腐食など
     * 無関係な挙動まで巻き込むので、専用のタグを切ってある。
     *
     * @see com.yayuy.overgrownvegetation.entity.VelophyllBombEntity
     */
    public static final TagKey<Block> BOMB_SHATTERS = BlockTags.create(OvergrownVegetation.id("bomb_shatters"));

    /**
     * アルボラの巣が<b>掘ってよい</b>ブロック(2026-09-19)。自然の地形とこの MOD の汚染地形だけを入れる。
     * ここに無いブロック(丸石・板材・チェスト・ベッドロックなど)に当たると掘削点はそのマスを飛ばすので、
     * <b>誰が置いたかを記録しなくてもプレイヤーの建築物が守られる</b>。
     */
    public static final TagKey<Block> NEST_DIGGABLE = BlockTags.create(OvergrownVegetation.id("nest_diggable"));

    /**
     * 巣の<b>壁として残す</b>ブロック(2026-09-19)。鉱石を入れてあるので、坑道は掘り抜きつつ
     * 壁には鉱脈の断面が残る。潜る動機のひとつ。
     */
    public static final TagKey<Block> NEST_LINING_KEEP = BlockTags.create(OvergrownVegetation.id("nest_lining_keep"));

    /**
     * 巣を掘るときに<b>一緒に消す飾り</b>(2026-09-19)。草・蔦・葉・苔の絨毯など、地形ではないので
     * {@link #NEST_DIGGABLE} には入らないが、残すと支えを失って<b>空中に浮いたまま取り残される</b> ——
     * 繫茂した洞窟を掘り抜いたときに実際に発生した。鉱石や建材は入れないこと。
     */
    public static final TagKey<Block> NEST_CLEARABLE = BlockTags.create(OvergrownVegetation.id("nest_clearable"));

    /**
     * 葉ブロックなど「上に乗る/横から生やす」のが不自然な薄い天蓋ブロック。
     * このタグが付いたブロックからは下面(垂れ下がる植物)しか生えない。
     */
    public static final TagKey<Block> GROWTH_HANGING_ONLY = BlockTags.create(OvergrownVegetation.id("growth_hanging_only"));

    /**
     * 「洞窟の天井」とみなさないブロック —— 木の幹・枝・葉や、巨大樹の各部、菌の柱・傘など。
     *
     * <p>{@link com.yayuy.overgrownvegetation.corruption.CaveGrowth#isCave} は
     * 「その列の地表({@code MOTION_BLOCKING_NO_LEAVES})より深いか」で洞窟を判定するが、この地表には
     * <b>木の幹も含まれる</b>。そのため高さ 120〜240 ブロックの巨大樹が建つと、その幹・枝や真下の地面が
     * まるごと「地表より遥かに深い = 洞窟」と誤判定され、樹冠の上や樹の下に洞窟用の構造物(菌の柱・
     * キノコの傘)が生えてしまっていた。天井を探すときはこのタグのブロックを読み飛ばす。
     */
    public static final TagKey<Block> NOT_CAVE_ROOF = BlockTags.create(OvergrownVegetation.id("not_cave_roof"));

    /**
     * 中に入っている相手を隠す「茂み」ブロック。既定では
     * {@link com.yayuy.overgrownvegetation.block.OvergrownBushBlock}(過繁茂した茂み)のみ。
     * データパックで他の下草などを追加可能。「過繁茂した(MOB名)」系の索敵・追跡 Goal
     * ({@link com.yayuy.overgrownvegetation.effect.FoliageConcealment} 参照)が、このタグの付いた
     * ブロックの中にいる相手を新規ターゲットにしない/追跡中でも即座に見失う判定に使う。
     */
    public static final TagKey<Block> CONCEALING = BlockTags.create(OvergrownVegetation.id("conceals_entities"));

    /**
     * 「ファランギオターブス」({@link com.yayuy.overgrownvegetation.entity.PhalangiotarbusEntity})が
     * 擬態のために張り付ける「木の幹」ブロック。既定では巨大樹の原木・過繁茂した木/原木・
     * バニラの {@code #minecraft:logs}。データパックで追加可能。近くにこのタグのブロックがあり、
     * かつ過繁茂バイオームの中にいるときだけファランギオターブスは擬態する。
     */
    public static final TagKey<Block> PHALANGIOTARBUS_PERCH = BlockTags.create(OvergrownVegetation.id("phalangiotarbus_perch"));

    /**
     * トンボ系の飛行 Mob(ステノデクティア・メガネウラ)が下面に止まって休める「木の枝」ブロック。
     * 既定では葉ブロック各種 + {@link #PHALANGIOTARBUS_PERCH}(原木類)。データパックで追加可能。
     */
    public static final TagKey<Block> DRAGONFLY_PERCH = BlockTags.create(OvergrownVegetation.id("dragonfly_perch"));

    /**
     * コエルロサウラヴスが<b>滑空のために登る幹</b>。既定の中身は
     * {@link #PHALANGIOTARBUS_PERCH} と同じ(原木類)だが、<b>別のタグにしてある</b> ——
     * 擬態する幹と登る幹は用途が違うので、片方だけデータパックで絞れるようにしておきたい。
     */
    public static final TagKey<Block> CLIMBABLE_TRUNK = BlockTags.create(OvergrownVegetation.id("climbable_trunk"));

    /**
     * 「ステノデクティア」が食べる実。既定では蔦の実の成る蔦・光るツタ。
     * データパックで追加可能。
     */
    public static final TagKey<Block> STENODICTYA_FOOD = BlockTags.create(OvergrownVegetation.id("stenodictya_food"));

    /**
     * フェーズ1以降、自然湧きの際に「胞子感染1」を持って湧く可能性がある Mob。
     * データパックで追加可能({@link com.yayuy.overgrownvegetation.effect.NaturalInfectionSpawner} 参照)。
     */
    public static final TagKey<EntityType<?>> PHASE_INFECTABLE_ANIMALS =
            TagKey.create(net.minecraft.core.registries.Registries.ENTITY_TYPE, OvergrownVegetation.id("phase_infectable_animals"));

    /**
     * 「過繁茂した Mob」のグループ分け(媒介種〜災害級の 10 分類)。フェーズごとの湧き・強化などは
     * このタグを基準に行う({@link com.yayuy.overgrownvegetation.phase.MobGroup} 参照)。
     * データパックで所属を差し替え可能。1 体が複数グループに属する場合は
     * {@link com.yayuy.overgrownvegetation.phase.MobGroup#of} が番号の小さいグループを返す。
     *
     * <ul>
     *     <li>{@link #GROUP_1}: 媒介種 — 現状のバニラ Mob ベースの「過繁茂した(MOB名)」全種。</li>
     *     <li>{@link #GROUP_2}: 原生生産種 — 未実装(枠のみ)。</li>
     *     <li>{@link #GROUP_3}: 原生消費種 — 過繁茂したスカトゥリウム。</li>
     *     <li>{@link #GROUP_4}: 拡大種 — 汚染シダ・アルボラ・フィロセンス・ヴェロフィル・スポロフィル・
     *     過繁茂したスカトゥリウム、および<b>レイドビーコン</b>(レイドの中核。汚染側から敵対されないよう
     *     2026-09-17 にここへ入れた)。</li>
     *     <li>{@link #GROUP_5}: 適応種 — 未実装(枠のみ)。</li>
     *     <li>{@link #GROUP_6}: 進化種 — 未実装(枠のみ)。</li>
     *     <li>{@link #GROUP_7}: 変異種 — 未実装(枠のみ)。</li>
 *     <li>{@link #GROUP_8}: 脅威種 — 未実装(枠のみ)。</li>
 *     <li>{@link #GROUP_9}: 侵略級 — 未実装(枠のみ。ハイヴノードの出現条件にする予定)。</li>
 *     <li>{@link #GROUP_10}: 災害級 — {@code dorga_archelaus_titanica}(攻城戦ボス。
 *     2026-09-19 にグループ7 から移動)。</li>
     * </ul>
     */
    public static final TagKey<EntityType<?>> GROUP_1 = entityTag("group_1");
    public static final TagKey<EntityType<?>> GROUP_2 = entityTag("group_2");
    public static final TagKey<EntityType<?>> GROUP_3 = entityTag("group_3");
    public static final TagKey<EntityType<?>> GROUP_4 = entityTag("group_4");
    public static final TagKey<EntityType<?>> GROUP_5 = entityTag("group_5");
    public static final TagKey<EntityType<?>> GROUP_6 = entityTag("group_6");
    public static final TagKey<EntityType<?>> GROUP_7 = entityTag("group_7");
    public static final TagKey<EntityType<?>> GROUP_8 = entityTag("group_8");
    public static final TagKey<EntityType<?>> GROUP_9 = entityTag("group_9");
    public static final TagKey<EntityType<?>> GROUP_10 = entityTag("group_10");

    /**
     * 「水ぐされ」({@link com.yayuy.overgrownvegetation.registry.ModMobEffects#WATER_ROT})にかからない
     * エンティティタイプ。
     *
     * <p>コード側では {@link com.yayuy.overgrownvegetation.entity.AquaticOvergrownMob} を実装している
     * Mob(リゾドゥス・ゼナカンサス・オルタカンサス・メガラクネ)が自動で除外される。
     * このタグが受け持つのは:
     * <ul>
     *     <li><b>マーカーを持たない水生種</b> —— {@code blight_water_grass} は {@code BlightFernEntity}
     *     派生なのでマーカーを持たない。</li>
     *     <li><b>攻城戦ボス</b> {@code dorga_archelaus_titanica}(ユーザー指定 2026-09-16)。
     *     水生ではないが、自分で広げた地形の上に居座るのが通常動作で、しかも水ぐされのダメージは
     *     防具貫通なので {@code siegeArmor} のしきい値を素通りしてしまう
     *     ({@link com.yayuy.overgrownvegetation.effect.ParasitizedSporeImmunity} で胞子感染を
     *     弾いているのと同じ理由)。</li>
     *     <li>データパックでの追加・例外指定。</li>
     * </ul>
     */
    public static final TagKey<EntityType<?>> WATER_ROT_IMMUNE = entityTag("water_rot_immune");

    /**
     * <b>水に入らずに攻撃できる</b>(= 遠距離攻撃を持つ)エンティティタイプ。
     * 使い道は今のところ 1 つ、{@link com.yayuy.overgrownvegetation.entity.Predation#isLandTarget} の
     * 例外指定 —— 「自分も水に浸かっているなら水中の相手を狙ってよい」のは、このタグの種だけ。
     *
     * <p>ユーザー指定 2026-09-17:「アルボラやスカトゥリウムなど遠距離攻撃手段のない Mob が
     * 水中の Mob を狙わないようにしてほしい」。狙えば必ず水へ入ることになり、水ぐされで溶けるため。
     *
     * <p><b>スカトゥリウムは迫撃砲({@link com.yayuy.overgrownvegetation.entity.ShielderMortarGoal})を
     * 持っているが、このタグには入れていない</b>(同上のユーザー指定)。迫撃砲は突進と枠を分け合う
     * 「引き離されたとき用」の手で、基本は詰めて殴りに行く種なので、水中の相手を狙わせると
     * 結局そのまま川へ入ってしまう。
     */
    public static final TagKey<EntityType<?>> RANGED_ATTACKER = entityTag("ranged_attacker");

    private static TagKey<EntityType<?>> entityTag(String path) {
        return TagKey.create(net.minecraft.core.registries.Registries.ENTITY_TYPE, OvergrownVegetation.id(path));
    }

    private ModTags() {
    }
}
