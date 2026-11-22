package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.BlightFernRootedBlockEntity;
import com.yayuy.overgrownvegetation.entity.BlightFernEntity;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.ModEntities;
import com.yayuy.overgrownvegetation.spore.field.SporeSprouts;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * 萌芽体のモデル(2026-10-23)のテスト: 当たり判定は低い 1 × 2(汚染水草は前のまま 1 ブロック)/
 * 根付いた姿のブロックが GeckoLib の中身を持ち、歩いていたときの向きを受け取る。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class HougataiGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(HougataiGameTests.class);
        }

        private Registration() {
        }
    }

    /** 萌芽体は体格 1・当たり判定の高さ = mobs.json の height(2。見た目の 3.65 は使わない、10-23 ユーザー指定)。 */
    @GameTest(template = "gametest_empty7", batch = "hougatai")
    public static void sproutHitboxMatchesModelHeight(GameTestHelper helper) {
        BlightFernEntity fern = helper.spawnWithNoFreeWill(ModEntities.BLIGHT_FERN.get(), new BlockPos(3, 2, 3));
        helper.assertTrue(Math.abs(fern.getScale() - 1.0F) < 1.0E-3F, "萌芽体の体格が 1 でない: " + fern.getScale());
        helper.assertTrue(Math.abs(fern.getBbHeight() - 2.0F) < 0.05F, "萌芽体の当たり判定の高さが 2 でない: " + fern.getBbHeight());
        helper.succeed();
    }

    /** 根付いた姿を置くと中身(GeckoLib の BlockEntity)ができ、萌芽体の向きを覚える。 */
    @GameTest(template = "gametest_empty7", batch = "hougatai")
    public static void rootedBlockKeepsSproutYaw(GameTestHelper helper) {
        BlockPos rel = new BlockPos(3, 2, 3);
        helper.setBlock(rel.below(), Blocks.MOSS_BLOCK);
        BlockPos abs = helper.absolutePos(rel);
        BlockPos spot = SporeSprouts.rootedSpot(helper.getLevel(), abs);
        helper.assertTrue(abs.equals(spot), "根付く場所が足元でない: " + spot);
        helper.assertTrue(SporeSprouts.placeRootedAt(helper.getLevel(), spot, 123.0F, true), "根付いた姿を置けない");
        helper.assertBlockPresent(ModBlocks.BLIGHT_FERN_ROOTED.get(), rel);
        if (!(helper.getLevel().getBlockEntity(abs) instanceof BlightFernRootedBlockEntity be)) {
            helper.fail("根付いた姿に中身が無い", rel);
            return;
        }
        helper.assertTrue(Math.abs(be.yaw() - 123.0F) < 1.0E-3F, "向きを覚えていない: " + be.yaw());
        helper.succeed();
    }
}
