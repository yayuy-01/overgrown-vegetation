package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * サーバー → クライアントへ、まわりの生きている巨大樹の一覧(2026-11-06 巨大樹の改善案 ① 胞子の柱・③ 夜の地脈の光)。
 * {@code spore.field.GiantTreeSync} が 5 秒ごとに、変わったときだけ送り、クライアントは {@code client.atmosphere.GiantTrees} が受ける。
 *
 * <ul>
 *     <li>{@code running}: 胞子の場が動いているか(オーバーワールド・場が有効・{@code /ov corruption false} でない)。false なら柱も光も出さない。</li>
 *     <li>{@code x[i], z[i]}: 根元(巨大樹を生やした所、{@code PhaseData} の記録)。ハイヴノードを倒された木は入らない。</li>
 *     <li>{@code spore[i]}: 梢の 1 日の放出 ÷ 成木の放出(朝の上乗せは入れない。胞子の場の発生源の上限 maxTrees から外れた木は 0)。</li>
 *     <li>{@code ley[i]}: 蓄えた地脈の度合い 0〜1。<b>仮の物差し</b>: ハイヴノードの貯蓄はまだ無いので、育ち具合 × 地脈の線への近さ({@code LeyLines})。</li>
 * </ul>
 */
public record GiantTreesPayload(boolean running, int[] x, int[] z, float[] spore, float[] ley) implements CustomPacketPayload {

    public static final Type<GiantTreesPayload> TYPE = new Type<>(OvergrownVegetation.id("giant_trees"));

    public static final StreamCodec<FriendlyByteBuf, GiantTreesPayload> STREAM_CODEC =
            StreamCodec.ofMember(GiantTreesPayload::write, GiantTreesPayload::read);

    private static GiantTreesPayload read(FriendlyByteBuf buf) {
        boolean running = buf.readBoolean();
        int n = Math.min(buf.readVarInt(), 512);
        int[] x = new int[n];
        int[] z = new int[n];
        float[] spore = new float[n];
        float[] ley = new float[n];
        for (int i = 0; i < n; i++) {
            x[i] = buf.readInt();
            z[i] = buf.readInt();
            spore[i] = buf.readFloat();
            ley[i] = buf.readFloat();
        }
        return new GiantTreesPayload(running, x, z, spore, ley);
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeBoolean(running);
        buf.writeVarInt(x.length);
        for (int i = 0; i < x.length; i++) {
            buf.writeInt(x[i]);
            buf.writeInt(z[i]);
            buf.writeFloat(spore[i]);
            buf.writeFloat(ley[i]);
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
