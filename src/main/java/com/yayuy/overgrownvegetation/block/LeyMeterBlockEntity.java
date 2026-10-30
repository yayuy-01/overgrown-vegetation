package com.yayuy.overgrownvegetation.block;

import com.yayuy.overgrownvegetation.phase.LeyOmen;
import com.yayuy.overgrownvegetation.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 地脈計の中身(2026-10-24)。流れの強さは場所だけで決まる(シードと座標の関数)ので最初に 1 回測って覚え、
 * 「不安定」(前兆の間)と一緒に画面へ送る(クライアントはシードを知らないので測れない)。
 * 前兆の始まり・終わりは 20 tick ごとに見て、変わったときだけ送り直す。
 */
public class LeyMeterBlockEntity extends BlockEntity {

    private static final int STEP = 20;

    /** 流れの強さ(0.05〜1、汲み上げ器の効率と同じ尺度)。−1 = まだ測っていない。 */
    private double flow = -1.0D;
    private boolean unstable;

    /** 画面側: 今描いている針の値(なめらかに寄せる)。保存しない。 */
    public float shownNeedle = Float.NaN;

    public LeyMeterBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.LEY_METER.get(), pos, state);
    }

    /** 覚えた流れの強さ(サーバーでは測っていなければ測る。画面側は送られた値)。 */
    public double flow() {
        if (flow < 0.0D && level instanceof ServerLevel server) {
            flow = LeyPumpBlockEntity.efficiencyAt(server, worldPosition);
        }
        return Math.max(flow, 0.0D);
    }

    public boolean isUnstable() {
        return unstable;
    }

    /** 右クリックで出す値: ふだんは流れの強さ、前兆の間は汲み上げ器と同じ揺れを掛けた今の値。 */
    public double reading() {
        double f = flow();
        if (unstable && level != null) {
            f *= LeyOmen.pumpJitter(worldPosition, level.getGameTime());
        }
        return f;
    }

    /** 測り直し・前兆を見直して、変わっていれば画面へ送る(サーバー)。 */
    public void refreshNow() {
        if (!(level instanceof ServerLevel)) {
            return;
        }
        boolean measured = flow < 0.0D;
        flow();
        boolean now = LeyOmen.isActive();
        if (measured || now != unstable) {
            unstable = now;
            setChanged();
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, LeyMeterBlockEntity be) {
        if (Math.floorMod(level.getGameTime() + pos.asLong(), STEP) == 0) {
            be.refreshNow();
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (flow >= 0.0D) {
            tag.putDouble("Flow", flow);
        }
        tag.putBoolean("Unstable", unstable);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        flow = tag.contains("Flow") ? Mth.clamp(tag.getDouble("Flow"), 0.0D, 1.0D) : -1.0D;
        unstable = tag.getBoolean("Unstable");
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putDouble("Flow", flow());
        tag.putBoolean("Unstable", unstable);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
