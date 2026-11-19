package com.yayuy.overgrownvegetation.organ;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.Locale;
import java.util.Random;

/**
 * {@code /ov perf organ shape <種類> <small|big> <面のブロック> <nx> <ny> <nz> [seed]} — 乗っ取りの器官を、指定した面に今すぐ 1 つ建てる
 * (2026-11-20 建物用の器官 5 種類の形の撮影・確認用。{@link TakeoverShapes})。種類は phase / tower / sac / nursery / ley。
 * 面は建物のブロックの位置と、外への向き(-1・0・1)。OP 専用。計画にかかった時間とブロックの読みも返す。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class TakeoverShapeCommand {
    private TakeoverShapeCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ov")
                .then(Commands.literal("perf")
                        .requires(s -> s.hasPermission(2))
                        .then(Commands.literal("organ")
                                .then(Commands.literal("shape")
                                        .then(Commands.argument("kind", StringArgumentType.word())
                                                .then(Commands.argument("size", StringArgumentType.word())
                                                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                                                .then(Commands.argument("nx", IntegerArgumentType.integer(-1, 1))
                                                                        .then(Commands.argument("ny", IntegerArgumentType.integer(-1, 1))
                                                                                .then(Commands.argument("nz", IntegerArgumentType.integer(-1, 1))
                                                                                        .executes(c -> build(c, 0))
                                                                                        .then(Commands.argument("seed", IntegerArgumentType.integer())
                                                                                                .executes(c -> build(c, IntegerArgumentType.getInteger(c, "seed"))))))))))))));
    }

    private static int build(CommandContext<CommandSourceStack> c, int seed) {
        CommandSourceStack src = c.getSource();
        ServerLevel level = src.getLevel();
        OrganKind kind = OrganKind.byId(StringArgumentType.getString(c, "kind"));
        if (kind == null) {
            src.sendFailure(Component.literal("種類は phase / tower / sac / nursery / ley"));
            return 0;
        }
        boolean big = "big".equals(StringArgumentType.getString(c, "size"));
        BlockPos p = BlockPosArgument.getBlockPos(c, "pos");
        Takeover.Face f = new Takeover.Face(p.getX(), p.getY(), p.getZ(), IntegerArgumentType.getInteger(c, "nx"), IntegerArgumentType.getInteger(c, "ny"),
                IntegerArgumentType.getInteger(c, "nz"));
        ForestOrgans data = ForestOrgans.get(level);
        Random rnd = new Random(seed == 0 ? level.getGameTime() : seed);
        boolean fits = TakeoverShapes.faceFits(level, kind, f);
        ForestOrgans.Organ o = Takeover.plant(level, data, f, big, kind, rnd);
        int n = OrganWorld.buildNow(level, data, o);
        String line = String.format(Locale.ROOT, "TAKEOVER_SHAPE %s %s 置いた %d・面に合う %s・計画 %.2f ms・読み %d", kind.id, big ? "大" : "小", n, fits ? "はい" : "いいえ",
                TakeoverShapes.lastPlanMs, TakeoverShapes.lastPlanReads);
        src.sendSuccess(() -> Component.literal(line), true);
        return Math.max(0, n);
    }
}
