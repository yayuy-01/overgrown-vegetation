package com.yayuy.overgrownvegetation.mixin.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.difficulty.CreateWorldDifficultyDraft;
import com.yayuy.overgrownvegetation.difficulty.DifficultyValues;
import com.yayuy.overgrownvegetation.difficulty.WorldDifficultyData;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.IOException;
import java.util.Optional;

/**
 * バニラのワールド作成画面に、Overgrown Vegetation の設定の「このワールドの値」を持たせる(2026-09-25、11-19 に Mob ごとの詳細も)。
 *
 * <p>値は葉っぱのボタンで開く設定のページが書き換え、「ワールド作成」で新しいワールドのフォルダができた直後
 * ({@code createNewWorldDirectory} が返ったところ。この後でサーバーが起動する)に {@link WorldDifficultyData#writeInitial} が
 * ワールドの {@code data/} へ書く。統合サーバーはそれをふつうの保存データとして読む。
 */
@Mixin(CreateWorldScreen.class)
public abstract class CreateWorldScreenMixin implements CreateWorldDifficultyDraft {

    @Unique
    private final DifficultyValues overgrownvegetation$difficulty = new DifficultyValues();

    @Override
    public DifficultyValues overgrownvegetation$difficultyDraft() {
        return overgrownvegetation$difficulty;
    }

    @Inject(method = "createNewWorldDirectory", at = @At("RETURN"))
    private void overgrownvegetation$writeDifficulty(
            CallbackInfoReturnable<Optional<LevelStorageSource.LevelStorageAccess>> cir) {
        Optional<LevelStorageSource.LevelStorageAccess> access = cir.getReturnValue();
        if (access == null || access.isEmpty() || overgrownvegetation$difficulty.isEmpty()) {
            return;
        }
        try {
            WorldDifficultyData.writeInitial(access.get().getDimensionPath(Level.OVERWORLD), overgrownvegetation$difficulty);
        } catch (IOException | RuntimeException e) {
            // 書けなくてもワールドは作る(設定が既定値になるだけ)。
            OvergrownVegetation.LOGGER.error("Could not write the Overgrown Vegetation settings into the new world", e);
        }
    }
}
