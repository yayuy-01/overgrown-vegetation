package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * サーバー → クライアントへ、<b>フェーズが上がったときの合図</b>を始める命令(2026-11-16。ユーザー決定の中身は Vault「フェーズの仕組み」)。
 * 送るのは {@code phase/PhaseCues}(フェーズが上がったとき全員へ)と {@code /ov fx phasecue}(確かめ用、打った人だけ)。
 * 受けるのは {@code client/atmosphere/PhaseCue}。
 *
 * <ul>
 *     <li>{@code phase}: 上がった先のフェーズ(フェーズごとの印を選ぶ)。</li>
 *     <li>{@code hasForest}・{@code dirX}・{@code dirZ}: そのプレイヤーから見た、いちばん近い汚染の森の方角(長さ 1 の水平の向き)。
 *     クライアントは森の場所を知らないので、サーバーが台帳から出す。森が無い・別のディメンションなら {@code hasForest} = false(ざわめきは鳴らさない)。</li>
 *     <li>{@code quiet}: 地鳴りの音を鳴らさない(F1 は地割れの音がすでに鳴るので、重ねない)。</li>
 * </ul>
 */
public record PhaseCuePayload(int phase, boolean hasForest, float dirX, float dirZ, boolean quiet) implements CustomPacketPayload {

    public static final Type<PhaseCuePayload> TYPE = new Type<>(OvergrownVegetation.id("phase_cue"));

    public static final StreamCodec<FriendlyByteBuf, PhaseCuePayload> STREAM_CODEC =
            StreamCodec.ofMember(PhaseCuePayload::write, PhaseCuePayload::new);

    private PhaseCuePayload(FriendlyByteBuf buf) {
        this(buf.readVarInt(), buf.readBoolean(), buf.readFloat(), buf.readFloat(), buf.readBoolean());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(phase);
        buf.writeBoolean(hasForest);
        buf.writeFloat(dirX);
        buf.writeFloat(dirZ);
        buf.writeBoolean(quiet);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
