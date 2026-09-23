package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.GunTurretBlock;
import com.yayuy.overgrownvegetation.block.GunTurretBlockEntity;
import com.yayuy.overgrownvegetation.corruption.CorruptionRegistry;
import com.yayuy.overgrownvegetation.defense.GunTurrets;
import com.yayuy.overgrownvegetation.entity.VelophyllBombEntity;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.siege.ShellTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import com.yayuy.overgrownvegetation.registry.ModItems;

/**
 * <b>機関砲台</b>(2026-09-21)のテスト。{@code gradlew.bat runGameTestServer} で実行(ヘッドレス)。
 * テンプレートは {@code data/overgrown_vegetation/structure/gametest_empty7.nbt}(7×7×7、床は石)。
 *
 * <p>ここで守りたいのは 3 つ。
 * <ul>
 *     <li><b>弾があれば勝手に撃つ</b> —— 自動で動く設備なので、これが動かなければ何も始まらない。</li>
 *     <li><b>弾が無ければ何もしない</b> —— 弾を消費せずに撃てたら、弾倉という仕組みが意味を失う。</li>
 *     <li><b>飛んでくる胞子弾を空中で割り、増援を運ぶ弾なら小さな爆弾が散る</b> ——
 *     エンティティではない弾を狙う道({@link ShellTracker#intercept})は、この機能でしか通らない。</li>
 * </ul>
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class GunTurretGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(GunTurretGameTests.class);
        }

        private Registration() {
        }
    }

    private static final String TEMPLATE = "gametest_empty7";
    /** 砲台のテストだけを別のバッチにまとめる(射程 32 ブロックが他のテストの Mob へ届かないように)。 */
    private static final String BATCH = "gun_turret";
    private static final BlockPos AT = new BlockPos(2, 2, 2);
    /**
     * 相手を置く場所。砲台の<b>東</b>(据え付けの向きは北なので 90 度旋回しないと撃てない)。
     *
     * <p>3 ブロック先の地面に立つ背の低いクモを見下ろす角度は −15 度ほど。<b>俯角 −28 度</b>
     * (ユーザー指定 2026-09-21)があって初めて狙える位置なので、ここは足元の死角のテストも兼ねている。
     */
    private static final BlockPos PREY = new BlockPos(5, 2, 2);

    /**
     * 弾を入れておけば、射程に入った敵を自動で撃つ。
     *
     * <p>旋回は秒間 15 度(ユーザー指定)なので、90 度回るのに 120 tick かかる。待つのはそのぶん。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 400, batch = BATCH)
    public static void turretShootsHostile(GameTestHelper helper) {
        GunTurretBlockEntity turret = place(helper, 32);
        Spider prey = helper.spawn(EntityType.SPIDER, PREY);
        prey.setNoAi(true);

        helper.runAfterDelay(200L, () -> {
            if (turret.ammoCount() >= 32) {
                helper.fail("射程内に敵がいるのに一発も撃っていない " + state(turret, prey));
                return;
            }
            helper.succeed();
        });
    }

    /**
     * 弾が無ければ何もしない(空撃ちしない)。
     *
     * <p>相手の体力では見ない —— GameTest は同じ世界に並べて走るので、<b>隣のテストの砲台</b>が
     * 射程 32 ブロックでこちらの相手を撃ってしまう。見るのはこの砲台自身の状態だけ。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 200, batch = BATCH)
    public static void emptyTurretDoesNothing(GameTestHelper helper) {
        GunTurretBlockEntity turret = place(helper, 0);
        Spider prey = helper.spawn(EntityType.SPIDER, PREY);
        prey.setNoAi(true);

        helper.runAfterDelay(80L, () -> {
            if (turret.isArmed() || turret.hasTarget()) {
                helper.fail("弾が無いのに撃つ気でいる " + state(turret, prey));
                return;
            }
            helper.succeed();
        });
    }

    /**
     * 飛んでくる胞子弾(増援入り)を空中で撃ち落とし、小さな胞子爆弾が散る。
     *
     * <p>弾はエンティティではないので、<b>散った小さな爆弾({@link VelophyllBombEntity})の数</b>で判定する ——
     * 撃ち落とされずに着弾した弾は爆発して増援を湧かせるだけで、小さな爆弾は 1 つも作らない。
     * 散った爆弾はすぐ落ちて自分も爆ぜるので、毎 tick 数えていちばん多かった瞬間を見る。
     *
     * <p>弾を高いところから落とせないのは GameTest の都合 —— テストの囲いは構造物の外側を
     * バリアブロックで塞いでいるので、屋根の上に出すと銃口から見通せない。旋回が秒間 15 度と遅いので、
     * <b>最初から弾の方を向かせて</b>から落とす(旋回そのものは {@link #turretShootsHostile} で見る)。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 300, batch = BATCH)
    public static void turretInterceptsSporeShell(GameTestHelper helper) {
        GunTurretBlockEntity turret = place(helper, 64);
        ServerLevel level = helper.getLevel();
        // 砲台の北の斜め上(据え付けの向き = 北)をゆっくり落ちてくる弾。真上だと仰角の可動域(+85 度)を越える。
        Vec3 origin = Vec3.atBottomCenterOf(helper.absolutePos(AT)).add(0.0D, 3.8D, -1.6D);
        // 弾の来る方へ据え付けておく。秒間 15 度では、近くを落ちてくるものに首を振って追いつけない
        // (近いほど見かけの角速度が大きい)ので、旋回の有無をここで混ぜない。
        double[] aim = com.yayuy.overgrownvegetation.defense.GunTurretGeometry.aimAt(
                com.yayuy.overgrownvegetation.defense.GunTurretGeometry.trunnion(helper.absolutePos(AT), 0.0D),
                origin);
        turret.setAzimuth(aim[1]);
        turret.setElevation(aim[0]);
        int id = ShellTracker.get(level).fireSpore(level, origin, Vec3.ZERO, 0.01D, 10.0F, 2);

        int[] mostBomblets = {0};
        for (long tick = 3L; tick <= 40L; tick++) {
            helper.runAfterDelay(tick, () -> mostBomblets[0] = Math.max(mostBomblets[0],
                    level.getEntitiesOfClass(VelophyllBombEntity.class,
                            new AABB(origin, origin).inflate(24.0D)).size()));
        }

        helper.runAfterDelay(45L, () -> {
            if (mostBomblets[0] <= 0) {
                helper.fail("増援を運ぶ胞子弾を撃ち落とせていない(小さな胞子爆弾が 1 つも散っていない)"
                        + " [残弾 " + turret.ammoCount() + " / 弾はまだ飛行中 "
                        + (ShellTracker.get(level).shell(id) != null) + "]");
                return;
            }
            if (turret.ammoCount() >= 64) {
                helper.fail("撃ったのに弾が減っていない");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * <b>汚染される</b>(ユーザー報告 2026-09-21「機関砲台が全く汚染されない」)。
     *
     * <p>機関砲台は<b>同じブロックの別の状態</b>へ汚染される(テクスチャだけ過繫茂に変わる)。
     * 汚染の各経路は「変換先が同じブロックなら何もしない」で素通りさせていたので、
     * いつまでも汚染されなかった。ここでは 3 つを押さえる:
     * きれいな砲台が汚染の対象であること / 変換すると過繫茂の状態になること /
     * <b>弾倉の中身が消えないこと</b>(同じブロックなので BlockEntity は生き残るはず)。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 100, batch = BATCH)
    public static void turretGetsOvergrown(GameTestHelper helper) {
        GunTurretBlockEntity turret = place(helper, 16);
        // 隣のテストの敵を撃って弾が減らないように、安全装置を入れておく。
        turret.setPowered(true);
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(AT);
        BlockState clean = level.getBlockState(pos);

        if (!CorruptionRegistry.canCorrupt(level, pos, clean)) {
            helper.fail("きれいな砲台が汚染の対象から外れている");
            return;
        }
        BlockState corrupted = CorruptionRegistry.corrupt(level, pos, clean, level.random);
        if (CorruptionRegistry.noChange(clean, corrupted)) {
            helper.fail("汚染しても何も変わらない(汚染の経路が素通りする)");
            return;
        }
        if (!corrupted.getValue(GunTurretBlock.OVERGROWN)) {
            helper.fail("汚染先が「過繫茂した機関砲台」になっていない: " + corrupted);
            return;
        }

        CorruptionRegistry.placeCorrupted(level, pos, clean, corrupted, Block.UPDATE_ALL);
        BlockState after = level.getBlockState(pos);
        if (!after.is(ModBlocks.GUN_TURRET.get()) || !after.getValue(GunTurretBlock.OVERGROWN)) {
            helper.fail("置き換わった結果が過繫茂した機関砲台ではない: " + after);
            return;
        }
        if (!(level.getBlockEntity(pos) instanceof GunTurretBlockEntity kept) || kept.ammoCount() != 16) {
            helper.fail("汚染で弾倉の中身が消えた");
            return;
        }
        if (CorruptionRegistry.canCorrupt(level, pos, after)) {
            helper.fail("もう過繫茂しているのに、まだ汚染の対象になっている");
            return;
        }
        helper.succeed();
    }

    /**
     * <b>覆われた砲台は動かない</b>(ユーザー指定 2026-09-21)。弾は残したまま、撃つのをやめる。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 400, batch = BATCH)
    public static void overgrownTurretStopsShooting(GameTestHelper helper) {
        GunTurretBlockEntity turret = place(helper, 32);
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(AT);
        ((GunTurretBlock) ModBlocks.GUN_TURRET.get()).setOvergrown(level, pos, level.getBlockState(pos), true);

        Spider prey = helper.spawn(EntityType.SPIDER, PREY);
        prey.setNoAi(true);

        helper.runAfterDelay(200L, () -> {
            if (turret.isArmed() || turret.hasTarget()) {
                helper.fail("覆われているのに撃つ気でいる " + state(turret, prey));
                return;
            }
            if (turret.ammoCount() != 32) {
                helper.fail("覆われているのに弾が減っている(残り " + turret.ammoCount() + "/32)");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * <b>胞子爆弾が近くで炸裂すると覆われて止まる</b> —— 過繫茂したスカトゥリウムが迫撃砲で狙う道
     * ({@code entity/ShielderTurretMortarGoal} → {@link GunTurrets#corruptNear})。
     * ここでは爆弾を直接落として、台帳と状態の両方が切り替わることを見る。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 200, batch = BATCH)
    public static void sporeBombCoversTurret(GameTestHelper helper) {
        place(helper, 16);
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(AT);
        Vec3 center = Vec3.atCenterOf(pos);

        if (GunTurrets.nearestWorking(level, center, 8.0D) == null) {
            helper.fail("置いた砲台が台帳に載っていない(スカトゥリウムに見つけてもらえない)");
            return;
        }
        // 砲台の真上へ小さめの胞子爆弾を落とす(スカトゥリウムの迫撃砲と同じ弾)。
        level.addFreshEntity(new VelophyllBombEntity(level, center.add(0.0D, 3.0D, 0.0D), Vec3.ZERO, null, 0.5F));

        helper.runAfterDelay(60L, () -> {
            if (!level.getBlockState(pos).getValue(GunTurretBlock.OVERGROWN)) {
                helper.fail("胞子爆弾が炸裂したのに砲台が覆われていない");
                return;
            }
            if (GunTurrets.nearestWorking(level, center, 8.0D) != null) {
                helper.fail("止まった砲台が台帳に残っている(スカトゥリウムが撃ち続けてしまう)");
                return;
            }
            if (!(level.getBlockEntity(pos) instanceof GunTurretBlockEntity kept) || kept.ammoCount() != 16) {
                helper.fail("覆われたときに弾倉の中身が消えた");
                return;
            }
            helper.succeed();
        });
    }

    /** 失敗したときに原因を追えるだけの状態を 1 行で。 */
    private static String state(GunTurretBlockEntity turret, Spider prey) {
        return "[弾 " + turret.ammoCount() + " / 射撃可 " + turret.isArmed() + " / 目標 " + turret.hasTarget()
                + " / 仰角 " + String.format("%.1f", turret.elevation())
                + " / 方位 " + String.format("%.1f", turret.azimuth())
                + " / 相手 " + (prey.isAlive() ? prey.getHealth() + " 生存" : "死亡") + "]";
    }

    /** 砲台を置いて、弾を {@code rounds} 発だけ入れる。 */
    private static GunTurretBlockEntity place(GameTestHelper helper, int rounds) {
        helper.setBlock(AT, ModBlocks.GUN_TURRET.get().defaultBlockState());
        if (!(helper.getBlockEntity(AT) instanceof GunTurretBlockEntity turret)) {
            throw new IllegalStateException("機関砲台のブロックエンティティが作られていない");
        }
        if (rounds > 0) {
            turret.setItem(0, new ItemStack(ModItems.AUTOCANNON_ROUND.get(), rounds));
        }
        return turret;
    }

    private GunTurretGameTests() {
    }
}
