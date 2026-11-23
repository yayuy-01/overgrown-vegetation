package com.yayuy.overgrownvegetation.client;

import com.mojang.serialization.Codec;
import com.yayuy.overgrownvegetation.config.OvergrownClientConfig;
import com.yayuy.overgrownvegetation.config.QualityPreset;
import net.minecraft.client.OptionInstance;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * バニラの<b>ビデオ設定</b>画面に足す「この MOD の演出: なし / 低 / 中 / 高」(2026-11-23、ユーザー決定。置き場がビデオ設定なのは全体管理の仮決め)。
 * {@link com.yayuy.overgrownvegetation.mixin.client.VideoSettingsScreenMixin} が {@link CurledFernOption} と一緒に画面へ差し込む。
 *
 * <p>{@link CurledFernOption} と同じく、値の置き場は mod の client config で options.txt ではない。段そのものは保存せず、押したときに
 * {@link QualityPreset#writeToConfig()} が各項目へ一式書く。表示は毎回 config の値から決め({@link QualityPreset#current()})、
 * 見た目の設定の画面で個々の項目を変えた後は「カスタム」と出る。カスタムはボタンで選べない(押すと なし → 低 → 中 → 高 の順で回る。Shift で逆)。
 */
public final class RenderTierOption {

    private static final String KEY = "options.overgrown_vegetation.render_tier";

    /** 表示の値(段 4 つ + カスタム)。 */
    public enum Shown {
        NONE, LOW, MEDIUM, HIGH, CUSTOM;

        static Shown of(@Nullable QualityPreset p) {
            return p == null ? CUSTOM : valueOf(p.name());
        }

        @Nullable
        QualityPreset preset() {
            return this == CUSTOM ? null : QualityPreset.valueOf(name());
        }

        public Component caption() {
            return Component.translatable(KEY + "." + name().toLowerCase(Locale.ROOT));
        }
    }


    /**
     * 1.20.1: 本流の自前の値の組(ValueSet)は、1.20.1 では ValueSet がパッケージの外から見えないので作れない。
     * 公開の {@code OptionInstance.Enum} にカスタムも入れた 5 つで回す(なし → 低 → 中 → 高 → カスタム。カスタムを押しても何も書かない)。
     */
    private static final OptionInstance.Enum<Shown> VALUES = new OptionInstance.Enum<>(
            List.of(Shown.NONE, Shown.LOW, Shown.MEDIUM, Shown.HIGH, Shown.CUSTOM), Codec.STRING.xmap(Shown::valueOf, Shown::name));

    @Nullable
    private static OptionInstance<Shown> instance;

    /** ビデオ設定に並べる項目。画面を開くたびに呼ばれるので、そのたびに config から読み直す。 */
    public static OptionInstance<Shown> get() {
        Shown current = now();
        if (instance == null) {
            instance = new OptionInstance<>(
                    KEY,
                    value -> net.minecraft.client.gui.components.Tooltip.create(Component.translatable(KEY + ".tooltip").append("\n\n")
                            .append(Component.translatable(KEY + "." + value.name().toLowerCase(Locale.ROOT) + ".desc"))),
                    (caption, value) -> net.minecraft.client.Options.genericValueLabel(caption, value.caption()),
                    VALUES,
                    current,
                    RenderTierOption::apply);
        } else {
            // set() は値が変わったときだけ onValueUpdate を呼ぶ。読み直しで apply が走っても同じ値を書くだけ(カスタムは何もしない)。
            instance.set(current);
        }
        return instance;
    }

    /** 今の設定の段(カスタムも)。Sodium の画面({@code client/compat/SodiumOptionsPage})も読む。 */
    public static Shown now() {
        return OvergrownClientConfig.SPEC.isLoaded() ? Shown.of(QualityPreset.current()) : Shown.HIGH;
    }

    /** 段を書く(カスタムは何もしない)。ビデオ設定と Sodium の画面から。 */
    public static void apply(Shown shown) {
        QualityPreset p = shown.preset();
        if (p == null || !OvergrownClientConfig.SPEC.isLoaded() || QualityPreset.current() == p) {
            return;
        }
        boolean wasOn = OvergrownClientConfig.CLIENT.visualEffects.get();
        p.writeToConfig();
        // 巻きシダの精細さが変わっていれば区画を焼き直す。ほかの演出は描く側が毎フレーム VisualQuality から読むので、次のフレームから効く。
        GeoStaticGeometry.applyDetailChange();
        RenderTierNotice.afterChange(wasOn, OvergrownClientConfig.CLIENT.visualEffects.get());
    }

    private RenderTierOption() {
    }
}
