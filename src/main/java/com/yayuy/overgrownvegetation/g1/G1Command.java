package com.yayuy.overgrownvegetation.g1;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.CorruptedCorpseBlockEntity;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.director.ForestDirector;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.Comparator;
import java.util.List;

/**
 * {@code /ov perf g1 …} —— G1 の一生と司令塔を見る・進める(OP。G1 の作り直し 第 1 段、2026-10-22)。
 * コマンドは 10 系統に絞る決まり(2026-10-10)なので、調べ物の系統 perf の下に付けた。出力は開発用なので翻訳キーを作らない。
 * <ul>
 *     <li>(引数なし)… 司令塔の判断の数と直近の判断、周り 32 ブロックの G1 の道と時計</li>
 *     <li>{@code advance <日数>} … 周り 32 ブロックの G1 の時計と、周り 16 ブロックの死体の時計をその日数ぶん進める</li>
 *     <li>{@code route <rooted|nursery|evolve>} … いちばん近い G1 の道を決める(司令塔を通さない)</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class G1Command {

    private static final double RADIUS = 32.0D;

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ov")
                .then(Commands.literal("perf")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("g1")
                                .executes(G1Command::status)
                                .then(Commands.literal("advance")
                                        .then(Commands.argument("days", DoubleArgumentType.doubleArg(0.0D, 30.0D))
                                                .executes(ctx -> advance(ctx, DoubleArgumentType.getDouble(ctx, "days")))))
                                .then(Commands.literal("route")
                                        .then(Commands.literal("rooted").executes(ctx -> route(ctx, G1Life.Route.ROOTED)))
                                        .then(Commands.literal("nursery").executes(ctx -> route(ctx, G1Life.Route.NURSERY)))
                                        .then(Commands.literal("evolve").executes(ctx -> route(ctx, G1Life.Route.EVOLVE)))))));
    }

    private static List<Mob> nearby(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        return level.getEntitiesOfClass(Mob.class, new AABB(BlockPos.containing(source.getPosition())).inflate(RADIUS),
                        m -> G1Lifecycle.isSubject(level, m))
                .stream().sorted(Comparator.comparingDouble(m -> m.distanceToSqr(source.getPosition()))).toList();
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        for (String line : ForestDirector.get(source.getServer()).describe()) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        List<Mob> mobs = nearby(source);
        source.sendSuccess(() -> Component.literal("周り " + (int) RADIUS + " ブロックの G1: " + mobs.size() + " 体"), false);
        for (Mob mob : mobs.subList(0, Math.min(10, mobs.size()))) {
            G1Life life = mob.getData(ModAttachments.G1_LIFE);
            String line = String.format("  %s: %s・段階 0 から %.2f 日・道に入って %.2f 日%s",
                    BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).getPath(), ForestDirector.routeName(life.route),
                    life.age / 24000.0D, life.routeAge / 24000.0D, life.note.isEmpty() ? "" : "  [" + life.note + "]");
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return mobs.size();
    }

    private static int advance(CommandContext<CommandSourceStack> ctx, double days) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        long ticks = (long) (days * 24000.0D);
        int n = 0;
        for (Mob mob : nearby(source)) {
            G1Lifecycle.step(level, mob, mob.getData(ModAttachments.G1_LIFE), ticks);
            n++;
        }
        int corpses = 0;
        BlockPos center = BlockPos.containing(source.getPosition());
        for (BlockPos p : BlockPos.betweenClosed(center.offset(-16, -8, -16), center.offset(16, 8, 16))) {
            if (level.getBlockEntity(p) instanceof CorruptedCorpseBlockEntity corpse) {
                corpse.setAgeForTests((int) Math.min(Integer.MAX_VALUE, corpse.age() + ticks));
                corpses++;
            }
        }
        int mobs = n, dead = corpses;
        source.sendSuccess(() -> Component.literal(String.format("%.2f 日進めた: G1 %d 体・死体 %d(死体は次の tick に起き上がる。起き上がりまで %d tick)",
                days, mobs, dead, DesignData.g1().corpseEmitTicks)), true);
        return n + corpses;
    }

    private static int route(CommandContext<CommandSourceStack> ctx, G1Life.Route route) {
        CommandSourceStack source = ctx.getSource();
        List<Mob> mobs = nearby(source);
        if (mobs.isEmpty()) {
            source.sendFailure(Component.literal("周り " + (int) RADIUS + " ブロックに G1 がいない"));
            return 0;
        }
        Mob mob = mobs.get(0);
        G1Life life = mob.getData(ModAttachments.G1_LIFE);
        G1Lifecycle.force(source.getLevel(), mob, life, route);
        source.sendSuccess(() -> Component.literal(BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).getPath()
                + " の道を " + ForestDirector.routeName(life.route) + " にした"), true);
        return 1;
    }

    private G1Command() {
    }
}
