package com.yayuy.overgrownvegetation.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /ov} の根。下のコマンドは各段の仕組みが {@link #root()} の下へ足す(系統を増やしすぎない。古い作りは 10 系統に絞った)。
 * 引数なしの {@code /ov} は MOD の版を返す。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID)
public final class OvCommand {

    private OvCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(root());
    }

    static LiteralArgumentBuilder<CommandSourceStack> root() {
        return Commands.literal("ov")
                .requires(source -> source.hasPermission(2))
                .executes(context -> {
                    String version = ModList.get().getModContainerById(OvergrownVegetation.MODID)
                            .map(container -> container.getModInfo().getVersion().toString())
                            .orElse("?");
                    context.getSource().sendSuccess(() -> Component.translatable("commands.overgrown_vegetation.ov.version", version), false);
                    return 1;
                });
    }
}
