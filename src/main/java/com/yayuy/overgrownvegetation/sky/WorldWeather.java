package com.yayuy.overgrownvegetation.sky;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.network.ForestLightningPayload;
import com.yayuy.overgrownvegetation.network.WeatherPayload;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import com.yayuy.overgrownvegetation.spore.field.PrevailingWind;
import com.yayuy.overgrownvegetation.spore.field.SporeFieldServer;
import com.yayuy.overgrownvegetation.spore.field.WindWeatherData;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 天気 4 つのサーバー側(2026-11-16 weather-system。正本は Vault「雨の種類(小雨・普通・大雨と胞子)」の「天気の種類を足す」「天気の中身」)。
 *
 * <p>いつ起きるかは世界の風 {@link PrevailingWind} の純関数(種・日・雨の印)で決める(保存が要らず、再起動・胞子の場の追いつき・雲・飛ぶ Mob で同じ答え):
 * <ul>
 *   <li><b>濃霧</b> {@link PrevailingWind#fogLevel}: 雨上がり(夜〜朝に止んだ雨)・雨の無い朝。胞子の濃さは変えない(見た目だけ)。</li>
 *   <li><b>凪</b> {@link PrevailingWind#calmLevel}: 地上の風だけが止まる(雲・上空の層は上空の風で流れ続ける。11-16 ユーザー決定)。
 *       胞子の場は地上の運びを落とし、浮遊の沈着と死滅を遅くして森のそばに滞らせる({@code SporeField} の calmHold)。</li>
 *   <li><b>強風の日</b>: 前からある {@link PrevailingWind#isStrongDay}。胞子の場は林床の層も風下へ運ぶ(galeGroundWind)。
 *       ペディキュラ・セミナリアは速く流され、ヴェロフィルは止まり木に留まる({@code entity.flyer.FlyerWind})。</li>
 *   <li><b>雷雨</b>: 雲の予定({@link CloudWeather})のまま。汚染の森(台帳の過繁茂のチャンク)に雷が落ちたら、その升へ放出して上空の層へ持ち上げ、
 *       近くのプレイヤーへ {@link ForestLightningPayload}。雷雨の後の胞子の雲は上空の塊の流れそのもの(クライアントへは stormAfter)。</li>
 * </ul>
 * クライアントへは {@link WeatherPayload} を 5 秒ごと・ワールドに入ったとき・ディメンションを移ったときに送る。数はすべて【仮】。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class WorldWeather {

    /** 送る間隔(tick)。 */
    static final int SEND_EVERY = 100;
    /** 雷雨の後の胞子の雲を見せる長さ(tick)【仮】。 */
    public static final int STORM_AFTER_TICKS = 6000;
    /** 予定の雷雨の雷が始まる何 tick 前から前触れを立ち上げるか【仮】。 */
    public static final int PRE_STORM_TICKS = 1200;
    /** 汚染の森への落雷 1 本で放出する量(過繁茂の升の約半日分)と、浮遊を上空の層へ持ち上げる割合【仮】。 */
    public static final double STRIKE_EMIT = 0.5D, STRIKE_LIFT = 0.5D;
    /** 落雷を知らせる距離(ブロック)。 */
    static final double STRIKE_RANGE = 256.0D;

    private static final PrevailingWind.Sample SAMPLE = new PrevailingWind.Sample();
    /** 確かめ用の強制(負 = なし): 雷雨の前触れ・雷雨の後の、クライアントへ送る値(アーク雲・胞子の雲の見た目を出すため。11-17)。 */
    static volatile double forcedPre = -1, forcedAfter = -1;

    private WorldWeather() {
    }

    /** 今の天気(オーバーワールドの値)。サーバースレッドから。 */
    public static WeatherPayload compute(ServerLevel overworld) {
        MinecraftServer server = overworld.getServer();
        WindWeatherData wx = WindWeatherData.get(server);
        PrevailingWind.Weather w = wx.weather();
        long seed = WindWeatherData.windSeed(server);
        long t = overworld.getDayTime();
        PrevailingWind.sample(seed, t, w, SAMPLE);
        int[] kind = new int[1];
        double fog = PrevailingWind.fogLevel(seed, t, w, kind);
        boolean thunderNow = w.raining() && w.thundering();
        long te = wx.lastThunderEnd();
        double after = !thunderNow && te != PrevailingWind.NONE && t >= te && t - te < STORM_AFTER_TICKS
                ? 1.0D - (t - te) / (double) STORM_AFTER_TICKS : 0.0D;
        float thunder = thunderNow ? (float) PrevailingWind.stormScale : 0.0F;
        int left = 0;
        if (fog > 0) {
            for (int dt = 100; dt <= 8000; dt += 100) {
                if (PrevailingWind.fogLevel(seed, t + dt, w, null) <= 0) {
                    left = dt;
                    break;
                }
            }
        }
        if (SAMPLE.calm > 0 && PrevailingWind.forcedCalm < 0) {
            double tod = Math.floorMod(t, (long) PrevailingWind.DAY);
            double x = tod >= 23000 ? tod - PrevailingWind.DAY : tod;
            left = Math.max(left, (int) Math.max(0, 14000 - x));
        }
        // 確かめ用の強制(11-17。/ov weather pre・after): 見た目へ送る値だけ差し替える(天気そのもの・胞子の場は変えない)。
        float pre = forcedPre >= 0 ? (float) forcedPre : preStorm(overworld, t, thunderNow);
        if (forcedAfter >= 0) after = forcedAfter;
        return new WeatherPayload((float) fog, (byte) kind[0], (float) SAMPLE.calm, (float) SAMPLE.gale, (float) after, thunder,
                pre, left, t);
    }

    /** 予定の雷雨の雷が始まる前の立ち上がり(0〜1)。雷の間は 1。差し込みの嵐・手で変えた天気は 0。 */
    static float preStorm(Level level, long t, boolean thunderNow) {
        if (thunderNow) return 1.0F;
        if (!CloudWeather.scheduleActive(level)) return 0.0F;
        CloudWeather.Rain forced = CloudWeather.forcedStorm();
        float best = 0.0F;
        for (CloudWeather.Rain r : new CloudWeather.Rain[]{CloudWeather.rainAt(t), CloudWeather.nextRain(t)}) {
            if (r == null || r == forced || r.thunderTo() <= r.thunderFrom() || r.thunderFrom() <= t) continue;
            long leftTicks = r.thunderFrom() - t;
            if (leftTicks >= PRE_STORM_TICKS) continue;
            float k = 1.0F - leftTicks / (float) PRE_STORM_TICKS;
            best = Math.max(best, k * k * (3.0F - 2.0F * k));
        }
        return best;
    }

    // ---------------------------------------------------------------- 送る

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % SEND_EVERY != 0) return;
        sendAll(server);
    }

    static void sendAll(MinecraftServer server) {
        if (server.getPlayerList().getPlayerCount() == 0) return;
        WeatherPayload p = compute(server.overworld());
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            PacketDistributor.sendToPlayer(player, player.level().dimension() == Level.OVERWORLD ? p : WeatherPayload.NONE);
        }
    }

    static void sendTo(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, player.level().dimension() == Level.OVERWORLD
                ? compute(player.getServer().overworld()) : WeatherPayload.NONE);
    }

    @SubscribeEvent
    public static void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) sendTo(sp);
    }

    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) sendTo(sp);
    }

    // ---------------------------------------------------------------- 汚染の森への落雷

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.loadedFromDisk() || !(event.getEntity() instanceof LightningBolt bolt)
                || !(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) {
            return;
        }
        strike(level, bolt.blockPosition());
    }

    /** 汚染の森への落雷を当てはめる(GameTest からも)。台帳の過繁茂のチャンクでなければ何もしない。当てはめたら true。 */
    public static boolean strike(ServerLevel level, BlockPos pos) {
        float power = forestPower(level, pos);
        if (power <= 0.0F) return false;
        SporeFieldServer.lightningBurst(pos, STRIKE_EMIT * power, STRIKE_LIFT * power);
        int groundY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ());
        ForestLightningPayload payload = new ForestLightningPayload(pos, groundY, power);
        double r2 = STRIKE_RANGE * STRIKE_RANGE;
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D) <= r2) {
                PacketDistributor.sendToPlayer(player, payload);
            }
        }
        return true;
    }

    /** 落雷した所の汚染の強さ(台帳の過繁茂 = 1、それ以外 0。繁茂の 0.5 は場の写しが要るので今は使わない)。 */
    static float forestPower(ServerLevel level, BlockPos pos) {
        return PhaseData.get(level).isOvergrownChunk(new ChunkPos(pos)) ? 1.0F : 0.0F;
    }

    // ---------------------------------------------------------------- /ov weather(確かめ用。OP 専用)

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ov")
                .then(Commands.literal("weather")
                        .requires(source -> source.hasPermission(2))
                        .executes(WorldWeather::status)
                        .then(Commands.literal("fog").executes(c -> force(c, "fog", 1.0D))
                                .then(Commands.argument("level", DoubleArgumentType.doubleArg(0.0D, 1.0D))
                                        .executes(c -> force(c, "fog", DoubleArgumentType.getDouble(c, "level")))))
                        .then(Commands.literal("calm").executes(c -> force(c, "calm", 1.0D))
                                .then(Commands.argument("level", DoubleArgumentType.doubleArg(0.0D, 1.0D))
                                        .executes(c -> force(c, "calm", DoubleArgumentType.getDouble(c, "level")))))
                        .then(Commands.literal("gale").executes(c -> force(c, "gale", 1.0D))
                                .then(Commands.argument("level", DoubleArgumentType.doubleArg(0.0D, 1.0D))
                                        .executes(c -> force(c, "gale", DoubleArgumentType.getDouble(c, "level")))))
                        // 確かめ用(11-17 見た目の側): 雷雨の前触れ・雷雨の後の、見た目へ送る値だけを強制する(天気と胞子の場は変えない)。
                        .then(Commands.literal("pre").executes(c -> force(c, "pre", 1.0D))
                                .then(Commands.argument("level", DoubleArgumentType.doubleArg(0.0D, 1.0D))
                                        .executes(c -> force(c, "pre", DoubleArgumentType.getDouble(c, "level")))))
                        .then(Commands.literal("after").executes(c -> force(c, "after", 1.0D))
                                .then(Commands.argument("level", DoubleArgumentType.doubleArg(0.0D, 1.0D))
                                        .executes(c -> force(c, "after", DoubleArgumentType.getDouble(c, "level")))))
                        .then(Commands.literal("clear").executes(WorldWeather::clear))
                        .then(Commands.literal("strike").executes(c -> strike(c, false))
                                .then(Commands.literal("forest").executes(c -> strike(c, true)))
                                .then(Commands.literal("ground").executes(c -> strike(c, false)))
                                // 確かめ用(11-16 見た目の側): 雲に関係なく、打った人の向いている先 150 ブロックに「森への落雷」の知らせを 1 通(見た目だけ)。
                                .then(Commands.literal("here").executes(WorldWeather::strikeHere)))));
    }

    private static int force(CommandContext<CommandSourceStack> ctx, String what, double level) {
        switch (what) {
            case "fog" -> PrevailingWind.forcedFog = level;
            case "calm" -> PrevailingWind.forcedCalm = level;
            case "pre" -> forcedPre = level;
            case "after" -> forcedAfter = level;
            default -> PrevailingWind.forcedGale = level;
        }
        sendAll(ctx.getSource().getServer());
        ctx.getSource().sendSuccess(() -> Component.literal("天気を強制: " + what + " = " + String.format("%.2f", level)
                + "(/ov weather clear で戻す。再起動で消える)"), true);
        return 1;
    }

    private static int strike(CommandContext<CommandSourceStack> ctx, boolean forest) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        String r = StormLightning.debugStrike(ctx.getSource().getPlayerOrException(), forest);
        ctx.getSource().sendSuccess(() -> Component.literal(r), false);
        return 1;
    }

    /**
     * {@code /ov weather strike here}(確かめ用。11-16): 雲に関係なく、打った人の向いている先 150 ブロックに forest = 1 の
     * {@code CloudStrikePayload} を、打った人にだけ 1 通送る。見た目だけ(胞子は吹き上げない・雷のエンティティも出さない)。
     * 森の光と吹き上がりの見た目を、近くで確かめるための口({@code /ov weather strike forest} は遠い雲を選ぶことが多い)。
     */
    private static int strikeHere(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level = player.serverLevel();
        net.minecraft.world.phys.Vec3 look = player.getLookAngle();
        double len = Math.max(1.0e-3, Math.sqrt(look.x * look.x + look.z * look.z));
        double x = player.getX() + look.x / len * 150.0;
        double z = player.getZ() + look.z / len * 150.0;
        int bx = net.minecraft.util.Mth.floor(x);
        int bz = net.minecraft.util.Mth.floor(z);
        boolean loaded = level.hasChunk(bx >> 4, bz >> 4);
        int hitY = loaded ? level.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz) : Integer.MIN_VALUE;
        int groundY = loaded ? level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz) : Integer.MIN_VALUE;
        PacketDistributor.sendToPlayer(player, new com.yayuy.overgrownvegetation.network.CloudStrikePayload(
                0L, x, z, hitY, groundY, CloudField.LOW_BASE, 1.0F, level.random.nextLong()));
        ctx.getSource().sendSuccess(() -> Component.literal(String.format("落雷(見た目だけ・森): (%d, %d) 当たった高さ %s・地面 %s", bx, bz,
                loaded ? String.valueOf(hitY) : "読み込まれていない", loaded ? String.valueOf(groundY) : "-")), false);
        return 1;
    }

    private static int clear(CommandContext<CommandSourceStack> ctx) {
        clearForced();
        sendAll(ctx.getSource().getServer());
        ctx.getSource().sendSuccess(() -> Component.literal("天気の強制を消した(予定どおりに戻る)"), true);
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        ServerLevel ow = server.overworld();
        WeatherPayload p = compute(ow);
        PrevailingWind.Weather w = WindWeatherData.get(server).weather();
        long seed = WindWeatherData.windSeed(server);
        long day = Math.floorDiv(ow.getDayTime(), (long) PrevailingWind.DAY);
        StringBuilder days = new StringBuilder();
        for (long d = day; d < day + 7; d++) {
            days.append(d == day ? "今日" : "+" + (d - day)).append(':')
                    .append(PrevailingWind.isStrongDay(seed, d, w) ? "強風" : PrevailingWind.isCalmDay(seed, d, w) ? "凪" : "-").append(' ');
        }
        String forced = (PrevailingWind.forcedFog >= 0 ? " 濃霧" : "") + (PrevailingWind.forcedCalm >= 0 ? " 凪" : "")
                + (PrevailingWind.forcedGale >= 0 ? " 強風" : "") + (forcedPre >= 0 ? " 前触れ" : "") + (forcedAfter >= 0 ? " 雷雨の後" : "");
        ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                "今の天気: 濃霧 %.2f(%s)・凪 %.2f・強風 %.2f・雷雨 %.2f・雷雨の前触れ %.2f・雷雨の後 %.2f・残り %d tick%n地上の風 %.2f・上空の風 %.2f%n%s%n日の種類(雨の印は今のもの): %s%s",
                p.fog(), p.fogKind() == 1 ? "雨上がり" : "朝方", p.calm(), p.gale(), p.thunder(), p.preStorm(), p.stormAfter(), p.ticksLeft(),
                SAMPLE.ground(), SAMPLE.strength, StormLightning.describe(), days, forced.isEmpty() ? "" : "\n強制中:" + forced)), false);
        return 1;
    }

    /** 強制を消す(/ov weather clear・サーバーが止まるとき・GameTest の後)。 */
    public static void clearForced() {
        PrevailingWind.forcedFog = -1;
        PrevailingWind.forcedCalm = -1;
        PrevailingWind.forcedGale = -1;
        forcedPre = -1;
        forcedAfter = -1;
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        clearForced();
    }
}
