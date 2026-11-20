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

    /**
     * 器官の茎(2026-11-20 建物用の器官 5 種類の形): 塔の角笛・嚢の柄・井戸の根と瘤。節のある肉質の茎。枯れたら {@link OrganTissueBlock#WITHERED}。
     * 絵は Fable の仮(ユーザー未確認)。硬さは嚢より少し固い【仮】。
     */
    public static final DeferredBlock<Block> ORGAN_STALK = BLOCKS.registerBlock("organ_stalk", OrganTissueBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_GREEN).strength(1.6F).sound(SoundType.ROOTS));

    // ---- 器官を組むブロック(2026-11-20 ユーザー決定「そろい 3 樹皮と髄」。外 = 暗い樹皮、柔らかい所と中 = 淡い髄)。硬さ・光は【仮】 ----
    /** 根の肋: 骨組み(今までの代用 = 過繁茂の原木)。 */
    public static final DeferredBlock<Block> ORGAN_RIB = BLOCKS.registerBlock("organ_rib", OrganRibBlock::new, // 11-20 向き(axis)を足した。既定は縦
            BlockBehaviour.Properties.of().mapColor(MapColor.PODZOL).strength(2.0F).sound(SoundType.WOOD));

    /** 硬い殻: 固い外側(今までの代用 = 巨大樹の原木)。 */
    public static final DeferredBlock<Block> ORGAN_SHELL = BLOCKS.registerBlock("organ_shell", OrganTissueBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.TERRACOTTA_BLACK).strength(3.0F, 6.0F).sound(SoundType.NETHER_WOOD));

    /** 髄: 柔らかい中身(今までの代用 = 巨大樹の形成層)。 */
    public static final DeferredBlock<Block> ORGAN_PITH = BLOCKS.registerBlock("organ_pith", OrganTissueBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_LIGHT_GREEN).strength(0.8F).sound(SoundType.WART_BLOCK));

    /** 膜: 半透明(まだ形では使っていない)。 */
    public static final DeferredBlock<Block> ORGAN_MEMBRANE = BLOCKS.registerBlock("organ_membrane", p -> new OrganTissueBlock(p) {
                @Override
                protected boolean skipRendering(BlockState state, BlockState adjacent, net.minecraft.core.Direction dir) {
                    return adjacent.is(this) || super.skipRendering(state, adjacent, dir);
                }
            },
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_LIGHT_GREEN).strength(0.4F).sound(SoundType.SLIME_BLOCK).noOcclusion()
                    .isSuffocating((s, l, q) -> false).isViewBlocking((s, l, q) -> false).isRedstoneConductor((s, l, q) -> false));

    /** 菌糸の床: 中の床と通路(まだ形では使っていない)。 */
    public static final DeferredBlock<Block> ORGAN_HYPHA_FLOOR = BLOCKS.registerBlock("organ_hypha_floor", OrganTissueBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_GREEN).strength(1.0F).sound(SoundType.MOSS));

    /** 棚の足場: 外を登る道(今までの代用 = プロトタキシーテスの笠)。 */
    public static final DeferredBlock<Block> ORGAN_SHELF = BLOCKS.registerBlock("organ_shelf", OrganTissueBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_GREEN).strength(1.2F).sound(SoundType.FUNGUS));

    /** 蜜: 蓄えの嚢の中身(まだ形では使っていない)。半透明・光 6、枯れたら 0。 */
    public static final DeferredBlock<Block> ORGAN_HONEY = BLOCKS.registerBlock("organ_honey", p -> new OrganTissueBlock(p) {
                @Override
                protected boolean skipRendering(BlockState state, BlockState adjacent, net.minecraft.core.Direction dir) {
                    return adjacent.is(this) || super.skipRendering(state, adjacent, dir);
                }
            },
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_YELLOW).strength(0.6F).sound(SoundType.HONEY_BLOCK).noOcclusion()
                    .lightLevel(s -> alive(s) ? 6 : 0).isSuffocating((s, l, q) -> false).isViewBlocking((s, l, q) -> false));

    /** 器官を組むブロック 7 つ(アイテムとクリエイティブタブ用)。 */
    private static final List<DeferredBlock<Block>> STRUCTURE_BLOCKS = List.of(ORGAN_RIB, ORGAN_SHELL, ORGAN_PITH, ORGAN_MEMBRANE, ORGAN_HYPHA_FLOOR, ORGAN_SHELF, ORGAN_HONEY);

    /**
     * 絡む根(2026-11-20 崖の汚染 A1 太い縄の滝。器官の 9 つの「絡む根」と共通)。何本もの根の塊で、すき間が透ける(マングローブの根と同じ作り)。
     * 横から体を押し付けると登れる({@link com.yayuy.overgrownvegetation.corruption.cliff.RootClimb})。絵は仮(Fable の案 A を透かした物)→ Fable が描き替える。
     */
    public static final DeferredBlock<Block> TANGLED_ROOT = BLOCKS.registerBlock("tangled_root", TangledRootBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.PODZOL).strength(1.0F).sound(SoundType.MANGROVE_ROOTS).noOcclusion()
                    .isSuffocating((s, l, p) -> false).isViewBlocking((s, l, p) -> false).isRedstoneConductor((s, l, p) -> false));

    /**
     * 器官の肉(2026-11-20 崖の汚染 B2 の割れ目に詰まる肉。器官の 9 つの「器官の肉」と同じ物の仮の登録)。絵は仮(Fable の案 A)。
     * 器官の方向(3 案)が決まったら Fable が描き替え、器官の組み立て({@code OrganWorld.stateOf})でも使う。
     */
    public static final DeferredBlock<Block> ORGAN_FLESH = BLOCKS.registerBlock("organ_flesh", OrganTissueBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_GREEN).strength(1.5F).sound(SoundType.WART_BLOCK));

    /** 崖の嚢(2026-11-20 崖の汚染 B2 嚢の房)。芯なし・触れても割れない・壊すと破裂して霧({@link com.yayuy.overgrownvegetation.corruption.cliff.CliffSacBlock})。 */
    public static final DeferredBlock<Block> CLIFF_SAC = BLOCKS.registerBlock("cliff_sac", com.yayuy.overgrownvegetation.corruption.cliff.CliffSacBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_LIGHT_GREEN).strength(0.8F).sound(SoundType.SLIME_BLOCK));

    /** 編んだ蔦(2026-11-20 洞窟の作り 柱1 ねじれた太い柱)。斜めの帯の編み目・透けない・登れない(崖の絡む根と分ける)。絵は仮(Fable の模型)→ Fable が描き替える。 */
    public static final DeferredBlock<Block> WOVEN_VINE = BLOCKS.registerBlock("woven_vine", Block::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_GREEN).strength(1.5F).sound(SoundType.MANGROVE_ROOTS));

    /** 洞窟の水ぶくれ(2026-11-20 洞窟の作り 嚢2)。ぼんやり光る(光 4【仮】)・芯なし・壊すと胞子の霧({@link com.yayuy.overgrownvegetation.corruption.cave.CaveBlisterBlock})。 */
    public static final DeferredBlock<Block> CAVE_BLISTER = BLOCKS.registerBlock("cave_blister", com.yayuy.overgrownvegetation.corruption.cave.CaveBlisterBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_LIGHT_GREEN).strength(0.8F).sound(SoundType.SLIME_BLOCK).lightLevel(s -> 4));

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
        for (DeferredBlock<Block> b : List.of(ORGAN_SAC, ORGAN_EGG_MASS, ORGAN_CORE, ORGAN_VEIN, ORGAN_VINE, ORGAN_STALK, ROOT_CRYSTAL_CLUSTER, ROOT_CRYSTAL_BLOCK, TANGLED_ROOT, ORGAN_FLESH, CLIFF_SAC, WOVEN_VINE, CAVE_BLISTER)) {
            ITEMS.registerSimpleBlockItem(b);
        }
        for (DeferredBlock<Block> b : STRUCTURE_BLOCKS) ITEMS.registerSimpleBlockItem(b);
    }

    /** クリエイティブタブに載せる(器官のブロックと根晶。2026-11-19 夜 ユーザー「器官のブロックとか、クリエイティブタブにないものはしっかり追加」)。 */
    public static void acceptItems(net.minecraft.world.item.CreativeModeTab.Output output) {
        for (DeferredBlock<Block> b : List.of(ORGAN_SAC, ORGAN_EGG_MASS, ORGAN_CORE, ORGAN_VEIN, ORGAN_VINE, ORGAN_STALK, ROOT_CRYSTAL_CLUSTER, ROOT_CRYSTAL_BLOCK, TANGLED_ROOT, ORGAN_FLESH, CLIFF_SAC, WOVEN_VINE, CAVE_BLISTER)) {
            output.accept(b.get());
        }
        for (DeferredBlock<Block> b : STRUCTURE_BLOCKS) output.accept(b.get());
        output.accept(ROOT_CRYSTAL.get());
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        OrganEntities.register(modBus);
    }
}
