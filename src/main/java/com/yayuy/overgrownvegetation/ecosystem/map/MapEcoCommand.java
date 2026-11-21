package com.yayuy.overgrownvegetation.ecosystem.map;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.Locale;
import java.util.Map;

/**
 * 確かめ用の命令(2026-11-21 地図の上の生態系):
 * <ul>
 *   <li>{@code /ov perf eco map} — 今いる升とまわり 8 升の満員・健康・感染・割合・発芽の倍率・状態と、台帳の数。</li>
 *   <li>{@code /ov perf eco map infect <体数> <秒>} — 今いる升に広げる部隊が居たことにする(前線と部隊の口と同じ {@link MapEcology#infect})。</li>
 *   <li>{@code /ov perf eco map sync} — 今いる升をすぐ実物と照らし合わせる。</li>
 *   <li>{@code /ov perf eco map reset} — 台帳を空にする(過繁茂の印は残る)。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class MapEcoCommand {

    private MapEcoCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ov")
                .then(Commands.literal("perf")
                        .requires(s -> s.hasPermission(2))
                        .then(Commands.literal("eco")
                                .then(Commands.literal("map")
                                        .executes(ctx -> show(ctx.getSource()))
                                        .then(Commands.literal("infect")
                                                .then(Commands.argument("size", IntegerArgumentType.integer(1, 500))
                                                        .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 86400))
                                                                .executes(ctx -> infect(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "size"),
                                                                        IntegerArgumentType.getInteger(ctx, "seconds"))))))
                                        .then(Commands.literal("sync").executes(ctx -> sync(ctx.getSource())))
                                        .then(Commands.literal("reset").executes(ctx -> reset(ctx.getSource())))))));
    }

    private static long here(CommandSourceStack src) {
        Vec3 p = src.getPosition();
        return MapEcology.cellOf((int) Math.floor(p.x) >> 4, (int) Math.floor(p.z) >> 4);
    }

    private static int show(CommandSourceStack src) {
        ServerLevel level = src.getServer().overworld();
        long key = here(src);
        MapLedger ledger = MapLedger.get(src.getServer());
        say(src, String.format(Locale.ROOT, "地図の上の生態系: %s・台帳の升 %d・胞子の場へ渡した升 %d・これまでの感染 地図 %d・実物 %d・照らし合わせ %d・過繁茂にした升 %d",
                MapEcology.active(level) ? "動いている" : "止まっている", ledger.size(), MapEcology.publishedCells(),
                ledger.infectedMap(), ledger.infectedReal(), MapSync.reconciled, ledger.overgrownCells()));
        int cx = net.minecraft.world.level.ChunkPos.getX(key), cz = net.minecraft.world.level.ChunkPos.getZ(key);
        for (int dz = -1; dz <= 1; dz++) {
            StringBuilder sb = new StringBuilder();
            for (int dx = -1; dx <= 1; dx++) {
                MapEcology.CellView v = MapEcology.view(level, net.minecraft.world.level.ChunkPos.asLong(cx + dx, cz + dz));
                sb.append(dx == 0 && dz == 0 ? "[" : " ").append(cell(v)).append(dx == 0 && dz == 0 ? "]" : " ");
            }
            say(src, sb.toString());
        }
        MapEcology.CellView v = MapEcology.view(level, key);
        say(src, String.format(Locale.ROOT, "今いる升 (%d, %d): 満員 %d(動物 %d・村人 %d)・健康 %.1f・感染 %.2f(%.0f%%)・発芽のしきい値 ×%.2f・%s%s・バイオーム %s",
                cx, cz, v.full(), v.animals(), v.villagers(), v.healthy(), v.infected(), v.ratio() * 100.0D, v.germMul(),
                v.barren() ? "生き物の少ない升(胞子だけ)" : "生き物のいる升", v.overgrown() ? "・過繁茂" : "",
                MapCapacity.biomeAt(level, key, MapEcology.shift()).unwrapKey().map(k -> k.location().toString()).orElse("?")));
        return 1;
    }

    private static String cell(MapEcology.CellView v) {
        if (v.overgrown()) return "  森  ";
        if (v.barren()) return String.format(Locale.ROOT, " (%2d) ", v.full());
        return String.format(Locale.ROOT, "%2.0f/%-2d%3.0f%%", v.healthy(), v.full(), v.ratio() * 100.0D);
    }

    private static int infect(CommandSourceStack src, int size, int seconds) {
        ServerLevel level = src.getServer().overworld();
        MapEcology.InfectResult r = MapEcology.infect(level, here(src), size, seconds * 20L);
        StringBuilder types = new StringBuilder();
        for (Map.Entry<EntityType<?>, Integer> e : r.byType().entrySet()) types.append(' ').append(MapCapacity.id(e.getKey())).append('×').append(e.getValue());
        say(src, String.format(Locale.ROOT, "%d 体 × %d 秒: 感染 %d(%s )・稼ぎ %.0f%s", size, seconds, r.infected(), types, r.income(),
                r.becameOvergrown() ? "・過繁茂になった" : ""));
        return r.infected();
    }

    private static int sync(CommandSourceStack src) {
        int n = MapSync.reconcile(src.getServer().overworld(), here(src), 1000);
        say(src, "実物を " + n + " 体感染させた");
        return n;
    }

    private static int reset(CommandSourceStack src) {
        MapLedger.get(src.getServer()).reset();
        MapEcology.clearCachesForTests();
        say(src, "地図の上の生態系の台帳を空にした");
        return 1;
    }

    private static void say(CommandSourceStack src, String s) {
        src.sendSuccess(() -> Component.literal(s), false);
    }
}
