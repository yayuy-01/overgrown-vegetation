package com.yayuy.overgrownvegetation.corruption.cliff;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.util.LoadedChunks;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /ov perf cliff grow <半径チャンク> [ropes|sacs|both]} — 汚染の段に関係なく、まわりのチャンクの崖・急斜面に今すぐ生やす
 * (2026-11-20 崖の汚染の撮影・確認用。OP 専用)。生やしたチャンクには巡回の済みの印も付ける。
 * {@code /ov perf cliff info} — 今いるチャンクの段・印と、巡回の直近の結果。{@code mobs}・{@code target} は縄を登る Mob の調べと撮影用。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class CliffCommand {
    private CliffCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ov")
                .then(Commands.literal("perf")
                        .requires(s -> s.hasPermission(2))
                        .then(Commands.literal("cliff")
                                .then(Commands.literal("info").executes(CliffCommand::info))
                                .then(Commands.literal("mobs").executes(CliffCommand::mobs))
                                .then(Commands.literal("target").executes(CliffCommand::target))
                                .then(Commands.literal("grow")
                                        .then(Commands.argument("radius", IntegerArgumentType.integer(0, 8))
                                                .executes(c -> grow(c, "both"))
                                                .then(Commands.argument("what", StringArgumentType.word())
                                                        .executes(c -> grow(c, StringArgumentType.getString(c, "what")))))))));
    }

    private static int grow(CommandContext<CommandSourceStack> c, String what) {
        CommandSourceStack src = c.getSource();
        ServerLevel level = src.getLevel();
        boolean ropes = !"sacs".equals(what), sacs = !"ropes".equals(what);
        int r = IntegerArgumentType.getInteger(c, "radius");
        ChunkPos center = new ChunkPos(BlockPos.containing(src.getPosition()));
        CliffCorruption.Data data = CliffCorruption.Data.get(level);
        CliffShapes.Result sum = new CliffShapes.Result();
        int chunks = 0, skipped = 0;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                ChunkPos cp = new ChunkPos(center.x + dx, center.z + dz);
                if (!LoadedChunks.neighbourhoodLoaded(level, cp.x, cp.z)) {
                    skipped++;
                    continue;
                }
                CliffShapes.Result res = CliffShapes.decorateChunk(level, cp, ropes, sacs);
                data.mark(cp, (byte) (data.get(cp) | (ropes ? CliffCorruption.ROPES : 0) | (sacs ? CliffCorruption.SACS : 0)));
                add(sum, res);
                chunks++;
            }
        }
        String msg = "崖の汚染: " + chunks + " チャンク(読み込み待ちで飛ばした " + skipped + ")/ " + sum;
        src.sendSuccess(() -> Component.literal(msg), true);
        return chunks;
    }

    private static void add(CliffShapes.Result s, CliffShapes.Result r) {
        s.cliffEdges += r.cliffEdges;
        s.slopeEdges += r.slopeEdges;
        s.ropes += r.ropes;
        s.thin += r.thin;
        s.cracks += r.cracks;
        s.roots += r.roots;
        s.sacs += r.sacs;
        s.flesh += r.flesh;
        s.nanos += r.nanos;
    }

    /** 撮影用: まわり 32 ブロックの植物側の Mob の相手を、コマンドを打った者にする(崖の上の相手へ縄を登る所を撮るため。G1 は遠い上の相手に気付かない)。 */
    private static int target(CommandContext<CommandSourceStack> c) {
        CommandSourceStack src = c.getSource();
        if (!(src.getEntity() instanceof net.minecraft.world.entity.LivingEntity me)) return 0;
        int n = 0;
        for (net.minecraft.world.entity.Mob m : src.getLevel().getEntitiesOfClass(net.minecraft.world.entity.Mob.class, me.getBoundingBox().inflate(32))) {
            if (!com.yayuy.overgrownvegetation.spore.PlantSideRules.isPlantSide(m)) continue;
            m.setTarget(me);
            n++;
        }
        final int count = n;
        src.sendSuccess(() -> Component.literal("相手を決めた植物側の Mob " + count), false);
        return n;
    }

    /** 調べ: まわり 24 ブロックの植物側の Mob の相手・経路(届くか・次の節)・縄の上で相手を保っているか。 */
    private static int mobs(CommandContext<CommandSourceStack> c) {
        CommandSourceStack src = c.getSource();
        ServerLevel level = src.getLevel();
        int n = 0;
        for (net.minecraft.world.entity.Mob m : level.getEntitiesOfClass(net.minecraft.world.entity.Mob.class, new net.minecraft.world.phys.AABB(BlockPos.containing(src.getPosition())).inflate(24))) {
            if (!com.yayuy.overgrownvegetation.spore.PlantSideRules.isPlantSide(m)) continue;
            net.minecraft.world.entity.LivingEntity t = m.getTarget();
            net.minecraft.world.level.pathfinder.Path p = m.getNavigation().getPath();
            String line = m.getType().toShortString() + " " + m.blockPosition().toShortString() + " 地面 " + m.onGround() + " 相手 " + (t == null ? "なし" : t.getType().toShortString() + String.format(" %.1f 上", t.getY() - m.getY()))
                    + " 経路 " + (p == null ? "なし" : (p.isDone() ? "終わり" : p.getNodeCount() + " 節") + (p.canReach() ? " 届く" : " 届かない")) + " 縄の上で相手を保つ " + RootClimbAssist.holding(m)
                    + " 触れる " + RootClimb.touching(m) + " 次の節 " + (p == null || p.isDone() ? "なし" : p.getNextNodePos().toShortString());
            src.sendSuccess(() -> Component.literal(line), false);
            n++;
        }
        final int count = n;
        src.sendSuccess(() -> Component.literal("植物側の Mob " + count), false);
        return n;
    }

    private static int info(CommandContext<CommandSourceStack> c) {
        CommandSourceStack src = c.getSource();
        ServerLevel level = src.getLevel();
        ChunkPos cp = new ChunkPos(BlockPos.containing(src.getPosition()));
        int st = CliffCorruption.stage(level, cp);
        byte bits = CliffCorruption.Data.get(level).get(cp);
        CliffShapes.Result last = CliffCorruption.last;
        String msg = "崖の汚染: このチャンクの段 " + st + " / 済みの印 " + bits + "(1 = 縄・2 = 嚢)/ 霧 " + CliffSacFog.active(level)
                + " / 巡回の直近: " + (last == null ? "まだ" : last.toString());
        src.sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }
}
