package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import com.yayuy.overgrownvegetation.phase.OvergrowthDepth;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 縁からの深さ d と湧きの重み(順 1 の着手順 10、2026-10-03)のテスト。本物の台帳は書き換えず、
 * 過繁茂のチャンクの集合を渡して測る。半径・下限は {@code climax.json} から引く。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class OvergrowthDepthGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(OvergrowthDepthGameTests.class);
        }

        private Registration() {
        }
    }

    private static final String TEMPLATE = "gametest_empty7";
    private static final String BATCH = "overgrowth_depth";

    /** 中心から半径 {@code filled} の円を過繁茂にした集合。 */
    private static LongOpenHashSet disc(int filled) {
        LongOpenHashSet set = new LongOpenHashSet();
        for (int dx = -filled; dx <= filled; dx++) {
            for (int dz = -filled; dz <= filled; dz++) {
                if (dx * dx + dz * dz <= filled * filled) {
                    set.add(ChunkPos.asLong(dx, dz));
                }
            }
        }
        return set;
    }

    /**
     * d は最初の「過繁茂でない」チャンクまでの円の距離: 中心が過繁茂でなければ 0、半径 r の円が埋まっていれば
     * その外の最寄り(r より大きく r + 1.5 以下)、半径の円の中が全部埋まっていれば上限(半径)。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 20, batch = BATCH)
    public static void depthMeasuresNearestGap(GameTestHelper helper) {
        int radius = DesignData.climax().neighborRadiusChunks();
        helper.assertTrue(radius >= 4, "極相化の半径が小さすぎてテストにならない: " + radius);
        helper.assertTrue(OvergrowthDepth.measure(new LongOpenHashSet()::contains, 0, 0, radius) == 0.0D,
                "過繁茂でないチャンクの d が 0 でない");
        LongOpenHashSet three = disc(3);
        double d3 = OvergrowthDepth.measure(three::contains, 0, 0, radius);
        helper.assertTrue(d3 > 3.0D && d3 <= 4.5D, "半径 3 の円が埋まっているときの d が違う: " + d3);
        // 斜めは円の距離で測る(正方形なら 1、円なら √2)
        LongOpenHashSet plus = new LongOpenHashSet();
        plus.add(ChunkPos.asLong(0, 0));
        plus.add(ChunkPos.asLong(1, 0));
        plus.add(ChunkPos.asLong(-1, 0));
        plus.add(ChunkPos.asLong(0, 1));
        plus.add(ChunkPos.asLong(0, -1));
        double diag = OvergrowthDepth.measure(plus::contains, 0, 0, radius);
        helper.assertTrue(Math.abs(diag - Math.sqrt(2.0D)) < 1e-9, "斜めの距離が円で測られていない: " + diag);
        LongOpenHashSet full = disc(radius);
        helper.assertTrue(OvergrowthDepth.measure(full::contains, 0, 0, radius) == radius,
                "半径の円が全部埋まっているのに d が上限でない");
        helper.succeed();
    }

    /** 重み: 縁寄りは d = 0 で 1・上限で下限、奥寄りはその逆、半径の半分でちょうど半々。帯の無いグループは 1。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 20, batch = BATCH)
    public static void weightsFollowTheFormula(GameTestHelper helper) {
        int r = DesignData.climax().neighborRadiusChunks();
        double floor = DesignData.climax().depthSpawnFloor();
        OvergrowthDepth.Band edge = OvergrowthDepth.Band.EDGE;
        OvergrowthDepth.Band deep = OvergrowthDepth.Band.DEEP;
        helper.assertTrue(Math.abs(OvergrowthDepth.weight(edge, 0, r) - 1.0D) < 1e-9, "縁寄りの d = 0 が 1 でない");
        helper.assertTrue(Math.abs(OvergrowthDepth.weight(edge, r, r) - floor) < 1e-9, "縁寄りの上限が下限でない");
        helper.assertTrue(Math.abs(OvergrowthDepth.weight(deep, 0, r) - floor) < 1e-9, "奥寄りの d = 0 が下限でない");
        helper.assertTrue(Math.abs(OvergrowthDepth.weight(deep, r, r) - 1.0D) < 1e-9, "奥寄りの上限が 1 でない");
        helper.assertTrue(Math.abs(OvergrowthDepth.weight(edge, r / 2.0D, r) - OvergrowthDepth.weight(deep, r / 2.0D, r)) < 1e-9,
                "半径の半分で半々になっていない");
        helper.assertTrue(OvergrowthDepth.weight(OvergrowthDepth.Band.NONE, 3, r) == 1.0D, "帯の無いグループに重みが掛かった");

        helper.assertTrue(OvergrowthDepth.band(MobGroup.GROUP_4) == edge && OvergrowthDepth.band(MobGroup.GROUP_6) == edge
                && OvergrowthDepth.band(MobGroup.GROUP_7) == edge && OvergrowthDepth.band(MobGroup.GROUP_9) == edge,
                "縁寄りの帯が違う(G4・G6・G7・G9)");
        helper.assertTrue(OvergrowthDepth.band(MobGroup.GROUP_2) == deep && OvergrowthDepth.band(MobGroup.GROUP_3) == deep
                && OvergrowthDepth.band(MobGroup.GROUP_8) == deep, "奥寄りの帯が違う(G2・G3・G8)");
        helper.assertTrue(OvergrowthDepth.band(MobGroup.GROUP_1) == OvergrowthDepth.Band.NONE
                && OvergrowthDepth.band(MobGroup.GROUP_5) == OvergrowthDepth.Band.NONE, "G1・G5 に帯がある");
        helper.succeed();
    }

    /**
     * 自然湧きの重みへのつなぎ: 縁(d = 0)では G4 のアルボラがそのまま・G2 のプロトファスマが下限倍、奥(上限)では逆。
     * G5(ファルキムネス)は d に依らない。d が負(オーバーワールド以外)なら何も掛けない。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 20, batch = BATCH)
    public static void spawnerWeightsFollowDepth(GameTestHelper helper) {
        int r = DesignData.climax().neighborRadiusChunks();
        double floor = DesignData.climax().depthSpawnFloor();
        var arbora = com.yayuy.overgrownvegetation.registry.ModEntities.ARBORA.get();
        var protophasma = com.yayuy.overgrownvegetation.registry.ModEntities.PROTOPHASMA.get();
        var falcimnes = com.yayuy.overgrownvegetation.registry.ModEntities.FALCIMNES.get();
        double wa = com.yayuy.overgrownvegetation.phase.OvergrownSpawnProfile.weight(arbora);
        double wp = com.yayuy.overgrownvegetation.phase.OvergrownSpawnProfile.weight(protophasma);
        double wf = com.yayuy.overgrownvegetation.phase.OvergrownSpawnProfile.weight(falcimnes);
        helper.assertTrue(wa > 0 && wp > 0 && wf > 0, "湧きの重みが 0 の種を選んでしまった");
        helper.assertTrue(close(com.yayuy.overgrownvegetation.phase.OvergrownBiomeSpawner.depthWeight(arbora, 0), wa)
                && close(com.yayuy.overgrownvegetation.phase.OvergrownBiomeSpawner.depthWeight(protophasma, 0), wp * floor),
                "縁(d = 0)の重みが違う");
        helper.assertTrue(close(com.yayuy.overgrownvegetation.phase.OvergrownBiomeSpawner.depthWeight(arbora, r), wa * floor)
                && close(com.yayuy.overgrownvegetation.phase.OvergrownBiomeSpawner.depthWeight(protophasma, r), wp),
                "奥(上限)の重みが違う");
        helper.assertTrue(close(com.yayuy.overgrownvegetation.phase.OvergrownBiomeSpawner.depthWeight(falcimnes, 0), wf)
                && close(com.yayuy.overgrownvegetation.phase.OvergrownBiomeSpawner.depthWeight(falcimnes, r), wf),
                "G5 の重みが d で変わった");
        helper.assertTrue(close(com.yayuy.overgrownvegetation.phase.OvergrownBiomeSpawner.depthWeight(protophasma, -1), wp),
                "d が負なのに重みが変わった");
        helper.succeed();
    }

    /**
     * 「最低 1 体」の床の抽選: 同じチャンク・種・日なら同じ答え。確率 1 なら必ず、0 なら付かない。
     * チャンクを替えると、およそ確率の割合で付く(2 万チャンクで 0.15 ± 0.02)。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 20, batch = BATCH)
    public static void floorRollIsStableAndProportional(GameTestHelper helper) {
        long key = ChunkPos.asLong(123, -45);
        boolean first = com.yayuy.overgrownvegetation.phase.OvergrownBiomeSpawner.floorRoll(key, 7, 3L, 0.5D);
        for (int i = 0; i < 10; i++) {
            helper.assertTrue(com.yayuy.overgrownvegetation.phase.OvergrownBiomeSpawner.floorRoll(key, 7, 3L, 0.5D) == first,
                    "同じ場所・種・日なのに答えが変わった");
        }
        helper.assertTrue(com.yayuy.overgrownvegetation.phase.OvergrownBiomeSpawner.floorRoll(key, 7, 3L, 1.0D), "確率 1 で付かない");
        helper.assertFalse(com.yayuy.overgrownvegetation.phase.OvergrownBiomeSpawner.floorRoll(key, 7, 3L, 0.0D), "確率 0 で付いた");
        int hits = 0;
        int n = 20000;
        for (int i = 0; i < n; i++) {
            if (com.yayuy.overgrownvegetation.phase.OvergrownBiomeSpawner.floorRoll(
                    ChunkPos.asLong(i % 141 - 70, i / 141 - 70), 7, 3L, 0.15D)) {
                hits++;
            }
        }
        double frac = hits / (double) n;
        helper.assertTrue(frac > 0.13D && frac < 0.17D, "床の付く割合が確率とずれている: " + frac);
        helper.succeed();
    }

    /** 群れの主力の頭数に重みを掛ける: 0.15 倍なら 1 体(最低 1)、1 倍なら今までどおり swarm.json の min〜max。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 20, batch = BATCH)
    public static void swarmLeadShrinksWithWeight(GameTestHelper helper) {
        var table = DesignData.swarm();
        var template = table.template(5, com.yayuy.overgrownvegetation.swarm.Swarm.Kind.GROUND);
        helper.assertTrue(template != null && !template.members().isEmpty(), "F5 の地上の群れの編成が無い");
        var spec = template.members().get(0);
        var lead = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.get(spec.id());
        var random = net.minecraft.util.RandomSource.create(42L);
        int max = table.maxSize(com.yayuy.overgrownvegetation.swarm.Swarm.Kind.GROUND);
        long full = template.roll(random, spec.min(), max, 1.0D).stream().filter(t -> t == lead).count();
        long small = template.roll(random, spec.min(), max, 0.15D).stream().filter(t -> t == lead).count();
        long none = template.roll(random, spec.min(), max, 0.0D).stream().filter(t -> t == lead).count();
        helper.assertTrue(full == Math.min(spec.min(), max), "重み 1 の主力の頭数が min でない: " + full);
        helper.assertTrue(small == Math.max(1, Math.round(spec.min() * 0.15D)), "重み 0.15 の主力の頭数が違う: " + small);
        helper.assertTrue(none == 1, "重み 0 でも主力は最低 1 体のはず: " + none);
        helper.succeed();
    }

    private static boolean close(double a, double b) {
        return Math.abs(a - b) < 1e-9;
    }

    private OvergrowthDepthGameTests() {
    }
}
