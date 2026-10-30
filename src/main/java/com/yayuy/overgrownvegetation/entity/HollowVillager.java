package com.yayuy.overgrownvegetation.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.Entity;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;

/**
 * <b>空ろな住人</b>(寄生された村人の進化 1 段目、G1。G1 の作り直し 第 1 段、2026-10-22)。
 * 中身は植物だが、村人だった頃の癖で<b>扉を開けて家に入る</b>(バニラの {@link OpenDoorGoal}、閉め直す)。
 * 武器は根を束ねた棍棒(攻撃力は mobs.json、仮 5)。見た目は前腕から育った根の瘤が手ごと飲み込んだ姿(生成器 tools/g5-models/mobs/G1Stage1Final、2026-10-23)。
 */
public class HollowVillager extends ParasitizedVillager {

    public HollowVillager(EntityType<? extends HollowVillager> type, Level level) {
        super(type, level);
        if (getNavigation() instanceof GroundPathNavigation nav) {
            nav.setCanOpenDoors(true);
            nav.setCanPassDoors(true);
        }
    }

    @Override
    protected void registerGoals() {
        super.registerGoals();
        this.goalSelector.addGoal(1, new OpenDoorGoal(this, true));
    }

    private static final RawAnimation SMASH_ANIM = RawAnimation.begin().thenPlay("smash");

    /** 殴ったら「smash」(前腕から育った根の瘤で殴る、2026-10-23)を "attack" のコントローラから一度だけ流す。右腕と胴だけ動かす。 */
    @Override
    public boolean doHurtTarget(Entity target) {
        boolean hit = super.doHurtTarget(target);
        if (hit) {
            triggerAnim("attack", "smash");
        }
        return hit;
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        super.registerControllers(controllers);
        controllers.add(new AnimationController<>(this, "attack", 0, state -> PlayState.STOP).triggerableAnim("smash", SMASH_ANIM));
    }

    /** 体力は村人と同じ 20、根の棍棒で攻撃 5(仮、10-22)。 */
    public static AttributeSupplier.Builder createAttributes() {
        return ParasitizedVillager.createAttributes()
                .add(Attributes.ATTACK_DAMAGE, 5.0);
    }
}
