package com.yayuy.overgrownvegetation.survival.decon;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.survival.room.RoomAir;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterCommandsEvent;

/**
 * 開発用(2026-11-14 サバイバルの背骨 段 3b・3c): {@code /ov perf spore dirt} = 着ている物・手の物の汚れの値と段・持っている汚れの点・
 * 吸う暴露量・立っている部屋の汚れ。{@code /ov perf spore dirt set <0〜full>} = 着ている物と手の物の汚れを決める。
 * {@code /ov perf spore dirt clearrooms} = この次元の部屋の汚れを全部消す。
 * ゲームの中では数字を見せない(決定 33)ので、値はこの命令でだけ見る。/ov の下の一覧はテストで決めてあるので perf spore の下に足す。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE)
public final class DeconCommand {

    private DeconCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ov")
                .then(Commands.literal("perf")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("spore")
                                .then(Commands.literal("dirt")
                                        .executes(DeconCommand::show)
                                        .then(Commands.literal("clearrooms").executes(ctx -> {
                                            if (!(ctx.getSource().getLevel() instanceof ServerLevel level)) return 0;
                                            com.yayuy.overgrownvegetation.survival.room.RoomDirt.get(level).clearAll();
                                            ctx.getSource().sendSuccess(() -> Component.literal("この次元の部屋の汚れを消した"), false);
                                            return 1;
                                        }))
                                        .then(Commands.literal("set").then(Commands.argument("value", IntegerArgumentType.integer(0, 1_000_000))
                                                .executes(ctx -> {
                                                    if (!(ctx.getSource().getEntity() instanceof Player player)) {
                                                        ctx.getSource().sendFailure(Component.literal("プレイヤーとして実行して"));
                                                        return 0;
                                                    }
                                                    int v = IntegerArgumentType.getInteger(ctx, "value");
                                                    for (EquipmentSlot slot : GearDirt.SLOTS) {
                                                        ItemStack st = player.getItemBySlot(slot);
                                                        if (GearDirt.canGetDirty(st)) GearDirt.setDirt(st, v);
                                                    }
                                                    GearDirt.applyModifiers(player);
                                                    return show(ctx);
                                                })))))));
    }

    private static int show(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getEntity() instanceof Player player) || !(player.level() instanceof ServerLevel level)) {
            ctx.getSource().sendFailure(Component.literal("プレイヤーとして実行して"));
            return 0;
        }
        StringBuilder b = new StringBuilder("汚れ:");
        for (EquipmentSlot slot : GearDirt.SLOTS) {
            ItemStack st = player.getItemBySlot(slot);
            if (st.isEmpty()) continue;
            b.append(' ').append(slot.getName()).append('=').append(GearDirt.dirt(st)).append("(段").append(GearDirt.stage(st)).append(')');
        }
        String line1 = b.toString();
        ctx.getSource().sendSuccess(() -> Component.literal(line1), false);
        String line2 = String.format("持っている汚れ %.0f 点・汚れた装備から吸う %.3f %%秒/秒・掘る速さ ×%.2f・%s",
                GearDirt.carriedPoints(player), GearDirt.seepPerSecond(player), GearDirt.breakSpeedFactor(player),
                RoomAir.describe(level, player.blockPosition()));
        ctx.getSource().sendSuccess(() -> Component.literal(line2), false);
        return 1;
    }
}
