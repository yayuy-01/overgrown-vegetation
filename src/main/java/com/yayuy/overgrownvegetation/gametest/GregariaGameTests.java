package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.entity.GregariaEntity;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import com.yayuy.overgrownvegetation.registry.ModCrawlers;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * グレガリアの段 1(2026-11-15): 「小さな地這いの虫」の型の移動。1 ブロックの段は這い上がる・2 段の壁と柵は越えない・
 * 1 マスの低い隙間をくぐる・仲間どうしは押し合わず詰め込みのダメージも受けない。
 * うろつき(小走り)は近くにプレイヤーがいるときだけなのでここでは見ない(テストクライアントで見る)。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class GregariaGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(GregariaGameTests.class);
        }

        private Registration() {
        }
    }

    private GregariaGameTests() {
    }

    private static GregariaEntity placeAt(GameTestHelper helper, double x, double z, float yaw) {
        GregariaEntity g = helper.spawnWithNoFreeWill(ModCrawlers.GREGARIA.get(), new BlockPos(0, 2, 0));
        Vec3 p = helper.absoluteVec(new Vec3(x, 2.0D, z));
        g.moveTo(p.x, p.y, p.z, yaw, 0.0F);
        g.snapFacing(yaw);
        g.setNoAi(false);
        g.setCustomName(Component.literal("nostroll"));
        return g;
    }

    private static void walkTo(GregariaEntity g, GameTestHelper helper, Vec3 rel) {
        Vec3 p = helper.absoluteVec(rel);
        g.getNavigation().moveTo(p.x, p.y, p.z, 1.0D);
    }

    /** G4 に入り、自然には湧かず、体の芯は 0.7 × 0.45、過繁茂の Mob と見なされる。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void registeredAsG4WithoutNaturalSpawn(GameTestHelper helper) {
        GregariaEntity g = placeAt(helper, 3.5D, 3.5D, 0.0F);
        if (MobGroup.of(g.getType()) != MobGroup.GROUP_4) {
            helper.fail("G4 に入っていない: " + MobGroup.of(g.getType()));
            return;
        }
        if (DesignData.mobs().forId(GregariaEntity.ID).naturalSpawn()) {
            helper.fail("自然湧きが切れていない");
            return;
        }
        if (Math.abs(g.getBbWidth() - 0.7F) > 1e-3 || Math.abs(g.getBbHeight() - 0.45F) > 1e-3) {
            helper.fail("芯の大きさが 0.7 × 0.45 でない: " + g.getBbWidth() + " × " + g.getBbHeight());
            return;
        }
        if (!com.yayuy.overgrownvegetation.effect.ParasitizedAttackInfectionHandler.isOvergrownMob(g)) {
            helper.fail("過繁茂の Mob と見なされない");
            return;
        }
        helper.succeed();
    }

    /** 1 ブロックの段(z 3〜6 の台)へ這い上がる。跳ばない(上る間に足の高さが段の上 0.3 を超えない)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 120)
    public static void climbsOneBlockStepWithoutJumping(GameTestHelper helper) {
        for (int x = 0; x <= 6; x++) {
            for (int z = 3; z <= 6; z++) helper.setBlock(new BlockPos(x, 2, z), Blocks.STONE.defaultBlockState());
        }
        GregariaEntity g = placeAt(helper, 3.5D, 1.0D, 0.0F);
        double top = helper.absoluteVec(new Vec3(0, 3.0D, 0)).y;
        double[] maxY = {Double.NEGATIVE_INFINITY};
        helper.runAfterDelay(2L, () -> walkTo(g, helper, new Vec3(3.5D, 3.0D, 5.5D)));
        helper.onEachTick(() -> {
            maxY[0] = Math.max(maxY[0], g.getY());
            if (g.getY() >= top - 0.01D && g.getZ() > helper.absoluteVec(new Vec3(0, 0, 4.5D)).z) {
                if (maxY[0] > top + 0.3D) {
                    helper.fail(String.format("段を跳んで上った(最高 %.2f、段の上 %.2f)", maxY[0], top));
                    return;
                }
                helper.succeed();
            }
        });
    }

    /** 2 段の壁(z 3 の全幅)は越えない: 向こう側へ行かせても 100 tick 手前に残る。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 120)
    public static void doesNotClimbTwoBlockWall(GameTestHelper helper) {
        for (int x = 0; x <= 6; x++) {
            for (int y = 2; y <= 3; y++) helper.setBlock(new BlockPos(x, y, 3), Blocks.STONE.defaultBlockState());
        }
        stayBehind(helper);
    }

    /** 柵(高さ 1.5)の列は越えない。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 120)
    public static void doesNotCrossFence(GameTestHelper helper) {
        for (int x = 0; x <= 6; x++) helper.setBlock(new BlockPos(x, 2, 3), Blocks.OAK_FENCE.defaultBlockState());
        stayBehind(helper);
    }

    private static void stayBehind(GameTestHelper helper) {
        GregariaEntity g = placeAt(helper, 3.5D, 1.0D, 0.0F);
        double wall = helper.absoluteVec(new Vec3(0, 0, 3.0D)).z;
        helper.onEachTick(() -> {
            if (helper.getTick() % 20 == 2) walkTo(g, helper, new Vec3(3.5D, 2.0D, 5.5D));   // 何度でも向こうへ行かせる
            if (g.getZ() > wall) helper.fail(String.format("越えた(z %.2f、y %.2f)", g.getZ() - wall, g.getY()));
        });
        helper.runAtTickTime(100L, helper::succeed);
    }

    /** 1 マスの低い隙間(z 3 の壁の x 3 だけ、下の段 y 2 が空いている)をくぐって向こうへ出る。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200)
    public static void crawlsUnderLowGap(GameTestHelper helper) {
        for (int x = 0; x <= 6; x++) {
            for (int y = 2; y <= 4; y++) {
                if (x == 3 && y == 2) continue;
                helper.setBlock(new BlockPos(x, y, 3), Blocks.STONE.defaultBlockState());
            }
        }
        GregariaEntity g = placeAt(helper, 1.5D, 1.0D, 0.0F);
        helper.onEachTick(() -> {
            if (helper.getTick() % 20 == 2 && g.getNavigation().isDone()) walkTo(g, helper, new Vec3(3.5D, 2.0D, 5.5D));
            if (g.getZ() > helper.absoluteVec(new Vec3(0, 0, 4.3D)).z) helper.succeed();   // 壁(z 3〜4)を体の半分(0.35)より抜けた
        });
        helper.runAtTickTime(190L, () -> {
            Vec3 o = helper.absoluteVec(Vec3.ZERO);
            net.minecraft.world.level.pathfinder.Path p = g.getNavigation().getPath();
            helper.fail(String.format("くぐれない(位置 %.2f %.2f %.2f、経路 %s)", g.getX() - o.x, g.getY() - o.y, g.getZ() - o.z,
                    p == null ? "なし" : (p.canReach() ? "届く " : "届かない ") + p.getNodeCount() + " 点 終わり " + p.getTarget()));
        });
    }

    /**
     * 仲間どうしは押し合わない: 30 体を同じ所に置いても詰め込み(既定 24)のダメージを受けず、40 tick 後もほとんど広がらない
     * (バニラの押し合いなら体の幅 0.7 ほど離れて真ん中から 2 を超える。弱く離すのは真ん中が 0.32 より近いときだけ = 30 体で半径 約 1)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 60)
    public static void sameKindOverlapsWithoutCramming(GameTestHelper helper) {
        List<GregariaEntity> all = new ArrayList<>();
        for (int i = 0; i < 30; i++) all.add(placeAt(helper, 3.5D + (i % 3) * 0.01D, 3.5D + (i / 3) * 0.01D, i * 12.0F));
        helper.runAtTickTime(40L, () -> {
            double far = 0.0D;
            Vec3 c = helper.absoluteVec(new Vec3(3.5D, 2.0D, 3.5D));
            for (GregariaEntity g : all) {
                if (!g.isAlive() || g.getHealth() < g.getMaxHealth()) {
                    helper.fail("詰め込みのダメージを受けた(体力 " + g.getHealth() + ")");
                    return;
                }
                far = Math.max(far, Math.hypot(g.getX() - c.x, g.getZ() - c.z));
            }
            if (far > 1.5D) {
                helper.fail(String.format("押し合って広がった(真ん中から最大 %.2f)", far));
                return;
            }
            helper.succeed();
        });
    }
}
