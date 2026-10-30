package com.yayuy.overgrownvegetation.registry;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.CorruptedCorpseBlockEntity;
import com.yayuy.overgrownvegetation.block.LithopleuraHuskBlockEntity;
import com.yayuy.overgrownvegetation.block.DecontaminatorBlockEntity;
import com.yayuy.overgrownvegetation.block.CorruptedLianaBlockEntity;
import com.yayuy.overgrownvegetation.block.OvergrownBarrelBlockEntity;
import com.yayuy.overgrownvegetation.block.OvergrownChestBlockEntity;
import com.yayuy.overgrownvegetation.block.ResearchTableBlockEntity;
import com.yayuy.overgrownvegetation.block.BeamShieldBlockEntity;
import com.yayuy.overgrownvegetation.block.SiegeCannonBlockEntity;
import com.yayuy.overgrownvegetation.block.SporeDeviceBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, OvergrownVegetation.MODID);

    // 2026-09-11: 丸まったシダ(overgrown_geo_plant)のブロックエンティティは廃止。モデルはチャンクメッシュへ
    // 焼き込む(CorruptedGeoGrowthBlock 参照)。旧ワールドに残った "overgrown_geo_plant" は読み込み時に
    // 「unknown block entity」の警告付きで捨てられるだけで、ブロック自体はそのまま残る。

    /** {@link com.yayuy.overgrownvegetation.block.CorruptedLianaBlock} の中身。 */
    /** 乳鉢の中身(2026-10-16)。 */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<com.yayuy.overgrownvegetation.block.MortarBlockEntity>> MORTAR =
            BLOCK_ENTITY_TYPES.register("mortar", () -> BlockEntityType.Builder.of(
                    com.yayuy.overgrownvegetation.block.MortarBlockEntity::new,
                    ModBlocks.MORTAR.get()).build(null));
    /** 地脈の汲み上げ器とおとりの灯籠(2026-10-16)。 */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<com.yayuy.overgrownvegetation.block.LeyPumpBlockEntity>> LEY_PUMP =
            BLOCK_ENTITY_TYPES.register("ley_pump", () -> BlockEntityType.Builder.of(
                    com.yayuy.overgrownvegetation.block.LeyPumpBlockEntity::new,
                    ModBlocks.LEY_PUMP.get()).build(null));
    /** 地脈技術の 2 段目の機械(2026-10-22)。 */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<com.yayuy.overgrownvegetation.block.LeyFurnaceBlockEntity>> LEY_FURNACE =
            BLOCK_ENTITY_TYPES.register("ley_furnace", () -> BlockEntityType.Builder.of(
                    com.yayuy.overgrownvegetation.block.LeyFurnaceBlockEntity::new,
                    ModBlocks.LEY_FURNACE.get()).build(null));
    /** 風見(2026-10-23 胞子の場と風 第 2 段 H)。羽根の描画だけ。 */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<com.yayuy.overgrownvegetation.block.WindVaneBlockEntity>> WIND_VANE =
            BLOCK_ENTITY_TYPES.register("wind_vane", () -> BlockEntityType.Builder.of(
                    com.yayuy.overgrownvegetation.block.WindVaneBlockEntity::new,
                    ModBlocks.WIND_VANE.get()).build(null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<com.yayuy.overgrownvegetation.block.LeyLampBlockEntity>> LEY_LAMP =
            BLOCK_ENTITY_TYPES.register("ley_lamp", () -> BlockEntityType.Builder.of(
                    com.yayuy.overgrownvegetation.block.LeyLampBlockEntity::new,
                    ModBlocks.LEY_LAMP.get()).build(null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<com.yayuy.overgrownvegetation.block.LeyUpdraftBlockEntity>> LEY_UPDRAFT =
            BLOCK_ENTITY_TYPES.register("ley_updraft", () -> BlockEntityType.Builder.of(
                    com.yayuy.overgrownvegetation.block.LeyUpdraftBlockEntity::new,
                    ModBlocks.LEY_UPDRAFT.get()).build(null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<com.yayuy.overgrownvegetation.block.LeyMeterBlockEntity>> LEY_METER =
            BLOCK_ENTITY_TYPES.register("ley_meter", () -> BlockEntityType.Builder.of(
                    com.yayuy.overgrownvegetation.block.LeyMeterBlockEntity::new,
                    ModBlocks.LEY_METER.get()).build(null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<com.yayuy.overgrownvegetation.block.LureLanternBlockEntity>> LURE_LANTERN =
            BLOCK_ENTITY_TYPES.register("lure_lantern", () -> BlockEntityType.Builder.of(
                    com.yayuy.overgrownvegetation.block.LureLanternBlockEntity::new,
                    ModBlocks.LURE_LANTERN.get()).build(null));
    /** 受け流しの杭と防胞子の天幕の登録係(2026-10-16)。 */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<com.yayuy.overgrownvegetation.block.DeflectingStakeBlockEntity>> DEFLECTING_STAKE =
            BLOCK_ENTITY_TYPES.register("deflecting_stake", () -> BlockEntityType.Builder.of(
                    com.yayuy.overgrownvegetation.block.DeflectingStakeBlockEntity::new,
                    ModBlocks.DEFLECTING_STAKE.get()).build(null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<com.yayuy.overgrownvegetation.block.SporeTentBlock.SporeTentBlockEntity>> SPORE_TENT =
            BLOCK_ENTITY_TYPES.register("spore_tent", () -> BlockEntityType.Builder.of(
                    com.yayuy.overgrownvegetation.block.SporeTentBlock.SporeTentBlockEntity::new,
                    ModBlocks.SPORE_TENT.get()).build(null));
    /** 鳴子の紐 1 本(2026-10-15)。 */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<com.yayuy.overgrownvegetation.defense.ClapperRopeBlockEntity>> CLAPPER_ROPE =
            BLOCK_ENTITY_TYPES.register("clapper_rope", () -> BlockEntityType.Builder.of(
                    com.yayuy.overgrownvegetation.defense.ClapperRopeBlockEntity::new,
                    ModBlocks.CLAPPER_ROPE.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CorruptedLianaBlockEntity>> OVERGROWN_LIANA =
            BLOCK_ENTITY_TYPES.register("overgrown_liana", () -> BlockEntityType.Builder.of(
                    CorruptedLianaBlockEntity::new,
                    ModBlocks.OVERGROWN_LIANA.get()).build(null));

    /**
     * {@link com.yayuy.overgrownvegetation.block.CorruptedCorpseBlock} の中身。種族専用モデル
     * ({@link ModBlocks#OVERGROWN_COW_CORPSE} など、全6種)は見た目が違うだけで挙動
     * (侵食を広げる/侵食完了時に対応する Mob か汚染シダを湧かせる)は共通なので、
     * 1つの BlockEntityType を全ブロックで共用する(バニラの furnace/lit_furnace などと同じやり方)。
     */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CorruptedCorpseBlockEntity>> CORRUPTED_CORPSE =
            BLOCK_ENTITY_TYPES.register("overgrown_corpse", () -> BlockEntityType.Builder.of(
                    CorruptedCorpseBlockEntity::new,
                    ModBlocks.OVERGROWN_COW_CORPSE.get(),
                    ModBlocks.OVERGROWN_PIG_CORPSE.get(),
                    ModBlocks.OVERGROWN_SHEEP_CORPSE.get(),
                    ModBlocks.OVERGROWN_CREEPER_CORPSE.get(),
                    ModBlocks.OVERGROWN_SPIDER_CORPSE.get(),
                    ModBlocks.OVERGROWN_VILLAGER_CORPSE.get(),
                    ModBlocks.OVERGROWN_ZOMBIE_CORPSE.get(),
                    ModBlocks.OVERGROWN_BABY_ZOMBIE_CORPSE.get(),
                    ModBlocks.OVERGROWN_SKELETON_CORPSE.get(),
                    ModBlocks.OVERGROWN_ENDERMAN_CORPSE.get(),
                    ModBlocks.OVERGROWN_CHICKEN_CORPSE.get(),
                    ModBlocks.OVERGROWN_PIGLIN_CORPSE.get(),
                    ModBlocks.OVERGROWN_PIGLIN_BRUTE_CORPSE.get(),
                    ModBlocks.OVERGROWN_ZOMBIFIED_PIGLIN_CORPSE.get(),
                    ModBlocks.OVERGROWN_WITHER_SKELETON_CORPSE.get(),
                    ModBlocks.OVERGROWN_HOGLIN_CORPSE.get(),
                    ModBlocks.OVERGROWN_ZOGLIN_CORPSE.get(),
                    ModBlocks.OVERGROWN_STRIDER_CORPSE.get(),
                    ModBlocks.OVERGROWN_IRON_GOLEM_CORPSE.get(),
                    ModBlocks.OVERGROWN_WARDEN_CORPSE.get()).build(null));

    /** リトプレウラの抜け殻。GeckoLib で描くためだけの中身(状態は持たない)。 */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<LithopleuraHuskBlockEntity>> LITHOPLEURA_HUSK =
            BLOCK_ENTITY_TYPES.register("lithopleura_husk", () -> BlockEntityType.Builder.of(
                    LithopleuraHuskBlockEntity::new,
                    ModBlocks.LITHOPLEURA_HUSK.get()).build(null));

    /** 根付いた萌芽体(2026-10-23 萌芽体のモデル)。歩く萌芽体と同じ模型を GeckoLib で描くための中身(向きと根付いた時刻だけ)。 */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<com.yayuy.overgrownvegetation.block.BlightFernRootedBlockEntity>> BLIGHT_FERN_ROOTED =
            BLOCK_ENTITY_TYPES.register("blight_fern_rooted", () -> BlockEntityType.Builder.of(
                    com.yayuy.overgrownvegetation.block.BlightFernRootedBlockEntity::new,
                    ModBlocks.BLIGHT_FERN_ROOTED.get()).build(null));

    /**
     * <b>覆われて動かなくなったブロック</b>の中身(2026-09-22)。化けている元のブロック状態と、
     * 覆われる前にそこにあったアイテムを覚えているだけで、<b>何も動かさない</b>。
     * ticker を付けないこと —— 付けた瞬間に「覆われているのに動くブロック」に逆戻りする。
     */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<com.yayuy.overgrownvegetation.block.OvergrownHuskBlockEntity>> OVERGROWN_HUSK =
            BLOCK_ENTITY_TYPES.register("overgrown_husk", () -> BlockEntityType.Builder.of(
                    com.yayuy.overgrownvegetation.block.OvergrownHuskBlockEntity::new,
                    ModBlocks.OVERGROWN_HUSK.get()).build(null));

    /** {@link com.yayuy.overgrownvegetation.block.ResearchTableBlock} の中身。 */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ResearchTableBlockEntity>> RESEARCH_TABLE =
            BLOCK_ENTITY_TYPES.register("research_table", () -> BlockEntityType.Builder.of(
                    ResearchTableBlockEntity::new,
                    ModBlocks.RESEARCH_TABLE.get()).build(null));

    /**
     * {@link com.yayuy.overgrownvegetation.block.SporeDeviceBlock} の中身。除去装置と拡散装置は
     * 挙動の切り替え({@link com.yayuy.overgrownvegetation.block.SporeDeviceKind})だけなので
     * 1 つの BlockEntityType を共用する。
     */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<SporeDeviceBlockEntity>> SPORE_DEVICE =
            BLOCK_ENTITY_TYPES.register("spore_device", () -> BlockEntityType.Builder.of(
                    SporeDeviceBlockEntity::new,
                    ModBlocks.SPORE_PURIFIER.get(),
                    ModBlocks.SPORE_DIFFUSER.get()).build(null));

    /** 「除染機」({@link com.yayuy.overgrownvegetation.block.DecontaminatorBlock})の中身。 */
    /** 忌避灯({@link com.yayuy.overgrownvegetation.block.RepellentLampBlock})。台帳への出入りだけ。 */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<com.yayuy.overgrownvegetation.block.RepellentLampBlockEntity>> REPELLENT_LAMP =
            BLOCK_ENTITY_TYPES.register("repellent_lamp", () -> BlockEntityType.Builder.of(
                    com.yayuy.overgrownvegetation.block.RepellentLampBlockEntity::new,
                    ModBlocks.REPELLENT_LAMP.get()).build(null));

    /** 機関砲台({@link com.yayuy.overgrownvegetation.block.GunTurretBlock})。弾倉と照準を持つ。 */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<com.yayuy.overgrownvegetation.block.GunTurretBlockEntity>> GUN_TURRET =
            BLOCK_ENTITY_TYPES.register("gun_turret", () -> BlockEntityType.Builder.of(
                    com.yayuy.overgrownvegetation.block.GunTurretBlockEntity::new,
                    ModBlocks.GUN_TURRET.get()).build(null));

    /** 胞子警報器({@link com.yayuy.overgrownvegetation.block.SporeAlarmBlock})。台帳への出入りだけ。 */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<com.yayuy.overgrownvegetation.block.SporeAlarmBlockEntity>> SPORE_ALARM =
            BLOCK_ENTITY_TYPES.register("spore_alarm", () -> BlockEntityType.Builder.of(
                    com.yayuy.overgrownvegetation.block.SporeAlarmBlockEntity::new,
                    ModBlocks.SPORE_ALARM.get()).build(null));

    /** ターゲットブロック({@link com.yayuy.overgrownvegetation.td.TargetBlock})。耐久(HP)を持つ。TD イベント(2026-09-25)。 */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<com.yayuy.overgrownvegetation.td.TargetBlockEntity>> TARGET_BLOCK =
            BLOCK_ENTITY_TYPES.register("target_block", () -> BlockEntityType.Builder.of(
                    com.yayuy.overgrownvegetation.td.TargetBlockEntity::new,
                    ModBlocks.TARGET_BLOCK.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<DecontaminatorBlockEntity>> DECONTAMINATOR =
            BLOCK_ENTITY_TYPES.register("decontaminator", () -> BlockEntityType.Builder.of(
                    DecontaminatorBlockEntity::new,
                    ModBlocks.DECONTAMINATOR.get()).build(null));

    /** 因子の抽出設備(順 1 の着手順 7)。 */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<com.yayuy.overgrownvegetation.block.FactorExtractorBlockEntity>> FACTOR_EXTRACTOR =
            BLOCK_ENTITY_TYPES.register("factor_extractor", () -> BlockEntityType.Builder.of(
                    com.yayuy.overgrownvegetation.block.FactorExtractorBlockEntity::new,
                    ModBlocks.FACTOR_EXTRACTOR.get()).build(null));

    /** {@link com.yayuy.overgrownvegetation.block.OvergrownChestBlock} の中身(バニラのチェストと同じ)。 */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<OvergrownChestBlockEntity>> OVERGROWN_CHEST =
            BLOCK_ENTITY_TYPES.register("overgrown_chest", () -> BlockEntityType.Builder.of(
                    OvergrownChestBlockEntity::new,
                    ModBlocks.OVERGROWN_CHEST.get()).build(null));

    /** {@link com.yayuy.overgrownvegetation.block.OvergrownBarrelBlock} の中身(バニラの樽と同じ)。 */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<OvergrownBarrelBlockEntity>> OVERGROWN_BARREL =
            BLOCK_ENTITY_TYPES.register("overgrown_barrel", () -> BlockEntityType.Builder.of(
                    OvergrownBarrelBlockEntity::new,
                    ModBlocks.OVERGROWN_BARREL.get()).build(null));

    /** {@link com.yayuy.overgrownvegetation.block.SiegeCannonBlock} の中身(砲弾・火薬・照準・装填)。 */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<SiegeCannonBlockEntity>> SIEGE_CANNON =
            BLOCK_ENTITY_TYPES.register("siege_cannon", () -> BlockEntityType.Builder.of(
                    SiegeCannonBlockEntity::new,
                    ModBlocks.SIEGE_CANNON.get()).build(null));

    /** {@link com.yayuy.overgrownvegetation.block.BeamShieldBlock} の中身（方位・展開）。 */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<BeamShieldBlockEntity>> BEAM_SHIELD =
            BLOCK_ENTITY_TYPES.register("beam_shield", () -> BlockEntityType.Builder.of(
                    BeamShieldBlockEntity::new,
                    ModBlocks.BEAM_SHIELD.get()).build(null));

    /** {@link com.yayuy.overgrownvegetation.block.ParasiteNurseryBlock} の中身(萌芽体を生む時計。G1 の作り直し 第 1 段)。 */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<com.yayuy.overgrownvegetation.block.ParasiteNurseryBlockEntity>> PARASITE_NURSERY =
            BLOCK_ENTITY_TYPES.register("parasite_nursery", () -> BlockEntityType.Builder.of(
                    com.yayuy.overgrownvegetation.block.ParasiteNurseryBlockEntity::new,
                    ModBlocks.PARASITE_NURSERY.get()).build(null));

    public static void register(IEventBus modBus) {
        BLOCK_ENTITY_TYPES.register(modBus);
    }

    private ModBlockEntities() {
    }
}
