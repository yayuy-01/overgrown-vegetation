package com.yayuy.overgrownvegetation.menu;

import com.yayuy.overgrownvegetation.registry.ModMenus;
import com.yayuy.overgrownvegetation.td.TargetStorage;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;

/**
 * ターゲットブロックの収納({@link TargetStorage}、ラージチェスト 4 つ分)の画面。ラージチェストと同じ 6 段 × 9 列 + プレイヤーの
 * インベントリで、<b>1 ページずつ</b>見せる(2026-09-25、ユーザー指定「ラージチェスト 4 つ分のインベントリ」)。
 *
 * <p>ページの切り替えはバニラの {@code ServerboundContainerButtonClickPacket} → {@link #clickMenuButton}(研究台の「解析」・砲台の装薬と
 * 同じ。独自のパケットは使わない)。サーバーの 54 マスは今のページの窓({@link PageView})で、ページを替えると窓の中身が替わり、
 * バニラの同期({@code broadcastChanges})が替わったマスをクライアントへ送る。クライアントの 54 マスはただの入れ物
 * (ページを知らない = 送られてきた物を並べるだけ)。今のページ番号と耐久は {@link DataSlot} で同期する(見出し用)。
 *
 * <p>シフトクリックでプレイヤーから入れると、今のページから順に<b>全ページ</b>へ入れる(ページが埋まっていても空きのあるページへ)。
 */
public class TargetStorageMenu extends AbstractContainerMenu {

    /** 段数(ラージチェストと同じ)。 */
    public static final int ROWS = 6;
    public static final int PAGE_SLOTS = TargetStorage.PAGE_SLOTS;
    public static final int PAGES = TargetStorage.PAGES;
    /** 耐久の同期の数(残り・最大をそれぞれ下位 16 ビット・上位 16 ビットに分ける)。 */
    public static final int HP_DATA_COUNT = 4;

    /** サーバーだけ: 収納の本体。クライアントは null。 */
    @Nullable
    private final TargetStorage storage;
    private final DataSlot page = DataSlot.standalone();
    private final ContainerData hp;

    /** クライアント側(追加のデータは無い)。 */
    public TargetStorageMenu(int containerId, Inventory inventory, @Nullable RegistryFriendlyByteBuf buf) {
        this(containerId, inventory, null, new SimpleContainerData(HP_DATA_COUNT));
    }

    /** サーバー側。 */
    public TargetStorageMenu(int containerId, Inventory inventory, @Nullable TargetStorage storage, ContainerData hp) {
        super(ModMenus.TARGET_STORAGE.get(), containerId);
        checkContainerDataCount(hp, HP_DATA_COUNT);
        this.storage = storage;
        this.hp = hp;
        Container window = storage != null ? new PageView(storage, this.page) : new SimpleContainer(PAGE_SLOTS);
        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(window, col + row * 9, 8 + col * 18, 18 + row * 18));
            }
        }
        int offset = (ROWS - 4) * 18;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, col + row * 9 + 9, 8 + col * 18, 103 + row * 18 + offset));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, 8 + col * 18, 161 + offset));
        }
        addDataSlot(this.page);
        addDataSlots(hp);
    }

    /** 今のページ(0 始まり)。 */
    public int page() {
        return Mth.clamp(this.page.get(), 0, PAGES - 1);
    }

    /** ターゲットブロックの残りの耐久(切り上げ)。 */
    public int hp() {
        return (this.hp.get(0) & 0xFFFF) | ((this.hp.get(1) & 0xFFFF) << 16);
    }

    /** ターゲットブロックの最大耐久(切り上げ)。 */
    public int maxHp() {
        return (this.hp.get(2) & 0xFFFF) | ((this.hp.get(3) & 0xFFFF) << 16);
    }

    /** ボタン 0〜{@value #PAGES}-1 = そのページを開く。 */
    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (id < 0 || id >= PAGES) {
            return false;
        }
        this.page.set(id);
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack result = stack.copy();
        if (index < PAGE_SLOTS) {
            // 収納 → プレイヤー
            if (!moveItemStackTo(stack, PAGE_SLOTS, this.slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else if (this.storage != null) {
            // プレイヤー → 収納(今のページから順に全ページへ)
            ItemStack rest = this.storage.insert(stack, page() * PAGE_SLOTS);
            if (rest.getCount() == stack.getCount()) {
                return ItemStack.EMPTY;
            }
            stack.setCount(rest.getCount());
        } else if (!moveItemStackTo(stack, 0, PAGE_SLOTS, false)) {
            // クライアントの見込み(今のページだけ。答えはサーバーから届く)
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

    @Override
    public boolean stillValid(Player player) {
        return this.storage == null || this.storage.stillValid(player);
    }

    /** サーバーの 54 マス: 収納の今のページの窓。 */
    private static final class PageView implements Container {
        private final TargetStorage storage;
        private final DataSlot page;

        PageView(TargetStorage storage, DataSlot page) {
            this.storage = storage;
            this.page = page;
        }

        private int base() {
            return Mth.clamp(this.page.get(), 0, PAGES - 1) * PAGE_SLOTS;
        }

        @Override
        public int getContainerSize() {
            return PAGE_SLOTS;
        }

        @Override
        public boolean isEmpty() {
            int base = base();
            for (int i = 0; i < PAGE_SLOTS; i++) {
                if (!this.storage.getItem(base + i).isEmpty()) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public ItemStack getItem(int slot) {
            return this.storage.getItem(base() + slot);
        }

        @Override
        public ItemStack removeItem(int slot, int amount) {
            return this.storage.removeItem(base() + slot, amount);
        }

        @Override
        public ItemStack removeItemNoUpdate(int slot) {
            return this.storage.removeItemNoUpdate(base() + slot);
        }

        @Override
        public void setItem(int slot, ItemStack stack) {
            this.storage.setItem(base() + slot, stack);
        }

        @Override
        public int getMaxStackSize() {
            return this.storage.getMaxStackSize();
        }

        @Override
        public void setChanged() {
            this.storage.setChanged();
        }

        @Override
        public boolean stillValid(Player player) {
            return this.storage.stillValid(player);
        }

        @Override
        public void clearContent() {
            int base = base();
            for (int i = 0; i < PAGE_SLOTS; i++) {
                this.storage.setItem(base + i, ItemStack.EMPTY);
            }
        }
    }
}
