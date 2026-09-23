package com.yayuy.overgrownvegetation.nest;

import com.mojang.brigadier.context.CommandContext;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.phase.TreeCanopyGroundFinder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.List;

/**
 * {@code /ov nest …} — アルボラの巣の確認用コマンド(OP、2026-09-19)。
 * <ul>
 *     <li>{@code start} … 自分の足元に巣を 1 つ掘り始める(営巣条件を無視する)</li>
 *     <li>{@code list} … 世界にある巣の一覧</li>
 *     <li>{@code pods} … いちばん近い巣の貯蔵胞と、その中身の数(運搬が効いているかの確認用)</li>
 *     <li>{@code simulate} … <b>自然発生の下見</b>。条件を上から順に見て、いまどこで止まっているかを出す</li>
 *     <li>{@code builders} … 確率の抽選を飛ばして、いますぐ建設グループを 1 つ組ませる</li>
 * </ul>
 *
 * <p>営巣は「アルボラが集まる → 確率で建設グループ → 歩いて行く → 掘る」と段が多く、
 * どこで止まっているかはゲーム内で見ていても分からない。{@code simulate} と {@code builders} は
 * そこを覗くためのもの(2026-09-20)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class NestCommand {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ov")
                .then(Commands.literal("nest")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("start").executes(NestCommand::start))
                        .then(Commands.literal("list").executes(NestCommand::list))
                        .then(Commands.literal("pods").executes(NestCommand::pods))
                        .then(Commands.literal("simulate").executes(NestCommand::simulate))
                        .then(Commands.literal("builders").executes(NestCommand::builders))));
    }

    private static int start(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.translatable("commands.overgrown_vegetation.nest.need_player"));
            return 0;
        }
        ServerLevel level = player.serverLevel();
        BlockPos surface = TreeCanopyGroundFinder.findSpawnSurface(level, player.getBlockX(), player.getBlockZ());
        if (surface == null) {
            surface = player.blockPosition();
        }
        if (!NestFormation.start(level, surface)) {
            source.sendFailure(Component.translatable("commands.overgrown_vegetation.nest.start_failed"));
            return 0;
        }
        BlockPos at = surface;
        source.sendSuccess(() -> Component.translatable("commands.overgrown_vegetation.nest.started",
                at.getX(), at.getY(), at.getZ()), true);
        return 1;
    }

    /** 自然発生の下見。条件を 1 段ずつ見て、どこで止まっているかを並べる。 */
    private static int simulate(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        List<String> lines = BuilderGroups.simulate(level);
        for (String line : lines) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return lines.size();
    }

    /** 確率の抽選を飛ばして、いますぐ建設グループを 1 つ組ませる。 */
    private static int builders(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String result = BuilderGroups.forceForm(source.getLevel());
        source.sendSuccess(() -> Component.literal(result), true);
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        ServerLevel overworld = ctx.getSource().getServer().overworld();
        List<NestData.Nest> nests = NestData.get(overworld).nests();
        if (nests.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.translatable("commands.overgrown_vegetation.nest.list_empty"), false);
            return 0;
        }
        for (NestData.Nest nest : nests) {
            BlockPos o = nest.origin();
            ctx.getSource().sendSuccess(() -> Component.translatable("commands.overgrown_vegetation.nest.list_entry",
                    o.getX(), o.getY(), o.getZ(), nest.chambers().size()), false);
        }
        return nests.size();
    }

    /**
     * いちばん近い巣の貯蔵胞を数えて、それぞれの中身の数を出す。
     * アルボラの運搬が効いているかを、部屋まで潜らずに確かめるためのもの(2026-09-20)。
     */
    private static int pods(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.translatable("commands.overgrown_vegetation.nest.need_player"));
            return 0;
        }
        ServerLevel level = player.serverLevel();
        NestData.Nest nest = NestData.get(level).nearest(player.blockPosition());
        if (nest == null) {
            source.sendSuccess(() -> Component.translatable("commands.overgrown_vegetation.nest.list_empty"), false);
            return 0;
        }
        List<BlockPos> pods = NestPods.find(level, nest);
        if (pods.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("commands.overgrown_vegetation.nest.pods_none"), false);
            return 0;
        }
        int total = 0;
        for (BlockPos pos : pods) {
            int count = 0;
            if (level.getBlockEntity(pos) instanceof com.yayuy.overgrownvegetation.block.ArboraStoragePodBlockEntity pod) {
                for (int i = 0; i < pod.getContainerSize(); i++) {
                    if (!pod.getItem(i).isEmpty()) {
                        count++;
                    }
                }
            }
            total += count;
            int slots = count;
            source.sendSuccess(() -> Component.translatable("commands.overgrown_vegetation.nest.pods_entry",
                    pos.getX(), pos.getY(), pos.getZ(), slots), false);
        }
        int sum = total;
        source.sendSuccess(() -> Component.translatable("commands.overgrown_vegetation.nest.pods_total",
                pods.size(), sum), false);
        return pods.size();
    }

    private NestCommand() {
    }
}
