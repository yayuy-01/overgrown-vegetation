package com.yayuy.overgrownvegetation.corruption.pushback;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterCommandsEvent;

import java.util.Locale;

/**
 * 確かめ用(押し返し 2026-11-18 夜): {@code /ov perf pushback} = 今いるチャンクの地面の汚染の割合・台帳(載った日数)・焼けた荒地・杭の範囲・台帳の大きさ・外した数。
 * {@code /ov perf pushback grid <半径>} = まわりのチャンクの地面の割合の表(台帳に載っているものに *)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE)
public final class PushBackCommand {

    private PushBackCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ov")
                .then(Commands.literal("perf")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("pushback")
                                .executes(PushBackCommand::here)
                                .then(Commands.literal("grid")
                                        .then(Commands.argument("radius", IntegerArgumentType.integer(0, 6))
                                                .executes(PushBackCommand::grid))))));
    }

    private static int here(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        BlockPos p = BlockPos.containing(ctx.getSource().getPosition());
        ChunkPos cp = new ChunkPos(p);
        PhaseData pd = PhaseData.get(level);
        LevelChunk chunk = level.getChunkSource().getChunkNow(cp.x, cp.z);
        double share = chunk == null ? -1 : LedgerShrink.groundShare(chunk, LedgerShrink.table().minColumns);
        long since = pd.overgrownSinceTick(cp);
        String age = since < 0 ? "載っていない" : String.format(Locale.ROOT, "載って %.2f 日", (pd.clock(level.getServer()) - since) / 24000.0D);
        String msg = String.format(Locale.ROOT, "押し返し: チャンク %d,%d・地面の汚染 %.0f%%(外すのは %.0f%% 未満)・台帳 %s・焼けた荒地 %s・杭の範囲 %s・帯の上 %s / 台帳 %d チャンク・外した数 %d・止めた数 %d",
                cp.x, cp.z, share * 100.0D, LedgerShrink.table().threshold * 100.0D, age,
                BurntLand.isBurnt(level, p) ? "はい" : "いいえ", RingWards.inStakeField(level, p) ? "の中" : "の外",
                RingWards.guardedFrom(level, null, p) ? "守られている" : "-",
                pd.overgrownLedgerSize(), LedgerShrink.forgottenCount, RingWards.blockedCount)
                + "\n" + AbsentWards.describe(level, p, false); // 留守の杭(11-23)
        ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
        return (int) Math.round(Math.max(0, share) * 100);
    }

    private static int grid(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        int r = IntegerArgumentType.getInteger(ctx, "radius");
        ChunkPos c = new ChunkPos(BlockPos.containing(ctx.getSource().getPosition()));
        PhaseData pd = PhaseData.get(level);
        StringBuilder sb = new StringBuilder("地面の汚染 %(* = 台帳に載っている・北が上)\n");
        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                LevelChunk ch = level.getChunkSource().getChunkNow(c.x + dx, c.z + dz);
                double s = ch == null ? -1 : LedgerShrink.groundShare(ch, LedgerShrink.table().minColumns);
                sb.append(String.format(Locale.ROOT, "%4s%s", s < 0 ? "-" : String.valueOf(Math.round(s * 100)),
                        pd.isOvergrownChunk(new ChunkPos(c.x + dx, c.z + dz)) ? "*" : " "));
            }
            sb.append('\n');
        }
        String out = sb.toString();
        ctx.getSource().sendSuccess(() -> Component.literal(out), false);
        OvergrownVegetation.LOGGER.info("[pushback grid]\n{}", out);
        return 1;
    }
}
