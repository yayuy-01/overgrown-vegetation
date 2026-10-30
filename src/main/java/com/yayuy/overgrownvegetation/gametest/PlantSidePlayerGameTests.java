package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.ally.AllyEntities;
import com.yayuy.overgrownvegetation.ally.SoldierKind;
import com.yayuy.overgrownvegetation.effect.ParasitizedTargetGoal;
import com.yayuy.overgrownvegetation.entity.OvergrownNexusEntity;
import com.yayuy.overgrownvegetation.entity.Predation;
import com.yayuy.overgrownvegetation.item.PlantArmor;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import com.yayuy.overgrownvegetation.registry.ModEntities;
import com.yayuy.overgrownvegetation.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * <b>植物側のプレイヤーは狙われない</b>という不変条件のテスト(2026-10-03)。
 *
 * <h2>何を守っているのか</h2>
 * <p>寄生植物装備({@link PlantArmor})を着けたプレイヤーは<b>過繁茂側(グループ 6)</b>として扱われる。
 * 中心の仕掛けは {@link MobGroup#of(net.minecraft.world.entity.Entity)} が {@code GROUP_6} を返すことで、
 * そこに索敵({@link Predation})とダメージ({@code MobGroupFriendlyFire})が乗っている。
 *
 * <p>2026-10-03 に 1 件ずつ調べたところ、この決まりは<b>もうほぼ成り立っていた</b>
 * (穴は {@code SiegeData#pickFarTarget} の 1 か所だけで、しかもダメージは落ちていた)。
 * だからこのテストは「直したことの確認」ではなく、<b>これから足すグループ 6〜9 が壊したら落ちる柵</b>である。
 * 1.1 の「F5 でストーリーが分岐して植物側に付ける」の土台でもある。
 *
 * <h2>装備あり・なしの両方を見る</h2>
 * <p>どのテストも<b>装備なしの対照</b>を置く。装備なしでも狙われないなら、それは不変条件が効いているのではなく
 * <b>索敵そのものが壊れている</b>ということなので、片側だけ見ると「全部 false」で通ってしまう。
 *
 * <h2>モックのプレイヤーで足りる・足りない所</h2>
 * <p>{@code helper.makeMockPlayer} はこの MOD の 13 のテストで使っている。述語を叩くには足りる。
 * 足りないのは<b>サーバーのプレイヤー一覧に入らない</b>こと —— {@code level.players()} を回す経路
 * ({@code SiegeData#pickFarTarget} と {@code SwarmTargeting#nearestPlayerInRange})は、ここでは試せない
 * (メモリ「GameTest のテスト場の作り」/ {@code SwarmGameTests} の冒頭)。そちらは呼び出し箇所の検査器で受け持つ。
 *
 * <p>群れの標的選び({@code SwarmTargeting#validKind})はパッケージの外から呼べないが、中で
 * {@code Predation.isInvaderTarget(anchor, entity) || Predation.isInvaderPreyGroup(entity)} を通しているので、
 * ここで {@link Predation#isInvaderTarget} を押さえれば群れも押さえられる。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class PlantSidePlayerGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(PlantSidePlayerGameTests.class);
        }

        private Registration() {
        }
    }

    private static final String TEMPLATE = "gametest_empty7";
    private static final String BATCH = "plantSide";

    /** テスト場の隅に置いたモックのプレイヤー。装備は入れない。 */
    private static Player bareMockPlayer(GameTestHelper helper, BlockPos at) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        Vec3 pos = helper.absoluteVec(new Vec3(at.getX() + 0.5D, at.getY(), at.getZ() + 0.5D));
        player.moveTo(pos.x, pos.y, pos.z);
        return player;
    }

    /** 寄生植物装備を着けたモックのプレイヤー。{@link PlantArmor#worn} は胸だけを見る。 */
    private static Player armoredMockPlayer(GameTestHelper helper, BlockPos at) {
        Player player = bareMockPlayer(helper, at);
        player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(ModItems.PLANT_ARMOR.get()));
        return player;
    }

    /** 装備を着けたプレイヤーはグループ 6、着けていなければどのグループにも属さない。ここが全部の土台。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 40, batch = BATCH)
    public static void plantArmorMakesThePlayerGroupSix(GameTestHelper helper) {
        Player bare = bareMockPlayer(helper, new BlockPos(1, 2, 1));
        Player armored = armoredMockPlayer(helper, new BlockPos(5, 2, 5));

        helper.assertFalse(PlantArmor.isHost(bare), "装備を着けていないプレイヤーが植物側になっている");
        helper.assertTrue(PlantArmor.isHost(armored), "胸に装備を入れたのに植物側にならない(worn が胸を見ていない?)");
        helper.assertTrue(MobGroup.of(bare) == null,
                "装備なしのプレイヤーがグループに属している: " + MobGroup.of(bare));
        helper.assertTrue(MobGroup.of(armored) == MobGroup.GROUP_6,
                "装備ありのプレイヤーがグループ 6 でない: " + MobGroup.of(armored));
        helper.succeed();
    }

    /**
     * 拡大種(グループ 4)は装備ありのプレイヤーを襲撃対象にしない。<b>装備なしは襲撃対象にする</b>
     * —— ここが対照で、両方見ないと索敵が死んでいても通ってしまう。
     *
     * <p>{@code SwarmTargeting#validKind} も群れの標的選びでこの述語を通すので、群れもここで押さえられる。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 40, batch = BATCH)
    public static void invadersIgnoreThePlantSidePlayer(GameTestHelper helper) {
        Mob invader = helper.spawnWithNoFreeWill(ModEntities.VELOPHYLL.get(), new BlockPos(3, 2, 3));
        Player bare = bareMockPlayer(helper, new BlockPos(1, 2, 1));
        Player armored = armoredMockPlayer(helper, new BlockPos(5, 2, 5));

        helper.assertTrue(Predation.isInvaderTarget(invader, bare),
                "拡大種が装備なしのプレイヤーを襲撃対象にしない(索敵が壊れている)");
        helper.assertFalse(Predation.isInvaderTarget(invader, armored),
                "拡大種が植物側のプレイヤーを襲撃対象にした");
        helper.succeed();
    }

    /** 苗床の胞子バースト({@code NexusSporeBurstGoal} が使う判定)も植物側のプレイヤーを数えない。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 40, batch = BATCH)
    public static void nexusIgnoresThePlantSidePlayer(GameTestHelper helper) {
        // 苗床は幅 3 なので真ん中に置く(テスト場の作り: 床は相対 y = 1)。
        Mob spawned = helper.spawnWithNoFreeWill(ModEntities.OVERGROWN_NEXUS.get(), new BlockPos(3, 2, 3));
        if (!(spawned instanceof OvergrownNexusEntity nexus)) {
            helper.fail("苗床が OvergrownNexusEntity でない: " + spawned.getType());
            return;
        }
        Player bare = bareMockPlayer(helper, new BlockPos(1, 2, 1));
        Player armored = armoredMockPlayer(helper, new BlockPos(5, 2, 5));

        helper.assertTrue(nexus.isHostileTarget(bare), "苗床が装備なしのプレイヤーを敵と見ない");
        helper.assertFalse(nexus.isHostileTarget(armored), "苗床が植物側のプレイヤーを敵と見た");
        helper.succeed();
    }

    /**
     * <b>グループ 2・3 は環境生物</b>(ユーザー指定 2026-10-03)—— 装備の有無にかかわらず、
     * プレイヤーを「縄張りの侵入者」にしない。
     *
     * <p>同時に、<b>同盟軍の兵士は侵入者のまま</b>であることも見る(ユーザー指定 2026-10-03
     * 「侵入者として扱います」)。これが落ちると、G3 が縄張りを守る生き物でなくなったことになる。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 40, batch = BATCH)
    public static void crittersNeverTreatPlayersAsIntruders(GameTestHelper helper) {
        Mob predator = helper.spawnWithNoFreeWill(ModEntities.HYLONOMUS.get(), new BlockPos(3, 2, 3));
        Mob soldier = helper.spawnWithNoFreeWill(AllyEntities.type(SoldierKind.RIFLEMAN), new BlockPos(6, 2, 0));
        soldier.setNoAi(true);
        Player bare = bareMockPlayer(helper, new BlockPos(1, 2, 1));
        Player armored = armoredMockPlayer(helper, new BlockPos(5, 2, 5));

        helper.assertFalse(Predation.isIntruder(predator, bare),
                "原生消費種が装備なしのプレイヤーを侵入者と見た(グループ 2・3 は環境生物)");
        helper.assertFalse(Predation.isIntruder(predator, armored),
                "原生消費種が植物側のプレイヤーを侵入者と見た");
        helper.assertTrue(Predation.isIntruder(predator, soldier),
                "原生消費種が同盟軍の兵士を侵入者と見ない(縄張りを守らなくなっている)");
        helper.succeed();
    }

    /**
     * 媒介種(グループ 1)も植物側のプレイヤーを狙わない。<b>装備なしは狙う</b> —— 媒介種は
     * 本来プレイヤーを襲う側なので、ここが両方 false だと索敵が死んでいることになる。
     *
     * <p>2026-10-03 に下の柵({@link #everyGroupMobPicksTargetsThroughAllowedGoals})が見つけた穴。
     * {@code ParasitizedTargetGoal#isExcluded} が寄生植物装備のプレイヤーを外していなかった。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 40, batch = BATCH)
    public static void parasitizedMobsIgnoreThePlantSidePlayer(GameTestHelper helper) {
        Player bare = bareMockPlayer(helper, new BlockPos(1, 2, 1));
        Player armored = armoredMockPlayer(helper, new BlockPos(5, 2, 5));

        helper.assertFalse(ParasitizedTargetGoal.isAlwaysExcluded(bare),
                "媒介種が装備なしのプレイヤーを標的から外した(索敵が壊れている)");
        helper.assertTrue(ParasitizedTargetGoal.isAlwaysExcluded(armored),
                "媒介種が植物側のプレイヤーを標的から外さない");
        helper.succeed();
    }

    /**
     * 標的を選ぶ Goal として<b>許されているクラス</b>。どれも中で
     * {@link Predation} の述語か「殴られた相手」だけを見るので、植物側のプレイヤーを狙わない。
     *
     * <p>新しい Mob がここに無いクラスを {@code targetSelector} に積むと、下のテストが落ちる。
     * そのときは<b>そのクラスが本当に中心の述語を通しているかを読んでから</b>ここへ足す。
     * 「落ちたから足す」をすると柵の意味が無くなる。
     */
    private static final java.util.Set<String> ALLOWED_TARGET_GOALS = java.util.Set.of(
            // 殴られたら反撃する(グループ 2・3 の環境生物がプレイヤーに反応する唯一の口)
            "OvergrownHurtByTargetGoal",
            // selector を取る索敵。どれも Predation:: の述語を渡して使う
            "OvergrownHunterTargetGoal",
            "OvergrownLandTargetGoal",
            "OvergrownWaterborneTargetGoal",
            "VelophyllTargetGoal",
            // バティムネスの水専用(OvergrownHunterTargetGoal を継承、selector は isPrey → isInvaderTarget)
            "WaterTargetGoal",
            // 媒介種(グループ 1)。自分の isExcluded で過繁茂 Mob・汚染シダ・苗床・寄生植物装備の
            // プレイヤーを外す。2026-10-03 にこの柵が「装備のプレイヤーを外していない」のを見つけ、足した
            "ParasitizedTargetGoal");

    /**
     * <b>グループ 1〜10 の全種が、許された Goal だけで標的を選んでいる。</b>
     *
     * <p>これがこのファイルの中でいちばん大事なテスト —— 上の 4 本は「今ある述語が正しい」ことしか見ないが、
     * ここは<b>これから足す Mob を自動で見てくれる</b>。グループ 6〜9 を 11 体足す予定なので、
     * そのどれかが生の {@code NearestAttackableTargetGoal<>(this, Player.class, ...)} を積んだら、
     * テストに種を書き足さなくても落ちる。
     *
     * <p>世界には置かず {@code EntityType#create} で作るだけ({@code registerGoals} は Mob の
     * コンストラクタで走る)。攻城戦ボスは全長 615 ブロックあるので、7×7 のテスト場には置けない。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 200, batch = BATCH)
    public static void everyGroupMobPicksTargetsThroughAllowedGoals(GameTestHelper helper) {
        int checked = 0;
        for (MobGroup group : MobGroup.values()) {
            for (net.minecraft.world.entity.EntityType<?> type : group.members()) {
                net.minecraft.world.entity.Entity created = type.create(helper.getLevel());
                if (!(created instanceof Mob mob)) {
                    if (created != null) {
                        created.discard();
                    }
                    continue;
                }
                checked++;
                for (net.minecraft.world.entity.ai.goal.WrappedGoal wrapped : mob.targetSelector.getAvailableGoals()) {
                    String name = wrapped.getGoal().getClass().getSimpleName();
                    if (!ALLOWED_TARGET_GOALS.contains(name)) {
                        mob.discard();
                        helper.fail(group + " の "
                                + net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(type)
                                + " が許されていない標的 Goal を積んでいる: " + name
                                + "(中心の述語 Predation:: を通しているか読んでから ALLOWED_TARGET_GOALS へ足す)");
                        return;
                    }
                }
                mob.discard();
            }
        }
        // タグが読めていないと members() が空になり、何も確かめずに通ってしまう。
        helper.assertTrue(checked >= 30, "グループの種が " + checked + " 体しか作れなかった(タグが読めていない?)");
        helper.succeed();
    }

    private PlantSidePlayerGameTests() {
    }
}
