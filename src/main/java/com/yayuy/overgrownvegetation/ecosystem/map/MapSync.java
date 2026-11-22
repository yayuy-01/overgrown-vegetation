package com.yayuy.overgrownvegetation.ecosystem.map;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corpse.G1Infection;
import com.yayuy.overgrownvegetation.infect.GenericInfection;
import com.yayuy.overgrownvegetation.spore.SporeExposureTracker;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.entity.living.BabyEntitySpawnEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * 地図の上の生態系と実物の照らし合わせ(2026-11-21)。<b>台帳が正</b>。
 * <ul>
 *   <li><b>台帳 → 実物</b>: プレイヤーのまわりの升(中心とまわり 8 升・真ん中のチャンクが読み込まれている)を {@link MapTable#syncIntervalTicks} ごとに見て、
 *   台帳で増えた感染(まだ実物に反映していない分)の割合だけ、升の中の実物の動物・村人を<b>消す</b>(11-21 夜 ユーザー決定「まあ消しとこか」:
 *   台帳で感染した分はもう G1 として部隊に入っているので、実物は部隊に連れて行かれた扱い。感染の死にはせず、死体・G1・汎用の感染体・ドロップ・経験値は出さない)。
 *   プレイヤーから見えていない個体・遠い個体から先に消す(目の前で消えるのを減らす)。1 回に 1 升 {@link MapTable#reconcilePerSync} 体まで。全部感染した升は残りも全部。</li>
 *   <li><b>実物 → 台帳</b>: プレイヤーのそばで実物が感染した({@link #onRealInfected}。汎用の感染体になった・死体になった所から呼ばれる)→ 感染 +1(実物に反映済み)。
 *   感染でなく死んだ → 欠け +1(森の外では戻る)。生まれた → 欠け −1。消した個体は死なないので、ここには来ない(二重に数えない)。</li>
 * </ul>
 * 数えるのは動物({@link Animal})と村人({@link AbstractVillager})。植物側・この MOD の生き物・飼われている動物は数えない。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE)
public final class MapSync {

    private MapSync() {
    }

    /** 数える生き物か。 */
    public static boolean tracked(Entity e) {
        if (!(e instanceof Animal) && !(e instanceof AbstractVillager)) return false;
        if (e instanceof net.minecraft.world.entity.TamableAnimal ta && ta.isTame()) return false;
        if (OvergrownVegetation.MODID.equals(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getNamespace())) return false;
        return !(e instanceof LivingEntity le) || !SporeExposureTracker.isPlantSide(le);
    }

    // ---------------------------------------------------------------- 実物 → 台帳

    /** 実物が感染体に変わった(汎用の感染体になった・死体になった)。{@code GenericInfection}・{@code CorpseCollapse} から呼ばれる。 */
    public static void onRealInfected(ServerLevel level, LivingEntity entity) {
        if (level.dimension() != Level.OVERWORLD || !tracked(entity) || !MapEcology.active(level)) return;
        try {
            MapEcology.addInfected(level, MapEcology.cellOf(entity.getBlockX() >> 4, entity.getBlockZ() >> 4), 1.0D, true);
        } catch (RuntimeException e) {
            OvergrownVegetation.LOGGER.error("地図の上の生態系: 実物の感染を台帳へ返せなかった", e);
        }
    }

    /** 感染でない死 → 欠け +1。感染の死は {@link #onRealInfected} が数える。 */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDeath(LivingDeathEvent event) {
        if (event.isCanceled()) return;
        LivingEntity e = event.getEntity();
        if (!(e.level() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        if (!tracked(e) || !MapEcology.active(level)) return;
        if (G1Infection.isInfectionDeath(e, event.getSource()) || GenericInfection.isTurned(e)) return;
        changeLack(level, e, 1.0D);
    }

    /** 生まれた → 欠け −1。 */
    @SubscribeEvent
    public static void onBaby(BabyEntitySpawnEvent event) {
        if (event.isCanceled() || event.getChild() == null) return;
        Entity child = event.getChild();
        if (!(event.getParentA().level() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        if (!tracked(child) || !MapEcology.active(level)) return;
        changeLack(level, event.getParentA(), -1.0D);
    }

    private static void changeLack(ServerLevel level, Entity at, double n) {
        MapTable t = MapEcology.table();
        long key = MapEcology.cellOf(at.getBlockX() >> 4, at.getBlockZ() >> 4);
        MapLedger ledger = MapLedger.get(level.getServer());
        MapLedger.Cell c = ledger.get(key);
        long now = MapEcology.now(level);
        double rt = MapEcology.recoveryTicks(t);
        if (c == null) {
            if (n < 0) return; // 満員の升で生まれても増えない
            MapCapacity.Info info = MapEcology.info(level, key);
            if (info.full() < t.barrenBelow) return;
            c = ledger.getOrCreate(key, info);
        } else if (c.full() < t.barrenBelow) {
            return;
        }
        if (n > 0) ledger.addLack(c, n, now, rt);
        else ledger.reduceLack(c, -n, now, rt);
    }

    // ---------------------------------------------------------------- 台帳 → 実物

    /** この照らし合わせの回で見た升(同じ回に 2 人のまわりが重なっても 1 回)。 */
    private static final LongOpenHashSet SEEN = new LongOpenHashSet();
    /** これまでに照らし合わせで消した数(表示用)。 */
    public static long reconciled;

    static void clear() {
        SEEN.clear();
    }

    static void tick(ServerLevel level, MapTable t) {
        if (level.getGameTime() % t.syncIntervalTicks != 3 % t.syncIntervalTicks) return;
        if (!MapEcology.active(level)) return;
        MapLedger ledger = MapLedger.get(level.getServer());
        if (ledger.size() == 0) return;
        SEEN.clear();
        int s = MapEcology.shift();
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator()) continue;
            int pcx = p.getBlockX() >> 4 >> s, pcz = p.getBlockZ() >> 4 >> s;
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++) {
                    long key = ChunkPos.asLong(pcx + dx, pcz + dz);
                    if (!SEEN.add(key)) continue;
                    BlockPosHelper mid = BlockPosHelper.of(key, s);
                    if (level.getChunkSource().getChunkNow(mid.cx, mid.cz) == null) continue;
                    reconcile(level, key, t.reconcilePerSync);
                }
        }
    }

    /** 升の真ん中のチャンク。 */
    private record BlockPosHelper(int cx, int cz) {
        static BlockPosHelper of(long cellKey, int s) {
            int half = (1 << s) / 2;
            return new BlockPosHelper((ChunkPos.getX(cellKey) << s) + half, (ChunkPos.getZ(cellKey) << s) + half);
        }
    }

    /**
     * 1 升を照らし合わせる: 台帳で増えた感染のうち実物に反映していない分の割合(増えた感染 ÷ その前の健康な数)だけ、升の中の実物を消す
     * (部隊に連れて行かれた。プレイヤーから見えていない・遠い個体から)。
     *
     * @return 消した数
     */
    public static int reconcile(ServerLevel level, long cellKey, int cap) {
        MapLedger ledger = MapLedger.get(level.getServer());
        MapLedger.Cell c = ledger.get(cellKey);
        if (c == null) return 0;
        int whole = (int) Math.floor(c.infected + 1.0E-9D);
        int delta = whole - c.synced;
        if (delta <= 0) return 0;
        MapTable t = MapEcology.table();
        double healthy = MapLedger.healthy(c, MapEcology.now(level), MapEcology.recoveryTicks(t));
        double q = Math.min(1.0D, delta / Math.max(1.0D, healthy + delta));
        List<LivingEntity> real = candidates(level, cellKey);
        int want = (int) Math.round(real.size() * q);
        if (healthy < 0.5D) want = real.size(); // 全部感染した升: 残りも全部
        int done = 0;
        if (want > 0 && cap > 0) {
            sortHiddenFarFirst(level, real);
            for (LivingEntity e : real) {
                if (done >= Math.min(want, cap)) break;
                if (removeReal(e)) done++;
            }
        }
        // 反映した分を基準に足す(上限で途中までなら、その割合だけ。実物がいなければ全部反映したことにする)
        int consumed = want <= 0 ? delta : done >= want ? delta : Math.max(done > 0 ? 1 : 0, (int) ((long) delta * done / want));
        c.synced += consumed;
        ledger.markDirty();
        reconciled += done;
        return done;
    }

    /**
     * 升の中の、消してよい実物(読み込まれた分だけ)。名前を付けた・リードでつないだ・乗り物になっている・乗っている個体はプレイヤーのものとして外す
     * (消すと持ち物を失わせるので。数にも入れない)。
     */
    static List<LivingEntity> candidates(ServerLevel level, long cellKey) {
        int s = MapEcology.shift(), size = 16 << s;
        int x0 = ChunkPos.getX(cellKey) << (s + 4), z0 = ChunkPos.getZ(cellKey) << (s + 4);
        AABB box = new AABB(x0, level.getMinBuildHeight(), z0, x0 + size, level.getMaxBuildHeight(), z0 + size);
        List<LivingEntity> out = new ArrayList<>();
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box, e -> e.isAlive() && tracked(e) && !GenericInfection.isTurned(e)
                && !e.hasCustomName() && !e.isVehicle() && !e.isPassenger() && !(e instanceof net.minecraft.world.entity.Mob m && m.isLeashed()))) {
            out.add(e);
        }
        return out;
    }

    /**
     * 消す順(全体管理の希望 11-21 夜): プレイヤーから見えていない個体 → 見えている個体、それぞれいちばん近いプレイヤーから遠い順。
     * 見えている = 128 ブロック以内のプレイヤーの視線の前(向きとの内積 0.5 以上)で、間に遮るものが無い。
     */
    static void sortHiddenFarFirst(ServerLevel level, List<LivingEntity> list) {
        java.util.Map<LivingEntity, double[]> key = new java.util.IdentityHashMap<>();
        for (LivingEntity e : list) {
            double near = Double.MAX_VALUE;
            boolean seen = false;
            for (ServerPlayer p : level.players()) {
                if (p.isSpectator()) continue;
                double d2 = p.distanceToSqr(e);
                near = Math.min(near, d2);
                if (!seen && d2 < 128.0D * 128.0D) {
                    net.minecraft.world.phys.Vec3 to = e.getEyePosition().subtract(p.getEyePosition()).normalize();
                    if (p.getViewVector(1.0F).dot(to) >= 0.5D && p.hasLineOfSight(e)) seen = true;
                }
            }
            key.put(e, new double[]{seen ? 1 : 0, -near, e.getId()});
        }
        list.sort(java.util.Comparator.<LivingEntity>comparingDouble(e -> key.get(e)[0])
                .thenComparingDouble(e -> key.get(e)[1]).thenComparingDouble(e -> key.get(e)[2]));
    }

    /** 実物 1 体を消す(部隊に連れて行かれた。死なせない = 死体・G1・ドロップ・経験値・死の知らせは無い)。 */
    static boolean removeReal(LivingEntity e) {
        if (!e.isAlive()) return false;
        e.discard();
        return true;
    }
}
