package com.yayuy.overgrownvegetation.mixin.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.difficulty.CreateWorldDifficultyDraft;
import com.yayuy.overgrownvegetation.difficulty.DifficultySetting;
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
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * バニラのワールド作成画面に、Overgrown Vegetation の難易度の「このワールドの値」を持たせる(2026-09-25)。
 *
 * <p>値は葉っぱのボタンで開く画面({@code WorldDifficultyScreen})が書き換え、「ワールド作成」で新しいワールドのフォルダが
 * できた直後({@code createNewWorldDirectory} が返ったところ。この後でサーバーが起動する)に
 * {@link WorldDifficultyData#writeInitial} がワールドの {@code data/} へ書く。統合サーバーはそれをふつうの保存データとして読む
 * —— 起動時に「作ったばかりか」を見分ける仕掛けが要らず、作成が途中で失敗しても別のワールドへ漏れない。
 */
@Mixin(CreateWorldScreen.class)
public abstract class CreateWorldScreenMixin implements CreateWorldDifficultyDraft {

    @Unique
    private final Map<DifficultySetting, Double> overgrownvegetation$difficulty = new EnumMap<>(DifficultySetting.class);

    @Override
    public Map<DifficultySetting, Double> overgrownvegetation$difficultyDraft() {
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
            // 書けなくてもワールドは作る(難易度が既定値になるだけ)。
            OvergrownVegetation.LOGGER.error("Could not write the Overgrown Vegetation difficulty into the new world", e);
        }
    }
}
