package com.yayuy.overgrownvegetation.spore;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.EffectTable;
import com.yayuy.overgrownvegetation.registry.ModDataComponents;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.item.TridentItem;

/**
 * 「装備の胞子汚染」— 武器・道具・防具が、胞子濃度({@link SporeConcentration})の高い場所に
 * 持ち込まれている間に少しずつ胞子に侵され、性能が落ちていく仕組み。
 *
 * <h2>進み方</h2>
 * <p>汚染度はアイテムごとに 0〜100(%)で、{@link ModDataComponents#SPORE_CONTAMINATION} に載る。
 * 胞子濃度が {@code effects.json} の {@code gearContamination.minConcentrationPercent}(既定 15%)以上の
 * 場所でだけ進み、下限ちょうどなら {@code ticksToFullAtMin}(既定 1 時間)、濃度 100% なら
 * {@code ticksToFullAtMax}(既定 10 分)で 100% に届く(間は満了時間を直線補間)。
 * 実際に進めるのは {@link GearContaminationTracker}。
 *
 * <h2>効き方</h2>
 * <p>汚染度が {@code steps} の段(既定 25% で −5%、50% で −25%、75% で −45%、100% で −65%)に達するごとに
 * 性能が落ちる。落ちるのは {@link GearContaminationEffects} が担当:
 * <ul>
 *     <li>属性修飾(攻撃力・防御力・防具強度など、正の加算値すべて)</li>
 *     <li>採掘速度</li>
 *     <li>盾で防げるダメージ量</li>
 *     <li>弓・クロスボウ・トライデントで撃った矢のダメージ</li>
 * </ul>
 *
 * <h2>戻し方</h2>
 * <p>除染機({@link com.yayuy.overgrownvegetation.block.DecontaminatorBlock})に入れておくと、
 * {@code decontaminationTicksPerPercent}(既定 40 tick = 2 秒)ごとに 1% ずつ落ちる。
 */
public final class GearContamination {

    /** これ未満の汚染度はコンポーネントごと外す(浮動小数の残りかすでスタックが分かれないように)。 */
    private static final float EPSILON = 0.001F;

    public static EffectTable.GearContaminationSpec spec() {
        return DesignData.effects().gearContamination();
    }

    /**
     * このアイテムが胞子汚染を受けるか。既定は「道具・武器・防具の類」:
     * 素材つきの道具({@link TieredItem} — 剣・斧・ツルハシ等)、防具、盾、飛び道具(弓・クロスボウ)、
     * トライデント、メイス。データパックの {@link ModTags#SPORE_CONTAMINABLE} で追加、
     * {@link ModTags#SPORE_CONTAMINATION_IMMUNE} で除外できる。
     */
    public static boolean isContaminable(ItemStack stack) {
        if (stack.getItem() instanceof com.yayuy.overgrownvegetation.item.PlantArmorItem) {
            return false; // 寄生植物装備そのものは胞子汚染しない(仕様 2026-09-18)。
        }
        if (stack.isEmpty() || stack.is(ModTags.SPORE_CONTAMINATION_IMMUNE)) {
            return false;
        }
        if (stack.is(ModTags.SPORE_CONTAMINABLE)) {
            return true;
        }
        return stack.getItem() instanceof TieredItem
                || stack.getItem() instanceof ArmorItem
                || stack.getItem() instanceof ShieldItem
                || stack.getItem() instanceof ProjectileWeaponItem
                || stack.getItem() instanceof TridentItem
                || stack.getItem() instanceof MaceItem;
    }

    /** 現在の汚染度(0〜100 の %)。コンポーネントが無ければ 0。 */
    public static float percent(ItemStack stack) {
        Float v = stack.get(ModDataComponents.SPORE_CONTAMINATION.get());
        return v == null ? 0.0F : Math.max(0.0F, Math.min(100.0F, v));
    }

    /** 汚染度を書き込む(0〜100 に丸める。0 ならコンポーネントごと外す)。 */
    public static void setPercent(ItemStack stack, float percent) {
        float clamped = Math.max(0.0F, Math.min(100.0F, percent));
        if (clamped <= EPSILON) {
            stack.remove(ModDataComponents.SPORE_CONTAMINATION.get());
        } else {
            stack.set(ModDataComponents.SPORE_CONTAMINATION.get(), clamped);
        }
    }

    /**
     * 汚染度を {@code delta}(%)だけ動かす(負なら除染)。
     *
     * @return 実際に値が変わったら true
     */
    public static boolean add(ItemStack stack, double delta) {
        float before = percent(stack);
        float after = (float) Math.max(0.0D, Math.min(100.0D, before + delta));
        if (Math.abs(after - before) <= EPSILON) {
            return false;
        }
        setPercent(stack, after);
        return true;
    }

    /**
     * このアイテムが鍛冶台で樹液結晶を打ち込まれているか
     * ({@link com.yayuy.overgrownvegetation.crafting.SapCrystalTreatmentRecipe})。
     */
    public static boolean isSapCrystalTreated(ItemStack stack) {
        return Boolean.TRUE.equals(stack.get(ModDataComponents.SAP_CRYSTAL_TREATED.get()));
    }

    /** このアイテムに「樹液結晶で処理した」印を付ける。 */
    public static void setSapCrystalTreated(ItemStack stack) {
        stack.set(ModDataComponents.SAP_CRYSTAL_TREATED.get(), Boolean.TRUE);
    }

    /**
     * 汚染の進む速さに掛ける倍率。樹液結晶で処理済みなら {@code sapCrystalRateFactor}(既定 0.1)、
     * そうでなければ 1。<b>除染には掛からない</b>(掛けるのは進む側だけ)。
     */
    public static double rateFactor(ItemStack stack) {
        return isSapCrystalTreated(stack) ? Math.max(0.0D, spec().sapCrystalRateFactor()) : 1.0D;
    }

    /**
     * 汚染を {@code gain}(%)ぶん進める。{@link #rateFactor} を掛けてから足すので、
     * 樹液結晶で処理した装備は遅くなる。
     *
     * @return 実際に値が変わったら true
     */
    public static boolean contaminate(ItemStack stack, double gain) {
        double scaled = gain * rateFactor(stack);
        return scaled > 0.0D && add(stack, scaled);
    }

    /** このアイテムの今の性能低下率(0〜1)。0 なら影響なし。 */
    public static double reduction(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0.0D;
        }
        float percent = percent(stack);
        if (percent <= 0.0F) {
            return 0.0D;
        }
        return spec().reductionAt(percent);
    }

    /** 性能に掛ける倍率(1 − 低下率)。 */
    public static double factor(ItemStack stack) {
        return 1.0D - reduction(stack);
    }

    /** 性能に掛ける倍率(float 版)。 */
    public static float factorF(ItemStack stack) {
        return (float) factor(stack);
    }

    private GearContamination() {
    }
}
