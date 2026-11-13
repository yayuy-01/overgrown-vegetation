package com.yayuy.overgrownvegetation.survival.vent;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.VentSpec;
import com.yayuy.overgrownvegetation.registry.ModDataComponents;
import com.yayuy.overgrownvegetation.registry.ModVent;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

/**
 * フィルターの状態の読み書き(2026-11-14 段 2c)。<b>マスクと換気の機械で同じフィルター</b>(決定 15)なので、
 * フィルターのアイテム・防護マスク・換気機の中のフィルターがすべてこの式を使う。
 *
 * <ul>
 *     <li>詰まり = 吸い取った量(%秒 = 濃度 × 秒)。{@link ModDataComponents#MASK_FILTER_USED}。</li>
 *     <li>容量 = {@code exposure.maskCapacity}(18000 %秒【仮】)×(1 − 洗った回数 × {@code vent.filterWashWear})。</li>
 *     <li>洗った回数 = {@link ModVent#FILTER_WASHES}(段 3 の洗い場で増やす。洗える回数は {@code vent.filterWashesMax})。</li>
 * </ul>
 */
public final class AirFilters {

    private AirFilters() {
    }

    private static VentSpec spec() {
        return DesignData.sporeField().vent();
    }

    /** 新品 1 枚の容量(%秒)。 */
    public static double baseCapacity() {
        return DesignData.sporeField().maskCapacity();
    }

    public static boolean isFilter(ItemStack stack) {
        return stack.is(ModVent.AIR_FILTER.get());
    }

    /** 吸い取った量(%秒)。 */
    public static int used(ItemStack stack) {
        return stack.getOrDefault(ModDataComponents.MASK_FILTER_USED, 0);
    }

    /** 洗った回数。 */
    public static int washes(ItemStack stack) {
        return stack.getOrDefault(ModVent.FILTER_WASHES, 0);
    }

    /** あと何回洗えるか(段 3 で使う)。 */
    public static int washesLeft(ItemStack stack) {
        return Math.max(0, spec().filterWashesMax() - washes(stack));
    }

    /** この 1 枚の容量(%秒。洗うたびに減る)。 */
    public static double capacity(ItemStack stack) {
        return baseCapacity() * Math.max(0.0D, 1.0D - washes(stack) * spec().filterWashWear());
    }

    /** 残りの吸い取れる量(%秒)。 */
    public static double remaining(ItemStack stack) {
        return Math.max(0.0D, capacity(stack) - used(stack));
    }

    /** 残りの割合 0〜1。 */
    public static double fraction(ItemStack stack) {
        double cap = capacity(stack);
        return cap <= 0.0D ? 0.0D : remaining(stack) / cap;
    }

    /** 詰まって使えないか。 */
    public static boolean clogged(ItemStack stack) {
        return remaining(stack) <= 0.0D;
    }

    /** {@code amount} %秒 吸わせる(容量で止める)。 */
    public static void absorb(ItemStack stack, int amount) {
        if (amount <= 0) return;
        int cap = (int) Math.ceil(capacity(stack));
        stack.set(ModDataComponents.MASK_FILTER_USED, Math.min(cap, used(stack) + amount));
    }

    /** {@code from} のフィルターの状態(詰まり・洗った回数)を {@code to} へ写す(無い欄は消す)。 */
    public static void copyState(ItemStack from, ItemStack to) {
        int u = used(from), w = washes(from);
        if (u > 0) to.set(ModDataComponents.MASK_FILTER_USED, u);
        else to.remove(ModDataComponents.MASK_FILTER_USED);
        if (w > 0) to.set(ModVent.FILTER_WASHES, w);
        else to.remove(ModVent.FILTER_WASHES);
    }

    /** {@code holder}(マスク)に入っているフィルターを、フィルターのアイテム 1 枚として取り出した物。 */
    public static ItemStack asFilterItem(ItemStack holder) {
        ItemStack out = new ItemStack(ModVent.AIR_FILTER.get());
        copyState(holder, out);
        return out;
    }

    // ---- 耐久のバー(フィルター・マスクで共通) ----

    public static boolean barVisible(ItemStack stack) {
        return used(stack) > 0 || washes(stack) > 0;
    }

    public static int barWidth(ItemStack stack) {
        return Math.round(13.0F * (float) fraction(stack));
    }

    public static int barColor(ItemStack stack) {
        return Mth.hsvToRgb((float) fraction(stack) / 3.0F, 1.0F, 1.0F);
    }

    /** 表示用の残り(%、切り上げ)。 */
    public static int percentLeft(ItemStack stack) {
        return (int) Math.ceil(fraction(stack) * 100.0D);
    }
}
