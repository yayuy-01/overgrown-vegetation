package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import com.yayuy.overgrownvegetation.registry.ModMobEffects;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.List;

/**
 * フィロセンスの「見る」と「教える」の実体(ユーザー要望 2026-09-14、索敵システム作り直し)。
 *
 * <h2>考え方</h2>
 * <p>以前は見つけた相手をワールドごとのメモ帳({@code ScoutIntel})へ載せ、味方がそれを読む方式だった。
 * いまは<b>2 つの効果そのものが共有状態</b>になっている:
 * <ul>
 *     <li>{@link ModMobEffects#PHYLLOSENS_WATCH 「フィロセンスの視線 / Phyllosens' Gaze」} —
 *     <b>プレイヤー</b>に付く。「汚染側に居場所を知られている」印。</li>
 *     <li>{@link ModMobEffects#PHYLLOSENS_GUIDANCE 「フィロセンスの誘導」} —
 *     <b>味方(グループ1〜10)</b>に付く。「視線持ちのプレイヤーの居場所を知っている」印。</li>
 * </ul>
 * 誘導を受けた Mob は {@link GuidedHuntGoal} で、<b>距離も視界も壁も無視して</b>
 * 視線持ちのプレイヤーへ向かう。
 *
 * <p>メモ帳をやめた利点は 3 つ — 誰が情報を持っているかが<b>ゲーム内のアイコンで見える</b>、
 * 効果が切れれば<b>自然に忘れる</b>(期限の管理が要らない)、そしてワールドを跨いだ状態を
 * 持たないので<b>保存も後片付けも要らない</b>。
 *
 * <h2>距離の現実</h2>
 * <p>「どれだけ離れていても襲いに来る」には Minecraft 側の上限がある —— Mob は
 * <b>シミュレーション距離の外ではそもそも動かない</b>。実際に歩いてくるのは
 * 「視線持ちのプレイヤーの周囲(既定 8 チャンク = 128 ブロック)に居る Mob」だけで、
 * それより遠い Mob は凍ったまま待ち、プレイヤーが近づいた瞬間に動き出す。
 * 誘導の射程(256)を広く取ってあるのは、この「凍っている予備軍」に先に印を配っておくため。
 */
public final class PhyllosensInfluence {

    /**
     * {@code center} から半径 {@code range} 以内のプレイヤーに<b>視線</b>を付ける / 付け直す。
     *
     * <p>{@code addEffect} は今より短い効果で上書きしないので、見られ続けている間は自然に延び、
     * 見られなくなればそのまま切れる。粒子は出さない — 伝えたいのはアイコン 1 つだけで、
     * 狙われている側の視界を汚したくないため。
     *
     * @return 実際に印を付けたプレイヤーの数
     */
    public static int markPlayers(ServerLevel level, LivingEntity source, double range) {
        int duration = com.yayuy.overgrownvegetation.data.DesignData.effects().phyllosensWatchTicks();
        if (duration <= 0 || range <= 0.0D) {
            return 0;
        }
        double rangeSq = range * range;
        int marked = 0;
        for (ServerPlayer player : level.players()) {
            if (!isWatchable(player) || source.distanceToSqr(player) > rangeSq) {
                continue;
            }
            // 付け直すのは残りが 1 秒以上減ってから。狙っている間はバニラの「狙い続けるか」が 2 tick ごとに setTarget を呼ぶので、
            // 毎回付け直すと効果の更新の通信とイベントが 2 tick ごとに出ていた(2026-10-10、0.1.9 の前の軽量化。切れるのが最大 1 秒早まるだけ)
            MobEffectInstance current = player.getEffect(ModMobEffects.PHYLLOSENS_WATCH);
            if (current == null || current.getDuration() < duration - 20) {
                player.addEffect(new MobEffectInstance(ModMobEffects.PHYLLOSENS_WATCH, duration, 0,
                        true, false, true));
            }
            marked++;
        }
        return marked;
    }

    /**
     * {@code center} から半径 {@code range} 以内の味方(グループ1〜10)に<b>誘導</b>を付ける / 付け直す。
     *
     * <p>読み込まれていないチャンクの Mob はそもそも拾えないので、実際に印が付くのは
     * 誰かの読み込み範囲に居る Mob だけ。範囲が広いぶん走査が重いので、呼ぶ側で間隔を空けること。
     *
     * @return 実際に印を付けた Mob の数
     */
    public static int guideAllies(ServerLevel level, LivingEntity source, double range) {
        int duration = com.yayuy.overgrownvegetation.data.DesignData.effects().phyllosensGuidanceTicks();
        if (duration <= 0 || range <= 0.0D) {
            return 0;
        }
        double rangeSq = range * range;
        AABB box = source.getBoundingBox().inflate(range);
        int guided = 0;
        for (LivingEntity ally : level.getEntitiesOfClass(LivingEntity.class, box)) {
            // 2026-09-24 軽量化(監査 867): 安い距離の判定を先に(どちらも副作用の無い判定なので、答えは同じ)。
            // 箱は立方体なので、球の外になる角の個体は種族の判定まで行かずに落ちる。
            if (source.distanceToSqr(ally) > rangeSq || !canBeGuided(ally)) {
                continue;
            }
            ally.addEffect(new MobEffectInstance(ModMobEffects.PHYLLOSENS_GUIDANCE, duration, 0,
                    true, false, true));
            guided++;
        }
        return guided;
    }

    /**
     * そのプレイヤーに視線を付けてよいか(クリエイティブ・観戦者は対象外)。
     * 「寄生植物装備」を着けたプレイヤーは過繫茂側なので対象外(視線が付くとグループ 4 が襲いに来てしまう。2026-09-18)。
     */
    public static boolean isWatchable(Player player) {
        return player.isAlive() && !player.isCreative() && !player.isSpectator()
                && !com.yayuy.overgrownvegetation.item.PlantArmor.isHost(player);
    }

    /**
     * その生き物に誘導を配ってよいか。
     *
     * <p>条件は「グループ1〜10 の歩ける Mob」。除外するのは:
     * <ul>
     *     <li><b>フィロセンス自身</b> — 情報を配る側が持ち場を離れては元も子もない。</li>
     *     <li><b>動けない種</b>(汚染シダ・苗床など移動速度 0) — 誘導しても歩けない。</li>
     * </ul>
     */
    public static boolean canBeGuided(LivingEntity entity) {
        if (!(entity instanceof PathfinderMob) || entity instanceof PhyllosensEntity) {
            return false;
        }
        if (!entity.isAlive() || MobGroup.of(entity) == null) {
            return false;
        }
        return entity.getAttributeValue(Attributes.MOVEMENT_SPEED) > 0.0D;
    }

    /** {@code entity} が誘導を受けているか。 */
    public static boolean isGuided(LivingEntity entity) {
        return entity.hasEffect(ModMobEffects.PHYLLOSENS_GUIDANCE);
    }

    /** {@code player} が視線を受けているか。 */
    public static boolean isWatched(Player player) {
        return player.hasEffect(ModMobEffects.PHYLLOSENS_WATCH);
    }

    /**
     * {@code seeker} から見ていちばん近い「視線持ちのプレイヤー」。<b>距離の上限は無い</b> —
     * 視線を持っている限り、ワールドの端に居ても対象になる(ユーザー要望:
     * 「どれだけ離れていても感知して襲いに行く」)。同じワールドのプレイヤーだけを見る。
     */
    @Nullable
    public static Player nearestWatchedPlayer(ServerLevel level, LivingEntity seeker) {
        List<ServerPlayer> players = level.players();
        Player best = null;
        double bestSq = Double.MAX_VALUE;
        for (ServerPlayer player : players) {
            if (!isWatchable(player) || !isWatched(player)) {
                continue;
            }
            double sq = seeker.distanceToSqr(player);
            if (sq < bestSq) {
                bestSq = sq;
                best = player;
            }
        }
        return best;
    }

    private PhyllosensInfluence() {
    }
}
