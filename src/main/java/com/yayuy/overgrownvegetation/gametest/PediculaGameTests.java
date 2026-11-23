package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.entity.PediculaEntity;
import com.yayuy.overgrownvegetation.entity.flyer.BalloonDrift;
import com.yayuy.overgrownvegetation.entity.flyer.FlightBody;
import com.yayuy.overgrownvegetation.entity.flyer.SkyFlyer;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import com.yayuy.overgrownvegetation.registry.ModFlyers;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * ペディキュラの段 1(2026-11-15): 「漂う型」。高さの帯(地面 +40〜80)はテスト場(7 × 7 × 7)に収まらないので、高さの風・帯・高さ選び・
 * 上下の動きは計算だけで回す(ゲームの中と同じ {@link BalloonDrift} の関数)。テスト場では、登録・群れを持たないこと・浮き上がることを見る。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class PediculaGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterPediculaGameTests(RegisterGameTestsEvent event) { // 1.20.1: Forge の受け手の包みは名前で使い回すので、クラスごとに名前を変える
            event.register(PediculaGameTests.class);
        }

        private Registration() {
        }
    }

    private PediculaGameTests() {
    }

    private static final long SEED = 0x5EEDL;

    /** G2 に入り、自然には湧かず、当たりは設計値どおり、飛ぶ型の群れ({@code SkyFlyer})ではない。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void pediculaRegisteredAsG2(GameTestHelper helper) {
        PediculaEntity p = helper.spawn(ModFlyers.PEDICULA.get(), new BlockPos(3, 2, 3));
        p.debugGround = helper.absolutePos(new BlockPos(0, 1, 0)).getY();
        if (MobGroup.of(p.getType()) != MobGroup.GROUP_2) {
            helper.fail("G2 に入っていない: " + MobGroup.of(p.getType()));
            return;
        }
        float w = MobStats.width(PediculaEntity.ID, 3.6F), h = MobStats.height(PediculaEntity.ID, 3.6F);
        if (Math.abs(p.getBbWidth() - w) > 1e-3 || Math.abs(p.getBbHeight() - h) > 1e-3) {
            helper.fail("当たりが設計値 " + w + " × " + h + " でない: " + p.getBbWidth() + " × " + p.getBbHeight());
            return;
        }
        if (((Object) p) instanceof SkyFlyer) {
            helper.fail("群れ・止まり木を持つ SkyFlyer になっている");
            return;
        }
        if (!p.isNoGravity()) {
            helper.fail("重力が効いている");
            return;
        }
        helper.succeed();
    }

    /**
     * 高さで風が違う: 帯(海面 +40〜+80 ≒ y 104〜144)の中で、向きは 60 度以上ずれる時がいつもあり(1 日ごとに 10 日見る)、上ほど強い。
     * 卓越風が 0 なら、どの高さも 0(世界の風そのものは変えない)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void pediculaWindTurnsWithHeight(GameTestHelper helper) {
        double[] a = new double[2], b = new double[2];
        double bx = 0.1D, bz = 0.0D;
        for (int day = 0; day < 10; day++) {
            long t = day * 24000L + 6000L;
            double maxDiff = 0.0D;
            for (double y1 = 104.0D; y1 <= 144.0D; y1 += 5.0D) {
                for (double y2 = y1; y2 <= 144.0D; y2 += 5.0D) {
                    BalloonDrift.layerWind(SEED, t, y1, bx, bz, a);
                    BalloonDrift.layerWind(SEED, t, y2, bx, bz, b);
                    double d = Math.abs(FlightBody.wrapDeg(FlightBody.yawOf(a[0], a[1]) - FlightBody.yawOf(b[0], b[1])));
                    maxDiff = Math.max(maxDiff, d);
                }
            }
            if (maxDiff < 60.0D) {
                helper.fail(String.format("%d 日目: 帯の中の向きの差が %.1f 度しかない", day, maxDiff));
                return;
            }
        }
        BalloonDrift.layerWind(SEED, 6000L, 104.0D, bx, bz, a);
        BalloonDrift.layerWind(SEED, 6000L, 144.0D, bx, bz, b);
        if (Math.hypot(b[0], b[1]) <= Math.hypot(a[0], a[1])) {
            helper.fail(String.format("上が強くない: 下 %.3f・上 %.3f", Math.hypot(a[0], a[1]), Math.hypot(b[0], b[1])));
            return;
        }
        BalloonDrift.layerWind(SEED, 6000L, 120.0D, 0.0D, 0.0D, a);
        if (a[0] != 0.0D || a[1] != 0.0D) {
            helper.fail("凪なのに風がある");
            return;
        }
        helper.succeed();
    }

    /**
     * 昼は高く夜は低く: 正午の帯は地面 +55〜80、真夜中は +40〜55。正午の高さ(地面 +70)から真夜中の帯の真ん中へ、
     * 降りる速さの上限を守って 1 分(1200 tick)以内に収まる。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void pediculaHighByDayLowByNight(GameTestHelper helper) {
        double noon = BalloonDrift.warmth(6000L), night = BalloonDrift.warmth(18000L);
        if (noon != 1.0D || night != 0.0D) {
            helper.fail("正午・真夜中の暖かさが 1・0 でない: " + noon + " / " + night);
            return;
        }
        if (BalloonDrift.bandLow(noon) != 55.0D || BalloonDrift.bandHigh(noon) != 80.0D
                || BalloonDrift.bandLow(night) != 40.0D || BalloonDrift.bandHigh(night) != 55.0D) {
            helper.fail("帯が決定(地面 +40〜80、昼は高く夜は低く)と違う");
            return;
        }
        FlightBody.Params p = PediculaEntity.PARAMS;
        BalloonDrift.State s = new BalloonDrift.State();
        double y = 70.0D, target = (BalloonDrift.bandLow(night) + BalloonDrift.bandHigh(night)) * 0.5D;
        double maxSink = 0.0D;
        int settled = -1;
        for (int t = 0; t < 1200; t++) {
            BalloonDrift.step(p, s, 0.05D, 0.0D, target - y);
            y += s.vy;
            maxSink = Math.max(maxSink, -s.vy);
            if (settled < 0 && Math.abs(y - target) < 1.0D) settled = t;
        }
        if (settled < 0) {
            helper.fail(String.format("1200 tick で夜の帯へ降りきらない(高さ %.1f・狙い %.1f)", y, target));
            return;
        }
        if (maxSink > p.sinkMax + 1e-9) {
            helper.fail(String.format("降りる速さ %.3f が上限 %.3f を超えた", maxSink, p.sinkMax));
            return;
        }
        if (Math.abs(s.vx - 0.05D * p.windFactor) > 1e-3) {
            helper.fail(String.format("横の速さ %.4f が風 %.4f に乗っていない", s.vx, 0.05D * p.windFactor));
            return;
        }
        helper.succeed();
    }

    /**
     * 上下して行き先を選ぶ: 帯の中のある高さの風の向きを行き先にすると、選ぶ高さの風は行き先へ帯の中でいちばん速く流れる
     * (真ん中に居続けるより速い)。今の高さとの差が小さいときは替えない。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void pediculaPicksHeightTowardGoal(GameTestHelper helper) {
        double bx = 0.08D, bz = 0.05D, low = 104.0D, high = 144.0D;
        long t = 3L * 24000L + 7000L;
        double[] w = new double[2];
        int better = 0;
        for (double yGood = low; yGood <= high; yGood += 5.0D) {
            BalloonDrift.layerWind(SEED, t, yGood, bx, bz, w);
            double l = Math.hypot(w[0], w[1]);
            double gx = w[0] / l, gz = w[1] / l;
            double y = BalloonDrift.chooseHeight(SEED, t, bx, bz, low, high, Double.NaN, gx, gz);
            BalloonDrift.layerWind(SEED, t, y, bx, bz, w);
            double chosen = w[0] * gx + w[1] * gz;
            double best = -1.0D;
            for (int i = 0; i < BalloonDrift.CANDIDATES; i++) {
                BalloonDrift.layerWind(SEED, t, low + (high - low) * i / (BalloonDrift.CANDIDATES - 1), bx, bz, w);
                best = Math.max(best, w[0] * gx + w[1] * gz);
            }
            if (chosen < best - 1e-9) {
                helper.fail(String.format("行き先 %.0f の向きで、いちばん良い高さを選んでいない(%.4f < %.4f)", yGood, chosen, best));
                return;
            }
            BalloonDrift.layerWind(SEED, t, (low + high) * 0.5D, bx, bz, w);
            if (chosen > w[0] * gx + w[1] * gz + 0.01D) better++;
            // 今の高さとの差が SWITCH_GAIN 以内なら替えない
            double cur = y + 0.5D <= high ? y + 0.5D : y - 0.5D;   // 帯の中に置く(帯の外の今の高さは替えてよい)
            double keep = BalloonDrift.chooseHeight(SEED, t, bx, bz, low, high, cur, gx, gz);
            BalloonDrift.layerWind(SEED, t, cur, bx, bz, w);
            boolean close = w[0] * gx + w[1] * gz >= best - BalloonDrift.SWITCH_GAIN;
            if (close && keep != cur) {
                helper.fail("差が小さいのに高さを替えた");
                return;
            }
        }
        if (better < 3) {
            helper.fail("真ん中に居続けるより速くなる行き先が少ない: " + better);
            return;
        }
        helper.succeed();
    }

    /**
     * 数の上限: 1 つの世界に {@link PediculaEntity#MAX_PER_LEVEL} 体まで。超えて出たものは最初の tick で消える。
     * {@code /summon} と同じく保存データの読み込み(空のタグ)を通したものも数える(11-15 テストクライアントで 14 体出た)。
     */
    /** 浮きの層の切り替え(段 2): 入る線と戻る線がずれていて、境目でちらつかない。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void pediculaFloatLayerHysteresis(GameTestHelper helper) {
        int F = PediculaEntity.FLOAT, R = PediculaEntity.RISE, S = PediculaEntity.SINK;
        int[][] cases = {
                // 今, vy(×1000), 次
                {F, 0, F}, {F, 29, F}, {F, 30, R}, {F, 60, R}, {F, -19, F}, {F, -20, S}, {F, -40, S},
                {R, 20, R}, {R, 10, R}, {R, 9, F}, {R, -40, S},
                {S, -10, S}, {S, -7, S}, {S, -6, F}, {S, 60, R}};
        for (int[] c : cases) {
            int got = PediculaEntity.nextFloatMode(c[0], c[1] / 1000.0D);
            if (got != c[2]) {
                helper.fail("今 " + c[0] + " vy " + c[1] / 1000.0D + " → " + got + "(" + c[2] + " のはず)");
                return;
            }
        }
        helper.succeed();
    }

    /** 射落とされたら(段 2): 転がりながら落ち、着いたら殻を残さず消える(ユーザー決定 11-15)。宙では消えない。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 80)
    public static void pediculaRupturesFallsAndVanishes(GameTestHelper helper) {
        PediculaEntity p = helper.spawn(ModFlyers.PEDICULA.get(), new BlockPos(3, 3, 3));
        p.debugGround = helper.absolutePos(new BlockPos(0, 1, 0)).getY();
        double startY = p.getY();
        p.hurt(helper.getLevel().damageSources().generic(), 1.0F);
        if (!p.isDeadOrDying()) {
            helper.fail("一撃で破れない(体力 " + p.getHealth() + ")");
            return;
        }
        helper.runAfterDelay(3, () -> {
            if (p.isRemoved()) {
                helper.fail("宙で消えた(3 tick)");
                return;
            }
            if (!(p.getY() < startY)) {
                helper.fail("落ちていない: " + startY + " → " + p.getY());
            }
        });
        helper.succeedWhen(() -> {
            if (!p.isRemoved()) throw new net.minecraft.gametest.framework.GameTestAssertException("まだ消えていない y " + p.getY());
            if (helper.getTick() <= 3) throw new net.minecraft.gametest.framework.GameTestAssertException("早すぎる");
            // その場に胞子を降らせる: 落ちた所に胞子の雲
            if (helper.getLevel().getEntitiesOfClass(com.yayuy.overgrownvegetation.entity.g1.skill.G1SporeCloud.class,
                    new net.minecraft.world.phys.AABB(helper.absolutePos(new BlockPos(0, 0, 0))).inflate(8.0D)).isEmpty()) {
                throw new net.minecraft.gametest.framework.GameTestAssertException("落ちた所に胞子の雲が無い");
            }
        });
    }

    /** 能力: 上にいるチャンクは、いる間だけ胞子濃度 100%(PediculaCover)。消えると 2 tick で戻る。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 40)
    public static void pediculaCoversChunkOnlyWhilePresent(GameTestHelper helper) {
        PediculaEntity p = helper.spawn(ModFlyers.PEDICULA.get(), new BlockPos(3, 2, 3));
        p.debugGround = helper.absolutePos(new BlockPos(0, 1, 0)).getY();
        long key = p.chunkPosition().toLong();
        helper.runAfterDelay(3, () -> {
            double cover = com.yayuy.overgrownvegetation.spore.PediculaCover.percentAt(helper.getLevel(), key);
            if (cover < PediculaEntity.PRESENCE_PERCENT) {
                helper.fail("いるチャンクが 100% でない: " + cover);
                return;
            }
            if (com.yayuy.overgrownvegetation.spore.SporeConcentration.useField(helper.getLevel())
                    && com.yayuy.overgrownvegetation.spore.SporeConcentration.percentAt(helper.getLevel(), p.chunkPosition()) < PediculaEntity.PRESENCE_PERCENT) {
                helper.fail("チャンクの胞子濃度に効いていない");
                return;
            }
            p.discard();
        });
        helper.runAfterDelay(8, () -> {
            double cover = com.yayuy.overgrownvegetation.spore.PediculaCover.percentAt(helper.getLevel(), key);
            // 同じチャンクにほかのテストのペディキュラがいることがあるので、この個体の印が残っていないかは「消えた後に書かれていない」で見る
            if (cover > 0.0D && helper.getLevel().getEntities(ModFlyers.PEDICULA.get(), e -> e.isAlive() && e.chunkPosition().toLong() == key).isEmpty()) {
                helper.fail("消えた後も 100% が残っている: " + cover);
                return;
            }
            helper.succeed();
        });
    }

    /** 3 軸の転がり(ユーザー決定 11-15): 3 軸とも回る・軸ごとに違う・同じ個体は同じ・凪では遅く風では速く進む。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void pediculaTumblesOnThreeAxes(GameTestHelper helper) {
        if (Math.abs(PediculaEntity.tumbleRate(0.0D) - 0.3F) > 1.0E-4F || Math.abs(PediculaEntity.tumbleRate(0.3D) - 1.0F) > 1.0E-4F
                || !(PediculaEntity.tumbleRate(0.05D) > PediculaEntity.tumbleRate(0.0D))) {
            helper.fail("転がりの進みが凪と風で変わらない: " + PediculaEntity.tumbleRate(0.0D) + " / " + PediculaEntity.tumbleRate(0.05D) + " / " + PediculaEntity.tumbleRate(0.3D));
            return;
        }
        java.util.Random r = new java.util.Random(7L);
        float[] a = new float[3], b = new float[3], c = new float[3];
        for (int n = 0; n < 20; n++) {
            java.util.UUID id = new java.util.UUID(r.nextLong(), r.nextLong());
            PediculaEntity.tumbleAngles(id, 0.0F, a);
            PediculaEntity.tumbleAngles(id, 300.0F, b);
            PediculaEntity.tumbleAngles(id, 300.0F, c);
            for (int axis = 0; axis < 3; axis++) {
                if (b[axis] != c[axis]) {
                    helper.fail("同じ個体・同じ時計で角度が違う");
                    return;
                }
                // 300 時計(風に乗って 15 秒)で、どの軸も回る(0.25〜0.75 度/時計 = 75〜225 度、揺らぎの差は 50 度まで)
                double d = Math.abs(Math.toDegrees(b[axis] - a[axis])) % 360.0D;
                if (Math.min(d, 360.0D - d) < 1.0D) {
                    helper.fail("軸 " + axis + " が回らない: " + Math.toDegrees(a[axis]) + " → " + Math.toDegrees(b[axis]));
                    return;
                }
            }
            if (a[0] == a[1] && a[1] == a[2]) {
                helper.fail("3 軸の位相が揃っている(1 本の軸で回る)");
                return;
            }
        }
        helper.succeed();
    }

    @GameTest(template = "gametest_empty7", timeoutTicks = 40, batch = "pedicula_cap")   // ほかのペディキュラのテストと同時に走らせない(上限で消える)
    public static void pediculaCapPerLevel(GameTestHelper helper) {
        int n = PediculaEntity.MAX_PER_LEVEL + 2;
        java.util.List<PediculaEntity> mine = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            PediculaEntity p = helper.spawn(ModFlyers.PEDICULA.get(), new BlockPos(3, 2, 3));
            p.readAdditionalSaveData(new net.minecraft.nbt.CompoundTag());
            p.debugGround = helper.absolutePos(new BlockPos(0, 2, 0)).getY();
            p.setInvulnerable(true);
            mine.add(p);
        }
        helper.runAfterDelay(10, () -> {
            int all = helper.getLevel().getEntities(ModFlyers.PEDICULA.get(), e -> e.isAlive()).size();
            long gone = mine.stream().filter(p -> !p.isAlive()).count();
            if (all > PediculaEntity.MAX_PER_LEVEL) {
                helper.fail("世界に " + all + " 体いる(上限 " + PediculaEntity.MAX_PER_LEVEL + ")");
                return;
            }
            if (gone < 2) {
                helper.fail("上限を超えて出したのに消えたのが " + gone + " 体");
                return;
            }
            mine.forEach(p -> p.discard());
            helper.succeed();
        });
    }

    /** テスト場では、狙いの高さ(地面 +40 以上)へ向かって浮き上がり、地面に降りない。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 60)
    public static void pediculaRisesTowardBand(GameTestHelper helper) {
        PediculaEntity p = helper.spawn(ModFlyers.PEDICULA.get(), new BlockPos(3, 2, 3));
        Vec3 at = helper.absoluteVec(new Vec3(3.5D, 2.0D, 3.5D));
        p.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        p.debugGround = helper.absolutePos(new BlockPos(0, 2, 0)).getY();
        p.setInvulnerable(true);
        double y0 = at.y;
        helper.runAfterDelay(50, () -> {
            if (!p.isAlive()) {
                helper.fail("消えた");
                return;
            }
            if (Double.isNaN(p.targetY()) || p.targetY() < p.debugGround + BalloonDrift.NIGHT_LOW - 1e-6) {
                helper.fail("狙いの高さが帯に入っていない: " + p.targetY() + "(地面 " + p.debugGround + ")");
                return;
            }
            if (p.getY() < y0 + 0.5D) {
                helper.fail(String.format("浮き上がらない: %.2f → %.2f", y0, p.getY()));
                return;
            }
            helper.succeed();
        });
    }
}
