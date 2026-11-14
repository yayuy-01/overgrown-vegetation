package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 監視塔フィロセンスに見られている強さ(S2C、2026-11-14。見られている本人だけへ)。視線が通った瞬間から 0 → 印が付く 2 秒で 1、
 * 見られている間は 1。送られてこなくなったら、クライアントがゆっくり引く({@code client/PhyllosensGazeEdge})。
 *
 * @param strength 0〜1
 */
public record PhyllosensGazePayload(float strength) implements CustomPacketPayload {

    public static final Type<PhyllosensGazePayload> TYPE = new Type<>(OvergrownVegetation.id("phyllosens_gaze"));

    public static final StreamCodec<FriendlyByteBuf, PhyllosensGazePayload> STREAM_CODEC =
            StreamCodec.ofMember(PhyllosensGazePayload::write, PhyllosensGazePayload::new);

    private PhyllosensGazePayload(FriendlyByteBuf buf) {
        this(buf.readFloat());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeFloat(strength);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
