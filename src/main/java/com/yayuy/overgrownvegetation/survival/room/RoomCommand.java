package com.yayuy.overgrownvegetation.survival.room;

import com.mojang.brigadier.context.CommandContext;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.spore.SporeConcentration;
import it.unimi.dsi.fastutil.longs.LongIterator;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * 開発用: {@code /ov perf spore room} = 今立っている所が閉じた部屋か・大きさ・見つけたすき間の場所(2026-11-13 段 2a)。
 * すき間は全部集めてたどり直し(覚えは使わない)、その場所に光の粒を出す(後でプレイヤー向けの手がかりにも使える)。
 * {@code /ov perf spore room stats} = 覚えの測り(覚えから答えた回数・たどった回数と平均の時間・断った回数)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class RoomCommand {

    private RoomCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ov")
                .then(Commands.literal("perf")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("spore")
                                .then(Commands.literal("room")
                                        .executes(RoomCommand::show)
                                        .then(Commands.literal("stats").executes(RoomCommand::stats))
                                        .then(Commands.literal("bench").executes(RoomCommand::bench))))));
    }

    /** 覚えを使わずに今の場所から 20 回たどり、1 回の時間を測る(本物の世界での費用。大きな洞窟・ネザーで上限までたどる重さ)。 */
    private static int bench(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        RoomCache.LevelWorld w = new RoomCache.LevelWorld(level);
        BlockPos start = RoomFinder.start(w, BlockPos.containing(src.getPosition()));
        if (start == null) {
            src.sendFailure(Component.literal("ブロックの中"));
            return 0;
        }
        com.yayuy.overgrownvegetation.data.RoomSpec spec = com.yayuy.overgrownvegetation.data.DesignData.sporeField().room();
        RoomScan last = RoomFinder.scan(w, start, spec, false);
        long t0 = System.nanoTime();
        for (int i = 0; i < 20; i++) last = RoomFinder.scan(new RoomCache.LevelWorld(level), start, spec, false);
        double ms = (System.nanoTime() - t0) / 1.0E6D / 20;
        RoomScan r = last;
        src.sendSuccess(() -> Component.literal(String.format("部屋をたどる 1 回 %.3f ms(%s・%d マス・空が開けている: %s)",
                ms, r.status().label, r.volume(), w.openSky(start.getX(), start.getY(), start.getZ()))), false);
        return 1;
    }

    private static int show(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        BlockPos pos = BlockPos.containing(src.getPosition());
        RoomScan full = RoomCache.scanFull(level, pos);
        RoomScan cached = RoomCache.scanAt(level, pos);
        String size = full.volume() > 0
                ? String.format("%d マス(%d×%d×%d)", full.volume(), full.maxX() - full.minX() + 1, full.maxY() - full.minY() + 1, full.maxZ() - full.minZ() + 1)
                : "-";
        src.sendSuccess(() -> Component.literal(String.format("部屋の判定: %s・大きさ %s・人の手のブロック %d・ふさいだ面 %d%s",
                full.status().label, size, full.built(), full.boundary(),
                full.isRoom() ? String.format("・印 %s", BlockPos.of(full.id()).toShortString()) : "")), false);
        src.sendSuccess(() -> Component.literal(String.format("濃度の読みに効く結果: %s・部屋の倍率 ×%.2f",
                cached == null ? "なし(判定を切ってある・この tick の上限)" : cached.status().label, SporeConcentration.roomFactorAt(level, pos))), false);
        if (!full.gaps().isEmpty()) {
            StringBuilder sb = new StringBuilder("すき間(内側のマス、" + full.gaps().size() + (full.gaps().size() >= RoomFinder.MAX_GAPS ? " 以上" : "") + "): ");
            int n = 0;
            for (LongIterator it = full.gaps().iterator(); it.hasNext() && n < 8; n++) {
                long g = it.nextLong();
                if (n > 0) sb.append(" / ");
                sb.append(BlockPos.of(g).toShortString());
            }
            src.sendSuccess(() -> Component.literal(sb.toString()), false);
            if (src.getEntity() instanceof ServerPlayer player) {
                for (LongIterator it = full.gaps().iterator(); it.hasNext(); ) {
                    BlockPos g = BlockPos.of(it.nextLong());
                    level.sendParticles(player, ParticleTypes.END_ROD, true, g.getX() + 0.5D, g.getY() + 0.5D, g.getZ() + 0.5D, 12, 0.25D, 0.25D, 0.25D, 0.0D);
                }
            }
        }
        return full.isRoom() ? 1 : 0;
    }

    private static int stats(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                "部屋の覚え: 覚えから %d 回・たどった %d 回(平均 %.3f ms)・上限で断った %d 回・捨てた %d 件・覚えているマス %d",
                RoomCache.hits, RoomCache.scans, RoomCache.scans == 0 ? 0.0D : RoomCache.scanNanos / 1.0E6D / RoomCache.scans,
                RoomCache.refused, RoomCache.invalidated, RoomCache.cachedCells(level))), false);
        return 1;
    }
}
