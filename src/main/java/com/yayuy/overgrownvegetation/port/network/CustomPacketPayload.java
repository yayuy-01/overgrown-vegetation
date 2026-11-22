package com.yayuy.overgrownvegetation.port.network;

import net.minecraft.resources.ResourceLocation;

/**
 * 【1.20.1 だけの物・互換の層】バニラ 1.20.5 の {@code net.minecraft.network.protocol.common.custom.CustomPacketPayload}。
 * 本流の payload の record はそのまま({@code implements CustomPacketPayload}・{@code new Type<>(id)}・{@code type()})。
 * 送り方は {@link PacketDistributor}、受け方は {@link RegisterPayloadHandlersEvent}。中身は SimpleChannel 1 本({@link OvChannel})。
 */
public interface CustomPacketPayload {

    Type<? extends CustomPacketPayload> type();

    record Type<T extends CustomPacketPayload>(ResourceLocation id) {
    }
}
