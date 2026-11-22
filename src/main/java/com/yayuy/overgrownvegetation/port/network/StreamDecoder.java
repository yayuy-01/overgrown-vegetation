package com.yayuy.overgrownvegetation.port.network;

/** 【1.20.1 だけの物・互換の層】バニラ 1.20.5 の {@code net.minecraft.network.codec.StreamDecoder}。 */
@FunctionalInterface
public interface StreamDecoder<I, T> {
    T decode(I buffer);
}
