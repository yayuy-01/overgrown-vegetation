package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.CoelurosauravusEntity;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import com.yayuy.overgrownvegetation.registry.ModLizards;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.Locale;

/**
 * コエルロサウラウスの段 1(2026-11-16): 幹を真上へ登り、葉の下で休み、跳んで「5 進んで 1 落ちる」で滑空し、当たったら落ちる(痛みなし)。
 * 狩る側の環境生物・植物側・体格 2.2・自然には湧かない。テスト場 gametest_empty24 は 24 × 10 × 24(高さが足りないので、滑空の比は 20 tick で測る)。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class CoelurosauravusGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterCoelurosauravusGameTests(RegisterGameTestsEvent event) // 1.20.1: Forge の受け手の包みは名前で使い回すので、クラスごとに名前を変える {
            event.register(CoelurosauravusGameTests.class);
        }

        private Registration() {
        }
    }

    private CoelurosauravusGameTests() {
    }

    private static CoelurosauravusEntity placeAt(GameTestHelper helper, double x, double y, double z, float yaw) {
        CoelurosauravusEntity v = helper.spawnWithNoFreeWill(ModLizards.COELUROSAURAVUS.get(), new BlockPos(0, 2, 0));
        Vec3 p = helper.absoluteVec(new Vec3(x, y, z));
        v.moveTo(p.x, p.y, p.z, yaw, 0.0F);
        v.snapFacing(yaw);
        v.setNoAi(false);
        v.setCustomName(net.minecraft.network.chat.Component.literal("nostroll"));
        return v;
    }

    /** 幹: (x, 2, z) から丸太を {@code logs} 本。上に 3 × 3 の葉(散らない)。根元の絶対位置を返す。 */
    private static BlockPos tree(GameTestHelper helper, int x, int z, int logs) {
        for (int y = 2; y < 2 + logs; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.OAK_LOG.defaultBlockState());
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                helper.setBlock(new BlockPos(x + dx, 2 + logs, z + dz), Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
            }
        }
        return helper.absolutePos(new BlockPos(x, 2, z));
    }

    /** 狩る側の環境生物・植物側・体格 2.2(11-16 ユーザー決定)で当たりは 0.7 × 0.4 の 2.2 倍。自然には湧かない(湧きの表に無い)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void registeredAsHunterAtScale22(GameTestHelper helper) {
        CoelurosauravusEntity v = placeAt(helper, 3.5D, 2.0D, 3.5D, 0.0F);
        helper.runAfterDelay(2L, () -> {
            if (MobGroup.of(v.getType()) != MobGroup.CRITTER_HUNTER) {
                helper.fail("狩る側の環境生物に入っていない: " + MobGroup.of(v.getType()));
                return;
            }
            if (!com.yayuy.overgrownvegetation.spore.PlantSideRules.isPlantSide(v)) {
                helper.fail("植物側でない");
                return;
            }
            if (Math.abs(v.getScale() - 2.2F) > 1e-3 || Math.abs(v.getBbWidth() - 0.7F * 2.2F) > 1e-3 || Math.abs(v.getBbHeight() - 0.4F * 2.2F) > 1e-3) {
                helper.fail(String.format(Locale.ROOT, "体格 %.2f・当たり %.2f × %.2f が決まり通りでない", v.getScale(), v.getBbWidth(), v.getBbHeight()));
                return;
            }
            helper.succeed();
        });
    }

    /**
     * 登って跳ぶ: 丸太 7 本の幹を真上へ(登る・休む間 横へ 1/1000 ブロックも動かない)登り、頭が葉に入らない高さで休み、跳んで滑空し、地面へ降りる。
     * 落下の痛みを受けない(体力そのまま)。
     */
    @GameTest(template = "gametest_empty24", timeoutTicks = 700)
    public static void climbsStraightUpPerchesAndGlidesDown(GameTestHelper helper) {
        BlockPos base = tree(helper, 12, 12, 7);
        CoelurosauravusEntity v = placeAt(helper, 12.5D, 2.0D, 14.5D, 180.0F);
        float hp = v.getHealth();
        double[] worstSide = {0.0D}, start = new double[3];
        double leafY = base.getY() + 7;
        helper.runAfterDelay(2L, () -> {
            CoelurosauravusEntity.Trunk t = v.planClimb(base);
            if (t == null) {
                helper.fail("丸太 7 本の幹に登る計画が立たない");
                return;
            }
            v.startClimb(t);
            start[0] = v.getX();
            start[1] = v.getZ();
            start[2] = v.getY();
        });
        helper.onEachTick(() -> {
            if (v.mode() == CoelurosauravusEntity.MODE_CLIMB || v.mode() == CoelurosauravusEntity.MODE_PERCH) {
                worstSide[0] = Math.max(worstSide[0], Math.max(Math.abs(v.getX() - start[0]), Math.abs(v.getZ() - start[1])));
                double head = v.getY() + (CoelurosauravusEntity.CLIMB_TAIL + CoelurosauravusEntity.CLIMB_HEAD) * v.getScale();
                if (head > leafY + 1e-3) {
                    helper.fail(String.format(Locale.ROOT, "頭が葉に入った: 頭 %.2f 葉の下 %.2f(根元から)", head - base.getY(), leafY - base.getY()));
                    return;
                }
            }
            if (v.landings > 0 && v.mode() == CoelurosauravusEntity.MODE_GROUND) {
                if (v.climbs != 1 || v.perches != 1 || v.launches != 1) {
                    helper.fail(String.format(Locale.ROOT, "流れが決まり通りでない: 登る %d 休む %d 跳ぶ %d 当たる %d 着く %d", v.climbs, v.perches, v.launches, v.knocks, v.landings));
                    return;
                }
                if (worstSide[0] > 1.0E-3D) {
                    helper.fail(String.format(Locale.ROOT, "登る間に横へ %.4f ブロック動いた", worstSide[0]));
                    return;
                }
                if (v.getHealth() < hp) {
                    helper.fail(String.format(Locale.ROOT, "落下の痛みを受けた: %.1f → %.1f", hp, v.getHealth()));
                    return;
                }
                helper.succeed();
            }
        });
        helper.runAtTickTime(690L, () -> helper.fail(String.format(Locale.ROOT, "690 tick で降りない: 形 %d 高さ %.2f 登る %d 休む %d 跳ぶ %d 当たる %d",
                v.mode(), v.getY() - base.getY(), v.climbs, v.perches, v.launches, v.knocks)));
    }

    /** 滑空の比: 20 tick で進んだ水平の道のり ÷ 落ちた高さ = 5(前へ 0.3・沈み 0.06。11-16 ユーザー【仮】)。 */
    @GameTest(template = "gametest_empty24", timeoutTicks = 60)
    public static void glidesFiveAcrossPerOneDown(GameTestHelper helper) {
        CoelurosauravusEntity v = placeAt(helper, 12.5D, 8.0D, 2.5D, 0.0F);
        double[] acc = new double[5];   // 水平の道のり・前の x・前の z・始めの y・数えた tick
        helper.runAfterDelay(1L, () -> {
            v.startGlide(0.0F);
            acc[1] = v.getX();
            acc[2] = v.getZ();
            acc[3] = v.getY();
        });
        helper.onEachTick(() -> {
            if (acc[3] == 0.0D || acc[4] >= 20) return;
            if (v.mode() != CoelurosauravusEntity.MODE_GLIDE) {
                helper.fail("滑空が途中で終わった: 形 " + v.mode());
                return;
            }
            acc[0] += Math.hypot(v.getX() - acc[1], v.getZ() - acc[2]);
            acc[1] = v.getX();
            acc[2] = v.getZ();
            if (++acc[4] < 20) return;
            double drop = acc[3] - v.getY();
            double ratio = acc[0] / Math.max(drop, 1e-6);
            if (Math.abs(ratio - 5.0D) > 0.25D || drop < 0.5D) {
                helper.fail(String.format(Locale.ROOT, "滑空の比 %.2f(水平 %.2f / 落ち %.2f)が 5 でない", ratio, acc[0], drop));
                return;
            }
            helper.succeed();
        });
    }

    /** 当たったら落ちる: 滑空の先の壁に当たると止まって落ち、床で地上へ戻る。落下の痛みは受けず、着いた後は経路で歩ける。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200)
    public static void hitsWallFallsWithoutDamageThenWalks(GameTestHelper helper) {
        for (int x = 0; x <= 6; x++) {
            for (int y = 2; y <= 6; y++) helper.setBlock(new BlockPos(x, y, 5), Blocks.STONE.defaultBlockState());
        }
        CoelurosauravusEntity v = placeAt(helper, 3.5D, 6.0D, 1.5D, 0.0F);
        float hp = v.getHealth();
        boolean[] walked = {false}, pathMade = {false};
        helper.runAfterDelay(1L, () -> v.startGlide(0.0F));
        helper.onEachTick(() -> {
            if (helper.getTick() < 3 || v.mode() != CoelurosauravusEntity.MODE_GROUND) return;
            if (v.knocks != 1 || v.landings != 1) {
                helper.fail(String.format(Locale.ROOT, "当たって落ちていない: 当たる %d 着く %d", v.knocks, v.landings));
                return;
            }
            if (v.getHealth() < hp) {
                helper.fail(String.format(Locale.ROOT, "落下の痛みを受けた: %.1f → %.1f", hp, v.getHealth()));
                return;
            }
            if (!walked[0] || (v.getNavigation().isDone() && helper.getTick() % 20 == 0)) {
                walked[0] = true;
                net.minecraft.world.level.pathfinder.Path path = v.getNavigation().createPath(helper.absolutePos(new BlockPos(3, 2, 1)), 0);
                pathMade[0] = path != null;
                if (path != null) v.getNavigation().moveTo(path, 1.0D);
            }
            if (v.getZ() < helper.absoluteVec(new Vec3(0, 0, 2.6D)).z) helper.succeed();
        });
        helper.runAtTickTime(190L, () -> helper.fail(String.format(Locale.ROOT, "190 tick で終わらない: 形 %d 当たる %d 着く %d 位置 %s 地面 %s 経路 %s(引けた %s)",
                v.mode(), v.knocks, v.landings, v.position().subtract(helper.absoluteVec(Vec3.ZERO)), v.onGround(),
                v.getNavigation().isDone() ? "なし" : "あり", pathMade[0])));
    }

    /** 自分で幹を見つける: 近くの幹へ寄って登り始める(うろつきの合間。待ちを 0 にして)。 */
    @GameTest(template = "gametest_empty24", timeoutTicks = 400)
    public static void findsTrunkAndStartsClimbing(GameTestHelper helper) {
        tree(helper, 12, 12, 7);
        CoelurosauravusEntity v = helper.spawn(ModLizards.COELUROSAURAVUS.get(), new BlockPos(8, 2, 8));   // 幹から約 5 ブロック(遠いと寄るだけで 200 tick を超える)
        v.readyToClimb();
        helper.onEachTick(() -> {
            if (v.climbs > 0) helper.succeed();
        });
        helper.runAtTickTime(390L, () -> helper.fail(String.format(Locale.ROOT, "390 tick で登り始めない: 位置 %s 経路 %s 計画 %s",
                v.position().subtract(helper.absoluteVec(Vec3.ZERO)), v.getNavigation().isDone() ? "なし" : "あり", v.debugGoal())));
    }
}
