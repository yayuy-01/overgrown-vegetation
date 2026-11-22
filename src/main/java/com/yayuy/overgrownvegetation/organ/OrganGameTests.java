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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

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

    /**
     * フェーズを進める器官の点は固定値(11-19 ユーザー了承): 大きい版 1 本 = 過繁茂 phaseChunks チャンク分の面積の点(1 日 = 面積の点の式 × N)、
     * 小さい版は small.strength 倍、フェーズに依らない。枯れの引きはその 1 日分 × pointsDays(フェーズ器官は × phaseOrganPointsFactor)。
     */
    @GameTest(template = "gametest_empty7", batch = "organ_pure", timeoutTicks = 20)
    public static void phaseOrganPointsAreFixed(GameTestHelper helper) {
        OrganTable t = DesignData.organs();
        com.yayuy.overgrownvegetation.data.PhaseTable p = DesignData.phases();
        ForestOrgans.Organ big = new ForestOrgans.Organ(-1, OrganKind.PHASE, false, 0, 0, 0, 0, 0, 0L);
        ForestOrgans.Organ small = new ForestOrgans.Organ(-2, OrganKind.PHASE, true, 0, 0, 0, 0, 0, 0L);
        ForestOrgans.Organ tower = new ForestOrgans.Organ(-3, OrganKind.TOWER, false, 0, 0, 0, 0, 0, 0L);
        double perChunk = p.areaPointsPerChunkPerSecond() * (com.yayuy.overgrownvegetation.data.PhaseTable.TICKS_PER_DAY / 20.0D); // 1 日 = 1200 秒
        double want = t.phaseChunks * perChunk;
        for (int ph = 1; ph <= 7; ph++) {
            double got = OrganRoles.phasePointsPerDay(ph, big);
            helper.assertTrue(Math.abs(got - want) < 1.0E-6D * want + 1.0E-6D, "F" + ph + " の大きいフェーズ器官の 1 日の点が違う: " + got + "(欲しい " + want + ")");
            helper.assertTrue(Math.abs(OrganRoles.phasePointsPerDay(ph, small) - want * t.smallStrength) < 1.0E-6D * want + 1.0E-6D, "F" + ph + " の小さい版が strength 倍でない");
            helper.assertTrue(Math.abs(ForestOrgans.witherPoints(ph, big) - want * t.witherPointsDays * t.phaseOrganPointsFactor) < 1.0E-6D * want + 1.0E-6D, "F" + ph + " のフェーズ器官の枯れの引きが違う");
            helper.assertTrue(Math.abs(ForestOrgans.witherPoints(ph, tower) - want * t.witherPointsDays) < 1.0E-6D * want + 1.0E-6D, "F" + ph + " の塔の枯れの引きが違う");
        }
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
            double want = DesignData.director().capPerHiveNode * DesignData.organs().sacHiveNodes; // 11-22: 基礎値 + ハイヴノード
            double cap1 = ForestDirector.cap(level);
            helper.assertTrue(Math.abs(cap1 - cap0 - want) < 1.0E-6D, "嚢の上限の上がりが違う: " + (cap1 - cap0) + "(欲しい " + want + ")");
            ForestOrgans.Organ small = data.plant(level, OrganKind.SAC, true, 0, 8, 0, floor.getX() + 3000, floor.getZ());
            planted.add(small.id);
            small.state = ForestOrgans.State.ALIVE;
            OrganWorld.invalidateCaches();
            double want2 = DesignData.director().capPerHiveNode * DesignData.organs().smallSacHiveNodes;
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
                long expect = Math.min(before, (long) Math.floor(OrganRoles.chunkPointsPerDay(DesignData.organs().phaseChunks)
                        * DesignData.organs().witherPointsDays * DesignData.organs().phaseOrganPointsFactor));
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

    /**
     * 森の中で建てる(11-19): 高さは木を除いた地面。建つ前に円の中の木を払い、幹が円の中の木は円の外へはみ出した葉もまとめて払う。
     * 巨大樹・円の外の木(円に差し掛かった葉だけ払う)・地面の下草は残る。
     */
    @GameTest(template = "gametest_empty24", batch = "organ_clear", timeoutTicks = 100)
    public static void clearsTreesAndBuildsOnGround(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ForestOrgans data = ForestOrgans.get(level);
        BlockPos c = helper.absolutePos(new BlockPos(12, 1, 12));
        int gy = c.getY() - 1;
        ForestDirector dir = ForestDirector.get(level);
        PhaseData pd = PhaseData.get(level);
        double budgetBefore = dir.available(level);
        long pointsBefore = pd.getPoints();
        List<Integer> planted = new ArrayList<>();
        int span = 0;
        try {
            ForestOrgans.Organ o = data.plant(level, OrganKind.SAC, true, 0, 7, 0, c.getX(), c.getZ());
            planted.add(o.id);
            OrganBlueprint bp = OrganWorld.blueprintNow(o);
            int r = OrganClearing.radius(bp, true);
            span = r + 10;
            // 地面と空
            for (int dx = -span; dx <= span; dx++) {
                for (int dz = -span; dz <= span; dz++) {
                    level.setBlock(new BlockPos(c.getX() + dx, gy, c.getZ() + dz), com.yayuy.overgrownvegetation.registry.ModBlocks.OVERGROWN_GRASS_BLOCK.get().defaultBlockState(), 2);
                    for (int y = gy + 1; y <= gy + 30; y++) level.setBlock(new BlockPos(c.getX() + dx, y, c.getZ() + dz), Blocks.AIR.defaultBlockState(), 2);
                }
            }
            net.minecraft.world.level.block.state.BlockState log = Blocks.OAK_LOG.defaultBlockState();
            net.minecraft.world.level.block.state.BlockState leaf = Blocks.OAK_LEAVES.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true);
            // A: 円の縁の内側の木。葉が円の外へはみ出す
            BlockPos a = c.offset(r - 1, 0, 0);
            for (int y = 1; y <= 7; y++) level.setBlock(a.atY(gy + y), log, 2);
            BlockPos aOut = a.atY(gy + 7).offset(3, 0, 0); // 円の外の葉
            for (int i = 1; i <= 3; i++) level.setBlock(a.atY(gy + 7).offset(i, 0, 0), leaf, 2);
            // B: 真ん中寄りの森の木(過繁茂の木)
            BlockPos b = c.offset(-3, 0, 2);
            for (int y = 1; y <= 9; y++) level.setBlock(b.atY(gy + y), com.yayuy.overgrownvegetation.registry.ModBlocks.OVERGROWN_LOG.get().defaultBlockState(), 2);
            level.setBlock(b.atY(gy + 10), leaf, 2);
            // C: 円の外の木。葉の腕が円の中へ差し掛かる
            BlockPos f = c.offset(0, 0, -(r + 3));
            for (int y = 1; y <= 8; y++) level.setBlock(f.atY(gy + y), log, 2);
            for (int i = 1; i <= 5; i++) level.setBlock(f.atY(gy + 8).offset(0, 0, i), leaf, 2); // z = -(r+2) … -(r-2)
            BlockPos fOuter = f.atY(gy + 8).offset(0, 0, 1), fInner = f.atY(gy + 8).offset(0, 0, 5);
            // 巨大樹の幹(円の中・形の外)と地面の下草
            BlockPos g = c.offset(0, 0, r - 1);
            for (int y = 1; y <= 6; y++) level.setBlock(g.atY(gy + y), com.yayuy.overgrownvegetation.registry.ModBlocks.GIANT_OVERGROWN_LOG.get().defaultBlockState(), 2);
            BlockPos grass = c.offset(-(r - 1), 1, 0);
            level.setBlock(grass, Blocks.GRASS.defaultBlockState(), 2);

            helper.assertTrue(OrganClearing.groundY(level, a.getX(), a.getZ()) == gy, "幹の上を地面と数える: " + OrganClearing.groundY(level, a.getX(), a.getZ()) + "(地面 " + gy + ")");
            helper.assertTrue(OrganClearing.groundY(level, g.getX(), g.getZ()) == gy, "巨大樹の幹の上を地面と数える");
            helper.assertTrue(OrganWorld.surface(level, c.getX(), c.getZ()) == gy, "器官の高さが地面でない: " + OrganWorld.surface(level, c.getX(), c.getZ()));

            OrganClearing.clearNow(level, o, bp);
            helper.assertTrue(o.cleared, "払い終わらない");
            for (int y = 1; y <= 7; y++) helper.assertTrue(level.getBlockState(a.atY(gy + y)).isAir(), "円の中の木の幹が残る y+" + y);
            helper.assertTrue(level.getBlockState(aOut).isAir(), "円の中の木のはみ出した葉が残る");
            for (int y = 1; y <= 10; y++) helper.assertTrue(level.getBlockState(b.atY(gy + y)).isAir(), "過繁茂の木が残る y+" + y);
            helper.assertTrue(level.getBlockState(f.atY(gy + 4)).is(Blocks.OAK_LOG), "円の外の木の幹まで払った");
            helper.assertTrue(level.getBlockState(fOuter).is(Blocks.OAK_LEAVES), "円の外の木の円の外の葉まで払った");
            helper.assertTrue(level.getBlockState(fInner).isAir(), "円に差し掛かった葉が残る");
            helper.assertTrue(level.getBlockState(g.atY(gy + 3)).is(com.yayuy.overgrownvegetation.registry.ModBlocks.GIANT_OVERGROWN_LOG.get()), "巨大樹を払った");
            helper.assertTrue(level.getBlockState(grass).is(Blocks.GRASS), "地面の下草を払った");
            helper.assertTrue(level.getBlockState(c.atY(gy)).is(com.yayuy.overgrownvegetation.registry.ModBlocks.OVERGROWN_GRASS_BLOCK.get()), "地面を払った");

            OrganWorld.buildNow(level, data, o);
            helper.assertTrue(o.y == gy, "器官が地面に建たない: y=" + o.y + "(地面 " + gy + ")");
            // 芯を壊して枯れた後、払った所が段階的に戻る(11-19: 下草 → 苗木)
            data.wither(level, o, "テスト");
            OrganTable tt = DesignData.organs();
            long day = 24000L;
            // 1 段目の途中: 2 段の草が増え、苗木はまだ無い
            o.witheredAt = PhaseData.clockNow(level) - (long) (tt.regrowDays * day * tt.regrowUndergrowthShare * 0.9D);
            for (int k = 0; k < 4000; k++) OrganClearing.regrow(level, o, bp, PhaseData.clockNow(level));
            int[] mid = countRegrowth(level, c, gy, r);
            helper.assertTrue(o.regrowUnder > 0, "下草の段が進まない");
            helper.assertTrue(mid[1] > 0, "下草の段で背の高い草が増えない: " + mid[1]);
            helper.assertTrue(mid[0] == 0, "下草の段なのに苗木がある: " + mid[0]);
            // 終わりまで: 苗木が植わり、戻り終える
            o.witheredAt = PhaseData.clockNow(level) - (long) (tt.regrowDays * day) - 1L;
            for (int k = 0; k < 4000 && o.regrown >= 0; k++) OrganClearing.regrow(level, o, bp, PhaseData.clockNow(level));
            int[] end = countRegrowth(level, c, gy, r);
            helper.assertTrue(o.regrown == -1, "生え直しが終わらない: " + o.regrown);
            helper.assertTrue(end[0] >= 2, "払った所に苗木が生え直さない: " + end[0]);
            // 戻り終えたとき、枯れた器官のそばに巨大樹の苗木が 1 本(11-19 ユーザー決定で既定 on)
            if (DesignData.organs().regrowGiantSapling) {
                int giants = 0;
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (level.getBlockState(new BlockPos(c.getX() + dx, gy + 1, c.getZ() + dz)).is(com.yayuy.overgrownvegetation.registry.ModBlocks.GIANT_OVERGROWN_SAPLING.get())) giants++;
                    }
                }
                helper.assertTrue(giants == 1, "巨大樹の苗木が 1 本でない: " + giants);
            }
            // 植えた苗木が本当に木になる(育ちを進める。巨大樹の苗木は育てない = テスト場に収まらない)
            boolean grew = false;
            net.minecraft.util.RandomSource rs = net.minecraft.util.RandomSource.create(42L);
            for (int dx = -r; dx <= r && !grew; dx++) {
                for (int dz = -r; dz <= r && !grew; dz++) {
                    BlockPos sp = new BlockPos(c.getX() + dx, gy + 1, c.getZ() + dz);
                    for (int k = 0; k < 6; k++) {
                        BlockState s = level.getBlockState(sp);
                        if (!s.is(com.yayuy.overgrownvegetation.registry.ModBlocks.OVERGROWN_SAPLING.get())
                                || !(s.getBlock() instanceof net.minecraft.world.level.block.SaplingBlock sb)) break;
                        sb.advanceTree(level, sp, s, rs);
                    }
                    if (level.getBlockState(sp).is(net.minecraft.tags.BlockTags.LOGS)) grew = true;
                }
            }
            helper.assertTrue(grew, "生え直した苗木が木にならない");
            helper.succeed();
        } finally {
            for (int id : planted) data.remove(id);
            dir.setBudget(level, budgetBefore);
            if (pd.getPoints() < pointsBefore) pd.addPointsUnscaledForTests(level, pointsBefore - pd.getPoints());
            if (span > 0) {
                net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(c).inflate(span + 8);
                for (OrganTentacle t : level.getEntitiesOfClass(OrganTentacle.class, box)) t.discard();
                for (int dx = -span; dx <= span; dx++) {
                    for (int dz = -span; dz <= span; dz++) {
                        for (int y = gy - 12; y <= gy + 40; y++) {
                            BlockPos p = new BlockPos(c.getX() + dx, y, c.getZ() + dz);
                            if (y > gy || !level.getBlockState(p).isAir()) level.setBlock(p, y > gy ? Blocks.AIR.defaultBlockState() : Blocks.DIRT.defaultBlockState(), 2);
                        }
                    }
                }
            }
        }
    }

    /** {@code /ov forest}(11-19): 働いている器官が種類の行に数えられ、育っているだけの種類は「まだ働いていない」と出る。 */
    @GameTest(template = "gametest_empty7", batch = "organ_forest_cmd", timeoutTicks = 20)
    public static void forestCommandCountsOrgans(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ForestOrgans data = ForestOrgans.get(level);
        BlockPos c = helper.absolutePos(new BlockPos(3, 1, 3));
        List<Integer> planted = new ArrayList<>();
        try {
            // 遠くに置く(読み込まれていないので建たない)。同じ呼び出しの中で消すので役割は回らない
            ForestOrgans.Organ tower = data.plant(level, OrganKind.TOWER, true, 0, 11, 0, c.getX() + 200000, c.getZ() + 200000);
            ForestOrgans.Organ nursery = data.plant(level, OrganKind.NURSERY, true, 0, 12, 0, c.getX() + 200400, c.getZ() + 200000);
            planted.add(tower.id);
            planted.add(nursery.id);
            tower.state = ForestOrgans.State.ALIVE;
            OrganWorld.invalidateCaches();
            String towerLine = null, nurseryLine = null;
            for (net.minecraft.network.chat.Component line : ForestCommand.lines(level)) {
                if (!(line.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents tc)) continue;
                if (!tc.getKey().endsWith(".kind_line")) continue;
                Object kind = tc.getArgs()[0];
                if (kind instanceof net.minecraft.network.chat.Component kc && kc.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents kt) {
                    Object eff = tc.getArgs()[4];
                    String effKey = eff instanceof net.minecraft.network.chat.Component ec && ec.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents et ? et.getKey() : "?";
                    if (kt.getKey().endsWith(".tower")) towerLine = tc.getArgs()[2] + "/" + effKey;
                    if (kt.getKey().endsWith(".nursery")) nurseryLine = tc.getArgs()[3] + "/" + effKey;
                }
            }
            helper.assertTrue(towerLine != null && towerLine.startsWith("1/") && towerLine.endsWith("effect.tower"), "胞子の塔の行が違う: " + towerLine);
            helper.assertTrue(nurseryLine != null && nurseryLine.startsWith("1/") && nurseryLine.endsWith("effect.inactive"), "育っている苗床の行が違う: " + nurseryLine);
            helper.succeed();
        } finally {
            for (int id : planted) data.remove(id);
            OrganWorld.invalidateCaches();
        }
    }

    /**
     * 触手の叩く合図(11-20 ユーザー「叩く直前に巻きがほどける短い合図を入れ、ほどけた瞬間だけ当てる」): 人が来ると合図が始まり、
     * 合図(巻きがほどける)の間は当たらない・ほどけて伸びた瞬間に当たる・1 振り 1 回・叩く時刻と長さがクライアントへ送られる・ほどけ具合が 0→1→0。
     */
    @GameTest(template = "gametest_empty7", batch = "organ_tentacle", timeoutTicks = 20)
    public static void tentacleTelegraphsBeforeStrike(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        OrganTentacle t = new OrganTentacle(OrganEntities.TENTACLE.get(), level); // 世界に入れない(tick させず、時刻を手で進める)
        BlockPos c = helper.absolutePos(new BlockPos(3, 4, 3));
        t.setPos(c.getX() + 0.5D, c.getY(), c.getZ() + 0.5D);
        t.setup(0, 8, 0.0F);
        OrganTable tt = DesignData.organs();
        long now = 1000L;
        net.minecraft.world.phys.Vec3 end = t.position().add(t.point(t.segments(), (float) now));
        net.minecraft.world.entity.player.Player p = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        p.setPos(end.x, end.y - 0.5D, end.z);
        p.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        t.getEntityData().packDirty(); // ここまでの変化は数えない
        List<net.minecraft.world.entity.player.Player> near = List.of(p);

        helper.assertTrue(t.unfurlAt(now) == 0.0F, "叩く前から巻きがほどけている");
        t.strikeStep(now, near);
        int w = t.windupTicks();
        helper.assertTrue(w == Math.max(1, (int) Math.round(tt.tentacleWindupSeconds * 20.0D)), "合図の長さが設計値と違う: " + w);
        helper.assertTrue(t.strikeAt() == now + w, "合図が始まらない: " + t.strikeAt());
        var dirty = t.getEntityData().packDirty();
        boolean sentAt = false;
        if (dirty != null) {
            for (var v : dirty) {
                if (v.id() == OrganTentacle.STRIKE_AT.id()) sentAt = true;
            }
        }
        helper.assertTrue(sentAt, "叩く時刻がクライアントへ送られない");
        // 長さは同期する値(変わったときだけ送られ、既定と同じなら現れたときの値で届く)。中身が設計値か
        helper.assertTrue(t.hitTicks() == Math.max(1, (int) Math.round(tt.tentacleHitSeconds * 20.0D))
                && t.recoverTicks() == Math.max(1, (int) Math.round(tt.tentacleRecoverSeconds * 20.0D)), "当たり・巻き戻りの長さが設計値と違う");
        // 合図の間(巻きがほどけている)は当たらない
        for (int k = 0; k < w; k++) {
            t.strikeStep(now + k, near);
            helper.assertTrue(p.getDeltaMovement().lengthSqr() < 1.0E-8D, "合図の間に当たった: " + k + " tick 目");
            float u = t.unfurlAt(now + k + 0.5D);
            helper.assertTrue(u > 0.0F && u < 1.0F, "合図の間のほどけ具合が 0〜1 の間でない: " + u);
        }
        // ほどけて伸びた瞬間に当たる
        t.strikeStep(now + w, near);
        helper.assertTrue(t.unfurlAt(now + w) == 1.0F, "当たりの瞬間に伸び切っていない");
        helper.assertTrue(p.getDeltaMovement().horizontalDistanceSqr() > 1.0E-4D, "伸びた瞬間に当たらない");
        // 1 振りで 2 回は当てない
        p.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        for (int k = 1; k < t.hitTicks(); k++) t.strikeStep(now + w + k, near);
        helper.assertTrue(p.getDeltaMovement().lengthSqr() < 1.0E-8D, "1 振りで 2 回当たった");
        // 巻き戻る(当たりの後は当てない)
        long after = now + w + t.hitTicks();
        t.strikeStep(after, near);
        helper.assertTrue(p.getDeltaMovement().lengthSqr() < 1.0E-8D, "当たりの後に当たった");
        float back = t.unfurlAt(after + t.recoverTicks() / 2.0D);
        helper.assertTrue(back > 0.0F && back < 1.0F, "巻き戻りの途中のほどけ具合が 0〜1 の間でない: " + back);
        helper.assertTrue(t.unfurlAt(after + t.recoverTicks() + 1) == 0.0F, "巻き戻らない");
        // 次まで(cooldown)は始めない
        t.strikeStep(after + 1, near);
        helper.assertTrue(t.strikeAt() == now + w, "次までの間に次の合図が始まった");
        helper.succeed();
    }

    /**
     * 器官を見に飛ぶ・知らせ(11-20 ユーザー「器官ができたらその場所に飛べるコマンドを」): tp latest はいちばん新しい器官を選び、
     * 器官の中ではなく少し離れた所から器官の方を向く。知らせが off なら出ず、on なら [飛ぶ] がその器官へのコマンドになる。
     */
    @GameTest(template = "gametest_empty7", batch = "organ_notice", timeoutTicks = 20)
    public static void organTpLatestAndNotice(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ForestOrgans data = ForestOrgans.get(level);
        BlockPos c = helper.absolutePos(new BlockPos(3, 1, 3));
        List<Integer> planted = new ArrayList<>();
        boolean before = OrganNotices.enabled();
        try {
            ForestOrgans.Organ older = data.plant(level, OrganKind.SAC, true, 0, 21, 0, c.getX() - 6, c.getZ());
            ForestOrgans.Organ newer = data.plant(level, OrganKind.TOWER, true, 0, 22, 0, c.getX() + 2, c.getZ() + 2);
            planted.add(older.id);
            planted.add(newer.id);
            ForestOrgans.Organ pick = OrganCommand.latest(data);
            helper.assertTrue(pick != null && pick.id == newer.id, "tp latest がいちばん新しい器官を選ばない: " + (pick == null ? "無し" : pick.id));
            net.minecraft.world.phys.Vec3 from = new net.minecraft.world.phys.Vec3(c.getX() + 40, c.getY(), c.getZ() + 2);
            OrganNotices.View v = OrganNotices.viewFrom(level, newer, from, true);
            double d = Math.hypot(v.at().x - (newer.x + 0.5D), v.at().z - (newer.z + 0.5D));
            helper.assertTrue(d > 8.0D && d < 40.0D, "飛ぶ先が器官の中か遠すぎる: " + d);
            // 器官の方を向いている(yaw から向きを出して、器官への向きと比べる)
            double yr = Math.toRadians(v.yaw());
            net.minecraft.world.phys.Vec3 facing = new net.minecraft.world.phys.Vec3(-Math.sin(yr), 0.0D, Math.cos(yr));
            net.minecraft.world.phys.Vec3 toOrgan = new net.minecraft.world.phys.Vec3(newer.x + 0.5D - v.at().x, 0.0D, newer.z + 0.5D - v.at().z).normalize();
            helper.assertTrue(facing.dot(toOrgan) > 0.95D, "器官の方を向いていない: " + facing.dot(toOrgan));
            // 知らせ: off なら出ない・on なら [飛ぶ] がその器官へのコマンド
            OrganNotices.setEnabled(false);
            OrganNotices.lastSent = null;
            OrganNotices.organ(level, newer);
            helper.assertTrue(OrganNotices.lastSent == null, "知らせが off なのに出た");
            OrganNotices.setEnabled(true);
            OrganNotices.organ(level, newer);
            helper.assertTrue(("/ov perf organ tp " + newer.id).equals(OrganNotices.lastSent), "[飛ぶ] のコマンドが違う: " + OrganNotices.lastSent);
            helper.succeed();
        } finally {
            OrganNotices.setEnabled(before);
            for (int id : planted) data.remove(id);
        }
    }

    /** 円の中の地面の 1 つ上の [過繁茂の苗木, 2 段の大きなシダ・葦] の数。 */
    private static int[] countRegrowth(ServerLevel level, BlockPos c, int gy, int r) {
        int[] n = new int[2];
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                BlockState s = level.getBlockState(new BlockPos(c.getX() + dx, gy + 1, c.getZ() + dz));
                if (s.is(com.yayuy.overgrownvegetation.registry.ModBlocks.OVERGROWN_SAPLING.get())) n[0]++;
                if (s.is(com.yayuy.overgrownvegetation.registry.ModBlocks.OVERGROWN_TALL_FERN.get())
                        || s.is(com.yayuy.overgrownvegetation.registry.ModBlocks.OVERGROWN_SPORE_REED.get())) n[1]++;
            }
        }
        return n;
    }

    /** 巨大樹の根元の近くの升には建てない(11-19 ユーザー「巨大樹は避けて」)。巨大樹の記録は (x << 32) | z で読む(目印にも使う)。 */
    @GameTest(template = "gametest_empty7", batch = "organ_world", timeoutTicks = 20)
    public static void avoidsGiantTrees(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PhaseData pd = PhaseData.get(level);
        OrganTable t = DesignData.organs();
        int cx = 620_000, cz = 620_000;
        int gx = cx * 16 + 8 + 20, gz = cz * 16 + 8;
        long packed = ((long) gx << 32) | (gz & 0xFFFFFFFFL);
        try {
            pd.recordGiantTreeOrigin(gx, gz);
            helper.assertTrue(OrganPlanner.nearGiantTree(pd, cx, cz, false, t), "巨大樹の 20 ブロック先に大きい版が建つ");
            helper.assertTrue(OrganPlanner.nearGiantTree(pd, cx, cz, true, t), "巨大樹の 20 ブロック先に小さい版が建つ");
            int far = (int) Math.ceil((t.giantAvoid + 40.0D) / 16.0D);
            helper.assertTrue(!OrganPlanner.nearGiantTree(pd, cx - far, cz, false, t), "巨大樹から遠い升まで外す");
            boolean anchored = false;
            for (long[] a : OrganPlanner.anchors(level, ForestOrgans.get(level), pd)) {
                if (a[0] == gx >> 4 && a[1] == gz >> 4) anchored = true;
            }
            helper.assertTrue(anchored, "巨大樹の升が目印に入らない(記録の読み違い)");
            helper.succeed();
        } finally {
            pd.forgetGiantTreeForTests(packed);
        }
    }
}
