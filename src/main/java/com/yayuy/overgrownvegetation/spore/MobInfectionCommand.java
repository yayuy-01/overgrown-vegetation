package com.yayuy.overgrownvegetation.spore;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterCommandsEvent;
import com.yayuy.overgrownvegetation.port.attachment.OvAttach;

/**
 * 確かめ用の命令(2026-11-14、{@link MobInfection})。
 * <ul>
 *     <li>{@code /ov perf infection} — 読み込まれている次元の、感染した Mob の数・数えている途中の数・起動してからの感染と飛び火の回数。</li>
 *     <li>{@code /ov perf infection infect <対象>} — 感染させる(測る Mob だけ)。</li>
 *     <li>{@code /ov perf infection show <対象>} — 1 体の暴露量・感染・逃げる向き。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE)
public final class MobInfectionCommand {

    private MobInfectionCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ov")
                .then(Commands.literal("perf")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("infection")
                        .executes(ctx -> status(ctx.getSource()))
                        .then(Commands.literal("infect")
                                .then(Commands.argument("targets", EntityArgument.entities())
                                        .executes(ctx -> infect(ctx.getSource(), EntityArgument.getEntities(ctx, "targets")))))
                        .then(Commands.literal("show")
                                .then(Commands.argument("targets", EntityArgument.entities())
                                        .executes(ctx -> show(ctx.getSource(), EntityArgument.getEntities(ctx, "targets"))))))));
    }

    private static int status(CommandSourceStack source) {
        int infected = 0, counting = 0;
        for (ServerLevel level : source.getServer().getAllLevels()) {
            for (Entity e : level.getAllEntities()) {
                if (!(e instanceof LivingEntity le) || !le.isAlive()) continue;
                if (MobInfection.isInfected(le)) infected++;
                else if (MobInfection.isCounting(le)) counting++;
            }
        }
        String msg = "感染 " + infected + " 体・数えている途中 " + counting + " 体 / 起動してから 感染 " + MobInfection.infectedCount
                + " 回・飛び火 " + MobInfection.jumpCount + " 回(最後 " + MobInfection.lastJumpForTests + ")";
        source.sendSuccess(() -> Component.literal(msg), false);
        return infected;
    }

    private static int infect(CommandSourceStack source, java.util.Collection<? extends Entity> targets) {
        int n = 0;
        long now = source.getLevel().getGameTime();
        for (Entity e : targets) {
            if (e instanceof LivingEntity le && SporeExposureTracker.isMeasuredMob(le)) {
                MobInfection.infect(le, now);
                n++;
            }
        }
        int count = n;
        source.sendSuccess(() -> Component.literal(count + " 体を感染させた"), true);
        return n;
    }

    private static int show(CommandSourceStack source, java.util.Collection<? extends Entity> targets) {
        for (Entity e : targets) {
            if (!(e instanceof LivingEntity le)) continue;
            String msg;
            if (!OvAttach.hasData(le, ModAttachments.SPORE_EXPOSURE)) {
                msg = e.getName().getString() + ": 記録なし";
            } else {
                SporeExposure ex = OvAttach.getData(le, ModAttachments.SPORE_EXPOSURE);
                msg = String.format("%s: 暴露 %.0f・感染 %s(%d tick 前)・数え %d tick・逃げる向き %.2f,%.2f・逃げる %s・体力 %.1f/%.1f",
                        e.getName().getString(), ex.dose, ex.infected,
                        ex.infectedGameTime < 0 ? -1 : source.getLevel().getGameTime() - ex.infectedGameTime,
                        ex.exposedTicks, ex.fleeX, ex.fleeZ, MobInfection.shouldFlee(le, source.getLevel().getGameTime()),
                        le.getHealth(), le.getMaxHealth());
            }
            String m = msg;
            source.sendSuccess(() -> Component.literal(m), false);
        }
        return targets.size();
    }
}
