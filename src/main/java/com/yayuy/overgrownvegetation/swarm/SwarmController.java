package com.yayuy.overgrownvegetation.swarm;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.corruption.OvergrownLayerData;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.defense.RepellentLamps;
import com.yayuy.overgrownvegetation.entity.InvaderNavigation;
import com.yayuy.overgrownvegetation.entity.VelophyllGroundSporeGoal;
import com.yayuy.overgrownvegetation.phase.TreeCanopyGroundFinder;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import com.yayuy.overgrownvegetation.swarm.goal.SwarmSiegeHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 群れの<b>司令塔</b>が考える中身(群れ v2、2026-09-25)。{@link SwarmManager} が群れごとに
 * {@code thinkIntervalTicks}(10)tick に 1 回、ID で位相をずらして呼ぶ。
 *
 * <h2>1 回に考える順番</h2>
 * <ol>
 *     <li>見直し(消えた・別の群れへ移った一員を外す)と、動いている / 止まっている(エンティティとして tick されない
 *     チャンクにいる・死んで倒れている)の分け直し。止まっている一員は消え方の距離の判定にだけ数える(レビュー指摘: 止まった個体が
 *     先導役に選ばれる・待たれる・攻城のダメージを出す)。</li>
 *     <li>先導役の選び直し(前衛を優先、アルボラが先。死んだ・読み込み外れた・止まった・前衛でないのに前衛がいる・
 *     組み直し直後により向いた個体が入った)。新しい先導役へ今の区間を写して引き継ぐ。</li>
 *     <li>組み直した直後の最初の 1 回だけ {@link SwarmListener#onSwarmRebuilt}(TD が攻城を戻す)。</li>
 *     <li>消え方(群れごと。ユーザー指定 2026-09-25)。消えたらここで終わり。</li>
 *     <li>動いている一員がいなければここで止める(先導役が止まっている間は考えない)。</li>
 *     <li>標的({@link SwarmTargeting})→ モード → 体の包絡 → 足跡と陣形 → 標的の受け渡しと囲む位置 → 遅れ →
 *     先導役の歩く速さ → 暇なときの行き先 → 汚染を広げる → 残党の合流 → peak の書き戻し。</li>
 * </ol>
 * 一員を消す・移すときは必ず写し(List.copyOf)を回す(discard は Leave を同期で投げる。レビュー指摘の CME)。
 */
final class SwarmController {

    /** 先導役の歩く速さを落とす modifier の id(保存しない。{@code addTransientModifier} 相当)。 */
    static final ResourceLocation PACE_ID = OvergrownVegetation.id("swarm_pace");

    /** 前衛の中ではアルボラを先に(ユーザー指定「1 匹が先導」「アルボラを先遣隊に」)。 */
    private static final Comparator<Swarm.Member> GROUND_RANK = Comparator
            .comparingInt((Swarm.Member m) -> m.role.order)
            .thenComparingInt(m -> SwarmRole.isArbora(m.mob.getType()) ? 0 : 1)
            .thenComparingInt(m -> m.slot)
            .thenComparing(m -> m.mob.getUUID());

    private static final Comparator<Swarm.Member> AIR_RANK = Comparator
            .comparingInt((Swarm.Member m) -> m.slot)
            .thenComparing(m -> m.mob.getUUID());

    /** 陣形の並び(役割 → 番号 → UUID)。保存した番号は並べ替えの鍵にだけ使うので、重なっても位置は重ならない。 */
    private static final Comparator<Swarm.Member> FORMATION_ORDER = Comparator
            .comparingInt((Swarm.Member m) -> m.role.order)
            .thenComparingInt(m -> m.slot)
            .thenComparing(m -> m.mob.getUUID());

    static void think(Swarm swarm, long now) {
        SwarmTable table = Swarm.table();
        ServerLevel level = swarm.level();

        prune(swarm, level);
        if (swarm.isEmpty()) {
            return;
        }
        List<Swarm.Member> active = new ArrayList<>(swarm.members.size());
        for (Swarm.Member m : swarm.members.values()) {
            // 死んだ一員は 20 tick 倒れたまま残る(LivingEntity#tickDeath。その間 AI は動かない)。動いている一員に数えない
            // (レビュー指摘: 死んだ先導役を最大 2 回ぶん選び直さず、後続が立ち止まっていた。待つ・歩く速さ・受け渡しにも数えていた)。
            m.active = m.mob.isAlive() && level.isPositionEntityTicking(m.mob.blockPosition());
            if (m.active) {
                active.add(m);
            }
        }

        elect(swarm, active, now, table);

        if (!swarm.firstThinkDone) {
            swarm.firstThinkDone = true;
            SwarmManager.fireRebuilt(swarm);
        }

        if (despawn(swarm)) {
            return;
        }

        Mob anchor = swarm.vanguard;
        if (anchor == null || active.isEmpty()) {
            // 動いている一員がいない(先導役が止まっている)間は考えない。
            swarm.dirty = false;
            return;
        }

        SwarmTargeting.think(swarm, active, anchor, now);
        updateMode(swarm, active, anchor, now, table);

        if (swarm.kind() == Swarm.Kind.GROUND) {
            envelope(swarm, active, anchor, table);
            trackVanguard(swarm, anchor, now);
            SwarmPaths.recordTrail(swarm);
            formation(swarm, active, anchor, table);
        } else {
            airFormation(swarm, active, anchor);
        }
        handTargets(swarm, active, anchor, now, table);
        if (swarm.kind() == Swarm.Kind.GROUND) {
            laggards(swarm, active, anchor, table);
            pace(swarm, active, anchor);
        }
        if (swarm.mode == Swarm.Mode.WANDER) {
            wander(swarm, anchor, table);
        }
        if (swarm.mode == Swarm.Mode.SPREAD) {
            tickSpread(swarm, active, now, table);
        }
        remnants(swarm, active, now, table);
        writePeak(swarm);
        swarm.dirty = false;
    }

    // --- 見直し ---

    /** 消えた一員・別の群れへ移った一員を外す(Leave が届かない経路の後始末)。 */
    private static void prune(Swarm swarm, ServerLevel level) {
        for (Swarm.Member m : List.copyOf(swarm.members.values())) {
            Mob mob = m.mob;
            boolean gone = mob.isRemoved() || level.getEntity(mob.getUUID()) != mob;
            SwarmMembership att = mob.getExistingData(ModAttachments.SWARM).orElse(null);
            boolean foreign = att == null || !swarm.id().equals(att.id());
            if (gone || foreign) {
                swarm.members.remove(mob);
                if (gone && (mob.getRemovalReason() == null || mob.getRemovalReason() == Entity.RemovalReason.UNLOADED_TO_CHUNK)) {
                    swarm.noteDeparture(mob.getUUID(), level.getGameTime());
                }
                if (swarm.vanguard == mob) {
                    swarm.vanguard = null;
                }
                if (!gone) {
                    removePace(mob);
                }
            }
        }
    }

    // --- 先導役 ---

    private static void elect(Swarm swarm, List<Swarm.Member> active, long now, SwarmTable table) {
        Comparator<Swarm.Member> rank = swarm.kind() == Swarm.Kind.AIR ? AIR_RANK : GROUND_RANK;
        Swarm.Member best = null;
        for (Swarm.Member m : active) {
            if (best == null || rank.compare(m, best) < 0) {
                best = m;
            }
        }
        Swarm.Member current = swarm.vanguard != null ? swarm.members.get(swarm.vanguard) : null;
        boolean settling = now - swarm.createdTick < table.settleWindowTicks();
        boolean need = current == null || !current.active
                || (swarm.kind() == Swarm.Kind.GROUND && current.role != SwarmRole.FRONT
                && best != null && best.role == SwarmRole.FRONT)
                || (settling && best != null && best != current && rank.compare(best, current) < 0);
        if (!need) {
            return;
        }
        handOver(swarm, best != null ? best.mob : null, now);
    }

    /**
     * 先導役を替える。今の<b>区間</b>を写し、新しい先導役にいちばん近いノードから歩けるようにしておく
     * (先導の Goal の start で {@link SwarmPaths#resumeHandedOverLeg})。足跡は新しい先導役より先を捨てる。
     *
     * <p>写すのは群れの包絡で確かめた区間だけで、区間を引いたときの包絡・通り道のずれ・水の扱いも一緒に渡す。
     * 区間が無ければ(跳んだ・囲んでいた・立て直していた)写さず、新しい先導役が区間を引き直す
     * (レビュー指摘: はじめは前の先導役のナビの経路 —— 詰まりの回り道・自分の体で引いた経路 —— も写し、今の包絡のずれで
     * 足跡を記録していたので、足跡が確かめた列から 1〜1.5 ブロックずれて木や壁の中に入っていた)。
     */
    static void handOver(Swarm swarm, @Nullable Mob next, long now) {
        Mob old = swarm.vanguard;
        if (next == old) {
            return;
        }
        if (swarm.kind() == Swarm.Kind.GROUND && next != null) {
            Path leg = swarm.currentLeg;
            swarm.handOverPath = leg != null && !leg.isDone() && leg.getNodeCount() > 0
                    ? SwarmPaths.copyPath(leg, next) : null;
            swarm.handOverEnvelope = swarm.legEnvelope;
            swarm.handOverCorridorOffset = swarm.legCorridorOffset;
            swarm.handOverWater = swarm.currentLegWater;
            // 前の先導役が前回から通ったノードを書き切ってから、新しい先導役より先を捨てる。
            SwarmPaths.flushLeg(swarm);
            if (!swarm.trail.isEmpty()) {
                swarm.trail.truncateAfter(swarm.trail.nearestSeq(next.position()));
            }
            Swarm.Member nm = swarm.members.get(next);
            if (nm != null) {
                nm.cursor = -1L;
                nm.formationIndex = -1;
                nm.formationPoint = null;
                nm.lagging = false;
            }
        }
        swarm.currentLeg = null;
        swarm.legRoot = null;
        swarm.legRecorded = 0;
        swarm.legBackoffUntil = 0L;
        swarm.legFailures = 0;
        if (old != null && !old.isRemoved() && swarm.members.containsKey(old)) {
            // 降ろされた先導役は区間を歩くのをやめ、後続として足跡を追う。
            old.getNavigation().stop();
        }
        if (old != null) {
            removePace(old);
        }
        swarm.vanguard = next;
        swarm.electedTick = now;
        swarm.lastVanguardPos = next != null ? next.position() : null;
        swarm.lastVanguardMoveTick = now;
    }

    // --- 消え方 ---

    /**
     * 群れごとに消える(ユーザー指定 2026-09-25、2026-09-15 の「揃った群れは消えない」は取り下げ)。
     * 読み込まれている全員(止まっている一員も)が、いちばん近いプレイヤーから {@code invaderDespawnRange}(128)より遠いとき、
     * 永続でない一員を消す。永続の一員(イベントの印・名札)は残り、イベントが永続を戻せば次から消えるようになる。
     * ワールドにプレイヤーが 1 人もいなければ何もしない(バニラと同じ。GameTest のサーバー・別のディメンションにいる間)。
     *
     * @return 1 体でも消した(この回はここで考えるのをやめる。残った一員は次の回に見直す)
     */
    private static boolean despawn(Swarm swarm) {
        if (!swarm.origin().despawns()) {
            return false;
        }
        ServerLevel level = swarm.level();
        double range = OvergrownVegetationConfig.COMMON.invaderDespawnRange.get();
        double rangeSq = range * range;
        for (Swarm.Member m : swarm.members.values()) {
            Player nearest = level.getNearestPlayer(m.mob, -1.0D);
            if (nearest == null || nearest.distanceToSqr(m.mob) <= rangeSq) {
                return false;
            }
        }
        boolean any = false;
        for (Mob mob : List.copyOf(swarm.members.keySet())) {
            if (!mob.isRemoved() && !mob.isPersistenceRequired() && !mob.requiresCustomPersistence()) {
                // discard は Leave を同期で投げる(一覧からはそこで外れる)。写しを回しているので壊れない。
                mob.discard();
                any = true;
            }
        }
        return any;
    }

    // --- モード ---

    private static void updateMode(Swarm swarm, List<Swarm.Member> active, Mob anchor, long now, SwarmTable table) {
        switch (swarm.mode) {
            case SIEGE, BREACH -> {
                // TD が入れて TD が外す。標的の塊が無くなっていれば暇に戻す(TD の外し忘れの保険)。
                if (swarm.targetBlock == null) {
                    swarm.mode = swarm.target != null ? Swarm.Mode.HUNT : Swarm.Mode.WANDER;
                }
            }
            case SPREAD -> {
                if (swarm.target != null) {
                    endSpread(swarm, table);
                    swarm.mode = Swarm.Mode.HUNT;
                }
            }
            case HUNT -> {
                if (swarm.target == null) {
                    swarm.mode = Swarm.Mode.WANDER;
                    swarm.destination = null;
                    swarm.headings = List.of();
                    swarm.wanderFailedLegs = 0;
                }
            }
            default -> {
                if (swarm.target != null) {
                    swarm.mode = Swarm.Mode.HUNT;
                    swarm.idleTicks = 0;
                    return;
                }
                swarm.idleTicks += table.thinkIntervalTicks();
                if (swarm.nextSpreadIdleTicks < 0) {
                    swarm.nextSpreadIdleTicks = rollSpreadCadence(swarm, table);
                }
                if (swarm.idleTicks >= swarm.nextSpreadIdleTicks && canStartSpread(swarm, anchor)) {
                    startSpread(swarm, active, anchor, now);
                }
            }
        }
    }

    private static int rollSpreadCadence(Swarm swarm, SwarmTable table) {
        int min = table.spreadCadenceMinTicks();
        int max = table.spreadCadenceMaxTicks();
        return min + (max > min ? swarm.level().random.nextInt(max - min + 1) : 0);
    }

    // --- 体の包絡 ---

    /**
     * 先導役の区間に掛ける<b>体の包絡</b>。幅 = 動いている前衛・支援役のいちばん太い体、高さ = 同じくいちばん高い体
     * (上限 {@code envelopeMaxHeight} ≒ スポロフィルの背丈)。遠距離(フィロセンス、背丈 約 8)は入れない ——
     * 入れると葉の下の道が全部塞がり、森の中で区間が引けなくなる(レビュー指摘)。フィロセンスは足跡を追い、
     * 塞がれたら自分で立て直す。壁を登る辺は、地上の全員が登れて、しかも狩りのとき、または<b>登る群れ</b>({@link Swarm#climber}、
     * TD の攻め手の一部)の攻城のとき。登る群れの攻城では、壁を横へ・天井を這う辺と角を回る辺も足す(包絡の crawl。2026-09-25)。
     */
    private static void envelope(Swarm swarm, List<Swarm.Member> active, Mob anchor, SwarmTable table) {
        float width = 0.0F;
        float height = 0.0F;
        boolean allClimb = true;
        for (Swarm.Member m : active) {
            if (!m.role.isGround()) {
                continue;
            }
            allClimb &= SwarmRole.canClimb(m.mob.getType());
            if (m.role == SwarmRole.FRONT || m.role == SwarmRole.SUPPORT) {
                width = Math.max(width, m.mob.getBbWidth());
                height = Math.max(height, m.mob.getBbHeight());
            }
        }
        if (width <= 0.0F) {
            width = anchor.getBbWidth();
            height = anchor.getBbHeight();
        }
        height = (float) Math.min(height, table.envelopeMaxHeight());
        if (swarm.mode == Swarm.Mode.SIEGE || swarm.mode == Swarm.Mode.BREACH) {
            // 攻城では TD が開ける穴(3×3 まで)と同じ大きさに頭打ちする。評価器は floor(大きさ + 1) マスを占めるので、
            // 3 マスに収まる 3 − ε にする。レビュー指摘 2026-09-25: 大きいスポロフィル(背丈 4〜5)・大きいスカトゥリウム(幅 3 以上)
            // の包絡のままだと、3×3 の穴を開け終えても区間が「通れない」と出る一方、TD の壁選びは 3×3 までしか見ないので
            // 「塞ぐ壁は無い」と答え、群れが拠点の前で止まり続けた。大きい体はその穴を通れずに遅れる(後続の立て直しに任せる)。
            float cap = SwarmSiegeHooks.MAX_BREACH_CELLS - SIEGE_ENVELOPE_EPSILON;
            width = Math.min(width, cap);
            height = Math.min(height, cap);
        }
        // 攻城で登るのは登る群れ(climber)だけ。ほかの群れは今までどおり壁を壊して入る(ユーザー指定 2026-09-24「囲った拠点は
        // 壁を壊して入る」と、2026-09-25「アルボラは壁や天井を通ってターゲットブロックへ行くルートも。壁を壊して入るグループと、
        // 壁を登って壁の上のプレイヤーを襲うグループ」)。
        boolean siege = swarm.mode == Swarm.Mode.SIEGE || swarm.mode == Swarm.Mode.BREACH;
        boolean climb = allClimb && (swarm.mode == Swarm.Mode.HUNT || (siege && swarm.climber));
        // 壁を横へ・天井を這う辺(と角を回る辺)は、登る群れの攻城で、包絡が 1 マスに収まるときだけ(評価器が見るのは 1 マスの列)。
        boolean crawl = climb && siege && swarm.climber && Mth.floor(width + 1.0F) == 1;
        swarm.envelope = new InvaderNavigation.Envelope(width, height, climb, crawl);
    }

    /** 攻城の包絡を 3 マスに収めるための差(ブロック)。 */
    private static final float SIEGE_ENVELOPE_EPSILON = 0.01F;

    // --- 足跡と陣形 ---

    private static void trackVanguard(Swarm swarm, Mob vanguard, long now) {
        Vec3 pos = vanguard.position();
        if (swarm.lastVanguardPos == null || swarm.lastVanguardPos.distanceToSqr(pos) >= 1.0D) {
            swarm.lastVanguardPos = pos;
            swarm.lastVanguardMoveTick = now;
        }
    }

    /**
     * 後続の陣形の位置。並びは(役割 → 番号 → UUID)。前から列ごとに、足跡を先導役から道のりで戻った位置に置く。
     * 列の間隔は {@code formationSpacing}(太い体どうしは体の幅に合わせて広げる)。1 列目は足跡の上、2・3 列目は左右へ
     * {@code laneOffset} ずらすが、<b>ずらした先に体が入るときだけ</b>(包絡の通り道の内側に収まる、または自分の体の箱が
     * そこに入って足場もある)。入らなければ次の列へ回す。後続はカーソルが自分の番号に来るまで足跡の中心をなぞり、
     * そこで初めて横へずれる(ずらすのは陣形の位置だけ)。
     *
     * <p>仮決め 2026-09-25: 設計では「通り道に収まる AND 箱が入る」だったが、通り道(アルボラだけなら 1 ブロック幅)に
     * 左右 2 ブロックずれて収まる体は無いので、それでは横の並びが一度も使われない。確かめ方を「収まる OR 箱と足場で確かめる」にした。
     */
    private static void formation(Swarm swarm, List<Swarm.Member> active, Mob vanguard, SwarmTable table) {
        List<Swarm.Member> followers = new ArrayList<>(active.size());
        for (Swarm.Member m : active) {
            if (m.mob != vanguard && m.role.isGround()) {
                followers.add(m);
            } else if (m.mob == vanguard) {
                m.formationIndex = -1;
                m.formationPoint = null;
                m.formationSeq = -1L;
                m.lagging = false;
            }
        }
        followers.sort(FORMATION_ORDER);
        Trail trail = swarm.trail;
        float yaw = vanguard.getYRot() * Mth.DEG_TO_RAD;
        // 先導役の背中側(足跡が 1 点しか無いとき・足跡より後ろへ延ばすときの向き)。
        Vec3 back = new Vec3(Mth.sin(yaw), 0.0D, -Mth.cos(yaw));
        double spacing = table.formationSpacing();
        int lanes = table.lanes();
        double laneOffset = table.laneOffset();
        double corridor = swarm.envelope != null ? swarm.envelope.width() : vanguard.getBbWidth();

        double rowDepth = 0.0D;
        double prevRowWidth = vanguard.getBbWidth();
        double rowWidth = 0.0D;
        int laneInRow = lanes;
        Trail.Anchor rowAnchor = null;
        int index = 0;
        for (Swarm.Member f : followers) {
            double w = f.mob.getBbWidth();
            boolean placed = false;
            while (rowAnchor != null && laneInRow < lanes && !placed) {
                double lateral = laneLateral(laneInRow, laneOffset);
                laneInRow++;
                Vec3 right = new Vec3(-rowAnchor.tangent().z, 0.0D, rowAnchor.tangent().x);
                Vec3 point = rowAnchor.pos().add(right.scale(lateral));
                if (laneFits(swarm, f.mob, rowAnchor.pos(), point, corridor, Math.abs(lateral))) {
                    assign(f, rowAnchor, point, true, rowDepth);
                    rowWidth = Math.max(rowWidth, w);
                    placed = true;
                }
            }
            if (!placed) {
                // 次の列の中央(足跡の上)。
                double widthBefore = rowAnchor == null ? prevRowWidth : rowWidth;
                rowDepth += Math.max(spacing, (widthBefore + w) * 0.5D + ROW_GAP);
                rowAnchor = trail.behindNewest(rowDepth, back);
                rowWidth = w;
                laneInRow = 1;
                assign(f, rowAnchor, rowAnchor.pos(), false, rowDepth);
            }
            f.formationIndex = index++;
        }
    }

    /** 太い体どうしの列の隙間(ブロック)。仮決め 2026-09-25。 */
    private static final double ROW_GAP = 0.5D;

    /** 列の中の並び(1 → 右、2 → 左、3 → 右 2 つ目、…)の横のずれ。0 は中央(足跡の上)。 */
    private static double laneLateral(int lane, double offset) {
        int rank = (lane + 1) / 2;
        double side = (lane % 2 == 1) ? 1.0D : -1.0D;
        return side * rank * offset;
    }

    private static void assign(Swarm.Member f, Trail.Anchor anchor, Vec3 point, boolean inLane, double depth) {
        f.formationSeq = anchor.seq();
        f.formationPoint = point;
        f.inLane = inLane;
        f.rowDepth = depth;
    }

    /**
     * 横へずらした陣形の位置にその一員が立てるか。包絡の通り道に収まるなら確かめずに通す(区間の探索で確かめ済み)。
     * そうでなければ、自分の体の箱が位置と途中(中央との中間)に入り、足元 1.2 ブロック以内に足場があり、忌避灯の中でないこと。
     */
    private static boolean laneFits(Swarm swarm, Mob mob, Vec3 centre, Vec3 point, double corridor, double lateral) {
        if (mob.getBbWidth() <= corridor - 2.0D * lateral) {
            return true;
        }
        ServerLevel level = swarm.level();
        if (level.getChunkSource().getChunkNow(Mth.floor(point.x) >> 4, Mth.floor(point.z) >> 4) == null) {
            return false;
        }
        AABB box = mob.getDimensions(mob.getPose()).makeBoundingBox(point).deflate(0.05D);
        if (!level.noCollision(mob, box)) {
            return false;
        }
        AABB mid = mob.getDimensions(mob.getPose()).makeBoundingBox(centre.lerp(point, 0.5D)).deflate(0.05D);
        if (!level.noCollision(mob, mid)) {
            return false;
        }
        AABB below = new AABB(box.minX, box.minY - 1.2D, box.minZ, box.maxX, box.minY - 1.0E-3D, box.maxZ);
        if (level.noCollision(below)) {
            return false;
        }
        return !insideLamp(level, point);
    }

    /** 空の群れの V 字の番号(先頭を除いて番号 → UUID の順)。 */
    private static void airFormation(Swarm swarm, List<Swarm.Member> active, Mob leader) {
        List<Swarm.Member> followers = new ArrayList<>(active.size());
        for (Swarm.Member m : swarm.members.values()) {
            m.airIndex = -1;
        }
        for (Swarm.Member m : active) {
            if (m.mob != leader) {
                followers.add(m);
            }
        }
        followers.sort(AIR_RANK);
        for (int i = 0; i < followers.size(); i++) {
            followers.get(i).airIndex = i;
        }
    }

    // --- 標的の受け渡し ---

    /**
     * 役割ごとの距離で群れの標的を一員へ渡す({@code setTarget})。群れの一員にとって、標的の出どころは<b>ここだけ</b>
     * (個体の索敵・反撃の Goal は一員の間は止める。エンティティ側)。
     * <ul>
     *     <li>自分だけの反撃({@link SwarmTargeting#onMemberHurt})が期限内で、相手が役割の距離(+ 余り)にいればそれ。</li>
     *     <li>そうでなければ群れの標的。新しく渡すのは役割の距離の内側、渡した標的を取り上げるのは距離 + {@code clearMargin} を
     *     出てから(揺れよけ)。フィロセンスへ新しく渡すのは視線が通るときだけ。</li>
     *     <li>スポロフィル(支援)には渡さない(群れの標的は群れのデータにだけ持つ)。</li>
     * </ul>
     * そのあと、同じ相手を囲む前衛に輪の上の番号を振る。攻城では、先導役が塊に着いてから、エンティティの標的を持たない
     * 前衛のうち塊の近くにいるもの(先導役と、塊から engageRadius 以内の後続)に塊を囲む番号を振る({@link Swarm#blockEngageSlot})。
     */
    private static void handTargets(Swarm swarm, List<Swarm.Member> active, Mob anchor, long now, SwarmTable table) {
        LivingEntity swarmTarget = swarm.target != null && swarm.target.isAlive() ? swarm.target : null;
        for (Swarm.Member m : active) {
            Mob mob = m.mob;
            LivingEntity desired = null;
            if (m.role != SwarmRole.SUPPORT) {
                LivingEntity local = m.localTarget;
                if (local != null && (now >= m.localUntil || !local.isAlive()
                        || !SwarmTargeting.withinRole(m, local, table.clearMargin()))) {
                    local = null;
                    m.localTarget = null;
                }
                if (local != null) {
                    desired = local;
                } else if (swarmTarget != null) {
                    boolean had = mob.getTarget() == swarmTarget;
                    if (SwarmTargeting.withinRole(m, swarmTarget, had ? table.clearMargin() : 0.0D)
                            && (had || m.role != SwarmRole.RANGED_SCOUT || mob.hasLineOfSight(swarmTarget))) {
                        desired = swarmTarget;
                    }
                }
            }
            m.handed = desired;
            if (mob.getTarget() != desired) {
                mob.setTarget(desired);
            }
        }

        // 囲む番号(同じ相手を囲む前衛ごと)。
        Map<LivingEntity, List<Swarm.Member>> groups = new IdentityHashMap<>();
        List<Swarm.Member> blockGroup = new ArrayList<>();
        double engage = table.engageRadius() + table.clearMargin();
        BlockPos block = siegeBlock(swarm);
        Vec3 blockCentre = block != null ? Vec3.atBottomCenterOf(block) : null;
        // 攻城の塊を囲むのは、先導役が塊に着いてから(レビュー指摘: 距離を見ずに全前衛を入れていたので、先導役まで囲む Goal に
        // 取られて区間を引かず、TD の壁を壊す判断も起きず、後続は経路なしで塊へ直進していた)。
        boolean arrived = block != null && vanguardAtBlock(swarm, anchor, block, blockCentre, engage, now);
        swarm.siegeArrived = arrived && swarm.mode == Swarm.Mode.SIEGE;
        for (Swarm.Member m : active) {
            boolean wasBlock = m.blockEngaged;
            m.engageIndex = 0;
            m.engageCount = 0;
            m.blockEngaged = false;
            if (m.role != SwarmRole.FRONT) {
                continue;
            }
            // スカトゥリウムは突進・迫撃砲・追跡を自分の Goal で行うので、相手を囲む輪には入れない(攻城の塊の輪には入る)。
            boolean shielder = SwarmRole.isShielder(m.mob.getType());
            if (m.handed != null && !shielder && m.mob.distanceToSqr(m.handed) <= engage * engage) {
                groups.computeIfAbsent(m.handed, k -> new ArrayList<>()).add(m);
            } else if (arrived && m.handed == null) {
                // 先導役は着いていれば入る。後続は塊から engageRadius 以内(入っていれば + clearMargin まで。揺れよけ)。
                // 登る群れの後続は、壁越しには入らない(塊まで線が通るか、手が届くときだけ)—— 入ると囲む Goal が足跡をなぞる Goal から
                // 動きを取り、足跡のカーソルが進まず、壁に張り付いたまま越えられなかった(2026-09-25、GameTest
                // climberSwarmCrossesWallWithoutBreaking)。壊して入る群れは今までどおり(壁は壊れる)。
                double r = wasBlock ? engage : table.engageRadius();
                if (m.mob == anchor || (m.mob.position().distanceToSqr(blockCentre) <= r * r
                        && (!swarm.climber || Swarm.canReachBlock(m.mob, block) || clearToBlock(swarm.level(), m.mob, block)))) {
                    blockGroup.add(m);
                }
            }
        }
        for (Map.Entry<LivingEntity, List<Swarm.Member>> e : groups.entrySet()) {
            LivingEntity target = e.getKey();
            List<Swarm.Member> ring = e.getValue();
            ring.sort(AIR_RANK);
            // 輪の向きは囲み始めたときに一度だけ(一員の重心の側に)決め、同じ相手を囲んでいる間は保つ。2026-09-25(ユーザー報告
            // 「くるくる回ってスタック」の調べ): はじめは考えるたびに重心から決め直していたので、囲み終えると重心が相手の上に来て
            // 向きがでたらめになり、10 tick ごとに全員の輪の位置が飛んで、相手のまわりを回り続けた。
            double base = Double.NaN;
            for (Swarm.Member m : ring) {
                if (m.ringTarget == target) {
                    base = m.engageBaseAngle;
                    break;
                }
            }
            if (Double.isNaN(base)) {
                Vec3 centroid = centroid(ring);
                base = Math.atan2(centroid.z - target.getZ(), centroid.x - target.getX());
            }
            for (int i = 0; i < ring.size(); i++) {
                Swarm.Member m = ring.get(i);
                m.engageIndex = i;
                m.engageCount = ring.size();
                m.engageBaseAngle = base;
                m.ringTarget = target;
            }
        }
        if (!blockGroup.isEmpty() && block != null) {
            blockGroup.sort(AIR_RANK);
            double base = Math.atan2(anchor.getZ() - (block.getZ() + 0.5D), anchor.getX() - (block.getX() + 0.5D));
            for (int i = 0; i < blockGroup.size(); i++) {
                Swarm.Member m = blockGroup.get(i);
                m.blockEngaged = true;
                m.engageIndex = i;
                m.engageCount = blockGroup.size();
                m.engageBaseAngle = base;
            }
        }
        for (Swarm.Member m : active) {
            if (m.engageCount == 0 || m.blockEngaged) {
                m.ringTarget = null;
            }
        }
        Swarm.Member lead = swarm.members.get(anchor);
        swarm.vanguardEngaged = lead != null && lead.role == SwarmRole.FRONT
                && ((lead.handed != null && anchor.distanceToSqr(lead.handed) <= engage * engage) || lead.blockEngaged);
    }

    /** 攻城で囲む塊(壁を壊している間は壊す壁)。攻城でなければ null。 */
    @Nullable
    private static BlockPos siegeBlock(Swarm swarm) {
        if ((swarm.mode != Swarm.Mode.SIEGE && swarm.mode != Swarm.Mode.BREACH) || swarm.targetBlock == null) {
            return null;
        }
        return swarm.mode == Swarm.Mode.BREACH && swarm.breachPos != null ? swarm.breachPos : swarm.targetBlock;
    }

    /**
     * 先導役が標的の塊(壊す壁)に着いているか。壁を壊している間(BREACH)はいつでも着いている(壁は先導役の目の前で選ばれ、
     * 区間も引かない)。攻城(SIEGE)では塊の輪 + {@value #VANGUARD_BLOCK_SLACK} 以内で着いたとし、着いたあとは
     * {@code keep}(engageRadius + clearMargin)を出るまで着いたままにする(揺れよけ。先導役が替わっても群れとして着いたまま)。
     *
     * <p>高さも見る: 塊の段が先導役の手の届く高さ({@link Swarm#withinBlockReachHeight}。着いたあとは跳んだぶんの 1 段の余り)に
     * なければ着いていない。TD が区間へ戻させている間({@link Swarm#holdSiegeArrival})も着いていない。
     * レビュー指摘 2026-09-25: 3D の距離だけで見ていたので、床の 1 段下に埋めた・1 階上に置いた・1〜2 枚の壁のすぐ裏に置いた
     * ターゲットでも「着いた」になり、先導の Goal が止まって壁選び({@code SwarmLeadGoal#tryBreach})が二度と呼ばれず、
     * 群れは叩けないまま止まっていた(壁の裏の場合の立て直しは TD の見張り {@code TdSiege#watchSiege})。
     */
    private static boolean vanguardAtBlock(Swarm swarm, Mob anchor, BlockPos block, Vec3 centre, double keep, long now) {
        if (swarm.mode == Swarm.Mode.BREACH) {
            return true;
        }
        if (now < swarm.siegeArrivalHoldUntil) {
            return false;
        }
        if (!Swarm.withinBlockReachHeight(anchor, block, swarm.siegeArrived ? ARRIVED_HEIGHT_SLACK : 0.0D)) {
            return false;
        }
        double reach = swarm.siegeArrived ? keep : Swarm.blockRingRadius(anchor) + VANGUARD_BLOCK_SLACK;
        if (anchor.position().distanceToSqr(centre) > reach * reach) {
            return false;
        }
        // 登る群れは壁越しには着かない(壁の手前で着いたことにすると、先導の Goal が止まって壁を登る区間を引かず、囲む Goal が
        // 壁の向こうの輪の位置へ体を押し付けて跳び続けた。2026-09-25、GameTest climberSwarmCrossesWallWithoutBreaking)。
        // 壊して入る群れは今までどおり距離だけで着く(着いたのに叩けない見張り TdSiege#watchSiege が壁を選ぶ)。
        return !swarm.climber || Swarm.canReachBlock(anchor, block) || clearToBlock(swarm.level(), anchor, block);
    }

    /** 先導役の体の真ん中から塊の中心まで、ほかの塊に当たらずに線が通るか(登る群れの「着いた」)。 */
    private static boolean clearToBlock(ServerLevel level, Mob anchor, BlockPos block) {
        Vec3 from = anchor.position().add(0.0D, anchor.getBbHeight() * 0.5D, 0.0D);
        BlockHitResult hit = level.clip(new ClipContext(from, Vec3.atCenterOf(block), ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, anchor));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(block);
    }

    /** 着いたあとの高さの揺れよけ(ブロック。跳んだ・段を 1 つ上り下りしただけでは着いたままにする)。仮決め 2026-09-25。 */
    private static final double ARRIVED_HEIGHT_SLACK = 1.0D;

    /**
     * 先導役が塊に「着いた」とみなす、囲む輪からの余り(ブロック)。区間の終わり(塊の隣のマス、太い体はノードの角から
     * 半幅ずれて立つ)が収まる幅。仮決め 2026-09-25。
     */
    private static final double VANGUARD_BLOCK_SLACK = 2.0D;

    private static Vec3 centroid(List<Swarm.Member> members) {
        double x = 0.0D;
        double z = 0.0D;
        for (Swarm.Member m : members) {
            x += m.mob.getX();
            z += m.mob.getZ();
        }
        int n = Math.max(1, members.size());
        return new Vec3(x / n, 0.0D, z / n);
    }

    // --- 遅れ ---

    /**
     * 自分の陣形の位置から {@code laggardDistance}(6)以上離れた後続は「遅れている」(レビュー指摘: 先導役からの距離で見ると、
     * 後ろの列の正しい位置がもう 18 を超えていて毎回 300 tick 待つ)。交戦中(攻城の塊を囲んでいるのも)・汚染を広げている間は
     * 数えない(塊を囲む後続を遅れと数えると、先導役が塊を攻める後続を待ってしまう。レビュー指摘)。
     * 遅れがいる間は待つ数えを進め、全員が追いついたときだけ 0 に戻す。
     */
    private static void laggards(Swarm swarm, List<Swarm.Member> active, Mob vanguard, SwarmTable table) {
        double lagSq = table.laggardDistance() * table.laggardDistance();
        boolean any = false;
        for (Swarm.Member m : active) {
            if (m.mob == vanguard || !m.role.isGround()) {
                m.lagging = false;
                continue;
            }
            boolean busy = m.handed != null || m.blockEngaged || swarm.mode == Swarm.Mode.SPREAD;
            m.lagging = !busy && m.formationPoint != null && m.mob.position().distanceToSqr(m.formationPoint) > lagSq;
            any |= m.lagging;
        }
        swarm.anyLagging = any;
        if (any) {
            swarm.waitTicks += table.thinkIntervalTicks();
        } else {
            swarm.waitTicks = 0;
        }
    }

    // --- 先導役の歩く速さ ---

    /**
     * 先導役は動いている一員のうちいちばん遅い速さで歩く(交戦中は外す)。{@code MOVEMENT_SPEED} に
     * 保存しない modifier({@link #PACE_ID})を掛ける。
     */
    private static void pace(Swarm swarm, List<Swarm.Member> active, Mob vanguard) {
        double slowest = Double.MAX_VALUE;
        for (Swarm.Member m : active) {
            if (m.role.isGround()) {
                slowest = Math.min(slowest, speedWithoutPace(m.mob));
            }
        }
        swarm.paceSpeed = slowest == Double.MAX_VALUE ? 0.0D : slowest;
        AttributeInstance speed = vanguard.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null) {
            return;
        }
        double own = speedWithoutPace(vanguard);
        double amount = swarm.vanguardEngaged || slowest == Double.MAX_VALUE || own <= 1.0E-6D
                ? 0.0D : Math.min(0.0D, slowest / own - 1.0D);
        AttributeModifier current = speed.getModifier(PACE_ID);
        if (amount > -PACE_EPSILON) {
            if (current != null) {
                speed.removeModifier(PACE_ID);
            }
            return;
        }
        if (current == null || Math.abs(current.amount() - amount) > PACE_EPSILON) {
            speed.addOrUpdateTransientModifier(
                    new AttributeModifier(PACE_ID, amount, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
    }

    /** これより小さい速さの差は掛け直さない(属性の同期を毎回出さない)。 */
    private static final double PACE_EPSILON = 0.005D;

    /** 群れの modifier を除いた移動速度。 */
    private static double speedWithoutPace(Mob mob) {
        AttributeInstance speed = mob.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null) {
            return 0.0D;
        }
        double value = speed.getValue();
        AttributeModifier ours = speed.getModifier(PACE_ID);
        if (ours != null && ours.operation() == AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL && ours.amount() > -1.0D) {
            value /= 1.0D + ours.amount();
        }
        return value;
    }

    /** 先導役の歩く速さの modifier を外す(降ろされた・群れを抜けた)。 */
    static void removePace(Mob mob) {
        AttributeInstance speed = mob.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null && speed.getModifier(PACE_ID) != null) {
            speed.removeModifier(PACE_ID);
        }
    }

    // --- 暇なときの行き先 ---

    /**
     * 暇な群れの行き先。過繁茂バイオームの中なら「外」の候補を近い順に作ってその先頭へ(ユーザー要望 2026-09-15)。
     * 外に出ていれば(または候補を使い切ったら)、直前の向きから ±60 度で 24〜48 ブロック先をぶらつく(仮決め 2026-09-25)。
     * 着いたら選び直す。
     */
    private static void wander(Swarm swarm, Mob anchor, SwarmTable table) {
        Vec3 pos = anchor.position();
        Vec3 dest = swarm.destination;
        boolean reached = dest != null && (swarm.kind() == Swarm.Kind.AIR
                ? airReached(anchor, dest, table)
                : horizontalSq(pos, dest) <= table.destinationReachedDistance() * table.destinationReachedDistance());
        if (dest != null && !reached) {
            return;
        }
        Boolean inside = SwarmHeading.overgrownAt(swarm.level(), anchor.blockPosition());
        if (inside == null || inside) {
            swarm.headings = SwarmHeading.exits(swarm.level(), pos, swarm.id(), table);
            swarm.headingIndex = 0;
            swarm.destination = swarm.headings.get(0);
            swarm.lastHeadingAngle = Math.atan2(swarm.destination.z - pos.z, swarm.destination.x - pos.x);
        } else {
            swarm.headings = List.of();
            swarm.destination = roam(swarm, anchor, table);
        }
        swarm.wanderFailedLegs = 0;
    }

    /**
     * 空の群れの先頭が行き先に着いたか(レビュー指摘 2026-09-25)。地上と同じ {@code destinationReachedDistance}(6)を
     * 考えるたび(10 tick)に見るだけだと、先頭は 1 tick に 1〜1.45 ブロック飛ぶ(1 回の間に 10〜14.5 ブロック)ので、
     * 半径 6 の円を 2 回の間にまたいで「着かなかった」ことになり、群れごと行き過ぎて大回り(旋回半径 約 28)して戻っていた。
     * <ul>
     *     <li>水平で {@value #AIR_REACH} 以内(1 回の間に飛ぶ距離より広い)、または</li>
     *     <li>行き先が先頭の<b>後ろ</b>(進む向きとの内積が負)になり、しかも水平で {@value #AIR_PASS_REACH} 以内
     *     (横へ大きく外して通り過ぎた)。</li>
     * </ul>
     * 数値は仮決め 2026-09-25。
     */
    private static boolean airReached(Mob leader, Vec3 dest, SwarmTable table) {
        Vec3 pos = leader.position();
        double sq = horizontalSq(pos, dest);
        double reach = Math.max(table.destinationReachedDistance(), AIR_REACH);
        if (sq <= reach * reach) {
            return true;
        }
        if (sq > AIR_PASS_REACH * AIR_PASS_REACH) {
            return false;
        }
        Vec3 v = leader.getDeltaMovement();
        double hx = v.x;
        double hz = v.z;
        if (hx * hx + hz * hz < 1.0E-4D) {
            // ほとんど動いていない。向いている方を進む向きにする(yRot は +Z が 0 度 → 向きは (-sin, cos))。
            float yaw = leader.getYRot() * Mth.DEG_TO_RAD;
            hx = -Mth.sin(yaw);
            hz = Mth.cos(yaw);
        }
        return (dest.x - pos.x) * hx + (dest.z - pos.z) * hz < 0.0D;
    }

    /** 空の群れの先頭が行き先に「着いた」水平距離(ブロック)。仮決め 2026-09-25。 */
    private static final double AIR_REACH = 16.0D;
    /** 空の群れの先頭が行き先を「通り過ぎた」とみなす水平距離の上限(ブロック)。仮決め 2026-09-25。 */
    private static final double AIR_PASS_REACH = 32.0D;

    /** 同じ行き先へ区間が続けて失敗した({@link SwarmPaths#noteLegOutcome})。次に近い外へ、使い切ったらぶらつく。 */
    static void reHead(Swarm swarm, Mob anchor) {
        SwarmTable table = Swarm.table();
        swarm.headingIndex++;
        if (swarm.headingIndex < swarm.headings.size()) {
            Vec3 next = swarm.headings.get(swarm.headingIndex);
            swarm.destination = next;
            swarm.lastHeadingAngle = Math.atan2(next.z - anchor.getZ(), next.x - anchor.getX());
        } else {
            swarm.destination = roam(swarm, anchor, table);
        }
        swarm.wanderFailedLegs = 0;
    }

    private static Vec3 roam(Swarm swarm, Mob anchor, SwarmTable table) {
        var random = swarm.level().random;
        double angle = Double.isNaN(swarm.lastHeadingAngle)
                ? random.nextDouble() * Math.PI * 2.0D
                : swarm.lastHeadingAngle + (random.nextDouble() * 2.0D - 1.0D) * ROAM_TURN;
        double distance = table.roamMin() + random.nextDouble() * (table.roamMax() - table.roamMin());
        swarm.lastHeadingAngle = angle;
        return new Vec3(anchor.getX() + Math.cos(angle) * distance, anchor.getY(), anchor.getZ() + Math.sin(angle) * distance);
    }

    /** ぶらつくとき、直前の向きから曲がってよい角度(±60 度)。仮決め 2026-09-25。 */
    private static final double ROAM_TURN = Math.toRadians(60.0D);

    // --- 汚染を広げる(SPREAD) ---

    /**
     * 汚染を広げ始めてよいか。先導役(空の群れは先頭の真下の地面)が<b>汚染されていない地面</b>に立っているときだけ ——
     * 群れは過繁茂バイオームの外へ向かっているので、これは縄張りの縁で起きる(ユーザー指定 2026-09-25「暇なら止まって全員で」)。
     */
    private static boolean canStartSpread(Swarm swarm, Mob anchor) {
        ServerLevel level = swarm.level();
        BlockPos ground;
        if (swarm.kind() == Swarm.Kind.GROUND) {
            if (!anchor.onGround() || anchor.isInWater()) {
                return false;
            }
            ground = anchor.blockPosition().below();
        } else {
            LevelChunk chunk = level.getChunkSource().getChunkNow(anchor.getBlockX() >> 4, anchor.getBlockZ() >> 4);
            if (chunk == null) {
                return false;
            }
            int top = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, anchor.getBlockX() & 15, anchor.getBlockZ() & 15);
            ground = new BlockPos(anchor.getBlockX(), top, anchor.getBlockZ());
            if (!level.getFluidState(ground).isEmpty()) {
                return false;
            }
            // 空の群れは<b>開けた地面の上でだけ</b>始める(レビュー指摘 2026-09-25)。森の上で始めると、一員はめいめい
            // 開けた場所を探して天蓋の上を巡航のまま飛び続け(降りるまで最大 400 tick、数百ブロック)、ばらばらに降りて
            // 「止まって全員で」にならなかった。
            if (!VelophyllGroundSporeGoal.isOpenGround(level, anchor.getBlockX(), anchor.getBlockZ())) {
                return false;
            }
        }
        return !isCorrupted(level, ground);
    }

    /** 汚染済みの地面か(タグ、または苔で覆っただけの未対応ブロック。メモリ「覆ったマスは #corrupted を持たない」)。 */
    private static boolean isCorrupted(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).is(ModTags.CORRUPTED) || OvergrownLayerData.isCovered(level, pos);
    }

    /**
     * 汚染を広げ始める。場所は先導役(空の群れは先頭の真下)を中心に、{@code 2 × invaderCorruptionRadius} 以上離して配る
     * (汚染の場が重ならないように)。候補は中心 → 半径 1 段目 → 2 段目 …(読み込み済みの地面・乾いている・汚染されていない・
     * 忌避灯の外)。一員は自分にいちばん近い空きを取る。取れなかった一員はその場に立つ(スポロフィルはそれで汚染する)。
     */
    private static void startSpread(Swarm swarm, List<Swarm.Member> active, Mob anchor, long now) {
        swarm.mode = Swarm.Mode.SPREAD;
        swarm.spreadStartTick = now;
        for (Swarm.Member m : swarm.members.values()) {
            m.spreadSpot = null;
            m.spreadStarted = false;
            m.spreadFinished = false;
        }
        ServerLevel level = swarm.level();
        int radius = Math.max(1, OvergrownVegetationConfig.COMMON.invaderCorruptionRadius.get());
        double spacing = Math.max(2.0D, 2.0D * radius);
        BlockPos centre = anchor.blockPosition();
        int refY = centre.getY();
        boolean air = swarm.kind() == Swarm.Kind.AIR;
        if (air) {
            BlockPos surface = surface(level, centre.getX(), centre.getZ());
            if (surface == null) {
                return;
            }
            centre = surface;
            refY = surface.getY();
        }
        swarm.spreadCentre = centre.immutable();
        List<BlockPos> spots = new ArrayList<>();
        BlockPos first = surfaceNear(level, centre.getX(), centre.getZ(), refY);
        if (first != null && spotUsable(level, first, air)) {
            spots.add(first);
        }
        double phase = Math.floorMod(swarm.id().hashCode(), 360) * Mth.DEG_TO_RAD;
        for (int ring = 1; ring <= SPREAD_RINGS && spots.size() < active.size(); ring++) {
            double r = ring * spacing;
            int count = Math.max(6, Mth.floor(Math.PI * 2.0D * r / spacing));
            for (int i = 0; i < count; i++) {
                double angle = phase + i * (Math.PI * 2.0D / count);
                int x = Mth.floor(centre.getX() + 0.5D + Math.cos(angle) * r);
                int z = Mth.floor(centre.getZ() + 0.5D + Math.sin(angle) * r);
                BlockPos p = surfaceNear(level, x, z, refY);
                if (p != null && spotUsable(level, p, air)) {
                    spots.add(p);
                }
            }
        }
        // 先導役(先頭)から先に、自分にいちばん近い空きを取る。
        List<Swarm.Member> order = new ArrayList<>(active);
        order.sort(Comparator.comparingInt((Swarm.Member m) -> m.mob == anchor ? 0 : 1).thenComparing(FORMATION_ORDER));
        for (Swarm.Member m : order) {
            BlockPos best = null;
            double bestSq = Double.MAX_VALUE;
            for (BlockPos p : spots) {
                double d = m.mob.distanceToSqr(p.getX() + 0.5D, p.getY(), p.getZ() + 0.5D);
                if (d < bestSq) {
                    bestSq = d;
                    best = p;
                }
            }
            if (best != null) {
                spots.remove(best);
                m.spreadSpot = best;
            }
        }
    }

    /** 汚染の場を配る輪の段数。仮決め 2026-09-25。 */
    private static final int SPREAD_RINGS = 4;
    /** 汚染の場の高さが中心からこれ以上違えば使わない(崖の上下に散らない)。仮決め 2026-09-25。 */
    private static final int SPREAD_MAX_DY = 4;

    private static void tickSpread(Swarm swarm, List<Swarm.Member> active, long now, SwarmTable table) {
        long elapsed = now - swarm.spreadStartTick;
        boolean started = false;
        boolean allDone = true;
        for (Swarm.Member m : swarm.members.values()) {
            if (m.spreadStarted) {
                started = true;
                allDone &= m.spreadFinished;
            }
        }
        if (elapsed >= table.spreadCapTicks() || (started && allDone)
                || (!started && elapsed >= table.spreadStartGraceTicks())) {
            endSpread(swarm, table);
            swarm.mode = Swarm.Mode.WANDER;
        }
    }

    private static void endSpread(Swarm swarm, SwarmTable table) {
        swarm.idleTicks = 0;
        swarm.nextSpreadIdleTicks = rollSpreadCadence(swarm, table);
        swarm.spreadCentre = null;
        for (Swarm.Member m : swarm.members.values()) {
            m.spreadSpot = null;
            m.spreadStarted = false;
            m.spreadFinished = false;
        }
    }

    /** (x, z) 列の地面の上のマス(読み込み済みのチャンクだけ)。高さが {@code refY} から {@value #SPREAD_MAX_DY} を超えて違えば null。 */
    @Nullable
    private static BlockPos surfaceNear(ServerLevel level, int x, int z, int refY) {
        BlockPos p = surface(level, x, z);
        return p != null && Math.abs(p.getY() - refY) <= SPREAD_MAX_DY ? p : null;
    }

    /**
     * (x, z) 列の地面の上のマス(読み込み済みのチャンクだけ)。木を掘り抜いた高さ({@link TreeCanopyGroundFinder}。過繁茂の葉・
     * 巨大樹の幹はバニラの葉・幹のタグを持たないので、高さ地図(葉を除く)だけだと過繁茂の森では梢の上になる。2026-09-25)。
     * 掘り抜けなければ高さ地図。
     */
    @Nullable
    private static BlockPos surface(ServerLevel level, int x, int z) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
        if (chunk == null) {
            return null;
        }
        BlockPos ground = TreeCanopyGroundFinder.findSpawnSurface(level, x, z);
        if (ground != null) {
            return ground;
        }
        int top = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15);
        return new BlockPos(x, top + 1, z);
    }

    /**
     * 汚染の場に使えるか(乾いている・汚染されていない・忌避灯の外)。空の群れ({@code air})は、降りられる<b>開けた地面</b>
     * (葉の天蓋との差が小さい。{@link VelophyllGroundSporeGoal#isOpenGround})だけ(木の下の場所を配ると、そこへ降りられずに
     * 天蓋の上を回り続ける。レビュー指摘 2026-09-25)。
     */
    private static boolean spotUsable(ServerLevel level, BlockPos p, boolean air) {
        if (!level.getFluidState(p).isEmpty() || !level.getFluidState(p.below()).isEmpty()) {
            return false;
        }
        if (isCorrupted(level, p.below())) {
            return false;
        }
        if (air && !VelophyllGroundSporeGoal.isOpenGround(level, p.getX(), p.getZ())) {
            return false;
        }
        return !insideLamp(level, Vec3.atBottomCenterOf(p));
    }

    /** 点いている忌避灯の半径の中か(群れの Goal は灯りの中へ行く点を選ばない。ユーザー指定「灯りのそばには寄れない」)。 */
    static boolean insideLamp(ServerLevel level, Vec3 point) {
        return RepellentLamps.nearestActive(level, point, DesignData.defense().lampRadius()) != null;
    }

    // --- 残党 ---

    /**
     * 残党の合流。いちばん大きいとき {@code remnantMinPeak}(3)体以上だった群れが、動いている一員 {@code remnantMaxActive}(2)体
     * 以下になったら、{@code remnantMergeRadius}(32)以内の<b>同じ鍵</b>(種類・出どころ・イベント・狙い・登るか)の群れで空きのあるものへ
     * 全員で移る(一部だけは移らない。上限は超えない)。組み直し直後・読み込み外れた一員がいる間は、残りがまだ読み込まれて
     * いないだけかもしれないのでしない(レビュー指摘)。テスト・計測の群れはしない。
     */
    private static void remnants(Swarm swarm, List<Swarm.Member> active, long now, SwarmTable table) {
        if (!swarm.origin().mergesRemnants() || swarm.peak < table.remnantMinPeak()) {
            return;
        }
        if (active.isEmpty() || active.size() > table.remnantMaxActive() || swarm.members.size() != active.size()) {
            return;
        }
        if (now - swarm.createdTick < table.settleWindowTicks() || swarm.membersMissing(now)) {
            return;
        }
        Mob anchor = swarm.vanguard != null ? swarm.vanguard : active.get(0).mob;
        double r = table.remnantMergeRadius();
        Swarm best = null;
        double bestSq = r * r;
        for (Swarm other : SwarmManager.swarms(swarm.level())) {
            if (other == swarm || other.kind() != swarm.kind() || other.origin() != swarm.origin()
                    || other.climber() != swarm.climber()
                    || !Objects.equals(other.eventId(), swarm.eventId())
                    || !Objects.equals(other.huntTarget, swarm.huntTarget)) {
                continue;
            }
            Mob ov = other.vanguard();
            if (ov == null || !SwarmManager.hasRoom(other, active.size())) {
                continue;
            }
            double d = ov.distanceToSqr(anchor);
            if (d <= bestSq) {
                bestSq = d;
                best = other;
            }
        }
        if (best == null) {
            return;
        }
        for (Swarm.Member m : List.copyOf(active)) {
            SwarmManager.join(m.mob, best);
        }
    }

    // --- peak ---

    /** 群れがいちばん大きくなった頭数を、読み込まれている一員の所属へ書き戻す(増えたときだけ)。 */
    private static void writePeak(Swarm swarm) {
        swarm.peak = Math.max(swarm.peak, swarm.members.size());
        if (swarm.peak <= swarm.peakWritten) {
            return;
        }
        for (Swarm.Member m : swarm.members.values()) {
            SwarmMembership att = m.mob.getExistingData(ModAttachments.SWARM).orElse(null);
            if (att != null && att.peak() < swarm.peak) {
                m.mob.setData(ModAttachments.SWARM, att.withPeak(swarm.peak));
            }
        }
        swarm.peakWritten = swarm.peak;
    }

    private static double horizontalSq(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return dx * dx + dz * dz;
    }

    private SwarmController() {
    }
}
