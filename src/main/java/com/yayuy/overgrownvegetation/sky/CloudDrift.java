package com.yayuy.overgrownvegetation.sky;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.SkyTable;
import com.yayuy.overgrownvegetation.network.CloudDriftPayload;
import com.yayuy.overgrownvegetation.spore.field.PrevailingWind;
import com.yayuy.overgrownvegetation.spore.field.WindWeatherData;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 雲を流す風(2026-11-08 雲の段階 1「風を 1 つに」)。設定では世界に風は 1 つなので、雲も胞子と同じ卓越風
 * {@link PrevailingWind}(シード・暦・天気の印で決まる。前線で回り、昼夜で振れ、雷雨で嵐)で流す。
 * 前の雲の風(10-18 の「時刻で回る 3 つの風」、{@link CloudField#legacyOffsetX})は胞子の風と別の向きに吹いていた。
 *
 * <p>卓越風は天気の履歴で決まり、閉じた式で積分できないので、<b>サーバーが積分して覚え</b>(この SavedData。ワールドを閉じても続く)、
 * 5 秒ごととワールドに入ったときにクライアントへ送る({@link CloudDriftPayload})。クライアントは届いた値から速さで先読みする
 * ({@code client.sky.CloudDriftClient})。雲の形と量は {@link CloudField} だけが決め、ここは「場をどれだけ流したか」だけを持つ。
 *
 * <p>雲の速さ = 向き × clamp(強さ, {@code sky.json wind.minStrength}, {@code maxStrength}) × {@code blocksPerTick}。雲の高さの風は
 * 地上の夜の凪では止まらないので下限を付けた(胞子の場の高い所の風 {@code highMinStrength} と同じ考え)。地上の突風
 * ({@code client.atmosphere.GustField})は地表の局地の風なので雲は動かさない。数値は仮決め。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class CloudDrift extends SavedData {

    public static final String ID = "overgrown_vegetation_cloud_drift";
    public static final SavedData.Factory<CloudDrift> FACTORY = new SavedData.Factory<>(CloudDrift::new, (tag, registries) -> load(tag), null);

    /** 風を引き直して積分する間(tick)と、クライアントへ送る間(tick)。 */
    static final int STEP = 20;
    static final int SEND = 100;

    private double offsetX;
    private double offsetZ;
    private long lastTick = Long.MIN_VALUE;
    /** 今の速さ(ブロック/tick。保存しない)。 */
    private double velX;
    private double velZ;

    private CloudDrift() {
    }

    private static CloudDrift load(CompoundTag tag) {
        CloudDrift d = new CloudDrift();
        d.offsetX = tag.getDouble("offsetX");
        d.offsetZ = tag.getDouble("offsetZ");
        d.lastTick = tag.contains("lastTick") ? tag.getLong("lastTick") : Long.MIN_VALUE;
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putDouble("offsetX", offsetX);
        tag.putDouble("offsetZ", offsetZ);
        tag.putLong("lastTick", lastTick);
        return tag;
    }

    /** 今のサーバーの雲の流れの値(サーバーが動いている間)。 */
    private static volatile CloudDrift active;

    /**
     * サーバーの雲の流れ(11-08 仕上げ 6): 積分した距離 + 今の速さ × 経った時間。サーバーの {@link CloudField} が読む
     * (専用サーバーでも、クライアントへ送る値と同じ雲の位置になる)。
     */
    public static final CloudField.Drift SERVER_DRIFT = new CloudField.Drift() {
        @Override
        public double x(double t) {
            CloudDrift d = active;
            return d == null || d.lastTick == Long.MIN_VALUE ? CloudField.legacyOffsetX(t) : d.offsetX + d.velX * (t - d.lastTick);
        }

        @Override
        public double z(double t) {
            CloudDrift d = active;
            return d == null || d.lastTick == Long.MIN_VALUE ? CloudField.legacyOffsetZ(t) : d.offsetZ + d.velZ * (t - d.lastTick);
        }
    };

    public static CloudDrift get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    /**
     * 雲の模様の種(11-08 ユーザー決定 Q2「ワールドのシードで変える」): ワールドのシードを混ぜた値(シードそのものはクライアントへ渡さない)。
     * サーバーの {@link CloudField} も同じ種を使う(段階 2 の雷で同じ雲の位置が要る)。
     */
    public static long cloudSeed(MinecraftServer server) {
        return PrevailingWind.hash(server.overworld().getSeed(), 0xC10D5EEDL);
    }

    /** 雲の速さ(ブロック/tick)を卓越風から。 */
    static void velocity(MinecraftServer server, ServerLevel level, double[] out) {
        PrevailingWind.Sample s = new PrevailingWind.Sample();
        PrevailingWind.sample(WindWeatherData.windSeed(server), level.getDayTime(), WindWeatherData.get(server).weather(), s);
        SkyTable sky = DesignData.sky();
        double strength = Math.max(sky.windMinStrength, Math.min(sky.windMaxStrength, s.strength));
        out[0] = s.dirX * strength * sky.windBlocksPerTick;
        out[1] = s.dirZ * strength * sky.windBlocksPerTick;
    }

    private void step(MinecraftServer server, ServerLevel level) {
        long now = level.getGameTime();
        if (lastTick == Long.MIN_VALUE || now < lastTick) {
            lastTick = now;
        }
        long dt = Math.min(now - lastTick, 1200L); // 長く止まっていた後(読み込み直後)に一気に飛ばさない
        // 区間の前の速さで進めてから、今の速さを引き直す(台形にしない: 5 秒で風はほとんど変わらない)
        offsetX += velX * dt;
        offsetZ += velZ * dt;
        lastTick = now;
        double[] v = new double[2];
        velocity(server, level, v);
        velX = v[0];
        velZ = v[1];
        if (dt > 0) {
            setDirty();
        }
    }

    private CloudDriftPayload payload(long gameTime) {
        double dt = gameTime - lastTick;
        CloudShape shape = CloudShape.fromDesign(); // 雲の形はサーバーの設計値(11-08 仕上げ 8)
        return new CloudDriftPayload(gameTime, offsetX + velX * dt, offsetZ + velZ * dt, (float) velX, (float) velZ, CloudField.seed(),
                shape.style(), shape.coverage(), shape.floorThickness(), shape.floorFlatness(), shape.storms(), shape.stormChance(), shape.giantChance(), shape.footScale(),
                shape.syncRain(), shape.rainDays(), shape.thunderShare(), shape.preRainTicks(), shape.realistic(), shape.stormShare(), shape.big(), shape.nest(),
                CloudWeather.serverActive());
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) {
            return;
        }
        long now = level.getGameTime();
        CloudDrift d = get(level.getServer());
        CloudField.setSeed(cloudSeed(level.getServer())); // 同じなら何もしない
        if (active != d) {
            active = d;
            CloudField.setServerDrift(SERVER_DRIFT);
        }
        if (now % STEP == 0 || d.lastTick == Long.MIN_VALUE) {
            d.step(level.getServer(), level);
        }
        if (now % SEND == 0) {
            CloudDriftPayload p = d.payload(now);
            for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
                PacketDistributor.sendToPlayer(player, p);
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event) {
        active = null;
        CloudField.setServerDrift(null);
    }

    @SubscribeEvent
    public static void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            sendTo(player);
        }
    }

    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            sendTo(player);
        }
    }

    private static void sendTo(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        ServerLevel overworld = server.overworld();
        CloudField.setSeed(cloudSeed(server));
        CloudDrift d = get(server);
        if (d.lastTick == Long.MIN_VALUE) {
            d.step(server, overworld);
        }
        PacketDistributor.sendToPlayer(player, d.payload(overworld.getGameTime()));
    }

    /** 今の雲の速さ(ブロック/tick。開発用の表示)。 */
    public double velX() {
        return velX;
    }

    public double velZ() {
        return velZ;
    }
}
