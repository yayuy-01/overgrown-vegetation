package com.yayuy.overgrownvegetation.infect;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterCommandsEvent;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import com.yayuy.overgrownvegetation.port.attachment.OvAttach;

/**
 * 汎用の感染体の確かめ用の命令(2026-11-15、{@link GenericInfection})。{@code /ov perf infection} の下に足す(同じ枝に Brigadier がまとめる)。
 * <ul>
 *     <li>{@code /ov perf infection transform <対象> [instant]} — 今すぐ変える(instant = 変わる動きを飛ばして変わり終えた姿に)。なれない Mob は数えない。</li>
 *     <li>{@code /ov perf infection revert <対象>} — 戻す。</li>
 *     <li>{@code /ov perf infection list [半径]} — まわり(既定 32)の変わった Mob の一覧。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE)
public final class GenericInfectionCommand {

    private GenericInfectionCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ov")
                .then(Commands.literal("perf")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("infection")
                                .then(Commands.literal("transform")
                                        .then(Commands.argument("targets", EntityArgument.entities())
                                                .executes(ctx -> transform(ctx.getSource(), EntityArgument.getEntities(ctx, "targets"), false))
                                                .then(Commands.literal("instant")
                                                        .executes(ctx -> transform(ctx.getSource(), EntityArgument.getEntities(ctx, "targets"), true)))))
                                .then(Commands.literal("revert")
                                        .then(Commands.argument("targets", EntityArgument.entities())
                                                .executes(ctx -> revert(ctx.getSource(), EntityArgument.getEntities(ctx, "targets")))))
                                .then(Commands.literal("list")
                                        .executes(ctx -> list(ctx.getSource(), 32))
                                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 256))
                                                .executes(ctx -> list(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "radius"))))))));
    }

    private static int transform(CommandSourceStack source, Collection<? extends Entity> targets, boolean instant) {
        int n = 0, refused = 0;
        for (Entity e : targets) {
            if (!(e instanceof LivingEntity le)) continue;
            if (GenericInfection.turn(le, instant)) n++;
            else if (!GenericInfection.isTurned(le)) refused++;
        }
        int count = n, no = refused;
        source.sendSuccess(() -> Component.literal(count + " 体を汎用の感染体に変えた" + (instant ? "(すぐ)" : "")
                + (no > 0 ? "・なれない " + no + " 体(専用の G1 がある・植物側・除外の種)" : "")), true);
        return n;
    }

    private static int revert(CommandSourceStack source, Collection<? extends Entity> targets) {
        int n = 0;
        for (Entity e : targets) {
            if (e instanceof LivingEntity le && GenericInfection.revert(le)) n++;
        }
        int count = n;
        source.sendSuccess(() -> Component.literal(count + " 体を戻した"), true);
        return n;
    }

    private static int list(CommandSourceStack source, int radius) {
        long now = source.getLevel().getGameTime();
        List<LivingEntity> found = source.getLevel().getEntitiesOfClass(LivingEntity.class,
                AABB.ofSize(source.getPosition(), radius * 2, radius * 2, radius * 2), GenericInfection::isTurned);
        StringBuilder sb = new StringBuilder("変わった Mob " + found.size() + " 体(起動してから変えた回数 " + GenericInfection.turnedCount + ")");
        int shown = 0;
        for (LivingEntity e : found) {
            if (shown++ >= 20) {
                sb.append("\n…ほか ").append(found.size() - 20).append(" 体");
                break;
            }
            GenericInfected g = OvAttach.getData(e, ModAttachments.GENERIC_INFECTED);
            long age = now - g.startTick;
            sb.append(String.format(Locale.ROOT, "\n%s #%d %.0f,%.0f,%.0f 体力 %.1f/%.1f %s%s 種 %016x",
                    BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()), e.getId(), e.getX(), e.getY(), e.getZ(),
                    e.getHealth(), e.getMaxHealth(),
                    g.transforming(now) ? "変わる途中 " + age + "/" + g.transformTicks + " tick" : "変わり終えた",
                    g.boss ? "・ボス" : "", g.seed));
            // 狙いと動いている Goal(ほかの MOD の Mob が襲わない不具合の確かめ用、11-15)
            LivingEntity t = e instanceof net.minecraft.world.entity.Mob m ? GenericInfectionAi.targetOf(m) : null;
            sb.append("\n  狙い ").append(t == null ? "なし" : BuiltInRegistries.ENTITY_TYPE.getKey(t.getType()) + " #" + t.getId())
                    .append(" 予備 ").append(g.fallbackOn);
            if (e instanceof net.minecraft.world.entity.Mob m) {
                sb.append(" 動いている Goal ");
                m.goalSelector.getAvailableGoals().stream().filter(net.minecraft.world.entity.ai.goal.WrappedGoal::isRunning)
                        .forEach(w -> sb.append(w.getGoal().getClass().getSimpleName()).append(' '));
                sb.append("| 標的 ");
                m.targetSelector.getAvailableGoals().forEach(w -> sb.append(w.getGoal().getClass().getSimpleName())
                        .append(w.isRunning() ? "* " : " "));
            }
        }
        String msg = sb.toString();
        source.sendSuccess(() -> Component.literal(msg), false);
        return found.size();
    }
}
