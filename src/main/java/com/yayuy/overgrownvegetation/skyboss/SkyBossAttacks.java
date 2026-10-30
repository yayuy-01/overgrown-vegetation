package com.yayuy.overgrownvegetation.skyboss;

import com.yayuy.overgrownvegetation.effect.SporeInfectionEffects;
import com.yayuy.overgrownvegetation.network.SkyBossShotPayload;
import com.yayuy.overgrownvegetation.registry.ModDamageTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 空の巨大生物の技(サーバー、2026-10-19 ユーザー「一旦全ての攻撃を実装してから」)。{@link SkyBossCombat} が毎 tick、姿勢を出した後に呼ぶ。
 * 動きは 10-17 の決定(行動パターンの「技ごとの動き」)、数値はすべて仮({@link SkyBossTuning} の「技」)。
 *
 * <h2>段階 1 から</h2>
 * <ul>
 *   <li><b>対空砲火の蕾</b>: 相手に向いた腹の側の蕾(段階 3 は全部)が、芯が光る 1 秒の後に開き、胞子の塊を 2 発ずつ撃ち、閉じる。胞子の塊は先回りして飛び、
 *       当たるか寿命で炸裂する(ユーザー指定 10-19: ダメージ + 胞子感染 + 小さな爆発で胞子がばら撒かれる)。</li>
 *   <li><b>樹液の弾</b>: 背の腺 4 つが脈打ってから、相手へ樹液を撃つ(少し落ちる弧)。当たると遅くなる。</li>
 *   <li><b>羽ばたきの下降気流</b>(大きな技): 相手が体より下にいるとき、扇をゆっくり振り上げて一斉に打ち下ろし、扇の下の相手を下へ叩き落とす。</li>
 *   <li><b>雲を吐いて身を隠す</b>(大きな技): 頭を下げて顎を開き、前へ雲を吐く(雲は雲の作業の {@code CloudPuffs}。10-19 ユーザー「雲の作業に口を頼む」)。
 *       進路は変えないので、体は吐いた雲の中へ入っていく。</li>
 *   <li><b>弾を吸う</b>(いつも): 苔に当たった弾は消え、苔が波打つ({@link SkyBossData#hurt})。</li>
 * </ul>
 * <h2>段階 2 から</h2>
 * <ul>
 *   <li><b>触手で打つ・絡め取る</b>: 腹の触手の近くを飛ぶ相手へ。打つ = 鞭のようにしなった触手に触れると、ダメージとはね飛ばし。
 *       絡め取る = 巻いた触手に触れると捕まり、締められてから投げ出される(当たりは見えている触手と同じ形・同じ時刻)。</li>
 *   <li><b>種の爆弾の打ち上げ</b>(大きな技): 地上の相手がいるとき、腹の扉から種を打ち上げ(体が反動で震える)、弧を描いて相手の近くへ落とす。
 *       地面で爆発して汚染する(ユーザー指定 10-19。読み込まれていない所に落ちた種は黙って消える)。</li>
 *   <li><b>大技 1: 転がりながらビーム</b>(大きな技): 戦いの中で日の光が溜まりきると({@link SkyBossBeam#lightRate})、頭から尾へ蕾が開き、
 *       転がりながら全部の蕾からビームを出す。ビームに触れた相手にダメージ(屋根の下は防げる)。終わると蕾が閉じて熱を逃がす雲を噴く。</li>
 *   <li><b>段階が変わるとき</b>: 体じゅうを震わせ(扇も身震い)、垂れ草と胞子を振り落とす。段階 3 は一斉射が全部の蕾・ビームの転がりが速い。</li>
 * </ul>
 * 大きな技はひとつずつ(間 {@link SkyBossTuning#BIG_MOVE_GAP})。放電の間(蕾は閉じる)・ビームの間は蕾を撃たない。ショートの隙の間は技を出さない
 * (ビームの転がりは止めずに、ビームだけ消す)。狙うのはサバイバル・アドベンチャーのプレイヤーだけ。状態は保存しない(読み込み直したら戦いの外から。
 * 日の光の溜まりと、技の時刻は飛び方の値として保存する)。
 */
public final class SkyBossAttacks {

    /** 技で狙う相手 1 人(体の表面からの距離・体のいちばん近い所・体の真ん中の点・速さ)。 */
    record Target(ServerPlayer player, double dist, double[] near, double[] pos, double[] vel) {
    }

    private long combatSince = SkyBossFlight.NONE;
    private long nextVolley;
    private long nextSap;
    private long nextBig;
    private long nextDowndraft;
    private long nextSpit;
    private int nextId = 1;
    final SkyBossShots shots = new SkyBossShots();
    /** 撃つ予定の胞子の塊 {撃つ tick, 蕾}。 */
    private final List<long[]> pendingBud = new ArrayList<>();
    private UUID volleyTarget;
    /** 今の一斉射の蕾と、閉じずに撃ち続けた回数(10-19 夜)。 */
    private int[] volleyBuds = new int[0];
    private int volleyExtends;
    private UUID sapTarget;
    private boolean sapFired = true;
    private boolean spitDone = true;
    private final Set<UUID> gusted = new HashSet<>();
    /**
     * 相手の速さ(先回りの狙い用): 位置の差を毎 tick ならしたもの {前の x・y・z, 速さ x・y・z, 測った tick}。
     * サーバーの {@code getDeltaMovement} には炸裂の吹き飛びの一瞬の速さが乗る(プレイヤーが自分で動いた分は乗らないことがある)
     * (10-19 夜のテストクライアントで、横の速さに吹き飛びの 0.2〜0.4 が一瞬だけ乗っていた。それを 200 tick 先まで延ばさないための用心。当たる割合はほぼ変わらなかった)。
     */
    private final java.util.Map<UUID, double[]> motion = new java.util.HashMap<>();
    private final double[] tmp = new double[3];
    private final double[] tmp2 = new double[3];
    // 段階 2 から
    private long nextTent;
    private long nextSeed;
    private boolean seedDone = true;
    private boolean beamSteamed = true;
    /** 段階が変わったのを見る(0 = まだ見ていない。読み込み直した・呼び出した直後は震えない)。 */
    private int lastStage;
    /** 触手で打たれた相手(1 回の技で 1 人 1 回)。 */
    private final Set<UUID> lashed = new HashSet<>();
    /** 絡め取った相手・触手のカプセル・カプセルの上の割合・次に締める tick。 */
    private UUID grabbed;
    private int grabCap;
    private double grabU;
    private long grabHurtAt;
    /** 触手のカプセルの前の tick の端(振りの速さ・振り抜いた間の当たり)。 */
    private double[] tentPrevA;
    private double[] tentPrevB;
    /** ビームの前の tick の向き(蕾ごと。速く回る間に通り過ぎた相手も当てる)。 */
    private double[] beamPrev;

    /** 確かめ用(devbridge): 次の tick に技 {@code what}(volley・sap・downdraft・spit)を出す(戦いの中・相手がいるとき)。分からなければ false。 */
    public boolean forceNext(String what, long now) {
        switch (what) {
            case "volley" -> nextVolley = now;
            case "sap" -> {
                nextSap = now;
                sapFired = true;
            }
            case "downdraft" -> {
                nextBig = now;
                nextDowndraft = now;
                forcedBig = 0;
            }
            case "spit" -> {
                nextBig = now;
                nextSpit = now;
                forcedBig = 1;
            }
            case "seed" -> {
                nextBig = now;
                nextSeed = now;
                forcedBig = 2;
            }
            case "beam" -> {
                nextBig = now;
                forcedBig = 3;
            }
            case "lash", "grab" -> {
                nextTent = now;
                forcedTent = what.equals("grab") ? 1 : 0;
            }
            default -> {
                return false;
            }
        }
        if (combatSince == SkyBossFlight.NONE) {
            combatSince = now - SkyBossTuning.FIRST_ATTACK_DELAY;
        }
        return true;
    }

    /** 確かめ用: 次の大きな技(0 下降気流・1 雲を吐く・2 種の爆弾・3 ビーム)・触手の技(0 打つ・1 絡め取る)を決め打ち(-1 = 決めない)。 */
    private int forcedBig = -1;
    private int forcedTent = -1;

    public void reset() {
        combatSince = SkyBossFlight.NONE;
        pendingBud.clear();
        shots.list.clear();
        sapFired = true;
        spitDone = true;
        gusted.clear();
        seedDone = true;
        beamSteamed = true;
        lastStage = 0;
        lashed.clear();
        grabbed = null;
        tentPrevA = null;
        tentPrevB = null;
        beamPrev = null;
    }

    void tick(ServerLevel level, SkyBossData data, SkyBossPose ps, List<ServerPlayer> players, long now) {
        SkyBossFlight f = data.flight();
        stepShots(level, players, now);
        trackMotion(players, now);
        if (!ps.hasShapes()) {
            return;
        }
        stageChange(level, data, ps, now);
        // 始めた技の続き(戦いを離れても、始めた技は終わりまで)
        boolean stunned = f.stunUntil != SkyBossFlight.NONE && now < f.stunUntil;
        tentacles(level, ps, f, now, players, stunned);
        beam(level, data, ps, f, now, players, stunned);
        seedRelease(level, data, ps, f, now, players);
        if (!data.inCombat(now)) {
            combatSince = SkyBossFlight.NONE;
            pendingBud.clear();
            return;
        }
        if (combatSince == SkyBossFlight.NONE || combatSince > now) {
            combatSince = now;
            nextVolley = now + SkyBossTuning.FIRST_ATTACK_DELAY;
            nextSap = now + SkyBossTuning.FIRST_ATTACK_DELAY + 60;
            nextBig = now + SkyBossTuning.FIRST_ATTACK_DELAY + 200;
            nextTent = now + SkyBossTuning.FIRST_ATTACK_DELAY;
        }
        // 日の光を溜める(段階 2 から、ビームの間は溜めない)
        if (data.stage() >= SkyBossTuning.BEAM_STAGE && !f.beaming(now) && f.light < 1.0F) {
            f.light = (float) Math.min(1.0, f.light + SkyBossBeam.lightRate(level.isDay(), level.getRainLevel(1.0F), level.getThunderLevel(1.0F), data.stage()));
        }
        List<Target> targets = targets(ps, players, SkyBossTuning.ATTACK_RANGE);
        // 対空砲火は放電と同じ 2000 まで(10-19 夜ユーザー)
        List<Target> aaTargets = targets(ps, players, SkyBossTuning.AA_RANGE);
        fireBuds(level, data, ps, now, aaTargets);
        fireSap(level, ps, now, targets);
        downdraftStrike(level, data, ps, now, targets);
        spitRelease(level, ps, f, now);
        if (stunned) {
            return;
        }
        if (now >= nextTent && data.stage() >= SkyBossTuning.TENT_STAGE && !SkyBossActClock.tentacleActing(f, now) && !f.beaming(now)) {
            startTentacle(level, data, ps, f, now, targets);
        }
        if (targets.isEmpty() && aaTargets.isEmpty() && groundTargets(ps, players).isEmpty()) {
            return;
        }
        boolean budsBusy = data.discharge().busy() || f.beaming(now);
        if (!budsBusy && now >= nextVolley && f.budsPhase(now) == 0 && !aaTargets.isEmpty()) {
            startVolley(level, data, ps, now, aaTargets);
        }
        if (now >= nextSap && sapFired && !targets.isEmpty()) {
            Target t = nearest(targets);
            sapTarget = t.player().getUUID();
            f.sapAt = now;
            sapFired = false;
            nextSap = now + SkyBossTuning.SAP_INTERVAL;
            data.sync(level);
        }
        if (now >= nextBig && !data.discharge().busy() && !f.beaming(now) && !f.downdrafting(now) && !SkyBossActClock.bodyActing(f, now)
                && seedDone && (f.seedAt == SkyBossFlight.NONE || now > f.seedAt + SkyBossTuning.SEED_RELEASE)) {
            startBigMove(level, data, ps, now, targets, players);
        }
    }

    // ------------------------------------------------------------------ 相手
    /** 相手の速さを測る(毎 tick)。テレポート(1 tick に {@link SkyBossTuning#TRACK_JUMP} より動いた)と、しばらく見なかった相手は 0 から。 */
    private void trackMotion(List<ServerPlayer> players, long now) {
        for (ServerPlayer p : players) {
            double[] m = motion.get(p.getUUID());
            double x = p.getX();
            double y = p.getY();
            double z = p.getZ();
            if (m == null || now - (long) m[6] > 5L || now <= (long) m[6]) {
                if (m == null || now != (long) m[6]) {
                    motion.put(p.getUUID(), new double[]{x, y, z, 0.0, 0.0, 0.0, now});
                }
                continue;
            }
            double dt = now - (long) m[6];
            double vx = (x - m[0]) / dt;
            double vy = (y - m[1]) / dt;
            double vz = (z - m[2]) / dt;
            if (vx * vx + vy * vy + vz * vz > SkyBossTuning.TRACK_JUMP * SkyBossTuning.TRACK_JUMP) {
                m[3] = 0.0;
                m[4] = 0.0;
                m[5] = 0.0;
            } else {
                double k = 1.0 - Math.pow(1.0 - SkyBossTuning.TRACK_SMOOTH, dt);
                m[3] += (vx - m[3]) * k;
                m[4] += (vy - m[4]) * k;
                m[5] += (vz - m[5]) * k;
            }
            m[0] = x;
            m[1] = y;
            m[2] = z;
            m[6] = now;
        }
        if ((now & 255L) == 0L) {
            motion.values().removeIf(m -> now - (long) m[6] > 200L);
        }
    }

    private double[] velOf(ServerPlayer p) {
        double[] m = motion.get(p.getUUID());
        return m == null ? new double[3] : new double[]{m[3], m[4], m[5]};
    }

    private List<Target> targets(SkyBossPose ps, List<ServerPlayer> players, double range) {
        List<Target> out = new ArrayList<>();
        for (ServerPlayer p : players) {
            if (p.isSpectator() || p.isCreative() || !p.isAlive()) {
                continue;
            }
            double cx = p.getX();
            double cy = p.getY() + p.getBbHeight() * 0.5;
            double cz = p.getZ();
            double[] near = new double[3];
            double d = ps.nearestPoint(cx, cy, cz, near);
            if (d > range) {
                continue;
            }
            out.add(new Target(p, d, near, new double[]{cx, cy, cz}, velOf(p)));
        }
        return out;
    }

    private static Target nearest(List<Target> targets) {
        Target best = targets.get(0);
        for (Target t : targets) {
            if (t.dist() < best.dist()) {
                best = t;
            }
        }
        return best;
    }

    private static Target find(List<Target> targets, UUID id) {
        for (Target t : targets) {
            if (t.player().getUUID().equals(id)) {
                return t;
            }
        }
        return targets.isEmpty() ? null : nearest(targets);
    }

    // ------------------------------------------------------------------ 対空砲火の蕾
    /** 腹の側の蕾(腹が上のとき空を向く。道具の SkyGlaucusAttacks.skyBud = 13〜20 番)。段階 1・2 で開くのはこれだけ。 */
    public static boolean skyBud(String name) {
        if (name.length() < 5) {
            return false;
        }
        try {
            int id = Integer.parseInt(name.substring(3, 5));
            return id >= 13 && id <= 20;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private void startVolley(ServerLevel level, SkyBossData data, SkyBossPose ps, long now, List<Target> targets) {
        SkyBossShapes s = SkyBossShapes.get();
        SkyBossFlight f = data.flight();
        Target t = nearest(targets);
        boolean all = data.stage() >= 3;
        int want = all ? SkyBossTuning.AA_BUDS_STAGE3 : SkyBossTuning.AA_BUDS;
        List<double[]> cand = new ArrayList<>();
        List<double[]> other = new ArrayList<>();
        for (int i = 0; i < s.budCount; i++) {
            if (!data.parts().budSolid(i)) {
                continue;
            }
            boolean sky = all || skyBud(s.budName[i]);
            int o = i * 3;
            double dx = t.pos()[0] - ps.spC[o];
            double dy = t.pos()[1] - ps.spC[o + 1];
            double dz = t.pos()[2] - ps.spC[o + 2];
            double l = Math.max(1.0E-6, Math.sqrt(dx * dx + dy * dy + dz * dz));
            ps.budOutward(i, tmp);
            double facing = (tmp[0] * dx + tmp[1] * dy + tmp[2] * dz) / l;
            if (facing > 0.1) {
                (sky ? cand : other).add(new double[]{i, l});
            }
        }
        // 10-19 夜: 腹の側の蕾で足りないとき(相手が体の横・下など)は、相手を向くほかの蕾も開いて撃つ(全部の蕾を開くアニメ)
        boolean widened = false;
        if (!all && cand.size() < want && !other.isEmpty()) {
            other.sort((a, b) -> Double.compare(a[1], b[1]));
            for (int k = 0; k < other.size() && cand.size() < want; k++) {
                cand.add(other.get(k));
            }
            widened = true;
        }
        if (cand.isEmpty()) {
            nextVolley = now + 40;
            return;
        }
        cand.sort((a, b) -> Double.compare(a[1], b[1]));
        RandomSource r = level.getRandom();
        // 開き始め(芯が光った後)。蕾ごとに開ききった所から撃ち始める(queueShots)。撃つ間の終わりは全部の蕾が開ききってから
        long openAt = now + SkyBossTuning.AA_GLOW_TICKS;
        long fire = openAt + SkyBossTuning.BUDS_OPEN_TICKS;
        int n = Math.min(want, cand.size());
        volleyBuds = new int[n];
        for (int k = 0; k < n; k++) {
            volleyBuds[k] = (int) cand.get(k)[0];
        }
        volleyExtends = 0;
        queueShots(openAt, fire + SkyBossTuning.AA_FIRE_TICKS, r, true);
        volleyTarget = t.player().getUUID();
        if (DEBUG_LOG) {
            com.yayuy.overgrownvegetation.OvergrownVegetation.LOGGER.info("[sky boss] volley: {} buds (of {} facing) at {}", n, cand.size(), t.player().getName().getString());
        }
        f.budsAt = now;
        f.budsKind = all || widened ? 1 : 0;
        f.budsEnd = fire + SkyBossTuning.AA_FIRE_TICKS + SkyBossTuning.BUDS_CLOSE_TICKS;
        nextVolley = f.budsEnd + SkyBossTuning.AA_INTERVAL;
        data.sync(level);
    }

    /**
     * 今の一斉射の蕾ごとに、{@code until} まで {@link SkyBossTuning#AA_SHOT_GAP} おきに撃つ予定を入れる。{@code opening} のときは {@code from} = 開き始めで、
     * 蕾ごとにその蕾が開ききった所({@link #budOpenTicks})+ 少しの乱数から撃ち始める(10-19 夜ユーザー「各々がバラバラなタイミングで撃ち始める」)。
     */
    private void queueShots(long from, long until, RandomSource r, boolean opening) {
        SkyBossShapes s = SkyBossShapes.get();
        int gap = SkyBossTuning.AA_SHOT_GAP;
        for (int bud : volleyBuds) {
            long t = from + 2 + r.nextInt(gap);
            if (opening && s != null) {
                t = from + Math.round(budOpenTicks(s.budName[bud])) + r.nextInt(gap * 2);
            }
            for (; t < until; t += gap) {
                pendingBud.add(new long[]{t + r.nextInt(7) - 3, bud});
            }
        }
    }

    /**
     * 蕾が開き始めてから開ききるまで(tick)。道具の開くアニメと同じ式: 元の秒 1.0 + ずれ({@code hash(左の蕾の名前, 2)} × {@link SkyBossTuning#BUD_OPEN_SPREAD})
     * × 時間の倍率 + 蕾の層の補間 4。右の蕾は左の写し(道具が左のアニメを鏡写しする)。
     */
    public static double budOpenTicks(String budName) {
        String left = budName.endsWith("_r") ? budName.substring(0, budName.length() - 2) + "_l" : budName;
        return (1.0 + SkyBossTuning.BUD_OPEN_SPREAD * kitHash(left, 2)) * SkyBossTuning.ANIM_SLOW * 20.0 + 4.0;
    }

    /** 道具の SkyGlaucusAttacks.hash と同じ式(文字列の hashCode から 0〜1)。 */
    static double kitHash(String s, int salt) {
        long h = s.hashCode() * 0x9E3779B97F4A7C15L + salt * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        return (h >>> 11) / (double) (1L << 53);
    }

    /**
     * 撃ち終わる前に、まだ相手が技の距離の中にいれば閉じずに撃ち続ける(10-19 夜ユーザー「クールダウンの短縮」。最大 {@link SkyBossTuning#AA_EXTEND_MAX} 回)。
     */
    private void extendVolley(ServerLevel level, SkyBossData data, long now, List<Target> targets) {
        SkyBossFlight f = data.flight();
        if (f.budsKind == SkyBossBeam.BUDS_KIND || f.budsPhase(now) != 3 || volleyBuds.length == 0 || volleyExtends >= SkyBossTuning.AA_EXTEND_MAX
                || now != f.budsEnd - SkyBossTuning.BUDS_CLOSE_TICKS - 4 || data.discharge().busy() || f.beaming(now)) {
            return;
        }
        if (targets.isEmpty()) {
            return;
        }
        volleyExtends++;
        f.budsEnd += SkyBossTuning.AA_FIRE_TICKS;
        queueShots(now + 4, f.budsEnd - SkyBossTuning.BUDS_CLOSE_TICKS, level.getRandom(), false);
        nextVolley = f.budsEnd + SkyBossTuning.AA_INTERVAL;
        data.sync(level);
    }

    private void fireBuds(ServerLevel level, SkyBossData data, SkyBossPose ps, long now, List<Target> targets) {
        extendVolley(level, data, now, targets);
        if (pendingBud.isEmpty()) {
            return;
        }
        RandomSource r = level.getRandom();
        for (int k = pendingBud.size() - 1; k >= 0; k--) {
            long[] p = pendingBud.get(k);
            if (p[0] > now) {
                continue;
            }
            pendingBud.remove(k);
            int bud = (int) p[1];
            Target t = find(targets, volleyTarget);
            if (t == null || !data.parts().budSolid(bud) || data.discharge().busy()) {
                continue;
            }
            int o = bud * 3;
            double[] from = {ps.spC[o], ps.spC[o + 1], ps.spC[o + 2]};
            // 10-19 夜: 6 割はしっかり狙い・3 割はまわりにばらけ・1 割は離れた所へ。時限信管は弾が着くときの相手の位置までの距離 ± 100 で炸裂(WW2 の戦艦の対空砲火のように。狙った弾は手前では炸裂しない)。
            // 近接の信管(相手の 5 の中を通ったら炸裂)はどの弾にも
            int[] lk = SkyBossShots.aimFlak(from, t.pos(), t.vel(), SkyBossTuning.AA_SPEED, r::nextDouble, tmp);
            double dist = Math.sqrt(sq(t.pos()[0] - from[0]) + sq(t.pos()[1] - from[1]) + sq(t.pos()[2] - from[2]));
            int life = Math.min(SkyBossTuning.AA_LIFE, lk[0]);
            if (DEBUG_LOG) {
                com.yayuy.overgrownvegetation.OvergrownVegetation.LOGGER.info("[sky boss] fire bud {} shot {} aim {} from ({}, {}, {}) at ({}, {}, {}) vel ({}, {}, {}) dist {} life {}", bud, nextId, lk[1],
                        Math.round(from[0]), Math.round(from[1]), Math.round(from[2]), Math.round(t.pos()[0]), Math.round(t.pos()[1]), Math.round(t.pos()[2]),
                        String.format("%.2f", t.vel()[0]), String.format("%.2f", t.vel()[1]), String.format("%.2f", t.vel()[2]), Math.round(dist), life);
            }
            spawn(level, SkyBossShots.SPORE, from, tmp, now, life);
        }
    }

    // ------------------------------------------------------------------ 樹液の弾
    private void fireSap(ServerLevel level, SkyBossPose ps, long now, List<Target> targets) {
        SkyBossFlight f = SkyBossData.get(level).flight();
        if (sapFired || now < f.sapAt + SkyBossTuning.SAP_RELEASE) {
            return;
        }
        sapFired = true;
        Target t = find(targets, sapTarget);
        if (t == null) {
            return;
        }
        RandomSource r = level.getRandom();
        for (String side : new String[]{"_l", "_r"}) {
            for (int g = 1; g <= 2; g++) {
                if (!ps.spotWorld("gland" + g + side, tmp2)) {
                    continue;
                }
                double[] from = tmp2.clone();
                SkyBossShots.lead(from, t.pos(), t.vel(), SkyBossTuning.SAP_SPEED, tmp);
                // 落ちる分だけ少し上へ(届くまでの時間 × 重さの半分)
                double dist = Math.sqrt(sq(t.pos()[0] - from[0]) + sq(t.pos()[1] - from[1]) + sq(t.pos()[2] - from[2]));
                double ticks = dist / SkyBossTuning.SAP_SPEED;
                tmp[1] += SkyBossShots.gravity(SkyBossShots.SAP) * ticks * 0.5;
                jitter(tmp, SkyBossTuning.AA_SPREAD * 1.5, r);
                spawn(level, SkyBossShots.SAP, from, tmp, now, SkyBossTuning.SAP_LIFE);
            }
        }
        level.playSound(null, tmp2[0], tmp2[1], tmp2[2], net.minecraft.sounds.SoundEvents.HONEY_BLOCK_SLIDE, net.minecraft.sounds.SoundSource.HOSTILE, 12.0F, 0.5F);
    }

    // ------------------------------------------------------------------ 大きな技
    private void startBigMove(ServerLevel level, SkyBossData data, SkyBossPose ps, long now, List<Target> targets, List<ServerPlayer> players) {
        SkyBossFlight f = data.flight();
        boolean below = false;
        for (Target t : targets) {
            if (t.pos()[1] < t.near()[1] - 20.0) {
                below = true;
                break;
            }
        }
        int stage = data.stage();
        List<Integer> options = new ArrayList<>();
        if (below && now >= nextDowndraft) {
            options.add(0);
        }
        if (now >= nextSpit && !targets.isEmpty()) {
            options.add(1);
        }
        if (stage >= SkyBossTuning.SEED_STAGE && now >= nextSeed && !groundTargets(ps, players).isEmpty()) {
            options.add(2);
        }
        // ビーム: 日の光が溜まりきっていて、腹が上のまま渡っているとき(雷雲に寄る・留まる・転がる途中は出さない = 転がりが重ならないように)
        // 10-19 夜ユーザー: 体の中心付近の横に相手がいるときだけ(頭の近くでは出さない)
        boolean beamReady = stage >= SkyBossTuning.BEAM_STAGE && f.light >= 1.0F && data.feeding().mode == SkyBossFeeding.CRUISE
                && !f.rolling(now) && !f.backUp(now) && beamZone(ps, players);
        if (forcedBig == 3) {
            beamReady = data.feeding().mode == SkyBossFeeding.CRUISE && !f.rolling(now) && !f.backUp(now);
        }
        if (forcedBig == 0 && !options.contains(0)) {
            options.add(0);
        }
        if (forcedBig == 2 && !options.contains(2)) {
            options.add(2);
        }
        if (options.isEmpty() && !beamReady) {
            nextBig = now + 40;
            return;
        }
        // 溜まりきったらビームを先に(決定「晴れ = 日の光をよく吸うので大技 1 が早く強い」、ユーザー「晴れの日はおそらくビーム主体」)
        int pick = beamReady ? 3 : options.get(level.getRandom().nextInt(options.size()));
        if (forcedBig >= 0) {
            if (forcedBig == 3 && !beamReady) {
                if (DEBUG_LOG) {
                    com.yayuy.overgrownvegetation.OvergrownVegetation.LOGGER.info("[sky boss] forced beam dropped: not cruising belly-up (feeding {}, rolling {}, back up {})",
                            data.feeding().mode, f.rolling(now), f.backUp(now));
                }
                forcedBig = -1;
                nextBig = now + 40;
                return;
            }
            pick = forcedBig;
            forcedBig = -1;
        }
        if (pick == 3) {
            SkyBossBeam.start(f, now, stage >= 3 ? SkyBossTuning.BEAM_TURNS_STAGE3 : SkyBossTuning.BEAM_TURNS);
            f.light = 0.0F;
            pendingBud.clear();
            beamSteamed = false;
            beamPrev = null;
            nextBig = f.beamEnd + SkyBossTuning.BIG_MOVE_GAP;
            nextVolley = Math.max(nextVolley, f.beamEnd + SkyBossTuning.AA_INTERVAL);
            if (ps.spotWorld("bay_seed", tmp)) {
                level.playSound(null, tmp[0], tmp[1], tmp[2], net.minecraft.sounds.SoundEvents.BEACON_ACTIVATE, net.minecraft.sounds.SoundSource.HOSTILE, 40.0F, 0.5F);
            }
            data.sync(level);
            return;
        }
        if (pick == 2) {
            f.seedAt = now;
            seedDone = false;
            nextSeed = now + SkyBossTuning.SEED_INTERVAL;
            nextBig = now + SkyBossTuning.SEED_RELEASE + SkyBossTuning.BIG_MOVE_GAP;
            data.sync(level);
            return;
        }
        if (pick == 0) {
            f.downdraftAt = now;
            gusted.clear();
            nextDowndraft = now + SkyBossTuning.DOWNDRAFT_COOLDOWN;
            nextBig = now + (long) (SkyBossFanClock.TRANSITION + SkyBossFanClock.length(SkyBossFanClock.DOWNDRAFT)) + SkyBossTuning.BIG_MOVE_GAP;
        } else {
            SkyBossActClock.startSpit(f, now);
            spitDone = false;
            nextSpit = now + SkyBossTuning.SPIT_COOLDOWN;
            nextBig = now + (long) (SkyBossTuning.BODY_TRANSITION + SkyBossActClock.bodyLength(SkyBossActClock.SPIT)) + SkyBossTuning.BIG_MOVE_GAP;
        }
        data.sync(level);
    }

    /** 下降気流: 打ち下ろす間、扇の下の相手を下へ叩き落とす(1 回の技で 1 人 1 回)。打ち下ろしの頭に扇の先から雲の尾。 */
    private void downdraftStrike(ServerLevel level, SkyBossData data, SkyBossPose ps, long now, List<Target> targets) {
        SkyBossFlight f = data.flight();
        if (f.downdraftAt == SkyBossFlight.NONE) {
            return;
        }
        long from = f.downdraftAt + SkyBossFanClock.TRANSITION + SkyBossTuning.DOWNDRAFT_STRIKE_FROM;
        long to = f.downdraftAt + SkyBossFanClock.TRANSITION + SkyBossTuning.DOWNDRAFT_STRIKE_TO;
        if (now < from || now > to) {
            return;
        }
        if (now == from) {
            SkyBossEffects.downdraftGust(level, ps);
        }
        for (Target t : targets) {
            if (gusted.contains(t.player().getUUID())) {
                continue;
            }
            double[] c = nearestSliceCenter(ps, t.pos());
            double dh = Math.hypot(t.pos()[0] - c[0], t.pos()[2] - c[2]);
            double dy = c[1] - t.pos()[1];
            if (dh > SkyBossTuning.DOWNDRAFT_REACH || dy < 0.0 || dy > SkyBossTuning.DOWNDRAFT_DEPTH) {
                continue;
            }
            gusted.add(t.player().getUUID());
            ServerPlayer p = t.player();
            Vec3 v = p.getDeltaMovement();
            p.setDeltaMovement(v.x * 0.5, Math.min(v.y, 0.0) - SkyBossTuning.DOWNDRAFT_PUSH, v.z * 0.5);
            p.hurtMarked = true;
            p.hurt(ModDamageTypes.skyBossGust(level), SkyBossTuning.DOWNDRAFT_DAMAGE);
        }
    }

    /** 雲を吐く: 顎が開いた所で、頭の前へ雲を出す(体は進路のまま入っていく)。 */
    private void spitRelease(ServerLevel level, SkyBossPose ps, SkyBossFlight f, long now) {
        if (spitDone || f.spitAt == SkyBossFlight.NONE || now < f.spitAt + SkyBossTuning.SPIT_RELEASE) {
            return;
        }
        spitDone = true;
        if (!ps.spotWorld("maw", tmp)) {
            return;
        }
        double[] fw = f.forward();
        double x = tmp[0] + fw[0] * SkyBossTuning.SPIT_RADIUS * 0.4;
        double y = tmp[1] - 60.0;
        double z = tmp[2] + fw[2] * SkyBossTuning.SPIT_RADIUS * 0.4;
        com.yayuy.overgrownvegetation.sky.CloudPuffs.puff(level, x, y, z, (float) SkyBossTuning.SPIT_RADIUS, (float) SkyBossTuning.SPIT_HEIGHT, SkyBossTuning.SPIT_LIFE);
        SkyBossEffects.spitMist(level, tmp[0], tmp[1], tmp[2], fw);
    }

    private static double[] nearestSliceCenter(SkyBossPose ps, double[] p) {
        double best = Double.MAX_VALUE;
        double[] c = new double[3];
        for (int i = 0; i < ps.sliceCount; i++) {
            int o = i * 3;
            double x = ps.sA[o] + ps.sEz[o] * ps.sLen[i] * 0.5;
            double y = ps.sA[o + 1] + ps.sEz[o + 1] * ps.sLen[i] * 0.5;
            double z = ps.sA[o + 2] + ps.sEz[o + 2] * ps.sLen[i] * 0.5;
            double d = sq(p[0] - x) + sq(p[2] - z);
            if (d < best) {
                best = d;
                c[0] = x;
                c[1] = y;
                c[2] = z;
            }
        }
        return c;
    }

    // ------------------------------------------------------------------ 段階が変わるとき
    /** 決定「体じゅうを震わせて垂れ草と胞子を振り落とし、開く蕾が増える」(開く蕾は一斉射が段階 3 で全部になる)。体力が戻って段階が下がったときは震えない。 */
    private void stageChange(ServerLevel level, SkyBossData data, SkyBossPose ps, long now) {
        int stage = data.stage();
        if (lastStage == 0) {
            lastStage = stage;
            return;
        }
        if (stage > lastStage) {
            SkyBossFlight f = data.flight();
            f.shakeAt = now;
            f.shakeKind = SkyBossFlight.SHAKE_STAGE;
            SkyBossEffects.stageShower(level, ps);
            data.sync(level);
        }
        lastStage = stage;
    }

    // ------------------------------------------------------------------ 触手で打つ・絡め取る
    /** 触手の技を始める: 腹の触手(の当たりの形)の近くに相手がいるとき。捕まえている相手がいなければ半々で絡め取る。 */
    private void startTentacle(ServerLevel level, SkyBossData data, SkyBossPose ps, SkyBossFlight f, long now, List<Target> targets) {
        int kind = forcedTent;
        if (kind < 0) {
            boolean near = false;
            for (Target t : targets) {
                if (tentDistance(ps, t.pos(), false) < SkyBossTuning.TENT_TRIGGER) {
                    near = true;
                    break;
                }
            }
            if (!near) {
                nextTent = now + 20;
                return;
            }
            kind = grabbed == null && level.getRandom().nextBoolean() ? 1 : 0;
        }
        forcedTent = -1;
        SkyBossActClock.startTentacle(f, now, kind);
        lashed.clear();
        nextTent = (long) Math.ceil(SkyBossActClock.tentEnd(f)) + SkyBossTuning.TENT_COOLDOWN;
        data.sync(level);
    }

    /**
     * 転がりのビームを出す所に相手がいるか: 相手を体の軸(頭の道の点)へ落とした所が、腕の付いた所の真ん中から前後 {@link SkyBossTuning#BEAM_ZONE_HALF} の中で、
     * 軸から {@link SkyBossTuning#BEAM_ZONE_DIST} より近い(体の上下左右どちらでも = ビームは体のまわりを 360° 掃く)。
     */
    static boolean beamZone(SkyBossPose ps, List<ServerPlayer> players) {
        for (ServerPlayer p : players) {
            if (!targetable(p)) {
                continue;
            }
            double[] c = center(p);
            double[] ad = alongAxis(ps, c);
            if (Math.abs(ad[0] - SkyBossBody.ARM_CENTER) <= SkyBossTuning.BEAM_ZONE_HALF && ad[1] <= SkyBossTuning.BEAM_ZONE_DIST) {
                return true;
            }
        }
        return false;
    }

    /** 点 p を体の軸(頭の道の点の折れ線)へ落とした {頭の先からの道の距離, 軸からの距離}。 */
    public static double[] alongAxis(SkyBossPose ps, double[] p) {
        double best = Double.MAX_VALUE;
        double along = 0.0;
        double[][] pts = ps.points;
        for (int i = 0; i + 1 < pts.length; i++) {
            double[] a = pts[i];
            double[] b = pts[i + 1];
            double dx = b[0] - a[0];
            double dy = b[1] - a[1];
            double dz = b[2] - a[2];
            double ll = dx * dx + dy * dy + dz * dz;
            double u = ll < 1.0E-9 ? 0.0 : Math.max(0.0, Math.min(1.0, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy + (p[2] - a[2]) * dz) / ll));
            double d = Math.sqrt(sq(p[0] - a[0] - dx * u) + sq(p[1] - a[1] - dy * u) + sq(p[2] - a[2] - dz * u));
            if (d < best) {
                best = d;
                along = SkyBossPose.DIST[i] + (SkyBossPose.DIST[i + 1] - SkyBossPose.DIST[i]) * u;
            }
        }
        return new double[]{along, best};
    }

    /** 点 p から腹の触手のカプセルの表面までのいちばん近い距離({@code outer} = 先の半分だけ)。 */
    private static double tentDistance(SkyBossPose ps, double[] p, boolean outer) {
        int first = ps.tentFirstCapsule();
        int each = ps.tentCapsulesEach();
        double best = Double.MAX_VALUE;
        for (int tn = 0; tn < ps.tentCount(); tn++) {
            for (int k = outer ? each / 2 : 0; k < each; k++) {
                int c = first + tn * each + k;
                best = Math.min(best, segDistance(p, ps.cA, ps.cB, c) - ps.cR[c]);
            }
        }
        return best;
    }

    /**
     * 触手の技の続き: 打つ間は触れた相手にダメージとはね飛ばし、絡め取る間は捕まえて締め、放すときに投げ出す。
     * 速く振る触手が 1 tick に相手を通り越さないよう、前の tick の形とその間の形でも当たりを見る。
     */
    private void tentacles(ServerLevel level, SkyBossPose ps, SkyBossFlight f, long now, List<ServerPlayer> players, boolean stunned) {
        int first = ps.tentFirstCapsule();
        int caps = ps.tentCount() * ps.tentCapsulesEach();
        if (first < 0 || caps <= 0) {
            return;
        }
        if (tentPrevA == null || tentPrevA.length != caps * 3) {
            tentPrevA = new double[caps * 3];
            tentPrevB = new double[caps * 3];
            System.arraycopy(ps.cA, first * 3, tentPrevA, 0, caps * 3);
            System.arraycopy(ps.cB, first * 3, tentPrevB, 0, caps * 3);
        }
        boolean acting = SkyBossActClock.tentacleActing(f, now);
        long age = acting ? now - f.tentAt : -1;
        if (acting && f.tentKind == 0 && !stunned && age >= SkyBossTuning.LASH_FROM && age <= SkyBossTuning.LASH_TO) {
            for (ServerPlayer p : players) {
                if (!targetable(p) || lashed.contains(p.getUUID())) {
                    continue;
                }
                int c = touching(ps, first, caps, center(p), SkyBossTuning.LASH_MARGIN, false);
                if (c < 0) {
                    continue;
                }
                lashed.add(p.getUUID());
                if (DEBUG_LOG) {
                    com.yayuy.overgrownvegetation.OvergrownVegetation.LOGGER.info("[sky boss] lash hit {} at {} ticks (capsule {})", p.getName().getString(), age, c);
                }
                double[] mv = capsuleMotion(ps, first, c);
                p.hurt(ModDamageTypes.skyBossLash(level), SkyBossTuning.LASH_DAMAGE);
                p.setDeltaMovement(mv[0] * SkyBossTuning.LASH_KNOCK, mv[1] * SkyBossTuning.LASH_KNOCK + 0.4, mv[2] * SkyBossTuning.LASH_KNOCK);
                p.hurtMarked = true;
                p.stopFallFlying();
                level.playSound(null, p.getX(), p.getY(), p.getZ(), net.minecraft.sounds.SoundEvents.PLAYER_ATTACK_KNOCKBACK, net.minecraft.sounds.SoundSource.HOSTILE, 4.0F, 0.5F);
            }
        }
        if (acting && f.tentKind == 1 && grabbed == null && !stunned && age >= SkyBossTuning.GRAB_FROM && age <= SkyBossTuning.GRAB_TO) {
            for (ServerPlayer p : players) {
                if (!targetable(p)) {
                    continue;
                }
                double[] pc = center(p);
                int c = touching(ps, first, caps, pc, SkyBossTuning.GRAB_MARGIN, true);
                if (c < 0) {
                    continue;
                }
                grabbed = p.getUUID();
                grabCap = c;
                grabU = segParam(pc, ps.cA, ps.cB, c);
                grabHurtAt = now;
                p.stopFallFlying();
                if (DEBUG_LOG) {
                    com.yayuy.overgrownvegetation.OvergrownVegetation.LOGGER.info("[sky boss] grab {} at {} ticks (capsule {}, u {})", p.getName().getString(), age, c, String.format("%.2f", grabU));
                }
                level.playSound(null, p.getX(), p.getY(), p.getZ(), net.minecraft.sounds.SoundEvents.SLIME_SQUISH, net.minecraft.sounds.SoundSource.HOSTILE, 6.0F, 0.4F);
                break;
            }
        }
        if (grabbed != null) {
            hold(level, ps, f, now, first, acting, age, stunned);
        }
        System.arraycopy(ps.cA, first * 3, tentPrevA, 0, caps * 3);
        System.arraycopy(ps.cB, first * 3, tentPrevB, 0, caps * 3);
    }

    /** 絡め取った相手を触手の表面に留め、締め、放す時刻に投げ出す。 */
    private void hold(ServerLevel level, SkyBossPose ps, SkyBossFlight f, long now, int first, boolean acting, long age, boolean stunned) {
        net.minecraft.world.entity.player.Player pl = level.getPlayerByUUID(grabbed);
        ServerPlayer p = pl instanceof ServerPlayer sp ? sp : null;
        int c = grabCap;
        int o = c * 3;
        double ax = ps.cA[o] + (ps.cB[o] - ps.cA[o]) * grabU;
        double ay = ps.cA[o + 1] + (ps.cB[o + 1] - ps.cA[o + 1]) * grabU;
        double az = ps.cA[o + 2] + (ps.cB[o + 2] - ps.cA[o + 2]) * grabU;
        if (p == null || !targetable(p) || stunned || !acting || f.tentKind != 1) {
            grabbed = null;
            return;
        }
        double[] pc = center(p);
        double dx = pc[0] - ax;
        double dy = pc[1] - ay;
        double dz = pc[2] - az;
        double dl = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dl > ps.cR[c] + 40.0) {
            // 遠くへ移った(テレポートなど)
            grabbed = null;
            return;
        }
        if (age >= SkyBossTuning.GRAB_RELEASE) {
            double[] mv = capsuleMotion(ps, first, c);
            double ml = Math.sqrt(mv[0] * mv[0] + mv[1] * mv[1] + mv[2] * mv[2]);
            if (ml < 1.0E-3) {
                mv[0] = dx / Math.max(1.0E-6, dl);
                mv[1] = dy / Math.max(1.0E-6, dl);
                mv[2] = dz / Math.max(1.0E-6, dl);
            }
            p.setDeltaMovement(mv[0] * SkyBossTuning.GRAB_FLING, mv[1] * SkyBossTuning.GRAB_FLING + 0.3, mv[2] * SkyBossTuning.GRAB_FLING);
            p.hurtMarked = true;
            grabbed = null;
            if (DEBUG_LOG) {
                com.yayuy.overgrownvegetation.OvergrownVegetation.LOGGER.info("[sky boss] grab released at {} ticks", age);
            }
            return;
        }
        // 触手の表面(軸から相手の側へ太さ + 1)へ引き寄せる
        double k = (ps.cR[c] + 1.0) / Math.max(1.0E-6, dl);
        double tx = ax + dx * k;
        double ty = ay + dy * k;
        double tz = az + dz * k;
        double vx = (tx - pc[0]) * 0.8;
        double vy = (ty - pc[1]) * 0.8;
        double vz = (tz - pc[2]) * 0.8;
        double vl = Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (vl > 10.0) {
            vx *= 10.0 / vl;
            vy *= 10.0 / vl;
            vz *= 10.0 / vl;
        }
        if (vl > 16.0) {
            // 締める触手は 1 tick に 10 ブロックほど動く: 離れすぎたら表面へ移す(毎 tick ではない)
            p.teleportTo(tx, ty - p.getBbHeight() * 0.5, tz);
        }
        p.setDeltaMovement(vx, vy, vz);
        p.hurtMarked = true;
        p.fallDistance = 0.0F;
        if (now - grabHurtAt >= SkyBossTuning.GRAB_DAMAGE_EVERY) {
            grabHurtAt = now;
            p.hurt(ModDamageTypes.skyBossLash(level), SkyBossTuning.GRAB_DAMAGE);
        }
    }

    /** 点 p に触れている触手のカプセル(今・前の tick・その間の形で、表面から {@code margin} の中)。無ければ -1。 */
    private int touching(SkyBossPose ps, int first, int caps, double[] p, double margin, boolean outer) {
        int each = ps.tentCapsulesEach();
        for (int i = 0; i < caps; i++) {
            if (outer && i % each < each / 2) {
                continue;
            }
            int c = first + i;
            double r = ps.cR[c] + margin;
            if (segDistance(p, ps.cA, ps.cB, c) < r) {
                return c;
            }
            // 前の tick の形・その間の形(触手の先は 1 tick に 30 ブロックほど動くので、太さより細かく分けて)
            int o = i * 3;
            int oc = c * 3;
            double move = Math.sqrt(sq(ps.cB[oc] - tentPrevB[o]) + sq(ps.cB[oc + 1] - tentPrevB[o + 1]) + sq(ps.cB[oc + 2] - tentPrevB[o + 2]));
            int n = Math.max(1, Math.min(12, (int) Math.ceil(move / Math.max(1.0, r))));
            for (int j = 0; j < n; j++) {
                double u = j / (double) n;
                double[] a = {tentPrevA[o] + (ps.cA[oc] - tentPrevA[o]) * u, tentPrevA[o + 1] + (ps.cA[oc + 1] - tentPrevA[o + 1]) * u,
                        tentPrevA[o + 2] + (ps.cA[oc + 2] - tentPrevA[o + 2]) * u};
                double[] b = {tentPrevB[o] + (ps.cB[oc] - tentPrevB[o]) * u, tentPrevB[o + 1] + (ps.cB[oc + 1] - tentPrevB[o + 1]) * u,
                        tentPrevB[o + 2] + (ps.cB[oc + 2] - tentPrevB[o + 2]) * u};
                if (segDistance(p, a, b) < r) {
                    return c;
                }
            }
        }
        return -1;
    }

    /** カプセル c の真ん中の、前の tick からの動きの向き(単位ベクトル。動いていなければ 0)。 */
    private double[] capsuleMotion(SkyBossPose ps, int first, int c) {
        int o = (c - first) * 3;
        int oc = c * 3;
        double[] mv = new double[3];
        for (int i = 0; i < 3; i++) {
            mv[i] = (ps.cA[oc + i] + ps.cB[oc + i]) * 0.5 - (tentPrevA[o + i] + tentPrevB[o + i]) * 0.5;
        }
        double l = Math.sqrt(mv[0] * mv[0] + mv[1] * mv[1] + mv[2] * mv[2]);
        if (l > 1.0E-6) {
            mv[0] /= l;
            mv[1] /= l;
            mv[2] /= l;
        }
        return mv;
    }

    // ------------------------------------------------------------------ 種の爆弾
    /** 地上の相手: 体のいちばん近い所よりずっと下にいて、横へ遠すぎない(技の距離 {@link SkyBossTuning#ATTACK_RANGE} の外でも狙う)。 */
    private List<Target> groundTargets(SkyBossPose ps, List<ServerPlayer> players) {
        List<Target> out = new ArrayList<>();
        for (ServerPlayer p : players) {
            if (!targetable(p)) {
                continue;
            }
            double[] c = center(p);
            double[] near = new double[3];
            double d = ps.nearestPoint(c[0], c[1], c[2], near);
            if (c[1] > near[1] - SkyBossTuning.SEED_BELOW || Math.hypot(c[0] - near[0], c[2] - near[2]) > SkyBossTuning.SEED_REACH) {
                continue;
            }
            out.add(new Target(p, d, near, c, velOf(p)));
        }
        return out;
    }

    /** seed_launch の押し出しの所で、腹の扉から種を打ち上げる(地上の相手の近くへ落ちる弧)。体が反動で震える。 */
    private void seedRelease(ServerLevel level, SkyBossData data, SkyBossPose ps, SkyBossFlight f, long now, List<ServerPlayer> players) {
        if (seedDone || f.seedAt == SkyBossFlight.NONE || now < f.seedAt + SkyBossTuning.SEED_RELEASE) {
            return;
        }
        seedDone = true;
        if (!ps.spotWorld("bay_seed", tmp2) || !ps.spotOutward("bay_seed", tmp)) {
            return;
        }
        double[] from = tmp2.clone();
        double[] out = tmp.clone();
        List<Target> ground = groundTargets(ps, players);
        RandomSource r = level.getRandom();
        double[] vel = new double[3];
        for (int k = 0; k < SkyBossTuning.SEED_COUNT; k++) {
            double[] at;
            if (!ground.isEmpty()) {
                Target t = ground.get(k % ground.size());
                // 落ちるまでの間に動く分は見ない(大きくばらまく)
                at = new double[]{t.pos()[0] + spread(r, SkyBossTuning.SEED_SCATTER), t.pos()[1] - 1.0, t.pos()[2] + spread(r, SkyBossTuning.SEED_SCATTER)};
            } else {
                at = new double[]{from[0] + spread(r, 150.0), Math.max(level.getMinBuildHeight(), from[1] - 700.0), from[2] + spread(r, 150.0)};
            }
            SkyBossShots.aimLob(from, at, out[1] * SkyBossTuning.SEED_UP, vel);
            spawn(level, SkyBossShots.SEED, from, vel, now, SkyBossTuning.SEED_LIFE);
        }
        if (DEBUG_LOG) {
            com.yayuy.overgrownvegetation.OvergrownVegetation.LOGGER.info("[sky boss] seed launch from ({}, {}, {}) out ({}, {}, {}), ground targets {}", Math.round(from[0]), Math.round(from[1]), Math.round(from[2]),
                    String.format("%.2f", out[0]), String.format("%.2f", out[1]), String.format("%.2f", out[2]), ground.size());
        }
        f.shakeAt = now;
        f.shakeKind = SkyBossFlight.SHAKE_SEED;
        data.sync(level);
        SkyBossEffects.seedLaunch(level, from[0], from[1], from[2], out);
    }

    // ------------------------------------------------------------------ 大技 1: 転がりながらビーム
    /**
     * ビームの続き: 出ている蕾(壊れていない・ショートの隙でない)ごとに、外向きの光の線に触れた相手へダメージ(晴れの昼は強い)。
     * 速く回る間に 1 tick で通り越さないよう、前の tick の向きとの間を分けて見る。相手から蕾へ {@link SkyBossTuning#BEAM_COVER} の中にブロックがあれば防げる。
     * 転がり終わったら、熱を逃がす雲。
     */
    private void beam(ServerLevel level, SkyBossData data, SkyBossPose ps, SkyBossFlight f, long now, List<ServerPlayer> players, boolean stunned) {
        SkyBossShapes s = SkyBossShapes.get();
        if (s == null || !f.beaming(now)) {
            beamPrev = null;
            return;
        }
        long rollEnd = SkyBossBeam.rollEnd(f);
        if (now >= rollEnd) {
            if (!beamSteamed) {
                beamSteamed = true;
                SkyBossEffects.beamSteam(level, ps, data.parts());
            }
            return;
        }
        if (beamPrev == null || beamPrev.length != s.budCount * 3) {
            beamPrev = new double[s.budCount * 3];
            for (int i = 0; i < s.budCount; i++) {
                SkyBossBeam.aim(ps, i, now - 1, tmp);
                System.arraycopy(tmp, 0, beamPrev, i * 3, 3);
            }
        }
        if (now % 40L == 0L) {
            double[] c = ps.points[ps.points.length / 2];
            level.playSound(null, c[0], c[1], c[2], net.minecraft.sounds.SoundEvents.BEACON_AMBIENT, net.minecraft.sounds.SoundSource.HOSTILE, 40.0F, 0.5F);
        }
        if (!stunned) {
            boolean clear = level.isDay() && !level.isRaining();
            float dmg = SkyBossTuning.BEAM_DAMAGE * (clear ? SkyBossTuning.BEAM_CLEAR_MULT : 1.0F);
            for (ServerPlayer p : players) {
                if (!targetable(p)) {
                    continue;
                }
                double[] pc = center(p);
                for (int i = 0; i < s.budCount; i++) {
                    if (!data.parts().budSolid(i) || !SkyBossBeam.on(f, s, i, now)) {
                        continue;
                    }
                    SkyBossBeam.aim(ps, i, now, tmp);
                    int o = i * 3;
                    if (!beamHits(ps.spC[o], ps.spC[o + 1], ps.spC[o + 2], beamPrev, o, tmp, pc)) {
                        continue;
                    }
                    boolean cov = covered(level, p, ps.spC[o], ps.spC[o + 1], ps.spC[o + 2]);
                    if (!cov) {
                        p.hurt(ModDamageTypes.skyBossBeam(level), dmg);
                    }
                    if (DEBUG_LOG) {
                        com.yayuy.overgrownvegetation.OvergrownVegetation.LOGGER.info("[sky boss] beam bud {} swept {} (covered {}, damage {})", i, p.getName().getString(), cov, dmg);
                    }
                    break;
                }
            }
        }
        for (int i = 0; i < s.budCount; i++) {
            SkyBossBeam.aim(ps, i, now, tmp);
            System.arraycopy(tmp, 0, beamPrev, i * 3, 3);
        }
    }

    /**
     * 蕾の真ん中 (ox, oy, oz) から、前の向き {@code prev[po..]} → 今の向き {@code cur} へ掃いた光の線が点 p に触れたか
     * (向きの間を、相手の距離で太さより細かくなるまで分けて見る)。
     */
    public static boolean beamHits(double ox, double oy, double oz, double[] prev, int po, double[] cur, double[] p) {
        double vx = p[0] - ox;
        double vy = p[1] - oy;
        double vz = p[2] - oz;
        double vv = vx * vx + vy * vy + vz * vz;
        double dist = Math.sqrt(vv);
        double w = SkyBossTuning.BEAM_WIDTH;
        if (dist > SkyBossTuning.BEAM_LENGTH + w) {
            return false;
        }
        double dot = prev[po] * cur[0] + prev[po + 1] * cur[1] + prev[po + 2] * cur[2];
        double ang = Math.acos(Math.max(-1.0, Math.min(1.0, dot)));
        int n = Math.max(1, Math.min(48, (int) Math.ceil(ang * dist / w)));
        for (int k = 0; k <= n; k++) {
            double u = k / (double) n;
            double dx = prev[po] + (cur[0] - prev[po]) * u;
            double dy = prev[po + 1] + (cur[1] - prev[po + 1]) * u;
            double dz = prev[po + 2] + (cur[2] - prev[po + 2]) * u;
            double dl = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dl < 1.0E-9) {
                continue;
            }
            double along = (vx * dx + vy * dy + vz * dz) / dl;
            if (along < 0.0 || along > SkyBossTuning.BEAM_LENGTH) {
                continue;
            }
            if (vv - along * along < w * w) {
                return true;
            }
        }
        return false;
    }

    /** 相手から蕾の方へ {@link SkyBossTuning#BEAM_COVER} の中にブロックがあるか(屋根・壁の陰。相手のまわりのチャンクだけ見る)。 */
    private static boolean covered(ServerLevel level, ServerPlayer p, double ox, double oy, double oz) {
        Vec3 eye = p.getEyePosition();
        double dx = ox - eye.x;
        double dy = oy - eye.y;
        double dz = oz - eye.z;
        double l = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (l < 1.0E-6) {
            return false;
        }
        double k = Math.min(SkyBossTuning.BEAM_COVER, l) / l;
        Vec3 to = new Vec3(eye.x + dx * k, eye.y + dy * k, eye.z + dz * k);
        if (!level.isLoaded(BlockPos.containing(to))) {
            return false;
        }
        BlockHitResult h = level.clip(new ClipContext(eye, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, net.minecraft.world.phys.shapes.CollisionContext.empty()));
        return h.getType() == HitResult.Type.BLOCK;
    }

    private static boolean targetable(ServerPlayer p) {
        return !p.isSpectator() && !p.isCreative() && p.isAlive() && !p.isRemoved();
    }

    private static double[] center(ServerPlayer p) {
        return new double[]{p.getX(), p.getY() + p.getBbHeight() * 0.5, p.getZ()};
    }

    /** 点 p から線分(カプセル c の軸)までの距離。 */
    private static double segDistance(double[] p, double[] a, double[] b, int c) {
        int o = c * 3;
        return segDistance(p, new double[]{a[o], a[o + 1], a[o + 2]}, new double[]{b[o], b[o + 1], b[o + 2]});
    }

    private static double segDistance(double[] p, double[] a, double[] b) {
        double dx = b[0] - a[0];
        double dy = b[1] - a[1];
        double dz = b[2] - a[2];
        double ll = dx * dx + dy * dy + dz * dz;
        double u = ll < 1.0E-12 ? 0.0 : Math.max(0.0, Math.min(1.0, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy + (p[2] - a[2]) * dz) / ll));
        return Math.sqrt(sq(p[0] - a[0] - dx * u) + sq(p[1] - a[1] - dy * u) + sq(p[2] - a[2] - dz * u));
    }

    /** 線分(カプセル c の軸)の上で点 p にいちばん近い所の割合(0〜1)。 */
    private static double segParam(double[] p, double[] a, double[] b, int c) {
        int o = c * 3;
        double dx = b[o] - a[o];
        double dy = b[o + 1] - a[o + 1];
        double dz = b[o + 2] - a[o + 2];
        double ll = dx * dx + dy * dy + dz * dz;
        return ll < 1.0E-12 ? 0.0 : Math.max(0.0, Math.min(1.0, ((p[0] - a[o]) * dx + (p[1] - a[o + 1]) * dy + (p[2] - a[o + 2]) * dz) / ll));
    }

    private static double spread(RandomSource r, double v) {
        return (r.nextDouble() * 2.0 - 1.0) * v;
    }

    // ------------------------------------------------------------------ 弾
    private void spawn(ServerLevel level, int kind, double[] from, double[] vel, long now, int life) {
        int id = nextId++;
        shots.add(id, kind, from[0], from[1], from[2], vel[0], vel[1], vel[2], now, life);
        PacketDistributor.sendToPlayersNear(level, null, from[0], from[1], from[2], SkyBossTuning.SHOT_SEND_RANGE,
                new SkyBossShotPayload(SkyBossShotPayload.SPAWN, id, kind, from[0], from[1], from[2], vel[0], vel[1], vel[2], now, life));
        if (kind == SkyBossShots.SPORE) {
            level.playSound(null, from[0], from[1], from[2], net.minecraft.sounds.SoundEvents.PUFFER_FISH_BLOW_OUT, net.minecraft.sounds.SoundSource.HOSTILE,
                    10.0F, 0.5F + 0.2F * level.getRandom().nextFloat());
        }
    }

    /** 弾を進め、当たり(プレイヤー・読み込まれたブロック)と寿命を見る。終わった弾は炸裂させて全員へ知らせる。 */
    private void stepShots(ServerLevel level, List<ServerPlayer> players, long now) {
        List<SkyBossShots.Shot> list = shots.list;
        for (int k = list.size() - 1; k >= 0; k--) {
            SkyBossShots.Shot s = list.get(k);
            while (s.tick < now && !s.dead) {
                SkyBossShots.move(s);
                Vec3 a = new Vec3(s.px, s.py, s.pz);
                Vec3 b = new Vec3(s.x, s.y, s.z);
                double reach = s.kind == SkyBossShots.SEED ? 1.2 : 0.7;
                double[] hit = null;
                for (ServerPlayer p : players) {
                    if (p.isSpectator() || !p.isAlive()) {
                        continue;
                    }
                    AABB box = p.getBoundingBox().inflate(reach);
                    var h = box.clip(a, b);
                    if (h.isPresent()) {
                        hit = new double[]{h.get().x, h.get().y, h.get().z};
                        break;
                    }
                }
                if (hit == null && s.kind == SkyBossShots.SPORE) {
                    // 近接の信管: 胞子の塊は相手の炸裂の半径の中を通ったら炸裂する(直撃しなくても)
                    hit = proximity(players, a, b, SkyBossTuning.AA_BLAST);
                }
                if (hit == null && level.isLoaded(BlockPos.containing(b))) {
                    BlockHitResult bh = level.clip(new ClipContext(a, b, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, net.minecraft.world.phys.shapes.CollisionContext.empty()));
                    if (bh.getType() == HitResult.Type.BLOCK) {
                        Vec3 l = bh.getLocation();
                        hit = new double[]{l.x, l.y, l.z};
                    }
                }
                if (hit != null) {
                    burst(level, s, hit[0], hit[1], hit[2]);
                    s.dead = true;
                } else if (s.tick - s.born >= s.life || s.y < level.getMinBuildHeight() - 32) {
                    // 寿命: 胞子の塊は空中で炸裂、ほかは消える
                    if (s.kind == SkyBossShots.SPORE) {
                        burst(level, s, s.x, s.y, s.z);
                    } else {
                        end(level, s, s.x, s.y, s.z);
                    }
                    s.dead = true;
                }
            }
            if (s.dead) {
                list.remove(k);
            }
        }
    }

    /** Claude のテストクライアント(-Dov.devbridge=true)のときだけ技をログへ。 */
    private static final boolean DEBUG_LOG = Boolean.getBoolean("ov.devbridge");

    /** 炸裂: まわりのプレイヤーにダメージと効き目、粒と音。 */
    private void burst(ServerLevel level, SkyBossShots.Shot s, double x, double y, double z) {
        end(level, s, x, y, z);
        if (DEBUG_LOG) {
            double pd = Double.MAX_VALUE;
            for (ServerPlayer p : level.players()) {
                pd = Math.min(pd, Math.sqrt(p.distanceToSqr(x, y - p.getBbHeight() * 0.5, z)));
            }
            com.yayuy.overgrownvegetation.OvergrownVegetation.LOGGER.info("[sky boss] shot {} kind {} burst at ({}, {}, {}) after {} ticks, nearest player {}", s.id, s.kind,
                    Math.round(x), Math.round(y), Math.round(z), s.tick - s.born, Math.round(pd));
        }
        double radius = s.kind == SkyBossShots.SPORE ? SkyBossTuning.AA_BLAST : s.kind == SkyBossShots.SAP ? SkyBossTuning.SAP_BLAST : SkyBossTuning.SEED_BLAST;
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator() || !p.isAlive()) {
                continue;
            }
            if (p.getBoundingBox().inflate(radius).contains(x, y, z) || p.position().add(0, p.getBbHeight() * 0.5, 0).distanceTo(new Vec3(x, y, z)) < radius) {
                if (s.kind == SkyBossShots.SPORE) {
                    if (p.hurt(ModDamageTypes.skyBossSpore(level), SkyBossTuning.AA_DAMAGE)) {
                        // ユーザー指定(10-19): ダメージ + 胞子感染(仮面で防げる。ほかの植物の攻撃と同じ確率)
                        SporeInfectionEffects.plantAttack(p, 0);
                    }
                } else if (s.kind == SkyBossShots.SAP) {
                    p.hurt(ModDamageTypes.skyBossSap(level), SkyBossTuning.SAP_DAMAGE);
                    p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, SkyBossTuning.SAP_SLOW_TICKS, 1));
                } else if (p.hurt(ModDamageTypes.skyBossSeed(level), SkyBossTuning.SEED_DAMAGE)) {
                    // 種の爆弾も胞子をばらまく(仮面で防げる)
                    SporeInfectionEffects.plantAttack(p, 0);
                }
            }
        }
        if (s.kind == SkyBossShots.SPORE) {
            SkyBossEffects.sporeBurst(level, x, y, z, radius);
        } else if (s.kind == SkyBossShots.SAP) {
            SkyBossEffects.sapSplash(level, x, y, z, radius);
        } else {
            // ユーザー指定(10-19)「地上で爆発して汚染」(/ov corruption false・胞子の除去装置のまわりでは汚染しない = 汚染の決まりのまま)
            com.yayuy.overgrownvegetation.effect.ParasitizedCreeperDetonation.corruptSurfaceDisc(level, new Vec3(x, y, z),
                    SkyBossTuning.SEED_CORRUPT_RADIUS, SkyBossTuning.SEED_CORRUPT_NOISE);
            SkyBossEffects.seedBurst(level, x, y, z, radius);
        }
    }

    /** 線分 a → b がプレイヤー(観戦・クリエイティブを除く)の体の真ん中から {@code radius} の中を通るなら、いちばん近い所(無ければ null)。 */
    private static double[] proximity(List<ServerPlayer> players, Vec3 a, Vec3 b, double radius) {
        double dx = b.x - a.x;
        double dy = b.y - a.y;
        double dz = b.z - a.z;
        double ll = dx * dx + dy * dy + dz * dz;
        for (ServerPlayer p : players) {
            if (p.isSpectator() || p.isCreative() || !p.isAlive()) {
                continue;
            }
            double cx = p.getX();
            double cy = p.getY() + p.getBbHeight() * 0.5;
            double cz = p.getZ();
            double u = ll < 1.0E-9 ? 0.0 : Math.max(0.0, Math.min(1.0, ((cx - a.x) * dx + (cy - a.y) * dy + (cz - a.z) * dz) / ll));
            double qx = a.x + dx * u;
            double qy = a.y + dy * u;
            double qz = a.z + dz * u;
            if (sq(cx - qx) + sq(cy - qy) + sq(cz - qz) < radius * radius) {
                return new double[]{qx, qy, qz};
            }
        }
        return null;
    }

    private static void end(ServerLevel level, SkyBossShots.Shot s, double x, double y, double z) {
        PacketDistributor.sendToPlayersNear(level, null, x, y, z, SkyBossTuning.SHOT_SEND_RANGE, SkyBossShotPayload.end(s.id, s.kind, x, y, z));
    }

    private static void jitter(double[] v, double angle, RandomSource r) {
        double l = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        v[0] += (r.nextDouble() * 2.0 - 1.0) * angle * l;
        v[1] += (r.nextDouble() * 2.0 - 1.0) * angle * l;
        v[2] += (r.nextDouble() * 2.0 - 1.0) * angle * l;
        double m = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        v[0] *= l / m;
        v[1] *= l / m;
        v[2] *= l / m;
    }

    private static double sq(double v) {
        return v * v;
    }
}
