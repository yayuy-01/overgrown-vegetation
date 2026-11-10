package com.yayuy.overgrownvegetation.client.atmosphere.heat;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.HeatFxTable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;

/**
 * <b>1. 光らない面を暗くする</b>(2026-11-10 熱水の森のシェーダー段階 1。ユーザー了承「ネザーは元々どこも薄明るい。周りが暗ければ弱い光でも植物が浮かぶ」)。
 *
 * <p>バニラのネザーは {@code ambientLight 0.1} と赤のちらつきの倍率 1.5 で、ブロックの光 0 の面でもライトマップが 0.15(明るさの設定でさらに持ち上がる)。
 * 熱水の森の中だけ、ライトマップの 1 画素(x = ブロックの光の段)を、光 0 の段で {@code 1 − 強さ} 倍、{@code keepLevel} 段へ向けてなめらかに 1 倍へ戻す倍率で暗くする。
 * 光る模様の面({@code _glow}、block_light 4・9・12・15)と、溶岩・火・松明に照らされた面は段が高いので暗くしすぎない。
 * ライトマップなので地形(バニラ・Sodium とも)・Mob・粒・アイテムが同じに暗くなる。見た目だけ(サーバーの明るさ・湧きは変わらない)。
 *
 * <p>読み手は {@code LightTextureCanopyLiftMixin}(緑の照り返しの包みに 1 回の呼び出し)。ライトマップは毎 tick 作り直されるので、度合いの変化はすぐ効く。
 * 暗視・コンジットパワーの間は、その強さの分だけ弱める。シェーダーパック使用中は掛けない({@link HeatFx#enabled})。
 */
public final class HeatFxShade {

    /** 確かめ用: 暗さを止める。 */
    static boolean devOff;
    /** 確かめ用: 0〜1 なら強さをこの値にする(負 = 設計値)。 */
    static float devStrength = -1.0F;

    private HeatFxShade() {
    }

    /** 暗視で弱める前の強さ(確かめ用の表示。設計値の昼夜 × 熱水の森の度合い)。 */
    static float baseStrength() {
        float s = devStrength >= 0.0F ? devStrength : Mth.lerp(HeatFxSky.day(), DesignData.heatFx().shadeStrength(), DesignData.heatFx().glow().dayShadeStrength);
        return Math.max(0.0F, Math.min(1.0F, s)) * HeatFxPresence.presence();
    }

    /** 今の強さ(0〜1)= 設計値 × 熱水の森の度合い × (1 − 暗視)。 */
    public static float strength() {
        if (devOff || !HeatFx.enabled()) {
            return 0.0F;
        }
        float p = HeatFxPresence.presence();
        if (p <= 0.0005F) {
            return 0.0F;
        }
        // 夜の値(shade.strength)と昼の値(day.shadeStrength)を偽の空の昼の度合いで寄せる(11-10 段階 2)
        float s = devStrength >= 0.0F ? devStrength : Mth.lerp(HeatFxSky.day(), DesignData.heatFx().shadeStrength(), DesignData.heatFx().glow().dayShadeStrength);
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null && player.hasEffect(MobEffects.NIGHT_VISION)) {
            s *= 1.0F - GameRenderer.getNightVisionScale(player, 1.0F);
        }
        return Math.max(0.0F, Math.min(1.0F, s)) * p;
    }

    /** ライトマップの 1 画素(ABGR、{@code x} = ブロックの光の段・{@code y} = 空の光の段)を暗くして返す。 */
    public static int lightmap(int abgr, int x, int y) {
        float s = strength();
        if (s <= 0.001F) {
            return abgr;
        }
        HeatFxTable t = DesignData.heatFx();
        float k = 1.0F - s * (1.0F - HeatFxPresence.smooth(x / t.shadeKeepLevel()));
        if (k >= 0.999F) {
            return abgr;
        }
        int r = Math.round((abgr & 255) * k);
        int g = Math.round((abgr >> 8 & 255) * k);
        int b = Math.round((abgr >> 16 & 255) * k);
        return abgr & 0xFF000000 | b << 16 | g << 8 | r;
    }
}
