package com.yayuy.overgrownvegetation.swarm;

import com.yayuy.overgrownvegetation.registry.ModAttachments;
import net.minecraft.world.entity.Mob;
import org.jetbrains.annotations.Nullable;

/**
 * 群れの一員の側(エンティティ・Goal)から使う短い問い合わせ(群れ v2、2026-09-25)。
 * どれもサーバーの個体でだけ意味を持つ(クライアントの個体では「群れに居ない」)。毎 tick 呼んでよい(軽い)。
 *
 * <ul>
 *     <li>{@link #isInSwarm} … 群れの一員として動いているか。個体の索敵・反撃・近接・散歩・うずくまりなどを止める判定。</li>
 *     <li>{@link #isFollower} … 先導役以外の一員。立て直し・坑道・水から上がる(次の点が SWIM のとき)も止める。</li>
 *     <li>{@link #isSwarmSteered} … 詰まり検知の「標的へ押し当てる」枝を止める({@code tickStuckDetection})。</li>
 *     <li>{@link #hasSwarmAttachment} … 所属の attachment を持っているか(名簿で引けなくても)。ポータルを使わせない判定用。</li>
 * </ul>
 */
public final class SwarmMember {

    /** 群れの一員か(所属があり、群れの名簿で引ける)。 */
    public static boolean isInSwarm(Mob mob) {
        return SwarmManager.get(mob) != null;
    }

    /** その個体の群れ。居なければ null。 */
    @Nullable
    public static Swarm swarmOf(Mob mob) {
        return SwarmManager.get(mob);
    }

    /** 先導役(空の群れは先頭)か。 */
    public static boolean isVanguard(Mob mob) {
        Swarm swarm = SwarmManager.get(mob);
        return swarm != null && swarm.isVanguard(mob);
    }

    /** 先導役以外の一員か。 */
    public static boolean isFollower(Mob mob) {
        Swarm swarm = SwarmManager.get(mob);
        return swarm != null && swarm.isFollower(mob);
    }

    /**
     * 群れに<b>動かされている</b>か(レビュー指摘: 後続に標的を渡すと、個体の詰まり検知が毎 tick その標的へ体を押し当て、
     * 60 tick で「詰まった」印を立てて坑道を掘り始める)。true のあいだ、詰まり検知の標的の枝は進捗を数え直すだけにする。
     * <ul>
     *     <li>後続は常に true。</li>
     *     <li>先導役は、交戦中({@link Swarm#isVanguardEngaged})・標的を渡されている(自分だけの反撃も)・攻城(SIEGE / BREACH)の間。
     *     交戦中の先導役は囲む Goal が輪の上の位置へ動かしていて、ナビは止まっている —— そこへ詰まり検知の「標的へ押し当てる」枝が
     *     囲む位置を毎 tick 上書きし、60 tick で詰まった印、2 回で標的を諦めて殴れなくなっていた(レビュー指摘)。
     *     攻城では壁を壊すかを先導の Goal が自分で決める。</li>
     * </ul>
     * 標的も区間も無い先導役(暇なとき)は false のまま —— 区間を歩いて進めない先導役は、詰まり検知から回り道の Goal を使う。
     */
    public static boolean isSwarmSteered(Mob mob) {
        Swarm swarm = SwarmManager.get(mob);
        if (swarm == null) {
            return false;
        }
        if (swarm.isFollower(mob)) {
            return true;
        }
        return swarm.isVanguardEngaged()
                || swarm.handedTarget(mob) != null || swarm.localTarget(mob) != null
                || swarm.mode() == Swarm.Mode.SIEGE || swarm.mode() == Swarm.Mode.BREACH;
    }

    /** 群れの中の役割。群れに居なければ null。 */
    @Nullable
    public static SwarmRole role(Mob mob) {
        Swarm swarm = SwarmManager.get(mob);
        return swarm != null ? swarm.role(mob) : null;
    }

    /** 所属の attachment を持っているか(名簿で引けるかは問わない)。 */
    public static boolean hasSwarmAttachment(Mob mob) {
        return mob.getExistingData(ModAttachments.SWARM).map(m -> !m.isNone()).orElse(false);
    }

    /** 一員の tick から毎 tick 呼ぶ(自己修復。{@link SwarmManager#memberTick})。 */
    public static void tick(Mob mob) {
        SwarmManager.memberTick(mob);
    }

    private SwarmMember() {
    }
}
