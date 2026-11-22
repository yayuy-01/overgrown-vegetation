package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.network.FriendlyByteBuf;
import com.yayuy.overgrownvegetation.port.network.StreamCodec;
import com.yayuy.overgrownvegetation.port.network.CustomPacketPayload;

/**
 * サーバー → クライアントへ、雰囲気の出来事の確かめ用の命令(2026-11-08、{@code /ov fx}。G4 胞子の風・G5 光る胞子の夜)。
 * 見た目はクライアントだけで決まるので、コマンドを打ったプレイヤーのクライアントへ渡す。
 * action: 0 = 胞子の風を起こす、1 = 光る胞子の夜(arg 0 = 自然に任せる・1 = 夜なら起こす・2 = 起こさない)、2 = 様子を表示。
 */
public record AtmosphereFxPayload(int action, int arg) implements CustomPacketPayload {

    public static final int GALE = 0;
    public static final int GLOW_NIGHT = 1;
    public static final int STATUS = 2;
    /** 怖い現象 A・D・E の確かめ(11-16): 林冠の影を通す・霧の影を 1 体出す・遠くの音を 1 回鳴らす。 */
    public static final int SCARY_SHADOW = 3;
    public static final int SCARY_FIGURE = 4;
    public static final int SCARY_FAR_SOUND = 5;

    public static final Type<AtmosphereFxPayload> TYPE = new Type<>(OvergrownVegetation.id("atmosphere_fx"));

    public static final StreamCodec<FriendlyByteBuf, AtmosphereFxPayload> STREAM_CODEC =
            StreamCodec.ofMember(AtmosphereFxPayload::write, AtmosphereFxPayload::new);

    private AtmosphereFxPayload(FriendlyByteBuf buf) {
        this(buf.readVarInt(), buf.readVarInt());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(action);
        buf.writeVarInt(arg);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
