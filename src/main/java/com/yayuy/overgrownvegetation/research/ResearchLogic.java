package com.yayuy.overgrownvegetation.research;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.ResearchState;
import com.yayuy.overgrownvegetation.data.ResearchTable;
import com.yayuy.overgrownvegetation.difficulty.ExperimentalFeatures;
import com.yayuy.overgrownvegetation.network.ResearchSyncPayload;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 研究システムのサーバー側ロジック — 開放判定・レシピの封印判定・クライアントへの同期。
 */
public final class ResearchLogic {

    /** このプレイヤーが今使える解析枠の数(基本 1 + 開放ノードのボーナス、上限 {@link com.yayuy.overgrownvegetation.menu.ResearchUi#ANALYZE_MAX})。 */
    public static int analyzeSlotCount(net.minecraft.world.entity.player.Player player) {
        int n = DesignData.research().analyzeSlotsTotal(player.getData(ModAttachments.RESEARCH).unlocked());
        return Math.max(1, Math.min(com.yayuy.overgrownvegetation.menu.ResearchUi.ANALYZE_MAX, n));
    }

    /**
     * {@code recipeId} のレシピが、このプレイヤーにとって(研究未開放で)封印されているか。
     * 実験的要素がオフのワールドでは、成果物が同盟軍のアイテムのレシピも封印する(研究を開放していても。{@link ExperimentalFeatures#locksRecipe})。
     */
    public static boolean isRecipeLocked(Player player, ResourceLocation recipeId) {
        if (ExperimentalFeatures.locksRecipe(player.level(), recipeId)) {
            return true;
        }
        String node = DesignData.research().nodeForRecipe(recipeId);
        if (node == null) {
            return false;
        }
        return !player.getData(ModAttachments.RESEARCH).unlocked().contains(node);
    }

    /**
     * {@code recipeId} のレシピが、<b>サーバーの誰にとっても</b>封印されているか。
     *
     * <p>かまど・溶鉱炉・燻製器のように「作った人」が決まらない加工に使う。
     * 接続中のプレイヤーが 1 人でも開放していれば通す(シングルプレイならそのプレイヤー本人の判定になる)。
     */
    public static boolean isRecipeLockedForAll(MinecraftServer server, ResourceLocation recipeId) {
        if (ExperimentalFeatures.locksRecipe(server.overworld(), recipeId)) {
            return true;
        }
        String node = DesignData.research().nodeForRecipe(recipeId);
        if (node == null) {
            return false;
        }
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.getData(ModAttachments.RESEARCH).unlocked().contains(node)) {
                return false;
            }
        }
        return true;
    }

    /**
     * プレイヤーの研究状態(ポイント + 開放集合)をクライアントへ送る。ツリーの定義は載せない
     * (2026-09-24 軽量化、監査 2033。定義が変わるのは design reload だけで、そのときは {@link #syncAll} が載せる)。
     */
    public static void sync(ServerPlayer player) {
        ResearchState state = player.getData(ModAttachments.RESEARCH);
        PacketDistributor.sendToPlayer(player, ResearchSyncPayload.stateOnly(state));
    }

    /**
     * 研究状態 + ツリーの定義を送る(ログイン時・design reload・実験的要素の切り替え)。実験的要素がオフのワールドでは、
     * 同盟軍のアイテムだけを開くノードを送らない({@link ExperimentalFeatures#hidesNode})。
     */
    public static void syncWithTree(ServerPlayer player) {
        ResearchState state = player.getData(ModAttachments.RESEARCH);
        MinecraftServer server = player.server;
        PacketDistributor.sendToPlayer(player, ResearchSyncPayload.of(DesignData.research(), state,
                node -> !ExperimentalFeatures.hidesNode(server, node)));
    }

    public static void syncAll(MinecraftServer server) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            syncWithTree(p);
        }
    }

    /** クライアントからの開放要求を検証して実行する。 */
    public static void tryUnlock(ServerPlayer player, String nodeId) {
        ResearchTable table = DesignData.research();
        ResearchTable.Node node = table.node(nodeId);
        if (node == null) {
            return;
        }
        ResearchState state = player.getData(ModAttachments.RESEARCH);
        if (state.isUnlocked(nodeId)) {
            return;
        }
        if (ExperimentalFeatures.hidesNode(player.server, node)) {
            return; // 実験的要素がオフのワールドでは開かない(ツリーにも出していない)
        }
        if (!table.parentsSatisfied(nodeId, state.unlocked())) {
            return;
        }
        // 二者択一グループで既に別の枝を選んでいたら開放させない。
        if (table.exclusiveConflict(nodeId, state.unlocked()) != null) {
            return;
        }
        if (state.points() < node.cost()) {
            return;
        }
        state.spendPoints(node.cost());
        state.unlock(nodeId);
        player.setData(ModAttachments.RESEARCH, state);
        awardRecipes(player, node.unlocks());
        sync(player);
    }

    private static void awardRecipes(ServerPlayer player, List<String> recipeIds) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        List<RecipeHolder<?>> holders = new ArrayList<>();
        for (String raw : recipeIds) {
            ResourceLocation id = raw.contains(":")
                    ? ResourceLocation.tryParse(raw)
                    : ResourceLocation.fromNamespaceAndPath("overgrown_vegetation", raw);
            if (id == null) {
                continue;
            }
            Optional<RecipeHolder<?>> h = server.getRecipeManager().byKey(id);
            h.ifPresent(holders::add);
        }
        if (!holders.isEmpty()) {
            player.awardRecipes(holders);
        }
    }

    private ResearchLogic() {
    }
}
