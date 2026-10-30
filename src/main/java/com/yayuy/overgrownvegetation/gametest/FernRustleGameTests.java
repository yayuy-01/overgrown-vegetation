package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.effect.FernRustle;
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

}
