package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.corruption.CorruptionRegistry;
import com.yayuy.overgrownvegetation.corruption.SwampTreeFall;
import com.yayuy.overgrownvegetation.block.OvergrownContainers;
import com.yayuy.overgrownvegetation.block.OvergrownHuskBlock;
import com.yayuy.overgrownvegetation.corruption.OvergrownLayerData;
import com.yayuy.overgrownvegetation.covered.CoveredBlocks;
import com.yayuy.overgrownvegetation.covered.CoveredInteractables;
import com.yayuy.overgrownvegetation.covered.CoveredLight;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.ModCreativeTabs;
import com.yayuy.overgrownvegetation.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.CopperBulbBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.minecraft.world.level.block.state.properties.WallSide;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 覆われたブロック({@link CoveredBlocks}、2026-10-05)のテスト。
 *
 * <p>覆われたブロックは<b>バニラと同じに働く</b>のが約束 —— ドロップ・タグ・採掘の道具はバニラから生成器で写している
 * ({@code tools/covered-blocks})。写し漏れ(タグに入っていないと鉄ブロックが素手で…ではなく、何で掘っても落ちない)は
 * runClient でしか気付けないので、ここでバニラと突き合わせる。見た目(苔)はクライアントなのでここでは見ない。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class CoveredBlockGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(CoveredBlockGameTests.class);
        }

        private Registration() {
        }
    }

    /** 働きに効くブロックタグ(つながり・採掘・ビーコン・壊れにくさ)。覆われたほうもバニラと同じでなければならない。 */
    private static final List<TagKey<Block>> BLOCK_TAGS = List.of(
            BlockTags.MINEABLE_WITH_PICKAXE, BlockTags.MINEABLE_WITH_AXE, BlockTags.MINEABLE_WITH_SHOVEL,
            BlockTags.MINEABLE_WITH_HOE, BlockTags.NEEDS_STONE_TOOL, BlockTags.NEEDS_IRON_TOOL, BlockTags.NEEDS_DIAMOND_TOOL,
            BlockTags.STAIRS, BlockTags.SLABS, BlockTags.WALLS, BlockTags.FENCES, BlockTags.WOODEN_FENCES,
            BlockTags.FENCE_GATES, BlockTags.DOORS, BlockTags.WOODEN_DOORS, BlockTags.TRAPDOORS, BlockTags.BUTTONS,
            BlockTags.PRESSURE_PLATES, BlockTags.LOGS, BlockTags.PLANKS, BlockTags.BEACON_BASE_BLOCKS,
            BlockTags.WITHER_IMMUNE, BlockTags.DRAGON_IMMUNE, BlockTags.MOB_INTERACTABLE_DOORS);

    /** 燃料・作業台のレシピに効くアイテムタグ。原木だけは木の種類ごとのタグに入れない(下のテスト)。 */
    private static final List<TagKey<Item>> ITEM_TAGS = List.of(
            ItemTags.LOGS, ItemTags.LOGS_THAT_BURN, ItemTags.PLANKS, ItemTags.WOODEN_SLABS, ItemTags.WOODEN_STAIRS,
            ItemTags.WOODEN_FENCES, ItemTags.FENCE_GATES, ItemTags.WOODEN_DOORS, ItemTags.WOODEN_TRAPDOORS,
            ItemTags.WOODEN_BUTTONS, ItemTags.WOODEN_PRESSURE_PLATES, ItemTags.NON_FLAMMABLE_WOOD,
            ItemTags.STONE_TOOL_MATERIALS, ItemTags.STONE_CRAFTING_MATERIALS);

    /** バニラの板材のレシピの材料になる、木の種類ごとのタグ。覆われた原木を入れるとバニラの板材が出てしまう。 */
    private static final List<TagKey<Item>> PER_WOOD_LOGS = List.of(
            ItemTags.OAK_LOGS, ItemTags.SPRUCE_LOGS, ItemTags.BIRCH_LOGS, ItemTags.JUNGLE_LOGS, ItemTags.ACACIA_LOGS,
            ItemTags.DARK_OAK_LOGS, ItemTags.MANGROVE_LOGS, ItemTags.CHERRY_LOGS, ItemTags.BAMBOO_BLOCKS,
            ItemTags.CRIMSON_STEMS, ItemTags.WARPED_STEMS);

    /** 形・タグ・採掘の道具がバニラと同じ。汚染済みのタグ(#corrupted)に入っている。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void coveredBlocksBehaveLikeVanilla(GameTestHelper helper) {
        List<ItemStack> tools = List.of(ItemStack.EMPTY, new ItemStack(Items.WOODEN_PICKAXE), new ItemStack(Items.STONE_PICKAXE),
                new ItemStack(Items.IRON_PICKAXE), new ItemStack(Items.DIAMOND_PICKAXE), new ItemStack(Items.WOODEN_AXE),
                new ItemStack(Items.WOODEN_SHOVEL), new ItemStack(Items.WOODEN_HOE));
        if (CoveredBlocks.entries().size() != 367) { // 建築タブ 366 + レバー
            helper.fail("覆われたブロックが 367 個でない: " + CoveredBlocks.entries().size());
            return;
        }
        for (CoveredBlocks.Entry entry : CoveredBlocks.entries()) {
            Block covered = entry.block().get();
            Block vanilla = entry.vanilla();
            String name = entry.block().getId().getPath();
            Set<String> coveredProps = covered.getStateDefinition().getProperties().stream()
                    .map(Property::getName).collect(Collectors.toSet());
            Set<String> vanillaProps = vanilla.getStateDefinition().getProperties().stream()
                    .map(Property::getName).collect(Collectors.toSet());
            // 手で動かすもの(扉など)だけは覆われ具合 overgrowth を足してある(時々動かない仕様)
            boolean interactable = covered instanceof CoveredInteractables.Door || covered instanceof CoveredInteractables.TrapDoor
                    || covered instanceof CoveredInteractables.FenceGate || covered instanceof CoveredInteractables.Button
                    || covered instanceof CoveredInteractables.Lever;
            if (interactable != coveredProps.remove(OvergrownContainers.OVERGROWTH.getName())) {
                helper.fail(name + " の覆われ具合の項目の有無がおかしい(手で動かすもの=" + interactable + ")");
                return;
            }
            if (!coveredProps.equals(vanillaProps)) {
                helper.fail(name + " のブロック状態の項目がバニラと違う: " + coveredProps + " / " + vanillaProps);
                return;
            }
            BlockState c = covered.defaultBlockState();
            BlockState v = vanilla.defaultBlockState();
            if (CoveredBlocks.vanillaState(c) != v) {
                helper.fail(name + " の写し先がバニラの既定の状態でない: " + CoveredBlocks.vanillaState(c));
                return;
            }
            for (TagKey<Block> tag : BLOCK_TAGS) {
                if (c.is(tag) != v.is(tag)) {
                    helper.fail(name + " のタグ " + tag.location() + " がバニラと違う(覆われた=" + c.is(tag) + ")");
                    return;
                }
            }
            if (c.requiresCorrectToolForDrops() != v.requiresCorrectToolForDrops()) {
                helper.fail(name + " の「道具が要る」がバニラと違う");
                return;
            }
            for (ItemStack tool : tools) {
                if (tool.isCorrectToolForDrops(c) != tool.isCorrectToolForDrops(v)
                        || tool.getDestroySpeed(c) != tool.getDestroySpeed(v)) {
                    helper.fail(name + " を " + tool + " で掘ったときの扱いがバニラと違う");
                    return;
                }
            }
            if (!c.is(ModTags.CORRUPTED)) {
                helper.fail(name + " が汚染済みのタグ(#corrupted)に無い(汚染済みの判定・基地の修理から漏れる)");
                return;
            }
            ItemStack cs = new ItemStack(entry.item().get());
            ItemStack vs = new ItemStack(vanilla.asItem());
            for (TagKey<Item> tag : ITEM_TAGS) {
                if (cs.is(tag) != vs.is(tag)) {
                    helper.fail(name + " のアイテムタグ " + tag.location() + " がバニラと違う(覆われた=" + cs.is(tag) + ")");
                    return;
                }
            }
            for (TagKey<Item> tag : PER_WOOD_LOGS) {
                if (cs.is(tag)) {
                    helper.fail(name + " が " + tag.location() + " に入っている(バニラの板材のレシピに当たる)");
                    return;
                }
            }
        }
        helper.succeed();
    }

    /**
     * ドロップがバニラと同じ(建築タブのブロックは覆われたほうに置き換わる)。同じ種で引くので、
     * 海のランタンのプリズマリンクリスタルの数・きらめくブラックストーンの金塊まで一致する。
     * 覆われた石はシルクタッチ無しで覆われた丸石、ハーフの 2 枚重ねは 2 枚、ドアは上半分からは落ちない。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void coveredDropsMirrorVanilla(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        BlockPos pos = helper.absolutePos(new BlockPos(3, 2, 3));
        Map<Item, Item> toCovered = new HashMap<>();
        for (CoveredBlocks.Entry entry : CoveredBlocks.entries()) {
            toCovered.put(entry.vanilla().asItem(), entry.item().get());
        }
        for (CoveredBlocks.Entry entry : CoveredBlocks.entries()) {
            Block covered = entry.block().get();
            String name = entry.block().getId().getPath();
            LootTable table = server.reloadableRegistries().getLootTable(covered.getLootTable());
            if (table == LootTable.EMPTY && !entry.vanilla().defaultBlockState().is(Blocks.REINFORCED_DEEPSLATE)) {
                helper.fail(name + " のドロップの表が無い");
                return;
            }
            List<BlockState> states = new ArrayList<>(List.of(covered.defaultBlockState()));
            if (covered instanceof SlabBlock) {
                states.add(covered.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.DOUBLE));
            }
            if (covered instanceof DoorBlock) {
                states.add(covered.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
            }
            for (BlockState c : states) {
                BlockState v = CoveredBlocks.vanillaState(c);
                ItemStack tool = toolFor(v);
                List<String> mine = describe(roll(server, level, pos, c, tool), Map.of());
                List<String> theirs = describe(roll(server, level, pos, v, tool), toCovered);
                if (!mine.equals(theirs)) {
                    helper.fail(name + " (" + c + ") のドロップがバニラと違う: " + mine + " / バニラ " + theirs);
                    return;
                }
            }
        }
        // 代表で 1 つ、中身まで見る(石 → 丸石)。
        BlockState stone = covered("stone").defaultBlockState();
        List<String> drops = describe(roll(server, level, pos, stone, new ItemStack(Items.IRON_PICKAXE)), Map.of());
        if (!drops.equals(List.of(id(covered("cobblestone").asItem()) + "x1"))) {
            helper.fail("覆われた石を鉄のツルハシで掘ったら覆われた丸石 1 個のはず: " + drops);
            return;
        }
        helper.succeed();
    }

    /** 覆われた原木 → 覆われた板材(バニラと同じ数)。過繁茂している原木 → 過繁茂している板材。バニラの原木は今までどおり。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void coveredLogsMakeCoveredPlanks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int checked = 0;
        for (CoveredBlocks.Entry entry : CoveredBlocks.entries()) {
            ItemStack vanillaResult = craft(level, new ItemStack(entry.vanilla().asItem()));
            ItemStack coveredResult = craft(level, new ItemStack(entry.item().get()));
            String name = entry.block().getId().getPath();
            boolean vanillaMakesPlanks = !vanillaResult.isEmpty() && vanillaResult.is(ItemTags.PLANKS);
            if (!vanillaMakesPlanks) {
                if (!coveredResult.isEmpty() && BuiltInRegistries.ITEM.getKey(coveredResult.getItem()).getNamespace().equals("minecraft")) {
                    helper.fail(name + " 1 個からバニラの " + coveredResult + " が作れてしまう");
                    return;
                }
                continue;
            }
            Item expected = covered(BuiltInRegistries.ITEM.getKey(vanillaResult.getItem()).getPath()).asItem();
            if (!coveredResult.is(expected) || coveredResult.getCount() != vanillaResult.getCount()) {
                helper.fail(name + " から " + id(expected) + " ×" + vanillaResult.getCount() + " が作れない: " + coveredResult);
                return;
            }
            checked++;
        }
        if (checked != 42) { // 11 種の木 × 原木・木・樹皮を剥いだ原木・樹皮を剥いだ木(竹は 2 つ)
            helper.fail("板材になる覆われた原木が 42 個でない: " + checked);
            return;
        }
        // 巨大樹の原木・形成層も過繁茂している原木と同じく板材になる(2026-10-09 ユーザー指定)。
        for (Item log : List.of(ModBlocks.OVERGROWN_LOG.get().asItem(), ModBlocks.OVERGROWN_WOOD.get().asItem(),
                ModBlocks.GIANT_OVERGROWN_LOG.get().asItem(), ModBlocks.GIANT_OVERGROWN_CAMBIUM.get().asItem())) {
            ItemStack planks = craft(level, new ItemStack(log));
            if (!planks.is(ModBlocks.OVERGROWN_PLANKS.get().asItem()) || planks.getCount() != 4) {
                helper.fail(id(log) + " から過繁茂している板材 4 個が作れない: " + planks);
                return;
            }
        }
        for (Item other : List.of(Items.OAK_LOG, covered("oak_log").asItem(), ModBlocks.OVERGROWN_SWAMP_LOG.get().asItem())) {
            if (craft(level, new ItemStack(other)).is(ModBlocks.OVERGROWN_PLANKS.get().asItem())) {
                helper.fail(id(other) + " から過繁茂している板材が作れてしまう(過繁茂している原木・巨大樹だけのはず)");
                return;
            }
        }
        if (!craft(level, new ItemStack(Items.OAK_LOG)).is(Items.OAK_PLANKS)) {
            helper.fail("バニラのオークの原木からオークの板材が作れなくなった");
            return;
        }
        helper.succeed();
    }

    /** 汚染は覆われたブロックに触らない(苔を重ねない・汎用の汚染ブロックに置き換えない)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void corruptionLeavesCoveredBlocksAlone(GameTestHelper helper) {
        BlockPos pos = new BlockPos(3, 2, 3);
        for (String id : List.of("stone_bricks", "oak_stairs", "iron_bars", "oak_door", "copper_bulb", "cobblestone_wall")) {
            BlockState state = covered(id).defaultBlockState();
            if (CorruptionRegistry.canCorrupt(helper.getLevel(), helper.absolutePos(pos), state)) {
                helper.fail("covered_" + id + " が汚染できることになっている");
                return;
            }
        }
        helper.succeed();
    }

    /**
     * 汚染は建築タブのブロックを<b>同じブロックの覆われた版</b>にする(2026-10-05 夜、ユーザー指定「汚染された扉が対応した扉に
     * ならず過繁茂したドアになった。ほかにもあれば直して」)。ただし<b>3 割は同じ形の過繁茂ブロック</b>(同じく夜、ユーザー指定):
     * 立方体 → 過繁茂しているブロック、ハーフ → そのハーフ、階段 → 過繁茂した石階段、塀 → 過繁茂した塀、フェンス → 過繁茂したフェンス。
     * 同じ形の無いもの(扉・ボタンなど)は必ず覆われた版。蝋引きの銅は蝋を引いていない版の覆われたブロック。
     * <b>木材(板材・木の階段・ハーフ・フェンス・原木・木)は過繁茂した木材 5:過繁茂ブロック 1.5:覆われた版 3.5</b>(夜 3、ユーザー指定)。
     * 前から専用の過繁茂ブロックがある 10 個だけはそちら。向き・形・開閉・水没は引き継ぎ、手で動かすものは覆われ具合が最大で来る。
     * 割合は 1 本の乱数で 200 回引いて見る(種はブロックごとに決まっているので、毎回同じ数になる)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void corruptionTurnsBuildingBlocksIntoCoveredOnes(GameTestHelper helper) {
        Set<String> dedicated = Set.of("stone", "cobblestone", "granite", "diorite", "andesite", "deepslate",
                "cobbled_deepslate", "bricks", "netherrack", "end_stone");
        Map<Block, Block> table = CoveredBlocks.coveredByVanilla();
        if (table.size() != 367 + 36) { // 蝋引きの銅 36 個も覆われた銅へ
            helper.fail("覆われた版の表が 403 件でない: " + table.size());
            return;
        }
        int woodKinds = 0;
        for (Map.Entry<Block, Block> e : table.entrySet()) {
            String id = BuiltInRegistries.BLOCK.getKey(e.getKey()).getPath();
            Block wood = expectedWood(e.getKey().defaultBlockState());
            Block counterpart = expectedCounterpart(e.getKey().defaultBlockState());
            boolean own = false;
            int coveredCount = 0;
            int woodCount = 0;
            int counterpartCount = 0;
            // 1 本の乱数を回し続ける。種を 0, 1, 2… と変えて最初の 1 回だけ引くと、どれも 0.73 前後になり(線形合同法の癖)
            // 3 割側ばかり出る(実際に 60/60 になって落ちた)。
            RandomSource rolls = RandomSource.create(20261005L ^ id.hashCode());
            for (int n = 0; n < 200; n++) {
                BlockState result = CorruptionRegistry.corrupt(e.getKey().defaultBlockState(), rolls);
                if (dedicated.contains(id)) {
                    if (result.is(e.getValue())) {
                        helper.fail(id + " は専用の過繁茂ブロックへ行くはずが、覆われたブロックになった");
                        return;
                    }
                    own |= BuiltInRegistries.BLOCK.getKey(result.getBlock()).getPath().equals("overgrown_" + id);
                } else if (result.is(e.getValue())) {
                    coveredCount++;
                } else if (wood != null && result.is(wood)) {
                    woodCount++; // 木のフェンスは過繁茂ブロックの側も過繁茂したフェンスなので、こちらに数える
                } else if (counterpart != null && result.is(counterpart)) {
                    counterpartCount++;
                } else {
                    helper.fail(id + " が覆われた版にも過繁茂ブロックにもならない: " + result);
                    return;
                }
            }
            if (dedicated.contains(id)) {
                if (!own) {
                    helper.fail(id + " が専用の過繁茂ブロック overgrown_" + id + " にならない");
                    return;
                }
                continue;
            }
            boolean ok;
            if (wood != null && wood != counterpart) {
                woodKinds++;
                ok = within(woodCount, 70, 130) && within(coveredCount, 40, 100) && within(counterpartCount, 10, 55);
            } else if (wood != null) {
                woodKinds++;
                ok = within(coveredCount, 40, 100); // 木のフェンス: 過繁茂した木材と過繁茂ブロックが同じ過繁茂したフェンス
            } else if (counterpart != null) {
                ok = within(coveredCount, 110, 170) && within(counterpartCount, 30, 90);
            } else {
                ok = coveredCount == 200;
            }
            if (!ok) {
                helper.fail(id + " の行き先の割合がおかしい(200 回): 覆われた版 " + coveredCount + "、過繁茂した木材 " + woodCount
                        + "、同じ形の過繁茂ブロック " + counterpartCount + "(期待 木材 " + wood + " / ブロック " + counterpart + ")");
                return;
            }
        }
        if (woodKinds < 60) {
            helper.fail("木材として扱われたブロックが少なすぎる: " + woodKinds);
            return;
        }
        RandomSource random = RandomSource.create(20261005L);
        List<BlockState> samples = List.of(
                Blocks.OAK_STAIRS.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)
                        .setValue(BlockStateProperties.HALF, Half.TOP).setValue(BlockStateProperties.STAIRS_SHAPE, StairsShape.OUTER_LEFT)
                        .setValue(BlockStateProperties.WATERLOGGED, true),
                Blocks.SPRUCE_SLAB.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE, SlabType.TOP),
                Blocks.BIRCH_LOG.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.X),
                Blocks.OAK_TRAPDOOR.defaultBlockState().setValue(BlockStateProperties.OPEN, true)
                        .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH).setValue(BlockStateProperties.HALF, Half.TOP),
                Blocks.LEVER.defaultBlockState().setValue(BlockStateProperties.ATTACH_FACE, AttachFace.WALL)
                        .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH).setValue(BlockStateProperties.POWERED, true),
                Blocks.WAXED_EXPOSED_CUT_COPPER_STAIRS.defaultBlockState()
                        .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST),
                Blocks.STONE_BRICK_WALL.defaultBlockState().setValue(BlockStateProperties.UP, false));
        for (BlockState vanilla : samples) {
            // 覆われた版も、同じ形の過繁茂ブロック(立方体の過繁茂しているブロックには向きが無いので除く)も、元の向き・形を持つ
            for (int i = 0; i < 60; i++) {
                BlockState result = CorruptionRegistry.corrupt(vanilla, random);
                if (result.is(ModBlocks.OVERGROWN_BLOCK.get()) || result.is(CoveredBlocks.OVERGROWN_TRUNK_BLOCK.get())) {
                    continue;
                }
                for (Property<?> property : vanilla.getProperties()) {
                    if (!result.hasProperty(property) || !result.getValue(property).equals(vanilla.getValue(property))) {
                        helper.fail(vanilla + " を汚染したら " + property.getName() + " が変わった: " + result);
                        return;
                    }
                }
                if (result.hasProperty(OvergrownContainers.OVERGROWTH)
                        && result.getValue(OvergrownContainers.OVERGROWTH) != OvergrownContainers.MAX_LEVEL) {
                    helper.fail(vanilla + " を汚染した覆われ具合が最大でない: " + result);
                    return;
                }
            }
        }
        boolean litSeen = false;
        for (int i = 0; i < 60; i++) {
            BlockState lantern = CorruptionRegistry.corrupt(Blocks.SEA_LANTERN.defaultBlockState(), random);
            if (lantern.is(covered("sea_lantern"))) {
                litSeen = true;
                if (lantern.getLightEmission() != CoveredLight.dim(15)) {
                    helper.fail("汚染された海のランタンが 4 割の明るさの覆われた海のランタンにならない");
                    return;
                }
            } else if (!lantern.is(ModBlocks.OVERGROWN_BLOCK.get())) {
                helper.fail("汚染された海のランタンが想定外のブロックになった: " + lantern);
                return;
            }
        }
        if (!litSeen) {
            helper.fail("汚染された海のランタンが 60 回とも過繁茂しているブロックになった(7 割は覆われた版のはず)");
            return;
        }
        helper.succeed();
    }

    private static boolean within(int value, int min, int max) {
        return value >= min && value <= max;
    }

    /** 木材の半分で来るはずの過繁茂した木材(木材でなければ null)。本体の規則と別に書いて突き合わせる。扉は入れない(ユーザーが選ばなかった)。 */
    private static Block expectedWood(BlockState vanilla) {
        if (vanilla.is(BlockTags.PLANKS)) {
            return ModBlocks.OVERGROWN_PLANKS.get();
        }
        if (vanilla.is(BlockTags.WOODEN_STAIRS)) {
            return ModBlocks.OVERGROWN_STAIRS.get();
        }
        if (vanilla.is(BlockTags.WOODEN_SLABS)) {
            return ModBlocks.OVERGROWN_SLAB.get();
        }
        if (vanilla.is(BlockTags.WOODEN_FENCES)) {
            return ModBlocks.OVERGROWN_FENCE.get();
        }
        if (vanilla.is(BlockTags.LOGS)) {
            String path = BuiltInRegistries.BLOCK.getKey(vanilla.getBlock()).getPath();
            return path.endsWith("_wood") || path.endsWith("_hyphae") ? ModBlocks.OVERGROWN_WOOD.get() : ModBlocks.OVERGROWN_LOG.get();
        }
        return null;
    }

    /** 覆われた版の代わりの 3 割で来るはずの、同じ形の過繁茂ブロック(無ければ null)。本体の規則と別に書いて突き合わせる。 */
    private static Block expectedCounterpart(BlockState vanilla) {
        Block block = vanilla.getBlock();
        if (block instanceof SlabBlock) {
            return ModBlocks.OVERGROWN_BLOCK_SLAB.get();
        }
        if (block instanceof net.minecraft.world.level.block.StairBlock) {
            return ModBlocks.OVERGROWN_STONE_STAIRS.get();
        }
        if (block instanceof WallBlock) {
            return ModBlocks.OVERGROWN_WALL.get();
        }
        if (block instanceof FenceBlock) {
            return ModBlocks.OVERGROWN_FENCE.get();
        }
        if (!com.yayuy.overgrownvegetation.corruption.OvergrownLayerField.isPlainCube(vanilla)) {
            return null;
        }
        // 原木から化けるときは見た目が同じで #logs に入った版(木を片付けるとき宙に残らない)
        return vanilla.is(BlockTags.LOGS) ? CoveredBlocks.OVERGROWN_TRUNK_BLOCK.get() : ModBlocks.OVERGROWN_BLOCK.get();
    }

    /**
     * 行き先は<b>座標で決まる</b>(対応表に無い立方体の混ざりと同じハッシュ {@code OvergrownLayerField.roll})。同じマスに同じブロックを置けば必ず同じ答え。
     * マスの値 u で 4 つに分けて見る(既定の {@code overgrownLayer.chance} 0.7 のとき):
     * <ul>
     *     <li>u &lt; 0.5: 木材 → 過繁茂した木材、石 → 覆われた版</li>
     *     <li>0.5 ≦ u &lt; 0.7: どちらも覆われた版</li>
     *     <li>0.7 ≦ u &lt; 0.85: 木材 → 覆われた版、石 → 同じ形の過繁茂ブロック</li>
     *     <li>0.85 ≦ u: どちらも同じ形の過繁茂ブロック</li>
     * </ul>
     * 扉はどのマスでも覆われた扉(同じ形が無い・木材に入れない)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void threeInTenBecomeOvergrownByPosition(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        double chance = com.yayuy.overgrownvegetation.data.DesignData.climax().overgrownLayerChance();
        if (chance != 0.7D) {
            helper.succeed(); // 区切りの数字はこの値で書いてある(設定を変えたら書き直す)
            return;
        }
        double[] bounds = {0.0D, 0.5D, 0.7D, 0.85D, 1.0D};
        BlockPos[] cells = new BlockPos[4];
        for (int y = 2; y <= 4; y++) {
            for (int x = 0; x < 7; x++) {
                for (int z = 0; z < 7; z++) {
                    BlockPos at = helper.absolutePos(new BlockPos(x, y, z));
                    double u = com.yayuy.overgrownvegetation.corruption.OvergrownLayerField.roll(at);
                    for (int k = 0; k < 4; k++) {
                        if (cells[k] == null && u >= bounds[k] && u < bounds[k + 1]) {
                            cells[k] = at;
                        }
                    }
                }
            }
        }
        for (BlockPos cell : cells) {
            if (cell == null) {
                helper.fail("テスト場に 4 つの区間のマスが揃わない");
                return;
            }
        }
        BlockState oakSlab = Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE, SlabType.TOP);
        BlockState stoneSlab = Blocks.STONE_BRICK_SLAB.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE, SlabType.TOP);
        BlockState oakLog = Blocks.OAK_LOG.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.X);
        Block[][] want = {
                // 木のハーフ, 石レンガのハーフ, オークの原木
                {ModBlocks.OVERGROWN_SLAB.get(), covered("stone_brick_slab"), ModBlocks.OVERGROWN_LOG.get()},
                {covered("oak_slab"), covered("stone_brick_slab"), covered("oak_log")},
                {covered("oak_slab"), ModBlocks.OVERGROWN_BLOCK_SLAB.get(), covered("oak_log")},
                {ModBlocks.OVERGROWN_BLOCK_SLAB.get(), ModBlocks.OVERGROWN_BLOCK_SLAB.get(), CoveredBlocks.OVERGROWN_TRUNK_BLOCK.get()}};
        for (int k = 0; k < 4; k++) {
            BlockState[] inputs = {oakSlab, stoneSlab, oakLog};
            for (int j = 0; j < inputs.length; j++) {
                for (int i = 0; i < 2; i++) { // 乱数によらない
                    BlockState result = CorruptionRegistry.corrupt(level, cells[k], inputs[j], RandomSource.create(i * 7919L + k));
                    if (!result.is(want[k][j])) {
                        helper.fail("区間 " + bounds[k] + "〜" + bounds[k + 1] + " のマスで " + inputs[j].getBlock() + " が "
                                + result + " になった(期待 " + want[k][j] + ")");
                        return;
                    }
                    if (result.hasProperty(BlockStateProperties.SLAB_TYPE) && result.getValue(BlockStateProperties.SLAB_TYPE) != SlabType.TOP
                            || result.hasProperty(BlockStateProperties.AXIS) && result.getValue(BlockStateProperties.AXIS) != Direction.Axis.X) {
                        helper.fail("ハーフの上下・原木の向きが変わった: " + result);
                        return;
                    }
                }
            }
            if (!CorruptionRegistry.corrupt(level, cells[k], Blocks.OAK_DOOR.defaultBlockState(), RandomSource.create(1))
                    .is(covered("oak_door"))) {
                helper.fail("区間 " + bounds[k] + " のマスで扉が覆われた扉にならない(扉は必ず覆われた版)");
                return;
            }
        }
        helper.succeed();
    }

    /**
     * 汚染された扉は上下とも同じ木の覆われた扉になる(向き・蝶番・開閉はそのまま、覆われ具合は最大)。上下どちらに当たっても。
     * 鉄の扉(手で開かないので覆われ具合を持たない)・蝋引きの銅の扉も落ちずに覆われた扉になる。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void corruptedDoorsBecomeCoveredDoors(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        record Case(BlockPos rel, BlockState door, boolean hitUpper) {
        }
        List<Case> cases = List.of(
                new Case(new BlockPos(1, 2, 2), Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.EAST)
                        .setValue(DoorBlock.OPEN, true).setValue(DoorBlock.HINGE, DoorHingeSide.RIGHT), true),
                new Case(new BlockPos(3, 2, 2), Blocks.SPRUCE_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH), false),
                new Case(new BlockPos(5, 2, 2), Blocks.IRON_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.SOUTH), true),
                new Case(new BlockPos(1, 2, 5), Blocks.WAXED_OXIDIZED_COPPER_DOOR.defaultBlockState().setValue(DoorBlock.OPEN, true), false));
        for (Case c : cases) {
            BlockPos lower = helper.absolutePos(c.rel());
            place(helper, lower, c.door());
            BlockPos hit = c.hitUpper() ? lower.above() : lower;
            BlockState before = level.getBlockState(hit);
            BlockState corrupted = CorruptionRegistry.corrupt(level, hit, before, RandomSource.create(7));
            CorruptionRegistry.placeCorrupted(level, hit, before, corrupted, Block.UPDATE_ALL);
            Block expected = CoveredBlocks.coveredOf(c.door().getBlock());
            for (BlockPos half : List.of(lower, lower.above())) {
                BlockState s = level.getBlockState(half);
                DoubleBlockHalf want = half.equals(lower) ? DoubleBlockHalf.LOWER : DoubleBlockHalf.UPPER;
                if (!s.is(expected) || s.getValue(DoorBlock.HALF) != want
                        || s.getValue(DoorBlock.FACING) != c.door().getValue(DoorBlock.FACING)
                        || s.getValue(DoorBlock.OPEN) != c.door().getValue(DoorBlock.OPEN)
                        || s.getValue(DoorBlock.HINGE) != c.door().getValue(DoorBlock.HINGE)) {
                    helper.fail(c.door().getBlock() + " の " + want + " が覆われた扉にならない / 向き・開閉が変わった: " + s);
                    return;
                }
                if (s.hasProperty(OvergrownContainers.OVERGROWTH)
                        && s.getValue(OvergrownContainers.OVERGROWTH) != OvergrownContainers.MAX_LEVEL) {
                    helper.fail(c.door().getBlock() + " の " + want + " の覆われ具合が最大でない: " + s);
                    return;
                }
            }
        }
        helper.succeed();
    }

    /**
     * 元の森の原木を汚染すると、覆われた原木になる道筋でも湿地・密林の「元の木を残さない」処理({@code SwampTreeFall})に積まれる。
     * 覆われた版への置き換えは特別扱いで早く返るので、積むのはその前(以前は対応表の後ろで積んでいて、そのままだと積まれなくなる)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void corruptedLogsStillQueueTreeFall(GameTestHelper helper) {
        if (!OvergrownVegetationConfig.COMMON.swampTreeFall.get() && !OvergrownVegetationConfig.COMMON.jungleTreeFall.get()) {
            helper.succeed(); // 設定で両方切ってある(積まないのが正しい)
            return;
        }
        ServerLevel level = helper.getLevel();
        BlockPos log = helper.absolutePos(new BlockPos(3, 2, 3));
        BlockState oak = Blocks.OAK_LOG.defaultBlockState();
        level.setBlock(log, oak, Block.UPDATE_CLIENTS);
        BlockState corrupted = CorruptionRegistry.corrupt(level, log, oak, RandomSource.create(5));
        // 木材の行き先: 過繁茂している原木 5:過繁茂しているブロック 1.5:覆われた原木 3.5(座標で決まる)
        if (!corrupted.is(covered("oak_log")) && !corrupted.is(ModBlocks.OVERGROWN_LOG.get())
                && !corrupted.is(CoveredBlocks.OVERGROWN_TRUNK_BLOCK.get())) {
            helper.fail("オークの原木が覆われた原木・過繁茂している原木・過繁茂しているブロックのどれにもならない: " + corrupted);
            return;
        }
        try {
            java.lang.reflect.Field field = SwampTreeFall.class.getDeclaredField("QUEUE");
            field.setAccessible(true);
            Object set = ((Map<?, ?>) field.get(null)).get(level.dimension());
            if (!(set instanceof it.unimi.dsi.fastutil.longs.LongCollection queue) || !queue.contains(log.asLong())) {
                helper.fail("汚染した森の原木が「元の木を残さない」処理に積まれていない");
                return;
            }
        } catch (ReflectiveOperationException e) {
            helper.fail("SwampTreeFall の QUEUE を読めない: " + e);
            return;
        }
        helper.succeed();
    }

    /**
     * 原木から化けた過繁茂しているブロック(2026-10-05 夜、ユーザー報告「倒木や木を消すときに空中に残る」)は<b>木の一部</b>:
     * {@code #logs}・汚染済み、湿地・密林の木の片付け(SwampTreeFall)が原木として辿る、隣の自然の葉は枯れ始めない(距離 1)。
     * 掘っても中ボタンでも過繁茂しているブロック(見た目も同じ)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 60)
    public static void trunkBlocksStayPartOfTheTree(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockState trunk = CoveredBlocks.OVERGROWN_TRUNK_BLOCK.get().defaultBlockState();
        if (!trunk.is(BlockTags.LOGS) || !trunk.is(ModTags.CORRUPTED) || !trunk.is(BlockTags.MINEABLE_WITH_PICKAXE)) {
            helper.fail("原木から化けた過繁茂しているブロックのタグが足りない(#logs・#corrupted・つるはし)");
            return;
        }
        try {
            java.lang.reflect.Method isTreeLog = SwampTreeFall.class.getDeclaredMethod("isTreeLog", BlockState.class);
            isTreeLog.setAccessible(true);
            if (!(boolean) isTreeLog.invoke(null, trunk)) {
                helper.fail("木の片付け(SwampTreeFall)が原木から化けた過繁茂しているブロックを幹として辿らない");
                return;
            }
        } catch (ReflectiveOperationException e) {
            helper.fail("SwampTreeFall.isTreeLog を呼べない: " + e);
            return;
        }
        BlockPos at = helper.absolutePos(new BlockPos(3, 2, 3));
        List<String> drops = describe(roll(level.getServer(), level, at, trunk, new ItemStack(Items.NETHERITE_PICKAXE)), Map.of());
        if (!drops.equals(List.of(id(ModBlocks.OVERGROWN_BLOCK.get().asItem()) + "x1"))
                || !trunk.getBlock().getCloneItemStack(level, at, trunk).is(ModBlocks.OVERGROWN_BLOCK.get().asItem())) {
            helper.fail("原木から化けた過繁茂しているブロックが、掘っても中ボタンでも過繁茂しているブロックにならない: " + drops);
            return;
        }
        // 自然の葉(手で置いていない)の隣に置くと、葉はそれを原木と数えて距離 1 になる
        BlockPos leaf = at.above();
        level.setBlock(leaf, Blocks.OAK_LEAVES.defaultBlockState().setValue(BlockStateProperties.PERSISTENT, false)
                .setValue(BlockStateProperties.DISTANCE, 7), Block.UPDATE_ALL);
        level.setBlock(at, trunk, Block.UPDATE_ALL);
        helper.runAfterDelay(10, () -> {
            BlockState leaves = level.getBlockState(leaf);
            if (!leaves.is(Blocks.OAK_LEAVES) || leaves.getValue(BlockStateProperties.DISTANCE) != 1) {
                helper.fail("自然の葉が、原木から化けた過繁茂しているブロックを原木と数えない: " + leaves);
                return;
            }
            helper.succeed();
        });
    }

    /**
     * 溶岩を固めると<b>覆われた玄武岩</b>(3 割は過繁茂しているブロック)になり、素の玄武岩も汚染される
     * (2026-10-05 夜、ユーザー指定「溶岩を玄武岩に変換した後、さらに玄武岩も汚染するように」)。どちらも座標で決まる。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void lavaAndBasaltBecomeCoveredBasalt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos at = helper.absolutePos(new BlockPos(3, 2, 3));
        BlockState basalt = Blocks.BASALT.defaultBlockState();
        // 見たいのは玄武岩の行き先だけなので、除去装置の台帳を外して確かめる(同じ呼び出しの中で戻すので、ほかのテストからは見えない)。
        // 外さないと、隣のテスト場で動いている除去装置(効果半径 48 = 4 列・3 行先まで)の圏内で汚染の関門が確率で止まる
        // —— 2026-10-18 に SporeFilterGameTests の装置が 30 ブロック先(強さ 0.44)から止めていた
        long[][] devices = com.yayuy.overgrownvegetation.spore.SporeDeviceData.isolateForTests(level);
        try {
            if (!CorruptionRegistry.canCorrupt(level, at, basalt)) {
                // 周り(効果半径ぶんのチャンク)の読み込まれている、稼働中の除去装置の at からの相対位置
                List<String> near = new java.util.ArrayList<>();
                int r = (com.yayuy.overgrownvegetation.block.SporeDeviceKind.PURIFIER.spec().radiusBlocks() >> 4) + 1;
                for (int cx = (at.getX() >> 4) - r; cx <= (at.getX() >> 4) + r; cx++) {
                    for (int cz = (at.getZ() >> 4) - r; cz <= (at.getZ() >> 4) + r; cz++) {
                        net.minecraft.world.level.chunk.LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                        if (chunk == null) {
                            continue;
                        }
                        for (net.minecraft.world.level.block.entity.BlockEntity be : chunk.getBlockEntities().values()) {
                            if (be instanceof com.yayuy.overgrownvegetation.block.SporeDeviceBlockEntity d && d.isActive()
                                    && d.kind() == com.yayuy.overgrownvegetation.block.SporeDeviceKind.PURIFIER) {
                                near.add(be.getBlockPos().subtract(at).toShortString() + (level.isPositionEntityTicking(be.getBlockPos()) ? "" : "(止まったチャンク)"));
                            }
                        }
                    }
                }
                helper.fail("素の玄武岩が汚染できない: オフ " + com.yayuy.overgrownvegetation.corruption.CorruptionSwitch.isOff()
                        + " / タグ " + (basalt.is(com.yayuy.overgrownvegetation.ModTags.CORRUPTED) || basalt.is(com.yayuy.overgrownvegetation.ModTags.CORRUPTION_IMMUNE))
                        + " / 焼けた荒地 " + com.yayuy.overgrownvegetation.purify.BurntLand.isBurnt(level, at)
                        + " / 杭の囲い " + com.yayuy.overgrownvegetation.defense.StakeWards.protects(level, at)
                        + " / 除去装置の強さ " + com.yayuy.overgrownvegetation.spore.SporeDeviceData.purifierStrengthAt(level, at)
                        + " / 近くの稼働中の除去装置(相対) " + near);
                return;
            }
            Block coveredBasalt = covered("basalt");
            for (int x = 0; x < 7; x++) {
                BlockPos cell = helper.absolutePos(new BlockPos(x, 2, 3));
                BlockState fromBasalt = CorruptionRegistry.corrupt(level, cell, basalt, RandomSource.create(x));
                BlockState fromLava = CorruptionRegistry.solidifiedLava(cell);
                if (!(fromBasalt.is(coveredBasalt) || fromBasalt.is(ModBlocks.OVERGROWN_BLOCK.get()))) {
                    helper.fail("玄武岩が覆われた玄武岩(か過繁茂しているブロック)にならない: " + fromBasalt);
                    return;
                }
                if (fromLava != fromBasalt) {
                    helper.fail("固めた溶岩と汚染した玄武岩の行き先が同じマスで違う: " + fromLava + " / " + fromBasalt);
                    return;
                }
            }
            helper.succeed();
        } finally {
            com.yayuy.overgrownvegetation.spore.SporeDeviceData.restoreForTests(level, devices);
        }
    }

    /**
     * 踏まれていた感圧板を汚染しても、降りれば戻る。「戻るか確かめる」予定は元のブロックの種類に付いているので、覆われた版に入れ直す
     * —— 入れ直さないと押されたまま固まり、つないだ扉が開きっぱなしになる(感圧板は信号が 0 のときしか乗っているものを確かめない)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 80)
    public static void pressedPlateReleasesAfterCorruption(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos plate = helper.absolutePos(new BlockPos(3, 2, 3));
        BlockState pressed = Blocks.OAK_PRESSURE_PLATE.defaultBlockState().setValue(BlockStateProperties.POWERED, true);
        level.setBlock(plate, pressed, Block.UPDATE_ALL);
        level.scheduleTick(plate, Blocks.OAK_PRESSURE_PLATE, 20); // 踏まれた板にバニラが入れる予定
        BlockState corrupted = CorruptionRegistry.corrupt(level, plate, pressed, RandomSource.create(3));
        CorruptionRegistry.placeCorrupted(level, plate, pressed, corrupted, Block.UPDATE_ALL);
        Block coveredPlate = covered("oak_pressure_plate");
        BlockState now = level.getBlockState(plate);
        if (!now.is(coveredPlate) || !now.getValue(BlockStateProperties.POWERED)) {
            helper.fail("踏まれた感圧板が、押された覆われた感圧板にならない: " + now);
            return;
        }
        helper.runAfterDelay(30, () -> {
            BlockState later = level.getBlockState(plate);
            if (!later.is(coveredPlate) || later.getValue(BlockStateProperties.POWERED)) {
                helper.fail("誰も乗っていないのに覆われた感圧板が押されたまま: " + later);
                return;
            }
            helper.succeed();
        });
    }

    /** 塀・フェンスがバニラと同じようにつながる(タグの写し漏れの見張り)。覆われた塀とバニラの塀もつながる。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void coveredWallsAndFencesConnect(GameTestHelper helper) {
        BlockPos wall = new BlockPos(1, 2, 1);
        BlockPos fence = new BlockPos(1, 2, 4);
        helper.setBlock(wall, covered("stone_brick_wall"));
        helper.setBlock(wall.east(), Blocks.STONE_BRICK_WALL);
        helper.setBlock(fence, covered("oak_fence"));
        helper.setBlock(fence.east(), covered("spruce_fence"));
        helper.setBlock(fence.west(), covered("nether_brick_fence"));
        helper.runAfterDelay(2, () -> {
            if (helper.getBlockState(wall).getValue(WallBlock.EAST_WALL) == WallSide.NONE) {
                helper.fail("覆われた塀がバニラの塀とつながらない");
                return;
            }
            BlockState f = helper.getBlockState(fence);
            if (!f.getValue(FenceBlock.EAST)) {
                helper.fail("覆われた木のフェンスどうしがつながらない");
                return;
            }
            if (f.getValue(FenceBlock.WEST)) {
                helper.fail("覆われた木のフェンスがネザーレンガのフェンスとつながった(バニラではつながらない)");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * クリエイティブタブの振り分け(ユーザー指定): 覆われたブロックは覆われたブロックのタブ、寄生植物装備は植物、
     * 機関砲台・基地の建材は人類。3 つのタブで同じアイテムが二重に出ない。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void creativeTabsSplitAsSpecified(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        CreativeModeTab.ItemDisplayParameters params =
                new CreativeModeTab.ItemDisplayParameters(level.enabledFeatures(), true, level.registryAccess());
        Map<String, Set<Item>> tabs = new HashMap<>();
        tabs.put("covered", items(ModCreativeTabs.COVERED.get(), params));
        tabs.put("plants", items(ModCreativeTabs.PLANTS.get(), params));
        tabs.put("humanity", items(ModCreativeTabs.HUMANITY.get(), params));
        Set<Item> seen = new HashSet<>();
        for (Map.Entry<String, Set<Item>> tab : tabs.entrySet()) {
            for (Item item : tab.getValue()) {
                if (!seen.add(item)) {
                    helper.fail(id(item) + " が 2 つのタブに出ている");
                    return;
                }
            }
        }
        for (CoveredBlocks.Entry entry : CoveredBlocks.entries()) {
            if (!tabs.get("covered").contains(entry.item().get())) {
                helper.fail(entry.block().getId() + " が覆われたブロックのタブに無い");
                return;
            }
        }
        for (Item item : List.of(ModBlocks.OVERGROWN_SLAB.get().asItem(), ModBlocks.OVERGROWN_BLOCK_SLAB.get().asItem(),
                ModItems.OVERGROWN_DOOR_ITEM.get(), covered("lever").asItem())) {
            if (!tabs.get("covered").contains(item)) {
                helper.fail(id(item) + " が覆われたブロックのタブに無い");
                return;
            }
        }
        Map<Item, String> expected = Map.of(
                ModItems.PLANT_ARMOR.get(), "plants",
                ModBlocks.OVERGROWN_STONE.get().asItem(), "covered",
                ModBlocks.OVERGROWN_TALL_FERN.get().asItem(), "plants",
                ModItems.FALCIMNES_SPAWN_EGG.get(), "plants",
                ModItems.GUN_TURRET_ITEM.get(), "humanity",
                ModItems.POWERED_ARMOR_HELMET.get(), "humanity",
                ModItems.CHLOROPHYLLITE_PICKAXE.get(), "humanity",
                com.yayuy.overgrownvegetation.base.BaseBlocks.ASPHALT.get().asItem(), "humanity");
        for (Map.Entry<Item, String> e : expected.entrySet()) {
            if (!tabs.get(e.getValue()).contains(e.getKey())) {
                helper.fail(id(e.getKey()) + " が " + e.getValue() + " のタブに無い");
                return;
            }
        }
        helper.succeed();
    }

    /**
     * 覆われた扉・トラップドア・フェンスゲート・ボタン・レバーは、覆われ具合 3 で時々動かない(過繫茂したドアと同じ確率)。
     * 覆われ具合 0(古いセーブ・コマンドで置いたもの)なら必ず動く(2026-10-05 ユーザー要望)。
     * <b>置いたときから覆われ具合 3</b>(2026-10-05 夜、ユーザー指定「最初から開かなくなったりする状態に」)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void coveredInteractablesSometimesStick(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        BlockPos abs = helper.absolutePos(new BlockPos(3, 2, 3));
        double chance = OvergrownContainers.stuckChance(OvergrownContainers.MAX_LEVEL);
        for (String id : List.of("oak_door", "copper_door", "oak_trapdoor", "oak_fence_gate", "oak_button", "stone_button", "lever")) {
            Block block = covered(id);
            // 床の上面を手で置いたときの状態(前の回の扉が残っていると扉は置けないので、先に空ける)
            int clear = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
            helper.getLevel().setBlock(abs.above(), Blocks.AIR.defaultBlockState(), clear);
            helper.getLevel().setBlock(abs, Blocks.AIR.defaultBlockState(), clear);
            BlockPos floor = abs.below();
            BlockState placed = block.getStateForPlacement(new net.minecraft.world.item.context.BlockPlaceContext(player,
                    InteractionHand.MAIN_HAND, new ItemStack(block.asItem()),
                    new BlockHitResult(Vec3.atCenterOf(floor).add(0.0D, 0.5D, 0.0D), Direction.UP, floor, false)));
            if (placed == null || placed.getValue(OvergrownContainers.OVERGROWTH) != OvergrownContainers.MAX_LEVEL) {
                helper.fail("covered_" + id + " を置いたときの覆われ具合が最大でない: " + placed);
                return;
            }
            int fresh = activations(helper, player, abs, block, 0, 20);
            if (fresh != 20) {
                helper.fail("covered_" + id + " が覆われ具合 0 で動かないことがある: " + fresh + "/20");
                return;
            }
            int overgrown = activations(helper, player, abs, block, OvergrownContainers.MAX_LEVEL, 60);
            boolean ok = chance <= 0.0D ? overgrown == 60 : chance >= 1.0D ? overgrown == 0 : overgrown > 0 && overgrown < 60;
            if (!ok) {
                helper.fail("covered_" + id + " が覆われ具合 3 で " + overgrown + "/60 回動いた(動かない確率 " + chance + ")");
                return;
            }
        }
        helper.succeed();
    }

    /**
     * ハサミで植物を剥がすと<b>元のブロック</b>に戻る(2026-10-05 夜、ユーザー指定「はさみで植物をはがしたら元の扉に戻る」)。
     * 扉は上下とも元の扉で、向き・開閉・蝶番はそのまま。押されていた覆われたボタンは元のボタンになり、そのあとちゃんと戻る
     * (「戻るか確かめる」予定の tick を移している。移さないと押されたまま固まる)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 80)
    public static void shearsTurnCoveredBackIntoOriginal(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        BlockPos door = helper.absolutePos(new BlockPos(1, 2, 1));
        BlockState lower = covered("oak_door").defaultBlockState().setValue(DoorBlock.FACING, Direction.WEST)
                .setValue(DoorBlock.OPEN, true).setValue(DoorBlock.HINGE, DoorHingeSide.RIGHT);
        place(helper, door, lower);
        lower.useItemOn(new ItemStack(Items.SHEARS), level, player, InteractionHand.MAIN_HAND, hit(door));
        for (BlockPos half : List.of(door, door.above())) {
            BlockState s = level.getBlockState(half);
            DoubleBlockHalf want = half.equals(door) ? DoubleBlockHalf.LOWER : DoubleBlockHalf.UPPER;
            if (!s.is(Blocks.OAK_DOOR) || s.getValue(DoorBlock.HALF) != want || s.getValue(DoorBlock.FACING) != Direction.WEST
                    || !s.getValue(DoorBlock.OPEN) || s.getValue(DoorBlock.HINGE) != DoorHingeSide.RIGHT) {
                helper.fail("ハサミで剥がした覆われた扉の " + want + " が元の扉にならない / 向き・開閉が変わった: " + s);
                return;
            }
        }
        BlockPos lever = helper.absolutePos(new BlockPos(3, 2, 1));
        BlockState pulled = covered("lever").defaultBlockState().setValue(BlockStateProperties.ATTACH_FACE, AttachFace.FLOOR)
                .setValue(BlockStateProperties.POWERED, true);
        level.setBlock(lever, pulled, Block.UPDATE_ALL);
        pulled.useItemOn(new ItemStack(Items.SHEARS), level, player, InteractionHand.MAIN_HAND, hit(lever));
        BlockState leverNow = level.getBlockState(lever);
        if (!leverNow.is(Blocks.LEVER) || !leverNow.getValue(BlockStateProperties.POWERED)) {
            helper.fail("ハサミで剥がした倒れたレバーが、倒れたままの元のレバーにならない: " + leverNow);
            return;
        }
        BlockPos button = helper.absolutePos(new BlockPos(5, 2, 1));
        BlockState pressed = covered("stone_button").defaultBlockState().setValue(BlockStateProperties.ATTACH_FACE, AttachFace.FLOOR)
                .setValue(BlockStateProperties.POWERED, true);
        level.setBlock(button, pressed, Block.UPDATE_ALL);
        level.scheduleTick(button, pressed.getBlock(), 20); // 押されたボタンが入れる「戻る」予定
        pressed.useItemOn(new ItemStack(Items.SHEARS), level, player, InteractionHand.MAIN_HAND, hit(button));
        BlockState buttonNow = level.getBlockState(button);
        if (!buttonNow.is(Blocks.STONE_BUTTON) || !buttonNow.getValue(BlockStateProperties.POWERED)) {
            helper.fail("ハサミで剥がした押されたボタンが、押されたままの元のボタンにならない: " + buttonNow);
            return;
        }
        helper.runAfterDelay(30, () -> {
            if (level.getBlockState(button).getValue(BlockStateProperties.POWERED)) {
                helper.fail("ハサミで元に戻したボタンが押されたまま戻らない");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * 過繁茂している板材 → ハーフ 6・階段 4・扉 3、過繁茂しているブロック → ハーフ 6(2026-10-05 ユーザー要望)。
     * 夜の追加(ユーザー指定「レシピをつけて」): 板材 4 + 棒 2 → 過繁茂したフェンス 3、過繁茂しているブロック 6 → 過繁茂した塀 6・
     * 4 つの階段の形 → 過繁茂した石階段 4。石切台では過繁茂しているブロック 1 → 塀 1・石階段 1・ハーフ 2(バニラの石と同じ数)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void overgrownPlanksAndBlockRecipes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ItemStack p = new ItemStack(ModBlocks.OVERGROWN_PLANKS.get());
        ItemStack b = new ItemStack(ModBlocks.OVERGROWN_BLOCK.get());
        ItemStack s = new ItemStack(Items.STICK);
        ItemStack e = ItemStack.EMPTY;
        record Case(int w, int h, List<ItemStack> grid, Item result, int count) {
        }
        List<Case> cases = List.of(
                new Case(3, 1, List.of(p, p, p), ModBlocks.OVERGROWN_SLAB.get().asItem(), 6),
                new Case(3, 3, List.of(p, e, e, p, p, e, p, p, p), ModBlocks.OVERGROWN_STAIRS.get().asItem(), 4),
                new Case(2, 3, List.of(p, p, p, p, p, p), ModItems.OVERGROWN_DOOR_ITEM.get(), 3),
                new Case(3, 1, List.of(b, b, b), ModBlocks.OVERGROWN_BLOCK_SLAB.get().asItem(), 6),
                new Case(3, 2, List.of(p, s, p, p, s, p), ModBlocks.OVERGROWN_FENCE.get().asItem(), 3),
                new Case(3, 2, List.of(b, b, b, b, b, b), ModBlocks.OVERGROWN_WALL.get().asItem(), 6),
                new Case(3, 3, List.of(b, e, e, b, b, e, b, b, b), ModBlocks.OVERGROWN_STONE_STAIRS.get().asItem(), 4));
        for (Case c : cases) {
            CraftingInput grid = CraftingInput.of(c.w(), c.h(), c.grid());
            ItemStack out = level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, grid, level)
                    .map(holder -> holder.value().assemble(grid, level.registryAccess())).orElse(ItemStack.EMPTY);
            if (!out.is(c.result()) || out.getCount() != c.count()) {
                helper.fail(id(c.result()) + " ×" + c.count() + " が作れない: " + out);
                return;
            }
        }
        net.minecraft.world.item.crafting.SingleRecipeInput one = new net.minecraft.world.item.crafting.SingleRecipeInput(b);
        List<ItemStack> cut = level.getRecipeManager().getRecipesFor(RecipeType.STONECUTTING, one, level).stream()
                .map(holder -> holder.value().assemble(one, level.registryAccess())).toList();
        for (Map.Entry<Item, Integer> want : Map.of(ModBlocks.OVERGROWN_WALL.get().asItem(), 1,
                ModBlocks.OVERGROWN_STONE_STAIRS.get().asItem(), 1, ModBlocks.OVERGROWN_BLOCK_SLAB.get().asItem(), 2).entrySet()) {
            if (cut.stream().noneMatch(stack -> stack.is(want.getKey()) && stack.getCount() == want.getValue())) {
                helper.fail("石切台で過繁茂しているブロックから " + id(want.getKey()) + " ×" + want.getValue() + " が作れない: " + cut);
                return;
            }
        }
        helper.succeed();
    }

    /** 汚染が松明に届くと火が消える(光らない別のブロックへ。壁の松明は向きを引き継ぐ)。レッドストーントーチは対象外。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void torchesGoOutWhenCorrupted(GameTestHelper helper) {
        RandomSource random = RandomSource.create(20261005L);
        BlockState torch = CorruptionRegistry.corrupt(Blocks.TORCH.defaultBlockState(), random);
        if (!torch.is(CoveredBlocks.EXTINGUISHED_TORCH.get()) || torch.getLightEmission() != 0) {
            helper.fail("松明が火の消えた松明にならない: " + torch);
            return;
        }
        BlockState wall = CorruptionRegistry.corrupt(Blocks.SOUL_WALL_TORCH.defaultBlockState()
                .setValue(WallTorchBlock.FACING, Direction.WEST), random);
        if (!wall.is(CoveredBlocks.EXTINGUISHED_WALL_TORCH.get()) || wall.getValue(WallTorchBlock.FACING) != Direction.WEST
                || wall.getLightEmission() != 0) {
            helper.fail("魂の壁の松明が向きを保った火の消えた壁の松明にならない: " + wall);
            return;
        }
        BlockState redstone = CorruptionRegistry.corrupt(Blocks.REDSTONE_TORCH.defaultBlockState(), random);
        if (redstone.is(CoveredBlocks.EXTINGUISHED_TORCH.get())) {
            helper.fail("レッドストーントーチまで火の消えた松明になった");
            return;
        }
        if (CorruptionRegistry.canCorrupt(helper.getLevel(), helper.absolutePos(new BlockPos(3, 2, 3)), torch)) {
            helper.fail("火の消えた松明がもう一度汚染されることになっている(#corrupted に無い)");
            return;
        }
        helper.succeed();
    }

    /**
     * 覆われた光源は 4 割の明るさ(−60%、2026-10-05 ユーザー要望): 覆われたブロック(海のランタン・点いた銅の電球)、
     * 汚染が中身ごと止めたブロック(火の付いたかまど 13 → 5)、汚染が記録で覆ったブロック(明かりも計算し直される)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 100)
    public static void coveredLightSourcesDim(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        if (covered("sea_lantern").defaultBlockState().getLightEmission() != CoveredLight.dim(15)
                || covered("copper_bulb").defaultBlockState().setValue(CopperBulbBlock.LIT, true).getLightEmission() != CoveredLight.dim(15)
                || covered("copper_bulb").defaultBlockState().setValue(CopperBulbBlock.LIT, false).getLightEmission() != 0) {
            helper.fail("覆われた海のランタン・銅の電球の明るさが 4 割でない");
            return;
        }
        BlockPos furnace = helper.absolutePos(new BlockPos(1, 2, 1));
        BlockState lit = Blocks.FURNACE.defaultBlockState().setValue(BlockStateProperties.LIT, true);
        level.setBlock(furnace, lit, Block.UPDATE_ALL);
        OvergrownHuskBlock.encase(level, furnace, lit, Block.UPDATE_ALL);
        BlockState husk = level.getBlockState(furnace);
        if (!husk.is(ModBlocks.OVERGROWN_HUSK.get()) || husk.getLightEmission() != CoveredLight.dim(lit.getLightEmission())) {
            helper.fail("火の付いたかまどを止めたブロックの明るさが 4 割でない: " + husk + " " + husk.getLightEmission());
            return;
        }
        // 記録で覆うブロック: 対応表に無い光源を 1 つ選ぶ
        BlockState source = null;
        for (Block candidate : List.of(Blocks.LANTERN, Blocks.SEA_LANTERN, Blocks.GLOWSTONE, Blocks.END_ROD, Blocks.SHROOMLIGHT)) {
            if (OvergrownLayerData.canCover(candidate.defaultBlockState())) {
                source = candidate.defaultBlockState();
                break;
            }
        }
        if (source == null) {
            helper.fail("記録で覆える光源が見つからない(全部対応表に載っている?)");
            return;
        }
        BlockPos at = helper.absolutePos(new BlockPos(4, 2, 4));
        level.setBlock(at, source, Block.UPDATE_ALL);
        int base = source.getLightEmission();
        OvergrownLayerData.cover(level, at);
        BlockState placed = level.getBlockState(at);
        if (placed.getLightEmission(level, at) != CoveredLight.dim(base)) {
            helper.fail(placed + " を覆っても明るさが " + placed.getLightEmission(level, at) + "(4 割は " + CoveredLight.dim(base) + ")");
            return;
        }
        BlockState finalSource = source;
        helper.runAfterDelay(30, () -> {
            int light = level.getBrightness(LightLayer.BLOCK, at);
            if (light != CoveredLight.dim(base)) {
                helper.fail("覆った " + finalSource + " のまわりの明るさが計算し直されていない: " + light);
                return;
            }
            OvergrownLayerData.uncover(level, at);
            helper.runAfterDelay(30, () -> {
                int restored = level.getBrightness(LightLayer.BLOCK, at);
                if (restored != base) {
                    helper.fail("剥がしたのに明るさが戻らない: " + restored + "(元は " + base + ")");
                    return;
                }
                helper.succeed();
            });
        });
    }

    // ---- 小物 ----

    /** 手で動かすブロックを、閉じた(押していない)状態で {@code times} 回動かそうとして、動いた回数。 */
    private static int activations(GameTestHelper helper, Player player, BlockPos abs, Block block, int overgrowth, int times) {
        int moved = 0;
        for (int i = 0; i < times; i++) {
            BlockState state = closed(block.defaultBlockState()).setValue(OvergrownContainers.OVERGROWTH, overgrowth);
            place(helper, abs, state);
            state.useWithoutItem(helper.getLevel(), player, hit(abs));
            BlockState after = helper.getLevel().getBlockState(abs);
            boolean on = after.hasProperty(BlockStateProperties.OPEN) ? after.getValue(BlockStateProperties.OPEN)
                    : after.getValue(BlockStateProperties.POWERED);
            if (on) {
                moved++;
            }
        }
        return moved;
    }

    /** 閉じた・押していない・床に付いた状態。 */
    private static BlockState closed(BlockState state) {
        if (state.hasProperty(BlockStateProperties.OPEN)) {
            state = state.setValue(BlockStateProperties.OPEN, false);
        }
        if (state.hasProperty(BlockStateProperties.POWERED)) {
            state = state.setValue(BlockStateProperties.POWERED, false);
        }
        if (state.hasProperty(BlockStateProperties.ATTACH_FACE)) {
            state = state.setValue(BlockStateProperties.ATTACH_FACE, AttachFace.FLOOR);
        }
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            state = state.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER);
        }
        return state;
    }

    /** 形の更新を飛ばして置く(扉の下半分だけ置くと上が無くて消えるので、上半分もそろえて置く)。 */
    private static void place(GameTestHelper helper, BlockPos abs, BlockState state) {
        ServerLevel level = helper.getLevel();
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        level.setBlock(abs, state, flags);
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            level.setBlock(abs.above(), state.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER), flags);
        } else {
            level.setBlock(abs.above(), Blocks.AIR.defaultBlockState(), flags);
        }
    }

    private static BlockHitResult hit(BlockPos abs) {
        return new BlockHitResult(Vec3.atCenterOf(abs), Direction.UP, abs, false);
    }

    private static Set<Item> items(CreativeModeTab tab, CreativeModeTab.ItemDisplayParameters params) {
        tab.buildContents(params);
        Set<Item> out = new HashSet<>();
        for (ItemStack stack : tab.getDisplayItems()) {
            out.add(stack.getItem());
        }
        return out;
    }

    private static Block covered(String vanillaId) {
        Block block = BuiltInRegistries.BLOCK.get(OvergrownVegetation.id("covered_" + vanillaId));
        if (block == Blocks.AIR) {
            throw new IllegalStateException("covered_" + vanillaId + " が無い");
        }
        return block;
    }

    /** 掘るのに向いた道具(タグから)。素手で落ちるものは素手。 */
    private static ItemStack toolFor(BlockState state) {
        if (state.is(BlockTags.MINEABLE_WITH_PICKAXE)) {
            return new ItemStack(Items.NETHERITE_PICKAXE);
        }
        if (state.is(BlockTags.MINEABLE_WITH_AXE)) {
            return new ItemStack(Items.NETHERITE_AXE);
        }
        if (state.is(BlockTags.MINEABLE_WITH_SHOVEL)) {
            return new ItemStack(Items.NETHERITE_SHOVEL);
        }
        if (state.is(BlockTags.MINEABLE_WITH_HOE)) {
            return new ItemStack(Items.NETHERITE_HOE);
        }
        return ItemStack.EMPTY;
    }

    private static List<ItemStack> roll(MinecraftServer server, ServerLevel level, BlockPos pos, BlockState state, ItemStack tool) {
        LootParams params = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(pos))
                .withParameter(LootContextParams.TOOL, tool)
                .withParameter(LootContextParams.BLOCK_STATE, state)
                .create(LootContextParamSets.BLOCK);
        return server.reloadableRegistries().getLootTable(state.getBlock().getLootTable()).getRandomItems(params, 20261005L);
    }

    /** ドロップを「id x 数」の並びにする。{@code map} はバニラの建築タブのアイテム → 覆われたほう。 */
    private static List<String> describe(List<ItemStack> drops, Map<Item, Item> map) {
        List<String> out = new ArrayList<>();
        for (ItemStack stack : drops) {
            Item item = map.getOrDefault(stack.getItem(), stack.getItem());
            out.add(id(item) + "x" + stack.getCount());
        }
        return out;
    }

    private static ItemStack craft(ServerLevel level, ItemStack input) {
        CraftingInput grid = CraftingInput.of(1, 1, List.of(input));
        Optional<ItemStack> out = level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, grid, level)
                .map(holder -> holder.value().assemble(grid, level.registryAccess()));
        return out.orElse(ItemStack.EMPTY);
    }

    private static String id(Item item) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        return id.toString();
    }

    private CoveredBlockGameTests() {
    }
}
