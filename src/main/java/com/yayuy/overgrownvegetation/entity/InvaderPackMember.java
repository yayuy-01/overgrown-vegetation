package com.yayuy.overgrownvegetation.entity;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * <b>グループ4(拡大種)の「群れ」に加わる Mob</b> の印(ユーザー要望 2026-09-15:
 * 「グループ4の mob 達は基本的にグループ4の mob 同士で集団行動するようにしてほしい」)。
 *
 * <p>群れの中身はすべて {@link InvaderPack} 側にある。<b>このインターフェースを実装した Mob だけが
 * 群れの一員として数えられる</b> —— グループ4 のタグには汚染シダ({@link BlightFernEntity})や
 * 汚染水草のように<b>根を張って動かない</b>種も入っているので、「タグに入っているか」では選べない。
 * 実装しているのは {@link AbstractOvergrownInvaderEntity}(アルボラ・フィロセンス・スポロフィル)と
 * {@link VelophyllEntity} の 2 系統。
 *
 * <p>役割ごとに<b>群れの中でどこに居たいかが違う</b>のを {@link #packCohesionDistance()} で表す —
 * 支援役のスポロフィルは傘のオーラ(半径 32)が仲間を覆えるよう中央寄りに、索敵役のフィロセンスは
 * 後ろ寄りに、飛行のヴェロフィルは群れの上空をずっと広く回る。
 */
public interface InvaderPackMember {

    /**
     * <b>群れの重心からこれ以上離れたら戻る</b>距離(ブロック)。
     * 地上の個体は {@link InvaderPackGoal} が、ヴェロフィルは
     * {@link VelophyllWanderGoal} が行き先の選び方でこれを守る。
     */
    double packCohesionDistance();

    /**
     * 標的を仲間へ回すとき({@link InvaderPack#shareTarget})、<b>どこまで声が届くか</b>(ブロック)。
     * 既定は {@code invaderPackCallRange}。上空を飛ぶヴェロフィルだけは<b>地面まで届くよう</b>広げる。
     */
    default double packCallRange() {
        return com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig.COMMON
                .invaderPackCallRange.get();
    }

    /**
     * 仲間から回されてきた標的({@link InvaderPack#shareTarget})を受け取れるか。
     *
     * <p>既定は「受け取る」。ヴェロフィルだけは<b>爆弾が届かない相手</b>(地下に潜っている等)を
     * 断る —— 受け取ってしまうと届かない標的を抱えて上空を旋回し続ける。
     */
    default boolean acceptsPackTarget(LivingEntity target) {
        return true;
    }

    /**
     * いま<b>群れ全体で向かっている先</b>。{@link InvaderAdvanceGoal} が「過繁茂バイオームの外」を
     * 選んだときにここへ置き、あとから動き出した仲間はそれを写して<b>同じ方向へ進む</b>
     * (各自が勝手に外側を選ぶと、群れがその場で散ってしまう)。向かっていなければ {@code null}。
     */
    @Nullable
    default Vec3 getPackDestination() {
        return null;
    }

    default void setPackDestination(@Nullable Vec3 destination) {
    }

    /**
     * この個体の<b>群れの所属</b>(ユーザー要望 2026-09-15「常に同じ個体の群れを形成し続ける」)。
     *
     * <p>実装クラスは {@code final InvaderPackBond} を 1 つ持ってそれを返すだけでよい。
     * 保存・読み込みは {@link InvaderPackBond#save} / {@link InvaderPackBond#load} を
     * {@code addAdditionalSaveData} / {@code readAdditionalSaveData} から呼ぶこと ——
     * <b>これを忘れるとリログのたびに群れが組み直しになる</b>。
     * 所属の決まり方は {@link InvaderPackBonds}。
     */
    InvaderPackBond packBond();

    /**
     * <b>先遣隊</b>か(ユーザー要望 2026-09-15)。true を返すのはアルボラだけ。
     *
     * <p>群れの形は「重心のまわりに集まる」のではなく<b>先遣隊のまわりに付いていく</b>形で決まる。
     * ほかの種は近くの先遣隊を目印に {@link #packFormationOffset()} が決める立ち位置を保ち、
     * 離されたら走って追いつく({@link InvaderPackGoal})。
     *
     * <p>先遣隊の側にも縛りがある —— 味方を置き去りにして前へ出ないよう、
     * 後続が {@code invaderPackFollowMax} より離れているあいだは
     * 過繁茂バイオームの外へ進まない({@link InvaderAdvanceGoal})。
     * 「アルボラが単独で飛び出していく」というテスト報告への対処。
     */
    default boolean isPackPointUnit() {
        return false;
    }

    /**
     * <b>陣形での前後の位置</b>(ブロック)。ユーザー指定 2026-09-15:
     * 「アルボラ・スカトゥリウム・ヴェロフィルを前に、スポロフィル・フィロセンスを後方に」。
     *
     * <p>基準は<b>先遣隊(アルボラ)の位置と向き</b>。<b>+ が前、- が後ろ</b>で、
     * 先遣隊自身は 0。{@link InvaderPackGoal} がこの値から立ち位置を出す。
     */
    default double packFormationOffset() {
        return 0.0D;
    }

    /**
     * いま<b>群れの動きに割り込まれては困る</b>状態か(戦闘の専用モーション中など)。
     * {@link InvaderPackGoal} はこれが true のあいだ動かない —— 戦いのほうが群れ作りより先。
     */
    default boolean isPackBusy() {
        return false;
    }

    /**
     * 群れの<b>地上の</b>重心を出すときに数える個体か。
     * 飛行中のヴェロフィルは高さが桁違いなので数えない(重心が空へ引っ張られる)。
     */
    default boolean countsInPackCentroid() {
        return true;
    }
}
