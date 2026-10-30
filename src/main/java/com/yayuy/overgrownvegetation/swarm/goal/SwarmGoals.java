package com.yayuy.overgrownvegetation.swarm.goal;

import com.yayuy.overgrownvegetation.block.RepellentLampBlock;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.defense.RepellentLamps;
import com.yayuy.overgrownvegetation.raid.EventMobs;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import com.yayuy.overgrownvegetation.swarm.Swarm;
import com.yayuy.overgrownvegetation.swarm.SwarmMember;
import com.yayuy.overgrownvegetation.swarm.SwarmMembership;
import com.yayuy.overgrownvegetation.swarm.SwarmRole;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * 群れの Goal とエンティティ側の組み込み(群れ v2、2026-09-25)で共有する小さな判定。
 *
 * <p>どれも状態を持たない(ワールドや個体への参照を持ち越さない)。
 * <ul>
 *     <li>{@link #sameSwarm} … 同じ群れの一員どうしは押し合わない(レビュー指摘: 12 体の群れが縦に並ぶと、
 *     押し合いだけで陣形が崩れ、後続が足跡から押し出されて立て直しの経路探索を呼んでいた)。</li>
 *     <li>{@link #insideLamp} … 群れの Goal は点いている忌避灯の半径の中へ向かう点を選ばない
 *     (ユーザー指定「灯りのそばには寄れない」。忌避の Goal と群れの Goal が毎 tick 引っぱり合わないように)。</li>
 *     <li>{@link #breachIgnoresLamps} … その例外: TD の攻城で灯りを覆っている・灯りのそばの壁を壊している群れ(仮決め 2026-09-25)。</li>
 *     <li>{@link #blocksPortal} … 群れ・イベントの個体はポータルを使わない(別のディメンションへ行くと群れの名簿からも
 *     イベントの数えからも外れて、戻れないまま永続で残る)。</li>
 * </ul>
 */
public final class SwarmGoals {

    /**
     * {@code a} と {@code b} が同じ群れの一員か(所属の群れ ID で比べる)。名簿は引かない(押し合いは 1 tick に何度も呼ばれる)。
     * クライアントの個体は所属を持たないので常に false(押し合いはサーバーが決める)。
     */
    public static boolean sameSwarm(Entity a, Entity b) {
        if (!(a instanceof Mob ma) || !(b instanceof Mob mb) || a.level().isClientSide()) {
            return false;
        }
        SwarmMembership x = ma.getExistingData(ModAttachments.SWARM).orElse(null);
        if (x == null || x.isNone()) {
            return false;
        }
        SwarmMembership y = mb.getExistingData(ModAttachments.SWARM).orElse(null);
        return y != null && !y.isNone() && x.id().equals(y.id());
    }

    /** 点いている忌避灯の半径の中か。灯りが 1 つも無いワールドでは表を 1 回引くだけ。 */
    public static boolean insideLamp(Level level, Vec3 point) {
        return RepellentLamps.nearestActive(level, point, DesignData.defense().lampRadius()) != null;
    }

    /**
     * 壊す壁の輪の立ち位置(塊の中心から ~2.5)が灯りの半径に入りうる余り(ブロック)。仮決め 2026-09-25。
     */
    private static final double BREACH_LAMP_MARGIN = 3.0D;

    /**
     * 攻城(TD)で<b>忌避灯を覆っている・灯りの半径のそばの壁を壊している</b>(BREACH)群れか。その間、一員は灯りを避けない
     * ({@code entity/RepellentAvoidGoal} は押し出さず、{@link SwarmEngageGoal} は灯りの中の立ち位置へも向かう)。
     *
     * <p>仮決め 2026-09-25(TD 設計 v2 の敵対レビュー)。ユーザー指定「灯りのそばには寄れない」のままだと、ターゲットの横に
     * 灯りを 1 つ置くだけで地上の攻め手が拠点に触れなくなる。TD が拠点のそばの灯りを「壊す壁」に選んだら({@code td/TdSiege})、
     * 群れは灯りへ寄って壊さずに胞子で覆う(プレイヤーは右クリックで戻せる)。灯りの半径の中の壁も同じ(壁の前に立てないと壊せず、
     * 壁の向こうの灯りにも届かない)。BREACH は TD しか入れないので、ふだんの群れ・個体の灯りの扱いは変わらない。
     */
    public static boolean breachIgnoresLamps(@Nullable Swarm swarm) {
        if (swarm == null || swarm.mode() != Swarm.Mode.BREACH) {
            return false;
        }
        BlockPos pos = swarm.breachPos();
        if (pos == null) {
            return false;
        }
        ServerLevel level = swarm.level();
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null) {
            return false;
        }
        if (chunk.getBlockState(pos).getBlock() instanceof RepellentLampBlock) {
            return true;
        }
        return RepellentLamps.nearestActive(level, Vec3.atCenterOf(pos),
                DesignData.defense().lampRadius() + BREACH_LAMP_MARGIN) != null;
    }

    /**
     * イベントの印({@link EventMobs})を持っているか。印は永続の個体にしか付かないので、永続でなければ見ない。
     * 印の中身は {@link EventMobs#taggedAt} で読む(公開の問い合わせがそれだけのため)。2026-09-25 より前に付いた
     * レイド・攻城戦の印は時刻を持たないので拾えない(その個体は群れの所属も持たないので、ポータルの判定に漏れるだけ)。
     */
    public static boolean hasEventTag(Mob mob) {
        if (!mob.isPersistenceRequired()) {
            return false;
        }
        for (EventMobs.Kind kind : EventMobs.Kind.values()) {
            if (EventMobs.taggedAt(mob, kind) >= 0L) {
                return true;
            }
        }
        return false;
    }

    /** ポータルを使わせないか(群れの所属、またはイベントの印を持っている)。{@code canUsePortal} から。 */
    public static boolean blocksPortal(Mob mob) {
        return SwarmMember.hasSwarmAttachment(mob) || hasEventTag(mob);
    }

    /**
     * 地上の群れの動いている一員が全員アルボラか。先導役が川を跳び越えてよいのはこのときだけ
     * (ほかの種は跳べないので、混成の群れで先導役だけ跳ぶと後続が岸に取り残される)。
     */
    public static boolean allArbora(Swarm swarm) {
        if (swarm.kind() != Swarm.Kind.GROUND) {
            return false;
        }
        for (Mob m : swarm.activeMembers()) {
            if (!SwarmRole.isArbora(m.getType())) {
                return false;
            }
        }
        return true;
    }

    /** 水平の距離の 2 乗。 */
    static double horizontalSq(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return dx * dx + dz * dz;
    }

    private SwarmGoals() {
    }
}
