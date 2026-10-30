package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.ResearchState;
import com.yayuy.overgrownvegetation.data.ResearchTable;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * サーバー → クライアント。プレイヤーの研究の進捗(ポイント + 開放済みノード)と
 * 研究ツリーの定義をまとめて送る。ログイン時と、ポイント/開放が変化するたびに再送。
 *
 * <p>2026-09-24 軽量化(監査 2033): ツリーの定義(全ノードの名前・説明文・配置)が変わるのは
 * {@code /ov design reload} のときだけなので、定義を載せるのはログインと design reload のときだけにした
 * ({@link #hasTree} = true、{@link #of})。解析・開放・リスポーン・次元移動では進捗だけを送る
 * ({@link #stateOnly})。クライアントは定義の無い回では手元の定義をそのまま使うので、表示は同じ。
 */
public record ResearchSyncPayload(int points, List<String> unlocked, boolean hasTree, List<NodeView> nodes,
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
            StreamCodec.ofMember(ResearchSyncPayload::write, ResearchSyncPayload::read);

    /** 進捗だけ(ツリーの定義は載せない。クライアントは前に受け取った定義を使い続ける)。 */
    public static ResearchSyncPayload stateOnly(ResearchState state) {
        return new ResearchSyncPayload(state.points(), new ArrayList<>(state.unlocked()), false, List.of(),
                0, 0, 0, 0);
    }

    /** 進捗 + ツリーの定義。 */
    public static ResearchSyncPayload of(ResearchTable table, ResearchState state) {
        return of(table, state, node -> true);
    }

    /** 進捗 + ツリーの定義のうち {@code shown} のノードだけ(実験的要素がオフのワールドで同盟軍のノードを隠す、2026-10-09)。 */
    public static ResearchSyncPayload of(ResearchTable table, ResearchState state, Predicate<ResearchTable.Node> shown) {
        List<NodeView> views = new ArrayList<>();
        for (ResearchTable.Node n : table.nodes().values()) {
            if (!shown.test(n)) {
                continue;
            }
            views.add(new NodeView(n.id(), n.name(), n.description(),
                    n.icon().type(), n.icon().id(), n.cost(), n.tier(), n.analyzeSlots(), n.x(), n.y(),
                    n.parents(), n.unlocks(), n.anyParent(), n.exclusiveGroup(), n.width(), n.height()));
        }
        ResearchTable.Layout layout = table.layout();
        return new ResearchSyncPayload(state.points(), new ArrayList<>(state.unlocked()), true, views,
                layout.nodeWidth(), layout.nodeHeight(), layout.spacingX(), layout.spacingY());
    }

    private static ResearchSyncPayload read(FriendlyByteBuf buf) {
        int points = buf.readVarInt();
        List<String> unlocked = buf.readList(FriendlyByteBuf::readUtf);
        if (!buf.readBoolean()) {
            return new ResearchSyncPayload(points, unlocked, false, List.of(), 0, 0, 0, 0);
        }
        List<NodeView> nodes = buf.readList(ResearchSyncPayload::readNode);
        int nodeWidth = buf.readVarInt();
        int nodeHeight = buf.readVarInt();
        int spacingX = buf.readVarInt();
        int spacingY = buf.readVarInt();
        return new ResearchSyncPayload(points, unlocked, true, nodes, nodeWidth, nodeHeight, spacingX, spacingY);
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(points);
        buf.writeCollection(unlocked, FriendlyByteBuf::writeUtf);
        buf.writeBoolean(hasTree);
        if (!hasTree) {
            return;
        }
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
