package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.horde.VirtualHordes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Map;

/**
 * 仮想の群れ({@link VirtualHordes}、2026-11-18 大群の軽量化 A)の決まり。どのテストも自前の {@link VirtualHordes} を作り(世界の保存データには触らない)、
 * プレイヤーの代わりの位置({@link VirtualHordes#playersForTests})で本物にする・戻すを動かす。本物の総数は世界全体の印で数えるので、テストごとに別の batch。
 * <ul>
 *     <li>本物にする → 戻す → 本物にする で頭数が変わらない(戻すのは死なせない discard)</li>
 *     <li>保存の往復で群れ・頭数・行き先が同じ</li>
 *     <li>本物の総数の上限で止まり、残りは同じ群れに仮想のまま待つ</li>
 *     <li>読み込まれていない所の群れは、プレイヤーが近くても本物にならない</li>
 * </ul>
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class VirtualHordeGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(VirtualHordeGameTests.class);
        }

        private Registration() {
        }
    }

    private static final ResourceLocation GREGARIA = OvergrownVegetation.id("gregaria");

    private static EntityType<?> gregaria() {
        return BuiltInRegistries.ENTITY_TYPE.get(GREGARIA);
    }

    /** テスト場の中の印つきの本物の数。 */
    private static void expectTrue(GameTestHelper helper, boolean ok, String what) {
        if (!ok) {
            VirtualHordes.playersForTests = null;
            VirtualHordes.realCapForTests = null;
            helper.fail(what);
        }
    }

    /** テスト場の中の印つきの本物の数。 */
    private static int realIn(GameTestHelper helper) {
        AABB box = helper.getBounds().inflate(16.0D);
        return helper.getLevel().getEntitiesOfClass(Mob.class, box, m -> m.isAlive() && m.getTags().contains(VirtualHordes.TAG)).size();
    }

    private static void cleanup(GameTestHelper helper) {
        AABB box = helper.getBounds().inflate(16.0D);
        for (Mob m : helper.getLevel().getEntitiesOfClass(Mob.class, box, m -> m.getTags().contains(VirtualHordes.TAG))) m.discard();
        VirtualHordes.playersForTests = null;
        VirtualHordes.realCapForTests = null;
    }

    @GameTest(template = "gametest_empty24", batch = "virtual_horde_roundtrip", timeoutTicks = 100)
    public static void roundTripKeepsHeadcount(GameTestHelper helper) {
        VirtualHordes h = new VirtualHordes();
        Vec3 c = helper.absoluteVec(new Vec3(12.0D, 1.0D, 12.0D));
        VirtualHordes.playersForTests = List.of(new double[]{c.x, c.z});
        h.spawn(c.x, c.y, c.z, -1, c.x + 300.0D, c.z, "", Map.of(gregaria(), 12));
        h.materialize(helper.getLevel(), DesignData.perf());
        int real1 = realIn(helper);
        expectTrue(helper, real1 > 0, "本物にならなかった");
        expectTrue(helper, real1 + h.virtualHeads() == 12, "本物にした後の合計が 12 でない: 本物 " + real1 + "・仮想 " + h.virtualHeads());
        // プレイヤーが遠くへ行った → 全部仮想へ戻る(死なせない)
        VirtualHordes.playersForTests = List.of(new double[]{c.x + 5000.0D, c.z});
        int back = h.dematerialize(helper.getLevel(), DesignData.perf());
        helper.runAfterDelay(2, () -> {
            expectTrue(helper, back == real1, "戻した数 " + back + " が本物の数 " + real1 + " と違う");
            expectTrue(helper, realIn(helper) == 0, "戻した後に本物が残っている: " + realIn(helper));
            expectTrue(helper, h.virtualHeads() == 12, "戻した後の仮想が 12 でない: " + h.virtualHeads());
            // 戻ってきた → また本物に
            VirtualHordes.playersForTests = List.of(new double[]{c.x, c.z});
            h.materialize(helper.getLevel(), DesignData.perf());
            int real2 = realIn(helper);
            boolean ok = real2 > 0 && real2 + h.virtualHeads() == 12;
            cleanup(helper);
            expectTrue(helper, ok, "2 回目の本物化の合計が 12 でない: 本物 " + real2 + "・仮想 " + h.virtualHeads());
            helper.succeed();
        });
    }

    @GameTest(template = "gametest_empty7", batch = "virtual_horde_save")
    public static void saveRoundTripKeepsSwarms(GameTestHelper helper) {
        VirtualHordes h = new VirtualHordes();
        h.spawn(100.5D, 70.0D, -40.25D, 7, 400.0D, -40.0D, "ov_large_raid", Map.of(gregaria(), 9));
        h.spawn(-300.0D, 64.0D, 12.0D, -1, 0.0D, 0.0D, "", Map.of(gregaria(), 3));
        CompoundTag tag = h.save(new CompoundTag(), helper.getLevel().registryAccess());
        VirtualHordes r = VirtualHordes.load(tag);
        expectTrue(helper, r.swarms().size() == 2, "群れの数が 2 でない: " + r.swarms().size());
        expectTrue(helper, r.virtualHeads() == 12, "頭数が 12 でない: " + r.virtualHeads());
        VirtualHordes.Swarm s = r.swarms().get(0);
        expectTrue(helper, Math.abs(s.x() - 100.5D) < 1.0E-9D && Math.abs(s.z() + 40.25D) < 1.0E-9D, "位置が違う");
        expectTrue(helper, s.counts().getOrDefault(GREGARIA, 0) == 9, "種の頭数が違う: " + s.counts());
        expectTrue(helper, s.id() != r.swarms().get(1).id(), "id が重なった");
        helper.succeed();
    }

    @GameTest(template = "gametest_empty24", batch = "virtual_horde_cap", timeoutTicks = 100)
    public static void realCapLeavesRestVirtual(GameTestHelper helper) {
        VirtualHordes h = new VirtualHordes();
        Vec3 c = helper.absoluteVec(new Vec3(12.0D, 1.0D, 12.0D));
        VirtualHordes.playersForTests = List.of(new double[]{c.x, c.z});
        VirtualHordes.realCapForTests = 5;
        h.spawn(c.x, c.y, c.z, -1, c.x + 300.0D, c.z, "", Map.of(gregaria(), 12));
        h.materialize(helper.getLevel(), DesignData.perf());
        int real = realIn(helper), virt = h.virtualHeads(), swarms = h.swarms().size();
        cleanup(helper);
        expectTrue(helper, real == 5, "上限 5 のはずが本物 " + real);
        expectTrue(helper, virt == 7 && swarms == 1, "残りが同じ群れに仮想で残っていない: 仮想 " + virt + "・群れ " + swarms);
        helper.succeed();
    }

    @GameTest(template = "gametest_empty7", batch = "virtual_horde_unloaded")
    public static void unloadedSwarmStaysVirtual(GameTestHelper helper) {
        VirtualHordes h = new VirtualHordes();
        BlockPos far = helper.absolutePos(new BlockPos(3, 1, 3)).offset(40000, 0, 40000); // 読み込まれていない所
        VirtualHordes.playersForTests = List.of(new double[]{far.getX(), far.getZ()});
        h.spawn(far.getX(), 64.0D, far.getZ(), -1, far.getX() + 300.0D, far.getZ(), "", Map.of(gregaria(), 6));
        h.materialize(helper.getLevel(), DesignData.perf());
        h.move(helper.getLevel(), 5, DesignData.perf());
        int virt = h.virtualHeads();
        boolean loaded = helper.getLevel().getChunkSource().getChunkNow(far.getX() >> 4, far.getZ() >> 4) != null;
        VirtualHordes.playersForTests = null;
        expectTrue(helper, !loaded, "読み込まれていない所のはずのチャンクが読み込まれた(本物化か進める処理が読み込んだ)");
        expectTrue(helper, virt == 6, "読み込まれていない所で本物になった: 仮想 " + virt);
        helper.succeed();
    }
}
