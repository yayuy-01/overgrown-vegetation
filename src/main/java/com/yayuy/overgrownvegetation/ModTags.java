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
     * 防具を貫通する攻撃(順 1 の着手順 6、2026-10-03)。パワードアーマーの<b>強靭</b>のツリーが減らす対象
     * ({@link com.yayuy.overgrownvegetation.item.PoweredArmorEffects})。中身は {@code #minecraft:bypasses_armor} に
     * この MOD が入れている攻撃と同じ 6 種(ファルキムネスの噛みつき・処刑、アクムネスの棘、ヴォラムネスの消化、
     * 胞子感染、水の腐敗)。<b>防具を貫通する攻撃を新しく足したら、ここにも入れる。</b>
     */
    public static final TagKey<DamageType> ARMOR_PIERCING =
            TagKey.create(net.minecraft.core.registries.Registries.DAMAGE_TYPE,
                    OvergrownVegetation.id("armor_piercing"));

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
     * G5 の「適応の記録」7 種(順 1 の着手順 11)と、パワードアーマーのスキルツリーの本ごとの支払いに使える記録
     * (ユーザー指定 2026-10-04: 防御 ← 盾・喰らい / 強靭 ← 狙い・掴み / 耐久 ← 水・闇・掘削)。
     * 支払いは {@code gear.json} の {@code poweredArmor.levels[].branches} から {@code #overgrown_vegetation:adaptation_records/<本>} で引く。
     */
    public static final TagKey<net.minecraft.world.item.Item> ADAPTATION_RECORDS =
            net.minecraft.tags.ItemTags.create(OvergrownVegetation.id("adaptation_records"));
    public static final TagKey<net.minecraft.world.item.Item> ADAPTATION_RECORDS_DEFENSE =
            net.minecraft.tags.ItemTags.create(OvergrownVegetation.id("adaptation_records/defense"));
    public static final TagKey<net.minecraft.world.item.Item> ADAPTATION_RECORDS_TOUGHNESS =
            net.minecraft.tags.ItemTags.create(OvergrownVegetation.id("adaptation_records/toughness"));
    public static final TagKey<net.minecraft.world.item.Item> ADAPTATION_RECORDS_DURABILITY =
            net.minecraft.tags.ItemTags.create(OvergrownVegetation.id("adaptation_records/durability"));

    /**
     * <b>汚染特効</b>の武器。このタグのアイテムで殴ると、グループ1〜10 の Mob に入るダメージが
     * {@code overgrowthBaneMultiplier}(既定 1.5)倍になる
     * ({@link com.yayuy.overgrownvegetation.phase.OvergrowthBaneDamage})。
     *
     * <p>既定の中身はクロロフィライトとクロロフィライト合金の剣・斧と、ツヴァイヘンダー 3 本(2026-10-09)
     * ({@code data/overgrown_vegetation/tags/item/overgrowth_bane.json})。
     * 対象を足したい/外したいときは Java ではなくこの json を触る。
     */
    public static final TagKey<net.minecraft.world.item.Item> OVERGROWTH_BANE =
            net.minecraft.tags.ItemTags.create(OvergrownVegetation.id("overgrowth_bane"));

    /**
     * <b>両手持ち</b>の武器(2026-10-10、ユーザー指定「左手のアイテムを使えないようにする」)。利き手に持っている間、
     * もう片方の手の物は使えない({@link com.yayuy.overgrownvegetation.item.TwoHandedRules})。今はツヴァイヘンダー 3 本
     * ({@code data/overgrown_vegetation/tags/item/two_handed.json})。両手持ちの武器を足したらここへ。
     */
    public static final TagKey<net.minecraft.world.item.Item> TWO_HANDED =
            net.minecraft.tags.ItemTags.create(OvergrownVegetation.id("two_handed"));

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

    /**
     * 司令塔の拠点の記憶で「人が住んでいる印」に数える置き物(2026-11-14 第 2 段。11-14 ユーザー決定 Q2 = 3: ベッド・入れ物・換気機・洗い場・かまど類・作業台)。
     * 退避中の防衛設備を戻したら、ここへ足す。
     */
    public static final TagKey<Block> BASE_STRUCTURE = BlockTags.create(OvergrownVegetation.id("base_structure"));

    /** 既に汚染済みのブロック。二重に汚染されないようにするためのタグ。 */
    public static final TagKey<Block> CORRUPTED = BlockTags.create(OvergrownVegetation.id("corrupted"));

    /** ネザーの覆いで、上面だけ「8〜9 割塗り」の絵({@code <名前>_top})を使う下地(2026-11-11 ユーザー: ネザーラックは上面だけ)。 */
    public static final TagKey<Block> NETHER_COVER_GROUND_TOP = BlockTags.create(OvergrownVegetation.id("nether_cover_ground_top"));

    /** ネザーの覆い(重ねる苔の絵、2026-11-11)を付けられる地面: ネザーラック・ソウルサンド・ソウルソイル・玄武岩・ブラックストーン(汚染の行き先があっても覆う)と、汚染済みの搾られた岩・泥炭。 */
    public static final TagKey<Block> NETHER_COVERABLE = BlockTags.create(OvergrownVegetation.id("nether_coverable"));

    /** 枝の継ぎ目({@code BranchJointBlock})が腕を出す相手(原木・継ぎ目・原木から化けた幹。2026-11-01)。葉は入れない。 */
    public static final TagKey<Block> BRANCH_CONNECTABLE = BlockTags.create(OvergrownVegetation.id("branch_connectable"));

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

    /** 胞子の溜まり(地下の植生 U6)。近づくと低いうなりが鳴る(client の CaveSound、2026-11-01)。今は空(溜まりのブロックを足したらここへ)。 */
    public static final TagKey<Block> SPORE_POOL = BlockTags.create(OvergrownVegetation.id("spore_pool"));

    /**
     * 全面に張り付く Mob(アルボラなど、{@code entity/cling})が<b>張り付けない</b>ブロック(2026-11-05 ユーザー決定「後から足せる口だけ」)。
     * 壁・天井として掴めないだけで、床としては乗れる。今は空。
     */
    public static final TagKey<Block> UNCLINGABLE = BlockTags.create(OvergrownVegetation.id("unclingable"));

    /**
     * 全面に張り付く Mob が<b>中へ潜って 3 次元に動ける</b>葉(2026-11-05 ユーザー決定「葉の中は潜れて自由自在に動ける」)。
     * 外の面には普通に張り付く。当たりのある葉だけを並べる(当たりの無い房は、そもそも空気と同じに通れる)。
     */
    public static final TagKey<Block> BURROWABLE_FOLIAGE = BlockTags.create(OvergrownVegetation.id("burrowable_foliage"));

    /** 地下で根の軋みが聞こえてくる向きにするブロック(天井を貫く巨大樹の幹・根。client の CaveSound、2026-11-01)。 */
    public static final TagKey<Block> CAVE_CREAK_ROOTS = BlockTags.create(OvergrownVegetation.id("cave_creak_roots"));

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
     * 道のブロック(アスファルト・ハーフ・階段)。プレイヤーが置いたものを道の台帳がマスとして数える
     * ({@code base/road/BaseRoads}、順 2 の着手順 7、2026-10-08)。
     */
    public static final TagKey<Block> ROAD_BLOCKS = BlockTags.create(OvergrownVegetation.id("road_blocks"));

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
     * <b>過繁茂の葉</b>(2026-11-16): コルダイテスの葉({@code overgrown_leaves})と巨大樹の葉({@code giant_canopy_leaves})。
     * 見た目が同じ 2 つを、演出・判定で同じに扱うためのタグ(名指しの {@code is(OVERGROWN_LEAVES)} はこれへ)。
     * 起動時の登録(葉の色 {@code LeafTones}・揺れ {@code SwayModels})はタグがまだ空なので名指しのまま。
     */
    public static final TagKey<Block> OVERGROWN_LEAVES = BlockTags.create(OvergrownVegetation.id("overgrown_leaves"));

    /**
     * <b>巨大樹の太い木部</b>(2026-11-16): 巨大樹の原木・形成層・硬化した幹と、見た目が同じ汚染の根({@code root_arch_log})。
     * 「巨大樹の原木と同じに扱う」判定(幹に巻き付く・根が通り抜ける・木の一部など)はこれで見る。影絵だけは {@link #GIANT_SILHOUETTE_WOOD}。
     */
    public static final TagKey<Block> GIANT_WOOD = BlockTags.create(OvergrownVegetation.id("giant_wood"));

    /** 巨大樹の遠景の影絵が写す木部(巨大樹そのものだけ。汚染の根は入れない。2026-11-16)。 */
    public static final TagKey<Block> GIANT_SILHOUETTE_WOOD = BlockTags.create(OvergrownVegetation.id("giant_silhouette_wood"));

    /** 巨大樹の遠景の影絵が写す葉(巨大樹の葉だけ。コルダイテスの葉は入れない。2026-11-16)。 */
    public static final TagKey<Block> GIANT_SILHOUETTE_LEAVES = BlockTags.create(OvergrownVegetation.id("giant_silhouette_leaves"));

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
     * 洞窟の天井になれる<b>天然の地形</b>のブロック(2026-11-14 ユーザー決定「丸石・石レンガなどバニラの建材で作った建物の中が洞窟判定なのはおかしい」)。
     * 石・深層岩・土・砂・砂利・砂岩・テラコッタ・鉱石など + この MOD の汚染後の地形 + 共通タグ {@code c:stones}・{@code c:ores}(他 MOD の天然の岩)。
     * 画面側の地下モード({@code client/atmosphere/CaveMap#isRock})だけが読む。石レンガ・丸石・磨いた石・板などの建材は入れない
     * (建物の中は洞窟の見え方にしない)。他 MOD の岩の洞窟が洞窟扱いにならないときは、このタグへ足す。
     */
    public static final TagKey<Block> CAVE_ROCK = BlockTags.create(OvergrownVegetation.id("cave_rock"));

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
     * 植物側の Mob の「グループ」(group_1〜10)。2026-11-18 夜 ユーザー決定でコード内の分類も組み直す途中:
     * <b>分類の正本は {@code design/mob_class.json}</b>({@link com.yayuy.overgrownvegetation.phase.MobClassTable}。系統・型・危険度・出るフェーズ)と、
     * 系統のタグ {@link #LINEAGE_PARASITE} など。group_N は移し替えの 3 段のうち 1 段目が済んだ所で、まだ次に使っている:
     * 縁の上限を数える箱(spawns.json の group と caps)・キル点の種類・植物側の判定・G5 の適応・遠征のドロップ など
     * (2 段目でそれぞれ系統のタグか専用のタグへ移し、3 段目で消す)。出るフェーズと森の値段は、もう group では決めない。
     * データパックで所属を差し替え可能。1 体が複数グループに属する場合は
     * {@link com.yayuy.overgrownvegetation.phase.MobGroup#of} が番号の小さいグループを返す。
     *
     * <ul>
     *     <li>{@link #GROUP_1}: 寄生種(G1)— 段階 0・1 段目・根付き・苗床・根の番人の 47 体。</li>
     *     <li>{@link #GROUP_2}: 環境生物(G2)— 生息しているだけの虫など 5 体。</li>
     *     <li>{@link #GROUP_3}: 環境生物(G3)— 虫を狩る爬虫類・サソリなど 7 体(プレイヤーは基本襲わない)。</li>
     *     <li>{@link #GROUP_4}: 眷属種(G4)と植物種 — アルボラ・グレガリア・ストロビラ・ヴェシカリア・ヴェロフィル・セミナリア、
     *     植物種の萌芽体・ペディキュラ。</li>
     *     <li>{@link #GROUP_5}: 眷属種(G5)— ファルキムネス・ヴェナムネス・サルティムネス・クリペイムネス・ピリムネス・アクムネス。</li>
     *     <li>{@link #GROUP_6}: ゲスタトリクス(眷属種)と植物種のフィロセンス。</li>
     *     <li>{@link #GROUP_7}〜{@link #GROUP_10}: 空(0.4.0 以降の枠)。</li>
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
     * 系統(2026-11-18 夜 ユーザー決定「○○型-危険度 N-○○種」の「○○種」)。寄生種・眷属種・植物種・環境生物。
     * 分類の正本は {@code design/mob_class.json}({@link com.yayuy.overgrownvegetation.phase.MobClassTable})で、このタグはそれと同じ中身
     * (GameTest {@code MobClassGameTests} が突き合わせる)。group_N からの移し替えは段を分けて行う(1 段目 = タグを足しただけ)。
     */
    public static final TagKey<EntityType<?>> LINEAGE_PARASITE = entityTag("lineage_parasite");
    public static final TagKey<EntityType<?>> LINEAGE_KIN = entityTag("lineage_kin");
    public static final TagKey<EntityType<?>> LINEAGE_PLANT = entityTag("lineage_plant");
    public static final TagKey<EntityType<?>> LINEAGE_CRITTER = entityTag("lineage_critter");

    /**
     * 「水ぐされ」({@link com.yayuy.overgrownvegetation.registry.ModMobEffects#WATER_ROT})にかからない
     * エンティティタイプ。
     *
     * <p>コード側では {@link com.yayuy.overgrownvegetation.entity.AquaticOvergrownMob} を実装している
     * Mob(リゾドゥス・ゼナカンサス・オルタカンサス・メガラクネ)が自動で除外される。
     * このタグが受け持つのは:
     * <ul>
     *     <li><b>マーカーを持たない水生種</b> —— (削除した汚染水草 blight_water_grass は {@code BlightFernEntity}
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
     * 胞子の暴露量を測らない Mob(データパック用。2026-11-09 から既定は空: ユーザー決定「ボスも外さない」。
     * 11-04 の仮決めではエンダードラゴンとウィザーを入れていた)。植物側は {@link #PLANT_SIDE} で外す。
     */
    public static final TagKey<EntityType<?>> SPORE_EXPOSURE_IMMUNE = entityTag("spore_exposure_immune");

    /**
     * 植物側の Mob(2026-11-09、暴露量を測らない・感染の死で萌芽体にしない)。グループ 1〜10 のタグ全部 + 萌芽体 + ハイヴノード。
     * コード側でも G1 の寄生体({@code AbstractParasitizedMonster})・萌芽体・ハイヴノードはクラスで外す(タグから抜かれても漏れない保険)。
     */
    public static final TagKey<EntityType<?>> PLANT_SIDE = entityTag("plant_side");

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

    /**
     * 難易度ピースフルでも<b>消さない・湧かせてよい</b>グループの種(2026-09-26)。グループ 1〜10 の種のうち、ここに無いものは
     * ピースフルで敵として消える({@link com.yayuy.overgrownvegetation.difficulty.Peaceful})。
     * 既定は<b>環境生物</b>(グループ 2 の原生生産種・グループ 3 の原生消費種。どちらもプレイヤーを襲わない)と
     * 汚染シダ・汚染水草(動かない植物)
     * ({@code data/overgrown_vegetation/tags/entity_type/peaceful_allowed.json})。
     * グループ 3 は 2026-10-03 に足した(プレイヤーを襲わなくなったので)。
     */
    public static final TagKey<EntityType<?>> PEACEFUL_ALLOWED = entityTag("peaceful_allowed");

    /**
     * 同盟軍の基地の壁の中でも湧きを止めない種(順 2 の着手順 3、2026-10-04、{@code base/build/BaseRefuge})。汚染シダ・汚染水草
     * (侵食で生える植物。除去装置の守りで別に止まる)・レイドの苗床(湧きを取り消すと「レイドが始まった」だけが残る。置き場所は先に基地を避ける)・
     * ティタニカ(攻城戦のボス。取り消すと毎 tick 作り直す)
     * ({@code data/overgrown_vegetation/tags/entity_type/refuge_exempt.json})。
     */
    public static final TagKey<EntityType<?>> REFUGE_EXEMPT = entityTag("refuge_exempt");

    /**
     * ネザー専用の種(2026-10-09、ネザーの媒介種 9 種)。ネザーにだけ湧き、ネザーの外には湧かない。
     * 逆に、ここに無い G1(オーバーワールド産の媒介種)はネザーに湧かない(10-02 ユーザー指定)。
     * 判定は {@link com.yayuy.overgrownvegetation.phase.OvergrownSpawnProfile#allowedIn} と
     * {@link com.yayuy.overgrownvegetation.phase.OutsideInvaderSpawner}
     * ({@code data/overgrown_vegetation/tags/entity_type/nether_native.json})。
     */
    public static final TagKey<EntityType<?>> NETHER_NATIVE = entityTag("nether_native");

    private static TagKey<EntityType<?>> entityTag(String path) {
        return TagKey.create(net.minecraft.core.registries.Registries.ENTITY_TYPE, OvergrownVegetation.id(path));
    }

    private ModTags() {
    }
}
