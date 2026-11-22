package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.CharaBlock;
import com.yayuy.overgrownvegetation.block.SigillariaFringeBlock;
import com.yayuy.overgrownvegetation.block.WaterRootsBlock;
import com.yayuy.overgrownvegetation.corruption.UnderwaterPlants;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * 水中の植物(2026-11-01): シャジクモの株・胞子昆布・垂れる根・水面の膜が水中専用で、崩れても空気の穴を作らない。
 * テスト場の {@value #SKY} 段上の空中にガラスの水槽を作って試し、終わったら空気に戻す。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class UnderwaterPlantGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(UnderwaterPlantGameTests.class);
        }

        private Registration() {
        }
    }

    private static final int SKY = 40;
    /** 水槽の中の広さ(1〜5)と深さ。 */
    private static final int LO = 1, HI = 5, DEPTH = 22;

    @GameTest(template = "gametest_empty7", timeoutTicks = 40)
    public static void charaStackBreaksIntoWater(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(0, SKY, 0));
        tank(level, origin, 5);
        BlockPos base = origin.offset(3, 1, 3);
        if (!CharaBlock.placeStack(level, base, 3, CharaBlock.Kind.B, ModBlocks.CHARA.get())) {
            clear(level, origin, 5);
            helper.fail("3 段の株を置けなかった");
            return;
        }
        String parts = level.getBlockState(base).getValue(CharaBlock.PART) + "/" + level.getBlockState(base.above()).getValue(CharaBlock.PART)
                + "/" + level.getBlockState(base.above(2)).getValue(CharaBlock.PART);
        boolean stackOk = level.getBlockState(base.above(2)).getValue(CharaBlock.PART) == CharaBlock.Part.TOP_OF_3
                && level.getBlockState(base).getFluidState().isSource();
        level.destroyBlock(base.above(), false);
        helper.runAfterDelay(2, () -> {
            String after = level.getBlockState(base) + " / " + level.getBlockState(base.above()) + " / " + level.getBlockState(base.above(2));
            boolean water = true;
            for (int i = 0; i < 3; i++) {
                water &= level.getBlockState(base.above(i)).is(Blocks.WATER);
            }
            clear(level, origin, 5);
            if (!stackOk) {
                helper.fail("段の並びが違う: " + parts);
            } else if (!water) {
                helper.fail("中の段を壊したあと、水に戻らない段がある: " + after);
            } else {
                helper.succeed();
            }
        });
    }

    @GameTest(template = "gametest_empty7", timeoutTicks = 90)
    public static void sporeKelpStandsWithArmsAndFronds(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(0, SKY, 0));
        tank(level, origin, DEPTH);
        BlockPos water = origin.offset(3, 1, 3);
        boolean grown = UnderwaterPlants.growKelp(level, water, RandomSource.create(origin.asLong()));
        int stalk = 0, arms = 0, hang = 0, side = 0, air = 0, topY = 0, bottomY = DEPTH;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int y = 1; y <= DEPTH; y++) {
            for (int x = LO; x <= HI; x++) {
                for (int z = LO; z <= HI; z++) {
                    p.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    BlockState s = level.getBlockState(p);
                    if (s.is(ModBlocks.SPORE_KELP_STALK.get())) {
                        if (x == 3 && z == 3) {
                            stalk++;
                            topY = Math.max(topY, y);
                            bottomY = Math.min(bottomY, y);
                        } else {
                            arms++;
                        }
                    } else if (s.is(ModBlocks.SPORE_KELP_FROND.get())) {
                        if (s.getValue(SigillariaFringeBlock.FACING) == Direction.UP) hang++;
                        else side++;
                    } else if (s.isAir()) {
                        air++;
                    }
                }
            }
        }
        String summary = "柱 " + stalk + "・腕 " + arms + "・垂れ " + hang + "・横 " + side + "・頂 " + topY + "・空気 " + air;
        OvergrownVegetation.LOGGER.info("胞子昆布: {}", summary);
        boolean shapeOk = stalk >= 8 && arms >= 1 && hang >= 1 && side >= 4;
        boolean heightOk = topY <= DEPTH - 2;
        boolean noAir = air == 0;
        // 根元を壊すと全部崩れて水に戻る
        level.destroyBlock(origin.offset(3, bottomY, 3), false);
        helper.runAfterDelay(45, () -> {
            int left = 0;
            int holes = 0;
            for (int y = 1; y <= DEPTH; y++) {
                for (int x = LO; x <= HI; x++) {
                    for (int z = LO; z <= HI; z++) {
                        p.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                        BlockState s = level.getBlockState(p);
                        if ((s.is(ModBlocks.SPORE_KELP_STALK.get()) || s.is(ModBlocks.SPORE_KELP_FROND.get()))) left++;
                        if (s.isAir()) holes++;
                    }
                }
            }
            clear(level, origin, DEPTH);
            if (!grown) {
                helper.fail("立たなかった: " + summary);
            } else if (!shapeOk) {
                helper.fail("揃っていない: " + summary);
            } else if (!heightOk) {
                helper.fail("海面の 2 段下より高い: " + summary);
            } else if (!noAir) {
                helper.fail("水槽に空気の穴: " + summary);
            } else if (left > 0 || holes > 0) {
                helper.fail("根元を壊しても崩れない / 穴が残る: 残り " + left + "・穴 " + holes);
            } else {
                helper.succeed();
            }
        });
    }

    @GameTest(template = "gametest_empty7", timeoutTicks = 40)
    public static void rootsHangFromBankAndFilmsNeedSource(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(0, SKY, 0));
        tank(level, origin, 8);
        // 岸の張り出し: 水面の上の段に土を 1 つ、その下の水へ根を垂らす
        BlockPos bank = origin.offset(2, 8, 2);
        level.setBlock(bank, Blocks.DIRT.defaultBlockState(), Block.UPDATE_CLIENTS);
        level.setBlock(bank.below(), Blocks.WATER.defaultBlockState(), Block.UPDATE_CLIENTS);
        UnderwaterPlants.hangRoots(level, bank.below(), RandomSource.create(7));
        int roots = 0;
        boolean tipLast = false;
        for (int i = 1; i <= 7; i++) {
            BlockState s = level.getBlockState(bank.below(i));
            if (!s.is(ModBlocks.WATER_ROOTS.get())) {
                break;
            }
            roots++;
            tipLast = s.getValue(WaterRootsBlock.TIP);
        }
        // 膜: 水源の上には置け、石・流れの上には置けない
        BlockState film = ModBlocks.ALGAE_FILM.get().defaultBlockState();
        BlockPos onWater = origin.offset(4, 9, 4);
        boolean onSource = film.canSurvive(level, onWater);
        level.setBlock(onWater.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        boolean onStone = film.canSurvive(level, onWater);
        level.setBlock(onWater.below(), Fluids.WATER.getFlowing(4, false).createLegacyBlock(), Block.UPDATE_CLIENTS);
        boolean onFlow = film.canSurvive(level, onWater);
        clear(level, origin, 8);
        level.setBlock(bank, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        String summary = "根 " + roots + "(先 " + tipLast + ")・膜 水源 " + onSource + "・石 " + onStone + "・流れ " + onFlow;
        if (roots < 2 || roots > 5 || !tipLast) {
            helper.fail("根が 2〜5 段で先に終わらない: " + summary);
        } else if (!onSource || onStone || onFlow) {
            helper.fail("膜の置き場所が違う: " + summary);
        } else {
            helper.succeed();
        }
    }

    /** 床が石・壁がガラスの水槽(中は 1〜5、深さ {@code depth})。水は流れを起こさないよう知らせずに置く。 */
    /**
     * 11-13 密林の水辺 ③④: 水底の落ち葉は水源を抱え、下の床が無くなると水に戻る。倒木は水槽の真ん中からどの向きへも 3 ブロック以上寝る。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 40)
    public static void sunkenLitterAndLogStayInWater(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(0, SKY, 0));
        tank(level, origin, 4);
        BlockPos litter = origin.offset(2, 1, 2);
        level.setBlock(litter, ModBlocks.SUNKEN_LEAF_LITTER.get().defaultBlockState(), Block.UPDATE_ALL);
        boolean holdsWater = level.getBlockState(litter).getFluidState().isSource();
        boolean logged = UnderwaterPlants.sinkLog(level, origin.offset(3, 1, 3), RandomSource.create(7L));
        int logs = 0;
        for (int x = 1; x <= 5; x++) {
            for (int z = 1; z <= 5; z++) {
                logs += level.getBlockState(origin.offset(x, 1, z)).is(ModBlocks.FALLEN_LOG.get()) ? 1 : 0;
            }
        }
        int logCount = logs;
        level.setBlock(litter.below(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        helper.runAfterDelay(2, () -> {
            BlockState after = level.getBlockState(litter);
            boolean back = after.getFluidState().is(Fluids.WATER) && !after.is(ModBlocks.SUNKEN_LEAF_LITTER.get());
            clear(level, origin, 4);
            if (!holdsWater) {
                helper.fail("水底の落ち葉が水源を抱えていない");
            } else if (!logged || logCount < 3) {
                helper.fail("倒木が寝ない: " + logged + " / " + logCount);
            } else if (!back) {
                helper.fail("床を外しても水に戻らない: " + after);
            } else {
                helper.succeed();
            }
        });
    }

    private static void tank(ServerLevel level, BlockPos origin, int depth) {
        BlockState water = Blocks.WATER.defaultBlockState();
        for (int x = 0; x <= 6; x++) {
            for (int z = 0; z <= 6; z++) {
                level.setBlock(origin.offset(x, 0, z), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
                boolean wall = x == 0 || z == 0 || x == 6 || z == 6;
                for (int y = 1; y <= depth; y++) {
                    level.setBlock(origin.offset(x, y, z), wall ? Blocks.GLASS.defaultBlockState() : water, Block.UPDATE_CLIENTS);
                }
            }
        }
    }

    private static void clear(ServerLevel level, BlockPos origin, int depth) {
        for (int x = 0; x <= 6; x++) {
            for (int z = 0; z <= 6; z++) {
                for (int y = depth + 2; y >= 0; y--) {
                    level.setBlock(origin.offset(x, y, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                }
            }
        }
    }

    private UnderwaterPlantGameTests() {
    }
}
