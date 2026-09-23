package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.ResearchState;
import com.yayuy.overgrownvegetation.data.ResearchTable;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;

/**
 * サーバー → クライアント。プレイヤーの研究の進捗(ポイント + 開放済みノード)と
 * 研究ツリーの定義をまとめて送る。ログイン時と、ポイント/開放が変化するたびに再送。
 */
public record ResearchSyncPayload(int points, List<String> unlocked, List<NodeView> nodes,
                                  int nodeWidth, int nodeHeight, int spacingX, int spacingY)
        implements CustomPacketPayload {

    /**
     * @param anyParent      親のどれか1つでよい(OR)か。false = 全部必要(AND)。
     * @param exclusiveGroup 二者択一グループ名(空 = 排他なし)。同 tier で同名のノードは1つだけ開放できる。
     * @param width          このノードの枠サイズ(サーバー側で解決済み — 個別指定が無ければ layout の既定値)。
     */
    public record NodeView(String id, String name, String description,
                           String iconType, String iconId, int cost, int tier, int analyzeSlots,
                           double x, double y, List<String> parents, List<String> unlocks,
                           boolean anyParent, String exclusiveGroup, int width, int height) {
    }

    public static final Type<ResearchSyncPayload> TYPE =
            new Type<>(OvergrownVegetation.id("research_sync"));

    public static final StreamCodec<FriendlyByteBuf, ResearchSyncPayload> STREAM_CODEC =
            StreamCodec.ofMember(ResearchSyncPayload::write, ResearchSyncPayload::new);

    public static ResearchSyncPayload of(ResearchTable table, ResearchState state) {
        List<NodeView> views = new ArrayList<>();
        for (ResearchTable.Node n : table.nodes().values()) {
            views.add(new NodeView(n.id(), n.name(), n.description(),
                    n.icon().type(), n.icon().id(), n.cost(), n.tier(), n.analyzeSlots(), n.x(), n.y(),
                    n.parents(), n.unlocks(), n.anyParent(), n.exclusiveGroup(), n.width(), n.height()));
        }
        ResearchTable.Layout layout = table.layout();
        return new ResearchSyncPayload(state.points(), new ArrayList<>(state.unlocked()), views,
                layout.nodeWidth(), layout.nodeHeight(), layout.spacingX(), layout.spacingY());
    }

    private ResearchSyncPayload(FriendlyByteBuf buf) {
        this(buf.readVarInt(),
                buf.readList(FriendlyByteBuf::readUtf),
                buf.readList(ResearchSyncPayload::readNode),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(points);
        buf.writeCollection(unlocked, FriendlyByteBuf::writeUtf);
        buf.writeCollection(nodes, ResearchSyncPayload::writeNode);
        buf.writeVarInt(nodeWidth);
        buf.writeVarInt(nodeHeight);
        buf.writeVarInt(spacingX);
        buf.writeVarInt(spacingY);
    }

    private static NodeView readNode(FriendlyByteBuf buf) {
        return new NodeView(
                buf.readUtf(), buf.readUtf(), buf.readUtf(),
                buf.readUtf(), buf.readUtf(),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readDouble(), buf.readDouble(),
                buf.readList(FriendlyByteBuf::readUtf),
                buf.readList(FriendlyByteBuf::readUtf),
                buf.readBoolean(),
                buf.readUtf(),
                buf.readVarInt(),
                buf.readVarInt());
    }

    private static void writeNode(FriendlyByteBuf buf, NodeView n) {
        buf.writeUtf(n.id());
        buf.writeUtf(n.name());
        buf.writeUtf(n.description());
        buf.writeUtf(n.iconType());
        buf.writeUtf(n.iconId());
        buf.writeVarInt(n.cost());
        buf.writeVarInt(n.tier());
        buf.writeVarInt(n.analyzeSlots());
        buf.writeDouble(n.x());
        buf.writeDouble(n.y());
        buf.writeCollection(n.parents(), FriendlyByteBuf::writeUtf);
        buf.writeCollection(n.unlocks(), FriendlyByteBuf::writeUtf);
        buf.writeBoolean(n.anyParent());
        buf.writeUtf(n.exclusiveGroup());
        buf.writeVarInt(n.width());
        buf.writeVarInt(n.height());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
