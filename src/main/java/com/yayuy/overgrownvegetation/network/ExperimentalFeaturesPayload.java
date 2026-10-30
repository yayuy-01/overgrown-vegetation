package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * サーバー → クライアント。このワールドで実験的要素がオンか(2026-10-09)。ログインのときと、設定の画面・コマンドで切り替わったときに送る。
 * クライアントはクリエイティブタブに同盟軍のアイテムを出すかをこれで決める
 * ({@link com.yayuy.overgrownvegetation.difficulty.ExperimentalFeatures})。
 */
public record ExperimentalFeaturesPayload(boolean enabled) implements CustomPacketPayload {

    public static final Type<ExperimentalFeaturesPayload> TYPE =
            new Type<>(OvergrownVegetation.id("experimental_features"));

    public static final StreamCodec<ByteBuf, ExperimentalFeaturesPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, ExperimentalFeaturesPayload::enabled,
            ExperimentalFeaturesPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
