package com.yayuy.overgrownvegetation.gear;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * 防具の胞子への強さ(2026-11-17 強くなる道 段 1・段 2)。段の防具 1 部位ごとに、暴露のたまり方を {@code exposureCutPerPiece} だけ下げる
 * (4 部位で足す。段 1 の遠征服は 4 部位で半分・段 2 の札鎧は 2 割【仮】)。
 *
 * <p>効く所({@code SporeExposureTracker}): 息で浴びる分(未感染の暴露量・感染中の段の進み・G1 の胞子の技)と、閉じた部屋・汚れた装備から染みる分。
 * 傷口から入る分(噛みつき・傷の暴露)は下げない(マスクと同じく防げない、11-15 ユーザー決定に合わせた)。
 * フィルターの残った頭を被っている間は元から 0 なので、効くのはフィルターが切れた後・頭を被っていないとき。
 */
public final class GearSpores {

    private GearSpores() {
    }

    /** 暴露の倍率(0〜1)。段の防具を着ていなければ 1。 */
    public static double exposureFactor(LivingEntity entity) {
        double cut = 0.0D;
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack s = entity.getItemBySlot(slot);
            if (s.getItem() instanceof TierArmor armor && armor.slotMatches(slot)) {
                cut += armor.spec().exposureCutPerPiece;
            }
        }
        return Math.max(0.0D, 1.0D - cut);
    }

    /** 段の防具の印(頭・胴・脚・足の 4 部位とも)。 */
    public interface TierArmor {
        GearTierTable.Armor spec();

        boolean slotMatches(EquipmentSlot slot);
    }
}
