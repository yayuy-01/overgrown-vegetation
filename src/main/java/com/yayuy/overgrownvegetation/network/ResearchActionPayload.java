package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import com.yayuy.overgrownvegetation.port.network.StreamCodec;
import com.yayuy.overgrownvegetation.port.network.CustomPacketPayload;

/**
 * クライアント → サーバーへ、研究の画面の操作(2026-11-17 研究台と研究ツリー)。サーバーは研究台のそばか・研究台があるかを確かめてから行う。
 *
 * @param action {@link #CLOSE} 画面を閉じた / {@link #START} 研究する / {@link #CANCEL} やめる
 * @param bench  研究台の位置
 * @param node   研究する節(START のとき)
 */
public record ResearchActionPayload(int action, BlockPos bench, String node) implements CustomPacketPayload {

    public static final int CLOSE = 0;
    public static final int START = 1;
    public static final int CANCEL = 2;

    public static final Type<ResearchActionPayload> TYPE = new Type<>(OvergrownVegetation.id("research_action"));

    public static final StreamCodec<FriendlyByteBuf, ResearchActionPayload> STREAM_CODEC =
            StreamCodec.ofMember(ResearchActionPayload::write, ResearchActionPayload::read);

    private static ResearchActionPayload read(FriendlyByteBuf buf) {
        return new ResearchActionPayload(buf.readVarInt(), buf.readBlockPos(), buf.readUtf(256));
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(action);
        buf.writeBlockPos(bench);
        buf.writeUtf(node, 256);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
