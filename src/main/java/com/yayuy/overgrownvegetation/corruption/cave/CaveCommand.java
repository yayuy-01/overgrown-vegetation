package com.yayuy.overgrownvegetation.corruption.cave;

import com.mojang.brigadier.context.CommandContext;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.fissure.FissureLook;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterCommandsEvent;

/**
 * 洞窟の飾りを確かめるコマンド(2026-11-20)。
 * <ul>
 *     <li>{@code /ov perf cave info} — 直近の巡回の結果・飾ったチャンクの数・置く列の残り</li>
 *     <li>{@code /ov perf cave here} — 今いるチャンクをすぐ飾る(割合は全部 1。済みの印も付ける)</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE)
public final class CaveCommand {
    private CaveCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ov")
                .then(Commands.literal("perf")
                        .requires(s -> s.hasPermission(2))
                        .then(Commands.literal("cave")
                                .then(Commands.literal("info").executes(CaveCommand::info))
                                .then(Commands.literal("here").executes(CaveCommand::here))
                                .then(Commands.literal("demo").executes(CaveCommand::demo))
                                .then(Commands.literal("demomouth").executes(CaveCommand::demoMouth)))));
    }

    /** 入口 B 吐き出す根の見本(撮影用): 足元の地面を口として、向いている向きへ積もる根と 6 本の腕をすぐ置く(時間の進みは飛ばす)。 */
    private static int demoMouth(CommandContext<CommandSourceStack> c) {
        ServerLevel level = c.getSource().getServer().overworld();
        BlockPos o = BlockPos.containing(c.getSource().getPosition());
        double yaw = Math.toRadians(c.getSource().getRotation().y + 90.0D); // マイクラの向き → x 軸からの角
        java.util.List<FissureLook.Cell> cells = new java.util.ArrayList<>(FissureLook.mouthMound(o, yaw, 11));
        int arms = com.yayuy.overgrownvegetation.data.DesignData.fissure().mouth.arms;
        for (int i = 0; i < arms; i++) {
            int[][] path = FissureLook.armPath(i, arms, yaw, 11);
            for (int k = 0; k < path.length; k++) {
                int x = o.getX() + path[k][0], z = o.getZ() + path[k][1];
                net.minecraft.world.level.chunk.LevelChunk lc = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
                if (lc == null) continue;
                BlockPos g = new BlockPos(x, lc.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15), z);
                cells.add(new FissureLook.Cell(g, FissureLook.Part.SPILL));
                if (k < 2) cells.add(new FissureLook.Cell(g.above(), FissureLook.Part.SPILL, FissureLook.Mode.PUT));
            }
        }
        FissureLook.enqueue(level, cells, null);
        int n = FissureLook.drainAll();
        c.getSource().sendSuccess(() -> Component.literal("入口 B の見本を置いた(" + n + " マス)"), false);
        return 1;
    }

    /**
     * 見本の部屋(撮影用): 今いる所を床として、29×29・高さ 12 の部屋を掘り、面を汚染してから、柱・水ぶくれ(床と天井)・縦穴の縄・
     * 張り出しから宙に垂れる縄・巣1 縫い目・送1 根株を決まった場所に置く。巣と根株は記録に載せない(芯は飾り)。地下で使うこと。
     */
    private static int demo(CommandContext<CommandSourceStack> c) {
        ServerLevel level = c.getSource().getServer().overworld();
        BlockPos o = BlockPos.containing(c.getSource().getPosition());
        net.minecraft.world.level.block.state.BlockState stone = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(),
                air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        int flags = net.minecraft.world.level.block.Block.UPDATE_CLIENTS;
        for (int x = -16; x <= 16; x++) for (int z = -16; z <= 16; z++) for (int y = -7; y <= 13; y++) {
            boolean room = Math.abs(x) <= 14 && Math.abs(z) <= 14 && y >= 0 && y <= 11 && !(x >= 9 && y >= 6 && y <= 7);
            level.setBlock(o.offset(x, y, z), room ? air : stone, flags);
        }
        net.minecraft.util.RandomSource r = net.minecraft.util.RandomSource.create(7);
        for (int x = -15; x <= 15; x++) for (int z = -15; z <= 15; z++) for (int y = -1; y <= 12; y++) {
            BlockPos p = o.offset(x, y, z);
            net.minecraft.world.level.block.state.BlockState s = level.getBlockState(p);
            if (s.isAir()) continue;
            boolean faces = false;
            for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) if (level.getBlockState(p.relative(d)).isAir()) faces = true;
            if (faces) com.yayuy.overgrownvegetation.corruption.fissure.FissureWorld.corruptOne(level, p, s, r, 0.0D);
        }
        java.util.List<FissureLook.Cell> cells = new java.util.ArrayList<>();
        CaveDecor.pillar(cells, new CaveDecor.Run(o.getX() - 7, o.getZ() - 7, o.getY(), 12, true, true));
        CaveDecor.blister(cells, new CaveDecor.Run(o.getX() + 5, o.getZ() - 9, o.getY(), 12, true, true), false, 3);
        CaveDecor.blister(cells, new CaveDecor.Run(o.getX() - 8, o.getZ() + 6, o.getY(), 12, true, true), true, 4);
        CaveDecor.shaftRope(cells, new CaveDecor.Run(o.getX() - 14, o.getZ() - 1, o.getY(), 12, true, true), net.minecraft.core.Direction.WEST, 5);
        CaveDecor.hangingRope(cells, new CaveDecor.Run(o.getX() + 9, o.getZ() - 3, o.getY(), 6, true, true), net.minecraft.core.Direction.WEST, false, 6);
        CaveDecor.hangingRope(cells, new CaveDecor.Run(o.getX() + 9, o.getZ() + 1, o.getY(), 6, true, true), net.minecraft.core.Direction.WEST, true, 7);
        FissureLook.enqueue(level, cells, null);
        com.yayuy.overgrownvegetation.corruption.fissure.FissureTable.Nest nt = com.yayuy.overgrownvegetation.data.DesignData.fissure().nest;
        it.unimi.dsi.fastutil.longs.LongArrayList junk = new it.unimi.dsi.fastutil.longs.LongArrayList();
        FissureLook.placeWithCore(level, FissureLook.NEST.build(o.offset(0, 0, -3), net.minecraft.core.Direction.Axis.X, net.minecraft.util.RandomSource.create(8), nt), junk);
        FissureLook.placeWithCore(level, FissureLook.BORER.build(o.offset(0, 0, 8), net.minecraft.core.Direction.Axis.X, net.minecraft.util.RandomSource.create(9), nt), junk);
        int n = FissureLook.drainAll();
        c.getSource().sendSuccess(() -> Component.literal("見本の部屋を組んだ(" + n + " マス)。柱 -7,-7・水ぶくれ 床 +5,-9 / 天井 -8,+6・縦穴の縄 -14,-1・宙の縄 +9,-3 / +9,+1(途中まで)・巣 0,-3・根株 0,+8"), false);
        return 1;
    }

    private static int info(CommandContext<CommandSourceStack> c) {
        ServerLevel level = c.getSource().getServer().overworld();
        c.getSource().sendSuccess(() -> Component.literal("洞窟の飾り: 飾ったチャンク " + CaveDecor.Data.get(level).size() + "・置く列の残り "
                + FissureLook.queued() + "・直近 " + CaveDecor.last), false);
        c.getSource().sendSuccess(() -> Component.literal("洞窟の呼吸: 覚えた口 " + CaveBreathing.Data.get(level).mouthCount() + "・直近 " + CaveBreathing.last), false);
        return 1;
    }

    private static int here(CommandContext<CommandSourceStack> c) {
        ServerLevel level = c.getSource().getServer().overworld();
        BlockPos p = BlockPos.containing(c.getSource().getPosition());
        LevelChunk chunk = level.getChunkSource().getChunkNow(p.getX() >> 4, p.getZ() >> 4);
        if (chunk == null || !CaveDecor.ready(level, p.getX() >> 4, p.getZ() >> 4)) {
            c.getSource().sendFailure(Component.literal("まわりが読み込まれていない"));
            return 0;
        }
        CaveDecor.Result r = CaveDecor.decorateChunk(level, chunk, level.getRandom(), new CaveDecor.Options(1.0D, 1.0D, 99));
        CaveDecor.Data.get(level).mark(chunk.getPos().toLong());
        String line = r.describe(chunk.getPos());
        CaveDecor.last = line;
        c.getSource().sendSuccess(() -> Component.literal(line), false);
        return 1;
    }
}
