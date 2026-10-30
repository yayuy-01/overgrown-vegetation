package com.yayuy.overgrownvegetation.base;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** 管理端末の中身(2026-10-01)。どの基地の端末か(台帳の番号)だけを持つ。0 = まだ結び付いていない。 */
public class BaseTerminalBlockEntity extends BlockEntity {

    private int siteId;

    public BaseTerminalBlockEntity(BlockPos pos, BlockState state) {
        super(BaseBlocks.BASE_TERMINAL_ENTITY.get(), pos, state);
    }

    public int siteId() {
        return siteId;
    }

    public void setSiteId(int id) {
        this.siteId = id;
        setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("site", siteId);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        siteId = tag.getInt("site");
    }
}
