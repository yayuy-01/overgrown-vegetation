package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * クライアント → サーバー。<b>砲台に乗っている間</b>の操作。
 * <ul>
 *   <li>{@link #ACTION_FIRE} — 攻撃キー: 撃つ。</li>
 *   <li>{@link #ACTION_CHARGE} — ホイール: 装薬を {@code value} だけ増減する。</li>
 * </ul>
 *
 * <p>照準(方位・仰角)はこのパケットでは送らない — 騎乗者の視線そのものなので、バニラが配る
 * プレイヤーの向きからサーバーもクライアントも同じ角度を出せる
 * ({@link com.yayuy.overgrownvegetation.block.SiegeCannonBlockEntity#aimAzimuth})。
 *
 * <p>対象の砲台はプレイヤーが乗っている座席から引くので、座標は送らない(なりすまし防止も兼ねる)。
 */
public record CannonControlPayload(int action, int value) implements CustomPacketPayload {

    public static final int ACTION_FIRE = 0;
    public static final int ACTION_CHARGE = 1;

    public static final Type<CannonControlPayload> TYPE =
            new Type<>(OvergrownVegetation.id("cannon_control"));

    public static final StreamCodec<FriendlyByteBuf, CannonControlPayload> STREAM_CODEC =
            StreamCodec.ofMember(CannonControlPayload::write, CannonControlPayload::new);

    private CannonControlPayload(FriendlyByteBuf buf) {
        this(buf.readVarInt(), buf.readVarInt());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(action);
        buf.writeVarInt(value);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
