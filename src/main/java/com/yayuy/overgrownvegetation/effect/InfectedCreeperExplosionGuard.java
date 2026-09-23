package com.yayuy.overgrownvegetation.effect;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.registry.ModMobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Creeper;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * 「胞子感染」({@link ModMobEffects#SPORE_INFECTION})にかかっているクリーパーは爆発しない
 * (ユーザー要望)。感染中のクリーパーはやがて {@link SporeInfectionExpiryHandler} により
 * その場で死亡し、死体ブロックへ変わる。
 *
 * <p>2段構え:
 * <ul>
 *   <li>{@link #onCreeperTick}: 毎 tick 導火線カウンタ({@code swellDir})を強制的に -1 に戻すことで、
 *       プレイヤーに近づいて自爆する通常の {@code SwellGoal} 由来の爆発を根本から止める
 *       (クリーパーは爆発せず生きたまま徘徊を続ける)。</li>
 *   <li>{@link #onExplosionStart}: 火打石と打ち金などで強制着火された場合の保険。着火状態は
 *       {@code swellDir} を上書きし続けるため tick 側だけでは止められないので、実際に爆発が
 *       始まる瞬間({@code ExplosionEvent.Start})を発生源で判定してキャンセルする。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class InfectedCreeperExplosionGuard {

    @SubscribeEvent
    public static void onCreeperTick(EntityTickEvent.Post event) {
        if (event.getEntity() instanceof Creeper creeper
                && !creeper.level().isClientSide()
                && creeper.hasEffect(ModMobEffects.SPORE_INFECTION)
                && creeper.getSwellDir() > 0) {
            creeper.setSwellDir(-1);
        }
    }

    @SubscribeEvent
    public static void onExplosionStart(ExplosionEvent.Start event) {
        Entity source = event.getExplosion().getDirectSourceEntity();
        if (source instanceof Creeper creeper && creeper.hasEffect(ModMobEffects.SPORE_INFECTION)) {
            event.setCanceled(true);
        }
    }

    private InfectedCreeperExplosionGuard() {
    }
}
