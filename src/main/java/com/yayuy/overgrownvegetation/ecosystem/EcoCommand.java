package com.yayuy.overgrownvegetation.ecosystem;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterCommandsEvent;

import java.util.Locale;

/**
 * 確かめ用の命令(2026-11-16):
 * <ul>
 *   <li>{@code /ov perf eco} — 多様性・倍率・今いる升の頭数・減った升の数・内側の上限と数・試しの結果。</li>
 *   <li>{@code /ov perf eco kill <2|3> [数]} — 今いる升で環境生物を倒したことにする(数える殺しと同じ)。</li>
 *   <li>{@code /ov perf eco tick [回数]} — その場で内側の守り(と、開発用のスイッチがあれば仮の種)の試しを回す。</li>
 *   <li>{@code /ov perf eco reset} — 全部の升を満員に戻す。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE)
public final class EcoCommand {

    private EcoCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ov")
                .then(Commands.literal("perf")
                        .requires(s -> s.hasPermission(2))
                        .then(Commands.literal("eco")
                                .executes(ctx -> show(ctx.getSource()))
                                .then(Commands.literal("kill")
                                        .then(killBranch("prey", Ecosystem.PREY))
                                        .then(killBranch("hunter", Ecosystem.HUNTER)))
                                .then(Commands.literal("tick")
                                        .executes(ctx -> tick(ctx.getSource(), 1))
                                        .then(Commands.argument("times", IntegerArgumentType.integer(1, 200))
                                                .executes(ctx -> tick(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "times")))))
                                .then(Commands.literal("reset").executes(ctx -> reset(ctx.getSource()))))));
    }

    /** kill の下の prey / hunter(環境生物の側。2026-11-21 に旧 G2・G3 の番号 2・3 から付け替え)。 */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> killBranch(String name, int kind) {
        return Commands.literal(name)
                .executes(ctx -> kill(ctx.getSource(), kind, 1))
                .then(Commands.argument("count", IntegerArgumentType.integer(1, 1000))
                        .executes(ctx -> kill(ctx.getSource(), kind, IntegerArgumentType.getInteger(ctx, "count"))));
    }

    private static int show(CommandSourceStack src) {
        ServerLevel level = src.getServer().overworld();
        EcoLedger ledger = EcoLedger.get(level);
        long now = EcoLedger.now(level);
        Vec3 p = src.getPosition();
        int cx = (int) Math.floor(p.x) >> 4, cz = (int) Math.floor(p.z) >> 4;
        EcoTable t = DesignData.ecosystem();
        double h = ledger.health(now);
        int[] c = EcoSpawns.count(level, p.x, p.z, t.innerCountRadius);
        String[] lines = {
                String.format(Locale.ROOT, "多様性 %.1f%%・予算の倍率 %.2f・減った升 %d・これまでに数えた殺し 獲物 %d・狩る側 %d",
                        h * 100.0D, Ecosystem.incomeFactor(level), ledger.damagedCells(), ledger.kills(Ecosystem.PREY), ledger.kills(Ecosystem.HUNTER)),
                String.format(Locale.ROOT, "今いる升(%d×%d チャンク): 獲物 %.1f / %d・狩る側 %.1f / %d・内側 %s",
                        t.cellChunks(), t.cellChunks(), ledger.heads(Ecosystem.PREY, cx, cz, now), t.fullPrey, ledger.heads(Ecosystem.HUNTER, cx, cz, now), t.fullHunter,
                        EcoSpawns.inside(com.yayuy.overgrownvegetation.phase.PhaseData.get(level), cx, cz) ? "はい" : "いいえ"),
                String.format(Locale.ROOT, "内側の守り: G2 %d / %d・G3 %d / %d(しきい値 %.0f%%)・仮の種 獲物 %d / %d・狩る側 %d / %d(%s)",
                        c[2], EcoSpawns.innerCap(level, 2), c[3], EcoSpawns.innerCap(level, 3), t.innerThreshold * 100.0D,
                        c[Ecosystem.PREY], EcoSpawns.stubCap(level, Ecosystem.PREY), c[Ecosystem.HUNTER], EcoSpawns.stubCap(level, Ecosystem.HUNTER), EcoSpawns.stubAllowed(level) ? "開発用のスイッチあり" : "湧かない"),
                misses()
        };
        for (String s : lines) src.sendSuccess(() -> Component.literal(s), false);
        return 1;
    }

    private static String misses() {
        StringBuilder sb = new StringBuilder("試しの結果:");
        for (EcoSpawns.Miss m : EcoSpawns.Miss.values()) {
            long n = EcoSpawns.stats[m.ordinal()];
            if (n > 0) sb.append(' ').append(m.label).append(' ').append(n);
        }
        return sb.toString();
    }

    private static int kill(CommandSourceStack src, int kind, int count) {
        ServerLevel level = src.getServer().overworld();
        Vec3 p = src.getPosition();
        EcoLedger ledger = EcoLedger.get(level);
        long now = EcoLedger.now(level);
        for (int i = 0; i < count; i++) ledger.kill(kind, (int) Math.floor(p.x) >> 4, (int) Math.floor(p.z) >> 4, now);
        double h = ledger.health(now);
        src.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "%s を %d 体倒したことにした。多様性 %.1f%%", kind == Ecosystem.HUNTER ? "狩る側" : "獲物", count, h * 100.0D)), false);
        return count;
    }

    private static int tick(CommandSourceStack src, int times) {
        ServerLevel level = src.getServer().overworld();
        Vec3 p = src.getPosition();
        long before = EcoSpawns.spawnedInner + EcoSpawns.spawnedStub;
        for (int i = 0; i < times; i++) {
            EcoSpawns.stats[EcoSpawns.tryInner(level, p.x, p.z, level.getRandom()).ordinal()]++;
            EcoSpawns.stats[EcoSpawns.tryStub(level, p.x, p.z, level.getRandom()).ordinal()]++;
        }
        long n = EcoSpawns.spawnedInner + EcoSpawns.spawnedStub - before;
        src.sendSuccess(() -> Component.literal(times + " 回試して " + n + " 体湧いた"), false);
        return (int) n;
    }

    private static int reset(CommandSourceStack src) {
        EcoLedger.get(src.getServer()).reset();
        src.sendSuccess(() -> Component.literal("全部の升を満員に戻した"), false);
        return 1;
    }
}
