package com.yayuy.overgrownvegetation.swarm.goal;

/**
 * 囲む Goal({@link SwarmEngageGoal})が「相手へまっすぐ向かっても壁に当たって近づけない」ことを一員へ伝える口
 * (群れ v2、2026-09-25、レビュー指摘)。アルボラ系({@code AbstractOvergrownInvaderEntity})が受ける。
 *
 * <p>群れの前衛は経路を引かずに直に動かされる(囲む Goal)ので、個体のときの「経路が引けない相手へ体を押し当てる → 壁に当たれば登る
 * → 3 秒で詰まった印 → 坑道を掘る/回り道」(ユーザー要望 2026-09-17 の坑道・2026-09-20 の壁登り)の入口が一度も開かず、
 * 同じ高さの壁・家の中の相手に、前衛全員が壁へ体を押し付けたまま止まっていた。この口がその入口の代わり。
 */
public interface SwarmApproachHandler {

    /**
     * 相手へまっすぐ向かっているのに、壁に当たって近づけていない(その tick)。
     * 受けた側は少しの間「回り道が無い」とみなし、壁に当たっていれば張り付いて登り始める(同じ高さの相手でも)。
     */
    void noteSwarmApproachBlocked();

    /**
     * まっすぐ向かう回が、壁に当たったまま続けて終わった。受けた側は先導役なら「詰まった」印を立てる
     * (坑道を掘る・回り道する。後続は掘らない・回り道しない —— swarm-design)。
     */
    void noteSwarmApproachExhausted();

    /** いま壁・天井に張り付いているか(張り付いて登っている間は「近づけていない」と数えない)。 */
    boolean isClinging();
}
