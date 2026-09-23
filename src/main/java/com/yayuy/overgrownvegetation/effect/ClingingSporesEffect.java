package com.yayuy.overgrownvegetation.effect;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * 「纏わりついた胞子」— スポロフィル
 * ({@link com.yayuy.overgrownvegetation.entity.SporophyllEntity})の傘の下にいる<b>敵</b>に付くデバフ。
 *
 * <p>効果は 3 つ。
 * <ul>
 *     <li><b>見つかりやすくなる</b> —— これが付いているあいだ<b>グループ4 の Mob からの被検知距離が
 *     {@code sporophyllDetectionBonus}(既定 24 ブロック)伸びる</b>
 *     ({@link SporeInfectionDetection#bonusFor(net.minecraft.world.entity.LivingEntity,
 *     net.minecraft.world.entity.LivingEntity)})。グループ4 以外(グループ3 の捕食者など)には効かない。</li>
 *     <li><b>移動速度低下</b> —— 1 段階につき {@value #SLOWNESS} (バニラの「移動速度低下」と同じ)。</li>
 *     <li><b>攻撃力低下</b> —— 1 段階につき {@value #WEAKNESS} (バニラの「攻撃力低下」と同じ)。</li>
 * </ul>
 *
 * <h2>速度と攻撃力を「この効果の中に」持たせてある(ユーザー提案 2026-09-16)</h2>
 * 以前は {@link com.yayuy.overgrownvegetation.entity.SporophyllAura} が
 * <b>バニラの移動速度低下・攻撃力低下を別々に配っていた</b>。そのままだと
 * 「胞子除去装置の圏内では消える」を実装したときに、<b>他所から掛かった鈍化・弱体化まで巻き込んで</b>
 * 消してしまう —— 効果には出所が記録されないので、スポロフィル由来のぶんだけを見分けられない。
 *
 * <p>能力値の変化をこの効果自身の {@link MobEffect#addAttributeModifier} に移したことで、
 * <b>消すのはこの効果 1 つだけ</b>になり、鈍化のポーションや他 MOD の効果は無傷で残る。
 * 見た目にも、アイコンが 3 つ並ぶのではなく 1 つにまとまる。
 *
 * <p>数値はバニラと同じにしてある(段階 II なら速度 -30%・攻撃力 -8)ので、
 * <b>まとめる前後で強さは変わらない</b>。強さは {@code sporophyllDebuffAmplifier} が
 * この効果の段階として決める。持続は {@code sporophyllDebuffTicks}(既定 30 秒)。
 */
public class ClingingSporesEffect extends MobEffect {

    /** 1 段階あたりの移動速度の低下率(バニラの「移動速度低下」と同じ -15%)。 */
    private static final double SLOWNESS = -0.15D;
    /** 1 段階あたりの攻撃力の低下量(バニラの「攻撃力低下」と同じ -4)。 */
    private static final double WEAKNESS = -4.0D;

    public ClingingSporesEffect(MobEffectCategory category, int color) {
        super(category, color);
        this.addAttributeModifier(Attributes.MOVEMENT_SPEED,
                OvergrownVegetation.id("effect.clinging_spores.slowness"),
                SLOWNESS, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
        this.addAttributeModifier(Attributes.ATTACK_DAMAGE,
                OvergrownVegetation.id("effect.clinging_spores.weakness"),
                WEAKNESS, AttributeModifier.Operation.ADD_VALUE);
    }

    /**
     * 毎 tick 呼ばれる必要はない。能力値は {@link MobEffect#addAttributeModifier} 側の仕掛けで
     * 効果が付いた/外れた瞬間にだけ反映されるので、tick 処理は要らない。
     */
    @Override
    public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) {
        return false;
    }
}
