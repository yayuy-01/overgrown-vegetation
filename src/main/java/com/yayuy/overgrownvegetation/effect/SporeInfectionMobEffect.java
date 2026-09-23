package com.yayuy.overgrownvegetation.effect;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.EffectTable;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;

/**
 * 「胞子感染」— 胞子濃度の高い場所への滞在({@link SporeInfectionTracker})、グループ1〜10 の Mob からの攻撃
 * ({@link ParasitizedAttackInfectionHandler})、巨大樹の核への接近などで付与される 3 段階のデバフ。
 * 実際の付与(持続時間の決定・チャンクへの胞子濃度加算)は {@link SporeInfectionEffects#infect} に一本化されている。
 *
 * <p>1 つのデバフに複数の症状をまとめてある(バニラの鈍化/空腹などを別々に付与するのではなく、
 * この効果アイコン 1 つに集約する)。各段階の数値は {@code effects.json}({@link EffectTable#level})で
 * <b>段階ごとに別値</b>として持つ — バニラの「amplifier+1 倍」ではなく、NeoForge の level curve 付き
 * {@link #addAttributeModifier} で段階ごとの低下率をそのまま返す。
 * <ul>
 *     <li><b>胞子感染1</b>(amplifier 0): 防御力低下・空腹。</li>
 *     <li><b>胞子感染2</b>(amplifier 1): より強い防御力低下・空腹、少しの移動速度低下、
 *     グループ1・3〜7 の Mob からの検知範囲増加({@link SporeInfectionDetection})。</li>
 *     <li><b>胞子感染3</b>(amplifier 2): さらに強い防御力/移動速度低下、検知範囲増加、茂みの中でも見つかる
 *     ({@link FoliageConcealment})、バニラの空腹 II を別枠で 10 分間。自然満了でプレイヤーは死亡し汚染シダになる
 *     ({@link SporeInfectionExpiryHandler})。</li>
 * </ul>
 *
 * <p>Mob(非プレイヤー)は効果が自然に切れた瞬間に死亡する — {@link SporeInfectionExpiryHandler}
 * ({@code MobEffectEvent.Expired})が担当する。ミルク等で満了前に治療された場合は {@code Expired} が
 * 発火しないため死亡しない(=感染を治療できる)。死亡位置への死体ブロック/汚染シダの設置は
 * {@link SporeInfectionDeathHandler}({@code LivingDeathEvent})が死因を問わず一括して行う。
 */
public class SporeInfectionMobEffect extends MobEffect {

    public SporeInfectionMobEffect(MobEffectCategory category, int color) {
        super(category, color);
        // 段階ごとの低下率(effects.json の levelN.armorReduction / moveReduction)を curve で返す。
        // curve は付与のたびに評価されるので、design reload 後の値は次回付与から反映される。
        addAttributeModifier(Attributes.ARMOR,
                OvergrownVegetation.id("effect.spore_infection.armor"),
                AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL,
                amplifier -> -DesignData.effects().level(amplifier).armorReduction());
        addAttributeModifier(Attributes.MOVEMENT_SPEED,
                OvergrownVegetation.id("effect.spore_infection.movement_speed"),
                AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL,
                amplifier -> -DesignData.effects().level(amplifier).moveReduction());
    }

    @Override
    public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) {
        // 空腹(満腹度消耗)を毎 tick 与えるため常に true。
        return true;
    }

    @Override
    public boolean applyEffectTick(LivingEntity entity, int amplifier) {
        if (entity instanceof Player player && entity.level() instanceof ServerLevel) {
            double exhaustion = DesignData.effects().level(amplifier).hungerExhaustionPerTick();
            if (exhaustion > 0.0D) {
                player.causeFoodExhaustion((float) exhaustion);
            }
        }
        return true;
    }
}
