package com.yayuy.overgrownvegetation.effect;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.DorgaArchelausTitanicaEntity;
import com.yayuy.overgrownvegetation.entity.OvergrownNexusEntity;
import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
import com.yayuy.overgrownvegetation.registry.ModMobEffects;
import net.minecraft.world.effect.MobEffectInstance;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;

/**
 * 「過繁茂した(MOB名)」({@link ParasitizedMob})と、レイドの「過繫茂の苗床」
 * ({@link OvergrownNexusEntity})、攻城戦ボス({@link DorgaArchelausTitanicaEntity})は、経路を問わず
 * 「胞子感染」({@link ModMobEffects#SPORE_INFECTION})に一切かからない。
 *
 * <p>1 つめはユーザー要望(既に感染しきった進化型)。あとの 2 つは<b>ボス自身が広げた過繫茂バイオームの
 * 真上に居座り続けるのが通常動作</b>であるため({@link SporeInfectionTracker} の滞在判定に素直に従うと、
 * 自分の汚染地形の上でいずれ「胞子感染」→ 自然満了で死亡してしまう)。
 *
 * <p class="warn">攻城戦ボスは 2026-09-13 に追加した。苗床のときにまったく同じ事故が起きていたのに
 * 攻城戦ボスを入れ忘れていて、<b>近距離モードで実エンティティが湧いてからおよそ 90 秒で勝手に死ぬ</b>
 * という症状になっていた(濃度 50% 以上に 30 秒 = {@code mobDwellTicks} 居ると胞子感染1 が付き、
 * その 60 秒後 = {@code mobDurationTicks} の満了で {@link SporeInfectionExpiryHandler} が
 * {@code entity.kill()} を呼ぶ。{@code kill()} は {@code fellOutOfWorld} なので
 * {@code siegeArmor} のしきい値も素通りし、そのまま {@code die()} → 攻城戦が「討伐」で終了する)。
 *
 * <p>{@link SporeInfectionEffects#infect} 側にも同じ除外はあるが、そちらを通らない付与経路
 * (バニラのクリーパー爆発が残す {@code AreaEffectCloud}、コマンド、他 MOD など)もあり得るため、
 * {@code LivingEntity#canBeAffected} が呼ばれるたびに発火する {@link MobEffectEvent.Applicable} で
 * ハード に弾いておく。これが全経路に効く唯一の関所になる。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class ParasitizedSporeImmunity {

    @SubscribeEvent
    public static void onApplicable(MobEffectEvent.Applicable event) {
        MobEffectInstance instance = event.getEffectInstance();
        if (instance == null) {
            return;
        }
        var entity = event.getEntity();
        // 「寄生植物装備」を着けたプレイヤーも過繫茂側 —— 胞子感染にかからない(仕様 2026-09-18)。
        boolean overgrownSide = entity instanceof ParasitizedMob
                || entity instanceof OvergrownNexusEntity
                || entity instanceof DorgaArchelausTitanicaEntity
                || com.yayuy.overgrownvegetation.item.PlantArmor.isHost(entity);
        if (overgrownSide && instance.is(ModMobEffects.SPORE_INFECTION)) {
            event.setResult(MobEffectEvent.Applicable.Result.DO_NOT_APPLY);
            return;
        }
        // 3 段階目は毒への耐性。
        if (instance.is(net.minecraft.world.effect.MobEffects.POISON)
                && com.yayuy.overgrownvegetation.item.PlantArmor.stageOf(entity) >= 3) {
            event.setResult(MobEffectEvent.Applicable.Result.DO_NOT_APPLY);
        }
    }

    private ParasitizedSporeImmunity() {
    }
}
