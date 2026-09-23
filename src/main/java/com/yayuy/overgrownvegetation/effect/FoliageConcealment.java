package com.yayuy.overgrownvegetation.effect;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * 「茂み」({@link ModTags#CONCEALING}、既定では
 * {@link com.yayuy.overgrownvegetation.block.OvergrownBushBlock} のみ)の中に入っている
 * <b>プレイヤー</b>を見えなくする判定の一本化。
 *
 * <p>判定は足元のブロック({@link LivingEntity#blockPosition()})が {@link ModTags#CONCEALING}
 * タグを持つかどうかだけを見る — 視線(LOS)が実際に通っているかどうかは問わない。茂みブロック
 * ({@code overgrown_bush})自体は当たり判定が無く(通り抜けられる)見た目も遮蔽しないため、
 * 純粋なレイキャストでは「茂みに入った」ことを検知できない。ゲーム内の「茂みに隠れる」という
 * 分かりやすいルールとして、中に足を踏み入れた時点で問答無用に見失わせる方式にしている
 * (ユーザー要望: 茂みの中に入ったプレイヤーを見失い、攻撃してこなくなるように)。
 *
 * <p><b>対象はプレイヤーのみ</b> — 過繁茂バイオームの地表は {@code overgrown_bush} の塊で
 * 埋め尽くされるため、Mob 同士の隠密にまで適用すると生態系の捕食(捕食者が被食者を狩る等)が
 * ほぼ成立しなくなる。茂み隠密は「プレイヤーが Mob から隠れる」ステルス機能に限定する
 * (ユーザー決定 2026-09-08)。
 *
 * <p>「過繁茂した(MOB名)」系の索敵・追跡 Goal({@link ParasitizedTargetGoal}、
 * {@link com.yayuy.overgrownvegetation.entity.OvergrownShielderEntity} の targetSelector)から
 * 共通で参照する — 新規ターゲット探索時に候補から除外する用途と、追跡中のターゲットが途中で
 * 茂みに逃げ込んだ場合に即座に追跡を打ち切る用途の両方。
 */
public final class FoliageConcealment {

    public static boolean isConcealed(LivingEntity entity) {
        if (!(entity instanceof Player)) {
            // 茂み隠密はプレイヤー専用(Mob 同士には効かない)。上のクラス javadoc 参照。
            return false;
        }
        if (!OvergrownVegetationConfig.COMMON.bushConcealment.get()) {
            return false;
        }
        if (SporeInfectionDetection.isRevealedInFoliage(entity)) {
            // 胞子感染3: 茂みの中にいても見つかる(effects.json の revealInFoliage)。
            return false;
        }
        return entity.level().getBlockState(entity.blockPosition()).is(ModTags.CONCEALING);
    }

    private FoliageConcealment() {
    }
}
