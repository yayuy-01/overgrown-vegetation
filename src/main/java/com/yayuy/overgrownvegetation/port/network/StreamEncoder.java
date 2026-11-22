package com.yayuy.overgrownvegetation.port.network;

/** 【1.20.1 だけの物・互換の層】バニラ 1.20.5 の {@code net.minecraft.network.codec.StreamEncoder}。 */
@FunctionalInterface
public interface StreamEncoder<O, T> {
    void encode(O buffer, T value);
}
