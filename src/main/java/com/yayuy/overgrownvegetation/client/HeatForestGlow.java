package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.atmosphere.heat.HeatFxGlow;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.world.level.block.Block;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;

/**
 * 熱水の森の「光る模様」の明るさの口(2026-11-09 段階 1)。
 *
 * <p>熱水の森の模型は、光る画素だけを描いた {@code _glow} の面を別に持ち、その面に {@code block_light}(光る強さ。種ごと)と
 * {@code tintindex} を付けてある(1 = 空・2 = 地の目印・3 = 地の粒。11-10 追記 6 で分けた。後の熱水の森のシェーダーと偽の空の昼夜で、
 * 分類ごとに明るさを掛ける)。ここはその 3 つの色を返す。<b>段階 1 は「夜の姿」で固定</b>なので常に白(そのまま)。
 * 後の段階(偽物の空の昼夜)で、地上の模様を昼 40% にするときは {@link #glowFactor} を時刻で変える
 * (チャンクの形に焼き込まれる色なので、変えたらネザーのチャンクを組み直すか、シェーダーで掛ける。どちらにするかは後の段階で決める)。
 * 本体の面(tintindex なし)には効かない。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class HeatForestGlow {

    /** 光る模様に掛ける明るさ(0〜1)。段階 1 は 1 で固定(夜の姿)。 */
    public static float glowFactor() {
        return 1.0F;
    }

    @SubscribeEvent
    public static void registerColors(RegisterColorHandlersEvent.Block event) {
        Block[] blocks = ModBlocks.heatForestBlocks().stream().map(b -> b.get()).toArray(Block[]::new);
        event.register((state, level, pos, tintIndex) -> {
            if (tintIndex < 1 || tintIndex > 5) {
                return -1;
            }
            // 11-10 段階 2(熱水の森のシェーダー): 地形のシェーダーが使えるときは階級の目印の色を返す(読み手 include/ov_heat_glow.glsl、
            // 作りは client/atmosphere/heat/HeatFxGlow)。使えないとき(シェーダーパック・Sodium への差し込み無し・設定で切った)は白。
            if (HeatFxGlow.codesOn()) {
                return HeatFxGlow.codeColor(tintIndex);
            }
            int v = Math.round(255 * Math.max(0F, Math.min(1F, glowFactor())));
            return 0xFF000000 | (v << 16) | (v << 8) | v;
        }, blocks);
    }

    private HeatForestGlow() {
    }
}
