package com.yayuy.overgrownvegetation.devport;

import com.yayuy.overgrownvegetation.port.PortConst;
import com.yayuy.overgrownvegetation.port.PortSetup;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * 【1.20.1 だけの物・開発用の仮の起動クラス】統合 I の段まで(本物の起動クラス {@code OvergrownVegetation} が本流のクラスを全部読むので、
 * まだコンパイルできない間)、互換の層だけで MOD を起動する。自己テスト({@code OV_PORT_SELFTEST=1})と、タイトル画面までの確かめに使う。
 *
 * <p>コンパイルされるのは {@code ported-sources.d/F-dev.txt} に載っている間だけ。<b>統合 I は、ported-sources を全部にするときに
 * F-dev.txt とこのフォルダを消す</b>(本物の起動クラスと同じ MOD の id なので、両方あると起動で落ちる)。
 */
@Mod(PortConst.MODID)
public class PortDevMod {

    public PortDevMod(FMLJavaModLoadingContext context) {
        PortSetup.init(context.getModEventBus());
        PortConst.LOGGER.info("OvergrownVegetation (Forge 1.20.1) — port-only dev launcher (devport.PortDevMod). 本物の起動クラスは統合 I から");
    }
}
