package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * プレイヤーの浸食度(S2C、2026-09-22)。<b>本人と、そのプレイヤーを追跡している全員</b>へ送る ——
 * 苔むした見た目は他人からも見える必要があるため。
 *
 * <p>送るのは<b>整数 % が変わったときだけ</b>(1 分に 1% なので、多くても 1 分に 1 回)。
 * ほかに、誰かがそのプレイヤーを追跡し始めたとき・ログイン・リスポーン・次元移動でも 1 回送る。
 *
 * @param entityId 対象のプレイヤーのエンティティ id
 * @param percent  浸食度(0〜100)
 */
public record ErosionPayload(int entityId, float percent) implements CustomPacketPayload {

    public static final Type<ErosionPayload> TYPE = new Type<>(OvergrownVegetation.id("erosion"));

    public static final StreamCodec<FriendlyByteBuf, ErosionPayload> STREAM_CODEC =
            StreamCodec.ofMember(ErosionPayload::write, ErosionPayload::new);

    private ErosionPayload(FriendlyByteBuf buf) {
        this(buf.readVarInt(), buf.readFloat());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(entityId);
        buf.writeFloat(percent);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
