package com.yayuy.overgrownvegetation.port.attachment;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.capabilities.RegisterCapabilitiesEvent;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.IEventBus;

/**
 * 【1.20.1 だけの物・互換の層】attachment の入れ物({@link AttachmentStore})を Entity・LevelChunk・Level・BlockEntity に付け、
 * プレイヤーの作り直しで引き継ぐ。{@code port.PortSetup} が起動のときに 1 回つなぐ。
 */
public final class AttachmentEvents {

    private AttachmentEvents() {
    }

    public static void init(IEventBus modBus) {
        modBus.addListener((RegisterCapabilitiesEvent e) -> e.register(AttachmentStore.class));
        MinecraftForge.EVENT_BUS.addGenericListener(Entity.class, (AttachCapabilitiesEvent<Entity> e) ->
                e.addCapability(AttachmentStore.KEY, new AttachmentStore()));
        MinecraftForge.EVENT_BUS.addGenericListener(LevelChunk.class, (AttachCapabilitiesEvent<LevelChunk> e) ->
                e.addCapability(AttachmentStore.KEY, new AttachmentStore()));
        MinecraftForge.EVENT_BUS.addGenericListener(Level.class, (AttachCapabilitiesEvent<Level> e) ->
                e.addCapability(AttachmentStore.KEY, new AttachmentStore()));
        // 2026-11-23: ブロックエンティティも持ち主にする(本流の SpawnerCorruption がスポナーに付ける)
        MinecraftForge.EVENT_BUS.addGenericListener(net.minecraft.world.level.block.entity.BlockEntity.class,
                (AttachCapabilitiesEvent<net.minecraft.world.level.block.entity.BlockEntity> e) ->
                        e.addCapability(AttachmentStore.KEY, new AttachmentStore()));
        MinecraftForge.EVENT_BUS.addListener(AttachmentEvents::onClone);
    }

    /** 死: copyOnDeath の型だけ。死ではない作り直し(エンドからの帰還): 保存する型を全部(NeoForge と同じ)。 */
    private static void onClone(PlayerEvent.Clone e) {
        var original = e.getOriginal();
        original.reviveCaps();
        try {
            AttachmentStore from = original.getCapability(AttachmentStore.CAPABILITY).orElse(null);
            AttachmentStore to = e.getEntity().getCapability(AttachmentStore.CAPABILITY).orElse(null);
            if (from != null && to != null) {
                to.copyFrom(from, e.isWasDeath());
            }
        } finally {
            original.invalidateCaps();
        }
    }
}
