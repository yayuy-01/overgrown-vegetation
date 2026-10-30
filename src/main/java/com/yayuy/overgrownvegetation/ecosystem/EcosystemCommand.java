package com.yayuy.overgrownvegetation.ecosystem;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.EcosystemTable;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import com.yayuy.overgrownvegetation.td.TdData;
import com.yayuy.overgrownvegetation.td.TdTable;
import com.yayuy.overgrownvegetation.td.TdTicker;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * 生態系の台帳のコマンド(OP)。{@code /ov ecosystem …} は 2026-10-10 に消え、残りは {@code /ov biomass} だけ。
 * {@code /ov biomass}(2026-09-26)… バイオマスの量だけ(森のバイオマス・収入・侵攻が起きる量まで・侵攻中の残り)。{@link #biomass}
 *
 * <p>出力は開発用なので {@code Component.literal}(翻訳キーを作らない)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class EcosystemCommand {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ov")
                .then(Commands.literal("biomass")
                        .requires(source -> source.hasPermission(2))
                        .executes(EcosystemCommand::biomass)
                        // 数字を付けるとその量を足す(2026-10-10 ユーザー指定。負の数で減らす、0 未満にはならない)。
                        .then(Commands.argument("amount", DoubleArgumentType.doubleArg())
                                .executes(EcosystemCommand::addBiomass))));
    }

    /**
     * {@code /ov biomass} — バイオマスの量だけを見る(ユーザー指定 2026-09-26「バイオマスの量を確認できるコマンド」)。
     * 森のバイオマスと収入、侵攻(TD)が始まる量までの残り、侵攻中ならその侵攻の残りのバイオマス(ボスバーと同じ数)と内訳。
     * 権限は ほかの {@code /ov} と同じ OP(仮決め 2026-09-26。OP でなくてもフェーズ計測器で森のバイオマスは見られる)。
     */
    private static int biomass(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        ServerLevel overworld = server.overworld();
        TdTable td = DesignData.td();
        double biomass = EcosystemData.get(server).biomass();
        double income = Ecosystem.incomePerSecond(overworld);

        send(source, "§a[バイオマス]§r 森のバイオマス " + TdTicker.biomassText(biomass)
                + "(収入 +" + String.format("%,.1f", income) + " /秒)");
        com.yayuy.overgrownvegetation.phase.PhaseData pd = com.yayuy.overgrownvegetation.phase.PhaseData.get(overworld);
        int living = pd.livingGiantTreeCount();
        send(source, "  貯蓄の上限 " + TdTicker.biomassText(DesignData.ecosystem().biomassCap(living))
                + "(生きている巨大樹 " + living + " 本・ハイヴノードを倒された木 " + pd.deadGiantTreeCount() + " 本)");
        TdData.Event live = TdData.get(server).live();
        if (live == null) {
            double left = td.startBiomass() - biomass;
            send(source, "  侵攻(TD)が起きる量 " + TdTicker.biomassText(td.startBiomass()) + " 以上: "
                    + (left <= 0.0D ? "§c届いている§r(ほかの条件は /ov td)"
                    : "あと " + TdTicker.biomassText(Math.ceil(left))
                    + (income > 0.0D ? "(今の収入で約 " + minutes(left / income / 60.0D) + ")" : "(いま収入が無い)")));
            return 1;
        }
        if (live.state() == TdData.State.WARNING) {
            send(source, "  §c侵攻の予告中§r: 始まった瞬間の森のバイオマスが全部攻めてくる(いまなら "
                    + TdTicker.biomassText(biomass) + ")");
            return 1;
        }
        ServerLevel level = server.getLevel(live.dimension());
        ServerLevel at = level != null ? level : overworld;
        double coming = TdTicker.comingBiomass(at, live);
        send(source, "  §c侵攻中§r: 残り " + TdTicker.biomassText(TdTicker.remainingBiomass(at, live))
                + " / " + TdTicker.biomassText(live.initialTotal()) + " バイオマス(これから出てくる "
                + TdTicker.biomassText(coming) + "・いま戦っている攻め手 " + live.alive() + " 体 "
                + TdTicker.biomassText(live.aliveCost()) + ")");
        return 1;
    }

    /** 分を「約 N 分 / N 時間 M 分」に。 */
    private static String minutes(double minutes) {
        long m = (long) Math.ceil(minutes);
        return m < 60L ? m + " 分" : (m / 60L) + " 時間 " + (m % 60L) + " 分";
    }

    /** {@code /ov biomass <量>} — 世界のバイオマスに足す(負の数で減らす。0 未満にはならない)。 */
    private static int addBiomass(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        double amount = DoubleArgumentType.getDouble(ctx, "amount");
        EcosystemData data = EcosystemData.get(source.getServer());
        double after = Math.max(0.0D, data.biomass() + amount);
        data.setBiomass(after);
        send(source, "§a[生態系]§r バイオマスに " + String.format("%,+.0f", amount) + " → " + String.format("%,.0f", after));
        return 1;
    }

    private static void send(CommandSourceStack source, String line) {
        source.sendSuccess(() -> Component.literal(line), false);
    }

    private static String pct(double ratio) {
        return String.format("%.1f%%", ratio * 100.0D);
    }

    private static String percents(double[] ratios) {
        StringBuilder out = new StringBuilder();
        for (double r : ratios) {
            if (out.length() > 0) {
                out.append(" / ");
            }
            out.append(String.format("%.0f%%", r * 100.0D));
        }
        return out.toString();
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    private EcosystemCommand() {
    }
}
