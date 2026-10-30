package com.yayuy.overgrownvegetation.block;

import com.yayuy.overgrownvegetation.defense.LeyLamps;
import com.yayuy.overgrownvegetation.defense.MachineBattery;
import com.yayuy.overgrownvegetation.registry.ModBlockEntities;
import com.yayuy.overgrownvegetation.registry.ModDataComponents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 地脈灯の中身(2026-10-22)。電池の残りを持ち、点いている間 1 tick に 1 減らす(満タンで 1 時間。機械の電池の決まり)。
 * 残りが 0 になると消え({@link LeyLampBlock#LIT})、湧き止めの台帳({@link LeyLamps})から外れる。
 * 電池の残りはアイテムの {@link ModDataComponents#BATTERY_CHARGE} と行き来する(置くと品から入り、壊すと品へ戻る。ルート表の copy_components)。
 */
public class LeyLampBlockEntity extends BlockEntity implements MachineBattery.Holder {

    private int charge;
    private boolean hasBattery;

    public LeyLampBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.LEY_LAMP.get(), pos, state);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, LeyLampBlockEntity be) {
        boolean lit = MachineBattery.hasEnergy(be);
        if (lit) {
            MachineBattery.drain(be, 1);
            lit = MachineBattery.hasEnergy(be);
        }
        if (state.getValue(LeyLampBlock.LIT) != lit) {
            level.setBlock(pos, state.setValue(LeyLampBlock.LIT, lit), 3);
            be.syncRegistry();
        }
    }

    /** 点いていれば台帳に載せ、消えていれば外す。 */
    void syncRegistry() {
        if (level == null || level.isClientSide()) {
            return;
        }
        if (charge > 0) {
            LeyLamps.register(level, worldPosition);
        } else {
            LeyLamps.unregister(level, worldPosition);
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        syncRegistry();
    }

    @Override
    public void setRemoved() {
        if (level != null) {
            LeyLamps.unregister(level, worldPosition);
        }
        super.setRemoved();
    }

    @Override
    public void onChunkUnloaded() {
        if (level != null) {
            LeyLamps.unregister(level, worldPosition);
        }
        super.onChunkUnloaded();
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
        syncRegistry();
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
