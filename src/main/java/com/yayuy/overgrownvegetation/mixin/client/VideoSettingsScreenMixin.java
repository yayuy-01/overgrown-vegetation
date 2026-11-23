package com.yayuy.overgrownvegetation.mixin.client;

import com.yayuy.overgrownvegetation.client.CurledFernOption;
import com.yayuy.overgrownvegetation.client.RenderTierOption;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Arrays;

/**
 * バニラの<b>ビデオ設定</b>画面の末尾に「シダの葉を立体で描く」を足す({@link CurledFernOption})。
 *
 * <p>差し込む先は {@code VideoSettingsScreen.options(Options)} —— 画面が
 * {@code list.addSmall(...)} に渡す項目の配列を作っている private static メソッド。
 * ここで返る配列に 1 つ足せば、<b>バニラの項目とまったく同じ見た目・同じ並び方</b>で
 * 2 列のリストに並ぶ(自前でウィジェットを置く必要がない)。
 *
 * <p>配列は画面を開くたびに作り直されるので、{@link CurledFernOption#get()} もそのたびに呼ばれる。
 * あちらは呼ばれたときに config から値を読み直すので、config ファイルを直接編集されていても
 * 画面には最新の値が出る。
 */
@Mixin(VideoSettingsScreen.class)
public abstract class VideoSettingsScreenMixin {

    @Inject(method = "options", at = @At("RETURN"), cancellable = true)
    private static void overgrownvegetation$addFernOption(Options options,
            CallbackInfoReturnable<OptionInstance<?>[]> cir) {
        OptionInstance<?>[] vanilla = cir.getReturnValue();
        OptionInstance<?>[] withFern = Arrays.copyOf(vanilla, vanilla.length + 2);
        // 2026-11-23: 「この MOD の演出: なし / 低 / 中 / 高」({@link RenderTierOption})をシダの前に。
        withFern[vanilla.length] = RenderTierOption.get();
        withFern[vanilla.length + 1] = CurledFernOption.get();
        cir.setReturnValue(withFern);
    }
}
