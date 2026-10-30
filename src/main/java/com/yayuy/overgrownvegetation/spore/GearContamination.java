package com.yayuy.overgrownvegetation.spore;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.EffectTable;
import com.yayuy.overgrownvegetation.registry.ModDataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.item.TridentItem;
import net.neoforged.neoforge.common.ItemAbilities;

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
 * <p>汚染度が {@code steps} の段(既定 25% / 50% / 75% / 100%)に達するごとに性能が落ちる。
 * 落ち方は装備の種類({@link Kind})ごとに違う(2026-10-14 ユーザー指定。前は全部を一律に下げていて、
 * 汚染された盾が攻撃を防がなくなった)。数値は汚染 100% のときの最大で、途中の段は
 * {@link EffectTable.GearContaminationSpec#severityAt} の割合に縮める:
 * <ul>
 *     <li>武器(剣・トライデント・メイス) — 攻撃力 −35%・攻撃速度 −20%・耐久値の減り 3 倍</li>
 *     <li>道具(つるはし・斧・シャベル・クワ) — 採掘速度 −35%・耐久値の減り 3 倍</li>
 *     <li>盾 — 耐久値の減り 2 倍(防ぐ力は落ちない)</li>
 *     <li>弓・クロスボウ — 矢の飛距離 −70%・矢の攻撃力 −35%</li>
 *     <li>防具・その他 — 今までどおり、正の属性修飾を steps の低下率ぶん減らす</li>
 * </ul>
 * 落とすのは {@link GearContaminationEffects}(属性・採掘速度・矢)と
 * {@link com.yayuy.overgrownvegetation.mixin.ItemStackDurabilityMixin}(耐久値)。
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

    /** 汚染の効き方を分ける、装備の種類。 */
    public enum Kind { WEAPON, TOOL, SHIELD, BOW, OTHER }

    /** 装備の種類。盾・弓を先に見る(剣と道具はどちらも TieredItem なので型で分ける)。 */
    public static Kind kindOf(ItemStack stack) {
        Item item = stack.getItem();
        if (item instanceof ShieldItem || stack.canPerformAction(ItemAbilities.SHIELD_BLOCK)) {
            return Kind.SHIELD;
        }
        if (item instanceof ProjectileWeaponItem) {
            return Kind.BOW;
        }
        if (item instanceof SwordItem || item instanceof TridentItem || item instanceof MaceItem
                || stack.is(ItemTags.SWORDS)) {
            return Kind.WEAPON;
        }
        if (item instanceof DiggerItem) {
            return Kind.TOOL;
        }
        return Kind.OTHER;
    }

    /** 今の汚染度での効きの強さ(0〜1。汚染 100% で 1)。 */
    public static double severity(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0.0D;
        }
        float percent = percent(stack);
        return percent <= 0.0F ? 0.0D : spec().severityAt(percent);
    }

    /**
     * 1 回の使用で減る耐久値に掛ける倍率。武器・道具は最大 3 倍、盾は最大 2 倍、それ以外は 1。
     * 途中の段は {@link #severity} で 1 との間を直線に縮める。
     */
    public static double durabilityMultiplier(ItemStack stack) {
        double s = severity(stack);
        if (s <= 0.0D) {
            return 1.0D;
        }
        double max = switch (kindOf(stack)) {
            case WEAPON -> spec().weaponDurabilityMultiplierMax();
            case TOOL -> spec().toolDurabilityMultiplierMax();
            case SHIELD -> spec().shieldDurabilityMultiplierMax();
            default -> 1.0D;
        };
        return Math.max(1.0D, 1.0D + (max - 1.0D) * s);
    }

    /**
     * 耐久値の減りを汚染ぶん増やす。端数は確率で切り上げる(2.3 倍なら 7 割で 2、3 割で 3)。
     */
    public static int scaleDurabilityDamage(ItemStack stack, int amount, RandomSource random) {
        if (amount <= 0) {
            return amount;
        }
        double scaled = amount * durabilityMultiplier(stack);
        int whole = (int) Math.floor(scaled);
        return whole + (random.nextDouble() < scaled - whole ? 1 : 0);
    }

    private GearContamination() {
    }
}
