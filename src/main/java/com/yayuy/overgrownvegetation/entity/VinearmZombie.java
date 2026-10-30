package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.G1Table;
import com.yayuy.overgrownvegetation.g1.VineWhip;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;

/**
 * <b>蔦腕</b>(寄生されたゾンビの進化 1 段目、G1。G1 の作り直し 第 1 段、2026-10-22)。体が完全に植物の支配下に入り、
 * 片腕が蔦になった。蔦は {@code g1.json abilities.vineArmRange}(4 ブロック)先まで伸びて標的を掴み、引き寄せる。
 * ほかは寄生されたゾンビのまま。モデルはユーザーのラフを土台にしたもの(生成器 tools/g5-models/mobs/G1Vinearm、2026-10-23)。
 *
 * <p>攻撃のアニメ(2026-10-23): 殴ったら「stab」(蔦の槍で突く)、蔦で引き寄せたら「lash」(振り下ろして引く)を、
 * 待機・歩きの "move" の後に登録した "attack" のコントローラから一度だけ流す。attack のアニメは右腕・肩の塊・蔦の骨だけを動かすので、
 * 脚と胴は歩きのまま(記憶 geckolib-two-layer-controllers)。
 */
public class VinearmZombie extends ParasitizedZombie {

    private static final RawAnimation STAB_ANIM = RawAnimation.begin().thenPlay("stab");
    private static final RawAnimation LASH_ANIM = RawAnimation.begin().thenPlay("lash");

    private int vineCooldown;

    public VinearmZombie(EntityType<? extends VinearmZombie> type, Level level) {
        super(type, level);
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        if (vineCooldown > 0) {
            vineCooldown--;
            return;
        }
        G1Table g = DesignData.g1();
        if (VineWhip.tryPull(this, getTarget(), g.vineArmRange, g.vineArmPull)) {
            vineCooldown = g.vineArmCooldownTicks;
            triggerAnim("attack", "lash");
        }
    }

    @Override
    public boolean doHurtTarget(Entity target) {
        boolean hit = super.doHurtTarget(target);
        if (hit) {
            triggerAnim("attack", "stab");
        }
        return hit;
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        super.registerControllers(controllers);
        controllers.add(new AnimationController<>(this, "attack", 0, state -> PlayState.STOP)
                .triggerableAnim("stab", STAB_ANIM)
                .triggerableAnim("lash", LASH_ANIM));
    }
}
