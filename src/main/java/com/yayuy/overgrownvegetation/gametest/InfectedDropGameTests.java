package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.ModMobEffects;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 胞子感染した Mob のドロップ(2026-10-10 ユーザー指定): プレイヤーが倒したときだけアイテム・経験値を落とし、
 * どちらの場合も死体(汚染シダ)への置き換えは起こる({@code effect/SporeInfectionDropSuppressor})。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class InfectedDropGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(InfectedDropGameTests.class);
        }

        private Registration() {
        }
    }

    /** プレイヤーが倒した感染牛: 牛肉などと経験値が落ち、牛の死体にもなる。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 100)
    public static void playerKillDropsLootAndStillLeavesCorpse(GameTestHelper helper) {
        Cow cow = infectedCow(helper);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        cow.hurt(helper.getLevel().damageSources().playerAttack(player), 1000.0F);
        helper.runAfterDelay(5, () -> {
            if (helper.getEntities(EntityType.ITEM).isEmpty()) {
                helper.fail("プレイヤーが倒した感染牛がアイテムを落とさない");
                return;
            }
            if (helper.getEntities(EntityType.EXPERIENCE_ORB).isEmpty()) {
                helper.fail("プレイヤーが倒した感染牛が経験値を落とさない");
                return;
            }
            helper.succeedWhen(() -> assertCorpse(helper));
        });
    }

    /** プレイヤー以外の原因で死んだ感染牛: 何も落とさず、牛の死体になる。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 100)
    public static void nonPlayerDeathDropsNothingButLeavesCorpse(GameTestHelper helper) {
        Cow cow = infectedCow(helper);
        cow.hurt(helper.getLevel().damageSources().generic(), 1000.0F);
        helper.runAfterDelay(5, () -> {
            if (!helper.getEntities(EntityType.ITEM).isEmpty() || !helper.getEntities(EntityType.EXPERIENCE_ORB).isEmpty()) {
                helper.fail("プレイヤー以外に倒された感染牛が何か落とした");
                return;
            }
            helper.succeedWhen(() -> {
                assertCorpse(helper);
                if (!helper.getEntities(EntityType.ITEM).isEmpty()) {
                    helper.fail("プレイヤー以外に倒された感染牛が後から何か落とした");
                }
            });
        });
    }

    private static Cow infectedCow(GameTestHelper helper) {
        Cow cow = helper.spawnWithNoFreeWill(EntityType.COW, 3.5F, 2.0F, 3.5F);
        cow.addEffect(new MobEffectInstance(ModMobEffects.SPORE_INFECTION, 20 * 60, 0));
        return cow;
    }

    /** テスト場の床の上に牛の死体があるか(崩れ落ちの終わりに置かれる)。 */
    private static void assertCorpse(GameTestHelper helper) {
        for (int x = 0; x <= 6; x++) {
            for (int z = 0; z <= 6; z++) {
                for (int y = 2; y <= 3; y++) {
                    if (helper.getBlockState(new BlockPos(x, y, z)).is(ModBlocks.OVERGROWN_COW_CORPSE.get())) {
                        return;
                    }
                }
            }
        }
        helper.fail("感染牛が死体にならない");
    }
}
