package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 卓越風の入力(S2C、2026-10-23 胞子の場と風 第 2 段 B)。入ったとき・雨と雷が切り替わったときに送る
 * ({@code spore/field/WindWeatherData})。クライアントは同じ式で卓越風の向きを出し、近距離の突風の土台にする。
 *
 * @param windSeed 風の種(ワールドのシードを混ぜた値)
 * @param lastRainStart 直近の雨の始まり(オーバーワールドの dayTime。無ければ Long.MIN_VALUE)。ほかの 3 つも同じ
 */
public record PrevailingWindPayload(long windSeed, boolean raining, boolean thundering,
                                    long lastRainStart, long lastRainEnd, long prevRainStart, long prevRainEnd)
        implements CustomPacketPayload {

    public static final Type<PrevailingWindPayload> TYPE = new Type<>(OvergrownVegetation.id("prevailing_wind"));

    public static final StreamCodec<FriendlyByteBuf, PrevailingWindPayload> STREAM_CODEC =
            StreamCodec.ofMember(PrevailingWindPayload::write, PrevailingWindPayload::new);

    private PrevailingWindPayload(FriendlyByteBuf buf) {
        this(buf.readLong(), buf.readBoolean(), buf.readBoolean(), buf.readLong(), buf.readLong(), buf.readLong(), buf.readLong());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeLong(windSeed);
        buf.writeBoolean(raining);
        buf.writeBoolean(thundering);
        buf.writeLong(lastRainStart);
        buf.writeLong(lastRainEnd);
        buf.writeLong(prevRainStart);
        buf.writeLong(prevRainEnd);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
