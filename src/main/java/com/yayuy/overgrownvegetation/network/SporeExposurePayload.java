package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * サーバー → クライアントへ、自分の胞子の暴露量(感染の量に対する割合 0〜1、1/32 刻み)と、にじみの出始め・濃くなりきる割合を伝える
 * S2C ペイロード(2026-11-04 感染システムの第 2 段「前触れ = 画面のにじみだけ」)。{@code spore.SporeExposureTracker} が刻みの変わったときに送り、
 * クライアントは {@code client.SporeExposureClient} が受けて画面の端に緑のにじみを描く。
 */
public record SporeExposurePayload(float fraction, float faint, float strong) implements CustomPacketPayload {

    public static final Type<SporeExposurePayload> TYPE = new Type<>(OvergrownVegetation.id("spore_exposure"));

    public static final StreamCodec<FriendlyByteBuf, SporeExposurePayload> STREAM_CODEC =
            StreamCodec.ofMember(SporeExposurePayload::write, SporeExposurePayload::new);

    private SporeExposurePayload(FriendlyByteBuf buf) {
        this(buf.readFloat(), buf.readFloat(), buf.readFloat());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeFloat(fraction);
        buf.writeFloat(faint);
        buf.writeFloat(strong);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
