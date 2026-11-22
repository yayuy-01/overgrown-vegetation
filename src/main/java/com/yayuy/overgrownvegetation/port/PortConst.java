package com.yayuy.overgrownvegetation.port;

import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 【1.20.1 だけの物・互換の層】互換の層が使う MOD の名前・ログ。起動クラス {@code OvergrownVegetation} に頼らないため
 * (port/ だけを Gradle でコンパイルして確かめられるように。起動クラスは本流のクラス全部を読む)。
 */
public final class PortConst {

    private PortConst() {
    }

    public static final String MODID = "overgrown_vegetation";
    public static final Logger LOGGER = LoggerFactory.getLogger("OvergrownVegetation");

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }
}
