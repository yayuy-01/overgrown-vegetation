package com.yayuy.overgrownvegetation.swarm;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * {@code /ov bench horde <count>}(OP)— G4 の群れ v2 の重さを測る(2026-09-25、設計 v2 の「Bench」)。
 *
 * <ol>
 *     <li>打った人から 48〜96 ブロック離れた、読み込まれて動いている地面に、G4 を {@code count} 体、計測用の群れ
 *     ({@link SwarmOrigin#BENCH}: 合流しない・群れごとに消えない)として湧かせる。編成は {@code swarm.json} のいちばん先のフェーズの
 *     地上の編成(アルボラ主体 + スカトゥリウム・スポロフィル・フィロセンス)を振り、群れの上限で区切る。標的は打った人
 *     ({@link Swarm#commandTarget} = 距離の上限なしの狩り)。</li>
 *     <li>湧かせた直後の {@value #WARMUP_TICKS} tick(読み込み・登録の山)を外し、{@value #MEASURE_TICKS} tick(20 秒)の
 *     1 tick ごとの所要時間({@code MinecraftServer#getTickTimesNanos})から平均と最悪の MSPT、50 ms を超えた tick の数を出す。
 *     比べる目安として、打った時点の直前 100 tick の平均も出す。</li>
 *     <li>終わったら、読み込まれている BENCH の Mob を全部消す(写しを回す。discard は Leave を同期で投げる)。</li>
 * </ol>
 * BENCH の Mob が残っている間・計測中は始めない。計測の外で読み込まれた BENCH の Mob(計測中にサーバーが落ちてセーブに残った、
 * 読み込み範囲の外へ出てセーブされた、など)は、Join のたびに次の tick で消す —— BENCH の群れは消えないので、放っておくと永久に残る。
 *
 * <p>空の群れ(ヴェロフィル)は湧かせない(地上の群れの経路・足跡・受け渡しが重さの本体。空は別に測る)。打った人がクリエイティブ・
 * 観戦だと標的にならず、群れは暇な動き(外へ向かう・汚染を広げる)のまま測ることになる(そのときは一言出す)。
 *
 * <p>数値はすべて仮決め 2026-09-25(48〜96・20 秒は設計どおり、ほかは {@link #WARMUP_TICKS} など)。出力は開発用なので
 * {@code Component.literal}(翻訳キーを作らない。{@code ClimaxCommand} と同じ)。
 *
 * <p>静的な状態(計測中の記録・消す予定の Mob)はサーバーが止まったとき・ワールドを閉じたときに捨てる。記録にはワールドも
 * エンティティも持たない(ディメンションのキーとプレイヤーの UUID だけ)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class SwarmBenchCommand {

    /** 測る長さ(tick)= 20 秒。設計 v2 の値。 */
    static final int MEASURE_TICKS = 400;
    /** 湧かせた直後に測らない tick(チャンクの読み込み・登録・最初の経路探索の山)。仮決め 2026-09-25。 */
    static final int WARMUP_TICKS = 20;
    /** 湧かせる距離(打った人から、水平)。設計 v2 の値。 */
    private static final double MIN_DISTANCE = 48.0D;
    private static final double MAX_DISTANCE = 96.0D;
    /** 一度に湧かせる上限。仮決め 2026-09-25(群れ 12 体 × 80 群れほど)。 */
    private static final int MAX_COUNT = 1000;
    /** 1 群れの中心を探す回数。 */
    private static final int CENTER_TRIES = 16;
    /** 1 tick の予算(ns)。これを超えた tick を数える。 */
    private static final long TICK_BUDGET_NANOS = 50_000_000L;

    /** 計測中の記録。無ければ null。 */
    @Nullable
    private static Session session;
    /** 計測の外で読み込まれた BENCH の Mob(次の tick に消す)。 */
    private static final List<Mob> STRAYS = new ArrayList<>();

    /** 1 回の計測。ワールド・エンティティへの参照は持たない。 */
    private static final class Session {
        final ResourceKey<Level> dimension;
        final UUID player;
        final String playerName;
        final int requested;
        final long baselineNanos;
        /** 湧かせている最中(この間の Join は計測の一部として通す)。 */
        boolean spawning = true;
        int spawned;
        int swarms;
        int warmup = WARMUP_TICKS;
        int samples;
        long sumNanos;
        long worstNanos;
        int overBudget;

        Session(ResourceKey<Level> dimension, ServerPlayer player, int requested, long baselineNanos) {
            this.dimension = dimension;
            this.player = player.getUUID();
            this.playerName = player.getGameProfile().getName();
            this.requested = requested;
            this.baselineNanos = baselineNanos;
        }
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ov")
                .then(Commands.literal("bench")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("horde")
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, MAX_COUNT))
                                        .executes(SwarmBenchCommand::horde)))));
    }

    private static int horde(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("プレイヤーから実行してください(群れの標的にします)"));
            return 0;
        }
        MinecraftServer server = source.getServer();
        if (session != null) {
            source.sendFailure(Component.literal("計測中です。終わるまで(最長 " + (WARMUP_TICKS + MEASURE_TICKS) / 20 + " 秒)待ってください"));
            return 0;
        }
        int leftover = countBenchMobs(server);
        if (leftover > 0) {
            source.sendFailure(Component.literal("計測用(BENCH)の Mob がまだ " + leftover + " 体います。次の tick に片付くので、少し待ってから打ち直してください"));
            return 0;
        }
        SwarmTable table = DesignData.swarm();
        SwarmTable.Template template = table.template(Integer.MAX_VALUE, Swarm.Kind.GROUND);
        if (template == null) {
            source.sendFailure(Component.literal("swarm.json に地上の編成(templates.ground)がありません"));
            return 0;
        }
        int count = IntegerArgumentType.getInteger(ctx, "count");
        ServerLevel level = player.serverLevel();
        Session s = new Session(level.dimension(), player, count, server.getAverageTickTimeNanos());
        session = s;
        try {
            spawnHorde(level, player, count, template, table, s);
        } finally {
            s.spawning = false;
        }
        if (s.spawned == 0) {
            session = null;
            source.sendFailure(Component.literal("湧かせる場所がありません(" + (int) MIN_DISTANCE + "〜" + (int) MAX_DISTANCE
                    + " ブロック先の、読み込まれて動いている乾いた地面が要ります)"));
            return 0;
        }
        String note = player.isCreative() || player.isSpectator()
                ? "(クリエイティブ・観戦なので標的になりません。群れは暇な動きのまま測ります)" : "";
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "G4 を %d 体(%d 群れ)湧かせました%s。%.1f 秒おいてから %d 秒の MSPT を測ります%s",
                s.spawned, s.swarms, s.spawned < count ? "(" + count + " 体の予定)" : "",
                WARMUP_TICKS / 20.0D, MEASURE_TICKS / 20, note)), true);
        return s.spawned;
    }

    /** {@code count} 体を群れの上限で区切って湧かせる。場所が見つからない回が続いても {@code count × 2 + 8} 回で打ち切る。 */
    private static void spawnHorde(ServerLevel level, ServerPlayer player, int count, SwarmTable.Template template,
                                   SwarmTable table, Session s) {
        RandomSource random = level.random;
        int max = table.maxSize(Swarm.Kind.GROUND);
        int attempts = 0;
        while (s.spawned < count && attempts < count * 2 + 8) {
            attempts++;
            BlockPos center = pickCenter(level, player, random);
            if (center == null) {
                continue;
            }
            int remaining = count - s.spawned;
            List<EntityType<?>> types = new ArrayList<>(template.roll(random, remaining, max));
            if (types.size() > remaining) {
                types = new ArrayList<>(types.subList(0, remaining));
            }
            if (types.isEmpty()) {
                break;
            }
            SwarmSpawner.Result result = SwarmSpawner.spawn(level, center, types, SwarmOrigin.BENCH, "", player);
            if (result.spawned()) {
                s.spawned += result.members().size();
                s.swarms++;
            }
        }
    }

    /**
     * 群れの中心。打った人から水平に 48〜96 ブロックの点で、チャンクが読み込まれていてエンティティが動き、
     * 地面(葉を除いた一番上)が水でないところ。見つからなければ null。
     */
    @Nullable
    private static BlockPos pickCenter(ServerLevel level, ServerPlayer player, RandomSource random) {
        for (int i = 0; i < CENTER_TRIES; i++) {
            double angle = random.nextDouble() * Math.PI * 2.0D;
            double distance = MIN_DISTANCE + random.nextDouble() * (MAX_DISTANCE - MIN_DISTANCE);
            int x = Mth.floor(player.getX() + Math.cos(angle) * distance);
            int z = Mth.floor(player.getZ() + Math.sin(angle) * distance);
            LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
            if (chunk == null) {
                continue;
            }
            int top = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15);
            BlockPos ground = new BlockPos(x, top, z);
            BlockPos feet = ground.above();
            if (!level.isPositionEntityTicking(feet) || !level.getFluidState(ground).isEmpty()) {
                continue;
            }
            return feet;
        }
        return null;
    }

    // --- 測る ---

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!STRAYS.isEmpty()) {
            List<Mob> strays = List.copyOf(STRAYS);
            STRAYS.clear();
            for (Mob mob : strays) {
                if (!mob.isRemoved()) {
                    mob.discard();
                }
            }
        }
        Session s = session;
        if (s == null || s.spawning) {
            return;
        }
        if (s.warmup > 0) {
            s.warmup--;
            return;
        }
        MinecraftServer server = event.getServer();
        // Post はこの tick の所要時間を書いた直後に来る(MinecraftServer#tickServer)。
        long nanos = server.getTickTimesNanos()[server.getTickCount() % 100];
        s.sumNanos += nanos;
        s.worstNanos = Math.max(s.worstNanos, nanos);
        if (nanos > TICK_BUDGET_NANOS) {
            s.overBudget++;
        }
        s.samples++;
        if (s.samples >= MEASURE_TICKS) {
            finish(server, s);
        }
    }

    private static void finish(MinecraftServer server, Session s) {
        session = null;
        int removed = discardBenchMobs(server);
        double avg = s.samples > 0 ? s.sumNanos / (double) s.samples / 1.0E6D : 0.0D;
        String message = String.format(Locale.ROOT,
                "[ov bench horde] G4 %d 体(%d 群れ、%s の %s を狙う)/ %d 秒: 平均 %.2f ms/tick、最悪 %.2f ms、50 ms 超え %d tick"
                        + "(打つ前の 100 tick の平均 %.2f ms)。計測用の Mob %d 体を片付けました",
                s.spawned, s.swarms, s.dimension.location(), s.playerName, s.samples / 20, avg, s.worstNanos / 1.0E6D,
                s.overBudget, s.baselineNanos / 1.0E6D, removed);
        OvergrownVegetation.LOGGER.info(message);
        ServerPlayer player = server.getPlayerList().getPlayer(s.player);
        if (player != null) {
            player.sendSystemMessage(Component.literal(message));
        }
    }

    // --- BENCH の Mob ---

    private static boolean isBench(Entity entity) {
        return entity instanceof Mob mob && mob.getExistingData(ModAttachments.SWARM)
                .map(m -> !m.isNone() && m.origin() == SwarmOrigin.BENCH).orElse(false);
    }

    /** 読み込まれている BENCH の Mob の数(全ディメンション)。 */
    private static int countBenchMobs(MinecraftServer server) {
        int n = 0;
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (isBench(entity) && !entity.isRemoved()) {
                    n++;
                }
            }
        }
        return n;
    }

    /** 読み込まれている BENCH の Mob を全部消す(写しを回す)。消した数を返す。 */
    private static int discardBenchMobs(MinecraftServer server) {
        int n = 0;
        for (ServerLevel level : server.getAllLevels()) {
            List<Mob> found = new ArrayList<>();
            for (Entity entity : level.getAllEntities()) {
                if (isBench(entity)) {
                    found.add((Mob) entity);
                }
            }
            for (Mob mob : found) {
                if (!mob.isRemoved()) {
                    mob.discard();
                    n++;
                }
            }
        }
        return n;
    }

    /**
     * 計測の外で BENCH の Mob が読み込まれたら、次の tick に消す(Join の中では消さない)。
     * 計測中(湧かせている最中を含む)に入ってきたものは通し、終わりにまとめて消す。
     */
    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || session != null || !isBench(event.getEntity())) {
            return;
        }
        STRAYS.add((Mob) event.getEntity());
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            STRAYS.removeIf(mob -> mob.level() == level);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        session = null;
        STRAYS.clear();
    }

    private SwarmBenchCommand() {
    }
}
