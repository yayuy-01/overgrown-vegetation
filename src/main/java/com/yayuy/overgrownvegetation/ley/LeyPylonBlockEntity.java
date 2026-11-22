package com.yayuy.overgrownvegetation.ley;

import com.yayuy.overgrownvegetation.registry.ModLey;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import software.bernie.geckolib.animatable.GeoBlockEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * <b>地脈の柱</b>の中身(2026-11-13 サバイバルの背骨 段 2b、決定 13)。張った線の先(ほかの柱・汲み上げ器・FE の口を持つ機械)の座標と、掛けた電池を持つ。
 *
 * <p>線の持ち方: 柱 ↔ 柱の線は<b>両方の柱</b>が相手を持つ。柱 → 機械(汲み上げ器・2c の換気の機械・ほかの MOD の機械)の線は柱だけが持つ。
 * 線の数を数えるのも、壊したときに線の巻きを返すのも「柱が持っている線の数」。相手が消えた線は {@link LeyGrid} が見つけて外す。
 * 線の先はクライアントにも送る(線の描画 {@code client/ley/LeyPylonRenderer})。
 */
public class LeyPylonBlockEntity extends BlockEntity implements LeyBatteryHolder, GeoBlockEntity {

    /** GeckoLib の模型の入れ物(2026-11-22。動きは無い。電池の箱を見せるかは描画が状態の battery を読む)。 */
    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    private final List<BlockPos> links = new ArrayList<>();
    private ItemStack battery = ItemStack.EMPTY;

    public LeyPylonBlockEntity(BlockPos pos, BlockState state) {
        super(ModLey.PYLON_BE.get(), pos, state);
    }

    public List<BlockPos> links() {
        return Collections.unmodifiableList(links);
    }

    public boolean hasLink(BlockPos other) {
        return links.contains(other);
    }

    public void addLink(BlockPos other) {
        if (!links.contains(other)) {
            links.add(other.immutable());
            changed();
        }
    }

    public void removeLink(BlockPos other) {
        if (links.remove(other)) {
            changed();
        }
    }

    /** 中身を変えたら保存とクライアントへの送り直し。 */
    private void changed() {
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide) {
            LeyGrid.track(level, worldPosition);
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level != null && !level.isClientSide) {
            LeyGrid.untrack(level, worldPosition);
        }
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        if (level != null && !level.isClientSide) {
            LeyGrid.untrack(level, worldPosition);
        }
    }

    @Override
    public ItemStack battery() {
        return battery;
    }

    @Override
    public void setBatteryRaw(ItemStack stack) {
        battery = stack;
        setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        long[] arr = new long[links.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = links.get(i).asLong();
        }
        tag.put("links", new LongArrayTag(arr));
        LeyBatteryHolder.save(tag, battery);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        links.clear();
        for (long l : tag.getLongArray("links")) {
            links.add(BlockPos.of(l));
        }
        battery = LeyBatteryHolder.load(tag);
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return geoCache;
    }

    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
