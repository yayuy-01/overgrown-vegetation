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
        // spawnWithNoFreeWill は Goal を全部外す(removeFreeWill)ので、群れの追う Goal が無くなる → ふつうに湧かせる(名前でうろつき・相手探しを切る)
        GregariaEntity g = helper.spawn(ModCrawlers.GREGARIA.get(), new BlockPos(0, 2, 0));
        Vec3 p = helper.absoluteVec(new Vec3(x, 2.0D, z));
        g.moveTo(p.x, p.y, p.z, yaw, 0.0F);
        g.snapFacing(yaw);
        g.setNoAi(false);
        g.setCustomName(Component.literal("nostroll_notarget_nopack"));   // 群れ・相手探しを切る(隣のテストの Mob を狙わない・混ざらない)
        return g;
    }

    private static void walkTo(GregariaEntity g, GameTestHelper helper, Vec3 rel) {
        // moveTo(x, y, z, 速さ) は着いた扱いの幅 1 で経路を引く(目標の 1 マス手前の点の真ん中で止まる)。
        // 急ぎを 0.175 に落としてから行き過ぎなくなり、z > 4.5 の判定に届かなかった(11-15)→ 幅 0 で引く
        Vec3 p = helper.absoluteVec(rel);
        g.getNavigation().moveTo(g.getNavigation().createPath(net.minecraft.core.BlockPos.containing(p), 0), 1.0D);
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

    // ------------------------------------------------------------------ 能力(2026-11-16)

    /** 動かない豚(噛まれる的)。 */
    private static net.minecraft.world.entity.animal.Pig pigAt(GameTestHelper helper, double x, double z) {
        net.minecraft.world.entity.animal.Pig pig = helper.spawnWithNoFreeWill(net.minecraft.world.entity.EntityType.PIG, new BlockPos(0, 2, 0));
        Vec3 p = helper.absoluteVec(new Vec3(x, 2.0D, z));
        pig.moveTo(p.x, p.y, p.z, 0.0F, 0.0F);
        return pig;
    }

    /** 動かないアイアンゴーレム(体力の多い的)。 */
    private static net.minecraft.world.entity.animal.IronGolem golemAt(GameTestHelper helper, double x, double z) {
        net.minecraft.world.entity.animal.IronGolem m = helper.spawnWithNoFreeWill(net.minecraft.world.entity.EntityType.IRON_GOLEM, new BlockPos(0, 2, 0));
        Vec3 p = helper.absoluteVec(new Vec3(x, 2.0D, z));
        m.moveTo(p.x, p.y, p.z, 0.0F, 0.0F);
        return m;
    }

    private static double doseOf(net.minecraft.world.entity.LivingEntity e) {
        return com.yayuy.overgrownvegetation.spore.SporeExposureTracker.dose(e);
    }

    /** 足首を噛む: 動かない豚へ走り寄って噛み、傷 1 と暴露量 +150 以上(傷の暴露も足される)が入る。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 120)
    public static void bitesAnkleWithWoundAndDose(GameTestHelper helper) {
        net.minecraft.world.entity.animal.Pig pig = pigAt(helper, 3.5D, 5.0D);
        GregariaEntity g = placeAt(helper, 3.5D, 1.0D, 0.0F);
        helper.runAfterDelay(1L, () -> g.setTarget(pig));
        helper.onEachTick(() -> {
            if (pig.getHealth() <= pig.getMaxHealth() - GregariaEntity.BITE_DAMAGE + 1e-3 && doseOf(pig) >= GregariaEntity.BITE_DOSE) helper.succeed();
        });
        helper.runAtTickTime(115L, () -> {
            Vec3 o = helper.absoluteVec(Vec3.ZERO);
            StringBuilder goals = new StringBuilder();
            g.goalSelector.getAvailableGoals().forEach(w -> { if (w.isRunning()) goals.append(w.getGoal().getClass().getSimpleName()).append(' '); });
            helper.fail(String.format("噛まない(体力 %.1f / %.1f、暴露量 %.0f、口が届く %s、位置 %.2f %.2f、豚 %.2f %.2f、標的 %s、Goal %s、噛み始め計 %d)",
                    pig.getHealth(), pig.getMaxHealth(), doseOf(pig), g.mouthReaches(pig), g.getX() - o.x, g.getZ() - o.z,
                    pig.getX() - o.x, pig.getZ() - o.z, g.getTarget() == null ? "なし" : g.getTarget().getName().getString(), goals,
                    GregariaEntity.biteStarts));
        });
    }

    /** 同じ個体は 2 秒おき: 1 体だけで 100 tick の間に噛み始めるのは 3 回まで(40 tick おき)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 140)
    public static void sameBiterWaitsTwoSeconds(GameTestHelper helper) {
        var pig = golemAt(helper, 3.5D, 3.5D);
        // 体力の多い的(豚は 8 体に噛まれると死ぬ。無敵にすると狙えない相手になる = canBeSeenAsEnemy)
        GregariaEntity g = placeAt(helper, 3.5D, 2.2D, 0.0F);
        int[] starts = {0};
        int[] firstAt = {-1};
        boolean[] wasBiting = {false};
        helper.runAfterDelay(1L, () -> g.setTarget(pig));
        helper.onEachTick(() -> {
            boolean biting = GregariaEntity.bitersOn(pig, helper.getLevel().getGameTime()) > 0;
            if (biting && !wasBiting[0]) {
                starts[0]++;
                if (firstAt[0] < 0) firstAt[0] = (int) helper.getTick();
            }
            wasBiting[0] = biting;
            if (firstAt[0] >= 0 && helper.getTick() >= firstAt[0] + 100) {
                if (starts[0] > 3 || starts[0] < 2) {
                    helper.fail("100 tick で噛み始めた数が 2〜3 でない: " + starts[0]);
                    return;
                }
                helper.succeed();
            }
        });
    }

    /** 1 人を同時に噛めるのは 4 体まで: 8 体で囲んでも、噛んでいる数は 4 を超えない(2 以上にはなる)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 160)
    public static void atMostFourBiteOneTarget(GameTestHelper helper) {
        var pig = golemAt(helper, 3.5D, 3.5D);
        // 体力の多い的
        List<GregariaEntity> all = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4.0D;
            all.add(placeAt(helper, 3.5D + Math.cos(a) * 2.2D, 3.5D + Math.sin(a) * 2.2D, 0.0F));
        }
        int[] most = {0};
        helper.runAfterDelay(1L, () -> all.forEach(g -> g.setTarget(pig)));
        helper.onEachTick(() -> {
            int n = GregariaEntity.bitersOn(pig, helper.getLevel().getGameTime());
            if (n > GregariaEntity.BITE_MAX_PER_TARGET) helper.fail("同時に噛んでいる数が多い: " + n);
            most[0] = Math.max(most[0], n);
        });
        helper.runAtTickTime(150L, () -> {
            if (most[0] < 2) helper.fail("囲んでも 2 体以上が同時に噛まない(最大 " + most[0] + ")");
            else helper.succeed();
        });
    }

    /** 群れ: 近くに湧いた 4 体は 1 つの群れになり、群れの標的は全員の標的になる。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 80)
    public static void packSharesTarget(GameTestHelper helper) {
        net.minecraft.world.entity.animal.Pig pig = pigAt(helper, 5.5D, 5.5D);
        List<GregariaEntity> all = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            GregariaEntity g = placeAt(helper, 1.5D + i, 1.5D, 0.0F);
            g.setCustomName(Component.literal("nostroll_notarget"));   // 群れには入る・自分では探さない
            all.add(g);
        }
        helper.runAtTickTime(10L, () -> {
            var p = all.get(0).pack();
            if (p == null || all.stream().anyMatch(g -> g.pack() != p)) {
                helper.fail("4 体が 1 つの群れにならない");
                return;
            }
            p.setTarget(pig);
        });
        helper.runAtTickTime(16L, () -> {
            if (all.stream().allMatch(g -> g.getTarget() == pig)) helper.succeed();
            else helper.fail("群れの標的が全員に渡らない");
        });
    }

    /** 通った所に胞子を降り積もらせる: 4 ブロックほど歩くと、走った距離 × 0.2% ぶんを場へ書く。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 160)
    public static void depositsSporesWhereItRuns(GameTestHelper helper) {
        GregariaEntity g = placeAt(helper, 3.5D, 0.8D, 0.0F);
        helper.runAfterDelay(2L, () -> walkTo(g, helper, new Vec3(3.5D, 2.0D, 6.0D)));
        helper.onEachTick(() -> {
            if (g.depositedPercent() >= 0.5D) helper.succeed();
        });
        helper.runAtTickTime(150L, () -> helper.fail(String.format("降り積もりを書かない(%.2f%%)", g.depositedPercent())));
    }
}
