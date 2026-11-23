package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.PhyllosensEntity;
import com.yayuy.overgrownvegetation.entity.PhyllosensWatch;
import com.yayuy.overgrownvegetation.entity.PhyllosensWatch.State;
import com.yayuy.overgrownvegetation.entity.tower.TowerNeck;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import com.yayuy.overgrownvegetation.registry.ModItems;
import com.yayuy.overgrownvegetation.registry.ModPhyllosens;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import com.yayuy.overgrownvegetation.port.attachment.OvAttach;

/**
 * 監視塔フィロセンス(2026-11-14)のテスト。首の計算がアニメの数値表と合う・動かない・古い竹馬の個体は地面へ下りる・見張りで見つけて 2 秒で印・
 * 隠れ直すと付かない・ガラスごしは付き石と葉では付かない・林冠の下の相手を木々の間から覗く・届く入口を覗き・届かない入口は覗かない・
 * 覗き中に頭を叩くと引っ込む・閉じている間に頭を叩くと起きる・板根 2 枚で崩れて起き上がる・胞子で暴露量が上がりマスクで防げる。
 *
 * <p>塔は高い(見張りの花が地面から 21 ブロック)ので、竹馬型と同じ強制読み込みの遠いチャンクの y 99 の床(上は y 124 まで更地)を使う
 * ({@link StiltGameTests#fresh})。相手の偽プレイヤーは世界の一覧に入らないので {@link PhyllosensWatch#forcedTarget} で渡す。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class PhyllosensTowerGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(PhyllosensTowerGameTests.class);
        }

        private Registration() {
        }
    }

    private PhyllosensTowerGameTests() {
    }

    static final int FLOOR = StiltGameTests.FLOOR;
    static final String BATCH = "phyllo_tower";

    // ------------------------------------------------------------------ 場

    private static void run(GameTestHelper helper, Consumer<StiltGameTests.Arena> build, double sx, double sz, float yaw,
                            BiConsumer<StiltGameTests.Arena, PhyllosensEntity> setup,
                            BiConsumer<StiltGameTests.Arena, PhyllosensEntity> each, int until,
                            BiConsumer<StiltGameTests.Arena, PhyllosensEntity> end) {
        ServerLevel level = helper.getLevel();
        ChunkPos c = StiltGameTests.fresh();
        StiltGameTests.force(level, c, true);
        StiltGameTests.Arena a = new StiltGameTests.Arena(level, c, c.getMinBlockX(), c.getMinBlockZ());
        PhyllosensEntity[] mob = new PhyllosensEntity[1];
        helper.runAfterDelay(20, () -> {
            StiltGameTests.fill(level, a.x(-4), 88, a.z(-4), a.x(StiltGameTests.SIZE + 3), 124, a.z(StiltGameTests.SIZE + 3), Blocks.AIR.defaultBlockState());
            StiltGameTests.fill(level, a.x(0), 99, a.z(0), a.x(StiltGameTests.SIZE - 1), 99, a.z(StiltGameTests.SIZE - 1), Blocks.STONE.defaultBlockState());
            build.accept(a);
            PhyllosensEntity e = ModPhyllosens.PHYLLOSENS.get().create(level);
            if (e == null) {
                helper.fail("作れない");
                return;
            }
            e.moveTo(a.x0() + sx, FLOOR, a.z0() + sz, yaw, 0.0F);
            e.setCustomName(Component.literal("tower"));
            level.addFreshEntity(e);
            mob[0] = e;
        });
        helper.runAfterDelay(30, () -> {
            if (mob[0] == null) return;
            if (!level.isPositionEntityTicking(mob[0].blockPosition())) {
                helper.fail("遠いチャンクでエンティティが動かない(テスト場の都合)");
                return;
            }
            setup.accept(a, mob[0]);
        });
        helper.onEachTick(() -> {
            if (mob[0] == null || helper.getTick() < 31) return;
            try {
                each.accept(a, mob[0]);
            } catch (RuntimeException ex) {
                cleanup(level, c, mob[0]);
                throw ex;
            }
        });
        helper.runAtTickTime(until, () -> {
            try {
                end.accept(a, mob[0]);
            } finally {
                cleanup(level, c, mob[0]);
            }
            helper.succeed();
        });
    }

    static void cleanup(ServerLevel level, ChunkPos c, PhyllosensEntity e) {
        if (e != null) e.discard();
        StiltGameTests.force(level, c, false);
    }

    private static Player player(GameTestHelper helper, StiltGameTests.Arena a, double x, double y, double z) {
        Player pl = GtCompat.mockPlayer(helper, GameType.SURVIVAL);
        pl.moveTo(a.x0() + x, y, a.z0() + z, 0.0F, 0.0F);
        return pl;
    }

    private static boolean marked(Player pl) {
        return pl.hasEffect(ModPhyllosens.PHYLLOSENS_WATCH.get());
    }

    private static String why(StiltGameTests.Arena a, PhyllosensEntity e) {
        PhyllosensWatch w = e.watch();
        Vec3 eye = w.eye();
        return String.format(Locale.ROOT, "状態 %s(%d tick)見える %s 見つめる %s 覗き %s 効き %.2f 目 (%.1f %.1f %.1f) 記録 [%s] 位置 (%.2f %.2f %.2f)",
                w.state(), w.stateTicks(), w.seesTarget(), w.locked(), w.peekKind(), w.peekWeight(), eye.x - a.x0(), eye.y - FLOOR, eye.z - a.z0(), w.note,
                e.getX() - a.x0(), e.getY() - FLOOR, e.getZ() - a.z0());
    }

    private static void box(StiltGameTests.Arena a, int cx, int cz, int y0, int y1, BlockState s) {
        StiltGameTests.fill(a.level(), a.x(cx - 1), y0, a.z(cz - 1), a.x(cx + 1), y1, a.z(cz + 1), s);
        StiltGameTests.fill(a.level(), a.x(cx), y0, a.z(cz), a.x(cx), y1 - 1, a.z(cz), Blocks.AIR.defaultBlockState());
    }

    // ------------------------------------------------------------------ 首の計算(Mob を出さない)

    /** 首の順運動学がアニメの数値表(anim_numbers_r2.txt の萼の位置)と合い、届く点へは頭がぴったり来て、地面に刺さらない。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 10, batch = BATCH)
    public static void neckMatchesAnimationNumbers(GameTestHelper helper) {
        TowerNeck.Shape sh = TowerNeck.PHYLLOSENS;
        // 頭の軸(萼の中心より約 0.3 下)の 前・高さ(ブロック)
        Object[][] cases = {{TowerNeck.WATCH, 3.5, 21.2}, {TowerNeck.PEEK_TOP, 2.9, 3.1}, {TowerNeck.PEEK_SIDE, 6.2, 1.6}, {TowerNeck.PEEK_GAP, 10.3, 12.0}};
        for (Object[] k : cases) {
            TowerNeck.Pose p = TowerNeck.of(sh, (double[]) k[0], 0.0D);
            double[] f = TowerNeck.planeToFile(sh, p.tipR(), p.tipY(), 0.0D);
            double fwd = -f[2] / 16.0D, up = f[1] / 16.0D;
            if (Math.abs(fwd - (double) k[1]) > 0.3D || Math.abs(up - (double) k[2]) > 0.4D) {
                helper.fail(String.format(Locale.ROOT, "見本の頭が数値表と合わない: 前 %.2f 高さ %.2f(%.1f %.1f のはず)", fwd, up, k[1], k[2]));
            }
        }
        // 届く点(前・高さ、ブロック)と見本
        Object[][] want = {{5.0, 3.0, TowerNeck.PEEK_TOP}, {7.0, 1.5, TowerNeck.PEEK_SIDE}, {3.0, 5.0, TowerNeck.PEEK_TOP}, {8.0, 9.0, TowerNeck.PEEK_GAP},
                {4.0, 2.0, TowerNeck.PEEK_SIDE}};
        for (Object[] w : want) {
            double r = (double) w[0] * 16.0D - 2.0D, y = (double) w[1] * 16.0D;
            TowerNeck.Pose p = TowerNeck.solve(sh, (double[]) w[2], r, y, 30.0D);
            if (p.miss() > 4.0D) helper.fail(String.format(Locale.ROOT, "前 %.1f 高さ %.1f へ届かない(ずれ %.1f 単位)", w[0], w[1], p.miss()));
            for (int k = 1; k <= sh.segments(); k++) {
                if (TowerNeck.joint(sh, p.th(), k)[1] < 0.0D) helper.fail("首が地面に刺さる: 節 " + k);
            }
            // まっすぐな棒になっていない: 隣の節との曲げのどれかが 15° 以上ある
            double maxBend = 0.0D;
            for (int i = 1; i < sh.segments(); i++) maxBend = Math.max(maxBend, Math.abs(p.bend(i)));
            if (maxBend < 15.0D) helper.fail(String.format(Locale.ROOT, "前 %.1f 高さ %.1f の首がまっすぐな棒(最大の曲げ %.1f°)", w[0], w[1], maxBend));
        }
        // ワールドとの行き来
        Vec3 pos = new Vec3(10.5, 64, -3.5);
        double[] f = {12, 300, -40};
        double[] back = TowerNeck.toFile(TowerNeck.toWorld(f, pos, 37.0F), pos, 37.0F);
        for (int i = 0; i < 3; i++) if (Math.abs(back[i] - f[i]) > 1.0E-6D) helper.fail("ワールドとの行き来が合わない");
        helper.succeed();
    }

    // ------------------------------------------------------------------ 立ち物

    /** 動かない・押されない・重力で落ちない。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200, batch = BATCH)
    public static void standsStillAndIsNotPushed(GameTestHelper helper) {
        Vec3[] start = new Vec3[1];
        run(helper, a -> {
        }, 16.5, 16.5, 0.0F, (a, e) -> {
            start[0] = e.position();
            e.knockback(3.0D, 1.0D, 0.0D);
            e.push(2.0D, 1.0D, 2.0D);
            e.hurt(a.level().damageSources().generic(), 1.0F);
            for (int i = 0; i < 4; i++) {
                Cow cow = new Cow(EntityType.COW, a.level());
                cow.moveTo(e.getX() + 0.3D, FLOOR, e.getZ() + 0.2D * i, 0, 0);
                a.level().addFreshEntity(cow);
            }
        }, (a, e) -> {
        }, 120, (a, e) -> {
            if (e.position().distanceTo(start[0]) > 1.0E-3D) helper.fail("動いた: " + start[0] + " → " + e.position());
            a.level().getEntitiesOfClass(Cow.class, e.getBoundingBox().inflate(4)).forEach(Cow::discard);
        });
    }

    /** 竹馬の時代の保存(監視塔の印なし・空中)は、最初の tick に真下の地面へ下りる。印のある監視塔は下ろさない。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 120, batch = BATCH)
    public static void oldStiltSaveSettlesToTheGround(GameTestHelper helper) {
        PhyllosensEntity[] old = new PhyllosensEntity[1];
        run(helper, a -> StiltGameTests.fill(a.level(), a.x(20), 99, a.z(20), a.x(22), 103, a.z(22), Blocks.STONE.defaultBlockState()), 8.5, 8.5, 0.0F,
                (a, e) -> {
                    CompoundTag t = new CompoundTag();
                    e.saveWithoutId(t);
                    t.remove("OvTower");
                    ListTag pos = new ListTag();
                    pos.add(DoubleTag.valueOf(a.x0() + 4.5D));
                    pos.add(DoubleTag.valueOf(FLOOR + 5.5D));
                    pos.add(DoubleTag.valueOf(a.z0() + 4.5D));
                    t.put("Pos", pos);
                    t.remove("UUID");
                    PhyllosensEntity o = ModPhyllosens.PHYLLOSENS.get().create(a.level());
                    o.load(t);
                    a.level().addFreshEntity(o);
                    old[0] = o;
                }, (a, e) -> {
                }, 80, (a, e) -> {
                    if (old[0] == null) helper.fail("古い個体を作れない");
                    double y = old[0].getY();
                    old[0].discard();
                    if (Math.abs(y - FLOOR) > 1.0E-3D) helper.fail("古い竹馬の個体が地面へ下りない: 高さ " + (y - FLOOR));
                    if (Math.abs(e.getY() - FLOOR) > 1.0E-3D) helper.fail("監視塔が動いた: " + (e.getY() - FLOOR));
                });
    }

    // ------------------------------------------------------------------ 見る

    /** 閉じた待機から、近くに人がいると開いて見張り、見え始めて 2 秒で印が付く。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 420, batch = BATCH)
    public static void watchSpotsAndMarksAfterTwoSeconds(GameTestHelper helper) {
        Player[] pl = new Player[1];
        long[] seenAt = {-1}, markedAt = {-1};
        Set<State> states = EnumSet.noneOf(State.class);
        run(helper, a -> {
        }, 16.5, 8.5, 0.0F, (a, e) -> {
            pl[0] = player(helper, a, 16.5, FLOOR, 28.5);
            e.watch().forcedTarget = pl[0];
        }, (a, e) -> {
            states.add(e.watch().state());
            long now = a.level().getGameTime();
            if (seenAt[0] < 0 && e.watch().seesTarget()) seenAt[0] = now;
            if (markedAt[0] < 0 && marked(pl[0])) markedAt[0] = now;
        }, 400, (a, e) -> {
            if (!states.contains(State.OPENING)) helper.fail("開かない: " + why(a, e));
            if (markedAt[0] < 0) helper.fail("印が付かない: " + why(a, e));
            long d = markedAt[0] - seenAt[0];
            if (d < PhyllosensWatch.MARK_DELAY - 5 || d > PhyllosensWatch.MARK_DELAY + 10) helper.fail("見え始めから印まで " + d + " tick(約 40 のはず)");
        });
    }

    /** 印が付く前に石の箱へ隠れ直すと付かない(覗いても見えない)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 520, batch = BATCH)
    public static void hidingAgainBeforeTheMarkPreventsIt(GameTestHelper helper) {
        Player[] pl = new Player[1];
        long[] seenAt = {-1};
        run(helper, a -> {
        }, 16.5, 8.5, 0.0F, (a, e) -> {
            pl[0] = player(helper, a, 16.5, FLOOR, 24.5);
            e.watch().forcedTarget = pl[0];
            e.watch().forceWatch();
        }, (a, e) -> {
            long now = a.level().getGameTime();
            if (seenAt[0] < 0 && e.watch().seesTarget()) {
                seenAt[0] = now;
            }
            if (seenAt[0] >= 0 && now == seenAt[0] + 20) box(a, 16, 24, 100, 103, Blocks.STONE.defaultBlockState());
            if (marked(pl[0])) helper.fail("隠れ直したのに印が付いた: " + why(a, e));
        }, 500, (a, e) -> {
            if (seenAt[0] < 0) helper.fail("一度も見えない: " + why(a, e));
        });
    }

    private static void throughCase(GameTestHelper helper, BlockState wall, boolean expect) {
        Player[] pl = new Player[1];
        run(helper, a -> box(a, 16, 24, 100, 103, wall), 16.5, 8.5, 0.0F, (a, e) -> {
            pl[0] = player(helper, a, 16.5, FLOOR, 24.5);
            e.watch().forcedTarget = pl[0];
            e.watch().forceWatch();
        }, (a, e) -> {
        }, 300, (a, e) -> {
            if (marked(pl[0]) != expect) helper.fail(wall.getBlock() + " の箱の中: 印 " + marked(pl[0]) + "(" + expect + " のはず)" + why(a, e));
        });
    }

    @GameTest(template = "gametest_empty7", timeoutTicks = 320, batch = BATCH)
    public static void marksThroughGlass(GameTestHelper helper) {
        throughCase(helper, Blocks.GLASS.defaultBlockState(), true);
    }

    @GameTest(template = "gametest_empty7", timeoutTicks = 320, batch = BATCH)
    public static void stoneBlocksTheMark(GameTestHelper helper) {
        throughCase(helper, Blocks.STONE.defaultBlockState(), false);
    }

    @GameTest(template = "gametest_empty7", timeoutTicks = 320, batch = BATCH)
    public static void leavesBlockTheMark(GameTestHelper helper) {
        throughCase(helper, StiltGameTests.leaves(), false);
    }

    /** 林冠(葉の天井)の下の相手は見張りの花から見えない → 首を林冠の下へ下ろして木々の間から覗き、印を付ける。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 520, batch = BATCH)
    public static void peeksUnderTheCanopyAndMarks(GameTestHelper helper) {
        Player[] pl = new Player[1];
        Set<State> states = EnumSet.noneOf(State.class);
        run(helper, a -> StiltGameTests.fill(a.level(), a.x(8), 106, a.z(16), a.x(25), 107, a.z(31), StiltGameTests.leaves()), 16.5, 8.5, 0.0F, (a, e) -> {
            pl[0] = player(helper, a, 16.5, FLOOR, 24.5);
            e.watch().forcedTarget = pl[0];
            e.watch().forceWatch();
        }, (a, e) -> states.add(e.watch().state()), 500, (a, e) -> {
            if (!states.contains(State.PEEK)) helper.fail("覗かない: " + why(a, e));
            if (!marked(pl[0])) helper.fail("覗いたのに印が付かない: " + why(a, e));
            if (e.watch().peekKind() == null) helper.fail("覗きの種類が無い");
        });
    }

    /** 小さな家(石・屋根あり)。入口は北の壁(z = dz0)の x = 16、2 段。中に相手。 */
    private static void house(StiltGameTests.Arena a, int dz0) {
        StiltGameTests.fill(a.level(), a.x(13), 100, a.z(dz0), a.x(19), 102, a.z(dz0 + 6), Blocks.STONE.defaultBlockState());
        StiltGameTests.fill(a.level(), a.x(14), 100, a.z(dz0 + 1), a.x(18), 102, a.z(dz0 + 5), Blocks.AIR.defaultBlockState());
        StiltGameTests.fill(a.level(), a.x(13), 103, a.z(dz0), a.x(19), 103, a.z(dz0 + 6), Blocks.STONE.defaultBlockState());
        StiltGameTests.fill(a.level(), a.x(16), 100, a.z(dz0), a.x(16), 101, a.z(dz0), Blocks.AIR.defaultBlockState());
    }

    private static void doorwayCase(GameTestHelper helper, double towerZ, boolean expect) {
        Player[] pl = new Player[1];
        Set<State> states = EnumSet.noneOf(State.class);
        run(helper, a -> house(a, 16), 16.5, towerZ, 0.0F, (a, e) -> {
            pl[0] = player(helper, a, 16.5, FLOOR, 18.5);
            e.watch().forcedTarget = pl[0];
            e.watch().noteSeen(pl[0].position(), a.level().getGameTime());
            e.watch().forceWatch();
        }, (a, e) -> {
            states.add(e.watch().state());
            if (e.watch().state() == State.PEEK && a.level().getGameTime() % 10 == 0) {
                // 何度も見えた所を覚え直す(偽プレイヤーは動かない)
                e.watch().noteSeen(pl[0].position(), a.level().getGameTime());
            }
        }, 400, (a, e) -> {
            boolean peeked = states.contains(State.PEEK);
            if (peeked != expect) helper.fail("入口を覗く = " + peeked + "(" + expect + " のはず)" + why(a, e));
            if (marked(pl[0]) != expect) helper.fail("入口ごしの印 = " + marked(pl[0]) + "(" + expect + " のはず)" + why(a, e));
        });
    }

    /** 首の届く入口(根元から約 6 ブロック)は、首を下ろして覗き、印を付ける。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 420, batch = BATCH)
    public static void peeksIntoADoorwayWithinReach(GameTestHelper helper) {
        doorwayCase(helper, 10.5, true);
    }

    /** 届かない入口(根元から約 13 ブロック)は覗かない。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 420, batch = BATCH)
    public static void doesNotPeekIntoAFarDoorway(GameTestHelper helper) {
        doorwayCase(helper, 3.5, false);
    }

    // ------------------------------------------------------------------ 叩かれる

    /** 覗き中に頭を叩くと、胞子を吹いて引っ込む(しばらく覗かない)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 520, batch = BATCH)
    public static void hittingThePeekingHeadMakesItRecoil(GameTestHelper helper) {
        Player[] pl = new Player[1];
        long[] hitAt = {-1};
        boolean[] puffed = {false};
        Set<State> after = EnumSet.noneOf(State.class);
        run(helper, a -> house(a, 16), 16.5, 10.5, 0.0F, (a, e) -> {
            pl[0] = player(helper, a, 16.5, FLOOR, 18.5);
            e.watch().forcedTarget = pl[0];
            e.watch().noteSeen(pl[0].position(), a.level().getGameTime());
            e.watch().forceWatch();
        }, (a, e) -> {
            long now = a.level().getGameTime();
            if (hitAt[0] < 0 && e.watch().state() == State.PEEK && e.watch().stateTicks() > 5) {
                hitAt[0] = now;
                e.hitParts().part(0).hurt(a.level().damageSources().generic(), 2.0F);
                if (e.watch().state() != State.RECOIL) helper.fail("叩いても引っ込まない: " + why(a, e));
            } else if (hitAt[0] >= 0 && now - hitAt[0] < PhyllosensWatch.HIT_COOLDOWN - 5) {
                after.add(e.watch().state());
                if (!a.level().getEntitiesOfClass(com.yayuy.overgrownvegetation.entity.g1.skill.G1SporeCloud.class, e.getBoundingBox().inflate(16)).isEmpty()) puffed[0] = true;
            }
        }, 500, (a, e) -> {
            if (hitAt[0] < 0) helper.fail("覗きまで行かない: " + why(a, e));
            if (after.contains(State.PEEK_IN)) helper.fail("叩かれた後すぐにまた覗いた");
            if (!after.contains(State.WATCH)) helper.fail("引っ込んだ後に見張りへ戻らない: " + why(a, e));
            if (!puffed[0]) helper.fail("頭から胞子を吹かない");
        });
    }

    /** 閉じている間に地面の頭を叩くと、胞子を吹いて起き、見張りへ。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200, batch = BATCH)
    public static void hittingTheClosedHeadWakesIt(GameTestHelper helper) {
        Set<State> states = EnumSet.noneOf(State.class);
        boolean[] puffed = {false};
        run(helper, a -> {
        }, 16.5, 8.5, 0.0F, (a, e) -> {
            if (e.watch().state() != State.CLOSED) helper.fail("閉じていない: " + why(a, e));
            Vec3 h = e.hitParts().part(0).position();
            if (h.y - FLOOR > 2.0D) helper.fail("閉じた頭が地面に無い: 高さ " + (h.y - FLOOR));
            e.hitParts().part(0).hurt(a.level().damageSources().generic(), 2.0F);
        }, (a, e) -> {
            states.add(e.watch().state());
            if (!a.level().getEntitiesOfClass(com.yayuy.overgrownvegetation.entity.g1.skill.G1SporeCloud.class, e.getBoundingBox().inflate(12)).isEmpty()) puffed[0] = true;
        }, 120, (a, e) -> {
            if (!states.contains(State.WAKE)) helper.fail("起きない: " + why(a, e));
            if (!states.contains(State.WATCH)) helper.fail("見張りにならない: " + why(a, e));
            if (!puffed[0]) helper.fail("胞子を吹かない");
        });
    }

    /** 板根を 2 枚折ると腰から崩れ、約 6 秒倒れて、編み直して起き上がる(閉じた姿へ)。倒れている間は頭が地面すれすれ。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 400, batch = BATCH)
    public static void twoBrokenRootsCollapseAndRise(GameTestHelper helper) {
        Set<State> states = EnumSet.noneOf(State.class);
        double[] lowHead = {99};
        long[] stunFor = {0};
        run(helper, a -> {
        }, 16.5, 16.5, 0.0F, (a, e) -> {
            e.watch().forceWatch();
            e.hitParts().part(1).hurt(a.level().damageSources().generic(), 40.0F);
            if (e.brokenPlates() != 1) helper.fail("板根が折れない: " + e.brokenPlates());
            if (e.watch().state() == State.COLLAPSE) helper.fail("1 枚で崩れた");
            if (e.getHealth() < e.getMaxHealth()) helper.fail("板根を叩いたのに本体の体力が減った");
        }, (a, e) -> {
            if (a.level().getGameTime() % 20 == 0 && e.brokenPlates() == 1) e.hitParts().part(3).hurt(a.level().damageSources().generic(), 40.0F);
            states.add(e.watch().state());
            if (e.watch().state() == State.STUN) {
                stunFor[0]++;
                lowHead[0] = Math.min(lowHead[0], e.hitParts().part(0).getY() - FLOOR);
            }
        }, 380, (a, e) -> {
            for (State s : new State[]{State.COLLAPSE, State.STUN, State.RISE, State.CLOSED}) {
                if (!states.contains(s)) helper.fail("段 " + s + " が無い: " + why(a, e));
            }
            if (Math.abs(stunFor[0] - PhyllosensWatch.STUN_TICKS) > 2) helper.fail("倒れている間 " + stunFor[0] + " tick(約 6 秒のはず)");
            if (lowHead[0] > 1.5D) helper.fail("倒れている間の頭が地面すれすれでない: " + lowHead[0]);
            if (e.brokenPlates() != 0) helper.fail("起き上がったのに板根が戻らない");
        });
    }

    /**
     * 倒れている途中で保存して読み直しても、倒れている段から続けて起き上がり、板根が戻る(2026-11-16 コードの見直し ①10)。
     * 前は段を保存せず、読み直すと閉じた待機に戻って板根が折れたまま残った。{@code legacy} は段の印の無い古い保存(倒れている段の頭から)。
     */
    private static void reloadMidStun(GameTestHelper helper, boolean legacy) {
        PhyllosensEntity[] back = new PhyllosensEntity[1];
        int[] firstT = {-1};
        State[] firstState = {null};
        Set<State> states = EnumSet.noneOf(State.class);
        run(helper, a -> {
        }, 16.5, 16.5, 0.0F, (a, e) -> {
            e.watch().forceWatch();
            e.hitParts().part(1).hurt(a.level().damageSources().generic(), 40.0F);
        }, (a, e) -> {
            PhyllosensEntity b = back[0];
            if (b != null) {
                if (firstState[0] == null && b.tickCount > 0) {   // 戻してから 1 回目の tick の後
                    firstState[0] = b.watch().state();
                    firstT[0] = b.watch().stateTicks() - b.tickCount;   // 保存したときの経った tick
                }
                states.add(b.watch().state());
                return;
            }
            if (a.level().getGameTime() % 20 == 0 && e.brokenPlates() == 1) e.hitParts().part(3).hurt(a.level().damageSources().generic(), 40.0F);
            if (e.watch().state() == State.STUN && e.watch().stateTicks() >= 30) {
                CompoundTag t = new CompoundTag();
                e.saveWithoutId(t);
                t.remove("UUID");
                if (legacy) {
                    t.remove(PhyllosensWatch.TAG_DOWN);
                    t.remove(PhyllosensWatch.TAG_DOWN_T);
                }
                PhyllosensEntity o = ModPhyllosens.PHYLLOSENS.get().create(a.level());
                o.load(t);
                e.discard();
                a.level().addFreshEntity(o);
                back[0] = o;
            }
        }, 380, (a, e) -> {
            PhyllosensEntity b = back[0];
            if (b == null) helper.fail("倒れている段まで進まない: " + why(a, e));
            try {
                if (firstState[0] != State.STUN) helper.fail("読み直した最初の段が倒れている段でない: " + firstState[0] + " " + why(a, b));
                if (!legacy && firstT[0] != 30) helper.fail("倒れている段の経った tick が続かない: " + firstT[0] + "(保存したときは 30)");
                if (!states.contains(State.RISE) || !states.contains(State.CLOSED)) helper.fail("起き上がらない: 段 " + states + " " + why(a, b));
                if (b.brokenPlates() != 0) helper.fail("読み直した後に板根が戻らない: 折れ " + b.brokenPlates() + " " + why(a, b));
            } finally {
                b.discard();
            }
        });
    }

    @GameTest(template = "gametest_empty7", timeoutTicks = 400, batch = BATCH)
    public static void reloadWhileDownedResumesAndRises(GameTestHelper helper) {
        reloadMidStun(helper, false);
    }

    @GameTest(template = "gametest_empty7", timeoutTicks = 400, batch = BATCH)
    public static void oldSaveWithBrokenRootsStillRises(GameTestHelper helper) {
        reloadMidStun(helper, true);
    }

    /** 叩かれて吹いた胞子で、近くの Mob の暴露量が上がる。防護マスクを被っていれば上がらない。ダメージは無い。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200, batch = BATCH)
    public static void sporesRaiseExposureAndTheMaskBlocksIt(GameTestHelper helper) {
        Cow[] cows = new Cow[2];
        run(helper, a -> {
        }, 16.5, 8.5, 0.0F, (a, e) -> {
            Vec3 h = e.hitParts().part(0).position();
            for (int i = 0; i < 2; i++) {
                Cow cow = new Cow(EntityType.COW, a.level());
                cow.moveTo(h.x + (i == 0 ? 0.6D : -0.6D), FLOOR, h.z, 0, 0);
                cow.setNoAi(true);
                cow.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100.0D);
                cow.setHealth(100.0F);
                a.level().addFreshEntity(cow);
                cows[i] = cow;
            }
            cows[1].setItemSlot(EquipmentSlot.HEAD, new net.minecraft.world.item.ItemStack(ModItems.PROTECTIVE_MASK.get()));
            e.hitParts().part(0).hurt(a.level().damageSources().generic(), 1.0F);
        }, (a, e) -> {
        }, 90, (a, e) -> {
            double bare = OvAttach.getData(cows[0], ModAttachments.SPORE_EXPOSURE).dose, masked = OvAttach.getData(cows[1], ModAttachments.SPORE_EXPOSURE).dose;
            float hp0 = cows[0].getHealth();
            for (Cow c : cows) c.discard();
            if (bare <= 0.0D) helper.fail("胞子で暴露量が上がらない: " + bare);
            if (masked > 0.0D) helper.fail("マスクを被っても暴露量が上がった: " + masked);
            if (hp0 < 100.0F) helper.fail("胞子でダメージを受けた: " + hp0);
        });
    }

    // ------------------------------------------------------------------ 見つけた後

    /**
     * 印を付けると、半径 64 の G3 が相手へ向かう(司令塔の口 Watchers.markSeen → PlantSideCall → この MOD の口)。
     * ファルキムネスは相手を標的に持ち、ヴェナムネス(まだ攻撃が無い)は相手の居場所へ歩く。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 520, batch = BATCH)
    public static void markedPlayerDrawsNearbyG3(GameTestHelper helper) {
        Player[] pl = new Player[1];
        net.minecraft.world.entity.Mob[] g3 = new net.minecraft.world.entity.Mob[2];
        double[] d0 = {-1};
        long[] markedAt = {-1};
        run(helper, a -> {
        }, 16.5, 8.5, 0.0F, (a, e) -> {
            pl[0] = player(helper, a, 16.5, FLOOR, 28.5);
            e.watch().forcedTarget = pl[0];
            e.watch().forceWatch();
            g3[0] = com.yayuy.overgrownvegetation.registry.ModEntities.FALCIMNES.get().create(a.level());
            g3[1] = com.yayuy.overgrownvegetation.registry.ModRunners.VENAMNES.get().create(a.level());
            g3[0].moveTo(a.x0() + 3.5, FLOOR, a.z0() + 3.5, 0, 0);
            g3[1].moveTo(a.x0() + 29.5, FLOOR, a.z0() + 3.5, 0, 0);
            for (net.minecraft.world.entity.Mob m : g3) {
                m.targetSelector.removeAllGoals(g -> true);   // 標的はこのテストの呼びかけだけで
                a.level().addFreshEntity(m);
            }
        }, (a, e) -> {
            long now = a.level().getGameTime();
            if (markedAt[0] < 0 && marked(pl[0])) {
                markedAt[0] = now;
                d0[0] = g3[1].distanceTo(pl[0]);
            }
        }, 500, (a, e) -> {
            boolean falc = g3[0].getTarget() == pl[0];
            double d1 = g3[1].distanceTo(pl[0]);
            boolean venGuided = com.yayuy.overgrownvegetation.entity.PhyllosensCall.guided(g3[1]) || g3[1].getTarget() == pl[0];
            for (net.minecraft.world.entity.Mob m : g3) m.discard();
            if (markedAt[0] < 0) helper.fail("印が付かない: " + why(a, e));
            if (!falc) helper.fail("ファルキムネスが印の相手を標的に持たない");
            if (!venGuided) helper.fail("ヴェナムネスが呼ばれていない");
            if (!(d1 < d0[0] - 3.0D)) helper.fail(String.format(Locale.ROOT, "ヴェナムネスが相手へ寄らない: %.1f → %.1f", d0[0], d1));
        });
    }
}
