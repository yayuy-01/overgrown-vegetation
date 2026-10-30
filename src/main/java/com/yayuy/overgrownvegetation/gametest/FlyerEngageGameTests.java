package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.ParasitizedBlaze;
import com.yayuy.overgrownvegetation.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 空を漂う寄生体(ガスト・ブレイズ)が標的をまだ追えるかの判定({@code AbstractParasitizedFlyer#canStillEngage}、2026-10-09)。
 * 空の経路探索が重かった(性能記録でサーバーの 38%)ので、見通せるか・まっすぐ飛んで行けるかで測るようにした。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class FlyerEngageGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(FlyerEngageGameTests.class);
        }

        private Registration() {
        }
    }

    /** 床から天井までの壁で仕切ると追えない。壁を取り除くと追える。 */
    @GameTest(template = "gametest_empty7")
    public static void wallCutsOffAndOpeningRestores(GameTestHelper helper) {
        for (int y = 2; y <= 7; y++) {
            for (int z = 0; z <= 6; z++) {
                helper.setBlock(new BlockPos(3, y, z), Blocks.STONE);
            }
        }
        ParasitizedBlaze blaze = helper.spawnWithNoFreeWill(ModEntities.PARASITIZED_BLAZE.get(), 1.5F, 3.0F, 3.5F);
        blaze.setNoAi(true);
        Husk husk = helper.spawnWithNoFreeWill(EntityType.HUSK, 5.5F, 2.0F, 3.5F);
        husk.setNoAi(true);
        helper.runAfterDelay(2, () -> {
            if (blaze.canStillEngage(husk)) {
                helper.fail("壁の向こうの相手を追えることになった");
                return;
            }
            for (int y = 2; y <= 7; y++) {
                for (int z = 0; z <= 6; z++) {
                    helper.setBlock(new BlockPos(3, y, z), Blocks.AIR);
                }
            }
            helper.runAfterDelay(2, () -> {
                if (!blaze.canStillEngage(husk)) {
                    helper.fail("壁を取り除いたのに追えない");
                    return;
                }
                helper.succeed();
            });
        });
    }
}
