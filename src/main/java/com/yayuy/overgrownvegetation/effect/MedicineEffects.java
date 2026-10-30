package com.yayuy.overgrownvegetation.effect;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import com.yayuy.overgrownvegetation.registry.ModMobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;

/**
 * 乳鉢の薬の効き目(2026-10-16、サバイバル用アイテム。効き目の割り当てはユーザー指定、数値は {@code effects.json} の {@code medicine})。
 * <ul>
 *     <li>胞子除け {@code spore_ward}(胞子除けの粉薬): 胞子濃度でかかるまでの 30 秒を {@code sporeWardDwellFactor} 倍に({@link #dwellFactor})。</li>
 *     <li>気付け {@code alertness}(気付け薬): 胞子感染の段階の症状(空腹・見つかりやすさ・防御・攻撃の低下)を治さずに抑える({@link #symptomsSuppressed})。
 *     最大体力の低下は症状ではなく傷なので抑えない。</li>
 *     <li>進行止め {@code stasis}(進行止めの丸薬): 10 分で 1 段の進行を止める({@link #progressPaused})。</li>
 *     <li>段階の守り {@code stage_guard}(暖花の丸薬): 次に段階が上がるときのダメージを 1 回防ぎ、そのとき最大体力も減らさない
 *     ({@link #consumeStageGuard})。使うと消える。減らさなかった分はプレイヤーの {@code spore_health_relief} に数えて、
 *     最大体力の低下から引く(治ると 0 に戻る)。</li>
 * </ul>
 * 胞子止めの霊薬(段階を下げる)は効果ではなく、食べたその場で {@link SporeInfectionEffects#setPlayerStage} する。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class MedicineEffects {

    public static double dwellFactor(Player player) {
        return player.hasEffect(ModMobEffects.SPORE_WARD) ? DesignData.effects().medicineSporeWardDwellFactor() : 1.0D;
    }

    public static boolean symptomsSuppressed(LivingEntity entity) {
        return entity.hasEffect(ModMobEffects.ALERTNESS);
    }

    public static boolean progressPaused(Player player) {
        return player.hasEffect(ModMobEffects.STASIS);
    }

    /** 段階の守りがあれば外して true(段階が上がる処理の側で、ダメージと最大体力の低下を飛ばす)。 */
    public static boolean consumeStageGuard(Player player) {
        if (!player.hasEffect(ModMobEffects.STAGE_GUARD)) {
            return false;
        }
        player.removeEffect(ModMobEffects.STAGE_GUARD);
        player.setData(ModAttachments.SPORE_HEALTH_RELIEF, player.getData(ModAttachments.SPORE_HEALTH_RELIEF) + 1);
        return true;
    }

    /** 最大体力の低下から引く段の数(0 以上、今の段階 − 1 まで)。 */
    public static int healthRelief(Player player, int amplifier) {
        return Math.max(0, Math.min(amplifier, player.getData(ModAttachments.SPORE_HEALTH_RELIEF)));
    }

    // 気付けが付く・外れるときに症状の属性(防御力)を張り直す。

    @SubscribeEvent
    public static void onAdded(MobEffectEvent.Added event) {
        if (event.getEffectInstance().getEffect().is(ModMobEffects.ALERTNESS)) {
            resyncLater(event.getEntity());
        }
    }

    @SubscribeEvent
    public static void onRemoved(MobEffectEvent.Remove event) {
        if (event.getEffect().is(ModMobEffects.ALERTNESS)) {
            resyncLater(event.getEntity());
        }
    }

    @SubscribeEvent
    public static void onExpired(MobEffectEvent.Expired event) {
        if (event.getEffectInstance() != null && event.getEffectInstance().getEffect().is(ModMobEffects.ALERTNESS)) {
            resyncLater(event.getEntity());
        }
    }

    /** 効果の付け外しはイベントの後で反映されるので、次の tick に張り直す。 */
    private static void resyncLater(LivingEntity entity) {
        var server = entity.level().getServer();
        if (server != null) {
            server.tell(new net.minecraft.server.TickTask(server.getTickCount() + 1, () -> SporeInfectionEffects.syncModifiers(entity)));
        }
    }

    private MedicineEffects() {
    }
}
