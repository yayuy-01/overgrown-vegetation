package com.yayuy.overgrownvegetation.director;

import com.yayuy.overgrownvegetation.data.BaseMemoryTable;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.entity.AbstractParasitizedMonster;
import com.yayuy.overgrownvegetation.entity.DevelopedParasitized;
import net.minecraft.core.BlockPos;
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

    /** 1 回の送り込みの結果。spawnedZero = stageZero のうち今湧かせた数。first = 最初の襲撃。hoard = 貯めた襲撃のまとめた回。 */
    public record Result(int wave, int stageZero, int developed, int evolved, int g4, int g5plus, int wantStageZero, int wantDeveloped,
                         int wantG4, int wantG5plus, int spawnedZero, boolean first, boolean hoard) {
        public int total() {
            return stageZero + developed + evolved + g4 + g5plus;
        }

        public String summary() {
            return String.format(Locale.ROOT, "%s段 %d: 段階 0 %d/%d(うち湧かせた %d)・進化体 %d/%d(うち今進化させた %d)・G4 %d/%d・G5〜 %d/%d・計 %d",
                    first ? "最初の襲撃・" : hoard ? "貯めた襲撃・" : "", wave, stageZero, wantStageZero, spawnedZero, developed + evolved, wantDeveloped, evolved,
                    g4, wantG4, g5plus, wantG5plus, total());
        }
    }

    /** 拠点 1 つへ 1 回送り込む(ふだんの回)。 */
    public static Result sendWave(ServerLevel level, BaseMemory.Base b, double day, RandomSource random) {
        return sendWave(level, b, day, random, false);
    }

    /**
     * 拠点 1 つへ 1 回送り込む(11-16 作り直し。正本は Vault「F5 の襲撃(11-16)」)。
     * <ul>
     *   <li>顔ぶれ: まだ 1 回も送っていなければ「最初の襲撃」({@code assault.firstRaid})、そうでなければ認識してからの日数の段。
     *   hoard(貯めた襲撃のまとめた回)なら進化体と G5 を {@code hoardShareFactor} 倍。どれも計 {@code maxWave}(40)まで。</li>
     *   <li>いる個体(拠点のまわりの段階 0・進化体・G4・G5)を先に歩かせ、足りない分は森の方角の見えない地面に湧かせて予算を払う。
     *   払う順は 段階 0 → G4 → 進化体 → G5(ユーザー)。払えない分は送らない。</li>
     * </ul>
     */
    public static Result sendWave(ServerLevel level, BaseMemory.Base b, double day, RandomSource random, boolean hoard) {
        return sendWave(level, b, day, random, hoard, null);
    }

    /**
     * 顔ぶれを決め打ちにする形(貯めた襲撃の継ぎ足し {@link HoardRaids})。override が null なら上の決め方。
     */
    public static Result sendWave(ServerLevel level, BaseMemory.Base b, double day, RandomSource random, boolean hoard,
                                  @javax.annotation.Nullable BaseMemoryTable.Wave override) {
        BaseMemoryTable t = DesignData.director().base;
        com.yayuy.overgrownvegetation.data.AssaultTable at = DesignData.director().assault;
        double since = Math.max(0.0D, day - b.stageSince());
        boolean first = BaseMemory.firstRaidPending(b);
        // 小さな段: 送り込みの枠に、その枠が開いたフェーズの強さを掛ける(進化体 F3・G4 F4・G5〜 F5。導入で少なく・最盛で表どおり。端数は確率)【仮】
        BaseMemoryTable.Wave w = scaled(level, first ? at.firstRaid : t.waveFor(since), random);
        // 学ぶ: 拠点の中に危ない所があれば(拠点を守りの固い所と覚えた)、進化体の枠を増やす(F5 から)
        if (DirectorGates.open(level, DirectorGates.Gate.LEARN_WAVE) && level.dimension() == net.minecraft.world.level.Level.OVERWORLD
                && KillMemory.get(level.getServer()).nearest(b.centerX(), b.centerZ(), t.matchBlocks, day) != null) {
            w = new BaseMemoryTable.Wave(w.fromDays(), w.stageZero(), w.developed() + DesignData.director().kills.baseDangerExtraDeveloped, w.g4(), w.g5plus());
        }
        if (hoard) {
            w = new BaseMemoryTable.Wave(w.fromDays(), w.stageZero(), (int) Math.round(w.developed() * at.hoardShareFactor), w.g4(),
                    (int) Math.round(w.g5plus() * at.hoardShareFactor));
        }
        w = capped(override != null ? override : w, at.maxWave);
        int waveNo = first ? -1 : t.waveIndex(since);
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
        // 弱い所を突く: 守りの薄い方角から回り込む割合(F5 の送り込みの段の強さを掛ける。11-14 ユーザー)
        double flank = DesignData.director().base.flankShare * DirectorGates.strength(level, DirectorGates.Gate.RAID);
        ForestDirector director = ForestDirector.get(level);
        // (1) いる進化体を歩かせる(ただ)
        int sentDev = 0;
        for (AbstractParasitizedMonster m : developed) {
            if (sentDev >= w.developed()) break;
            go(m, b, flank, random);
            sentDev++;
        }
        // (2) 進化体の足りない分のために、進化できる段階 0 を取り置く(払うのは後: 払う順は 段階 0 → G4 → 進化体 → G5)
        boolean canEvolve = DirectorGates.open(level, DirectorGates.Gate.EVOLVE); // 送り込みのための進化も F3 から
        int devNeed = Math.max(0, w.developed() - sentDev);
        List<AbstractParasitizedMonster> toEvolve = new ArrayList<>();
        List<AbstractParasitizedMonster> rest = new ArrayList<>();
        for (AbstractParasitizedMonster m : zeros) {
            if (canEvolve && toEvolve.size() < devNeed && G1Fate.developedOf(m) != null) toEvolve.add(m);
            else rest.add(m);
        }
        // (3) いる段階 0 を歩かせる(ただ)
        int sentZero = 0;
        for (AbstractParasitizedMonster m : rest) {
            if (sentZero >= w.stageZero()) break;
            go(m, b, flank, random);
            sentZero++;
        }
        // (4) 段階 0 の足りない分を湧かせる(1 体 stageZeroCost)
        int spawnedZero = 0;
        BlockPos spot = null;
        for (int tries = 0; sentZero < w.stageZero() && tries < w.stageZero() * 2; tries++) {
            if (director.available(level) + 1.0E-9D < at.stageZeroCost) break;
            if (spot == null) spot = com.yayuy.overgrownvegetation.spawn.DirectorHooks.hiddenSpot(level, b, random);
            if (spot == null) break;
            AbstractParasitizedMonster m = RaidSpawns.spawnAttacker(level, com.yayuy.overgrownvegetation.spawn.DirectorHooks.near(level, spot, random), false, random);
            if (m == null) continue;
            director.tryPay(level, at.stageZeroCost);
            go(m, b, flank, random);
            sentZero++;
            spawnedZero++;
        }
        // (5) G4 は F4・G5〜 は F5 から(G4〜G6 の混ざった群れ = F6 は、登録する側が DirectorGates.Gate.WAVE_MIXED を見る)
        int g4 = DirectorGates.open(level, DirectorGates.Gate.WAVE_G4) ? send(G4, level, b, w.g4(), random) : 0;
        // (6) 進化体の足りない分: 取り置いた段階 0 を予算で進化させて送る。足りなければ段階 0 を湧かせて進化させる
        int evolved = 0;
        double cost = DesignData.director().costEvolve;
        List<AbstractParasitizedMonster> unused = new ArrayList<>();
        for (AbstractParasitizedMonster m : toEvolve) {
            if (evolved < devNeed && director.tryPay(level, cost)) {
                beginEvolveMarch(director, m, b, flank, cost, random);
                evolved++;
            } else {
                unused.add(m);
            }
        }
        for (int tries = 0; canEvolve && evolved < devNeed && tries < devNeed * 4; tries++) {
            if (director.available(level) + 1.0E-9D < cost + at.stageZeroCost) break;
            if (spot == null) spot = com.yayuy.overgrownvegetation.spawn.DirectorHooks.hiddenSpot(level, b, random);
            if (spot == null) break;
            // 1 段目を直に湧かせる(進化体の無い種が引かれたら消して引き直す。払わない)
            AbstractParasitizedMonster m = RaidSpawns.spawnAttacker(level, com.yayuy.overgrownvegetation.spawn.DirectorHooks.near(level, spot, random), true, random);
            if (m == null) continue;
            if (!(m instanceof DevelopedParasitized)) {
                m.discard();
                continue;
            }
            director.tryPay(level, at.stageZeroCost + cost);
            go(m, b, flank, random);
            director.record(ForestDirector.Route.EVOLVE, "1 段目を湧かせた(拠点 #" + b.id() + " へ送るため)");
            evolved++;
        }
        // 払えずに進化させなかった段階 0 も、そこにいるので段階 0 として送る(上限まで)
        for (AbstractParasitizedMonster m : unused) {
            if (sentZero + sentDev + evolved + g4 + w.g5plus() >= at.maxWave) break; // G5 の枠を残す(見直し ②5)
            go(m, b, flank, random);
            sentZero++;
        }
        // (7) G5
        // 計が上限 40 を超えないように(見直し ②5: 払えずに段階 0 として送った分の後で G5 を足すと超えていた)
        int g5Room = Math.max(0, Math.min(w.g5plus(), at.maxWave - (sentZero + sentDev + evolved + g4)));
        int g5 = DirectorGates.open(level, DirectorGates.Gate.WAVE_G5) ? send(G5_PLUS, level, b, g5Room, random) : 0;
        Result res = new Result(waveNo, sentZero, sentDev, evolved, g4, g5, w.stageZero(), w.developed(), w.g4(), w.g5plus(), spawnedZero, first, hoard);
        director.note("拠点 #" + b.id() + " へ送り込み " + res.summary());
        return res;
    }

    /** この送り込みで歩かせる個体に付ける印(貯めた襲撃の継ぎ足しが数える。null なら付けない)。 */
    @javax.annotation.Nullable
    private static String waveTag;

    @javax.annotation.Nullable
    public static String waveTag() {
        return waveTag;
    }

    /** 送り込みの間だけ印を付ける(終わったら null に戻す)。 */
    public static void setWaveTag(@javax.annotation.Nullable String tag) {
        waveTag = tag;
    }

    /** 拠点へ向かわせる(印があれば付ける)。 */
    private static void go(AbstractParasitizedMonster m, BaseMemory.Base b, double flank, RandomSource random) {
        if (waveTag != null) m.addTag(waveTag);
        com.yayuy.overgrownvegetation.spawn.EnemySpawns.release(m); // 縁・地下の印を外す(縁の上限・片付けから外す。見直し ②4)
        m.march().beginFlank(b, flank, random);
    }

    private static void beginEvolveMarch(ForestDirector director, AbstractParasitizedMonster m, BaseMemory.Base b, double flank, double cost, RandomSource random) {
        m.fate().beginEvolve();
        m.fate().markPaid(cost);
        go(m, b, flank, random); // 進化で入れ替わるときに引き継ぐ(G1Fate#finishEvolve)
        director.record(ForestDirector.Route.EVOLVE, m.species().key() + " → 進化(拠点 #" + b.id() + " へ送るため)");
    }

    /** 計 max 体に収める(多ければ 段階 0 → G4 → 進化体 → G5 の順に削る = 安いものから)。 */
    public static BaseMemoryTable.Wave capped(BaseMemoryTable.Wave w, int max) {
        int[] c = {w.stageZero(), w.g4(), w.developed(), w.g5plus()};
        int over = c[0] + c[1] + c[2] + c[3] - max;
        for (int i = 0; i < 4 && over > 0; i++) {
            int cut = Math.min(c[i], over);
            c[i] -= cut;
            over -= cut;
        }
        return new BaseMemoryTable.Wave(w.fromDays(), c[0], c[2], c[1], c[3]);
    }

    /** G4 以降を count 体まで拠点へ送る(G4 の口から、足りなければ G5 以降の口から)。大きな襲撃・後の超大規模の襲撃が呼ぶ。 */
    public static int sendPlantSide(ServerLevel level, BaseMemory.Base b, int count, RandomSource random) {
        int n = send(G4, level, b, count, random);
        return n + send(G5_PLUS, level, b, count - n, random);
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

    /** 送り込みの枠に、フェーズの小さな段の強さを掛ける(段階 0 は掛けない = 送り込みそのものの強さは送り込みの間で掛ける)。 */
    static BaseMemoryTable.Wave scaled(ServerLevel level, BaseMemoryTable.Wave w, RandomSource random) {
        return new BaseMemoryTable.Wave(w.fromDays(), w.stageZero(),
                DirectorGates.scaleCount(w.developed(), DirectorGates.strength(level, DirectorGates.Gate.EVOLVE), random),
                DirectorGates.scaleCount(w.g4(), DirectorGates.strength(level, DirectorGates.Gate.WAVE_G4), random),
                DirectorGates.scaleCount(w.g5plus(), DirectorGates.strength(level, DirectorGates.Gate.WAVE_G5), random));
    }

    /** 見張りから: 送り込む時が来た拠点へ送る。 */
    public static void dispatch(ServerLevel level, BaseMemory memory, double day) {
        for (BaseMemory.Base b : List.copyOf(memory.bases())) {
            if (!BaseMemory.wantsWave(b, day)) continue;
            boolean first = BaseMemory.firstRaidPending(b);
            Result r = sendWave(level, b, day, level.getRandom());
            // 見直し ②1: 最初の襲撃が 0 体なら「送った」と数えず、間を空けて試し直す(読み込まれていない / 予算が足りないで間を分ける。夜を過ぎたら次の夜)
            if (first && r.total() == 0) {
                boolean loaded = level.isPositionEntityTicking(BlockPos.containing(b.centerX(), 64.0D, b.centerZ()));
                com.yayuy.overgrownvegetation.data.AssaultTable at = DesignData.director().assault;
                memory.firstRaidRetry(b, day, loaded ? at.firstRetryPoorDays : at.firstRetryUnloadedDays, BaseMemory.timeOfDay(level.getServer()),
                        level.getRandom().nextDouble());
                ForestDirector.get(level).note("拠点 #" + b.id() + " の最初の襲撃が 0 体(" + (loaded ? "予算が足りない" : "拠点が読み込まれていない") + ")。試し直す");
                continue;
            }
            memory.waveSent(b, day, level.getRandom(), DirectorGates.strength(level, DirectorGates.Gate.RAID)); // F5 の導入では送り込みの間が長い(0.3 なら 3.3 倍)
        }
    }
}
