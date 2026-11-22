package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.CaveMyceliumBlock;
import com.yayuy.overgrownvegetation.block.CaveSheetBlock;
import com.yayuy.overgrownvegetation.block.LeyRootBlock;
import com.yayuy.overgrownvegetation.corruption.CaveCover;
import com.yayuy.overgrownvegetation.corruption.CaveFlora;
import com.yayuy.overgrownvegetation.corruption.LeyLines;
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
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 地下の植生 1(2026-11-03): 菌糸の覆いの判定・天井の光源 A・地脈の根・狐火茸。
 * テスト場の {@value #SKY} 段上の空中に石の箱(屋根 3 段)で洞窟を作って試し、終わったら空気に戻す。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class CaveFloraGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(CaveFloraGameTests.class);
        }

        private Registration() {
        }
    }

    private static final int SKY = 40;
    /** 箱の外寸(7×7)と屋根の厚み。中は 1〜5。 */
    private static final int SIZE = 7, ROOF = 3;

    /** 中の高さ {@code inner} の石の箱(床 1 段 + 中 + 屋根 3 段)を作る。中の床の上のマスの y は origin + 1。 */
    private static void box(ServerLevel level, BlockPos origin, int inner) {
        int top = inner + ROOF;
        for (int y = 0; y <= top; y++) {
            for (int z = 0; z < SIZE; z++) {
                for (int x = 0; x < SIZE; x++) {
                    boolean shell = y == 0 || y > inner || x == 0 || z == 0 || x == SIZE - 1 || z == SIZE - 1;
                    level.setBlock(origin.offset(x, y, z), shell ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(),
                            Block.UPDATE_CLIENTS);
                }
            }
        }
    }

    private static void clear(ServerLevel level, BlockPos origin, int inner) {
        for (int y = inner + ROOF; y >= 0; y--) {
            for (int z = 0; z < SIZE; z++) {
                for (int x = 0; x < SIZE; x++) {
                    level.setBlock(origin.offset(x, y, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                }
            }
        }
    }

    private static int count(ServerLevel level, BlockPos origin, int inner, Block... blocks) {
        int n = 0;
        for (int y = 1; y <= inner; y++) {
            for (int z = 1; z < SIZE - 1; z++) {
                for (int x = 1; x < SIZE - 1; x++) {
                    BlockState s = level.getBlockState(origin.offset(x, y, z));
                    for (Block b : blocks) {
                        if (s.is(b)) {
                            n++;
                        }
                    }
                }
            }
        }
        return n;
    }

    /**
     * (a) 閉じた小さな石の箱(中の高さ 4)の中には菌糸 cave_mycelium が貼られ(複数の面をまとめたマスもある)、空の下の屋根の上には貼られない。
     * 中の高さ 13(巨大な洞窟)の箱には、天井・壁・床のどこにも貼られない(11-03 直し。ユーザー指定「出すのは小さな洞窟だけ」)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 160)
    public static void closedCaveFloorGetsMyceliumCover(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos small = helper.absolutePos(new BlockPos(0, SKY, 0));
        BlockPos big = helper.absolutePos(new BlockPos(0, SKY - 20, 0)); // 小さな箱の下(上に置くと小さな箱の屋根が「岩の天井の下」になる)
        box(level, small, 4);
        box(level, big, 13);
        helper.runAfterDelay(60, () -> { // 空の光が 0 になるまで待つ
            int floorFaces = CaveCover.caveFaces(level, small.offset(3, 0, 3));
            int roofFaces = CaveCover.caveFaces(level, small.offset(3, 4 + ROOF, 3));
            RandomSource random = RandomSource.create(5);
            int placedSmall = 0;
            for (int i = 0; i < 400; i++) {
                placedSmall += myceliumFromShell(level, small, 4, random) ? 1 : 0;
            }
            for (int i = 0; i < 400; i++) {
                myceliumFromShell(level, big, 13, random);
            }
            // 屋根の上(空の下)からも回す
            for (int i = 0; i < 100; i++) {
                BlockPos roof = small.offset(1 + random.nextInt(5), 4 + ROOF, 1 + random.nextInt(5));
                CaveFlora.placeMycelium(level, roof, level.getBlockState(roof), random);
            }
            int smallCells = count(level, small, 4, ModBlocks.CAVE_MYCELIUM.get());
            int multi = 0;
            for (int y = 1; y <= 4; y++) {
                for (int z = 1; z < SIZE - 1; z++) {
                    for (int x = 1; x < SIZE - 1; x++) {
                        BlockState s = level.getBlockState(small.offset(x, y, z));
                        if (s.is(ModBlocks.CAVE_MYCELIUM.get()) && net.minecraft.world.level.block.MultifaceBlock.availableFaces(s).size() >= 2) {
                            multi++;
                        }
                    }
                }
            }
            int bigCells = count(level, big, 13, ModBlocks.CAVE_MYCELIUM.get());
            int onRoof = 0;
            for (int z = 0; z < SIZE; z++) {
                for (int x = 0; x < SIZE; x++) {
                    onRoof += level.getBlockState(small.offset(x, 5 + ROOF, z)).is(ModBlocks.CAVE_MYCELIUM.get()) ? 1 : 0;
                }
            }
            BlockState sample = level.getBlockState(small.offset(3, 1, 3));
            clear(level, small, 4);
            clear(level, big, 13);
            if ((floorFaces & 1 << Direction.UP.ordinal()) == 0 || roofFaces != 0) {
                helper.fail("地下の面の判定が違う: 床の面 " + Integer.toBinaryString(floorFaces) + "・屋根の上の面 " + Integer.toBinaryString(roofFaces));
            } else if (smallCells < 10 || multi == 0) {
                helper.fail("小さな洞窟の菌糸が少ない: " + smallCells + " マス(2 面以上 " + multi + ")・貼った回数 " + placedSmall);
            } else if (bigCells != 0) {
                helper.fail("巨大な洞窟(高さ 13)に菌糸が貼られた: " + bigCells + " マス");
            } else if (onRoof != 0) {
                helper.fail("空の下の屋根の上に菌糸が貼られた: " + onRoof + " マス");
            } else if (sample.is(ModBlocks.CAVE_MYCELIUM.get()) && sample.getValue(CaveMyceliumBlock.GROUND) != CaveMyceliumBlock.Ground.STONE) {
                helper.fail("石の上の菌糸の下地が石でない: " + sample);
            } else {
                helper.succeed();
            }
        });
    }

    /** 箱の殻(中に面する石)をランダムに 1 つ選んで菌糸を貼らせる。 */
    private static boolean myceliumFromShell(ServerLevel level, BlockPos origin, int inner, RandomSource random) {
        int x = random.nextInt(SIZE), y = random.nextInt(inner + 2), z = random.nextInt(SIZE);
        BlockPos host = origin.offset(x, y, z);
        BlockState s = level.getBlockState(host);
        return s.is(Blocks.STONE) && CaveFlora.placeMycelium(level, host, s, random);
    }

    /** (b) 高さ 13 の空洞に網と房が付き、床に淡い下生えが生える。高さ 4 の洞窟には何度回しても網が付かない。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 40)
    public static void bigCaveGetsCeilingLightLowCaveDoesNot(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(0, SKY, 0));
        int inner = 13;
        box(level, origin, inner);
        BlockPos ceiling = origin.offset(3, inner + 1, 3);
        int height = CaveFlora.caveHeightBelow(level, ceiling);
        boolean placed = CaveFlora.placeCeilingLight(level, ceiling, RandomSource.create(7));
        int nets = count(level, origin, inner, ModBlocks.CEILING_ROOT_NET.get());
        int tips = count(level, origin, inner, ModBlocks.GLOW_TASSEL_TIP.get());
        int under = count(level, origin, inner, ModBlocks.PALE_CAVE_FERN.get(), ModBlocks.PALE_CAVE_CAPS.get(), ModBlocks.PALE_CAVE_MOSS.get(),
                ModBlocks.CAVE_HORSETAIL.get());
        BlockState netState = level.getBlockState(ceiling.below());
        clear(level, origin, inner);

        // 低い洞窟(高さ 4): 天井のどのブロックから何度回しても網は付かない
        box(level, origin, 4);
        RandomSource random = RandomSource.create(11);
        for (int i = 0; i < 400; i++) {
            BlockPos host = origin.offset(1 + random.nextInt(5), 5, 1 + random.nextInt(5));
            CaveFlora.decorate(level, host, level.getBlockState(host), random);
        }
        int lowNets = count(level, origin, 4, ModBlocks.CEILING_ROOT_NET.get(), ModBlocks.GLOW_TASSEL_TIP.get());
        clear(level, origin, 4);

        if (height < CaveFlora.BIG_CAVE_HEIGHT) {
            helper.fail("箱の高さの測り違い: " + height);
        } else if (!placed || nets < 5) {
            helper.fail("網が足りない: placed " + placed + "・網 " + nets);
        } else if (!netState.is(ModBlocks.CEILING_ROOT_NET.get()) || netState.getValue(CaveSheetBlock.FACING) != Direction.UP) {
            helper.fail("中心の天井の下が上向きの網でない: " + netState);
        } else if (tips < 1) {
            helper.fail("房の先が無い");
        } else if (under < 1) {
            helper.fail("房の下の床に下生えが無い");
        } else if (lowNets != 0) {
            helper.fail("高さ 4 の洞窟に網・房が付いた: " + lowNets);
        } else {
            helper.succeed();
        }
    }

    /** (c) 地脈の線までの距離で根の光り方が決まり(近い = near・遠い = far・無い = none)、置いた根はその場所の光り方と光量になる。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 40)
    public static void leyRootGlowFollowsDistanceToLeyLine(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        long seed = level.getSeed();
        // 線の近く・遠く・無い所が、テスト場の近く(±400)の地面の列にどれもあること
        BlockPos center = helper.absolutePos(BlockPos.ZERO);
        boolean near = false, far = false, none = false;
        for (int dx = -400; dx <= 400; dx += 2) {
            LeyRootBlock.Glow g = CaveFlora.leyGlowAt(seed, center.getX() + dx, center.getZ());
            near |= g == LeyRootBlock.Glow.NEAR;
            far |= g == LeyRootBlock.Glow.FAR;
            none |= g == LeyRootBlock.Glow.NONE;
        }
        BlockPos origin = helper.absolutePos(new BlockPos(0, SKY, 0));
        box(level, origin, 5);
        BlockPos wall = origin.offset(0, 5, 3); // 西の壁(中は東側)
        LeyRootBlock.Glow expected = CaveFlora.leyGlowAt(seed, wall.getX(), wall.getZ());
        int placed = CaveFlora.placeLeyRoots(level, wall, Direction.EAST, expected, RandomSource.create(3));
        BlockState root = level.getBlockState(wall.east());
        int light = root.getLightEmission();
        double d = LeyLines.distanceToLine(seed, wall.getX(), wall.getZ());
        clear(level, origin, 5);

        if (CaveFlora.glowForDistance(3) != LeyRootBlock.Glow.NEAR || CaveFlora.glowForDistance(10) != LeyRootBlock.Glow.FAR
                || CaveFlora.glowForDistance(40) != LeyRootBlock.Glow.NONE) {
            helper.fail("距離と光り方の対応が違う");
        } else if (!near || !far || !none) {
            helper.fail("±400 の列に揃わない: near " + near + "・far " + far + "・none " + none);
        } else if (placed < 2) {
            helper.fail("壁に根が 2 段以上這わない: " + placed);
        } else if (!root.is(ModBlocks.LEY_ROOT.get()) || root.getValue(LeyRootBlock.GLOW) != expected
                || root.getValue(CaveSheetBlock.FACING) != Direction.WEST) {
            helper.fail("置いた根が違う: " + root + "(期待 " + expected + "・距離 " + String.format("%.1f", d) + ")");
        } else if (light != LeyRootBlock.emission(root)) {
            helper.fail("光量が違う: " + light);
        } else {
            helper.succeed();
        }
    }

    /**
     * (e) 地下の植生 2・U5: 閉じた箱の天井は簾の出る所でなく、屋根の上に過繁茂の原木(コルダイテスの幹)を立てると木の真下になって簾が垂れる。
     * 片側の壁を抜いた箱(洞窟の口)の天井は、空の光の帯の所が口になり、簾が垂れる。口の箱は木の箱の 30 ブロックより下に置く。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 160)
    public static void rootCurtainHangsUnderTreeAndAtMouth(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos tree = helper.absolutePos(new BlockPos(0, SKY + 20, 0));
        BlockPos mouth = helper.absolutePos(new BlockPos(0, SKY - 15, 0));
        BlockPos thin = helper.absolutePos(new BlockPos(0, SKY - 35, 0)); // 屋根 1 段の口(11-04 ユーザー指定: 薄い天井の口にも垂れる)
        int inner = 5;
        box(level, tree, inner);
        box(level, mouth, inner);
        thinMouthBox(level, thin, inner);
        for (int y = 1; y <= inner; y++) {
            for (int z = 1; z < SIZE - 1; z++) {
                level.setBlock(mouth.offset(SIZE - 1, y, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL); // 東の壁を抜く
            }
        }
        helper.runAfterDelay(60, () -> { // 空の光が落ち着くまで待つ
            BlockPos ceiling = tree.offset(3, inner + 1, 3);
            CaveFlora.CurtainSite closed = CaveFlora.curtainSite(level, ceiling);
            for (int i = 1; i <= 3; i++) {
                level.setBlock(tree.offset(3, inner + ROOF + i, 3), ModBlocks.OVERGROWN_LOG.get().defaultBlockState(), Block.UPDATE_ALL);
            }
            CaveFlora.CurtainSite underTree = CaveFlora.curtainSite(level, ceiling);
            int treeStrands = CaveFlora.placeRootCurtain(level, ceiling, RandomSource.create(9));
            int treeTips = count(level, tree, inner, ModBlocks.DANGLING_ROOTS.get());
            int treeBody = count(level, tree, inner, ModBlocks.DANGLING_ROOTS_PLANT.get());

            BlockPos mouthCeiling = mouth.offset(4, inner + 1, 3); // 口から 2 ブロック奥
            int sky = level.getBrightness(net.minecraft.world.level.LightLayer.SKY, mouthCeiling.below());
            CaveFlora.CurtainSite atMouth = CaveFlora.curtainSite(level, mouthCeiling);
            int mouthStrands = CaveFlora.placeRootCurtain(level, mouthCeiling, RandomSource.create(4));
            int mouthTips = count(level, mouth, inner, ModBlocks.DANGLING_ROOTS.get());
            RandomSource tr = RandomSource.create(21);
            int thinStrands = 0;
            for (int i = 0; i < 600 && thinStrands == 0; i++) {
                BlockPos host = thin.offset(1 + tr.nextInt(5), inner + 1, 1 + tr.nextInt(5));
                thinStrands = CaveFlora.decorateMouth(level, host, level.getBlockState(host), tr);
            }
            int thinTips = count(level, thin, inner, ModBlocks.DANGLING_ROOTS.get());
            int thinSky = level.getBrightness(net.minecraft.world.level.LightLayer.SKY, thin.offset(4, inner, 3));
            BlockState tip = level.getBlockState(findTip(level, tree, inner));
            for (int i = 1; i <= 3; i++) {
                level.setBlock(tree.offset(3, inner + ROOF + i, 3), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
            clear(level, tree, inner);
            clear(level, mouth, inner);
            clear(level, thin, inner);
            if (closed != CaveFlora.CurtainSite.NONE) {
                helper.fail("閉じた箱(木なし)の天井が簾の出る所になった: " + closed);
            } else if (underTree != CaveFlora.CurtainSite.TREE) {
                helper.fail("原木の真下が木の真下にならない: " + underTree);
            } else if (treeStrands < 1 || treeTips != treeStrands) {
                helper.fail("木の真下に簾が垂れない: 本数 " + treeStrands + "・先 " + treeTips);
            } else if (treeBody < 1) {
                helper.fail("簾が 1 段だけ(中間が無い)");
            } else if (!tip.is(ModBlocks.DANGLING_ROOTS.get())) {
                helper.fail("先が見つからない: " + tip);
            } else if (atMouth != CaveFlora.CurtainSite.MOUTH) {
                helper.fail("口の天井が口にならない: " + atMouth + "(空の光 " + sky + ")");
            } else if (mouthStrands < 1 || mouthTips < 1) {
                helper.fail("口に簾が垂れない: " + mouthStrands);
            } else if (thinStrands < 1 || thinTips < 1) {
                helper.fail("屋根 1 段の口に簾が垂れない(空の光 " + thinSky + ")");
            } else {
                helper.succeed();
            }
        });
    }

    /**
     * (f) 境目の帯 2(11-08): 森の下の浅い洞窟の天井から地上の木の根。屋根 3 段の箱の上を汚染した草にしてバニラの原木を立てると、汚染の入口
     * ({@link CaveFlora#decorateForestRoots})から根が垂れる(バニラの垂れ根とこの MOD の垂れ根)。木が無い・地面が汚染されていない・屋根が厚い(forestRoofMax を超える)
     * 箱では森の上と判定されない。屋根の厚みの上限は設計値から(メモリ gametest-derive-from-design-values)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 160)
    public static void forestRootsHangUnderShallowCorruptedForest(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int inner = 5;
        int extra = com.yayuy.overgrownvegetation.data.DesignData.sporeField().forestRoofMax() + 1 - ROOF; // 厚い箱の屋根に足す段(上限 + 1 段)
        BlockPos forest = helper.absolutePos(new BlockPos(0, SKY + 20, 0));
        BlockPos bare = helper.absolutePos(new BlockPos(0, SKY + 4, 0)); // 木の無い箱
        BlockPos clean = helper.absolutePos(new BlockPos(0, SKY - 12, 0)); // 地面が汚染されていない箱
        BlockPos thick = helper.absolutePos(new BlockPos(0, SKY - 28 - extra, 0)); // 屋根の厚い箱
        BlockState grass = ModBlocks.OVERGROWN_GRASS_BLOCK.get().defaultBlockState();
        BlockState log = Blocks.OAK_LOG.defaultBlockState();
        for (BlockPos o : new BlockPos[]{forest, bare, clean, thick}) {
            box(level, o, inner);
        }
        for (int i = 1; i <= extra; i++) {
            for (int z = 0; z < SIZE; z++) {
                for (int x = 0; x < SIZE; x++) {
                    level.setBlock(thick.offset(x, inner + ROOF + i, z), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
        }
        int topForest = inner + ROOF;
        int topThick = inner + ROOF + extra;
        for (int z = 0; z < SIZE; z++) {
            for (int x = 0; x < SIZE; x++) {
                level.setBlock(forest.offset(x, topForest, z), grass, Block.UPDATE_CLIENTS);
                level.setBlock(bare.offset(x, topForest, z), grass, Block.UPDATE_CLIENTS);
                level.setBlock(clean.offset(x, topForest, z), Blocks.GRASS_BLOCK.defaultBlockState(), Block.UPDATE_CLIENTS);
                level.setBlock(thick.offset(x, topThick, z), grass, Block.UPDATE_CLIENTS);
            }
        }
        for (int i = 1; i <= 3; i++) {
            level.setBlock(forest.offset(3, topForest + i, 3), log, Block.UPDATE_CLIENTS);
            level.setBlock(clean.offset(3, topForest + i, 3), log, Block.UPDATE_CLIENTS);
            level.setBlock(thick.offset(3, topThick + i, 3), log, Block.UPDATE_CLIENTS);
        }
        helper.runAfterDelay(5, () -> {
            boolean underForest = CaveFlora.forestAbove(level, forest.offset(3, inner + 1, 3));
            boolean noTree = CaveFlora.forestAbove(level, bare.offset(3, inner + 1, 3));
            boolean notCorrupted = CaveFlora.forestAbove(level, clean.offset(3, inner + 1, 3));
            boolean thickRoof = CaveFlora.forestAbove(level, thick.offset(3, inner + 1, 3));
            // 汚染の入口から(確率に当たるまで天井のブロックを選び直す)
            RandomSource r = RandomSource.create(17);
            int strands = 0;
            for (int i = 0; i < 2000 && strands == 0; i++) {
                BlockPos host = forest.offset(1 + r.nextInt(5), inner + 1, 1 + r.nextInt(5));
                strands = CaveFlora.decorateForestRoots(level, host, level.getBlockState(host), r);
            }
            int modTips = count(level, forest, inner, ModBlocks.DANGLING_ROOTS.get());
            int vanilla = count(level, forest, inner, Blocks.HANGING_ROOTS);
            // 2 束目は間隔(forestRootsSpacing)で止まる
            int second = 0;
            for (int i = 0; i < 400; i++) {
                BlockPos host = forest.offset(2 + r.nextInt(3), inner + 1, 2 + r.nextInt(3));
                second += CaveFlora.decorateForestRoots(level, host, level.getBlockState(host), r);
            }
            for (BlockPos o : new BlockPos[]{forest, clean}) {
                for (int i = 1; i <= 3; i++) {
                    level.setBlock(o.offset(3, topForest + i, 3), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
            for (int i = 1; i <= 3; i++) {
                level.setBlock(thick.offset(3, topThick + i, 3), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
            for (int i = extra; i >= 1; i--) {
                for (int z = 0; z < SIZE; z++) {
                    for (int x = 0; x < SIZE; x++) {
                        level.setBlock(thick.offset(x, inner + ROOF + i, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                    }
                }
            }
            for (BlockPos o : new BlockPos[]{forest, bare, clean, thick}) {
                clear(level, o, inner);
            }
            if (!underForest) {
                helper.fail("屋根 3 段・汚染した草・原木の下が森の上にならない");
            } else if (noTree) {
                helper.fail("木の無い箱が森の上になった");
            } else if (notCorrupted) {
                helper.fail("汚染されていない地面の箱が森の上になった");
            } else if (thickRoof) {
                helper.fail("屋根 " + (ROOF + extra) + " 段の箱が森の上になった");
            } else if (strands < 1 || modTips + vanilla < 1) {
                helper.fail("森の下に根が垂れない: 本数 " + strands + "・先 " + modTips + "・バニラ " + vanilla);
            } else if (second != 0) {
                helper.fail("間隔の中に 2 束目が垂れた: " + second);
            } else {
                helper.succeed();
            }
        });
    }

    /** 屋根 1 段・東の壁の無い石の箱(洞窟と判定されない薄い天井の口)。 */
    private static void thinMouthBox(ServerLevel level, BlockPos origin, int inner) {
        for (int y = 0; y <= inner + 1; y++) {
            for (int z = 0; z < SIZE; z++) {
                for (int x = 0; x < SIZE; x++) {
                    boolean wall = x == 0 || z == 0 || z == SIZE - 1 || (x == SIZE - 1 && (y == 0 || y > inner));
                    boolean shell = y == 0 || y > inner || wall;
                    level.setBlock(origin.offset(x, y, z), shell ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(),
                            Block.UPDATE_CLIENTS);
                }
            }
        }
    }

    private static BlockPos findTip(ServerLevel level, BlockPos origin, int inner) {
        for (int y = 1; y <= inner; y++) {
            for (int z = 1; z < SIZE - 1; z++) {
                for (int x = 1; x < SIZE - 1; x++) {
                    if (level.getBlockState(origin.offset(x, y, z)).is(ModBlocks.DANGLING_ROOTS.get())) {
                        return origin.offset(x, y, z);
                    }
                }
            }
        }
        return origin;
    }

    /**
     * (f) 地下の植生 2・U6: 床に 2 段の窪み(3×3)を作った箱では窪みの真ん中に溜まりの印が置かれ、中の頭の高さは溜まりの中・縁の上は外。
     * 平らな床の箱には置かれない。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 40)
    public static void sporePoolMarksPitNotFlatFloor(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pit = helper.absolutePos(new BlockPos(0, SKY, 0));
        BlockPos flat = helper.absolutePos(new BlockPos(0, SKY - 20, 0));
        int inner = 5;
        box(level, pit, inner);
        box(level, flat, inner);
        for (int y = 1; y <= 2; y++) {
            for (int z = 1; z < SIZE - 1; z++) {
                for (int x = 1; x < SIZE - 1; x++) {
                    if (x < 2 || x > 4 || z < 2 || z > 4) {
                        level.setBlock(pit.offset(x, y, z), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS); // 縁を 2 段上げる
                    }
                }
            }
        }
        boolean placedPit = CaveFlora.placeSporePool(level, pit.offset(3, 0, 3));
        boolean marker = level.getBlockState(pit.offset(3, 1, 3)).is(ModBlocks.CAVE_SPORE_POOL.get());
        boolean inside = CaveFlora.inSporePool(level, pit.offset(2, 2, 4)); // 窪みの隅に立った頭
        boolean onRim = CaveFlora.inSporePool(level, pit.offset(1, 4, 1)); // 縁の上に立った頭
        boolean placedFlat = CaveFlora.placeSporePool(level, flat.offset(3, 0, 3));
        int flatMarkers = count(level, flat, inner, ModBlocks.CAVE_SPORE_POOL.get());
        clear(level, pit, inner);
        clear(level, flat, inner);
        if (!placedPit || !marker) {
            helper.fail("窪みに印が置かれない: " + placedPit + "・" + marker);
        } else if (!inside) {
            helper.fail("窪みの中の頭が溜まりの中にならない");
        } else if (onRim) {
            helper.fail("縁の上の頭が溜まりの中になった");
        } else if (placedFlat || flatMarkers != 0) {
            helper.fail("平らな床に印が置かれた: " + flatMarkers);
        } else {
            helper.succeed();
        }
    }

    /** (g) 地下の植生 2・U7: 巨大菌の柱は床から天井まで 13 の空洞には立ち(高さ 8 以上)、9 の空洞には立たない。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 40)
    public static void prototaxitesOnlyInTallCaves(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos low = helper.absolutePos(new BlockPos(0, SKY, 0));
        BlockPos tall = helper.absolutePos(new BlockPos(0, SKY - 25, 0));
        RandomSource random = RandomSource.create(13);
        // 低い方を先に(高い方の柱があるとセクションの上限で低い方も断られ、理由を取り違える)
        box(level, low, 9);
        boolean placedLow = false;
        for (int i = 0; i < 20; i++) {
            placedLow |= CaveFlora.placePrototaxites(level, low.offset(1 + random.nextInt(5), 0, 1 + random.nextInt(5)), random);
        }
        int lowStems = count(level, low, 9, ModBlocks.PROTOTAXITES_STEM.get());
        clear(level, low, 9);
        box(level, tall, 13);
        int span = CaveFlora.caveHeightAbove(level, tall.offset(3, 0, 3));
        boolean placedTall = CaveFlora.placePrototaxites(level, tall.offset(3, 0, 3), random, 1, -1); // 2×2(11-04: 列ごとに高さがばらばら)
        int tallStems = count(level, tall, 13, ModBlocks.PROTOTAXITES_STEM.get());
        int[][] colsXZ = {{3, 3}, {4, 3}, {3, 4}, {4, 4}};
        int[] heights = new int[4];
        boolean topsOk = true;
        String tops = "";
        for (int c = 0; c < 4; c++) {
            while (level.getBlockState(tall.offset(colsXZ[c][0], 1 + heights[c], colsXZ[c][1])).is(ModBlocks.PROTOTAXITES_STEM.get())) {
                heights[c]++;
            }
            BlockState t = level.getBlockState(tall.offset(colsXZ[c][0], 1 + heights[c], colsXZ[c][1])); // 列ごとの天辺(瘤か傘)
            topsOk &= t.is(ModBlocks.PROTOTAXITES_KNOB.get()) || t.is(ModBlocks.PROTOTAXITES_CAP.get());
            tops += " " + t.getBlock().getDescriptionId().replace("block.overgrown_vegetation.", "");
        }
        int column = Math.max(Math.max(heights[0], heights[1]), Math.max(heights[2], heights[3]));
        boolean uneven = heights[0] != heights[1] || heights[0] != heights[2] || heights[0] != heights[3];
        // 2 本目は 1 本目と同じチャンクの空いた床から(上限はチャンクの 3 セクションごと。テスト場の位置でチャンクの境が箱を横切る)
        net.minecraft.world.level.ChunkPos first = new net.minecraft.world.level.ChunkPos(tall.offset(3, 0, 3));
        BlockPos second = null;
        for (int z = 1; z < SIZE - 1 && second == null; z++) {
            for (int x = 1; x < SIZE - 1 && second == null; x++) {
                BlockPos f = tall.offset(x, 0, z);
                if (new net.minecraft.world.level.ChunkPos(f).equals(first) && level.getBlockState(f.above()).isAir()) {
                    second = f;
                }
            }
        }
        boolean again = second != null && CaveFlora.placePrototaxites(level, second, random);
        clear(level, tall, 13);
        if (span != 13) {
            helper.fail("空洞の高さの測り違い: " + span);
        } else if (placedLow || lowStems != 0) {
            helper.fail("高さ 9 の空洞に柱が立った: " + lowStems);
        } else if (!placedTall || column < 8 || column > 11) {
            helper.fail("高さ 13 の空洞の柱が違う: placed " + placedTall + "・高さ " + java.util.Arrays.toString(heights) + "・幹 " + tallStems);
        } else if (!uneven) {
            helper.fail("2×2 の 4 列の高さが全部同じ: " + java.util.Arrays.toString(heights));
        } else if (!topsOk) {
            helper.fail("列の天辺に瘤も傘も無い:" + tops);
        } else if (again) {
            helper.fail("同じ区画に 2 本目が立った");
        } else {
            helper.succeed();
        }
    }

    /** (d) 狐火茸は床に立ち、チャンクのその高さで上限({@link CaveFlora#FOXFIRE_MAX})を超えない。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 40)
    public static void foxfireStaysSparse(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(0, SKY, 0));
        box(level, origin, 3);
        RandomSource random = RandomSource.create(5);
        int placed = 0;
        int worst = 0;
        for (int z = 1; z < SIZE - 1; z++) {
            for (int x = 1; x < SIZE - 1; x++) {
                BlockPos floor = origin.offset(x, 0, z);
                if (CaveFlora.placeFoxfire(level, floor, random)) {
                    placed++;
                }
                worst = Math.max(worst, CaveFlora.countFoxfire(level, floor.above()));
            }
        }
        int standing = count(level, origin, 3, ModBlocks.FOXFIRE.get());
        clear(level, origin, 3);
        if (placed < 1 || standing != placed) {
            helper.fail("狐火茸が立たない・数が合わない: 置いた " + placed + "・立っている " + standing);
        } else if (worst > CaveFlora.FOXFIRE_MAX) {
            helper.fail("チャンクの上限を超えた: " + worst);
        } else {
            helper.succeed();
        }
    }
}
