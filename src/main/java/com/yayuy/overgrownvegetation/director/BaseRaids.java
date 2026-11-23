package com.yayuy.overgrownvegetation.director;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.entity.AbstractParasitizedMonster;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;

/**
 * 認識した拠点への送り込みの時を見る口(2026-11-14、司令塔 第 2 段 区切り 3)と、苗床から孵った Mob を拠点へ向かわせる口。
 *
 * <p>送り込みの時が来た拠点へは、前線から襲う部隊を出す({@code front/FrontRaids}。最初の襲撃は小、その後は {@link RaidScales} の見送りと貯めで小・中・大)。
 * 2026-11-23 片付け E: 前の形(拠点のまわりの G1 を選んで歩かせる・段階 0 を進化させる・G2 以降を森の方角から湧かせる送り込み)は消した。
 */
public final class BaseRaids {

    private BaseRaids() {
    }

    // ---------------------------------------------------------------- 苗床から孵った Mob(区切り 4)

    /** G2 以降の Mob を拠点へ歩かせる口(その Mob の群れの側が登録する)。歩かせられたら true。 */
    public interface Marcher {
        boolean march(net.minecraft.world.entity.Mob mob, BaseMemory.Base base);
    }

    private static final List<Marcher> MARCHERS = new ArrayList<>();

    /** G2 以降を戻したら、その Mob を拠点へ歩かせる方法を足す(今は何も登録されていない)。 */
    public static void registerMarcher(Marcher marcher) {
        MARCHERS.add(marcher);
    }

    /**
     * 苗床から孵った Mob(区切り 4。D-14): 苗床が認識した拠点のそば(evolveBaseBlocks の中)なら、拠点へ向かわせる。
     * G1 なら {@link G1March}、それ以外(G2 以降)は登録された {@link Marcher} に任せる。
     *
     * @return 拠点へ向かわせたか
     */
    public static boolean onNurseryHatched(ServerLevel level, net.minecraft.world.entity.Mob mob) {
        BaseMemory.Base b = ForestDirector.nearestRecognized(level, mob.getX(), mob.getZ());
        if (b == null) return false;
        if (mob instanceof AbstractParasitizedMonster g1) {
            g1.march().begin(b);
            return true;
        }
        for (Marcher m : MARCHERS) {
            if (m.march(mob, b)) return true;
        }
        return false;
    }

    /** 見張りから: 送り込む時が来た拠点へ、前線から襲う部隊を出す。前線が止まっていれば何もしない。 */
    public static void dispatch(ServerLevel level, BaseMemory memory, double day) {
        if (!RaidScales.Knobs.raidsEnabled) return; // 設定のページで襲撃を切った
        if (!com.yayuy.overgrownvegetation.front.FrontRaids.active(level)) return;
        for (BaseMemory.Base b : List.copyOf(memory.bases())) {
            if (!BaseMemory.wantsWave(b, day)) continue;
            // 11-19 襲撃の規模: 最初の襲撃が済んだ後の機会は、見送るか、貯めた日数で小・中・大を選んで起こす
            if (!BaseMemory.firstRaidPending(b)) {
                if (RaidScales.skipOrStart(level, memory, b, day, level.getRandom().nextDouble(), level.getRandom())) continue;
                memory.waveSent(b, day, level.getRandom());
                rescaleInterval(memory, b, day);
                if (ForestDirector.get(level).mood() != ForestDirector.Mood.NORMAL) break; // 中・大の間はほかの拠点も待つ
                continue;
            }
            // 11-21 前線と部隊: 最初の襲撃も前線から出す(出せる部隊が無ければ 0 = 試し直す)
            int sent = com.yayuy.overgrownvegetation.front.FrontRaids.send(level, b, day, RaidScales.RaidScale.SMALL);
            if (sent == 0) {
                memory.firstRaidRetry(b, day, DesignData.director().assault.firstRetryPoorDays, BaseMemory.timeOfDay(level.getServer()), level.getRandom().nextDouble());
                continue;
            }
            memory.waveSent(b, day, level.getRandom(), DirectorGates.strength(level, DirectorGates.Gate.RAID)); // F5 の導入では送り込みの間が長い(0.3 なら 3.3 倍)
            rescaleInterval(memory, b, day);
        }
    }

    /** 設定のページの頻度の倍率を、決めた次の機会までの間に掛ける。 */
    private static void rescaleInterval(BaseMemory memory, BaseMemory.Base b, double day) {
        double f = RaidScales.Knobs.intervalFactor;
        if (Math.abs(f - 1.0D) > 1.0E-9D) memory.setNextWaveDay(b, day + Math.max(0.0D, b.nextWaveDay() - day) * Math.max(0.05D, f));
    }
}
