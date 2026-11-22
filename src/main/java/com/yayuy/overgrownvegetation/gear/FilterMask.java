package com.yayuy.overgrownvegetation.gear;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * <b>フィルターを使う頭</b>の印(2026-11-17 強くなる道 段 1・段 2)。防護マスクと、各段の防具の頭(決定 11「頭はマスクと一体で、フィルターを使う」)。
 * フィルターの残り・入れ替え・除染・汚れの扱いは、この印のある物すべてで防護マスクと同じ({@code ProtectiveMaskItem} の静的な口・{@code AirFilters})。
 *
 * <p>マスクとの関係【仮・全体管理へ 11-17 に送った案】: 防護マスクは序盤の最初の 1 枚として残し、段の頭はその上位(防御があり、フィルターの減りが遅い)。
 * 案が変わったときはこの印 1 つを直せば済むよう、判定はここに集める。
 */
public interface FilterMask {

    /** フィルターの減りの倍率(1 = 防護マスクと同じ)。 */
    double filterWear();

    /** {@code stack} がフィルターを使う頭か。 */
    static boolean isMask(ItemStack stack) {
        return stack.getItem() instanceof FilterMask
                || com.yayuy.overgrownvegetation.gear.attach.ArmorAttachments.hasMouthCloth(stack); // 口覆いの布の付いた頭の防具も(2026-11-21 夜)
    }

    /** {@code entity} がフィルターを使う頭を被っているか(残りを問わない)。 */
    static boolean isWearing(LivingEntity entity) {
        return isMask(entity.getItemBySlot(EquipmentSlot.HEAD));
    }

    /** {@code stack} のフィルターの減りの倍率。フィルターを使う頭でなければ 1。 */
    static double wearOf(ItemStack stack) {
        if (stack.getItem() instanceof FilterMask m) return Math.max(0.0D, m.filterWear());
        return com.yayuy.overgrownvegetation.gear.attach.ArmorAttachments.hasMouthCloth(stack)
                ? com.yayuy.overgrownvegetation.gear.attach.ArmorAttachments.mouthClothWear() : 1.0D;
    }
}
