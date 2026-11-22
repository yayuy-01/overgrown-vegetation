package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.entity.HiveNodeEntity;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import com.yayuy.overgrownvegetation.registry.ModEntities;
import com.yayuy.overgrownvegetation.spore.field.SporeFieldServer;
import com.yayuy.overgrownvegetation.worldgen.HiveChamberLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Random;

/**
 * ハイヴノードと巨大樹の胞子(2026-10-23 胞子の場 D・E)。ノードはふつうに殴って倒せ(体力 1000・防御 20、前の 99% カットは無い)、
 * 倒すとその巨大樹の梢の放出が止まる。巣の蔦を消した後も、巣の球には小部屋と儀式の広間が掘られる。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class HiveNodeGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(HiveNodeGameTests.class);
        }

        private Registration() {
        }
    }

    private HiveNodeGameTests() {
    }

    /** 体力 1000・防御 20 で、殴ったぶんがそのまま通る(前は 99% カット)。倒すと死ぬ。 */
    @GameTest(template = "gametest_empty7")
    public static void hiveNodeCanBeKilled(GameTestHelper helper) {
        HiveNodeEntity node = helper.spawn(ModEntities.HIVE_NODE.get(), new BlockPos(3, 2, 3));
        double max = node.getAttributeValue(Attributes.MAX_HEALTH);
        double armor = node.getAttributeValue(Attributes.ARMOR);
        float before = node.getHealth();
        node.hurt(helper.getLevel().damageSources().generic(), 100.0F);
        float lost = before - node.getHealth();
        node.hurt(helper.getLevel().damageSources().generic(), 100000.0F);
        boolean dead = node.isDeadOrDying();
        node.discard();
        if (max != 1000.0D || armor != 20.0D) {
            helper.fail("体力・防御が 1000・20 でない: " + max + "・" + armor);
            return;
        }
        if (lost < 10.0F) {
            helper.fail("殴ったぶんが通らない(前の 99% カットが残っている?): 100 の攻撃で " + lost);
            return;
        }
        if (!dead) {
            helper.fail("大きな攻撃で倒れない");
            return;
        }
        helper.succeed();
    }

    /**
     * 巨大樹の梢は一日中放出し、ハイヴノードを倒すとその木の放出が止まる(再生なし)。
     * テスト場の真ん中に巨大樹の記録を置き、場を 1 回ずつ回して梢のチャンクの濃度を比べる。
     */
    @GameTest(template = "gametest_empty7", batch = "spore_field_world", timeoutTicks = 100)
    public static void killingHiveNodeStopsTreeSpores(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos center = helper.absolutePos(new BlockPos(3, 2, 3));
        int cx = center.getX() >> 4, cz = center.getZ() >> 4;
        long packed = ((long) center.getX() << 32) | (center.getZ() & 0xFFFFFFFFL);
        PhaseData pd = PhaseData.get(level);
        int ticks = DesignData.sporeField().updateTicks();
        SporeFieldServer.watchForTests(cx, cz);
        Runnable clearField = () -> SporeFieldServer.withFieldForTests(f -> {
            f.extractAround(cx, cz, 1.5D, Double.POSITIVE_INFINITY);
            f.clearDepositAround(cx, cz, 1.5D);
            return null;
        });
        pd.recordGiantTreeOrigin(center.getX(), center.getZ());
        pd.recordGiantTreeBorn(center.getX(), center.getZ(), level.getGameTime() - 24000L * 10); // 育ちきった木
        clearField.run();
        SporeFieldServer.catchUpForTests(level, ticks);
        SporeFieldServer.Cell alive = SporeFieldServer.cell(cx, cz);

        HiveNodeEntity node = helper.spawn(ModEntities.HIVE_NODE.get(), new BlockPos(3, 2, 3));
        // 見直し ④(11-16): ノードが最初の tick で記録してから倒す(湧いたその tick に倒すと記録の前で、印が付かなかった)
        helper.runAfterDelay(1, () -> {
            node.hurt(level.damageSources().generic(), 100000.0F);
            boolean dead = pd.isGiantTreeDead(packed);
            clearField.run();
            SporeFieldServer.catchUpForTests(level, ticks);
            SporeFieldServer.Cell after = SporeFieldServer.cell(cx, cz);
            clearField.run();
            node.discard();
            pd.forgetGiantTreeForTests(packed);

            if (alive == null || alive.conc() <= 0.0D) {
                helper.fail("巨大樹の梢が放出していない: " + alive);
                return;
            }
            if (!dead) {
                helper.fail("ハイヴノードを倒しても巨大樹の印が付かない");
                return;
            }
            if (after == null || after.conc() > alive.conc() * 0.1D) {
                helper.fail("ノードを倒した後も梢が放出している: " + alive.conc() + " → " + (after == null ? "null" : after.conc()));
                return;
            }
            helper.succeed();
        });
    }

    /** 巣の蔦を消した後も、巣の球の真ん中(ノードの小部屋)と儀式の広間は掘られた空気になる。 */
    @GameTest(template = "gametest_empty7")
    public static void hiveChamberHasRoomWithoutVines(GameTestHelper helper) {
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        for (long seed = 1; seed <= 5; seed++) {
            Random r = new Random(seed * 7919L);
            HiveChamberLayout layout = new HiveChamberLayout(new HiveChamberLayout.Params(
                    Math.max(cfg.giantTreeHiveChamberRadius.get(), 16),
                    cfg.giantTreeHiveChamberBarkThickness.get(), cfg.hiveNodeCavityRadius.get(),
                    cfg.hiveRitualChamberHalfWidth.get(), cfg.hiveRitualChamberHeight.get(),
                    cfg.hiveTunnelRadius.get(), cfg.hiveTunnelWinding.get(), cfg.hiveTunnelTurns.get(),
                    cfg.hiveTunnelEntranceElevationMin.get(), cfg.hiveTunnelEntranceElevationMax.get()), r::nextDouble);
            if (layout.get(0, 0, 0) != HiveChamberLayout.CARVED) {
                helper.fail("ノードの小部屋が掘られていない(seed " + seed + ")");
                return;
            }
            int[] c = layout.chamberCenter;
            if (layout.get(c[0], c[1], c[2]) != HiveChamberLayout.CARVED) {
                helper.fail("儀式の広間が掘られていない(seed " + seed + ")");
                return;
            }
        }
        helper.succeed();
    }

}
