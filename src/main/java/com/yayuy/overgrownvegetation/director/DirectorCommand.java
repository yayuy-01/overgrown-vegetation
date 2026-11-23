package com.yayuy.overgrownvegetation.director;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.AbstractParasitizedMonster;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterCommandsEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 司令塔の確かめ用のコマンド(2026-11-13、第 1 段)。{@code /ov perf g1} の下({@code corpse/G1Command} と同じ枝に足す)。
 * <ul>
 *   <li>{@code director} — 森の予算・上限・貯まる速さ・これまでの判断と直近の判断。</li>
 *   <li>{@code budget <量>} — 森の予算をその量にする。</li>
 *   <li>{@code route evolve [半径]} — 周りの段階 0 を、司令塔を通さず(予算も払わず)進化の前兆に入らせる。</li>
 *   <li>{@code director base} — 拠点の記憶(覚えている拠点・点・段・いない日数)。{@code base sample} = 見張りを今すぐ 1 回、{@code base here <時間>} = 立っている升にいたことにする、
 *   {@code base absent <日>} = 全部の拠点でその日数いなかったことにする、{@code base forget} = 全部消す(第 2 段 区切り 1)。</li>
 * </ul>
 * 2026-11-23 片付け E: {@code visit}・{@code route rooted}・{@code route nursery}・{@code danger send}・{@code base wave}・{@code raid large omen}・{@code raid large start} を消した。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE)
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
                                        .then(Commands.literal("watcher")
                                                .executes(ctx -> watcherDescribe(ctx.getSource()))
                                                .then(Commands.literal("debug")
                                                        .then(Commands.literal("on").executes(ctx -> watcherDebug(ctx.getSource(), true)))
                                                        .then(Commands.literal("off").executes(ctx -> watcherDebug(ctx.getSource(), false))))
                                                .then(Commands.literal("plant").executes(ctx -> watcherPlant(ctx.getSource())))
                                                .then(Commands.literal("grown").executes(ctx -> watcherNearest(ctx.getSource(), 0)))
                                                .then(Commands.literal("cut").executes(ctx -> watcherNearest(ctx.getSource(), 1)))
                                                .then(Commands.literal("see").executes(ctx -> watcherNearest(ctx.getSource(), 2))))
                                        .then(Commands.literal("raid")
                                                .then(Commands.literal("large")
                                                        .executes(ctx -> largeDescribe(ctx.getSource()))
                                                        .then(Commands.literal("stop").executes(ctx -> {
                                                            var s = ctx.getSource();
                                                            LargeRaids.end(s.getServer().overworld(), BaseMemory.today(s.getServer()), "コマンド");
                                                            return largeDescribe(s);
                                                        }))))
                                        .then(Commands.literal("mood")
                                                .executes(ctx -> moodDescribe(ctx.getSource()))
                                                .then(Commands.literal("hoard")
                                                        .then(Commands.argument("days", DoubleArgumentType.doubleArg(0.01D, 10.0D))
                                                                .executes(ctx -> {
                                                                    var s = ctx.getSource();
                                                                    ForestMoods.startHoard(s.getServer().overworld(), BaseMemory.today(s.getServer()), DoubleArgumentType.getDouble(ctx, "days"));
                                                                    return moodDescribe(s);
                                                                })))
                                                .then(Commands.literal("spend").executes(ctx -> {
                                                    var s = ctx.getSource();
                                                    int n = ForestMoods.spend(s.getServer().overworld(), BaseMemory.today(s.getServer()), s.getServer().overworld().getRandom(), null);
                                                    s.sendSuccess(() -> Component.literal("一斉に進化させた: " + n + " 体"), true);
                                                    return moodDescribe(s);
                                                })))
                                        .then(Commands.literal("danger")
                                                .executes(ctx -> dangerDescribe(ctx.getSource()))
                                                .then(Commands.literal("here")
                                                        .then(Commands.argument("kills", IntegerArgumentType.integer(1, 100))
                                                                .executes(ctx -> dangerHere(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "kills")))))
                                                .then(Commands.literal("forget").executes(ctx -> {
                                                    KillMemory.get(ctx.getSource().getServer()).clear();
                                                    ctx.getSource().sendSuccess(() -> Component.literal("倒された記録を全部消した"), true);
                                                    return 1;
                                                })))
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
                                                .then(Commands.literal("defense")
                                                        .executes(ctx -> baseDefense(ctx.getSource())))
                                                .then(Commands.literal("stage")
                                                        .then(Commands.literal("remembered").executes(ctx -> baseStage(ctx.getSource(), BaseMemory.Stage.REMEMBERED)))
                                                        .then(Commands.literal("scouted").executes(ctx -> baseStage(ctx.getSource(), BaseMemory.Stage.SCOUTED)))
                                                        .then(Commands.literal("recognized").executes(ctx -> baseStage(ctx.getSource(), BaseMemory.Stage.RECOGNIZED))))
                                                .then(Commands.literal("forget")
                                                        .executes(ctx -> baseForget(ctx.getSource())))))
                                .then(Commands.literal("soak") // 計測用(11-20 F7 までの通しの放置)
                                        .then(Commands.literal("start").executes(ctx -> say(ctx.getSource(), EconomySoak.start(ctx.getSource().getServer()))))
                                        .then(Commands.literal("stop").executes(ctx -> say(ctx.getSource(), EconomySoak.stop())))
                                        .then(Commands.literal("status").executes(ctx -> say(ctx.getSource(), EconomySoak.status(ctx.getSource().getLevel())))))
                                .then(Commands.literal("budget")
                                        .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0.0D, 1.0E9D))
                                                .executes(ctx -> budget(ctx.getSource(), DoubleArgumentType.getDouble(ctx, "amount")))))
                                .then(Commands.literal("route")
                                        .then(Commands.literal("evolve")
                                                .executes(ctx -> forceEvolve(ctx.getSource(), 16))
                                                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 64))
                                                        .executes(ctx -> forceEvolve(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "radius")))))))));
    }

    /** 半径の中の、進化の道を持つ個体(段階 0)。 */
    private static List<AbstractParasitizedMonster> stageZero(CommandSourceStack source, int radius) {
        List<AbstractParasitizedMonster> out = new ArrayList<>();
        for (AbstractParasitizedMonster m : source.getLevel().getEntitiesOfClass(AbstractParasitizedMonster.class,
                AABB.ofSize(source.getPosition(), radius * 2, radius * 2, radius * 2))) {
            if (m.isAlive() && m.fate() != null) out.add(m);
        }
        return out;
    }

    private static int say(CommandSourceStack source, String line) {
        source.sendSuccess(() -> Component.literal(line), false);
        return 1;
    }

    private static int describe(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        for (String line : ForestDirector.get(level).describe(level)) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        // 11-20: 森の視界(ここが見えているか)・探りの偵察・棘の生垣
        source.sendSuccess(() -> Component.literal("ここは森に " + (ForestVision.sees(level, net.minecraft.core.BlockPos.containing(source.getPosition()))
                ? "見えている(" + ForestVision.why(level, net.minecraft.core.BlockPos.containing(source.getPosition())) + ")" : "見えていない")), false);
        for (String line : ScoutSearch.get(level.getServer()).describe(level)) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        source.sendSuccess(() -> Component.literal(ThornHedges.describe()), false);
        return 1;
    }

    // ---------------------------------------------------------------- 監視塔(11-14。本体が組み込まれるまでの確かめ用)

    private static int watcherDescribe(CommandSourceStack source) {
        BaseMemory.Base b = nearestBase(source);
        if (b == null) return 0;
        double day = BaseMemory.today(source.getServer());
        StringBuilder sb = new StringBuilder();
        for (WatcherSite w : b.watchers()) {
            sb.append(String.format(Locale.ROOT, " (%d, %d, %d)%s", w.pos().getX(), w.pos().getY(), w.pos().getZ(), w.grown() ? "育った" : "芽"));
        }
        String line = String.format(Locale.ROOT, "監視塔: 組み込み %s・拠点 #%d(%s)の監視塔 %d 本%s・刈られた 育つ前 %d / 後 %d・見られた %s・次に植えてよいまで %.2f 日",
                Watchers.active() ? "あり" : "なし(植えない・確定に印は要らない)", b.id(), b.stage().label, b.watchers().size(), sb,
                b.watchersCutYoung(), b.watchersCutGrown(),
                Double.isInfinite(b.watcherSeenDay()) ? "まだ" : String.format(Locale.ROOT, "%.2f 日前", day - b.watcherSeenDay()),
                Math.max(0.0D, b.nextPlantDay() - day));
        source.sendSuccess(() -> Component.literal(line), false);
        return 1;
    }

    /** 確かめ用の植える口(苔の柱を 3 段置く)を差し込む / 外す。 */
    private static int watcherDebug(CommandSourceStack source, boolean on) {
        Watchers.setPlanterForTests(on ? (level, pos, base) -> {
            for (int i = 0; i < 3; i++) level.setBlockAndUpdate(pos.above(i), net.minecraft.world.level.block.Blocks.MOSS_BLOCK.defaultBlockState());
            return true;
        } : null);
        source.sendSuccess(() -> Component.literal(on ? "確かめ用の監視塔(苔の柱)を植える口を差し込んだ" : "確かめ用の監視塔を外した"), true);
        return 1;
    }

    private static int watcherPlant(CommandSourceStack source) {
        BaseMemory.Base b = nearestBase(source);
        if (b == null) return 0;
        var server = source.getServer();
        b.nextPlantDay = 0.0D;
        BlockPos at = Watchers.tryPlant(server.overworld(), BaseMemory.get(server), b, BaseMemory.today(server), server.overworld().getRandom());
        source.sendSuccess(() -> Component.literal(at == null ? "植えなかった(監視塔の組み込み・本数・予算・場所を見る)" : "植えた: " + at.toShortString()), true);
        return at == null ? 0 : 1;
    }

    /** いちばん近い監視塔に: 0 = 育ち切った・1 = 刈られた・2 = コマンドを打った人に視線の印を付けた。 */
    private static int watcherNearest(CommandSourceStack source, int what) {
        BaseMemory.Base b = nearestBase(source);
        if (b == null) return 0;
        WatcherSite best = null;
        for (WatcherSite w : b.watchers()) {
            if (best == null || w.pos().distToCenterSqr(source.getPosition()) < best.pos().distToCenterSqr(source.getPosition())) best = w;
        }
        if (best == null) {
            source.sendFailure(Component.literal("拠点 #" + b.id() + " に監視塔が無い"));
            return 0;
        }
        ServerLevel level = source.getServer().overworld();
        BlockPos pos = best.pos();
        switch (what) {
            case 0 -> Watchers.onGrown(level, pos);
            case 1 -> {
                Watchers.onCut(level, pos);
                for (int i = 0; i < 3; i++) {
                    if (level.getBlockState(pos.above(i)).is(net.minecraft.world.level.block.Blocks.MOSS_BLOCK)) level.removeBlock(pos.above(i), false);
                }
            }
            default -> {
                if (!(source.getEntity() instanceof net.minecraft.world.entity.LivingEntity target)) {
                    source.sendFailure(Component.literal("印を付ける相手(コマンドを打った人)がいない"));
                    return 0;
                }
                int n = Watchers.markSeen(level, pos, target);
                source.sendSuccess(() -> Component.literal("視線の印を付けた: 呼び寄せた植物側の Mob " + n + " 体"), true);
            }
        }
        return watcherDescribe(source);
    }

    // ---------------------------------------------------------------- 大きな襲撃(第 4 段)

    private static int largeDescribe(CommandSourceStack source) {
        var server = source.getServer();
        ServerLevel level = server.overworld();
        ForestDirector d = ForestDirector.get(level);
        double day = BaseMemory.today(server);
        BaseMemory.Base b = LargeRaids.target(level);
        String line = String.format(Locale.ROOT, "大きな襲撃: 気分 %s%s・的 %s / 上限 %d・予算 %.1f / 上限 %.1f・次に起こしてよいのは %.2f 日後(F%d から・今の段 %s)",
                d.mood().label,
                d.mood() == ForestDirector.Mood.LARGE_OMEN ? String.format(Locale.ROOT, "(本番まで %.2f 日)", Math.max(0.0D, d.moodUntil() - day)) : "",
                b == null ? "なし" : "拠点 #" + b.id(),
                LargeRaids.maxAlive(), d.available(level), ForestDirector.cap(level),
                Math.max(0.0D, d.largeRaidCooldownUntil() - day), com.yayuy.overgrownvegetation.data.DesignData.director().gates.largeRaid,
                DirectorGates.stage(level).name());
        source.sendSuccess(() -> Component.literal(line), false);
        return 1;
    }

    // ---------------------------------------------------------------- 貯めて一気に使う(第 3 段 区切り 3)

    private static int moodDescribe(CommandSourceStack source) {
        var server = source.getServer();
        ServerLevel level = server.overworld();
        ForestDirector d = ForestDirector.get(level);
        double day = BaseMemory.today(server);
        String line = String.format(Locale.ROOT, "森の気分: %s%s・予算 %.1f / 上限 %.1f・次に貯め始めてよいのは %.2f 日後(今の段 %s。導入では貯めない)",
                d.mood().label, d.mood() == ForestDirector.Mood.HOARD ? String.format(Locale.ROOT, "(あと %.2f 日)", Math.max(0.0D, d.moodUntil() - day)) : "",
                d.available(level), ForestDirector.cap(level), Math.max(0.0D, d.hoardCooldownUntil() - day), DirectorGates.stage(level).name());
        source.sendSuccess(() -> Component.literal(line), false);
        return 1;
    }

    // ---------------------------------------------------------------- 学ぶ(第 3 段 区切り 1)

    private static int dangerDescribe(CommandSourceStack source) {
        var server = source.getServer();
        List<String> lines = KillMemory.get(server).describe(BaseMemory.today(server));
        for (String line : lines) source.sendSuccess(() -> Component.literal(line), false);
        return lines.size();
    }

    /** 立っている升に、n 体倒されたことにする。 */
    private static int dangerHere(CommandSourceStack source, int kills) {
        var server = source.getServer();
        net.minecraft.core.BlockPos pos = net.minecraft.core.BlockPos.containing(source.getPosition());
        KillMemory.get(server).addForTests(net.minecraft.world.level.ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4), kills, BaseMemory.today(server));
        source.sendSuccess(() -> Component.literal("この升で " + kills + " 体倒されたことにした"), true);
        return dangerDescribe(source);
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

    /** いちばん近い拠点の守りの方角を、今すぐ見直す(第 3 段 区切り 2)。 */
    private static int baseDefense(CommandSourceStack source) {
        BaseMemory.Base b = nearestBase(source);
        if (b == null) return 0;
        var server = source.getServer();
        double day = BaseMemory.today(server);
        double[] d = BaseDefense.measure(server.overworld(), b, day);
        int weak = BaseDefense.weakest(d);
        b.setDefense(d, weak, day);
        source.sendSuccess(() -> Component.literal("拠点 #" + b.id() + " の守り: " + BaseDefense.describe(d, weak)), true);
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
