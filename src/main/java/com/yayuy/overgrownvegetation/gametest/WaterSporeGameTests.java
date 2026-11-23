package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.BlightFernRootedBlock;
import com.yayuy.overgrownvegetation.corruption.CorruptionSpreader;
import com.yayuy.overgrownvegetation.corruption.OvergrownLayerData;
import com.yayuy.overgrownvegetation.data.SporeFieldTable;
import com.yayuy.overgrownvegetation.entity.BlightFernEntity;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.ModEntities;
import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.spore.field.PrevailingWind;
import com.yayuy.overgrownvegetation.spore.field.SporeField;
import com.yayuy.overgrownvegetation.spore.field.SporeFieldServer;
import com.yayuy.overgrownvegetation.spore.field.SporeSprouts;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import com.yayuy.overgrownvegetation.port.entity.PortAttributes;

/**
 * 水中の汚染(2026-10-24 追記 18【決定】地上も海も見境なく広がる): 海のチャンクも降り積もりから発芽する・海の株は空気への放出が小さい・
 * 萌芽体は沈んで水底で根付く(根付いた姿は水の中の姿)・水の中の点から水底が汚染される。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class WaterSporeGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(WaterSporeGameTests.class);
        }

        private Registration() {
        }
    }

    private WaterSporeGameTests() {
    }

    private static Holder<Biome> biome(GameTestHelper helper, net.minecraft.resources.ResourceKey<Biome> key) {
        return helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(key);
    }

    /** x ≥ 5 が海(土地の決まりはゲームと同じ BiomeGround の式)、x < 5 が平原の土地。 */
    private static SporeField.Ground coast(Holder<Biome> sea, Holder<Biome> land) {
        return new SporeField.Ground() {
            @Override
            public float germFactor(int cx, int cz) {
                return SporeFieldServer.BiomeGround.germOf(cx >= 5 ? sea : land);
            }

            @Override
            public boolean wet(int cx, int cz) {
                return SporeFieldServer.BiomeGround.wetOf(cx >= 5 ? sea : land);
            }

            @Override
            public float airEmitFactor(int cx, int cz) {
                return SporeFieldServer.BiomeGround.airEmitOf(cx >= 5 ? sea : land);
            }
        };
    }

    /** 海のチャンクも降り積もりから発芽する(前は海 = 発芽しない)。陸の汚染から風下の海へ、2 日で芽が出る。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200)
    public static void oceanChunksSprout(GameTestHelper helper) {
        Holder<Biome> ocean = biome(helper, Biomes.OCEAN), deep = biome(helper, Biomes.DEEP_OCEAN), plains = biome(helper, Biomes.PLAINS);
        helper.assertTrue(SporeFieldServer.BiomeGround.germOf(ocean) == 1.0F, "海が発芽しない: " + SporeFieldServer.BiomeGround.germOf(ocean));
        helper.assertTrue(SporeFieldServer.BiomeGround.germOf(deep) > 0.0F && SporeFieldServer.BiomeGround.germOf(deep) < 1.0F,
                "深い海の発芽の速さが 0〜1 の間でない: " + SporeFieldServer.BiomeGround.germOf(deep));
        helper.assertTrue(SporeFieldServer.BiomeGround.wetOf(ocean), "海が湿った所でない");
        SporeField.Params p = SporeFieldTable.gameDefaults();
        SporeField f = new SporeField(42L, p);
        f.ground = coast(ocean, plains);
        for (int x = 0; x < 5; x++) for (int z = 0; z < 5; z++) f.setCorrupted(x, z, -10L * PrevailingWind.DAY);
        PrevailingWind.Sample wind = new PrevailingWind.Sample();
        wind.dirX = 1;
        wind.dirZ = 0;
        wind.strength = 1;
        for (int k = 1; k <= 2 * PrevailingWind.DAY / p.stepTicks; k++) {
            long t = (long) k * p.stepTicks;
            f.step(new SporeField.Conditions(t, false, false, false), wind);
        }
        int sea = 0;
        for (int z = -10; z < 15; z++) for (int x = 5; x < 40; x++) if (f.isCorrupted(x, z)) sea++;
        if (sea == 0) {
            helper.fail("風下の海のチャンクが発芽しない");
            return;
        }
        helper.succeed();
    }

    /** 海の升の株は空気への放出が小さい(水の下。仮 0.3 倍)。同じ育ちの陸の升より浮遊が少ない。 */
    @GameTest(template = "gametest_empty7")
    public static void oceanSproutsEmitLessAir(GameTestHelper helper) {
        Holder<Biome> ocean = biome(helper, Biomes.OCEAN), plains = biome(helper, Biomes.PLAINS);
        helper.assertTrue(SporeFieldServer.BiomeGround.airEmitOf(ocean) < 1.0F, "海の放出が小さくない");
        SporeField.Params p = SporeFieldTable.gameDefaults();
        SporeField f = new SporeField(7L, p);
        f.ground = coast(ocean, plains);
        f.setCorrupted(-100, 0, -10L * PrevailingWind.DAY); // 陸
        f.setCorrupted(100, 0, -10L * PrevailingWind.DAY); // 海
        PrevailingWind.Sample calm = new PrevailingWind.Sample();
        calm.dirX = 1;
        calm.dirZ = 0;
        calm.strength = 0;
        f.step(new SporeField.Conditions(p.stepTicks, false, false, false), calm);
        double land = f.air(-100, 0), sea = f.air(100, 0);
        if (!(land > 0) || !(sea < land * 0.6D)) {
            helper.fail("海の株の放出が小さくない: 陸 " + land + " / 海 " + sea);
            return;
        }
        helper.succeed();
    }

    /** 水たまりを作る(床は土、深さ 3)。 */
    private static void pool(GameTestHelper helper) {
        for (int x = 0; x <= 6; x++)
            for (int z = 0; z <= 6; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.DIRT); // 砂は下が空気だと落ちる
                boolean wall = x == 0 || z == 0 || x == 6 || z == 6;
                for (int y = 2; y <= 4; y++) helper.setBlock(new BlockPos(x, y, z), wall ? Blocks.GLASS : Blocks.WATER);
            }
    }

    /** 根付いた姿を水の中に置くと水の中の姿になる(水が抜けない)。 */
    @GameTest(template = "gametest_empty7", batch = "water_spore")
    public static void rootedBlockWaterlogsInWater(GameTestHelper helper) {
        pool(helper);
        BlockPos rel = new BlockPos(3, 2, 3);
        BlockPos abs = helper.absolutePos(rel);
        helper.assertTrue(SporeSprouts.placeRootedAt(helper.getLevel(), abs, 0.0F, false), "水底に根付いた姿を置けない");
        BlockState s = helper.getLevel().getBlockState(abs);
        helper.assertTrue(s.is(ModBlocks.BLIGHT_FERN_ROOTED.get()), "根付いた姿でない: " + s);
        helper.assertTrue(s.getValue(BlightFernRootedBlock.WATERLOGGED), "水の中の姿でない");
        helper.assertTrue(helper.getLevel().getFluidState(abs).is(FluidTags.WATER), "根付いた所の水が抜けた");
        helper.succeed();
    }

    /** 水に落ちた萌芽体は浮かずに沈み、水底で根付く(根付いた姿は水の中の姿)。 */
    @GameTest(template = "gametest_empty7", batch = "water_spore", timeoutTicks = 400)
    public static void sproutlingSinksAndRootsOnSeabed(GameTestHelper helper) {
        pool(helper);
        BlightFernEntity fern = helper.spawn(ModEntities.BLIGHT_FERN.get(), new BlockPos(3, 4, 3));
        fern.rootSoonForTests();
        double[] maxY = {fern.getY()};
        helper.onEachTick(() -> {
            if (!fern.isRemoved()) maxY[0] = Math.max(maxY[0], fern.getY());
        });
        helper.succeedWhen(() -> {
            BlockPos at = null;
            for (int x = 1; x <= 5 && at == null; x++)
                for (int z = 1; z <= 5 && at == null; z++)
                    if (helper.getBlockState(new BlockPos(x, 2, z)).is(ModBlocks.BLIGHT_FERN_ROOTED.get())) at = new BlockPos(x, 2, z);
            helper.assertTrue(at != null, "水底に根付いていない(萌芽体: 消えた " + fern.isRemoved() + "・足が着いた " + fern.onGround()
                    + "・水の中 " + fern.isInWater() + "・y " + String.format("%.2f", fern.getY() - helper.absolutePos(BlockPos.ZERO).getY())
                    + "・場 " + SporeFieldServer.enabled() + "・根付き中 " + fern.isRooting() + "・" + fern.rootDebugForTests() + ")");
            helper.assertTrue(helper.getBlockState(at).getValue(BlightFernRootedBlock.WATERLOGGED), "水底の株が水の中の姿でない");
            helper.assertTrue(maxY[0] < helper.absolutePos(new BlockPos(3, 4, 3)).getY() + 0.6D, "浮き上がった: 最高 " + maxY[0]);
        });
    }

    /**
     * 水面の膜(藻の膜)の上は根付く足場にならない(2026-11-08 ユーザー報告「萌芽体が水面に設置される」。Fable の見立て = 膜の上に根付く)。
     * 膜の上の 1 マスは根付く所に選ばれず、膜の所に出した萌芽体は沈んで水底(水の中の姿)に根付く。膜の高さ・その上には株ができない。
     */
    @GameTest(template = "gametest_empty7", batch = "water_spore", timeoutTicks = 400)
    public static void sproutlingDoesNotRootOnSurfaceFilm(GameTestHelper helper) {
        pool(helper);
        for (int x = 1; x <= 5; x++)
            for (int z = 1; z <= 5; z++) helper.setBlock(new BlockPos(x, 5, z), ModBlocks.ALGAE_FILM.get());
        helper.assertBlockPresent(ModBlocks.ALGAE_FILM.get(), new BlockPos(3, 5, 3));
        BlockPos filmAbs = helper.absolutePos(new BlockPos(3, 5, 3));
        helper.assertTrue(SporeSprouts.rootedSpot(helper.getLevel(), filmAbs) == null,
                "膜の所・その上が根付く所に選ばれた: " + SporeSprouts.rootedSpot(helper.getLevel(), filmAbs));
        BlightFernEntity fern = helper.spawn(ModEntities.BLIGHT_FERN.get(), new BlockPos(3, 5, 3));
        fern.rootSoonForTests();
        helper.succeedWhen(() -> {
            for (int x = 1; x <= 5; x++)
                for (int z = 1; z <= 5; z++)
                    for (int y = 3; y <= 6; y++)
                        helper.assertBlockNotPresent(ModBlocks.BLIGHT_FERN_ROOTED.get(), new BlockPos(x, y, z));
            BlockPos at = null;
            for (int x = 1; x <= 5 && at == null; x++)
                for (int z = 1; z <= 5 && at == null; z++)
                    if (helper.getBlockState(new BlockPos(x, 2, z)).is(ModBlocks.BLIGHT_FERN_ROOTED.get())) at = new BlockPos(x, 2, z);
            helper.assertTrue(at != null, "水底に根付いていない(萌芽体 y " + String.format("%.2f", fern.getY() - helper.absolutePos(BlockPos.ZERO).getY())
                    + "・水の中 " + fern.isInWater() + "・" + fern.rootDebugForTests() + ")");
            helper.assertTrue(helper.getBlockState(at).getValue(BlightFernRootedBlock.WATERLOGGED), "水底の株が水の中の姿でない");
        });
    }

    /**
     * 凍った水面の氷の上に根付いた株は、汚染で氷が溶けたら水底へ沈む(2026-11-08 ユーザー報告「萌芽体が水面に設置される」の原因。
     * 前は氷の置き換えが形の更新を出さず、支えを失った植物の片付けも BlockEntity を持つ株を飛ばすので、株が水面に浮いて残っていた)。
     */
    @GameTest(template = "gametest_empty7", batch = "water_spore", timeoutTicks = 100)
    public static void rootedOnMeltingIceSinksToSeabed(GameTestHelper helper) {
        pool(helper);
        BlockPos ice = new BlockPos(3, 4, 3);
        // 株を置くと株の根がすぐ足元を汚染する(氷なら下草に覆われて株が消える)ので、置く間は石にし、形の更新を出さずに氷へ差し替える
        helper.setBlock(ice, Blocks.STONE);
        BlockPos onIce = ice.above();
        ServerLevel level = helper.getLevel();
        helper.assertTrue(SporeSprouts.placeRootedAt(level, helper.absolutePos(onIce), 90.0F, false), "石の上に根付けない");
        // 本来の汚染の経路で氷を溶かす(CorruptionRegistry#corrupt → placeCorrupted)
        BlockPos iceAbs = helper.absolutePos(ice);
        level.setBlock(iceAbs, Blocks.ICE.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        helper.assertBlockPresent(ModBlocks.BLIGHT_FERN_ROOTED.get(), onIce);
        BlockState before = level.getBlockState(iceAbs);
        BlockState melted = com.yayuy.overgrownvegetation.corruption.CorruptionRegistry.corrupt(level, iceAbs, before, level.random);
        helper.assertTrue(melted.getFluidState().is(FluidTags.WATER), "氷が水にならない: " + melted);
        com.yayuy.overgrownvegetation.corruption.CorruptionRegistry.placeCorrupted(level, iceAbs, before, melted, Block.UPDATE_ALL);
        helper.succeedWhen(() -> {
            helper.assertBlockNotPresent(ModBlocks.BLIGHT_FERN_ROOTED.get(), onIce);
            BlockPos bottom = new BlockPos(3, 2, 3);
            helper.assertBlockPresent(ModBlocks.BLIGHT_FERN_ROOTED.get(), bottom);
            helper.assertTrue(helper.getBlockState(bottom).getValue(BlightFernRootedBlock.WATERLOGGED), "水底の株が水の中の姿でない");
            helper.assertTrue(helper.getLevel().getFluidState(helper.absolutePos(ice)).is(FluidTags.WATER), "氷の跡が水でない");
        });
    }

    /** 水の中の点からも水底の床が汚染される(芽の点は水底にも置く)。 */
    @GameTest(template = "gametest_empty7", batch = "water_spore", timeoutTicks = 200)
    public static void seabedCorruptsFromWaterPoint(GameTestHelper helper) {
        pool(helper);
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(3, 2, 3));
        CorruptionSpreader spreader = new CorruptionSpreader();
        spreader.seedGround(level, origin);
        for (int i = 0; i < 60; i++) spreader.tick(level, origin);
        int floor = 0;
        for (int x = 1; x <= 5; x++)
            for (int z = 1; z <= 5; z++) {
                BlockPos a = helper.absolutePos(new BlockPos(x, 1, z));
                if (level.getBlockState(a).is(ModTags.CORRUPTED) || OvergrownLayerData.isCovered(level, a)) floor++;
            }
        if (floor < 3) {
            helper.fail("水底の床が汚染されない: " + floor + " マス");
            return;
        }
        helper.succeed();
    }

    /** 萌芽体は段差 1 ブロックを歩いて登る(穴につっかえないように。2026-10-24 追記 18)。 */
    @GameTest(template = "gametest_empty7", batch = "water_spore")
    public static void sproutlingStepsOneBlock(GameTestHelper helper) {
        BlightFernEntity fern = helper.spawnWithNoFreeWill(ModEntities.BLIGHT_FERN.get(), new BlockPos(3, 2, 3));
        double step = fern.getStepHeight(); // 1.20.1: 属性は Forge の 0.6 への足し分(PortAttributes.stepHeightBase)。実際の段差の高さで見る
        helper.assertTrue(Math.abs(step - 1.0D) < 1.0E-6D, "段差の高さが 1 でない: " + step);
        helper.succeed();
    }

    /** 行き先があるのに動けないまま(つっかえ)stuckRootTicks たつと、その場で根付く。 */
    @GameTest(template = "gametest_empty7", batch = "water_spore", timeoutTicks = 500)
    public static void stuckSproutlingRootsInPlace(GameTestHelper helper) {
        for (int x = 0; x <= 6; x++) for (int z = 0; z <= 6; z++) helper.setBlock(new BlockPos(x, 1, z), Blocks.DIRT);
        BlightFernEntity fern = helper.spawn(ModEntities.BLIGHT_FERN.get(), new BlockPos(1, 2, 1));
        // 足を 0 にして動けなくし、行き先だけ与える(穴につっかえたのと同じ: 行き先はあるのに位置が変わらない)
        fern.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED).setBaseValue(0.0D);
        BlockPos target = helper.absolutePos(new BlockPos(5, 2, 5));
        helper.runAfterDelay(5, () -> fern.getNavigation().moveTo(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D, 1.0D));
        helper.succeedWhen(() -> {
            boolean rooted = false;
            for (int x = 0; x <= 2 && !rooted; x++)
                for (int z = 0; z <= 2 && !rooted; z++)
                    rooted = helper.getBlockState(new BlockPos(x, 2, z)).is(ModBlocks.BLIGHT_FERN_ROOTED.get());
            helper.assertTrue(rooted, "つっかえた萌芽体がその場で根付かない");
        });
    }
}
