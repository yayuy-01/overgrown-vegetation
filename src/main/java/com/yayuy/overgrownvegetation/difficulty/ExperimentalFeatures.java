package com.yayuy.overgrownvegetation.difficulty;

import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;

import java.util.OptionalDouble;

/**
 * <b>実験的要素</b>(2026-10-09 ユーザー指定「設定画面に『実験的要素』の項目を用意し、オンにしたときだけ出るように」)の問い合わせ口。
 * 値はワールドごと({@link DifficultySetting#EXPERIMENTAL}。Overgrown Vegetation の設定の画面)、<b>既定はオフ</b>。
 * <b>GameTest のサーバーだけは値が無ければオン</b>(オフの確かめはテストが値を書いて同じ tick で戻す)。
 *
 * <p>前の版(同盟軍・パワードアーマーなどを止めていた)は 2026-10-30 に同盟軍と一緒に {@code parked/} へ行った。
 * 2026-11-12 に、新しいネザーの仕組みを止める口としてサーバー側だけ作り直した(ネザーの作り直しは 0.2.0 の後へ回すため。ユーザー決定)。
 *
 * <p><b>今オフのワールドで止めるもの</b>: 新しいネザーの仕組み(根の台帳・5 段階・先駆けの植物・管の樹・湖・ネザーの胞子の場。
 * 入口は {@code NetherLedger#active} と {@code NetherSporeFieldServer})。新しいブロック・アイテムの登録とクリエイティブタブはそのまま。
 */
public final class ExperimentalFeatures {

    /** このワールドで実験的要素がオンか(値が無ければオフ、GameTest のサーバーだけはオン)。 */
    public static boolean enabled(MinecraftServer server) {
        OptionalDouble v = WorldDifficultyData.get(server).override(DifficultySetting.EXPERIMENTAL);
        return v.isPresent() ? v.getAsDouble() >= 0.5D : server instanceof GameTestServer;
    }

    /** サーバーのワールドならそのワールドの値。クライアントは今は知らない(false)。 */
    public static boolean enabled(Level level) {
        if (level.isClientSide()) return false;
        MinecraftServer server = level.getServer();
        return server != null && enabled(server);
    }

    /** このワールドの値を書く(テスト用)。 */
    public static void set(MinecraftServer server, boolean on) {
        WorldDifficultyData.get(server).set(DifficultySetting.EXPERIMENTAL, on ? 1.0D : 0.0D);
    }

    /** ワールドの値を消す(既定に戻す。テスト用)。 */
    public static void reset(MinecraftServer server) {
        WorldDifficultyData.get(server).reset(DifficultySetting.EXPERIMENTAL);
    }

    private ExperimentalFeatures() {
    }
}
