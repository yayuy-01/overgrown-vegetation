package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
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
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

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
        public static void onRegisterAcumnesGameTests(RegisterGameTestsEvent event) { // 1.20.1: Forge の受け手の包みは名前で使い回すので、クラスごとに名前を変える
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

    /** G3 に入り、自然には湧かず、芯は 1.2 × 3.0(体格 1)、翅・頭・腹の叩ける部位がある。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void registeredAsG3WithParts(GameTestHelper helper) {
        AcumnesEntity a = spawn(helper, 3.5D, 2.0D, 3.5D);
        if (MobGroup.of(a.getType()) != MobGroup.GROUP_3) {
            helper.fail("G3 に入っていない: " + MobGroup.of(a.getType()));
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
    @GameTest(template = "gametest_empty7", timeoutTicks = 120)
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
        helper.runAfterDelay(90L, () -> {
            if (a.getTarget() != villager) {
                helper.fail("村人を選ばない: " + a.getTarget());
                return;
            }
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ 技(2026-11-15 能力の段)

    /** 浮いた個体を置き、技の相手(AI の無い村人)を並べる。 */
    private static AcumnesEntity skillRig(GameTestHelper helper, double ax, double ay, double az) {
        AcumnesEntity a = spawn(helper, ax, ay, az);
        a.roostOverride = Boolean.FALSE;
        a.debugHoldHere(1000);
        return a;
    }

    private static net.minecraft.world.entity.Mob armoredZombie(GameTestHelper helper, BlockPos at) {
        net.minecraft.world.entity.Mob z = helper.spawnWithNoFreeWill(net.minecraft.world.entity.EntityType.ZOMBIE, at);
        z.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_HELMET));
        z.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_CHESTPLATE));
        z.setItemSlot(net.minecraft.world.entity.EquipmentSlot.LEGS, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_LEGGINGS));
        z.setItemSlot(net.minecraft.world.entity.EquipmentSlot.FEET, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_BOOTS));
        return z;
    }

    /**
     * 狙撃【決定 11-15】: 溜め(入り 4 + aim 2 秒)の後に 1 発。ダイヤの防具を着ても 12 減る(防具を貫く)。撃って装填したら技を終え、必ず場所を移す。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 140)
    public static void snipePiercesArmorThenRelocates(GameTestHelper helper) {
        AcumnesEntity a = skillRig(helper, 0.5D, 5.0D, 0.5D);
        net.minecraft.world.entity.Mob z = armoredZombie(helper, new BlockPos(5, 2, 5));
        float hp0 = z.getHealth();
        int moves0 = a.relocations;
        a.debugStartSkill(z, AcumnesEntity.S_AIM, 0);
        helper.runAfterDelay(AcumnesEntity.AIM_TICKS - 5, () -> {
            if (z.getHealth() < hp0) helper.fail("溜めの途中で当たった");
        });
        helper.runAfterDelay(AcumnesEntity.AIM_TICKS + 12, () -> {
            float lost = hp0 - z.getHealth();
            if (lost < 11.5F) helper.fail(String.format("防具を貫いていない: %.1f しか減らない", lost));
        });
        helper.runAfterDelay(AcumnesEntity.AIM_TICKS + 50, () -> {
            if (a.skill() != AcumnesEntity.S_NONE) {
                helper.fail("撃った後に技を終えない: 段 " + a.skill());
                return;
            }
            if (a.relocations <= moves0 && !a.mustMove()) {
                helper.fail("撃った後に場所を移さない");
                return;
            }
            helper.succeed();
        });
    }

    /** 物陰で狙いの線を 0.3 秒切ると取りやめ、撃たない【決定 11-15: 線は描かないが判定は残す】。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 100)
    public static void aimCancelsWhenLineIsCut(GameTestHelper helper) {
        AcumnesEntity a = skillRig(helper, 0.5D, 5.0D, 0.5D);
        net.minecraft.world.entity.Mob z = helper.spawnWithNoFreeWill(net.minecraft.world.entity.EntityType.VILLAGER, new BlockPos(5, 2, 5));
        float hp0 = z.getHealth();
        a.debugStartSkill(z, AcumnesEntity.S_AIM, 0);
        helper.runAfterDelay(20L, () -> {
            // 相手を石の箱で囲む(物陰)
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int dy = 0; dy <= 2; dy++) {
                if (dx == 0 && dz == 0 && dy < 2) continue;
                helper.setBlock(new BlockPos(5 + dx, 2 + dy, 5 + dz), Blocks.STONE);
            }
        });
        helper.runAfterDelay(20L + AcumnesEntity.LOS_GRACE + 3, () -> {
            int s = a.skill();
            if (s == AcumnesEntity.S_AIM || s == AcumnesEntity.S_FIRE) helper.fail("線を切っても狙い続ける: 段 " + s);
        });
        helper.runAfterDelay(AcumnesEntity.AIM_TICKS + 30, () -> {
            if (z.getHealth() < hp0) {
                helper.fail("取りやめたのに当たった");
                return;
            }
            helper.succeed();
        });
    }

    /** 縫い止め【決定 11-15】: 約 3 秒狙ってから重い棘。当たった相手は 20 tick 動けない(移動の属性が 0)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 160)
    public static void pinRootsTheTarget(GameTestHelper helper) {
        AcumnesEntity a = skillRig(helper, 0.5D, 5.0D, 0.5D);
        net.minecraft.world.entity.Mob z = armoredZombie(helper, new BlockPos(5, 2, 5));
        float hp0 = z.getHealth();
        a.debugStartSkill(z, AcumnesEntity.S_AIM, AcumnesEntity.SKILL_PIN);
        helper.runAfterDelay(AcumnesEntity.AIM_TICKS + 4, () -> {
            if (z.getHealth() < hp0) helper.fail("縫い止めが狙撃の長さで撃った(約 3 秒のはず)");
        });
        helper.runAfterDelay(AcumnesEntity.PIN_AIM_TICKS + 8, () -> {
            if (!z.hasEffect(com.yayuy.overgrownvegetation.registry.ModMobEffects.ACUMNES_PINNED.get())) {
                helper.fail("縫い止められていない(体力 " + z.getHealth() + ")");
                return;
            }
            if (z.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED) > 1e-6) {
                helper.fail("縫い止められても動ける: " + z.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED));
                return;
            }
            if (hp0 - z.getHealth() < AcumnesEntity.PIN_DAMAGE - 0.5F) {
                helper.fail("縫い止めのダメージが防具に削られた: " + (hp0 - z.getHealth()));
                return;
            }
        });
        helper.runAfterDelay(AcumnesEntity.PIN_AIM_TICKS + 40, () -> {
            if (z.hasEffect(com.yayuy.overgrownvegetation.registry.ModMobEffects.ACUMNES_PINNED.get())) {
                helper.fail("1 秒で解けない");
                return;
            }
            helper.succeed();
        });
    }

    /** 散弾【決定 11-15】: 前触れ 10 tick の後、2〜6 tick に 1 発ずつ 5 発。植物側(ほかのアクムネス)には当たらない。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 80)
    public static void scatterThrowsFiveSpines(GameTestHelper helper) {
        AcumnesEntity a = skillRig(helper, 3.5D, 4.0D, 0.5D);
        net.minecraft.world.entity.Mob v = helper.spawnWithNoFreeWill(net.minecraft.world.entity.EntityType.VILLAGER, new BlockPos(3, 2, 6));
        a.debugStartSkill(v, AcumnesEntity.S_SCATTER_WINDUP, 0);
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        int[] before = {0};
        helper.onEachTick(() -> {
            for (com.yayuy.overgrownvegetation.entity.AcumnesSpine s : helper.getLevel().getEntitiesOfClass(
                    com.yayuy.overgrownvegetation.entity.AcumnesSpine.class, a.getBoundingBox().inflate(16.0D))) {
                if (s.kind() == com.yayuy.overgrownvegetation.entity.AcumnesSpine.SCATTER && s.getOwner() == a && seen.add(s.getId())
                        && a.skill() == AcumnesEntity.S_SCATTER_WINDUP) before[0]++;
            }
        });
        helper.runAfterDelay(40L, () -> {
            if (before[0] > 0) {
                helper.fail("前触れの間に撃った");
                return;
            }
            if (seen.size() != 5) {
                helper.fail("散弾が 5 発でない: " + seen.size());
                return;
            }
            helper.succeed();
        });
    }

    /**
     * 刺突を外す【決定 11-15】: 針が壁に刺さると {@code dive_recover} の 34 tick は体が動かない(殴り返せる隙)。その後は離れて解き、技を終える。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 160)
    public static void diveMissSticksThenPullsAway(GameTestHelper helper) {
        AcumnesEntity a = skillRig(helper, 0.5D, 3.0D, 3.5D);
        a.setYRot(-90.0F);
        net.minecraft.world.entity.Mob v = helper.spawnWithNoFreeWill(net.minecraft.world.entity.EntityType.VILLAGER, new BlockPos(6, 2, 3));
        for (int dy = 1; dy <= 6; dy++) for (int dz = 0; dz <= 6; dz++) helper.setBlock(new BlockPos(4, dy, dz), Blocks.STONE);
        a.debugStartSkill(v, AcumnesEntity.S_DIVE_WINDUP, 0);
        Vec3[] stuckAt = {null};
        int[] missAt = {-1};
        helper.onEachTick(() -> {
            if (missAt[0] < 0 && a.skill() == AcumnesEntity.S_DIVE_MISS) {
                missAt[0] = a.tickCount;
                stuckAt[0] = a.position();
            } else if (missAt[0] >= 0 && a.skill() == AcumnesEntity.S_DIVE_MISS && a.position().distanceTo(stuckAt[0]) > 1e-6) {
                helper.fail("針が刺さっている間に動いた");
            }
        });
        helper.runAfterDelay(80L, () -> {
            if (missAt[0] < 0) {
                helper.fail("壁に刺さらない: 段 " + a.skill());
                return;
            }
            int s = a.skill();
            if (s == AcumnesEntity.S_DIVE_MISS || s == AcumnesEntity.S_DIVE || s == AcumnesEntity.S_DIVE_WINDUP) {
                helper.fail("隙が 34 tick で終わらない: 段 " + s);
            }
        });
        helper.runAfterDelay(150L, () -> {
            if (a.skill() != AcumnesEntity.S_NONE) {
                helper.fail("刺突を終えない: 段 " + a.skill());
                return;
            }
            helper.succeed();
        });
    }

    /** 刺突が当たる: 針の先が届けば 10 減り、当てた後の段(dive_hit_recover)へ。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 60)
    public static void diveHitsTarget(GameTestHelper helper) {
        AcumnesEntity a = skillRig(helper, 0.5D, 2.0D, 3.5D);
        a.setYRot(-90.0F);
        net.minecraft.world.entity.Mob v = helper.spawnWithNoFreeWill(net.minecraft.world.entity.EntityType.VILLAGER, new BlockPos(6, 2, 3));
        float hp0 = v.getHealth();
        a.debugStartSkill(v, AcumnesEntity.S_DIVE_WINDUP, 0);
        helper.runAfterDelay(30L, () -> {
            if (hp0 - v.getHealth() < AcumnesEntity.DIVE_DAMAGE - 0.5F) {
                helper.fail("刺突が当たらない: 減った " + (hp0 - v.getHealth()) + " 段 " + a.skill());
                return;
            }
            helper.succeed();
        });
    }

    /**
     * 射程 60【11-16 ユーザー決定「遠距離はもっと射程を」】に揃っているか: 相手を見つける距離(followRange)が 60 以上・
     * 止まり場所の帯(水平 radiusMax・高さ aboveMax)の一番遠い所が射程の中。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void snipeReachIsSixty(GameTestHelper helper) {
        AcumnesEntity a = spawn(helper, 3.5D, 2.0D, 3.5D);
        double follow = a.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.FOLLOW_RANGE);
        if (AcumnesEntity.SNIPE_MAX < 60.0D) {
            helper.fail("狙撃の射程が 60 でない: " + AcumnesEntity.SNIPE_MAX);
            return;
        }
        if (follow < AcumnesEntity.SNIPE_MAX) {
            helper.fail("相手を見つける距離 " + follow + " が射程 " + AcumnesEntity.SNIPE_MAX + " より短い(config の写しが古いかも)");
            return;
        }
        double far = Math.sqrt(AcumnesEntity.SPOTS.radiusMax * AcumnesEntity.SPOTS.radiusMax + AcumnesEntity.SPOTS.aboveMax * AcumnesEntity.SPOTS.aboveMax);
        if (far > AcumnesEntity.SNIPE_MAX) {
            helper.fail(String.format("止まり場所の一番遠い所 %.1f が射程の外", far));
            return;
        }
        helper.succeed();
    }
}
