package com.yayuy.overgrownvegetation.siege;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /siege} — <b>フェーズ7突入の仮トリガー</b>。フェーズ6のときだけ通り、フェーズを7へ上げて
 * 攻城戦を始める(包囲の中心は打った本人の位置、昇格レイドは出さない)。OP 権限は不要。
 *
 * {@code /ov siege start} — 実行者の現在位置を中心に攻城戦を開始する。
 * {@code /ov siege stop} — 進行中の攻城戦を終了する。
 * {@code /ov siege status} — 中心・半径・現在位置・距離・体力を表示する。
 * {@code /ov siege damage <量>} — ボスに直接ダメージを与える(動作確認用)。
 * {@code /ov siege mortar} — 実行地点へ胞子爆弾を 1 斉射させる。
 * {@code /ov siege barrage} — 実行地点の方角へ触手の小ビームの乱射を始めさせる。
 * {@code /ov siege shoot} — 視線の向きへ砲弾を撃つ(砲台なしの動作確認用)。
 * {@code /ov siege snipe} — ボスへ偏差射撃で砲弾を撃つ(命中判定の確認用)。
 *
 * <p>{@code RaidCommand} と同じく最上位の {@code overgrownvegetation} リテラルを別途 register するが、
 * Brigadier は同名リテラルの子ノードをマージするので共存する。
 * {@code overgrownvegetation siege ...} は OP 専用({@code hasPermission(2)})で、
 * 仮トリガーの {@code /siege} だけが権限なしで通る。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class SiegeCommand {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        // 仮のフェーズ7突入トリガー(2026-09-17)。フェーズ7の昇格条件が決まるまでの暫定で、
        // OP 権限は要求しない(フェーズ6まで進めた人が自分の意思で最終決戦を始める想定)。
        event.getDispatcher().register(Commands.literal("siege").executes(SiegeCommand::enterPhase7));

        event.getDispatcher().register(Commands.literal("ov")
                .then(Commands.literal("siege")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("start").executes(SiegeCommand::start))
                        .then(Commands.literal("stop").executes(SiegeCommand::stop))
                        .then(Commands.literal("status").executes(SiegeCommand::status))
                        .then(Commands.literal("damage")
                                .then(Commands.argument("amount", FloatArgumentType.floatArg(0.0F))
                                        .executes(SiegeCommand::damage)))
                        .then(Commands.literal("beam").executes(SiegeCommand::beam))
                        .then(Commands.literal("tentacle").executes(SiegeCommand::tentacle))
                        .then(Commands.literal("mortar").executes(SiegeCommand::mortar))
                        .then(Commands.literal("barrage").executes(SiegeCommand::barrage))
                        .then(Commands.literal("shoot").executes(SiegeCommand::shoot))
                        .then(Commands.literal("snipe").executes(SiegeCommand::snipe))));
    }

    /**
     * {@code /siege} — <b>フェーズ7突入の仮トリガー</b>(2026-09-17)。フェーズ6に到達している間だけ通り、
     * フェーズを7へ上げて攻城戦(ドルガ=アーケロウス・ティタニカ)を始める。
     *
     * <p>包囲の中心は<b>打った本人の位置</b>({@link SiegeData#setNextCenterHint})。ボスはそこから
     * {@code siegeRadius}(既定 1025)ブロック離れた位置を回りながら、少しずつ中心へ寄ってくる。
     *
     * <p>このコマンドで上げたぶんの<b>昇格レイドは出さない</b>
     * ({@code RaidData#skipPromotionRaid}。ボス戦と苗床レイドを同時に始めないため。周期レイドは従来どおり)。
     *
     * <p>フェーズ7の本来の昇格条件が決まったら、このコマンドは消すか OP 専用のデバッグ用に落とす。
     * 本番の経路(ポイントによる昇格)でも {@code PhaseData#advanceToPhase} →
     * {@link SiegeData#onPhaseAdvanced} で同じように攻城戦が始まる。
     */
    private static int enterPhase7(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel overworld = source.getServer().overworld();
        var phaseData = com.yayuy.overgrownvegetation.phase.PhaseData.get(overworld);
        int siegePhase = com.yayuy.overgrownvegetation.data.DesignData.phases().siegeMinPhase();
        int required = siegePhase - 1;

        if (phaseData.getPhase() != required) {
            source.sendFailure(Component.translatable(
                    "commands.overgrown_vegetation.siege.phase_required", required, phaseData.getPhase()));
            return 0;
        }
        if (!com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig.COMMON.siegeEnabled.get()) {
            // toml で攻城戦を切ってある。フェーズだけ上げても即座に止まるので、上げずに知らせる。
            source.sendFailure(Component.translatable("commands.overgrown_vegetation.siege.disabled"));
            return 0;
        }
        if (SiegeData.get(overworld).isActive()) {
            source.sendFailure(Component.translatable("commands.overgrown_vegetation.siege.already_active"));
            return 0;
        }

        // 包囲される場所 = 打った本人の位置(コンソールから打たれたらコマンドの実行座標)。
        BlockPos center = source.getEntity() != null
                ? source.getEntity().blockPosition()
                : BlockPos.containing(source.getPosition());
        phaseData.setPhase(overworld, siegePhase);
        // このフェーズぶんの昇格レイドは出さない(ボス戦と苗床レイドを同時に始めない)。
        // 周期レイドは従来どおり。
        com.yayuy.overgrownvegetation.raid.RaidData.skipPromotionRaid(overworld, siegePhase);
        if (!SiegeData.startForPhase(overworld, siegePhase, center)) {
            // フェーズは上がっているので、あとは tick の再挑戦に任せる(まず起きないはず)。
            source.sendFailure(Component.translatable("commands.overgrown_vegetation.siege.start_failed", siegePhase));
            return 0;
        }

        source.sendSuccess(() -> Component.translatable(
                "commands.overgrown_vegetation.siege.phase_entered", siegePhase,
                center.getX(), center.getY(), center.getZ()), true);
        return 1;
    }

    /**
     * {@code siege shoot} — 砲台なしの動作確認用。実行者の目の位置から視線の向きへ、装薬最大の初速で
     * 砲弾({@link ShellTracker})を 1 発撃つ。
     */
    private static int shoot(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (!(source.getEntity() instanceof net.minecraft.world.entity.LivingEntity shooter)) {
            source.sendFailure(Component.translatable("commands.overgrown_vegetation.siege.shoot_failed"));
            return 0;
        }
        ServerLevel level = source.getLevel();
        double speed = CannonBallistics.speedForCharge(Integer.MAX_VALUE);
        Vec3 origin = shooter.getEyePosition().add(shooter.getLookAngle().scale(1.5D));
        int id = ShellTracker.get(level).fire(level, origin, shooter.getLookAngle().scale(speed));
        source.sendSuccess(() -> Component.translatable("commands.overgrown_vegetation.siege.shoot", id), true);
        return 1;
    }

    /**
     * {@code siege snipe} — 砲台なしの命中判定の確認用。実行者の目の位置から攻城戦ボスへ偏差射撃の解を
     * 求めて砲弾を 1 発撃つ(誤差なし)。
     */
    private static int snipe(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel overworld = source.getServer().overworld();
        SiegeData data = SiegeData.get(overworld);
        if (!data.isActive() || source.getLevel() != overworld) {
            source.sendFailure(Component.translatable("commands.overgrown_vegetation.siege.not_active"));
            return 0;
        }
        var cfg = com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig.COMMON;
        Vec3 origin = source.getEntity() != null ? source.getEntity().getEyePosition() : source.getPosition();
        double speed = CannonBallistics.speedForCharge(Integer.MAX_VALUE);
        var solution = CannonBallistics.lead(origin, data.motion(), data.elapsed(overworld), cfg.siegeScale.get(),
                speed, cfg.cannonGravity.get(), cfg.cannonAutoAimHighArc.get());
        if (solution.isEmpty()) {
            source.sendFailure(Component.translatable("commands.overgrown_vegetation.siege.snipe_failed"));
            return 0;
        }
        CannonBallistics.Solution s = solution.get();
        ShellTracker.get(overworld).fire(overworld, origin, CannonBallistics.direction(s.elevationDeg(), s.azimuthDeg()).scale(speed));
        source.sendSuccess(() -> Component.translatable("commands.overgrown_vegetation.siege.snipe",
                String.format(java.util.Locale.ROOT, "%.1f", s.elevationDeg()),
                String.format(java.util.Locale.ROOT, "%.1f", s.azimuthDeg()),
                String.format(java.util.Locale.ROOT, "%.1f", s.flightTicks() / 20.0D)), true);
        return 1;
    }

    /** {@code siege beam} — 射程の条件を無視して、実行地点へ花のビーム(遠距離砲撃)を撃ち始める。 */
    private static int beam(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel overworld = source.getServer().overworld();
        if (SiegeData.get(overworld).forceFlowerBeam(overworld, source.getPosition())) {
            source.sendSuccess(() -> Component.translatable("commands.overgrown_vegetation.siege.beam"), true);
            return 1;
        }
        source.sendFailure(Component.translatable("commands.overgrown_vegetation.siege.beam_failed"));
        return 0;
    }

    /** {@code siege tentacle} — 実エンティティが居れば、実行地点へ触手ビームを撃ち始める。 */
    private static int tentacle(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel overworld = source.getServer().overworld();
        if (SiegeData.get(overworld).forceTentacleBeam(overworld, source.getPosition().add(0.0D, 1.0D, 0.0D))) {
            source.sendSuccess(() -> Component.translatable("commands.overgrown_vegetation.siege.tentacle"), true);
            return 1;
        }
        source.sendFailure(Component.translatable("commands.overgrown_vegetation.siege.tentacle_failed"));
        return 0;
    }

    /** {@code siege mortar} — 間隔や頭のビームの条件を無視して、実行地点へ胞子爆弾を 1 斉射させる。 */
    private static int mortar(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel overworld = source.getServer().overworld();
        if (SiegeData.get(overworld).forceMortar(overworld, source.getPosition())) {
            source.sendSuccess(() -> Component.translatable("commands.overgrown_vegetation.siege.mortar"), true);
            return 1;
        }
        source.sendFailure(Component.translatable("commands.overgrown_vegetation.siege.not_active"));
        return 0;
    }

    /** {@code siege barrage} — 実行地点の方角へ、触手の小ビームの乱射を始めさせる。 */
    private static int barrage(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel overworld = source.getServer().overworld();
        if (SiegeData.get(overworld).forceBarrage(overworld, source.getPosition().add(0.0D, 1.0D, 0.0D))) {
            source.sendSuccess(() -> Component.translatable("commands.overgrown_vegetation.siege.barrage"), true);
            return 1;
        }
        source.sendFailure(Component.translatable("commands.overgrown_vegetation.siege.barrage_failed"));
        return 0;
    }

    private static int start(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel overworld = source.getServer().overworld();
        BlockPos center = BlockPos.containing(source.getPosition());
        if (SiegeData.get(overworld).start(overworld, center)) {
            source.sendSuccess(() -> Component.translatable("commands.overgrown_vegetation.siege.started",
                    center.getX(), center.getY(), center.getZ()), true);
            return 1;
        }
        source.sendFailure(Component.translatable("commands.overgrown_vegetation.siege.already_active"));
        return 0;
    }

    private static int stop(CommandContext<CommandSourceStack> ctx) {
        ServerLevel overworld = ctx.getSource().getServer().overworld();
        SiegeData data = SiegeData.get(overworld);
        if (!data.isActive()) {
            ctx.getSource().sendFailure(Component.translatable("commands.overgrown_vegetation.siege.not_active"));
            return 0;
        }
        data.stop(overworld, false);
        ctx.getSource().sendSuccess(() -> Component.translatable("commands.overgrown_vegetation.siege.stopped"), true);
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel overworld = source.getServer().overworld();
        SiegeData data = SiegeData.get(overworld);
        if (!data.isActive()) {
            source.sendFailure(Component.translatable("commands.overgrown_vegetation.siege.not_active"));
            return 0;
        }
        SiegeBossMotion motion = data.motion();
        // 半径も 1 周の時間も、寄ってくるぶん時間とともに変わる(§2 の螺旋)。いまの値を出す。
        double elapsed = data.elapsed(overworld);
        Vec3 pos = data.position(overworld);
        double distance = pos.distanceTo(source.getPosition());
        source.sendSuccess(() -> Component.translatable("commands.overgrown_vegetation.siege.status",
                (int) motion.centerX(), (int) motion.centerY(), (int) motion.centerZ(),
                (int) motion.radiusAt(elapsed),
                (int) pos.x, (int) pos.y, (int) pos.z,
                (int) distance,
                (int) data.getHealth(), (int) data.getMaxHealth(),
                (int) (motion.lapTicksAt(elapsed) / 20.0D)), false);
        return 1;
    }

    private static int damage(CommandContext<CommandSourceStack> ctx) {
        ServerLevel overworld = ctx.getSource().getServer().overworld();
        SiegeData data = SiegeData.get(overworld);
        if (!data.isActive()) {
            ctx.getSource().sendFailure(Component.translatable("commands.overgrown_vegetation.siege.not_active"));
            return 0;
        }
        float amount = FloatArgumentType.getFloat(ctx, "amount");
        data.hurt(overworld, amount);
        data.sync(overworld);
        ctx.getSource().sendSuccess(() -> Component.translatable("commands.overgrown_vegetation.siege.damaged",
                (int) amount, (int) data.getHealth()), true);
        return 1;
    }

    private SiegeCommand() {
    }
}
