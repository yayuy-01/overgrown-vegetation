package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.SeminariaEntity;
import com.yayuy.overgrownvegetation.entity.SeminariaSeed;
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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

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

    /** G2 に入り、自然には湧かず、芯は 0.8 × 1.0、翅と腹の叩ける部位がある。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void seminariaRegisteredAsG2WithParts(GameTestHelper helper) {
        SeminariaEntity v = spawn(helper, 3.5D, 3.0D, 3.5D);
        if (MobGroup.of(v.getType()) != MobGroup.GROUP_2) {
            helper.fail("G2 に入っていない: " + MobGroup.of(v.getType()));
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

    /** ひらひら: 飛んでいる間、まっすぐ行けと言われても向きが左右に揺れる(120 tick までに向きの変わり方の左右が 2 回以上入れ替わる)。
     * 揺れの位相は個体ごとの乱数なので、前の 60 tick の窓では位相しだいで 1 回しか入れ替わらず、時々落ちていた(2026-11-18 揺れるテストの調べ: 過去 273 回中 11 回)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 160)
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
            if (flips[0] >= 2) {
                helper.succeed();
                return;
            }
            if (++ticks[0] >= 120) {
                helper.fail("向きが揺れない(入れ替わり " + flips[0] + " 回)");
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

    /**
     * 能力(11-16): 種を落とす升は「台帳に載っていない・まわり 1 チャンクに台帳の升が無く、風上へ 2〜4 チャンク戻ると台帳の升がある」もので、先頭にいちばん近い升。
     * 台帳の升はテスト場から遠いチャンク(ほかのテストと重ならない所)に置き、終わったら消す。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void seminariaSowTargetIsDownwindOfFront(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        com.yayuy.overgrownvegetation.phase.PhaseData pd = com.yayuy.overgrownvegetation.phase.PhaseData.get(level);
        int X = 1_500_000, Z = 1_500_000;   // チャンクの番号(ブロック 2400 万。どのテストも使わない所)
        net.minecraft.world.level.ChunkPos front = new net.minecraft.world.level.ChunkPos(X, Z);
        boolean added = pd.adoptOvergrownChunk(level, front);   // フェーズのポイントを増やさない方
        try {
            long none = SeminariaEntity.findSowTarget(pd, X + 20, Z, 0.1D, 0.0D);
            if (none != Long.MIN_VALUE) {
                helper.fail("前線の無い所で升を選んだ");
                return;
            }
            // 東風(+x へ吹く): 前線の東 2 チャンクにいる先頭 → 自分の升(前線から風下へ 2)
            long east = SeminariaEntity.findSowTarget(pd, X + 2, Z, 0.1D, 0.0D);
            if (east != net.minecraft.world.level.ChunkPos.asLong(X + 2, Z)) {
                helper.fail("東風で風下の先の升を選ばない: " + new net.minecraft.world.level.ChunkPos(east));
                return;
            }
            // 西風(−x へ吹く): 同じ所の先頭 → 前線の西 2(風下の先で、前線に接していない升。東側は風上なので選ばない)
            long west = SeminariaEntity.findSowTarget(pd, X + 2, Z, -0.1D, 0.0D);
            if (west != net.minecraft.world.level.ChunkPos.asLong(X - 2, Z)) {
                helper.fail("西風で前線の風下の先(西 2)を選ばない: " + (west == Long.MIN_VALUE ? "無し" : new net.minecraft.world.level.ChunkPos(west)));
                return;
            }
            // 前線に接した升は選ばない: 東隣にいる先頭 → 東 2
            long gap = SeminariaEntity.findSowTarget(pd, X + 1, Z, 0.1D, 0.0D);
            if (gap != net.minecraft.world.level.ChunkPos.asLong(X + 2, Z)) {
                helper.fail("前線に接した升を選んだ・東 2 を選ばない: " + (gap == Long.MIN_VALUE ? "無し" : new net.minecraft.world.level.ChunkPos(gap)));
                return;
            }
            // 台帳に載った升そのものは選ばない
            long on = SeminariaEntity.findSowTarget(pd, X, Z, 0.1D, 0.0D);
            if (on == front.toLong()) {
                helper.fail("台帳に載った升を選んだ");
                return;
            }
            helper.succeed();
        } finally {
            if (added) pd.forgetOvergrownChunk(front);
        }
    }

    /**
     * 能力(11-16): 舞い降りて、浮いたまま(足元が地面から 2 ブロック以上)前触れの後に種を 1 粒落とし、種は地面に落ちて降り積もりを足す。
     * 種が離れるのは落とす段の 3 tick 目。終わると土台の飛び方へ戻る。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 260)
    public static void seminariaDescendsAndDropsSeedWhileFloating(GameTestHelper helper) {
        SeminariaEntity v = spawn(helper, 3.5D, 7.0D, 3.5D);
        v.roostOverride = Boolean.FALSE;
        v.debugNoSowDrift = true;   // 隣のテストの上へ流されないように
        int dropped0 = SeminariaEntity.seedsDropped, landed0 = SeminariaSeed.landed;
        double[] heightAtDrop = {Double.NaN};
        boolean[] began = {false}, done = {false};
        helper.onEachTick(() -> {
            if (done[0]) return;
            if (!began[0]) {
                if (v.mode() != SkyFlyer.MODE_FLY) return;
                began[0] = true;
                v.debugBeginSow();
                return;
            }
            if (Double.isNaN(heightAtDrop[0]) && SeminariaEntity.seedsDropped > dropped0) {
                int surface = SeminariaEntity.groundBelow(helper.getLevel(), v.position());
                heightAtDrop[0] = v.getY() - surface;
                if (heightAtDrop[0] < 2.0D) {
                    done[0] = true;
                    helper.fail(String.format("浮いたまま落とさない: 足元が地面から %.2f(足元 y %.2f・真下の面 %d・テスト場の床 %d)", heightAtDrop[0],
                            v.getY(), surface, helper.absolutePos(BlockPos.ZERO).getY()));
                    return;
                }
            }
            if (!Double.isNaN(heightAtDrop[0]) && SeminariaSeed.landed > landed0 && v.sowPhase() == SeminariaEntity.SOW_NONE) {
                done[0] = true;
                if (SeminariaEntity.seedsDropped - dropped0 != 1) {
                    helper.fail("1 回の種まきで落とした数が 1 でない: " + (SeminariaEntity.seedsDropped - dropped0));
                    return;
                }
                helper.succeed();
            }
        });
    }

    /**
     * 能力(11-16): 種が落ちた升は飛び地になる(場の式。1 日進める)。群れの 3 粒を汚染の無い遠くの升に落とすと、8 か所のうち 1 か所以上で芽が出る
     * (感染した Mob の飛び火 {@code MobInfectionGameTests#singleJumpGerminatesFarChunk} と同じ確かめ方)。1 粒だけの升の割合も記録に出す。
     */
    @GameTest(template = "gametest_empty7", batch = "spore_field_wait", timeoutTicks = 600)
    public static void seminariaSeedsGerminateEnclave(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        com.yayuy.overgrownvegetation.difficulty.WorldDifficultyData data = com.yayuy.overgrownvegetation.difficulty.WorldDifficultyData.get(level.getServer());
        java.util.Map<com.yayuy.overgrownvegetation.difficulty.DifficultySetting, Double> saved = data.overrides();
        int trials = 8;
        int[] sprouted = new int[2];   // [0] = 3 粒、[1] = 1 粒
        StringBuilder log = new StringBuilder();
        long dayTimeBefore = level.getDayTime();
        try {
            data.set(com.yayuy.overgrownvegetation.difficulty.DifficultySetting.UNLOADED_SPREAD, 0.0D); // 読み込まれていない升は「待ち」で残る
            // 追いつきの 1 日の終わりを夕方にそろえる(11-18 切り分け: 発芽は 1 日の終わりの時刻で大きく変わり、昼に終わると芽が出ない。
            // 前のテストの数で走る時刻がずれ、ほかのテストを足しただけで決まって落ちていた)
            level.setDayTime(Math.floorDiv(dayTimeBefore, 24000L) * 24000L + 13000L);
            for (int seeds = 3, row = 0; row < 2; seeds = 1, row++) {
                for (int k = 0; k < trials; k++) {
                    int cx = 1_400_000 + k * 40, cz = -1_400_000 - row * 40;
                    long key = net.minecraft.world.level.ChunkPos.asLong(cx, cz);
                    com.yayuy.overgrownvegetation.spore.field.SporeFieldServer.watchForTests(cx, cz);
                    for (int s = 0; s < seeds; s++) SeminariaSeed.land(level, new Vec3(cx * 16 + 6 + s * 2, 64, cz * 16 + 8));
                    com.yayuy.overgrownvegetation.spore.field.SporeFieldServer.catchUpForTests(level, 24000L, key);
                    boolean hit = Boolean.TRUE.equals(com.yayuy.overgrownvegetation.spore.field.SporeFieldServer.withFieldForTests(
                            f -> f.isWaiting(cx, cz) || f.isCorrupted(cx, cz)));
                    if (hit) sprouted[row]++;
                    log.append(hit ? "芽 " : "- ");
                    com.yayuy.overgrownvegetation.spore.field.SporeFieldServer.withFieldForTests(f -> {
                        f.unsprout(cx, cz);
                        f.clearDepositAround(cx, cz, 1.5D);
                        f.extractAround(cx, cz, 1.5D, Double.POSITIVE_INFINITY);
                        return null;
                    });
                    com.yayuy.overgrownvegetation.phase.PhaseData.get(level).forgetOvergrownChunk(new net.minecraft.world.level.ChunkPos(cx, cz));
                }
                log.append(row == 0 ? "| 1 粒: " : "");
            }
        } finally {
            level.setDayTime(dayTimeBefore);
            data.replaceAll(saved);
        }
        OvergrownVegetation.LOGGER.info("セミナリアの種の飛び地(1 日): 3 粒 {}/{}・1 粒 {}/{} [{}]", sprouted[0], trials, sprouted[1], trials, log);
        if (sprouted[0] == 0) {
            helper.fail("3 粒落とした升が 1 日でどこも芽を出さない: " + log);
            return;
        }
        helper.succeed();
    }

    /** 能力(11-16): 飛んでいる間に叩かれると、群れで逃げる(群れの状態 FLEE)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 60)
    public static void seminariaFleesWhenHitInFlight(GameTestHelper helper) {
        SeminariaEntity v = spawn(helper, 3.5D, 6.0D, 3.5D);
        v.roostOverride = Boolean.FALSE;
        boolean[] hit = {false};
        helper.onEachTick(() -> {
            if (hit[0] || v.mode() != SkyFlyer.MODE_FLY || v.flock() == null) return;
            hit[0] = true;
            v.hurt(helper.getLevel().damageSources().generic(), 1.0F);
            if (v.flock().state() != Flock.State.FLEE) {
                helper.fail("飛んでいる間に叩かれても逃げない: " + v.flock().state());
                return;
            }
            helper.succeed();
        });
    }

    /**
     * 段 2(11-15): 一斉に飛び立つ距離は 10【仮・決定 11-15】(土台・ヴェロフィルは 8 のまま)、溜めは 7 tick。
     * 風に揉まれる層は強さ 0.4 から揉まれ始め 1.3 でいっぱい【仮】。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void seminariaStage2Wiring(GameTestHelper helper) {
        if (SeminariaEntity.FLUSH_RANGE_SEMINARIA != 10.0D || SkyFlyer.FLUSH_RANGE != 8.0D) {
            helper.fail("飛び立つ距離が違う: " + SeminariaEntity.FLUSH_RANGE_SEMINARIA + " / 土台 " + SkyFlyer.FLUSH_RANGE);
            return;
        }
        if (SeminariaEntity.WINDUP_TICKS != 7) {
            helper.fail("溜めが 7 tick でない: " + SeminariaEntity.WINDUP_TICKS);
            return;
        }
        if (SeminariaEntity.gustFor(0.3D) != 0.0F || SeminariaEntity.gustFor(1.5D) != 1.0F
                || Math.abs(SeminariaEntity.gustFor(1.0D) - 0.6667F) > 0.01F) {
            helper.fail("揉まれる層の強さの曲線が違う: " + SeminariaEntity.gustFor(1.0D));
            return;
        }
        helper.succeed();
    }
}
