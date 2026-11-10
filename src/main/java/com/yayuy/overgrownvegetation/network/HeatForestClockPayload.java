package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * サーバー → クライアントへ、ネザーの植物がネザーに適応したか(熱水の森の「最初の 10 分」を過ぎたか)を伝える S2C ペイロード
 * (2026-11-10 熱水の森の追記 4。ユーザー決定: 覆いの絵を 10 分の前後で分ける)。{@code corruption.HeatForestAdaptation} がログイン・次元の移動・
 * 適応したときに送り、クライアントは {@code client.HeatForestClockClient} が受けて、ネザーの覆いの絵をネザー版へ切り替える。
 */
public record HeatForestClockPayload(boolean adapted) implements CustomPacketPayload {

    public static final Type<HeatForestClockPayload> TYPE = new Type<>(OvergrownVegetation.id("heat_forest_clock"));

    public static final StreamCodec<FriendlyByteBuf, HeatForestClockPayload> STREAM_CODEC =
            StreamCodec.ofMember(HeatForestClockPayload::write, HeatForestClockPayload::new);

    private HeatForestClockPayload(FriendlyByteBuf buf) {
        this(buf.readBoolean());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeBoolean(adapted);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
