package com.yayuy.overgrownvegetation.base.build;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 工兵の仕事 1 つ(順 2 の着手順 4 = 整備、2026-10-04)。機関砲台・胞子除去装置の整備か、近くにまとまった修理と解体のマス。
 * 仕事は {@link BaseUpkeep} が作って手の空いた工兵に渡し、工兵の {@link EngineerWorkGoal} が倉庫 → 現場 → (持ち帰り)と動いて片付ける。
 * 保存しない(読み込み直したら見回りで作り直す)。
 */
public final class UpkeepJob {

    public enum Kind {
        /** 機関砲台: 覆われていれば拭く・弾を足す。 */
        TURRET,
        /** 胞子除去装置: 胞子を取り出す・レッドストーンを足す・すり減ったフィルターを替えて持ち帰る。 */
        PURIFIER,
        /** 計画と見比べた修理と解体のマスのまとまり。 */
        FIX
    }

    /** 修理と解体の 1 マスの中身。 */
    public enum FixKind {
        /** 計画のブロックを置く(無くなった所は補修材 {@link Fix#cost} 個、汚染された所は 0 個)。 */
        RESTORE,
        /** 汚染の草木・根・死骸を取り除く(空気にする)。 */
        REMOVE,
        /** 計画の無いマスの汚染された土・石・砂利・草・砂を元のブロックに戻す。 */
        REVERT,
        /** 植物の覆い(見た目の苔)を剥がす。ブロックは変えない。 */
        UNCOVER
    }

    /**
     * 1 マス。{@code state} は置くブロック(取り除くなら空気)。{@code expected} は見比べたときにそこにあったブロック
     * (直す前にもう一度見て、変わっていたら飛ばす —— プレイヤーが先に直した・置いた)。
     */
    public record Fix(BlockPos pos, FixKind kind, BlockState state, BlockState expected, int cost, int priority) {
    }

    final Kind kind;
    final int siteId;
    /** 設備の場所、または修理のまとまりの最初のマス。 */
    final BlockPos pos;
    /** 小さいほど先(止まった設備 0・減った設備 1・外周の穴 2・ほかの穴 3・汚染 4・草木と根と覆い 5)。 */
    final int priority;
    final List<Fix> fixes;
    /** 作った tick(古い仕事は捨てて、次の見回りで作り直す)。 */
    final long created;
    /** 受け持っている工兵(null = まだ誰も)。 */
    @Nullable
    UUID worker;
    /** 行けなかった・倉庫に品が無かったなどで、この tick まで誰にも渡さない。 */
    long notBefore;

    UpkeepJob(Kind kind, int siteId, BlockPos pos, int priority, List<Fix> fixes, long created) {
        this.kind = kind;
        this.siteId = siteId;
        this.pos = pos.immutable();
        this.priority = priority;
        this.fixes = fixes;
        this.created = created;
    }

    static UpkeepJob device(Kind kind, int siteId, BlockPos pos, int priority, long created) {
        return new UpkeepJob(kind, siteId, pos, priority, new ArrayList<>(), created);
    }

    public Kind kind() {
        return kind;
    }

    public BlockPos pos() {
        return pos;
    }

    public int priority() {
        return priority;
    }

    public List<Fix> fixes() {
        return fixes;
    }

    /** 修理のまとまりで使う補修材の数(汚染を戻す・取り除く・剥がすマスは 0)。 */
    public int repairCost() {
        int n = 0;
        for (Fix f : fixes) {
            n += f.cost();
        }
        return n;
    }
}
