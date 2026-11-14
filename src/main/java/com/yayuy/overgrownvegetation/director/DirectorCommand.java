package com.yayuy.overgrownvegetation.director;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.AbstractParasitizedMonster;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 司令塔の確かめ用のコマンド(2026-11-13、第 1 段)。{@code /ov perf g1} の下({@code corpse/G1Command} と同じ枝に足す)。
 * <ul>
 *   <li>{@code director} — 森の予算・上限・貯まる速さ・これまでの判断と直近の判断、周り 32 ブロックの段階 0 の「見に来るまで」。</li>
 *   <li>{@code budget <量>} — 森の予算をその量にする。</li>
 *   <li>{@code visit [半径]} — 周りの段階 0 を今すぐ見に来させる(判断は司令塔がする。予算が足りなければ何もしない)。</li>
 *   <li>{@code route evolve [半径]} — 周りの段階 0 を、司令塔を通さず(予算も払わず)進化の前兆に入らせる。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class DirectorCommand {

    private DirectorCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ov")
                .then(Commands.literal("perf")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("g1")
                                .then(Commands.literal("director")
                                        .executes(ctx -> describe(ctx.getSource())))
                                .then(Commands.literal("budget")
                                        .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0.0D, 1.0E6D))
                                                .executes(ctx -> budget(ctx.getSource(), DoubleArgumentType.getDouble(ctx, "amount")))))
                                .then(Commands.literal("visit")
                                        .executes(ctx -> visit(ctx.getSource(), 16))
                                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 64))
                                                .executes(ctx -> visit(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "radius")))))
                                .then(Commands.literal("route")
                                        .then(Commands.literal("evolve")
                                                .executes(ctx -> forceEvolve(ctx.getSource(), 16))
                                                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 64))
                                                        .executes(ctx -> forceEvolve(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "radius")))))
                                        .then(Commands.literal("rooted")
                                                .executes(ctx -> forceRooted(ctx.getSource(), 16))
                                                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 64))
                                                        .executes(ctx -> forceRooted(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "radius")))))
                                        .then(Commands.literal("nursery")
                                                .executes(ctx -> forceNursery(ctx.getSource(), 16))
                                                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 64))
                                                        .executes(ctx -> forceNursery(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "radius")))))))));
    }

    /** 半径の中の段階 0 を、司令塔を通さず(予算も払わず)苗床の道に入らせる: 森から離れて歩く → 根を下ろす → 苗床に入れ替わる。 */
    private static int forceNursery(CommandSourceStack source, int radius) {
        ServerLevel level = source.getLevel();
        int n = 0;
        for (AbstractParasitizedMonster m : stageZero(source, radius)) {
            if (m.fate().route() == ForestDirector.Route.NONE && !m.isRising()) {
                m.fate().beginNursery();
                ForestDirector.get(level).record(ForestDirector.Route.NURSERY, m.species().key() + " → 苗床(コマンド)");
                n++;
            }
        }
        int count = n;
        source.sendSuccess(() -> Component.literal("段階 0 を苗床の道に入らせた(森から離れて歩く): " + count + " 体"), true);
        return n;
    }

    /**
     * 半径の中の段階 0 を、司令塔を通さず(予算も払わず)根付きの道に入らせる: 森から離れて歩く → 根を下ろす。
     * 根付きの姿をまだ組み込んでいない種は、根を下ろした所でやめて段階 0 のまま残る。
     */
    private static int forceRooted(CommandSourceStack source, int radius) {
        ServerLevel level = source.getLevel();
        int n = 0;
        for (AbstractParasitizedMonster m : stageZero(source, radius)) {
            if (m.fate().route() == ForestDirector.Route.NONE && !m.isRising()) {
                m.fate().beginRooted();
                ForestDirector.get(level).record(ForestDirector.Route.ROOTED, m.species().key() + " → 根付き(コマンド)");
                n++;
            }
        }
        int count = n;
        source.sendSuccess(() -> Component.literal("段階 0 を根付きの道に入らせた(森から離れて歩く): " + count + " 体"), true);
        return n;
    }

    /** 半径の中の、司令塔が見に来る個体(段階 0。前兆の途中のものは除く)。 */
    private static List<AbstractParasitizedMonster> stageZero(CommandSourceStack source, int radius) {
        List<AbstractParasitizedMonster> out = new ArrayList<>();
        for (AbstractParasitizedMonster m : source.getLevel().getEntitiesOfClass(AbstractParasitizedMonster.class,
                AABB.ofSize(source.getPosition(), radius * 2, radius * 2, radius * 2))) {
            if (m.isAlive() && m.fate() != null) out.add(m);
        }
        return out;
    }

    private static int describe(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        for (String line : ForestDirector.get(level).describe(level)) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        long now = level.getGameTime();
        List<AbstractParasitizedMonster> near = stageZero(source, 32);
        for (AbstractParasitizedMonster m : near) {
            G1Fate f = m.fate();
            String when = f.route() != ForestDirector.Route.NONE ? f.route().label + "の途中"
                    : f.nextVisit() < 0L ? "まだ決めていない"
                    : String.format(Locale.ROOT, "あと %.1f 分", Math.max(0L, f.nextVisit() - now) / 1200.0D);
            source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "  %s(%.0f, %.0f, %.0f): 見に来るまで %s",
                    m.species().key(), m.getX(), m.getY(), m.getZ(), when)), false);
        }
        return 1 + near.size();
    }

    private static int budget(CommandSourceStack source, double amount) {
        ServerLevel level = source.getLevel();
        ForestDirector.get(level).setBudget(level, amount);
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "森の予算を %.1f にした(上限 %.1f)", amount, ForestDirector.cap(level))), true);
        return 1;
    }

    private static int visit(CommandSourceStack source, int radius) {
        long now = source.getLevel().getGameTime();
        int n = 0;
        for (AbstractParasitizedMonster m : stageZero(source, radius)) {
            if (m.fate().route() == ForestDirector.Route.NONE) {
                m.fate().setNextVisit(now);
                n++;
            }
        }
        int count = n;
        source.sendSuccess(() -> Component.literal("段階 0 を今すぐ見に来させた: " + count + " 体(結果は /ov perf g1 director)"), true);
        return n;
    }

    private static int forceEvolve(CommandSourceStack source, int radius) {
        ServerLevel level = source.getLevel();
        int n = 0;
        for (AbstractParasitizedMonster m : stageZero(source, radius)) {
            if (m.fate().route() == ForestDirector.Route.NONE && !m.isRising()) {
                m.fate().beginEvolve();
                ForestDirector.get(level).record(ForestDirector.Route.EVOLVE, m.species().key() + " → 進化(コマンド)");
                n++;
            }
        }
        int count = n;
        source.sendSuccess(() -> Component.literal("段階 0 を進化の前兆に入らせた: " + count + " 体"), true);
        return n;
    }
}
