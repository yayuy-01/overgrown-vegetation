package com.yayuy.overgrownvegetation.ecosystem;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.Locale;

/**
 * 確かめ用の命令(2026-11-16):
 * <ul>
 *   <li>{@code /ov perf eco} — 多様性・倍率・今いる升の頭数・減った升の数・内側か。</li>
 *   <li>{@code /ov perf eco kill <2|3> [数]} — 今いる升で環境生物を倒したことにする(数える殺しと同じ)。</li>
 *   <li>(2026-11-23 片付け E: 内側の守り・仮の種の湧きと {@code tick} は消した。森の中の湧きは前線と部隊の守りの部隊が受け持つ。)</li>
 *   <li>{@code /ov perf eco reset} — 全部の升を満員に戻す。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
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
        String[] lines = {
                String.format(Locale.ROOT, "多様性 %.1f%%・予算の倍率 %.2f・減った升 %d・これまでに数えた殺し 獲物 %d・狩る側 %d",
                        h * 100.0D, Ecosystem.incomeFactor(level), ledger.damagedCells(), ledger.kills(Ecosystem.PREY), ledger.kills(Ecosystem.HUNTER)),
                String.format(Locale.ROOT, "今いる升(%d×%d チャンク): 獲物 %.1f / %d・狩る側 %.1f / %d・内側 %s",
                        t.cellChunks(), t.cellChunks(), ledger.heads(Ecosystem.PREY, cx, cz, now), t.fullPrey, ledger.heads(Ecosystem.HUNTER, cx, cz, now), t.fullHunter,
                        com.yayuy.overgrownvegetation.ecosystem.life.EcoDens.insideChunk(com.yayuy.overgrownvegetation.phase.PhaseData.get(level), cx, cz) ? "はい" : "いいえ"),
        };
        for (String s : lines) src.sendSuccess(() -> Component.literal(s), false);
        return 1;
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

    private static int reset(CommandSourceStack src) {
        EcoLedger.get(src.getServer()).reset();
        src.sendSuccess(() -> Component.literal("全部の升を満員に戻した"), false);
        return 1;
    }
}
