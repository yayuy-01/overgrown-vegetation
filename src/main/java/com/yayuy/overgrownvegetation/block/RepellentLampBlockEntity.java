package com.yayuy.overgrownvegetation.block;

import com.yayuy.overgrownvegetation.defense.MachineBattery;
import com.yayuy.overgrownvegetation.defense.RepellentLamps;
import com.yayuy.overgrownvegetation.registry.ModBlockEntities;
import com.yayuy.overgrownvegetation.registry.ModDataComponents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 忌避灯の中身(2026-09-19)。ロード/アンロードで {@link RepellentLamps} の台帳へ出入りする(点いている灯りだけ登録する)。
 *
 * <p>2026-10-24(地脈技術の 3 段目)から<b>地脈電池で点く</b>。地脈灯と同じく電池の残りを持ち、点いていて蔦に覆われていない間
 * 1 tick に 1 減らす(満タンで 1 時間。仮)。切れると消えて({@link RepellentLampBlock#LIT})台帳から外れる。
 * 前のワールドに置かれていた灯りは電池なし(消えた状態)で読み込まれる。電池の残りは地脈灯と同じく品と行き来する。
 */
public class RepellentLampBlockEntity extends BlockEntity implements MachineBattery.Holder {

    private int charge;
    private boolean hasBattery;

    public RepellentLampBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.REPELLENT_LAMP.get(), pos, state);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, RepellentLampBlockEntity be) {
        boolean covered = state.getValue(RepellentLampBlock.COVERED);
        if (!covered && MachineBattery.hasEnergy(be)) {
            MachineBattery.drain(be, 1);
        }
        boolean lit = MachineBattery.hasEnergy(be);
        if (state.getValue(RepellentLampBlock.LIT) != lit) {
            BlockState next = state.setValue(RepellentLampBlock.LIT, lit);
            level.setBlock(pos, next, Block.UPDATE_ALL);
            syncRegistry(level, pos, next);
        }
    }

    /** 点いていて覆われていなければ台帳に載せ、でなければ外す。 */
    static void syncRegistry(Level level, BlockPos pos, BlockState state) {
        if (level.isClientSide()) {
            return;
        }
        if (RepellentLampBlock.isActive(state)) {
            RepellentLamps.register(level, pos);
        } else {
            RepellentLamps.unregister(level, pos);
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (this.level != null && !this.level.isClientSide && RepellentLampBlock.isActive(getBlockState())) {
            RepellentLamps.register(this.level, this.worldPosition);
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (this.level != null && !this.level.isClientSide) {
            RepellentLamps.unregister(this.level, this.worldPosition);
        }
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        if (this.level != null && !this.level.isClientSide) {
            RepellentLamps.unregister(this.level, this.worldPosition);
        }
    }

    // ------------------------------------------------------------------------------------------------
    // 電池
    // ------------------------------------------------------------------------------------------------

    @Override
    public int batteryCharge() {
        return charge;
    }

    @Override
    public void setBatteryCharge(int charge) {
        this.charge = Math.max(0, charge);
    }

    @Override
    public boolean hasBattery() {
        return hasBattery;
    }

    @Override
    public void setHasBattery(boolean has) {
        this.hasBattery = has;
    }

    @Override
    public void batteryChanged() {
        setChanged();
    }

    // ------------------------------------------------------------------------------------------------
    // 品との行き来・保存
    // ------------------------------------------------------------------------------------------------

    @Override
    protected void applyImplicitComponents(DataComponentInput input) {
        super.applyImplicitComponents(input);
        Integer c = input.get(ModDataComponents.BATTERY_CHARGE.get());
        hasBattery = c != null;
        charge = c == null ? 0 : Math.max(0, c);
    }

    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder builder) {
        super.collectImplicitComponents(builder);
        if (hasBattery) {
            builder.set(ModDataComponents.BATTERY_CHARGE.get(), charge);
        }
    }

    @Override
    public void removeComponentsFromTag(CompoundTag tag) {
        super.removeComponentsFromTag(tag);
        tag.remove("BatteryCharge");
        tag.remove("HasBattery");
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        MachineBattery.save(this, tag);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        MachineBattery.load(this, tag);
    }
}
