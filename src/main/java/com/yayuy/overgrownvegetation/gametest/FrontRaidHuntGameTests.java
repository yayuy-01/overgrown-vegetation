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
            int want = Math.min(raidable, (int) Math.ceil(raidable * DesignData.front().raidShareMedium));
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
}
