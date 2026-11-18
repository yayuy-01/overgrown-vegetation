package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.difficulty.DifficultyValues;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * クライアント → サーバー。設定のページで「完了」が押された。<b>ワールドの値をまるごと</b>送る(入っていない項目は既定値に戻す)。
 * サーバー側で {@link com.yayuy.overgrownvegetation.difficulty.WorldDifficultySync#apply} が権限を確かめてから書く
 * (範囲に収めるのは読むとき {@link DifficultyValues#read})。
 */
public record WorldDifficultyApplyPayload(DifficultyValues values) implements CustomPacketPayload {

    public static final Type<WorldDifficultyApplyPayload> TYPE =
            new Type<>(OvergrownVegetation.id("world_difficulty_apply"));

    public static final StreamCodec<FriendlyByteBuf, WorldDifficultyApplyPayload> STREAM_CODEC =
            StreamCodec.ofMember((p, buf) -> p.values.write(buf), buf -> new WorldDifficultyApplyPayload(DifficultyValues.read(buf)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
