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
 *   <li>{@code fusion tree} — いちばん近い構造物に、過繁茂でなくても今すぐ木を生やし始める(小・中 = 構造物の木、大 = 中庭の巨大樹)。</li>
 *   <li>{@code fusion forget} — 「木が済み」の印を全部消す(撮り直し用)。</li>
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
                                        .then(Commands.literal("tree").executes(ctx -> tree(ctx.getSource())))
                                        .then(Commands.literal("forget").executes(ctx -> forget(ctx.getSource())))))));
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
        StructureTrees data = StructureTrees.get(level);
        for (StructureFusion.Site s : ss) {
            String line = s.id() + " 段=" + s.wholeClass() + " 長辺=" + StructureFusion.span(s.surfaceWhole()) + " 箱=" + s.surfaceWhole()
                    + " 部品=" + s.pieces().length + (data.isDone(s) ? " 木=済み" : "") + (StructureTrees.overgrown(level, s) ? " 過繁茂" : "");
            src.sendSuccess(() -> Component.literal(line), false);
        }
        return ss.size();
    }

    private static int tree(CommandSourceStack src) {
        ServerLevel level = src.getLevel();
        BlockPos at = BlockPos.containing(src.getPosition());
        List<StructureFusion.Site> ss = near(level, at, 64);
        if (ss.isEmpty()) {
            src.sendFailure(Component.literal("まわり 64 に構造物は無い"));
            return 0;
        }
        StructureFusion.Site s = ss.get(0);
        StructureTrees.Start r = StructureTrees.get(level).start(level, s, true);
        src.sendSuccess(() -> Component.literal("FUSION_TREE " + s.id() + " 段=" + s.wholeClass() + " → " + r + "(詳しくはログ StructureTrees)"), false);
        return r == StructureTrees.Start.STARTED ? 1 : 0;
    }

    private static int forget(CommandSourceStack src) {
        StructureTrees.get(src.getLevel()).clearForTests();
        src.sendSuccess(() -> Component.literal("構造物の木の「済み」を消した"), false);
        return 1;
    }
}
