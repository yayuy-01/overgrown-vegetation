package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.registry.ModSounds;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.ItemAbilities;

/**
 * <b>盾で突進を弾く</b> — とびかかってきた相手を、構えた盾で受け止めて跳ね返す処理
 * (ユーザー要望 2026-09-14)。
 *
 * <p>普通の攻撃はバニラの盾ガードがそのまま効くが、{@link InvaderLeapGoal} のような
 * 「ぶつかった瞬間に殴る」動きは {@code doHurtTarget} を直接呼ぶので、ガードの判定を
 * 自分で見る必要がある。
 *
 * <h2>弾ける条件</h2>
 * <ol>
 *     <li><b>盾を構え始めてから {@code invaderParryWindowTicks}(既定 10 tick = 0.5 秒)以内</b>
 *     であること(ユーザー要望 2026-09-14)。ずっと構えっぱなしでは弾けない —
 *     突進に合わせて構え直す「見切り」の操作になる。</li>
 *     <li>攻撃が<b>自分の正面側</b>から来ていること(判定式はバニラの
 *     {@code LivingEntity#isDamageSourceBlocked} と同じ)。背中側からの突進は弾けない。</li>
 * </ol>
 *
 * <p>窓を外した(構えっぱなしだった)場合は<b>何も特別なことは起きない</b> — そのまま
 * {@code doHurtTarget} が走り、バニラの盾ガードが普通に受け止める(ダメージは防げるが、
 * 跳ね返しも専用の音も出ない)。盾は常に守ってくれるが、押し返せるのは合わせたときだけ。
 *
 * <p>弾いたときは:
 * <ul>
 *     <li>ダメージは一切通らない</li>
 *     <li>突進してきた側が<b>勢いよく後ろへ跳ね返る</b>(自分の突進速度に応じて強くなる)</li>
 *     <li>受けた側も少しだけ後ろへ押される</li>
 *     <li>{@link ModSounds#SHIELD_PARRY} — バニラの盾ガード音より低く重い、
 *     体当たりを受け止めた衝撃音が鳴る</li>
 * </ul>
 *
 * <p>グループ4 の mob はこの先も飛びつきを持つ予定なので、判定と反動をここにまとめてある。
 */
public final class ShieldParry {

    /** 跳ね返される側の水平方向の勢い(突進速度にこの倍率を掛けて反転する)。 */
    private static final double BOUNCE_HORIZONTAL = 0.85D;
    /** 跳ね返される側の最低限の水平方向の勢い(ほぼ止まった状態で当たったとき用)。 */
    private static final double BOUNCE_MIN = 0.42D;
    /** 跳ね返される側の跳ね上がり。 */
    private static final double BOUNCE_LIFT = 0.34D;
    /** 受け止めた側が押し戻される強さ。 */
    private static final double DEFENDER_PUSH = 0.38D;

    /**
     * {@code victim} が {@code attacker} からの突進を盾で弾けるか。
     * 条件はクラス javadoc の「弾ける条件」を参照。
     */
    public static boolean canParry(LivingEntity victim, Entity attacker) {
        if (!isRaisingShield(victim)) {
            return false;
        }
        // 構え始めてからの経過 tick。ずっと構えていると窓を外れる。
        int held = victim.getTicksUsingItem();
        if (held > OvergrownVegetationConfig.COMMON.invaderParryWindowTicks.get()) {
            return false;
        }
        Vec3 view = victim.getViewVector(1.0F);
        Vec3 toAttacker = attacker.position().vectorTo(victim.position()).normalize();
        toAttacker = new Vec3(toAttacker.x, 0.0D, toAttacker.z);
        return toAttacker.dot(view) < 0.0D;
    }

    /**
     * 盾(相当のもの)を構えているか。
     *
     * <p>{@link LivingEntity#isBlocking()} をそのまま使わないのは、あちらが
     * 「構えてから 5 tick 経っていること」を要求するため — 突進に合わせてきっちり構えた瞬間が
     * 弾ける窓から外れてしまう。ここでは経過 tick の判定を {@link #canParry} 側に任せ、
     * 「盾として機能するアイテムを使用中か」だけを見る({@code ItemAbilities.SHIELD_BLOCK} 判定なので
     * 他 MOD の盾も拾える)。
     */
    private static boolean isRaisingShield(LivingEntity victim) {
        if (!victim.isUsingItem()) {
            return false;
        }
        ItemStack stack = victim.getUseItem();
        return !stack.isEmpty() && stack.canPerformAction(ItemAbilities.SHIELD_BLOCK);
    }

    /**
     * 弾かれた反動を適用して音を鳴らす。ダメージ処理は呼び出し側でやらないこと
     * (この関数を呼んだ時点で「攻撃は通らなかった」)。
     */
    public static void applyParry(Entity attacker, LivingEntity victim) {
        Level level = victim.level();

        // 突進してきた側を、来た方向へ跳ね返す。
        double dx = attacker.getX() - victim.getX();
        double dz = attacker.getZ() - victim.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        Vec3 away = len > 1.0E-4D ? new Vec3(dx / len, 0.0D, dz / len) : victim.getViewVector(1.0F).scale(-1.0D);

        double incoming = attacker.getDeltaMovement().horizontalDistance();
        double power = Math.max(BOUNCE_MIN, incoming * BOUNCE_HORIZONTAL);
        attacker.setDeltaMovement(away.x * power, BOUNCE_LIFT, away.z * power);
        attacker.hasImpulse = true;
        attacker.hurtMarked = true;

        // 受け止めた側も少し押される。hurtMarked を立てないとプレイヤーには同期されない。
        victim.knockback(DEFENDER_PUSH, dx, dz);
        victim.hurtMarked = true;

        level.playSound(null, victim.getX(), victim.getY(), victim.getZ(),
                ModSounds.SHIELD_PARRY.get(), SoundSource.PLAYERS, 1.0F, 1.0F);
    }

    private ShieldParry() {
    }
}
