package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.SwampFlooder;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 湿地の棚田で、畦を水にする前の確かめ({@link SwampFlooder#rimHoldsWater})。
 * 2026-10-09 ユーザー報告: 角の溢れ口・縫い目の修復で、横の低い面へ流れ出す縁の無い水源ができていた。
 * <p>棚田の処理そのもの({@code processChunk})はテスト場では動かない(高さの地図が天井のバリアを答え、全列が人工物になる)ので、関数を直接見る。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class SwampTerraceGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(SwampTerraceGameTests.class);
        }

        private Registration() {
        }
    }

    /**
     * 畦(3,2,3): 西は同じ段の水源、北は壁、東は下の段へ落ちる向き(空気)。南が空気なら水にしない、泥・水没ハーフなら水にしてよい。
     * 東の空気は、落ちる向きとして許したときだけ通す。
     */
    @GameTest(template = "gametest_empty7")
    public static void cornerRimStaysUnlessEverySideHolds(GameTestHelper helper) {
        BlockPos rim = new BlockPos(3, 2, 3);
        helper.setBlock(rim, ModBlocks.OVERGROWN_MUD.get());
        helper.setBlock(rim.west(), Blocks.WATER);
        helper.setBlock(rim.north(), Blocks.STONE);
        helper.setBlock(rim.east(), Blocks.AIR);
        helper.setBlock(rim.south(), Blocks.AIR);
        BlockPos abs = helper.absolutePos(rim);
        // 向きの番号は SwampFlooder#neighbourIndex と同じ: 0 = 東(x+1)、1 = 西、2 = 南(z+1)、3 = 北。
        java.util.function.IntPredicate fallEast = d -> d == 0;

        if (SwampFlooder.rimHoldsWater(helper.getLevel(), abs, fallEast)) {
            helper.fail("南が空いている角の畦を水にしてよいことになった");
            return;
        }
        if (SwampFlooder.rimHoldsWater(helper.getLevel(), abs, d -> false)) {
            helper.fail("落ちる向きを許していないのに、東の空気を通した");
            return;
        }
        helper.setBlock(rim.south(), ModBlocks.OVERGROWN_MUD.get());
        if (!SwampFlooder.rimHoldsWater(helper.getLevel(), abs, fallEast)) {
            helper.fail("南を泥で塞いだのに水にしない");
            return;
        }
        helper.setBlock(rim.south(), ModBlocks.OVERGROWN_MUD_SLAB.get().defaultBlockState()
                .setValue(SlabBlock.TYPE, SlabType.BOTTOM).setValue(SlabBlock.WATERLOGGED, Boolean.TRUE));
        if (!SwampFlooder.rimHoldsWater(helper.getLevel(), abs, fallEast)) {
            helper.fail("南が同じ段の水面(水没した泥のハーフ)なのに水にしない");
            return;
        }
        helper.succeed();
    }
}
