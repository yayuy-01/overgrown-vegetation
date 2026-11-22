package com.yayuy.overgrownvegetation.port.network;

/** 【1.20.1 だけの物・互換の層】バニラ 1.20.5 の {@code net.minecraft.network.codec.StreamMemberEncoder}(値の側の write)。 */
@FunctionalInterface
public interface StreamMemberEncoder<O, T> {
    void encode(T value, O buffer);
}
