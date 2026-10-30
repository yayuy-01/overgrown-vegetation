package com.yayuy.overgrownvegetation.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

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

    /**
     * 1 回の {@link #getNeighbors} の中で当たり判定まで行った上りの答え(行き先の床の高さごと)。
     * 同じノードから見た上りの箱は<b>行き先の床の高さだけ</b>で決まる(柱と足元は {@code from} で決まっている)ので、
     * 段の縁に立って同じ高さへの上りが何本も並んだときは、当たり判定を高さごとに 1 回で済ませる。
     * 呼び出しのたびに捨てるので、ほかのノードや別の経路探索へ答えを持ち越すことはない。
     */
    private final double[] riseFloors = new double[8];
    private final boolean[] riseAnswers = new boolean[8];
    private int riseCount;

    // --- 探索 1 回ぶんの上書き(群れ v2、2026-09-25) ---

    /**
     * 群れの先導役が引く「区間」の探索にだけ掛ける<b>体の包絡</b>(群れの前衛・支援役のいちばん大きい体)。
     * {@link InvaderNavigation#withRequest} が探索の直前に置き、終わったら<b>必ず</b>外す(try/finally)。
     * 探索の中では {@link #prepare} が 1 回だけ読む。近接・立て直し・坑道・水から上がる経路は
     * 置かずに探すので、先導役自身の体のまま(レビュー指摘: 評価器にずっと置くと全部の探索が太い体になる)。
     */
    @Nullable
    private InvaderNavigation.Envelope requestEnvelope;
    /** 同じく探索 1 回ぶんの「水を渡る」上書き。重みそのものは Mob 側で掛け替える({@link InvaderNavigation#withRequest})。 */
    private boolean requestWaterCrossing;
    /** 上書きつきの探索の最中か(包絡が無く、水だけの上書きでも true)。 */
    private boolean requestActive;

    /** 上りの判定の箱(半幅・高さ・ノードから箱の中心までのずれ)。{@link #prepare} で探索 1 回につき 1 回決める。 */
    private double riseHalfWidth;
    private double riseHeight;
    private double riseCenterOffset;

    /** {@link InvaderNavigation#withRequest} から。探索の前に置く。 */
    void beginRequest(@Nullable InvaderNavigation.Envelope envelope, boolean waterCrossing) {
        this.requestEnvelope = envelope;
        this.requestWaterCrossing = waterCrossing;
        this.requestActive = true;
    }

    /**
     * {@link InvaderNavigation#withRequest} の finally から。上書きを外す。
     *
     * <p>探索が途中で終わって {@code done()} が呼ばれていない(= {@code mob} が残っている)ときは、ここで呼ぶ。
     * バニラの {@code WalkNodeEvaluator} は通ったマスの種類を<b>位置だけを鍵に</b>控えていて、それを消すのが {@code done()} ——
     * 包絡の幅で調べた控えが、次の(自分の体の)探索へ持ち越されないように。
     */
    void endRequest() {
        this.requestEnvelope = null;
        this.requestWaterCrossing = false;
        this.requestActive = false;
        if (this.mob != null) {
            this.done();
        }
    }

    /** いまの探索に掛かっている体の包絡。無ければ null。 */
    @Nullable
    InvaderNavigation.Envelope requestEnvelope() {
        return this.requestEnvelope;
    }

    boolean requestWaterCrossing() {
        return this.requestWaterCrossing;
    }

    boolean hasRequest() {
        return this.requestActive;
    }

    /**
     * 探索の準備。包絡があれば、バニラが体から決める占有マス数({@code entityWidth} など)と
     * 上りの判定({@link #canRiseTo})の箱を<b>包絡の大きさ</b>に差し替える。
     *
     * <p>包絡の箱の中心は<b>ノード + floor(幅 + 1) / 2</b>。バニラは太い体の占有マスをノードの角から
     * {@code entityWidth} マス分({@code node.x 〜 node.x + entityWidth - 1})で調べ、
     * {@code Path#getEntityPosAtNode} もその体をノード + {@code (int)(幅 + 1) × 0.5} に立たせるので、
     * 調べた通り道の中心はそこ(レビュー指摘「通り道の中心と歩く位置が 1 ブロックずれる」)。
     * 包絡が無いときは自分の体で、中心は同じく<b>ノード + floor(幅 + 1) / 2</b>(幅 1 未満なら今までどおり 0.5)。
     *
     * <p>2026-09-25(ユーザー報告「湿地で水から陸へ上がれずスタック」の調べ): 包絡が無いときは中心をノード + 0.5 に固定していたので、
     * 幅 1 以上の体では上りの箱が 1 マス −X・−Z 側へずれて段そのものに重なり、<b>北・西向きの 1 段の上りがすべて塞がれていた</b>
     * (護衛の経路・後続の立て直し・群れから外れた一員。水たまりから北・西の岸へも上がれない)。
     */
    @Override
    public void prepare(PathNavigationRegion level, Mob mob) {
        super.prepare(level, mob);
        InvaderNavigation.Envelope envelope = this.requestEnvelope;
        if (envelope != null) {
            int blocks = envelope.blockWidth();
            this.entityWidth = blocks;
            this.entityDepth = blocks;
            this.entityHeight = Mth.floor(envelope.height() + 1.0F);
            this.riseHalfWidth = envelope.width() / 2.0D;
            this.riseHeight = envelope.height();
            this.riseCenterOffset = envelope.corridorOffset();
        } else {
            this.riseHalfWidth = mob.getBbWidth() / 2.0D;
            this.riseHeight = mob.getBbHeight();
            this.riseCenterOffset = Mth.floor(mob.getBbWidth() + 1.0F) * 0.5D;
        }
    }

    @Override
    public int getNeighbors(Node[] outputArray, Node node) {
        int found = super.getNeighbors(outputArray, node);
        int kept = 0;
        this.riseCount = 0;
        // 元の足場の高さは候補ごとに変わらない。上りの候補が出たときに 1 回だけ読む。
        double fromFloor = Double.NaN;
        for (int i = 0; i < found; i++) {
            Node candidate = outputArray[i];
            if (candidate.y > node.y && Double.isNaN(fromFloor)) {
                fromFloor = this.getFloorLevel(this.scratch.set(node.x, node.y, node.z));
            }
            if (candidate.y <= node.y || canRiseTo(node, fromFloor, candidate)) {
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
     *
     * @param fromFloor {@code from} の足場の高さ(呼び出し側が 1 回の {@link #getNeighbors} につき 1 回だけ読んだもの)
     */
    private boolean canRiseTo(Node from, double fromFloor, Node to) {
        double toFloor = this.getFloorLevel(this.scratch.set(to.x, to.y, to.z));
        for (int i = 0; i < this.riseCount; i++) {
            if (this.riseFloors[i] == toFloor) {
                // 同じ getNeighbors で同じ高さへの上りをもう調べた = まったく同じ箱。
                return this.riseAnswers[i];
            }
        }
        // 箱の大きさと中心のずれは prepare が決めたもの(自分の体、または群れの体の包絡。2026-09-25)。
        double half = this.riseHalfWidth;
        double top = toFloor + this.riseHeight - 0.002D;
        double bottom = fromFloor + 0.001D;
        if (top <= bottom) {
            return true;
        }
        double centerX = from.x + this.riseCenterOffset;
        double centerZ = from.z + this.riseCenterOffset;
        AABB rise = new AABB(centerX - half, bottom, centerZ - half, centerX + half, top, centerZ + half);
        boolean clear = this.currentContext.level().noCollision(this.mob, rise);
        if (this.riseCount < this.riseFloors.length) {
            this.riseFloors[this.riseCount] = toFloor;
            this.riseAnswers[this.riseCount] = clear;
            this.riseCount++;
        }
        return clear;
    }
}
