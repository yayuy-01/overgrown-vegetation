package com.yayuy.overgrownvegetation.organ;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.director.ForestDirector;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * 森の器官(2026-11-17)。器官の記録・司令塔の予算・フェーズのポイントは世界に 1 つなので、世界を使う確かめは自分だけのバッチに入れ、
 * 最後に片付ける。台帳は遠くのチャンクに足して外す。形をまるごと世界に置く確かめは Claude 用クライアントで(大きい版は 60 ブロック級)。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class OrganGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(OrganGameTests.class);
        }

        private Registration() {
        }
    }

    private OrganGameTests() {
    }

    /** 大きい版 16 形・小さい版 10 形がどれも作れて芯があり、地脈を吸う根には結晶・塔には口がある。向きを変えても数は同じ。 */
    @GameTest(template = "gametest_empty7", batch = "organ_pure", timeoutTicks = 200)
    public static void shapesHaveCoresAndRotate(GameTestHelper helper) {
        for (OrganKind k : OrganKind.values()) {
            for (boolean small : new boolean[]{false, true}) {
                int n = small ? OrganShapes.SMALL_VARIANTS : OrganShapes.variants(k);
                for (int v = 0; v < n; v++) {
                    OrganBlueprint a = OrganBlueprint.create(k, small, v, 12345, 0);
                    OrganBlueprint b = OrganBlueprint.create(k, small, v, 12345, 1);
                    String name = k.id + (small ? " 小 " : " ") + v;
                    helper.assertTrue(a.entries.length > (small ? 40 : 2000), name + " のブロックが少ない: " + a.entries.length);
                    helper.assertTrue(!a.cores.isEmpty(), name + " に芯が無い");
                    helper.assertTrue(a.entries.length == b.entries.length, name + " が向きで数を変える");
                    if (k == OrganKind.LEY && !small) helper.assertTrue(!a.crystals.isEmpty(), name + " に結晶が無い");
                    if (k == OrganKind.TOWER) helper.assertTrue(!a.mouths.isEmpty(), name + " に胞子の口が無い");
                    for (long e : a.entries) {
                        int dx = OrganBlueprint.dx(e), dy = OrganBlueprint.dy(e), dz = OrganBlueprint.dz(e);
                        if (Math.abs(dx) > OrganBlueprint.MAX_HALF || Math.abs(dz) > OrganBlueprint.MAX_HALF || dy > OrganBlueprint.MAX_UP
                                || dy < -OrganBlueprint.MAX_DOWN) {
                            helper.fail(name + " が器官の箱をはみ出す: " + dx + "," + dy + "," + dz);
                            return;
                        }
                    }
                }
            }
        }
        OrganBlueprint s1 = OrganBlueprint.create(OrganKind.TOWER, false, 0, 1, 0), s2 = OrganBlueprint.create(OrganKind.TOWER, false, 0, 1, 0),
                s3 = OrganBlueprint.create(OrganKind.TOWER, false, 0, 2, 0);
        helper.assertTrue(java.util.Arrays.equals(s1.entries, s2.entries), "同じ種で形が変わる");
        helper.assertTrue(!java.util.Arrays.equals(s1.entries, s3.entries), "種を変えても形が同じ");
        helper.succeed();
    }

    /** 建てられる升: 台帳に載っている・古い・まわりが全部台帳(境目から奥)・ほかの器官から離れている。小さい版は条件がゆるい。 */
    @GameTest(template = "gametest_empty7", batch = "organ_world", timeoutTicks = 100)
    public static void buildableNeedsDepthAgeAndSpacing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PhaseData pd = PhaseData.get(level);
        ForestOrgans data = ForestOrgans.get(level);
        int base = 610_000; // 遠くのチャンク
        List<ChunkPos> added = new ArrayList<>();
        List<Integer> planted = new ArrayList<>();
        try {
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    ChunkPos c = new ChunkPos(base + dx, base + dz);
                    if (pd.markOvergrownChunk(level, c)) added.add(c);
                }
            }
            long now = pd.clock(level.getServer());
            OrganTable.Json j = new OrganTable.Json();
            j.minAgeDays = 0.0D;
            j.minDepthChunks = 2;
            j.spacingChunks = 4;
            j.small = new OrganTable.SmallJson();
            j.small.minAgeDays = 0.0D;
            j.small.minDepthChunks = 1;
            j.small.spacingChunks = 1;
            OrganTable young = OrganTable.fromJson(j);
            helper.assertTrue(OrganPlanner.buildable(pd, data, base, base, now, young, false), "奥の升に建てられない");
            helper.assertTrue(!OrganPlanner.buildable(pd, data, base + 2, base, now, young, false), "境目に近い升に建てられる(奥行き 2)");
            helper.assertTrue(OrganPlanner.buildable(pd, data, base + 2, base, now, young, true), "小さい版が奥行き 1 の升に建てられない");
            helper.assertTrue(!OrganPlanner.buildable(pd, data, base + 10, base, now, young, true), "台帳に無い升に建てられる");
            helper.assertTrue(!OrganPlanner.buildable(pd, data, base, base, now, OrganTable.DEFAULT, false), "過繁茂になったばかりの升に建てられる");
            ForestOrgans.Organ o = data.plant(level, OrganKind.SAC, false, 0, 1, 0, (base + 2) * 16 + 8, base * 16 + 8);
            planted.add(o.id);
            helper.assertTrue(!OrganPlanner.buildable(pd, data, base, base, now, young, false), "器官のすぐそばに建てられる");
            helper.succeed();
        } finally {
            for (int id : planted) data.remove(id);
            for (ChunkPos c : added) pd.forgetOvergrownChunk(c);
        }
    }

    /**
     * 蓄えの嚢は司令塔の上限を上げる(大 = ハイヴノード 3 つ分・小 = 0.2)。芽の芯を壊すと器官が枯れ(育つ前に潰せる)、上限が戻り、
     * フェーズのポイントと予算が一気に下がる。器官の手順で芯を置き換えるときは枯れない。
     */
    @GameTest(template = "gametest_empty7", batch = "organ_world", timeoutTicks = 100)
    public static void sacCapAndCoreBreakPenalty(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PhaseData pd = PhaseData.get(level);
        ForestOrgans data = ForestOrgans.get(level);
        ForestDirector dir = ForestDirector.get(level);
        double budgetBefore = dir.available(level);
        long pointsBefore = pd.getPoints();
        List<Integer> planted = new ArrayList<>();
        BlockPos floor = helper.absolutePos(new BlockPos(3, 1, 3));
        try {
            double cap0 = ForestDirector.cap(level);
            ForestOrgans.Organ sac = data.plant(level, OrganKind.SAC, false, 0, 7, 0, floor.getX(), floor.getZ());
            planted.add(sac.id);
            sac.y = floor.getY();
            helper.assertTrue(Math.abs(ForestDirector.cap(level) - cap0) < 1.0E-6D, "育っている嚢が上限を上げる");
            sac.state = ForestOrgans.State.ALIVE;
            OrganWorld.invalidateCaches();
            double income = ForestDirector.incomePerDay(level);
            double want = income * DesignData.director().capPerHiveNodeDays * DesignData.organs().sacHiveNodes;
            double cap1 = ForestDirector.cap(level);
            helper.assertTrue(Math.abs(cap1 - cap0 - want) < 1.0E-6D, "嚢の上限の上がりが違う: " + (cap1 - cap0) + "(欲しい " + want + ")");
            ForestOrgans.Organ small = data.plant(level, OrganKind.SAC, true, 0, 8, 0, floor.getX() + 3000, floor.getZ());
            planted.add(small.id);
            small.state = ForestOrgans.State.ALIVE;
            OrganWorld.invalidateCaches();
            double want2 = income * DesignData.director().capPerHiveNodeDays * DesignData.organs().smallSacHiveNodes;
            helper.assertTrue(Math.abs(ForestDirector.cap(level) - cap1 - want2) < 1.0E-6D, "小さい嚢の上限の上がりが違う");
            data.remove(small.id);

            // 芽を置き、芽の芯を壊す → 枯れる・上限が戻る・予算が下がる
            dir.setBudget(level, ForestDirector.cap(level));
            double budget1 = dir.available(level);
            OrganWorld.placeBud(level, sac);
            BlockPos bud = sac.anchor().above();
            helper.assertTrue(level.getBlockState(bud).is(OrganBlocks.ORGAN_CORE.get()), "芽の芯が置かれない");
            level.destroyBlock(bud, false);
            helper.assertTrue(sac.state == ForestOrgans.State.WITHERED, "芯を壊しても枯れない: " + sac.state);
            helper.assertTrue(Math.abs(ForestDirector.cap(level) - cap0) < 1.0E-6D, "枯れた嚢が上限を上げたまま");
            helper.assertTrue(dir.available(level) < budget1 - 1.0D, "芯を壊しても予算が下がらない: " + budget1 + " → " + dir.available(level));

            // ポイントの引き(フェーズを進める器官は多め)。ポイントを足してから枯らす
            if (pd.getPhase() >= 1 && DesignData.phases().pointsToAdvance(pd.getPhase()) > 0L) {
                long before = pd.getPoints();
                ForestOrgans.Organ ph = data.plant(level, OrganKind.PHASE, false, 0, 5, 0, floor.getX() + 6000, floor.getZ());
                planted.add(ph.id);
                ph.state = ForestOrgans.State.ALIVE;
                ForestOrgans.Penalty p = data.wither(level, ph, "テスト");
                long expect = Math.min(before, (long) Math.floor(DesignData.phases().pointsToAdvance(pd.getPhase())
                        * DesignData.organs().witherPointsPercent / 100.0D * DesignData.organs().phaseOrganPointsFactor));
                helper.assertTrue(p.points() == expect, "フェーズを進める器官のポイントの引きが違う: " + p.points() + "(欲しい " + expect + ")");
                pd.addPointsUnscaledForTests(level, p.points()); // 戻す
            }

            // 器官の手順で芯を置き換えるときは枯れない(芽の片付け)
            ForestOrgans.Organ tower = data.plant(level, OrganKind.TOWER, false, 0, 9, 0, floor.getX(), floor.getZ());
            planted.add(tower.id);
            tower.y = floor.getY();
            OrganWorld.placeBud(level, tower);
            OrganWorld.clearBud(level, tower);
            helper.assertTrue(tower.state == ForestOrgans.State.GROWING, "芽の片付けで枯れた");
            helper.assertTrue(level.getBlockState(tower.anchor().above()).is(OrganBlocks.ORGAN_SAC.get()), "芽の芯が嚢に替わらない");
            helper.succeed();
        } finally {
            for (int id : planted) data.remove(id);
            OrganWorld.invalidateCaches();
            dir.setBudget(level, budgetBefore);
            if (pd.getPoints() < pointsBefore) pd.addPointsUnscaledForTests(level, pointsBefore - pd.getPoints());
            for (int y = 0; y < 4; y++) {
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        level.setBlock(floor.offset(dx, y, dz), Blocks.AIR.defaultBlockState(), 2);
                    }
                }
            }
        }
    }

    /** 地脈を吸う根: 1 本につき植物側の Mob のステータスが 1 つ上がる(芽吹いたときに種で決まる)。小さい版は弱い。枯れたら外れる。 */
    @GameTest(template = "gametest_empty7", batch = "organ_world", timeoutTicks = 100)
    public static void leyRootsBuffPlantSide(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ForestOrgans data = ForestOrgans.get(level);
        PhaseData pd = PhaseData.get(level);
        ForestDirector dir = ForestDirector.get(level);
        double budgetBefore = dir.available(level);
        long pointsBefore = pd.getPoints();
        List<Integer> planted = new ArrayList<>();
        try {
            OrganWorld.invalidateCaches();
            double[] t0 = LeyBuffs.totals(level).clone();
            OrganTable t = DesignData.organs();
            ForestOrgans.Organ a = data.plant(level, OrganKind.LEY, false, 0, 1, 0, 9_000_000, 0);
            ForestOrgans.Organ b = data.plant(level, OrganKind.LEY, false, 0, 2, 0, 9_000_400, 0);
            ForestOrgans.Organ c = data.plant(level, OrganKind.LEY, true, 0, 3, 0, 9_000_800, 0);
            planted.add(a.id);
            planted.add(b.id);
            planted.add(c.id);
            a.state = b.state = c.state = ForestOrgans.State.ALIVE;
            OrganWorld.invalidateCaches();
            double[] t1 = LeyBuffs.totals(level);
            LeyBuffs.Stat sa = LeyBuffs.statOf(level, a), sb = LeyBuffs.statOf(level, b), sc = LeyBuffs.statOf(level, c);
            helper.assertTrue(sa != null && sb != null && sc != null, "生きている根がステータスを持たない");
            helper.assertTrue(sa.ordinal() == LeyBuffs.statIndex(a) && LeyBuffs.statIndex(a) == LeyBuffs.statIndex(a), "根のステータスが種で決まらない");
            double added = 0.0D, want = t.leyStat[sa.ordinal()] + t.leyStat[sb.ordinal()] + t.leyStat[sc.ordinal()] * t.smallStrength;
            for (int i = 0; i < t1.length; i++) added += t1[i] - t0[i];
            helper.assertTrue(Math.abs(added - want) < 1.0E-9D, "上げ幅の合計が違う: " + added + "(欲しい " + want + ")");
            data.wither(level, b, "テスト");
            double[] t2 = LeyBuffs.totals(level);
            helper.assertTrue(Math.abs(t1[sb.ordinal()] - t2[sb.ordinal()] - t.leyStat[sb.ordinal()]) < 1.0E-9D, "枯れた根のステータスが残る");
            helper.succeed();
        } finally {
            for (int id : planted) data.remove(id);
            OrganWorld.invalidateCaches();
            dir.setBudget(level, budgetBefore);
            if (pd.getPoints() < pointsBefore) pd.addPointsUnscaledForTests(level, pointsBefore - pd.getPoints());
        }
    }
}
