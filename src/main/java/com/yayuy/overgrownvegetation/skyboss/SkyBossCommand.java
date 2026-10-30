package com.yayuy.overgrownvegetation.skyboss;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * 空の巨大生物を確かめるコマンド(OP 専用。2026-10-18 ユーザー了承「/ov sky を足す」)。
 * <ul>
 *   <li>{@code /ov sky summon} — 実行した所の風上の空に出す(居れば置き直す)。風に流されて上を通る</li>
 *   <li>{@code /ov sky remove} — 消す</li>
 *   <li>{@code /ov sky tp} — 体の真ん中の横 {@link SkyBossTuning#TP_SIDE} ブロックへ移ってボスの方を向く(サバイバルなら落下を遅くする 60 秒)</li>
 *   <li>{@code /ov sky health <割合>} — 体力を上限のその割合(0〜1)にする。段階の切り替わり・最期を確かめる用(2026-10-18 夜 ユーザー了承)。0 なら倒れる</li>
 *   <li>{@code /ov sky charge <割合>} — 放電の溜めをその割合(0〜1)にする。1 なら、戦いの中で段階 2 から自分で放電する(2026-10-19 ユーザー了承)</li>
 *   <li>{@code /ov sky discharge} — 溜め・戦い・段階・隙を見ずに今すぐ放電を始める(腹が上なら背を上へ転がる 26 秒の後に 30 秒。2026-10-19 ユーザー了承)。
 *       転がっている途中・もう放電中・避雷針が壊れているときは始めない</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class SkyBossCommand {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ov")
                .then(Commands.literal("sky")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("summon").executes(SkyBossCommand::summon))
                        .then(Commands.literal("remove").executes(SkyBossCommand::remove))
                        .then(Commands.literal("tp").executes(SkyBossCommand::tp))
                        .then(Commands.literal("health")
                                .then(Commands.argument("fraction", FloatArgumentType.floatArg(0.0F, 1.0F)).executes(SkyBossCommand::health)))
                        .then(Commands.literal("charge")
                                .then(Commands.argument("fraction", FloatArgumentType.floatArg(0.0F, 1.0F)).executes(SkyBossCommand::charge)))
                        .then(Commands.literal("discharge").executes(SkyBossCommand::discharge))));
    }

    /** 放電の溜めを割合で(雷の本数で知らせる)。 */
    private static int charge(CommandContext<CommandSourceStack> ctx) {
        ServerLevel overworld = ctx.getSource().getServer().overworld();
        SkyBossData data = SkyBossData.get(overworld);
        if (!data.isActive()) {
            ctx.getSource().sendFailure(Component.translatable("commands.overgrown_vegetation.sky.none"));
            return 0;
        }
        float fraction = FloatArgumentType.getFloat(ctx, "fraction");
        data.debugCharge(overworld, fraction);
        int strikes = Math.round(fraction * SkyBossTuning.CHARGE_STRIKES);
        ctx.getSource().sendSuccess(() -> Component.translatable("commands.overgrown_vegetation.sky.charge", strikes, SkyBossTuning.CHARGE_STRIKES), true);
        return 1;
    }

    /** 今すぐ放電を始める。 */
    private static int discharge(CommandContext<CommandSourceStack> ctx) {
        ServerLevel overworld = ctx.getSource().getServer().overworld();
        SkyBossData data = SkyBossData.get(overworld);
        if (!data.isActive()) {
            ctx.getSource().sendFailure(Component.translatable("commands.overgrown_vegetation.sky.none"));
            return 0;
        }
        if (!data.parts().rodSolid()) {
            ctx.getSource().sendFailure(Component.translatable("commands.overgrown_vegetation.sky.discharge_no_rod"));
            return 0;
        }
        if (!data.debugDischarge(overworld)) {
            ctx.getSource().sendFailure(Component.translatable("commands.overgrown_vegetation.sky.discharge_busy"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.translatable("commands.overgrown_vegetation.sky.discharge"), true);
        return 1;
    }

    private static int summon(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel overworld = source.getServer().overworld();
        if (com.yayuy.overgrownvegetation.difficulty.Peaceful.is(overworld)) {
            source.sendFailure(Component.translatable("commands.overgrown_vegetation.peaceful_blocked"));
            return 0;
        }
        SkyBossData.get(overworld).summon(overworld, source.getPosition());
        source.sendSuccess(() -> Component.translatable("commands.overgrown_vegetation.sky.summoned",
                Math.round(SkyBossTuning.SUMMON_UPWIND), Math.round(SkyBossTuning.CRUISE_Y)), true);
        return 1;
    }

    private static int remove(CommandContext<CommandSourceStack> ctx) {
        ServerLevel overworld = ctx.getSource().getServer().overworld();
        SkyBossData data = SkyBossData.get(overworld);
        if (!data.isActive()) {
            ctx.getSource().sendFailure(Component.translatable("commands.overgrown_vegetation.sky.none"));
            return 0;
        }
        data.remove(overworld);
        ctx.getSource().sendSuccess(() -> Component.translatable("commands.overgrown_vegetation.sky.removed"), true);
        return 1;
    }

    private static int health(CommandContext<CommandSourceStack> ctx) {
        ServerLevel overworld = ctx.getSource().getServer().overworld();
        SkyBossData data = SkyBossData.get(overworld);
        if (!data.isActive()) {
            ctx.getSource().sendFailure(Component.translatable("commands.overgrown_vegetation.sky.none"));
            return 0;
        }
        float fraction = FloatArgumentType.getFloat(ctx, "fraction");
        data.setHealthFraction(overworld, fraction);
        int hp = Math.round(data.health());
        int stage = data.stage();
        boolean alive = data.isActive();
        ctx.getSource().sendSuccess(() -> alive
                ? Component.translatable("commands.overgrown_vegetation.sky.health", hp, Math.round(SkyBossTuning.MAX_HEALTH), stage)
                : Component.translatable("commands.overgrown_vegetation.sky.health_slain"), true);
        return 1;
    }

    private static int tp(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel overworld = source.getServer().overworld();
        SkyBossData data = SkyBossData.get(overworld);
        if (!data.isActive()) {
            source.sendFailure(Component.translatable("commands.overgrown_vegetation.sky.none"));
            return 0;
        }
        if (source.getEntity() == null) {
            source.sendFailure(Component.translatable("commands.overgrown_vegetation.sky.need_entity"));
            return 0;
        }
        Vec3 c = data.bodyCenter(overworld.getGameTime());
        // 向きの横(ヨー + 90°)へ
        double yr = Math.toRadians(data.flight().yaw);
        double x = c.x + Math.cos(yr) * SkyBossTuning.TP_SIDE;
        double z = c.z + Math.sin(yr) * SkyBossTuning.TP_SIDE;
        double y = c.y;
        float yaw = (float) Math.toDegrees(Math.atan2(-(c.x - x), c.z - z));
        source.getEntity().teleportTo(overworld, x, y, z, java.util.Set.of(), yaw, 0.0F);
        boolean slow = false;
        if (source.getEntity() instanceof ServerPlayer player && !player.isCreative() && !player.isSpectator()) {
            player.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 20 * 60, 0));
            slow = true;
        }
        boolean slowFall = slow;
        source.sendSuccess(() -> {
            MutableComponent m = Component.translatable("commands.overgrown_vegetation.sky.teleported",
                    Math.round(SkyBossTuning.TP_SIDE), Math.round(y), Mth.floor(x), Mth.floor(z));
            if (slowFall) {
                m.append(Component.translatable("commands.overgrown_vegetation.sky.slow_fall"));
            }
            return m;
        }, false);
        return 1;
    }

    private SkyBossCommand() {
    }
}
