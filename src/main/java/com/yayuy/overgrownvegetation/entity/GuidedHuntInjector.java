package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import net.minecraft.world.entity.PathfinderMob;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * {@link GuidedHuntGoal} を<b>グループ1〜10 のすべての {@link PathfinderMob}</b> に仕込む
 * (ユーザー要望 2026-09-14)。
 *
 * <p>感染した牛からアルボラまで、汚染側なら種類を問わず「フィロセンスの誘導」を受け取れる
 * ようにするための一括の差し込み。1 種ずつ {@code registerGoals} を書き換えて回らずに済むし、
 * 今後増える種にも自動で乗る。
 *
 * <p>誘導そのものの付与条件は {@link PhyllosensInfluence#canBeGuided} 側 —
 * フィロセンス自身と、動けない種(汚染シダなど)は除外される。ここで Goal を持っていても
 * 誘導が付かなければ動かないので、差し込みは広めでよい。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID)
public final class GuidedHuntInjector {

    /**
     * 通常の追跡より<b>先に</b>評価させる。ユーザー選択「視線持ちのプレイヤー優先」
     * (2026-09-14)を、Goal の優先度そのもので表現している —— 村人や動物を追っている
     * 最中でも、視線持ちのプレイヤーが居ればそちらへ切り替わる。
     *
     * <p>拡大種の並び(0 水泳 / 1 立て直し / 2 種族固有 / 3 うずくまり / 4 近接)に対して 3。
     * 飛びつき・索敵・ビームなどの最中は {@link GuidedHuntGoal} 側が行動状態を見て降りるので、
     * それらを潰すことはない。
     */
    private static final int PRIORITY = 3;

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        // クライアント側の Mob は Goal を一度も回さないので積まない(2026-09-24 軽量化、報告 1670 行)。
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (!(event.getEntity() instanceof PathfinderMob mob)) {
            return;
        }
        // グループは 1 回だけ引いて使い回す(2026-09-24、報告 1670 行。同じ相手を続けて引くだけなので答えは同じ)。
        MobGroup group = MobGroup.of(mob);
        if (group != null) {
            mob.goalSelector.addGoal(PRIORITY, new GuidedHuntGoal(mob, 1.0D));
            // 小規模レイドの狙い(2026-09-19)。誘導と同じ優先度で、誘導が無いときだけ動く(canUse が排他)。
            mob.goalSelector.addGoal(PRIORITY, new com.yayuy.overgrownvegetation.raid.SmallRaidHuntGoal(mob, 1.0D));
            // 忌避灯(2026-09-19)。グループ1〜4 は点いている灯りの半径から追い出される。ヴェロフィルは灯りを爆撃する側なので除く。
            if (group.number() <= 4 && !(mob instanceof VelophyllEntity)) {
                mob.goalSelector.addGoal(1, new RepellentAvoidGoal(mob, 1.1D));
            }
        }
    }

    private GuidedHuntInjector() {
    }
}
