package com.yayuy.overgrownvegetation.client.compat;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.CurledFernOption;
import com.yayuy.overgrownvegetation.client.RenderTierOption;
import com.yayuy.overgrownvegetation.client.RenderTierOption.Shown;
import com.yayuy.overgrownvegetation.client.difficulty.VisualSettingsScreen;
import com.yayuy.overgrownvegetation.config.OvergrownClientConfig;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.EnumSet;
import java.util.Set;

/**
 * Sodium の設定の画面に足すこの MOD のページ(2026-11-23、ユーザー決定)。Sodium はバニラのビデオ設定の画面を自前の物に差し替えるので、
 * Sodium が入っているとビデオ設定に足した「この MOD の演出」({@link RenderTierOption})と「シダの葉を立体で描く」({@link CurledFernOption})が
 * 見えなかった。Sodium 0.6 以降の設定の API でここに同じ 2 つと、見た目の設定の画面を開くボタンを並べる。
 *
 * <p>Sodium は {@code neoforge.mods.toml} の {@code [modproperties.overgrown_vegetation]} の {@code "sodium:config_api_user"} に書いたこの
 * クラスの名前を読み、自分で作って呼ぶ。<b>Sodium が無い・設定の API の無い古い版のときは、このクラスはどこからも読まれない</b>
 * (ほかのクラスから参照しないこと。参照すると Sodium の無い環境で NoClassDefFoundError になる)。API はコンパイルにだけ使う(build.gradle の compileOnly)。
 * 値の置き場は今までどおり MOD の client config で、Sodium の保存には何も残さない。
 */
public final class SodiumOptionsPage implements ConfigEntryPoint {

    private static final String KEY = "options.overgrown_vegetation.";

    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        var tier = builder.createEnumOption(OvergrownVegetation.id("render_tier"), Shown.class)
                .setName(Component.translatable(KEY + "render_tier"))
                .setTooltip(Component.translatable(KEY + "render_tier.tooltip"))
                .setElementNameProvider(Shown::caption)
                // カスタムは選べないが、今の値がカスタムのときは表示のために入れておく
                .setAllowedValuesProvider(state -> allowed())
                .setDefaultValue(Shown.HIGH)
                .setBinding(RenderTierOption::apply, RenderTierOption::now)
                .setStorageHandler(SodiumOptionsPage::save)
                .setImpact(OptionImpact.HIGH);
        var ferns = builder.createBooleanOption(OvergrownVegetation.id("show_all_ferns"))
                .setName(Component.translatable(KEY + "show_all_ferns"))
                .setTooltip(Component.translatable(KEY + "show_all_ferns.tooltip"))
                .setDefaultValue(false)
                .setBinding(CurledFernOption::apply, CurledFernOption::showAll)
                .setStorageHandler(SodiumOptionsPage::save)
                .setImpact(OptionImpact.MEDIUM);
        var more = builder.createExternalButtonOption(OvergrownVegetation.id("visual_settings"))
                .setName(Component.translatable(KEY + "sodium.more"))
                .setTooltip(Component.translatable(KEY + "sodium.more.tooltip"))
                .setScreenConsumer(parent -> Minecraft.getInstance().setScreen(new VisualSettingsScreen(parent)));
        builder.registerOwnModOptions()
                .addPage(builder.createOptionPage()
                        .setName(Component.translatable(KEY + "sodium.page"))
                        .addOptionGroup(builder.createOptionGroup()
                                .addOption(tier)
                                .addOption(ferns))
                        .addOptionGroup(builder.createOptionGroup()
                                .addOption(more)));
    }

    private static Set<Shown> allowed() {
        Set<Shown> set = EnumSet.of(Shown.NONE, Shown.LOW, Shown.MEDIUM, Shown.HIGH);
        if (RenderTierOption.now() == Shown.CUSTOM) {
            set.add(Shown.CUSTOM);
        }
        return set;
    }

    private static void save() {
        if (OvergrownClientConfig.SPEC.isLoaded()) {
            OvergrownClientConfig.SPEC.save();
        }
    }
}
