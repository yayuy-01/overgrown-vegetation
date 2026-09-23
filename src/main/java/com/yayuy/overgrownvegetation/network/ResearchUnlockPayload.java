package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * クライアント → サーバー。研究ツリー画面で「このノードを開放する」と要求する。
 * サーバー側で {@link com.yayuy.overgrownvegetation.research.ResearchLogic#tryUnlock} が検証する。
 */
public record ResearchUnlockPayload(String nodeId) implements CustomPacketPayload {

    public static final Type<ResearchUnlockPayload> TYPE =
            new Type<>(OvergrownVegetation.id("research_unlock"));

    public static final StreamCodec<FriendlyByteBuf, ResearchUnlockPayload> STREAM_CODEC =
            StreamCodec.ofMember(ResearchUnlockPayload::write, ResearchUnlockPayload::new);

    private ResearchUnlockPayload(FriendlyByteBuf buf) {
        this(buf.readUtf());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeUtf(nodeId);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
