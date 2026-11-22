package com.yayuy.overgrownvegetation.entity.g1.skill;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import com.yayuy.overgrownvegetation.port.event.tick.ServerTickEvent;

import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;
import com.yayuy.overgrownvegetation.port.entity.PortAttributes;
import com.yayuy.overgrownvegetation.port.entity.OvModifiers;

/**
 * 根糸に絡まった(2026-11-13、能力の第 4 回。クモの 1 段目の (c)「当たると絡まって遅くなり、跳べない」)。
 * 鈍足 II(バニラの効果)と、<b>跳ぶ強さ 0</b>(属性 {@code generic.jump_strength} を一時的に ×0)を同じ長さだけ掛ける。
 *
 * <p>新しい効果(アイコンの絵が要る)を作らないための作り: 跳べないのは属性の一時的な修飾(保存しない)で、切れる時刻をここで持つ。
 * プレイヤーの跳びはクライアントが決めるが、この属性は同期されるので効く。絡まっている相手だけをサーバーの tick の終わりに見る(普段は空)。
 * 掛け直すと長い方が残る。サーバーが止まる・相手が世界から出ると修飾ごと消える。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID)
public final class G1Tangle {

    /** 鈍足の強さ(1 = II = −30%)。仕様「鈍化 II」。 */
    static final int SLOW_AMPLIFIER = 1;
    private static final ResourceLocation NO_JUMP_ID = OvergrownVegetation.id("g1_rootsilk_tangle");

    /** 絡まっている相手 → 切れる時刻(その世界のゲームの tick)。 */
    private static final Map<LivingEntity, Long> TANGLED = new WeakHashMap<>();

    private G1Tangle() {
    }

    /** {@code ticks} の間、遅くして跳べなくする(サーバー)。 */
    public static void apply(LivingEntity target, int ticks) {
        if (target.level().isClientSide() || ticks <= 0 || !target.isAlive()) return;
        target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, ticks, SLOW_AMPLIFIER));
        AttributeInstance jump = target.getAttribute(PortAttributes.JUMP_STRENGTH.get());
        if (jump != null && !jump.hasModifier(NO_JUMP_ID)) {
            jump.addTransientModifier(OvModifiers.create(NO_JUMP_ID, -1.0D, AttributeModifier.Operation.MULTIPLY_TOTAL));
        }
        long until = target.level().getGameTime() + ticks;
        TANGLED.merge(target, until, Math::max);
    }

    /** 絡まって跳べない間か。 */
    public static boolean isTangled(LivingEntity target) {
        Long until = TANGLED.get(target);
        return until != null && target.level().getGameTime() < until;
    }

    /** 今すぐほどく(GameTest・確かめ用)。 */
    public static void release(LivingEntity target) {
        TANGLED.remove(target);
        AttributeInstance jump = target.getAttribute(PortAttributes.JUMP_STRENGTH.get());
        if (jump != null) jump.removeModifier(NO_JUMP_ID);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (TANGLED.isEmpty()) return;
        Iterator<Map.Entry<LivingEntity, Long>> it = TANGLED.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<LivingEntity, Long> e = it.next();
            LivingEntity target = e.getKey();
            if (target == null) continue;
            if (target.isRemoved() || !target.isAlive() || target.level().getGameTime() >= e.getValue()) {
                AttributeInstance jump = target.getAttribute(PortAttributes.JUMP_STRENGTH.get());
                if (jump != null) jump.removeModifier(NO_JUMP_ID);
                it.remove();
            }
        }
    }
}
