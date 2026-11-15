package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * クリペイムネスに抱え込まれた本人への知らせ(S2C、2026-11-15。サルティムネスの押さえ込み {@link SaltimnesPinPayload} にそろえた【仮・全体管理】)。
 * {@link #START} 抱えられた瞬間(視線を頭へ一度だけ向ける)/ {@link #SQUEEZE} 締められた・噛まれた(画面を小さく揺らす)/ {@link #END} 放された。
 *
 * @param event 出来事
 * @param mob   抱えたクリペイムネスのエンティティ id
 */
public record ClipeimnesHoldPayload(byte event, int mob) implements CustomPacketPayload {

    public static final byte START = 0, SQUEEZE = 1, END = 2;

    public static final Type<ClipeimnesHoldPayload> TYPE = new Type<>(OvergrownVegetation.id("clipeimnes_hold"));

    public static final StreamCodec<FriendlyByteBuf, ClipeimnesHoldPayload> STREAM_CODEC =
            StreamCodec.ofMember(ClipeimnesHoldPayload::write, ClipeimnesHoldPayload::new);

    private ClipeimnesHoldPayload(FriendlyByteBuf buf) {
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
