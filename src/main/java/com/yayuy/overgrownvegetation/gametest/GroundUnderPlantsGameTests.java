package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.CorruptionGrowth;
import com.yayuy.overgrownvegetation.corruption.CorruptionSpreader;
import com.yayuy.overgrownvegetation.corruption.OvergrownBiomeCreep;
import com.yayuy.overgrownvegetation.corruption.OvergrownLayerData;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * 草の下の汚染の抜け(2026-11-01 夜、ユーザー「草が生えている場所の下が汚染されない」)。
 *
 * <p>過繁茂の地域の浸食(OvergrownBiomeCreep)は列の頂から的を選ぶ。草・シダ・花・雪の層の下の地面、
 * 梢(過繁茂の葉)の下の陸の地面、汚染済みの下草の下の地面が、浸食と触手のどちらでも置き換わるか覆われること。
 * テスト場の {@value #SKY} 段上の空中に床を作って試し、終わったら空気に戻す。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class GroundUnderPlantsGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(GroundUnderPlantsGameTests.class);
        }

        private Registration() {
        }
    }

    private static final int SKY = 40;
    /** 床の高さ(y 1)から上に掃除する段数。梢は床の 4 段上。 */
    private static final int HEIGHT = 6;
    private static final Block[] GROUNDS = {Blocks.GRASS_BLOCK, Blocks.DIRT, Blocks.GRAVEL, Blocks.STONE, Blocks.COARSE_DIRT};

    /**
     * 草ブロック・土・砂利・石・粗い土の床(x ごと)に、短い草・シダ・背の高い草・ポピー・雪の層(z ごと)を載せて列ごとの浸食を回す。
     * 床の 25 マスが全部、置き換わるか覆われる。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 40)
    public static void creepReachesGroundUnderVanillaPlants(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(0, SKY, 0));
        clear(level, origin);
        for (int x = 1; x <= 5; x++) {
            for (int z = 1; z <= 5; z++) {
                floor(level, origin, x, z);
                BlockPos plant = origin.offset(x, 2, z);
                switch (z) {
                    case 1 -> set(level, plant, Blocks.SHORT_GRASS.defaultBlockState());
                    case 2 -> set(level, plant, Blocks.FERN.defaultBlockState());
                    case 3 -> tall(level, plant, Blocks.TALL_GRASS.defaultBlockState());
                    case 4 -> set(level, plant, Blocks.POPPY.defaultBlockState());
                    default -> set(level, plant, Blocks.SNOW.defaultBlockState());
                }
            }
        }
        creepAndCheck(helper, level, origin, new String[] {"", "短い草", "シダ", "背の高い草", "ポピー", "雪の層"});
    }

    /**
     * 汚染済みの下草(立つもの・2 段のもの)と梢(過繁茂の葉)の下の陸の床。z 1・2 は梢だけ、z 3 は下草、z 4 は 2 段の下草、
     * z 5 は梢 + 短い草。床の 25 マスが全部、置き換わるか覆われる。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 40)
    public static void creepReachesGroundUnderCanopyAndCorruptedPlants(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(0, SKY, 0));
        clear(level, origin);
        for (int x = 1; x <= 5; x++) {
            for (int z = 1; z <= 5; z++) {
                floor(level, origin, x, z);
                BlockPos plant = origin.offset(x, 2, z);
                switch (z) {
                    case 3 -> set(level, plant, CorruptionGrowth.randomTop(level.random));
                    case 4 -> tall(level, plant, ModBlocks.OVERGROWN_TALL_FERN.get().defaultBlockState());
                    case 5 -> set(level, plant, Blocks.SHORT_GRASS.defaultBlockState());
                    default -> { }
                }
                if (z != 3 && z != 4) {
                    set(level, origin.offset(x, 5, z), ModBlocks.OVERGROWN_LEAVES.get().defaultBlockState());
                }
            }
        }
        // 深部浸食の入口: 梢の下の林床が残っている列は「まだ」
        BlockPos underCanopy = origin.offset(1, 1, 1);
        if (OvergrownBiomeCreep.surfaceDoneForTest(level, underCanopy.getX(), underCanopy.getZ())) {
            clear(level, origin);
            helper.fail("梢の下の林床が素のままなのに、深部浸食の入口で「済み」と数えた");
            return;
        }
        // 幹の根元(幹が直に載った地面)は表に出ていないので浸食の的にしない
        BlockPos trunkFoot = origin.offset(6, 1, 3);
        set(level, trunkFoot.below(), Blocks.STONE.defaultBlockState());
        set(level, trunkFoot, Blocks.GRASS_BLOCK.defaultBlockState());
        for (int y = 2; y <= 4; y++) {
            set(level, origin.offset(6, y, 3), ModBlocks.OVERGROWN_LOG.get().defaultBlockState());
        }
        set(level, origin.offset(6, 5, 3), ModBlocks.OVERGROWN_LEAVES.get().defaultBlockState());
        OvergrownBiomeCreep.creepColumnForTest(level, trunkFoot.getX(), trunkFoot.getZ());
        if (!level.getBlockState(trunkFoot).is(Blocks.GRASS_BLOCK)) {
            clear(level, origin);
            helper.fail("幹の根元の地面を浸食の的にした: " + level.getBlockState(trunkFoot));
            return;
        }
        creepAndCheck(helper, level, origin, new String[] {"", "梢", "梢", "汚染済みの下草", "汚染済みの 2 段の下草", "梢 + 短い草"});
    }

    /**
     * 巨大樹(高さ 120)の樹冠の下: 床の {@value #HIGH_CROWN} 段上に 2 段の葉を張り、草ブロックと砂利の床の列を浸食する。
     * 間の空気だけの区画は飛ばして読む。床が置き換わるか覆われる。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 40)
    public static void creepReachesGroundUnderGiantCrown(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(0, SKY, 0));
        BlockPos[] floors = {origin.offset(2, 1, 3), origin.offset(4, 1, 3)};
        Block[] grounds = {Blocks.GRASS_BLOCK, Blocks.GRAVEL};
        for (int i = 0; i < floors.length; i++) {
            set(level, floors[i].below(), Blocks.STONE.defaultBlockState());
            set(level, floors[i], grounds[i].defaultBlockState());
            set(level, floors[i].above(), Blocks.SHORT_GRASS.defaultBlockState());
            set(level, floors[i].above(HIGH_CROWN), ModBlocks.OVERGROWN_LEAVES.get().defaultBlockState());
            set(level, floors[i].above(HIGH_CROWN + 1), ModBlocks.OVERGROWN_LEAVES.get().defaultBlockState());
        }
        for (int round = 0; round < 3; round++) {
            for (BlockPos floor : floors) {
                OvergrownBiomeCreep.creepColumnForTest(level, floor.getX(), floor.getZ());
            }
        }
        StringBuilder left = new StringBuilder();
        for (BlockPos floor : floors) {
            if (!OvergrownLayerData.isCorruptedCell(level, floor, level.getBlockState(floor))) {
                left.append(' ').append(level.getBlockState(floor).getBlock().getDescriptionId());
            }
            for (int dy = 0; dy <= HIGH_CROWN + 1; dy++) {
                BlockPos p = floor.above(dy);
                OvergrownLayerData.uncover(level, p);
                level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            }
            level.setBlock(floor.below(), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        if (left.length() > 0) {
            helper.fail(HIGH_CROWN + " 段上の樹冠の下の床が残った:" + left);
        } else {
            helper.succeed();
        }
    }

    private static final int HIGH_CROWN = 110;

    /**
     * 触手の経路:草ブロックの床に短い草・シダ・背の高い草を一面に生やしたときと、何も生やさないときで、コア半径 3 の触手を
     * {@value #SPREADER_TRIALS} 回ずつ回す。コアはわざとまだらに広がる(隙間が残るのは仕様)ので、起点から 2 マス以内の床が汚染された数を比べ、
     * 草があっても何も無いときの 85% を下回らないこと。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 400)
    public static void spreaderReachesGroundUnderPlants(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(0, SKY, 0));
        int withPlants = 0;
        int bare = 0;
        for (int trial = 0; trial < SPREADER_TRIALS; trial++) {
            withPlants += spreadOnce(level, origin, true);
            bare += spreadOnce(level, origin, false);
        }
        if (withPlants < bare * 0.85D) {
            helper.fail("触手のコアの床(13 マス × " + SPREADER_TRIALS + " 回)で汚染されたのは、草あり " + withPlants + " / 草なし " + bare);
        } else {
            helper.succeed();
        }
    }

    private static final int SPREADER_TRIALS = 6;

    /** 草ブロックの床で触手を 1 回回し、起点から 2 マス以内の床(13 マス)のうち汚染された数。 */
    private static int spreadOnce(ServerLevel level, BlockPos origin, boolean plants) {
        clear(level, origin);
        for (int x = 0; x <= 6; x++) {
            for (int z = 0; z <= 6; z++) {
                set(level, origin.offset(x, 0, z), Blocks.STONE.defaultBlockState());
                set(level, origin.offset(x, 1, z), Blocks.GRASS_BLOCK.defaultBlockState());
                if (!plants) {
                    continue;
                }
                BlockPos plant = origin.offset(x, 2, z);
                switch ((x + z) % 3) {
                    case 0 -> set(level, plant, Blocks.SHORT_GRASS.defaultBlockState());
                    case 1 -> set(level, plant, Blocks.FERN.defaultBlockState());
                    default -> tall(level, plant, Blocks.TALL_GRASS.defaultBlockState());
                }
            }
        }
        BlockPos seed = origin.offset(3, 2, 3);
        CorruptionSpreader spreader = new CorruptionSpreader();
        spreader.seedGround(level, seed);
        for (int i = 0; i < 400 && !spreader.isFinished(); i++) {
            spreader.tick(level, seed, 4, 0, 3, 4, 8);
        }
        int done = 0;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos p = origin.offset(3 + dx, 1, 3 + dz);
                if (dx * dx + dz * dz <= 4 && OvergrownLayerData.isCorruptedCell(level, p, level.getBlockState(p))) {
                    done++;
                }
            }
        }
        clear(level, origin);
        return done;
    }

    private static void creepAndCheck(GameTestHelper helper, ServerLevel level, BlockPos origin, String[] rowNames) {
        for (int round = 0; round < 12; round++) {
            for (int x = 1; x <= 5; x++) {
                for (int z = 1; z <= 5; z++) {
                    OvergrownBiomeCreep.creepColumnForTest(level, origin.getX() + x, origin.getZ() + z);
                }
            }
        }
        int done = 0;
        StringBuilder left = new StringBuilder();
        for (int x = 1; x <= 5; x++) {
            for (int z = 1; z <= 5; z++) {
                BlockPos p = origin.offset(x, 1, z);
                BlockState s = level.getBlockState(p);
                if (OvergrownLayerData.isCorruptedCell(level, p, s)) {
                    done++;
                } else {
                    int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ()) - 1;
                    left.append(' ').append(x).append(',').append(z).append('=').append(s.getBlock().getDescriptionId())
                            .append('(').append(rowNames[z]).append(" 頂=")
                            .append(level.getBlockState(new BlockPos(p.getX(), top, p.getZ())).getBlock().getDescriptionId())
                            .append(" y").append(top - origin.getY()).append(')');
                }
            }
        }
        clear(level, origin);
        if (done < 25) {
            helper.fail("床 25 マスのうち汚染されたのは " + done + "。残り:" + left);
        } else {
            helper.succeed();
        }
    }

    private static void floor(ServerLevel level, BlockPos origin, int x, int z) {
        set(level, origin.offset(x, 0, z), Blocks.STONE.defaultBlockState());
        set(level, origin.offset(x, 1, z), GROUNDS[(x - 1) % GROUNDS.length].defaultBlockState());
    }

    private static void tall(ServerLevel level, BlockPos lower, BlockState state) {
        set(level, lower, state.setValue(DoublePlantBlock.HALF, DoubleBlockHalf.LOWER));
        set(level, lower.above(), state.setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER));
    }

    private static void set(ServerLevel level, BlockPos pos, BlockState state) {
        level.setBlock(pos, state, Block.UPDATE_CLIENTS);
    }

    private static void clear(ServerLevel level, BlockPos origin) {
        for (int x = 0; x <= 6; x++) {
            for (int z = 0; z <= 6; z++) {
                for (int y = HEIGHT; y >= 0; y--) {
                    BlockPos p = origin.offset(x, y, z);
                    OvergrownLayerData.uncover(level, p);
                    level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                }
            }
        }
    }

    private GroundUnderPlantsGameTests() {
    }
}
