package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 捕まえて乗せる Mob({@code entity/CaptorMount}: サルティムネスの押さえ込み・クリペイムネスの抱え込み)に捕まった本人への知らせ(S2C)。
 * 2026-11-16 コードの見直し ①14 で、種ごとの 2 つ(saltimnes_pin・clipeimnes_hold。中身は同じ)をまとめた。
 * {@link #START} 捕まった瞬間(視線を頭へ一度だけ向ける。サルティムネスは地面へ押し倒す)/ {@link #HIT} 噛まれた・締められた(画面を小さく揺らす)/
 * {@link #END} 放された(押し倒しを戻す)。種ごとの違いは {@code client/CaptorCamera}。
 *
 * @param event 出来事
 * @param mob   捕まえた Mob のエンティティ id
 */
public record CaptorPayload(byte event, int mob) implements CustomPacketPayload {

    public static final byte START = 0, HIT = 1, END = 2;

    public static final Type<CaptorPayload> TYPE = new Type<>(OvergrownVegetation.id("captor"));

    public static final StreamCodec<FriendlyByteBuf, CaptorPayload> STREAM_CODEC =
            StreamCodec.ofMember(CaptorPayload::write, CaptorPayload::new);

    private CaptorPayload(FriendlyByteBuf buf) {
        this(buf.readByte(), buf.readVarInt());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeByte(this.event);
        buf.writeVarInt(this.mob);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
