package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.GunTurretBlock;
import com.yayuy.overgrownvegetation.block.GunTurretBlockEntity;
import com.yayuy.overgrownvegetation.block.GunTurretMode;
import com.yayuy.overgrownvegetation.block.SporeDeviceBlockEntity;
import com.yayuy.overgrownvegetation.corruption.CorruptionRegistry;
import com.yayuy.overgrownvegetation.defense.GunTurretClaims;
import com.yayuy.overgrownvegetation.defense.GunTurrets;
import com.yayuy.overgrownvegetation.entity.VelophyllBombEntity;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.siege.ShellTracker;
import com.yayuy.overgrownvegetation.spore.SporeDeviceData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
    /** 砲台のテストだけを別のバッチにまとめる(射程 48 ブロックが他のテストの Mob へ届かないように)。 */
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
     * <p>旋回は秒間 22.5 度(ユーザー指定)なので、90 度回るのに 80 tick かかる。待つのはそのぶん。
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
     * 射程 48 ブロックでこちらの相手を撃ってしまう。見るのはこの砲台自身の状態だけ。
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
     * バリアブロックで塞いでいるので、屋根の上に出すと銃口から見通せない。旋回が秒間 22.5 度と遅いので、
     * <b>最初から弾の方を向かせて</b>から落とす(旋回そのものは {@link #turretShootsHostile} で見る)。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 300, batch = BATCH)
    public static void turretInterceptsSporeShell(GameTestHelper helper) {
        GunTurretBlockEntity turret = place(helper, 64);
        ServerLevel level = helper.getLevel();
        // 砲台の北の斜め上(据え付けの向き = 北)をゆっくり落ちてくる弾。真上だと仰角の可動域(+85 度)を越える。
        Vec3 origin = Vec3.atBottomCenterOf(helper.absolutePos(AT)).add(0.0D, 3.8D, -1.6D);
        // 弾の来る方へ据え付けておく。秒間 22.5 度でも、近くを落ちてくるものには首を振って追いつけない
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
        // 砲台の真上へ小さめの胞子爆弾を落とす。迫撃砲の印の無い爆弾なので bombDisableChance(既定 1.0 = 必ず止まる)。
        // 迫撃砲の弾の確率(15%)は mortarDisablesTurretByChance で見る。
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

    /**
     * <b>確率で止まる</b>(ユーザー指定 2026-09-24「迫撃砲が当たって 35% で無効化」→ 2026-09-25 に 15%)。
     * 止まるかどうかは {@link GunTurrets#corruptNear} が砲台ごとに振るので、種を決めた乱数で 400 回振って割合を見る
     * (止まるたびに拭いて動く状態へ戻す)。確率 0 なら決して止まらず、1 なら必ず止まる。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 40, batch = BATCH)
    public static void mortarDisablesTurretByChance(GameTestHelper helper) {
        place(helper, 0);
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(AT);
        Vec3 center = Vec3.atCenterOf(pos);
        net.minecraft.util.RandomSource random = net.minecraft.util.RandomSource.create(20260924L);

        int trials = 400;
        int covered = 0;
        for (int i = 0; i < trials; i++) {
            covered += GunTurrets.corruptNear(level, center, 1.0D, 0.15D, random);
            wipe(level, pos);
        }
        // 期待値 60・標準偏差 7.1。種を決めてあるので毎回同じ数になるが、式が 15% から外れたら必ず枠を出る幅にしてある。
        if (covered < 35 || covered > 85) {
            helper.fail("15% で振ったのに 400 回中 " + covered + " 回止まった(期待 60 前後)");
            return;
        }
        if (GunTurrets.corruptNear(level, center, 1.0D, 0.0D, random) != 0) {
            helper.fail("確率 0 なのに止まった");
            return;
        }
        if (GunTurrets.corruptNear(level, center, 1.0D, 1.0D, random) != 1) {
            helper.fail("確率 1 なのに止まらなかった");
            return;
        }
        helper.succeed();
    }

    /**
     * 迫撃砲の弾だけが迫撃砲の確率({@code mortarDisableChance})を使い、ほかの胞子爆弾は今までどおり
     * ({@code bombDisableChance})。弾の印はセーブを挟んでも残る(飛んでいる途中でワールドを閉じても迫撃砲の弾のまま)。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 20, batch = BATCH)
    public static void mortarShellKeepsItsChance(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        com.yayuy.overgrownvegetation.data.DefenseTable defense = com.yayuy.overgrownvegetation.data.DesignData.defense();
        VelophyllBombEntity bomb = new VelophyllBombEntity(level, Vec3.ZERO, Vec3.ZERO, null);
        VelophyllBombEntity shell = new VelophyllBombEntity(level, Vec3.ZERO, Vec3.ZERO, null).asMortarShell();
        if (bomb.turretDisableChance() != defense.turretBombDisableChance()) {
            helper.fail("迫撃砲でない爆弾が bombDisableChance を使っていない: " + bomb.turretDisableChance());
            return;
        }
        if (shell.turretDisableChance() != defense.turretMortarDisableChance()) {
            helper.fail("迫撃砲の弾が mortarDisableChance を使っていない: " + shell.turretDisableChance());
            return;
        }
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        shell.saveWithoutId(tag);
        VelophyllBombEntity loaded = new VelophyllBombEntity(
                com.yayuy.overgrownvegetation.registry.ModEntities.VELOPHYLL_BOMB.get(), level);
        loaded.load(tag);
        if (!loaded.isMortarShell()) {
            helper.fail("セーブを挟んだら迫撃砲の弾でなくなった");
            return;
        }
        helper.succeed();
    }

    /** 覆われた砲台を拭いて、動く状態(台帳にも載る)へ戻す。 */
    private static void wipe(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof GunTurretBlock turret) {
            turret.setOvergrown(level, pos, state, false);
        }
    }

    /**
     * <b>稼働中の胞子除去装置の圏内では覆われない</b>(ユーザー指定 2026-09-26)。
     * 通常の汚染({@link CorruptionRegistry#canCorrupt})と、胞子爆弾の経路
     * ({@link GunTurrets#corruptNear} —— スカトゥリウムの迫撃砲)の<b>両方</b>を見る。
     *
     * <p><b>別のバッチ</b>にしてあるのは除去装置の効き幅(半径 3 チャンク = 48 ブロック)のため ——
     * 同じバッチに並ぶ砲台のテスト場(24〜48 ブロック隣)まで守ってしまい、
     * 「汚染される」側のテストが通らなくなる。ほかのテストが残した装置の影響も受けないよう、
     * 台帳を空にしてから自分の 1 台だけを置き、最後に必ず戻す。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 400, batch = "gun_turret_purifier")
    public static void purifierKeepsTurretClean(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        long[][] devices = SporeDeviceData.isolateForTests(level);

        GunTurretBlockEntity turret = place(helper, 16);
        turret.setPowered(true); // 撃たせない(このテストで見たいのは汚染だけ)
        BlockPos pos = helper.absolutePos(AT);

        BlockPos devicePos = new BlockPos(5, 2, 5);
        helper.setBlock(devicePos, ModBlocks.SPORE_PURIFIER.get().defaultBlockState());
        SporeDeviceBlockEntity device = (SporeDeviceBlockEntity) helper.getBlockEntity(devicePos);
        device.setItem(SporeDeviceBlockEntity.SLOT_BATTERY, com.yayuy.overgrownvegetation.item.BatteryItem.withCharge(com.yayuy.overgrownvegetation.item.BatteryItem.CAPACITY));
        device.setItem(SporeDeviceBlockEntity.SLOT_FILTER, new ItemStack(ModItems.SPORE_FILTER.get()));

        helper.runAfterDelay(80L, () -> {
            try {
                if (!device.isActive()) {
                    helper.fail("胞子除去装置が動いていない(テストの前提が崩れている)");
                    return;
                }
                if (!SporeDeviceData.protectsFromCorruption(level, pos)) {
                    helper.fail("砲台が稼働中の除去装置の圏内に入っていない(テストの前提が崩れている)");
                    return;
                }
                BlockState clean = level.getBlockState(pos);
                if (CorruptionRegistry.canCorrupt(level, pos, clean)) {
                    helper.fail("除去装置の圏内なのに、通常の汚染の対象になっている");
                    return;
                }
                // 胞子爆弾(必ず覆う確率 1.0)を砲台の上で炸裂させる。
                GunTurrets.corruptNear(level, Vec3.atCenterOf(pos), 8.0D, 1.0D, level.getRandom());
                if (level.getBlockState(pos).getValue(GunTurretBlock.OVERGROWN)) {
                    helper.fail("除去装置の圏内なのに、胞子爆弾で覆われた");
                    return;
                }
                helper.succeed();
            } finally {
                SporeDeviceData.restoreForTests(level, devices);
            }
        });
    }

    /**
     * <b>2 台の砲台は別々の相手を狙う</b>(ユーザー要望 2026-09-26
     * 「複数の機関砲台があると、すべてが同じ目標を狙ってしまう」)。
     *
     * <p>仕組みは札({@link GunTurretClaims}) —— 先に選んだ砲台が「自分が撃っている」と立て、
     * あとから選ぶ砲台は同じ優先度の中でそれを後回しにする。
     *
     * <p><b>別のバッチ</b>にしてあるのは、砲台の射程(48)が隣のテスト場まで届くため ——
     * 隣のテストの砲台がこちらのクモに札を立てると、2 台とも「もう一方」へ寄ってしまう。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 400, batch = "gun_turret_split")
    public static void twoTurretsSplitTargets(GameTestHelper helper) {
        // 2 台を隣どうしに置く —— 向ける手間(engageCost)がほぼ同じになるので、札が無ければ
        // 2 台とも同じクモ(近くて正面の方)を選ぶ。
        GunTurretBlockEntity first = place(helper, 32);
        GunTurretBlockEntity second = place(helper, new BlockPos(3, 2, 2), 32, GunTurretMode.DUAL);
        Spider left = helper.spawn(EntityType.SPIDER, PREY);
        Spider right = helper.spawn(EntityType.SPIDER, new BlockPos(5, 2, 4));
        // 倒されると「目標なし」になって何も見えなくなるので、的は無敵にして狙いだけを見る。
        for (Spider prey : new Spider[]{left, right}) {
            prey.setNoAi(true);
            prey.setInvulnerable(true);
        }

        helper.runAfterDelay(120L, () -> {
            int a = first.targetEntityId();
            int b = second.targetEntityId();
            if (a < 0 || b < 0) {
                helper.fail("どちらかが目標を持っていない [" + a + " / " + b + "]"
                        + " 1 台目 " + state(first, left) + " / 2 台目 " + state(second, right)
                        + " / 2 台目→左 " + engageReport(helper, new BlockPos(3, 2, 2), second, left)
                        + " / 2 台目→右 " + engageReport(helper, new BlockPos(3, 2, 2), second, right));
                return;
            }
            if (a == b) {
                helper.fail("2 台が同じ相手を狙っている(札が効いていない)");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * <b>防空モードは地上の目標を狙わない</b>(ユーザー要望 2026-09-26)。
     * 目の前にクモが居ても、弾は 1 発も減らない。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 300, batch = BATCH)
    public static void airModeIgnoresGroundTargets(GameTestHelper helper) {
        GunTurretBlockEntity turret = place(helper, AT, 32, GunTurretMode.AIR);
        Spider prey = helper.spawn(EntityType.SPIDER, PREY);
        prey.setNoAi(true);

        helper.runAfterDelay(200L, () -> {
            if (turret.hasTarget() || turret.ammoCount() != 32) {
                helper.fail("防空モードなのに地上のクモを狙っている " + state(turret, prey));
                return;
            }
            helper.succeed();
        });
    }

    /**
     * <b>対地モードは空の目標を狙わない</b>(ユーザー要望 2026-09-26)。
     * 真上に胞子爆弾が落ちてきても撃たない。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 300, batch = BATCH)
    public static void groundModeIgnoresAirTargets(GameTestHelper helper) {
        GunTurretBlockEntity turret = place(helper, AT, 32, GunTurretMode.GROUND);
        ServerLevel level = helper.getLevel();
        Vec3 above = Vec3.atBottomCenterOf(helper.absolutePos(AT)).add(0.0D, 3.5D, -1.5D);
        level.addFreshEntity(new VelophyllBombEntity(level, above, Vec3.ZERO, null, 0.4F));

        helper.runAfterDelay(30L, () -> {
            if (turret.ammoCount() != 32) {
                helper.fail("対地モードなのに胞子爆弾を撃っている(残弾 " + turret.ammoCount() + "/32)");
                return;
            }
            helper.succeed();
        });
    }

    /** 失敗を追うため: その砲台からその相手を撃てるか、撃てないなら何で止まっているか。 */
    private static String engageReport(GameTestHelper helper, BlockPos rel, GunTurretBlockEntity turret, Spider prey) {
        BlockPos pos = helper.absolutePos(rel);
        Vec3 point = com.yayuy.overgrownvegetation.defense.GunTurretTargeting.aimPoint(prey, 1.0F);
        Vec3 trunnion = com.yayuy.overgrownvegetation.defense.GunTurretGeometry.trunnion(pos, turret.azimuth());
        double[] aim = com.yayuy.overgrownvegetation.defense.GunTurretGeometry.aimAt(trunnion, point);
        Vec3 blocked = com.yayuy.overgrownvegetation.defense.GunTurretTargeting.blockedAt(
                helper.getLevel(), pos, turret.azimuth(), point);
        return "(距離 " + String.format("%.1f", Math.sqrt(point.distanceToSqr(trunnion)))
                + " / 必要仰角 " + String.format("%.1f", aim[0])
                + " / 遮蔽 " + (blocked == null ? "なし" : blocked.toString())
                + " / 撃てる " + com.yayuy.overgrownvegetation.defense.GunTurretTargeting.canEngage(
                        helper.getLevel(), pos, turret.azimuth(), point,
                        com.yayuy.overgrownvegetation.defense.GunTurretTargeting.rangeFor(prey)) + ")";
    }

    /** 失敗したときに原因を追えるだけの状態を 1 行で。 */
    /**
     * <b>向いている方の相手から撃ち、撃ち切るまで乗り換えない</b>(2026-09-25 ユーザー報告「機関砲台がずっと目標を切り替え続け、
     * 敵が近くにいるのに撃たない」)。北を向いた砲台の、東 2 ブロック(90 度横)と北 4 ブロック(正面)にハスク。
     * <ol>
     *     <li>近い順なら東だが、旋回に 80 tick かかる。正面の北を選ぶ({@code GunTurretTargeting#engageCost})。</li>
     *     <li>撃っている間に正面の 2 ブロック(北より近くて向ける手間も小さい)に 3 体目が来ても、北を撃ち続ける(乗り換えない)。</li>
     *     <li>北が消えたら、残りのうち向ける手間の小さい 3 体目へ移る。</li>
     * </ol>
     * 的は死なない(無敵・NoAI)ので、撃った数と目標だけで判定する。ほかのバッチの爆弾・ヴェロフィル(最優先)が射程に入らないよう
     * 自分だけのバッチ。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 200, batch = "gun_turret_pick")
    public static void turretPrefersAimedTargetAndKeepsIt(GameTestHelper helper) {
        BlockPos at = new BlockPos(3, 2, 5);
        helper.setBlock(at, ModBlocks.GUN_TURRET.get().defaultBlockState());
        if (!(helper.getBlockEntity(at) instanceof GunTurretBlockEntity turret)) {
            throw new IllegalStateException("機関砲台のブロックエンティティが作られていない");
        }
        com.yayuy.overgrownvegetation.defense.MachineBattery.swap(turret, new ItemStack(ModItems.BATTERY.get()));
        turret.setItem(0, new ItemStack(ModItems.AUTOCANNON_ROUND.get(), 64));
        turret.setAzimuth(0.0D);
        turret.setElevation(0.0D);
        net.minecraft.world.entity.monster.Husk side = pickTarget(helper, new BlockPos(5, 2, 5));
        net.minecraft.world.entity.monster.Husk ahead = pickTarget(helper, new BlockPos(3, 2, 1));
        net.minecraft.world.entity.monster.Husk[] closer = new net.minecraft.world.entity.monster.Husk[1];
        int[] ammo = new int[1];
        helper.startSequence()
                .thenExecuteAfter(15, () -> {
                    if (turret.targetEntityId() != ahead.getId()) {
                        helper.fail("正面(北 4)ではなく " + turret.targetEntityId() + " を選んだ(横 " + side.getId() + "・正面 "
                                + ahead.getId() + ")方位 " + turret.azimuth());
                    }
                    ammo[0] = turret.ammoCount();
                    closer[0] = pickTarget(helper, new BlockPos(3, 2, 3));
                })
                .thenExecuteAfter(60, () -> {
                    if (turret.targetEntityId() != ahead.getId()) {
                        helper.fail("撃っている相手を捨てて " + turret.targetEntityId() + " へ乗り換えた(正面 " + ahead.getId()
                                + "・近い 3 体目 " + closer[0].getId() + ")");
                    }
                    if (turret.ammoCount() >= ammo[0]) {
                        helper.fail("正面の相手を狙ったまま 60 tick 撃っていない(弾 " + turret.ammoCount() + ")方位 " + turret.azimuth()
                                + "・仰角 " + turret.elevation());
                    }
                    ahead.discard();
                })
                .thenExecuteAfter(15, () -> {
                    if (turret.targetEntityId() != closer[0].getId()) {
                        helper.fail("正面の相手が消えたあと、手前の 3 体目ではなく " + turret.targetEntityId() + " を選んだ(横 "
                                + side.getId() + ")");
                    }
                })
                .thenSucceed();
    }

    /** 選び方のテストの的: 動かず(NoAI)死なない(無敵)ハスク(日に焼けない)。 */
    private static net.minecraft.world.entity.monster.Husk pickTarget(GameTestHelper helper, BlockPos pos) {
        net.minecraft.world.entity.monster.Husk husk = helper.spawn(EntityType.HUSK, pos);
        husk.setNoAi(true);
        husk.setInvulnerable(true);
        return husk;
    }

    private static String state(GunTurretBlockEntity turret, Spider prey) {
        return "[弾 " + turret.ammoCount() + " / 射撃可 " + turret.isArmed() + " / 目標 " + turret.hasTarget()
                + " / 仰角 " + String.format("%.1f", turret.elevation())
                + " / 方位 " + String.format("%.1f", turret.azimuth())
                + " / 相手 " + (prey.isAlive() ? prey.getHealth() + " 生存" : "死亡") + "]";
    }

    /** 砲台を置いて、弾を {@code rounds} 発だけ入れる。 */
    private static GunTurretBlockEntity place(GameTestHelper helper, int rounds) {
        return place(helper, AT, rounds, GunTurretMode.DUAL);
    }

    /** 場所とモードを指定して置く(2 台のテスト・モードのテスト用)。 */
    private static GunTurretBlockEntity place(GameTestHelper helper, BlockPos at, int rounds, GunTurretMode mode) {
        helper.setBlock(at, ModBlocks.GUN_TURRET.get().defaultBlockState().setValue(GunTurretBlock.MODE, mode));
        if (!(helper.getBlockEntity(at) instanceof GunTurretBlockEntity turret)) {
            throw new IllegalStateException("機関砲台のブロックエンティティが作られていない");
        }
        // 2026-10-16 から電池が無いと動かない(㉔)。満タンの電池を入れておく。
        com.yayuy.overgrownvegetation.defense.MachineBattery.swap(turret, new ItemStack(ModItems.BATTERY.get()));
        if (rounds > 0) {
            turret.setItem(0, new ItemStack(ModItems.AUTOCANNON_ROUND.get(), rounds));
        }
        return turret;
    }

    private GunTurretGameTests() {
    }
}
