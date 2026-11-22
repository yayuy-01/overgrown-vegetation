package com.yayuy.overgrownvegetation.port.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;

import java.util.function.Supplier;

/**
 * 【1.20.1 だけの物・互換の層】バニラ 1.20.5 の {@code net.minecraft.network.RegistryFriendlyByteBuf}。
 * 送る・受けるときに {@link OvChannel} が包む。登録簿は使うときに引く(引けない所で呼ぶと落ちる)。
 */
public class RegistryFriendlyByteBuf extends FriendlyByteBuf {

    private final Supplier<RegistryAccess> registryAccess;

    public RegistryFriendlyByteBuf(ByteBuf source, Supplier<RegistryAccess> registryAccess) {
        super(source);
        this.registryAccess = registryAccess;
    }

    public RegistryAccess registryAccess() {
        return registryAccess.get();
    }
}
