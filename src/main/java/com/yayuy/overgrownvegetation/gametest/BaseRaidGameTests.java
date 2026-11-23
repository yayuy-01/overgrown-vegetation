package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corpse.G1Species;
import com.yayuy.overgrownvegetation.data.BaseMemoryTable;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.director.BaseMemory;
import com.yayuy.overgrownvegetation.director.BaseRaids;
import com.yayuy.overgrownvegetation.director.ForestDirector;
import com.yayuy.overgrownvegetation.entity.AbstractParasitizedMonster;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * 司令塔 第 2 段 区切り 3: 送り込み({@link BaseRaids#sendWave})。まわり 128 ブロックの G1 を集めるので、ほかのテストと同じバッチに入れない
 * (このバッチはこの 1 本だけ)。世界に 1 つの予算を書き換えるので、終わったら戻す。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class BaseRaidGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(BaseRaidGameTests.class);
        }

        private Registration() {
        }
    }

    private BaseRaidGameTests() {
    }

    /**
     * 区切り 4: 認識した拠点のそばの苗床から孵った G1 は拠点へ向かう。拠点から遠い苗床は、ときどき孵すのを断る(そばの苗床は断らない)。
     * 世界に 1 つの記憶を使う(GameTest の世界にプレイヤーはいないので空のはず)。終わったら消す。
     */
    @GameTest(template = "gametest_empty7", batch = "base_raid", timeoutTicks = 20)
    public static void nurseryNearBaseSendsHatchlingsAndFarOnesYield(GameTestHelper helper) {
        var level = helper.getLevel();
        BaseMemory global = BaseMemory.get(level.getServer());
        global.clear();
        try {
            AbstractParasitizedMonster z = helper.spawn(G1Species.ZOMBIE.entityType(), new Vec3(3.5, 2.0, 3.5));
            z.goalSelector.removeAllGoals(g -> true);
            double day = BaseMemory.today(level.getServer());
            global.addPresence(ChunkPos.asLong(z.getBlockX() >> 4, z.getBlockZ() >> 4), DesignData.director().base.rememberScore * 2.0D, day);
            global.update(day);
            global.setStageForTests(global.bases().get(0), BaseMemory.Stage.RECOGNIZED, day);
            global.update(day);
            helper.assertTrue(BaseRaids.onNurseryHatched(level, z) && z.march().active(), "拠点のそばで孵った G1 が拠点へ向かわない");
            var random = net.minecraft.util.RandomSource.create(3L);
            int nearSkips = 0, farSkips = 0;
            Vec3 far = z.position().add(DesignData.director().base.evolveBaseBlocks * 3.0D, 0.0D, 0.0D);
            for (int i = 0; i < 100; i++) {
                if (ForestDirector.skipFarNursery(level, z.position(), random)) nearSkips++;
                if (ForestDirector.skipFarNursery(level, far, random)) farSkips++;
            }
            helper.assertTrue(nearSkips == 0, "拠点のそばの苗床が断った");
            helper.assertTrue(farSkips > 0 && farSkips < 100 || DesignData.director().base.farNurserySkip >= 1.0D, "遠い苗床が断らない: " + farSkips);
            z.discard();
        } finally {
            global.clear();
        }
        helper.succeed();
    }
}
