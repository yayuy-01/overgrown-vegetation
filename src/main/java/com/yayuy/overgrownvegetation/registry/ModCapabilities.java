package com.yayuy.overgrownvegetation.registry;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.items.wrapper.InvWrapper;

/**
 * ブロックの能力(Capability)登録。ホッパー・ドロッパー・他 MOD のパイプ類は NeoForge の
 * {@link Capabilities.ItemHandler#BLOCK} 経由でコンテナに触るので、バニラのチェスト/樽と同じように
 * 「過繫茂したチェスト/樽」にも登録しておかないとホッパーが中身を出し入れできない(2026-09-11)。
 * 胞子装置({@code SporeDeviceBlockEntity})は {@code WorldlyContainer} なので NeoForge が自動で面倒を見る…わけではなく、
 * バニラの BlockEntityType にしか自動登録は無いので、こちらも明示的に登録する。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class ModCapabilities {

    @SubscribeEvent
    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, ModBlockEntities.OVERGROWN_CHEST.get(),
                (be, side) -> new InvWrapper(be));
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, ModBlockEntities.OVERGROWN_BARREL.get(),
                (be, side) -> new InvWrapper(be));
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, ModBlockEntities.SPORE_DEVICE.get(),
                (be, side) -> side == null ? new InvWrapper(be) : new net.neoforged.neoforge.items.wrapper.SidedInvWrapper(be, side));
        // ターゲットブロックの収納(2026-09-25。攻め手のドロップが溜まる。ホッパーで運び出せるように)。
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, ModBlockEntities.TARGET_BLOCK.get(),
                (be, side) -> new InvWrapper(be.storage()));
    }

    private ModCapabilities() {
    }
}
