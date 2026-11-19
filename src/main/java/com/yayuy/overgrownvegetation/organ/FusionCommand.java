package com.yayuy.overgrownvegetation.organ;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.worldgen.fusion.StructureFusion;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * 構造物と森の融合の確かめ用(2026-11-19 段 2)。{@code /ov perf organ fusion ...}(/ov perf の下の枝は決まった一覧なので、器官の枝に足す)(権限 2)。
 * <ul>
 *   <li>{@code fusion sites} — まわり 64 ブロックの構造物(種類・全体の段・地表の箱・木が済みか)。</li>
 *   <li>{@code fusion cleanup [半径]} — まわり(既定 48)の構造物のそばで、支えの無い汚染の原木・葉の塊(浮いた木)を消す。{@code cleanup check [半径]} は数えるだけ(段 2b)。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class FusionCommand {
    private FusionCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ov")
                .then(Commands.literal("perf")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("organ")
                                .then(Commands.literal("fusion")
                                        .then(Commands.literal("sites").executes(ctx -> sites(ctx.getSource())))
                                        .then(Commands.literal("takeover")
                                                .executes(ctx -> takeover(ctx.getSource(), 20, 2))
                                                .then(Commands.argument("count", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 2000))
                                                        .executes(ctx -> takeover(ctx.getSource(), com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "count"), 2))
                                                        .then(Commands.argument("radius", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 8))
                                                                .executes(ctx -> takeover(ctx.getSource(), com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "count"),
                                                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "radius"))))))
                                        .then(Commands.literal("cleanup")
                                                .executes(ctx -> cleanup(ctx.getSource(), 48, false))
                                                .then(Commands.literal("check")
                                                        .executes(ctx -> cleanup(ctx.getSource(), 48, true))
                                                        .then(Commands.argument("radius", com.mojang.brigadier.arguments.IntegerArgumentType.integer(4, 128))
                                                                .executes(ctx -> cleanup(ctx.getSource(), com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "radius"), true))))
                                                .then(Commands.argument("radius", com.mojang.brigadier.arguments.IntegerArgumentType.integer(4, 128))
                                                        .executes(ctx -> cleanup(ctx.getSource(), com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "radius"), false))))))));
    }

    private static List<StructureFusion.Site> near(ServerLevel level, BlockPos at, int r) {
        List<StructureFusion.Site> out = new ArrayList<>();
        for (int cx = (at.getX() - r) >> 4; cx <= (at.getX() + r) >> 4; cx++) {
            for (int cz = (at.getZ() - r) >> 4; cz <= (at.getZ() + r) >> 4; cz++) {
                StructureFusion.Site[] ss = StructureFusion.sitesAt(level, cx, cz);
                if (ss == null) continue;
                for (StructureFusion.Site s : ss) if (!out.contains(s)) out.add(s);
            }
        }
        out.sort((a, b) -> Double.compare(dist(a.surfaceWhole(), at), dist(b.surfaceWhole(), at)));
        return out;
    }

    private static double dist(BoundingBox b, BlockPos p) {
        double dx = Math.max(0, Math.max(b.minX() - p.getX(), p.getX() - b.maxX())), dz = Math.max(0, Math.max(b.minZ() - p.getZ(), p.getZ() - b.maxZ()));
        return Math.hypot(dx, dz);
    }

    private static int sites(CommandSourceStack src) {
        ServerLevel level = src.getLevel();
        BlockPos at = BlockPos.containing(src.getPosition());
        List<StructureFusion.Site> ss = near(level, at, 64);
        if (ss.isEmpty()) {
            src.sendSuccess(() -> Component.literal("まわり 64 に構造物は無い"), false);
            return 0;
        }
        for (StructureFusion.Site s : ss) {
            String line = s.id() + " 段=" + s.wholeClass() + " 長辺=" + StructureFusion.span(s.surfaceWhole()) + " 箱=" + s.surfaceWhole()
                    + " 部品=" + s.pieces().length;
            src.sendSuccess(() -> Component.literal(line), false);
        }
        return ss.size();
    }

    /** 乗っ取りを今すぐ count 回(まわり radius チャンクからランダム。過繁茂でなくても・確率を見ずに)。建てるのは器官の手順に任せる。 */
    private static int takeover(CommandSourceStack src, int count, int radius) {
        ServerLevel level = src.getLevel();
        BlockPos at = BlockPos.containing(src.getPosition());
        java.util.Random rnd = new java.util.Random(level.getGameTime());
        int planted = 0, big = 0, gaps = 0;
        long t0 = System.nanoTime(), worst = 0;
        for (int i = 0; i < count; i++) {
            long s0 = System.nanoTime();
            Takeover.Visit v = Takeover.visit(level, (at.getX() >> 4) + rnd.nextInt(2 * radius + 1) - radius, (at.getZ() >> 4) + rnd.nextInt(2 * radius + 1) - radius, rnd,
                    StructureFusion.table(), true);
            worst = Math.max(worst, System.nanoTime() - s0);
            gaps += Takeover.lastGaps;
            Takeover.lastGaps = 0;
            if (v.planted() != null) {
                planted++;
                if (!v.planted().small) big++;
            }
        }
        double ms = (System.nanoTime() - t0) / 1.0e6;
        int p = planted, b = big, g = gaps;
        long w = worst;
        src.sendSuccess(() -> Component.literal(String.format(java.util.Locale.ROOT, "TAKEOVER 見た %d 回・芽吹かせた %d(大 %d)・隙間の草 %d・%.1f ms(1 回平均 %.2f・最悪 %.2f ms)", count, p, b, g, ms, ms / count, w / 1.0e6)), true);
        return planted;
    }

    private static int cleanup(CommandSourceStack src, int radius, boolean dryRun) {
        FloatingTreeCleanup.Result r = FloatingTreeCleanup.run(src.getLevel(), BlockPos.containing(src.getPosition()), radius, dryRun);
        src.sendSuccess(() -> Component.literal((dryRun ? "FUSION_CLEANUP 数えただけ: " : "FUSION_CLEANUP 消した: ") + "浮いた木 " + r.clusters() + " 塊・" + r.blocks()
                + " ブロック(調べた木のブロック " + r.scanned() + "・支えのある塊 " + r.kept() + "・端にかかって残した塊 " + r.edge() + ")"), true);
        return r.clusters();
    }

}
