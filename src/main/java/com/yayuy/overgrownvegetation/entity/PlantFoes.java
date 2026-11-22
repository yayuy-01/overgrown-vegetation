package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
import com.yayuy.overgrownvegetation.spore.PlantSideRules;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import org.jetbrains.annotations.Nullable;

/**
 * 植物側の Mob から見た「相手の側か」の共通の判定(2026-11-16 コードの見直し ①13)。前は標的の絞り込みが 3 系統
 * (G2・G3 の SeekGoal のラムダ・{@link AcumnesEntity#isPrey}・ストロビラなどの素の検索)に分かれ、防具立て・汎用の感染体・グループの種の
 * 外し方が少しずつ違った(弾は当たるのに標的には選ばない、など)。
 *
 * <p><b>ここで揃えるのは陣営と除外だけ</b>(Codex の注意)。見えるか・乗っているか・体格・距離など、用途ごとの条件は呼ぶ側に残す。</p>
 * <ul>
 *   <li>{@link #isOtherSide}: 陣営だけ。植物側(G1〜G8・環境生物のグループ・萌芽体・汚染シダ・ハイヴノード・汎用の感染体・{@link ParasitizedMob})でない。</li>
 *   <li>{@link #isFoe}: 陣営 + 除外。自分でない・生きている・防具立てでない・無敵でない({@code canBeSeenAsEnemy})・創造/観戦のプレイヤーでない。</li>
 * </ul>
 */
public final class PlantFoes {

    private PlantFoes() {
    }

    /** 植物側でない(陣営だけ。生死・防具立て・創造は見ない)。 */
    public static boolean isOtherSide(LivingEntity e) {
        return !PlantSideRules.isPlantSide(e); // 陣営の判定は PlantSideRules の 1 か所(11-16 コードの見直し ③6。中身は前のここの条件と同じ: ParasitizedMob・グループ・植物側のタグ/クラス・汎用の感染体)
    }

    /** 狙ってよい・技が当たってよい相手か(陣営 + 除外)。{@code self} は自分(無ければ null)。 */
    public static boolean isFoe(@Nullable Entity self, LivingEntity e) {
        return e != self && e.isAlive() && !e.isRemoved() && !(e instanceof ArmorStand) && e.canBeSeenAsEnemy()
                && EntitySelector.NO_CREATIVE_OR_SPECTATOR.test(e) && isOtherSide(e);
    }
}
