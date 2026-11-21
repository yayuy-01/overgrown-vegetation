package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.ClipeimnesEntity;
import com.yayuy.overgrownvegetation.entity.DevNames;
import com.yayuy.overgrownvegetation.entity.SaltimnesEntity;
import com.yayuy.overgrownvegetation.entity.leap.LeapMotion;
import com.yayuy.overgrownvegetation.entity.leap.LeapPlan;
import com.yayuy.overgrownvegetation.entity.leap.LeapPlanner;
import com.yayuy.overgrownvegetation.entity.runner.LongBodyRunner;
import com.yayuy.overgrownvegetation.registry.ModLeapers;
import com.yayuy.overgrownvegetation.registry.ModRunners;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * コードの見直し(2026-11-16)第 1 便のうち「① G2・G3 の Mob と移動の型」の直しを確かめる。
 * <ul>
 *   <li>①1 獲物を乗せたまま保存して読み直すと、技は消えて獲物だけ乗っている —— 次の tick に放す(サルティムネス・クリペイムネス)。</li>
 *   <li>①2 跳ぶ型が水に浮いて接地しないと、向きは揃っても蹴れず身を沈めたまま —— 時間切れで跳ばずにやめる。</li>
 *   <li>①6 確かめ用の名札は GameTest のサーバーでは効く(開発用の操作口が無くても)。</li>
 * </ul>
 * ①10(フィロセンスの倒れ系の段)は場の作りが違うので {@link PhyllosensTowerGameTests} にある。
 * 24 × 24 の場 {@code gametest_empty24}(床は相対 y = 1、外はバリア)。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class ReviewFixMobsGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(ReviewFixMobsGameTests.class);
        }

        private Registration() {
        }
    }

    private ReviewFixMobsGameTests() {
    }

    static final String ARENA = "gametest_empty24";

    private static <T extends LongBodyRunner> T place(GameTestHelper helper, EntityType<T> type, double x, double z) {
        T m = helper.spawn(type, new BlockPos((int) x, 2, (int) z));
        Vec3 p = helper.absoluteVec(new Vec3(x, 2.0D, z));
        m.moveTo(p.x, p.y, p.z, 0.0F, 0.0F);
        m.snapFacing(0.0F);
        m.setCustomName(Component.literal("nostroll"));   // うろつかない・自分で標的を探さない
        return m;
    }

    /** UUID を全部外す(元の個体と同じ tick に戻すので、ぶつからないように)。 */
    private static void dropUuids(CompoundTag t) {
        t.remove("UUID");
        if (t.contains("Passengers", Tag.TAG_LIST)) {
            ListTag l = t.getList("Passengers", Tag.TAG_COMPOUND);
            for (int i = 0; i < l.size(); i++) dropUuids(l.getCompound(i));
        }
    }

    // ------------------------------------------------------------------ ①1

    /**
     * 獲物(ハスク)を乗せた捕まえる型を保存し、元を消して保存から戻す。戻した直後は獲物が乗っている(乗客は保存される = 固まりの起き方)が、
     * 技は保存されないので何もしていない段。数 tick のうちに獲物を放す。
     */
    private static void reloadReleasesPrey(GameTestHelper helper, EntityType<? extends LongBodyRunner> type) {
        ServerLevel level = helper.getLevel();
        LongBodyRunner m = place(helper, type, 12.5D, 12.5D);
        Husk prey = helper.spawn(EntityType.HUSK, new BlockPos(12, 2, 15));
        prey.setNoAi(true);
        Entity[] back = new Entity[1];
        Entity[] backPrey = new Entity[1];
        helper.runAtTickTime(5, () -> {
            if (!prey.startRiding(m, true)) {
                helper.fail("獲物を乗せられない(テストの前提)");
                return;
            }
            CompoundTag t = new CompoundTag();
            if (!m.save(t) || !t.contains("Passengers")) {
                helper.fail("保存に乗客が入らない(テストの前提): " + t.getAllKeys());
                return;
            }
            dropUuids(t);
            m.discard();
            prey.discard();
            Entity e = EntityType.loadEntityRecursive(t, level, x -> x);
            if (e == null) {
                helper.fail("保存から戻せない");
                return;
            }
            level.addFreshEntityWithPassengers(e);
            back[0] = e;
            backPrey[0] = e.getFirstPassenger();
            if (backPrey[0] == null) helper.fail("戻した直後に獲物が乗っていない(テストの前提: 乗客は保存される)");
        });
        helper.runAtTickTime(12, () -> {
            Entity e = back[0];
            Entity p = backPrey[0];
            try {
                if (e == null || p == null) return;
                if (e.isVehicle()) helper.fail("読み直した後も獲物を乗せたまま: 乗客 " + e.getPassengers());
                if (p.isPassenger()) helper.fail("獲物がまだ何かに乗っている: " + p.getVehicle());
                if (!p.isAlive()) helper.fail("獲物が消えた");
            } finally {
                if (e != null) e.discard();
                if (p != null) p.discard();
            }
            helper.succeed();
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 40)
    public static void saltimnesReloadWithPinnedPreyLetsGo(GameTestHelper helper) {
        reloadReleasesPrey(helper, ModLeapers.SALTIMNES.get());
    }

    @GameTest(template = ARENA, timeoutTicks = 40)
    public static void clipeimnesReloadWithHeldPreyLetsGo(GameTestHelper helper) {
        reloadReleasesPrey(helper, ModRunners.CLIPEIMNES.get());
    }

    // ------------------------------------------------------------------ ①2

    /**
     * 床の上で跳びを始め(向きを揃えてから {@code CROUCH} 20 tick で蹴る)、すぐ足元を水で満たして浮かせる。接地しないので蹴れない。
     * 前は身を沈めたまま永遠に止まっていた。時間切れ(20 + {@link LeapMotion#CROUCH_GIVE_UP} + 向きの上限 60)のうちに跳ばずにやめる。
     */
    @GameTest(template = ARENA, timeoutTicks = 240)
    public static void leaperFloatingInWaterGivesUpCrouch(GameTestHelper helper) {
        SaltimnesEntity s = place(helper, ModLeapers.SALTIMNES.get(), 12.5D, 8.5D);
        long[] startAt = {-1};
        helper.runAtTickTime(5, () -> {
            if (!s.onGround()) {
                helper.fail("床に着いていない(テストの前提)");
                return;
            }
            LeapPlan p = LeapPlanner.plan(s, s.position().add(0.0D, 0.0D, 6.0D), SaltimnesEntity.LEAP);
            if (p == null) {
                helper.fail("跳びの弧が引けない(テストの前提)");
                return;
            }
            s.snapFacing(p.yaw());
            s.leapMotion().start(s, p, 20, 0, LeapMotion.ALIGN_DEG);
            // 囲いの池(深さ 3)。水は囲いの中だけ
            for (int x = 8; x <= 17; x++) {
                for (int z = 4; z <= 13; z++) {
                    boolean wall = x == 8 || x == 17 || z == 4 || z == 13;
                    for (int y = 2; y <= 4; y++) helper.setBlock(new BlockPos(x, y, z), wall ? Blocks.STONE : Blocks.WATER);
                }
            }
            startAt[0] = helper.getTick();
        });
        helper.onEachTick(() -> {
            if (startAt[0] < 0) return;
            if (s.leapMotion().leaps() > 0) helper.fail("水の中で蹴ってしまった(テストの前提が崩れた): 接地 " + s.onGround() + " 水 " + s.isInWater());
            if (!s.leapMotion().active()) {
                long took = helper.getTick() - startAt[0];
                if (took > 20 + 60 + LeapMotion.CROUCH_GIVE_UP + 2) helper.fail("やめるのが遅い: " + took + " tick");
                s.discard();
                helper.succeed();
            } else if (helper.getTick() - startAt[0] > 200) {
                helper.fail("水に浮いたまま身を沈め続けている: 段 " + s.leapMotion().phase() + " 接地 " + s.onGround() + " 水 " + s.isInWater());
            }
        });
    }

    // ------------------------------------------------------------------ 第 2 便: ①13・①9・①11(陣営と除外の共通判定)

    /** 防具立て・植物側(G3)・自分は狙わない・当てない。ハスクは狙う。標的選び・アクムネスの弾・ファルキムネスの斬りが同じ答え。 */
    @GameTest(template = ARENA, timeoutTicks = 10)
    public static void plantFoesShareSideAndExclusions(GameTestHelper helper) {
        Husk husk = helper.spawn(EntityType.HUSK, new BlockPos(4, 2, 4));
        husk.setNoAi(true);
        net.minecraft.world.entity.decoration.ArmorStand stand = helper.spawn(EntityType.ARMOR_STAND, new BlockPos(8, 2, 4));
        SaltimnesEntity s = place(helper, ModLeapers.SALTIMNES.get(), 12.5D, 12.5D);
        s.setNoAi(true);
        com.yayuy.overgrownvegetation.entity.FalcimnesEntity f = helper.spawn(com.yayuy.overgrownvegetation.registry.ModEntities.FALCIMNES.get(), new BlockPos(18, 2, 18));
        f.setNoAi(true);
        try {
            if (!com.yayuy.overgrownvegetation.entity.PlantFoes.isFoe(s, husk)) helper.fail("ハスクが相手にならない");
            if (com.yayuy.overgrownvegetation.entity.PlantFoes.isFoe(s, stand)) helper.fail("防具立てが相手になる");
            if (com.yayuy.overgrownvegetation.entity.PlantFoes.isFoe(s, s)) helper.fail("自分が相手になる");
            if (com.yayuy.overgrownvegetation.entity.PlantFoes.isFoe(null, s)) helper.fail("G3 が相手になる(持ち主の無い技から)");
            if (com.yayuy.overgrownvegetation.entity.AcumnesEntity.isPrey(s, stand)) helper.fail("アクムネスの判定で防具立てが相手になる");
            if (!f.isFoeForWave(husk)) helper.fail("ファルキムネスの斬りがハスクに当たらない");
            if (f.isFoeForWave(stand)) helper.fail("ファルキムネスの斬りが防具立てに当たる(①9)");
            if (f.isFoeForWave(s)) helper.fail("ファルキムネスの斬りが G3 に当たる");
        } finally {
            husk.discard();
            stand.discard();
            s.discard();
            f.discard();
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ 第 2 便: ①8(群れの止まり木・空の群れ)

    /** 止まり木へ向かう途中(まだ飛んでいる)の個体を保存しても、止まり木が残る。 */
    @GameTest(template = ARENA, timeoutTicks = 40)
    public static void flyerApproachingPerchKeepsItThroughReload(GameTestHelper helper) {
        com.yayuy.overgrownvegetation.entity.VelophyllEntity v = helper.spawn(com.yayuy.overgrownvegetation.registry.ModFlyers.VELOPHYLL.get(), new BlockPos(12, 6, 12));
        helper.runAtTickTime(5, () -> {
            try {
                if (v.flock() == null) {
                    helper.fail("群れに入らない(テストの前提)");
                    return;
                }
                BlockPos perch = helper.absolutePos(new BlockPos(3, 2, 3));
                v.approachPerchForTests(perch);
                if (v.mode() == com.yayuy.overgrownvegetation.entity.flyer.SkyFlyer.MODE_PERCHED) helper.fail("止まっている(テストの前提: 着地前)");
                CompoundTag t = new CompoundTag();
                v.saveWithoutId(t);
                if (!t.contains("Perch")) helper.fail("着地前の止まり木が保存されない");
                com.yayuy.overgrownvegetation.entity.VelophyllEntity o = com.yayuy.overgrownvegetation.registry.ModFlyers.VELOPHYLL.get().create(helper.getLevel());
                o.load(t);
                if (!perch.equals(o.perchPos())) helper.fail("読み直すと止まり木が無い: " + o.perchPos());
            } finally {
                v.discard();
            }
            helper.succeed();
        });
    }

    /** 群れの最後の 1 体が面から出たら、群れを台帳から忘れる(前は誰も群れを回さず残り続けた)。 */
    @GameTest(template = ARENA, timeoutTicks = 40)
    public static void flockIsForgottenWhenItsLastFlyerLeaves(GameTestHelper helper) {
        com.yayuy.overgrownvegetation.entity.VelophyllEntity v = helper.spawn(com.yayuy.overgrownvegetation.registry.ModFlyers.VELOPHYLL.get(), new BlockPos(12, 6, 12));
        java.util.UUID[] id = new java.util.UUID[1];
        helper.runAtTickTime(5, () -> {
            if (v.flock() == null) {
                helper.fail("群れに入らない(テストの前提)");
                return;
            }
            id[0] = v.flock().id;
            if (!com.yayuy.overgrownvegetation.entity.flyer.SkyFlyer.flockKnownForTests(helper.getLevel(), id[0])) helper.fail("群れが台帳に無い(テストの前提)");
            v.discard();
        });
        helper.runAtTickTime(8, () -> {
            if (id[0] != null && com.yayuy.overgrownvegetation.entity.flyer.SkyFlyer.flockKnownForTests(helper.getLevel(), id[0])) helper.fail("空の群れが台帳に残っている");
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ ①6

    /** 確かめ用の名札は GameTest のサーバーでは効く(遊ぶ環境で効かない側は、開発用の操作口の有無で決まる静的な値なので GameTest では試せない)。 */
    @GameTest(template = ARENA, timeoutTicks = 10)
    public static void devNamesWorkOnTheGameTestServer(GameTestHelper helper) {
        SaltimnesEntity s = place(helper, ModLeapers.SALTIMNES.get(), 12.5D, 12.5D);
        if (!DevNames.enabled(s)) helper.fail("GameTest のサーバーで名札が効かない");
        if (!DevNames.noStroll(s)) helper.fail("nostroll が読めない");
        s.setCustomName(Component.literal("hop"));
        if (!"hop".equals(DevNames.of(s))) helper.fail("hop が読めない: " + DevNames.of(s));
        s.setCustomName(null);
        if (!DevNames.of(s).isEmpty()) helper.fail("名前が無いのに名札がある");
        s.discard();
        helper.succeed();
    }
}
