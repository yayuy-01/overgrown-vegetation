package com.yayuy.overgrownvegetation.spore;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.EffectTable;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * プレイヤーが胞子濃度({@link SporeConcentration})の高い場所に居る間、持っている武器・道具・防具の
 * 「胞子汚染」({@link GearContamination})を進める。
 *
 * <p>負荷対策として {@code effects.json} の {@code gearContamination.checkIntervalTicks}(既定 20)tick に
 * 1 回だけ判定し、その 1 回で間隔ぶんまとめて進める。胞子濃度そのものはチャンク単位でキャッシュされている
 * ({@link SporeConcentration})ので、判定 1 回の実コストはインベントリの走査だけ。
 *
 * <p>鍛冶台で樹液結晶を打ち込んだ装備は、{@link GearContamination#contaminate} の中で
 * {@code gearContamination.sapCrystalRateFactor}(既定 0.1 = 10 分の 1)倍に落とされる。
 *
 * <p>クリエイティブ/スペクテイターのプレイヤーは対象外。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class GearContaminationTracker {

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!(player.level() instanceof ServerLevel level) || !player.isAlive()) {
            return;
        }
        if (player.isSpectator() || player.isCreative()) {
            return;
        }

        EffectTable.GearContaminationSpec spec = GearContamination.spec();
        if (!spec.enabled()) {
            return;
        }
        int interval = Math.max(1, spec.checkIntervalTicks());
        if (Math.floorMod(player.tickCount + player.getId(), interval) != 0) {
            return;
        }

        double concentration = SporeConcentration.percentAt(level, player.blockPosition());
        double gain = spec.gainPerTick(concentration) * interval
                * Erosion.gearContaminationFactor(player);
        if (gain <= 0.0D) {
            return;
        }

        if (spec.wholeInventory()) {
            contaminateInventory(player, gain);
        } else {
            contaminateEquipped(player, gain);
        }
    }

    /** 持ち物すべて(メイン 36 + 防具 4 + オフハンド)。 */
    private static void contaminateInventory(ServerPlayer player, double gain) {
        contaminateAll(player.getInventory().items, gain);
        contaminateAll(player.getInventory().armor, gain);
        contaminateAll(player.getInventory().offhand, gain);
    }

    /** 手に持っている物と身に着けている防具だけ。 */
    private static void contaminateEquipped(ServerPlayer player, double gain) {
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack stack = player.getItemBySlot(slot);
            if (GearContamination.isContaminable(stack)) {
                GearContamination.contaminate(stack, gain);
            }
        }
    }

    private static void contaminateAll(Iterable<ItemStack> stacks, double gain) {
        for (ItemStack stack : stacks) {
            if (GearContamination.isContaminable(stack)) {
                GearContamination.contaminate(stack, gain);
            }
        }
    }

    private GearContaminationTracker() {
    }
}
