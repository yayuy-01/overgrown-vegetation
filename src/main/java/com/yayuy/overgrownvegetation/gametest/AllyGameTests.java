package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.ally.AllyEntities;
import com.yayuy.overgrownvegetation.ally.AllyItems;
import com.yayuy.overgrownvegetation.ally.SoldierEntity;
import com.yayuy.overgrownvegetation.ally.SoldierKind;
import com.yayuy.overgrownvegetation.base.build.BaseGarrison;
import com.yayuy.overgrownvegetation.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Map;

/**
 * 同盟軍の兵士(2026-10-01、{@code ally/})のテスト。
 *
 * <p>守りたいもの: 兵士が過繁茂の Mob を撃って傷つける / プレイヤーと兵士を狙わない(撃たれても撃ち返さない)/
 * 兵士は過繁茂側の敵の標的になる / 兵士は HK416 を持って現れ、遠くへ行っても消えない / 守備兵の定員がレベルで増える。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class AllyGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(AllyGameTests.class);
        }

        private Registration() {
        }
    }

    /** 歩兵が、5 マス先の動かない寄生された牛を撃って傷つける(テスト場はバリアで囲まれ、隣の場は見えない)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200)
    public static void riflemanShootsOvergrownMob(GameTestHelper helper) {
        SoldierEntity soldier = spawnSoldier(helper, SoldierKind.RIFLEMAN, new BlockPos(1, 2, 3));
        Mob cow = helper.spawn(ModEntities.PARASITIZED_COW.get(), new BlockPos(5, 2, 3));
        cow.setNoAi(true);
        float full = cow.getHealth();
        soldier.setTarget(cow);
        helper.succeedWhen(() -> helper.assertTrue(cow.getHealth() < full || !cow.isAlive(),
                "撃っていない: 牛の体力 " + cow.getHealth() + " / " + full + "・標的 " + soldier.getTarget()));
    }

    /** 金網越しに見通せて撃てる(基地の外周は金網。中の兵士が外の敵を撃てないと困る)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200)
    public static void riflemanShootsThroughChainLink(GameTestHelper helper) {
        for (int y = 2; y <= 4; y++) {
            for (int z = 0; z <= 6; z++) {
                helper.setBlock(new BlockPos(3, y, z), com.yayuy.overgrownvegetation.base.BaseBlocks.CHAIN_LINK_FENCE.get());
            }
        }
        SoldierEntity soldier = spawnSoldier(helper, SoldierKind.RIFLEMAN, new BlockPos(1, 2, 3));
        Mob cow = helper.spawn(ModEntities.PARASITIZED_COW.get(), new BlockPos(5, 2, 3));
        cow.setNoAi(true);
        float full = cow.getHealth();
        helper.assertTrue(soldier.hasLineOfSight(cow), "金網越しに見えない");
        soldier.setTarget(cow);
        helper.succeedWhen(() -> helper.assertTrue(cow.getHealth() < full || !cow.isAlive(),
                "金網越しに撃っていない: 牛の体力 " + cow.getHealth() + " / " + full));
    }

    /** 兵士の標的の判定: 過繁茂の Mob と敵対 Mob は狙い、プレイヤーと兵士は狙わない。標的にしようとしても付かない。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 40)
    public static void soldiersIgnorePlayersAndAllies(GameTestHelper helper) {
        SoldierEntity a = spawnSoldier(helper, SoldierKind.RIFLEMAN, new BlockPos(1, 2, 1));
        SoldierEntity b = spawnSoldier(helper, SoldierKind.ELITE, new BlockPos(5, 2, 5));
        a.setNoAi(true);
        b.setNoAi(true);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        Mob cow = helper.spawn(ModEntities.PARASITIZED_COW.get(), new BlockPos(3, 2, 1));
        Mob husk = helper.spawn(EntityType.HUSK, new BlockPos(3, 2, 5));
        cow.setNoAi(true);
        husk.setNoAi(true);
        helper.assertFalse(SoldierEntity.isFoe(player), "プレイヤーを敵と見た");
        helper.assertFalse(SoldierEntity.isFoe(b), "兵士を敵と見た");
        helper.assertTrue(SoldierEntity.isFoe(cow), "寄生された牛を敵と見ない");
        helper.assertTrue(SoldierEntity.isFoe(husk), "ハスクを敵と見ない");
        a.setTarget(player);
        helper.assertTrue(a.getTarget() == null, "プレイヤーが標的になった");
        a.setTarget(b);
        helper.assertTrue(a.getTarget() == null, "兵士が標的になった");
        // 逆向き: 過繁茂側の捕食種(グループ 3)は兵士を縄張りへの侵入者として狙う(ほかのグループはグループに属さない相手を狙う)
        helper.assertTrue(com.yayuy.overgrownvegetation.entity.Predation.isIntruder(cow, a), "捕食種が兵士を侵入者と見ない");
        helper.succeed();
    }

    /**
     * 汚染シダも狙って除く(ユーザー指定 2026-10-02)。動く敵がいなければシダを自分で標的に選ぶ。
     * テスト場のシダは早く汚染し終えて消える(メモリ「GameTest のテスト場の作り」)ので、撃ち終わりではなく「標的に選んだか」を見る。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200)
    public static void soldierTargetsBlightFern(GameTestHelper helper) {
        SoldierEntity soldier = spawnSoldier(helper, SoldierKind.RIFLEMAN, new BlockPos(1, 2, 3));
        helper.spawn(ModEntities.BLIGHT_FERN.get(), new BlockPos(5, 2, 3));
        helper.spawn(ModEntities.BLIGHT_FERN.get(), new BlockPos(5, 2, 5));
        helper.succeedWhen(() -> helper.assertTrue(soldier.getTarget() instanceof com.yayuy.overgrownvegetation.entity.BlightFernEntity,
                "シダを標的にしない: 標的 " + soldier.getTarget()));
    }

    /** 兵士の弾は盾を素通りする(ユーザー指定 2026-10-02「弾は盾で防げないように」)。飛び道具の扱いでもない。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void bulletBypassesShields(GameTestHelper helper) {
        var types = helper.getLevel().registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.DAMAGE_TYPE);
        var bullet = types.getOrThrow(AllyEntities.BULLET);
        helper.assertTrue(bullet.is(net.minecraft.tags.DamageTypeTags.BYPASSES_SHIELD), "弾が盾を素通りしない");
        helper.assertFalse(bullet.is(net.minecraft.tags.DamageTypeTags.IS_PROJECTILE), "弾が飛び道具の扱い(スクティムネスが弾く)");
        helper.succeed();
    }

    /** 兵士は HK416 を持って現れ(落とさない)、遠くへ離れても消えない。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 40)
    public static void soldierSpawnsArmedAndPersistent(GameTestHelper helper) {
        for (SoldierKind k : SoldierKind.values()) {
            SoldierEntity s = spawnSoldier(helper, k, new BlockPos(3, 2, 3));
            helper.assertTrue(s.getItemBySlot(EquipmentSlot.MAINHAND).is(AllyItems.HK416.get()), k + " が HK416 を持っていない");
            helper.assertTrue(s.isPersistenceRequired(), k + " が遠くで消える");
            helper.assertTrue(s.getMaxHealth() == (float) k.maxHealth, k + " の体力が違う: " + s.getMaxHealth());
            s.discard();
        }
        helper.succeed();
    }

    /** 守備兵の定員はユーザー指定(10 / 25 / 40 / 50 / 60)、砲兵はレベル 3、エリートはレベル 4 から。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void garrisonQuotaGrows(GameTestHelper helper) {
        int[] expected = {0, 10, 25, 40, 50, 60};
        int prev = 0;
        for (int lv = 1; lv <= 5; lv++) {
            Map<SoldierKind, Integer> q = BaseGarrison.quota(lv);
            int total = q.values().stream().mapToInt(Integer::intValue).sum();
            helper.assertTrue(total == expected[lv], "レベル " + lv + " の定員が " + expected[lv] + " でない: " + total);
            helper.assertTrue(total > prev, "レベル " + lv + " で定員が増えない: " + total);
            prev = total;
            helper.assertTrue((q.get(SoldierKind.ARTILLERY) > 0) == (lv >= 3), "砲兵の始まりが違う: L" + lv);
            helper.assertTrue((q.get(SoldierKind.ELITE) > 0) == (lv >= 4), "エリートの始まりが違う: L" + lv);
        }
        helper.succeed();
    }

    static SoldierEntity spawnSoldier(GameTestHelper helper, SoldierKind kind, BlockPos rel) {
        SoldierEntity s = AllyEntities.type(kind).create(helper.getLevel(), null, helper.absolutePos(rel), MobSpawnType.EVENT, false, false);
        if (s == null) {
            throw new IllegalStateException("兵士を作れない: " + kind);
        }
        helper.getLevel().addFreshEntity(s);
        return s;
    }

    private AllyGameTests() {
    }
}
