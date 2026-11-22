package com.yayuy.overgrownvegetation.entity.parts;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.entity.player.CriticalHitEvent;

/**
 * 叩ける部位でも会心(跳んで殴る)が出るようにする(11-08 ユーザー決定「部位でも出るようにする。自前で補ってよい」)。
 *
 * <p>バニラの {@code Player.attack} は会心の条件に「相手が生き物」を含むので、部位({@link HitPart}、生き物でない)を殴ると会心にならない。
 * NeoForge の会心のイベントで、その条件だけを外したバニラの条件(溜めきり・落ちている・地面にいない・はしごでない・水の中でない・
 * 盲目でない・乗っていない・走っていない)を見直し、満たせば会心(1.5 倍)にする。
 * 溜めの値はこの時点ではまだ戻されていない(NeoForge が戻すのを攻撃の最後へ動かしている)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID)
public final class HitPartEvents {

    private HitPartEvents() {
    }

    @SubscribeEvent
    public static void onCriticalHit(CriticalHitEvent event) {
        if (!(event.getTarget() instanceof HitPart) || event.isVanillaCritical() || event.getResult() == net.minecraftforge.eventbus.api.Event.Result.ALLOW) {   // 【1.20.1】会心は Result.ALLOW で付ける
            return;
        }
        Player p = event.getEntity();
        boolean crit = p.getAttackStrengthScale(0.5F) > 0.9F
                && p.fallDistance > 0.0F
                && !p.onGround()
                && !p.onClimbable()
                && !p.isInWater()
                && !p.hasEffect(MobEffects.BLINDNESS)
                && !p.isPassenger()
                && !p.isSprinting();
        if (crit) {
            event.setResult(net.minecraftforge.eventbus.api.Event.Result.ALLOW);
            event.setDamageModifier(1.5F);
        }
    }
}
