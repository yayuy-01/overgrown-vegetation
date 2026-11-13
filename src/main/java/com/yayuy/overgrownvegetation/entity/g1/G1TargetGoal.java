package com.yayuy.overgrownvegetation.entity.g1;

import com.yayuy.overgrownvegetation.effect.ParasitizedAttackInfectionHandler;
import com.yayuy.overgrownvegetation.entity.AbstractParasitizedMonster;
import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.decoration.ArmorStand;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * G1 段階 0 はどの生き物も襲う(11-05 共通の決定)。プレイヤーだけでなく、周りの Mob を種を問わず狙う(G1〜G10 どうしは襲わない)。
 * 旧 {@code effect/ParasitizedTargetGoal}(parked、567 行。群れ・茂みの隠れ・汚染の優先などに依存)の残すべき 3 つだけを小さく書き直した:
 *
 * <ul>
 *   <li>索敵は追跡範囲(FOLLOW_RANGE、旧の値 35)で、視線を問わない(壁越しでも見つけ、見えなくなっても見失わない。旧のユーザー要望)。</li>
 *   <li>たどり着けない相手は諦めて、{@value #IGNORE_TICKS} tick は選ばない({@link G1StrikeGoal} が経路の無さを数えて {@link #ignore} を呼ぶ)。</li>
 *   <li>水の中・水面にいる相手は狙わない(自分も水にいるときは別。旧 2026-09-11 のユーザー要望。今回は【案】として残した)。</li>
 * </ul>
 * 旧の「茂みに隠れると見失う」(FoliageConcealment、parked)は戻していない。
 */
public class G1TargetGoal extends NearestAttackableTargetGoal<LivingEntity> {

    static final int IGNORE_TICKS = 200;

    /** 諦めた相手と、また選んでよくなる時刻(Mob ごと)。 */
    private static final Map<Mob, Ignored> IGNORED = new WeakHashMap<>();

    private record Ignored(LivingEntity target, long until) {
    }

    public G1TargetGoal(AbstractParasitizedMonster mob) {
        super(mob, LivingEntity.class, 10, false, false, null);
        this.targetConditions = TargetingConditions.forCombat()
                .range(mob.getAttributeValue(Attributes.FOLLOW_RANGE))
                .ignoreLineOfSight()
                .selector(this::isPrey);
    }

    /** 狙ってよい相手か(自分・G1〜G10・寄生体・防具立ては外す)。 */
    private boolean isPrey(LivingEntity e) {
        return isPrey(this.mob, e);
    }

    public static boolean isPrey(Mob self, LivingEntity e) {
        if (e == self || e instanceof ArmorStand || e instanceof ParasitizedMob || ParasitizedAttackInfectionHandler.isOvergrownMob(e)) {
            return false;
        }
        if (com.yayuy.overgrownvegetation.OvergrownVegetation.MODID.equals(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getNamespace())) {
            return false; // この MOD の Mob(萌芽体・ハイヴノード・G4 以降)は襲わない(仕様「G1・萌芽体どうしは襲わない」。グループのタグに無い物も)
        }
        if (!e.isAlive() || !e.canBeSeenAsEnemy() || !net.minecraft.world.entity.EntitySelector.NO_CREATIVE_OR_SPECTATOR.test(e)) {
            return false; // 創造・観戦のプレイヤー・無敵の相手(夜歩きの行き先にも数えない)
        }
        if (e.isInWaterOrBubble() && !self.isInWaterOrBubble()) {
            return false;
        }
        Ignored ig = IGNORED.get(self);
        return ig == null || ig.target != e || self.level().getGameTime() >= ig.until;
    }

    /** {@code target} を {@value #IGNORE_TICKS} tick 選ばない(たどり着けなかった)。 */
    public static void ignore(Mob self, LivingEntity target) {
        IGNORED.put(self, new Ignored(target, self.level().getGameTime() + IGNORE_TICKS));
    }

    @Override
    public boolean canContinueToUse() {
        LivingEntity target = this.mob.getTarget();
        if (target != null && target.isInWaterOrBubble() && !this.mob.isInWaterOrBubble()) {
            return false;
        }
        return super.canContinueToUse();
    }

    /** 見えていなくても追い続ける(mustSee=false と対)。 */
    @Override
    protected double getFollowDistance() {
        return this.mob.getAttributeValue(Attributes.FOLLOW_RANGE);
    }
}
