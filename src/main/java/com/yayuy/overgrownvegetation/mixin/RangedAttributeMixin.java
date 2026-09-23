package com.yayuy.overgrownvegetation.mixin;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * バニラの最大体力属性({@code generic.max_health})の上限 1024 を外す。
 *
 * <p>{@code Attributes.MAX_HEALTH} は {@code RangedAttribute(…, 20, 1, 1024)} で、
 * {@code setBaseValue(5000)} のように上限を超える値は {@link RangedAttribute#sanitizeValue} で
 * 黙って 1024 に丸められる。すると「体力 5000 で湧かせたつもりが、最初の被弾で
 * {@code setHealth} が最大体力(1024)にクランプして体力が 1024 まで落ちる」という現象になる —
 * 攻城戦ボス(体力 1,000,000)だけでなく、ハイヴノードのように JSON で大きな体力を与えた Mob も同じ。
 * ここでは最大体力属性に限り、上限を {@value #MAX_HEALTH_CAP} に引き上げる(他の属性は触らない)。
 *
 * <p>属性の同定は説明 ID の文字列で行う。{@code Attributes.MAX_HEALTH} を参照すると
 * {@code Attributes} のクラス初期化中に呼ばれた場合に循環するため。
 */
@Mixin(RangedAttribute.class)
public abstract class RangedAttributeMixin {

    private static final double MAX_HEALTH_CAP = 1.0E9D;
    private static final String MAX_HEALTH_ID = "attribute.name.generic.max_health";

    /**
     * この属性が最大体力かどうかの判定結果。{@code 0}=未判定 / {@code 1}=最大体力 / {@code 2}=それ以外。
     *
     * <p>{@code sanitizeValue} は<b>能力値を計算し直すたびに</b>(全エンティティ・全属性)呼ばれる
     * ゲーム中でも最も回数の多いメソッドのひとつなので、そこで毎回文字列比較をしない。
     * 属性のインスタンスは登録時に 1 個作られたきり差し替わらないので、初回の判定を覚えておけばよい
     * (説明 ID で見分ける理由は上のクラス javadoc のとおり)。
     */
    @Unique
    private byte overgrown_vegetation$maxHealthFlag;

    @Unique
    private boolean overgrown_vegetation$isMaxHealth() {
        byte flag = this.overgrown_vegetation$maxHealthFlag;
        if (flag == 0) {
            flag = MAX_HEALTH_ID.equals(((Attribute) (Object) this).getDescriptionId()) ? (byte) 1 : (byte) 2;
            this.overgrown_vegetation$maxHealthFlag = flag;
        }
        return flag == 1;
    }

    @Inject(method = "sanitizeValue", at = @At("HEAD"), cancellable = true)
    private void overgrown_vegetation$uncapMaxHealth(double value, CallbackInfoReturnable<Double> cir) {
        if (overgrown_vegetation$isMaxHealth()) {
            cir.setReturnValue(Double.isNaN(value) ? 1.0D : Mth.clamp(value, 1.0D, MAX_HEALTH_CAP));
        }
    }

    @Inject(method = "getMaxValue", at = @At("HEAD"), cancellable = true)
    private void overgrown_vegetation$uncapMaxHealthRange(CallbackInfoReturnable<Double> cir) {
        if (overgrown_vegetation$isMaxHealth()) {
            cir.setReturnValue(MAX_HEALTH_CAP);
        }
    }
}
