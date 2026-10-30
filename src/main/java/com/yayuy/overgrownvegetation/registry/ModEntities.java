package com.yayuy.overgrownvegetation.registry;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.entity.BlightFernEntity;
import com.yayuy.overgrownvegetation.entity.DorgaArchelausTitanicaEntity;
import com.yayuy.overgrownvegetation.entity.TitanicaHitboxEntity;
import com.yayuy.overgrownvegetation.entity.SiegeBombEntity;
import com.yayuy.overgrownvegetation.entity.SiegeCannonSeatEntity;
import com.yayuy.overgrownvegetation.entity.OvergrownNexusEntity;
import com.yayuy.overgrownvegetation.entity.ArboraEntity;
import com.yayuy.overgrownvegetation.entity.ArthropleuraEntity;
import com.yayuy.overgrownvegetation.entity.CoelurosauravusEntity;
import com.yayuy.overgrownvegetation.entity.LithopleuraEntity;
import com.yayuy.overgrownvegetation.entity.SpelaeopleuraEntity;
import com.yayuy.overgrownvegetation.entity.HiveNodeEntity;
import com.yayuy.overgrownvegetation.entity.HiveRitualEntity;
import com.yayuy.overgrownvegetation.entity.HiveRitualSeatEntity;
import com.yayuy.overgrownvegetation.entity.HylonomusEntity;
import com.yayuy.overgrownvegetation.entity.IanthasaurusEntity;
import com.yayuy.overgrownvegetation.entity.MeganeuraEntity;
import com.yayuy.overgrownvegetation.entity.MegarachneEntity;
import com.yayuy.overgrownvegetation.entity.OrthacanthusEntity;
import com.yayuy.overgrownvegetation.entity.AcumnesEntity;
import com.yayuy.overgrownvegetation.entity.AcumnesSpine;
import com.yayuy.overgrownvegetation.entity.BathymnesEntity;
import com.yayuy.overgrownvegetation.entity.ErebomnesEntity;
import com.yayuy.overgrownvegetation.entity.FodimnesEntity;
import com.yayuy.overgrownvegetation.entity.VoramnesEntity;
import com.yayuy.overgrownvegetation.entity.FalcimnesEntity;
import com.yayuy.overgrownvegetation.entity.ScutimnesEntity;
import com.yayuy.overgrownvegetation.entity.ScutimnesShockwave;
import com.yayuy.overgrownvegetation.entity.OvergrownShielderEntity;
import com.yayuy.overgrownvegetation.entity.PediculaEntity;
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
import com.yayuy.overgrownvegetation.entity.CrassigyrinusEntity;
import com.yayuy.overgrownvegetation.entity.DiplocaulusEntity;
import com.yayuy.overgrownvegetation.entity.EryopsEntity;
import com.yayuy.overgrownvegetation.entity.GiantSnailEntity;
import com.yayuy.overgrownvegetation.entity.HibbertopterusEntity;
import com.yayuy.overgrownvegetation.entity.LungfishEntity;
import com.yayuy.overgrownvegetation.entity.PulmonoscorpiusEntity;
import com.yayuy.overgrownvegetation.entity.StenodictyaNymphEntity;
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

    /** 巨大シダの寄生植物。侵食の起点。 */
    public static final DeferredHolder<EntityType<?>, EntityType<BlightFernEntity>> BLIGHT_FERN =
            ENTITY_TYPES.register("blight_fern", () -> EntityType.Builder
                    .of(BlightFernEntity::new, MobCategory.MONSTER)
                    .sized(MobStats.width("blight_fern", 1.4F), MobStats.height("blight_fern", 2.6F))
                    .spawnDimensionsScale(MobStats.spawnScale("blight_fern")) // 10-23 萌芽体は体格 1・当たり判定 1 × 2(見た目は 3.65。描画の箱は BlightFernEntity#getBoundingBoxForCulling で広げる)
                    .clientTrackingRange(12)
                    .updateInterval(3)
                    .build("blight_fern"));

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

    // 寄生された村人(G1、2026-10-09)。死体ブロックから起き上がる。
    public static final DeferredHolder<EntityType<?>, EntityType<com.yayuy.overgrownvegetation.entity.ParasitizedVillager>> PARASITIZED_VILLAGER =
            ENTITY_TYPES.register("parasitized_villager", () -> EntityType.Builder
                    .of(com.yayuy.overgrownvegetation.entity.ParasitizedVillager::new, MobCategory.MONSTER)
                    .sized(MobStats.width("parasitized_villager", 0.6F), MobStats.height("parasitized_villager", 1.95F))
                    .spawnDimensionsScale(MobStats.spawnScale("parasitized_villager"))
                    .clientTrackingRange(8)
                    .build("parasitized_villager"));

    // 寄生された鶏・スケルトン・エンダーマン・チビゾンビ(G1、2026-10-09)。死体ブロックから起き上がる。
    public static final DeferredHolder<EntityType<?>, EntityType<com.yayuy.overgrownvegetation.entity.ParasitizedChicken>> PARASITIZED_CHICKEN =
            ENTITY_TYPES.register("parasitized_chicken", () -> EntityType.Builder
                    .of(com.yayuy.overgrownvegetation.entity.ParasitizedChicken::new, MobCategory.CREATURE)
                    .sized(MobStats.width("parasitized_chicken", 0.4F), MobStats.height("parasitized_chicken", 0.7F))
                    .spawnDimensionsScale(MobStats.spawnScale("parasitized_chicken"))
                    .clientTrackingRange(10)
                    .build("parasitized_chicken"));
    public static final DeferredHolder<EntityType<?>, EntityType<com.yayuy.overgrownvegetation.entity.ParasitizedSkeleton>> PARASITIZED_SKELETON =
            ENTITY_TYPES.register("parasitized_skeleton", () -> EntityType.Builder
                    .of(com.yayuy.overgrownvegetation.entity.ParasitizedSkeleton::new, MobCategory.MONSTER)
                    .sized(MobStats.width("parasitized_skeleton", 0.6F), MobStats.height("parasitized_skeleton", 1.99F))
                    .spawnDimensionsScale(MobStats.spawnScale("parasitized_skeleton"))
                    .clientTrackingRange(8)
                    .build("parasitized_skeleton"));
    public static final DeferredHolder<EntityType<?>, EntityType<com.yayuy.overgrownvegetation.entity.ParasitizedEnderman>> PARASITIZED_ENDERMAN =
            ENTITY_TYPES.register("parasitized_enderman", () -> EntityType.Builder
                    .of(com.yayuy.overgrownvegetation.entity.ParasitizedEnderman::new, MobCategory.MONSTER)
                    .sized(MobStats.width("parasitized_enderman", 0.6F), MobStats.height("parasitized_enderman", 2.9F))
                    .spawnDimensionsScale(MobStats.spawnScale("parasitized_enderman"))
                    .clientTrackingRange(8)
                    .build("parasitized_enderman"));
    public static final DeferredHolder<EntityType<?>, EntityType<com.yayuy.overgrownvegetation.entity.ParasitizedBabyZombie>> PARASITIZED_BABY_ZOMBIE =
            ENTITY_TYPES.register("parasitized_baby_zombie", () -> EntityType.Builder
                    .of(com.yayuy.overgrownvegetation.entity.ParasitizedBabyZombie::new, MobCategory.MONSTER)
                    .sized(MobStats.width("parasitized_baby_zombie", 0.3F), MobStats.height("parasitized_baby_zombie", 0.975F))
                    .spawnDimensionsScale(MobStats.spawnScale("parasitized_baby_zombie"))
                    .clientTrackingRange(8)
                    .build("parasitized_baby_zombie"));

    // ネザーの媒介種(2026-10-09)。ネザーにだけ湧く(タグ nether_native)。全種燃える(ユーザー指定)ので fireImmune は付けない。
    public static final DeferredHolder<EntityType<?>, EntityType<com.yayuy.overgrownvegetation.entity.ParasitizedPiglin>> PARASITIZED_PIGLIN =
            ENTITY_TYPES.register("parasitized_piglin", () -> EntityType.Builder
                    .of(com.yayuy.overgrownvegetation.entity.ParasitizedPiglin::new, MobCategory.MONSTER)
                    .sized(MobStats.width("parasitized_piglin", 0.6F), MobStats.height("parasitized_piglin", 1.95F))
                    .spawnDimensionsScale(MobStats.spawnScale("parasitized_piglin"))
                    .clientTrackingRange(8)
                    .build("parasitized_piglin"));
    public static final DeferredHolder<EntityType<?>, EntityType<com.yayuy.overgrownvegetation.entity.ParasitizedPiglinBrute>> PARASITIZED_PIGLIN_BRUTE =
            ENTITY_TYPES.register("parasitized_piglin_brute", () -> EntityType.Builder
                    .of(com.yayuy.overgrownvegetation.entity.ParasitizedPiglinBrute::new, MobCategory.MONSTER)
                    .sized(MobStats.width("parasitized_piglin_brute", 0.6F), MobStats.height("parasitized_piglin_brute", 1.95F))
                    .spawnDimensionsScale(MobStats.spawnScale("parasitized_piglin_brute"))
                    .clientTrackingRange(8)
                    .build("parasitized_piglin_brute"));
    public static final DeferredHolder<EntityType<?>, EntityType<com.yayuy.overgrownvegetation.entity.ParasitizedZombifiedPiglin>> PARASITIZED_ZOMBIFIED_PIGLIN =
            ENTITY_TYPES.register("parasitized_zombified_piglin", () -> EntityType.Builder
                    .of(com.yayuy.overgrownvegetation.entity.ParasitizedZombifiedPiglin::new, MobCategory.MONSTER)
                    .sized(MobStats.width("parasitized_zombified_piglin", 0.6F), MobStats.height("parasitized_zombified_piglin", 1.95F))
                    .spawnDimensionsScale(MobStats.spawnScale("parasitized_zombified_piglin"))
                    .clientTrackingRange(8)
                    .build("parasitized_zombified_piglin"));
    public static final DeferredHolder<EntityType<?>, EntityType<com.yayuy.overgrownvegetation.entity.ParasitizedWitherSkeleton>> PARASITIZED_WITHER_SKELETON =
            ENTITY_TYPES.register("parasitized_wither_skeleton", () -> EntityType.Builder
                    .of(com.yayuy.overgrownvegetation.entity.ParasitizedWitherSkeleton::new, MobCategory.MONSTER)
                    .sized(MobStats.width("parasitized_wither_skeleton", 0.7F), MobStats.height("parasitized_wither_skeleton", 2.4F))
                    .spawnDimensionsScale(MobStats.spawnScale("parasitized_wither_skeleton"))
                    .clientTrackingRange(8)
                    .build("parasitized_wither_skeleton"));
    public static final DeferredHolder<EntityType<?>, EntityType<com.yayuy.overgrownvegetation.entity.ParasitizedHoglin>> PARASITIZED_HOGLIN =
            ENTITY_TYPES.register("parasitized_hoglin", () -> EntityType.Builder
                    .of(com.yayuy.overgrownvegetation.entity.ParasitizedHoglin::new, MobCategory.MONSTER)
                    .sized(MobStats.width("parasitized_hoglin", 1.3964844F), MobStats.height("parasitized_hoglin", 1.4F))
                    .spawnDimensionsScale(MobStats.spawnScale("parasitized_hoglin"))
                    .clientTrackingRange(8)
                    .build("parasitized_hoglin"));
    public static final DeferredHolder<EntityType<?>, EntityType<com.yayuy.overgrownvegetation.entity.ParasitizedZoglin>> PARASITIZED_ZOGLIN =
            ENTITY_TYPES.register("parasitized_zoglin", () -> EntityType.Builder
                    .of(com.yayuy.overgrownvegetation.entity.ParasitizedZoglin::new, MobCategory.MONSTER)
                    .sized(MobStats.width("parasitized_zoglin", 1.3964844F), MobStats.height("parasitized_zoglin", 1.4F))
                    .spawnDimensionsScale(MobStats.spawnScale("parasitized_zoglin"))
                    .clientTrackingRange(8)
                    .build("parasitized_zoglin"));
    public static final DeferredHolder<EntityType<?>, EntityType<com.yayuy.overgrownvegetation.entity.ParasitizedStrider>> PARASITIZED_STRIDER =
            ENTITY_TYPES.register("parasitized_strider", () -> EntityType.Builder
                    .of(com.yayuy.overgrownvegetation.entity.ParasitizedStrider::new, MobCategory.MONSTER)
                    .sized(MobStats.width("parasitized_strider", 0.9F), MobStats.height("parasitized_strider", 1.7F))
                    .spawnDimensionsScale(MobStats.spawnScale("parasitized_strider"))
                    .clientTrackingRange(10)
                    .build("parasitized_strider"));
    // 死体を作らず、死んだガスト・ブレイズがその場で入れ替わる(corpse/NetherReanimation)。
    public static final DeferredHolder<EntityType<?>, EntityType<com.yayuy.overgrownvegetation.entity.ParasitizedGhast>> PARASITIZED_GHAST =
            ENTITY_TYPES.register("parasitized_ghast", () -> EntityType.Builder
                    .of(com.yayuy.overgrownvegetation.entity.ParasitizedGhast::new, MobCategory.MONSTER)
                    .sized(MobStats.width("parasitized_ghast", 4.0F), MobStats.height("parasitized_ghast", 4.0F))
                    .eyeHeight(2.6F)
                    .spawnDimensionsScale(MobStats.spawnScale("parasitized_ghast"))
                    .clientTrackingRange(10)
                    .build("parasitized_ghast"));
    public static final DeferredHolder<EntityType<?>, EntityType<com.yayuy.overgrownvegetation.entity.ParasitizedBlaze>> PARASITIZED_BLAZE =
            ENTITY_TYPES.register("parasitized_blaze", () -> EntityType.Builder
                    .of(com.yayuy.overgrownvegetation.entity.ParasitizedBlaze::new, MobCategory.MONSTER)
                    .sized(MobStats.width("parasitized_blaze", 0.6F), MobStats.height("parasitized_blaze", 1.8F))
                    .spawnDimensionsScale(MobStats.spawnScale("parasitized_blaze"))
                    .clientTrackingRange(8)
                    .build("parasitized_blaze"));
    // 2026-10-10: アイアンゴーレム・ウォーデン。自然には湧かず、胞子感染で死んだ元の Mob の死体からだけ起き上がる(ユーザー指定)。
    public static final DeferredHolder<EntityType<?>, EntityType<com.yayuy.overgrownvegetation.entity.ParasitizedIronGolem>> PARASITIZED_IRON_GOLEM =
            ENTITY_TYPES.register("parasitized_iron_golem", () -> EntityType.Builder
                    .of(com.yayuy.overgrownvegetation.entity.ParasitizedIronGolem::new, MobCategory.MONSTER)
                    .sized(MobStats.width("parasitized_iron_golem", 1.4F), MobStats.height("parasitized_iron_golem", 2.7F))
                    .spawnDimensionsScale(MobStats.spawnScale("parasitized_iron_golem"))
                    .clientTrackingRange(10)
                    .build("parasitized_iron_golem"));
    public static final DeferredHolder<EntityType<?>, EntityType<com.yayuy.overgrownvegetation.entity.ParasitizedWarden>> PARASITIZED_WARDEN =
            ENTITY_TYPES.register("parasitized_warden", () -> EntityType.Builder
                    .of(com.yayuy.overgrownvegetation.entity.ParasitizedWarden::new, MobCategory.MONSTER)
                    .sized(MobStats.width("parasitized_warden", 0.9F), MobStats.height("parasitized_warden", 2.9F))
                    .spawnDimensionsScale(MobStats.spawnScale("parasitized_warden"))
                    .clientTrackingRange(16)
                    .build("parasitized_warden"));

    // G1 の作り直し 第 1 段(2026-10-22): 進化 1 段目(G1)。司令塔が「進化」を選んだ段階 0 が 2 日で変わる(g1/G1Evolutions)。
    public static final DeferredHolder<EntityType<?>, EntityType<com.yayuy.overgrownvegetation.entity.VinearmZombie>> VINEARM_ZOMBIE =
            ENTITY_TYPES.register("vinearm_zombie", () -> EntityType.Builder
                    .of(com.yayuy.overgrownvegetation.entity.VinearmZombie::new, MobCategory.MONSTER)
                    .sized(MobStats.width("vinearm_zombie", 0.6F), MobStats.height("vinearm_zombie", 1.95F))
                    .spawnDimensionsScale(MobStats.spawnScale("vinearm_zombie"))
                    .clientTrackingRange(8)
                    .build("vinearm_zombie"));
    public static final DeferredHolder<EntityType<?>, EntityType<com.yayuy.overgrownvegetation.entity.HollowVillager>> HOLLOW_VILLAGER =
            ENTITY_TYPES.register("hollow_villager", () -> EntityType.Builder
                    .of(com.yayuy.overgrownvegetation.entity.HollowVillager::new, MobCategory.MONSTER)
                    .sized(MobStats.width("hollow_villager", 0.6F), MobStats.height("hollow_villager", 1.95F))
                    .spawnDimensionsScale(MobStats.spawnScale("hollow_villager"))
                    .clientTrackingRange(8)
                    .build("hollow_villager"));
    public static final DeferredHolder<EntityType<?>, EntityType<com.yayuy.overgrownvegetation.entity.SporesacCow>> SPORESAC_COW =
            ENTITY_TYPES.register("sporesac_cow", () -> EntityType.Builder
                    .of(com.yayuy.overgrownvegetation.entity.SporesacCow::new, MobCategory.CREATURE)
                    .sized(MobStats.width("sporesac_cow", 0.9F), MobStats.height("sporesac_cow", 1.4F))
                    .spawnDimensionsScale(MobStats.spawnScale("sporesac_cow"))
                    .clientTrackingRange(10)
                    .build("sporesac_cow"));
    public static final DeferredHolder<EntityType<?>, EntityType<com.yayuy.overgrownvegetation.entity.PoddedCreeper>> PODDED_CREEPER =
            ENTITY_TYPES.register("podded_creeper", () -> EntityType.Builder
                    .of(com.yayuy.overgrownvegetation.entity.PoddedCreeper::new, MobCategory.MONSTER)
                    .sized(MobStats.width("podded_creeper", 0.6F), MobStats.height("podded_creeper", 1.7F))
                    .spawnDimensionsScale(MobStats.spawnScale("podded_creeper"))
                    .clientTrackingRange(8)
                    .build("podded_creeper"));
    // 進化 2 段目(1 段目からさらに 5 日)。能力で分類: 蔦の処刑者・根の打ち手 = G5、歩く胞子嚢・破裂莢 = G4(タグ group_N.json)。
    public static final DeferredHolder<EntityType<?>, EntityType<com.yayuy.overgrownvegetation.entity.VineExecutioner>> VINE_EXECUTIONER =
            ENTITY_TYPES.register("vine_executioner", () -> EntityType.Builder
                    .of(com.yayuy.overgrownvegetation.entity.VineExecutioner::new, MobCategory.MONSTER)
                    .sized(MobStats.width("vine_executioner", 0.7F), MobStats.height("vine_executioner", 2.2F))
                    .spawnDimensionsScale(MobStats.spawnScale("vine_executioner"))
                    .clientTrackingRange(10)
                    .build("vine_executioner"));
    public static final DeferredHolder<EntityType<?>, EntityType<com.yayuy.overgrownvegetation.entity.RootStriker>> ROOT_STRIKER =
            ENTITY_TYPES.register("root_striker", () -> EntityType.Builder
                    .of(com.yayuy.overgrownvegetation.entity.RootStriker::new, MobCategory.MONSTER)
                    .sized(MobStats.width("root_striker", 0.7F), MobStats.height("root_striker", 2.2F))
                    .spawnDimensionsScale(MobStats.spawnScale("root_striker"))
                    .clientTrackingRange(10)
                    .build("root_striker"));
    public static final DeferredHolder<EntityType<?>, EntityType<com.yayuy.overgrownvegetation.entity.WalkingSporangium>> WALKING_SPORANGIUM =
            ENTITY_TYPES.register("walking_sporangium", () -> EntityType.Builder
                    .of(com.yayuy.overgrownvegetation.entity.WalkingSporangium::new, MobCategory.MONSTER)
                    .sized(MobStats.width("walking_sporangium", 1.3F), MobStats.height("walking_sporangium", 2.0F))
                    .spawnDimensionsScale(MobStats.spawnScale("walking_sporangium"))
                    .clientTrackingRange(10)
                    .build("walking_sporangium"));
    public static final DeferredHolder<EntityType<?>, EntityType<com.yayuy.overgrownvegetation.entity.BurstPod>> BURST_POD =
            ENTITY_TYPES.register("burst_pod", () -> EntityType.Builder
                    .of(com.yayuy.overgrownvegetation.entity.BurstPod::new, MobCategory.MONSTER)
                    .sized(MobStats.width("burst_pod", 0.8F), MobStats.height("burst_pod", 1.6F))
                    .spawnDimensionsScale(MobStats.spawnScale("burst_pod"))
                    .clientTrackingRange(10)
                    .build("burst_pod"));

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
     * 「スクティムネス」— 甲殻の盾を 2 枚構えた 8 本脚の生き物。グループ5(適応種)の陸の盾役(2026-09-23)。
     * 体格 1.6 倍で全長 7.3 ブロック。当たり判定は脚の幅(実効 2.88 x 1.44)だけを覆い、尾は外へ出る。
     * 向きの同期は毎 tick(ゆっくり回るのが売りの Mob なので、2 tick おきの補間だと回り方がかくつく)。
     */
    public static final DeferredHolder<EntityType<?>, EntityType<ScutimnesEntity>> SCUTIMNES =
            ENTITY_TYPES.register("scutimnes", () -> EntityType.Builder
                    .of(ScutimnesEntity::new, MobCategory.MONSTER)
                    .sized(MobStats.width("scutimnes", 1.8F), MobStats.height("scutimnes", 0.9F))
                    .spawnDimensionsScale(MobStats.spawnScale("scutimnes"))
                    .clientTrackingRange(12)
                    .updateInterval(1)
                    .build("scutimnes"));

    /**
     * スクティムネスの叩きつけの衝撃波({@link ScutimnesShockwave})。軽いエンティティ 1 体で、ブロックが持ち上がって
     * 波打つのは描画だけ(地形は変えない)。約 1 秒で消え、保存しない。
     */
    public static final DeferredHolder<EntityType<?>, EntityType<ScutimnesShockwave>> SCUTIMNES_SHOCKWAVE =
            ENTITY_TYPES.register("scutimnes_shockwave", () -> EntityType.Builder
                    .<ScutimnesShockwave>of(ScutimnesShockwave::new, MobCategory.MISC)
                    .sized(0.5F, 0.5F)
                    .clientTrackingRange(8)
                    .updateInterval(1)
                    .build("scutimnes_shockwave"));

    /**
     * 「アクムネス」— ヒメバチの姿の、グループ5(適応種)の空の狙撃役(2026-09-28)。体格 1.8 倍で全長 10.6 ブロック(針込み)。
     * 当たり判定は胸・腹の前半・垂らした脚(実効 2.52 x 3.24)。針・翅・触角は外へ出る。
     * 向きの同期は毎 tick(長い針が回るのを滑らかに見せる)。上空から撃つので追跡の範囲は広め。
     */
    public static final DeferredHolder<EntityType<?>, EntityType<AcumnesEntity>> ACUMNES =
            ENTITY_TYPES.register("acumnes", () -> EntityType.Builder
                    .of(AcumnesEntity::new, MobCategory.MONSTER)
                    .sized(MobStats.width("acumnes", 1.4F), MobStats.height("acumnes", 1.8F))
                    .eyeHeight(1.42F)
                    .spawnDimensionsScale(MobStats.spawnScale("acumnes"))
                    .clientTrackingRange(16)
                    .updateInterval(1)
                    .build("acumnes"));

    /** アクムネスが撃つ棘({@link AcumnesSpine})。まっすぐ速く飛ぶので位置の同期は毎 tick。保存しない。 */
    /** パチンコの弾(2026-10-16、⑯)。 */
    public static final DeferredHolder<EntityType<?>, EntityType<com.yayuy.overgrownvegetation.entity.SlingshotShot>> SLINGSHOT_SHOT =
            ENTITY_TYPES.register("slingshot_shot", () -> EntityType.Builder
                    .<com.yayuy.overgrownvegetation.entity.SlingshotShot>of(com.yayuy.overgrownvegetation.entity.SlingshotShot::new, MobCategory.MISC)
                    .sized(0.25F, 0.25F).clientTrackingRange(4).updateInterval(10).build("slingshot_shot"));
    /** 粘りの投網(2026-10-16、⑳)。 */
    public static final DeferredHolder<EntityType<?>, EntityType<com.yayuy.overgrownvegetation.entity.StickyNet>> STICKY_NET =
            ENTITY_TYPES.register("sticky_net", () -> EntityType.Builder
                    .<com.yayuy.overgrownvegetation.entity.StickyNet>of(com.yayuy.overgrownvegetation.entity.StickyNet::new, MobCategory.MISC)
                    .sized(0.25F, 0.25F).clientTrackingRange(4).updateInterval(10).build("sticky_net"));
    /** ⑪ 灰煙玉(2026-10-16)。 */
    public static final DeferredHolder<EntityType<?>, EntityType<com.yayuy.overgrownvegetation.entity.AshSmokeBomb>> ASH_SMOKE_BOMB =
            ENTITY_TYPES.register("ash_smoke_bomb", () -> EntityType.Builder
                    .<com.yayuy.overgrownvegetation.entity.AshSmokeBomb>of(com.yayuy.overgrownvegetation.entity.AshSmokeBomb::new, MobCategory.MISC)
                    .sized(0.25F, 0.25F).clientTrackingRange(4).updateInterval(10).build("ash_smoke_bomb"));
    /** ⑧ 焼夷瓶(2026-10-16)。 */
    public static final DeferredHolder<EntityType<?>, EntityType<com.yayuy.overgrownvegetation.purify.IncendiaryFlask>> INCENDIARY_FLASK =
            ENTITY_TYPES.register("incendiary_flask", () -> EntityType.Builder
                    .<com.yayuy.overgrownvegetation.purify.IncendiaryFlask>of(com.yayuy.overgrownvegetation.purify.IncendiaryFlask::new, MobCategory.MISC)
                    .sized(0.25F, 0.25F).clientTrackingRange(4).updateInterval(10).build("incendiary_flask"));

    public static final DeferredHolder<EntityType<?>, EntityType<AcumnesSpine>> ACUMNES_SPINE =
            ENTITY_TYPES.register("acumnes_spine", () -> EntityType.Builder
                    .<AcumnesSpine>of(AcumnesSpine::new, MobCategory.MISC)
                    .sized(0.3F, 0.3F)
                    .clientTrackingRange(8)
                    .updateInterval(1)
                    .fireImmune()
                    .noSummon()
                    .build("acumnes_spine"));

    // --- G5 の低キューブ 4 種(2026-10-01 足場)。素の判定の大きさ・同期の間隔は仮決め(実装係が変えてよい)。
    //     描画倍率(NOTES の「描画倍率」)は mobs.json の scale で掛かり、判定も一緒に拡大される。

    /**
     * 「バティムネス」— 海のカメロケラス(長さ 11 ブロック)。体格 2 倍で素の判定 1.25 x 1.25 → 実効 2.5 x 2.5
     * (本体だけ。頭と殻の後ろ半分は部位の判定を足す案。NOTES)。長い体が回るので向きの同期は毎 tick。
     */
    public static final DeferredHolder<EntityType<?>, EntityType<BathymnesEntity>> BATHYMNES =
            ENTITY_TYPES.register("bathymnes", () -> EntityType.Builder
                    .of(BathymnesEntity::new, MobCategory.MONSTER)
                    .sized(MobStats.width("bathymnes", 1.25F), MobStats.height("bathymnes", 1.25F))
                    .spawnDimensionsScale(MobStats.spawnScale("bathymnes"))
                    .clientTrackingRange(16)
                    .updateInterval(1)
                    .build("bathymnes"));

    /**
     * 「エレボムネス」— 巨大な洞窟の空中を泳ぐアノマロカリス類(長さ 8.6 ブロック)。体格 2 倍で素の判定 1.75 x 1.05 → 実効 3.5 x 2.1、
     * 目の高さ 0.8 → 1.6(NOTES の目安)。頭は判定の外(前 2.4〜4.8)。長い体が回るので向きの同期は毎 tick。
     */
    public static final DeferredHolder<EntityType<?>, EntityType<ErebomnesEntity>> EREBOMNES =
            ENTITY_TYPES.register("erebomnes", () -> EntityType.Builder
                    .of(ErebomnesEntity::new, MobCategory.MONSTER)
                    .sized(MobStats.width("erebomnes", 1.75F), MobStats.height("erebomnes", 1.05F))
                    .eyeHeight(0.8F)
                    .spawnDimensionsScale(MobStats.spawnScale("erebomnes"))
                    .clientTrackingRange(12)
                    .updateInterval(1)
                    .build("erebomnes"));

    /**
     * 「ヴォラムネス」— 地中を泳ぐ大型の蠕虫(地上に出ると高さ 8.7 ブロック)。体格 2 倍で素の判定 1.25 x 4.25 → 実効 2.5 x 8.5
     * (地上に出た姿。NOTES)。地面から素早く出入りするので同期は毎 tick。
     */
    public static final DeferredHolder<EntityType<?>, EntityType<VoramnesEntity>> VORAMNES =
            ENTITY_TYPES.register("voramnes", () -> EntityType.Builder
                    .of(VoramnesEntity::new, MobCategory.MONSTER)
                    .sized(MobStats.width("voramnes", 1.25F), MobStats.height("voramnes", 4.25F))
                    .spawnDimensionsScale(MobStats.spawnScale("voramnes"))
                    .clientTrackingRange(12)
                    .updateInterval(1)
                    .build("voramnes"));

    /**
     * 「フォディムネス」— 地中の砂虫型のワームの<b>頭</b>(節と尾は PartEntity)。体格 1、判定 0.65 x 0.66(頭の目安。NOTES)。
     * 節の位置は頭の通った道から両側で同じ式で出す(ユーザー指定)ので、頭の位置の同期は毎 tick。
     */
    public static final DeferredHolder<EntityType<?>, EntityType<FodimnesEntity>> FODIMNES =
            ENTITY_TYPES.register("fodimnes", () -> EntityType.Builder
                    .of(FodimnesEntity::new, MobCategory.MONSTER)
                    .sized(MobStats.width("fodimnes", 0.65F), MobStats.height("fodimnes", 0.66F))
                    .spawnDimensionsScale(MobStats.spawnScale("fodimnes"))
                    .clientTrackingRange(10)
                    .updateInterval(1)
                    .build("fodimnes"));

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

    /**
     * 「ペディキュラ」— グループ4(拡大種)の漂流種(2026-09-23)。上空を漂う巨大な胞子の塊で、
     * 過繫茂バイオームの無い方へ流れながら、いるチャンクの胞子濃度を 100% にしていく。
     * 撃ち落とすと小さな胞子爆弾を散らし、着弾点に汚染シダが湧く({@link PediculaEntity})。
     *
     * <p>当たり判定は main の大きな立方体がどの向きに回っても収まる立方体(一辺 3.6、中心の高さ 1.8 =
     * 描画の回転の中心)。まわりの塊(sub)ははみ出す。経路探索は使わないので、幅 3 超えの制約は関係ない。
     */
    public static final DeferredHolder<EntityType<?>, EntityType<PediculaEntity>> PEDICULA =
            ENTITY_TYPES.register("pedicula", () -> EntityType.Builder
                    .of(PediculaEntity::new, MobCategory.MONSTER)
                    .sized(MobStats.width("pedicula", 3.6F), MobStats.height("pedicula", 3.6F))
                    .spawnDimensionsScale(MobStats.spawnScale("pedicula"))
                    // 上空に浮かんでいるので、遠くから見えていてほしい(ヴェロフィルと同じ)。
                    .clientTrackingRange(16)
                    .build("pedicula"));

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

    // --- G2・G3 の新しい 8 種(2026-10-22)。生成器 tools/g5-models/mobs/G23N*。大きさは仮決め(ユーザー了承「仮の大きさのままでよい」) ---

    /** 「ディプロカウルス」— ブーメラン形の頭の両生類(グループ2・水生)。浅瀬の群れ。 */
    public static final DeferredHolder<EntityType<?>, EntityType<DiplocaulusEntity>> DIPLOCAULUS =
            ENTITY_TYPES.register("diplocaulus", () -> EntityType.Builder
                    .of(DiplocaulusEntity::new, MobCategory.WATER_CREATURE)
                    .sized(MobStats.width("diplocaulus", 0.9F), MobStats.height("diplocaulus", 0.3F))
                    .spawnDimensionsScale(MobStats.spawnScale("diplocaulus"))
                    .clientTrackingRange(8)
                    .build("diplocaulus"));

    /** 「肺魚」— 川底の泥に潜み、雨の日に陸へ這い出す(グループ2・水生、陸でも息ができる)。 */
    public static final DeferredHolder<EntityType<?>, EntityType<LungfishEntity>> LUNGFISH =
            ENTITY_TYPES.register("lungfish", () -> EntityType.Builder
                    .of(LungfishEntity::new, MobCategory.WATER_CREATURE)
                    .sized(MobStats.width("lungfish", 0.6F), MobStats.height("lungfish", 0.4F))
                    .spawnDimensionsScale(MobStats.spawnScale("lungfish"))
                    .clientTrackingRange(8)
                    .build("lungfish"));

    /** 「ヒベルトプテルス」— 湖底を掃く大型のウミサソリ(グループ2・水陸両生)。 */
    public static final DeferredHolder<EntityType<?>, EntityType<HibbertopterusEntity>> HIBBERTOPTERUS =
            ENTITY_TYPES.register("hibbertopterus", () -> EntityType.Builder
                    .of(HibbertopterusEntity::new, MobCategory.WATER_CREATURE)
                    .sized(MobStats.width("hibbertopterus", 1.3F), MobStats.height("hibbertopterus", 0.5F))
                    .spawnDimensionsScale(MobStats.spawnScale("hibbertopterus"))
                    .clientTrackingRange(10)
                    .build("hibbertopterus"));

    /** 「巨大な陸生カタツムリ」(グループ2)。地上の大型の草食の群れ。 */
    public static final DeferredHolder<EntityType<?>, EntityType<GiantSnailEntity>> GIANT_SNAIL =
            ENTITY_TYPES.register("giant_snail", () -> EntityType.Builder
                    .of(GiantSnailEntity::new, MobCategory.CREATURE)
                    .sized(MobStats.width("giant_snail", 0.7F), MobStats.height("giant_snail", 0.9F))
                    .spawnDimensionsScale(MobStats.spawnScale("giant_snail"))
                    .clientTrackingRange(8)
                    .build("giant_snail"));

    /** 「梢の巨大な幼虫」— ステノディクティアの幼虫(グループ2)。 */
    public static final DeferredHolder<EntityType<?>, EntityType<StenodictyaNymphEntity>> STENODICTYA_NYMPH =
            ENTITY_TYPES.register("stenodictya_nymph", () -> EntityType.Builder
                    .of(StenodictyaNymphEntity::new, MobCategory.CREATURE)
                    .sized(MobStats.width("stenodictya_nymph", 0.6F), MobStats.height("stenodictya_nymph", 0.45F))
                    .spawnDimensionsScale(MobStats.spawnScale("stenodictya_nymph"))
                    .clientTrackingRange(8)
                    .build("stenodictya_nymph"));

    /** 「エリオプス」— 岸で待ち伏せる大型の両生類(グループ3)。 */
    public static final DeferredHolder<EntityType<?>, EntityType<EryopsEntity>> ERYOPS =
            ENTITY_TYPES.register("eryops", () -> EntityType.Builder
                    .of(EryopsEntity::new, MobCategory.MONSTER)
                    .sized(MobStats.width("eryops", 1.2F), MobStats.height("eryops", 0.5F))
                    .spawnDimensionsScale(MobStats.spawnScale("eryops"))
                    .clientTrackingRange(10)
                    .build("eryops"));

    /** 「プルモノスコルピウス」— 夜に倒木の下から出る巨大サソリ(グループ3)。 */
    public static final DeferredHolder<EntityType<?>, EntityType<PulmonoscorpiusEntity>> PULMONOSCORPIUS =
            ENTITY_TYPES.register("pulmonoscorpius", () -> EntityType.Builder
                    .of(PulmonoscorpiusEntity::new, MobCategory.MONSTER)
                    .sized(MobStats.width("pulmonoscorpius", 1.0F), MobStats.height("pulmonoscorpius", 0.6F))
                    .spawnDimensionsScale(MobStats.spawnScale("pulmonoscorpius"))
                    .clientTrackingRange(9)
                    .build("pulmonoscorpius"));

    /** 「クラッシギリヌス」— 濁った水で待ち伏せるウナギ型の四肢動物(グループ3・水生)。 */
    public static final DeferredHolder<EntityType<?>, EntityType<CrassigyrinusEntity>> CRASSIGYRINUS =
            ENTITY_TYPES.register("crassigyrinus", () -> EntityType.Builder
                    .of(CrassigyrinusEntity::new, MobCategory.MONSTER)
                    .sized(MobStats.width("crassigyrinus", 1.0F), MobStats.height("crassigyrinus", 0.45F))
                    .spawnDimensionsScale(MobStats.spawnScale("crassigyrinus"))
                    .clientTrackingRange(10)
                    .build("crassigyrinus"));

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

    /**
     * 空の巨大生物の<b>当たりの受け手</b>({@link com.yayuy.overgrownvegetation.skyboss.SkyBossHitboxEntity})。飛び道具が当たる所・剣が届く所へだけ置く見えない小さな箱。
     * {@code noSave}(毎 tick 置き直す)、追跡は近くだけ。
     */
    public static final DeferredHolder<EntityType<?>, EntityType<com.yayuy.overgrownvegetation.skyboss.SkyBossHitboxEntity>> SKY_BOSS_HITBOX =
            ENTITY_TYPES.register("sky_boss_hitbox", () -> EntityType.Builder
                    .<com.yayuy.overgrownvegetation.skyboss.SkyBossHitboxEntity>of(com.yayuy.overgrownvegetation.skyboss.SkyBossHitboxEntity::new, MobCategory.MISC)
                    .sized(1.0F, 1.0F)
                    .clientTrackingRange(4)
                    .updateInterval(2)
                    .fireImmune()
                    .noSummon()
                    .noSave()
                    .build("sky_boss_hitbox"));

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
