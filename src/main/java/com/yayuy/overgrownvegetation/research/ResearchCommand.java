package com.yayuy.overgrownvegetation.research;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterCommandsEvent;

/**
 * 開発用(2026-11-17 研究台と研究ツリー)。/ov の下の一覧はテストで決めてあるので {@code perf ley} の下に足す(研究は地脈技術)。
 * <ul>
 *     <li>{@code /ov perf ley research} = 開いた節・走っている研究と進み。</li>
 *     <li>{@code /ov perf ley research open <節>|all} = 節を開く(作り方も覚える)。</li>
 *     <li>{@code /ov perf ley research reset} = すべて閉じる(走っている研究は標本を研究台に落としてやめる)。</li>
 *     <li>{@code /ov perf ley research finish} = 走っている研究を今すぐ終える。</li>
 *     <li>{@code /ov perf ley research power <FE>} = 走っている研究の研究台に地脈の力を入れる。</li>
 *     <li>{@code /ov perf ley research reload} = {@code research_tree.json} を読み直す。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE)
public final class ResearchCommand {

    private ResearchCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ov")
                .then(Commands.literal("perf").requires(s -> s.hasPermission(2))
                        .then(Commands.literal("ley").then(Commands.literal("research")
                                .executes(ctx -> {
                                    ctx.getSource().sendSuccess(() -> Component.literal(status(ctx.getSource().getServer())), false);
                                    return 1;
                                })
                                .then(Commands.literal("open").then(Commands.argument("node", StringArgumentType.word()).executes(ctx -> {
                                    String id = StringArgumentType.getString(ctx, "node");
                                    MinecraftServer server = ctx.getSource().getServer();
                                    ResearchTree tree = ResearchTree.get();
                                    if (id.equals("all")) {
                                        tree.nodes().keySet().forEach(n -> Research.open(server, n, false));
                                    } else if (tree.node(id) == null) {
                                        ctx.getSource().sendFailure(Component.literal("その節は無い: " + id));
                                        return 0;
                                    } else {
                                        Research.open(server, id, false);
                                    }
                                    ctx.getSource().sendSuccess(() -> Component.literal("開いた: " + id), true);
                                    return 1;
                                })))
                                .then(Commands.literal("reset").executes(ctx -> {
                                    MinecraftServer server = ctx.getSource().getServer();
                                    Research.cancel(server, null);
                                    ResearchData.get(server).clearAll();
                                    Research.broadcastState(server);
                                    ctx.getSource().sendSuccess(() -> Component.literal("研究をすべて閉じた"), true);
                                    return 1;
                                }))
                                .then(Commands.literal("finish").executes(ctx -> {
                                    MinecraftServer server = ctx.getSource().getServer();
                                    if (ResearchData.get(server).active() == null) {
                                        ctx.getSource().sendFailure(Component.literal("走っている研究は無い"));
                                        return 0;
                                    }
                                    Research.complete(server);
                                    return 1;
                                }))
                                .then(Commands.literal("power").then(Commands.argument("fe", IntegerArgumentType.integer(0)).executes(ctx -> {
                                    MinecraftServer server = ctx.getSource().getServer();
                                    ResearchData.Active a = ResearchData.get(server).active();
                                    ServerLevel level = a == null ? null : server.getLevel(a.bench.dimension());
                                    BlockPos p = a == null ? null : a.bench.pos();
                                    if (level == null || !(level.getBlockEntity(p) instanceof ResearchBenchBlockEntity be)) {
                                        ctx.getSource().sendFailure(Component.literal("走っている研究の研究台が無い"));
                                        return 0;
                                    }
                                    be.setEnergy(IntegerArgumentType.getInteger(ctx, "fe"));
                                    ctx.getSource().sendSuccess(() -> Component.literal("研究台の地脈の力 = " + be.energy() + " FE"), true);
                                    return 1;
                                })))
                                .then(Commands.literal("reload").executes(ctx -> {
                                    ResearchTree t = ResearchTree.reload();
                                    ctx.getSource().sendSuccess(() -> Component.literal("研究ツリーを読み直した(節 " + t.nodes().size() + ")"), true);
                                    return 1;
                                }))))));
    }

    static String status(MinecraftServer server) {
        ResearchData d = ResearchData.get(server);
        ResearchTree tree = ResearchTree.get();
        StringBuilder sb = new StringBuilder("開いた節: ").append(d.done().isEmpty() ? "(無し)" : String.join("・", d.done()));
        ResearchData.Active a = d.active();
        if (a == null) {
            sb.append(" / 走っている研究: 無し");
        } else {
            ResearchTree.Node n = tree.node(a.node);
            sb.append(" / 走っている研究: ").append(a.node).append(" ").append(a.progress).append("/").append(n == null ? "?" : n.ticks())
                    .append(" tick・研究台 ").append(a.bench.pos().toShortString());
        }
        return sb.toString();
    }
}
