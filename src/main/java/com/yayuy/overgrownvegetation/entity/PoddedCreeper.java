package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.mixin.CreeperAccessor;
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
 * <b>莢持ち</b>(寄生されたクリーパーの進化 1 段目、G1。G1 の作り直し 第 1 段、2026-10-22)。背に種の莢を付けた。
 * 爆発は寄生されたクリーパーの汚染爆発のまま({@code ParasitizedCreeperDetonation})、そこに<b>浮遊の放出</b>
 * ({@code g1.json abilities.podCreeperBurst}、仮 0.5)を足す。導火が少し長い({@code podCreeperFuseTicks}、仮 45 tick。バニラ 30)。
 * 見た目は背の右に莢の房・苔の塊(生成器 G1Stage1Final、2026-10-23)。描画は PoddedCreeperRenderer(導火の膨張・白フラッシュ)。
 */
public class PoddedCreeper extends ParasitizedCreeper {

    public PoddedCreeper(EntityType<? extends PoddedCreeper> type, Level level) {
        super(type, level);
        ((CreeperAccessor) this).overgrown$setMaxSwell(DesignData.g1().podCreeperFuseTicks);
    }

    private static final RawAnimation SWELL_ANIM = RawAnimation.begin().thenPlay("swell");
    private boolean swellAnimOn;

    /** 導火が始まったら「swell」(莢が脈打って膨らむ、2026-10-23)、止めたら戻す。 */
    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) {
            return;
        }
        boolean on = getSwellDir() > 0;
        if (on && !swellAnimOn) {
            triggerAnim("attack", "swell");
        } else if (!on && swellAnimOn) {
            stopTriggeredAnim("attack", "swell");
        }
        swellAnimOn = on;
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        super.registerControllers(controllers);
        controllers.add(new AnimationController<>(this, "attack", 0, state -> PlayState.STOP).triggerableAnim("swell", SWELL_ANIM));
    }

    /** 爆発した(バニラの explodeCreeper は dead にしてから discard する)ら、その升へ胞子を放つ。 */
    @Override
    public void remove(RemovalReason reason) {
        if (reason == RemovalReason.DISCARDED && this.dead && level() instanceof ServerLevel server) {
            SporeFieldServer.emitPoint(server, blockPosition(), DesignData.g1().podCreeperBurst);
        }
        super.remove(reason);
    }
}
