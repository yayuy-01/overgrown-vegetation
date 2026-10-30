package com.yayuy.overgrownvegetation.base;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.registry.ModBlockEntities;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.ModCreativeTabs;
import com.yayuy.overgrownvegetation.registry.ModItems;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;

import java.util.List;

/**
 * 同盟軍の基地に使う建材(2026-10-01、第 1 弾 12 ブロック + 妨害灯 + 管理端末)。道・壁・小物・灯り。
 *
 * <p>並行セッションと共有の {@link ModBlocks} / {@link ModItems} / {@link ModCreativeTabs} を太らせないよう、
 * 登録先の DeferredRegister だけ借りてここにまとめる。{@link #init()} を {@code ModBlocks.register} より前に呼ぶ。
 *
 * <p>id は基地の jigsaw の構造物 NBT に焼き込まれるので<b>改名しない</b>。
 * 硬さは TD の群れが壁を壊す量(硬さ × {@code td.json} の {@code breakHardnessFactor} 10)に効く —
 * 石 15 / 鉄ブロック 50 に対して鉄筋コンクリートは 80(仮決め 2026-10-01)。
 * 汚染は対応表に無いので、植物の覆い(70%)か {@code overgrown_block}(30%)になる。
 * 塀・階段・ハーフはバニラのタグに入れているので、塀は {@code overgrown_wall}、階段は {@code overgrown_stone_stairs} に変わる。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class BaseBlocks {

    // ---- 道(タグ overgrown_vegetation:road_blocks。基地どうしの道の台帳が後でこのタグを見る) ----

    /** アスファルト。道の台帳(未実装)が数えるのはタグ {@code road_blocks}。 */
    public static final DeferredBlock<Block> ASPHALT = ModBlocks.BLOCKS.registerSimpleBlock("asphalt", asphalt());
    public static final DeferredBlock<Block> ASPHALT_SLAB = ModBlocks.BLOCKS.registerBlock("asphalt_slab",
            SlabBlock::new, asphalt());
    public static final DeferredBlock<Block> ASPHALT_STAIRS = ModBlocks.BLOCKS.registerBlock("asphalt_stairs",
            props -> new StairBlock(ASPHALT.get().defaultBlockState(), props),
            asphalt());

    // ---- 壁 ----

    /** 鉄筋コンクリート。外周の壁と建物の材料。硬さ 8(TD の群れで 80 打撃)、爆発耐性 100(クリーパーでは崩れない)。 */
    public static final DeferredBlock<Block> REINFORCED_CONCRETE = ModBlocks.BLOCKS.registerSimpleBlock(
            "reinforced_concrete", concrete());
    public static final DeferredBlock<Block> REINFORCED_CONCRETE_SLAB = ModBlocks.BLOCKS.registerBlock(
            "reinforced_concrete_slab", SlabBlock::new, concrete());
    public static final DeferredBlock<Block> REINFORCED_CONCRETE_STAIRS = ModBlocks.BLOCKS.registerBlock(
            "reinforced_concrete_stairs",
            props -> new StairBlock(REINFORCED_CONCRETE.get().defaultBlockState(), props),
            concrete());
    /** 塀。#minecraft:walls に入れないと塀どうしがつながらない(その代わり汚染で overgrown_wall になる)。 */
    public static final DeferredBlock<Block> REINFORCED_CONCRETE_WALL = ModBlocks.BLOCKS.registerBlock(
            "reinforced_concrete_wall", WallBlock::new,
            concrete().forceSolidOn());

    // ---- 小物 ----

    /** 土嚢。シャベルで掘る。爆発耐性 30。 */
    public static final DeferredBlock<Block> SANDBAGS = ModBlocks.BLOCKS.registerSimpleBlock("sandbags",
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.SAND)
                    .sound(SoundType.WOOL)
                    .strength(2.0F, 30.0F));
    /** 金網フェンス。鉄格子と同じつながり方・当たり判定。 */
    public static final DeferredBlock<Block> CHAIN_LINK_FENCE = ModBlocks.BLOCKS.registerBlock("chain_link_fence",
            IronBarsBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BARS)
                    .mapColor(MapColor.METAL)
                    .sound(SoundType.CHAIN));
    /** 鋼板(縞鋼板の床)。 */
    public static final DeferredBlock<Block> STEEL_PLATE = ModBlocks.BLOCKS.registerSimpleBlock("steel_plate",
            BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK)
                    .mapColor(MapColor.METAL));
    /** 弾薬箱。27 スロットの収納(樽と同じ)。 */
    public static final DeferredBlock<Block> AMMO_CRATE = ModBlocks.BLOCKS.registerBlock("ammo_crate",
            AmmoCrateBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.BARREL)
                    .mapColor(MapColor.COLOR_GREEN)
                    // 機械はつるはしが適正・石以上で回収(2026-10-10 ユーザー指定。元の樽は斧・素手でも落ちる)
                    .requiresCorrectToolForDrops());
    /** 投光器。明るさ 15。向きは置いた人のほう。 */
    public static final DeferredBlock<Block> FLOODLIGHT = ModBlocks.BLOCKS.registerBlock("floodlight",
            FloodlightBlock::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .sound(SoundType.LANTERN)
                    .strength(3.5F, 6.0F)
                    .requiresCorrectToolForDrops()
                    .noOcclusion()
                    .lightLevel(state -> 15));

    // ---- 灯り ----

    /**
     * 妨害灯(2026-10-01)。忌避灯の上位: 半径の中のグループ6 以下を追い出し、過繁茂の Mob に弱体化と移動速度低下を掛ける
     * ({@link JammingLamps})。蔦に覆われると止まり、明るさ 3 に落ちる(忌避灯と同じ)。
     */
    public static final DeferredBlock<Block> JAMMING_LAMP = ModBlocks.BLOCKS.registerBlock("jamming_lamp",
            JammingLampBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.LANTERN)
                    .mapColor(MapColor.COLOR_RED)
                    .noOcclusion()
                    .lightLevel(state -> state.getValue(JammingLampBlock.COVERED) ? 3 : 15));

    /** クリエイティブタブ・アイテム登録の並び。 */
    private static final List<DeferredBlock<Block>> ALL = List.of(
            ASPHALT, ASPHALT_SLAB, ASPHALT_STAIRS,
            REINFORCED_CONCRETE, REINFORCED_CONCRETE_SLAB, REINFORCED_CONCRETE_STAIRS, REINFORCED_CONCRETE_WALL,
            SANDBAGS, CHAIN_LINK_FENCE, STEEL_PLATE, AMMO_CRATE, FLOODLIGHT, JAMMING_LAMP);

    private static final List<DeferredItem<BlockItem>> ITEMS = ALL.stream()
            .map(block -> ModItems.ITEMS.registerSimpleBlockItem(block, new Item.Properties()))
            .toList();

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<AmmoCrateBlockEntity>> AMMO_CRATE_ENTITY =
            ModBlockEntities.BLOCK_ENTITY_TYPES.register("ammo_crate", () -> BlockEntityType.Builder.of(
                    AmmoCrateBlockEntity::new, AMMO_CRATE.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<JammingLampBlockEntity>> JAMMING_LAMP_ENTITY =
            ModBlockEntities.BLOCK_ENTITY_TYPES.register("jamming_lamp", () -> BlockEntityType.Builder.of(
                    JammingLampBlockEntity::new, JAMMING_LAMP.get()).build(null));

    // ---- 管理棟 ----

    /**
     * 管理端末(2026-10-01)。管理棟の作戦室に置かれ、基地のレベルを見て上げる({@link BaseTerminalBlock})。
     * 作り方は無く(研究の封印の外)、壊しても落とさない —— 基地の計画が置く。{@link #ALL} には入れない(建材のテストの外)。
     */
    public static final DeferredBlock<Block> BASE_TERMINAL = ModBlocks.BLOCKS.registerBlock("base_terminal",
            BaseTerminalBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK)
                    .mapColor(MapColor.COLOR_GRAY)
                    .strength(8.0F, 100.0F)
                    .lightLevel(state -> 7));
    private static final DeferredItem<BlockItem> BASE_TERMINAL_ITEM =
            ModItems.ITEMS.registerSimpleBlockItem(BASE_TERMINAL, new Item.Properties());
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<BaseTerminalBlockEntity>> BASE_TERMINAL_ENTITY =
            ModBlockEntities.BLOCK_ENTITY_TYPES.register("base_terminal", () -> BlockEntityType.Builder.of(
                    BaseTerminalBlockEntity::new, BASE_TERMINAL.get()).build(null));

    /**
     * アスファルト系の性質(石と同じ硬さ 1.5 → TD の群れで 15 打撃)。ハーフ・階段も同じものを使う —
     * 静的初期化の時点では {@code ASPHALT.get()} がまだ無いので {@code ofFullCopy} で写せない。
     */
    private static BlockBehaviour.Properties asphalt() {
        return BlockBehaviour.Properties.ofFullCopy(Blocks.STONE)
                .mapColor(MapColor.COLOR_BLACK)
                .strength(1.5F, 6.0F);
    }

    /** 鉄筋コンクリート系の性質(硬さ 8、爆発耐性 100、ツルハシ必須。鉄以上は needs_iron_tool タグ)。 */
    private static BlockBehaviour.Properties concrete() {
        return BlockBehaviour.Properties.ofFullCopy(Blocks.LIGHT_GRAY_CONCRETE)
                .mapColor(MapColor.COLOR_LIGHT_GRAY)
                .requiresCorrectToolForDrops()
                .strength(8.0F, 100.0F);
    }

    /** 研究で解放する建材すべて(登録順。管理端末は入らない)。 */
    public static List<DeferredBlock<Block>> all() {
        return ALL;
    }

    /** 静的初期化で登録を済ませる。{@code ModBlocks.register} より前に呼ぶ。 */
    public static void init() {
    }

    @SubscribeEvent
    public static void addToCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == ModCreativeTabs.HUMANITY.getKey()) {
            ITEMS.forEach(item -> event.accept(item.get()));
            event.accept(BASE_TERMINAL_ITEM.get());
        }
    }

    @SubscribeEvent
    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, AMMO_CRATE_ENTITY.get(),
                (be, side) -> new InvWrapper(be));
    }

    private BaseBlocks() {
    }
}
