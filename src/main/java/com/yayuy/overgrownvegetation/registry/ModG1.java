package com.yayuy.overgrownvegetation.registry;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.CorruptedCorpseBlockEntity;
import com.yayuy.overgrownvegetation.block.GeoCorruptedCorpseBlock;
import com.yayuy.overgrownvegetation.corpse.G1Species;
import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.entity.AbstractParasitizedMonster;
import com.yayuy.overgrownvegetation.entity.*;
import com.yayuy.overgrownvegetation.entity.g1.G1Developed;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.DeferredSpawnEggItem;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * G1 段階 0(作り直し 2 回目、2026-11-08)の登録をまとめた所: 種ごとの死体ブロック・死体のブロックエンティティ・G1 の Mob・スポーンエッグ・属性。
 * 種の表は {@link G1Species}。
 *
 * <p>共有の登録クラス(ModBlocks・ModEntities・ModItems・ModEvents)は並行するセッションが同時に触るので、G1 の行はここに寄せた
 * (入れ物の {@code DeferredRegister} はそれぞれのものを使う)。{@link #init()} を MOD の構築子から呼んで、登録の前にこのクラスを読ませる。
 * 自然湧きは無い(10-22 決定)。湧くのは死体からの起き上がり・スポーンエッグ・コマンドだけ。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class ModG1 {

    private ModG1() {
    }

    /** MOD の構築子から呼ぶ(このクラスの static の登録を走らせるだけ)。 */
    public static void init() {
    }

    // ------------------------------------------------------------------ 死体ブロック
    /** 死体ブロックの性質(苔の音・光を通す・押されても動かない。描画は GeoBlockRenderer)。 */
    private static BlockBehaviour.Properties corpseProperties() {
        return BlockBehaviour.Properties.of() // 石の写しにしない(石は「正しい道具でないと落とさない・遅い」が付く。素手でも壊せるように)
                .mapColor(MapColor.COLOR_PURPLE)
                .strength(1.2F, 3.0F) // 11-08 ユーザー: 素手でも壊せる少し固いブロック、適正ツールは鍬(タグ mineable/hoe)。1.2 は【仮】
                .sound(SoundType.MOSS)
                .noOcclusion()
                .pushReaction(PushReaction.BLOCK);
    }

    private static final Map<G1Species, DeferredBlock<Block>> CORPSES = new EnumMap<>(G1Species.class);

    static {
        for (G1Species s : G1Species.values()) {
            CORPSES.put(s, ModBlocks.BLOCKS.registerBlock(s.assetName() + "_corpse", GeoCorruptedCorpseBlock::new, corpseProperties()));
        }
    }

    public static DeferredBlock<Block> corpse(G1Species s) {
        return CORPSES.get(s);
    }

    /** 死体のブロックエンティティ(名前は旧と同じ {@code overgrown_corpse})。 */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CorruptedCorpseBlockEntity>> CORRUPTED_CORPSE =
            ModBlockEntities.BLOCK_ENTITY_TYPES.register("overgrown_corpse", () -> BlockEntityType.Builder.of(
                    CorruptedCorpseBlockEntity::new,
                    CORPSES.values().stream().map(DeferredBlock::get).toArray(Block[]::new)).build(null));

    // ------------------------------------------------------------------ G1 の Mob
    private static final Map<G1Species, Supplier<EntityType<? extends AbstractParasitizedMonster>>> ENTITIES = new EnumMap<>(G1Species.class);
    private static final Map<G1Species, Supplier<AttributeSupplier.Builder>> ATTRIBUTES = new EnumMap<>(G1Species.class);

    private static <T extends AbstractParasitizedMonster> DeferredHolder<EntityType<?>, EntityType<T>> entity(
            G1Species s, EntityType.EntityFactory<T> factory, float width, float height, Supplier<AttributeSupplier.Builder> attributes) {
        String id = s.entityId();
        DeferredHolder<EntityType<?>, EntityType<T>> holder = ModEntities.ENTITY_TYPES.register(id, () -> EntityType.Builder
                .of(factory, MobCategory.MONSTER)
                .sized(MobStats.width(id, width), MobStats.height(id, height))
                .spawnDimensionsScale(MobStats.spawnScale(id))
                .clientTrackingRange(8)
                .build(id));
        ENTITIES.put(s, holder::get);
        ATTRIBUTES.put(s, attributes);
        return holder;
    }

    public static final DeferredHolder<EntityType<?>, EntityType<ParasitizedZombie>> PARASITIZED_ZOMBIE =
            entity(G1Species.ZOMBIE, ParasitizedZombie::new, 0.6F, 1.95F, ParasitizedZombie::createAttributes);
    public static final DeferredHolder<EntityType<?>, EntityType<ParasitizedBabyZombie>> PARASITIZED_BABY_ZOMBIE =
            entity(G1Species.BABY_ZOMBIE, ParasitizedBabyZombie::new, 0.3F, 0.975F, ParasitizedBabyZombie::createAttributes);
    public static final DeferredHolder<EntityType<?>, EntityType<ParasitizedVillager>> PARASITIZED_VILLAGER =
            entity(G1Species.VILLAGER, ParasitizedVillager::new, 0.6F, 1.95F, ParasitizedVillager::createAttributes);
    public static final DeferredHolder<EntityType<?>, EntityType<ParasitizedCow>> PARASITIZED_COW =
            entity(G1Species.COW, ParasitizedCow::new, 0.9F, 1.4F, ParasitizedCow::createAttributes);
    public static final DeferredHolder<EntityType<?>, EntityType<ParasitizedPig>> PARASITIZED_PIG =
            entity(G1Species.PIG, ParasitizedPig::new, 0.9F, 0.9F, ParasitizedPig::createAttributes);
    public static final DeferredHolder<EntityType<?>, EntityType<ParasitizedSheep>> PARASITIZED_SHEEP =
            entity(G1Species.SHEEP, ParasitizedSheep::new, 0.9F, 1.3F, ParasitizedSheep::createAttributes);
    public static final DeferredHolder<EntityType<?>, EntityType<ParasitizedChicken>> PARASITIZED_CHICKEN =
            entity(G1Species.CHICKEN, ParasitizedChicken::new, 0.4F, 0.7F, ParasitizedChicken::createAttributes);
    public static final DeferredHolder<EntityType<?>, EntityType<ParasitizedCreeper>> PARASITIZED_CREEPER =
            entity(G1Species.CREEPER, ParasitizedCreeper::new, 0.6F, 1.7F, ParasitizedCreeper::createAttributes);
    public static final DeferredHolder<EntityType<?>, EntityType<ParasitizedSkeleton>> PARASITIZED_SKELETON =
            entity(G1Species.SKELETON, ParasitizedSkeleton::new, 0.6F, 1.99F, ParasitizedSkeleton::createAttributes);
    public static final DeferredHolder<EntityType<?>, EntityType<ParasitizedSpider>> PARASITIZED_SPIDER =
            entity(G1Species.SPIDER, ParasitizedSpider::new, 1.4F, 0.9F, ParasitizedSpider::createAttributes);
    public static final DeferredHolder<EntityType<?>, EntityType<ParasitizedEnderman>> PARASITIZED_ENDERMAN =
            entity(G1Species.ENDERMAN, ParasitizedEnderman::new, 0.6F, 2.9F, ParasitizedEnderman::createAttributes);
    public static final DeferredHolder<EntityType<?>, EntityType<ParasitizedIronGolem>> PARASITIZED_IRON_GOLEM =
            entity(G1Species.IRON_GOLEM, ParasitizedIronGolem::new, 1.4F, 2.7F, ParasitizedIronGolem::createAttributes);
    public static final DeferredHolder<EntityType<?>, EntityType<ParasitizedWarden>> PARASITIZED_WARDEN =
            entity(G1Species.WARDEN, ParasitizedWarden::new, 0.9F, 2.9F, ParasitizedWarden::createAttributes);

    public static Supplier<EntityType<? extends AbstractParasitizedMonster>> entity(G1Species s) {
        return ENTITIES.get(s);
    }

    // ------------------------------------------------------------------ スポーンエッグ(地の色は元の Mob の色、点は寄生の紫。旧と同じ)
    private static final Map<G1Species, DeferredItem<DeferredSpawnEggItem>> EGGS = new EnumMap<>(G1Species.class);

    /** 卵の地の色(1 段目も同じ色を使う)。 */
    private static final Map<G1Species, Integer> EGG_BASE = new EnumMap<>(G1Species.class);

    private static void egg(G1Species s, DeferredHolder<EntityType<?>, ? extends EntityType<? extends AbstractParasitizedMonster>> type, int base) {
        EGG_BASE.put(s, base);
        EGGS.put(s, ModItems.ITEMS.registerItem(s.entityId() + "_spawn_egg",
                props -> new DeferredSpawnEggItem(type, base, 0x7B2D8C, props)));
    }

    static {
        egg(G1Species.ZOMBIE, PARASITIZED_ZOMBIE, 0x3B6642);
        egg(G1Species.BABY_ZOMBIE, PARASITIZED_BABY_ZOMBIE, 0x3B6642);
        egg(G1Species.VILLAGER, PARASITIZED_VILLAGER, 0x56603A);
        egg(G1Species.COW, PARASITIZED_COW, 0x4A3123);
        egg(G1Species.PIG, PARASITIZED_PIG, 0xF0A5A5);
        egg(G1Species.SHEEP, PARASITIZED_SHEEP, 0xE6E6E6);
        egg(G1Species.CHICKEN, PARASITIZED_CHICKEN, 0x9A9A86);
        egg(G1Species.CREEPER, PARASITIZED_CREEPER, 0x0DA70B);
        egg(G1Species.SKELETON, PARASITIZED_SKELETON, 0xA9A99A);
        egg(G1Species.SPIDER, PARASITIZED_SPIDER, 0x342D27);
        egg(G1Species.ENDERMAN, PARASITIZED_ENDERMAN, 0x1E2A1C);
        egg(G1Species.IRON_GOLEM, PARASITIZED_IRON_GOLEM, 0x8A8A7E);
        egg(G1Species.WARDEN, PARASITIZED_WARDEN, 0x0F2A2E);
    }

    /** 植物側の Mob のタブへ卵を並べる({@code ModCreativeTabs} から)。 */
    public static void acceptEggs(CreativeModeTab.Output output) {
        for (DeferredItem<DeferredSpawnEggItem> egg : EGGS.values()) {
            output.accept(egg.get());
        }
        for (DeferredItem<DeferredSpawnEggItem> egg : DEVELOPED_EGGS.values()) {
            output.accept(egg.get());
        }
        for (DeferredItem<DeferredSpawnEggItem> egg : ROOTED_EGGS.values()) {
            output.accept(egg.get());
        }
        output.accept(ROOT_GUARDIAN_EGG.get());
        for (DeferredItem<DeferredSpawnEggItem> egg : NURSERY_EGGS.values()) {
            output.accept(egg.get());
        }
    }

    // ------------------------------------------------------------------ 1 段目(2026-11-12、第 1 段)
    /**
     * 発達した寄生された○○ 11 種(1 つのクラス {@link DevelopedParasitized}、種の値は {@link G1Developed})。卵の点は苔の緑(段階 0 は寄生の紫)。
     * 自然には湧かない(司令塔ができるまで卵とコマンドだけ、11-09)。
     */
    private static final Map<G1Developed, DeferredHolder<EntityType<?>, EntityType<DevelopedParasitized>>> DEVELOPED = new EnumMap<>(G1Developed.class);
    private static final Map<G1Developed, DeferredItem<DeferredSpawnEggItem>> DEVELOPED_EGGS = new EnumMap<>(G1Developed.class);

    static {
        for (G1Developed d : G1Developed.values()) {
            String id = d.id();
            DeferredHolder<EntityType<?>, EntityType<DevelopedParasitized>> holder = ModEntities.ENTITY_TYPES.register(id, () -> EntityType.Builder
                    .of(DevelopedParasitized::new, MobCategory.MONSTER)
                    .sized(MobStats.width(id, d.width), MobStats.height(id, d.height))
                    .spawnDimensionsScale(MobStats.spawnScale(id))
                    .clientTrackingRange(8)
                    .build(id));
            DEVELOPED.put(d, holder);
            int base = EGG_BASE.getOrDefault(d.base(), 0x3B6642);
            DEVELOPED_EGGS.put(d, ModItems.ITEMS.registerItem(id + "_spawn_egg", props -> new DeferredSpawnEggItem(holder, base, 0x2F5A1E, props)));
        }
    }

    /**
     * 1 段目が噴く胞子の煙(2026-11-12、能力の第 1 回。見えない実体 = 円柱の中の暴露量・鈍足・霞み。見た目はクライアントの粒)。
     * {@link com.yayuy.overgrownvegetation.entity.g1.skill.G1SporeCloud}。
     */
    public static final DeferredHolder<EntityType<?>, EntityType<com.yayuy.overgrownvegetation.entity.g1.skill.G1SporeCloud>> SPORE_CLOUD =
            ModEntities.ENTITY_TYPES.register("g1_spore_cloud", () -> EntityType.Builder
                    .<com.yayuy.overgrownvegetation.entity.g1.skill.G1SporeCloud>of(com.yayuy.overgrownvegetation.entity.g1.skill.G1SporeCloud::new, MobCategory.MISC)
                    .sized(0.5F, 0.5F)
                    .noSummon()
                    .fireImmune()
                    .clientTrackingRange(8)
                    .updateInterval(20)
                    .build("g1_spore_cloud"));

    /** G1 が投げる胞子の塊(2026-11-13、村人の 1 段目の「胞子を配る」)。{@link com.yayuy.overgrownvegetation.entity.g1.skill.G1SporeLump}。 */
    public static final DeferredHolder<EntityType<?>, EntityType<com.yayuy.overgrownvegetation.entity.g1.skill.G1SporeLump>> SPORE_LUMP =
            ModEntities.ENTITY_TYPES.register("g1_spore_lump", () -> EntityType.Builder
                    .<com.yayuy.overgrownvegetation.entity.g1.skill.G1SporeLump>of(com.yayuy.overgrownvegetation.entity.g1.skill.G1SporeLump::new, MobCategory.MISC)
                    .sized(0.25F, 0.25F)
                    .noSummon()
                    .clientTrackingRange(4)
                    .updateInterval(10)
                    .build("g1_spore_lump"));

    // ------------------------------------------------------------------ 能力の第 2 回(2026-11-13: 牛・羊・鶏)
    /** 胞子の乳だまり(牛の 1 段目の (b)。地面に貼り付いた平たい円)。{@link com.yayuy.overgrownvegetation.entity.g1.skill.G1MilkPuddle}。 */
    public static final DeferredHolder<EntityType<?>, EntityType<com.yayuy.overgrownvegetation.entity.g1.skill.G1MilkPuddle>> MILK_PUDDLE =
            ModEntities.ENTITY_TYPES.register("g1_milk_puddle", () -> EntityType.Builder
                    .<com.yayuy.overgrownvegetation.entity.g1.skill.G1MilkPuddle>of(com.yayuy.overgrownvegetation.entity.g1.skill.G1MilkPuddle::new, MobCategory.MISC)
                    .sized(2.4F, 0.1F)
                    .noSummon()
                    .fireImmune()
                    .clientTrackingRange(6)
                    .updateInterval(20)
                    .build("g1_milk_puddle"));

    /**
     * 汚染された卵(鶏の 1 段目の (b) が産む。亀の卵の作り・効果は後で決める【決定 11-09】)。{@link com.yayuy.overgrownvegetation.block.CorruptedEggBlock}。
     * 素手で壊せる・何も落とさない・押されると壊れる(亀の卵と同じ)。音は湿った卵(カエルの卵)【仮】。アイテムは無い。
     */
    public static final DeferredBlock<com.yayuy.overgrownvegetation.block.CorruptedEggBlock> CORRUPTED_EGG = ModBlocks.BLOCKS.registerBlock(
            "corrupted_egg", com.yayuy.overgrownvegetation.block.CorruptedEggBlock::new, BlockBehaviour.Properties.of()
                    .mapColor(MapColor.TERRACOTTA_GREEN)
                    .strength(0.5F)
                    .sound(SoundType.FROGSPAWN)
                    .noOcclusion()
                    .noLootTable()
                    .pushReaction(PushReaction.DESTROY));

    /** 風に乗る綿毛の粒(羊の 1 段目の (c)。{@code client/G1FluffParticle}・出す側は {@link com.yayuy.overgrownvegetation.entity.g1.skill.G1FluffDrift})。 */
    public static final DeferredHolder<net.minecraft.core.particles.ParticleType<?>, net.minecraft.core.particles.SimpleParticleType> FLUFF =
            ModParticles.PARTICLE_TYPES.register("g1_fluff", () -> new net.minecraft.core.particles.SimpleParticleType(false));

    // ------------------------------------------------------------------ 能力の第 3 回(2026-11-13: クリーパー・スケルトン)
    /** 地面に落ちた熟した莢(クリーパーの 1 段目の (c)。10 秒で膨らんで割れる・叩けば潰れる)。{@link com.yayuy.overgrownvegetation.entity.g1.skill.G1SeedPod}。 */
    public static final DeferredHolder<EntityType<?>, EntityType<com.yayuy.overgrownvegetation.entity.g1.skill.G1SeedPod>> SEED_POD =
            ModEntities.ENTITY_TYPES.register("g1_seed_pod", () -> EntityType.Builder
                    .<com.yayuy.overgrownvegetation.entity.g1.skill.G1SeedPod>of(com.yayuy.overgrownvegetation.entity.g1.skill.G1SeedPod::new, MobCategory.MISC)
                    .sized(0.4F, 0.45F)
                    .clientTrackingRange(6)
                    .updateInterval(10)
                    .build("g1_seed_pod"));

    /** 植物の弓が射る胞子の塊(スケルトンの 1 段目の (b))。{@link com.yayuy.overgrownvegetation.entity.g1.skill.G1SporeBolt}。 */
    public static final DeferredHolder<EntityType<?>, EntityType<com.yayuy.overgrownvegetation.entity.g1.skill.G1SporeBolt>> SPORE_BOLT =
            ModEntities.ENTITY_TYPES.register("g1_spore_bolt", () -> EntityType.Builder
                    .<com.yayuy.overgrownvegetation.entity.g1.skill.G1SporeBolt>of(com.yayuy.overgrownvegetation.entity.g1.skill.G1SporeBolt::new, MobCategory.MISC)
                    .sized(0.3F, 0.3F)
                    .noSummon()
                    .clientTrackingRange(6)
                    .updateInterval(10)
                    .build("g1_spore_bolt"));

    // ------------------------------------------------------------------ 能力の第 4 回(2026-11-13: クモ・エンダーマン)
    /**
     * 根糸の巣(クモの 1 段目の (c) が外れて着いた所にできる。クモの巣の植物版【決定 11-09】)。{@link com.yayuy.overgrownvegetation.block.RootsilkWebBlock}。
     * 性質はバニラのクモの巣の写し(当たり無し・固さ 4・押されると壊れる)。正しい道具でなくても落とす(素手は遅い)。音は垂れ根【仮】。
     */
    public static final DeferredBlock<com.yayuy.overgrownvegetation.block.RootsilkWebBlock> ROOTSILK_WEB = ModBlocks.BLOCKS.registerBlock(
            "rootsilk_web", com.yayuy.overgrownvegetation.block.RootsilkWebBlock::new, BlockBehaviour.Properties.of()
                    .mapColor(MapColor.PLANT)
                    .strength(4.0F)
                    .sound(SoundType.HANGING_ROOTS)
                    .forceSolidOn()
                    .noCollission()
                    .noOcclusion()
                    .pushReaction(PushReaction.DESTROY));

    /** 根糸の巣のアイテム(ハサミで切ると落ちる。タブには並べていない)。 */
    public static final DeferredItem<net.minecraft.world.item.BlockItem> ROOTSILK_WEB_ITEM = ModItems.ITEMS.registerSimpleBlockItem(ROOTSILK_WEB);

    /** クモの 1 段目が吐く根糸の束(弾)。{@link com.yayuy.overgrownvegetation.entity.g1.skill.G1RootsilkBolt}。 */
    public static final DeferredHolder<EntityType<?>, EntityType<com.yayuy.overgrownvegetation.entity.g1.skill.G1RootsilkBolt>> ROOTSILK_BOLT =
            ModEntities.ENTITY_TYPES.register("g1_rootsilk_bolt", () -> EntityType.Builder
                    .<com.yayuy.overgrownvegetation.entity.g1.skill.G1RootsilkBolt>of(com.yayuy.overgrownvegetation.entity.g1.skill.G1RootsilkBolt::new, MobCategory.MISC)
                    .sized(0.3F, 0.3F)
                    .noSummon()
                    .clientTrackingRange(6)
                    .updateInterval(5)
                    .build("g1_rootsilk_bolt"));

    /** エンダーマンの 1 段目の (c-2) で汚染した地面から突き上がる根(見た目だけの短命の実体)。{@link com.yayuy.overgrownvegetation.entity.g1.skill.G1RootSpike}。 */
    public static final DeferredHolder<EntityType<?>, EntityType<com.yayuy.overgrownvegetation.entity.g1.skill.G1RootSpike>> ROOT_SPIKE =
            ModEntities.ENTITY_TYPES.register("g1_root_spike", () -> EntityType.Builder
                    .<com.yayuy.overgrownvegetation.entity.g1.skill.G1RootSpike>of(com.yayuy.overgrownvegetation.entity.g1.skill.G1RootSpike::new, MobCategory.MISC)
                    .sized(0.4F, 1.4F)
                    .noSave()
                    .fireImmune()
                    .clientTrackingRange(6)
                    .updateInterval(20)
                    .build("g1_root_spike"));

    public static DeferredHolder<EntityType<?>, EntityType<DevelopedParasitized>> developed(G1Developed d) {
        return DEVELOPED.get(d);
    }

    // ------------------------------------------------------------------ 根付き(2026-11-13、司令塔 第 1 段の区切り 2)
    /**
     * 根付き(1 つのクラス {@link com.yayuy.overgrownvegetation.entity.RootedParasitized}、種の値は {@link com.yayuy.overgrownvegetation.entity.g1.G1Rooted})。
     * 表に行のある種だけ登録する(アニメの無い姿は組み込まない)。卵の点は樹皮の茶。登録した種は、司令塔が「根付き」を選べるようになる
     * ({@link com.yayuy.overgrownvegetation.director.G1Fate#registerRooted})。
     */
    private static final Map<com.yayuy.overgrownvegetation.entity.g1.G1Rooted,
            DeferredHolder<EntityType<?>, EntityType<com.yayuy.overgrownvegetation.entity.RootedParasitized>>> ROOTED =
            new EnumMap<>(com.yayuy.overgrownvegetation.entity.g1.G1Rooted.class);
    private static final Map<com.yayuy.overgrownvegetation.entity.g1.G1Rooted, DeferredItem<DeferredSpawnEggItem>> ROOTED_EGGS =
            new EnumMap<>(com.yayuy.overgrownvegetation.entity.g1.G1Rooted.class);

    static {
        for (com.yayuy.overgrownvegetation.entity.g1.G1Rooted r : com.yayuy.overgrownvegetation.entity.g1.G1Rooted.values()) {
            String id = r.id();
            DeferredHolder<EntityType<?>, EntityType<com.yayuy.overgrownvegetation.entity.RootedParasitized>> holder =
                    ModEntities.ENTITY_TYPES.register(id, () -> EntityType.Builder
                            .of(com.yayuy.overgrownvegetation.entity.RootedParasitized::new, MobCategory.MONSTER)
                            .sized(MobStats.width(id, r.width), MobStats.height(id, r.height))
                            .clientTrackingRange(10)
                            .build(id));
            ROOTED.put(r, holder);
            int base = EGG_BASE.getOrDefault(r.base(), 0x3B6642);
            ROOTED_EGGS.put(r, ModItems.ITEMS.registerItem(id + "_spawn_egg", props -> new DeferredSpawnEggItem(holder, base, 0x5A4126, props)));
            com.yayuy.overgrownvegetation.director.G1Fate.registerRooted(r.base(), holder);
        }
    }

    // ------------------------------------------------------------------ 苗床(2026-11-13、司令塔 第 1 段の区切り 6)
    /**
     * 苗床 11 種(1 つのクラス {@link com.yayuy.overgrownvegetation.entity.NurseryParasitized}、種の値は
     * {@link com.yayuy.overgrownvegetation.entity.g1.G1Nursery})。卵の点は胞子の黄緑。登録した種は、司令塔が「苗床」を選べる。
     */
    private static final Map<com.yayuy.overgrownvegetation.entity.g1.G1Nursery,
            DeferredHolder<EntityType<?>, EntityType<com.yayuy.overgrownvegetation.entity.NurseryParasitized>>> NURSERY =
            new EnumMap<>(com.yayuy.overgrownvegetation.entity.g1.G1Nursery.class);
    private static final Map<com.yayuy.overgrownvegetation.entity.g1.G1Nursery, DeferredItem<DeferredSpawnEggItem>> NURSERY_EGGS =
            new EnumMap<>(com.yayuy.overgrownvegetation.entity.g1.G1Nursery.class);

    static {
        for (com.yayuy.overgrownvegetation.entity.g1.G1Nursery n : com.yayuy.overgrownvegetation.entity.g1.G1Nursery.values()) {
            String id = n.id();
            DeferredHolder<EntityType<?>, EntityType<com.yayuy.overgrownvegetation.entity.NurseryParasitized>> holder =
                    ModEntities.ENTITY_TYPES.register(id, () -> EntityType.Builder
                            .of(com.yayuy.overgrownvegetation.entity.NurseryParasitized::new, MobCategory.MONSTER)
                            .sized(MobStats.width(id, n.width), MobStats.height(id, n.height))
                            .clientTrackingRange(8)
                            .build(id));
            NURSERY.put(n, holder);
            int base = EGG_BASE.getOrDefault(n.base(), 0x3B6642);
            NURSERY_EGGS.put(n, ModItems.ITEMS.registerItem(id + "_spawn_egg", props -> new DeferredSpawnEggItem(holder, base, 0xBCC100, props)));
            com.yayuy.overgrownvegetation.director.G1Fate.registerNursery(n.base(), holder);
        }
    }

    public static DeferredHolder<EntityType<?>, EntityType<com.yayuy.overgrownvegetation.entity.NurseryParasitized>> nursery(
            com.yayuy.overgrownvegetation.entity.g1.G1Nursery n) {
        return NURSERY.get(n);
    }

    /** 根の守り手(根付きの村人が育てる根のゴーレム。2026-11-13)。素材 g1_root_guardian。 */
    public static final DeferredHolder<EntityType<?>, EntityType<com.yayuy.overgrownvegetation.entity.RootGuardian>> ROOT_GUARDIAN =
            ModEntities.ENTITY_TYPES.register("root_guardian", () -> EntityType.Builder
                    .of(com.yayuy.overgrownvegetation.entity.RootGuardian::new, MobCategory.MONSTER)
                    .sized(MobStats.width("root_guardian", 1.3F), MobStats.height("root_guardian", 1.9F))
                    .clientTrackingRange(8)
                    .build("root_guardian"));
    private static final DeferredItem<DeferredSpawnEggItem> ROOT_GUARDIAN_EGG =
            ModItems.ITEMS.registerItem("root_guardian_spawn_egg", props -> new DeferredSpawnEggItem(ROOT_GUARDIAN, 0x4A3A22, 0x7BA43A, props));

    public static DeferredHolder<EntityType<?>, EntityType<com.yayuy.overgrownvegetation.entity.RootedParasitized>> rooted(
            com.yayuy.overgrownvegetation.entity.g1.G1Rooted r) {
        return ROOTED.get(r);
    }

    // ------------------------------------------------------------------ 属性
    @SubscribeEvent
    public static void onEntityAttributeCreation(EntityAttributeCreationEvent event) {
        for (G1Species s : G1Species.values()) {
            event.put(ENTITIES.get(s).get(), MobStats.apply(ATTRIBUTES.get(s).get(), s.entityId()).build());
        }
        for (G1Developed d : G1Developed.values()) {
            event.put(DEVELOPED.get(d).get(), MobStats.apply(DevelopedParasitized.attributes(d), d.id()).build());
        }
        for (com.yayuy.overgrownvegetation.entity.g1.G1Rooted r : com.yayuy.overgrownvegetation.entity.g1.G1Rooted.values()) {
            event.put(ROOTED.get(r).get(), MobStats.apply(com.yayuy.overgrownvegetation.entity.RootedParasitized.attributes(r), r.id()).build());
        }
        event.put(ROOT_GUARDIAN.get(), MobStats.apply(com.yayuy.overgrownvegetation.entity.RootGuardian.attributes(), "root_guardian").build());
        for (com.yayuy.overgrownvegetation.entity.g1.G1Nursery n : com.yayuy.overgrownvegetation.entity.g1.G1Nursery.values()) {
            event.put(NURSERY.get(n).get(), MobStats.apply(com.yayuy.overgrownvegetation.entity.NurseryParasitized.attributes(n), n.id()).build());
        }
    }
}
