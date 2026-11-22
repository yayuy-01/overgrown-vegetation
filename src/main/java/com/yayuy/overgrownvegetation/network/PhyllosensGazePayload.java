package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 監視塔フィロセンスに見られている知らせ(S2C、2026-11-14。見られている本人だけへ)。画面の縁の効果({@code client/PhyllosensGazeEdge})の土台。
 *
 * <p>出来事 {@code event}:
 * <ul>
 *   <li>{@link #START} 視線が通り始めた(この塔に見え始めた)</li>
 *   <li>{@link #SEEN} 見えている(塔の見る間隔 = 5 tick ごと。強さ付き)</li>
 *   <li>{@link #MARK} 印が付いた瞬間(この見え始めから 2 秒。見え直すたびに 1 回)</li>
 *   <li>{@link #LOST} 視線が切れた(隠れた・塔が閉じた・倒れた)</li>
 * </ul>
 *
 * @param event    出来事
 * @param tower    塔のエンティティ id(いくつもの塔に見られているときの区別)
 * @param strength 0〜1(見え始め 0 → 印の 2 秒で 1 → 見られている間 1)
 * @param sinceSeen この塔の見え始めからの tick(-1 = 分からない)。知らせが途切れた後(ひとり用の一時停止・通信の詰まり)に画面側が見え始めを戻すため(11-14 版 3)
 * @param sinceMark この見え始めで印が付いてからの tick(-1 = まだ)。途切れた後に画面側が印の瞬間を戻すため(印の知らせは 1 回しか来ない)
 */
public record PhyllosensGazePayload(byte event, int tower, float strength, int sinceSeen, int sinceMark) implements CustomPacketPayload {

    public static final byte START = 0, SEEN = 1, MARK = 2, LOST = 3;

    public static final Type<PhyllosensGazePayload> TYPE = new Type<>(OvergrownVegetation.id("phyllosens_gaze"));

    public static final StreamCodec<FriendlyByteBuf, PhyllosensGazePayload> STREAM_CODEC =
            StreamCodec.ofMember(PhyllosensGazePayload::write, PhyllosensGazePayload::new);

    /** 見え始め・印からの tick を付けない形(版 2 までと同じ。確かめ用の devbridge/GazeDev が使う)。 */
    public PhyllosensGazePayload(byte event, int tower, float strength) {
        this(event, tower, strength, -1, -1);
    }

    private PhyllosensGazePayload(FriendlyByteBuf buf) {
        this(buf.readByte(), buf.readVarInt(), buf.readFloat(), buf.readVarInt() - 1, buf.readVarInt() - 1);
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeByte(event);
        buf.writeVarInt(tower);
        buf.writeFloat(strength);
        buf.writeVarInt(Math.max(-1, sinceSeen) + 1);
        buf.writeVarInt(Math.max(-1, sinceMark) + 1);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
