package com.yayuy.overgrownvegetation.registry;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.entity.BlightFernEntity;
import com.yayuy.overgrownvegetation.entity.BlightWaterGrassEntity;
import com.yayuy.overgrownvegetation.entity.DorgaArchelausTitanicaEntity;
import com.yayuy.overgrownvegetation.entity.TitanicaHitboxEntity;
import com.yayuy.overgrownvegetation.entity.SiegeBombEntity;
import com.yayuy.overgrownvegetation.entity.SiegeCannonSeatEntity;
import com.yayuy.overgrownvegetation.entity.OvergrownNexusEntity;
import com.yayuy.overgrownvegetation.entity.ArboraEntity;
import com.yayuy.overgrownvegetation.entity.ArboraQueenEntity;
import com.yayuy.overgrownvegetation.entity.NestCoreEntity;
import com.yayuy.overgrownvegetation.entity.ArthropleuraEntity;
import com.yayuy.overgrownvegetation.entity.CoelurosauravusEntity;
import com.yayuy.overgrownvegetation.entity.LithopleuraEntity;
import com.yayuy.overgrownvegetation.entity.SpelaeopleuraEntity;
import com.yayuy.overgrownvegetation.entity.HiveNodeEntity;
import com.yayuy.overgrownvegetation.entity.HiveRitualEntity;
import com.yayuy.overgrownvegetation.entity.HiveRitualSeatEntity;
import com.yayuy.overgrownvegetation.entity.HiveVineEntity;
import com.yayuy.overgrownvegetation.entity.HylonomusEntity;
import com.yayuy.overgrownvegetation.entity.IanthasaurusEntity;
import com.yayuy.overgrownvegetation.entity.MeganeuraEntity;
import com.yayuy.overgrownvegetation.entity.MegarachneEntity;
import com.yayuy.overgrownvegetation.entity.OrthacanthusEntity;
import com.yayuy.overgrownvegetation.entity.FalcimnesEntity;
import com.yayuy.overgrownvegetation.entity.OvergrownShielderEntity;
import com.yayuy.overgrownvegetation.entity.PetrolacosaurusEntity;
import com.yayuy.overgrownvegetation.entity.PhyllosensEntity;
import com.yayuy.overgrownvegetation.entity.SporophyllEntity;
import com.yayuy.overgrownvegetation.entity.VelophyllBombEntity;
import com.yayuy.overgrownvegetation.entity.VelophyllEntity;
import com.yayuy.overgrownvegetation.entity.RhizodusEntity;
import com.yayuy.overgrownvegetation.entity.XenacanthusEntity;
import com.yayuy.overgrownvegetation.entity.PhalangiotarbusEntity;
import com.yayuy.overgrownvegetation.entity.ProtophasmaEntity;
import com.yayuy.overgrownvegetation.entity.StenodictyaLobataEntity;
import com.yayuy.overgrownvegetation.entity.ParasitizedCow;
import com.yayuy.overgrownvegetation.entity.ParasitizedCreeper;
import com.yayuy.overgrownvegetation.entity.ParasitizedPig;
import com.yayuy.overgrownvegetation.entity.ParasitizedSheep;
import com.yayuy.overgrownvegetation.entity.ParasitizedSpider;
import com.yayuy.overgrownvegetation.entity.ParasitizedZombie;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * この MOD のエンティティ型の登録。
 *
 * <p><b>当たり判定を mobs.json から取る Mob には {@code spawnDimensionsScale} も付ける</b>({@link MobStats#spawnScale})。
 * SCALE 属性(mobs.json の {@code scale} と個体差 {@code scaleMin}〜{@code scaleMax})が当たり判定に掛かるのは
 * 湧いた後の最初の tick の終わりだが、湧く場所の空きを確かめる箱({@code EntityType#getSpawnAABB} —
 * {@link com.yayuy.overgrownvegetation.entity.SpawnFit#fits}・バニラの自然湧き・スポナーが使う)は
 * {@code sized} の素の大きさのまま。付けないと素の大きさの隙間に湧いてから育って、丸太や壁に埋まる
 * (2026-09-23、ファルキムネスで発覚)。体格 1 の種にも付けてある —— mod マネージャーで {@code scaleMax} を
 * 足したときに、ここを直し忘れても湧き判定がずれないように。
 *
 * <p>付けないのは、当たり判定から SCALE を打ち消している種だけ(汚染シダ・汚染水草・ハイヴノード。
 * {@code getDefaultDimensions} で {@code 1 / scale} を掛けて判定を固定しているので、素の箱のほうが大きいか同じ)。
 */
public final class ModEntities {

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, OvergrownVegetation.MODID);

    /**
     * 「ハイヴノード」— 巨大樹の真下の「巣の広間」に吊り下がる球体。旧「古代樹の核」の代替で、
     * 生きている間だけ周囲が胞子感染3 の強制ゾーンになる
     * ({ com.yayuy.overgrownvegetation.entity.HiveNodeEntity})。
     */
    public static final DeferredHolder<EntityType<?>, EntityType<HiveNodeEntity>> HIVE_NODE =
            ENTITY_TYPES.register("hive_node", () -> EntityType.Builder
                    .of(HiveNodeEntity::new, MobCategory.MONSTER)
                    .sized(MobStats.width("hive_node", 3.0F), MobStats.height("hive_node", 3.0F))
                    // spawnDimensionsScale は付けない(当たり判定から SCALE を打ち消しているので、素の箱のほうが大きいか同じ。クラス javadoc)
                    .clientTrackingRange(16)
                    .updateInterval(3)
                    .fireImmune()
                    .build("hive_node"));

    /**
     * 寄生植物装備を受け取る儀式({@link com.yayuy.overgrownvegetation.entity.HiveRitualEntity})。
     * ハイヴノードを右クリックすると湧き、プレイヤーを乗せて蔦のアニメーションを 1 回再生して消える。
     * 見た目は蔦のモデルだけ(プレイヤー本体は PAL が動かす)。当たらない・保存しない。
     */
    public static final DeferredHolder<EntityType<?>, EntityType<HiveRitualEntity>> HIVE_RITUAL =
            ENTITY_TYPES.register("hive_ritual", () -> EntityType.Builder
                    .<HiveRitualEntity>of(HiveRitualEntity::new, MobCategory.MISC)
                    .sized(1.0F, 1.0F)
                    .clientTrackingRange(16)
                    .updateInterval(20)
                    .fireImmune()
                    .noSave()
                    .build("hive_ritual"));

    /**
     * 儀式の座席({@link com.yayuy.overgrownvegetation.entity.HiveRitualSeatEntity})。プレイヤーが乗り、
     * アニメの body の道筋どおりに動く。見えない・当たらない・保存しない。
     */
    public static final DeferredHolder<EntityType<?>, EntityType<HiveRitualSeatEntity>> HIVE_RITUAL_SEAT =
            ENTITY_TYPES.register("hive_ritual_seat", () -> EntityType.Builder
                    .<HiveRitualSeatEntity>of(HiveRitualSeatEntity::new, MobCategory.MISC)
                    .sized(0.1F, 0.1F)
                    .clientTrackingRange(16)
                    .updateInterval(20)
                    .fireImmune()
                    .noSummon()
                    .noSave()
                    .build("hive_ritual_seat"));

    /**
     * 「巣の蔦」— ハイヴノードを広間の殻から吊っている蔦 1 本ぶん
     * ({@link com.yayuy.overgrownvegetation.entity.HiveVineEntity})。7 割を切り落とすとノードが落ちる。
     */
    public static final DeferredHolder<EntityType<?>, EntityType<HiveVineEntity>> HIVE_VINE =
            ENTITY_TYPES.register("hive_vine", () -> EntityType.Builder
                    .of(HiveVineEntity::new, MobCategory.MONSTER)
                    .sized(MobStats.width("hive_vine", 1.2F), MobStats.height("hive_vine", 1.2F))
                    .spawnDimensionsScale(MobStats.spawnScale("hive_vine"))
                    .clientTrackingRange(16)
                    .updateInterval(3)
                    .fireImmune()
                    .build("hive_vine"));

    /** 巨大シダの寄生植物。侵食の起点。 */
    public static final DeferredHolder<EntityType<?>, EntityType<BlightFernEntity>> BLIGHT_FERN =
            ENTITY_TYPES.register("blight_fern", () -> EntityType.Builder
                    .of(BlightFernEntity::new, MobCategory.MONSTER)
                    .sized(MobStats.width("blight_fern", 1.4F), MobStats.height("blight_fern", 2.6F))
                    // spawnDimensionsScale は付けない(当たり判定から SCALE を打ち消しているので、素の箱のほうが大きいか同じ。クラス javadoc)
                    .clientTrackingRange(12)
                    .updateInterval(3)
                    .build("blight_fern"));

    /**
     * 汚染水草 — {@link BlightFernEntity} の水中版。仕様はすべて汚染シダと同じで、違いは湧いた場所が
     * 水中か地上かだけ({@link com.yayuy.overgrownvegetation.entity.BlightWaterGrassEntity} /
     * {@link com.yayuy.overgrownvegetation.entity.BlightFernPlacement})。
     */
    public static final DeferredHolder<EntityType<?>, EntityType<BlightWaterGrassEntity>> BLIGHT_WATER_GRASS =
            ENTITY_TYPES.register("blight_water_grass", () -> EntityType.Builder
                    .of(BlightWaterGrassEntity::new, MobCategory.MONSTER)
                    .sized(MobStats.width("blight_water_grass", 1.4F), MobStats.height("blight_water_grass", 2.6F))
                    // spawnDimensionsScale は付けない(当たり判定から SCALE を打ち消しているので、素の箱のほうが大きいか同じ。クラス javadoc)
                    .clientTrackingRange(12)
                    .updateInterval(3)
                    .build("blight_water_grass"));

    /*
     * 「寄生された(MOB名)」— フェーズ4の進化型感染 Mob(仮実装)。
     * 見た目・当たり判定・スポーン設定はすべてバニラの対応 Mob と同一にしてある
     * (寄生に関する本格的なシステムは後日実装。差し替え時は entity/Parasitized* クラス参照)。
     */

    public static final DeferredHolder<EntityType<?>, EntityType<ParasitizedCow>> PARASITIZED_COW =
            ENTITY_TYPES.register("parasitized_cow", () -> EntityType.Builder
                    .of(ParasitizedCow::new, MobCategory.CREATURE)
                    .sized(MobStats.width("parasitized_cow", 0.9F), MobStats.height("parasitized_cow", 1.4F))
                    .spawnDimensionsScale(MobStats.spawnScale("parasitized_cow"))
                    .clientTrackingRange(10)
                    .build("parasitized_cow"));

    public static final DeferredHolder<EntityType<?>, EntityType<ParasitizedSheep>> PARASITIZED_SHEEP =
            ENTITY_TYPES.register("parasitized_sheep", () -> EntityType.Builder
                    .of(ParasitizedSheep::new, MobCategory.CREATURE)
                    .sized(MobStats.width("parasitized_sheep", 0.9F), MobStats.height("parasitized_sheep", 1.3F))
                    .spawnDimensionsScale(MobStats.spawnScale("parasitized_sheep"))
                    .clientTrackingRange(10)
                    .build("parasitized_sheep"));

    public static final DeferredHolder<EntityType<?>, EntityType<ParasitizedPig>> PARASITIZED_PIG =
            ENTITY_TYPES.register("parasitized_pig", () -> EntityType.Builder
                    .of(ParasitizedPig::new, MobCategory.CREATURE)
                    .sized(MobStats.width("parasitized_pig", 0.9F), MobStats.height("parasitized_pig", 0.9F))
                    .spawnDimensionsScale(MobStats.spawnScale("parasitized_pig"))
                    .clientTrackingRange(10)
                    .build("parasitized_pig"));

    public static final DeferredHolder<EntityType<?>, EntityType<ParasitizedZombie>> PARASITIZED_ZOMBIE =
            ENTITY_TYPES.register("parasitized_zombie", () -> EntityType.Builder
                    .of(ParasitizedZombie::new, MobCategory.MONSTER)
                    .sized(MobStats.width("parasitized_zombie", 0.6F), MobStats.height("parasitized_zombie", 1.95F))
                    .spawnDimensionsScale(MobStats.spawnScale("parasitized_zombie"))
                    .clientTrackingRange(8)
                    .build("parasitized_zombie"));

    public static final DeferredHolder<EntityType<?>, EntityType<ParasitizedCreeper>> PARASITIZED_CREEPER =
            ENTITY_TYPES.register("parasitized_creeper", () -> EntityType.Builder
                    .of(ParasitizedCreeper::new, MobCategory.MONSTER)
                    .sized(MobStats.width("parasitized_creeper", 0.6F), MobStats.height("parasitized_creeper", 1.7F))
                    .spawnDimensionsScale(MobStats.spawnScale("parasitized_creeper"))
                    .clientTrackingRange(8)
                    .build("parasitized_creeper"));

    public static final DeferredHolder<EntityType<?>, EntityType<ParasitizedSpider>> PARASITIZED_SPIDER =
            ENTITY_TYPES.register("parasitized_spider", () -> EntityType.Builder
                    .of(ParasitizedSpider::new, MobCategory.MONSTER)
                    .sized(MobStats.width("parasitized_spider", 1.4F), MobStats.height("parasitized_spider", 0.9F))
                    .spawnDimensionsScale(MobStats.spawnScale("parasitized_spider"))
                    .clientTrackingRange(8)
                    .build("parasitized_spider"));

    /**
     * 「過繁茂したスカトゥリウム」— 苔と甲殻に覆われた大型の寄生生物。
     * 近接攻撃以外は無効。引き離されると溜め→突進してくる（{@code entity/ShielderChargeGoal}）。
     */
    public static final DeferredHolder<EntityType<?>, EntityType<OvergrownShielderEntity>> OVERGROWN_SHIELDER =
            ENTITY_TYPES.register("overgrown_shielder", () -> EntityType.Builder
                    .of(OvergrownShielderEntity::new, MobCategory.MONSTER)
                    .sized(MobStats.width("overgrown_shielder", 2.5F), MobStats.height("overgrown_shielder", 2.0F))
                    .spawnDimensionsScale(MobStats.spawnScale("overgrown_shielder"))
                    .clientTrackingRange(12)
                    .updateInterval(3)
                    .build("overgrown_shielder"));

    /**
     * 「ファルキムネス」— カマキリの姿をした爬虫類。グループ5(適応種)の陸の攻撃役(2026-09-23)。
     * 体格 1.8 倍で全長 11.5 ブロックあるが、当たり判定は胴(実効 2.97 x 3.06)だけを覆う。
     */
    public static final DeferredHolder<EntityType<?>, EntityType<FalcimnesEntity>> FALCIMNES =
            ENTITY_TYPES.register("falcimnes", () -> EntityType.Builder
                    .of(FalcimnesEntity::new, MobCategory.MONSTER)
                    .sized(MobStats.width("falcimnes", 1.65F), MobStats.height("falcimnes", 1.7F))
                    .spawnDimensionsScale(MobStats.spawnScale("falcimnes"))
                    .clientTrackingRange(12)
                    .updateInterval(2)
                    .build("falcimnes"));

    /**
     * 「ファランギオターブス」— 8 本脚の大型寄生節足動物。過繁茂バイオームの原木に
     * 擬態しながら待ち伏せ、獲物に飛びついて取り付き継続ダメージで仕留める(グループ3)。
     */
    public static final DeferredHolder<EntityType<?>, EntityType<PhalangiotarbusEntity>> PHALANGIOTARBUS =
            ENTITY_TYPES.register("phalangiotarbus", () -> EntityType.Builder
                    .of(PhalangiotarbusEntity::new, MobCategory.MONSTER)
                    .sized(MobStats.width("phalangiotarbus", 1.0F), MobStats.height("phalangiotarbus", 0.9F))
                    .spawnDimensionsScale(MobStats.spawnScale("phalangiotarbus"))
                    .clientTrackingRange(10)
                    .updateInterval(3)
                    .build("phalangiotarbus"));

    /**
     * 「プロトファスマ」— 鈴虫に似たグループ2(原生生産種)の Mob。他の Mob を襲わず、
     * 時々立ち止まって鳴く以外は変哲のない虫。スカトゥリウム・ファランギオターブスに捕食される。
     */
    public static final DeferredHolder<EntityType<?>, EntityType<ProtophasmaEntity>> PROTOPHASMA =
            ENTITY_TYPES.register("protophasma", () -> EntityType.Builder
                    .of(ProtophasmaEntity::new, MobCategory.CREATURE)
                    .sized(MobStats.width("protophasma", 0.5F), MobStats.height("protophasma", 0.5F))
                    .spawnDimensionsScale(MobStats.spawnScale("protophasma"))
                    .clientTrackingRange(8)
                    .build("protophasma"));

    /**
     * 「アースロプレウラ」— 巨大なムカデに似たグループ2の Mob。移動が遅く(スカトゥリウムより少し遅い)
     * 防御力が高い。他の Mob を襲わない。スカトゥリウム・ファランギオターブスに捕食される。
     */
    public static final DeferredHolder<EntityType<?>, EntityType<ArthropleuraEntity>> ARTHROPLEURA =
            ENTITY_TYPES.register("arthropleura", () -> EntityType.Builder
                    .of(ArthropleuraEntity::new, MobCategory.CREATURE)
                    .sized(MobStats.width("arthropleura", 1.1F), MobStats.height("arthropleura", 0.55F))
                    .spawnDimensionsScale(MobStats.spawnScale("arthropleura"))
                    .clientTrackingRange(9)
                    .build("arthropleura"));

    /**
     * 「リトプレウラ」— 岩に擬態するヤスデ(グループ2)。ときどき脱皮して<b>抜け殻ブロック</b>を残す。
     * 他の Mob を襲わない。
     */
    public static final DeferredHolder<EntityType<?>, EntityType<LithopleuraEntity>> LITHOPLEURA =
            ENTITY_TYPES.register("lithopleura", () -> EntityType.Builder
                    .of(LithopleuraEntity::new, MobCategory.CREATURE)
                    .sized(MobStats.width("lithopleura", 0.9F), MobStats.height("lithopleura", 0.5F))
                    .spawnDimensionsScale(MobStats.spawnScale("lithopleura"))
                    .clientTrackingRange(9)
                    .build("lithopleura"));

    /**
     * 「コエルロサウラヴス」— 滑空する爬虫類(グループ3)。木を登って飛び出し、滑空比 3:1 で
     * 降りながら獲物を探し、見つけると急降下して襲う。プレイヤーは襲わない。
     */
    public static final DeferredHolder<EntityType<?>, EntityType<CoelurosauravusEntity>> COELUROSAURAVUS =
            ENTITY_TYPES.register("coelurosauravus", () -> EntityType.Builder
                    .of(CoelurosauravusEntity::new, MobCategory.CREATURE)
                    .sized(MobStats.width("coelurosauravus", 0.7F), MobStats.height("coelurosauravus", 0.4F))
                    .spawnDimensionsScale(MobStats.spawnScale("coelurosauravus"))
                    .clientTrackingRange(10)
                    .build("coelurosauravus"));

    /**
     * 「スペラエオプレウラ」— 洞窟に棲むアースロプレウラの近縁(グループ2)。発光菌糸を食べると
     * 背の筋が光り、時間をかけて暗くなる。他の Mob を襲わない。
     */
    public static final DeferredHolder<EntityType<?>, EntityType<SpelaeopleuraEntity>> SPELAEOPLEURA =
            ENTITY_TYPES.register("spelaeopleura", () -> EntityType.Builder
                    .of(SpelaeopleuraEntity::new, MobCategory.CREATURE)
                    .sized(MobStats.width("spelaeopleura", 0.7F), MobStats.height("spelaeopleura", 0.35F))
                    .spawnDimensionsScale(MobStats.spawnScale("spelaeopleura"))
                    .clientTrackingRange(9)
                    .build("spelaeopleura"));

    /**
     * 「ステノデクティア」— トンボに似たグループ2の Mob。空を飛び、蔦の実や熟した垂れ草の光る果実を食べる。
     * 「止まってから直線的に素早く動き、また止まる」を繰り返すトンボ特有の移動をする。体力が低く、
     * 時々木の枝に止まる。メガネウラに捕食される。
     */
    public static final DeferredHolder<EntityType<?>, EntityType<StenodictyaLobataEntity>> STENODICTYA_LOBATA =
            ENTITY_TYPES.register("stenodictya_lobata", () -> EntityType.Builder
                    .of(StenodictyaLobataEntity::new, MobCategory.CREATURE)
                    .sized(MobStats.width("stenodictya_lobata", 0.6F), MobStats.height("stenodictya_lobata", 0.4F))
                    .spawnDimensionsScale(MobStats.spawnScale("stenodictya_lobata"))
                    .clientTrackingRange(8)
                    .build("stenodictya_lobata"));

    /**
     * 「メガネウラ」— 巨大なトンボのグループ3(原生消費種)の Mob。飛び回って
     * {@link #STENODICTYA_LOBATA} を探して狩る。倒すたびに攻撃力・体力が 5% ずつ上昇する
     * ({@link com.yayuy.overgrownvegetation.entity.PredatorMob})。攻撃力・移動速度が高いが体力・防御力は低い。
     */
    public static final DeferredHolder<EntityType<?>, EntityType<MeganeuraEntity>> MEGANEURA =
            ENTITY_TYPES.register("meganeura", () -> EntityType.Builder
                    .of(MeganeuraEntity::new, MobCategory.MONSTER)
                    .sized(MobStats.width("meganeura", 1.0F), MobStats.height("meganeura", 0.5F))
                    .spawnDimensionsScale(MobStats.spawnScale("meganeura"))
                    .clientTrackingRange(10)
                    .build("meganeura"));

    /**
     * 「アルボラ」— グループ4(拡大種)の雑魚枠。カマキリ型で、武器腕を振り上げて叩きつける。
     * 過繁茂バイオームの外へ積極的に出ていき、グループ1〜10 に属していない生き物とプレイヤーを襲う。
     * 外では定期的にうずくまって胞子をばらまき、死ぬと周囲半径3ブロックを汚染する
     * ({@link com.yayuy.overgrownvegetation.entity.AbstractOvergrownInvaderEntity})。
     */
    public static final DeferredHolder<EntityType<?>, EntityType<ArboraEntity>> ARBORA =
            ENTITY_TYPES.register("arbora", () -> EntityType.Builder
                    .of(ArboraEntity::new, MobCategory.MONSTER)
                    .sized(MobStats.width("arbora", 1.0F), MobStats.height("arbora", 0.9F))
                    .spawnDimensionsScale(MobStats.spawnScale("arbora"))
                    .clientTrackingRange(10)
                    .build("arbora"));

    /**
     * 「アルボラの女王」(2026-09-20)— 巣の女王室の主。アルボラの 2 倍の大きさで、
     * 移動が遅く、とびかかりを持たない。地面を叩く衝撃波と溜めてからの広範囲の鎌を持ち、
     * 戦闘中にアルボラを産む({@link ArboraQueenEntity})。巣から出ず、離れても消えない。
     */
    public static final DeferredHolder<EntityType<?>, EntityType<ArboraQueenEntity>> ARBORA_QUEEN =
            ENTITY_TYPES.register("arbora_queen", () -> EntityType.Builder
                    .of(ArboraQueenEntity::new, MobCategory.MONSTER)
                    .sized(MobStats.width("arbora_queen", 2.0F), MobStats.height("arbora_queen", 2.0F))
                    .spawnDimensionsScale(MobStats.spawnScale("arbora_queen"))
                    .clientTrackingRange(16)
                    .build("arbora_queen"));

    /**
     * 「巣の核」(2026-09-20)— 女王室の奥。生きているあいだ、世界全体で新しく湧いた過繫茂 Mob を
     * 永続強化する({@link com.yayuy.overgrownvegetation.nest.NestBuffs})。倒すとその巣の噴出孔も止まる。
     */
    public static final DeferredHolder<EntityType<?>, EntityType<NestCoreEntity>> NEST_CORE =
            ENTITY_TYPES.register("nest_core", () -> EntityType.Builder
                    .of(NestCoreEntity::new, MobCategory.MONSTER)
                    .sized(MobStats.width("nest_core", 1.4F), MobStats.height("nest_core", 1.4F))
                    .spawnDimensionsScale(MobStats.spawnScale("nest_core"))
                    .clientTrackingRange(16)
                    .updateInterval(3)
                    .fireImmune()
                    .build("nest_core"));

    /**
     * 「フィロセンス」— グループ4(拡大種)の索敵役。長い首の先に花を持つ多脚の植物型。
     * ふだんは周囲 32 ブロックのプレイヤーに「視線」を、味方に「誘導」を配り、
     * 1 分プレイヤーが来なければ首を上げて花を開き、512/256 ブロックへ一気にばら撒く
     * ({@link com.yayuy.overgrownvegetation.entity.PhyllosensInfluence})。
     * 誘導を受けた味方は距離を問わず視線持ちのプレイヤーへ向かう。索敵中以外は細いビームで撃つ。
     */
    public static final DeferredHolder<EntityType<?>, EntityType<PhyllosensEntity>> PHYLLOSENS =
            ENTITY_TYPES.register("phyllosens", () -> EntityType.Builder
                    .of(PhyllosensEntity::new, MobCategory.MONSTER)
                    .sized(MobStats.width("phyllosens", 1.8F), MobStats.height("phyllosens", 3.5F))
                    .spawnDimensionsScale(MobStats.spawnScale("phyllosens"))
                    // 索敵の姿は「遠景の合図」なので、クライアントへ送る距離も広く取る
                    // (ユーザー要望 2026-09-14)。実際の上限はサーバーの描画距離側で頭打ちになる。
                    .clientTrackingRange(32)
                    .build("phyllosens"));

    /**
     * 「ヴェロフィル」— グループ4(拡大種)の爆撃役。膜翼の滑空型で、ふだんは上空を流している。
     * 標的を見つけると急降下して真上 8 ブロックから胞子爆弾を 1 発ずつ落とし、撃ち切ったら
     * 20 秒のあいだ上空を旋回して待つ({@link com.yayuy.overgrownvegetation.entity.VelophyllBombGoal})。
     * 3 分プレイヤーを見つけられなければ地上へ降りて這いながら汚染を広げる
     * ({@link com.yayuy.overgrownvegetation.entity.VelophyllGroundSporeGoal})。
     *
     * <p>当たり判定はモデル全体(幅 5.8 × 奥行き 5.0)ではなく<b>胴体</b>に合わせてある。
     * 翼と尾は判定からはみ出す — 幅 6 の判定を持つ Mob は経路探索が通らず、木立の上を飛べなくなるため。
     */
    public static final DeferredHolder<EntityType<?>, EntityType<VelophyllEntity>> VELOPHYLL =
            ENTITY_TYPES.register("velophyll", () -> EntityType.Builder
                    .of(VelophyllEntity::new, MobCategory.MONSTER)
                    .sized(MobStats.width("velophyll", 1.6F), MobStats.height("velophyll", 0.9F))
                    .spawnDimensionsScale(MobStats.spawnScale("velophyll"))
                    // 上空を飛ぶので、地上の Mob より遠くから見えていてほしい。
                    .clientTrackingRange(16)
                    .build("velophyll"));

    /**
     * 「スポロフィル」— グループ4(拡大種)の支援役。体はフィロセンスの流用で、頭が花ではなくキノコの傘。
     * 自分では攻撃せず、半径 32 ブロックに胞子を撒いて<b>味方を強化し・敵を弱らせ・胞子濃度を上げ・
     * その範囲を過繫茂バイオームと同じ扱いにする</b>
     * ({@link com.yayuy.overgrownvegetation.entity.SporophyllAura})。
     */
    public static final DeferredHolder<EntityType<?>, EntityType<SporophyllEntity>> SPOROPHYLL =
            ENTITY_TYPES.register("sporophyll", () -> EntityType.Builder
                    .of(SporophyllEntity::new, MobCategory.MONSTER)
                    .sized(MobStats.width("sporophyll", 2.0F), MobStats.height("sporophyll", 3.6F))
                    .spawnDimensionsScale(MobStats.spawnScale("sporophyll"))
                    .clientTrackingRange(16)
                    .build("sporophyll"));

    /** ヴェロフィルが落とす胞子爆弾({@link VelophyllBombEntity})。地形は一切壊さない。 */
    public static final DeferredHolder<EntityType<?>, EntityType<VelophyllBombEntity>> VELOPHYLL_BOMB =
            ENTITY_TYPES.register("velophyll_bomb", () -> EntityType.Builder
                    .<VelophyllBombEntity>of(VelophyllBombEntity::new, MobCategory.MISC)
                    .sized(0.7F, 0.7F)
                    .clientTrackingRange(12)
                    .updateInterval(2)
                    .fireImmune()
                    .noSummon()
                    .build("velophyll_bomb"));

    /**
     * 「ペトロラコサウルス」— 最古級の双弓類。細身で素早い小型の陸生捕食者(グループ3)。
     * 獲物は {@code predation.json} で設定する。
     */
    public static final DeferredHolder<EntityType<?>, EntityType<PetrolacosaurusEntity>> PETROLACOSAURUS =
            ENTITY_TYPES.register("petrolacosaurus", () -> EntityType.Builder
                    .of(PetrolacosaurusEntity::new, MobCategory.MONSTER)
                    .sized(MobStats.width("petrolacosaurus", 0.6F), MobStats.height("petrolacosaurus", 0.4F))
                    .spawnDimensionsScale(MobStats.spawnScale("petrolacosaurus"))
                    .clientTrackingRange(9)
                    .build("petrolacosaurus"));

    /**
     * 「イアンタサウルス」— 背に小さな帆を持つエダフォサウルス類の単弓類(グループ3)。
     * 昆虫食で、日光浴で体温を上げる。獲物は {@code predation.json} で設定する。
     */
    public static final DeferredHolder<EntityType<?>, EntityType<IanthasaurusEntity>> IANTHASAURUS =
            ENTITY_TYPES.register("ianthasaurus", () -> EntityType.Builder
                    .of(IanthasaurusEntity::new, MobCategory.MONSTER)
                    .sized(MobStats.width("ianthasaurus", 0.7F), MobStats.height("ianthasaurus", 0.6F))
                    .spawnDimensionsScale(MobStats.spawnScale("ianthasaurus"))
                    .clientTrackingRange(9)
                    .build("ianthasaurus"));

    /**
     * 「ヒロノムス」— 知られる限り最古の爬虫類。全長 20cm ほどの極小の昆虫食(グループ3)。
     * 獲物は {@code predation.json} で設定する。
     */
    public static final DeferredHolder<EntityType<?>, EntityType<HylonomusEntity>> HYLONOMUS =
            ENTITY_TYPES.register("hylonomus", () -> EntityType.Builder
                    .of(HylonomusEntity::new, MobCategory.MONSTER)
                    .sized(MobStats.width("hylonomus", 0.4F), MobStats.height("hylonomus", 0.3F))
                    .spawnDimensionsScale(MobStats.spawnScale("hylonomus"))
                    .clientTrackingRange(8)
                    .build("hylonomus"));

    /**
     * 「リゾドゥス」— 全長数メートルの巨大な肉鰭類(グループ2・水生)。泳ぐだけで他 Mob を襲わない。
     * 遅いが体力が非常に高い。
     */
    public static final DeferredHolder<EntityType<?>, EntityType<RhizodusEntity>> RHIZODUS =
            ENTITY_TYPES.register("rhizodus", () -> EntityType.Builder
                    .of(RhizodusEntity::new, MobCategory.WATER_CREATURE)
                    .sized(MobStats.width("rhizodus", 1.2F), MobStats.height("rhizodus", 1.2F))
                    .spawnDimensionsScale(MobStats.spawnScale("rhizodus"))
                    .clientTrackingRange(10)
                    .build("rhizodus"));

    /**
     * 「クセナカンサス」— 淡水性の古代サメ(グループ2・水生)。泳ぎ回るが他 Mob を襲わない。
     */
    public static final DeferredHolder<EntityType<?>, EntityType<XenacanthusEntity>> XENACANTHUS =
            ENTITY_TYPES.register("xenacanthus", () -> EntityType.Builder
                    .of(XenacanthusEntity::new, MobCategory.WATER_CREATURE)
                    .sized(MobStats.width("xenacanthus", 0.8F), MobStats.height("xenacanthus", 0.7F))
                    .spawnDimensionsScale(MobStats.spawnScale("xenacanthus"))
                    .clientTrackingRange(9)
                    .build("xenacanthus"));

    /**
     * 「オルタカンタス」— 大型の古代サメ(グループ3・水生)。過繁茂した水域の頂点捕食者。
     * 捕食対象・水中/水面の相手を襲う。既定でメガラクネも捕食対象。
     */
    public static final DeferredHolder<EntityType<?>, EntityType<OrthacanthusEntity>> ORTHACANTHUS =
            ENTITY_TYPES.register("orthacanthus", () -> EntityType.Builder
                    .of(OrthacanthusEntity::new, MobCategory.MONSTER)
                    .sized(MobStats.width("orthacanthus", 1.0F), MobStats.height("orthacanthus", 0.9F))
                    .spawnDimensionsScale(MobStats.spawnScale("orthacanthus"))
                    .clientTrackingRange(10)
                    .build("orthacanthus"));

    /**
     * 「メガラクネ」— かつてクモの仲間と誤同定された大型のウミサソリ(グループ3・水陸両生)。
     * 地上でも生きられるが積極的に水へ戻る。捕食対象・水中/水面の相手を襲う。
     */
    public static final DeferredHolder<EntityType<?>, EntityType<MegarachneEntity>> MEGARACHNE =
            ENTITY_TYPES.register("megarachne", () -> EntityType.Builder
                    .of(MegarachneEntity::new, MobCategory.MONSTER)
                    .sized(MobStats.width("megarachne", 1.0F), MobStats.height("megarachne", 0.7F))
                    .spawnDimensionsScale(MobStats.spawnScale("megarachne"))
                    .clientTrackingRange(10)
                    .build("megarachne"));

    /** レイドイベントの中核「過繫茂の苗床」(ボス)。 */
    public static final DeferredHolder<EntityType<?>, EntityType<OvergrownNexusEntity>> OVERGROWN_NEXUS =
            ENTITY_TYPES.register("overgrown_nexus", () -> EntityType.Builder
                    .of(OvergrownNexusEntity::new, MobCategory.MISC)
                    .sized(MobStats.width("overgrown_nexus", 3.0F), MobStats.height("overgrown_nexus", 6.5F))
                    .spawnDimensionsScale(MobStats.spawnScale("overgrown_nexus"))
                    .clientTrackingRange(16)
                    .updateInterval(3)
                    .fireImmune()
                    .build("overgrown_nexus"));

    /**
     * 超大型レイドボス「ドルガ=アーケロウス・ティタニカ」(攻城戦)の<b>近距離用</b>実エンティティ。遠方ではエンティティは存在せず、
     * プレイヤーが {@code siegeCloseRange} まで近づいたときだけ
     * {@link com.yayuy.overgrownvegetation.siege.SiegeData} が湧かせる
     * ({@link DorgaArchelausTitanicaEntity} のクラス javadoc 参照)。
     *
     * <p>当たり判定は {@code getDefaultDimensions} が config から決めるので、ここの {@code sized} は
     * 実質使われない初期値。{@code clientTrackingRange} は最大値 — 近距離用とはいえ、
     * バニラの上限(サーバー view-distance)いっぱいまでは追跡させたいため。
     */
    public static final DeferredHolder<EntityType<?>, EntityType<DorgaArchelausTitanicaEntity>> DORGA_ARCHELAUS_TITANICA =
            ENTITY_TYPES.register("dorga_archelaus_titanica", () -> EntityType.Builder
                    .of(DorgaArchelausTitanicaEntity::new, MobCategory.MISC)
                    .sized(40.0F, 16.0F)
                    .clientTrackingRange(32)
                    .updateInterval(1)
                    .fireImmune()
                    .noSummon()
                    // 保存しない — 体力も軌道も SiegeData 側が持っていて、近づけば湧き直すだけ。
                    // 保存すると、ワールドを読み直したときに古い本体と当たり判定の箱が二重に残る。
                    .noSave()
                    .build("dorga_archelaus_titanica"));

    /**
     * 攻城戦ボスの<b>当たり判定の箱</b>({@link TitanicaHitboxEntity})。体に沿って 28 個並ぶ見えないエンティティ。
     *
     * <p>{@code noSave} — ボス本体が毎 tick そろえ直すので保存する意味が無く、保存するとワールドを
     * 読み直したときに二重に存在してしまう。{@code updateInterval(1)} はボスが動き続けるため。
     */
    public static final DeferredHolder<EntityType<?>, EntityType<TitanicaHitboxEntity>> TITANICA_HITBOX =
            ENTITY_TYPES.register("titanica_hitbox", () -> EntityType.Builder
                    .<TitanicaHitboxEntity>of(TitanicaHitboxEntity::new, MobCategory.MISC)
                    .sized(16.0F, 16.0F)
                    .clientTrackingRange(32)
                    // 位置はクライアントが自分で計算する(TitanicaHitboxEntity.tick)ので、
                    // サーバーからの座標更新は保険でしかない。28 体ぶんを毎 tick 送る必要は無い。
                    .updateInterval(20)
                    .fireImmune()
                    .noSummon()
                    .noSave()
                    .build("titanica_hitbox"));

    /** 攻城戦ボスが真下へ落とす爆弾({@link SiegeBombEntity})。地形破壊は既定でゼロ。 */
    public static final DeferredHolder<EntityType<?>, EntityType<SiegeBombEntity>> SIEGE_BOMB =
            ENTITY_TYPES.register("siege_bomb", () -> EntityType.Builder
                    .<SiegeBombEntity>of(SiegeBombEntity::new, MobCategory.MISC)
                    .sized(2.0F, 2.0F)
                    .clientTrackingRange(16)
                    .updateInterval(2)
                    .fireImmune()
                    .noSummon()
                    .build("siege_bomb"));

    /**
     * 砲台の座席({@link SiegeCannonSeatEntity})。乗って照準するためだけの見えないエンティティで、
     * 位置は砲台の方位から両側で計算する。描画距離は砲台の描画距離より広く取る
     * (遠くのクライアントでも「誰が乗っているか」が分からないと砲身の向きが出せないため)。
     */
    public static final DeferredHolder<EntityType<?>, EntityType<SiegeCannonSeatEntity>> SIEGE_CANNON_SEAT =
            ENTITY_TYPES.register("siege_cannon_seat", () -> EntityType.Builder
                    .<SiegeCannonSeatEntity>of(SiegeCannonSeatEntity::new, MobCategory.MISC)
                    .sized(0.1F, 0.1F)
                    .clientTrackingRange(10)
                    .updateInterval(20)
                    .fireImmune()
                    .noSummon()
                    .noSave()
                    .build("siege_cannon_seat"));

    public static void register(IEventBus modBus) {
        ENTITY_TYPES.register(modBus);
    }

    private ModEntities() {
    }
}
