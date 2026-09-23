package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * <b>群れの所属を決める</b>(ユーザー要望 2026-09-15:
 * 「近くにいたら群れグループを作り、常に同じ個体の群れを形成し続けるように」)。
 *
 * <p>各個体が {@code invaderPackBondIntervalTicks} おきに 1 回だけ自分の所属を見直す。
 * やることは 4 つだけで、上から順に試す:
 *
 * <ol>
 *     <li><b>入る</b> —— 無所属で、近くに群れ持ちが居るなら<b>いちばん人数の多い群れ</b>に入る。</li>
 *     <li><b>作る</b> —— 無所属で、近くの無所属が自分を含めて {@code invaderPackFormMinMembers} 体
 *     以上いるなら、新しい ID を発行して<b>その場の無所属全員に配る</b>。
 *     「近くにいたら群れになる」の本体。</li>
 *     <li><b>合流する</b> —— 所属済みで、近くに<b>自分の群れより大きい群れ</b>が居るならそちらへ移る。
 *     半端な群れ同士が出会ったときに 1 つにまとまる。</li>
 *     <li><b>抜ける</b> —— 仲間を {@code invaderPackDisbandTicks} のあいだ一度も見かけなければ、
 *     群れはもう無いものとして所属を捨てる。次の見直しで新しい群れを作り直せる。</li>
 * </ol>
 *
 * <h2>行ったり来たりしない理由</h2>
 * 合流の判定は<b>「人数が多いほう」、同数なら「ID が小さいほう」</b>という
 * 全順序で決めている。どの個体から見ても勝ち負けが同じになるので、
 * A が B へ移った次の瞬間に B が A へ移り返す、という揺れが起きない。
 *
 * <h2>離れても解散しない</h2>
 * 所属は<b>距離ではなく時間</b>で切れる({@code invaderPackDisbandTicks}、既定 5 分)。
 * 戦いで散っても、迎えに行っている最中でも、少し離れたくらいでは群れは壊れない。
 * 「常に同じ個体の群れであり続ける」という要望はここで担保している。
 */
public final class InvaderPackBonds {

    /**
     * 建設グループの一員は<b>ふつうの群れに入らない</b>(ユーザー指定 2026-09-20)。
     * アルボラだけの特別な群れなので、編成の抽選から外す。
     */
    private static boolean isBuilder(net.minecraft.world.entity.Mob mob) {
        return mob instanceof AbstractOvergrownInvaderEntity invader
                && invader.nestRole() == com.yayuy.overgrownvegetation.entity.NestRole.BUILDER;
    }


    /**
     * 自分の所属を見直す。毎 tick 呼んでよい(中で間引く)。
     *
     * <p>見直しの位相は個体ごとにずらしてある —— 同じ tick に全員が周囲を走査すると、
     * そこだけ処理が跳ねる({@link InvaderPack#shareTarget} と同じ理由)。
     */
    public static void tick(Mob mob) {
        if (!(mob.level() instanceof ServerLevel) || !(mob instanceof InvaderPackMember self)) {
            return;
        }
        if (isBuilder(mob)) {
            self.packBond().setId(null);      // 建設グループはふつうの群れに入らない(2026-09-20)
            return;
        }
        int interval = OvergrownVegetationConfig.COMMON.invaderPackBondIntervalTicks.get();
        if (interval <= 0
                || Math.floorMod(mob.tickCount, interval) != Math.floorMod(mob.getId(), interval)) {
            return;
        }
        InvaderPackBond bond = self.packBond();
        List<Mob> neighbours = InvaderPack.membersNear(mob, InvaderPack.scanRange());

        if (bond.id() == null) {
            bond.clearComplete();
            joinOrForm(mob, bond, neighbours);
            return;
        }
        if (mergeIntoBigger(mob, bond, neighbours)) {
            return;
        }
        expireIfAlone(mob, bond);
        checkComplete(mob, self, bond);
    }

    /**
     * <b>編成が揃っているか</b>を見て、揃っていれば「消えない」印を更新する
     * (ユーザー要望 2026-09-15「群れが最低数を満たしたらデスポーンしなくなるように」)。
     *
     * <p>数えるのは<b>迎えに行く距離まで</b>({@code invaderPackRecruitRange})。
     * ふだんの群れの輪({@code invaderPackScanRange})で数えると、
     * 足りない種を迎えに行っている個体が輪の外へ出た瞬間に「編成が崩れた」ことになり、
     * <b>揃った群れが迎えに出るたびに刈られうる</b>。
     *
     * <p>印は<b>期限</b>({@code invaderPackCompleteGraceTicks})で持つ。
     * 確認はこの間隔に 1 回しか行わないので、そのとき偶然 1 体が離れていただけで
     * 永続性が落ちてしまわないようにするため。
     */
    private static void checkComplete(Mob mob, InvaderPackMember self, InvaderPackBond bond) {
        int grace = OvergrownVegetationConfig.COMMON.invaderPackCompleteGraceTicks.get();
        if (grace <= 0) {
            bond.clearComplete();
            return;
        }
        List<Mob> mates = InvaderPack.packMatesNear(mob, InvaderPack.recruitRange());
        if (InvaderPackComposition.isComplete(mob, mates)) {
            bond.markComplete(mob.tickCount, grace);
        }
    }

    /** 無所属のとき —— 近くの群れに入るか、無所属同士で新しい群れを作る。 */
    private static void joinOrForm(Mob mob, InvaderPackBond bond, List<Mob> neighbours) {
        Map<UUID, Integer> counts = countPacks(neighbours);
        UUID biggest = biggest(counts, null, 0);
        if (biggest != null) {
            bond.setId(biggest);
            bond.markSeen(mob.tickCount);
            return;
        }
        // 近くに群れが 1 つも無い。無所属が揃っているならここで群れになる。
        List<Mob> free = new ArrayList<>();
        for (Mob other : neighbours) {
            if (other instanceof InvaderPackMember member && member.packBond().id() == null) {
                free.add(other);
            }
        }
        int needed = OvergrownVegetationConfig.COMMON.invaderPackFormMinMembers.get();
        if (1 + free.size() < needed) {
            return;
        }
        UUID id = UUID.randomUUID();
        bond.setId(id);
        bond.markSeen(mob.tickCount);
        for (Mob other : free) {
            InvaderPackBond otherBond = ((InvaderPackMember) other).packBond();
            otherBond.setId(id);
            otherBond.markSeen(other.tickCount);
        }
    }

    /**
     * 近くに自分の群れより大きい群れが居れば、そちらへ移る。
     * 移ったら true(その回はこれで終わり)。
     */
    private static boolean mergeIntoBigger(Mob mob, InvaderPackBond bond, List<Mob> neighbours) {
        UUID mine = bond.id();
        Map<UUID, Integer> counts = countPacks(neighbours);
        int mineCount = 1 + counts.getOrDefault(mine, 0);
        if (counts.getOrDefault(mine, 0) > 0) {
            bond.markSeen(mob.tickCount);
        }
        UUID best = biggest(counts, mine, mineCount);
        if (best == null) {
            return false;
        }
        bond.setId(best);
        bond.markSeen(mob.tickCount);
        return true;
    }

    /**
     * 仲間を長いこと見かけないなら所属を捨てる。
     *
     * <p>ここだけ<b>広い {@code invaderPackRecruitRange} で探し直す</b> ——
     * ふだんの群れの輪({@code invaderPackScanRange})から一時的にはみ出しただけで
     * 解散させると、迎えに行っている最中や戦いで散ったときに群れが壊れてしまう。
     */
    private static void expireIfAlone(Mob mob, InvaderPackBond bond) {
        int idle = mob.tickCount - bond.lastSeenTick();
        int limit = OvergrownVegetationConfig.COMMON.invaderPackDisbandTicks.get();
        if (idle < limit) {
            return;
        }
        for (Mob other : InvaderPack.membersNear(mob, InvaderPack.recruitRange())) {
            if (other instanceof InvaderPackMember member
                    && bond.id().equals(member.packBond().id())) {
                bond.markSeen(mob.tickCount);
                return;
            }
        }
        bond.setId(null);
        bond.markSeen(mob.tickCount);
    }

    /** 近くの個体を群れごとに数える(無所属は数えない)。 */
    private static Map<UUID, Integer> countPacks(List<Mob> neighbours) {
        Map<UUID, Integer> counts = new HashMap<>();
        for (Mob other : neighbours) {
            if (!(other instanceof InvaderPackMember member)) {
                continue;
            }
            UUID id = member.packBond().id();
            if (id != null) {
                counts.merge(id, 1, Integer::sum);
            }
        }
        return counts;
    }

    /**
     * {@code counts} の中で<b>いちばん大きい群れ</b>。{@code mine}(と その人数)より
     * 大きくなければ {@code null}。
     *
     * <p>同数のときは<b>ID が小さいほうを勝ちにする</b>。どの個体から見ても同じ結果になる
     * 全順序なので、移り合って揺れることがない。
     */
    @Nullable
    private static UUID biggest(Map<UUID, Integer> counts, @Nullable UUID mine, int mineCount) {
        UUID best = null;
        int bestCount = 0;
        for (Map.Entry<UUID, Integer> entry : counts.entrySet()) {
            UUID id = entry.getKey();
            if (id.equals(mine)) {
                continue;
            }
            int count = entry.getValue();
            if (count > bestCount || (count == bestCount && best != null && id.compareTo(best) < 0)) {
                best = id;
                bestCount = count;
            }
        }
        if (best == null) {
            return null;
        }
        if (mine == null) {
            return best;
        }
        return bestCount > mineCount || (bestCount == mineCount && best.compareTo(mine) < 0) ? best : null;
    }

    private InvaderPackBonds() {
    }
}
