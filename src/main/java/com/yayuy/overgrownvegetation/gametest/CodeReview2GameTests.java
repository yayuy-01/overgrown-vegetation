package com.yayuy.overgrownvegetation.gametest;

import com.mojang.authlib.GameProfile;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.director.G1Fate;
import com.yayuy.overgrownvegetation.director.ForestDirector;
import com.yayuy.overgrownvegetation.entity.AbstractParasitizedMonster;
import com.yayuy.overgrownvegetation.entity.DevelopedParasitized;
import com.yayuy.overgrownvegetation.entity.g1.G1Developed;
import com.yayuy.overgrownvegetation.entity.g1.skill.G1CarryGoal;
import com.yayuy.overgrownvegetation.entity.g1.skill.G1Latch;
import com.yayuy.overgrownvegetation.horde.VirtualHordes;
import com.yayuy.overgrownvegetation.registry.ModG1;
import com.yayuy.overgrownvegetation.registry.ModResearch;
import com.yayuy.overgrownvegetation.research.Research;
import com.yayuy.overgrownvegetation.research.ResearchData;
import com.yayuy.overgrownvegetation.research.ResearchTree;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * コードの見直し 第 2 回(11-18)の直し。正本は Vault「コードの見直し 第 2 回(11-18)」。器官の分は organ パッケージの
 * {@code OrganReview2GameTests}。世界に 1 つの状態(研究・仮想の群れの数え)を使うテストは別々の batch。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class CodeReview2GameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(CodeReview2GameTests.class);
        }

        private Registration() {
        }
    }

    private CodeReview2GameTests() {
    }

    private static EntityType<?> gregaria() {
        return BuiltInRegistries.ENTITY_TYPE.get(OvergrownVegetation.id("gregaria"));
    }

    // ------------------------------------------------------------------ 第 1 便

    /** ①1: 張り付いたクモ・取り付いたチビゾンビの一時の無重力を保存しない。古い保存は読むときに外す。本物の無重力は残す。 */
    @GameTest(template = "gametest_empty7", batch = "review2_g1_gravity", timeoutTicks = 20)
    public static void perchAndLatchDoNotSaveNoGravity(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        DevelopedParasitized spider = ModG1.developed(G1Developed.SPIDER).get().create(level);
        spider.setPerched(true);
        helper.assertTrue(spider.isNoGravity(), "張り付いている間に無重力でない");
        CompoundTag tag = spider.saveWithoutId(new CompoundTag());
        helper.assertTrue(!tag.getBoolean("NoGravity"), "張り付きの無重力が保存された");
        // 古い保存(本物の印として書かれていた)
        CompoundTag old = tag.copy();
        old.putBoolean("NoGravity", true);
        old.remove("OvGravityFixed");
        DevelopedParasitized back = ModG1.developed(G1Developed.SPIDER).get().create(level);
        back.load(old);
        helper.assertTrue(!back.isNoGravity(), "古い保存の張り付きの無重力が残った(読み込み後ずっと浮く)");
        // 本物の無重力(コマンドで付けた)は保存の往復で残る
        DevelopedParasitized real = ModG1.developed(G1Developed.SPIDER).get().create(level);
        real.setNoGravity(true);
        DevelopedParasitized real2 = ModG1.developed(G1Developed.SPIDER).get().create(level);
        real2.load(real.saveWithoutId(new CompoundTag()));
        helper.assertTrue(real2.isNoGravity(), "本物の無重力が保存の往復で消えた");
        // 取り付き: 本物の印を付けない
        DevelopedParasitized baby = ModG1.developed(G1Developed.BABY_ZOMBIE).get().create(level);
        Husk target = EntityType.HUSK.create(level);
        BlockPos at = helper.absolutePos(new BlockPos(3, 2, 3));
        baby.moveTo(at.getX() + 0.5D, at.getY(), at.getZ() + 0.5D);
        target.moveTo(at.getX() + 1.0D, at.getY(), at.getZ() + 0.5D);
        G1Latch latch = new G1Latch(baby, target, 100, 20, 0.0F, 0.0D, -1, true);
        helper.assertTrue(latch.holdsUp(), "体に付く取り付きが重力を切らない");
        CompoundTag bt = baby.saveWithoutId(new CompoundTag());
        helper.assertTrue(!bt.getBoolean("NoGravity"), "取り付きが本物の無重力の印を付けた");
        helper.succeed();
    }

    /** ①2・①3: 根付きへ歩く個体は背負われない。時間切れの後に背負われた・水に浮いたまま長く経つと取り消して道を外す。 */
    @GameTest(template = "gametest_empty7", batch = "review2_g1_settle", timeoutTicks = 40)
    public static void settlingG1HasAnExit(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        if (!DesignData.director().enabled) {
            helper.succeed();
            return;
        }
        BlockPos at = helper.absolutePos(new BlockPos(3, 2, 3));
        AbstractParasitizedMonster small = ModG1.PARASITIZED_BABY_ZOMBIE.get().create(level);
        Boat boat = EntityType.BOAT.create(level);
        List<net.minecraft.world.entity.Entity> made = new ArrayList<>();
        try {
            small.moveTo(at.getX() + 0.5D, at.getY(), at.getZ() + 0.5D);
            small.setNoAi(true);
            level.addFreshEntity(small);
            made.add(small);
            helper.assertTrue(G1CarryGoal.carriable(small), "道の無い段階 0 のチビゾンビを背負えない");
            G1Fate fate = small.fate();
            helper.assertTrue(fate != null, "段階 0 に司令塔の行き先が無い");
            fate.beginRooted();
            helper.assertTrue(!G1CarryGoal.carriable(small), "根付きへ歩く個体を背負える");
            // 背負われた(乗った)まま時間切れを大きく過ぎる → 取り消し
            boat.moveTo(at.getX() + 0.5D, at.getY(), at.getZ() + 0.5D);
            level.addFreshEntity(boat);
            made.add(boat);
            small.startRiding(boat, true);
            int timeout = DesignData.director().walkTimeoutTicks;
            fate.setWalkedForTests(timeout + 10);
            fate.tick();
            helper.assertTrue(fate.route() == ForestDirector.Route.ROOTED, "時間切れの直後に取り消した(待つ間が無い)");
            fate.setWalkedForTests(timeout + G1Fate.STUCK_GIVE_UP + 5);
            fate.tick();
            helper.assertTrue(fate.route() == ForestDirector.Route.NONE, "乗ったまま長く経っても根付きの道のまま: " + fate.route());
            helper.succeed();
        } finally {
            for (var e : made) e.discard();
        }
    }

    /** ③1: 終わった大規模レイドの攻め手は、遠くで仮想へ戻すときに群れへ戻さず消える。今の本番の攻め手は群れへ戻る。前の本番の生き残りも消える。 */
    @GameTest(template = "gametest_empty7", batch = "review2_raid_over", timeoutTicks = 40)
    public static void endedRaidAttackersDoNotReturn(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        VirtualHordes h = new VirtualHordes();
        Vec3 c = helper.absoluteVec(new Vec3(3.5D, 2.0D, 3.5D));
        VirtualHordes.playersForTests = List.of(new double[]{c.x + 5000.0D, c.z});
        List<Mob> made = new ArrayList<>();
        try {
            Mob over = spawnAttacker(level, c, 5.0D, made);
            VirtualHordes.raidForTests = Double.NaN; // 本番は終わった
            h.dematerialize(level, DesignData.perf());
            helper.assertTrue(!over.isAlive() || over.isRemoved(), "終わった襲撃の攻め手が残っている");
            helper.assertTrue(h.virtualHeads() == 0, "終わった襲撃の攻め手が仮想の群れへ戻された: " + h.virtualHeads());
            Mob live = spawnAttacker(level, c, 5.0D, made);
            Mob stale = spawnAttacker(level, c.add(1.0D, 0.0D, 0.0D), 4.0D, made);
            VirtualHordes.raidForTests = 5.0D; // 本番の最中(世代 5)
            h.dematerialize(level, DesignData.perf());
            helper.assertTrue(live.isRemoved() && stale.isRemoved(), "遠くの攻め手が戻されない");
            helper.assertTrue(h.virtualHeads() == 1, "今の本番の攻め手 1 体だけが群れへ戻るはず: " + h.virtualHeads());
            helper.succeed();
        } finally {
            VirtualHordes.raidForTests = null;
            VirtualHordes.playersForTests = null;
            for (Mob m : made) m.discard();
        }
    }

    private static Mob spawnAttacker(ServerLevel level, Vec3 at, double raid, List<Mob> made) {
        Mob m = (Mob) gregaria().create(level);
        m.moveTo(at.x, at.y, at.z);
        m.setNoAi(true);
        level.addFreshEntity(m);
        VirtualHordes.markRaidAttackerForTests(m, raid, -1, at.x + 400.0D, at.z);
        made.add(m);
        return m;
    }

    /** ②5(ユーザー決定 1): 研究台の画面からやめると、標本はやめた人へではなく研究台の上に落ちる。 */
    @GameTest(template = "gametest_empty7", batch = "review2_research_cancel", timeoutTicks = 20)
    public static void cancelDropsSpecimensOnBench(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        Research.cancel(server, null);
        ResearchData.get(server).clearAll();
        helper.setBlock(new BlockPos(3, 2, 3), ModResearch.BENCH.get().defaultBlockState());
        BlockPos bench = helper.absolutePos(new BlockPos(3, 2, 3));
        FakePlayer p = FakePlayerFactory.get(level, new GameProfile(UUID.nameUUIDFromBytes("ov_review2_research".getBytes()), "ov_review2_research"));
        p.getInventory().clearContent();
        ResearchTree.Specimen sp = ResearchTree.get().node("t1_fiber").specimens().get(0);
        p.getInventory().add(new ItemStack(sp.asItem(), sp.count()));
        try {
            helper.assertTrue(Research.start(p, bench, "t1_fiber") == null, "研究を始められない");
            helper.assertTrue(Research.count(p, sp.asItem()) == 0, "標本が取られていない");
            helper.assertTrue(Research.cancelOnBench(server, level, bench), "やめられない");
            helper.assertTrue(Research.count(p, sp.asItem()) == 0, "やめた人に標本が渡った");
            int dropped = 0;
            for (ItemEntity it : level.getEntitiesOfClass(ItemEntity.class, new AABB(bench).inflate(2.0D))) {
                if (it.getItem().is(sp.asItem())) dropped += it.getItem().getCount();
                it.discard();
            }
            helper.assertTrue(dropped == sp.count(), "研究台の上に落ちた標本が " + dropped + "(払ったのは " + sp.count() + ")");
            helper.succeed();
        } finally {
            Research.cancel(server, null);
            ResearchData.get(server).clearAll();
        }
    }
}
