package com.yayuy.overgrownvegetation.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.AABB;

/**
 * この MOD の陸上 Mob 共通の経路探索。<b>「上にブロックがあるのに段差へ挑み続ける」不具合の元を断つ</b>。
 *
 * <p>バニラの {@link WalkNodeEvaluator} は段差を上る経路を作るとき、
 * {@code tryJumpOn} の中で「上りきるまでに体がつかえないか」を当たり判定で確かめている。
 * ところがその確認は
 *
 * <pre>{@code
 * } else if (this.mob.getBbWidth() >= 1.0F) {
 *     return node;   // 幅 1 ブロック以上の Mob は確認せずに通す
 * }
 * }</pre>
 *
 * と、<b>幅が 1 ブロック以上の Mob では丸ごと省略される</b>。この MOD ではスポロフィル(2.0)・
 * 大盾(2.5)・アルトロプレウラ(1.1)・ファランギオターブス(1.0)がこれに当たり、
 * 「頭上が葉や岩で塞がっていて物理的には絶対に上がれない段差」へも平然と経路を引いてしまう。
 * 経路がそこを通ると決めた以上 {@code MoveControl} は延々とジャンプを命じ続けるので、
 * Mob はその場でぴょんぴょんし続ける(ユーザー報告 2026-09-14 — 当時のアルボラは 幅1.4。
 * 2026-09-17 に 0.98 へ縮めたので、いまアルボラ自身はこの罠には掛からない)。
 *
 * <p>ここでは {@link #getNeighbors} が返した候補のうち<b>上り</b>のものだけを対象に、
 * 幅に関係なく同じ当たり判定を自前で行い、通れないものを捨てる。捨てられた段差は経路に現れないので、
 * Mob は最初から回り道を探す。
 */
public class OvergrownNodeEvaluator extends WalkNodeEvaluator {

    /** 経路探索の内側で回るので、毎回の {@code new BlockPos} を避ける。 */
    private final BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();

    @Override
    public int getNeighbors(Node[] outputArray, Node node) {
        int found = super.getNeighbors(outputArray, node);
        int kept = 0;
        for (int i = 0; i < found; i++) {
            Node candidate = outputArray[i];
            if (candidate.y <= node.y || canRiseTo(node, candidate)) {
                outputArray[kept++] = candidate;
            }
        }
        // PathFinder は戻り値の個数ぶんしか見ないので、後ろに残った古い候補は無視される。
        return kept;
    }

    /**
     * {@code from} の足場に立ったまま {@code to} の足場の高さまで体を持ち上げられるか。
     * 上りの最中は元の足元の真上を通るので、判定は {@code from} の柱で行う
     * (バニラ {@code tryJumpOn} の判定と同じ形)。
     */
    private boolean canRiseTo(Node from, Node to) {
        double half = this.mob.getBbWidth() / 2.0D;
        double fromFloor = this.getFloorLevel(this.scratch.set(from.x, from.y, from.z));
        double toFloor = this.getFloorLevel(this.scratch.set(to.x, to.y, to.z));
        double top = toFloor + this.mob.getBbHeight() - 0.002D;
        double bottom = fromFloor + 0.001D;
        if (top <= bottom) {
            return true;
        }
        double centerX = from.x + 0.5D;
        double centerZ = from.z + 0.5D;
        AABB rise = new AABB(centerX - half, bottom, centerZ - half, centerX + half, top, centerZ + half);
        return this.currentContext.level().noCollision(this.mob, rise);
    }
}
