package com.yayuy.overgrownvegetation.gear;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModArmorMaterials;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.ModItems;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DropExperienceBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.neoforge.common.SimpleTier;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;

import java.util.EnumMap;
import java.util.List;
import java.util.function.Supplier;

/**
 * <b>強くなる道 段 1・段 2 の物</b>(2026-11-17)の登録。名前・絵は仮。数値は {@code gear_tiers.json}({@link GearTierTable})。
 * 正本は Vault「強くなる道(素材・装備・武器・探索)(11-17)」の決定 17・18・21。入れ物は共有の {@code DeferredRegister} を使い、
 * {@link #init()} を MOD の構築子から呼ぶ({@code ModExpedition} と同じ作り)。レシピは今は最初から作れる(研究ツリーへのつなぎは後)。
 *
 * <ul>
 *   <li>段 1(縁): 繊維の草 → 草の繊維({@code plant_fiber} を流用)→ 草布・撚り紐 / 遠征服 4 部位 / 山刀 / 胞子弾の弓・胞子弾の矢。</li>
 *   <li>段 2(過繁茂の林床): 菌集鉱(石・深層岩)→ 菌鉄の原石 → 菌鉄 / 札鎧 4 部位 / 両手剣 / 貫きのクロスボウ。</li>
 * </ul>
 */
public final class ModGear {

    private ModGear() {
    }

    /** MOD の構築子から呼ぶ(クラスを読み込ませて登録を走らせる)。 */
    public static void init() {
    }

    private static GearTierTable t() {
        return DesignData.gearTiers();
    }

    // ---- 防具の素材 ----

    public static final DeferredHolder<ArmorMaterial, ArmorMaterial> SUIT_MATERIAL = ModArmorMaterials.ARMOR_MATERIALS.register(
            "expedition_suit", () -> material("expedition_suit", t().suit, SoundEvents.ARMOR_EQUIP_LEATHER,
                    () -> Ingredient.of(ModGear.GRASS_CLOTH.get())));

    public static final DeferredHolder<ArmorMaterial, ArmorMaterial> PLATE_MATERIAL = ModArmorMaterials.ARMOR_MATERIALS.register(
            "fungal_plate", () -> material("fungal_plate", t().plate, SoundEvents.ARMOR_EQUIP_IRON,
                    () -> Ingredient.of(ModGear.FUNGAL_METAL_INGOT.get())));

    private static ArmorMaterial material(String name, GearTierTable.Armor a, Holder<net.minecraft.sounds.SoundEvent> sound, Supplier<Ingredient> repair) {
        EnumMap<ArmorItem.Type, Integer> defense = new EnumMap<>(ArmorItem.Type.class);
        defense.put(ArmorItem.Type.HELMET, a.defense(0));
        defense.put(ArmorItem.Type.CHESTPLATE, a.defense(1));
        defense.put(ArmorItem.Type.LEGGINGS, a.defense(2));
        defense.put(ArmorItem.Type.BOOTS, a.defense(3));
        defense.put(ArmorItem.Type.BODY, a.defense(1));
        return new ArmorMaterial(defense, a.enchantability, sound, repair,
                List.of(new ArmorMaterial.Layer(OvergrownVegetation.id(name))), a.toughness, a.knockbackResistance);
    }

    // ---- 道具の格 ----

    /** 山刀(鉄の刃)。採掘には使わない。 */
    static final Tier MACHETE_TIER = new SimpleTier(BlockTags.INCORRECT_FOR_IRON_TOOL, 300, 6.0F, 0.0F, 14, () -> Ingredient.of(Items.IRON_INGOT));
    /** 両手剣(菌鉄の刃)。 */
    static final Tier GREATSWORD_TIER = new SimpleTier(BlockTags.INCORRECT_FOR_DIAMOND_TOOL, 900, 8.0F, 0.0F, 12,
            () -> Ingredient.of(ModGear.FUNGAL_METAL_INGOT.get()));

    /** 攻撃の合計 {@code damage}(素手 1 込み)と 1 秒の振り {@code speed} にする属性。 */
    static net.minecraft.world.item.component.ItemAttributeModifiers weaponAttributes(Tier tier, float damage, float speed) {
        return SwordItem.createAttributes(tier, damage - 1.0F - tier.getAttackDamageBonus(), speed - 4.0F);
    }

    // ---- ブロック ----

    /** 繊維の草(縁に生える)。 */
    public static final DeferredBlock<FiberGrassBlock> FIBER_GRASS = ModBlocks.BLOCKS.registerBlock(
            "fiber_grass", FiberGrassBlock::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.PLANT)
                    .replaceable()
                    .noCollission()
                    .instabreak()
                    .sound(SoundType.GRASS)
                    .offsetType(BlockBehaviour.OffsetType.XZ)
                    .ignitedByLava()
                    .pushReaction(PushReaction.DESTROY));

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

    // ---- 段 1 の素材 ----

    public static final DeferredItem<BlockItem> FIBER_GRASS_ITEM = ModItems.ITEMS.registerSimpleBlockItem(FIBER_GRASS);
    /** 草布(草の繊維を織った布)。 */
    public static final DeferredItem<Item> GRASS_CLOTH = ModItems.ITEMS.registerSimpleItem("grass_cloth", new Item.Properties());
    /** 撚り紐(草の繊維を撚った紐。弦にもなる)。 */
    public static final DeferredItem<Item> TWISTED_CORD = ModItems.ITEMS.registerSimpleItem("twisted_cord", new Item.Properties());

    // ---- 段 1 の遠征服 ----

    public static final DeferredItem<TierHelmetItem> EXPEDITION_HOOD = ModItems.ITEMS.registerItem("expedition_hood",
            p -> new TierHelmetItem(SUIT_MATERIAL, () -> t().suit, p),
            new Item.Properties().durability(ArmorItem.Type.HELMET.getDurability(DesignData.gearTiers().suit.durability)));
    public static final DeferredItem<TierArmorItem> EXPEDITION_COAT = ModItems.ITEMS.registerItem("expedition_coat",
            p -> new TierArmorItem(SUIT_MATERIAL, ArmorItem.Type.CHESTPLATE, () -> t().suit, p),
            new Item.Properties().durability(ArmorItem.Type.CHESTPLATE.getDurability(DesignData.gearTiers().suit.durability)));
    public static final DeferredItem<TierArmorItem> EXPEDITION_TROUSERS = ModItems.ITEMS.registerItem("expedition_trousers",
            p -> new TierArmorItem(SUIT_MATERIAL, ArmorItem.Type.LEGGINGS, () -> t().suit, p),
            new Item.Properties().durability(ArmorItem.Type.LEGGINGS.getDurability(DesignData.gearTiers().suit.durability)));
    public static final DeferredItem<TierArmorItem> EXPEDITION_BOOTS = ModItems.ITEMS.registerItem("expedition_boots",
            p -> new TierArmorItem(SUIT_MATERIAL, ArmorItem.Type.BOOTS, () -> t().suit, p),
            new Item.Properties().durability(ArmorItem.Type.BOOTS.getDurability(DesignData.gearTiers().suit.durability)));

    // ---- 段 1 の武器 ----

    public static final DeferredItem<MacheteItem> MACHETE = ModItems.ITEMS.registerItem("machete",
            p -> new MacheteItem(MACHETE_TIER, p),
            new Item.Properties().durability(DesignData.gearTiers().machete.durability)
                    .attributes(weaponAttributes(MACHETE_TIER, DesignData.gearTiers().machete.attackDamage, DesignData.gearTiers().machete.attackSpeed)));
    public static final DeferredItem<SporeBowItem> SPORE_BOW = ModItems.ITEMS.registerItem("spore_bow", SporeBowItem::new,
            new Item.Properties().durability(DesignData.gearTiers().sporeShot.bowDurability));
    public static final DeferredItem<SporeArrowItem> SPORE_ARROW = ModItems.ITEMS.registerItem("spore_arrow", SporeArrowItem::new,
            new Item.Properties());

    // ---- 段 2 の素材 ----

    public static final DeferredItem<BlockItem> FUNGAL_ORE_ITEM = ModItems.ITEMS.registerSimpleBlockItem(FUNGAL_ORE);
    public static final DeferredItem<BlockItem> DEEPSLATE_FUNGAL_ORE_ITEM = ModItems.ITEMS.registerSimpleBlockItem(DEEPSLATE_FUNGAL_ORE);
    /** 菌鉄の原石(菌集鉱を掘ると落ちる。焼くと菌鉄)。 */
    public static final DeferredItem<Item> RAW_FUNGAL_METAL = ModItems.ITEMS.registerSimpleItem("raw_fungal_metal", new Item.Properties());
    /** 菌鉄(菌が集めた金属を焼いて固めた地金)。 */
    public static final DeferredItem<Item> FUNGAL_METAL_INGOT = ModItems.ITEMS.registerSimpleItem("fungal_metal_ingot", new Item.Properties());

    // ---- 段 2 の札鎧 ----

    public static final DeferredItem<TierHelmetItem> PLATE_HELMET = ModItems.ITEMS.registerItem("fungal_plate_helmet",
            p -> new TierHelmetItem(PLATE_MATERIAL, () -> t().plate, p),
            new Item.Properties().durability(ArmorItem.Type.HELMET.getDurability(DesignData.gearTiers().plate.durability)));
    public static final DeferredItem<TierArmorItem> PLATE_CHESTPLATE = ModItems.ITEMS.registerItem("fungal_plate_chestplate",
            p -> new TierArmorItem(PLATE_MATERIAL, ArmorItem.Type.CHESTPLATE, () -> t().plate, p),
            new Item.Properties().durability(ArmorItem.Type.CHESTPLATE.getDurability(DesignData.gearTiers().plate.durability)));
    public static final DeferredItem<TierArmorItem> PLATE_LEGGINGS = ModItems.ITEMS.registerItem("fungal_plate_leggings",
            p -> new TierArmorItem(PLATE_MATERIAL, ArmorItem.Type.LEGGINGS, () -> t().plate, p),
            new Item.Properties().durability(ArmorItem.Type.LEGGINGS.getDurability(DesignData.gearTiers().plate.durability)));
    public static final DeferredItem<TierArmorItem> PLATE_BOOTS = ModItems.ITEMS.registerItem("fungal_plate_boots",
            p -> new TierArmorItem(PLATE_MATERIAL, ArmorItem.Type.BOOTS, () -> t().plate, p),
            new Item.Properties().durability(ArmorItem.Type.BOOTS.getDurability(DesignData.gearTiers().plate.durability)));

    // ---- 段 2 の武器 ----

    public static final DeferredItem<GreatswordItem> GREATSWORD = ModItems.ITEMS.registerItem("greatsword",
            p -> new GreatswordItem(GREATSWORD_TIER, p),
            new Item.Properties().durability(DesignData.gearTiers().greatsword.durability)
                    .attributes(weaponAttributes(GREATSWORD_TIER, DesignData.gearTiers().greatsword.attackDamage, DesignData.gearTiers().greatsword.attackSpeed)));
    public static final DeferredItem<PiercingCrossbowItem> PIERCING_CROSSBOW = ModItems.ITEMS.registerItem("piercing_crossbow", PiercingCrossbowItem::new,
            new Item.Properties().stacksTo(1).durability(DesignData.gearTiers().crossbow.durability)
                    .component(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.EMPTY));

    /** 人類側のタブに並べる({@code ModCreativeTabs} から呼ぶ)。 */
    public static void acceptItems(CreativeModeTab.Output output) {
        output.accept(FIBER_GRASS_ITEM.get());
        output.accept(GRASS_CLOTH.get());
        output.accept(TWISTED_CORD.get());
        output.accept(EXPEDITION_HOOD.get());
        output.accept(EXPEDITION_COAT.get());
        output.accept(EXPEDITION_TROUSERS.get());
        output.accept(EXPEDITION_BOOTS.get());
        output.accept(MACHETE.get());
        output.accept(SPORE_BOW.get());
        output.accept(SPORE_ARROW.get());
        output.accept(FUNGAL_ORE_ITEM.get());
        output.accept(DEEPSLATE_FUNGAL_ORE_ITEM.get());
        output.accept(RAW_FUNGAL_METAL.get());
        output.accept(FUNGAL_METAL_INGOT.get());
        output.accept(PLATE_HELMET.get());
        output.accept(PLATE_CHESTPLATE.get());
        output.accept(PLATE_LEGGINGS.get());
        output.accept(PLATE_BOOTS.get());
        output.accept(GREATSWORD.get());
        output.accept(PIERCING_CROSSBOW.get());
    }
}
