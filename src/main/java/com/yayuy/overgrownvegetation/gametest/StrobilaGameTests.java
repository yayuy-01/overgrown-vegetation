package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.StrobilaEntity;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import com.yayuy.overgrownvegetation.registry.ModCrawlers;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * ストロビラの段 1(2026-11-15): 「小さな地這いの虫」の型の 2 体目。1 ブロックの段は這い上がる・2 段の壁は越えない・
 * 1 マス幅の通路は通る・高さ 1 の隙間はくぐらない(尾が天井を抜けないように)・急ぎは walk 2.5 倍の速さで止まる・
 * 描画の外接箱が脚と尾を覆う。うろつき(小走り)は近くにプレイヤーがいるときだけなのでここでは見ない(テストクライアントで見る)。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class StrobilaGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(StrobilaGameTests.class);
        }

        private Registration() {
        }
    }

    private StrobilaGameTests() {
    }

    private static StrobilaEntity placeAt(GameTestHelper helper, double x, double z, float yaw) {
        StrobilaEntity s = helper.spawn(ModCrawlers.STROBILA.get(), new BlockPos(0, 2, 0));   // Goal を残す(spawnWithNoFreeWill は Goal を全部消す)
        Vec3 p = helper.absoluteVec(new Vec3(x, 2.0D, z));
        s.moveTo(p.x, p.y, p.z, yaw, 0.0F);
        s.snapFacing(yaw);
        s.setNoAi(false);
        s.setCustomName(Component.literal("nostroll"));
        return s;
    }

    private static void walkTo(StrobilaEntity s, GameTestHelper helper, Vec3 rel) {
        Vec3 p = helper.absoluteVec(rel);
        s.getNavigation().moveTo(p.x, p.y, p.z, 1.0D);
    }

    /** G2 に入り、自然には湧かず、芯は 0.95 × 1.2、過繁茂の Mob と見なされる。描画の外接箱は脚と尾(全体 3.6 × 2.8 × 5.5)を覆う。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void strobilaRegisteredAsG2WithWideCullingBox(GameTestHelper helper) {
        StrobilaEntity s = placeAt(helper, 3.5D, 3.5D, 0.0F);
        if (MobGroup.of(s.getType()) != MobGroup.GROUP_2) {
            helper.fail("G2 に入っていない: " + MobGroup.of(s.getType()));
            return;
        }
        if (Math.abs(s.getBbWidth() - 0.95F) > 1e-3 || Math.abs(s.getBbHeight() - 1.2F) > 1e-3) {
            helper.fail("芯の大きさが 0.95 × 1.2 でない: " + s.getBbWidth() + " × " + s.getBbHeight());
            return;
        }
        if (!com.yayuy.overgrownvegetation.spore.PlantSideRules.isPlantSide(s)) {
            helper.fail("過繁茂の Mob と見なされない");
            return;
        }
        AABB c = s.getBoundingBoxForCulling();
        if (c.getXsize() < 5.5D || c.getZsize() < 5.5D || c.maxY - s.getY() < 2.8D) {
            helper.fail(String.format("描画の外接箱が狭い(%.2f × %.2f × 上 %.2f)", c.getXsize(), c.getZsize(), c.maxY - s.getY()));
            return;
        }
        helper.succeed();
    }

    /** 1 ブロックの段(z 3〜6 の台)へ這い上がる。跳ばない(上る間に足の高さが段の上 0.3 を超えない)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200)
    public static void strobilaClimbsOneBlockStep(GameTestHelper helper) {
        for (int x = 0; x <= 6; x++) {
            for (int z = 3; z <= 6; z++) helper.setBlock(new BlockPos(x, 2, z), Blocks.STONE.defaultBlockState());
        }
        StrobilaEntity s = placeAt(helper, 3.5D, 1.0D, 0.0F);
        double top = helper.absoluteVec(new Vec3(0, 3.0D, 0)).y;
        double[] maxY = {Double.NEGATIVE_INFINITY};
        helper.onEachTick(() -> {
            if (helper.getTick() % 20 == 2 && s.getNavigation().isDone()) walkTo(s, helper, new Vec3(3.5D, 3.0D, 5.5D));
            maxY[0] = Math.max(maxY[0], s.getY());
            if (s.getY() >= top - 0.01D && s.getZ() > helper.absoluteVec(new Vec3(0, 0, 4.0D)).z) {
                if (maxY[0] > top + 0.3D) {
                    helper.fail(String.format("段を跳んで上った(最高 %.2f、段の上 %.2f)", maxY[0], top));
                    return;
                }
                helper.succeed();
            }
        });
        helper.runAtTickTime(190L, () -> helper.fail("上れない: " + where(helper, s)));
    }

    /** 失敗の文: 場の中の位置と、経路(届くか・点の数・終わり)。 */
    private static String where(GameTestHelper helper, StrobilaEntity s) {
        Vec3 o = helper.absoluteVec(Vec3.ZERO);
        net.minecraft.world.level.pathfinder.Path p = s.getNavigation().getPath();
        return String.format("位置 %.2f %.2f %.2f 向き %.0f、経路 %s", s.getX() - o.x, s.getY() - o.y, s.getZ() - o.z, s.getYRot(),
                p == null ? "なし" : (p.canReach() ? "届く " : "届かない ") + p.getNextNodeIndex() + "/" + p.getNodeCount()
                        + " 終わり " + p.getTarget().subtract(helper.absolutePos(BlockPos.ZERO)));
    }

    /** 2 段の壁(z 3 の全幅)は越えない: 向こう側へ行かせても 100 tick 手前に残る。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 120)
    public static void strobilaStopsAtTwoBlockWall(GameTestHelper helper) {
        for (int x = 0; x <= 6; x++) {
            for (int y = 2; y <= 3; y++) helper.setBlock(new BlockPos(x, y, 3), Blocks.STONE.defaultBlockState());
        }
        StrobilaEntity s = placeAt(helper, 3.5D, 1.0D, 0.0F);
        double wall = helper.absoluteVec(new Vec3(0, 0, 3.0D)).z;
        helper.onEachTick(() -> {
            if (helper.getTick() % 20 == 2) walkTo(s, helper, new Vec3(3.5D, 2.0D, 6.5D));
            if (s.getZ() > wall) helper.fail(String.format("越えた(z %.2f、y %.2f)", s.getZ() - wall, s.getY()));
        });
        helper.runAtTickTime(100L, helper::succeed);
    }

    /** 1 マス幅・高さ 3 の通路(z 3 の壁の x 3 だけ空いている)を抜けて向こうへ出る。目標は z 6.5(moveTo は目標から 1 ブロック以内で着いた扱いなので、z 5.5 だと通路の出口で止まる)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 240)
    public static void strobilaPassesOneWideDoorway(GameTestHelper helper) {
        for (int x = 0; x <= 6; x++) {
            if (x == 3) continue;
            for (int y = 2; y <= 4; y++) helper.setBlock(new BlockPos(x, y, 3), Blocks.STONE.defaultBlockState());
        }
        StrobilaEntity s = placeAt(helper, 1.5D, 1.0D, 0.0F);
        helper.onEachTick(() -> {
            if (helper.getTick() % 20 == 2 && s.getNavigation().isDone()) walkTo(s, helper, new Vec3(3.5D, 2.0D, 6.5D));
            if (s.getZ() > helper.absoluteVec(new Vec3(0, 0, 4.6D)).z) helper.succeed();   // 壁(z 3〜4)を体の半分(0.475)より抜けた
        });
        helper.runAtTickTime(230L, () -> helper.fail("通れない: " + where(helper, s)));
    }

    /** 高さ 1 の隙間(z 3 の壁の x 3 だけ、下の段 y 2 が空いている)はくぐらない(グレガリアはくぐる)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 160)
    public static void strobilaDoesNotCrawlUnderLowGap(GameTestHelper helper) {
        for (int x = 0; x <= 6; x++) {
            for (int y = 2; y <= 4; y++) {
                if (x == 3 && y == 2) continue;
                helper.setBlock(new BlockPos(x, y, 3), Blocks.STONE.defaultBlockState());
            }
        }
        StrobilaEntity s = placeAt(helper, 3.5D, 1.0D, 0.0F);
        double wall = helper.absoluteVec(new Vec3(0, 0, 3.0D)).z;
        helper.onEachTick(() -> {
            if (helper.getTick() % 20 == 2) walkTo(s, helper, new Vec3(3.5D, 2.0D, 6.5D));
            if (s.getZ() + s.getBbWidth() / 2.0D > wall + 0.5D) helper.fail(String.format("くぐった(z %.2f)", s.getZ() - wall));
        });
        helper.runAtTickTime(150L, helper::succeed);
    }

    /**
     * 急ぎ(経路の速さ 1.0)の地面の速さは run 1 倍(= {@link StrobilaEntity#RUN_REF} 0.28 = アルボラの床の速さ)の近く(11-15 段 2 で上げた):
     * 平らな 24 マスの場をまっすぐ歩かせ、15〜45 tick の平均が的の 0.73〜1.13 倍。
     */
    @GameTest(template = "gametest_empty24", timeoutTicks = 80)
    public static void strobilaHurrySpeedMatchesRun(GameTestHelper helper) {
        StrobilaEntity s = placeAt(helper, 3.5D, 2.5D, 0.0F);
        double[] z0 = {Double.NaN};
        helper.runAfterDelay(2L, () -> walkTo(s, helper, new Vec3(3.5D, 2.0D, 22.5D)));
        helper.runAtTickTime(15L, () -> z0[0] = s.getZ());
        helper.runAtTickTime(45L, () -> {
            double v = (s.getZ() - z0[0]) / 30.0D;
            double ref = StrobilaEntity.RUN_REF;
            if (v < ref * 0.73D || v > ref * 1.13D) {
                double attr = s.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
                helper.fail(String.format("急ぎの速さ %.4f ブロック/tick(的 %.4f)。移動の速さの属性 %.3f%s", v, ref, attr,
                        attr < 0.3D ? "(0.36 のはず。この GameTest のフォルダの config/overgrown_vegetation/mobs.json が古い写しかもしれない)" : ""));
                return;
            }
            helper.succeed();
        });
    }

    /** 的(動かない牛)を置き、ストロビラに狙わせる。 */
    private static net.minecraft.world.entity.animal.Cow targetCow(GameTestHelper helper, StrobilaEntity s, double x, double z) {
        net.minecraft.world.entity.animal.Cow cow = helper.spawnWithNoFreeWill(net.minecraft.world.entity.EntityType.COW, new BlockPos((int) x, 2, (int) z));
        Vec3 p = helper.absoluteVec(new Vec3(x, 2.0D, z));
        cow.moveTo(p.x, p.y, p.z, 0.0F, 0.0F);
        return cow;
    }

    /**
     * 山なりの初速は、投げ物の毎 tick の動き(進む → 0.99 倍 → 重さ 0.03)をなぞると的の点を通る(近い・遠い・上・下・射程いっぱいの 7 通り。誤差 0.1 ブロック以内)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void strobilaLobVelocityReachesTarget(GameTestHelper helper) {
        Vec3 from = new Vec3(0.0D, 2.57D, 0.0D);
        Vec3[] targets = {new Vec3(4.0D, 0.3D, 1.0D), new Vec3(-3.0D, 0.0D, 15.0D), new Vec3(8.0D, 3.0D, 0.0D), new Vec3(0.0D, -4.0D, -10.0D),
                new Vec3(2.0D, 0.0D, 30.0D), new Vec3(-22.0D, 2.0D, -21.0D), new Vec3(0.0D, 0.0D, StrobilaEntity.THROW_MAX)};   // 遠く(射程 32、11-16)
        for (Vec3 to : targets) {
            Vec3 v = StrobilaEntity.lobVelocity(from, to);
            Vec3 p = from;
            double best = Double.MAX_VALUE, apex = from.y;
            for (int i = 0; i < 80; i++) {
                p = p.add(v);
                v = v.scale(0.99D).add(0.0D, -0.03D, 0.0D);
                best = Math.min(best, p.distanceTo(to));
                apex = Math.max(apex, p.y);
            }
            if (best > 0.1D) {
                helper.fail(String.format("%s へ届かない(いちばん近くて %.2f)", to, best));
                return;
            }
            if (apex < Math.max(from.y, to.y) + 1.0D) {
                helper.fail(String.format("%s へ山なりになっていない(頂 %.2f)", to, apex));
                return;
            }
        }
        helper.succeed();
    }

    /** 球果は植物側(グレガリア)を素通りし、牛には当たる。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void strobilaConePassesThroughPlantSide(GameTestHelper helper) {
        var g = helper.spawnWithNoFreeWill(ModCrawlers.GREGARIA.get(), new BlockPos(1, 2, 1));
        var cow = helper.spawnWithNoFreeWill(net.minecraft.world.entity.EntityType.COW, new BlockPos(5, 2, 5));
        if (!com.yayuy.overgrownvegetation.entity.StrobilaCone.passesThrough(g)) {
            helper.fail("グレガリアに当たる");
            return;
        }
        if (com.yayuy.overgrownvegetation.entity.StrobilaCone.passesThrough(cow)) {
            helper.fail("牛を素通りする");
            return;
        }
        helper.succeed();
    }

    /**
     * 投げの時刻: 真横(90 度)の的には、まず向き直り、正面の左右 60 度に入ってから投げ始める。弾は投げ始めから {@link StrobilaEntity#RELEASE_AT} tick 目に
     * 1 つだけ出る。{@link StrobilaEntity#ACTION_TICKS} tick で投げ終わり、次の投げまでの残りは間隔 − 投げの長さ。
     */
    @GameTest(template = "gametest_empty24", timeoutTicks = 200)
    public static void strobilaThrowTimelineAfterTurning(GameTestHelper helper) {
        StrobilaEntity s = placeAt(helper, 6.5D, 6.5D, 0.0F);
        var cow = targetCow(helper, s, 14.5D, 6.5D);   // 東へ 8 ブロック = 体の向き(南)から 90 度
        long[] start = {-1L}, release = {-1L};
        int[] cones = {0};
        AABB field = new AABB(helper.absolutePos(BlockPos.ZERO)).inflate(30.0D);
        helper.onEachTick(() -> {
            s.setTarget(cow);
            long t = helper.getTick();
            if (start[0] < 0 && s.throwTick() == 0) {
                start[0] = t;
                float want = (float) (net.minecraft.util.Mth.atan2(cow.getZ() - s.getZ(), cow.getX() - s.getX()) * net.minecraft.util.Mth.RAD_TO_DEG) - 90.0F;
                float off = Math.abs(net.minecraft.util.Mth.wrapDegrees(want - s.getYRot()));
                if (off > 60.5F) helper.fail(String.format("向き直る前に投げ始めた(ずれ %.0f 度)", off));
            }
            int n = helper.getLevel().getEntitiesOfClass(com.yayuy.overgrownvegetation.entity.StrobilaCone.class, field).size();
            if (n > cones[0] && release[0] < 0) release[0] = t;
            cones[0] = Math.max(cones[0], n);
            if (start[0] >= 0 && t == start[0] + StrobilaEntity.ACTION_TICKS + 1) {
                if (release[0] - start[0] != StrobilaEntity.RELEASE_AT) {
                    helper.fail("弾が投げ始めから " + (release[0] - start[0]) + " tick 目に出た(的 " + StrobilaEntity.RELEASE_AT + ")");
                } else if (s.isThrowing() || s.throwTick() >= 0) {
                    helper.fail("投げ終わらない");
                } else if (Math.abs(s.throwCooldown() - (StrobilaEntity.THROW_INTERVAL - StrobilaEntity.ACTION_TICKS - 1)) > 2) {
                    helper.fail("次の投げまでの残りが " + s.throwCooldown());
                } else {
                    helper.succeed();
                }
            }
        });
        helper.runAtTickTime(190L, () -> helper.fail("投げない: 投げ始め " + start[0] + " 弾 " + release[0] + " 的 " + s.getTarget() + " 残り " + s.throwCooldown() + " Goal " + s.runningGoalNames() + " " + where(helper, s)));
    }

    /**
     * 塀の向こうへ落とす(設定上いる理由): 高さ 3 の壁(z 9)の向こう 4.5 ブロックの牛へ投げると、胞子の煙が牛から 3 ブロック以内・壁の向こう側にできる。
     * 手前に煙ができたら失敗。
     */
    @GameTest(template = "gametest_empty24", timeoutTicks = 200)
    public static void strobilaLobsOverWall(GameTestHelper helper) {
        for (int x = 0; x <= 23; x++) {
            for (int y = 2; y <= 4; y++) helper.setBlock(new BlockPos(x, y, 9), Blocks.STONE.defaultBlockState());
        }
        StrobilaEntity s = placeAt(helper, 11.5D, 3.5D, 0.0F);
        var cow = targetCow(helper, s, 11.5D, 14.5D);   // 11 ブロック先
        double wall = helper.absoluteVec(new Vec3(0, 0, 10.0D)).z;
        AABB field = new AABB(helper.absolutePos(BlockPos.ZERO)).inflate(30.0D);
        helper.onEachTick(() -> {
            s.setTarget(cow);
            for (var c : helper.getLevel().getEntitiesOfClass(com.yayuy.overgrownvegetation.entity.g1.skill.G1SporeCloud.class, field)) {
                if (c.getZ() < wall) {
                    helper.fail(String.format("壁の手前に煙ができた(z %.2f、壁の向こうは %.2f から)", c.getZ() - wall, 0.0D));
                    return;
                }
                double d = Math.sqrt(c.distanceToSqr(cow.getX(), c.getY(), cow.getZ()));
                if (d <= 3.0D) {
                    helper.succeed();
                    return;
                }
                helper.fail(String.format("煙が牛から %.2f ブロック離れた所にできた", d));
                return;
            }
        });
        helper.runAtTickTime(190L, () -> helper.fail("煙ができない: 投げ " + s.throwTick() + " " + where(helper, s)));
    }

    // ------------------------------------------------------------------ 尾の払い(11-15 夜)

    /**
     * 払いの見張り: 牛を的にして毎 tick 狙わせ、払い始めの向き・左右・牛が叩かれた tick・投げたかを数える。
     * {@code expectRight} が null なら左右は見ない。
     */
    private static void sweepCase(GameTestHelper helper, double cowX, double cowZ, Boolean expectRight, float minTurn, float maxTurn) {
        StrobilaEntity s = placeAt(helper, 6.5D, 6.5D, 0.0F);
        var cow = targetCow(helper, s, cowX, cowZ);
        float yaw0 = s.getYRot();
        double d0 = Math.hypot(cow.getX() - s.getX(), cow.getZ() - s.getZ());
        float maxHp = cow.getHealth();
        boolean[] started = {false};
        float[] yawAtStart = {Float.NaN};
        helper.onEachTick(() -> {
            s.setTarget(cow);
            if (!started[0] && s.sweepTick() == 0) {
                started[0] = true;
                yawAtStart[0] = s.getYRot();
                if (expectRight != null && s.sweepRight() != expectRight) {
                    helper.fail("払う向きが逆(" + (s.sweepRight() ? "右" : "左") + ")");
                    return;
                }
                float turned = Math.abs(net.minecraft.util.Mth.wrapDegrees(yawAtStart[0] - yaw0));
                if (turned < minTurn || turned > maxTurn) {
                    helper.fail(String.format("払う前に %.0f 度回った(的 %.0f〜%.0f)", turned, minTurn, maxTurn));
                    return;
                }
            }
            if (s.throwTick() >= 0) {
                helper.fail("近い横・後ろの相手に投げ始めた(払いが先のはず)");
                return;
            }
            if (started[0] && cow.getHealth() < maxHp) {
                double d = Math.hypot(cow.getX() - s.getX(), cow.getZ() - s.getZ());
                if (helper.getTick() > 0 && d > d0 + 0.8D) {
                    if (maxHp - cow.getHealth() > StrobilaEntity.SWEEP_DAMAGE + 0.01F) helper.fail("傷が強すぎる: " + (maxHp - cow.getHealth()));
                    else helper.succeed();
                }
            }
        });
        helper.runAtTickTime(110L, () -> helper.fail(String.format("払って弾き飛ばさない: 払い始め %s 牛の体力 %.1f/%.1f 距離 %.2f → %.2f %s",
                started[0], cow.getHealth(), maxHp, d0, Math.hypot(cow.getX() - s.getX(), cow.getZ() - s.getZ()), where(helper, s))));
    }

    /** 真後ろ 2 ブロックの相手: 向き直らずに払い(回り 0 度)、弱い傷で弾き飛ばす。投げない。 */
    @GameTest(template = "gametest_empty24", timeoutTicks = 120)
    public static void strobilaSweepsTargetBehind(GameTestHelper helper) {
        sweepCase(helper, 6.5D, 4.5D, null, 0.0F, 0.5F);
    }

    /** 左後ろ(方位 −60)の相手は左へ払う(左 = 体の向き +z に対して +x)。 */
    @GameTest(template = "gametest_empty24", timeoutTicks = 120)
    public static void strobilaSweepsLeftForLeftTarget(GameTestHelper helper) {
        sweepCase(helper, 6.5D + 1.9D, 6.5D - 1.1D, false, 0.0F, 0.5F);
    }

    /** 右後ろ(方位 +60)の相手は右へ払う。 */
    @GameTest(template = "gametest_empty24", timeoutTicks = 120)
    public static void strobilaSweepsRightForRightTarget(GameTestHelper helper) {
        sweepCase(helper, 6.5D - 1.9D, 6.5D - 1.1D, true, 0.0F, 0.5F);
    }

    /** 右の正面から 70 度(方位 +110)の相手: 尾が届かない帯なので、体を左へ 1〜3 tick 回してから(13〜45 度)右へ払う。 */
    @GameTest(template = "gametest_empty24", timeoutTicks = 120)
    public static void strobilaTurnsAwayThenSweepsBandTarget(GameTestHelper helper) {
        sweepCase(helper, 6.5D - 1.88D, 6.5D + 0.68D, true, 13.0F, 45.0F);
    }

    /** 正面 2 ブロックの近い相手は払わない(尾は後ろにしか届かない。投げの届く 3 より近いので投げもしない)。 */
    @GameTest(template = "gametest_empty24", timeoutTicks = 80)
    public static void strobilaDoesNotSweepFrontTarget(GameTestHelper helper) {
        StrobilaEntity s = placeAt(helper, 6.5D, 6.5D, 0.0F);
        var cow = targetCow(helper, s, 6.5D, 8.5D);
        helper.onEachTick(() -> {
            s.setTarget(cow);
            if (s.isSweeping()) helper.fail("正面の相手を払った");
        });
        helper.runAtTickTime(70L, helper::succeed);
    }
}
