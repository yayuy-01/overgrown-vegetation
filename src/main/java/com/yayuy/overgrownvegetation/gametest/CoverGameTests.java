package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.OvergrownHuskBlock;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.corruption.CorruptionRegistry;
import com.yayuy.overgrownvegetation.corruption.CoverInPlace;
import com.yayuy.overgrownvegetation.corruption.CoverLook;
import com.yayuy.overgrownvegetation.corruption.CoverMipmap;
import com.yayuy.overgrownvegetation.corruption.CoverPeel;
import com.yayuy.overgrownvegetation.corruption.CoveredInteractions;
import com.yayuy.overgrownvegetation.corruption.CoveredLight;
import com.yayuy.overgrownvegetation.corruption.OvergrownLayerData;
import com.yayuy.overgrownvegetation.corruption.OvergrownLayerField;
import com.yayuy.overgrownvegetation.corruption.SwampTreeFall;
import com.yayuy.overgrownvegetation.data.CoverTable;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 覆い(2026-10-30 の作り直し)のテスト。建築タブのブロックは汚染で<b>元のまま記録で覆う</b>(covered_ 366 個は parked の U10)。
 * 前の {@code CoveredBlockGameTests}(17 本、covered_ ブロックの id・ドロップ・タグ・レシピを見ていた)の、残る働きを記録向けに書き直した:
 * 行き先の割合(記録 : 同じ形の過繁茂ブロック : 過繁茂した木材)・扉の上下・木の片付け・扉などが時々動かない・右クリックを止める・
 * ハサミで剥がす・壊した/動いた位置の記録を消す・松明・光源 4 割。作り直し側の {@code CoverGameTests} から絵の選び方とミップ。
 * 描画そのものは Claude 用クライアントのスクショで見る。期待値は設計値({@code cover.json})から引く。
 *
 * <p>記録はテストのワールドに残り続ける(メモリ covered-cells-not-corrupted-tag の罠)ので、使うマスは最初に {@link #clear} で消す。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class CoverGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(CoverGameTests.class);
        }

        private Registration() {
        }
    }

    private CoverGameTests() {
    }

    /** covered_ の id・覆われたブロックのタブが残っていない。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void noCoveredBlocksOrTabRemain(GameTestHelper helper) {
        for (ResourceLocation id : BuiltInRegistries.BLOCK.keySet()) {
            if (id.getNamespace().equals(OvergrownVegetation.MODID) && id.getPath().startsWith("covered_")) {
                helper.fail("覆われた版のブロックが残っている: " + id);
                return;
            }
        }
        for (ResourceLocation id : BuiltInRegistries.ITEM.keySet()) {
            if (id.getNamespace().equals(OvergrownVegetation.MODID) && id.getPath().startsWith("covered_")) {
                helper.fail("覆われた版のアイテムが残っている: " + id);
                return;
            }
        }
        if (BuiltInRegistries.CREATIVE_MODE_TAB.containsKey(ResourceKey.create(Registries.CREATIVE_MODE_TAB, OvergrownVegetation.id("covered")))) {
            helper.fail("覆われたブロックのタブが残っている");
            return;
        }
        // 残す 3 つ(原木から化けた過繁茂しているブロック・火の消えた松明 2 つ)
        for (Block kept : List.of(ModBlocks.OVERGROWN_TRUNK_BLOCK.get(), ModBlocks.EXTINGUISHED_TORCH.get(), ModBlocks.EXTINGUISHED_WALL_TORCH.get())) {
            if (!kept.defaultBlockState().is(ModTags.CORRUPTED)) {
                helper.fail(kept + " が #corrupted に無い");
                return;
            }
        }
        helper.succeed();
    }

    /**
     * 汚染は建築タブのブロックを<b>元のまま</b>にする(呼び出し元は noChange で素通りし、位置付きなら記録で覆う)。ただし 3 割は同じ形の過繁茂
     * ブロック、木材は過繁茂した木材 5 : 過繁茂ブロック 1.5 : 覆う 3.5(2026-10-05 夜のユーザー指定。前の「覆われた版」の枠が記録になっただけ)。
     * 前から専用の過繁茂ブロックがある 6 個はそちら(丸石・花崗岩・閃緑岩・安山岩の専用ブロックは 2026-10-31 に削除して覆う側へ。前は 10 個)。割合は 1 本の乱数で 200 回引いて見る。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void corruptionLeavesBuildingBlocksInPlace(GameTestHelper helper) {
        Set<String> dedicated = Set.of("stone", "deepslate", "cobbled_deepslate", "bricks", "netherrack", "end_stone");
        Set<Block> table = CoverInPlace.blocks();
        if (table.size() != 367 + 36) { // 蝋引きの銅 36 個も
            helper.fail("記録で覆うブロックの一覧が 403 件でない: " + table.size());
            return;
        }
        double chance = DesignData.cover().chance();
        int woodKinds = 0;
        for (Block vanilla : table) {
            String id = BuiltInRegistries.BLOCK.getKey(vanilla).getPath();
            BlockState in = vanilla.defaultBlockState();
            Block wood = expectedWood(in);
            Block counterpart = expectedCounterpart(in);
            boolean own = false;
            int kept = 0;
            int woodCount = 0;
            int counterpartCount = 0;
            // 1 本の乱数を回し続ける(種を変えて最初の 1 回だけ引くと、どれも 0.73 前後になる。メモリ random-small-seeds-first-roll)
            RandomSource rolls = RandomSource.create(20261030L ^ id.hashCode());
            for (int n = 0; n < 200; n++) {
                BlockState result = CorruptionRegistry.corrupt(in, rolls);
                if (dedicated.contains(id)) {
                    own |= BuiltInRegistries.BLOCK.getKey(result.getBlock()).getPath().equals("overgrown_" + id);
                } else if (result == in) {
                    kept++;
                } else if (wood != null && result.is(wood)) {
                    woodCount++;
                } else if (counterpart != null && result.is(counterpart)) {
                    counterpartCount++;
                } else {
                    helper.fail(id + " が元のまま(覆う)にも過繁茂ブロックにもならない: " + result);
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
            // 汚染されないもの(#corruption_immune の強化された深層岩・壊せないもの)は汚染がそもそも届かないので覆う資格も無くてよい
            boolean immune = in.is(ModTags.CORRUPTION_IMMUNE) || vanilla.defaultDestroyTime() < 0.0F;
            if (!CorruptionRegistry.coversInPlace(in) || !OvergrownLayerData.canCover(in) && !immune) {
                helper.fail(id + " が「記録で覆う」に入っていない / 覆う資格が無い");
                return;
            }
            boolean ok;
            if (chance != 0.7D) {
                ok = true; // 区切りの数字はこの値で書いてある
            } else if (wood != null && wood != counterpart) {
                woodKinds++;
                ok = within(woodCount, 70, 130) && within(kept, 40, 100) && within(counterpartCount, 10, 55);
            } else if (wood != null) {
                woodKinds++;
                ok = within(kept, 40, 100); // 木のフェンス: 過繁茂した木材と過繁茂ブロックが同じ過繁茂したフェンス
            } else if (counterpart != null) {
                ok = within(kept, 110, 170) && within(counterpartCount, 30, 90);
            } else {
                ok = kept == 200;
            }
            if (!ok) {
                helper.fail(id + " の行き先の割合がおかしい(200 回): 覆う " + kept + "、過繁茂した木材 " + woodCount
                        + "、同じ形の過繁茂ブロック " + counterpartCount);
                return;
            }
        }
        if (chance == 0.7D && woodKinds < 40) { // 板材・木の階段・ハーフ・フェンス(原木・木は 2026-11-01 から入らない)
            helper.fail("木材として扱われたブロックが少なすぎる: " + woodKinds);
            return;
        }
        // 罠の見張り: 対応表にある板材の「対応表に無い」の答えは変えない(変えると前の記録が無効になる。メモリ covered-blocks)
        if (CorruptionRegistry.hasNoCorruptedForm(Blocks.OAK_PLANKS.defaultBlockState())) {
            helper.fail("板材が「対応表に無い」になった(hasNoCorruptedForm の答えが変わった)");
            return;
        }
        helper.succeed();
    }

    /**
     * 行き先は座標で決まる({@code OvergrownLayerField.roll})。位置付きの汚染で「覆う」に当たったマスは記録で覆われ、ブロックは元のまま
     * (ハーフの上下・原木の向きもそのまま)。#corrupted には入らないが {@link OvergrownLayerData#isCovered} が答える
     * (メモリ covered-cells-not-corrupted-tag)。区間(chance 0.7 のとき): u &lt; 0.5 木材 → 過繁茂した木材 / 0.5〜0.7 どちらも覆う /
     * 0.7〜0.85 木材 → 覆う・石 → 過繁茂ブロック / 0.85〜 どちらも過繁茂ブロック。扉はどの区間でも覆う。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void coverIsDecidedByPositionAndRecorded(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        clear(helper);
        if (DesignData.cover().chance() != 0.7D) {
            helper.succeed();
            return;
        }
        double[] bounds = {0.0D, 0.5D, 0.7D, 0.85D, 1.0D};
        BlockPos[] cells = new BlockPos[4];
        for (int y = 2; y <= 4; y += 2) {
            for (int x = 0; x < 7; x += 2) {
                for (int z = 0; z < 7; z += 2) {
                    BlockPos at = helper.absolutePos(new BlockPos(x, y, z));
                    double u = OvergrownLayerField.roll(at);
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
        Block keep = Blocks.AIR; // 「元のまま + 記録」の印
        Block[][] want = {
                // 原木は 2026-11-01 から過繁茂した木材(過繁茂した原木)に化けない = 石のハーフと同じ 7:3
                {ModBlocks.OVERGROWN_SLAB.get(), keep, keep},
                {keep, keep, keep},
                {keep, ModBlocks.OVERGROWN_BLOCK_SLAB.get(), ModBlocks.OVERGROWN_TRUNK_BLOCK.get()},
                {ModBlocks.OVERGROWN_BLOCK_SLAB.get(), ModBlocks.OVERGROWN_BLOCK_SLAB.get(), ModBlocks.OVERGROWN_TRUNK_BLOCK.get()}};
        BlockState[] inputs = {oakSlab, stoneSlab, oakLog};
        for (int k = 0; k < 4; k++) {
            for (int j = 0; j < inputs.length; j++) {
                level.setBlock(cells[k], inputs[j], Block.UPDATE_CLIENTS);
                OvergrownLayerData.uncover(level, cells[k]);
                BlockState result = CorruptionRegistry.corrupt(level, cells[k], inputs[j], RandomSource.create(7919L + k));
                boolean covered = OvergrownLayerData.isCovered(level, cells[k]);
                if (want[k][j] == keep) {
                    if (result != inputs[j] || !covered) {
                        helper.fail("区間 " + bounds[k] + " のマスで " + inputs[j] + " が元のまま記録で覆われない: " + result + " 記録 " + covered);
                        return;
                    }
                    if (result.is(ModTags.CORRUPTED)) {
                        helper.fail(inputs[j] + " が #corrupted に入っている(記録で覆ったマスは isCovered で見る)");
                        return;
                    }
                } else if (!result.is(want[k][j]) || covered) {
                    helper.fail("区間 " + bounds[k] + " のマスで " + inputs[j] + " が " + result + " になった(期待 " + want[k][j]
                            + "、記録 " + covered + ")");
                    return;
                }
                if (result.hasProperty(BlockStateProperties.SLAB_TYPE) && result.getValue(BlockStateProperties.SLAB_TYPE) != SlabType.TOP
                        || result.hasProperty(BlockStateProperties.AXIS) && result.getValue(BlockStateProperties.AXIS) != Direction.Axis.X) {
                    helper.fail("ハーフの上下・原木の向きが変わった: " + result);
                    return;
                }
                OvergrownLayerData.uncover(level, cells[k]);
            }
        }
        // 置き直された別のブロック(覆う資格が無い = 専用の過繁茂ブロックがある石)では記録が切れる
        BlockPos stale = cells[1];
        level.setBlock(stale, Blocks.OAK_PLANKS.defaultBlockState(), Block.UPDATE_CLIENTS);
        OvergrownLayerData.cover(level, stale);
        level.setBlock(stale, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        if (OvergrownLayerData.isCovered(level, stale)) {
            helper.fail("覆う資格の無い石に置き換えたのに覆われたまま");
            return;
        }
        helper.succeed();
    }

    /** 扉は上下どちらに当たっても上下とも記録で覆う(扉は元のまま。向き・開閉・蝶番もそのまま)。鉄の扉・蝋引きの銅の扉も。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void corruptedDoorsCoverBothHalves(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        clear(helper);
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
            if (corrupted != before) {
                helper.fail(c.door().getBlock() + " が置き換わった(元のまま覆うはず): " + corrupted);
                return;
            }
            for (BlockPos half : List.of(lower, lower.above())) {
                BlockState s = level.getBlockState(half);
                if (!s.is(c.door().getBlock()) || !OvergrownLayerData.isCovered(level, half)) {
                    helper.fail(c.door().getBlock() + " の " + (half.equals(lower) ? "下" : "上") + "半分が記録で覆われない: " + s);
                    return;
                }
            }
        }
        helper.succeed();
    }

    /** 元の森の原木を汚染すると、記録で覆う道筋でも湿地・密林の「元の木を残さない」処理({@code SwampTreeFall})に積まれる。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void corruptedLogsStillQueueTreeFall(GameTestHelper helper) {
        if (!OvergrownVegetationConfig.COMMON.swampTreeFall.get() && !OvergrownVegetationConfig.COMMON.jungleTreeFall.get()) {
            helper.succeed();
            return;
        }
        ServerLevel level = helper.getLevel();
        clear(helper);
        BlockPos log = helper.absolutePos(new BlockPos(3, 2, 3));
        BlockState oak = Blocks.OAK_LOG.defaultBlockState();
        level.setBlock(log, oak, Block.UPDATE_CLIENTS);
        BlockState corrupted = CorruptionRegistry.corrupt(level, log, oak, RandomSource.create(5));
        if (corrupted != oak && !corrupted.is(ModBlocks.OVERGROWN_TRUNK_BLOCK.get())) {
            helper.fail("オークの原木が覆う・過繁茂しているブロックのどちらにもならない: " + corrupted);
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
     * 原木から化けた過繁茂しているブロック(2026-10-05 夜、10-30 に block/ へ移した)は木の一部: {@code #logs}・汚染済み・
     * 木の片付けが原木として辿る・隣の自然の葉は枯れ始めない。中ボタンで過繁茂しているブロック。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 60)
    public static void trunkBlocksStayPartOfTheTree(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockState trunk = ModBlocks.OVERGROWN_TRUNK_BLOCK.get().defaultBlockState();
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
        if (!trunk.getBlock().getCloneItemStack(level, at, trunk).is(ModBlocks.OVERGROWN_BLOCK.get().asItem())) {
            helper.fail("原木から化けた過繁茂しているブロックの中ボタンが過繁茂しているブロックでない");
            return;
        }
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
     * 覆った扉・トラップドア・フェンスゲート・ボタン・レバーは、開ける・押す向きで {@code stuckChance} の割合で動かない(右クリックの
     * イベントを通して 60 回)。閉じる・戻す向きは常にできる。覆っていなければ必ず動く。覆った作業台は画面を開かない(ブロックの右クリックを
     * 止める)が、手に持った物は使える。Mob が覆われた扉を開けるときも引っかかる(鉄の扉・覆っていない扉は引っかからない)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 40)
    public static void coveredInteractablesSometimesStick(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        clear(helper);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        BlockPos abs = helper.absolutePos(new BlockPos(3, 2, 3));
        double chance = DesignData.cover().stuckChance();
        for (Block block : List.of(Blocks.OAK_DOOR, Blocks.COPPER_DOOR, Blocks.OAK_TRAPDOOR, Blocks.OAK_FENCE_GATE, Blocks.OAK_BUTTON,
                Blocks.STONE_BUTTON, Blocks.LEVER)) {
            BlockState closed = closed(block.defaultBlockState());
            if (CoveredInteractions.movable(closed) != CoveredInteractions.Movable.ACTIVATING) {
                helper.fail(block + " の閉じた状態が「動かす向き」にならない");
                return;
            }
            int fresh = activations(helper, player, abs, closed, false, 20);
            if (fresh != 0) {
                helper.fail(block + " が覆っていないのに止められた: " + fresh + "/20");
                return;
            }
            int stuck = activations(helper, player, abs, closed, true, 60);
            boolean ok = chance <= 0.0D ? stuck == 0 : chance >= 1.0D ? stuck == 60 : stuck > 0 && stuck < 60;
            if (!ok) {
                helper.fail(block + " を覆ったら 60 回中 " + stuck + " 回止まった(止まる割合 " + chance + ")");
                return;
            }
            if (closed.hasProperty(BlockStateProperties.OPEN)) {
                BlockState opened = closed.setValue(BlockStateProperties.OPEN, true);
                if (CoveredInteractions.movable(opened) != CoveredInteractions.Movable.RETURNING) {
                    helper.fail(block + " の開いた状態が「戻す向き」にならない");
                    return;
                }
            }
        }
        // 手で動かすものでない覆われたブロックはブロックの右クリックを起こさない(画面を開かない)
        place(helper, abs, Blocks.CRAFTING_TABLE.defaultBlockState());
        OvergrownLayerData.cover(level, abs);
        PlayerInteractEvent.RightClickBlock event = CommonHooks.onRightClickBlock(player, InteractionHand.MAIN_HAND, abs, hit(abs));
        if (event.getUseBlock() != TriState.FALSE || event.getUseItem() == TriState.FALSE || event.isCanceled()) {
            helper.fail("覆った作業台の右クリックが止まらない / 手に持った物まで止まった");
            return;
        }
        OvergrownLayerData.uncover(level, abs);
        // Mob の開け閉め(DoorBlock#setOpen の関門)
        BlockState oak = Blocks.OAK_DOOR.defaultBlockState();
        place(helper, abs, oak);
        if (CoveredInteractions.mobOpenBlocked(null, level, oak, abs, true, 0.0D)) {
            helper.fail("覆っていない扉で Mob が引っかかった");
            return;
        }
        OvergrownLayerData.cover(level, abs.above()); // 上半分だけ覆っていても
        if (chance > 0.0D && !CoveredInteractions.mobOpenBlocked(null, level, oak, abs, true, 0.0D)) {
            helper.fail("覆った扉で Mob が引っかからない");
            return;
        }
        if (CoveredInteractions.mobOpenBlocked(null, level, oak, abs, false, 0.0D)
                || chance < 1.0D && CoveredInteractions.mobOpenBlocked(null, level, oak, abs, true, 0.9999D)) {
            helper.fail("閉じる向き・割合の外で Mob が引っかかった");
            return;
        }
        OvergrownLayerData.uncover(level, abs.above());
        BlockState iron = Blocks.IRON_DOOR.defaultBlockState();
        place(helper, abs, iron);
        OvergrownLayerData.cover(level, abs);
        if (CoveredInteractions.mobOpenBlocked(null, level, iron, abs, true, 0.0D)) {
            helper.fail("鉄の扉で引っかかった(手で開かないので対象外)");
            return;
        }
        OvergrownLayerData.uncover(level, abs);
        helper.succeed();
    }

    /**
     * ハサミで剥がすと記録が消え、ブロックは元のまま(扉は上下とも)。ハサミの耐久が {@code peelDamage} 減る。覆っていないマスは何もしない。
     * 右クリックのイベントでも剥がれる(ハサミは扉を開けない)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void shearsPeelTheCover(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        clear(helper);
        BlockPos door = helper.absolutePos(new BlockPos(1, 2, 1));
        BlockState lower = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.WEST)
                .setValue(DoorBlock.OPEN, false).setValue(DoorBlock.HINGE, DoorHingeSide.RIGHT);
        place(helper, door, lower);
        CorruptionRegistry.coverInPlace(level, door, lower);
        if (!OvergrownLayerData.isCovered(level, door) || !OvergrownLayerData.isCovered(level, door.above())) {
            helper.fail("扉が上下とも覆われない");
            return;
        }
        ItemStack shears = new ItemStack(Items.SHEARS);
        if (!CoverPeel.peel(level, door.above(), shears, null, EquipmentSlot.MAINHAND)) {
            helper.fail("覆った扉の上半分をハサミで剥がせない");
            return;
        }
        if (OvergrownLayerData.isCovered(level, door) || OvergrownLayerData.isCovered(level, door.above())
                || !level.getBlockState(door).equals(lower)) {
            helper.fail("剥がしたあと記録が残る / 扉が変わった: " + level.getBlockState(door));
            return;
        }
        if (shears.getDamageValue() != DesignData.cover().peelDamage()) {
            helper.fail("ハサミの減りが peelDamage と違う: " + shears.getDamageValue());
            return;
        }
        if (CoverPeel.peel(level, door, shears, null, EquipmentSlot.MAINHAND)) {
            helper.fail("覆っていないマスを剥がせたことになった");
            return;
        }
        // 右クリックの道筋: ハサミを持って覆った板材を右クリック → 剥がれる
        BlockPos plank = helper.absolutePos(new BlockPos(4, 2, 4));
        place(helper, plank, Blocks.OAK_PLANKS.defaultBlockState());
        OvergrownLayerData.cover(level, plank);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.SHEARS));
        PlayerInteractEvent.RightClickBlock event = CommonHooks.onRightClickBlock(player, InteractionHand.MAIN_HAND, plank, hit(plank));
        if (!event.isCanceled() || OvergrownLayerData.isCovered(level, plank)) {
            helper.fail("ハサミの右クリックで覆いが剥がれない");
            return;
        }
        helper.succeed();
    }

    /** ピストンで動いた・爆発で壊れたブロックの記録が元の位置に残らない(残ると、後で来たブロックが覆われて見える)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 40)
    public static void pistonAndExplosionClearCoverRecords(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        clear(helper);
        helper.setBlock(2, 2, 2, Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.EAST));
        helper.setBlock(3, 2, 2, Blocks.STONE_BRICKS);
        BlockPos pushed = helper.absolutePos(new BlockPos(3, 2, 2));
        OvergrownLayerData.cover(level, pushed);
        helper.setBlock(5, 2, 5, Blocks.OAK_PLANKS); // 爆発 1.5 で壊れる硬さ(石レンガは壊れない)
        BlockPos blown = helper.absolutePos(new BlockPos(5, 2, 5));
        OvergrownLayerData.cover(level, blown);
        if (!OvergrownLayerData.isCovered(level, pushed) || !OvergrownLayerData.isCovered(level, blown)) {
            helper.fail("石レンガ・板材を記録で覆えない");
            return;
        }
        helper.setBlock(1, 2, 2, Blocks.REDSTONE_BLOCK);
        level.explode(null, blown.getX() + 0.5, blown.getY() + 0.5, blown.getZ() + 0.5, 1.5f, Level.ExplosionInteraction.BLOCK);
        helper.succeedWhen(() -> {
            helper.assertBlockPresent(Blocks.STONE_BRICKS, 4, 2, 2);
            helper.assertFalse(OvergrownLayerData.isCovered(level, pushed), "ピストンで押した元の位置に記録が残った");
            helper.assertFalse(OvergrownLayerData.isCovered(level, blown), "爆発で壊れた位置に記録が残った");
        });
    }

    /** 汚染が松明に届くと火が消える(光らない別のブロックへ。壁の松明は向きを引き継ぐ)。レッドストーントーチは対象外。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void torchesGoOutWhenCorrupted(GameTestHelper helper) {
        RandomSource random = RandomSource.create(20261005L);
        BlockState torch = CorruptionRegistry.corrupt(Blocks.TORCH.defaultBlockState(), random);
        if (!torch.is(ModBlocks.EXTINGUISHED_TORCH.get()) || torch.getLightEmission() != 0) {
            helper.fail("松明が火の消えた松明にならない: " + torch);
            return;
        }
        BlockState wall = CorruptionRegistry.corrupt(Blocks.SOUL_WALL_TORCH.defaultBlockState()
                .setValue(WallTorchBlock.FACING, Direction.WEST), random);
        if (!wall.is(ModBlocks.EXTINGUISHED_WALL_TORCH.get()) || wall.getValue(WallTorchBlock.FACING) != Direction.WEST
                || wall.getLightEmission() != 0) {
            helper.fail("魂の壁の松明が向きを保った火の消えた壁の松明にならない: " + wall);
            return;
        }
        if (CorruptionRegistry.corrupt(Blocks.REDSTONE_TORCH.defaultBlockState(), random).is(ModBlocks.EXTINGUISHED_TORCH.get())) {
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
     * 覆われた光源は {@code lightKeep} 倍の明るさ(2026-10-05 ユーザー要望 −60%): 記録で覆った海のランタン(建築タブ。明かりも計算し直され、
     * 剥がすと戻る)と、汚染が中身ごと止めたブロック(火の付いたかまど)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200)
    public static void coveredLightSourcesDim(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        clear(helper);
        BlockPos furnace = helper.absolutePos(new BlockPos(1, 2, 1));
        BlockState lit = Blocks.FURNACE.defaultBlockState().setValue(BlockStateProperties.LIT, true);
        level.setBlock(furnace, lit, Block.UPDATE_ALL);
        OvergrownHuskBlock.encase(level, furnace, lit, Block.UPDATE_ALL);
        BlockState husk = level.getBlockState(furnace);
        if (!husk.is(ModBlocks.OVERGROWN_HUSK.get()) || husk.getLightEmission() != CoveredLight.dim(lit.getLightEmission())) {
            helper.fail("火の付いたかまどを止めたブロックの明るさが lightKeep 倍でない: " + husk + " " + husk.getLightEmission());
            return;
        }
        BlockPos at = helper.absolutePos(new BlockPos(4, 2, 4));
        BlockState source = Blocks.SEA_LANTERN.defaultBlockState();
        level.setBlock(at, source, Block.UPDATE_ALL);
        int base = source.getLightEmission();
        CorruptionRegistry.coverInPlace(level, at, source);
        if (source.getLightEmission(level, at) != CoveredLight.dim(base)
                || CoveredLight.dim(15) != (int) Math.round(15 * DesignData.cover().lightKeep())) {
            helper.fail("海のランタンを覆っても明るさが " + source.getLightEmission(level, at) + "(期待 " + CoveredLight.dim(base) + ")");
            return;
        }
        // 光の計算し直しは光エンジンの手が空いてから届く。同じ組で重いテスト(木の生成など)が走ると 30 tick では
        // 間に合わないことがある(2026-11-02、144 本になってから落ちた)ので、決まった tick で読まず、期待の値になるまで待つ。
        helper.startSequence()
                .thenWaitUntil(() -> {
                    int light = level.getBrightness(LightLayer.BLOCK, at);
                    helper.assertTrue(light == CoveredLight.dim(base), "覆った海のランタンのまわりの明るさが計算し直されていない: " + light);
                })
                .thenExecute(() -> OvergrownLayerData.uncover(level, at))
                .thenWaitUntil(() -> {
                    int restored = level.getBrightness(LightLayer.BLOCK, at);
                    helper.assertTrue(restored == base, "剥がしたのに明るさが戻らない: " + restored + "(元は " + base + ")");
                })
                .thenSucceed();
    }

    /** 踏まれていた感圧板を汚染しても(元のまま覆うので予定の tick は消えない)、降りれば戻る。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 80)
    public static void pressedPlateReleasesAfterCorruption(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        clear(helper);
        BlockPos plate = helper.absolutePos(new BlockPos(3, 2, 3));
        BlockState pressed = Blocks.OAK_PRESSURE_PLATE.defaultBlockState().setValue(BlockStateProperties.POWERED, true);
        level.setBlock(plate, pressed, Block.UPDATE_ALL);
        level.scheduleTick(plate, Blocks.OAK_PRESSURE_PLATE, 20);
        BlockState corrupted = CorruptionRegistry.corrupt(level, plate, pressed, RandomSource.create(3));
        CorruptionRegistry.placeCorrupted(level, plate, pressed, corrupted, Block.UPDATE_ALL);
        if (corrupted != pressed || !OvergrownLayerData.isCovered(level, plate)) {
            helper.fail("踏まれた感圧板が元のまま記録で覆われない: " + corrupted);
            return;
        }
        helper.runAfterDelay(30, () -> {
            BlockState later = level.getBlockState(plate);
            if (!later.is(Blocks.OAK_PRESSURE_PLATE) || later.getValue(BlockStateProperties.POWERED)) {
                helper.fail("誰も乗っていないのに覆った感圧板が押されたまま: " + later);
                return;
            }
            helper.succeed();
        });
    }

    /**
     * 過繁茂している板材 → ハーフ 6・階段 4・扉 3、過繁茂しているブロック → ハーフ 6・塀 6・石階段 4、板材 + 棒 → フェンス 3
     * (2026-10-05 ユーザー要望。前の CoveredBlockGameTests から、covered_ と関係ないのでそのまま)。
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
                helper.fail(BuiltInRegistries.ITEM.getKey(c.result()) + " ×" + c.count() + " が作れない: " + out);
                return;
            }
        }
        helper.succeed();
    }

    /** 絵と回転はいつも同じ・どの絵も使われる・隣どうしで揃いすぎない(作り直し側の CoverGameTests から)。 */
    @GameTest(template = "gametest_empty7")
    public static void coverLookIsDeterministicAndUsesEveryTexture(GameTestHelper helper) {
        CoverTable c = DesignData.cover();
        int count = c.textures().size();
        int[] used = new int[count];
        boolean[] rots = new boolean[4];
        for (int x = -20; x < 20; x++) {
            for (int z = -20; z < 20; z++) {
                for (Direction d : Direction.values()) {
                    int p = CoverLook.pick(x, 64, z, d, count, c.rotate());
                    helper.assertTrue(p == CoverLook.pick(x, 64, z, d, count, c.rotate()), "同じ座標・面で選び方が変わる");
                    int i = CoverLook.index(p);
                    helper.assertTrue(i >= 0 && i < count, "絵の番号が一覧の外: " + i);
                    used[i]++;
                    rots[CoverLook.rotation(p)] = true;
                    helper.assertTrue(c.rotate() || CoverLook.rotation(p) == 0, "回さない設定で回った");
                }
            }
        }
        int total = Arrays.stream(used).sum();
        for (int i = 0; i < count; i++) {
            helper.assertTrue(used[i] * count * 2 >= total, c.textures().get(i) + " が少ない: " + used[i] + " / " + total);
        }
        if (c.rotate()) {
            for (int r = 0; r < 4; r++) {
                helper.assertTrue(rots[r], "回転 " + r + " が出ない");
            }
        }
        int sameNext = 0;
        int pairs = 0;
        for (int x = -20; x < 19; x++) {
            for (int z = -20; z < 20; z++) {
                pairs++;
                if (CoverLook.pick(x, 64, z, Direction.UP, count, c.rotate()) == CoverLook.pick(x + 1, 64, z, Direction.UP, count, c.rotate())) {
                    sameNext++;
                }
            }
        }
        double expect = 1.0 / (count * (c.rotate() ? 4 : 1));
        helper.assertTrue(sameNext < pairs * expect * 2 + 3, "隣と同じ絵・向きが多い: " + sameNext + " / " + pairs);
        helper.succeed();
    }

    /** 遠くの段は近くと同じ不透明の割合(緑に塗りつぶさない。作り直し側の CoverGameTests から。ユーザーの 5 枚で見る)。 */
    @GameTest(template = "gametest_empty7")
    public static void coverMipsKeepNearCoverageInsteadOfFillingGreen(GameTestHelper helper) {
        CoverTable c = DesignData.cover();
        for (String name : c.textures()) {
            int[] px;
            int w;
            int h;
            try (InputStream in = CoverGameTests.class.getResourceAsStream("/assets/overgrown_vegetation/textures/block/cover/" + name + ".png")) {
                if (in == null) {
                    helper.fail("覆いの絵 " + name + ".png が無い");
                    return;
                }
                BufferedImage img = ImageIO.read(in);
                w = img.getWidth();
                h = img.getHeight();
                px = img.getRGB(0, 0, w, h, null, 0, w);
            } catch (Exception e) {
                helper.fail("覆いの絵 " + name + " を読めない: " + e);
                return;
            }
            double near = CoverMipmap.coverage(px);
            int[] vanilla = px;
            int vw = w;
            int vh = h;
            for (int level = 1; (w >> level) >= 2 && level <= 4; level++) {
                int[] ours = CoverMipmap.level(px, w, h, level, c.mipCoverage(level));
                int n = ours.length;
                double want = Math.min(1, near * c.mipCoverage(level));
                double got = CoverMipmap.opaqueFraction(ours);
                helper.assertTrue(Math.abs(got - want) <= 0.5 / n + 1e-9,
                        name + " の " + level + " 段目の不透明 " + got + "(近く " + near + " × " + c.mipCoverage(level) + ")");
                vanilla = CoverMipmap.vanillaLevel(vanilla, vw, vh);
                vw = Math.max(1, vw >> 1);
                vh = Math.max(1, vh >> 1);
                if (level == 2) {
                    double v = CoverMipmap.opaqueFraction(vanilla);
                    helper.assertTrue(near >= 0.99 || got < v, name + ": 2 段目でバニラ " + v + " より薄くない(" + got + ")");
                }
            }
        }
        helper.succeed();
    }

    /**
     * 記録で覆ったマスは<b>汚染済み</b>として数える(2026-10-30 夜、ユーザー指定「覆ったマスを汚染済みに数える」)。
     * タグには入らないが {@code isCorruptedCell} は真、区画に記録があることも分かり、区画の添え字も正しい。資格の無いブロックへ置き換えたら偽。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void coveredCellsCountAsCorrupted(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        clear(helper);
        BlockPos at = helper.absolutePos(new BlockPos(3, 3, 3));
        level.setBlock(at, Blocks.STONE_BRICKS.defaultBlockState(), Block.UPDATE_CLIENTS);
        net.minecraft.world.level.chunk.LevelChunk chunk = level.getChunkAt(at);
        int sectionY = at.getY() >> 4;
        if (OvergrownLayerData.isCorruptedCell(level, at, level.getBlockState(at))) {
            helper.fail("覆っていない石レンガが汚染済みになった");
            return;
        }
        OvergrownLayerData.cover(level, at);
        BlockState s = level.getBlockState(at);
        if (s.is(ModTags.CORRUPTED) || !OvergrownLayerData.isCorruptedCell(level, at, s) || !OvergrownLayerData.isCorruptedCell(chunk, at, s)) {
            helper.fail("覆った石レンガが汚染済みに数えられない(タグには入らないはず)");
            return;
        }
        if (!OvergrownLayerData.hasAnyInSection(chunk, sectionY)) {
            helper.fail("覆ったマスの区画に記録が無いことになっている");
            return;
        }
        int want = ((at.getY() & 15) << 8) | ((at.getZ() & 15) << 4) | (at.getX() & 15);
        int[] found = {0};
        OvergrownLayerData.forEachInSection(chunk, sectionY, i -> {
            if (i == want) {
                found[0]++;
            }
        });
        if (found[0] != 1) {
            helper.fail("区画の中の添え字に覆ったマスが出ない");
            return;
        }
        level.setBlock(at, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS); // 覆う資格の無い(専用の過繁茂ブロックがある)石
        if (OvergrownLayerData.isCorruptedCell(level, at, level.getBlockState(at))) {
            helper.fail("石に置き換えたのに汚染済みのまま");
            return;
        }
        helper.succeed();
    }

    /** 触手以外の経路で新しく覆ったマスも段階の数に入る(前に覆ったマスをもう一度訪ねても増えない)。他のテストと混ざらないよう別の組で流す。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20, batch = "cover_counter")
    public static void newlyCoveredCellsAddToPhaseCount(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        clear(helper);
        BlockPos at = helper.absolutePos(new BlockPos(3, 2, 3));
        BlockState plate = Blocks.OAK_PRESSURE_PLATE.defaultBlockState(); // 同じ形の過繁茂ブロックが無いので必ず覆う
        level.setBlock(at, plate, Block.UPDATE_CLIENTS);
        com.yayuy.overgrownvegetation.phase.PhaseData phase = com.yayuy.overgrownvegetation.phase.PhaseData.get(level);
        long before = phase.getCorruptedBlocks();
        CorruptionRegistry.corrupt(level, at, plate, RandomSource.create(1));
        com.yayuy.overgrownvegetation.corruption.CorruptionSpreader.growPlantsIfNewlyCovered(level, at, plate);
        long once = phase.getCorruptedBlocks();
        CorruptionRegistry.corrupt(level, at, plate, RandomSource.create(2));
        com.yayuy.overgrownvegetation.corruption.CorruptionSpreader.growPlantsIfNewlyCovered(level, at, plate);
        long twice = phase.getCorruptedBlocks();
        if (once != before + 1 || twice != once) {
            helper.fail("覆ったマスの段階の数がおかしい: 前 " + before + " → 覆った " + once + " → もう一度 " + twice);
            return;
        }
        helper.succeed();
    }

    // ---- 小物 ----

    /** テスト場の記録を消す(前の回の記録がワールドに残っている)。 */
    private static void clear(GameTestHelper helper) {
        for (int x = 0; x < 7; x++) {
            for (int y = 1; y < 7; y++) {
                for (int z = 0; z < 7; z++) {
                    OvergrownLayerData.uncover(helper.getLevel(), helper.absolutePos(new BlockPos(x, y, z)));
                }
            }
        }
    }

    /** 閉じた状態のブロックを置き({@code covered} なら覆い)、右クリックのイベントを {@code times} 回通して、止められた回数。 */
    private static int activations(GameTestHelper helper, Player player, BlockPos abs, BlockState closed, boolean covered, int times) {
        ServerLevel level = helper.getLevel();
        int stuck = 0;
        for (int i = 0; i < times; i++) {
            place(helper, abs, closed);
            OvergrownLayerData.uncover(level, abs);
            OvergrownLayerData.uncover(level, abs.above());
            if (covered) {
                OvergrownLayerData.cover(level, abs);
            }
            PlayerInteractEvent.RightClickBlock event = CommonHooks.onRightClickBlock(player, InteractionHand.MAIN_HAND, abs, hit(abs));
            if (event.isCanceled()) {
                stuck++;
            }
        }
        OvergrownLayerData.uncover(level, abs);
        return stuck;
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

    /** 形の更新を飛ばして置く(扉は上半分もそろえて置く)。 */
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

    private static boolean within(int value, int min, int max) {
        return value >= min && value <= max;
    }

    /** 木材の半分で来るはずの過繁茂した木材(木材でなければ null)。本体の規則と別に書いて突き合わせる。 */
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
        // 原木・木は 2026-11-01 から過繁茂した木材に化けない(覆いと 3 割の過繁茂しているブロックだけ)
        return null;
    }

    /** 3 割で来るはずの同じ形の過繁茂ブロック(無ければ null)。 */
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
        if (!OvergrownLayerField.isPlainCube(vanilla)) {
            return null;
        }
        return vanilla.is(BlockTags.LOGS) ? ModBlocks.OVERGROWN_TRUNK_BLOCK.get() : ModBlocks.OVERGROWN_BLOCK.get();
    }

    /**
     * 這い上がる苔(28)の記録(2026-11-01 ユーザー指定): 覆いに接して<b>置いた</b>ブロックだけに記録ができる。自然に置かれた(イベントの無い)ブロック・
     * 覆いに接していないブロックにはできない。壊すと消える・そのマスが覆われると消える。帯を出す面と段階の決まりも見る。
     */
    @GameTest(template = "gametest_empty7")
    public static void creepRecordOnlyForPlacedBlocks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos covered = helper.absolutePos(new BlockPos(3, 2, 3));
        BlockPos above = covered.above();
        BlockPos side = covered.east();
        BlockPos far = helper.absolutePos(new BlockPos(1, 2, 1));
        helper.setBlock(new BlockPos(3, 2, 3), Blocks.OAK_PLANKS);
        if (!OvergrownLayerData.cover(level, covered)) {
            helper.fail("板材を覆えない");
            return;
        }
        try {
            // 自然のブロック(置いたイベントが無い)には記録ができない
            helper.setBlock(new BlockPos(3, 3, 3), Blocks.OAK_PLANKS);
            if (com.yayuy.overgrownvegetation.corruption.CoverCreepData.get(level, above) != Long.MIN_VALUE) {
                helper.fail("自然に置かれたブロックに記録ができた");
                return;
            }
            // 置いたブロック: 覆いに接していれば記録(接している向き)、接していなければ無し
            helper.setBlock(new BlockPos(4, 2, 3), Blocks.STONE);
            helper.setBlock(new BlockPos(1, 2, 1), Blocks.STONE);
            boolean placedSide = com.yayuy.overgrownvegetation.corruption.CoverCreepData.placed(level, side, level.getBlockState(side));
            boolean placedFar = com.yayuy.overgrownvegetation.corruption.CoverCreepData.placed(level, far, level.getBlockState(far));
            long value = com.yayuy.overgrownvegetation.corruption.CoverCreepData.get(level, side);
            if (!placedSide || placedFar || value == Long.MIN_VALUE
                    || com.yayuy.overgrownvegetation.corruption.CoverCreepData.sides(value) != 1 << Direction.WEST.ordinal()
                    || com.yayuy.overgrownvegetation.corruption.CoverCreepData.startTick(value) != level.getGameTime()) {
                helper.fail("置いたときの記録: 横 " + placedSide + "・離れた所 " + placedFar + "・値 " + value);
                return;
            }
            // 壊すと消える
            helper.setBlock(new BlockPos(4, 2, 3), Blocks.AIR);
            if (com.yayuy.overgrownvegetation.corruption.CoverCreepData.get(level, side) != Long.MIN_VALUE) {
                helper.fail("壊しても記録が残った");
                return;
            }
            // 覆われると消える
            if (!com.yayuy.overgrownvegetation.corruption.CoverCreepData.placed(level, above, level.getBlockState(above))) {
                helper.fail("上に置いた板材に記録ができない");
                return;
            }
            OvergrownLayerData.cover(level, above);
            if (com.yayuy.overgrownvegetation.corruption.CoverCreepData.get(level, above) != Long.MIN_VALUE) {
                helper.fail("覆われても記録が残った");
                return;
            }
            OvergrownLayerData.uncover(level, above);
            // 帯を出す面: 下が覆われていれば側面 4 つ、西が覆われていれば上下と南北
            for (Direction face : Direction.values()) {
                if (com.yayuy.overgrownvegetation.corruption.CoverCreep.bandOnFace(Direction.DOWN, face) != face.getAxis().isHorizontal()
                        || com.yayuy.overgrownvegetation.corruption.CoverCreep.bandOnFace(Direction.WEST, face) != (face.getAxis() != Direction.Axis.X)) {
                    helper.fail("帯を出す面が違う: " + face);
                    return;
                }
            }
            // 段階: 置いた直後 1、creepSeconds の半分で 4、creepSeconds で 8
            CoverTable design = DesignData.cover();
            long full = Math.round(design.creepSeconds() * 20.0D);
            int s0 = com.yayuy.overgrownvegetation.corruption.CoverCreep.stage(0L, design);
            int sMid = com.yayuy.overgrownvegetation.corruption.CoverCreep.stage(full / 2, design);
            int sEnd = com.yayuy.overgrownvegetation.corruption.CoverCreep.stage(full, design);
            int sLate = com.yayuy.overgrownvegetation.corruption.CoverCreep.stage(full * 3, design);
            int last = com.yayuy.overgrownvegetation.corruption.CoverCreep.STAGES;
            if (s0 != 1 || sEnd != last || sLate != last || sMid != last / 2) {
                helper.fail("段階: 始め " + s0 + "・半分 " + sMid + "・終わり " + sEnd + "・その後 " + sLate);
                return;
            }
            helper.succeed();
        } finally {
            OvergrownLayerData.uncover(level, above);
            OvergrownLayerData.uncover(level, covered);
        }
    }
    /**
     * 這い上がる苔の元は記録で覆ったマスだけでなく汚染ブロック全般(2026-11-01 ユーザー指定): overgrown_stone の上に置いた石には記録ができる。
     * 葉のような非固体の汚染ブロックは元にしない(仮決め)。
     */
    @GameTest(template = "gametest_empty7")
    public static void creepFromCorruptedBlocks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(new BlockPos(5, 2, 5), ModBlocks.OVERGROWN_STONE.get());
        helper.setBlock(new BlockPos(5, 3, 5), Blocks.STONE);
        helper.setBlock(new BlockPos(1, 2, 5), ModBlocks.OVERGROWN_LEAVES.get());
        helper.setBlock(new BlockPos(1, 3, 5), Blocks.STONE);
        BlockPos onStone = helper.absolutePos(new BlockPos(5, 3, 5));
        BlockPos onLeaves = helper.absolutePos(new BlockPos(1, 3, 5));
        boolean stone = com.yayuy.overgrownvegetation.corruption.CoverCreepData.placed(level, onStone, level.getBlockState(onStone));
        boolean leaves = com.yayuy.overgrownvegetation.corruption.CoverCreepData.placed(level, onLeaves, level.getBlockState(onLeaves));
        long value = com.yayuy.overgrownvegetation.corruption.CoverCreepData.get(level, onStone);
        if (!stone || leaves || com.yayuy.overgrownvegetation.corruption.CoverCreepData.sides(value) != 1 << Direction.DOWN.ordinal()) {
            helper.fail("汚染ブロックの上: overgrown_stone " + stone + "・葉 " + leaves + "・値 " + value);
            return;
        }
        helper.succeed();
    }
}
