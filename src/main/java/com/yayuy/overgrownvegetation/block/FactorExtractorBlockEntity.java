package com.yayuy.overgrownvegetation.block;

import com.yayuy.overgrownvegetation.crafting.FactorExtractionRecipe;
import com.yayuy.overgrownvegetation.menu.FactorExtractorMenu;
import com.yayuy.overgrownvegetation.registry.ModBlockEntities;
import com.yayuy.overgrownvegetation.registry.ModRecipes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * 「因子の抽出設備」の中身(順 1 の着手順 7、2026-10-03)。入れる枠({@link #INPUT})のドロップが
 * {@link FactorExtractionRecipe} の数だけそろっていて、出る枠({@link #OUTPUT})に入るなら、{@code time} tick 進めて
 * 1 回ぶん因子にする。燃料は使わない(時間だけ)。入れるものが替わったら進みは 0 に戻る(かまどと同じ)。
 *
 * <p>ホッパー: 上と横からは入れる枠へ(抽出できるものだけ)、下からは出る枠を抜く。
 */
public class FactorExtractorBlockEntity extends BaseContainerBlockEntity implements WorldlyContainer {

    public static final int INPUT = 0;
    public static final int OUTPUT = 1;
    private static final int[] TOP_AND_SIDES = {INPUT};
    private static final int[] BOTTOM = {OUTPUT};

    public static final int DATA_PROGRESS = 0;
    public static final int DATA_TOTAL = 1;
    public static final int DATA_COUNT = 2;

    private NonNullList<ItemStack> items = NonNullList.withSize(2, ItemStack.EMPTY);
    private int progress;
    private int total;

    private final ContainerData data = new ContainerData() {
        @Override
        public int get(int index) {
            return switch (index) {
                case DATA_PROGRESS -> progress;
                case DATA_TOTAL -> total;
                default -> 0;
            };
        }

        @Override
        public void set(int index, int value) {
            if (index == DATA_PROGRESS) {
                progress = value;
            } else if (index == DATA_TOTAL) {
                total = value;
            }
        }

        @Override
        public int getCount() {
            return DATA_COUNT;
        }
    };

    public FactorExtractorBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.FACTOR_EXTRACTOR.get(), pos, state);
    }

    /** 入れる枠のものに合うレシピ。無ければ null。 */
    @Nullable
    public static RecipeHolder<FactorExtractionRecipe> recipeFor(Level level, ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        return level.getRecipeManager()
                .getRecipeFor(ModRecipes.FACTOR_EXTRACTION_TYPE.get(), new SingleRecipeInput(stack), level).orElse(null);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, FactorExtractorBlockEntity be) {
        if (!(level instanceof ServerLevel)) {
            return;
        }
        boolean working = be.step(level);
        if (state.getValue(FactorExtractorBlock.LIT) != working) {
            level.setBlock(pos, state.setValue(FactorExtractorBlock.LIT, working), 3);
        }
    }

    /** 1 tick 進める。動いていれば true(GameTest からも呼ぶ)。 */
    public boolean step(Level level) {
        ItemStack input = items.get(INPUT);
        RecipeHolder<FactorExtractionRecipe> holder = recipeFor(level, input);
        FactorExtractionRecipe recipe = holder != null ? holder.value() : null;
        boolean working = recipe != null && input.getCount() >= recipe.count() && canOutput(recipe.result());
        if (working) {
            total = recipe.time();
            progress++;
            if (progress >= total) {
                input.shrink(recipe.count());
                ItemStack out = items.get(OUTPUT);
                if (out.isEmpty()) {
                    items.set(OUTPUT, recipe.result().copy());
                } else {
                    out.grow(recipe.result().getCount());
                }
                progress = 0;
            }
            setChanged();
        } else if (progress != 0) {
            progress = 0;
            setChanged();
        }
        if (recipe == null && total != 0) {
            total = 0;
        }
        return working;
    }

    private boolean canOutput(ItemStack result) {
        ItemStack out = items.get(OUTPUT);
        return out.isEmpty() || (ItemStack.isSameItemSameComponents(out, result)
                && out.getCount() + result.getCount() <= out.getMaxStackSize());
    }

    // --- Container ---

    @Override
    protected Component getDefaultName() {
        return Component.translatable("container.overgrown_vegetation.factor_extractor");
    }

    @Override
    protected AbstractContainerMenu createMenu(int containerId, Inventory inventory) {
        return new FactorExtractorMenu(containerId, inventory, this, data);
    }

    @Override
    protected NonNullList<ItemStack> getItems() {
        return items;
    }

    @Override
    protected void setItems(NonNullList<ItemStack> items) {
        this.items = items;
    }

    @Override
    public int getContainerSize() {
        return items.size();
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        ItemStack old = items.get(slot);
        super.setItem(slot, stack);
        if (slot == INPUT && !ItemStack.isSameItemSameComponents(old, stack)) {
            progress = 0;
        }
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return slot == INPUT && (level == null || recipeFor(level, stack) != null);
    }

    @Override
    public int[] getSlotsForFace(Direction side) {
        return side == Direction.DOWN ? BOTTOM : TOP_AND_SIDES;
    }

    @Override
    public boolean canPlaceItemThroughFace(int slot, ItemStack stack, @Nullable Direction direction) {
        return canPlaceItem(slot, stack);
    }

    @Override
    public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction direction) {
        return slot == OUTPUT;
    }

    // --- NBT ---

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        items = NonNullList.withSize(2, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(tag, items, registries);
        progress = tag.getInt("Progress");
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ContainerHelper.saveAllItems(tag, items, registries);
        tag.putInt("Progress", progress);
    }
}
