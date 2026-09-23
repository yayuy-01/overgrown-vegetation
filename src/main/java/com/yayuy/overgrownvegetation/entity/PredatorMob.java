package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * 対応するグループ2の Mob({@link Predation})を狩るグループ3の Mob。
 * 1体倒すごとに最大体力と攻撃力が {@code predation.json} の {@code preyKillBonusPerKill}
 * (既定 5%)ずつ複利で上昇する。
 *
 * <p>捕食数は各実装 Mob が {@code int} フィールドで持ち NBT に保存する
 * ({@link #getPreyKillCount()} / {@link #setPreyKillCount(int)})。ステータス補正は
 * 固定 ID の一時 {@link AttributeModifier}(乗算)で表現し、捕食数が変わるたび貼り直す。
 * 湧き直し・再読み込み時は {@code readAdditionalSaveData} から
 * {@link #applyPreyKillBonus(Mob, int)} を呼んで復元する。
 */
public interface PredatorMob {

    /** 捕食数ボーナスの {@link AttributeModifier} ID(最大体力・攻撃力で共通)。 */
    ResourceLocation PREY_KILL_MODIFIER_ID = OvergrownVegetation.id("prey_kill_bonus");

    int getPreyKillCount();

    void setPreyKillCount(int count);

    /**
     * 捕食1体を記録し、ステータス補正を貼り直す。最大体力が増えた分だけ現在体力も回復させる
     * (倒した直後にじわじわ回復するイメージ)。{@link PredationEvents} から呼ばれる。
     */
    default void registerPreyKill() {
        if (!(this instanceof Mob mob)) {
            return;
        }
        int next = getPreyKillCount() + 1;
        setPreyKillCount(next);
        double oldMax = mob.getMaxHealth();
        applyPreyKillBonus(mob, next);
        double newMax = mob.getMaxHealth();
        if (newMax > oldMax) {
            mob.setHealth((float) (mob.getHealth() + (newMax - oldMax)));
        }
    }

    /** {@code kills} 体分のステータス補正を {@code mob} に貼り直す(0 なら剥がすだけ)。 */
    static void applyPreyKillBonus(Mob mob, int kills) {
        reapply(mob, Attributes.MAX_HEALTH, kills);
        reapply(mob, Attributes.ATTACK_DAMAGE, kills);
    }

    private static void reapply(Mob mob, Holder<Attribute> attribute, int kills) {
        AttributeInstance instance = mob.getAttribute(attribute);
        if (instance == null) {
            return;
        }
        instance.removeModifier(PREY_KILL_MODIFIER_ID);
        if (kills > 0) {
            double bonusPerKill = com.yayuy.overgrownvegetation.data.DesignData.predation().preyKillBonusPerKill();
            instance.addTransientModifier(new AttributeModifier(
                    PREY_KILL_MODIFIER_ID,
                    bonusPerKill * kills,
                    AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
    }
}
