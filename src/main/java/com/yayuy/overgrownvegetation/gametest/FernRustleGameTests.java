package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.effect.FernRustle;
import com.yayuy.overgrownvegetation.effect.SporeInfectionDetection;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * <b>忍び足</b>({@link FernRustle}。2026-10-16 ユーザー指定「シダの茂みを歩いて抜けると胞子がばら撒かれる」「忍び足の意味」)のテスト。
 *
 * <p>シダの茂みを歩くと被検知距離が +{@link FernRustle#WALK_BONUS}、走ると +{@link FernRustle#SPRINT_BONUS} になり、
 * 過繁茂の Mob(グループに属する)からの被検知距離に足される。しゃがむと付かない。茂みの外を歩いても付かない。
 * 偽プレイヤーは tick しないので {@link FernRustle#update} を直に呼ぶ。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class FernRustleGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(FernRustleGameTests.class);
        }

        private Registration() {
        }
    }

    @GameTest(template = "gametest_empty7", timeoutTicks = 100)
    public static void walkingThroughFernsIsHeardSneakingIsNot(GameTestHelper helper) {
        // 床(相対 y=1)の 2 マスを過繁茂の草にして、その上(y=2)にゼンマイ。
        for (int x = 2; x <= 3; x++) {
            helper.setBlock(new BlockPos(x, 1, 3), ModBlocks.OVERGROWN_GRASS_BLOCK.get());
            helper.setBlock(new BlockPos(x, 2, 3), ModBlocks.OVERGROWN_ZENMAI.get());
        }
        if (!helper.getLevel().getBlockState(helper.absolutePos(new BlockPos(3, 2, 3))).is(ModBlocks.OVERGROWN_ZENMAI.get())) {
            helper.fail("ゼンマイが置けなかった(草の上に立たない)");
            return;
        }
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        FernRustle.clear(player);
        Vec3 a = helper.absoluteVec(new Vec3(2.5, 2.0, 3.5));
        Vec3 b = helper.absoluteVec(new Vec3(3.5, 2.0, 3.5));
        Vec3 out = helper.absoluteVec(new Vec3(5.5, 2.0, 5.5));
        Vec3 out2 = helper.absoluteVec(new Vec3(5.5, 2.0, 4.5));

        // しゃがんで抜けても付かない。
        player.setPose(Pose.CROUCHING);
        player.setPos(a);
        FernRustle.update(player);
        player.setPos(b);
        FernRustle.update(player);
        if (FernRustle.bonusFor(player) != 0.0D) {
            helper.fail("しゃがんでいるのに葉擦れが付いた: " + FernRustle.bonusFor(player));
            return;
        }
        // 茂みの外を歩いても付かない。
        player.setPose(Pose.STANDING);
        player.setPos(out);
        FernRustle.update(player);
        player.setPos(out2);
        FernRustle.update(player);
        if (FernRustle.bonusFor(player) != 0.0D) {
            helper.fail("茂みの外で葉擦れが付いた: " + FernRustle.bonusFor(player));
            return;
        }
        // 歩いて抜けると +WALK。
        player.setPos(a);
        FernRustle.update(player);
        player.setPos(b);
        FernRustle.update(player);
        if (FernRustle.bonusFor(player) != FernRustle.WALK_BONUS) {
            helper.fail("歩いて抜けたのに +" + FernRustle.WALK_BONUS + " でない: " + FernRustle.bonusFor(player));
            return;
        }
        // 走ると +SPRINT。
        player.setSprinting(true);
        player.setPos(a);
        FernRustle.update(player);
        if (FernRustle.bonusFor(player) != FernRustle.SPRINT_BONUS) {
            helper.fail("走って抜けたのに +" + FernRustle.SPRINT_BONUS + " でない: " + FernRustle.bonusFor(player));
            return;
        }
        // 過繁茂の Mob からの被検知距離に足される(バニラの Mob には足されない)。
        Mob seeker = helper.spawnWithNoFreeWill(ModEntities.PARASITIZED_COW.get(), new BlockPos(5, 2, 1));
        seeker.setNoAi(true);
        Mob vanilla = helper.spawnWithNoFreeWill(net.minecraft.world.entity.EntityType.HUSK, new BlockPos(1, 2, 5));
        vanilla.setNoAi(true);
        double fromGroup = SporeInfectionDetection.bonusFor(seeker, player);
        double fromVanilla = SporeInfectionDetection.bonusFor(vanilla, player);
        FernRustle.clear(player);
        seeker.discard();
        vanilla.discard();
        if (fromGroup < FernRustle.SPRINT_BONUS || fromVanilla >= FernRustle.SPRINT_BONUS) {
            helper.fail("被検知距離への足し方が違う: 過繁茂の Mob から " + fromGroup + " / バニラの Mob から " + fromVanilla);
            return;
        }
        helper.succeed();
    }
}
