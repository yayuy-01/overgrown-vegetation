package com.yayuy.overgrownvegetation.effect;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Enemy;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * レベルに参加する全ての「バニラの敵対 Mob」({@link Enemy} 実装、ゾンビ・スケルトン・クリーパー・
 * クモ・エンダーマン等 — {@link ParasitizedMob} 自身を除く)に、近くの「過繁茂した(MOB名)」
 * ({@link ParasitizedMob})も攻撃対象にする {@link NearestAttackableTargetGoal} を仕込む。
 *
 * <p>これにより「過繁茂した(MOB名)」は{@link ParasitizedTargetGoal}で種族を問わず一方的に
 * 攻撃するだけでなく、逆に他の敵対 Mob 側からも狙われるようになり、双方向の敵対関係になる。
 * 既存のプレイヤー狙いの索敵 Goal(優先度1〜3が一般的)はそのまま残し、これと同じ優先度2に
 * 追加で積む方式 — バニラ自身も同じ優先度に複数のターゲット Goal を積むことがある
 * (例: バニラの {@code Zombie} は優先度3に村人用とアイアンゴーレム用を両方持つ)ので、
 * 同じ優先度に複数の {@link NearestAttackableTargetGoal} が共存すること自体は問題ない。
 *
 * <p>{@link ParasitizedMob} 自身(寄生されたゾンビ等)はここでは対象外 — 既に
 * {@link ParasitizedTargetGoal} が種族を問わず全方位を攻撃対象にしており、二重に追加する必要が無い。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class ParasitizedHostilityInjector {

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (event.getEntity() instanceof Mob mob && mob instanceof Enemy && !(mob instanceof ParasitizedMob)) {
            mob.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(
                    mob, LivingEntity.class, 10, true, false,
                    candidate -> candidate instanceof ParasitizedMob));
        }
    }

    private ParasitizedHostilityInjector() {
    }
}
