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
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * 確かめ用の命令(2026-11-15):
 * <ul>
 *   <li>{@code /ov perf spawns} — まわりの縁の数・グループごとの数と上限・試しの結果。</li>
 *   <li>{@code /ov perf spawns tick [回数]} — その場で縁と地下の試しを回す(湧く様子を早く見る)。</li>
 *   <li>{@code /ov perf spawns clear} — まわり 256 ブロックの、縁・地下で湧いた個体を消す。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class SpawnCommand {

    private SpawnCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ov")
                .then(Commands.literal("perf")
                        .requires(s -> s.hasPermission(2))
                        .then(Commands.literal("spawns")
                        .executes(ctx -> show(ctx.getSource()))
                        .then(Commands.literal("tick")
                                .executes(ctx -> tick(ctx.getSource(), 1))
                                .then(Commands.argument("times", IntegerArgumentType.integer(1, 200))
                                        .executes(ctx -> tick(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "times")))))
                        .then(Commands.literal("clear").executes(ctx -> clear(ctx.getSource())))
                        // 重さの計測(見直し ②10、11-16): count・型の引き・種の判定の 1 回あたりの時間
                        .then(Commands.literal("bench").executes(ctx -> bench(ctx.getSource())))
                        // 怖い現象 E の「たまに本物」を今すぐ 1 体(確かめ用。条件のうち、汚染地帯の中・木の幹のそばだけを見る。11-16)
                        .then(Commands.literal("lurker").executes(ctx -> {
                            CommandSourceStack src = ctx.getSource();
                            Vec3 p = src.getPosition();
                            net.minecraft.world.entity.Mob m = FogLurkers.trySpawn(src.getLevel(), p.x, p.z, src.getLevel().getRandom());
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

    /** まわり 32 ブロックの、歩かせられる G4・G5 を (x, z) へ歩かせる(司令塔の送り込みと同じ歩き。確かめ用)。 */
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

    private static int bench(CommandSourceStack src) {
        ServerLevel level = src.getLevel();
        Vec3 p = src.getPosition();
        SpawnTable t = com.yayuy.overgrownvegetation.data.DesignData.spawns();
        int[] c = null;
        long a0 = System.nanoTime();
        for (int i = 0; i < 200; i++) c = EnemySpawns.count(level, p.x, p.y, p.z, t.countRadius);
        long a1 = System.nanoTime();
        int sink = 0;
        for (int i = 0; i < 100_000; i++) for (SpawnTable.Species sp : t.species) if (sp.type() != null) sink++;
        long a2 = System.nanoTime();
        net.minecraft.world.entity.EntityType<?> arbora = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.get(
                OvergrownVegetation.id("arbora"));
        for (int i = 0; i < 100_000; i++) if (DirectorHooks.breaker(arbora) && DirectorHooks.marchable(arbora)) sink++;
        long a3 = System.nanoTime();
        int[] cc = c;
        int fs = sink;
        src.sendSuccess(() -> Component.literal(String.format(java.util.Locale.ROOT,
                "SPAWNBENCH count=%.3fms(縁 %d・地下 %d) type()=%.1fns/種 breaker+marchable=%.0fns/回 (%d)",
                (a1 - a0) / 200.0 / 1e6, cc[1] + cc[4] + cc[5], cc[0], (a2 - a1) / 100_000.0 / t.species.size(), (a3 - a2) / 100_000.0, fs)), false);
        return 1;
    }

    private static int show(CommandSourceStack src) {
        ServerLevel level = src.getLevel();
        Vec3 p = src.getPosition();
        for (String line : EnemySpawns.describe(level, p.x, p.y, p.z)) {
            src.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    private static int tick(CommandSourceStack src, int times) {
        ServerLevel level = src.getLevel();
        Vec3 p = src.getPosition();
        long before = EnemySpawns.spawnedEdge + EnemySpawns.spawnedUnder;
        boolean allowed = EnemySpawns.allowed(level);
        if (allowed) {
            for (int i = 0; i < times; i++) EnemySpawns.tickPlayer(level, p.x, p.y, p.z, level.getRandom());
        }
        long n = EnemySpawns.spawnedEdge + EnemySpawns.spawnedUnder - before;
        src.sendSuccess(() -> Component.literal(allowed ? times + " 回試して " + n + " 体湧いた" : "湧きが止まっている(次元・ピースフル・doMobSpawning・大きな襲撃の前触れ)"), false);
        return (int) n;
    }

    private static int clear(CommandSourceStack src) {
        ServerLevel level = src.getLevel();
        Vec3 p = src.getPosition();
        int n = 0;
        for (Mob m : level.getEntitiesOfClass(Mob.class, new AABB(p, p).inflate(256.0D, 384.0D, 256.0D), EnemySpawns::spawnedHere)) {
            m.discard();
            n++;
        }
        int fn = n;
        src.sendSuccess(() -> Component.literal(fn + " 体消した"), false);
        return n;
    }
}
