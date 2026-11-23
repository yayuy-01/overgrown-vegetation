package com.yayuy.overgrownvegetation.gametest;

import com.mojang.authlib.GameProfile;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corpse.G1Species;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.director.BaseMemory;
import com.yayuy.overgrownvegetation.director.DirectorGates;
import com.yayuy.overgrownvegetation.director.RaidScales;
import com.yayuy.overgrownvegetation.front.FrontDirector;
import com.yayuy.overgrownvegetation.front.FrontHunts;
import com.yayuy.overgrownvegetation.front.FrontRaids;
import com.yayuy.overgrownvegetation.front.Fronts;
import com.yayuy.overgrownvegetation.front.UnitTerrain;
import com.yayuy.overgrownvegetation.horde.UnitPurpose;
import com.yayuy.overgrownvegetation.horde.VirtualHordes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 前線と部隊 区切り 3(2026-11-21): 狩り(抜き出す・守りの下限を守る・見失って探して戻る・同じ人にあける)と、
 * 襲撃(前線から出す・集まる所で待ってから拠点へ・撃退を覚えて次を大きく・保存と復元)。
 * 世界に 1 つの前線・部隊・拠点の台帳を使うので、遠くの座標で、終わったら自分の分を消す。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class FrontRaidHuntGameTests {

    private static final String BATCH = "front_raid_hunt";

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(FrontRaidHuntGameTests.class);
        }

        private Registration() {
        }
    }

    private FrontRaidHuntGameTests() {
    }

    private static ResourceLocation zombie() {
        return BuiltInRegistries.ENTITY_TYPE.getKey(G1Species.ZOMBIE.entityType());
    }

    private static void cleanup(VirtualHordes h, Fronts fronts, int frontId) {
        for (VirtualHordes.Swarm s : new ArrayList<>(h.swarms())) if (s.front() == frontId) h.removeUnit(s);
        Fronts.Front f = fronts.byId(frontId);
        if (f != null) fronts.fronts().remove(f);
    }

    private static int heads(VirtualHordes h, int frontId, UnitPurpose p) {
        int n = 0;
        for (VirtualHordes.Swarm s : h.swarms()) if (s.front() == frontId && s.purpose() == p) n += s.size();
        return n;
    }

    /** 見られたプレイヤーへ近くの部隊から抜き出す(上限・守りの下限)。見失ったら最後に見た所で探して、守りへ戻る。同じ人には数分あける。 */
    @GameTest(template = "gametest_empty7", batch = BATCH, timeoutTicks = 20)
    public static void huntPullsSearchesAndReturns(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Fronts fronts = Fronts.get(level.getServer());
        VirtualHordes h = VirtualHordes.get(level.getServer());
        Integer phase = DirectorGates.phaseOverride();
        double x = 11_000_000.5D, z = 11_000_000.5D;
        Fronts.Front f = fronts.addSourceForTests(x, z);
        ServerPlayer p = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "ov_hunt_test"));
        try {
            DirectorGates.setPhaseForTests(3);
            UnitTerrain.terrainForTests = (cx, cz) -> UnitTerrain.LAND;
            p.moveTo(x + 40, 64, z);
            VirtualHordes.Swarm d = h.newUnit(x + 20, 64, z, f.id(), UnitPurpose.DEFEND, level.getGameTime());
            d.addHeads(zombie(), 30, 100.0D);
            VirtualHordes.Swarm e = h.newUnit(x + 30, 64, z, f.id(), UnitPurpose.EXPAND, level.getGameTime());
            e.addHeads(zombie(), 10, 100.0D);
            FrontHunts.seenForTests = q -> true;
            FrontHunts.tick(level, List.of(p));
            int want = DesignData.front().huntMax(3);
            int hunting = heads(h, f.id(), UnitPurpose.HUNT);
            helper.assertTrue(hunting == want, "狩りの体数が上限どおりでない: " + hunting + "(欲しい " + want + ")");
            int floor = (int) Math.ceil(DesignData.front().defendShareQuiet * 40);
            helper.assertTrue(heads(h, f.id(), UnitPurpose.DEFEND) >= floor, "守りの下限を割って抜いた: " + heads(h, f.id(), UnitPurpose.DEFEND));
            // 見失う → 最後に見た所へ着く → 探す → 戻る
            FrontHunts.seenForTests = q -> false;
            for (int i = 0; i < 40; i++) h.move(level, 100, DesignData.perf());
            FrontHunts.ageForTests(p.getUUID(), (long) (DesignData.front().huntLostSeconds * 20.0D) + 20L);
            FrontHunts.tick(level, List.of(p)); // 探し始め
            helper.assertTrue(heads(h, f.id(), UnitPurpose.HUNT) == want, "見失ってすぐ戻った(探していない)");
            FrontHunts.ageForTests(p.getUUID(), (long) (DesignData.front().huntSearchSeconds * 20.0D) + 20L);
            FrontHunts.tick(level, List.of(p));
            helper.assertTrue(heads(h, f.id(), UnitPurpose.HUNT) == 0, "探し終えても狩る部隊が残った");
            helper.assertTrue(FrontHunts.cooldownUntil(p.getUUID()) > level.getGameTime(), "同じ人にあける時間が付かない");
            // あけている間は見られても狩らない
            FrontHunts.seenForTests = q -> true;
            FrontHunts.tick(level, List.of(p));
            helper.assertTrue(heads(h, f.id(), UnitPurpose.HUNT) == 0, "あけている間にまた狩った");
            helper.succeed();
        } finally {
            FrontHunts.seenForTests = null;
            FrontHunts.clearForTests();
            UnitTerrain.terrainForTests = null;
            DirectorGates.setPhaseForTests(phase);
            cleanup(h, fronts, f.id());
        }
    }

    /** 島の拠点へ運ぶきっかけ: 拠点へ向かっているのに 2 秒ごとに STALL_CHECKS 回ほとんど進まなければ「阻まれた」。進めば数え直す。 */
    @GameTest(template = "gametest_empty7", batch = BATCH, timeoutTicks = 20)
    public static void stalledRaidUnitIsDetected(GameTestHelper helper) {
        VirtualHordes h = VirtualHordes.load(new CompoundTag());
        VirtualHordes.Swarm s = h.newUnit(100, 64, 100, 1, UnitPurpose.RAID, 0L);
        s.addHeads(zombie(), 10, 100.0D);
        s.assign(UnitPurpose.RAID, 5000, 100, 0L);
        boolean hit = false;
        for (int i = 0; i <= FrontRaids.STALL_CHECKS; i++) hit = FrontRaids.stalled(s);
        helper.assertTrue(hit, "止まった部隊を見分けない");
        helper.succeed();
    }

    /** 中の襲撃: 前線から出し、拠点の近くの集まる所で待ってから一緒に拠点へ。撃退されたら拠点が覚え、次は大きくする。 */
    @GameTest(template = "gametest_empty7", batch = BATCH, timeoutTicks = 20)
    public static void raidGathersAdvancesAndRemembersRepel(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Fronts fronts = Fronts.get(level.getServer());
        VirtualHordes h = VirtualHordes.get(level.getServer());
        BaseMemory memory = BaseMemory.get(level.getServer());
        FrontRaids data = FrontRaids.get(level.getServer());
        double x = 12_000_000.5D, z = 12_000_000.5D;
        Fronts.Front f = fronts.addSourceForTests(x, z);
        boolean ticking = FrontDirector.tickInGameTests;
        try {
            FrontDirector.tickInGameTests = true; // FrontRaids.active を開く
            UnitTerrain.terrainForTests = (cx, cz) -> UnitTerrain.LAND;
            memory.clear();
            double day = BaseMemory.today(level.getServer());
            int bcx = ((int) x + 600) >> 4, bcz = (int) z >> 4;
            memory.addPresence(ChunkPos.asLong(bcx, bcz), DesignData.director().base.rememberScore * 2.0D, day);
            memory.update(day);
            BaseMemory.Base b = memory.bases().get(0);
            memory.setStageForTests(b, BaseMemory.Stage.RECOGNIZED, day);
            VirtualHordes.Swarm d = h.newUnit(x + 10, 64, z, f.id(), UnitPurpose.DEFEND, level.getGameTime());
            d.addHeads(zombie(), 40, 100.0D);
            int raidable = 40 - (int) Math.ceil(DesignData.front().defendShareQuiet * 40);
            int sent = FrontRaids.send(level, b, day, RaidScales.RaidScale.MEDIUM);
            int want = Math.min(FrontRaids.cap(b, day, RaidScales.RaidScale.MEDIUM), Math.min(raidable, (int) Math.ceil(raidable * DesignData.front().raidShareMedium)));
            helper.assertTrue(sent == want && heads(h, f.id(), UnitPurpose.RAID) == want, "出した数が違う: " + sent + "(欲しい " + want + ")");
            FrontRaids.Raid r = data.raids().get(data.raids().size() - 1);
            helper.assertTrue(r.gathering(), "中の襲撃が集まる所へ行かない");
            for (VirtualHordes.Swarm s : h.swarms()) {
                if (s.purpose() == UnitPurpose.RAID && s.front() == f.id()) {
                    double gd = Math.hypot(s.tx() - b.centerX(), s.tz() - b.centerZ());
                    helper.assertTrue(Math.abs(gd - DesignData.front().raidGatherBlocks) < 1.0D, "集まる所が拠点の近くでない: " + gd);
                }
            }
            for (int i = 0; i < 200; i++) h.move(level, 100, DesignData.perf());
            FrontRaids.tick(level);
            helper.assertTrue(!r.gathering(), "全部集まっても拠点へ進まない");
            for (VirtualHordes.Swarm s : h.swarms()) {
                if (s.purpose() == UnitPurpose.RAID && s.front() == f.id()) {
                    helper.assertTrue(Math.abs(s.tx() - b.centerX()) < 1.0D && Math.abs(s.tz() - b.centerZ()) < 1.0D, "拠点へ向かっていない");
                }
            }
            // 撃退: ほとんど倒されて残りもいなくなった
            r.addDeadForTests(r.sent());
            for (VirtualHordes.Swarm s : h.swarms()) if (s.purpose() == UnitPurpose.RAID && s.front() == f.id()) s.takeHeads(s.size());
            d.takeHeads(d.size()); // 11-23 C: 継ぎ足せる樹域が残っていると全滅でも終わらないので、樹域も空にする
            FrontRaids.tick(level);
            helper.assertTrue(b.repelled() == 1, "撃退を覚えない: " + b.repelled());
            helper.assertTrue(FrontRaids.repelFactor(b) > 1.0D, "撃退されたのに次が大きくならない");
            helper.assertTrue(!data.raids().contains(r), "終わった襲撃が残った");
            // 保存と復元
            FrontRaids back = FrontRaids.load(data.save(new CompoundTag(), level.registryAccess()));
            helper.assertTrue(back.raids().size() == data.raids().size(), "襲撃の台帳が復元されない");
            helper.succeed();
        } finally {
            FrontDirector.tickInGameTests = ticking;
            UnitTerrain.terrainForTests = null;
            memory.clear();
            data.clearForTests();
            cleanup(h, fronts, f.id());
        }
    }

    /** 11-22 ユーザー決定 案 3: 太い前線でも、最初の襲撃は 15・小は認識からの日数の上限(認識した日は raidCapStart)・中は同時 40 で、日数の上限(小の 2 倍)まで継ぎ足す。 */
    @GameTest(template = "gametest_empty7", batch = BATCH, timeoutTicks = 20)
    public static void raidSizesFollowDayCaps(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Fronts fronts = Fronts.get(level.getServer());
        VirtualHordes h = VirtualHordes.get(level.getServer());
        BaseMemory memory = BaseMemory.get(level.getServer());
        FrontRaids data = FrontRaids.get(level.getServer());
        double x = 13_000_000.5D, z = 13_000_000.5D;
        Fronts.Front f = fronts.addSourceForTests(x, z);
        boolean ticking = FrontDirector.tickInGameTests;
        try {
            FrontDirector.tickInGameTests = true;
            UnitTerrain.terrainForTests = (cx, cz) -> UnitTerrain.LAND;
            memory.clear();
            double day = BaseMemory.today(level.getServer());
            int bcx = ((int) x + 600) >> 4, bcz = (int) z >> 4;
            memory.addPresence(ChunkPos.asLong(bcx, bcz), DesignData.director().base.rememberScore * 2.0D, day);
            memory.update(day);
            BaseMemory.Base b = memory.bases().get(0);
            memory.setStageForTests(b, BaseMemory.Stage.RECOGNIZED, day);
            for (int i = 0; i < 20; i++) {
                VirtualHordes.Swarm d = h.newUnit(x + 10 + i, 64, z, f.id(), UnitPurpose.DEFEND, level.getGameTime());
                d.addHeads(zombie(), 50, 100.0D);
            }
            int first = FrontRaids.send(level, b, day, RaidScales.RaidScale.SMALL);
            int firstWant = DesignData.director().assault.firstRaid.total();
            helper.assertTrue(first == firstWant, "最初の襲撃が約 15 でない: " + first + "(欲しい " + firstWant + ")");
            memory.waveSent(b, day, level.getRandom());
            int small = FrontRaids.send(level, b, day, RaidScales.RaidScale.SMALL);
            int smallWant = DesignData.front().raidCapSmall(0.0D);
            helper.assertTrue(small == smallWant, "認識した日の送り込みが日数の上限でない: " + small + "(欲しい " + smallWant + ")");
            int medium = FrontRaids.send(level, b, day, RaidScales.RaidScale.MEDIUM);
            int alive = com.yayuy.overgrownvegetation.director.RaidScales.mediumMaxAlive();
            helper.assertTrue(medium == alive, "貯めた襲撃の 1 回目が同時の上限でない: " + medium + "(欲しい " + alive + ")");
            FrontRaids.Raid r = data.raids().get(data.raids().size() - 1);
            for (int i = 0; i < 200; i++) h.move(level, 100, DesignData.perf());
            FrontRaids.tick(level); // 集まり終えて拠点へ
            helper.assertTrue(!r.gathering(), "貯めた襲撃が拠点へ進まない");
            // 半分倒された → 継ぎ足しで同時の上限へ戻る。日数の合計の上限で止まる
            int total = FrontRaids.mediumTotal(b, day);
            for (int round = 0; round < 10; round++) {
                int left = alive / 2;
                for (VirtualHordes.Swarm s : h.swarms()) {
                    if (s.purpose() != UnitPurpose.RAID || !r.group().equals(s.hunt()) || left <= 0) continue;
                    left -= s.takeHeads(Math.min(left, s.size())).values().stream().mapToInt(Integer::intValue).sum();
                }
                int now = 0;
                for (VirtualHordes.Swarm s : h.swarms()) if (s.purpose() == UnitPurpose.RAID && r.group().equals(s.hunt())) now += s.size();
                FrontRaids.refill(level, h, r, b, now, new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap());
            }
            helper.assertTrue(r.sent() == total, "継ぎ足しが合計の上限で止まらない: " + r.sent() + "(欲しい " + total + ")");
            helper.succeed();
        } finally {
            FrontDirector.tickInGameTests = ticking;
            UnitTerrain.terrainForTests = null;
            memory.clear();
            data.clearForTests();
            cleanup(h, fronts, f.id());
        }
    }

    /** 11-22: いちばん近い樹域に出せる部隊が無ければ、範囲の中で次に近い樹域から出す。どこからも出せなければ送ったと数えず試し直す(回数を超えたら数える)。 */
    @GameTest(template = "gametest_empty7", batch = BATCH, timeoutTicks = 20)
    public static void raidFallsBackToNextTerritoryAndRetries(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Fronts fronts = Fronts.get(level.getServer());
        VirtualHordes h = VirtualHordes.get(level.getServer());
        BaseMemory memory = BaseMemory.get(level.getServer());
        FrontRaids data = FrontRaids.get(level.getServer());
        double x = 14_000_000.5D, z = 14_000_000.5D;
        Fronts.Front empty = fronts.addSourceForTests(x + 300, z); // 拠点にいちばん近いが部隊がいない(建ったばかりの巨大樹)
        Fronts.Front full = fronts.addSourceForTests(x, z);
        boolean ticking = FrontDirector.tickInGameTests;
        try {
            FrontDirector.tickInGameTests = true;
            UnitTerrain.terrainForTests = (cx, cz) -> UnitTerrain.LAND;
            memory.clear();
            double day = BaseMemory.today(level.getServer());
            int bcx = ((int) x + 600) >> 4, bcz = (int) z >> 4;
            memory.addPresence(ChunkPos.asLong(bcx, bcz), DesignData.director().base.rememberScore * 2.0D, day);
            memory.update(day);
            BaseMemory.Base b = memory.bases().get(0);
            memory.setStageForTests(b, BaseMemory.Stage.RECOGNIZED, day);
            memory.waveSent(b, day, level.getRandom()); // 最初の襲撃は済んだ
            helper.assertTrue(DesignData.front().raidRangeBlocks >= 600.0D, "テストの前提: 範囲が狭い");
            VirtualHordes.Swarm d = h.newUnit(x + 10, 64, z, full.id(), UnitPurpose.DEFEND, level.getGameTime());
            d.addHeads(zombie(), 50, 100.0D);
            int sent = FrontRaids.send(level, b, day, RaidScales.RaidScale.SMALL);
            helper.assertTrue(sent > 0 && heads(h, full.id(), UnitPurpose.RAID) == sent, "次に近い樹域から出さない: " + sent);
            helper.assertTrue(heads(h, empty.id(), UnitPurpose.RAID) == 0, "部隊のいない樹域から出した");
            // どこからも出せない: 送ったと数えず試し直す
            cleanup(h, fronts, full.id());
            fronts.fronts().add(full);
            int waves = b.wavesSent();
            int zero = FrontRaids.send(level, b, day, RaidScales.RaidScale.SMALL);
            helper.assertTrue(zero == 0, "部隊がいないのに出した: " + zero);
            int max = DesignData.front().raidRetryMax;
            for (int i = 0; i < max; i++) {
                helper.assertTrue(FrontRaids.retryEmpty(memory, b, day, 0), "出せなかったのに試し直さない(" + (i + 1) + " 回目)");
                helper.assertTrue(Math.abs(b.nextWaveDay() - (day + DesignData.front().raidRetryDays)) < 1.0E-6D, "試し直しの日が違う: " + b.nextWaveDay());
            }
            helper.assertTrue(!FrontRaids.retryEmpty(memory, b, day, 0), "回数を超えても試し直し続ける");
            helper.assertTrue(b.wavesSent() == waves, "試し直しで送った回数が増えた");
            helper.succeed();
        } finally {
            FrontDirector.tickInGameTests = ticking;
            UnitTerrain.terrainForTests = null;
            memory.clear();
            data.clearForTests();
            cleanup(h, fronts, empty.id());
            cleanup(h, fronts, full.id());
        }
    }

    /** 11-23 ユーザー決定 A: 小の襲撃も raidRangeBlocks の中の樹域から近い順に集める(1 つの樹域だけから出さない)。 */
    @GameTest(template = "gametest_empty7", batch = BATCH, timeoutTicks = 20)
    public static void smallRaidGathersFromSeveralTerritories(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Fronts fronts = Fronts.get(level.getServer());
        VirtualHordes h = VirtualHordes.get(level.getServer());
        BaseMemory memory = BaseMemory.get(level.getServer());
        FrontRaids data = FrontRaids.get(level.getServer());
        double x = 15_000_000.5D, z = 15_000_000.5D;
        Fronts.Front a = fronts.addSourceForTests(x + 300, z); // 拠点にいちばん近い
        Fronts.Front c = fronts.addSourceForTests(x, z);        // 次に近い
        boolean ticking = FrontDirector.tickInGameTests;
        try {
            FrontDirector.tickInGameTests = true;
            UnitTerrain.terrainForTests = (cx, cz) -> UnitTerrain.LAND;
            memory.clear();
            double day = BaseMemory.today(level.getServer());
            int bcx = ((int) x + 600) >> 4, bcz = (int) z >> 4;
            memory.addPresence(ChunkPos.asLong(bcx, bcz), DesignData.director().base.rememberScore * 2.0D, day);
            memory.update(day);
            BaseMemory.Base b = memory.bases().get(0);
            memory.setStageForTests(b, BaseMemory.Stage.RECOGNIZED, day);
            memory.waveSent(b, day, level.getRandom()); // 最初の襲撃は済んだ
            VirtualHordes.Swarm da = h.newUnit(x + 310, 64, z, a.id(), UnitPurpose.DEFEND, level.getGameTime());
            da.addHeads(zombie(), 50, 100.0D);
            VirtualHordes.Swarm dc = h.newUnit(x + 10, 64, z, c.id(), UnitPurpose.DEFEND, level.getGameTime());
            dc.addHeads(zombie(), 50, 100.0D);
            int raidable = 50 - (int) Math.ceil(DesignData.front().defendShareQuiet * 50);
            int each = Math.min(raidable, (int) Math.ceil(raidable * DesignData.front().raidShareSmall * FrontRaids.repelFactor(b)));
            int cap = FrontRaids.cap(b, day, RaidScales.RaidScale.SMALL);
            helper.assertTrue(each < cap && 2 * each <= cap, "テストの前提: 1 つの樹域の分で上限に届く(" + each + " / " + cap + ")");
            int sent = FrontRaids.send(level, b, day, RaidScales.RaidScale.SMALL);
            int fromA = heads(h, a.id(), UnitPurpose.RAID), fromC = heads(h, c.id(), UnitPurpose.RAID);
            helper.assertTrue(fromA == each && fromC == each && sent == 2 * each,
                    "2 つの樹域から集めていない: 近い " + fromA + "・次 " + fromC + "・計 " + sent + "(各 " + each + ")");
            helper.succeed();
        } finally {
            FrontDirector.tickInGameTests = ticking;
            UnitTerrain.terrainForTests = null;
            memory.clear();
            data.clearForTests();
            cleanup(h, fronts, a.id());
            cleanup(h, fronts, c.id());
        }
    }

    /** 11-23 ユーザー決定 C: 中は継ぎ足しの間に全部倒されても、継ぎ足せる間は終わらない(合計の上限に届いたら終わる)。 */
    @GameTest(template = "gametest_empty7", batch = BATCH, timeoutTicks = 20)
    public static void mediumRaidRefillsAfterWipe(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Fronts fronts = Fronts.get(level.getServer());
        VirtualHordes h = VirtualHordes.get(level.getServer());
        BaseMemory memory = BaseMemory.get(level.getServer());
        FrontRaids data = FrontRaids.get(level.getServer());
        double x = 16_000_000.5D, z = 16_000_000.5D;
        Fronts.Front f = fronts.addSourceForTests(x, z);
        boolean ticking = FrontDirector.tickInGameTests;
        try {
            FrontDirector.tickInGameTests = true;
            UnitTerrain.terrainForTests = (cx, cz) -> UnitTerrain.LAND;
            memory.clear();
            double day = BaseMemory.today(level.getServer());
            int bcx = ((int) x + 600) >> 4, bcz = (int) z >> 4;
            memory.addPresence(ChunkPos.asLong(bcx, bcz), DesignData.director().base.rememberScore * 2.0D, day);
            memory.update(day);
            BaseMemory.Base b = memory.bases().get(0);
            memory.setStageForTests(b, BaseMemory.Stage.RECOGNIZED, day);
            memory.waveSent(b, day, level.getRandom());
            for (int i = 0; i < 20; i++) {
                VirtualHordes.Swarm d = h.newUnit(x + 10 + i, 64, z, f.id(), UnitPurpose.DEFEND, level.getGameTime());
                d.addHeads(zombie(), 50, 100.0D);
            }
            int alive = com.yayuy.overgrownvegetation.director.RaidScales.mediumMaxAlive();
            int total = FrontRaids.mediumTotal(b, day);
            helper.assertTrue(total > alive, "テストの前提: 合計の上限が同時の上限より大きい");
            int first = FrontRaids.send(level, b, day, RaidScales.RaidScale.MEDIUM);
            helper.assertTrue(first == alive, "1 回目が同時の上限でない: " + first);
            FrontRaids.Raid r = data.raids().get(data.raids().size() - 1);
            for (int i = 0; i < 200; i++) h.move(level, 100, DesignData.perf());
            FrontRaids.tick(level); // 集まり終えて拠点へ
            helper.assertTrue(!r.gathering(), "拠点へ進まない");
            int rounds = 0;
            while (data.raids().contains(r) && rounds++ < 20) {
                // 継ぎ足しの間に全部倒された
                for (VirtualHordes.Swarm s : h.swarms()) if (s.purpose() == UnitPurpose.RAID && r.group().equals(s.hunt())) s.takeHeads(s.size());
                int before = r.sent();
                FrontRaids.tick(level);
                if (before < total) {
                    helper.assertTrue(data.raids().contains(r), "合計の上限の手前で全滅して終わった: 出した " + before + " / " + total);
                    helper.assertTrue(r.sent() == Math.min(total, before + alive), "全滅の後に同時の上限まで継ぎ足していない: " + before + " → " + r.sent());
                }
            }
            helper.assertTrue(!data.raids().contains(r), "合計の上限に届いても終わらない");
            helper.assertTrue(r.sent() == total, "出した合計が上限でない: " + r.sent() + " / " + total);
            helper.succeed();
        } finally {
            FrontDirector.tickInGameTests = ticking;
            UnitTerrain.terrainForTests = null;
            memory.clear();
            data.clearForTests();
            cleanup(h, fronts, f.id());
        }
    }

    /** 試しの拠点を 1 つ、(x + 600, z) に認識させ、最初の襲撃は済ませる。 */
    private static BaseMemory.Base recognizedBase(ServerLevel level, BaseMemory memory, double x, double z, double day) {
        memory.clear();
        int bcx = ((int) x + 600) >> 4, bcz = (int) z >> 4;
        memory.addPresence(ChunkPos.asLong(bcx, bcz), DesignData.director().base.rememberScore * 2.0D, day);
        memory.update(day);
        BaseMemory.Base b = memory.bases().get(0);
        memory.setStageForTests(b, BaseMemory.Stage.RECOGNIZED, day);
        memory.waveSent(b, day, level.getRandom());
        return b;
    }

    /**
     * 11-23 片付け E の後のつなぎ直し: ワールドの設定「Mob の湧く量」は樹域の目標の頭数に、「襲撃の数」は前線の襲撃の上限と出す割合に掛かる。
     * 既定(100%)なら今までどおり。
     */
    @GameTest(template = "gametest_empty7", batch = BATCH, timeoutTicks = 20)
    public static void worldSettingsScaleTargetsAndRaids(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        net.minecraft.server.MinecraftServer server = level.getServer();
        Fronts fronts = Fronts.get(server);
        VirtualHordes h = VirtualHordes.get(server);
        BaseMemory memory = BaseMemory.get(server);
        FrontRaids data = FrontRaids.get(server);
        com.yayuy.overgrownvegetation.difficulty.WorldDifficultyData wd = com.yayuy.overgrownvegetation.difficulty.WorldDifficultyData.get(server);
        double x = 17_000_000.5D, z = 17_000_000.5D;
        Fronts.Front f = fronts.addSourceForTests(x, z);
        boolean ticking = FrontDirector.tickInGameTests;
        try {
            FrontDirector.tickInGameTests = true;
            UnitTerrain.terrainForTests = (cx, cz) -> UnitTerrain.LAND;
            double day = BaseMemory.today(server);
            BaseMemory.Base b = recognizedBase(level, memory, x, z, day);
            for (int i = 0; i < 20; i++) {
                VirtualHordes.Swarm d = h.newUnit(x + 10 + i, 64, z, f.id(), UnitPurpose.DEFEND, level.getGameTime());
                d.addHeads(zombie(), 50, 100.0D);
            }
            // 湧く量
            FrontDirector.readWorldSettings(server);
            int base = FrontDirector.target(f.id());
            helper.assertTrue(base > 0, "テストの前提: 目標の頭数が 0");
            wd.set(com.yayuy.overgrownvegetation.difficulty.DifficultySetting.MOB_SPAWN_AMOUNT, 2.0D);
            FrontDirector.readWorldSettings(server);
            helper.assertTrue(FrontDirector.target(f.id()) == Math.round(base * 2.0D), "湧く量 200% で目標が 2 倍にならない: " + FrontDirector.target(f.id()) + "(元 " + base + ")");
            wd.set(com.yayuy.overgrownvegetation.difficulty.DifficultySetting.MOB_SPAWN_AMOUNT, 0.0D);
            FrontDirector.readWorldSettings(server);
            helper.assertTrue(FrontDirector.target(f.id()) == 0, "湧く量 0% で目標が 0 にならない: " + FrontDirector.target(f.id()));
            wd.reset(com.yayuy.overgrownvegetation.difficulty.DifficultySetting.MOB_SPAWN_AMOUNT);
            FrontDirector.readWorldSettings(server);
            helper.assertTrue(FrontDirector.target(f.id()) == base, "既定に戻しても目標が戻らない");
            // 襲撃の数
            int cap1 = FrontRaids.cap(b, day, RaidScales.RaidScale.SMALL), med1 = FrontRaids.mediumTotal(b, day);
            wd.set(com.yayuy.overgrownvegetation.difficulty.DifficultySetting.RAID_COUNT_SMALL, 2.0D);
            wd.set(com.yayuy.overgrownvegetation.difficulty.DifficultySetting.RAID_COUNT_MEDIUM, 0.5D);
            com.yayuy.overgrownvegetation.difficulty.RaidSettings.push(server);
            int cap2 = FrontRaids.cap(b, day, RaidScales.RaidScale.SMALL), med2 = FrontRaids.mediumTotal(b, day);
            helper.assertTrue(cap2 == Math.round(cap1 * 2.0D), "小の数 200% で上限が 2 倍にならない: " + cap1 + " → " + cap2);
            helper.assertTrue(med2 == Math.round(med1 * 0.5D), "中の数 50% で合計の上限が半分にならない: " + med1 + " → " + med2);
            int total = 20 * 50;
            int raidable = total - (int) Math.ceil(DesignData.front().defendShareQuiet * total);
            int want = Math.min(cap2, Math.min(raidable, (int) Math.ceil(raidable * DesignData.front().raidShareSmall * FrontRaids.repelFactor(b) * 2.0D)));
            int sent = FrontRaids.send(level, b, day, RaidScales.RaidScale.SMALL);
            helper.assertTrue(sent == want, "小の数 200% の送り込みの数が違う: " + sent + "(欲しい " + want + ")");
            helper.succeed();
        } finally {
            wd.reset(com.yayuy.overgrownvegetation.difficulty.DifficultySetting.MOB_SPAWN_AMOUNT);
            wd.reset(com.yayuy.overgrownvegetation.difficulty.DifficultySetting.RAID_COUNT_SMALL);
            wd.reset(com.yayuy.overgrownvegetation.difficulty.DifficultySetting.RAID_COUNT_MEDIUM);
            com.yayuy.overgrownvegetation.difficulty.RaidSettings.push(server);
            FrontDirector.readWorldSettings(server);
            FrontDirector.tickInGameTests = ticking;
            UnitTerrain.terrainForTests = null;
            memory.clear();
            data.clearForTests();
            cleanup(h, fronts, f.id());
        }
    }

    /**
     * 11-23 つなぎ直し: 「大きな襲撃の同時の上限」は前線の大きな襲撃の部隊が同時に本物になる数を抑える(数えるのはその襲撃の本物だけ)。
     * 小・中の襲撃と守りの部隊には掛からない。
     */
    @GameTest(template = "gametest_empty7", batch = BATCH, timeoutTicks = 20)
    public static void largeRaidUnitsRespectMaxAlive(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        net.minecraft.server.MinecraftServer server = level.getServer();
        Fronts fronts = Fronts.get(server);
        VirtualHordes h = VirtualHordes.get(server);
        BaseMemory memory = BaseMemory.get(server);
        FrontRaids data = FrontRaids.get(server);
        com.yayuy.overgrownvegetation.difficulty.WorldDifficultyData wd = com.yayuy.overgrownvegetation.difficulty.WorldDifficultyData.get(server);
        double x = 18_000_000.5D, z = 18_000_000.5D;
        Fronts.Front f = fronts.addSourceForTests(x, z);
        boolean ticking = FrontDirector.tickInGameTests;
        net.minecraft.world.entity.monster.Zombie real = null;
        try {
            FrontDirector.tickInGameTests = true;
            UnitTerrain.terrainForTests = (cx, cz) -> UnitTerrain.LAND;
            double day = BaseMemory.today(server);
            BaseMemory.Base b = recognizedBase(level, memory, x, z, day);
            VirtualHordes.Swarm defend = null;
            for (int i = 0; i < 20; i++) {
                defend = h.newUnit(x + 10 + i, 64, z, f.id(), UnitPurpose.DEFEND, level.getGameTime());
                defend.addHeads(zombie(), 50, 100.0D);
            }
            helper.assertTrue(com.yayuy.overgrownvegetation.horde.VirtualRaids.countKey(level, defend) == null, "守りの部隊に襲撃の枠が掛かった");
            helper.assertTrue(FrontRaids.send(level, b, day, RaidScales.RaidScale.SMALL) > 0, "小の襲撃が出ない");
            FrontRaids.Raid small = data.raids().get(data.raids().size() - 1);
            helper.assertTrue(FrontRaids.send(level, b, day, RaidScales.RaidScale.LARGE) > 0, "大の襲撃が出ない");
            FrontRaids.Raid large = data.raids().get(data.raids().size() - 1);
            VirtualHordes.Swarm su = null, lu = null;
            for (VirtualHordes.Swarm s : h.swarms()) {
                if (s.purpose() != UnitPurpose.RAID) continue;
                if (small.group().equals(s.hunt())) su = s;
                if (large.group().equals(s.hunt())) lu = s;
            }
            helper.assertTrue(su != null && lu != null, "襲撃の部隊が見つからない");
            helper.assertTrue(com.yayuy.overgrownvegetation.horde.VirtualRaids.countKey(level, su) == null, "小の襲撃に大の枠が掛かった");
            Integer key = com.yayuy.overgrownvegetation.horde.VirtualRaids.countKey(level, lu);
            helper.assertTrue(key != null && key < 0, "大の襲撃の部隊に枠の鍵が無い: " + key);
            int max = com.yayuy.overgrownvegetation.director.LargeRaids.maxAlive();
            int room0 = com.yayuy.overgrownvegetation.horde.VirtualRaids.room(level, lu, new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap());
            helper.assertTrue(room0 == max, "本物がいないのに枠が上限でない: " + room0 + " / " + max);
            real = helper.spawn(net.minecraft.world.entity.EntityType.ZOMBIE, 1, 2, 1);
            real.getPersistentData().putLong(VirtualHordes.UNIT_KEY, lu.id());
            int room1 = com.yayuy.overgrownvegetation.horde.VirtualRaids.room(level, lu, new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap());
            helper.assertTrue(room1 == max - 1, "本物になった 1 体を数えない: " + room1 + "(欲しい " + (max - 1) + ")");
            wd.set(com.yayuy.overgrownvegetation.difficulty.DifficultySetting.LARGE_RAID_MAX_ALIVE, 20.0D);
            int room2 = com.yayuy.overgrownvegetation.horde.VirtualRaids.room(level, lu, new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap());
            helper.assertTrue(room2 == 19, "設定の上限 20 が効かない: " + room2);
            helper.succeed();
        } finally {
            if (real != null) real.discard();
            wd.reset(com.yayuy.overgrownvegetation.difficulty.DifficultySetting.LARGE_RAID_MAX_ALIVE);
            FrontDirector.tickInGameTests = ticking;
            UnitTerrain.terrainForTests = null;
            memory.clear();
            data.clearForTests();
            cleanup(h, fronts, f.id());
        }
    }

    /** 11-23: 前線の襲撃(小でも)が拠点へ向かっている・着いている間は、その拠点のそばで寝られない。終われば戻る。 */
    @GameTest(template = "gametest_empty7", batch = BATCH, timeoutTicks = 20)
    public static void cannotSleepWhileFrontRaidIsOn(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        net.minecraft.server.MinecraftServer server = level.getServer();
        Fronts fronts = Fronts.get(server);
        VirtualHordes h = VirtualHordes.get(server);
        BaseMemory memory = BaseMemory.get(server);
        FrontRaids data = FrontRaids.get(server);
        double x = 19_000_000.5D, z = 19_000_000.5D;
        Fronts.Front f = fronts.addSourceForTests(x, z);
        boolean ticking = FrontDirector.tickInGameTests;
        try {
            FrontDirector.tickInGameTests = true;
            UnitTerrain.terrainForTests = (cx, cz) -> UnitTerrain.LAND;
            double day = BaseMemory.today(server);
            BaseMemory.Base b = recognizedBase(level, memory, x, z, day);
            VirtualHordes.Swarm d = h.newUnit(x + 10, 64, z, f.id(), UnitPurpose.DEFEND, level.getGameTime());
            d.addHeads(zombie(), 50, 100.0D);
            double bx = b.centerX(), bz = b.centerZ();
            boolean before = com.yayuy.overgrownvegetation.spawn.RaidNightSleep.raidTonight(server, bx, bz);
            helper.assertTrue(!FrontRaids.raidOn(server, b.id()), "テストの前提: 前線の襲撃がもう来ている");
            helper.assertTrue(FrontRaids.send(level, b, day, RaidScales.RaidScale.SMALL) > 0, "小の襲撃が出ない");
            helper.assertTrue(FrontRaids.raidOn(server, b.id()), "出した襲撃が拠点へ向かっていると数えない");
            helper.assertTrue(com.yayuy.overgrownvegetation.spawn.RaidNightSleep.raidTonight(server, bx, bz), "前線の襲撃が来ているのに拠点のそばで寝られる");
            double far = com.yayuy.overgrownvegetation.spawn.RaidNightSleep.RANGE + 200.0D;
            helper.assertTrue(!com.yayuy.overgrownvegetation.spawn.RaidNightSleep.raidTonight(server, bx + far, bz), "拠点から遠い所でも寝られない");
            data.clearForTests();
            helper.assertTrue(com.yayuy.overgrownvegetation.spawn.RaidNightSleep.raidTonight(server, bx, bz) == before, "襲撃が終わっても寝られない");
            helper.succeed();
        } finally {
            FrontDirector.tickInGameTests = ticking;
            UnitTerrain.terrainForTests = null;
            memory.clear();
            data.clearForTests();
            cleanup(h, fronts, f.id());
        }
    }
}
