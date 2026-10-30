package com.yayuy.overgrownvegetation.raid;

import com.yayuy.overgrownvegetation.registry.ModAttachments;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * 小規模レイドの「狙い」({@link ModAttachments#HUNT_TARGET})を<b>いつ捨てるか</b>の規則
 * (2026-09-25、ユーザー承認「レイドの見直し」、監査報告 1130 / 1978)。
 *
 * <p><b>小規模レイドそのものは 2026-10-10 にユーザー指定で削除した</b>(起こす仕組み・設定・コマンド)。ここと {@link SmallRaidHuntGoal}・
 * 狙いの印({@link ModAttachments#HUNT_TARGET})・出どころ {@code small_raid} は<b>わざと残している</b>: 前に湧いた一団が古いセーブに
 * 狙いと永続を持ったまま眠っているので、読み込まれたときに期限切れ・諦めで永続を外して片付けるため。新しく狙いが付くことはもう無い。
 * 群れの経路の「諦める回数」({@link #UNREACHABLE_LEGS})はほかの標的にも使っている。
 *
 * <p>どれも小さな static にしてある。今は {@link SmallRaidHuntGoal} が個体ごとに呼ぶが、次の段階で狩りの寿命を
 * 一団をまとめて動かす制御役へ移すときに、同じ規則をそのまま呼べるように(状態 = 見失った回数・失敗した区間の数は
 * 呼ぶ側が持つ)。
 *
 * <h2>狙いを捨てる条件(どれか 1 つで捨てる)</h2>
 * <ul>
 *     <li>狙いの文字列が UUID として読めない({@link #parseTarget} が null)</li>
 *     <li>プレイヤーがこのワールドに居ない(ログアウト・別のディメンション)状態が {@link #LOST_GRACE_TICKS} 続いた</li>
 *     <li>プレイヤーが死んだ / クリエイティブ / スペクテイター({@link #quarryOutOfPlay})</li>
 *     <li><b>届かない</b>: 経路の引き直しの区間が {@link #UNREACHABLE_LEGS} 回続けて失敗した({@link #legFailed})。
 *     川や海の向こうの相手を狙って岸に張り付いたままの一団が、いつまでも狙いと永続を持ち続けていた(報告 1130)</li>
 *     <li><b>期限切れ</b>: 狙いを付けてから {@link #MAX_HUNT_TICKS} 経った({@link #expired})。標的が生きたまま
 *     逃げ切ると、一団は永続のまま未ロードのチャンクで眠り続け、次の一団がその上に積み増されていた(報告 1978)。
 *     未ロードの間は Goal が回らないので、読み込まれた瞬間に {@link EventMobs#onJoin} も同じ期限で判定する</li>
 * </ul>
 * 捨てるときは必ず {@link #giveUp} を通す —— 狙いを空にするのと一緒に、湧いたときに付けた永続も外す
 * ({@link EventMobs#onHuntCleared})。外れた個体は、その種の普段の規則(G4 なら InvaderDespawn)で消える。
 */
public final class SmallRaidHunt {

    /**
     * 見失ったプレイヤーを待つ回数。数えるのは狙いを読み直した回数(Goal の canUse は 2 tick に 1 回)なので、
     * 実際は約 2400 tick。2026-09-19 からの値のまま。
     */
    public static final int LOST_GRACE_TICKS = 1200;

    /**
     * 何区間続けて失敗したら「届かない」とみなすか。区間 = 経路の引き直しから次の引き直しまで(約 40 tick)
     * なので、6 区間 ≒ 12 秒動けなかったら諦める。仮決め 2026-09-25。
     */
    public static final int UNREACHABLE_LEGS = 6;

    /**
     * 1 区間でこれだけ(水平、ブロック)も動けなければ、その区間は失敗(部分経路の終点 = 岸や崖の下で立ち止まっている)。
     * 一団の中で一番遅い地上の種(移動速度 0.18)でも 1 区間に 3 ブロック以上は歩く。仮決め 2026-09-25。
     */
    public static final double LEG_MIN_MOVE = 1.0D;

    /**
     * 狙いの寿命(tick)。狙いを付けてからこれだけ経ったら、届いていてもいなくても捨てる。
     * 24000 = ゲーム内 1 日(20 分)。次の一団(既定 1.5〜3 日おき)が来る前には必ず切れる。仮決め 2026-09-25
     * (監査の提案値)。
     */
    public static final long MAX_HUNT_TICKS = 24000L;

    /** その Mob が小規模レイドの狙いを持っているか。 */
    public static boolean hasHunt(LivingEntity entity) {
        return !entity.getExistingData(ModAttachments.HUNT_TARGET).orElse("").isEmpty();
    }

    /** 狙いの文字列をプレイヤーの UUID に。読めなければ null(= 狙いを捨てる)。 */
    @Nullable
    public static UUID parseTarget(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 見つかったプレイヤーが、もう狙えない(死んだ・クリエイティブ・スペクテイター)か。 */
    public static boolean quarryOutOfPlay(Player player) {
        return !player.isAlive() || player.isSpectator() || player.isCreative();
    }

    /** 見失った回数が猶予を超えたか。 */
    public static boolean lostTooLong(int lostTicks) {
        return lostTicks > LOST_GRACE_TICKS;
    }

    /**
     * 1 区間が失敗だったか。経路が取れなかった({@code gotPath} が false。このとき航法は止まっている)、
     * または区間のあいだに {@link #LEG_MIN_MOVE} ブロックも動けなかった。
     *
     * <p>「経路が取れたか」だけでは見分けられない —— 岸で立ち止まった個体に経路を引かせると、その場 1 マスだけの
     * 部分経路が取れて true になる回と、歩き終えた同じ経路が返って false になる回が交互に来る。
     *
     * @param movedSq 区間の始めからの水平移動量の 2 乗。区間の始めが無い(引き直しを始めて 1 回目)なら負の値を渡す
     *                (= 動きでは判定しない)
     */
    public static boolean legFailed(boolean gotPath, double movedSq) {
        return !gotPath || (movedSq >= 0.0D && movedSq < LEG_MIN_MOVE * LEG_MIN_MOVE);
    }

    /** 失敗した区間が続いた回数から、届かないとみなすか。 */
    public static boolean unreachable(int failedLegsInARow) {
        return failedLegsInARow >= UNREACHABLE_LEGS;
    }

    /**
     * 狙いを付けた時刻({@link EventMobs} の印の {@code since})から {@link #MAX_HUNT_TICKS} を過ぎたか。
     * 印や時刻が無ければ判定しない(false)。
     */
    public static boolean expired(Mob mob, long now) {
        long since = EventMobs.taggedAt(mob, EventMobs.Kind.SMALL_RAID);
        return since >= 0L && now - since > MAX_HUNT_TICKS;
    }

    /**
     * 上の規則をまとめて判定する。
     *
     * @param quarry           狙いのプレイヤー。このワールドに居なければ null
     * @param lostTicks        見失っている回数(見つかったら 0 に戻す)
     * @param failedLegsInARow 続けて失敗した区間の数(成功したら 0 に戻す)
     */
    public static boolean shouldGiveUp(Mob mob, long now, @Nullable Player quarry, int lostTicks, int failedLegsInARow) {
        if (expired(mob, now)) {
            return true;
        }
        if (quarry == null) {
            return lostTooLong(lostTicks);
        }
        return quarryOutOfPlay(quarry) || unreachable(failedLegsInARow);
    }

    /** 狙いを捨てる。湧いたときに付けた永続も外れ、その種の普段の規則で消えるようになる。 */
    public static void giveUp(Mob mob) {
        mob.setData(ModAttachments.HUNT_TARGET, "");
        EventMobs.onHuntCleared(mob);
    }

    private SmallRaidHunt() {
    }
}
