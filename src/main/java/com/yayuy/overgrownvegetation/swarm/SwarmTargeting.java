package com.yayuy.overgrownvegetation.swarm;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.effect.SporeInfectionDetection;
import com.yayuy.overgrownvegetation.entity.PhyllosensInfluence;
import com.yayuy.overgrownvegetation.entity.Predation;
import com.yayuy.overgrownvegetation.entity.VelophyllTargetGoal;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import com.yayuy.overgrownvegetation.raid.SmallRaidHunt;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * 群れの<b>標的</b>の選び方(群れ v2、2026-09-25)。群れの一員は自分で標的を探さない —— 司令塔がここで 1 回探し、
 * 役割ごとの距離で一員へ渡す({@link SwarmController})。
 *
 * <h2>優先順(小さいほど先)</h2>
 * <ol type="a">
 *     <li>小規模レイドの狙い・湧かせた側の指定({@link Swarm.TargetReason#HUNT})。距離の上限なし</li>
 *     <li>視線持ちのプレイヤー({@link Swarm.TargetReason#WATCHED})。<b>群れの誰かが誘導を受けているときだけ</b>、
 *     距離の上限なし(ユーザー指定「視線持ちのプレイヤー優先」「どれだけ離れていても感知して襲いに行く」)。
 *     汚染を広げている間(SPREAD)は新しく拾わない(仮決め 2026-09-25。{@link #think} の b)</li>
 *     <li>群れを殴ったプレイヤーへの反撃({@link Swarm.TargetReason#RETALIATION})。仮決め 2026-09-25(ユーザー未確認):
 *     a・b の下。はじめはいちばん上で、誰が殴っても次の探索で狙い・視線持ちから群れを奪っていた(レビュー指摘)</li>
 *     <li>動いている一員の誰かの追跡範囲({@code FOLLOW_RANGE} + 胞子感染などの被検知ボーナス)に入ったプレイヤー。
 *     {@code level.players()} だけを回す</li>
 *     <li>獲物(グループ1〜3、先導役から 48 = 旧 {@code InvaderPreyIntel} の半径)とグループ外の生き物(先導役の追跡範囲)。
 *     群れにつき 40 tick に 1 回だけ箱で探す(被検知ボーナスは箱の中で足す。箱そのものは広げない)</li>
 * </ol>
 * 今の標的は、同じか低い優先の候補が見つかっても替えない(同じ探索で捨てて拾い直す揺れを出さない)。
 * d・e は探す距離の {@code leashMultiplier}(1.5)倍まで離れるまで追い続ける。諦めた相手({@link Swarm#giveUpTarget})は
 * {@code giveUpTicks} のあいだ候補にしない。
 *
 * <p>攻城(SIEGE / BREACH)では c と、先導役から {@code siegeEngageRadius}(24)以内のプレイヤー・グループ外の生き物だけ。
 * グループ外の生き物は標的の塊から {@value #SIEGE_OUTSIDER_BASE_RANGE} 以内のものだけ(2026-09-25)
 * (狙い・獲物・視線は追わない。追うと標的の塊を攻めなくなる)。
 */
final class SwarmTargeting {

    // --- 読み込み・登録 ---

    /**
     * 一員が登録されたとき。小規模レイドの狙い({@code hunt_target})を群れの狙いとして読む。
     * 群れがもう諦めた狙いを持っていれば、その場で消す(あとから読み込まれた一員が永続のまま残らないように。レビュー指摘)。
     */
    static void onRegistered(Swarm swarm, Mob mob) {
        String raw = mob.getExistingData(ModAttachments.HUNT_TARGET).orElse("");
        if (raw.isEmpty()) {
            return;
        }
        if (swarm.givenUpHunts.contains(raw)) {
            SmallRaidHunt.giveUp(mob);
            return;
        }
        if (swarm.huntTarget == null) {
            UUID id = SmallRaidHunt.parseTarget(raw);
            if (id == null) {
                SmallRaidHunt.giveUp(mob);
                return;
            }
            swarm.huntTarget = id;
            swarm.huntLostTicks = 0;
            swarm.huntFailedLegs = 0;
        }
    }

    /**
     * 狙いのプレイヤーを諦めるかを見る。規則は小規模レイドの {@link SmallRaidHunt} と同じ(2026-09-25、「レイドの見直し」):
     * 見つからないまま {@code huntLostGraceTicks} 経った(ログアウト・別のワールド)/ 死んだ・観戦・クリエイティブ /
     * 先導役の区間が {@link SmallRaidHunt#UNREACHABLE_LEGS} 回続けて失敗した(川や海の向こう)/ 一員の印の期限切れ
     * ({@link SmallRaidHunt#expired})。群れの一員は個体の狩りの Goal を回さないので、狩りの寿命はここで持つ(レビュー指摘:
     * 後続の狙いを誰も消さず、小規模レイドのたびに永続の個体が残る)。
     */
    static void tickHunt(Swarm swarm, int elapsedTicks, long now) {
        UUID id = swarm.huntTarget;
        if (id == null) {
            return;
        }
        boolean giveUp;
        Player player = swarm.level().getPlayerByUUID(id);
        if (player == null) {
            swarm.huntLostTicks += elapsedTicks;
            giveUp = swarm.huntLostTicks > Swarm.table().huntLostGraceTicks();
        } else {
            swarm.huntLostTicks = 0;
            giveUp = SmallRaidHunt.quarryOutOfPlay(player);
        }
        giveUp |= SmallRaidHunt.unreachable(swarm.huntFailedLegs);
        if (!giveUp) {
            for (Swarm.Member m : swarm.members.values()) {
                if (SmallRaidHunt.expired(m.mob, now)) {
                    giveUp = true;
                    break;
                }
            }
        }
        if (giveUp) {
            giveUpHunt(swarm);
        }
    }

    /** 狙いを諦める。読み込まれている一員の {@code hunt_target} を消し、小規模レイドの永続を外す。 */
    static void giveUpHunt(Swarm swarm) {
        UUID id = swarm.huntTarget;
        if (id == null) {
            return;
        }
        String raw = id.toString();
        if (!swarm.givenUpHunts.contains(raw)) {
            swarm.givenUpHunts.add(raw);
        }
        swarm.huntTarget = null;
        swarm.huntFailedLegs = 0;
        if (swarm.target != null && swarm.targetReason == Swarm.TargetReason.HUNT && id.equals(swarm.target.getUUID())) {
            swarm.setTarget(null, Swarm.TargetReason.OTHER);
        }
        for (Mob mob : swarm.members()) {
            if (raw.equals(mob.getExistingData(ModAttachments.HUNT_TARGET).orElse(""))) {
                SmallRaidHunt.giveUp(mob);
            }
        }
    }

    // --- 考えるたび ---

    /**
     * 今の標的を見直し、探す周期なら探し直す。
     *
     * @param active 動いている一員
     * @param anchor 先導役(空の群れでは先頭)
     */
    static void think(Swarm swarm, List<Swarm.Member> active, Mob anchor, long now) {
        SwarmTable table = Swarm.table();
        if (swarm.retaliationPlayer != null && now >= swarm.retaliationUntil) {
            swarm.retaliationPlayer = null;
            if (swarm.targetReason == Swarm.TargetReason.RETALIATION) {
                swarm.targetReason = Swarm.TargetReason.PLAYER;
            }
        }
        swarm.gaveUpUntil.values().removeIf(until -> now >= until);

        // 今の標的を見直す(毎回。死んだ・消えた・遠すぎる相手はその場で外す)。
        if (swarm.target != null) {
            Swarm.TargetReason kept = keepReason(swarm, swarm.target, swarm.targetReason, active, anchor);
            if (kept == null) {
                if (swarm.cachedOther == swarm.target) {
                    swarm.cachedOther = null;
                }
                swarm.setTarget(null, Swarm.TargetReason.OTHER);
            } else {
                swarm.targetReason = kept;
            }
        }

        if (now - swarm.lastTargetSearch < table.targetSearchIntervalTicks()) {
            return;
        }
        int elapsed = swarm.lastTargetSearch == Swarm.NEVER ? table.targetSearchIntervalTicks()
                : (int) Math.min(Integer.MAX_VALUE, now - swarm.lastTargetSearch);
        swarm.lastTargetSearch = now;
        tickHunt(swarm, elapsed, now);
        swarm.anyGuided = false;
        for (Swarm.Member m : active) {
            if (PhyllosensInfluence.isGuided(m.mob)) {
                swarm.anyGuided = true;
                break;
            }
        }

        boolean siege = isSiege(swarm);
        LivingEntity best = null;
        Swarm.TargetReason bestReason = null;

        // 見つけた順に優先(TargetReason の rank の順)。
        // a) 小規模レイドの狙い・指定された相手
        if (swarm.huntTarget != null && !siege) {
            Player p = swarm.level().getPlayerByUUID(swarm.huntTarget);
            if (p != null && keepReason(swarm, p, Swarm.TargetReason.HUNT, active, anchor) != null) {
                best = p;
                bestReason = Swarm.TargetReason.HUNT;
            }
        }
        // b) 視線持ちのプレイヤー(誰かが誘導を受けているときだけ)。
        // 汚染を広げている間(SPREAD)は新しく拾わない(仮決め 2026-09-25、ユーザー確認待ち。レビュー指摘): 群れのフィロセンスは
        // SPREAD で索敵し、最初の tick に 512 ブロック以内のプレイヤー全員へ視線を配る。ここで拾うと 20 tick 以内に SPREAD が終わり、
        // 「暇なら止まって全員で汚染を広げる」(ユーザー指定 2026-09-25)が、フィロセンスのいる群れでは一度も最後まで行かなかった
        // (うずくまり・座り込みが途中で切れて芽吹きも増殖も起きない)。SPREAD が終われば(上限 600 tick。視線は 1200 tick 残る)
        // 次の探索で拾って狩りに行く。追跡範囲にいる視線持ちは d でふつうのプレイヤーとして拾うので、そばにいれば SPREAD はすぐ終わる。
        if (best == null && swarm.anyGuided && !siege && swarm.mode != Swarm.Mode.SPREAD) {
            Player p = PhyllosensInfluence.nearestWatchedPlayer(swarm.level(), anchor);
            if (p != null && keepReason(swarm, p, Swarm.TargetReason.WATCHED, active, anchor) != null) {
                best = p;
                bestReason = Swarm.TargetReason.WATCHED;
            }
        }
        // c) 群れを殴ったプレイヤー
        if (best == null && swarm.retaliationPlayer != null) {
            Player p = swarm.level().getPlayerByUUID(swarm.retaliationPlayer);
            if (p != null && keepReason(swarm, p, Swarm.TargetReason.RETALIATION, active, anchor) != null) {
                best = p;
                bestReason = Swarm.TargetReason.RETALIATION;
            }
        }
        // d) 追跡範囲の中のプレイヤー
        if (best == null) {
            Player p = nearestPlayerInRange(swarm, active, anchor, siege);
            if (p != null) {
                best = p;
                bestReason = Swarm.TargetReason.PLAYER;
            }
        }
        // e) 獲物・グループ外(箱で探すのは 40 tick に 1 回。間は控えを見直すだけ)
        if (best == null) {
            if (now - swarm.lastOtherSearch >= table.otherSearchIntervalTicks()) {
                swarm.lastOtherSearch = now;
                swarm.cachedOther = searchOther(swarm, anchor, siege);
            } else if (swarm.cachedOther != null
                    && keepReason(swarm, swarm.cachedOther, Swarm.TargetReason.OTHER, active, anchor) == null) {
                swarm.cachedOther = null;
            }
            if (swarm.cachedOther != null) {
                best = swarm.cachedOther;
                bestReason = Swarm.TargetReason.OTHER;
            }
        }

        if (best == null) {
            return;
        }
        // 同じか低い優先の候補では今の標的を替えない(揺れよけ)。
        if (swarm.target == null || bestReason.rank < swarm.targetReason.rank) {
            swarm.setTarget(best, bestReason);
        }
    }

    /**
     * {@code entity} を {@code reason} の標的として持ち続けてよいか。よければ(視線が切れたプレイヤーを
     * ふつうのプレイヤーへ落とすなど)持ち続ける理由、だめなら null。
     */
    @Nullable
    static Swarm.TargetReason keepReason(Swarm swarm, LivingEntity entity, Swarm.TargetReason reason,
                                         List<Swarm.Member> active, Mob anchor) {
        if (!entity.isAlive() || entity.isRemoved() || entity.level() != swarm.level()) {
            return null;
        }
        if (swarm.hasGivenUp(entity) || !validKind(swarm, anchor, entity)) {
            return null;
        }
        if (swarm.testBounds != null && !swarm.testBounds.contains(entity.position())) {
            return null;
        }
        SwarmTable table = Swarm.table();
        if (isSiege(swarm)) {
            double r = swarm.siegeEngageRadius() * table.leashMultiplier();
            return anchor.distanceToSqr(entity) <= r * r ? reason : null;
        }
        switch (reason) {
            case HUNT -> {
                // 距離の上限なし。狙いのプレイヤーである限り持ち続ける。
                return entity.getUUID().equals(swarm.huntTarget) ? reason : null;
            }
            case WATCHED -> {
                if (entity instanceof Player player && PhyllosensInfluence.isWatched(player) && swarm.anyGuided) {
                    return reason;
                }
                // 視線が切れた / 誰も誘導を受けていない。ふつうのプレイヤーとして追える距離なら持ち続ける。
                return withinPlayerLeash(swarm, entity, active, table) && landReachable(swarm, anchor, entity)
                        ? Swarm.TargetReason.PLAYER : null;
            }
            case RETALIATION, PLAYER -> {
                return withinPlayerLeash(swarm, entity, active, table) && landReachable(swarm, anchor, entity) ? reason : null;
            }
            default -> {
                double base = anchor.getAttributeValue(Attributes.FOLLOW_RANGE);
                double range = Predation.isInvaderPreyGroup(entity) ? Math.max(table.preySearchRadius(), base) : base;
                double r = (range + SporeInfectionDetection.bonusFor(anchor, entity)) * table.leashMultiplier();
                return anchor.distanceToSqr(entity) <= r * r && landReachable(swarm, anchor, entity) ? reason : null;
            }
        }
    }

    /** 群れの標的になりうる相手か(プレイヤー・グループ外・グループ1〜3)。空の群れは爆撃か噛みつきで届く相手だけ。 */
    static boolean validKind(Swarm swarm, Mob anchor, LivingEntity entity) {
        if (entity instanceof Player player && (player.isSpectator() || player.isCreative())) {
            return false;
        }
        if (entity instanceof Mob mob && swarm.contains(mob)) {
            return false;
        }
        boolean ok = Predation.isInvaderTarget(anchor, entity) || Predation.isInvaderPreyGroup(entity);
        if (!ok) {
            return false;
        }
        return swarm.kind() != Swarm.Kind.AIR || VelophyllTargetGoal.isAttackable(anchor, entity);
    }

    /** 陸の足で届く相手か(水に入っていない・ずっと深く潜っていない。旧 {@code OvergrownLandTargetGoal} と同じ)。空の群れは問わない。 */
    private static boolean landReachable(Swarm swarm, Mob anchor, LivingEntity entity) {
        if (swarm.kind() == Swarm.Kind.AIR) {
            return true;
        }
        return Predation.isLandTarget(anchor, entity) && Predation.isWithinChaseDepth(anchor, entity);
    }

    /** 動いている一員の誰かの(追跡範囲 + 被検知ボーナス)× {@code leashMultiplier} 以内か。 */
    private static boolean withinPlayerLeash(Swarm swarm, LivingEntity entity, List<Swarm.Member> active, SwarmTable table) {
        for (Swarm.Member m : active) {
            double r = (m.mob.getAttributeValue(Attributes.FOLLOW_RANGE)
                    + SporeInfectionDetection.bonusFor(m.mob, entity)) * table.leashMultiplier();
            if (m.mob.distanceToSqr(entity) <= r * r) {
                return true;
            }
        }
        return false;
    }

    /** d) 動いている一員の誰かの追跡範囲(+ 被検知ボーナス)に入ったプレイヤーのうち、先導役にいちばん近い人。 */
    @Nullable
    private static Player nearestPlayerInRange(Swarm swarm, List<Swarm.Member> active, Mob anchor, boolean siege) {
        ServerLevel level = swarm.level();
        Player best = null;
        double bestSq = Double.MAX_VALUE;
        double siegeSq = swarm.siegeEngageRadius() * swarm.siegeEngageRadius();
        for (ServerPlayer player : level.players()) {
            if (!player.isAlive() || player.isSpectator() || player.isCreative()) {
                continue;
            }
            double toAnchor = anchor.distanceToSqr(player);
            if (toAnchor >= bestSq) {
                continue;
            }
            if (siege && toAnchor > siegeSq) {
                continue;
            }
            if (swarm.hasGivenUp(player) || (swarm.testBounds != null && !swarm.testBounds.contains(player.position()))) {
                continue;
            }
            if (!siege && !inAnyFollowRange(active, player)) {
                continue;
            }
            if (!validKind(swarm, anchor, player) || !landReachable(swarm, anchor, player)) {
                continue;
            }
            best = player;
            bestSq = toAnchor;
        }
        return best;
    }

    private static boolean inAnyFollowRange(List<Swarm.Member> active, LivingEntity entity) {
        for (Swarm.Member m : active) {
            double r = m.mob.getAttributeValue(Attributes.FOLLOW_RANGE) + SporeInfectionDetection.bonusFor(m.mob, entity);
            if (m.mob.distanceToSqr(entity) <= r * r) {
                return true;
            }
        }
        return false;
    }

    /**
     * e) 先導役のまわりの箱で、獲物(半径 {@code preySearchRadius})とグループ外の生き物(先導役の追跡範囲)を探す。
     * 条件は安いものから(グループの判定 → 距離 → 種類 → 水・深さ)。被検知ボーナスは箱の中で足す(箱は広げない)。
     */
    @Nullable
    private static LivingEntity searchOther(Swarm swarm, Mob anchor, boolean siege) {
        SwarmTable table = Swarm.table();
        double base = anchor.getAttributeValue(Attributes.FOLLOW_RANGE);
        double prey = siege ? 0.0D : table.preySearchRadius();
        double outsider = siege ? Math.min(base, swarm.siegeEngageRadius()) : base;
        double r = Math.max(outsider, prey);
        if (r <= 0.0D) {
            return null;
        }
        double height = swarm.kind() == Swarm.Kind.AIR ? r : table.otherSearchHeight();
        AABB box = anchor.getBoundingBox().inflate(r, height, r);
        List<LivingEntity> found = swarm.level().getEntitiesOfClass(LivingEntity.class, box,
                e -> candidateOther(swarm, anchor, e, outsider, prey));
        LivingEntity best = null;
        double bestSq = Double.MAX_VALUE;
        for (LivingEntity e : found) {
            double d = anchor.distanceToSqr(e);
            if (d < bestSq) {
                bestSq = d;
                best = e;
            }
        }
        return best;
    }

    private static boolean candidateOther(Swarm swarm, Mob anchor, LivingEntity e, double outsiderRange, double preyRange) {
        if (e instanceof Player || !e.isAlive() || e == anchor) {
            return false;
        }
        MobGroup group = MobGroup.of(e.getType());
        boolean prey = group == MobGroup.GROUP_1 || group == MobGroup.GROUP_2 || group == MobGroup.GROUP_3;
        if (group != null && !prey) {
            // グループ4〜10 は味方。
            return false;
        }
        double d2 = anchor.distanceToSqr(e);
        if (prey) {
            double allowed = preyRange + SporeInfectionDetection.bonusFor(anchor, e);
            if (preyRange <= 0.0D || d2 > allowed * allowed) {
                return false;
            }
        } else {
            double allowed = outsiderRange + SporeInfectionDetection.bonusFor(anchor, e);
            if (d2 > allowed * allowed || !Predation.isInvaderTarget(anchor, e)) {
                return false;
            }
            if (isSiege(swarm) && !nearSiegeBlock(swarm, e)) {
                // 攻城では拠点のまわりの生き物だけ(防衛側・拠点の住人)。2026-09-25(ユーザー報告「TD 中にターゲットへ行かず、
                // 湧いた場所でずっとくるくる」の調べ): 先導役のまわり 24 の生き物を全部相手にしていたので、湧いた場所の牛・村人・
                // 崖の上の動物を追いかけて塊へ向かわなかった。殴ってきた相手には一員がそれぞれ反撃する(onMemberHurt)。
                return false;
            }
        }
        if (swarm.hasGivenUp(e) || (swarm.testBounds != null && !swarm.testBounds.contains(e.position()))) {
            return false;
        }
        if (swarm.kind() == Swarm.Kind.AIR) {
            return VelophyllTargetGoal.isAttackable(anchor, e);
        }
        return Predation.isLandTarget(anchor, e) && Predation.isWithinChaseDepth(anchor, e);
    }

    private static boolean isSiege(Swarm swarm) {
        return swarm.mode == Swarm.Mode.SIEGE || swarm.mode == Swarm.Mode.BREACH;
    }

    /** 攻城で相手にしてよい生き物の、標的の塊からの距離(水平、ブロック)。仮決め 2026-09-25。 */
    static final double SIEGE_OUTSIDER_BASE_RANGE = 32.0D;

    /** 標的の塊から {@value #SIEGE_OUTSIDER_BASE_RANGE} 以内か(塊が無ければ true)。 */
    private static boolean nearSiegeBlock(Swarm swarm, LivingEntity e) {
        BlockPos block = swarm.targetBlock;
        if (block == null) {
            return true;
        }
        double dx = e.getX() - (block.getX() + 0.5D);
        double dz = e.getZ() - (block.getZ() + 0.5D);
        return dx * dx + dz * dz <= SIEGE_OUTSIDER_BASE_RANGE * SIEGE_OUTSIDER_BASE_RANGE;
    }

    // --- 役割ごとの距離 ---

    /**
     * 一員 {@code m} へ {@code e} を渡してよい距離か(+ {@code margin})。
     * <ul>
     *     <li>前衛(アルボラ): 3D で {@code engageRadius}(12)。壁を登れない前衛は高さの差 {@code groundDyLimit}(4)まで。</li>
     *     <li>前衛(スカトゥリウム): 迫撃砲の射程 {@code shielderMortarMaxRange}(突進・迫撃砲・追跡は自分の Goal)。</li>
     *     <li>遠距離(フィロセンス): ビームの射程({@code mobs.json} の beam.range)。</li>
     *     <li>支援(スポロフィル): 渡さない。</li>
     *     <li>空(ヴェロフィル): 水平で {@code airHandRadius}(64)。高さは問わない。</li>
     * </ul>
     */
    static boolean withinRole(Swarm.Member m, LivingEntity e, double margin) {
        SwarmTable table = Swarm.table();
        Mob mob = m.mob;
        switch (m.role) {
            case FRONT -> {
                if (SwarmRole.isShielder(mob.getType())) {
                    double r = OvergrownVegetationConfig.COMMON.shielderMortarMaxRange.get() + margin;
                    return mob.distanceToSqr(e) <= r * r;
                }
                double r = table.engageRadius() + margin;
                if (mob.distanceToSqr(e) > r * r) {
                    return false;
                }
                return SwarmRole.canClimb(mob.getType()) || Math.abs(e.getY() - mob.getY()) <= table.groundDyLimit() + margin;
            }
            case RANGED_SCOUT -> {
                double r = MobStats.beam("phyllosens").range() + margin;
                return mob.distanceToSqr(e) <= r * r;
            }
            case AIR -> {
                double r = table.airHandRadius() + margin;
                double dx = mob.getX() - e.getX();
                double dz = mob.getZ() - e.getZ();
                return dx * dx + dz * dz <= r * r;
            }
            default -> {
                return false;
            }
        }
    }

    // --- 殴られた ---

    /**
     * 一員が殴られた({@code hurt} から)。
     * <ul>
     *     <li>殴った相手がその一員の役割の距離にいれば、<b>その一員だけ</b> {@code localRetaliationTicks}(100)のあいだ
     *     反撃させる(群れの標的は変えない。レビュー指摘「群れがプレイヤーを追っている間、狼に殴られ放題」)。</li>
     *     <li>群れの標的を殴った相手へ替えるのは、群れに標的が無いときか、相手がプレイヤーで、今の標的の理由が反撃
     *     ({@link Swarm.TargetReason#RETALIATION})より下(ふつうのプレイヤー・獲物・グループ外)のときだけ。小規模レイドの狙い・
     *     視線持ちのプレイヤー・先に殴ったプレイヤーを追っている群れは、殴られても追う相手を替えない(殴られた一員だけ反撃する)。
     *     次の探索の優先順({@link #think} の a〜e)と同じ規則(レビュー指摘: はじめは探索のほうだけ反撃がいちばん上で、
     *     ここの規則と食い違っていた)。</li>
     * </ul>
     * 殴ったプレイヤーは覚えておき({@code retaliationMemoryTicks})、今の標的がいなくなれば次の探索で反撃の標的になる。
     * 支援(スポロフィル)は自分では反撃しない(標的を持たない種)。
     */
    static void onMemberHurt(Swarm swarm, Mob mob, DamageSource source) {
        Entity src = source.getEntity();
        if (!(src instanceof LivingEntity attacker) || attacker == mob || !attacker.isAlive()) {
            return;
        }
        if (attacker instanceof Mob attackerMob && swarm.contains(attackerMob)) {
            return;
        }
        if (attacker instanceof Player player && (player.isCreative() || player.isSpectator())) {
            return;
        }
        if (!mob.canAttack(attacker)) {
            return;
        }
        SwarmTable table = Swarm.table();
        long now = swarm.level().getGameTime();
        swarm.gaveUpUntil.remove(attacker.getUUID());
        Swarm.Member m = swarm.member(mob);
        if (m != null && m.role != SwarmRole.SUPPORT && withinRole(m, attacker, 0.0D)) {
            m.localTarget = attacker;
            m.localUntil = now + table.localRetaliationTicks();
            m.handed = attacker;
            if (mob.getTarget() != attacker) {
                mob.setTarget(attacker);
            }
        }
        boolean isPlayer = attacker instanceof Player;
        if (isPlayer) {
            swarm.retaliationPlayer = attacker.getUUID();
            swarm.retaliationUntil = now + table.retaliationMemoryTicks();
        }
        if (swarm.target == null
                || (isPlayer && Swarm.TargetReason.RETALIATION.rank < swarm.targetReason.rank)) {
            Mob anchor = swarm.vanguard() != null ? swarm.vanguard() : mob;
            if (validKind(swarm, anchor, attacker)) {
                swarm.setTarget(attacker, isPlayer ? Swarm.TargetReason.RETALIATION : Swarm.TargetReason.OTHER);
            }
        }
    }

    private SwarmTargeting() {
    }
}
