package com.yayuy.overgrownvegetation.mixin.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.yayuy.overgrownvegetation.client.ClientErosion;
import com.yayuy.overgrownvegetation.spore.ErosionFood;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 浸食度で食料ゲージの上限が減っているあいだ、<b>失われた枠を「枯れた肉アイコン」として描く</b>
 * (2026-09-22)。
 *
 * <p>一人称では自分のスキンが見えないので、<b>浸食度が何%まで進んでいるかを常に見せているのは
 * このゲージだけ</b>になる。単に短くするのではなく失われた枠を残しているのはそのため ——
 * 「10 枠あるうち 5 枠が枯れている」と一目で分かる。
 *
 * <p>バニラの {@code Gui#renderFood} は 10 枠を決め打ちで描くので、浸食度があるときだけ
 * 丸ごと差し替える(無いときは素通しでバニラに任せる)。枯れた枠は<b>専用のテクスチャを作らず</b>、
 * バニラの空アイコンを {@link GuiGraphics#setColor} で暗く落として描いている。
 *
 * <p>ゆらぎ({@code tickCount % (i * 3 + 1)})と空腹エフェクトの色替えはバニラと同じ条件を保つ。
 * 空腹の判定は {@code GuiFoodBarSporeHungerMixin} と同じく胞子感染の空腹も拾いたいので、
 * ここでも {@code player.hasEffect} をそのまま呼ぶ(あちらの {@code @Redirect} が効く)。
 */
@Mixin(Gui.class)
public abstract class GuiFoodBarErosionMixin {

    @Shadow
    @Final
    private static ResourceLocation FOOD_EMPTY_SPRITE;
    @Shadow
    @Final
    private static ResourceLocation FOOD_HALF_SPRITE;
    @Shadow
    @Final
    private static ResourceLocation FOOD_FULL_SPRITE;
    @Shadow
    @Final
    private static ResourceLocation FOOD_EMPTY_HUNGER_SPRITE;
    @Shadow
    @Final
    private static ResourceLocation FOOD_HALF_HUNGER_SPRITE;
    @Shadow
    @Final
    private static ResourceLocation FOOD_FULL_HUNGER_SPRITE;

    @Shadow
    @Final
    private RandomSource random;

    @Shadow
    private int tickCount;

    /** 枯れた枠の色(暗い緑がかった灰色)。 */
    private static final float DEAD_R = 0.30F;
    private static final float DEAD_G = 0.36F;
    private static final float DEAD_B = 0.28F;

    @Inject(method = "renderFood", at = @At("HEAD"), cancellable = true)
    private void overgrown_vegetation$renderCappedFood(GuiGraphics guiGraphics, Player player, int y, int x,
                                                       CallbackInfo ci) {
        float percent = ClientErosion.percentOf(player);
        if (percent <= 0.0F) {
            return; // 浸食度が無いときはバニラのまま。
        }
        int maxFood = ErosionFood.maxFood(percent);
        if (maxFood >= ErosionFood.VANILLA_MAX_FOOD) {
            return;
        }

        FoodData food = player.getFoodData();
        int level = food.getFoodLevel();
        boolean hunger = player.hasEffect(MobEffects.HUNGER);
        ResourceLocation empty = hunger ? FOOD_EMPTY_HUNGER_SPRITE : FOOD_EMPTY_SPRITE;
        ResourceLocation half = hunger ? FOOD_HALF_HUNGER_SPRITE : FOOD_HALF_SPRITE;
        ResourceLocation full = hunger ? FOOD_FULL_HUNGER_SPRITE : FOOD_FULL_SPRITE;

        // 生きている枠の数。上限は 2 で 1 枠なので切り上げる(上限 9 なら 5 枠目が半分だけ使える)。
        int liveSlots = (maxFood + 1) / 2;

        RenderSystem.enableBlend();
        for (int slot = 0; slot < 10; slot++) {
            int drawY = y;
            // ゆらぎの条件はバニラと同じ(飽和度 0 のときだけ揺れる)。
            if (food.getSaturationLevel() <= 0.0F && this.tickCount % (level * 3 + 1) == 0) {
                drawY = y + (this.random.nextInt(3) - 1);
            }
            int drawX = x - slot * 8 - 9;

            if (slot >= liveSlots) {
                // 失われた枠。空アイコンを暗く落として描くだけで、中身は描かない。
                guiGraphics.setColor(DEAD_R, DEAD_G, DEAD_B, 1.0F);
                guiGraphics.blitSprite(empty, drawX, drawY, 9, 9);
                guiGraphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
                continue;
            }

            guiGraphics.blitSprite(empty, drawX, drawY, 9, 9);
            if (slot * 2 + 1 < level) {
                guiGraphics.blitSprite(full, drawX, drawY, 9, 9);
            } else if (slot * 2 + 1 == level) {
                guiGraphics.blitSprite(half, drawX, drawY, 9, 9);
            }
        }
        RenderSystem.disableBlend();
        ci.cancel();
    }
}
