package com.yayuy.overgrownvegetation.block;

import com.yayuy.overgrownvegetation.registry.ModCreepingWall;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 成れの果て({@link WallRemainsBlock})の中身: 飲まれたプレイヤーの持ち物(持ち物 36 + 防具 4 + 左手 1)と名前。
 * 名前は、後で壁の面に「前に飲まれたプレイヤーの形」を混ぜるときのため(決定 その 3)。
 */
public class WallRemainsBlockEntity extends BlockEntity {

    public static final int SIZE = 41;

    private final NonNullList<ItemStack> items = NonNullList.withSize(SIZE, ItemStack.EMPTY);
    private String ownerName = "";

    public WallRemainsBlockEntity(BlockPos pos, BlockState state) {
        super(ModCreepingWall.WALL_REMAINS_BE.get(), pos, state);
    }

    public NonNullList<ItemStack> items() {
        return items;
    }

    public String ownerName() {
        return ownerName;
    }

    public void setOwnerName(String name) {
        this.ownerName = name;
        setChanged();
    }

    /** 中身を 1 つ足す(空いた枠へ)。入らなければ false。 */
    public boolean add(ItemStack stack) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).isEmpty()) {
                items.set(i, stack);
                setChanged();
                return true;
            }
        }
        return false;
    }

    public int count() {
        int n = 0;
        for (ItemStack s : items) {
            if (!s.isEmpty()) {
                n++;
            }
        }
        return n;
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        ContainerHelper.saveAllItems(tag, items);
        tag.putString("Owner", ownerName);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        items.clear();
        ContainerHelper.loadAllItems(tag, items);
        ownerName = tag.getString("Owner");
    }
}
