package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.CorruptionRegistry;
import com.yayuy.overgrownvegetation.corruption.OvergrownLayerData;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
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

/**
 * 汚染が氷に届いたら溶ける・汚染の隣の水は凍らない({@link com.yayuy.overgrownvegetation.corruption.MeltedIce}、2026-10-16)。
 *
 * <p>以前は氷が対応表に無いので苔で覆って残り、汚染地域に氷が残っていた(ユーザーの報告)。
 * 水は 1 マスに石で囲う(床一面に流れるので。テスト場の決まり)。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class IceMeltGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(IceMeltGameTests.class);
        }

        private Registration() {
        }
    }

    /** 水に接した氷は水、接していない氷は空気。どちらも覆わない。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 100)
    public static void corruptionMeltsIce(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        enclosedWater(helper, new BlockPos(2, 2, 2));
        BlockPos onWater = new BlockPos(2, 3, 2);
        BlockPos onLand = new BlockPos(5, 2, 5);

        BlockState ice = Blocks.ICE.defaultBlockState();
        BlockState packed = Blocks.PACKED_ICE.defaultBlockState();
        helper.setBlock(onWater, ice);
        helper.setBlock(onLand, packed);

        BlockState wet = CorruptionRegistry.corrupt(level, helper.absolutePos(onWater), ice, level.random);
        if (!wet.is(Blocks.WATER)) {
            helper.fail("水の上の氷が水にならない: " + wet);
            return;
        }
        BlockState dry = CorruptionRegistry.corrupt(level, helper.absolutePos(onLand), packed, level.random);
        if (!dry.isAir()) {
            helper.fail("陸のパックされた氷が消えない: " + dry);
            return;
        }
        for (Block block : new Block[] {Blocks.ICE, Blocks.PACKED_ICE, Blocks.BLUE_ICE, Blocks.FROSTED_ICE}) {
            if (OvergrownLayerData.canCover(block.defaultBlockState())) {
                helper.fail(block + " がまだ覆える扱い(苔で覆って残る)");
                return;
            }
        }
        helper.succeed();
    }

    /** 寒いバイオームでも、汚染された地面の隣の水は凍らない。隣が石なら今までどおり凍る。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 100)
    public static void waterNextToCorruptionDoesNotFreeze(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos water = new BlockPos(4, 2, 3);
        enclosedWater(helper, water);
        Biome frozenRiver = level.registryAccess().registryOrThrow(Registries.BIOME).getOrThrow(Biomes.FROZEN_RIVER);
        BlockPos abs = helper.absolutePos(water);

        if (!frozenRiver.shouldFreeze(level, abs, true)) {
            helper.fail("前提: 石に囲まれた水が凍った川のバイオームで凍らない");
            return;
        }
        helper.setBlock(water.east(), ModBlocks.OVERGROWN_DIRT.get());
        if (frozenRiver.shouldFreeze(level, abs, true)) {
            helper.fail("汚染された地面の隣の水が凍る");
            return;
        }
        helper.succeed();
    }

    /** 床の上の 1 マスの水源を、横 4 方向の石で囲う(床は相対 y=1)。 */
    private static void enclosedWater(GameTestHelper helper, BlockPos at) {
        helper.setBlock(at.north(), Blocks.STONE);
        helper.setBlock(at.south(), Blocks.STONE);
        helper.setBlock(at.east(), Blocks.STONE);
        helper.setBlock(at.west(), Blocks.STONE);
        helper.setBlock(at, Blocks.WATER);
    }
}
