package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.VelophyllEntity;
import com.yayuy.overgrownvegetation.entity.flyer.FlightBody;
import com.yayuy.overgrownvegetation.entity.flyer.Flock;
import com.yayuy.overgrownvegetation.entity.flyer.SkyFlyer;
import com.yayuy.overgrownvegetation.entity.flyer.VFormation;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import com.yayuy.overgrownvegetation.registry.ModFlyers;
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

/**
 * ヴェロフィルの段 1(2026-11-15): 「飛ぶ型」の土台。旋回の半径(10 ブロック級)と V 字はテスト場(7 × 7)に収まらないので、
 * 飛び方({@link FlightBody})と隊列({@link VFormation})は計算だけで回す(ゲームの中の後続と同じ関数)。
 * 止まり木へ降りる・叩かれて飛び立つはテスト場で。夜・雨の判断は世界全体で共通なので、{@link SkyFlyer#roostOverride} で差し込む。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class VelophyllGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterVelophyllGameTests(RegisterGameTestsEvent event) { // 1.20.1: Forge の受け手の包みは名前で使い回すので、クラスごとに名前を変える
            event.register(VelophyllGameTests.class);
        }

        private Registration() {
        }
    }

    private VelophyllGameTests() {
    }

    private static FlightBody.Params params() {
        FlightBody.Params p = new FlightBody.Params();
        p.cruise = 0.7D;
        p.maxTurnDeg = 4.0D;
        return p;
    }

    private static VelophyllEntity spawn(GameTestHelper helper, double x, double y, double z) {
        VelophyllEntity v = helper.spawn(ModFlyers.VELOPHYLL.get(), new BlockPos(3, 2, 3));
        Vec3 p = helper.absoluteVec(new Vec3(x, y, z));
        v.moveTo(p.x, p.y, p.z, 0.0F, 0.0F);
        v.debugSolo = true;
        return v;
    }

    /** G2 に入り、自然には湧かず、芯は 1.2 × 0.8、翼と頭の叩ける部位がある。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void registeredAsG2WithWingParts(GameTestHelper helper) {
        VelophyllEntity v = spawn(helper, 3.5D, 3.0D, 3.5D);
        if (MobGroup.of(v.getType()) != MobGroup.GROUP_2) {
            helper.fail("G2 に入っていない: " + MobGroup.of(v.getType()));
            return;
        }
        if (Math.abs(v.getBbWidth() - 1.2F) > 1e-3 || Math.abs(v.getBbHeight() - 0.8F) > 1e-3) {
            helper.fail("芯の大きさが 1.2 × 0.8 でない: " + v.getBbWidth() + " × " + v.getBbHeight());
            return;
        }
        if (v.hitParts().count() != 3 || v.hitParts().indexOf("wing_l") < 0 || v.hitParts().indexOf("wing_r") < 0) {
            helper.fail("翼の叩ける部位が無い");
            return;
        }
        helper.succeed();
    }

    /** 巡航で目一杯曲がると半径 約 10 ブロックの弧(9〜11.5)を描き、横へは滑らない(地面の速さの向き = 機首)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void turnsInWideArcWithoutSideslip(GameTestHelper helper) {
        FlightBody.Params p = params();
        FlightBody b = new FlightBody();
        b.speed = p.cruise;
        double maxSlip = 0.0D;
        for (int t = 0; t < 200; t++) {
            b.step(p, b.heading + 90.0D, p.cruise, 0.0D, 0.0D, 0.0D);
            double vh = FlightBody.yawOf(b.vx, b.vz);
            maxSlip = Math.max(maxSlip, Math.abs(FlightBody.wrapDeg(vh - b.heading)));
        }
        double r = b.speed / Math.toRadians(Math.abs(b.turnRate));
        if (r < 9.0D || r > 11.5D) {
            helper.fail(String.format("旋回の半径 %.2f(速さ %.3f・%.2f 度/tick)", r, b.speed, b.turnRate));
            return;
        }
        if (maxSlip > 0.01D) {
            helper.fail(String.format("横へ滑った(最大 %.3f 度)", maxSlip));
            return;
        }
        double bank = FlightBody.bankDeg(b.speed, b.turnRate);
        if (Math.abs(bank) < 20.0D) {
            helper.fail(String.format("曲がっているのに体の傾きが小さい: %.1f 度", bank));
            return;
        }
        helper.succeed();
    }

    /** 風上へは遅く、風下へは速い(風 0.1 ブロック/tick で差 0.15 以上)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void windSlowsUpwind(GameTestHelper helper) {
        FlightBody.Params p = params();
        double[] g = new double[2];
        for (int i = 0; i < 2; i++) {
            FlightBody b = new FlightBody();
            b.heading = i == 0 ? 0.0D : 180.0D;   // 0 = 南へ(風下)、180 = 北へ(風上)
            b.speed = p.cruise;
            for (int t = 0; t < 100; t++) b.step(p, b.heading, p.cruise, 0.0D, 0.0D, 0.1D);
            g[i] = Math.sqrt(b.vx * b.vx + b.vz * b.vz);
        }
        if (g[0] - g[1] < 0.15D) {
            helper.fail(String.format("風下 %.3f・風上 %.3f の差が小さい", g[0], g[1]));
            return;
        }
        helper.succeed();
    }

    /**
     * 後続 6 体がばらばらの所から V 字へ収まり(各々の位置から 2.5 ブロック以内)、先頭がゆるく曲がり続けても崩れない。
     * 先頭を落とすと並びの 1 番が先頭になり、残りが詰めて並び直す(3 ブロック以内)。ゲームの中と同じ関数で回す。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void followersHoldVAndCloseUpAfterLeaderLoss(GameTestHelper helper) {
        FlightBody.Params p = params();
        int n = 7;
        java.util.List<FlightBody> bodies = new java.util.ArrayList<>();
        java.util.List<double[]> pos = new java.util.ArrayList<>();
        java.util.Random rnd = new java.util.Random(7);
        for (int i = 0; i < n; i++) {
            FlightBody b = new FlightBody();
            b.speed = p.cruise * 0.8D;
            b.heading = (rnd.nextDouble() - 0.5D) * 60.0D;
            bodies.add(b);
            pos.add(new double[]{(rnd.nextDouble() - 0.5D) * 20.0D, 0.0D, -5.0D - rnd.nextDouble() * 20.0D});
        }
        double[] tmp = new double[3];
        VFormation.Command c = new VFormation.Command();
        String err = simulate(p, bodies, pos, tmp, c, 600, 2.5D);
        if (err != null) {
            helper.fail("隊列に収まらない: " + err);
            return;
        }
        bodies.remove(0);
        pos.remove(0);
        err = simulate(p, bodies, pos, tmp, c, 500, 3.0D);
        if (err != null) {
            helper.fail("先頭を落とした後に並び直せない: " + err);
            return;
        }
        helper.succeed();
    }

    /** 先頭はゆるく曲がり続け、後続は VFormation で付いていく。最後の 100 tick の各々の位置からのずれの最大が tol 以下なら null。 */
    private static String simulate(FlightBody.Params p, java.util.List<FlightBody> bodies, java.util.List<double[]> pos,
                                   double[] tmp, VFormation.Command c, int ticks, double tol) {
        double worst = 0.0D;
        int worstK = -1;
        for (int t = 0; t < ticks; t++) {
            FlightBody lb = bodies.get(0);
            double[] lp = pos.get(0);
            double turn = Math.sin(t / 120.0D) * 40.0D;   // ゆるい S 字
            lb.step(p, lb.heading + turn * 0.2D, p.cruise * 0.9D, 0.0D, 0.0D, 0.0D);
            for (int k = 1; k < bodies.size(); k++) {
                FlightBody b = bodies.get(k);
                double[] mp = pos.get(k);
                VFormation.follow(lp[0], lp[1], lp[2], lb.heading, lb.speed, lb.turnRate, k, mp[0], mp[2], p.maxSpeed, tmp, c);
                b.step(p, c.heading, c.speed, 0.0D, 0.0D, 0.0D);
                if (t >= ticks - 100 && c.dist > worst) {
                    worst = c.dist;
                    worstK = k;
                }
            }
            for (int k = 0; k < bodies.size(); k++) {
                double[] q = pos.get(k);
                q[0] += bodies.get(k).vx;
                q[2] += bodies.get(k).vz;
            }
        }
        return worst <= tol ? null : String.format("並び %d が %.2f ブロック離れている", worstK, worst);
    }

    /** 夜(差し込み): 真上にいる個体が、テスト場の葉の柱のてっぺんへ降りて止まる。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 300)
    public static void perchesOnTreeTopWhenRoosting(GameTestHelper helper) {
        for (int y = 1; y <= 9; y++) {
            helper.setBlock(new BlockPos(3, y, 3), Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
        }
        BlockPos top = helper.absolutePos(new BlockPos(3, 9, 3));
        VelophyllEntity v = spawn(helper, 3.5D, 14.0D, 3.5D);
        v.roostOverride = Boolean.TRUE;
        helper.onEachTick(() -> {
            if (v.mode() == SkyFlyer.MODE_PERCHED) {
                if (!top.equals(v.perchPos())) {
                    helper.fail("葉の柱でない所に止まった: " + v.perchPos() + "(柱 " + top + ")");
                    return;
                }
                if (Math.abs(v.getY() - (top.getY() + 1)) > 0.01D) {
                    helper.fail("止まった高さが葉の上でない: " + v.getY());
                    return;
                }
                if (v.flock() == null || v.flock().state() != Flock.State.ROOST) {
                    helper.fail("群れが休んでいない");
                    return;
                }
                helper.succeed();
            }
        });
    }

    /** 止まっている個体を叩くと群れごと飛び立ち、上へ昇る(30 tick で 2 ブロック以上)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 60)
    public static void takesOffWhenHitOnPerch(GameTestHelper helper) {
        helper.setBlock(new BlockPos(3, 1, 3), Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
        BlockPos top = helper.absolutePos(new BlockPos(3, 1, 3));
        VelophyllEntity v = spawn(helper, 3.5D, 2.0D, 3.5D);
        v.roostOverride = Boolean.TRUE;
        v.debugPerchAt(top);
        v.debugTickFlock();
        double[] y0 = {Double.NaN};
        helper.runAfterDelay(5L, () -> {
            if (v.mode() != SkyFlyer.MODE_PERCHED) {
                helper.fail("止まったままでいない: " + v.mode());
                return;
            }
            y0[0] = v.getY();
            v.hurt(helper.getLevel().damageSources().generic(), 1.0F);
        });
        // 段 2(11-15): 驚いても昇る前にその場で溜める(7 tick + 入りの補間 3)。叩いてから 9 tick はまだ昇らない
        helper.runAfterDelay(14L, () -> {
            if (Math.abs(v.getY() - y0[0]) > 0.01D) {
                helper.fail(String.format("溜めの間に動いた(%.2f)", v.getY() - y0[0]));
            }
        });
        helper.runAfterDelay(35L, () -> {
            int m = v.mode();
            if (m == SkyFlyer.MODE_PERCHED || m == SkyFlyer.MODE_LANDING) {
                helper.fail("叩かれても飛び立たない: " + m);
                return;
            }
            if (v.getY() - y0[0] < 2.0D) {
                helper.fail(String.format("昇っていない(%.2f)", v.getY() - y0[0]));
                return;
            }
            if (v.flock() == null || v.flock().state() != Flock.State.FLEE) {
                helper.fail("群れが逃げていない: " + (v.flock() == null ? "null" : v.flock().state()));
                return;
            }
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ 爆撃(2026-11-15、能力の段)

    /** 爆弾は落ちた所で胞子の雲になる(植物側は素通りするが、床には当たる)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 80)
    public static void bombBurstsIntoSporeCloud(GameTestHelper helper) {
        VelophyllEntity v = spawn(helper, 3.5D, 6.0D, 3.5D);
        Vec3 at = helper.absoluteVec(new Vec3(3.5D, 5.0D, 3.5D));
        com.yayuy.overgrownvegetation.entity.VelophyllBomb b = new com.yayuy.overgrownvegetation.entity.VelophyllBomb(helper.getLevel(), v, at);
        b.setDeltaMovement(0.0D, -0.3D, 0.0D);
        helper.getLevel().addFreshEntity(b);
        helper.succeedWhen(() -> {
            java.util.List<com.yayuy.overgrownvegetation.entity.g1.skill.G1SporeCloud> c = helper.getLevel().getEntitiesOfClass(
                    com.yayuy.overgrownvegetation.entity.g1.skill.G1SporeCloud.class, new net.minecraft.world.phys.AABB(at, at).inflate(4.0D, 6.0D, 4.0D));
            helper.assertTrue(!c.isEmpty(), "爆弾が雲にならない");
            helper.assertTrue(b.isRemoved(), "爆弾が残っている");
        });
    }

    /** 空が見えている相手だけを見つける: 牛の真上 3 ブロックに屋根を置くと見つけない(開けた側はテスト場の並びで上に物が来ることがあるので見ない)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void ignoresTargetsUnderRoof(GameTestHelper helper) {
        net.minecraft.world.entity.animal.Cow open = helper.spawnWithNoFreeWill(net.minecraft.world.entity.EntityType.COW, new BlockPos(1, 2, 1));
        net.minecraft.world.entity.animal.Cow hidden = helper.spawnWithNoFreeWill(net.minecraft.world.entity.EntityType.COW, new BlockPos(5, 2, 5));
        helper.setBlock(new BlockPos(5, 5, 5), Blocks.OAK_PLANKS);
        helper.runAfterDelay(2L, () -> {
            helper.assertFalse(VelophyllEntity.exposedToSky(hidden), "屋根の下の牛が空から見える");
            helper.succeed();
        });
    }
    /**
     * 急降下: 前触れ(その場の高さを保つ)→ 相手の頭上へ落ちる → 放す → 引き起こす。爆弾は相手の近く(水平 6 ブロック以内)で放す(雲になるのは別のテスト)。
     * 前触れの間は昇り降りしない。
     * 11-17: テスト場は狭く上に天井があるので、ヴェロフィルは場の中(相手の真上 6)に置き、前触れはその場で向き直るだけにする
     * ({@link VelophyllEntity#debugWindupInPlace})。前は前触れで 13 ブロック進んでエンティティを動かさないチャンクへ出て止まり、時間切れで落ちていた。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200)
    public static void divesAndBombsTarget(GameTestHelper helper) {
        net.minecraft.world.entity.animal.Cow cow = helper.spawnWithNoFreeWill(net.minecraft.world.entity.EntityType.COW, new BlockPos(3, 2, 3));
        VelophyllEntity v = spawn(helper, 3.5D, 8.0D, 3.5D);
        v.roostOverride = Boolean.FALSE;
        v.debugWindupInPlace = true;
        int before = VelophyllEntity.bombsDropped;
        double[] y0 = {Double.NaN};
        boolean[] sawFall = {false};
        helper.runAfterDelay(5L, () -> {
            y0[0] = v.getY();
            v.startDive(cow);
        });
        helper.runAfterDelay(5L + VelophyllEntity.DIVE_WINDUP - 2, () -> {
            helper.assertTrue(v.diveState() == VelophyllEntity.DIVE_WINDUP_P, "前触れのまま来ない: " + v.diveState());
            helper.assertTrue(Math.abs(v.getY() - y0[0]) < 0.3D, String.format("前触れの間に高さが変わった(%.2f)", v.getY() - y0[0]));
        });
        int[] tick = {0};
        helper.onEachTick(() -> {
            tick[0]++;
            if (v.diveState() == VelophyllEntity.DIVE_FALL) sawFall[0] = true;
            if (VelophyllEntity.bombsDropped > before && sawFall[0]) {
                Vec3 b = VelophyllEntity.lastBombAt;
                double hd = Math.hypot(b.x - cow.getX(), b.z - cow.getZ());
                if (hd > 6.0D) {
                    helper.fail(String.format("爆弾を相手から離れた所で放した(水平 %.1f)", hd));
                    return;
                }
                helper.succeed();
            } else if (tick[0] == 190) {
                // 時間切れの前に、どこで止まったかを書いて落とす
                helper.fail(String.format("爆弾を放さない: 段 %d・動き %d・相手からの位置 (%.1f, %.1f, %.1f)", v.diveState(), v.mode(),
                        v.getX() - cow.getX(), v.getY() - cow.getY(), v.getZ() - cow.getZ()));
            }
        });
    }

    /** 【決定 11-15 ユーザー(案 B)】爆弾が相手に直に当たると小さな傷(2)を付け、雲にもなる。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 60)
    public static void bombDirectHitHurtsLightly(GameTestHelper helper) {
        net.minecraft.world.entity.animal.Cow cow = helper.spawnWithNoFreeWill(net.minecraft.world.entity.EntityType.COW, new BlockPos(3, 2, 3));
        VelophyllEntity v = spawn(helper, 3.5D, 9.0D, 3.5D);
        float hp0 = cow.getHealth();
        Vec3 at = new Vec3(cow.getX(), cow.getY() + 3.0D, cow.getZ());
        com.yayuy.overgrownvegetation.entity.VelophyllBomb b = new com.yayuy.overgrownvegetation.entity.VelophyllBomb(helper.getLevel(), v, at);
        b.setDeltaMovement(0.0D, -0.4D, 0.0D);
        helper.getLevel().addFreshEntity(b);
        helper.succeedWhen(() -> {
            helper.assertTrue(b.isRemoved(), "爆弾がまだ落ちている");
            float lost = hp0 - cow.getHealth();
            helper.assertTrue(Math.abs(lost - com.yayuy.overgrownvegetation.entity.VelophyllBomb.DIRECT_DAMAGE) < 0.01F,
                    "直に当たった傷が " + com.yayuy.overgrownvegetation.entity.VelophyllBomb.DIRECT_DAMAGE + " でない: " + lost);
        });
    }

    // ------------------------------------------------------------------ 狙いの正確さ(2026-11-16、ユーザー「狙いが下手」)
    // テスト場の上には物があり(放した直後の爆弾がブロックに当たった)、空からの着弾は場の中で測れない。
    // そこで、爆弾の落ち方(ThrowableProjectile と同じ: 位置 += 速さ → 速さ × 0.99・下へ 0.03)をそのまま回して、
    // VelophyllEntity.aimBomb の初速で着く所と相手のずれを計算で確かめる(ゲームの中の値はテストクライアントで測って Vault に書いた)。

    /** 止まっている相手: 1 ブロック以内【仮】。放す所は相手の頭上 5.5・横へ 2 ずれた所(引き起こしの途中)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void bombAimHitsStillTarget(GameTestHelper helper) {
        aimSim(helper, 0.0D, 1.0D);
    }

    /** 歩く相手(0.1 ブロック/tick): 2 ブロック以内【仮】。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void bombAimLeadsWalkingTarget(GameTestHelper helper) {
        aimSim(helper, 0.1D, 2.0D);
    }

    /** 走る相手(0.2 ブロック/tick): 目安が無いので 2 ブロック以内【仮】。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void bombAimLeadsRunningTarget(GameTestHelper helper) {
        aimSim(helper, 0.2D, 2.0D);
    }

    private static void aimSim(GameTestHelper helper, double speed, double tol) {
        double worst = 0.0D;
        // 放す所を相手の頭上 4〜7・横へ 0〜3 のいくつかで、相手の向きも前後左右で試す
        double[][] offs = {{0, 5.5, 0}, {2, 5.5, 0}, {-2, 6, 1}, {1, 4, -2.5}, {3, 7, 1}};
        double[][] dirs = {{1, 0}, {0, 1}, {-1, 0}, {0.7, -0.7}};
        double ty = 0.7D;   // 相手の体の真ん中(牛の高さ 1.4 の半分)
        double[] v0 = new double[3];
        for (double[] o : offs) {
            for (double[] d : dirs) {
                double tvx = d[0] * speed, tvz = d[1] * speed;
                Vec3 p = new Vec3(o[0], ty + o[1], o[2]);
                VelophyllEntity.aimBomb(p, 0.0D, ty, 0.0D, tvx, tvz, v0);
                double x = p.x, y = p.y, z = p.z, vx = v0[0], vy = v0[1], vz = v0[2];
                int n = 0;
                double hd = Double.NaN;
                while (n < 100) {
                    double ny = y + vy;
                    if (ny <= ty) {
                        double f = (y - ty) / (y - ny);   // 体の高さを通った tick の中の割合
                        double cx = x + vx * f, cz = z + vz * f, tt = n + f;
                        hd = Math.hypot(cx - tvx * tt, cz - tvz * tt);
                        break;
                    }
                    x += vx;
                    y = ny;
                    z += vz;
                    vx *= VelophyllEntity.BOMB_DRAG;
                    vz *= VelophyllEntity.BOMB_DRAG;
                    vy = vy * VelophyllEntity.BOMB_DRAG - VelophyllEntity.BOMB_GRAVITY;
                    n++;
                }
                if (Double.isNaN(hd)) {
                    helper.fail("爆弾が体の高さまで落ちない");
                    return;
                }
                worst = Math.max(worst, hd);
            }
        }
        com.mojang.logging.LogUtils.getLogger().info(String.format("VELO_AIM 計算: 速さ %.1f のずれの最大 %.2f", speed, worst));
        if (worst > tol) {
            helper.fail(String.format("速さ %.1f: ずれの最大 %.2f > %.1f", speed, worst, tol));
            return;
        }
        helper.succeed();
    }
}
