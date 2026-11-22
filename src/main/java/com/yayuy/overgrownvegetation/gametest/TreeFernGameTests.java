package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.SigillariaFringeBlock;
import com.yayuy.overgrownvegetation.block.TreeFernTrunkBlock;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.ModFeatures;
import com.yayuy.overgrownvegetation.worldgen.TreeFernFeature;
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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * 木生シダ(2026-11-01): A(一本幹 + 傘)と C(株立ち)が幹・根元・芯・板(垂れる・横・ゼンマイ)を持ち、高さが中層(8〜15 前後)に収まる。
 * 封印木と同じく、テスト場の {@value #SKY} 段上の空中に生やし、確かめたら木生シダのブロックだけ空気に戻す。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class TreeFernGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(TreeFernGameTests.class);
        }

        private Registration() {
        }
    }

    private static final int SKY = 40;
    private static final int REACH = 7, TALL = 20;

    @GameTest(template = "gametest_empty7", timeoutTicks = 40)
    public static void singleTreeFernHasTrunkCoreAndFronds(GameTestHelper helper) {
        growAndCheck(helper, false);
    }

    @GameTest(template = "gametest_empty7", timeoutTicks = 40)
    public static void clumpTreeFernHasTwoTrunks(GameTestHelper helper) {
        growAndCheck(helper, true);
    }

    private record Counts(boolean placed, int trunk, int base, int leaves, int hang, int rise, int side, int corner, int columns, int height) {
        @Override
        public String toString() {
            return "幹 " + trunk + "(根元 " + base + ")・芯 " + leaves + "・垂れ " + hang + "・ゼンマイ " + rise + "・横 " + side + "・角 " + corner
                    + "・幹の列 " + columns + "・高さ " + height;
        }
    }

    private static void growAndCheck(GameTestHelper helper, boolean clump) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(3, SKY, 3));
        ResourceKey<ConfiguredFeature<?, ?>> key = ResourceKey.create(Registries.CONFIGURED_FEATURE,
                ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, "tree_fern"));
        ConfiguredFeature<?, ?> configured = level.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE).get(key);
        if (configured == null || !(configured.config() instanceof TreeFernFeature.Config base)) {
            helper.fail("configured_feature tree_fern が無い");
            return;
        }
        long seed = level.getGameTime() * 31 + origin.asLong();
        Counts top = grow(level, origin, base.fixed(clump, true), seed);
        // 角を全部の段に置いたときとの比べ(報告用。同じ種)
        Counts all = grow(level, origin, base.fixed(clump, false), seed);
        OvergrownVegetation.LOGGER.info("木生シダ {}: 角は頂だけ = {} / 角を全部 = {}", clump ? "C" : "A", top, all);

        int minTrunk = clump ? base.clumpTallMinHeight() + base.clumpShortMinHeight() : base.singleMinHeight();
        String summary = (clump ? "C" : "A") + ": " + top;
        if (!top.placed()) {
            helper.fail("生えなかった(" + summary + ")");
        } else if (top.trunk() < minTrunk || top.base() < 3 || top.leaves() < 5 || top.hang() < 1 || top.rise() < 1 || top.side() < 4) {
            helper.fail("揃っていない: " + summary);
        } else if (top.columns() != (clump ? 2 : 1)) {
            helper.fail("幹の本数が違う: " + summary);
        } else if (clump && top.rise() < 2) {
            helper.fail("株立ちの 2 つの冠にゼンマイが無い: " + summary);
        } else if (top.height() < 9 || top.height() > 16) {
            helper.fail("高さが中層に無い: " + summary);
        } else if (all.corner() < top.corner()) {
            helper.fail("角を全部置いた方が少ない: " + all + " / " + top);
        } else {
            helper.succeed();
        }
    }

    private static Counts grow(ServerLevel level, BlockPos origin, TreeFernFeature.Config config, long seed) {
        boolean placed = false;
        int trunk = 0, base = 0, leaves = 0, hang = 0, rise = 0, side = 0, corner = 0, maxY = origin.getY();
        Set<Long> columns = new HashSet<>();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        try {
            placed = ModFeatures.TREE_FERN.get().place(new FeaturePlaceContext<>(Optional.empty(), level,
                    level.getChunkSource().getGenerator(), RandomSource.create(seed), origin, config));
            for (int dy = 0; dy <= TALL; dy++) {
                for (int dx = -REACH; dx <= REACH; dx++) {
                    for (int dz = -REACH; dz <= REACH; dz++) {
                        p.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                        BlockState s = level.getBlockState(p);
                        if (s.is(ModBlocks.TREE_FERN_TRUNK.get())) {
                            trunk++;
                            if (s.getValue(TreeFernTrunkBlock.BASE)) base++;
                            if (dy == 4) columns.add(p.asLong()); // 根元の張り出し(3 段まで)より上の列 = 幹の本数
                        } else if (s.is(ModBlocks.TREE_FERN_LEAVES.get())) {
                            leaves++;
                        } else if (s.is(ModBlocks.TREE_FERN_FROND.get())) {
                            Direction f = s.getValue(SigillariaFringeBlock.FACING);
                            if (s.getValue(SigillariaFringeBlock.CORNER)) corner++;
                            else if (f == Direction.UP) hang++;
                            else if (f == Direction.DOWN) rise++;
                            else side++;
                        } else {
                            continue;
                        }
                        maxY = Math.max(maxY, p.getY());
                    }
                }
            }
        } finally {
            for (int dy = 0; dy <= TALL; dy++) {
                for (int dx = -REACH; dx <= REACH; dx++) {
                    for (int dz = -REACH; dz <= REACH; dz++) {
                        p.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                        BlockState s = level.getBlockState(p);
                        if (s.is(ModBlocks.TREE_FERN_TRUNK.get()) || s.is(ModBlocks.TREE_FERN_LEAVES.get()) || s.is(ModBlocks.TREE_FERN_FROND.get())) {
                            level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                        }
                    }
                }
            }
        }
        return new Counts(placed, trunk, base, leaves, hang, rise, side, corner, columns.size(), maxY - origin.getY() + 1);
    }
}
