package com.yayuy.overgrownvegetation.front;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.director.ForestUpkeep;
import com.yayuy.overgrownvegetation.horde.VirtualHordes;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterCommandsEvent;

import java.util.Locale;

/**
 * 前線と部隊の確かめ(2026-11-21)。
 * <ul>
 *     <li>{@code /ov perf front} … 今の数({@link FrontDirector#describe} と維持費)</li>
 *     <li>{@code /ov perf front list} … 前線ごとの場所・攻められた度合い・部隊の数</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE)
public final class FrontCommand {

    private FrontCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        // /ov の下の一覧は決めてある(OvCommandTree のテスト)ので、確かめ用は /ov perf の下に置く
        event.getDispatcher().register(Commands.literal("ov")
                .then(Commands.literal("perf").requires(s -> s.hasPermission(2))
                        .then(Commands.literal("front")
                                .executes(ctx -> show(ctx.getSource()))
                                .then(Commands.literal("list").executes(ctx -> list(ctx.getSource()))))));
    }

    private static int show(CommandSourceStack src) {
        ServerLevel level = src.getServer().overworld();
        src.sendSuccess(() -> Component.literal(FrontDirector.describe(level)), false);
        src.sendSuccess(() -> Component.literal(ForestUpkeep.describe(level)), false);
        src.sendSuccess(() -> Component.literal(FrontRaids.describe(level)), false);
        src.sendSuccess(() -> Component.literal(FrontTrees.describe(level)), false);
        src.sendSuccess(() -> Component.literal(ForeignInfection.describe(level)), false);
        src.sendSuccess(() -> Component.literal(FrontNurseries.describe(level)), false);
        src.sendSuccess(() -> Component.literal(FrontOutposts.describe(level)), false);
        src.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "狩り: 始めた %d 回・抜いた %d 体・終えた %d", FrontHunts.started, FrontHunts.pulled, FrontHunts.ended)), false);
        return 1;
    }

    private static int list(CommandSourceStack src) {
        ServerLevel level = src.getServer().overworld();
        VirtualHordes h = VirtualHordes.get(src.getServer());
        for (Fronts.Front f : Fronts.get(src.getServer()).fronts()) {
            int units = 0, heads = 0;
            for (VirtualHordes.Swarm s : h.swarms()) {
                if (s.front() != f.id()) continue;
                units++;
                heads += s.size();
            }
            int u = units, hd = heads;
            src.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "#%d %s (%.0f, %.0f) 部隊 %d・仮想 %d 体・最近倒された値段 %.0f",
                    f.id(), f.kind() == Fronts.Kind.TREE ? "巨大樹" : "発生源", f.x(), f.z(), u, hd, f.losses())), false);
        }
        return 1;
    }
}
