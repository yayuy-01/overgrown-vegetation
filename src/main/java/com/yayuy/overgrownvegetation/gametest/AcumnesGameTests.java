package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.entity.AcumnesEntity;
import com.yayuy.overgrownvegetation.entity.flyer.DartHover;
import com.yayuy.overgrownvegetation.entity.flyer.SkyFlyer;
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

import java.util.Random;

/**
 * アクムネスの段 1(2026-11-15): 止まっては飛ぶ・場所を移す。高さの帯(相手の 8〜20 上)と鋭く飛ぶ長さはテスト場(7 × 7)に収まらないので、
 * 動き({@link DartHover})と場所の選び方({@link DartHover#pickSpot})は計算だけで回す(ゲームの中と同じ関数)。止まる・飛び立つはテスト場で。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class AcumnesGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(AcumnesGameTests.class);
        }

        private Registration() {
        }
    }

    private AcumnesGameTests() {
    }

    private static AcumnesEntity spawn(GameTestHelper helper, double x, double y, double z) {
        AcumnesEntity a = helper.spawn(ModFlyers.ACUMNES.get(), new BlockPos(3, 2, 3));
        Vec3 p = helper.absoluteVec(new Vec3(x, y, z));
        a.moveTo(p.x, p.y, p.z, 0.0F, 0.0F);
        a.debugSolo = true;
        return a;
    }

    /** G5 に入り、自然には湧かず、芯は 1.2 × 3.0(体格 1)、翅・頭・腹の叩ける部位がある。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void registeredAsG5WithParts(GameTestHelper helper) {
        AcumnesEntity a = spawn(helper, 3.5D, 2.0D, 3.5D);
        if (MobGroup.of(a.getType()) != MobGroup.GROUP_5) {
            helper.fail("G5 に入っていない: " + MobGroup.of(a.getType()));
            return;
        }
        if (DesignData.mobs().forId(AcumnesEntity.ID).naturalSpawn()) {
            helper.fail("自然湧きが切れていない");
            return;
        }
        if (Math.abs(a.getScale() - 1.0F) > 1e-3 || Math.abs(a.getBbWidth() - 1.2F) > 1e-3 || Math.abs(a.getBbHeight() - 3.0F) > 1e-3) {
            helper.fail("芯の大きさが 1.2 × 3.0(体格 1)でない: " + a.getBbWidth() + " × " + a.getBbHeight() + " 体格 " + a.getScale());
            return;
        }
        if (a.hitParts().count() != 5 || a.hitParts().indexOf("wing_l") < 0 || a.hitParts().indexOf("gaster_b") < 0) {
            helper.fail("翅・腹の叩ける部位が無い");
            return;
        }
        helper.succeed();
    }

    /** 鋭く飛んでいる最中(1.0 ブロック/tick)に止まれと言うと 5 tick 以内に止まり、その後は風があっても 60 tick 一歩も動かない。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void holdStopsDeadEvenInWind(GameTestHelper helper) {
        DartHover.Params p = AcumnesEntity.DART;
        DartHover h = new DartHover();
        double x = 0.0D, y = 0.0D, z = 0.0D;
        h.vx = 1.0D;
        h.holdAt(x, y, z);
        int stopAt = -1;
        double sx = 0, sy = 0, sz = 0;
        for (int t = 1; t <= 70; t++) {
            h.step(p, x, y, z, 0.15D, -0.1D, false);
            x += h.vx;
            y += h.vy;
            z += h.vz;
            if (stopAt < 0 && h.settled()) {
                stopAt = t;
                sx = x;
                sy = y;
                sz = z;
            }
        }
        if (stopAt < 0 || stopAt > 5) {
            helper.fail("5 tick で止まらない: " + stopAt);
            return;
        }
        double drift = Math.sqrt((x - sx) * (x - sx) + (y - sy) * (y - sy) + (z - sz) * (z - sz));
        if (drift > 1e-9) {
            helper.fail(String.format("止まった後に動いた: %.4f ブロック", drift));
            return;
        }
        if (Math.abs(sx) > 1.5D) {
            helper.fail(String.format("止まるまでに流れすぎ: %.2f ブロック", sx));
            return;
        }
        helper.succeed();
    }

    /** 止まった所から 10 ブロック先へ鋭く飛ぶ: 速さの頂点は 0.9 以上、20 tick 以内に着いて行き過ぎず、また止まる(斜め上でも)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void dartIsShortSharpAndStops(GameTestHelper helper) {
        DartHover.Params p = AcumnesEntity.DART;
        DartHover h = new DartHover();
        double x = 0.0D, y = 0.0D, z = 0.0D;
        double tx = 6.0D, ty = 5.0D, tz = 6.245D;   // 約 10 ブロック
        double d0 = Math.sqrt(tx * tx + ty * ty + tz * tz);
        h.holdAt(x, y, z);
        h.dartTo(tx, ty, tz);
        double peak = 0.0D, maxProgress = 0.0D;
        int arrived = -1;
        for (int t = 1; t <= 40; t++) {
            h.step(p, x, y, z, 0.1D, 0.0D, false);
            x += h.vx;
            y += h.vy;
            z += h.vz;
            peak = Math.max(peak, h.speed());
            maxProgress = Math.max(maxProgress, (x * tx + y * ty + z * tz) / d0);
            if (arrived < 0 && h.settled()) arrived = t;
        }
        if (peak < 0.9D) {
            helper.fail(String.format("鋭くない: 速さの頂点 %.2f", peak));
            return;
        }
        if (arrived < 0 || arrived > 20) {
            helper.fail("20 tick で着いて止まらない: " + arrived);
            return;
        }
        if (maxProgress > d0 + 0.2D) {
            helper.fail(String.format("行き過ぎた: %.2f / %.2f", maxProgress, d0));
            return;
        }
        double miss = Math.sqrt((x - tx) * (x - tx) + (y - ty) * (y - ty) + (z - tz) * (z - tz));
        if (miss > 0.1D) {
            helper.fail(String.format("行き先から %.2f ずれて止まった", miss));
            return;
        }
        helper.succeed();
    }

    /**
     * 場所を移し続けても、選ぶ場所は相手の 8〜20 上・水平に 14 以内(1 跳び以内)で、直前に使った 4 か所の 4 ブロック以内には戻らない。
     * 見える所(ここでは相手の東側だけ)があればそちらを選ぶ。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void relocateNeverReusesSpotAndKeepsHeightBand(GameTestHelper helper) {
        DartHover.SpotRules r = AcumnesEntity.SPOTS;
        Random rnd = new Random(1234L);
        DartHover.Space open = new DartHover.Space() {
            @Override
            public boolean fits(double x, double y, double z) {
                return true;
            }

            @Override
            public boolean clearPath(double x0, double y0, double z0, double x1, double y1, double z1) {
                return true;
            }

            @Override
            public boolean canSee(double x, double y, double z) {
                return x > 0.0D;
            }

            @Override
            public double floorAt(double x, double z) {
                return 70.0D;
            }
        };
        double px = 0.0D, py = 64.0D, pz = 0.0D;
        double x = 8.0D, y = 76.0D, z = 0.0D;
        double[] used = new double[4 * 3];
        int count = 0, next = 0, seen = 0;
        for (int i = 0; i < 30; i++) {
            used[next * 3] = x;
            used[next * 3 + 1] = y;
            used[next * 3 + 2] = z;
            next = (next + 1) % 4;
            count = Math.min(4, count + 1);
            double[] s = DartHover.pickSpot(r, open, rnd::nextDouble, x, y, z, true, px, py, pz, 0.0D, 0.0D, used, count);
            if (s == null) {
                helper.fail(i + " 回目: 開けた所で場所を選べない");
                return;
            }
            for (int k = 0; k < count; k++) {
                double dx = used[k * 3] - s[0], dy = used[k * 3 + 1] - s[1], dz = used[k * 3 + 2] - s[2];
                if (dx * dx + dy * dy + dz * dz < r.reuseGap * r.reuseGap) {
                    helper.fail(String.format("%d 回目: 使った場所の %.2f ブロックへ戻った", i, Math.sqrt(dx * dx + dy * dy + dz * dz)));
                    return;
                }
            }
            double above = s[1] - py;
            if (above < r.aboveMin - 1e-6 || above > r.aboveMax + 1e-6) {
                helper.fail(String.format("%d 回目: 相手の %.2f 上(8〜20 でない)", i, above));
                return;
            }
            double hop = Math.sqrt((s[0] - x) * (s[0] - x) + (s[1] - y) * (s[1] - y) + (s[2] - z) * (s[2] - z));
            if (hop > r.hopMax + 1e-6) {
                helper.fail(String.format("%d 回目: 1 跳びが長すぎる %.2f", i, hop));
                return;
            }
            if (s[3] > 0.5D) seen++;
            x = s[0];
            y = s[1];
            z = s[2];
        }
        if (seen < 27) {
            helper.fail("見える所を選ばない: 30 回中 " + seen);
            return;
        }
        helper.succeed();
    }

    /** 世界の中で: 止まっている間は 40 tick 一歩も動かない(重力・風・押されに負けない)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 60)
    public static void holdsStillInWorld(GameTestHelper helper) {
        AcumnesEntity a = spawn(helper, 3.5D, 2.5D, 3.5D);
        a.roostOverride = Boolean.FALSE;
        a.debugHoldHere(1000);
        Vec3[] start = {null};
        helper.runAfterDelay(5L, () -> start[0] = a.position());
        helper.runAfterDelay(45L, () -> {
            if (a.mode() != SkyFlyer.MODE_FLY || !a.hover().holding()) {
                helper.fail("止まって飛んでいない: 動き " + a.mode() + " 段 " + a.hover().phase);
                return;
            }
            double d = a.position().distanceTo(start[0]);
            if (d > 1e-6) {
                helper.fail(String.format("止まっている間に %.4f ブロック動いた", d));
                return;
            }
            helper.succeed();
        });
    }

    /** 止まり木に止まっているところを叩くと飛び立ち(30 tick で 2 ブロック以上昇る)、その後は空中でピタッと止まる(トンボの動きへ戻る)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 80)
    public static void takesOffWhenHitOnPerchThenHovers(GameTestHelper helper) {
        helper.setBlock(new BlockPos(3, 1, 3), Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
        BlockPos top = helper.absolutePos(new BlockPos(3, 1, 3));
        AcumnesEntity a = spawn(helper, 3.5D, 2.0D, 3.5D);
        a.roostOverride = Boolean.TRUE;
        a.debugPerchAt(top);
        a.debugTickFlock();
        double[] y0 = {Double.NaN};
        helper.runAfterDelay(5L, () -> {
            if (a.mode() != SkyFlyer.MODE_PERCHED) {
                helper.fail("止まったままでいない: " + a.mode());
                return;
            }
            y0[0] = a.getY();
            a.hurt(helper.getLevel().damageSources().generic(), 1.0F);
        });
        helper.runAfterDelay(35L, () -> {
            int m = a.mode();
            if (m == SkyFlyer.MODE_PERCHED || m == SkyFlyer.MODE_LANDING) {
                helper.fail("叩かれても飛び立たない: " + m);
                return;
            }
            if (a.getY() - y0[0] < 2.0D) {
                helper.fail(String.format("昇っていない(%.2f)", a.getY() - y0[0]));
                return;
            }
        });
        helper.runAfterDelay(60L, () -> {
            if (a.mode() != SkyFlyer.MODE_FLY) {
                helper.fail("飛んでいない: " + a.mode());
                return;
            }
            // 止まるか鋭く飛ぶかのどちらか(テスト場が狭いので選べる場所はほぼ無い)。止まり点が決まっていれば、トンボの動きに戻っている
            if (Double.isNaN(a.hover().ax)) {
                helper.fail("トンボの動きに戻っていない(止まり点が無い)");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * 止まり木の真上の空きは種ごと(Vault 5.7 の 12): アクムネスは 8(若枝の姿 7.35)、既定(ヴェロフィルなど)は 6 のまま。
     * 葉の 7 ブロック上に石があると、既定では止まれて、アクムネスの空きでは止まれない。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void perchClearanceIsPerSpecies(GameTestHelper helper) {
        helper.setBlock(new BlockPos(3, 1, 3), Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
        helper.setBlock(new BlockPos(3, 8, 3), Blocks.STONE);
        BlockPos top = helper.absolutePos(new BlockPos(3, 1, 3));
        var level = helper.getLevel();
        if (!com.yayuy.overgrownvegetation.entity.flyer.SkyProbe.clearAbove(level, top)) {
            helper.fail("既定(6)で止まれない(ほかの種の動きが変わった)");
            return;
        }
        if (com.yayuy.overgrownvegetation.entity.flyer.SkyProbe.clearAbove(level, top, AcumnesEntity.PERCH_CLEAR_UP)) {
            helper.fail("アクムネスの空き 8 なのに 7 上の石を見逃した");
            return;
        }
        helper.succeed();
    }

    /**
     * 木から飛び立つときだけ溜める【決定 11-15 ユーザー】: 叩かれてもその場で takeoff_windup の分(8 + 入りの補間 3 tick)は動かず、
     * 送る段は「溜め」。その後に昇る段へ移る。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 60)
    public static void takeoffFromPerchWindsUpInPlace(GameTestHelper helper) {
        helper.setBlock(new BlockPos(3, 1, 3), Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
        BlockPos top = helper.absolutePos(new BlockPos(3, 1, 3));
        AcumnesEntity a = spawn(helper, 3.5D, 2.0D, 3.5D);
        a.roostOverride = Boolean.TRUE;
        a.debugPerchAt(top);
        a.debugTickFlock();
        double[] y0 = {Double.NaN};
        int[] hitAt = {-1}, windup = {0}, climbAt = {-1};
        helper.runAfterDelay(5L, () -> {
            y0[0] = a.getY();
            hitAt[0] = a.tickCount;
            a.hurt(helper.getLevel().damageSources().generic(), 1.0F);
        });
        helper.onEachTick(() -> {
            if (hitAt[0] < 0 || climbAt[0] >= 0 || a.mode() != SkyFlyer.MODE_TAKEOFF) return;
            if (a.takeoffPhase() == 1) {
                windup[0]++;
                if (Math.abs(a.getY() - y0[0]) > 1e-6) helper.fail(String.format("溜めの間に %.3f 動いた", a.getY() - y0[0]));
            } else if (a.takeoffPhase() == 2) {
                climbAt[0] = a.tickCount;
            }
        });
        helper.runAfterDelay(50L, () -> {
            if (climbAt[0] < 0) {
                helper.fail("昇り始めない(溜め " + windup[0] + " tick)");
                return;
            }
            int want = AcumnesEntity.WINDUP_TICKS + AcumnesEntity.ENTRY_SHORT;
            if (Math.abs(windup[0] - want) > 1) {
                helper.fail("溜めの長さが " + windup[0] + " tick(" + want + " のはず)");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * 狙う相手【決定 11-15 ユーザー: 植物側の Mob 以外すべて】: 村人・動物・ゴーレムは狙い、植物側(ヴェロフィル・ほかのアクムネス)は狙わない。
     * 村人とヴェロフィルを並べると村人を選ぶ。プレイヤーを先に選ぶ順は、偽プレイヤーが世界のプレイヤーの一覧に入らないので確かめていない。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 60)
    public static void targetsEveryoneButPlantSide(GameTestHelper helper) {
        AcumnesEntity a = spawn(helper, 3.5D, 3.0D, 3.5D);
        a.roostOverride = Boolean.FALSE;
        a.debugHoldHere(1000);
        net.minecraft.world.entity.Mob villager = helper.spawnWithNoFreeWill(net.minecraft.world.entity.EntityType.VILLAGER, new BlockPos(1, 2, 1));
        net.minecraft.world.entity.Mob cow = helper.spawnWithNoFreeWill(net.minecraft.world.entity.EntityType.COW, new BlockPos(5, 2, 1));
        net.minecraft.world.entity.Mob golem = helper.spawnWithNoFreeWill(net.minecraft.world.entity.EntityType.IRON_GOLEM, new BlockPos(5, 2, 5));
        net.minecraft.world.entity.Mob velo = helper.spawnWithNoFreeWill(ModFlyers.VELOPHYLL.get(), new BlockPos(1, 2, 5));
        AcumnesEntity other = spawn(helper, 1.5D, 4.0D, 3.5D);
        other.setNoAi(true);
        if (!AcumnesEntity.isPrey(a, villager) || !AcumnesEntity.isPrey(a, cow) || !AcumnesEntity.isPrey(a, golem)) {
            helper.fail("村人・牛・ゴーレムを狙わない");
            return;
        }
        if (AcumnesEntity.isPrey(a, velo) || AcumnesEntity.isPrey(a, other) || AcumnesEntity.isPrey(a, a)) {
            helper.fail("植物側・自分を狙う");
            return;
        }
        cow.discard();
        golem.discard();
        helper.runAfterDelay(30L, () -> {
            if (a.getTarget() != villager) {
                helper.fail("村人を選ばない: " + a.getTarget());
                return;
            }
            helper.succeed();
        });
    }
}
