package com.yayuy.overgrownvegetation.spore.field;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.CorruptionSwitch;
import com.yayuy.overgrownvegetation.corruption.LeyLines;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.SporeFieldTable;
import com.yayuy.overgrownvegetation.network.GiantTreesPayload;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.server.ServerStoppedEvent;
import com.yayuy.overgrownvegetation.port.event.tick.ServerTickEvent;
import com.yayuy.overgrownvegetation.port.network.PacketDistributor;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * まわりの生きている巨大樹を 5 秒ごとに、前と変わっていればプレイヤーへ送る(2026-11-06 巨大樹の改善案 ① 胞子の柱・③ 夜の地脈の光。{@link GiantTreesPayload})。
 *
 * <p>胞子の量は場と<b>同じ規則</b>({@link SporeFieldServer#forEachTreeSource}: 古い順に maxTrees 本・ハイヴノードを倒された木は外す・育ち具合)。
 * 朝の上乗せはクライアントが同じ関数({@link SporeFieldServer#canopyMorningPerDay})で足す(時刻はクライアントも持っている)。
 *
 * <p>地脈の貯蓄は<b>仮の物差し</b>(ハイヴノードの貯蓄はまだ実装されていない。10-22 決定の「貯蓄上限を引き上げる役」が入ったら差し替える):
 * 育ち具合(若木 0 → 成木 1)× ({@value #LEY_FLOOR} + (1 − {@value #LEY_FLOOR}) × 地脈の線への近さ)。近さは根元から線まで 0 ブロックで 1、
 * {@value #LEY_REACH} ブロック以上で 0({@link LeyLines}。セルの真ん中でおよそ 100)。どちらの数も仮決め。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID)
public final class GiantTreeSync {

    private static final int INTERVAL = 100;
    /** 送る範囲(根元までの水平距離、ブロック。描画距離 32 の 4 倍 = 胞子の柱が見える遠さを覆う。仮決め)。 */
    static final int RANGE = 1536;
    /** 地脈の物差しの床(線から遠い成木の度合い。仮決め)。 */
    static final double LEY_FLOOR = 0.35;
    /** 地脈の線への近さが 0 になる距離(ブロック。仮決め)。 */
    static final double LEY_REACH = 96.0;
    private static final Map<UUID, Integer> LAST = new HashMap<>();

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % INTERVAL != 0) {
            return;
        }
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            send(player);
        }
    }

    private static void send(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        boolean running = level.dimension() == Level.OVERWORLD && SporeFieldServer.enabled() && !CorruptionSwitch.isOff();
        int[] xs = new int[0];
        int[] zs = new int[0];
        float[] spore = new float[0];
        float[] ley = new float[0];
        if (running) {
            PhaseData pd = PhaseData.get(level);
            SporeFieldTable table = DesignData.sporeField();
            long now = level.getGameTime();
            double mature = Math.max(1.0E-6, table.giantTreePerDay(Double.MAX_VALUE));
            double young = table.giantTreePerDay(0.0);
            Long2DoubleOpenHashMap sources = new Long2DoubleOpenHashMap();
            SporeFieldServer.forEachTreeSource(pd, now, table, sources::put);
            long[] all = pd.giantTreeOriginSnapshot();
            int n = 0;
            xs = new int[all.length];
            zs = new int[all.length];
            spore = new float[all.length];
            ley = new float[all.length];
            double r2 = (double) RANGE * RANGE;
            for (long tr : all) {
                if (pd.isGiantTreeDead(tr)) {
                    continue;
                }
                int x = (int) (tr >> 32);
                int z = (int) tr;
                double dx = x - player.getX();
                double dz = z - player.getZ();
                if (dx * dx + dz * dz > r2) {
                    continue;
                }
                long born = pd.giantTreeBorn(tr);
                double ageDays = born == Long.MIN_VALUE ? Double.MAX_VALUE : (now - born) / 24000.0D;
                double grown = mature - young <= 1.0E-6 ? 1.0 : (table.giantTreePerDay(ageDays) - young) / (mature - young);
                double near = 1.0 - Math.min(1.0, LeyLines.distanceToLine(level.getSeed(), x, z) / LEY_REACH);
                xs[n] = x;
                zs[n] = z;
                spore[n] = (float) (sources.containsKey(tr) ? sources.get(tr) / mature : 0.0);
                // 2 桁に丸める(育ち具合が毎回わずかに変わって 5 秒ごとに送り直さないように)
                ley[n] = Math.round((float) (Math.max(0.0, Math.min(1.0, grown)) * (LEY_FLOOR + (1.0 - LEY_FLOOR) * near)) * 100.0F) / 100.0F;
                spore[n] = Math.round(spore[n] * 100.0F) / 100.0F;
                n++;
            }
            xs = Arrays.copyOf(xs, n);
            zs = Arrays.copyOf(zs, n);
            spore = Arrays.copyOf(spore, n);
            ley = Arrays.copyOf(ley, n);
        }
        int sign = ((Boolean.hashCode(running) * 31 + Arrays.hashCode(xs)) * 31 + Arrays.hashCode(zs)) * 31
                + Arrays.hashCode(spore) * 17 + Arrays.hashCode(ley) + level.dimension().location().hashCode() * 7;
        Integer last = LAST.get(player.getUUID());
        if (last != null && last == sign) {
            return;
        }
        LAST.put(player.getUUID(), sign);
        PacketDistributor.sendToPlayer(player, new GiantTreesPayload(running, xs, zs, spore, ley));
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        LAST.clear();
    }

    @SubscribeEvent
    public static void onLoggedOut(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        LAST.remove(event.getEntity().getUUID());
    }

    private GiantTreeSync() {
    }
}
