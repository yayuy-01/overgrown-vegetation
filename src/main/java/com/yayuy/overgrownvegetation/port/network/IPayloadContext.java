package com.yayuy.overgrownvegetation.port.network;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

import java.util.concurrent.CompletableFuture;

/**
 * 【1.20.1 だけの物・互換の層】NeoForge の {@code IPayloadContext} のうち、本流が使う物。
 * 受け手は NeoForge と同じくメインのスレッドで呼ばれる({@link #enqueueWork} はその場で走る)。
 */
public interface IPayloadContext {

    /** サーバーで受けたときは送り手、クライアントで受けたときは手元のプレイヤー。 */
    Player player();

    CompletableFuture<Void> enqueueWork(Runnable task);

    /** 送り手へ返す。 */
    void reply(CustomPacketPayload payload);

    void disconnect(Component reason);
}
