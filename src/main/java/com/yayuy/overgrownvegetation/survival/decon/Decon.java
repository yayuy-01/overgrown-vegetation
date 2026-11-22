package com.yayuy.overgrownvegetation.survival.decon;

import com.yayuy.overgrownvegetation.data.DeconSpec;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.item.ProtectiveMaskItem;
import com.yayuy.overgrownvegetation.registry.ModDataComponents;
import com.yayuy.overgrownvegetation.registry.ModDecon;
import com.yayuy.overgrownvegetation.registry.ModItems;
import com.yayuy.overgrownvegetation.registry.ModVent;
import com.yayuy.overgrownvegetation.survival.vent.AirFilters;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import com.yayuy.overgrownvegetation.port.component.OvItemData;

/**
 * 除染の中身(2026-11-14 サバイバルの背骨 段 3a、決定 30・31)。手洗い({@link HandWash})と地脈の洗い場({@link WasherBlockEntity})が同じ式を使う。
 *
 * <ul>
 *     <li><b>素材</b>: 変換表({@link DeconRecipe})にある物 1 つ → 元のバニラの素材 + 胞子。</li>
 *     <li><b>フィルター</b>(フィルターと、フィルターの入った防護マスク): 詰まりを 0 に戻し、洗った回数を 1 増やす(容量は 1 回ごとに
 *     {@code vent.filterWashWear} 減る。段 2c の式 {@link AirFilters})。洗える回数を使い切った物・詰まっていない物は洗わない。</li>
 *     <li><b>装備の汚れ</b>: 段 3b({@link GearDirt})。</li>
 * </ul>
 */
public final class Decon {

    private Decon() {
    }

    public enum Kind {
        /** 洗う物が無い(きれい・洗えない)。 */
        NONE,
        /** 覆われた○○(変換表)。 */
        MATERIAL,
        /** 装備・フィルター(1 つずつ、元の物が洗われて戻る)。 */
        ITEM
    }

    public static DeconSpec spec() {
        return DesignData.sporeField().decon();
    }

    /** 1.20.1: レシピは RecipeHolder に包まれない(1.21 は {@code Optional<RecipeHolder<DeconRecipe>>})。 */
    public static Optional<DeconRecipe> recipe(Level level, ItemStack stack) {
        if (stack.isEmpty()) return Optional.empty();
        return level.getRecipeManager().getRecipeFor(ModDecon.RECIPE_TYPE.get(), new SimpleContainer(stack), level);
    }

    /** フィルターを持つ物(フィルターそのもの・防護マスク)。 */
    public static boolean holdsFilter(ItemStack stack) {
        return AirFilters.isFilter(stack) || com.yayuy.overgrownvegetation.gear.FilterMask.isMask(stack); // 段の頭も(2026-11-17)
    }

    /** フィルターが詰まっていて(少しでも吸っていて)、まだ洗える回数が残っているか。 */
    public static boolean filterWashable(ItemStack stack) {
        return holdsFilter(stack) && AirFilters.used(stack) > 0 && AirFilters.washesLeft(stack) > 0;
    }

    /** フィルターが汚れているのに、洗える回数を使い切っている(手洗いで知らせる)。 */
    public static boolean filterWornOut(ItemStack stack) {
        return holdsFilter(stack) && AirFilters.used(stack) > 0 && AirFilters.washesLeft(stack) <= 0;
    }

    /** {@code stack} が洗えるか・何として洗うか。 */
    public static Kind kind(Level level, ItemStack stack) {
        if (stack.isEmpty()) return Kind.NONE;
        if (recipe(level, stack).isPresent()) return Kind.MATERIAL;
        if (filterWashable(stack) || GearDirt.dirt(stack) > 0) return Kind.ITEM;
        return Kind.NONE;
    }

    /**
     * 1 つ洗う。{@code one} は 1 個に分けた物(呼ぶ側が元の山から {@code split(1)} する)。洗い上がった物(素材なら元の素材と胞子)を返す。
     * 洗えない物はそのまま 1 つだけ返す。{@code dirtStep} = 装備の汚れを落とす量(手洗い 1 回分・洗い場 1 回分。段 3b)。
     */
    public static List<ItemStack> washOne(Level level, ItemStack one, RandomSource random, int dirtStep) {
        List<ItemStack> out = new ArrayList<>(2);
        Optional<DeconRecipe> r = recipe(level, one);
        if (r.isPresent()) {
            DeconRecipe recipe = r.get();
            out.add(recipe.assemble(new SimpleContainer(one), level.registryAccess()));
            int spores = sporeCount(recipe.spores(), random);
            if (spores > 0) out.add(new ItemStack(ModItems.SPORE.get(), spores));
            return out;
        }
        if (filterWashable(one)) {
            washFilter(one);
        }
        GearDirt.wash(one, dirtStep);
        out.add(one);
        return out;
    }

    /** 平均 {@code mean} 個(整数部は必ず、端数は確率)。 */
    public static int sporeCount(float mean, RandomSource random) {
        int whole = (int) Math.floor(mean);
        return whole + (random.nextFloat() < mean - whole ? 1 : 0);
    }

    /** フィルターを洗う: 詰まりを 0 にし、洗った回数を 1 増やす。 */
    public static void washFilter(ItemStack stack) {
        OvItemData.remove(stack, ModDataComponents.MASK_FILTER_USED);
        OvItemData.set(stack, ModVent.FILTER_WASHES, AirFilters.washes(stack) + 1);
    }
}
