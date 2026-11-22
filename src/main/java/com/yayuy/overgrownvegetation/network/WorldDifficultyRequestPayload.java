package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.network.FriendlyByteBuf;
import com.yayuy.overgrownvegetation.port.network.StreamCodec;
import com.yayuy.overgrownvegetation.port.network.CustomPacketPayload;

/**
 * クライアント → サーバー。設定のページを開きたい: このワールドの値を送ってほしい
 * (返事は {@link WorldDifficultySyncPayload}、それを受けてクライアントが画面を開く)。
 */
public record WorldDifficultyRequestPayload() implements CustomPacketPayload {

    public static final WorldDifficultyRequestPayload INSTANCE = new WorldDifficultyRequestPayload();

    public static final Type<WorldDifficultyRequestPayload> TYPE =
            new Type<>(OvergrownVegetation.id("world_difficulty_request"));

    public static final StreamCodec<FriendlyByteBuf, WorldDifficultyRequestPayload> STREAM_CODEC =
            StreamCodec.unit(INSTANCE);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
