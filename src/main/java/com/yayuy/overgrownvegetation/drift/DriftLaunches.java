package com.yayuy.overgrownvegetation.drift;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import com.yayuy.overgrownvegetation.registry.ModDrift;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 漂流する擬態 Mob を流す口(2026-11-20)。森が汚染された岸からバイオマスを払って流す。
 *
 * <p><b>今は司令塔の外の仮の考え手</b>: {@code director/} を触れない間(区切り ① 待ち)、ここで {@link DriftTable.Launch#intervalDays} ごとに
 * 考え、{@link DriftTable.Launch#chance} で流す。払いは {@link DriftCosts}(今は仮の払い手)。① の後は、司令塔が考える回のひとつとして
 * {@link #tryLaunch} を呼ぶ形へ移す(ここの考え手は {@link #selfThinking} を false にして止める)。
 *
 * <p>流す岸: プレイヤーのまわり {@link DriftTable.Launch#scanChunks} チャンクの、過繁茂の台帳に載った読み込み済みのチャンクで、
 * 海面の水の列が陸と接し、そこから風下へ {@link DriftTable.Launch#minOpenWater} ブロック以上水が続き、その先 {@link DriftTable.Launch#landRange}
 * までに陸がある(または読み込まれていない所に届いた = 先は分からない)所。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class DriftLaunches {

    private DriftLaunches() {
    }

    /** ここで考えて流す(司令塔へ移したら false)。 */
    public static volatile boolean selfThinking = true;

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!selfThinking || !(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        DriftTable t = DesignData.drift();
        long every = Math.max(200L, (long) (t.launch.intervalDays * 24000.0D));
        if (!t.enabled || level.getGameTime() % every != 0) return;
        if (PhaseData.get(level).getPhase() < t.launch.minPhase) return;
        if (level.getRandom().nextDouble() >= t.launch.chance) return;
        tryLaunch(level, level.getRandom());
    }

    /** 流せるだけ流す(上限・岸・払い)。流した数を返す。司令塔から呼ぶ口。 */
    public static int tryLaunch(ServerLevel level, RandomSource random) {
        DriftTable.Launch t = DesignData.drift().launch;
        int room = t.maxAlive - alive(level);
        int n = Math.min(room, t.perLaunch);
        int done = 0;
        for (int k = 0; k < n; k++) {
            BlockPos site = findSite(level, random);
            if (site == null) break;
            if (launch(level, site) == null) break;
            done++;
        }
        return done;
    }

    /** 海の上にいる数(姿 + 帳面)。 */
    public static int alive(ServerLevel level) {
        return level.getEntities(ModDrift.DRIFT_MIMIC.get(), e -> e.isAlive()).size() + DriftLedger.count(level);
    }

    /**
     * 水の列 {@code water}(y は水面の段)から 1 体流す。払えなければ流さず null。
     */
    @Nullable
    public static DriftMimicEntity launch(ServerLevel level, BlockPos water) {
        double price = DriftCosts.launchPrice();
        if (!DriftCosts.pay(level, price, "drift_launch")) return null;
        DriftMimicEntity e = spawnAt(level, water);
        if (e != null) {
            com.yayuy.overgrownvegetation.director.ForestEconomy.markPaid(e, price); // 森の中で倒されたら一部が戻る(沖では戻らない)
            DriftStats.launched++;
        }
        return e;
    }

    @Nullable
    static DriftMimicEntity spawnAt(ServerLevel level, BlockPos water) {
        DriftMimicEntity e = ModDrift.DRIFT_MIMIC.get().create(level);
        if (e == null) return null;
        e.moveTo(water.getX() + 0.5D, water.getY() + 0.6D, water.getZ() + 0.5D, level.getRandom().nextFloat() * 360.0F, 0.0F);
        e.finalizeSpawn(level, level.getCurrentDifficultyAt(water), MobSpawnType.EVENT, null);
        e.born = level.getGameTime();
        level.addFreshEntity(e);
        return e;
    }

    // ------------------------------------------------------------ 岸を選ぶ

    private static final double[] WIND = new double[2];

    /** 流す岸を探す(見つからなければ null)。y は水面の段。 */
    @Nullable
    public static BlockPos findSite(ServerLevel level, RandomSource random) {
        DriftTable.Launch t = DesignData.drift().launch;
        List<ServerPlayer> players = new ArrayList<>();
        for (ServerPlayer p : level.players()) if (!p.isSpectator()) players.add(p);
        if (players.isEmpty()) return null;
        DriftWind.now(level, null, WIND);
        double wl = Math.hypot(WIND[0], WIND[1]);
        if (wl < 1e-6) return null; // 無風の日は流さない
        double wx = WIND[0] / wl, wz = WIND[1] / wl;
        PhaseData pd = PhaseData.get(level);
        int sea = level.getSeaLevel() - 1;
        for (int i = 0; i < t.samples; i++) {
            ServerPlayer p = players.get(random.nextInt(players.size()));
            int cx = (p.getBlockX() >> 4) + random.nextInt(t.scanChunks * 2 + 1) - t.scanChunks;
            int cz = (p.getBlockZ() >> 4) + random.nextInt(t.scanChunks * 2 + 1) - t.scanChunks;
            if (!pd.isOvergrownKey(ChunkPos.asLong(cx, cz))) continue;
            if (level.getChunkSource().getChunkNow(cx, cz) == null) continue;
            int x = (cx << 4) + random.nextInt(16), z = (cz << 4) + random.nextInt(16);
            BlockPos site = siteAt(level, x, z, sea, wx, wz);
            if (site != null) return site;
        }
        return null;
    }

    /** 列 (x, z) が流す岸に向いた水の列なら、流し出す深い水の列を返す({@code sea} の段の近くの水面で見る)。 */
    @Nullable
    public static BlockPos siteAt(ServerLevel level, int x, int z, int sea, double wx, double wz) {
        return siteAt(level, x, z, sea, wx, wz, DesignData.drift().drift.minDepth);
    }

    /**
     * {@link #siteAt} の深さを渡す版。岸の水の列から風下へ、体の半径 + 鉤の届く距離の中で {@code minDepth} 以上の深さの列を
     * 流し出す所にする(形 B 改 3/4 は水中に 約 10 の房を垂らす。浅瀬には出さない)。
     */
    @Nullable
    public static BlockPos siteAt(ServerLevel level, int x, int z, int sea, double wx, double wz, int minDepth) {
        DriftTable.Launch t = DesignData.drift().launch;
        int wy = DriftShore.waterSurfaceY(level, new BlockPos(x, sea, z));
        if (wy == Integer.MIN_VALUE) return null;
        boolean shore = false;
        for (int dx = -2; dx <= 2 && !shore; dx++)
            for (int dz = -2; dz <= 2 && !shore; dz++)
                if (DriftShore.column(level, x + dx, z + dz, wy) == DriftShore.Col.LAND) shore = true;
        if (!shore) return null;
        int water = 0;
        for (int r = 1; r <= t.landRange; r++) {
            int px = Mth.floor(x + 0.5D + wx * r), pz = Mth.floor(z + 0.5D + wz * r);
            DriftShore.Col c = DriftShore.column(level, px, pz, wy);
            if (c == DriftShore.Col.WATER) {
                water++;
                continue;
            }
            if (water < t.minOpenWater) return null; // 入り江・岸沿い
            // 陸に届く・先は分からない: 風下の深い水の列から流す
            DriftTable.Drift d = DesignData.drift().drift;
            int reach = (int) Math.ceil(d.bodyRadius + d.gripReach);
            for (int k = 0; k <= Math.min(reach, r - 1); k++) {
                int qx = Mth.floor(x + 0.5D + wx * k), qz = Mth.floor(z + 0.5D + wz * k);
                if (DriftShore.depth(level, qx, qz, wy, minDepth) >= minDepth) return new BlockPos(qx, wy, qz);
            }
            return null; // 岸の近くに深い水が無い
        }
        return null; // 届く範囲に陸が無い
    }

    /** 乗せる Mob の出し方(司令塔の口から)。出せなければ null。 */
    @Nullable
    static Mob spawnRider(ServerLevel level, net.minecraft.world.entity.EntityType<? extends Mob> type, BlockPos at) {
        Mob m = type.create(level);
        if (m == null) return null;
        m.moveTo(at.getX() + 0.5D, at.getY() + 1.0D, at.getZ() + 0.5D, 0.0F, 0.0F);
        m.finalizeSpawn(level, level.getCurrentDifficultyAt(at), MobSpawnType.EVENT, null);
        level.addFreshEntity(m);
        return m;
    }
}
