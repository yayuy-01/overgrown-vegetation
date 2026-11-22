package com.yayuy.overgrownvegetation.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.yayuy.overgrownvegetation.client.difficulty.ClientWorldDifficulty;
import com.yayuy.overgrownvegetation.client.difficulty.LeafIconButton;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.layouts.LayoutSettings;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.world.Difficulty;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * ワールド作成画面の「ゲーム」タブで、<b>「難易度」のボタンの右隣に葉っぱのボタン</b>を置く(2026-09-25、ユーザー指定)。
 *
 * <p>タブは縦 1 列の表に部品を 1 つずつ足して作られる。難易度のボタンを足す呼び出しを包み、ボタンを 24 ピクセル縮めて、
 * 葉っぱのボタンと横に並べた小さな列ごと足す(列の幅は元の 210 のまま = ほかのボタンと揃う)。
 * どの呼び出しが難易度かは順番ではなく<b>中身</b>(値が {@link Difficulty} の {@link CycleButton})で見分ける。
 * 呼び出し元は戻り値を難易度のボタンとして使い続ける(ハードコアで無効にする処理など)ので、戻り値は元のボタンを返す。
 */
@Mixin(targets = "net.minecraft.client.gui.screens.worldselection.CreateWorldScreen$GameTab")
public abstract class CreateWorldScreenGameTabMixin {

    @WrapOperation(
            method = "<init>",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/layouts/GridLayout$RowHelper;addChild(Lnet/minecraft/client/gui/layouts/LayoutElement;Lnet/minecraft/client/gui/layouts/LayoutSettings;)Lnet/minecraft/client/gui/layouts/LayoutElement;"))
    private LayoutElement overgrownvegetation$addDifficultyButton(GridLayout.RowHelper rows, LayoutElement child,
                                                                  LayoutSettings settings,
                                                                  Operation<LayoutElement> original) {
        if (!(child instanceof CycleButton<?> cycle) || !(cycle.getValue() instanceof Difficulty)) {
            return original.call(rows, child, settings);
        }
        int gap = 4;
        LinearLayout line = LinearLayout.horizontal().spacing(gap);
        cycle.setWidth(cycle.getWidth() - LeafIconButton.SIZE - gap);
        line.addChild(cycle);
        line.addChild(ClientWorldDifficulty.createWorldButton());
        original.call(rows, line, settings);
        return cycle;
    }
}
