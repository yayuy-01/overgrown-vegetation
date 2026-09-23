package com.yayuy.overgrownvegetation.effect;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.registry.ModMobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.living.LivingExperienceDropEvent;

/**
 * 「胞子感染」({@link ModMobEffects#SPORE_INFECTION})にかかっている Mob が死んだときは、
 * 死因を問わず通常のドロップ(アイテム・経験値)を一切出さない(要望)。
 *
 * <p>感染した Mob の死亡地点には {@link SporeInfectionDeathHandler} が死体ブロックや
 * 汚染シダ・進化型 Mob を残すので、加えてバニラのドロップ(肉・骨・矢・経験値オーブなど)まで
 * 出ると「腐食に取り込まれた」表現と噛み合わない。プレイヤーに倒された場合も含めて抑制する
 * ({@link SporeInfectionDeathHandler} と同じく「感染中の死亡」すべてが対象)。
 *
 * <p>プレイヤー自身のドロップには手を出さない。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class SporeInfectionDropSuppressor {

    private static boolean infectedNonPlayer(LivingEntity entity) {
        return !(entity instanceof Player) && entity.hasEffect(ModMobEffects.SPORE_INFECTION);
    }

    @SubscribeEvent
    public static void onLivingDrops(LivingDropsEvent event) {
        if (infectedNonPlayer(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onExperienceDrop(LivingExperienceDropEvent event) {
        if (infectedNonPlayer(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    private SporeInfectionDropSuppressor() {
    }
}
