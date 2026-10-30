package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * クライアント → サーバー。腰に下げた陽溜まりのランタンを灯す・消すキー(2026-10-16、①)。中身は無い。
 * サーバーは腰の枠に本当にランタンがあるかを確かめてから切り替える({@code defense/SunlitLanterns#toggleWorn})。
 */
public record SunlitLanternTogglePayload() implements CustomPacketPayload {

    public static final Type<SunlitLanternTogglePayload> TYPE = new Type<>(OvergrownVegetation.id("sunlit_lantern_toggle"));

    public static final StreamCodec<ByteBuf, SunlitLanternTogglePayload> STREAM_CODEC = StreamCodec.unit(new SunlitLanternTogglePayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
