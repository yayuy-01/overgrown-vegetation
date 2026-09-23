package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.network.ResearchSyncPayload;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * クライアント側にキャッシュされた「自分の研究の進捗」+ 研究ツリー定義。
 * {@link ResearchSyncPayload} 受信で更新される。
 */
public final class ClientResearch {

    private static int points;
    private static Set<String> unlocked = new HashSet<>();
    private static List<ResearchSyncPayload.NodeView> nodes = List.of();
    // サーバーから同期される表示レイアウト。未同期時は現行既定値と同じ値で初期化。
    private static int nodeWidth = 120;
    private static int nodeHeight = 28;
    private static int spacingX = 172;
    private static int spacingY = 80;

    public static void accept(ResearchSyncPayload payload) {
        points = payload.points();
        unlocked = new HashSet<>(payload.unlocked());
        nodes = List.copyOf(payload.nodes());
        nodeWidth = payload.nodeWidth();
        nodeHeight = payload.nodeHeight();
        spacingX = payload.spacingX();
        spacingY = payload.spacingY();
    }

    public static int points() {
        return points;
    }

    public static List<ResearchSyncPayload.NodeView> nodes() {
        return nodes;
    }

    public static int nodeWidth() {
        return nodeWidth;
    }

    public static int nodeHeight() {
        return nodeHeight;
    }

    public static int spacingX() {
        return spacingX;
    }

    public static int spacingY() {
        return spacingY;
    }

    public static boolean isUnlocked(String nodeId) {
        return unlocked.contains(nodeId);
    }

    /**
     * 前提(親)が満たされているか(ポイントは見ない — 「開放できる段階に来ているか」)。
     * {@code anyParent} のノードは親のどれか1つ、そうでなければ全部が開放済みである必要がある。
     * サーバー側 {@code ResearchTable#parentsSatisfied} と同じ判定。
     */
    public static boolean parentsSatisfied(ResearchSyncPayload.NodeView node) {
        if (node.parents().isEmpty()) {
            return true;
        }
        if (node.anyParent()) {
            for (String p : node.parents()) {
                if (unlocked.contains(p)) {
                    return true;
                }
            }
            return false;
        }
        for (String p : node.parents()) {
            if (!unlocked.contains(p)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 二者択一グループで既に別のノードを選んでいるか。
     *
     * @return 先に開放されている同グループのノード id。競合が無ければ null。
     */
    public static String exclusiveConflict(ResearchSyncPayload.NodeView node) {
        if (node.exclusiveGroup().isEmpty()) {
            return null;
        }
        for (ResearchSyncPayload.NodeView o : nodes) {
            if (!o.id().equals(node.id()) && o.tier() == node.tier()
                    && node.exclusiveGroup().equals(o.exclusiveGroup()) && unlocked.contains(o.id())) {
                return o.id();
            }
        }
        return null;
    }

    /** 今すぐ開放できるか(未開放 + 前提を満たす + 排他で潰れていない + ポイント足りる)。 */
    public static boolean canUnlock(ResearchSyncPayload.NodeView node) {
        return !isUnlocked(node.id()) && parentsSatisfied(node)
                && exclusiveConflict(node) == null && points >= node.cost();
    }

    private ClientResearch() {
    }
}
