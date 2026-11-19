package com.yayuy.overgrownvegetation.organ;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.List;

/**
 * 森の器官の専用ブロックと根晶(2026-11-17)。共有の ModBlocks を太らせないよう、ここに自分の登録の口を持つ
 * ({@link OvergrownVegetation} から {@link #register} を 1 行呼ぶ)。絵の名前は organs セッションと合わせた。硬さ・光は【仮】。
 */
public final class OrganBlocks {
    private OrganBlocks() {
    }

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(OvergrownVegetation.MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(OvergrownVegetation.MODID);

    private static boolean alive(BlockState s) {
        return !s.getValue(OrganTissueBlock.WITHERED);
    }

    /** 嚢のかたまり(膜)。触れると破裂する({@link OrganSacBlock})。 */
    public static final DeferredBlock<Block> ORGAN_SAC = BLOCKS.registerBlock("organ_sac", OrganSacBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_LIGHT_GREEN).strength(1.2F).sound(SoundType.SLIME_BLOCK));

    /** 卵の房と標本の嚢のかたまり(苗床・フェーズを進める器官)。 */
    public static final DeferredBlock<Block> ORGAN_EGG_MASS = BLOCKS.registerBlock("organ_egg_mass", OrganTissueBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.TERRACOTTA_WHITE).strength(1.0F).sound(SoundType.FROGSPAWN));

    /** 芯。硬い【仮】。光 15、枯れたら 4。 */
    public static final DeferredBlock<Block> ORGAN_CORE = BLOCKS.registerBlock("organ_core", OrganCoreBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_YELLOW).strength(12.0F, 30.0F).sound(SoundType.SHROOMLIGHT)
                    .lightLevel(s -> alive(s) ? 15 : 4).pushReaction(PushReaction.BLOCK));

    /** 光る筋(面に這う)。光 9、枯れたら 0。 */
    public static final DeferredBlock<Block> ORGAN_VEIN = BLOCKS.registerBlock("organ_vein", OrganVeinBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_LIGHT_GREEN).noCollission().strength(0.2F).noOcclusion()
                    .sound(SoundType.GLOW_LICHEN).lightLevel(s -> alive(s) ? 9 : 0).pushReaction(PushReaction.DESTROY));

    /**
     * 融合の器官の蔦の触手(2026-11-19 構造物と森の融合 段 1)。袋から建物の壁へ伸びて巻きつく。空気のマスにだけ置く。
     * 器官が枯れると {@link OrganTissueBlock#WITHERED} になり、しばらくして消える。絵は仮(過繁茂の原木・枯れは嚢の枯れ色)→ Fable に頼む。
     */
    public static final DeferredBlock<Block> ORGAN_VINE = BLOCKS.registerBlock("organ_vine", OrganTissueBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_GREEN).strength(1.0F).sound(SoundType.ROOTS).pushReaction(PushReaction.DESTROY));

    /** 根晶の芽(段 3 の結晶。地脈を吸う根で育つ)。壊すと根晶。 */
    public static final DeferredBlock<Block> ROOT_CRYSTAL_CLUSTER = BLOCKS.registerBlock("root_crystal_cluster",
            p -> new AmethystClusterBlock(7.0F, 3.0F, p),
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_LIGHT_GREEN).forceSolidOn().noOcclusion().strength(1.5F)
                    .sound(SoundType.AMETHYST_CLUSTER).lightLevel(s -> 6).pushReaction(PushReaction.DESTROY));

    /** 根晶の塊(器官の壁の結晶)。壊すと根晶をいくつか。 */
    public static final DeferredBlock<Block> ROOT_CRYSTAL_BLOCK = BLOCKS.registerBlock("root_crystal_block", Block::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_LIGHT_GREEN).strength(3.0F).sound(SoundType.AMETHYST)
                    .lightLevel(s -> 8));

    /** 根晶(研究ツリーの段 3 の標本 {@code overgrown_vegetation:root_crystal})。 */
    public static final DeferredItem<Item> ROOT_CRYSTAL = ITEMS.registerSimpleItem("root_crystal", new Item.Properties());

    static {
        for (DeferredBlock<Block> b : List.of(ORGAN_SAC, ORGAN_EGG_MASS, ORGAN_CORE, ORGAN_VEIN, ORGAN_VINE, ROOT_CRYSTAL_CLUSTER, ROOT_CRYSTAL_BLOCK)) {
            ITEMS.registerSimpleBlockItem(b);
        }
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        OrganEntities.register(modBus);
    }
}
