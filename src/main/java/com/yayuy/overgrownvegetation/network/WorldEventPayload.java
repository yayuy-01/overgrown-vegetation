package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.worldevent.WorldEventKind;
import net.minecraft.network.FriendlyByteBuf;
import com.yayuy.overgrownvegetation.port.network.StreamCodec;
import com.yayuy.overgrownvegetation.port.network.CustomPacketPayload;
import org.jetbrains.annotations.Nullable;

/**
 * サーバー → クライアントへ「今どのワールドイベントが起きているか」(2026-11-16 world-events)。{@code worldevent.WorldEvents} が
 * 始まり・終わりのとき・5 秒ごと・ワールドに入ったとき・ディメンションを移ったときに送り、{@code client.worldevent.WorldEventClient} が受ける。
 * オーバーワールド以外・何も起きていないときは {@link #NONE}。
 *
 * <p>進み 0〜1 は送らず、始まりと終わりの時刻(オーバーワールドの dayTime)を送る: クライアントが自分の dayTime から毎フレーム滑らかに出す
 * (5 秒ごとの値だと日食がかくつくため)。
 *
 * @param kind    {@link WorldEventKind#ordinal()}。-1 = 何も起きていない
 * @param start   始まりの dayTime
 * @param end     終わりの dayTime
 * @param dayTime 送ったときのオーバーワールドの dayTime(クライアントの時刻のずれの確かめ用)
 */
public record WorldEventPayload(byte kind, long start, long end, long dayTime) implements CustomPacketPayload {

    public static final WorldEventPayload NONE = new WorldEventPayload((byte) -1, 0L, 0L, 0L);

    public static final Type<WorldEventPayload> TYPE = new Type<>(OvergrownVegetation.id("world_event"));

    public static final StreamCodec<FriendlyByteBuf, WorldEventPayload> STREAM_CODEC =
            StreamCodec.ofMember(WorldEventPayload::write, WorldEventPayload::new);

    private WorldEventPayload(FriendlyByteBuf buf) {
        this(buf.readByte(), buf.readLong(), buf.readLong(), buf.readLong());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeByte(kind);
        buf.writeLong(start);
        buf.writeLong(end);
        buf.writeLong(dayTime);
    }

    @Nullable
    public WorldEventKind eventKind() {
        return WorldEventKind.byIndex(kind);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
