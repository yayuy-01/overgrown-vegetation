package com.yayuy.overgrownvegetation.worldevent;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.network.WorldEventPayload;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import com.yayuy.overgrownvegetation.spore.field.PrevailingWind;
import com.yayuy.overgrownvegetation.spore.field.WindWeatherData;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

/**
 * ワールドイベント 7 つのサーバー側(2026-11-16 world-events。正本は Vault「ワールドイベント 7 つの中身の案(Fable・11-16)」の【決定】)。
 * 日程は {@link WorldEventSchedule}(保存は {@link WorldEventData})、値は {@code world_events.json}({@link WorldEventTable})。オーバーワールドだけ。
 *
 * <ul>
 *   <li>毎 tick 日程を進め、始まり・終わりのとき・5 秒ごと・ワールドに入ったとき・ディメンションを移ったときに {@link WorldEventPayload} を送る。</li>
 *   <li>合図・チャットは出さない(空や地面を見た人だけが気づく。ユーザー)。</li>
 *   <li>効き目は地脈の大潮だけ: {@link #pumpMultiplier} を汲み上げ器が掛ける。ほかは見た目だけ(日食でも敵対 Mob は変えない)。</li>
 *   <li>天気とのつながり: 地平線を歩く影は濃霧({@link PrevailingWind#fogLevel})の日だけ。空のイベントは始まりに雨なら次の窓へ(【仮】)。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class WorldEvents {

    /** 送り直しの間隔(tick)。 */
    static final int SEND_EVERY = 100;

    private WorldEvents() {
    }

    /** くじの種(ワールドのシードそのものは使わない)。 */
    public static long seed(MinecraftServer server) {
        return WindWeatherData.windSeed(server) ^ 0x57E7E7E5L;
    }

    /** ゲームの様子(フェーズ・雨・濃霧の予定)。 */
    public static WorldEventSchedule.Env env(ServerLevel overworld) {
        MinecraftServer server = overworld.getServer();
        return new WorldEventSchedule.Env() {
            private PrevailingWind.Weather weather;

            @Override
            public int phase() {
                return PhaseData.get(overworld).getPhase();
            }

            @Override
            public boolean raining() {
                return overworld.isRaining();
            }

            @Override
            public double fog(long t) {
                if (weather == null) weather = WindWeatherData.get(server).weather();
                return PrevailingWind.fogLevel(WindWeatherData.windSeed(server), t, weather, null);
            }
        };
    }

    // ---------------------------------------------------------------- 回す

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        ServerLevel ow = server.overworld();
        WorldEventData data = WorldEventData.get(server);
        data.schedule().frequency = com.yayuy.overgrownvegetation.difficulty.WorldDifficultyData.of(server, com.yayuy.overgrownvegetation.difficulty.DifficultySetting.EVENT_FREQUENCY); // 11-19 MOD の設定「ワールドイベントの頻度」
        boolean changed = data.schedule().step(ow.getDayTime(), seed(server), DesignData.worldEvents(), env(ow));
        data.setDirty();
        if (changed || server.getTickCount() % SEND_EVERY == 0) {
            sendAll(server);
        }
    }

    /** 今起きている種(オーバーワールド。起きていなければ null)。 */
    @Nullable
    public static WorldEventKind active(MinecraftServer server) {
        return WorldEventData.get(server).schedule().active();
    }

    /**
     * 地脈の汲み上げ器の汲む量の倍率(地脈の大潮の間だけ {@code leyTidePumpMultiplier}【仮】、ほかは 1)。オーバーワールドの汲み上げ器だけ。
     * 汲み上げ器が毎 tick 呼ぶ。
     */
    public static double pumpMultiplier(@Nullable Level level) {
        if (!(level instanceof ServerLevel sl) || sl.dimension() != Level.OVERWORLD) return 1.0D;
        return active(sl.getServer()) == WorldEventKind.LEY_TIDE ? DesignData.worldEvents().leyTidePumpMultiplier : 1.0D;
    }

    /**
     * 今すぐ起こす(フェーズ・時刻・霧・雨を見ない)。フェーズの合図(F3 の夜のオーロラ)など、ほかの仕組みから呼ぶ口。
     * 確かめ用の印が付き、終わっても次のくじの日は変えない。
     */
    public static void startNow(MinecraftServer server, WorldEventKind kind, double progress) {
        startNow(server, kind, progress, DesignData.worldEvents().spec(kind).length());
    }

    /** 長さ(tick)を指定して今すぐ起こす。 */
    public static void startNow(MinecraftServer server, WorldEventKind kind, double progress, int lengthTicks) {
        WorldEventData data = WorldEventData.get(server);
        data.schedule().forceStart(kind, server.overworld().getDayTime(), progress, lengthTicks);
        data.setDirty();
        sendAll(server);
    }

    // ---------------------------------------------------------------- 送る

    public static WorldEventPayload payload(MinecraftServer server) {
        WorldEventSchedule s = WorldEventData.get(server).schedule();
        WorldEventKind k = s.active();
        if (k == null) return WorldEventPayload.NONE;
        return new WorldEventPayload((byte) k.ordinal(), s.activeStart(), s.activeEnd(), server.overworld().getDayTime());
    }

    static void sendAll(MinecraftServer server) {
        if (server.getPlayerList().getPlayerCount() == 0) return;
        WorldEventPayload p = payload(server);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            PacketDistributor.sendToPlayer(player, player.level().dimension() == Level.OVERWORLD ? p : WorldEventPayload.NONE);
        }
    }

    static void sendTo(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, player.level().dimension() == Level.OVERWORLD
                ? payload(player.getServer()) : WorldEventPayload.NONE);
    }

    @SubscribeEvent
    public static void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) sendTo(sp);
    }

    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) sendTo(sp);
    }

    // ---------------------------------------------------------------- /ov event(確かめ用。OP 専用)

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        String[] ids = Arrays.stream(WorldEventKind.values()).map(k -> k.id).toArray(String[]::new);
        event.getDispatcher().register(Commands.literal("ov")
                .then(Commands.literal("event")
                        .requires(source -> source.hasPermission(2))
                        .executes(WorldEvents::status)
                        .then(Commands.literal("status").executes(WorldEvents::status))
                        .then(Commands.literal("start")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(ids, b))
                                        .executes(c -> start(c, 0.0D))
                                        .then(Commands.argument("progress", DoubleArgumentType.doubleArg(0.0D, 1.0D))
                                                .executes(c -> start(c, DoubleArgumentType.getDouble(c, "progress"))))))
                        .then(Commands.literal("stop").executes(WorldEvents::stop))
                        .then(Commands.literal("next").executes(WorldEvents::next))));
    }

    private static int start(CommandContext<CommandSourceStack> ctx, double progress) {
        String name = StringArgumentType.getString(ctx, "name");
        WorldEventKind k = WorldEventKind.byId(name);
        if (k == null) {
            ctx.getSource().sendFailure(Component.literal("知らないイベント: " + name + "(" + String.join(" / ",
                    Arrays.stream(WorldEventKind.values()).map(x -> x.id).toList()) + ")"));
            return 0;
        }
        startNow(ctx.getSource().getServer(), k, progress);
        long len = DesignData.worldEvents().spec(k).length();
        ctx.getSource().sendSuccess(() -> Component.literal(String.format("%s(%s)を起こした: 進み %.2f から、長さ %d tick(実時間 %.1f 分)。"
                + "フェーズ・時刻・霧・雨は見ない。終わっても次のくじの日は変えない", k.jaName, k.id, progress, len, len / 1200.0D)), true);
        return 1;
    }

    private static int stop(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        WorldEventData data = WorldEventData.get(server);
        WorldEventSchedule s = data.schedule();
        WorldEventKind was = s.kind();
        s.stop(server.overworld().getDayTime(), seed(server), DesignData.worldEvents());
        data.setDirty();
        sendAll(server);
        ctx.getSource().sendSuccess(() -> Component.literal((was != null ? was.jaName + " を止めた" : "何も起きていない")
                + "。次のくじは " + s.nextDueDay() + " 日目"), true);
        return 1;
    }

    private static int next(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        ServerLevel ow = server.overworld();
        WorldEventData data = WorldEventData.get(server);
        WorldEventSchedule s = data.schedule();
        WorldEventKind k = s.drawNow(ow.getDayTime(), seed(server), DesignData.worldEvents(), env(ow));
        data.setDirty();
        sendAll(server);
        ctx.getSource().sendSuccess(() -> Component.literal(k == null ? "起きてよいイベントが無い(フェーズを見る)。明日もう一度引く"
                : "くじを引いた: " + k.jaName + "(" + k.id + ")。" + describe(s, ow.getDayTime())), true);
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        ServerLevel ow = server.overworld();
        long t = ow.getDayTime();
        WorldEventSchedule s = WorldEventData.get(server).schedule();
        String text = String.format("今は %d 日目 %s(dayTime %d)。フェーズ F%d%n%s",
                WorldEventSchedule.day(t), clock(t), t, PhaseData.get(ow).getPhase(), describe(s, t));
        ctx.getSource().sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    static String describe(WorldEventSchedule s, long t) {
        String omen = s.omens().isEmpty() ? "" : "\nフェーズの合図: " + String.join(" → ", s.omens().stream().map(k -> k.jaName).toList())
                + (s.omenActive() ? String.format("(起きている: 進み %.2f)", s.progress(t))
                : s.omenStart() != WorldEventSchedule.NONE ? "(" + WorldEventSchedule.day(s.omenStart()) + " 日目 " + clock(s.omenStart()) + " から)" : "");
        return omen.isEmpty() ? describeLottery(s, t) : describeLottery(s, t) + omen;
    }

    static String describeLottery(WorldEventSchedule s, long t) {
        return switch (s.state()) {
            case IDLE -> "休み。次のくじは " + s.nextDueDay() + " 日目(あと " + (s.nextDueDay() - WorldEventSchedule.day(t)) + " 日)";
            case WAITING -> s.start() == WorldEventSchedule.NONE
                    ? s.kind().jaName + " を待っている(霞・霧の朝か夕。" + s.giveUpDay() + " 日目を過ぎたら引き直す)"
                    : String.format("%s を待っている: %d 日目 %s に始まり %s まで(あと %d tick。持ち越し %d 回。%d 日目を過ぎたら引き直す)",
                    s.kind().jaName, WorldEventSchedule.day(s.start()), clock(s.start()), clock(s.end()), s.start() - t, s.carry(), s.giveUpDay());
            case ACTIVE -> String.format("%s が起きている: 進み %.2f(%s〜%s、あと %d tick)%s",
                    s.kind().jaName, s.progress(t), clock(s.start()), clock(s.end()), s.end() - t, s.forced() ? "・確かめ用" : "");
        };
    }

    /** dayTime を時刻に(0 = 6:00)。 */
    static String clock(long t) {
        long tod = Math.floorMod(t + 6000L, (long) WorldEventSchedule.DAY);
        return String.format("%d:%02d", tod / 1000, tod % 1000 * 60 / 1000);
    }
}
