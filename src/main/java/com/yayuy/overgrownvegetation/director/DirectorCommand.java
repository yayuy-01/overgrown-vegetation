package com.yayuy.overgrownvegetation.director;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.AbstractParasitizedMonster;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 司令塔の確かめ用のコマンド(2026-11-13、第 1 段)。{@code /ov perf g1} の下({@code corpse/G1Command} と同じ枝に足す)。
 * <ul>
 *   <li>{@code director} — 森の予算・上限・貯まる速さ・これまでの判断と直近の判断、周り 32 ブロックの段階 0 の「見に来るまで」。</li>
 *   <li>{@code budget <量>} — 森の予算をその量にする。</li>
 *   <li>{@code visit [半径]} — 周りの段階 0 を今すぐ見に来させる(判断は司令塔がする。予算が足りなければ何もしない)。</li>
 *   <li>{@code route evolve [半径]} — 周りの段階 0 を、司令塔を通さず(予算も払わず)進化の前兆に入らせる。</li>
 *   <li>{@code director base} — 拠点の記憶(覚えている拠点・点・段・いない日数)。{@code base sample} = 見張りを今すぐ 1 回、{@code base here <時間>} = 立っている升にいたことにする、
 *   {@code base absent <日>} = 全部の拠点でその日数いなかったことにする、{@code base forget} = 全部消す(第 2 段 区切り 1)。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class DirectorCommand {

    private DirectorCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ov")
                .then(Commands.literal("perf")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("g1")
                                .then(Commands.literal("director")
                                        .executes(ctx -> describe(ctx.getSource()))
                                        .then(Commands.literal("phase")
                                                .executes(ctx -> gates(ctx.getSource()))
                                                .then(Commands.literal("reset").executes(ctx -> phaseSet(ctx.getSource(), null)))
                                                .then(Commands.literal("stage")
                                                        .then(Commands.literal("intro").executes(ctx -> stageSet(ctx.getSource(), com.yayuy.overgrownvegetation.phase.PhaseStage.INTRO)))
                                                        .then(Commands.literal("established").executes(ctx -> stageSet(ctx.getSource(), com.yayuy.overgrownvegetation.phase.PhaseStage.ESTABLISHED)))
                                                        .then(Commands.literal("peak").executes(ctx -> stageSet(ctx.getSource(), com.yayuy.overgrownvegetation.phase.PhaseStage.PEAK)))
                                                        .then(Commands.literal("reset").executes(ctx -> stageSet(ctx.getSource(), null))))
                                                .then(Commands.argument("phase", IntegerArgumentType.integer(0, 99))
                                                        .executes(ctx -> phaseSet(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "phase")))))
                                        .then(Commands.literal("base")
                                                .executes(ctx -> baseDescribe(ctx.getSource()))
                                                .then(Commands.literal("sample")
                                                        .executes(ctx -> baseSample(ctx.getSource())))
                                                .then(Commands.literal("here")
                                                        .then(Commands.argument("hours", DoubleArgumentType.doubleArg(0.0D, 1000.0D))
                                                                .executes(ctx -> baseHere(ctx.getSource(), DoubleArgumentType.getDouble(ctx, "hours")))))
                                                .then(Commands.literal("absent")
                                                        .then(Commands.argument("days", DoubleArgumentType.doubleArg(0.0D, 100.0D))
                                                                .executes(ctx -> baseAbsent(ctx.getSource(), DoubleArgumentType.getDouble(ctx, "days")))))
                                                .then(Commands.literal("scout")
                                                        .executes(ctx -> baseScout(ctx.getSource())))
                                                .then(Commands.literal("wave")
                                                        .executes(ctx -> baseWave(ctx.getSource())))
                                                .then(Commands.literal("stage")
                                                        .then(Commands.literal("remembered").executes(ctx -> baseStage(ctx.getSource(), BaseMemory.Stage.REMEMBERED)))
                                                        .then(Commands.literal("scouted").executes(ctx -> baseStage(ctx.getSource(), BaseMemory.Stage.SCOUTED)))
                                                        .then(Commands.literal("recognized").executes(ctx -> baseStage(ctx.getSource(), BaseMemory.Stage.RECOGNIZED))))
                                                .then(Commands.literal("forget")
                                                        .executes(ctx -> baseForget(ctx.getSource())))))
                                .then(Commands.literal("budget")
                                        .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0.0D, 1.0E6D))
                                                .executes(ctx -> budget(ctx.getSource(), DoubleArgumentType.getDouble(ctx, "amount")))))
                                .then(Commands.literal("visit")
                                        .executes(ctx -> visit(ctx.getSource(), 16))
                                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 64))
                                                .executes(ctx -> visit(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "radius")))))
                                .then(Commands.literal("route")
                                        .then(Commands.literal("evolve")
                                                .executes(ctx -> forceEvolve(ctx.getSource(), 16))
                                                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 64))
                                                        .executes(ctx -> forceEvolve(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "radius")))))
                                        .then(Commands.literal("rooted")
                                                .executes(ctx -> forceRooted(ctx.getSource(), 16))
                                                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 64))
                                                        .executes(ctx -> forceRooted(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "radius")))))
                                        .then(Commands.literal("nursery")
                                                .executes(ctx -> forceNursery(ctx.getSource(), 16))
                                                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 64))
                                                        .executes(ctx -> forceNursery(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "radius")))))))));
    }

    /** 半径の中の段階 0 を、司令塔を通さず(予算も払わず)苗床の道に入らせる: 森から離れて歩く → 根を下ろす → 苗床に入れ替わる。 */
    private static int forceNursery(CommandSourceStack source, int radius) {
        ServerLevel level = source.getLevel();
        int n = 0;
        for (AbstractParasitizedMonster m : stageZero(source, radius)) {
            if (m.fate().route() == ForestDirector.Route.NONE && !m.isRising()) {
                m.fate().beginNursery();
                ForestDirector.get(level).record(ForestDirector.Route.NURSERY, m.species().key() + " → 苗床(コマンド)");
                n++;
            }
        }
        int count = n;
        source.sendSuccess(() -> Component.literal("段階 0 を苗床の道に入らせた(森から離れて歩く): " + count + " 体"), true);
        return n;
    }

    /**
     * 半径の中の段階 0 を、司令塔を通さず(予算も払わず)根付きの道に入らせる: 森から離れて歩く → 根を下ろす。
     * 根付きの姿をまだ組み込んでいない種は、根を下ろした所でやめて段階 0 のまま残る。
     */
    private static int forceRooted(CommandSourceStack source, int radius) {
        ServerLevel level = source.getLevel();
        int n = 0;
        for (AbstractParasitizedMonster m : stageZero(source, radius)) {
            if (m.fate().route() == ForestDirector.Route.NONE && !m.isRising()) {
                m.fate().beginRooted();
                ForestDirector.get(level).record(ForestDirector.Route.ROOTED, m.species().key() + " → 根付き(コマンド)");
                n++;
            }
        }
        int count = n;
        source.sendSuccess(() -> Component.literal("段階 0 を根付きの道に入らせた(森から離れて歩く): " + count + " 体"), true);
        return n;
    }

    /** 半径の中の、司令塔が見に来る個体(段階 0。前兆の途中のものは除く)。 */
    private static List<AbstractParasitizedMonster> stageZero(CommandSourceStack source, int radius) {
        List<AbstractParasitizedMonster> out = new ArrayList<>();
        for (AbstractParasitizedMonster m : source.getLevel().getEntitiesOfClass(AbstractParasitizedMonster.class,
                AABB.ofSize(source.getPosition(), radius * 2, radius * 2, radius * 2))) {
            if (m.isAlive() && m.fate() != null) out.add(m);
        }
        return out;
    }

    private static int describe(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        for (String line : ForestDirector.get(level).describe(level)) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        long now = level.getGameTime();
        List<AbstractParasitizedMonster> near = stageZero(source, 32);
        for (AbstractParasitizedMonster m : near) {
            G1Fate f = m.fate();
            String when = f.route() != ForestDirector.Route.NONE ? f.route().label + "の途中"
                    : f.nextVisit() < 0L ? "まだ決めていない"
                    : String.format(Locale.ROOT, "あと %.1f 分", Math.max(0L, f.nextVisit() - now) / 1200.0D);
            source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "  %s(%.0f, %.0f, %.0f): 見に来るまで %s",
                    m.species().key(), m.getX(), m.getY(), m.getZ(), when)), false);
        }
        return 1 + near.size();
    }

    // ---------------------------------------------------------------- フェーズで開くもの(第 2 段の続き)

    private static int gates(CommandSourceStack source) {
        String line = DirectorGates.describe(source.getServer().overworld());
        source.sendSuccess(() -> Component.literal(line), false);
        return 1;
    }

    /** 司令塔が見る小さな段だけを差し替える(PhaseStages は変えない。reset で戻す)。 */
    private static int stageSet(CommandSourceStack source, @javax.annotation.Nullable com.yayuy.overgrownvegetation.phase.PhaseStage stage) {
        DirectorGates.setStageForTests(stage);
        source.sendSuccess(() -> Component.literal(stage == null ? "司令塔が見る段を元に戻した" : "司令塔が見る段を " + stage.name() + " にした(司令塔だけ)"), true);
        return gates(source);
    }

    /** 司令塔が見るフェーズだけを差し替える(ほかの仕組みのフェーズは変えない。reset で戻す)。 */
    private static int phaseSet(CommandSourceStack source, @javax.annotation.Nullable Integer phase) {
        DirectorGates.setPhaseForTests(phase);
        if (phase == null) DirectorGates.setStageForTests(null);
        source.sendSuccess(() -> Component.literal(phase == null ? "司令塔が見るフェーズを元に戻した" : "司令塔が見るフェーズを F" + phase + " にした(司令塔だけ)"), true);
        return gates(source);
    }

    // ---------------------------------------------------------------- 拠点の記憶(第 2 段 区切り 1)

    private static int baseDescribe(CommandSourceStack source) {
        var server = source.getServer();
        String gates = DirectorGates.describe(server.overworld());
        source.sendSuccess(() -> Component.literal(gates), false);
        List<String> lines = BaseMemory.get(server).describe(BaseMemory.today(server));
        for (String line : lines) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return lines.size();
    }

    /** 見張りを今すぐ 1 回まわす(20 秒待たずに点を見る)。 */
    private static int baseSample(CommandSourceStack source) {
        BaseWatch.sample(source.getServer());
        return baseDescribe(source);
    }

    /** 立っている升に「いた時間」を足す(何時間もいたことにする)。 */
    private static int baseHere(CommandSourceStack source, double hours) {
        var server = source.getServer();
        net.minecraft.core.BlockPos pos = net.minecraft.core.BlockPos.containing(source.getPosition());
        BaseMemory m = BaseMemory.get(server);
        double day = BaseMemory.today(server);
        m.addPresence(net.minecraft.world.level.ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4), hours, day);
        m.update(day);
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "この升に %.1f 時間いたことにした", hours)), true);
        return baseDescribe(source);
    }

    /** 全部の拠点で、その日数だけいなかったことにする(忘れる・減るのを確かめる)。 */
    private static int baseAbsent(CommandSourceStack source, double days) {
        var server = source.getServer();
        BaseMemory m = BaseMemory.get(server);
        m.pretendAbsent(days);
        m.update(BaseMemory.today(server));
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "%.1f 日いなかったことにした", days)), true);
        return baseDescribe(source);
    }

    /** いちばん近い拠点(真ん中まで 256 ブロック以内)。 */
    @javax.annotation.Nullable
    private static BaseMemory.Base nearestBase(CommandSourceStack source) {
        BaseMemory.Base b = BaseMemory.get(source.getServer()).nearest(source.getPosition().x, source.getPosition().z, 256.0D);
        if (b == null) source.sendFailure(Component.literal("近くに覚えている拠点が無い(/ov perf g1 director base here 20 で作れる)"));
        return b;
    }

    /** いちばん近い拠点へ、今すぐ偵察を出す(まわりに出せる段階 0 がいれば)。 */
    private static int baseScout(CommandSourceStack source) {
        BaseMemory.Base b = nearestBase(source);
        if (b == null) return 0;
        var server = source.getServer();
        BaseMemory m = BaseMemory.get(server);
        double day = BaseMemory.today(server);
        AbstractParasitizedMonster pick = BaseWatch.pickScout(server.overworld(), b,
                com.yayuy.overgrownvegetation.data.DesignData.director().base, server.overworld().getRandom());
        if (pick == null) pick = BaseWatch.spawnScoutFromForest(server.overworld(), b, com.yayuy.overgrownvegetation.data.DesignData.director().base,
                server.overworld().getRandom()); // いなければ森の方角から湧かせる(見張りと同じ)
        if (pick == null) {
            source.sendFailure(Component.literal(String.format(Locale.ROOT,
                    "拠点 #%d のまわり(%.0f〜%.0f ブロック)に出せる段階 0 がいない。森の方角から湧かせる所も無い(森が見当たらない・予算が足りない・見通せる所だけ)", b.id(),
                    com.yayuy.overgrownvegetation.data.DesignData.director().base.scoutFromMinBlocks,
                    com.yayuy.overgrownvegetation.data.DesignData.director().base.scoutSearchBlocks)));
            return 0;
        }
        final AbstractParasitizedMonster scout = pick;
        scout.fate().scout().begin(b);
        m.scoutSent(b, day, server.overworld().getRandom(), !DirectorGates.open(server.overworld(), DirectorGates.Gate.RECOGNIZE));
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "%s(%.0f, %.0f, %.0f)を拠点 #%d の偵察に出した",
                scout.species().key(), scout.getX(), scout.getY(), scout.getZ(), b.id())), true);
        return 1;
    }

    /** いちばん近い拠点へ、今すぐ 1 回送り込む(認識していなくても送る。顔ぶれは認識してからの日数の段)。 */
    private static int baseWave(CommandSourceStack source) {
        BaseMemory.Base b = nearestBase(source);
        if (b == null) return 0;
        var server = source.getServer();
        BaseRaids.Result r = BaseRaids.sendWave(server.overworld(), b, BaseMemory.today(server), server.overworld().getRandom());
        source.sendSuccess(() -> Component.literal("拠点 #" + b.id() + " へ送り込んだ: " + r.summary()), true);
        return 1;
    }

    /** いちばん近い拠点の段を変える(偵察済みにしたら、認識までの日数も決める)。 */
    private static int baseStage(CommandSourceStack source, BaseMemory.Stage stage) {
        BaseMemory.Base b = nearestBase(source);
        if (b == null) return 0;
        var server = source.getServer();
        BaseMemory.get(server).setStage(b, stage, BaseMemory.today(server), server.overworld().getRandom());
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "拠点 #%d を「%s」にした", b.id(), stage.label)), true);
        return baseDescribe(source);
    }

    private static int baseForget(CommandSourceStack source) {
        BaseMemory.get(source.getServer()).clear();
        source.sendSuccess(() -> Component.literal("拠点の記憶を全部消した"), true);
        return 1;
    }

    private static int budget(CommandSourceStack source, double amount) {
        ServerLevel level = source.getLevel();
        ForestDirector.get(level).setBudget(level, amount);
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "森の予算を %.1f にした(上限 %.1f)", amount, ForestDirector.cap(level))), true);
        return 1;
    }

    private static int visit(CommandSourceStack source, int radius) {
        long now = source.getLevel().getGameTime();
        int n = 0;
        for (AbstractParasitizedMonster m : stageZero(source, radius)) {
            if (m.fate().route() == ForestDirector.Route.NONE) {
                m.fate().setNextVisit(now);
                n++;
            }
        }
        int count = n;
        source.sendSuccess(() -> Component.literal("段階 0 を今すぐ見に来させた: " + count + " 体(結果は /ov perf g1 director)"), true);
        return n;
    }

    private static int forceEvolve(CommandSourceStack source, int radius) {
        ServerLevel level = source.getLevel();
        int n = 0;
        for (AbstractParasitizedMonster m : stageZero(source, radius)) {
            if (m.fate().route() == ForestDirector.Route.NONE && !m.isRising()) {
                m.fate().beginEvolve();
                ForestDirector.get(level).record(ForestDirector.Route.EVOLVE, m.species().key() + " → 進化(コマンド)");
                n++;
            }
        }
        int count = n;
        source.sendSuccess(() -> Component.literal("段階 0 を進化の前兆に入らせた: " + count + " 体"), true);
        return n;
    }
}
