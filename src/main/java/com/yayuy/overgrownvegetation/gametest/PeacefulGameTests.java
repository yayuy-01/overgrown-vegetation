package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.difficulty.Peaceful;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import com.yayuy.overgrownvegetation.raid.RaidController;
import com.yayuy.overgrownvegetation.raid.RaidData;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.ModEntities;
import com.yayuy.overgrownvegetation.siege.SiegeData;
import com.yayuy.overgrownvegetation.td.TdData;
import com.yayuy.overgrownvegetation.td.TdTestAccess;
import com.yayuy.overgrownvegetation.td.TdTicker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * 難易度ピースフル({@link Peaceful}、2026-09-26 のユーザー報告「ピースフルでもレイドやアルボラが湧いてしまう」)。
 *
 * <p><b>難易度はサーバーに 1 つ</b>なので、自分たちだけのバッチ({@value #BATCH} と TD 用の {@value #TD_BATCH})に入れ、バッチの前後で
 * ピースフルにする・元へ戻す({@link BeforeBatch} / {@link AfterBatch}。バッチは順番に走るので、ほかのバッチのテストはピースフルを見ない)。
 * 戻し忘れると後のバッチの敵が片端から消えるので、テストの中では難易度を変えたままにしない(攻城戦のテストだけ、1 tick の中で
 * ノーマルに戻して始めてからピースフルへ戻す)。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class PeacefulGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(PeacefulGameTests.class);
        }

        private Registration() {
        }
    }

    private static final String TEMPLATE = "gametest_empty7";
    private static final String BATCH = "peaceful";
    /**
     * TD のテストだけ別のバッチ: 予告中の TD があると、TdHostiles がディメンション全体で G1〜G3 を消し・湧かせない
     * (td.json の hostileClearRadius 0)ので、同じバッチの「敵が消える」テストが Peaceful ではない理由で通ってしまう。
     */
    private static final String TD_BATCH = "peaceful_td";

    /** バッチの前の難易度(GameTest サーバーはノーマル)。 */
    private static Difficulty before = Difficulty.NORMAL;

    @BeforeBatch(batch = BATCH)
    public static void enterPeaceful(ServerLevel level) {
        before = level.getDifficulty();
        level.getServer().setDifficulty(Difficulty.PEACEFUL, true);
    }

    @AfterBatch(batch = BATCH)
    public static void leavePeaceful(ServerLevel level) {
        level.getServer().setDifficulty(before, true);
    }

    @BeforeBatch(batch = TD_BATCH)
    public static void enterPeacefulForTd(ServerLevel level) {
        enterPeaceful(level);
    }

    @AfterBatch(batch = TD_BATCH)
    public static void leavePeacefulForTd(ServerLevel level) {
        leavePeaceful(level);
    }

    /**
     * 敵の見分け: 報告のアルボラ・レイドビーコンと、プレイヤーを襲う各グループの種は敵。<b>原生生産種(グループ 2)と
     * 原生消費種(グループ 3)の全種</b>・汚染シダ・汚染水草・グループの無い種(巣の節・蔦)・バニラの種は敵ではない
     * (バニラの敵対 Mob はバニラが自分で消す)。
     *
     * <p><b>グループ 3 は 2026-10-03 に敵から外れた</b> —— {@code Predation#isIntruder} がプレイヤーを
     * 返さなくなり(ユーザー指定「G2・G3 は環境生物」)、ピースフルで消す根拠が消えたため。
     * それまでは「コエルロサウラヴスを除いてプレイヤーも襲う」という理由でグループごと敵にしていた(仮決め)。
     */
    @GameTest(template = TEMPLATE, batch = BATCH)
    public static void enemiesAreGroupMobsExceptProducersAndFerns(GameTestHelper helper) {
        List<EntityType<?>> enemies = List.of(ModEntities.ARBORA.get(), ModEntities.OVERGROWN_NEXUS.get(),
                ModEntities.PARASITIZED_COW.get(), ModEntities.FALCIMNES.get(),
                ModEntities.DORGA_ARCHELAUS_TITANICA.get());
        for (EntityType<?> type : enemies) {
            check(helper, Peaceful.isEnemy(type), BuiltInRegistries.ENTITY_TYPE.getKey(type) + " が敵になっていない");
        }
        List<EntityType<?>> others = List.of(ModEntities.BLIGHT_FERN.get(),
                ModEntities.HIVE_NODE.get(), EntityType.COW, EntityType.ZOMBIE);
        for (EntityType<?> type : others) {
            check(helper, !Peaceful.isEnemy(type), BuiltInRegistries.ENTITY_TYPE.getKey(type) + " が敵になっている");
        }
        List<EntityType<?>> producers = MobGroup.GROUP_2.members();
        check(helper, !producers.isEmpty(), "グループ 2 の種が 1 つも無い(タグが読めていない?)");
        for (EntityType<?> type : producers) {
            check(helper, !Peaceful.isEnemy(type), "原生生産種 " + BuiltInRegistries.ENTITY_TYPE.getKey(type) + " が敵になっている");
        }
        List<EntityType<?>> consumers = MobGroup.GROUP_3.members();
        check(helper, !consumers.isEmpty(), "グループ 3 の種が 1 つも無い(タグが読めていない?)");
        for (EntityType<?> type : consumers) {
            check(helper, !Peaceful.isEnemy(type), "原生消費種 " + BuiltInRegistries.ENTITY_TYPE.getKey(type) + " が敵になっている");
        }
        helper.succeed();
    }

    /**
     * いる敵は次の tick に消える(名札・永続でも。{@code helper.spawn} の Mob は永続)。レイドビーコン(レイド)とアルボラが報告の 2 つ、
     * 寄生された牛は {@code Monster} でない媒介種。<b>環境生物(グループ 2 の原生生産種・グループ 3 の原生消費種)は残る</b>。
     *
     * <p>ヒロノムス(グループ 3)は 2026-10-03 まで「プレイヤーを襲う捕食者」として消える側に置いていた。
     * グループ 3 を中立にしたので<b>残る側へ移した</b>。
     */
    @GameTest(template = TEMPLATE, batch = BATCH)
    public static void enemiesVanishProducersStay(GameTestHelper helper) {
        // 床は相対 y = 1。ビーコンは幅 3 なので真ん中に、ほかは隅に置く。
        Mob nexus = helper.spawnWithNoFreeWill(ModEntities.OVERGROWN_NEXUS.get(), new BlockPos(3, 2, 3));
        Mob arbora = helper.spawnWithNoFreeWill(ModEntities.ARBORA.get(), new BlockPos(0, 2, 0));
        Mob cow = helper.spawnWithNoFreeWill(ModEntities.PARASITIZED_COW.get(), new BlockPos(6, 2, 0));
        Mob consumer = helper.spawnWithNoFreeWill(ModEntities.HYLONOMUS.get(), new BlockPos(0, 2, 6));
        Mob producer = helper.spawnWithNoFreeWill(ModEntities.PROTOPHASMA.get(), new BlockPos(6, 2, 6));
        helper.runAfterDelay(3L, () -> {
            for (Mob enemy : List.of(nexus, arbora, cow)) {
                check(helper, enemy.isRemoved(), BuiltInRegistries.ENTITY_TYPE.getKey(enemy.getType())
                        + " がピースフルで消えない");
            }
            check(helper, !producer.isRemoved() && producer.isAlive(), "原生生産種(プロトファスマ)まで消えた");
            check(helper, !consumer.isRemoved() && consumer.isAlive(), "原生消費種(ヒロノムス)まで消えた");
            helper.succeed();
        });
    }

    /** レイドはピースフルでは始まらない(自動の周期・昇格レイドとコマンドが通る口。始まってしまったら止めてから落とす)。 */
    @GameTest(template = TEMPLATE, batch = BATCH)
    public static void raidDoesNotStart(GameTestHelper helper) {
        ServerLevel overworld = helper.getLevel().getServer().overworld();
        if (RaidController.tryStartRaid(overworld, helper.absolutePos(new BlockPos(3, 2, 3)))) {
            RaidData.get(overworld).stopAllRaids(overworld);
            helper.fail("ピースフルでレイドが始まった");
        }
        helper.succeed();
    }

    /**
     * TD はピースフルでは始まらない。予告中の TD はピースフルなら中止になり(帳簿より先。攻め手は敵なので消える)、次の侵攻までの
     * 待ちは止まる(ピースフルの間、終わった時刻からの経過が増えない)。待ちの長さは td.json から引く。
     * ターゲットブロックは本物を置く(無いと帳簿が「予告中にターゲットが無くなった」で同じ中止にする)。自分だけのバッチ({@link #TD_BATCH})。
     */
    @GameTest(template = TEMPLATE, batch = TD_BATCH, timeoutTicks = 40)
    public static void tdIsCalledOffAndItsCooldownHolds(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        CompoundTag snapshot = TdTestAccess.isolate(server);
        BlockPos relTarget = new BlockPos(3, 2, 3);
        BlockPos target = helper.absolutePos(relTarget);
        helper.setBlock(relTarget, ModBlocks.TARGET_BLOCK.get());
        if (TdTicker.forceStart(level, target, 0.0D, null)) {
            TdTestAccess.restore(server, snapshot);
            helper.setBlock(relTarget, Blocks.AIR);
            helper.fail("ピースフルで TD が始まった");
        }
        // 予告の台帳は直に書ける(自動の始まり・コマンドはピースフルで断るので、ピースフルになる前から続いていた予告の代わり)。
        TdTestAccess.startWarning(level, target);
        helper.runAfterDelay(2L, () -> {
            TdData data = TdData.get(server);
            if (data.live() != null || data.lastOutcome() != TdData.Outcome.CANCELLED) {
                TdTestAccess.restore(server, snapshot);
                helper.setBlock(relTarget, Blocks.AIR);
                helper.fail("ピースフルなのに予告中の TD が中止にならない(結果 " + data.lastOutcome() + ")");
            }
            long gap = level.getGameTime() - data.lastEventEndTick();
            helper.runAfterDelay(5L, () -> {
                long later = level.getGameTime() - TdData.get(server).lastEventEndTick();
                TdTestAccess.restore(server, snapshot);
                // ターゲットは片付ける(後のバッチの自動の侵攻に拾われないように。イベントはもう終わっているので負けにはならない)。
                helper.setBlock(relTarget, Blocks.AIR);
                if (DesignData.td().cooldownTicks() > later) {
                    check(helper, later == gap, "ピースフルの間に次の侵攻までの待ちが進んだ(" + gap + " → " + later + " tick)");
                }
                helper.succeed();
            });
        });
    }

    /**
     * 攻城戦はピースフルでは始まらず、進行中ならピースフルになると止まる(ボスは checkDespawn を通らないので、攻城戦の側で止める)。
     * 止まることを見るには始まっていないといけないので、1 tick の中だけノーマルに戻して始める(その間、同じバッチのほかのテストは
     * 動かない)。
     */
    @GameTest(template = TEMPLATE, batch = BATCH)
    public static void siegeDoesNotStartAndStopsInPeaceful(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        ServerLevel overworld = server.overworld();
        SiegeData siege = SiegeData.get(overworld);
        BlockPos at = helper.absolutePos(new BlockPos(3, 2, 3));
        check(helper, !siege.isActive(), "前のテストの攻城戦が残っている");
        if (siege.start(overworld, at)) {
            siege.stop(overworld, false);
            helper.fail("ピースフルで攻城戦が始まった");
        }
        server.setDifficulty(Difficulty.NORMAL, true);
        boolean started = siege.start(overworld, at);
        server.setDifficulty(Difficulty.PEACEFUL, true);
        check(helper, started, "ノーマルで攻城戦を始められなかった");
        helper.runAfterDelay(2L, () -> {
            SiegeData now = SiegeData.get(server.overworld());
            if (now.isActive()) {
                now.stop(server.overworld(), false);
                helper.fail("ピースフルになっても攻城戦が止まらない");
            }
            helper.succeed();
        });
    }

    private static void check(GameTestHelper helper, boolean ok, String message) {
        if (!ok) {
            helper.fail(message);
        }
    }

    private PeacefulGameTests() {
    }
}
