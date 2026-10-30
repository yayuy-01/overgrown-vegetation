package com.yayuy.overgrownvegetation.menu;

import com.yayuy.overgrownvegetation.crafting.PoweredArmorWorkshop;
import com.yayuy.overgrownvegetation.crafting.PoweredArmorTableRecipe;
import com.yayuy.overgrownvegetation.item.PoweredArmorBlueprint;
import com.yayuy.overgrownvegetation.item.PoweredArmorBlueprintItem;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.ModMenus;
import com.yayuy.overgrownvegetation.research.ResearchLogic;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;

/**
 * パワードアーマー作成テーブルのコンテナ(順 1 の着手順 5・6、2026-10-03)。
 *
 * <p>1 つの画面に 4 つの役をタブで切り替える(仕様書 {@code docs/ORDER1_IMPL.md} の 6-1 節):
 * <ol start="0">
 *   <li>{@link #TAB_CRAFT 作成} —— 一覧から選んでインベントリの素材で作る({@code PoweredArmorTableCrafting})</li>
 *   <li>{@link #TAB_BLUEPRINT 設計図} —— 作業枠の装備を設計図へ写す / 設計図から作り直す</li>
 *   <li>{@link #TAB_TREE スキルツリー} —— 作業枠の装備のツリーを 1 段ずつ上げる</li>
 *   <li>{@link #TAB_FIT 装着}(カートリッジ・装飾品・拡張パーツ)—— 着手順 8・9</li>
 * </ol>
 *
 * <h2>枠</h2>
 * 0〜35 = プレイヤーの持ち物(全タブ共通・同じ位置)、36 = <b>作業枠</b>(防具 1 つ。設計図・スキルツリー・装着の
 * タブで共用 —— ユーザー指定「P」で「置く枠は設計図・装着のタブと共用」。装飾品は通常の防具にも付くので、着手順 9 から
 * パワードアーマー以外の防具も置ける。改造・写すはパワードアーマーだけ)、37 = 設計図の枠(設計図のタブだけ)、
 * 38 = <b>付ける物の枠</b>(カートリッジ・装飾品・拡張パーツのどれでも 1 つ。ユーザー指定「b」)、39 = 因子の枠
 * (どちらも装着のタブだけ。着手順 8・9)。
 * タブで出し入れするのは {@code Slot#isActive}(画面は非アクティブな枠を描かず当たりも取らない)。
 * 作業枠・設計図の枠の中身はメニューだけが持ち、閉じると持ち物へ返す(ブロックエンティティは無い)。
 *
 * <h2>ボタンの番号({@link #clickMenuButton})</h2>
 * 0〜3 = タブ、{@link #BUTTON_UPGRADE} + 本(0〜2)= 改造、{@link #BUTTON_COPY} = 設計図に写す、
 * {@link #BUTTON_REBUILD} = 設計図から作り直す、{@link #BUTTON_INSERT} = 因子をカートリッジへ、
 * {@link #BUTTON_ATTACH} = 付ける物の枠のものを装備へ(同じ差し口に付いていたものと入れ替え)、
 * {@link #BUTTON_DETACH} / {@link #BUTTON_DETACH_ACCESSORY} / {@link #BUTTON_DETACH_EXTENSION} =
 * カートリッジ / 装飾品 / 拡張パーツを外して付ける物の枠へ(枠が空のときだけ)。
 * 作業はタブが合っているときだけ受け付ける。
 */
public class PoweredArmorTableMenu extends AbstractContainerMenu {

    public static final int TAB_CRAFT = 0;
    public static final int TAB_BLUEPRINT = 1;
    public static final int TAB_TREE = 2;
    public static final int TAB_FIT = 3;

    public static final int BUTTON_UPGRADE = 10;
    public static final int BUTTON_COPY = 20;
    public static final int BUTTON_REBUILD = 21;
    public static final int BUTTON_INSERT = 30;
    public static final int BUTTON_ATTACH = 31;
    public static final int BUTTON_DETACH = 32;
    public static final int BUTTON_DETACH_ACCESSORY = 33;
    public static final int BUTTON_DETACH_EXTENSION = 34;

    private static final int INV_START = 0;
    private static final int HOTBAR_START = 27;
    private static final int INV_END = 36;
    public static final int WORK_SLOT = 36;
    public static final int PRINT_SLOT = 37;
    public static final int ATTACH_SLOT = 38;
    public static final int FACTOR_SLOT = 39;

    private final ContainerLevelAccess access;
    private final DataSlot tab = DataSlot.standalone();
    /** 0 = 作業枠、1 = 設計図の枠、2 = 付ける物の枠、3 = 因子の枠。 */
    private final SimpleContainer work = new SimpleContainer(4) {
        @Override
        public void setChanged() {
            super.setChanged();
            PoweredArmorTableMenu.this.slotsChanged(this);
        }
    };

    /** クライアント側(画面を開くとき)。 */
    public PoweredArmorTableMenu(int containerId, Inventory playerInventory) {
        this(containerId, playerInventory, ContainerLevelAccess.NULL);
    }

    public PoweredArmorTableMenu(int containerId, Inventory playerInventory, ContainerLevelAccess access) {
        super(ModMenus.POWERED_ARMOR_TABLE.get(), containerId);
        this.access = access;
        this.addDataSlot(this.tab);
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInventory, col + row * 9 + 9,
                        PoweredArmorUi.INV_X + col * PoweredArmorUi.SLOT,
                        PoweredArmorUi.INV_Y + row * PoweredArmorUi.SLOT));
            }
        }
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInventory, col,
                    PoweredArmorUi.INV_X + col * PoweredArmorUi.SLOT, PoweredArmorUi.HOTBAR_Y));
        }
        this.addSlot(new Slot(this.work, 0, PoweredArmorUi.WORK_X, PoweredArmorUi.WORK_Y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                // パワードアーマーと、装飾品を付けられる通常の防具(着手順 9)
                return com.yayuy.overgrownvegetation.item.ArmorAccessory.acceptsAccessories(stack);
            }

            @Override
            public int getMaxStackSize() {
                return 1;
            }

            @Override
            public boolean isActive() {
                int t = tab();
                return t == TAB_BLUEPRINT || t == TAB_TREE || t == TAB_FIT;
            }
        });
        this.addSlot(new Slot(this.work, 1, PoweredArmorUi.PRINT_X, PoweredArmorUi.PRINT_Y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return stack.getItem() instanceof PoweredArmorBlueprintItem;
            }

            @Override
            public int getMaxStackSize() {
                return 1;
            }

            @Override
            public boolean isActive() {
                return tab() == TAB_BLUEPRINT;
            }
        });
        this.addSlot(new Slot(this.work, 2, PoweredArmorUi.CART_X, PoweredArmorUi.CART_Y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return stack.getItem() instanceof com.yayuy.overgrownvegetation.item.CartridgeItem
                        || stack.getItem() instanceof com.yayuy.overgrownvegetation.item.ArmorAccessoryItem;
            }

            @Override
            public int getMaxStackSize() {
                return 1;
            }

            @Override
            public boolean isActive() {
                return tab() == TAB_FIT;
            }
        });
        this.addSlot(new Slot(this.work, 3, PoweredArmorUi.FACTOR_X, PoweredArmorUi.FACTOR_Y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return com.yayuy.overgrownvegetation.item.CartridgeFactor.of(stack) != null;
            }

            @Override
            public boolean isActive() {
                return tab() == TAB_FIT;
            }
        });
    }

    /** 付ける物の枠の中身(装着のタブ。カートリッジ・装飾品・拡張パーツ)。 */
    public ItemStack attachStack() {
        return this.work.getItem(2);
    }

    /** 因子の枠の中身(装着のタブ)。 */
    public ItemStack factorStack() {
        return this.work.getItem(3);
    }

    /** いま開いているタブ(0〜3)。 */
    public int tab() {
        return Mth.clamp(this.tab.get(), 0, PoweredArmorUi.TAB_COUNT - 1);
    }

    /** 作業枠の装備(空なら空のスタック)。 */
    public ItemStack workStack() {
        return this.work.getItem(0);
    }

    /** 設計図の枠の中身。 */
    public ItemStack blueprintStack() {
        return this.work.getItem(1);
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (id >= 0 && id < PoweredArmorUi.TAB_COUNT) {
            this.tab.set(id);
            return true;
        }
        PoweredArmorWorkshop.Result result = null;
        if (id >= BUTTON_UPGRADE && id < BUTTON_UPGRADE + 3 && tab() == TAB_TREE) {
            result = PoweredArmorWorkshop.upgrade(player, workStack(), id - BUTTON_UPGRADE);
            if (result == PoweredArmorWorkshop.Result.OK && player instanceof ServerPlayer sp) {
                ResearchLogic.sync(sp);
            }
        } else if (id == BUTTON_COPY && tab() == TAB_BLUEPRINT) {
            result = PoweredArmorWorkshop.copy(workStack(), blueprintStack());
        } else if (id == BUTTON_REBUILD && tab() == TAB_BLUEPRINT) {
            result = rebuild(player);
        } else if (id == BUTTON_INSERT && tab() == TAB_FIT) {
            result = PoweredArmorWorkshop.insertFactors(attachStack(), factorStack());
        } else if (id == BUTTON_ATTACH && tab() == TAB_FIT) {
            ItemStack old = PoweredArmorWorkshop.attachAny(workStack(), attachStack());
            if (old == null) {
                result = PoweredArmorWorkshop.canAttach(workStack(), attachStack());
            } else {
                this.work.setItem(2, old);
                result = PoweredArmorWorkshop.Result.OK;
            }
        } else if (id >= BUTTON_DETACH && id <= BUTTON_DETACH_EXTENSION && tab() == TAB_FIT) {
            if (!attachStack().isEmpty()) {
                result = PoweredArmorWorkshop.Result.SLOT_BUSY;
            } else {
                ItemStack out = id == BUTTON_DETACH ? PoweredArmorWorkshop.detach(workStack())
                        : PoweredArmorWorkshop.detachAccessory(workStack(), id == BUTTON_DETACH_ACCESSORY
                        ? com.yayuy.overgrownvegetation.item.ArmorAccessory.Kind.ACCESSORY
                        : com.yayuy.overgrownvegetation.item.ArmorAccessory.Kind.EXTENSION);
                if (out == null) {
                    result = PoweredArmorWorkshop.Result.NOT_ATTACHED;
                } else {
                    this.work.setItem(2, out);
                    result = PoweredArmorWorkshop.Result.OK;
                }
            }
        }
        if (result == null) {
            return false;
        }
        if (result == PoweredArmorWorkshop.Result.OK) {
            this.work.setChanged();
            player.level().playSound(null, player.blockPosition(), SoundEvents.SMITHING_TABLE_USE, SoundSource.BLOCKS,
                    1.0F, id == BUTTON_COPY ? 1.4F : 1.0F);
            this.broadcastChanges();
        }
        return result == PoweredArmorWorkshop.Result.OK;
    }

    private PoweredArmorWorkshop.Result rebuild(Player player) {
        PoweredArmorBlueprint bp = PoweredArmorBlueprintItem.data(blueprintStack());
        if (bp == null) {
            return PoweredArmorWorkshop.Result.BLANK;
        }
        if (player.level().getServer() == null) {
            return PoweredArmorWorkshop.Result.NO_RECIPE;
        }
        RecipeHolder<PoweredArmorTableRecipe> recipe =
                PoweredArmorWorkshop.rebuildRecipe(player.level().getServer().getRecipeManager(), bp);
        if (recipe == null) {
            return PoweredArmorWorkshop.Result.NO_RECIPE;
        }
        if (ResearchLogic.isRecipeLocked(player, recipe.id())) {
            return PoweredArmorWorkshop.Result.LOCKED;
        }
        return PoweredArmorWorkshop.rebuild(player, blueprintStack(), recipe);
    }

    /**
     * shift クリック。持ち物からは、パワードアーマー → 作業枠 / 設計図 → 設計図の枠(どちらも見えていて空いているときだけ)、
     * それ以外は持ち物 ↔ ホットバー。作業枠・設計図の枠からは持ち物へ。
     */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack result = stack.copy();
        if (index >= INV_END) {
            if (!this.moveItemStackTo(stack, INV_START, INV_END, true)) {
                return ItemStack.EMPTY;
            }
        } else if (intoActive(stack, WORK_SLOT) || intoActive(stack, PRINT_SLOT) || intoActive(stack, ATTACH_SLOT)
                || intoActiveStacking(stack, FACTOR_SLOT)) {
            // 作業枠・設計図・付ける物・因子の枠のどれかへ入った
        } else if (index < HOTBAR_START) {
            if (!this.moveItemStackTo(stack, HOTBAR_START, INV_END, false)) {
                return ItemStack.EMPTY;
            }
        } else if (!this.moveItemStackTo(stack, INV_START, HOTBAR_START, false)) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        if (stack.getCount() == result.getCount()) {
            return ItemStack.EMPTY;
        }
        slot.onTake(player, stack);
        return result;
    }

    private boolean intoActive(ItemStack stack, int index) {
        Slot target = this.slots.get(index);
        return target.isActive() && !target.hasItem() && target.mayPlace(stack)
                && this.moveItemStackTo(stack, index, index + 1, false);
    }

    /** 重なる枠(因子)へ。同じ因子が入っていれば足す。 */
    private boolean intoActiveStacking(ItemStack stack, int index) {
        Slot target = this.slots.get(index);
        return target.isActive() && target.mayPlace(stack) && this.moveItemStackTo(stack, index, index + 1, false);
    }

    @Override
    public boolean stillValid(Player player) {
        return AbstractContainerMenu.stillValid(this.access, player, ModBlocks.POWERED_ARMOR_TABLE.get());
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        this.access.execute((level, pos) -> this.clearContainer(player, this.work));
    }
}
