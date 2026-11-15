package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * サルティムネスに押さえ込まれた本人への知らせ(S2C、2026-11-15。カメラの案 b = ユーザー決定)。
 * {@link #START} 押さえ込まれた瞬間(地面へ押し倒し、視線を真上の頭へ一度だけ向ける)/ {@link #BITE} 噛まれた(画面を小さく揺らす)/
 * {@link #END} 放された(押し倒しを戻す)。
 *
 * @param event 出来事
 * @param mob   押さえ込んだサルティムネスのエンティティ id
 */
public record SaltimnesPinPayload(byte event, int mob) implements CustomPacketPayload {

    public static final byte START = 0, BITE = 1, END = 2;

    public static final Type<SaltimnesPinPayload> TYPE = new Type<>(OvergrownVegetation.id("saltimnes_pin"));

    public static final StreamCodec<FriendlyByteBuf, SaltimnesPinPayload> STREAM_CODEC =
            StreamCodec.ofMember(SaltimnesPinPayload::write, SaltimnesPinPayload::new);

    private SaltimnesPinPayload(FriendlyByteBuf buf) {
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
