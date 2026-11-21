package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.IanthasaurusEntity;
import com.yayuy.overgrownvegetation.entity.PetrolacosaurusEntity;
import com.yayuy.overgrownvegetation.entity.lizard.SimpleLizard;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import com.yayuy.overgrownvegetation.registry.ModLizards;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 狩る側の環境生物のトカゲ 2 種(ペトロラコサウルス・イアンタサウルス)の段 1(2026-11-16): 「トカゲ型」で歩くこと。狩る側の環境生物に入り、植物側として扱われ
 * (ほかのすべての Mob から敵対される)、自然には湧かない。体格 2(10-10 の決定)で当たりは 2 倍。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class CritterLizardGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(CritterLizardGameTests.class);
        }

        private Registration() {
        }
    }

    private CritterLizardGameTests() {
    }

    private static <T extends SimpleLizard> T placeAt(GameTestHelper helper, EntityType<T> type, double x, double z, float yaw) {
        T v = helper.spawnWithNoFreeWill(type, new BlockPos(0, 2, 0));
        Vec3 p = helper.absoluteVec(new Vec3(x, 2.0D, z));
        v.moveTo(p.x, p.y, p.z, yaw, 0.0F);
        v.snapFacing(yaw);
        v.setNoAi(false);
        v.setCustomName(net.minecraft.network.chat.Component.literal("nostroll"));   // うろつきで経路を上書きしない
        return v;
    }

    private static void check(GameTestHelper helper, SimpleLizard v, String id, float width, float height) {
        if (MobGroup.of(v.getType()) != MobGroup.CRITTER_HUNTER) {
            helper.fail(id + " が狩る側の環境生物に入っていない: " + MobGroup.of(v.getType()));
            return;
        }
        if (!com.yayuy.overgrownvegetation.spore.PlantSideRules.isPlantSide(v)) {
            helper.fail(id + " が植物側でない(ほかの Mob から敵対されない)");
            return;
        }
        // 体格 2(10-10 の決定)が掛かって、当たりは素の大きさの 2 倍
        if (Math.abs(v.getScale() - 2.0F) > 1e-3 || Math.abs(v.getBbWidth() - width * 2.0F) > 1e-3 || Math.abs(v.getBbHeight() - height * 2.0F) > 1e-3) {
            helper.fail(String.format(java.util.Locale.ROOT, "%s の体格 %.2f・当たり %.2f × %.2f が決まり通りでない", id, v.getScale(), v.getBbWidth(), v.getBbHeight()));
        }
    }

    /**
     * 3 種とも狩る側の環境生物・植物側・体格 2(自然には湧かない = どの湧きの表にも入っていない。11-16 の見直し ④ で mobs.json の湧きの欄は無くなった)。
     * ヒロノムスの体格 2 は 11-16 のユーザー決定(前は 2.5)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void registeredAsHunterWithoutNaturalSpawn(GameTestHelper helper) {
        PetrolacosaurusEntity p = placeAt(helper, ModLizards.PETROLACOSAURUS.get(), 1.5D, 3.5D, 0.0F);
        IanthasaurusEntity i = placeAt(helper, ModLizards.IANTHASAURUS.get(), 5.5D, 3.5D, 0.0F);
        com.yayuy.overgrownvegetation.entity.HylonomusEntity h = placeAt(helper, ModLizards.HYLONOMUS.get(), 3.5D, 5.5D, 0.0F);
        helper.runAfterDelay(2L, () -> {
            check(helper, p, PetrolacosaurusEntity.ID, 0.6F, 0.4F);
            check(helper, i, IanthasaurusEntity.ID, 0.7F, 0.6F);
            check(helper, h, com.yayuy.overgrownvegetation.entity.HylonomusEntity.ID, 0.4F, 0.3F);
            helper.succeed();
        });
    }

    @GameTest(template = "gametest_empty7", timeoutTicks = 200)
    public static void hylonomusWalksForward(GameTestHelper helper) {
        walksForward(helper, ModLizards.HYLONOMUS.get());
    }

    /** 平地: 向こうの端まで歩き、体の向きに対して横へ滑らない(1 tick 0.07 ブロック以下)。止まって見回しても着く。 */
    private static void walksForward(GameTestHelper helper, EntityType<? extends SimpleLizard> type) {
        SimpleLizard v = placeAt(helper, type, 3.5D, 0.9D, 0.0F);
        double[] maxSide = {0.0D};
        double[] last = {v.getX(), v.getZ()};
        helper.runAfterDelay(2L, () -> walkTo(helper, v, new BlockPos(3, 2, 6)));
        helper.onEachTick(() -> {
            double dx = v.getX() - last[0], dz = v.getZ() - last[1];
            last[0] = v.getX();
            last[1] = v.getZ();
            float yaw = (v.yRotO + Mth.wrapDegrees(v.getYRot() - v.yRotO) * 0.5F) * Mth.DEG_TO_RAD;
            maxSide[0] = Math.max(maxSide[0], Math.abs(dx * Mth.cos(yaw) + dz * Mth.sin(yaw)));
            if (v.getZ() > helper.absoluteVec(new Vec3(0, 0, 5.2D)).z) {
                // 0.07: 小さく速い体は 20 度/tick で回るので、「この tick の向きの真ん中」で測ると 0.25 × sin 10° ≈ 0.04 と、前の向きの勢い(バニラの慣性)が混ざる
                // (ピリムネスの 0.05 だと 0.052 で落ちた回があった。横へ歩く・滑るのはこれよりずっと大きい)
                if (maxSide[0] > 0.07D) {
                    helper.fail("体の向きに対して横へ滑った(1 tick 最大 " + String.format("%.3f", maxSide[0]) + ")");
                    return;
                }
                helper.succeed();
            }
        });
        helper.runAtTickTime(190L, () -> {
            Vec3 r = v.position().subtract(helper.absoluteVec(Vec3.ZERO));
            helper.fail(String.format(java.util.Locale.ROOT, "190 tick で着かない: 位置 %.2f %.2f %.2f 止まった %d 経路 %s", r.x, r.y, r.z, v.pauses,
                    v.getNavigation().isDone() ? "なし" : "あり"));
        });
    }

    private static void walkTo(GameTestHelper helper, SimpleLizard v, BlockPos goalRel) {
        Path path = v.getNavigation().createPath(helper.absolutePos(goalRel), 0);
        if (path == null) {
            helper.fail("経路が引けない(" + goalRel + ")");
            return;
        }
        v.getNavigation().moveTo(path, 1.0D);
    }

    @GameTest(template = "gametest_empty7", timeoutTicks = 200)
    public static void coelurosauravusWalksForward(GameTestHelper helper) {
        walksForward(helper, ModLizards.COELUROSAURAVUS.get());
    }

    @GameTest(template = "gametest_empty7", timeoutTicks = 200)
    public static void petrolacosaurusWalksForward(GameTestHelper helper) {
        walksForward(helper, ModLizards.PETROLACOSAURUS.get());
    }

    @GameTest(template = "gametest_empty7", timeoutTicks = 200)
    public static void ianthasaurusWalksForward(GameTestHelper helper) {
        walksForward(helper, ModLizards.IANTHASAURUS.get());
    }

    /** 2 ブロックの段(z 3 から先が 2 段高い台): 下から跳び上がって台の上の目標へ着く(トカゲ型の段差)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 240)
    public static void petrolacosaurusHopsUpTwoBlocks(GameTestHelper helper) {
        for (int x = 0; x <= 6; x++) {
            for (int z = 3; z <= 6; z++) {
                helper.setBlock(new BlockPos(x, 2, z), Blocks.STONE.defaultBlockState());
                helper.setBlock(new BlockPos(x, 3, z), Blocks.STONE.defaultBlockState());
            }
        }
        SimpleLizard v = placeAt(helper, ModLizards.PETROLACOSAURUS.get(), 3.5D, 0.9D, 0.0F);
        double topY = helper.absoluteVec(new Vec3(0, 4.0D, 0)).y;
        helper.runAfterDelay(2L, () -> walkTo(helper, v, new BlockPos(3, 4, 5)));
        helper.onEachTick(() -> {
            if (v.getNavigation().isDone() && helper.getTick() > 10 && v.getY() < topY - 0.5D) walkTo(helper, v, new BlockPos(3, 4, 5));
            if (v.onGround() && v.getY() > topY - 0.01D && v.getZ() > helper.absoluteVec(new Vec3(0, 0, 3.6D)).z) helper.succeed();
        });
    }
}
