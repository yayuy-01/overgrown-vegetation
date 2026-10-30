package com.yayuy.overgrownvegetation.td;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.network.TdHordePayload;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * TD の<b>行進中の仮想の群れ</b>を、近くのプレイヤーへ送る(2026-09-25、ユーザー指定「大群が迫ってくる演出を TD に組み込みたい」の 2 段目。
 * {@link TdHordePayload})。クライアントは群れの一員を遠景の身代わりで描く。
 *
 * <ul>
 *     <li>帳簿のたび(20 tick ごと)、実体化のあとに、各プレイヤーから水平に {@value #SEND_RADIUS} の中の群れを全部送る
 *     (差分ではない。取りこぼしても次で揃う。実体になった群れはその帳簿で一覧から消えるので、身代わりと本物の入れ替わりが同じ tick)。</li>
 *     <li>向きはターゲットへ(止まっていても群れはそちらを向く)。速さは侵攻中で一時停止していなければ {@code speed}
 *     (1 tick あたり)、止まる輪({@code TdTicker#holdRing})まで。予告中・一時停止中・輪に着いた群れは 0。
 *     置けなかった群れが輪に沿って横へ歩く間({@code TdTicker#sidestep})は、その接線の向き・速さ・残りの弧の長さ。</li>
 *     <li>1 人に送る群れは {@link TdHordePayload#MAX_SWARMS} まで(多ければ近い順)。</li>
 *     <li>群れが無くなった・侵攻が終わった・範囲の外へ出た・別のディメンションへ行ったプレイヤーには、空の一覧を 1 回送る(消える)。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class TdHordeSync {

    /** 送る群れの距離(プレイヤーから水平、ブロック)。描画距離 32 チャンク = 512 ブロックまで届く。 */
    static final double SEND_RADIUS = 512.0D;

    /** 空でない一覧を送ってあるプレイヤー(空の一覧を送ったら外す)。 */
    private static final Set<UUID> SENT = new HashSet<>();

    /** 帳簿のたびに呼ぶ(実体化のあと)。 */
    static void send(ServerLevel level, TdData.Event event, TdTable table) {
        List<TdHordePayload.Swarm> all = views(event, table);
        double radiusSq = SEND_RADIUS * SEND_RADIUS;
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            List<TdHordePayload.Swarm> mine = new ArrayList<>();
            if (player.level() == level) {
                for (TdHordePayload.Swarm s : all) {
                    double dx = s.x() - player.getX();
                    double dz = s.z() - player.getZ();
                    if (dx * dx + dz * dz <= radiusSq) {
                        mine.add(s);
                    }
                }
            }
            if (mine.isEmpty()) {
                if (SENT.remove(player.getUUID())) {
                    PacketDistributor.sendToPlayer(player, new TdHordePayload(List.of()));
                }
                continue;
            }
            if (mine.size() > TdHordePayload.MAX_SWARMS) {
                // 受け取る側の上限を超えると接続ごと切られる。近い順に残す。
                double px = player.getX();
                double pz = player.getZ();
                mine.sort(java.util.Comparator.comparingDouble(s -> (s.x() - px) * (s.x() - px) + (s.z() - pz) * (s.z() - pz)));
                mine = new ArrayList<>(mine.subList(0, TdHordePayload.MAX_SWARMS));
            }
            PacketDistributor.sendToPlayer(player, new TdHordePayload(mine));
            SENT.add(player.getUUID());
        }
    }

    /** 侵攻が無いとき(帳簿の間隔で)呼ぶ。群れを送ってあったプレイヤーへ空の一覧を送る。 */
    static void clearAll(MinecraftServer server) {
        if (SENT.isEmpty()) {
            return;
        }
        for (UUID id : SENT) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player != null) {
                PacketDistributor.sendToPlayer(player, new TdHordePayload(List.of()));
            }
        }
        SENT.clear();
    }

    /** いまの群れの一覧(クライアントへ送る形)。 */
    static List<TdHordePayload.Swarm> views(TdData.Event event, TdTable table) {
        boolean moving = event.state() == TdData.State.ACTIVE && !event.paused();
        double tx = event.target().getX() + 0.5D;
        double tz = event.target().getZ() + 0.5D;
        double hold = TdTicker.holdRing(table);
        List<TdHordePayload.Swarm> out = new ArrayList<>(event.virtual.size());
        for (TdData.VirtualSwarm v : event.virtual) {
            TdHordePayload.Swarm view = view(v, tx, tz, hold, moving);
            if (view != null) {
                out.add(view);
            }
        }
        return out;
    }

    /** 群れ 1 つ(種の頭数が 1 体も無ければ null)。 */
    @Nullable
    static TdHordePayload.Swarm view(TdData.VirtualSwarm v, double tx, double tz, double hold, boolean moving) {
        List<TdHordePayload.Count> counts = counts(v);
        if (counts.isEmpty()) {
            return null;
        }
        double dx = tx - v.x;
        double dz = tz - v.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        float dirX = dist > 1.0E-6D ? (float) (dx / dist) : 0.0F;
        float dirZ = dist > 1.0E-6D ? (float) (dz / dist) : 1.0F;
        if (!Double.isNaN(v.sidestepAngle) && dist > 1.0E-6D) {
            // 輪に沿って横へ歩いている: 向きは接線(角度が増える向き = (−sin, cos))、残りは弧の長さ。
            double angle = Math.atan2(-dz, -dx);
            double left = TdTicker.angleBetween(angle, v.sidestepAngle);
            double sign = left >= 0.0D ? 1.0D : -1.0D;
            float speed = moving ? (float) TdTicker.sidestepSpeed(v) : 0.0F;
            float travel = moving ? (float) (Math.abs(left) * Math.max(dist, hold)) : 0.0F;
            return new TdHordePayload.Swarm(v.id.hashCode(), v.x, (float) v.y, v.z, (float) (-Math.sin(angle) * sign),
                    (float) (Math.cos(angle) * sign), speed, travel, v.air, counts);
        }
        boolean marching = moving && dist > hold && v.speed > 0.0D;
        float speed = marching ? (float) v.speed : 0.0F;
        float travel = marching ? (float) (dist - hold) : 0.0F;
        return new TdHordePayload.Swarm(v.id.hashCode(), v.x, (float) v.y, v.z, dirX, dirZ, speed, travel, v.air, counts);
    }

    /**
     * 群れの種ごとの頭数を送る形で(並びは編成の行の順)。クライアントの隊列と、サーバーが空の群れを湧かせる位置
     * ({@link HordeFormation})はこの並びから作るので、両方で同じものを使う。引けない種・0 体は飛ばす。
     */
    static List<TdHordePayload.Count> counts(TdData.VirtualSwarm v) {
        List<TdHordePayload.Count> counts = new ArrayList<>(v.counts.size());
        for (Object2IntMap.Entry<String> e : v.counts.object2IntEntrySet()) {
            if (e.getIntValue() <= 0) {
                continue;
            }
            Optional<EntityType<?>> type = EntityType.byString(e.getKey());
            type.ifPresent(t -> counts.add(new TdHordePayload.Count(t, e.getIntValue())));
        }
        return counts;
    }

    /** 群れの番号(クライアントが同じ群れを見分ける・隊列の乱数の種)。 */
    static int viewId(TdData.VirtualSwarm v) {
        return v.id.hashCode();
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        SENT.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        SENT.clear();
    }

    private TdHordeSync() {
    }
}
