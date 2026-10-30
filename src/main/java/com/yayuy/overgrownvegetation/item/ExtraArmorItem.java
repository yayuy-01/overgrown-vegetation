package com.yayuy.overgrownvegetation.item;

import net.minecraft.resources.ResourceLocation;

/**
 * 追加防具の枠({@link ExtraArmor})に付ける品が持つ情報(2026-10-15)。枠に入れられるかはタグで決まり、
 * ここに書くのは<b>付けたときの防御力と見た目</b>だけ。
 */
public interface ExtraArmorItem {

    /** どの枠の品か(見た目の描き方と防御力の修飾の名前に使う)。 */
    ExtraArmor.Part extraArmorPart();

    /** 付けている間に足す防御力(既定 0)。 */
    default int extraArmorDefense() {
        return 0;
    }

    /**
     * 体に描く絵({@code textures/models/armor/…}、バニラの防具の層と同じ 64×32 の並び)。
     * 描かない品は null。
     */
    ResourceLocation wornTexture();

    /**
     * 付けている間、持ち主の周りを照らす「動く光」の明るさ(0 = 照らさない。{@code client/light/DynamicLights})。
     * 琥珀の角灯は 12。
     */
    default int wornLightLevel() {
        return 0;
    }

    /**
     * その品のその時の明るさ(既定は {@link #wornLightLevel()})。灯す・消すがある品(陽溜まりのランタン、2026-10-16)はこちらを上書きする。
     * 動く光はこちらを引く。
     */
    default int wornLightLevel(net.minecraft.world.item.ItemStack stack) {
        return wornLightLevel();
    }

    /**
     * 体に描くとき、64×32 の絵の代わりに<b>品の模型そのもの</b>を枠の場所(腰なら腰の横)に下げて描くか
     * (角灯のように、防具の層の絵では描けない形の品)。
     */
    default boolean renderWornItem() {
        return false;
    }
}
