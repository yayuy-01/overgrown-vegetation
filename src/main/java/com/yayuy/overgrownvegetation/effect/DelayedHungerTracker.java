package com.yayuy.overgrownvegetation.effect;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import com.yayuy.overgrownvegetation.registry.ModItems;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * 「大きな未発達な果実」({@link ModItems#LARGE_UNDEVELOPED_FRUIT_ITEM})を食べたプレイヤーに、
 * <b>しばらく経ってから</b>バニラの「空腹」を届ける。
 *
 * <p>食べた瞬間に {@link FruitEffects} が {@link #schedule} を呼んで残り tick を書き込み、
 * ここが毎 tick 1 ずつ減らして 0 になった瞬間に効果を付ける。残り tick は
 * {@link ModAttachments#DELAYED_HUNGER} に入っていてワールドセーブに残るので、
 * <b>ログアウトして戻ってきても予約は生きている</b>(ただしオフラインの間は進まない ——
 * 6 分は「ゲームを 6 分続けたら」という意味になる)。
 *
 * <h2>重ねて食べた場合</h2>
 * 予約は 1 つだけで、後から食べたぶんで<b>上書き</b>される(タイマーが再び 6 分に戻る)。
 * 連続で食べれば空腹を先送りできるが、そのぶん胞子感染は進む —— 逃げ切れない作りにしてある。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class DelayedHungerTracker {

    /** {@code delayTicks} 後に空腹が来るよう予約する(0 以下なら予約しない)。 */
    public static void schedule(Player player, int delayTicks) {
        if (delayTicks <= 0) {
            return;
        }
        player.setData(ModAttachments.DELAYED_HUNGER, delayTicks);
    }

    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Player player) || player.level().isClientSide()) {
            return;
        }
        int remaining = player.getData(ModAttachments.DELAYED_HUNGER);
        if (remaining <= 0) {
            return;
        }
        remaining--;
        if (remaining > 0) {
            player.setData(ModAttachments.DELAYED_HUNGER, remaining);
            return;
        }
        player.setData(ModAttachments.DELAYED_HUNGER, 0);

        int ticks = DesignData.effects().largeFruitHungerTicks();
        if (ticks > 0) {
            player.addEffect(new MobEffectInstance(MobEffects.HUNGER, ticks,
                    DesignData.effects().largeFruitHungerAmplifier(), false, true, true));
        }
    }

    private DelayedHungerTracker() {
    }
}
