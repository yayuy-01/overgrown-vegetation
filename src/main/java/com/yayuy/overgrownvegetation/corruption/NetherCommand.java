package com.yayuy.overgrownvegetation.corruption;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.NetherSpreadTable;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterCommandsEvent;

/**
 * {@code /ov nether …} — ネザーの汚染の骨組み(根の台帳と段階、2026-11-11)を見る・進める。OP 専用。どれもネザーにいるときだけ。
 * <ul>
 *     <li>{@code /ov nether status} 台帳の全体(届いたチャンク・前線・過繁茂・湖・速さ)と、段階の当てはめの重さ</li>
 *     <li>{@code /ov nether stage} 立っているチャンクの段階・届いてからの時間・当てはめ済みの段階・海か・湖の水面</li>
 *     <li>{@code /ov nether advance <日数>} 台帳の時計を進める(届いた時刻を前へずらし、前線を追いつかせる)</li>
 *     <li>{@code /ov nether speed [倍率]} 根の速さの倍率(このワールドの値。これから決まる前線に効く)</li>
 *     <li>{@code /ov nether seed} 足元のチャンクから森を始める(ポータルの出口と同じ。最初の根も伸びる)</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE)
public final class NetherCommand {

    private NetherCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ov")
                .then(Commands.literal("nether")
                        .requires(s -> s.hasPermission(2))
                        .executes(NetherCommand::status)
                        .then(Commands.literal("status").executes(NetherCommand::status))
                        .then(Commands.literal("stage").executes(NetherCommand::stage))
                        .then(Commands.literal("advance")
                                .then(Commands.argument("days", DoubleArgumentType.doubleArg(0.0, 30.0))
                                        .executes(NetherCommand::advance)))
                        .then(Commands.literal("speed")
                                .executes(NetherCommand::showSpeed)
                                .then(Commands.argument("multiplier", DoubleArgumentType.doubleArg(0.01, 1000.0))
                                        .executes(NetherCommand::setSpeed)))
                        .then(Commands.literal("seed").executes(NetherCommand::seed))));
    }

    private static ServerLevel nether(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        if (!NetherLedger.isLedgerLevel(level)) {
            ctx.getSource().sendFailure(Component.literal("ネザーで使ってください"));
            return null;
        }
        if (!NetherLedger.active(level)) {
            ctx.getSource().sendFailure(Component.literal("新しいネザーの仕組みは止まっている(このワールドの実験的要素がオフ = /ov experimental on で動く。か nether_spread.json の enabled が false)"));
            return null;
        }
        return level;
    }

    private static void say(CommandContext<CommandSourceStack> ctx, String text) {
        ctx.getSource().sendSuccess(() -> Component.literal(text), false);
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = nether(ctx);
        if (level == null) return 0;
        NetherLedgerData d = NetherLedgerData.get(level);
        NetherSpreadTable t = DesignData.netherSpread();
        say(ctx, String.format("ネザーの根: %s・届いたチャンク %d・前線 %d・過繁茂(段階 3〜)%d・天を支える樹の口 %d・速さ ×%.2f",
                t.enabled ? "動いている" : "止まっている(nether_spread.json enabled=false)",
                d.reachedCount(), d.frontierCount(), d.overgrownCount(), d.pillarCount(), d.speed()));
        say(ctx, "当てはめ: " + NetherStageWorker.perfLine());
        NetherStageWorker.resetPerf(); // 次の status までの分を測る
        return d.reachedCount();
    }

    private static int stage(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = nether(ctx);
        if (level == null) return 0;
        ChunkPos pos = new ChunkPos(BlockPos.containing(ctx.getSource().getPosition()));
        long key = pos.toLong();
        NetherLedgerData d = NetherLedgerData.get(level);
        boolean sea = NetherLedger.isSea(level, d, key);
        net.minecraft.world.level.chunk.LevelChunk loaded = level.getChunkSource().getChunkNow(pos.x, pos.z);
        say(ctx, String.format("海の判定: 覚え %s・読み込んだチャンク %s・生成器 %s", sea,
                loaded == null ? "-" : String.valueOf(NetherTerrain.isSeaLoaded(level, loaded)),
                NetherTerrain.isSeaByGenerator(level, pos.x, pos.z)));
        if (!d.isReached(key)) {
            say(ctx, String.format("チャンク %d, %d: まだ前線が届いていない(%s)", pos.x, pos.z, sea ? "溶岩の海" : "陸"));
            return 0;
        }
        long age = level.getGameTime() - d.arrivalOf(key);
        int st = NetherLedger.stageOf(level, key);
        say(ctx, String.format("チャンク %d, %d: 段階 %d(届いて %.2f 日)・地面は段階 %d まで・%s・湖の水面 %d マス",
                pos.x, pos.z, st, age / 24000.0, d.appliedStage(key), sea ? "溶岩の海" : "陸", d.lakeCellsOf(key)));
        // 管の樹(2026-11-12): 立てた樹の数と、胞子の場の管の樹の多さ(立てた数 ÷ tubeFullTrees)
        int trees = d.tubesOf(key);
        int full = com.yayuy.overgrownvegetation.data.DesignData.netherSpread().tubeFullTrees;
        say(ctx, String.format("管の樹 %d 本(多さ %.2f = %d 本で 1)%s", trees, Math.min(1.0, trees / (double) full), full,
                d.tubesCopy().containsKey(key) ? "" : "・まだ立て終えていない(胞子の場は熱さで見立てる)"));
        return st;
    }

    private static int advance(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = nether(ctx);
        if (level == null) return 0;
        double days = DoubleArgumentType.getDouble(ctx, "days");
        long ticks = Math.round(days * 24000.0);
        NetherLedger.advance(level, ticks);
        say(ctx, String.format("ネザーの根の時計を %.2f 日進めた(届いたチャンク %d)", days, NetherLedgerData.get(level).reachedCount()));
        return (int) ticks;
    }

    private static int showSpeed(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = nether(ctx);
        if (level == null) return 0;
        say(ctx, String.format("根の速さ ×%.2f", NetherLedgerData.get(level).speed()));
        return 1;
    }

    private static int setSpeed(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = nether(ctx);
        if (level == null) return 0;
        double m = DoubleArgumentType.getDouble(ctx, "multiplier");
        NetherLedger.setSpeed(level, m);
        say(ctx, String.format("根の速さを ×%.2f にした(これから決まる前線に効く)", m));
        return 1;
    }

    private static int seed(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = nether(ctx);
        if (level == null) return 0;
        BlockPos at = BlockPos.containing(ctx.getSource().getPosition());
        if (NetherLedgerData.get(level).isReached(new ChunkPos(at).toLong())) {
            ctx.getSource().sendFailure(Component.literal("ここはもう森(前線が届いている)"));
            return 0;
        }
        ChunkPos sea = NetherLedger.seedFromPortal(level, at);
        say(ctx, sea == null ? "ここから森を始めた(近くに溶岩の海は無い)"
                : String.format("ここから森を始めた。最初の根はチャンク %d, %d の溶岩の海へ", sea.x, sea.z));
        return 1;
    }
}
