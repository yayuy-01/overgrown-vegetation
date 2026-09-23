package com.yayuy.overgrownvegetation.mixin.client;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModMobEffects;
import net.minecraft.client.gui.Gui;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 「胞子感染」の空腹(段階ごとの {@code hungerExhaustionPerTick} &gt; 0)がかかっている間、
 * HUD の食料ゲージをバニラの「空腹」効果と同じ緑色のスプライトで描画する。
 *
 * <p>バニラの {@code Gui#renderFood} は {@code player.hasEffect(MobEffects.HUNGER)} だけを見て
 * スプライトを切り替えるので、その呼び出しを差し替えて「胞子感染の空腹も同じ扱い」にする。
 * 胞子感染3 はバニラの空腹 II を別枠で付与するので元から緑になる。
 */
@Mixin(Gui.class)
public abstract class GuiFoodBarSporeHungerMixin {

    @Redirect(
            method = "renderFood",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/player/Player;hasEffect(Lnet/minecraft/core/Holder;)Z"))
    private boolean overgrownvegetation$sporeHungerFoodBar(Player player, Holder<MobEffect> effect) {
        if (player.hasEffect(effect)) {
            return true;
        }
        if (effect != MobEffects.HUNGER) {
            return false;
        }
        MobEffectInstance infection = player.getEffect(ModMobEffects.SPORE_INFECTION);
        return infection != null
                && DesignData.effects().level(infection.getAmplifier()).hungerExhaustionPerTick() > 0.0D;
    }
}
