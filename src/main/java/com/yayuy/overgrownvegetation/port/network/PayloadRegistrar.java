package com.yayuy.overgrownvegetation.port.network;

/**
 * 【1.20.1 だけの物・互換の層】NeoForge の {@code PayloadRegistrar}。登録は {@link OvChannel} の表に入る。
 * 版の文字列は全部の registrar の分をまとめて 1 本の通信路の版にする(どれかが違えば接続のときにはじかれる。NeoForge と同じ)。
 */
public class PayloadRegistrar {

    private final String version;
    private boolean optional;

    PayloadRegistrar(String version) {
        this.version = version;
        OvChannel.addVersion(version);
    }

    /** NeoForge: 相手に無くても接続してよい印。1.20.1 では 1 本の通信路なので受けるだけ。 */
    public PayloadRegistrar optional() {
        this.optional = true;
        return this;
    }

    public PayloadRegistrar versioned(String version) {
        return new PayloadRegistrar(version);
    }

    public <T extends CustomPacketPayload> PayloadRegistrar playToClient(
            CustomPacketPayload.Type<T> type, StreamCodec<? super RegistryFriendlyByteBuf, T> codec, IPayloadHandler<T> handler) {
        OvChannel.register(type, codec, handler, OvChannel.Flow.TO_CLIENT);
        return this;
    }

    public <T extends CustomPacketPayload> PayloadRegistrar playToServer(
            CustomPacketPayload.Type<T> type, StreamCodec<? super RegistryFriendlyByteBuf, T> codec, IPayloadHandler<T> handler) {
        OvChannel.register(type, codec, handler, OvChannel.Flow.TO_SERVER);
        return this;
    }

    public <T extends CustomPacketPayload> PayloadRegistrar playBidirectional(
            CustomPacketPayload.Type<T> type, StreamCodec<? super RegistryFriendlyByteBuf, T> codec, IPayloadHandler<T> handler) {
        OvChannel.register(type, codec, handler, OvChannel.Flow.BOTH);
        return this;
    }
}
