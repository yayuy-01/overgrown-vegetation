package com.yayuy.overgrownvegetation.entity;

import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.world.entity.Entity;

/**
 * 確かめ用の名札({@code nostroll}・{@code hop}・{@code notarget}・{@code nopack} など)を読む口(2026-11-16、コードの見直し ①6)。
 * 名札が効くのは<b>開発用の操作口({@code -Dov.devbridge}。runClaudeClient・runCompatClient)か GameTest のサーバー</b>のときだけ。
 * 前は守りが無く、遊ぶ人が金床の名札で G4・G5 を「うろつかない・標的を探さない」にできた。
 * ユーザーの runClient(run-client.bat)には操作口が付かないので、そこでは名札は効かない。
 */
public final class DevNames {

    private static final boolean DEV = Boolean.getBoolean("ov.devbridge");

    private DevNames() {
    }

    /** 名札が効く環境か(サーバー側の判定。クライアントの世界は GameTest でない扱い)。 */
    public static boolean enabled(Entity e) {
        return DEV || e.level().getServer() instanceof GameTestServer;
    }

    /** 確かめ用の名前(名札が効かない・名前が無いときは "")。 */
    public static String of(Entity e) {
        if (!e.hasCustomName() || !enabled(e)) return "";
        return e.getCustomName().getString();
    }

    /** 名前が {@code nostroll} で始まる(うろつかない・自分で標的を探さない)。 */
    public static boolean noStroll(Entity e) {
        return of(e).startsWith("nostroll");
    }

    /** 名前に {@code word} を含む。 */
    public static boolean has(Entity e, String word) {
        return of(e).contains(word);
    }
}
