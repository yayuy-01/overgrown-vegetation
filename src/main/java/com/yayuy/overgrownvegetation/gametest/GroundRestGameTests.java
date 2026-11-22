package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.CordaitesButtressBlock;
import com.yayuy.overgrownvegetation.block.HorsetailBlock;
import com.yayuy.overgrownvegetation.block.SigillariaFringeBlock;
import com.yayuy.overgrownvegetation.block.StigmariaBlock;
import com.yayuy.overgrownvegetation.corruption.FloorGrowth;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.ModFeatures;
import com.yayuy.overgrownvegetation.worldgen.ForcedTreeFeature;
import com.yayuy.overgrownvegetation.worldgen.SigillariaTreeFeature;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 地上の植物の残り(2026-11-02): トクサの浅い淵(16)・コルダイテスの板根と封印木のスティグマリア(32)・中層の葉の房(33)。
 * テスト場の上の空中に台を作って試し、終わったら台ごと空気に戻す。高さは林床・封印木・木生シダのテストと重ならないよう別々にする。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class GroundRestGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(GroundRestGameTests.class);
        }

        private Registration() {
        }
    }

    // ======================= 16 トクサ =======================

    /** 泥炭の面の真ん中(群れの中心)に深さ 1 の水たまり 3×3。群れの 13 マスはどれも水から 2 以内。株の数・高さ・水没・崩れを見る。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 60)
    public static void horsetailStandGrowsAtPeatPool(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos probe = helper.absolutePos(new BlockPos(0, 175, 0));
        int[] a = FloorGrowth.horsetailAnchor(level.getSeed(), probe.getX() >> 4, probe.getZ() >> 4);
        if (a == null) {
            helper.succeed(); // 群れの無いチャンク(既定では無い)
            return;
        }
        BlockPos center = new BlockPos(a[0], probe.getY(), a[1]);
        int r = 6;
        build(level, center, r, true);
        List<BlockPos> bottoms = new ArrayList<>();
        int expected = 0;
        String bad = null;
        try {
            FloorGrowth.runAsJungle(() -> {
                for (int dx = -r + 1; dx < r; dx++) {
                    for (int dz = -r + 1; dz < r; dz++) {
                        BlockPos g = center.offset(dx, 0, dz);
                        FloorGrowth.tryHorsetail(level, isPool(dx, dz) ? g.below() : g);
                    }
                }
            });
            for (int dx = -r + 1; dx < r; dx++) {
                for (int dz = -r + 1; dz < r; dz++) {
                    BlockPos g = center.offset(dx, 0, dz);
                    // 水から 2 以内(水たまり ±1 のまわり ±3)の群れの列だけが株になる
                    if (Math.max(Math.abs(dx), Math.abs(dz)) <= 3 && FloorGrowth.horsetailHeightAt(level.getSeed(), g.getX(), g.getZ()) > 0) {
                        expected++;
                    }
                    BlockPos b = isPool(dx, dz) ? g : g.above();
                    BlockState s = level.getBlockState(b);
                    if (!s.is(ModBlocks.HORSETAIL.get())) {
                        continue;
                    }
                    bottoms.add(b);
                    int h = 0;
                    while (level.getBlockState(b.above(h)).is(ModBlocks.HORSETAIL.get())) h++;
                    boolean water = isPool(dx, dz);
                    int lo = water ? 3 : 2, hi = water ? 5 : 4;
                    if (s.getValue(HorsetailBlock.PART) != HorsetailBlock.Part.BOTTOM || s.getValue(HorsetailBlock.WATERLOGGED) != water
                            || h < lo || h > hi || level.getBlockState(b.above(h - 1)).getValue(HorsetailBlock.PART) != HorsetailBlock.Part.TOP) {
                        bad = "株 " + b.toShortString() + " 高さ " + h + " 水 " + water + " " + s;
                    }
                }
            }
        } catch (RuntimeException e) {
            clear(level, center, r);
            throw e;
        }
        String summary = "株 " + bottoms.size() + "(群れの列 " + expected + ")";
        OvergrownVegetation.LOGGER.info("トクサの淵: {}", summary);
        if (bad != null || bottoms.size() < 5 || bottoms.size() > 13 || bottoms.size() != expected) {
            clear(level, center, r);
            helper.fail("トクサの群れがおかしい: " + summary + (bad != null ? " / " + bad : ""));
            return;
        }
        // 下を壊すと上が崩れる: いちばん高い株の根元を空気に
        BlockPos tallest = bottoms.get(0);
        for (BlockPos b : bottoms) {
            if (height(level, b) > height(level, tallest)) tallest = b;
        }
        BlockPos broken = tallest;
        int brokenHeight = height(level, broken);
        level.setBlock(broken, level.getBlockState(broken).getValue(HorsetailBlock.WATERLOGGED) ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        helper.runAfterDelay(20, () -> {
            boolean collapsed = true;
            for (int i = 1; i < brokenHeight; i++) {
                if (level.getBlockState(broken.above(i)).is(ModBlocks.HORSETAIL.get())) collapsed = false;
            }
            clear(level, center, r);
            if (collapsed) {
                helper.succeed();
            } else {
                helper.fail("根元を壊しても上が残った: " + broken.toShortString() + " 高さ " + brokenHeight);
            }
        });
    }

    /** 水の無い平らな泥炭・草の面には群れが出ない(群れの列でも、水から遠いので)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 40)
    public static void horsetailNotOnDryFlat(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos probe = helper.absolutePos(new BlockPos(0, 190, 0));
        int[] a = FloorGrowth.horsetailAnchor(level.getSeed(), probe.getX() >> 4, probe.getZ() >> 4);
        if (a == null) {
            helper.succeed();
            return;
        }
        BlockPos center = new BlockPos(a[0], probe.getY(), a[1]);
        int r = 6;
        build(level, center, r, false);
        int stems = 0, members = 0;
        try {
            FloorGrowth.runAsJungle(() -> {
                for (int dx = -r + 1; dx < r; dx++) {
                    for (int dz = -r + 1; dz < r; dz++) {
                        FloorGrowth.tryHorsetail(level, center.offset(dx, 0, dz));
                    }
                }
            });
            for (int dx = -r + 1; dx < r; dx++) {
                for (int dz = -r + 1; dz < r; dz++) {
                    BlockPos g = center.offset(dx, 0, dz);
                    if (level.getBlockState(g.above()).is(ModBlocks.HORSETAIL.get())) stems++;
                    if (FloorGrowth.horsetailHeightAt(level.getSeed(), g.getX(), g.getZ()) > 0) members++;
                }
            }
        } finally {
            clear(level, center, r);
        }
        if (members == 0) {
            helper.fail("群れの列が 1 つも無い(試しにならない)");
        } else if (stems > 0) {
            helper.fail("水の無い面にトクサが " + stems + " 本立った(群れの列 " + members + ")");
        } else {
            helper.succeed();
        }
    }

    private static boolean isPool(int dx, int dz) {
        return Math.abs(dx) <= 1 && Math.abs(dz) <= 1;
    }

    private static int height(ServerLevel level, BlockPos bottom) {
        int h = 0;
        while (level.getBlockState(bottom.above(h)).is(ModBlocks.HORSETAIL.get())) h++;
        return h;
    }

    /** 中心のまわり ±r に石 → 泥炭の面。{@code pool} なら真ん中 3×3 を深さ 1 の水たまり(底は泥炭の水たまりの底)。 */
    private static void build(ServerLevel level, BlockPos center, int r, boolean pool) {
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                BlockPos g = center.offset(dx, 0, dz);
                level.setBlock(g.below(2), Blocks.STONE.defaultBlockState(), flags);
                if (pool && isPool(dx, dz)) {
                    level.setBlock(g.below(), ModBlocks.PEAT_POOL_BOTTOM.get().defaultBlockState(), flags);
                    level.setBlock(g, Blocks.WATER.defaultBlockState(), flags);
                } else {
                    level.setBlock(g.below(), Blocks.STONE.defaultBlockState(), flags);
                    level.setBlock(g, (dx + dz & 1) == 0 ? ModBlocks.PEAT.get().defaultBlockState() : ModBlocks.OVERGROWN_GRASS_BLOCK.get().defaultBlockState(), flags);
                }
            }
        }
    }

    private static void clear(ServerLevel level, BlockPos center, int r) {
        clearBox(level, center.offset(-r, -2, -r), center.offset(r, 7, r));
    }

    // ======================= 32 板根・33 中層の房 =======================

    @GameTest(template = "gametest_empty7", timeoutTicks = 40)
    public static void cordaitesHasButtressesAndMidTufts(GameTestHelper helper) {
        growCordaites(helper, "overgrown_tree_main", 200, 1);
    }

    @GameTest(template = "gametest_empty7", timeoutTicks = 40)
    public static void tallCordaitesHasThreeStepButtresses(GameTestHelper helper) {
        growCordaites(helper, "overgrown_tree_tall", 252, 2);
    }

    private static final int TREE_REACH = 12, TREE_TALL = 48;

    /** 平らな台に木を 3 本(種を変えて)順に生やし、板根 4 枚・段が下がること・房 2 つ(高さ 4〜6 割、林冠の塊の下 3 段に無い)を見る。 */
    private static void growCordaites(GameTestHelper helper, String feature, int sky, int width) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(3, sky, 3));
        ConfiguredFeature<?, ?> configured = level.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE)
                .get(ResourceKey.create(Registries.CONFIGURED_FEATURE, ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, feature)));
        if (configured == null || !(configured.config() instanceof ForcedTreeFeature.Config config)) {
            helper.fail("configured_feature " + feature + " が無い");
            return;
        }
        int pairs = 0;
        List<String> log = new ArrayList<>();
        String bad = null;
        try {
            for (int seed = 0; seed < 8 && bad == null; seed++) {
                clearBox(level, origin.offset(-TREE_REACH, -2, -TREE_REACH), origin.offset(TREE_REACH, TREE_TALL, TREE_REACH));
                platform(level, origin, TREE_REACH);
                ModFeatures.FORCED_TREE.get().place(new FeaturePlaceContext<>(Optional.empty(), level,
                        level.getChunkSource().getGenerator(), RandomSource.create(9100 + seed * 77L + width), origin, config));
                int trunk = 0;
                while (level.getBlockState(origin.above(trunk)).is(ModBlocks.OVERGROWN_LOG.get())) trunk++;
                // 板根: 4 面それぞれ、面の列のどれかから 1 段目 → 先へ低くなる
                int fins = 0;
                for (Direction face : Direction.Plane.HORIZONTAL) {
                    String why = checkFin(level, origin, width, face, config.rootLength());
                    if (why == null) fins++;
                    else log.add(face + ": " + why);
                }
                // 房: 立ち上がる板の下が葉(芯)、芯の横に芯へ腕を出した枝の継ぎ目(腕が芯に刺さる)、芯の下に垂れる板
                List<Integer> tufts = new ArrayList<>();
                String tuftBad = null;
                BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
                for (int dy = 0; dy <= TREE_TALL; dy++) {
                    for (int dx = -TREE_REACH; dx <= TREE_REACH; dx++) {
                        for (int dz = -TREE_REACH; dz <= TREE_REACH; dz++) {
                            p.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                            BlockState s = level.getBlockState(p);
                            if (s.is(ModBlocks.OVERGROWN_LEAVES_TUFT.get()) && s.getValue(SigillariaFringeBlock.FACING) == Direction.DOWN
                                    && isTuftCore(level, p.below())) {
                                int armY = dy - 1;
                                tufts.add(armY);
                                if (armY < Math.round(trunk * 0.4) || armY > Math.round(trunk * 0.6)) {
                                    tuftBad = "房の高さ " + armY + " が幹 " + trunk + " の 4〜6 割の外";
                                }
                                BlockPos core = p.below();
                                BlockState hang = level.getBlockState(core.below());
                                if (!hang.is(ModBlocks.OVERGROWN_LEAVES_TUFT.get()) || hang.getValue(SigillariaFringeBlock.FACING) != Direction.UP) {
                                    tuftBad = "房 " + core.toShortString() + " の下に垂れる板が無い";
                                }
                                for (int uy = 1; uy <= 3; uy++) {
                                    for (int ux = -ForcedTreeFeature.MID_TUFT_SPAN; ux <= ForcedTreeFeature.MID_TUFT_SPAN; ux++) {
                                        for (int uz = -ForcedTreeFeature.MID_TUFT_SPAN; uz <= ForcedTreeFeature.MID_TUFT_SPAN; uz++) {
                                            BlockState u = level.getBlockState(core.offset(ux, uy, uz));
                                            if (u.is(ModBlocks.OVERGROWN_LEAVES.get()) && !isTuftCore(level, core.offset(ux, uy, uz))) {
                                                tuftBad = "房 " + core.toShortString() + " の上 " + uy + " 段に林冠の葉";
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (tufts.size() == 2) pairs++;
                log.add("種 " + seed + ": 幹 " + trunk + "・板根 " + fins + "・房 " + tufts);
                if (fins != 4) {
                    bad = "板根が 4 枚でない";
                } else if (tufts.size() > 2) {
                    // 2 つが目当て。帯の上に低い枝の林冠の塊が掛かる木(低い木)では 1 つか 0 になる(塊の下には置かないのが先)
                    bad = "房が 3 つ以上";
                } else if (tuftBad != null) {
                    bad = tuftBad;
                }
            }
        } finally {
            clearBox(level, origin.offset(-TREE_REACH, -2, -TREE_REACH), origin.offset(TREE_REACH, TREE_TALL, TREE_REACH));
        }
        OvergrownVegetation.LOGGER.info("{}: {}", feature, log);
        if (bad == null && pairs == 0) {
            bad = "房が 2 つの木が 1 本も無い";
        }
        if (bad != null) {
            helper.fail(feature + ": " + bad + " " + log);
        } else {
            helper.succeed();
        }
    }

    /** 房の芯の葉(水平の隣に、芯へ腕を出した枝の継ぎ目がある)か。 */
    private static boolean isTuftCore(ServerLevel level, BlockPos pos) {
        if (!level.getBlockState(pos).is(ModBlocks.OVERGROWN_LEAVES.get())) {
            return false;
        }
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockState arm = level.getBlockState(pos.relative(d));
            if (arm.is(ModBlocks.OVERGROWN_BRANCH.get()) && arm.getValue(PipeBlock.PROPERTY_BY_DIRECTION.get(d.getOpposite()))) {
                return true;
            }
        }
        return false;
    }

    /** 面 {@code face} の板根を見る(良ければ null)。面の列は 2×2 なら 2 本のどちらか。 */
    private static String checkFin(ServerLevel level, BlockPos origin, int width, Direction face, int length) {
        String why = "1 段目が無い";
        for (int along = 0; along < width; along++) {
            int tx = origin.getX() + (face == Direction.EAST ? width - 1 : face == Direction.WEST ? 0 : along);
            int tz = origin.getZ() + (face == Direction.SOUTH ? width - 1 : face == Direction.NORTH ? 0 : along);
            BlockPos first = new BlockPos(tx, origin.getY(), tz).relative(face);
            BlockState s = level.getBlockState(first);
            if (!s.is(ModBlocks.CORDAITES_BUTTRESS.get())) {
                continue;
            }
            if (s.getValue(CordaitesButtressBlock.FACING) != face) {
                return "向きが違う " + s;
            }
            double prev = Double.MAX_VALUE;
            for (int k = 0; k < length; k++) {
                BlockPos cell = first.relative(face, k);
                BlockState c = level.getBlockState(cell);
                if (!c.is(ModBlocks.CORDAITES_BUTTRESS.get())) {
                    return k + " 段目が無い";
                }
                double top = c.getShape(level, cell).max(Direction.Axis.Y);
                if (top >= prev) {
                    return k + " 段目が低くならない";
                }
                prev = top;
            }
            CordaitesButtressBlock.Part climb = width > 1 ? CordaitesButtressBlock.Part.CLIMB : CordaitesButtressBlock.Part.CLIMB_TOP;
            BlockState up = level.getBlockState(first.above());
            if (!up.is(ModBlocks.CORDAITES_BUTTRESS.get()) || up.getValue(CordaitesButtressBlock.PART) != climb) {
                return "幹を這い上がる箱が無い";
            }
            return null;
        }
        return why;
    }

    // ======================= 32 スティグマリア =======================

    @GameTest(template = "gametest_empty7", timeoutTicks = 40)
    public static void sigillariaHasFiveStigmaria(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(3, 305, 3));
        ConfiguredFeature<?, ?> configured = level.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE)
                .get(ResourceKey.create(Registries.CONFIGURED_FEATURE, ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, "sigillaria_tree")));
        if (configured == null || !(configured.config() instanceof SigillariaTreeFeature.Config base)) {
            helper.fail("configured_feature sigillaria_tree が無い");
            return;
        }
        int reach = 12, tall = 40;
        List<String> log = new ArrayList<>();
        String bad = null;
        try {
            for (int seed = 0; seed < 2 && bad == null; seed++) {
                clearBox(level, origin.offset(-reach, -2, -reach), origin.offset(reach, tall, reach));
                platform(level, origin, reach);
                ModFeatures.SIGILLARIA_TREE.get().place(new FeaturePlaceContext<>(Optional.empty(), level,
                        level.getChunkSource().getGenerator(), RandomSource.create(5300 + seed), origin, base.fixedForTests(seed == 1)));
                int roots = 0;
                for (int d = 0; d < 8; d++) {
                    int[] st = StigmariaBlock.step(d);
                    int stages = 0;
                    for (int k = 0; k < 3; k++) {
                        // 真っすぐも斜めも幹のすぐ隣から(根元の十字は 11-02 に外した)
                        BlockState s = level.getBlockState(origin.offset(st[0] * (1 + k), 0, st[1] * (1 + k)));
                        if (s.is(ModBlocks.STIGMARIA.get()) && s.getValue(StigmariaBlock.DIRECTION) == d && s.getValue(StigmariaBlock.STAGE) == k) {
                            stages++;
                        }
                    }
                    if (stages == 3) roots++;
                    else if (stages > 0) bad = "向き " + d + " の根が途中で切れた(" + stages + " 段)";
                }
                log.add("種 " + seed + ": 根 " + roots);
                if (roots != base.stigmariaCount() && bad == null) {
                    bad = "根が " + base.stigmariaCount() + " 本でない";
                }
            }
        } finally {
            clearBox(level, origin.offset(-reach, -2, -reach), origin.offset(reach, tall, reach));
        }
        OvergrownVegetation.LOGGER.info("スティグマリア: {}", log);
        if (bad != null) {
            helper.fail(bad + " " + log);
        } else {
            helper.succeed();
        }
    }

    // ======================= 32 根の段差(11-02 浮きの直し) =======================

    private static final int STEP_R = 5;

    /**
     * 幹の根元(地面 = origin の 1 段下)のまわりの 4 面に段差を作り、板根だけを置く(1 列の幹・長さ 2・4 面)。
     * 北 = 2 マス目から 1 段下がる(落ちる部品でつながる)、南 = 2 段下がる(止まる)、東 = 2 マス目が 1 段上がる(止まる)、西 = 2 マス目から水(止まる)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 40)
    public static void buttressDropsOneStepAndStopsElsewhere(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(3, 150, 3));
        ForcedTreeFeature.Config config = forcedConfig(helper, "overgrown_tree_main");
        if (config == null) {
            return;
        }
        String bad;
        try {
            stepTerrain(level, origin, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST);
            ForcedTreeFeature.placeButtressesForTests(level, RandomSource.create(41), origin, 1, config);
            bad = expectButtress(level, origin.north(), Direction.NORTH, CordaitesButtressBlock.Part.NEAR);
            if (bad == null) bad = expectButtress(level, origin.north(2).below(), Direction.NORTH, CordaitesButtressBlock.Part.DROP);
            if (bad == null) bad = expectButtress(level, origin.above().north(), Direction.NORTH, CordaitesButtressBlock.Part.CLIMB_TOP);
            for (Direction stop : List.of(Direction.SOUTH, Direction.EAST, Direction.WEST)) {
                if (bad == null) bad = expectButtress(level, origin.relative(stop), stop, CordaitesButtressBlock.Part.NEAR);
                for (int dy = -2; dy <= 1 && bad == null; dy++) {
                    if (level.getBlockState(origin.relative(stop, 2).above(dy)).is(ModBlocks.CORDAITES_BUTTRESS.get())) {
                        bad = stop + " の 2 マス目(高さ " + dy + ")に板根がある(止まるはず)";
                    }
                }
            }
        } finally {
            clearBox(level, origin.offset(-STEP_R, -3, -STEP_R), origin.offset(STEP_R, 3, STEP_R));
        }
        finish(helper, bad);
    }

    /** 幹に接する 1 マス目の地面が幹の根元より 1 段低い面(北)には板根が付かない。ほかの 3 面(平ら)には付く。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 40)
    public static void buttressSkipsFaceLowerThanTrunkBase(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(3, 160, 3));
        ForcedTreeFeature.Config config = forcedConfig(helper, "overgrown_tree_main");
        if (config == null) {
            return;
        }
        String bad = null;
        try {
            platform(level, origin, STEP_R);
            int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
            for (int k = 1; k < STEP_R; k++) {
                level.setBlock(origin.north(k).below(), Blocks.AIR.defaultBlockState(), flags);
            }
            ForcedTreeFeature.placeButtressesForTests(level, RandomSource.create(43), origin, 1, config);
            for (int k = 1; k <= 2; k++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (level.getBlockState(origin.north(k).above(dy)).is(ModBlocks.CORDAITES_BUTTRESS.get())) {
                        bad = "北(根元より低い面)の " + k + " マス目に板根がある";
                    }
                }
            }
            for (Direction flat : List.of(Direction.SOUTH, Direction.EAST, Direction.WEST)) {
                if (bad == null) bad = expectButtress(level, origin.relative(flat), flat, CordaitesButtressBlock.Part.NEAR);
                if (bad == null) bad = expectButtress(level, origin.relative(flat, 2), flat, CordaitesButtressBlock.Part.TIP);
            }
        } finally {
            clearBox(level, origin.offset(-STEP_R, -3, -STEP_R), origin.offset(STEP_R, 3, STEP_R));
        }
        finish(helper, bad);
    }

    /**
     * スティグマリアを 8 向きすべてに置く。真っすぐの北 = 2 マス目から 1 段下がる(落ちる部品でつながり、3 マス目は下の段で続く)、
     * 南 = 2 段下がる・東 = 上がる・西 = 水(どれも 1 マス目だけ)、斜めの北東 = 2 マス目から 1 段下がる(斜めは止まる)、
     * 斜めの南東 = 1 マス目から低い(付かない)、南西・北西 = 平ら(3 マス)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 40)
    public static void stigmariaDropsOneStepAndStopsElsewhere(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(3, 168, 3));
        ConfiguredFeature<?, ?> configured = level.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE)
                .get(ResourceKey.create(Registries.CONFIGURED_FEATURE, ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, "sigillaria_tree")));
        if (configured == null || !(configured.config() instanceof SigillariaTreeFeature.Config base)) {
            helper.fail("configured_feature sigillaria_tree が無い");
            return;
        }
        String bad = null;
        try {
            stepTerrain(level, origin, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST);
            int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
            for (int k = 1; k < STEP_R; k++) {
                if (k >= 2) level.setBlock(origin.offset(k, -1, -k), Blocks.AIR.defaultBlockState(), flags);  // 北東
                level.setBlock(origin.offset(k, -1, k), Blocks.AIR.defaultBlockState(), flags);               // 南東
            }
            SigillariaTreeFeature.placeStigmariaForTests(level, RandomSource.create(47), origin, base.fixedForTests(false, 8));
            // 北: 段 0(平ら)→ 段 1(落ちる)→ 段 2(下の段)
            bad = expectStigmaria(level, origin.north(), 0, 0, false);
            if (bad == null) bad = expectStigmaria(level, origin.north(2).below(), 0, 1, true);
            if (bad == null) bad = expectStigmaria(level, origin.north(3).below(), 0, 2, false);
            // 南・東・西・北東: 段 0 だけ
            int[][] stops = {{4, 0, 1}, {2, 1, 0}, {6, -1, 0}, {1, 1, -1}};
            for (int[] st : stops) {
                if (bad == null) bad = expectStigmaria(level, origin.offset(st[1], 0, st[2]), st[0], 0, false);
                for (int k = 2; k <= 3 && bad == null; k++) {
                    for (int dy = -2; dy <= 1; dy++) {
                        if (level.getBlockState(origin.offset(st[1] * k, dy, st[2] * k)).is(ModBlocks.STIGMARIA.get())) {
                            bad = "向き " + st[0] + " の " + k + " マス目(高さ " + dy + ")に根がある(止まるはず)";
                        }
                    }
                }
            }
            // 南東: 付かない
            for (int k = 1; k <= 3 && bad == null; k++) {
                for (int dy = -1; dy <= 0; dy++) {
                    if (level.getBlockState(origin.offset(k, dy, k)).is(ModBlocks.STIGMARIA.get())) {
                        bad = "南東(根元より低い)の " + k + " マス目に根がある";
                    }
                }
            }
            // 南西・北西: 平らに 3 マス
            for (int d : new int[]{5, 7}) {
                int[] s = StigmariaBlock.step(d);
                for (int k = 0; k < 3 && bad == null; k++) {
                    bad = expectStigmaria(level, origin.offset(s[0] * (k + 1), 0, s[1] * (k + 1)), d, k, false);
                }
            }
        } finally {
            clearBox(level, origin.offset(-STEP_R, -3, -STEP_R), origin.offset(STEP_R, 3, STEP_R));
        }
        finish(helper, bad);
    }

    /** 台の上に、{@code down1} は 2 マス目から 1 段下がり、{@code down2} は 2 段下がり、{@code up} は 2 マス目が 1 段上がり、{@code water} は 2 マス目から水。 */
    private static void stepTerrain(ServerLevel level, BlockPos origin, Direction down1, Direction down2, Direction up, Direction water) {
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        platform(level, origin, STEP_R);
        for (int k = 2; k < STEP_R; k++) {
            level.setBlock(origin.relative(down1, k).below(), Blocks.AIR.defaultBlockState(), flags);
            level.setBlock(origin.relative(down2, k).below(), Blocks.AIR.defaultBlockState(), flags);
            level.setBlock(origin.relative(down2, k).below(2), Blocks.AIR.defaultBlockState(), flags);
            level.setBlock(origin.relative(down2, k).below(3), ModBlocks.OVERGROWN_DIRT.get().defaultBlockState(), flags);
            level.setBlock(origin.relative(water, k).below(), Blocks.WATER.defaultBlockState(), flags);
        }
        level.setBlock(origin.relative(up, 2), ModBlocks.OVERGROWN_GRASS_BLOCK.get().defaultBlockState(), flags);
    }

    private static ForcedTreeFeature.Config forcedConfig(GameTestHelper helper, String feature) {
        ConfiguredFeature<?, ?> configured = helper.getLevel().registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE)
                .get(ResourceKey.create(Registries.CONFIGURED_FEATURE, ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, feature)));
        if (configured == null || !(configured.config() instanceof ForcedTreeFeature.Config config)) {
            helper.fail("configured_feature " + feature + " が無い");
            return null;
        }
        return config;
    }

    private static String expectButtress(ServerLevel level, BlockPos pos, Direction facing, CordaitesButtressBlock.Part part) {
        BlockState s = level.getBlockState(pos);
        if (!s.is(ModBlocks.CORDAITES_BUTTRESS.get()) || s.getValue(CordaitesButtressBlock.FACING) != facing
                || s.getValue(CordaitesButtressBlock.PART) != part) {
            return pos.toShortString() + " に " + facing + "・" + part + " の板根が無い(" + s + ")";
        }
        return null;
    }

    private static String expectStigmaria(ServerLevel level, BlockPos pos, int direction, int stage, boolean drop) {
        BlockState s = level.getBlockState(pos);
        if (!s.is(ModBlocks.STIGMARIA.get()) || s.getValue(StigmariaBlock.DIRECTION) != direction
                || s.getValue(StigmariaBlock.STAGE) != stage || s.getValue(StigmariaBlock.DROP) != drop) {
            return pos.toShortString() + " に向き " + direction + "・段 " + stage + (drop ? "・落ちる" : "") + " の根が無い(" + s + ")";
        }
        return null;
    }

    private static void finish(GameTestHelper helper, String bad) {
        if (bad != null) {
            helper.fail(bad);
        } else {
            helper.succeed();
        }
    }

    /** {@code origin} の 1 段下を中心に ±r の土の台(上面 = 汚染した草)。 */
    private static void platform(ServerLevel level, BlockPos origin, int r) {
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                level.setBlock(origin.offset(dx, -2, dz), ModBlocks.OVERGROWN_DIRT.get().defaultBlockState(), flags);
                level.setBlock(origin.offset(dx, -1, dz), ModBlocks.OVERGROWN_GRASS_BLOCK.get().defaultBlockState(), flags);
            }
        }
    }

    private static void clearBox(ServerLevel level, BlockPos from, BlockPos to) {
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        for (BlockPos p : BlockPos.betweenClosed(from, to)) {
            if (!level.getBlockState(p).isAir()) {
                level.setBlock(p, Blocks.AIR.defaultBlockState(), flags);
            }
        }
    }

    private GroundRestGameTests() {
    }
}
