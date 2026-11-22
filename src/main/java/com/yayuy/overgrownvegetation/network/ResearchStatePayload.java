package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import com.yayuy.overgrownvegetation.port.network.StreamCodec;
import com.yayuy.overgrownvegetation.port.network.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;

/**
 * サーバー → クライアントへ、研究の状態(2026-11-17 研究台と研究ツリー)。研究台を右クリックしたとき({@code open} = true。研究ツリーの
 * JSON を付けて画面を開く)と、画面を開いている間 10 tick ごと・始めた/やめた/開いたとき({@code open} = false。開いている画面だけ書き換える)。
 *
 * @param open       画面を開くか(true なら treeJson が付く)
 * @param bench      研究台の位置
 * @param treeJson   研究ツリーの JSON(open のときだけ)
 * @param done       開いた節(研究台の節は入らない)
 * @param active     走っている研究の節("" = 無し)
 * @param progress   その進み(tick)
 * @param activeHere 走っている研究がこの研究台か
 * @param energy     研究台にためた地脈の力(FE)
 * @param buffer     ためられる上限(FE)
 * @param instruments 器具の印(1 = 段 2 の器具あり、2 = 段 3 の器具あり)
 */
public record ResearchStatePayload(boolean open, BlockPos bench, String treeJson, List<String> done, String active,
                                   int progress, boolean activeHere, int energy, int buffer, int instruments)
        implements CustomPacketPayload {

    public static final Type<ResearchStatePayload> TYPE = new Type<>(OvergrownVegetation.id("research_state"));

    public static final StreamCodec<FriendlyByteBuf, ResearchStatePayload> STREAM_CODEC =
            StreamCodec.ofMember(ResearchStatePayload::write, ResearchStatePayload::read);

    private static ResearchStatePayload read(FriendlyByteBuf buf) {
        boolean open = buf.readBoolean();
        BlockPos bench = buf.readBlockPos();
        String json = buf.readUtf(1 << 20);
        int n = buf.readVarInt();
        List<String> done = new ArrayList<>(n);
        for (int i = 0; i < n; i++) done.add(buf.readUtf());
        return new ResearchStatePayload(open, bench, json, done, buf.readUtf(), buf.readVarInt(), buf.readBoolean(),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeBoolean(open);
        buf.writeBlockPos(bench);
        buf.writeUtf(treeJson, 1 << 20);
        buf.writeVarInt(done.size());
        for (String s : done) buf.writeUtf(s);
        buf.writeUtf(active);
        buf.writeVarInt(progress);
        buf.writeBoolean(activeHere);
        buf.writeVarInt(energy);
        buf.writeVarInt(buffer);
        buf.writeVarInt(instruments);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
