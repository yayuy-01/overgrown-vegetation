package com.yayuy.overgrownvegetation.drift;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModDrift;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import com.yayuy.overgrownvegetation.port.event.tick.LevelTickEvent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import com.yayuy.overgrownvegetation.port.saved.OvSavedData;

/**
 * 読み込みの外を流れている漂流する擬態 Mob の帳面(2026-11-20、オーバーワールドだけ)【仮・Claude】。
 *
 * <p>エンティティはプレイヤーの近くでしか動かないので、誰も見ていない海を渡れるよう、流れていく先が動かない所で近くにプレイヤーがいなければ
 * 姿を消してここへ移す。帳面の上では 20 tick ごとに卓越風(乗せて運ぶ分は行き先へ漕ぐ)で粗く進め、読み込まれて動く所に入ったら
 * 姿に戻す。陸に着いたかは帳面の上では見ない(読み込まれていない所は読めない)—— 姿に戻ってから、岸を探して寄る・打ち上げられて根付く。
 * 乗せていた Mob は種類だけ覚え、戻すときに新しく出す。{@link DriftTable.Drift#lifetimeDays} を過ぎたら沈んで消える。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE)
public final class DriftLedger extends OvSavedData {

    public static final String ID = "overgrown_vegetation_drift_ledger";
    /** 帳面の上限(超えたら姿のまま留まる)。 */
    static final int CAP = 64;
    /** この距離にプレイヤーがいれば姿のまま。 */
    static final double KEEP_NEAR = 48.0D;

    static final class Rec {
        double x, z;
        int wy;
        DriftMimicEntity.Mode mode;
        BlockPos target;
        long born;
        double paid;
        final List<String> riders = new ArrayList<>();
    }

    final List<Rec> recs = new ArrayList<>();

    public static final OvSavedData.Factory<DriftLedger> FACTORY = new OvSavedData.Factory<>(DriftLedger::new, DriftLedger::load, null);

    public static DriftLedger get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(FACTORY.load(), FACTORY.constructor(), ID);
    }

    /** 帳面の上を流れている数。 */
    public static int count(ServerLevel level) {
        return level.dimension() == Level.OVERWORLD ? get(level).recs.size() : 0;
    }

    static boolean shouldGoVirtual(ServerLevel level, DriftMimicEntity e) {
        if (level.dimension() != Level.OVERWORLD) return false;
        Vec3 v = e.driftVelocity();
        double l = Math.sqrt(v.horizontalDistanceSqr());
        if (l < 1e-4) return false;
        Vec3 ahead = e.position().add(v.x / l * (3.0D + e.getBbWidth()), 0.0D, v.z / l * (3.0D + e.getBbWidth()));
        if (level.isPositionEntityTicking(BlockPos.containing(ahead))) return false;
        if (DriftMimicEntity.playerNear(level, e.position(), KEEP_NEAR)) return false;
        return get(level).recs.size() < CAP;
    }

    static void store(ServerLevel level, DriftMimicEntity e) {
        DriftLedger d = get(level);
        Rec r = new Rec();
        r.x = e.getX();
        r.z = e.getZ();
        int wy = DriftShore.waterSurfaceY(level, e.blockPosition());
        r.wy = wy == Integer.MIN_VALUE ? e.getBlockY() : wy;
        r.mode = e.mode;
        r.target = e.target;
        r.born = e.born == Long.MIN_VALUE ? level.getGameTime() : e.born;
        r.paid = com.yayuy.overgrownvegetation.director.ForestEconomy.paid(e);
        for (Entity p : new ArrayList<>(e.getPassengers())) {
            r.riders.add(EntityType.getKey(p.getType()).toString());
            p.discard();
        }
        d.recs.add(r);
        d.setDirty();
        e.discard();
        DriftStats.wentVirtual++;
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        if (level.getGameTime() % 20 != 0) return;
        DriftLedger d = get(level);
        if (d.recs.isEmpty()) return;
        long t0 = System.nanoTime();
        step(level, d, 20);
        DriftStats.ledger(System.nanoTime() - t0);
    }

    private final double[] wind = new double[2];

    static void step(ServerLevel level, DriftLedger d, int ticks) {
        DriftTable.Drift t = DesignData.drift().drift;
        DriftWind.now(level, null, d.wind);
        double s = Math.min(t.windCap, Math.hypot(d.wind[0], d.wind[1]));
        double wl = Math.hypot(d.wind[0], d.wind[1]);
        double wx = wl > 1e-9 ? d.wind[0] / wl * s * t.blocksPerWind : 0.0D, wz = wl > 1e-9 ? d.wind[1] / wl * s * t.blocksPerWind : 0.0D;
        long life = (long) (t.lifetimeDays * 24000.0D);
        for (Iterator<Rec> it = d.recs.iterator(); it.hasNext(); ) {
            Rec r = it.next();
            if (level.getGameTime() - r.born > life) {
                it.remove();
                DriftStats.lost++;
                continue;
            }
            double vx = wx, vz = wz;
            if (r.target != null && r.mode == DriftMimicEntity.Mode.FERRY) {
                double tx = r.target.getX() + 0.5D - r.x, tz = r.target.getZ() + 0.5D - r.z, tl = Math.hypot(tx, tz);
                if (tl > 1e-6) {
                    vx = wx * 0.3D + tx / tl * t.paddleSpeed;
                    vz = wz * 0.3D + tz / tl * t.paddleSpeed;
                }
            }
            r.x += vx * ticks;
            r.z += vz * ticks;
            BlockPos at = BlockPos.containing(r.x, r.wy, r.z);
            if (level.isPositionEntityTicking(at) && level.getChunkSource().getChunkNow(at.getX() >> 4, at.getZ() >> 4) != null) {
                materialize(level, r);
                it.remove();
            }
        }
        d.setDirty();
    }

    private static void materialize(ServerLevel level, Rec r) {
        DriftMimicEntity e = ModDrift.DRIFT_MIMIC.get().create(level);
        if (e == null) return;
        e.moveTo(r.x, r.wy + 0.5D, r.z, 0.0F, 0.0F);
        e.mode = r.mode == DriftMimicEntity.Mode.SEEK ? DriftMimicEntity.Mode.DRIFT : r.mode;
        e.target = r.mode == DriftMimicEntity.Mode.FERRY ? r.target : null;
        e.born = r.born;
        com.yayuy.overgrownvegetation.director.ForestEconomy.markPaid(e, r.paid);
        level.addFreshEntity(e);
        for (String id : r.riders) {
            EntityType<?> type = EntityType.byString(id).orElse(null);
            if (type == null) continue;
            Entity rider = type.create(level);
            if (rider == null) continue;
            rider.moveTo(r.x, r.wy + 1.0D, r.z, 0.0F, 0.0F);
            if (rider instanceof Mob m) {
                m.finalizeSpawn(level, level.getCurrentDifficultyAt(rider.blockPosition()), MobSpawnType.EVENT, null);
                m.setPersistenceRequired();
            }
            level.addFreshEntity(rider);
            rider.startRiding(e, true);
        }
        DriftStats.cameBack++;
    }

    // ------------------------------------------------------------ 保存

    static DriftLedger load(CompoundTag tag, HolderLookup.Provider lookup) {
        DriftLedger d = new DriftLedger();
        ListTag list = tag.getList("recs", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag c = list.getCompound(i);
            Rec r = new Rec();
            r.x = c.getDouble("x");
            r.z = c.getDouble("z");
            r.wy = c.getInt("wy");
            try {
                r.mode = DriftMimicEntity.Mode.valueOf(c.getString("mode"));
            } catch (IllegalArgumentException e) {
                r.mode = DriftMimicEntity.Mode.DRIFT;
            }
            r.target = c.contains("target") ? BlockPos.of(c.getLong("target")) : null;
            r.born = c.getLong("born");
            r.paid = c.getDouble("paid");
            ListTag rl = c.getList("riders", Tag.TAG_STRING);
            for (int k = 0; k < rl.size(); k++) r.riders.add(rl.getString(k));
            d.recs.add(r);
        }
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider lookup) {
        ListTag list = new ListTag();
        for (Rec r : recs) {
            CompoundTag c = new CompoundTag();
            c.putDouble("x", r.x);
            c.putDouble("z", r.z);
            c.putInt("wy", r.wy);
            c.putString("mode", r.mode.name());
            if (r.target != null) c.putLong("target", r.target.asLong());
            c.putLong("born", r.born);
            c.putDouble("paid", r.paid);
            ListTag rl = new ListTag();
            for (String id : r.riders) rl.add(StringTag.valueOf(id));
            c.put("riders", rl);
            list.add(c);
        }
        tag.put("recs", list);
        return tag;
    }

    /** 確かめ用: 帳面を空にする。 */
    public static void clearForTests(ServerLevel level) {
        get(level).recs.clear();
    }

    static double dist(Rec r, double x, double z) {
        return Mth.length(r.x - x, r.z - z);
    }
}
