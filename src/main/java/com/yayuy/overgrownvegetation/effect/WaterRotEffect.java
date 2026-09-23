package com.yayuy.overgrownvegetation.effect;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.EffectTable;
import com.yayuy.overgrownvegetation.registry.ModDamageTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.LivingEntity;

/**
 * 「水ぐされ」— 水に長く浸かったグループ1〜10 の Mob が起こす腐り。
 * 付与/継続の判定は {@link WaterRotTracker}、数値は {@code effects.json} の {@code waterRot}
 * ({@link EffectTable.WaterRotSpec})。
 *
 * <p>症状は 1 つだけ: {@code damageIntervalTicks}(既定 20 = 1 秒)ごとに
 * {@code damagePerHit}(既定 1)のダメージ({@link ModDamageTypes#WATER_ROT})。
 * 能力値はいじらないので {@code addAttributeModifier} は無い。
 *
 * <h2>ダメージの間隔は「効果の残り時間」ではなく {@code tickCount} で数える</h2>
 * {@link WaterRotTracker} は水に浸かっているあいだ<b>持続を張り直し続ける</b>ので、
 * 残り時間は {@code lingerTicks} 付近で行ったり来たりする。バニラの毒のように
 * {@code duration % interval == 0} で数えると、その張り直しの拍子でダメージが飛んだり重なったりする。
 * エンティティ自身の {@code tickCount} を基準にすれば、水から上がっても入り直しても
 * <b>きっかり 1 秒に 1 回</b>で揃う。
 *
 * <h2>無敵時間を明示的に切っている</h2>
 * バニラの被弾無敵(直後 10 tick は超過分しか通らない)は 20 tick 間隔とちょうど境目で、
 * 直前に別の攻撃を受けていると腐りのダメージが丸ごと消えることがある。攻城戦ボスの砲弾
 * ({@code DorgaArchelausTitanicaEntity#hurt})と同じく {@code invulnerableTime} を 0 にしてから
 * 与え、毎秒必ず入るようにしている。
 */
public class WaterRotEffect extends MobEffect {

    public WaterRotEffect(MobEffectCategory category, int color) {
        super(category, color);
    }

    @Override
    public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) {
        // 間隔の判定に entity が要る(tickCount を見る)ので、絞り込みは applyEffectTick 側で行う。
        return true;
    }

    @Override
    public boolean applyEffectTick(LivingEntity entity, int amplifier) {
        if (!(entity.level() instanceof ServerLevel level)) {
            return true;
        }
        EffectTable.WaterRotSpec spec = DesignData.effects().waterRot();
        int interval = Math.max(1, spec.damageIntervalTicks());
        if (entity.tickCount % interval != 0) {
            return true;
        }
        float damage = (float) spec.damagePerHit();
        if (damage <= 0.0F) {
            return true;
        }
        entity.invulnerableTime = 0;
        entity.hurt(ModDamageTypes.waterRot(level), damage);
        return true;
    }
}
