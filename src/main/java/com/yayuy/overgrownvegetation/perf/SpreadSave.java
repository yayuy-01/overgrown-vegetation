package com.yayuy.overgrownvegetation.perf;

/** 自動保存を tick に分ける mixin(ChunkMapSpreadSaveMixin)が先に書き出したチャンクの数(測り用。サーバースレッドだけが書く)。 */
public final class SpreadSave {
    private SpreadSave() {
    }

    public static long saved, ticks;
}
