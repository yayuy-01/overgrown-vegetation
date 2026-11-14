package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.entity.SeminariaEntity;
import com.yayuy.overgrownvegetation.entity.flyer.FlightBody;
import com.yayuy.overgrownvegetation.entity.flyer.Flock;
import com.yayuy.overgrownvegetation.entity.flyer.FlyerWind;
import com.yayuy.overgrownvegetation.entity.flyer.LooseFormation;
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
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * セミナリアの段 1(2026-11-15): 「飛ぶ型」の土台の上の、風に半分任せる飛び方・ゆるい群れ・止まり木。
 * 風と群れはテスト場に収まらないので計算だけで回す(ゲームの中と同じ関数)。止まり木へ降りる・叩かれて飛び立つはテスト場で。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class SeminariaGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(SeminariaGameTests.class);
        }

        private Registration() {
        }
    }

    private SeminariaGameTests() {
    }

    private static SeminariaEntity spawn(GameTestHelper helper, double x, double y, double z) {
        SeminariaEntity v = helper.spawn(ModFlyers.SEMINARIA.get(), new BlockPos(3, 2, 3));
        Vec3 p = helper.absoluteVec(new Vec3(x, y, z));
        v.moveTo(p.x, p.y, p.z, 0.0F, 0.0F);
        v.debugSolo = true;
        return v;
    }

    /** G4 に入り、自然には湧かず、芯は 0.8 × 1.0、翅と腹の叩ける部位がある。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void seminariaRegisteredAsG4WithParts(GameTestHelper helper) {
        SeminariaEntity v = spawn(helper, 3.5D, 3.0D, 3.5D);
        if (MobGroup.of(v.getType()) != MobGroup.GROUP_4) {
            helper.fail("G4 に入っていない: " + MobGroup.of(v.getType()));
            return;
        }
        if (DesignData.mobs().forId(SeminariaEntity.ID).naturalSpawn()) {
            helper.fail("自然湧きが切れていない");
            return;
        }
        if (Math.abs(v.getBbWidth() - 0.8F) > 1e-3 || Math.abs(v.getBbHeight() - 1.0F) > 1e-3) {
            helper.fail("芯の大きさが 0.8 × 1.0 でない: " + v.getBbWidth() + " × " + v.getBbHeight());
            return;
        }
        if (v.hitParts().count() != 3 || v.hitParts().indexOf("wing_l") < 0 || v.hitParts().indexOf("abdomen") < 0) {
            helper.fail("翅・腹の叩ける部位が無い");
            return;
        }
        helper.succeed();
    }

    /** 巡航のまま 100 tick 飛んだ地面の速さ(風は強さ s の卓越風を南へ)。heading 0 = 南(風下)、180 = 北(風上)。 */
    private static double groundSpeed(FlightBody.Params p, double heading, double cmdSpeed, double strength) {
        FlightBody b = new FlightBody();
        b.heading = heading;
        b.speed = cmdSpeed;
        double wz = Math.min(strength, FlyerWind.STRENGTH_CAP) * FlyerWind.BLOCKS_PER_STRENGTH;
        for (int t = 0; t < 100; t++) b.step(p, heading, cmdSpeed, 0.0D, 0.0D, wz);
        return b.vz * (heading == 0.0D ? 1.0D : -1.0D);   // 行きたい向きへ進んだ速さ(負 = 押し戻された)
    }

    /**
     * 風に半分任せる: ふだんの午後の風(強さ 1)で、巡航なら風下へは速く(0.45 以上)風上へはほとんど進めない(0.1 未満)。
     * 家・止まり木へ戻るとき(向かい風の分だけ強く羽ばたく)は、上限の風(1.5)でも風上へ進める。ヴェロフィル(倍率 1)は変わらない。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void seminariaHalfRidesTheWind(GameTestHelper helper) {
        FlightBody.Params p = SeminariaEntity.flightParams();
        double down = groundSpeed(p, 0.0D, p.cruise * 0.9D, 1.0D);
        double up = groundSpeed(p, 180.0D, p.cruise * 0.9D, 1.0D);
        if (down < 0.45D || up >= 0.1D) {
            helper.fail(String.format("風下 %.3f・風上 %.3f(風下 0.45 以上・風上 0.1 未満のはず)", down, up));
            return;
        }
        for (double s : new double[]{1.0D, 1.5D}) {
            double hw = Math.min(s, FlyerWind.STRENGTH_CAP) * FlyerWind.BLOCKS_PER_STRENGTH * p.windFactor;
            double effort = Math.min(p.maxSpeed, p.cruise * 0.9D + hw);
            double back = groundSpeed(p, 180.0D, effort, s);
            if (back < 0.05D) {
                helper.fail(String.format("強さ %.1f の向かい風で家へ戻れない(%.3f)", s, back));
                return;
            }
        }
        FlightBody.Params v = new FlightBody.Params();
        if (v.windFactor != 1.0D || v.flutterDeg != 0.0D || v.flutterBob != 0.0D) {
            helper.fail("土台の既定値が変わった(ヴェロフィルの飛び方が変わる)");
            return;
        }
        helper.succeed();
    }

    /**
     * ゆるい群れ: 後続 4 体がばらばらの所から先頭のまわりへ寄り(先頭から 12 ブロック以内)、重ならず(互いに 1 ブロック以上)、
     * V 字の並び(VFormation の位置)にはならない。先頭はゆるい S 字で曲がり続ける。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void seminariaFlockStaysLooselyTogether(GameTestHelper helper) {
        FlightBody.Params p = SeminariaEntity.flightParams();
        int n = SeminariaEntity.FLOCK_MAX;
        FlightBody[] b = new FlightBody[n];
        double[][] pos = new double[n][3];
        double[] phase = new double[n];
        java.util.Random rnd = new java.util.Random(11);
        for (int i = 0; i < n; i++) {
            b[i] = new FlightBody();
            b[i].speed = p.cruise * 0.8D;
            b[i].heading = (rnd.nextDouble() - 0.5D) * 90.0D;
            pos[i] = new double[]{(rnd.nextDouble() - 0.5D) * 16.0D, 0.0D, -4.0D - rnd.nextDouble() * 12.0D};
            phase[i] = rnd.nextDouble() * Math.PI * 2.0D;
        }
        pos[0] = new double[]{0.0D, 0.0D, 0.0D};
        double[] off = new double[3], vslot = new double[3];
        VFormation.Command c = new VFormation.Command();
        double worstFar = 0.0D, worstNear = Double.MAX_VALUE, vErr = 0.0D;
        for (int t = 0; t < 800; t++) {
            double turn = Math.sin(t / 150.0D) * 30.0D;
            b[0].step(p, b[0].heading + turn * 0.2D, p.cruise * 0.9D, 0.0D, 0.0D, 0.0D);
            for (int k = 1; k < n; k++) {
                LooseFormation.slot(k, phase[k], t, off);
                LooseFormation.follow(pos[0][0], 0.0D, pos[0][2], b[0].heading, b[0].speed, b[0].turnRate, off,
                        pos[k][0], pos[k][2], p.maxSpeed, c);
                b[k].step(p, c.heading, c.speed, 0.0D, 0.0D, 0.0D);
            }
            for (int k = 0; k < n; k++) {
                pos[k][0] += b[k].vx;
                pos[k][2] += b[k].vz;
            }
            if (t >= 600) {
                for (int k = 1; k < n; k++) {
                    double dx = pos[k][0] - pos[0][0], dz = pos[k][2] - pos[0][2];
                    worstFar = Math.max(worstFar, Math.sqrt(dx * dx + dz * dz));
                    VFormation.slot(k, vslot);
                    double h = Math.toRadians(b[0].heading);
                    double sx = pos[0][0] + Math.sin(h) * vslot[0] + Math.cos(h) * vslot[1];
                    double sz = pos[0][2] - Math.cos(h) * vslot[0] + Math.sin(h) * vslot[1];
                    vErr = Math.max(vErr, Math.hypot(pos[k][0] - sx, pos[k][2] - sz));
                    for (int j = 0; j < k; j++) {
                        worstNear = Math.min(worstNear, Math.hypot(pos[k][0] - pos[j][0], pos[k][2] - pos[j][2]));
                    }
                }
            }
        }
        if (worstFar > 12.0D) {
            helper.fail(String.format("先頭から %.1f ブロック離れた", worstFar));
            return;
        }
        if (worstNear < 1.0D) {
            helper.fail(String.format("2 体が %.2f ブロックまで重なった", worstNear));
            return;
        }
        if (vErr < 2.0D) {
            helper.fail(String.format("V 字の並びになっている(ずれ最大 %.2f)", vErr));
            return;
        }
        helper.succeed();
    }

    /** ひらひら: 飛んでいる間、まっすぐ行けと言われても向きが左右に揺れる(60 tick で向きの変わり方の左右が 2 回以上入れ替わる)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 80)
    public static void seminariaFluttersInFlight(GameTestHelper helper) {
        SeminariaEntity v = spawn(helper, 3.5D, 6.0D, 3.5D);
        v.roostOverride = Boolean.FALSE;
        float[] last = {Float.NaN};
        int[] sign = {0}, flips = {0}, ticks = {0};
        helper.onEachTick(() -> {
            if (v.mode() != SkyFlyer.MODE_FLY) return;
            float y = v.getYRot();
            if (!Float.isNaN(last[0])) {
                float d = net.minecraft.util.Mth.wrapDegrees(y - last[0]);
                int s = d > 0.2F ? 1 : (d < -0.2F ? -1 : 0);
                if (s != 0 && sign[0] != 0 && s != sign[0]) flips[0]++;
                if (s != 0) sign[0] = s;
            }
            last[0] = y;
            if (++ticks[0] >= 60) {
                if (flips[0] >= 2) helper.succeed();
                else helper.fail("向きが揺れない(入れ替わり " + flips[0] + " 回)");
            }
        });
    }

    /** 夜(差し込み): 真上にいる個体が、テスト場の葉の柱のてっぺんへ降りて止まる。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 400)
    public static void seminariaPerchesOnTreeTop(GameTestHelper helper) {
        for (int y = 1; y <= 9; y++) {
            helper.setBlock(new BlockPos(3, y, 3), Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
        }
        BlockPos top = helper.absolutePos(new BlockPos(3, 9, 3));
        SeminariaEntity v = spawn(helper, 3.5D, 14.0D, 3.5D);
        v.roostOverride = Boolean.TRUE;
        v.debugRoostAt(top);   // 本来の止まり木探しはまわりのテストの高い物を選ぶことがある(探し方はヴェロフィルのテストで見ている)
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
        helper.runAfterDelay(390L, () -> {
            if (v.mode() == SkyFlyer.MODE_PERCHED) return;
            helper.fail(String.format("390 tick で止まらない: 動き %d 群れ %s 止まり木 %s 位置 %.1f %.1f %.1f(柱 %s)",
                    v.mode(), v.flock() == null ? "null" : v.flock().state(), v.perchPos(), v.getX(), v.getY(), v.getZ(), top));
        });
    }

    /** 止まっている個体を叩くと群れごと飛び立ち、上へ昇る(30 tick で 2 ブロック以上)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 60)
    public static void seminariaTakesOffWhenHit(GameTestHelper helper) {
        helper.setBlock(new BlockPos(3, 1, 3), Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
        BlockPos top = helper.absolutePos(new BlockPos(3, 1, 3));
        SeminariaEntity v = spawn(helper, 3.5D, 2.0D, 3.5D);
        v.roostOverride = Boolean.TRUE;
        v.debugPerchAt(top);
        v.debugTickFlock();
        double[] y0 = {Double.NaN};
        boolean[] hit = {false};
        helper.runAfterDelay(5L, () -> {
            if (hit[0]) return;
            hit[0] = true;
            if (v.mode() != SkyFlyer.MODE_PERCHED) {
                helper.fail("止まったままでいない: " + v.mode());
                return;
            }
            y0[0] = v.getY();
            v.hurt(helper.getLevel().damageSources().generic(), 1.0F);
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
}
