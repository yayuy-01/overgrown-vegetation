package com.yayuy.overgrownvegetation.base.road;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.ally.AllyEntities;
import com.yayuy.overgrownvegetation.ally.SoldierEntity;
import com.yayuy.overgrownvegetation.ally.SoldierKind;
import com.yayuy.overgrownvegetation.base.build.BaseSite;
import com.yayuy.overgrownvegetation.base.build.BaseSites;
import com.yayuy.overgrownvegetation.data.BaseRoadConfig;
import com.yayuy.overgrownvegetation.data.DesignData;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 工事の先頭の工兵(順 2 の着手順 7、2026-10-08)。<b>ユーザー指定(10-08)</b>: 工事は離れていても進み、工事の先頭が近くにあれば、そこで工兵が敷いているのが見える。
 *
 * <p>Claude 仮決め: 工事が進んでいる端(陥落しておらず、貯蓄が下限より上)の先頭が、エンティティの動くチャンクにあって、プレイヤーが
 * {@link #VIEW_RANGE} ブロック以内にいる間だけ、工兵 {@link #CREW_SIZE} 人を先頭の脇に出す(守備兵の名簿には入れない・基地の番号を持たない)。
 * 先頭に合わせて歩いて付いていき(離れすぎたら飛ぶ)、ときどき道具を振る。条件が外れたら消す。保存はしない —— 読み込み直しや
 * 再起動で残った工兵は、世界に入るときに消す({@link #CREW_TAG})。工事そのものは時間で進み、工兵がいてもいなくても同じ速さ。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class RoadCrews {

    /** 工兵の印(残った工兵を世界に入るときに消す)。 */
    public static final String CREW_TAG = "overgrown_vegetation.road_crew";
    /** 先頭から何ブロック以内にプレイヤーがいれば出すか。 */
    static final double VIEW_RANGE = 80.0D;
    /** 1 つの端に出す工兵の数。 */
    static final int CREW_SIZE = 2;
    /** 先頭がこれより離れたら飛んで付いていく。 */
    private static final double TELEPORT_RANGE = 24.0D;

    /** (道の番号 × 2 + 端)→ 出している工兵。 */
    private static final Long2ObjectOpenHashMap<List<UUID>> CREWS = new Long2ObjectOpenHashMap<>();
    /** 今いてよい工兵(これ以外の印の付いた工兵は世界に入るときに消す)。 */
    private static final Set<UUID> LIVE = new HashSet<>();

    private static long key(AlliedRoad r, boolean fromA) {
        return ((long) r.id << 1) | (fromA ? 0L : 1L);
    }

    /** 工事を進めた後に呼ぶ(20 tick おき)。 */
    static void tick(MinecraftServer server) {
        ServerLevel level = server.overworld();
        RoadLedger ledger = RoadLedger.get(server);
        BaseSites data = BaseSites.get(server);
        BaseRoadConfig cfg = DesignData.defense().baseRoad();
        Set<Long> wanted = new HashSet<>();
        for (AlliedRoad r : ledger.roads()) {
            if (r.done()) {
                continue;
            }
            for (boolean fromA : new boolean[]{true, false}) {
                BaseSite site = data.byId(fromA ? r.siteA : r.siteB);
                if (site == null || !RoadWorks.canPay(site, cfg)) {
                    continue; // 進んでいない端には出さない(止まっていることが見て分かる)
                }
                int f = r.frontier(fromA);
                BlockPos front = new BlockPos(r.pointX(f), (int) Math.floor(r.surfaceY(f)), r.pointZ(f));
                if (!level.isPositionEntityTicking(front) || !playerNear(level, front)) {
                    continue;
                }
                long k = key(r, fromA);
                wanted.add(k);
                keep(level, r, fromA, f, front, k);
            }
        }
        // 要らなくなった工兵を消す
        for (long k : new ArrayList<>(CREWS.keySet())) {
            if (!wanted.contains(k)) {
                dismiss(level, CREWS.remove(k));
            }
        }
    }

    private static boolean playerNear(ServerLevel level, BlockPos pos) {
        for (ServerPlayer p : level.players()) {
            if (!p.isSpectator() && p.distanceToSqr(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D) <= VIEW_RANGE * VIEW_RANGE) {
                return true;
            }
        }
        return false;
    }

    /** 先頭の脇に工兵をそろえ、付いていかせ、道具を振らせる。 */
    private static void keep(ServerLevel level, AlliedRoad r, boolean fromA, int f, BlockPos front, long k) {
        List<UUID> crew = CREWS.computeIfAbsent(k, x -> new ArrayList<>());
        crew.removeIf(id -> {
            Entity e = level.getEntity(id);
            if (e == null || !e.isAlive()) {
                LIVE.remove(id);
                return true;
            }
            return false;
        });
        // 進む向き(先頭の次の点へ)と、その横
        int next = fromA ? Math.min(r.length() - 1, f + 1) : Math.max(0, f - 1);
        double dx = r.pointX(next) - r.pointX(f);
        double dz = r.pointZ(next) - r.pointZ(f);
        double len = Math.max(1e-6, Math.hypot(dx, dz));
        double nx = -dz / len;
        double nz = dx / len;
        while (crew.size() < CREW_SIZE) {
            int side = crew.size() % 2 == 0 ? 1 : -1;
            BlockPos at = ground(level, front.getX() + (int) Math.round(nx * 2 * side), front.getZ() + (int) Math.round(nz * 2 * side), front.getY());
            SoldierEntity s = AllyEntities.type(SoldierKind.ENGINEER).create(level, null, at, MobSpawnType.EVENT, false, false);
            if (s == null) {
                break;
            }
            s.addTag(CREW_TAG);
            s.restrictTo(front, 6);
            LIVE.add(s.getUUID());
            if (!level.addFreshEntity(s)) {
                LIVE.remove(s.getUUID());
                break;
            }
            crew.add(s.getUUID());
        }
        for (UUID id : crew) {
            if (!(level.getEntity(id) instanceof SoldierEntity s)) {
                continue;
            }
            double d2 = s.distanceToSqr(front.getX() + 0.5D, front.getY(), front.getZ() + 0.5D);
            if (d2 > TELEPORT_RANGE * TELEPORT_RANGE) {
                BlockPos at = ground(level, front.getX(), front.getZ(), front.getY());
                s.teleportTo(at.getX() + 0.5D, at.getY(), at.getZ() + 0.5D);
            } else if (d2 > 9.0D && s.getTarget() == null) {
                s.getNavigation().moveTo(front.getX() + 0.5D, front.getY(), front.getZ() + 0.5D, 0.8D);
            }
            s.restrictTo(front, 6);
            if (s.getTarget() == null) {
                s.getLookControl().setLookAt(front.getX() + 0.5D, front.getY(), front.getZ() + 0.5D);
                if (level.random.nextInt(3) == 0) {
                    s.swing(level.random.nextBoolean() ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND);
                }
            }
        }
    }

    /** 立てる高さ(葉を除いた地面の上。路面より大きく離れていたら路面の上)。 */
    private static BlockPos ground(ServerLevel level, int x, int z, int roadY) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        if (Math.abs(y - roadY) > 6) {
            y = roadY + 1;
        }
        return new BlockPos(x, y, z);
    }

    private static void dismiss(ServerLevel level, List<UUID> crew) {
        if (crew == null) {
            return;
        }
        for (UUID id : crew) {
            LIVE.remove(id);
            Entity e = level.getEntity(id);
            if (e != null) {
                e.discard();
            }
        }
    }

    /** 残った工兵(前の読み込み・前回の起動のもの)は世界に入れない。 */
    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide() && event.getEntity() instanceof SoldierEntity s
                && s.getTags().contains(CREW_TAG) && !LIVE.contains(s.getUUID())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        CREWS.clear();
        LIVE.clear();
    }

    private RoadCrews() {
    }
}
