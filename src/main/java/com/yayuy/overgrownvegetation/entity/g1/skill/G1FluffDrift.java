package com.yayuy.overgrownvegetation.entity.g1.skill;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModG1;
import com.yayuy.overgrownvegetation.spore.field.PrevailingWind;
import com.yayuy.overgrownvegetation.spore.field.SporeFieldServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 風に乗る綿毛(2026-11-13、能力の第 2 回。羊の 1 段目の (c)「綿毛を風に飛ばす」。仕様 Vault「G1 羊(1 段目)」3 節・5 節【決定 11-09・数は仮】)。
 * 綿毛は Mob ではなく<b>粒 + 落ちた所の降り積もり</b>(当たり判定を持たせない・重くしない)。共通の部品 —— 後の回も {@link #loose} で使える。
 *
 * <ul>
 *   <li>着地点: 放った所から、世界の卓越風({@link SporeFieldServer#windNow})の吹いていく向きへ {@value #MIN_DISTANCE}〜{@value #MAX_DISTANCE} ブロック
 *   (風が強いほど遠く。向きは ±{@value #SPREAD_DEG}° ばらける)。場がまだ無ければ(風が読めなければ)向きは乱数・強さは半分。計算は {@link #landing}(純関数)。</li>
 *   <li>降り積もり: <b>放った瞬間に</b>着地点の升へ足す(発芽のしきい値の何 % か。見た目の到達を待たない = 保存をまたいでも失われない)。</li>
 *   <li>見た目: 綿毛の粒のかたまりが、放った所から着地点まで山なりに流れていく({@value #PARTICLE_EVERY} tick ごとに今の位置へ粒を足す。
 *   {@value #VIEW} ブロック以内のプレイヤーへ遠くても見える粒で送る)。着いた所では、綿毛が上から降ってくる(風下に雪のように降る、仕様 1 節)。
 *   見た目の流れは保存しない(読み込み直すと消える。降り積もりは済んでいる)。</li>
 * </ul>
 * 数値はすべて【仮】。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID)
public final class G1FluffDrift {

    public static final double MIN_DISTANCE = 20.0D;
    public static final double MAX_DISTANCE = 40.0D;
    static final double SPREAD_DEG = 10.0D;
    /** 流れる速さ(ブロック / tick)と山なりの高さ(ブロック)。 */
    static final double SPEED = 0.3D;
    static final double PEAK = 7.0D;
    static final int PARTICLE_EVERY = 2;
    static final double VIEW = 96.0D;
    /** 着いた所に降る綿毛の数と広さ(ブロック)。 */
    static final int FALL_COUNT = 24;
    static final double FALL_SPREAD = 3.0D;

    /** 見た目の流れ 1 本。 */
    public record Flight(ResourceKey<Level> dimension, Vec3 from, Vec3 to, long start, int duration) {
        Vec3 at(double t) {
            return new Vec3(Mth.lerp(t, from.x, to.x), Mth.lerp(t, from.y, to.y) + PEAK * 4.0D * t * (1.0D - t), Mth.lerp(t, from.z, to.z));
        }
    }

    private static final List<Flight> FLIGHTS = new ArrayList<>();

    private G1FluffDrift() {
    }

    /**
     * 着地点(水平の位置。高さは {@code from} のまま)。純関数。
     *
     * @param dirX,dirZ 風が吹いていく向き(単位ベクトル)
     * @param strength  風の強さ(凪 0・ふだんの午後 1・嵐 3)。1 以上で最大の距離
     * @param roll      -1〜1 の乱数(向きのばらけ)
     */
    public static Vec3 landing(Vec3 from, double dirX, double dirZ, double strength, double roll) {
        double distance = Mth.lerp(Mth.clamp(strength, 0.0D, 1.0D), MIN_DISTANCE, MAX_DISTANCE);
        double a = Math.atan2(dirZ, dirX) + Math.toRadians(SPREAD_DEG) * Mth.clamp(roll, -1.0D, 1.0D);
        return new Vec3(from.x + Math.cos(a) * distance, from.y, from.z + Math.sin(a) * distance);
    }

    /**
     * 綿毛を放つ(サーバー)。着地点の升へ降り積もりを足し、見た目の流れを始める。
     *
     * @param from             放つ所(房の高さ)
     * @param depositPercent   着地点の升へ足す降り積もり(発芽のしきい値の %)
     * @return 着地点(高さは地表。読み込まれていない所は放った高さ)
     */
    public static Vec3 loose(ServerLevel level, Vec3 from, double depositPercent, RandomSource random) {
        PrevailingWind.Sample wind = SporeFieldServer.windNow(level);
        double dirX, dirZ, strength;
        if (wind != null) {
            dirX = wind.dirX;
            dirZ = wind.dirZ;
            strength = wind.strength;
        } else {
            double a = random.nextDouble() * Math.PI * 2.0D;
            dirX = Math.cos(a);
            dirZ = Math.sin(a);
            strength = 0.5D;
        }
        Vec3 flat = landing(from, dirX, dirZ, strength, random.nextDouble() * 2.0D - 1.0D);
        BlockPos column = BlockPos.containing(flat);
        double y = level.isLoaded(column) ? level.getHeight(Heightmap.Types.MOTION_BLOCKING, column.getX(), column.getZ()) : from.y;
        Vec3 to = new Vec3(flat.x, y, flat.z);
        SporeFieldServer.depositPoint(level, BlockPos.containing(to), DesignData.sporeField().field().germThreshold * depositPercent / 100.0D);
        int duration = Math.max(20, (int) Math.round(Math.sqrt(from.distanceToSqr(to)) / SPEED));
        FLIGHTS.add(new Flight(level.dimension(), from, to, level.getGameTime(), duration));
        // 放った瞬間: 房が弾けて綿毛が舞い上がる
        for (int i = 0; i < 14; i++) {
            send(level, from.x + (random.nextDouble() - 0.5D) * 0.8D, from.y + random.nextDouble() * 0.5D, from.z + (random.nextDouble() - 0.5D) * 0.8D,
                    dirX * 0.08D + (random.nextDouble() - 0.5D) * 0.12D, 0.12D + random.nextDouble() * 0.1D, dirZ * 0.08D + (random.nextDouble() - 0.5D) * 0.12D);
        }
        return to;
    }

    /** 綿毛の粒を 1 つ、{@value #VIEW} ブロック以内のプレイヤーへ(遠くても見える粒で。数 0 = 速度つき)。 */
    static void send(ServerLevel level, double x, double y, double z, double vx, double vy, double vz) {
        ParticleOptions fluff = ModG1.FLUFF.get();
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(x, y, z) <= VIEW * VIEW) {
                level.sendParticles(player, fluff, true, x, y, z, 0, vx, vy, vz, 1.0D);
            }
        }
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (FLIGHTS.isEmpty() || !(event.getLevel() instanceof ServerLevel level)) return;
        long now = level.getGameTime();
        RandomSource random = level.random;
        for (Iterator<Flight> it = FLIGHTS.iterator(); it.hasNext(); ) {
            Flight f = it.next();
            if (f.dimension() != level.dimension()) continue;
            long age = now - f.start();
            if (age >= f.duration()) {
                it.remove();
                // 着いた所: 綿毛が上から降ってくる
                for (int i = 0; i < FALL_COUNT; i++) {
                    send(level, f.to().x + (random.nextDouble() - 0.5D) * 2.0D * FALL_SPREAD, f.to().y + 2.0D + random.nextDouble() * 4.0D,
                            f.to().z + (random.nextDouble() - 0.5D) * 2.0D * FALL_SPREAD,
                            (random.nextDouble() - 0.5D) * 0.03D, -0.03D - random.nextDouble() * 0.03D, (random.nextDouble() - 0.5D) * 0.03D);
                }
                continue;
            }
            if (age % PARTICLE_EVERY != 0) continue;
            double t = (double) age / f.duration();
            Vec3 p = f.at(t);
            Vec3 ahead = f.at(Math.min(1.0D, t + 1.0D / f.duration()));
            Vec3 v = ahead.subtract(p).scale(0.8D); // かたまりと一緒に流れる
            for (int i = 0; i < 3; i++) {
                send(level, p.x + (random.nextDouble() - 0.5D) * 2.4D, p.y + (random.nextDouble() - 0.5D) * 1.6D, p.z + (random.nextDouble() - 0.5D) * 2.4D,
                        v.x + (random.nextDouble() - 0.5D) * 0.03D, v.y + (random.nextDouble() - 0.5D) * 0.02D, v.z + (random.nextDouble() - 0.5D) * 0.03D);
            }
        }
    }

    /** サーバーが止まったら見た目の流れを捨てる(次の世界へ持ち越さない)。 */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        FLIGHTS.clear();
    }

    /** 流れている最中の数(GameTest・確かめ用)。 */
    public static int flights() {
        return FLIGHTS.size();
    }
}
