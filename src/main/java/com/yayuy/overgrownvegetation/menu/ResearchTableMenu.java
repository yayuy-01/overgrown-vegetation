package com.yayuy.overgrownvegetation.menu;

import com.yayuy.overgrownvegetation.crafting.SizedCraftingRecipe;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.ResearchState;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.ModMenus;
import com.yayuy.overgrownvegetation.registry.ModRecipes;
import com.yayuy.overgrownvegetation.research.ResearchLogic;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

import java.util.Optional;

/**
 * 研究台のコンテナ。
 * <ul>
 *   <li>0: 5×5 拡大作業台の成果スロット</li>
 *   <li>1〜25: 5×5 クラフトグリッド({@link SizedCraftingRecipe} と 3×3 以内のバニラレシピ。研究未開放は結果が出ない)</li>
 *   <li>26〜(25+N): 解析枠(N = {@link ResearchLogic#analyzeSlotCount})。mob ドロップを入れて「解析」で研究ポイントに</li>
 *   <li>以降: プレイヤーインベントリ</li>
 * </ul>
 */
public class ResearchTableMenu extends AbstractContainerMenu {

    public static final int ANALYZE_BUTTON = 0;
    public static final int GRID = ResearchUi.GRID;

    private final CraftingContainer craftSlots = new TransientCraftingContainer(this, GRID, GRID);
    private final ResultContainer resultSlots = new ResultContainer();
    private final SimpleContainer analyzeSlots;
    private final ContainerLevelAccess access;
    private final Player player;
    private final int analyzeCount;
    private final int invStart;
    /** 画面の「作業」タブを開いている間だけ true(クライアントだけが切り替える。サーバーは常に true)。研究のタブではスロットを描かず当たりも取らない。 */
    private boolean workSlotsShown = true;

    public ResearchTableMenu(int containerId, Inventory playerInventory, RegistryFriendlyByteBuf buf) {
        this(containerId, playerInventory, ContainerLevelAccess.NULL,
                Math.max(1, Math.min(ResearchUi.ANALYZE_MAX, buf.readVarInt())));
    }

    public ResearchTableMenu(int containerId, Inventory playerInventory, ContainerLevelAccess access, int analyzeCount) {
        super(ModMenus.RESEARCH_TABLE.get(), containerId);
        this.access = access;
        this.player = playerInventory.player;
        this.analyzeCount = Math.max(1, Math.min(ResearchUi.ANALYZE_MAX, analyzeCount));
        this.analyzeSlots = new SimpleContainer(this.analyzeCount);
        this.invStart = 1 + GRID * GRID + this.analyzeCount;

        this.addSlot(new ResultSlot(playerInventory.player, this.craftSlots, this.resultSlots, 0,
                ResearchUi.RESULT_X, ResearchUi.RESULT_Y) {
            @Override
            public boolean isActive() {
                return workSlotsShown;
            }
        });
        for (int row = 0; row < GRID; row++) {
            for (int col = 0; col < GRID; col++) {
                this.addSlot(new Slot(this.craftSlots, col + row * GRID,
                        ResearchUi.GRID_X + col * ResearchUi.SLOT,
                        ResearchUi.GRID_Y + row * ResearchUi.SLOT) {
                    @Override
                    public boolean isActive() {
                        return workSlotsShown;
                    }
                });
            }
        }
        for (int i = 0; i < this.analyzeCount; i++) {
            this.addSlot(new Slot(this.analyzeSlots, i,
                    ResearchUi.ANALYZE_X + i * ResearchUi.SLOT, ResearchUi.ANALYZE_Y) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    return DesignData.research().pointsFor(stack) > 0;
                }

                @Override
                public boolean isActive() {
                    return workSlotsShown;
                }
            });
        }
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInventory, col + row * 9 + 9,
                        ResearchUi.INV_X + col * ResearchUi.SLOT,
                        ResearchUi.INV_Y + row * ResearchUi.SLOT) {
                    @Override
                    public boolean isActive() {
                        return workSlotsShown;
                    }
                });
            }
        }
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInventory, col,
                    ResearchUi.INV_X + col * ResearchUi.SLOT, ResearchUi.HOTBAR_Y) {
                @Override
                public boolean isActive() {
                    return workSlotsShown;
                }
            });
        }
    }

    public int analyzeCount() {
        return analyzeCount;
    }

    /** 画面のタブに合わせてスロットを出し入れする(クライアントだけ)。 */
    public void setWorkSlotsShown(boolean shown) {
        this.workSlotsShown = shown;
    }

    // --- 5×5 作業台の結果 ---

    @Override
    public void slotsChanged(Container container) {
        if (container == this.craftSlots) {
            this.access.execute((level, pos) -> recalcResult(level));
        }
        super.slotsChanged(container);
    }

    private void recalcResult(Level level) {
        if (level.isClientSide() || !(this.player instanceof ServerPlayer sp) || level.getServer() == null) {
            return;
        }
        CraftingInput input = this.craftSlots.asCraftInput();
        ItemStack out = ItemStack.EMPTY;
        ResourceLocation recipeId = null;

        Optional<RecipeHolder<SizedCraftingRecipe>> sized =
                level.getServer().getRecipeManager().getRecipeFor(ModRecipes.SIZED_CRAFTING_TYPE.get(), input, level);
        if (sized.isPresent()) {
            out = sized.get().value().assemble(input, level.registryAccess());
            recipeId = sized.get().id();
        } else {
            Optional<RecipeHolder<CraftingRecipe>> vanilla =
                    level.getServer().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, level);
            if (vanilla.isPresent()) {
                out = vanilla.get().value().assemble(input, level.registryAccess());
                recipeId = vanilla.get().id();
            }
        }
        if (!out.isEmpty() && recipeId != null && ResearchLogic.isRecipeLocked(this.player, recipeId)) {
            out = ItemStack.EMPTY;
        }
        this.resultSlots.setItem(0, out);
        this.setRemoteSlot(0, out);
        sp.connection.send(new ClientboundContainerSetSlotPacket(this.containerId, this.incrementStateId(), 0, out));
    }

    // --- 解析 ---

    @Override
    public boolean clickMenuButton(Player p, int id) {
        if (id != ANALYZE_BUTTON) {
            return false;
        }
        if (p.level().isClientSide()) {
            return true;
        }
        int total = 0;
        for (int i = 0; i < this.analyzeSlots.getContainerSize(); i++) {
            ItemStack s = this.analyzeSlots.getItem(i);
            int per = DesignData.research().pointsFor(s);
            if (per > 0 && !s.isEmpty()) {
                total += per * s.getCount();
                this.analyzeSlots.setItem(i, ItemStack.EMPTY);
            }
        }
        if (total <= 0) {
            return false;
        }
        ResearchState state = p.getData(ModAttachments.RESEARCH);
        state.addPoints(total);
        p.setData(ModAttachments.RESEARCH, state);
        this.analyzeSlots.setChanged();
        if (p instanceof ServerPlayer sp) {
            ResearchLogic.sync(sp);
        }
        return true;
    }

    // --- shift クリック ---

    @Override
    public ItemStack quickMoveStack(Player p, int index) {
        ItemStack result = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return result;
        }
        ItemStack stack = slot.getItem();
        result = stack.copy();
        int analyzeStart = 1 + GRID * GRID;
        int end = this.slots.size();

        if (index == 0) {
            if (!this.moveItemStackTo(stack, invStart, end, true)) {
                return ItemStack.EMPTY;
            }
        } else if (index < analyzeStart) {
            // グリッド → インベントリ
            if (!this.moveItemStackTo(stack, invStart, end, false)) {
                return ItemStack.EMPTY;
            }
        } else if (index < invStart) {
            // 解析枠 → インベントリ
            if (!this.moveItemStackTo(stack, invStart, end, false)) {
                return ItemStack.EMPTY;
            }
        } else {
            // インベントリ → 解析枠(ポイントになる物)、無理ならグリッド
            if (DesignData.research().pointsFor(stack) > 0 && this.moveItemStackTo(stack, analyzeStart, invStart, false)) {
                // moved
            } else if (!this.moveItemStackTo(stack, 1, analyzeStart, false)) {
                return ItemStack.EMPTY;
            }
        }

        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        if (stack.getCount() == result.getCount()) {
            return ItemStack.EMPTY;
        }
        slot.onTake(p, stack);
        return result;
    }

    @Override
    public boolean stillValid(Player p) {
        return AbstractContainerMenu.stillValid(this.access, p, ModBlocks.RESEARCH_TABLE.get());
    }

    @Override
    public void removed(Player p) {
        super.removed(p);
        this.access.execute((level, pos) -> {
            this.clearContainer(p, this.craftSlots);
            this.clearContainer(p, this.analyzeSlots);
        });
    }
}
