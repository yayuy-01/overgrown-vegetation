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
 * 「胞子感染」({@link ModMobEffects#SPORE_INFECTION})にかかっている Mob が<b>プレイヤー以外に</b>倒されたときは、
 * 通常のドロップ(アイテム・経験値)を出さない。
 *
 * <p>止めている理由は重さ: 汚染地帯では感染した Mob どうしが勝手に戦って死に続けるので、そのたびに肉・骨・矢が落ちると
 * 落ちたアイテムが溜まって重くなる。死亡地点には {@link SporeInfectionDeathHandler} が死体ブロックや汚染シダを残す。
 *
 * <p><b>プレイヤーが倒したときは落とす</b>(2026-10-10、ユーザー指定)。そのうえで死体・汚染シダへの置き換えも今までどおり起こる
 * ({@link SporeInfectionDeathHandler} はドロップと関係なく動く)。「プレイヤーが倒した」はバニラが経験値と
 * 「プレイヤーが倒したときだけ落ちる物」に使うのと同じ決まり: 直前 5 秒(100 tick)以内にプレイヤー(とその飼い狼など)から
 * 傷を受けていたか。矢で倒した・殴った後に落下で死んだ、も入る。それまでは死因を問わず止めていた。
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
        // isRecentlyHit = 直前 100 tick 以内にプレイヤーから傷を受けた(バニラの lastHurtByPlayerTime > 0)。
        if (infectedNonPlayer(event.getEntity()) && !event.isRecentlyHit()) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onExperienceDrop(LivingExperienceDropEvent event) {
        // 渡されるのはバニラの lastHurtByPlayer(直前 100 tick 以内に傷を付けたプレイヤー。いなければ null)。
        if (infectedNonPlayer(event.getEntity()) && event.getAttackingPlayer() == null) {
            event.setCanceled(true);
        }
    }

    private SporeInfectionDropSuppressor() {
    }
}
