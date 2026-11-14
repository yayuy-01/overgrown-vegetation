package com.yayuy.overgrownvegetation.director;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.KillMemoryTable;
import com.yayuy.overgrownvegetation.entity.AbstractParasitizedMonster;
import com.yayuy.overgrownvegetation.entity.DevelopedParasitized;
import com.yayuy.overgrownvegetation.entity.NurseryParasitized;
import com.yayuy.overgrownvegetation.entity.RootGuardian;
import com.yayuy.overgrownvegetation.entity.RootedParasitized;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 司令塔の「学ぶ」(2026-11-14、第 3 段 区切り 1。11-13 ユーザー「G1 が何度も倒された場所へは進化体(戦う役)を送る」)。
 *
 * <ul>
 *   <li>記録: プレイヤー(とその飛び道具・飼い犬など持ち主のいる生き物)に倒された G1 を {@link KillMemory} へ。フェーズは見ない(F1 から裏で)。</li>
 *   <li>送る: 危ない所ごとに {@code dangerIntervalMin〜MaxDays} おき、まわりの 1 段目を歩かせる({@link G1March})。足りなければ段階 0 を予算で進化させて送る。
 *   F3 から(DirectorGates.Gate.LEARN)。大きな襲撃の前触れの間は止める。数に小さな段の強さを掛ける。</li>
 * </ul>
 * 送れるのは読み込まれた範囲の G1 だけ(拠点の送り込みと同じ)。前兆は、送られた 1 段目が歩いてくる姿と、足りない分の進化の震え(新しい演出は無し)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class DangerWatch {

    private DangerWatch() {
    }

    /** 危ない所(升)ごとの、次に送ってよい日(保存しない。再起動したらすぐ送ってよい)。 */
    private static final Long2DoubleOpenHashMap NEXT = new Long2DoubleOpenHashMap();

    /** 数える G1 か(段階 0・1 段目・根付き・苗床・根の守り手)。 */
    public static boolean isG1(Entity e) {
        return e instanceof AbstractParasitizedMonster || e instanceof RootedParasitized || e instanceof NurseryParasitized
                || e instanceof RootGuardian;
    }

    /** プレイヤーが倒したことになるか(プレイヤー本人・その飛び道具・持ち主がプレイヤーの生き物)。 */
    public static boolean byPlayer(@javax.annotation.Nullable Entity killer) {
        if (killer instanceof Player p) return !p.isSpectator();
        return killer instanceof OwnableEntity own && own.getOwner() instanceof Player;
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        LivingEntity e = event.getEntity();
        if (!(e.level() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD || !isG1(e)) return;
        if (!DesignData.director().enabled || !byPlayer(event.getSource().getEntity())) return;
        KillMemory.get(level.getServer()).record(ChunkPos.asLong(e.getBlockX() >> 4, e.getBlockZ() >> 4), BaseMemory.today(level.getServer()));
    }

    /** 見張り({@link BaseWatch})から: 危ない所へ進化体を送る。 */
    static void dispatch(ServerLevel level, double day) {
        for (KillMemory.Danger d : KillMemory.get(level.getServer()).dangers(day)) {
            if (day < NEXT.getOrDefault(d.chunk(), Double.NEGATIVE_INFINITY)) continue;
            send(level, d, level.getRandom());
            KillMemoryTable k = DesignData.director().kills;
            NEXT.put(d.chunk(), day + k.dangerIntervalMinDays + level.getRandom().nextDouble() * (k.dangerIntervalMaxDays - k.dangerIntervalMinDays));
        }
    }

    /** 1 つの危ない所へ送る。返り値 = [今いる 1 段目を送った数, 進化させて送った数]。 */
    public static int[] send(ServerLevel level, KillMemory.Danger d, RandomSource random) {
        KillMemoryTable k = DesignData.director().kills;
        int want = DirectorGates.scaleCount(k.dangerDeveloped, DirectorGates.strength(level, DirectorGates.Gate.LEARN), random);
        double r = k.dangerSearchBlocks;
        AABB box = new AABB(d.x() - r, level.getMinBuildHeight(), d.z() - r, d.x() + r, level.getMaxBuildHeight(), d.z() + r);
        List<AbstractParasitizedMonster> dev = new ArrayList<>(), zeros = new ArrayList<>();
        for (AbstractParasitizedMonster m : level.getEntitiesOfClass(AbstractParasitizedMonster.class, box)) {
            if (!m.isAlive() || m.march().active() || m.isRising()) continue;
            double dx = m.getX() - d.x(), dz = m.getZ() - d.z(), d2 = dx * dx + dz * dz;
            if (d2 > r * r || d2 < k.dangerFromMinBlocks * k.dangerFromMinBlocks) continue;
            if (!level.isPositionEntityTicking(m.blockPosition())) continue;
            if (m instanceof DevelopedParasitized) {
                dev.add(m);
            } else if (BaseWatch.canScout(m) && G1Fate.developedOf(m) != null) {
                zeros.add(m);
            }
        }
        Collections.shuffle(dev, new java.util.Random(random.nextLong()));
        Collections.shuffle(zeros, new java.util.Random(random.nextLong()));
        int sent = 0;
        for (AbstractParasitizedMonster m : dev) {
            if (sent >= want) break;
            m.march().beginTo(d.x(), d.z(), -1);
            sent++;
        }
        int evolved = 0;
        ForestDirector director = ForestDirector.get(level);
        double price = ForestDirector.stagePrice(DesignData.director().costEvolve, DirectorGates.strength(level, DirectorGates.Gate.EVOLVE));
        for (AbstractParasitizedMonster m : zeros) {
            if (sent + evolved >= want || !director.tryPay(level, price)) break;
            m.fate().beginEvolve();
            m.fate().markPaid(price);
            m.march().beginTo(d.x(), d.z(), -1); // 進化で入れ替わるときに引き継ぐ
            evolved++;
        }
        if (sent + evolved > 0) {
            director.note(String.format(Locale.ROOT, "危ない所 (%.0f, %.0f) へ進化体 %d(うち今進化させた %d)/ %d", d.x(), d.z(), sent + evolved, evolved, want));
        }
        return new int[]{sent, evolved};
    }

    /** 確かめ用: 次に送ってよい日を全部消す(すぐ送れる)。 */
    public static void resetTimers() {
        NEXT.clear();
    }
}
