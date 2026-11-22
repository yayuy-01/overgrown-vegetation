package com.yayuy.overgrownvegetation.voice;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.synchronization.SuggestionProviders;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterCommandsEvent;

/**
 * {@code /ov fx voice <sound_event> <版 0〜3> [mid|small] [強さ]} — 任意の SoundEvent を汚して、自分の 4 ブロック前で鳴らす(2026-11-14 試作。OP 専用)。
 * 版 0 = 加工なし(聞き比べ用)・1 = かすれ・2 = つぶれ・3 = つぶれ + 途切れ。small は小型の Mob の帯(鶏など)。強さ 1 = 試聴用と同じ。
 * 高さは試聴用と同じく、版 1〜3 は 0.92 倍(低め)、版 0 は 1 倍。周りのプレイヤーにも聞こえる({@link CrushedVoice})。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE)
public final class VoiceCommand {

    private VoiceCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ov")
                .then(Commands.literal("fx")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("voice")
                                .then(Commands.argument("sound", ResourceLocationArgument.id())
                                        .suggests(SuggestionProviders.AVAILABLE_SOUNDS)
                                        .then(Commands.argument("mode", IntegerArgumentType.integer(0, 3))
                                                .executes(ctx -> run(ctx, false, 1.0F))
                                                .then(Commands.literal("mid")
                                                        .executes(ctx -> run(ctx, false, 1.0F))
                                                        .then(Commands.argument("strength", FloatArgumentType.floatArg(0.0F, 2.0F))
                                                                .executes(ctx -> run(ctx, false, FloatArgumentType.getFloat(ctx, "strength")))))
                                                .then(Commands.literal("small")
                                                        .executes(ctx -> run(ctx, true, 1.0F))
                                                        .then(Commands.argument("strength", FloatArgumentType.floatArg(0.0F, 2.0F))
                                                                .executes(ctx -> run(ctx, true, FloatArgumentType.getFloat(ctx, "strength"))))))))));
    }

    private static int run(CommandContext<CommandSourceStack> ctx, boolean small, float strength) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("プレイヤーとして実行してください(自分の 4 ブロック前で鳴らす)"));
            return 0;
        }
        ResourceLocation sound = ResourceLocationArgument.getId(ctx, "sound");
        int mode = IntegerArgumentType.getInteger(ctx, "mode");
        Vec3 look = player.getLookAngle();
        Vec3 at = player.getEyePosition().add(look.x * 4, look.y * 4, look.z * 4);
        float pitch = mode == CrushedVoice.DRY ? 1.0F : 0.92F;
        CrushedVoice.play(player.serverLevel(), null, at.x, at.y, at.z, sound, SoundSource.HOSTILE, 1.0F, pitch, mode, small, strength);
        String[] names = {"加工なし", "かすれ", "つぶれ", "つぶれ + 途切れ"};
        source.sendSuccess(() -> Component.literal("声: " + sound + "・版 " + mode + "(" + names[mode] + ")・" + (small ? "小型" : "中型")
                + "の帯・強さ " + strength), false);
        return 1;
    }
}
