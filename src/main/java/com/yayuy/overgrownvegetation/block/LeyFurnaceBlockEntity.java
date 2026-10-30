package com.yayuy.overgrownvegetation.block;

import com.yayuy.overgrownvegetation.defense.MachineBattery;
import com.yayuy.overgrownvegetation.item.BatteryItem;
import com.yayuy.overgrownvegetation.menu.LeyFurnaceMenu;
import com.yayuy.overgrownvegetation.registry.ModBlockEntities;
import com.yayuy.overgrownvegetation.registry.ModItems;
import com.yayuy.overgrownvegetation.research.ResearchLogic;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.Optional;

/**
 * 地脈炉の中身(2026-10-22、地脈技術の 2 段目。ユーザー指定「かまど・溶鉱炉・燻製器の 3 つとして認識される・速さは 2 倍・燃料は地脈電池だけ」)。
 *
 * <ul>
 *     <li>枠はバニラのかまどと同じ 3 つ(0 = 焼く物、1 = 地脈電池、2 = 出来た物)。窓もバニラのかまど({@link LeyFurnaceMenu})。</li>
 *     <li>焼けるのは燻製・溶鉱・精錬の 3 種のレシピ。同じ品に複数あるときは <b>燻製 &gt; 溶鉱 &gt; 精錬</b>(ユーザー指定)。
 *     焼く時間は元のレシピの半分(最低 1 tick)、出てくる経験値はそのレシピのもの(バニラの「使ったレシピ」の記録をそのまま使う。
 *     {@link AbstractFurnaceBlockEntity} を継いでいるので、出来た物の枠から取ったときにバニラが経験値を出す)。</li>
 *     <li>電池は 1 の枠の品そのもの({@link MachineBattery.Holder} もこの枠を読み書きする。電池を持って右クリックで入れ替えても、窓から入れても同じ)。
 *     焼いている間だけ 1 tick に 1 減る。空になると空の地脈電池に替わる。</li>
 *     <li>研究で封印されたレシピは焼かない(バニラのかまどは {@code mixin/AbstractFurnaceBlockEntityMixin} で止めているが、この炉は自分で tick を回すので自分で見る)。</li>
 * </ul>
 * バニラのかまどの tick({@link AbstractFurnaceBlockEntity#serverTick})は使わない(1 種類のレシピしか見ないため)。親の燃える時間の欄も使わない。
 */
public class LeyFurnaceBlockEntity extends AbstractFurnaceBlockEntity implements MachineBattery.Holder {

    /** 焼いている間の 1 tick の電気。 */
    public static final int DRAIN_PER_TICK = 1;
    /** 元のレシピの何分の 1 の時間で焼くか(ユーザー指定 2 倍速)。 */
    public static final int SPEED = 2;

    private final RecipeManager.CachedCheck<SingleRecipeInput, ? extends AbstractCookingRecipe> smokingCheck =
            RecipeManager.createCheck(RecipeType.SMOKING);
    private final RecipeManager.CachedCheck<SingleRecipeInput, ? extends AbstractCookingRecipe> blastingCheck =
            RecipeManager.createCheck(RecipeType.BLASTING);
    private final RecipeManager.CachedCheck<SingleRecipeInput, ? extends AbstractCookingRecipe> smeltingCheck =
            RecipeManager.createCheck(RecipeType.SMELTING);

    private int progress;
    private int total;

    /** 窓へ送る値(バニラのかまどの 4 つ。燃える時間の欄は電池の残り(秒)と満タン(秒) —— 送る値は short なので tick のままだと溢れる)。 */
    private final ContainerData menuData = new ContainerData() {
        @Override
        public int get(int index) {
            return switch (index) {
                case 0 -> (batteryCharge() + 19) / 20;
                case 1 -> BatteryItem.CAPACITY / 20;
                case 2 -> progress;
                case 3 -> total;
                default -> 0;
            };
        }

        @Override
        public void set(int index, int value) {
            if (index == 2) {
                progress = value;
            } else if (index == 3) {
                total = value;
            }
        }

        @Override
        public int getCount() {
            return 4;
        }
    };

    public LeyFurnaceBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.LEY_FURNACE.get(), pos, state, RecipeType.SMELTING);
    }

    // ------------------------------------------------------------------------------------------------
    // レシピ
    // ------------------------------------------------------------------------------------------------

    /** {@code stack} を焼くレシピ(燻製 → 溶鉱 → 精錬の順に探す)。無ければ空。 */
    public Optional<? extends RecipeHolder<? extends AbstractCookingRecipe>> recipeFor(Level level, ItemStack stack) {
        if (stack.isEmpty()) {
            return Optional.empty();
        }
        SingleRecipeInput input = new SingleRecipeInput(stack);
        Optional<? extends RecipeHolder<? extends AbstractCookingRecipe>> r = smokingCheck.getRecipeFor(input, level);
        if (r.isEmpty()) {
            r = blastingCheck.getRecipeFor(input, level);
        }
        if (r.isEmpty()) {
            r = smeltingCheck.getRecipeFor(input, level);
        }
        return r;
    }

    /** 3 種のどれかのレシピで焼けるか(窓のシフトクリック・ホッパー用。どのサイドからも呼べる)。 */
    public static boolean canCook(Level level, ItemStack stack) {
        SingleRecipeInput input = new SingleRecipeInput(stack);
        RecipeManager rm = level.getRecipeManager();
        return rm.getRecipeFor(RecipeType.SMOKING, input, level).isPresent()
                || rm.getRecipeFor(RecipeType.BLASTING, input, level).isPresent()
                || rm.getRecipeFor(RecipeType.SMELTING, input, level).isPresent();
    }

    /** レシピの焼く時間をこの炉の速さに直したもの。 */
    public static int cookTime(AbstractCookingRecipe recipe) {
        return Math.max(1, recipe.getCookingTime() / SPEED);
    }

    // ------------------------------------------------------------------------------------------------
    // 毎 tick
    // ------------------------------------------------------------------------------------------------

    public static void serverTick(Level level, BlockPos pos, BlockState state, LeyFurnaceBlockEntity be) {
        ItemStack input = be.items.get(0);
        RecipeHolder<? extends AbstractCookingRecipe> recipe = be.recipeFor(level, input).orElse(null);
        boolean working = false;
        if (recipe != null && be.canBurn(level, recipe) && MachineBattery.hasEnergy(be)) {
            working = true;
            be.total = cookTime(recipe.value());
            be.progress++;
            MachineBattery.drain(be, DRAIN_PER_TICK);
            if (be.progress >= be.total) {
                be.progress = 0;
                be.burn(level, recipe);
            }
        } else if (be.progress > 0) {
            // 止まったら少しずつ冷める(バニラと同じ 2 ずつ)。焼く物が無くなったら 0 に。
            be.progress = recipe == null ? 0 : Math.max(0, be.progress - 2);
            be.total = recipe == null ? 0 : cookTime(recipe.value());
        } else {
            be.total = recipe == null ? 0 : cookTime(recipe.value());
        }
        if (state.getValue(AbstractFurnaceBlock.LIT) != working) {
            level.setBlock(pos, state.setValue(AbstractFurnaceBlock.LIT, working), 3);
        }
        if (working) {
            be.setChanged();
        }
    }

    private boolean canBurn(Level level, RecipeHolder<? extends AbstractCookingRecipe> recipe) {
        MinecraftServer server = level.getServer();
        if (server != null && ResearchLogic.isRecipeLockedForAll(server, recipe.id())) {
            return false;
        }
        ItemStack result = recipe.value().assemble(new SingleRecipeInput(items.get(0)), level.registryAccess());
        if (result.isEmpty()) {
            return false;
        }
        ItemStack out = items.get(2);
        if (out.isEmpty()) {
            return true;
        }
        if (!ItemStack.isSameItemSameComponents(out, result)) {
            return false;
        }
        int sum = out.getCount() + result.getCount();
        return sum <= getMaxStackSize() && sum <= out.getMaxStackSize();
    }

    private void burn(Level level, RecipeHolder<? extends AbstractCookingRecipe> recipe) {
        ItemStack input = items.get(0);
        ItemStack result = recipe.value().assemble(new SingleRecipeInput(input), level.registryAccess());
        ItemStack out = items.get(2);
        if (out.isEmpty()) {
            items.set(2, result.copy());
        } else {
            out.grow(result.getCount());
        }
        input.shrink(1);
        setRecipeUsed(recipe);
    }

    // ------------------------------------------------------------------------------------------------
    // 枠
    // ------------------------------------------------------------------------------------------------

    @Override
    public void setItem(int index, ItemStack stack) {
        ItemStack before = items.get(index);
        boolean same = !stack.isEmpty() && ItemStack.isSameItemSameComponents(before, stack);
        items.set(index, stack);
        stack.limitSize(getMaxStackSize(stack));
        if (index == 0 && !same) {
            progress = 0;
            total = level == null ? 0 : recipeFor(level, stack).map(r -> cookTime(r.value())).orElse(0);
            setChanged();
        }
    }

    @Override
    public boolean canPlaceItem(int index, ItemStack stack) {
        return switch (index) {
            case 0 -> true;
            case 1 -> stack.is(ModItems.BATTERY.get());
            default -> false;
        };
    }

    /** 下からは出来た物と、使い切った空の電池だけ抜ける。 */
    @Override
    public boolean canTakeItemThroughFace(int index, ItemStack stack, Direction direction) {
        return direction != Direction.DOWN || index != 1 || stack.is(ModItems.EMPTY_BATTERY.get());
    }

    @Override
    protected int getBurnDuration(ItemStack fuel) {
        return 0;
    }

    @Override
    protected Component getDefaultName() {
        return Component.translatable("container.overgrown_vegetation.ley_furnace");
    }

    @Override
    protected AbstractContainerMenu createMenu(int id, Inventory inventory) {
        return new LeyFurnaceMenu(id, inventory, this, menuData);
    }

    // ------------------------------------------------------------------------------------------------
    // 電池(1 の枠の品そのもの)
    // ------------------------------------------------------------------------------------------------

    @Override
    public int batteryCharge() {
        return BatteryItem.charge(items.get(1).is(ModItems.BATTERY.get()) ? items.get(1) : ItemStack.EMPTY);
    }

    @Override
    public void setBatteryCharge(int charge) {
        items.set(1, BatteryItem.stackFor(charge));
    }

    @Override
    public boolean hasBattery() {
        return items.get(1).is(ModItems.BATTERY.get());
    }

    /** 燃料枠に電池以外(バケツなど)があるときは、電池を持って右クリックしても入れ替えない(その品が消えていた。2026-10-22 直し)。 */
    @Override
    public boolean canSwapBattery() {
        return items.get(1).isEmpty() || hasBattery();
    }

    @Override
    public void setHasBattery(boolean has) {
        if (!has) {
            items.set(1, ItemStack.EMPTY);
        }
    }

    @Override
    public void batteryChanged() {
        setChanged();
    }

    /** 焼いている途中の tick(GameTest 用)。 */
    public int progress() {
        return progress;
    }

    /** 今の品を焼き上げるまでの tick(GameTest 用)。 */
    public int totalTime() {
        return total;
    }

    // ------------------------------------------------------------------------------------------------
    // 保存
    // ------------------------------------------------------------------------------------------------

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        progress = tag.getInt("LeyProgress");
        total = tag.getInt("LeyTotal");
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("LeyProgress", progress);
        tag.putInt("LeyTotal", total);
    }

    @Nullable
    public static LeyFurnaceBlockEntity at(Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof LeyFurnaceBlockEntity f ? f : null;
    }
}
