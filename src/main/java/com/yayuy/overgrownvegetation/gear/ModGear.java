package com.yayuy.overgrownvegetation.gear;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.ModFeatures;
import com.yayuy.overgrownvegetation.registry.ModItems;
import com.yayuy.overgrownvegetation.registry.ModLey;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DropExperienceBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.neoforge.common.SimpleTier;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;

/**
 * 装備・鉱石の登録。数値は {@code gear_tiers.json}({@link GearTierTable})。正本は Vault「強くなる道(素材・装備・武器・探索)(11-17)」の 11-21 の節。
 * 入れ物は共有の {@code DeferredRegister} を使い、{@link #init()} を MOD の構築子から呼ぶ。
 *
 * <p>2026-11-21 に 11-17〜18 の段 1〜3 の装備を消して作り直し始めた(グレードごとに 道具 → 装備 → 役に立つ道具)。今あるのは:
 * <ul>
 *   <li>菌集鉱(石・深層岩)→ 菌鉄の原石 → 菌鉄(グレード 2 で使う。でき方は {@link FungalOre})。</li>
 *   <li>グレード 1: 導晶鉱(石・深層岩。地脈の線の近くの地下 = {@link ConduitOre})→ 導晶の原石 → 導晶の地金 → 導晶の板 /
 *   導晶の道具 4 本({@link ConduitTools}。地脈の力で速くなる = {@link LeyCharge})/ 飲み込まれた導晶鉱(緑)と小さな根晶。</li>
 * </ul>
 * 名前・絵・数はすべて【仮】。
 */
public final class ModGear {

    private ModGear() {
    }

    /** MOD の構築子から呼ぶ(クラスを読み込ませて登録を走らせる)。 */
    public static void init() {
        ModMobDrops.init(); // Mob ごとのドロップ(2026-11-17 夜)
    }

    // ---- 菌集鉱(グレード 2 の元) ----

    /** 菌集鉱(過繁茂の石から)。 */
    public static final DeferredBlock<DropExperienceBlock> FUNGAL_ORE = ModBlocks.BLOCKS.registerBlock(
            "fungal_ore", p -> new DropExperienceBlock(ConstantInt.of(0), p),
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.STONE)
                    .instrument(NoteBlockInstrument.BASEDRUM)
                    .requiresCorrectToolForDrops()
                    .strength(3.0F, 3.0F));

    /** 菌集鉱(過繁茂の深層岩から)。 */
    public static final DeferredBlock<DropExperienceBlock> DEEPSLATE_FUNGAL_ORE = ModBlocks.BLOCKS.registerBlock(
            "deepslate_fungal_ore", p -> new DropExperienceBlock(ConstantInt.of(0), p),
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.DEEPSLATE)
                    .instrument(NoteBlockInstrument.BASEDRUM)
                    .requiresCorrectToolForDrops()
                    .strength(4.5F, 3.0F)
                    .sound(SoundType.DEEPSLATE));

    public static final DeferredItem<BlockItem> FUNGAL_ORE_ITEM = ModItems.ITEMS.registerSimpleBlockItem(FUNGAL_ORE);
    public static final DeferredItem<BlockItem> DEEPSLATE_FUNGAL_ORE_ITEM = ModItems.ITEMS.registerSimpleBlockItem(DEEPSLATE_FUNGAL_ORE);
    /** 菌鉄の原石(菌集鉱を掘ると落ちる。焼くと菌鉄)。 */
    public static final DeferredItem<Item> RAW_FUNGAL_METAL = ModItems.ITEMS.registerSimpleItem("raw_fungal_metal", new Item.Properties());
    /** 菌鉄(菌が集めた金属を焼いて固めた地金)。 */
    public static final DeferredItem<Item> FUNGAL_METAL_INGOT = ModItems.ITEMS.registerSimpleItem("fungal_metal_ingot", new Item.Properties());

    // ---- グレード 1: 導晶(2026-11-21) ----

    /** 導晶鉱(石の版)。色は地脈と同じ黄緑。 */
    public static final DeferredBlock<DropExperienceBlock> CONDUIT_ORE = ModBlocks.BLOCKS.registerBlock(
            "conduit_ore", p -> new DropExperienceBlock(UniformInt.of(0, 2), p),
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.STONE)
                    .instrument(NoteBlockInstrument.BASEDRUM)
                    .requiresCorrectToolForDrops()
                    .strength(3.0F, 3.0F));

    /** 導晶鉱(深層岩の版)。 */
    public static final DeferredBlock<DropExperienceBlock> DEEPSLATE_CONDUIT_ORE = ModBlocks.BLOCKS.registerBlock(
            "deepslate_conduit_ore", p -> new DropExperienceBlock(UniformInt.of(0, 2), p),
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.DEEPSLATE)
                    .instrument(NoteBlockInstrument.BASEDRUM)
                    .requiresCorrectToolForDrops()
                    .strength(4.5F, 3.0F)
                    .sound(SoundType.DEEPSLATE));

    /** 汚染に飲み込まれた導晶鉱(石)。変質はしない(菌集鉱にはならない)。緑に変わり、落とすのは黄緑の導晶の原石(11-21 夜)。表面に小さな根晶が生える。 */
    public static final DeferredBlock<DropExperienceBlock> OVERGROWN_CONDUIT_ORE = ModBlocks.BLOCKS.registerBlock(
            "overgrown_conduit_ore", p -> new DropExperienceBlock(UniformInt.of(0, 2), p),
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_GREEN)
                    .instrument(NoteBlockInstrument.BASEDRUM)
                    .requiresCorrectToolForDrops()
                    .strength(3.0F, 3.0F));

    /** 汚染に飲み込まれた導晶鉱(深層岩)。 */
    public static final DeferredBlock<DropExperienceBlock> OVERGROWN_DEEPSLATE_CONDUIT_ORE = ModBlocks.BLOCKS.registerBlock(
            "overgrown_deepslate_conduit_ore", p -> new DropExperienceBlock(UniformInt.of(0, 2), p),
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_GREEN)
                    .instrument(NoteBlockInstrument.BASEDRUM)
                    .requiresCorrectToolForDrops()
                    .strength(4.5F, 3.0F)
                    .sound(SoundType.DEEPSLATE));

    /** 小さな根晶(飲み込まれた導晶鉱の表面に生える。掘ると 25% で根晶 = ルートテーブル)。 */
    public static final DeferredBlock<AmethystClusterBlock> SMALL_ROOT_CRYSTAL = ModBlocks.BLOCKS.registerBlock(
            "small_root_crystal", p -> new AmethystClusterBlock(3.0F, 4.0F, p),
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_LIGHT_GREEN)
                    .forceSolidOn()
                    .noOcclusion()
                    .strength(1.0F)
                    .sound(SoundType.SMALL_AMETHYST_BUD)
                    .lightLevel(s -> 2)
                    .pushReaction(PushReaction.DESTROY));

    public static final DeferredItem<BlockItem> CONDUIT_ORE_ITEM = ModItems.ITEMS.registerSimpleBlockItem(CONDUIT_ORE);
    public static final DeferredItem<BlockItem> DEEPSLATE_CONDUIT_ORE_ITEM = ModItems.ITEMS.registerSimpleBlockItem(DEEPSLATE_CONDUIT_ORE);
    public static final DeferredItem<BlockItem> OVERGROWN_CONDUIT_ORE_ITEM = ModItems.ITEMS.registerSimpleBlockItem(OVERGROWN_CONDUIT_ORE);
    public static final DeferredItem<BlockItem> OVERGROWN_DEEPSLATE_CONDUIT_ORE_ITEM = ModItems.ITEMS.registerSimpleBlockItem(OVERGROWN_DEEPSLATE_CONDUIT_ORE);
    public static final DeferredItem<BlockItem> SMALL_ROOT_CRYSTAL_ITEM = ModItems.ITEMS.registerSimpleBlockItem(SMALL_ROOT_CRYSTAL);

    /** 導晶の原石(導晶鉱を掘ると落ちる。焼くと導晶の地金)。 */
    public static final DeferredItem<Item> RAW_CONDUIT = ModItems.ITEMS.registerSimpleItem("raw_conduit", new Item.Properties());
    /** 導晶の地金。 */
    public static final DeferredItem<Item> CONDUIT_INGOT = ModItems.ITEMS.registerSimpleItem("conduit_ingot", new Item.Properties());
    /** 導晶の板(中間素材。作業台で地金 1 → 板 1)。 */
    public static final DeferredItem<Item> CONDUIT_PLATE = ModItems.ITEMS.registerSimpleItem("conduit_plate", new Item.Properties());
    /** 導晶機関(中間素材。11-21 夜ユーザー「名前は導晶機関」。今は持つだけで使い道・説明は無い。作り方はユーザーが mod マネージャー 2 で決める = 今のレシピは【仮】)。 */
    public static final DeferredItem<Item> CONDUIT_ENGINE = ModItems.ITEMS.registerSimpleItem("conduit_engine", new Item.Properties());
    /** 黄緑の導晶の原石(飲み込まれた導晶鉱から出る。汚染されていない導晶とは別のアイテム = 11-21 夜ユーザー決定。焼くと黄緑の導晶の地金)。 */
    public static final DeferredItem<Item> OVERGROWN_RAW_CONDUIT = ModItems.ITEMS.registerSimpleItem("overgrown_raw_conduit", new Item.Properties());
    /** 黄緑の導晶の地金(作業台で導晶の板にもなる = 道具は青・黄緑のどちらからでも作れる。黄緑だけの使い道は後で決める)。 */
    public static final DeferredItem<Item> OVERGROWN_CONDUIT_INGOT = ModItems.ITEMS.registerSimpleItem("overgrown_conduit_ingot", new Item.Properties());

    /** 導晶の道具の格(硬さはダイヤと同じ)。壊れにくさ・速さは登録の時に読む(変えたら再起動)。 */
    public static final Tier CONDUIT_TIER = new SimpleTier(BlockTags.INCORRECT_FOR_DIAMOND_TOOL,
            DesignData.gearTiers().conduit.durability, DesignData.gearTiers().conduit.speed,
            DesignData.gearTiers().conduit.attackBonus, DesignData.gearTiers().conduit.enchantability,
            () -> Ingredient.of(ModGear.CONDUIT_PLATE.get()));

    public static final DeferredItem<ConduitTools.Pickaxe> CONDUIT_PICKAXE = ModItems.ITEMS.registerItem("conduit_pickaxe",
            p -> new ConduitTools.Pickaxe(CONDUIT_TIER, p),
            new Item.Properties().attributes(PickaxeItem.createAttributes(CONDUIT_TIER, 1.0F, -2.8F)));
    public static final DeferredItem<ConduitTools.Axe> CONDUIT_AXE = ModItems.ITEMS.registerItem("conduit_axe",
            p -> new ConduitTools.Axe(CONDUIT_TIER, p),
            new Item.Properties().attributes(AxeItem.createAttributes(CONDUIT_TIER, 5.0F, -3.0F)));
    public static final DeferredItem<ConduitTools.Shovel> CONDUIT_SHOVEL = ModItems.ITEMS.registerItem("conduit_shovel",
            p -> new ConduitTools.Shovel(CONDUIT_TIER, p),
            new Item.Properties().attributes(ShovelItem.createAttributes(CONDUIT_TIER, 1.5F, -3.0F)));
    public static final DeferredItem<ConduitTools.Hoe> CONDUIT_HOE = ModItems.ITEMS.registerItem("conduit_hoe",
            p -> new ConduitTools.Hoe(CONDUIT_TIER, p),
            new Item.Properties().attributes(HoeItem.createAttributes(CONDUIT_TIER, -3.0F, 0.0F)));

    // ---- 導晶の武器(11-21 夜。剣と、導晶機関で弦を巻く弩) ----
    /** 導晶の剣(攻撃は力の有無で変わるので、部品の攻撃の値は持たせない = {@link ConduitWeapons.Sword#getDefaultAttributeModifiers})。 */
    public static final DeferredItem<ConduitWeapons.Sword> CONDUIT_SWORD = ModItems.ITEMS.registerItem("conduit_sword",
            p -> new ConduitWeapons.Sword(CONDUIT_TIER, p), new Item.Properties());
    public static final DeferredItem<ConduitWeapons.Crossbow> CONDUIT_CROSSBOW = ModItems.ITEMS.registerItem("conduit_crossbow",
            ConduitWeapons.Crossbow::new,
            new Item.Properties().stacksTo(1).durability(DesignData.gearTiers().crossbow.durability)
                    .component(net.minecraft.core.component.DataComponents.CHARGED_PROJECTILES,
                            net.minecraft.world.item.component.ChargedProjectiles.EMPTY));

    // ---- 導晶の防具(11-21 夜。兜は防護マスクと一体にしない) ----
    /** 導晶の防具の素材(防御・強度・エンチャントは登録の時に読む)。体の絵は {@code textures/models/armor/conduit_layer_1・2}。 */
    public static final DeferredHolder<net.minecraft.world.item.ArmorMaterial, net.minecraft.world.item.ArmorMaterial> CONDUIT_ARMOR_MATERIAL =
            com.yayuy.overgrownvegetation.registry.ModArmorMaterials.ARMOR_MATERIALS.register("conduit", () -> {
                GearTierTable.Armor a = DesignData.gearTiers().armor;
                java.util.EnumMap<ArmorItem.Type, Integer> defense = new java.util.EnumMap<>(ArmorItem.Type.class);
                defense.put(ArmorItem.Type.HELMET, a.helmetDefense);
                defense.put(ArmorItem.Type.CHESTPLATE, a.chestplateDefense);
                defense.put(ArmorItem.Type.LEGGINGS, a.leggingsDefense);
                defense.put(ArmorItem.Type.BOOTS, a.bootsDefense);
                defense.put(ArmorItem.Type.BODY, a.chestplateDefense);
                return new net.minecraft.world.item.ArmorMaterial(defense, a.enchantability,
                        net.minecraft.sounds.SoundEvents.ARMOR_EQUIP_IRON, () -> Ingredient.of(CONDUIT_PLATE.get()),
                        java.util.List.of(new net.minecraft.world.item.ArmorMaterial.Layer(OvergrownVegetation.id("conduit"))),
                        a.toughness, a.knockbackResistance);
            });
    public static final DeferredItem<ConduitArmor.Piece> CONDUIT_HELMET = armorPiece("conduit_helmet", ArmorItem.Type.HELMET);
    public static final DeferredItem<ConduitArmor.Piece> CONDUIT_CHESTPLATE = armorPiece("conduit_chestplate", ArmorItem.Type.CHESTPLATE);
    public static final DeferredItem<ConduitArmor.Piece> CONDUIT_LEGGINGS = armorPiece("conduit_leggings", ArmorItem.Type.LEGGINGS);
    public static final DeferredItem<ConduitArmor.Piece> CONDUIT_BOOTS = armorPiece("conduit_boots", ArmorItem.Type.BOOTS);

    private static DeferredItem<ConduitArmor.Piece> armorPiece(String name, ArmorItem.Type type) {
        return ModItems.ITEMS.registerItem(name, p -> new ConduitArmor.Piece(CONDUIT_ARMOR_MATERIAL, type, p),
                new Item.Properties().durability(type.getDurability(DesignData.gearTiers().armor.durabilityMultiplier)));
    }

    /** 地脈の力をためる導晶の物すべて(口の登録・タブ・試験で使う)。 */
    public static java.util.List<DeferredItem<? extends Item>> chargeables() {
        return java.util.List.of(CONDUIT_PICKAXE, CONDUIT_AXE, CONDUIT_SHOVEL, CONDUIT_HOE, CONDUIT_SWORD, CONDUIT_CROSSBOW,
                CONDUIT_HELMET, CONDUIT_CHESTPLATE, CONDUIT_LEGGINGS, CONDUIT_BOOTS);
    }

    /** 導晶鉱を地脈の線の近くの地下に置く(biome_modifier {@code conduit_ore})。 */
    public static final DeferredHolder<Feature<?>, ConduitOre.Placer> CONDUIT_ORE_FEATURE =
            ModFeatures.FEATURES.register("conduit_ore", () -> new ConduitOre.Placer(NoneFeatureConfiguration.CODEC));

    /** 地脈の力の口(電池と同じ。受け取るだけで出さない = 鉄塔の網に吸い出されない)。 */
    @net.neoforged.fml.common.EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = net.neoforged.fml.common.EventBusSubscriber.Bus.MOD)
    public static final class Capabilities {
        @net.neoforged.bus.api.SubscribeEvent
        public static void register(net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent event) {
            event.registerItem(net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.ITEM,
                    (stack, ctx) -> new net.neoforged.neoforge.energy.ComponentEnergyStorage(stack, ModLey.ENERGY.get(),
                            LeyCharge.capacity(stack), LeyCharge.maxReceive(), 0),
                    chargeables().stream().map(DeferredItem::get).toArray(Item[]::new));
        }

        private Capabilities() {
        }
    }

    /** 人類側のタブに並べる({@code ModCreativeTabs} から呼ぶ)。 */
    public static void acceptItems(CreativeModeTab.Output output) {
        output.accept(CONDUIT_ORE_ITEM.get());
        output.accept(DEEPSLATE_CONDUIT_ORE_ITEM.get());
        output.accept(OVERGROWN_CONDUIT_ORE_ITEM.get());
        output.accept(OVERGROWN_DEEPSLATE_CONDUIT_ORE_ITEM.get());
        output.accept(SMALL_ROOT_CRYSTAL_ITEM.get());
        output.accept(RAW_CONDUIT.get());
        output.accept(CONDUIT_INGOT.get());
        output.accept(OVERGROWN_RAW_CONDUIT.get());
        output.accept(OVERGROWN_CONDUIT_INGOT.get());
        output.accept(CONDUIT_PLATE.get());
        output.accept(CONDUIT_ENGINE.get());
        for (DeferredItem<? extends Item> item : chargeables()) { // 道具 4・剣・弩・防具 4
            output.accept(item.get());
            output.accept(LeyCharge.full(new ItemStack(item.get()))); // 満たした物も 1 つずつ(試す用。電池の満タンと同じ扱い)
        }
        output.accept(FUNGAL_ORE_ITEM.get());
        output.accept(DEEPSLATE_FUNGAL_ORE_ITEM.get());
        output.accept(RAW_FUNGAL_METAL.get());
        output.accept(FUNGAL_METAL_INGOT.get());
        ModMobDrops.acceptItems(output);
    }
}
