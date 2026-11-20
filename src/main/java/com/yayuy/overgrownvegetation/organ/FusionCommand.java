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
 *   <li>{@code fusion count [半径]} — まわり(既定 96)の乗っ取りの器官(小・大)・司令塔の器官・守りの印の Mob の数(11-20)。</li>
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
                                        .then(Commands.literal("count")
                                                .executes(ctx -> count(ctx.getSource(), 96))
                                                .then(Commands.argument("radius", com.mojang.brigadier.arguments.IntegerArgumentType.integer(8, 512))
                                                        .executes(ctx -> count(ctx.getSource(), com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "radius")))))
                                        .then(Commands.literal("takeover")
                                                .executes(ctx -> takeover(ctx.getSource(), 20, 2))
                                                .then(Commands.argument("count", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 2000))
                                                        .executes(ctx -> takeover(ctx.getSource(), com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "count"), 2))
                                                        .then(Commands.argument("radius", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 8))
                                                                .executes(ctx -> takeover(ctx.getSource(), com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "count"),
                                                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "radius")))
                                                                .then(Commands.literal("split").executes(ctx -> takeover(ctx.getSource(), com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "count"),
                                                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "radius"), true))))))
                                        .then(Commands.literal("cleanup")
                                                .executes(ctx -> cleanup(ctx.getSource(), 48, false))
                                                .then(Commands.literal("why")
                                                        .executes(ctx -> why(ctx.getSource(), 64, false))
                                                        .then(Commands.argument("radius", com.mojang.brigadier.arguments.IntegerArgumentType.integer(4, 256))
                                                                .executes(ctx -> why(ctx.getSource(), com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "radius"), false))
                                                                .then(Commands.literal("all")
                                                                        .executes(ctx -> why(ctx.getSource(), com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "radius"), true)))))
                                                .then(Commands.literal("check")
                                                        .executes(ctx -> cleanup(ctx.getSource(), 48, true))
                                                        .then(Commands.argument("radius", com.mojang.brigadier.arguments.IntegerArgumentType.integer(4, 256))
                                                                .executes(ctx -> cleanup(ctx.getSource(), com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "radius"), true))))
                                                .then(Commands.argument("radius", com.mojang.brigadier.arguments.IntegerArgumentType.integer(4, 256))
                                                        .executes(ctx -> cleanup(ctx.getSource(), com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "radius"), false))))))));
    }

    /** まわり radius ブロック(横)の器官の数(乗っ取りの小・大/司令塔の器官)と、守りの印の Mob の数(20 構造物の調べ 11-20)。 */
    private static int count(CommandSourceStack src, int radius) {
        ServerLevel level = src.getLevel();
        BlockPos at = BlockPos.containing(src.getPosition());
        int tkSmall = 0, tkBig = 0, other = 0, guardsWanted = 0;
        long r2 = (long) radius * radius;
        for (ForestOrgans.Organ o : ForestOrgans.get(level).organs()) {
            long dx = o.x - at.getX(), dz = o.z - at.getZ();
            if (dx * dx + dz * dz > r2) continue;
            if (o.takeover == null) {
                other++;
                continue;
            }
            if (o.small) tkSmall++;
            else tkBig++;
            guardsWanted += OrganRoles.takeoverGuards(o);
        }
        int guards = level.getEntitiesOfClass(net.minecraft.world.entity.Mob.class, new net.minecraft.world.phys.AABB(at).inflate(radius, 96.0D, radius),
                m -> m.getTags().contains(OrganWorld.GUARD_TAG)).size();
        int a = tkSmall, b = tkBig, c = other, w = guardsWanted;
        src.sendSuccess(() -> Component.literal(String.format(java.util.Locale.ROOT,
                "FUSION_COUNT 半径 %d: 乗っ取りの器官 小 %d・大 %d(守りの決まり %d 体)・司令塔の器官 %d・守りの印の Mob %d 体", radius, a, b, w, c, guards)), false);
        return a + b;
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
        return takeover(src, count, radius, false);
    }

    /** split = ふつうの広がりと同じく露出面を何回かに分けて集め、覚えた物を使う(11-20 見回りの重さの確かめ)。 */
    private static int takeover(CommandSourceStack src, int count, int radius, boolean split) {
        ServerLevel level = src.getLevel();
        BlockPos at = BlockPos.containing(src.getPosition());
        java.util.Random rnd = new java.util.Random(level.getGameTime());
        int planted = 0, big = 0, gaps = 0;
        long t0 = System.nanoTime(), worst = 0;
        for (int i = 0; i < count; i++) {
            long s0 = System.nanoTime();
            Takeover.Visit v = Takeover.visit(level, (at.getX() >> 4) + rnd.nextInt(2 * radius + 1) - radius, (at.getZ() >> 4) + rnd.nextInt(2 * radius + 1) - radius, rnd,
                    StructureFusion.table(), true, split);
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

    /**
     * 浮いた木の中身(数えるだけ。2026-11-20 20 構造物の調べの続き): 種類(木部・樹冠の縁の葉・離れた葉)・ブロックの名前・構造物の箱の中か・
     * 地面からの高さ・大きい塊の場所。all を付けると構造物のそばでなくても数える(山の高さのせいかを分ける)。
     */
    private static int why(CommandSourceStack src, int radius, boolean all) {
        FloatingTreeCleanup.Why w = new FloatingTreeCleanup.Why();
        FloatingTreeCleanup.Result r = FloatingTreeCleanup.run(src.getLevel(), BlockPos.containing(src.getPosition()), radius, true, all, w);
        java.util.List<String> lines = new ArrayList<>();
        lines.add(String.format(java.util.Locale.ROOT, "FUSION_WHY 半径 %d%s: 塊 %d・ブロック %d(調べた木 %d)", radius, all ? " 構造物のそば以外も" : "", r.clusters(), r.blocks(), r.scanned()));
        w.kinds.forEach((k, v) -> lines.add("  種類 " + k + ": " + v[0] + " 塊・" + v[1] + " ブロック(" + w.kindBlocks.getOrDefault(k, java.util.Map.of()).entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue()).limit(4).map(e -> e.getKey() + "×" + e.getValue()).reduce((a, b) -> a + ", " + b).orElse("") + ")"));
        lines.add("  構造物の箱の中: " + w.inBox[0] + " 塊・" + w.inBox[1] + " ブロック / 地面から 0〜8: " + w.height[0] + "・9〜24: " + w.height[1] + "・25〜: " + w.height[2] + " 塊");
        lines.add("  ブロック: " + w.blocks.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(6)
                .map(e -> e.getKey() + "×" + e.getValue()).reduce((a, b) -> a + ", " + b).orElse("なし"));
        lines.add("  浮いた木部のうち 2 マス以内に器官がある塊: " + w.nearOrgan);
        lines.add("  浮いた木部が触れている物: " + w.touching.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(8)
                .map(e -> e.getKey() + "×" + e.getValue()).reduce((a, b) -> a + ", " + b).orElse("なし"));
        for (String s : w.biggest(6)) lines.add("  例 " + s);
        w.biggest(100000).stream().filter(s -> s.startsWith("木部(どこ")).limit(6).forEach(s -> lines.add("  浮いた木部の例 " + s));
        w.biggest(100000).stream().filter(s -> s.startsWith("葉(離れた")).limit(3).forEach(s -> lines.add("  離れた葉の例 " + s));
        for (String s : lines) {
            OvergrownVegetation.LOGGER.info(s);
            src.sendSuccess(() -> Component.literal(s), false);
        }
        return r.clusters();
    }

    private static int cleanup(CommandSourceStack src, int radius, boolean dryRun) {
        FloatingTreeCleanup.Result r = FloatingTreeCleanup.run(src.getLevel(), BlockPos.containing(src.getPosition()), radius, dryRun);
        src.sendSuccess(() -> Component.literal((dryRun ? "FUSION_CLEANUP 数えただけ: " : "FUSION_CLEANUP 消した: ") + "浮いた木 " + r.clusters() + " 塊・" + r.blocks()
                + " ブロック(調べた木のブロック " + r.scanned() + "・支えのある塊 " + r.kept() + "・端にかかって残した塊 " + r.edge() + ")"), true);
        return r.clusters();
    }

}
