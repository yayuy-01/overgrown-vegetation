package com.yayuy.overgrownvegetation.port.network;

/** 【1.20.1 だけの物・互換の層】NeoForge の {@code IPayloadHandler}。 */
@FunctionalInterface
public interface IPayloadHandler<T extends CustomPacketPayload> {
    void handle(T payload, IPayloadContext context);
}
