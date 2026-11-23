package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.world.item.Item;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;

import java.lang.reflect.Field;

/**
 * 【1.20.1 だけの物・G5 の助け手】NeoForge の {@code RegisterClientExtensionsEvent#registerItem} の代わり。
 * Forge 1.20.1 はアイテムの描き方の拡張を {@code Item#initializeClient}(アイテムを作るとき)でしか受けないので、
 * よそのクラス(バニラ・互換の層の {@code SpawnEggItem}・{@code BlockItem})には、Forge が持たせる欄 {@code Item#renderProperties} へ直に入れる。
 * {@code IClientItemExtensions.of(item)} は毎回この欄を読むので、最初に描く前(クライアントの準備の知らせ)に入れれば効く。
 * 欄はForge が足した物なので、本番の jar でも名前は変わらない。
 */
public final class PortClientExtensions {

    private static Field field;

    public void registerItem(IClientItemExtensions extensions, Item... items) {
        try {
            if (field == null) {
                field = Item.class.getDeclaredField("renderProperties");
                field.setAccessible(true);
            }
            for (Item item : items) {
                Object old = field.get(item);
                if (old != null && old != extensions) {
                    OvergrownVegetation.LOGGER.warn("{} はもう描き方の拡張を持っている。上書きする", item);
                }
                field.set(item, extensions);
            }
        } catch (ReflectiveOperationException e) {
            OvergrownVegetation.LOGGER.error("アイテムの描き方の拡張を入れられなかった(スポーンエッグ・チェストのアイテムは平たい絵になる)", e);
        }
    }
}
