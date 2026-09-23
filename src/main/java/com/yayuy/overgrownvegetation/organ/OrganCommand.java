package com.yayuy.overgrownvegetation.organ;

import com.mojang.brigadier.context.CommandContext;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.OrganTable;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /ov organ …} — 器官の確認用コマンド(OP、2026-09-23)。
 * <ul>
 *     <li>{@code status} … 生えている数・いま効いている<b>下限</b>・この場の上乗せ・いちばん近い器官</li>
 *     <li>{@code list} … 生えている器官の位置と育ち具合</li>
 *     <li>{@code site} … いちばん近い<b>候補地</b>(まだ生えていなくてもよい)。
 *     「極相にしたのに器官が生えない」ときに、そもそもここが候補地なのかを見る</li>
 *     <li>{@code grow} … いま立っているチャンクに候補地かどうかを無視して 1 つ生やす</li>
 *     <li>{@code clear} … 台帳を空にする(ブロックは残る)</li>
 * </ul>
 *
 * <p>器官は<b>わざと何も知らせない</b>設計なので、
 * 開発中にこれが無いと「効いているのかどうか」すら分からない。
 *
 * <p>出力は開発用なので {@code Component.literal}(翻訳キーを作らない)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class OrganCommand {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ov")
                .then(Commands.literal("organ")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("status").executes(OrganCommand::status))
                        .then(Commands.literal("list").executes(OrganCommand::list))
                        .then(Commands.literal("site").executes(OrganCommand::site))
                        .then(Commands.literal("grow").executes(OrganCommand::grow))
                        .then(Commands.literal("clear").executes(OrganCommand::clear))));
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            return 0;
        }
        ServerLevel level = player.serverLevel();
        OrganData data = OrganData.get(level);
        OrganTable table = DesignData.organs();
        OrganTable.SporeGland spec = table.sporeGland();
        ChunkPos here = new ChunkPos(player.blockPosition());

        reply(ctx, "器官: 生きている " + data.count() + " 個 / 枯れかけ " + data.withering().size()
                + (table.enabled() ? "" : " ※ organs.json で無効"));
        reply(ctx, "世界の下限: " + fmt(OrganInfluence.worldFloorPercent(level)) + "%"
                + "  (1 個あたり " + fmt(spec.worldFloorPercent())
                + "% / 頭打ち " + fmt(spec.worldFloorMaxPercent()) + "%)");
        reply(ctx, "この場の上乗せ: " + fmt(OrganInfluence.localPercentAt(level, here)) + "%"
                + "  (真上で " + fmt(spec.localPercent())
                + "% / " + spec.localReachChunks() + " チャンクで 0)");

        BlockPos nearest = data.nearest(player.blockPosition());
        if (nearest == null) {
            reply(ctx, "生えている器官はまだ無い。");
        } else {
            reply(ctx, "いちばん近い器官: " + nearest.toShortString()
                    + " (" + Math.round(Math.sqrt(nearest.distSqr(player.blockPosition()))) + " ブロック)");
        }
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            return 0;
        }
        ServerLevel level = player.serverLevel();
        OrganData data = OrganData.get(level);
        if (data.count() == 0) {
            reply(ctx, "生えている器官はまだ無い。");
            return 1;
        }
        OrganTable.SporeGland spec = OrganData.spec();
        long now = level.getGameTime();
        for (Long2ObjectMap.Entry<OrganData.Organ> entry : data.nodes().long2ObjectEntrySet()) {
            BlockPos pos = BlockPos.of(entry.getLongKey());
            double maturity = OrganData.maturity(entry.getValue().born(), now, spec);
            reply(ctx, entry.getValue().kind().getSerializedName() + " " + pos.toShortString()
                    + "  育ち " + Math.round(maturity * 100.0D) + "%"
                    + "  下限への寄与 " + fmt(spec.worldFloorPercent() * maturity) + "%");
        }
        return 1;
    }

    private static int site(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            return 0;
        }
        ServerLevel level = player.serverLevel();
        OrganTable.SporeGland spec = DesignData.organs().sporeGland();
        ChunkPos here = new ChunkPos(player.blockPosition());
        boolean isSite = OrganField.isSite(level.getSeed(), here.x, here.z, spec.cellSizeChunks());
        ChunkPos nearest = OrganField.nearestSite(level.getSeed(), here, spec.cellSizeChunks());

        reply(ctx, "このチャンク " + here + " は候補地" + (isSite ? "である。" : "ではない。")
                + " 地脈" + (OrganField.hasVein(level.getSeed(), here.x, here.z) ? "あり" : "なし"));
        if (nearest == null) {
            reply(ctx, "近くの升目に候補地が無い —— まわりに地脈が 1 本も通っていない。"
                    + "  升目 " + spec.cellSizeChunks() + " チャンク");
        } else {
            reply(ctx, "いちばん近い候補地: " + nearest
                    + " (中心 " + nearest.getMiddleBlockX() + ", " + nearest.getMiddleBlockZ() + ")"
                    + "  升目 " + spec.cellSizeChunks() + " チャンク");
        }
        reply(ctx, "※ 候補地が極相になって全層置換を終えたときに生える。既に生えているかは list を見ること。");
        return 1;
    }

    private static int grow(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            return 0;
        }
        ServerLevel level = player.serverLevel();
        ChunkPos here = new ChunkPos(player.blockPosition());
        BlockPos grown = OrganPlacer.plant(level, here, OrganKind.SPORE_GLAND);
        if (grown == null) {
            reply(ctx, "生やせなかった。このチャンクに<地脈が通っていて地面がある>列が無い —— "
                    + "先に /ov climax ley force で地脈の上へ飛ぶこと(/ov organ site でも確かめられる)。");
            return 0;
        }
        reply(ctx, "胞子腺の節を置いた: " + grown.toShortString() + " (体はそこから組み上がる)");
        return 1;
    }

    private static int clear(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            return 0;
        }
        OrganData data = OrganData.get(player.serverLevel());
        int count = data.count();
        data.clear();
        reply(ctx, "台帳から " + count + " 個を外した(ブロックはそのまま残っている)。");
        return 1;
    }

    private static String fmt(double value) {
        return String.format("%.1f", value);
    }

    private static void reply(CommandContext<CommandSourceStack> ctx, String text) {
        ctx.getSource().sendSuccess(() -> Component.literal(text), false);
    }
}
