package com.yayuy.overgrownvegetation.spore;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModItems;
import net.minecraft.world.item.ItemStack;

/**
 * <b>胞子フィルター</b>の目詰まりと洗浄(2026-09-21 のユーザー指定
 * 「使い切ったフィルターは除染装置で再使用できるように」)。
 *
 * <h2>新しいアイテムを足していない</h2>
 * <p>「目詰まりしたフィルター」を別のアイテムにする手もあったが、<b>同じアイテムのまま</b>にした。
 * 耐久がバニラの仕組みに乗っているので、目詰まりの度合いは<b>耐久バーでそのまま見える</b> ——
 * 別アイテムにすると、モデル・テクスチャ・翻訳・レシピが増えるわりに、
 * プレイヤーが得る情報は増えない。
 *
 * <p>そのぶん<b>使い切っても壊さない</b>。耐久を「残り 1」で止めて、そこを
 * {@link #isSpent 目詰まり}とみなす。除染装置に入れると少しずつ耐久が戻る。
 *
 * <h2>「残り 1」で止める理由</h2>
 * <p>バニラの道具は耐久が尽きると消える。フィルターを消してしまうと洗いようがないので、
 * <b>0 にしない</b>。1 残っているが {@link #isUsable} は false —— 除去装置は動かない。
 */
public final class SporeFilters {

    /** そのアイテムが胞子フィルターか。 */
    public static boolean is(ItemStack stack) {
        return stack.is(ModItems.SPORE_FILTER.get());
    }

    /** 目詰まりしている(これ以上濾せない)か。 */
    public static boolean isSpent(ItemStack stack) {
        return is(stack) && stack.isDamageableItem() && stack.getDamageValue() >= spentAt(stack);
    }

    /** まだ濾せるか。除去装置が動く条件。 */
    public static boolean isUsable(ItemStack stack) {
        return is(stack) && (!stack.isDamageableItem() || stack.getDamageValue() < spentAt(stack));
    }

    /** 洗う余地があるか(除染装置が受け付ける条件)。 */
    public static boolean needsWashing(ItemStack stack) {
        return is(stack) && stack.isDamageableItem() && stack.getDamageValue() > 0;
    }

    /**
     * 使ったぶんだけ目詰まりさせる。<b>壊しはしない</b> —— 残り 1 で止める。
     *
     * @return この呼び出しで目詰まりしきったか(音を鳴らすのに使う)
     */
    public static boolean wear(ItemStack stack, int amount) {
        if (!is(stack) || !stack.isDamageableItem() || amount <= 0) {
            return false;
        }
        int limit = spentAt(stack);
        int before = stack.getDamageValue();
        if (before >= limit) {
            return false;
        }
        stack.setDamageValue(Math.min(limit, before + amount));
        return stack.getDamageValue() >= limit;
    }

    /**
     * 洗って耐久を戻す。
     *
     * @return 洗い上がったか(耐久が 0 に戻った)
     */
    public static boolean wash(ItemStack stack, int amount) {
        if (!needsWashing(stack) || amount <= 0) {
            return false;
        }
        stack.setDamageValue(Math.max(0, stack.getDamageValue() - amount));
        return stack.getDamageValue() <= 0;
    }

    /** 1 回の洗浄サイクルで戻る耐久。 */
    public static int repairPerCycle() {
        return Math.max(1, DesignData.effects().purifier().filterRepairPerCycle());
    }

    /** 目詰まりとみなす耐久値(最大耐久の 1 つ手前)。 */
    private static int spentAt(ItemStack stack) {
        return Math.max(1, stack.getMaxDamage() - 1);
    }

    private SporeFilters() {
    }
}
