package com.yayuy.overgrownvegetation.survival.expedition;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterCommandsEvent;

/**
 * 開発用 {@code /ov perf spore mushroom}(2026-11-16 段 4): 胞子喰い茸がまわりで生えない理由を数える。
 * まわりの列を全部見て、生える決まりのどこで落ちたかを集計し、生えてよい列の数を返す(置かない)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE)
public final class ExpeditionCommand {

    private ExpeditionCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ov")
                .then(Commands.literal("perf")
                        .requires(s -> s.hasPermission(2))
                        .then(Commands.literal("spore")
                                .then(Commands.literal("mushroom").executes(ctx -> report(ctx.getSource()))))));
    }

    private static int report(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        BlockPos c = BlockPos.containing(source.getPosition());
        String text = SporeEaterSpread.diagnose(level, c, 32);
        source.sendSuccess(() -> Component.literal(text), false);
        return 1;
    }
}
