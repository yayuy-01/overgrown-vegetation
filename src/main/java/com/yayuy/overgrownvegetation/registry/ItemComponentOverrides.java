package com.yayuy.overgrownvegetation.registry;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;

/**
 * {@code items.json} に書いた<b>スタック上限</b>と<b>レア度(名前の色)</b>をアイテムへ掛ける
 * (2026-10-15 ユーザー指定)。
 *
 * <p>どちらも本来は {@code ModItems} の登録時に {@code Item.Properties} で決まるが、そこは約 250 行の
 * 宣言が並ぶ所で、1 つ変えるたびに Java を触ることになる。NeoForge の {@code ModifyDefaultComponentsEvent}
 * は<b>登録が済んだあと</b>に既定のデータコンポーネントを差し替えられるので、宣言には一切触らずに済む。
 *
 * <p>書いていないアイテムは登録時のまま。反映は<b>再起動</b>(このイベントは起動時に 1 度だけ)。
 *
 * @see ItemTable
 */
public final class ItemComponentOverrides {

    private ItemComponentOverrides() {
    }

    // 1.20.1: ModifyDefaultComponentsEvent(1.20.5)が無い。本流でも中身は空(items.json の上書きは今は無い)なので、受け手ごと外した。
    // スタック上限・レア度を items.json から掛けるのを戻すときは、1.20.1 では Item#getMaxStackSize・getRarity を上書きする形になる
}
