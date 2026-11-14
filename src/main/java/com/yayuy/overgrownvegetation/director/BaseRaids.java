package com.yayuy.overgrownvegetation.director;

import com.yayuy.overgrownvegetation.data.BaseMemoryTable;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.entity.AbstractParasitizedMonster;
import com.yayuy.overgrownvegetation.entity.DevelopedParasitized;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 認識した拠点への送り込み(2026-11-14、司令塔 第 2 段 区切り 3。D-8・D-12・D-14)。司令塔の側から個体へ働きかける口。
 *
 * <p>司令塔は個体の一覧を持たないので、送り込むときに拠点のまわり(読み込まれて動いている所)を箱で探して選ぶ。
 * <b>送り込めるのは読み込まれた範囲にいる G1 だけ</b>(プレイヤーが拠点にいれば拠点のまわりは読み込まれている)。
 *
 * <p>1 回の送り込みの顔ぶれは、認識してからの日数で決まる段({@link BaseMemoryTable#waveFor})。11-14 ユーザー決定
 * 「最初は数体の 0 段階や G4、そこから徐々に進化体が混ざったり増えたり、G4 以降の Mob が混ざったり増えたりする」。
 * <ul>
 *   <li>段階 0: 拠点の外にいる段階 0 を歩かせる({@link G1March})。</li>
 *   <li>進化体: 拠点の外にいる 1 段目を歩かせる。足りなければ、段階 0 を予算で進化させ(前兆も流す)、進化したら歩かせる。</li>
 *   <li>G4・G5 以降: 退避中なので、今は口だけ({@link #registerSender})。戻した群れの側で送り方を登録する。</li>
 * </ul>
 * 拠点に近すぎる個体(raidFromMinBlocks の中)は選ばない(もう拠点にいる)。
 */
public final class BaseRaids {

    private BaseRaids() {
    }

    /** G4 以降を拠点へ送る口。count 体まで送り、送れた数を返す。 */
    public interface Sender {
        int send(ServerLevel level, BaseMemory.Base base, int count, RandomSource random);
    }

    private static final List<Sender> G4 = new ArrayList<>();
    private static final List<Sender> G5_PLUS = new ArrayList<>();

    /** G4(g4 = true)か G5 以降の送り方を足す。今は何も登録されていない(G4 以降は退避中。D-14「仕組みだけ先に」)。 */
    public static void registerSender(boolean g4, Sender sender) {
        (g4 ? G4 : G5_PLUS).add(sender);
    }

    /** 1 回の送り込みの結果。 */
    public record Result(int wave, int stageZero, int developed, int evolved, int g4, int g5plus, int wantStageZero, int wantDeveloped,
                         int wantG4, int wantG5plus) {
        public String summary() {
            return String.format(Locale.ROOT, "段 %d: 段階 0 %d/%d・進化体 %d/%d(うち今進化させた %d)・G4 %d/%d・G5〜 %d/%d",
                    wave, stageZero, wantStageZero, developed + evolved, wantDeveloped, evolved, g4, wantG4, g5plus, wantG5plus);
        }
    }

    /** 拠点 1 つへ 1 回送り込む。 */
    public static Result sendWave(ServerLevel level, BaseMemory.Base b, double day, RandomSource random) {
        BaseMemoryTable t = DesignData.director().base;
        double since = Math.max(0.0D, day - b.stageSince());
        BaseMemoryTable.Wave w = t.waveFor(since);
        int waveNo = t.waveIndex(since);
        double r = t.raidSearchBlocks;
        AABB box = new AABB(b.centerX() - r, level.getMinBuildHeight(), b.centerZ() - r, b.centerX() + r, level.getMaxBuildHeight(), b.centerZ() + r);
        List<AbstractParasitizedMonster> zeros = new ArrayList<>();
        List<AbstractParasitizedMonster> developed = new ArrayList<>();
        for (AbstractParasitizedMonster m : level.getEntitiesOfClass(AbstractParasitizedMonster.class, box)) {
            if (!m.isAlive() || m.march().active() || m.isRising()) continue;
            double dx = m.getX() - b.centerX(), dz = m.getZ() - b.centerZ();
            double d2 = dx * dx + dz * dz;
            if (d2 > r * r || d2 < t.raidFromMinBlocks * t.raidFromMinBlocks) continue;
            if (!level.isPositionEntityTicking(m.blockPosition())) continue;
            if (m instanceof DevelopedParasitized) {
                developed.add(m);
            } else if (BaseWatch.canScout(m)) {
                zeros.add(m);
            }
        }
        Collections.shuffle(zeros, new java.util.Random(random.nextLong()));
        Collections.shuffle(developed, new java.util.Random(random.nextLong()));
        int sentDev = 0;
        for (AbstractParasitizedMonster m : developed) {
            if (sentDev >= w.developed()) break;
            m.march().begin(b);
            sentDev++;
        }
        // 進化体が足りない分は、段階 0 を予算で進化させて送る(進化体のある種だけ。予算が尽きたらそこまで)
        int evolved = 0;
        boolean canEvolve = DirectorGates.open(level, DirectorGates.Gate.EVOLVE); // 送り込みのための進化も F3 から
        ForestDirector director = ForestDirector.get(level);
        double cost = DesignData.director().costEvolve;
        List<AbstractParasitizedMonster> rest = new ArrayList<>();
        for (AbstractParasitizedMonster m : zeros) {
            if (canEvolve && sentDev + evolved < w.developed() && G1Fate.developedOf(m) != null && director.tryPay(level, cost)) {
                m.fate().beginEvolve();
                m.fate().markPaid();
                m.march().begin(b); // 進化で入れ替わるときに引き継ぐ(G1Fate#finishEvolve)
                director.record(ForestDirector.Route.EVOLVE, m.species().key() + " → 進化(拠点 #" + b.id() + " へ送るため)");
                evolved++;
            } else {
                rest.add(m);
            }
        }
        int sentZero = 0;
        for (AbstractParasitizedMonster m : rest) {
            if (sentZero >= w.stageZero()) break;
            m.march().begin(b);
            sentZero++;
        }
        // G4 は F4・G5〜 は F5 から(G4〜G6 の混ざった群れ = F6 は、登録する側が DirectorGates.Gate.WAVE_MIXED を見る)
        int g4 = DirectorGates.open(level, DirectorGates.Gate.WAVE_G4) ? send(G4, level, b, w.g4(), random) : 0;
        int g5 = DirectorGates.open(level, DirectorGates.Gate.WAVE_G5) ? send(G5_PLUS, level, b, w.g5plus(), random) : 0;
        Result res = new Result(waveNo, sentZero, sentDev, evolved, g4, g5, w.stageZero(), w.developed(), w.g4(), w.g5plus());
        director.note("拠点 #" + b.id() + " へ送り込み " + res.summary());
        return res;
    }

    private static int send(List<Sender> senders, ServerLevel level, BaseMemory.Base b, int count, RandomSource random) {
        int sent = 0;
        for (Sender s : senders) {
            if (sent >= count) break;
            sent += Math.max(0, s.send(level, b, count - sent, random));
        }
        return sent;
    }

    // ---------------------------------------------------------------- 苗床から孵った Mob(区切り 4)

    /** G4 以降の Mob を拠点へ歩かせる口(その Mob の群れの側が登録する)。歩かせられたら true。 */
    public interface Marcher {
        boolean march(net.minecraft.world.entity.Mob mob, BaseMemory.Base base);
    }

    private static final List<Marcher> MARCHERS = new ArrayList<>();

    /** G4 以降を戻したら、その Mob を拠点へ歩かせる方法を足す(今は何も登録されていない)。 */
    public static void registerMarcher(Marcher marcher) {
        MARCHERS.add(marcher);
    }

    /**
     * 苗床から孵った Mob(区切り 4。D-14): 苗床が認識した拠点のそば(evolveBaseBlocks の中)なら、拠点へ向かわせる。
     * G1 なら {@link G1March}、それ以外(G4 以降)は登録された {@link Marcher} に任せる。
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

    /** 見張りから: 送り込む時が来た拠点へ送る。 */
    static void dispatch(ServerLevel level, BaseMemory memory, double day) {
        for (BaseMemory.Base b : List.copyOf(memory.bases())) {
            if (!BaseMemory.wantsWave(b, day)) continue;
            sendWave(level, b, day, level.getRandom());
            memory.waveSent(b, day, level.getRandom(), DirectorGates.stageMultiplier(level));
        }
    }
}
