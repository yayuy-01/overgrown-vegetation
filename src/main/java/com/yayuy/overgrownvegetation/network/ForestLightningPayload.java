package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 汚染の森に雷が落ちた瞬間(2026-11-16 weather-system、雷雨の強化「落雷した汚染の森が光って胞子を吹き上げる」)。サーバーはその升の胞子を
 * 吹き上げ、256 ブロック以内のプレイヤーへこれを 1 本の雷につき 1 通送る。光と吹き上がりの見た目はクライアント
 * ({@code client.atmosphere.WeatherClient#lightningListener})。
 *
 * @param pos     バニラの雷が当たった位置(葉を含む高さ。木のある所では林冠の上)
 * @param groundY 葉を除いた地面の高さ(その x, z の MOTION_BLOCKING_NO_LEAVES)
 * @param power   汚染の強さ 0〜1(繁茂 0.5・過繁茂 1)
 */
public record ForestLightningPayload(BlockPos pos, int groundY, float power) implements CustomPacketPayload {

    public static final Type<ForestLightningPayload> TYPE = new Type<>(OvergrownVegetation.id("forest_lightning"));

    public static final StreamCodec<FriendlyByteBuf, ForestLightningPayload> STREAM_CODEC =
            StreamCodec.ofMember(ForestLightningPayload::write, ForestLightningPayload::new);

    private ForestLightningPayload(FriendlyByteBuf buf) {
        this(buf.readBlockPos(), buf.readVarInt(), buf.readFloat());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeVarInt(groundY);
        buf.writeFloat(power);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
