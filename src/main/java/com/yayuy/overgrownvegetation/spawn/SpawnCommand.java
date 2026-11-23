package com.yayuy.overgrownvegetation.spawn;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterCommandsEvent;

/**
 * 確かめ用の命令(2026-11-15):
 * <ul>
 *   <li>{@code /ov perf spawns lurker} — 霧の中の本物を今すぐ 1 体。</li>
 *   <li>{@code /ov perf spawns march <x> <z>} — まわりの歩かせられる Mob を (x, z) へ歩かせる。</li>
 * </ul>
 * 2026-11-23 片付け E: 縁・地下の湧きを消したので、表示・{@code tick}・{@code clear}・{@code bench} も消した。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE)
public final class SpawnCommand {

    private SpawnCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ov")
                .then(Commands.literal("perf")
                        .requires(s -> s.hasPermission(2))
                        .then(Commands.literal("spawns")
                        // 怖い現象 E の「たまに本物」を今すぐ 1 体(確かめ用。条件のうち、汚染地帯の中・木の幹のそばだけを見る。11-16)
                        .then(Commands.literal("lurker").executes(ctx -> {
                            CommandSourceStack src = ctx.getSource();
                            Vec3 p = src.getPosition();
                            net.minecraft.world.entity.Mob m = FogLurkers.trySpawn(src.getLevel(), p.x, p.z, src.getLevel().getRandom());
                            if (m != null) m.addTag(FogLurkers.MANUAL_TAG); // 条件が外れても残す(見比べ用)
                            src.sendSuccess(() -> Component.literal(m == null ? "出せなかった(汚染地帯の外・見通しの縁に木が無い)"
                                    : "霧の中の本物: " + m.getType().getDescription().getString() + " (" + m.getBlockX() + ", " + m.getBlockY() + ", " + m.getBlockZ() + ")"), false);
                            return m == null ? 0 : 1;
                        }))
                        .then(Commands.literal("march")
                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                                .executes(ctx -> march(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "x"),
                                                        IntegerArgumentType.getInteger(ctx, "z")))))))));
    }

    /** まわり 32 ブロックの、歩かせられる G2・G3 を (x, z) へ歩かせる(司令塔の送り込みと同じ歩き。確かめ用)。 */
    private static int march(CommandSourceStack src, int x, int z) {
        ServerLevel level = src.getLevel();
        Vec3 p = src.getPosition();
        int n = 0;
        for (Mob m : level.getEntitiesOfClass(Mob.class, new AABB(p, p).inflate(32.0D))) {
            if (PlantMarch.begin(m, -1, x + 0.5D, z + 0.5D)) n++;
        }
        int fn = n;
        src.sendSuccess(() -> Component.literal(fn + " 体を (" + x + ", " + z + ") へ歩かせた"), false);
        return n;
    }

}
