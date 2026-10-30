package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.spore.field.SporeFieldServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.Entity;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;

/**
 * <b>胞子袋の牛</b>(寄生された牛の進化 1 段目、G1。G1 の作り直し 第 1 段、2026-10-22)。腹が胞子の袋に膨れ、
 * <b>歩きながらその升の浮遊を少し上げる</b>動く発生源({@code g1.json abilities.sporesacCowPerDay}、1 日あたり、仮 0.5 =
 * 過繁茂 1 チャンクの半分)。止まっている間は出さない。見た目は腹の胞子嚢の房・背の嚢の筒・苔の塊(生成器 G1Stage1Final、2026-10-23。光るのは嚢の中身だけ)。
 */
public class SporesacCow extends ParasitizedCow {

    private static final int EMIT_EVERY = 100;
    private double lastX = Double.NaN, lastZ = Double.NaN;

    public SporesacCow(EntityType<? extends SporesacCow> type, Level level) {
        super(type, level);
    }

    private static final RawAnimation BUTT_ANIM = RawAnimation.begin().thenPlay("butt");

    /** 攻撃が当たったら「butt」(頭で突く、2026-10-23)。頭と胴だけ動かす。 */
    @Override
    public boolean doHurtTarget(Entity target) {
        boolean hit = super.doHurtTarget(target);
        if (hit) {
            triggerAnim("attack", "butt");
        }
        return hit;
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        super.registerControllers(controllers);
        controllers.add(new AnimationController<>(this, "attack", 0, state -> PlayState.STOP).triggerableAnim("butt", BUTT_ANIM));
    }

    @Override
    public void tick() {
        super.tick();
        if (!(level() instanceof ServerLevel server) || (tickCount + getId()) % EMIT_EVERY != 0) {
            return;
        }
        boolean moved = !Double.isNaN(lastX) && (Math.abs(getX() - lastX) + Math.abs(getZ() - lastZ)) > 0.5D;
        lastX = getX();
        lastZ = getZ();
        if (moved && !isNoAi()) {
            SporeFieldServer.emitPoint(server, blockPosition(), DesignData.g1().sporesacCowPerDay * EMIT_EVERY / 24000.0D);
        }
    }
}
