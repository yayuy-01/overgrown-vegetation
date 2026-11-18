package com.yayuy.overgrownvegetation.horde;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 仮想の群れの確かめ(2026-11-18 大群の軽量化 A)。
 * <ul>
 *     <li>{@code /ov perf virtual} … 今の数({@link VirtualHordes#describe})</li>
 *     <li>{@code /ov perf virtual spawn <頭数> <fx> <fz> <tx> <tz>} … (fx, fz) に地上の行進できる 7 種を均等に混ぜた群れを置き、(tx, tz) へ進ませる(64 体ごとに 1 群れ)</li>
 *     <li>{@code /ov perf virtual clear} … 仮想の群れを全部消す(本物は残る)</li>
 *     <li>{@code /ov perf virtual on|off} … toml の virtualHordeEnabled を書き換える</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class VirtualHordeCommand {

    /** spawn で混ぜる種(地上の行進できる G4・G5)。 */
    static final String[] MIX = {"gregaria", "vesicaria", "strobila", "falcimnes", "pilimnes", "saltimnes", "venamnes"};

    private VirtualHordeCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ov")
                .then(Commands.literal("perf").requires(s -> s.hasPermission(2))
                        .then(Commands.literal("virtual")
                                .executes(ctx -> show(ctx.getSource()))
                                .then(Commands.literal("clear").executes(ctx -> {
                                    int n = VirtualHordes.get(ctx.getSource().getServer()).clear();
                                    ctx.getSource().sendSuccess(() -> Component.literal("仮想の群れを消した: " + n + " 体"), false);
                                    return n;
                                }))
                                .then(Commands.literal("list").executes(ctx -> list(ctx.getSource())))
                                .then(Commands.literal("on").executes(ctx -> set(ctx.getSource(), true)))
                                .then(Commands.literal("off").executes(ctx -> set(ctx.getSource(), false)))
                                .then(Commands.literal("spawn")
                                        .then(Commands.argument("heads", IntegerArgumentType.integer(1, 20000))
                                                .then(Commands.argument("fx", IntegerArgumentType.integer())
                                                        .then(Commands.argument("fz", IntegerArgumentType.integer())
                                                                .then(Commands.argument("tx", IntegerArgumentType.integer())
                                                                        .then(Commands.argument("tz", IntegerArgumentType.integer())
                                                                                .executes(ctx -> spawn(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "heads"),
                                                                                        IntegerArgumentType.getInteger(ctx, "fx"), IntegerArgumentType.getInteger(ctx, "fz"),
                                                                                        IntegerArgumentType.getInteger(ctx, "tx"), IntegerArgumentType.getInteger(ctx, "tz"))))))))))));
    }

    private static int show(CommandSourceStack src) {
        String s = VirtualHordes.get(src.getServer()).describe(src.getServer().overworld()) + "・大規模レイドから出した 群れ " + VirtualRaids.sentSwarms + "・" + VirtualRaids.sentHeads + " 体・攻め手 " + VirtualRaids.attackers(src.getServer().overworld())[0] + "(うち仮想から " + VirtualRaids.attackers(src.getServer().overworld())[1] + ")/ 上限 " + com.yayuy.overgrownvegetation.director.LargeRaids.maxAlive();
        src.sendSuccess(() -> Component.literal(s), false);
        return 1;
    }

    /** 群れごとに 1 行: プレイヤー(打った人)からの水平の距離・行き先までの距離・頭数・状態。 */
    private static int list(CommandSourceStack src) {
        net.minecraft.world.phys.Vec3 p = src.getPosition();
        long now = src.getServer().overworld().getGameTime();
        int n = 0;
        for (VirtualHordes.Swarm s : VirtualHordes.get(src.getServer()).swarms()) {
            double d = Math.sqrt((s.x - p.x) * (s.x - p.x) + (s.z - p.z) * (s.z - p.z));
            double dt = Math.sqrt((s.tx - s.x) * (s.tx - s.x) + (s.tz - s.z) * (s.tz - s.z));
            String state = s.retreating ? "引き返し" : s.arrivedAt >= 0L ? "着いた(" + (now - s.arrivedAt) + " tick)" : s.waiting ? "待ち" : "進む";
            String line = String.format(java.util.Locale.ROOT, "群れ #%d %d 体 (%.0f %.0f %.0f) 自分から %.0f・行き先まで %.0f・%s%s", s.id, s.size(), s.x, s.y, s.z, d, dt, state,
                    s.extraTag.isEmpty() ? "" : "・" + s.extraTag);
            src.sendSuccess(() -> Component.literal(line), false);
            n++;
        }
        if (n == 0) src.sendSuccess(() -> Component.literal("仮想の群れは無い"), false);
        return n;
    }

    private static int set(CommandSourceStack src, boolean on) {
        OvergrownVegetationConfig.COMMON.virtualHordeEnabled.set(on);
        return show(src);
    }

    /** 64 体ごとに 1 群れ。群れどうしは 12 ブロックずつ横に並べる。 */
    static int spawn(CommandSourceStack src, int heads, int fx, int fz, int tx, int tz) {
        ServerLevel level = src.getServer().overworld();
        VirtualHordes h = VirtualHordes.get(src.getServer());
        double dx = tx - fx, dz = tz - fz, d = Math.max(1.0E-3D, Math.sqrt(dx * dx + dz * dz));
        double sx = -dz / d, sz = dx / d;
        int made = 0, swarms = 0, left = heads;
        while (left > 0) {
            int n = Math.min(64, left);
            Map<EntityType<?>, Integer> mix = new LinkedHashMap<>();
            for (int i = 0; i < n; i++) {
                EntityType<?> t = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, MIX[(made + i) % MIX.length]));
                mix.merge(t, 1, Integer::sum);
            }
            double off = (swarms - (heads / 64) / 2.0D) * 12.0D;
            double x = fx + sx * off, z = fz + sz * off;
            double y = VirtualHordes.surfaceY(level, x, z);
            VirtualHordes.Swarm s = h.spawn(x, Double.isNaN(y) ? 64.0D : y, z, -1, tx + 0.5D, tz + 0.5D, "", mix);
            if (s != null) {
                made += s.size();
                swarms++;
            }
            left -= n;
        }
        int fm = made, fs = swarms;
        src.sendSuccess(() -> Component.literal("仮想の群れを置いた: " + fs + " 群れ・" + fm + " 体"), false);
        return made;
    }
}
