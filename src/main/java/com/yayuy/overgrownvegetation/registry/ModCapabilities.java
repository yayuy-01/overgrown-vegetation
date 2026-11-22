package com.yayuy.overgrownvegetation.registry;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import com.yayuy.overgrownvegetation.port.capabilities.Capabilities;
import com.yayuy.overgrownvegetation.port.capabilities.RegisterCapabilitiesEvent;
import net.minecraftforge.items.wrapper.InvWrapper;

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
    }

    private ModCapabilities() {
    }
}
