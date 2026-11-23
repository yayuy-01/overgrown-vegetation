package com.yayuy.overgrownvegetation.difficulty;

import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.OptionalDouble;

/**
 * <b>実験的要素</b>(2026-10-09 ユーザー指定「設定画面に『実験的要素』の項目を用意し、オンにしたときだけ出るように」)の問い合わせ口。
 * 値はワールドごと(Overgrown Vegetation の設定の画面の「実験的要素」の節)、<b>どれも既定はオフ</b>。
 * <b>GameTest のサーバーだけは値が無ければオン</b>(オフの確かめはテストが値を書いて同じ tick で戻す)。
 *
 * <p>2026-11-23 夜のユーザー決定で、スイッチを要素ごとに分けた({@link Feature})。
 * <ul>
 *     <li>{@link Feature#NETHER} — 新しいネザーの汚染(11-12〜。入口は {@code NetherLedger#active} と {@code NetherSporeFieldServer})。
 *     保存の名前は前の 1 つのスイッチと同じ {@code experimental} なので、<b>前の「実験的要素オン」のワールドはネザーのオンとして読む</b>。</li>
 *     <li>{@link Feature#WORLD_EVENTS} — ワールドイベントの日程({@code WorldEvents#onServerTick})。オフでも {@code /ov event} は動く。</li>
 *     <li>{@link Feature#CREEPING_WALL} — 蠢く壁が自然に起きる({@code CreepingWallNatural#allowed})。オフでも {@code /ov wall} は動く。</li>
 *     <li>{@link Feature#RESEARCH} — 研究台などの研究の要素。オフなら研究のブロック・アイテムは作れず置けず、研究で開く作り方は最初から全部開く。</li>
 *     <li>{@link Feature#ARMOR_ATTACHMENTS} — 防具の付け物(口覆いの布以外)と付け物の台。</li>
 * </ul>
 */
public final class ExperimentalFeatures {

    /** 実験的要素の 1 つずつ。 */
    public enum Feature {
        NETHER("nether", DifficultySetting.EXPERIMENTAL_NETHER),
        WORLD_EVENTS("events", DifficultySetting.EXPERIMENTAL_EVENTS),
        CREEPING_WALL("wall", DifficultySetting.EXPERIMENTAL_WALL),
        RESEARCH("research", DifficultySetting.EXPERIMENTAL_RESEARCH),
        ARMOR_ATTACHMENTS("attachments", DifficultySetting.EXPERIMENTAL_ATTACHMENTS);

        private final String id;
        private final DifficultySetting setting;

        Feature(String id, DifficultySetting setting) {
            this.id = id;
            this.setting = setting;
        }

        /** {@code /ov experimental <id>} の名前。 */
        public String id() {
            return id;
        }

        public DifficultySetting setting() {
            return setting;
        }
    }

    /** クライアントが知っている値(ログイン時と変わったときにサーバーから届く。1 人プレイでも同じ)。ビットは {@link Feature#ordinal()}。 */
    private static volatile int clientMask;

    /** このワールドでその要素がオンか(値が無ければオフ、GameTest のサーバーだけはオン)。 */
    public static boolean enabled(MinecraftServer server, Feature feature) {
        OptionalDouble v = WorldDifficultyData.get(server).override(feature.setting());
        return v.isPresent() ? v.getAsDouble() >= 0.5D : server instanceof GameTestServer;
    }

    /**
     * サーバーのワールドならそのワールドの値、クライアントのワールドならサーバーから届いた値。
     * 研究の作り方・クリエイティブのタブのように両方で読む所はこれを使う。
     */
    public static boolean enabled(Level level, Feature feature) {
        if (level.isClientSide()) return clientEnabled(feature);
        MinecraftServer server = level.getServer();
        return server != null && enabled(server, feature);
    }

    /** 動いているサーバーがあればその値(無ければクライアントが知っている値)。レベルを持たない所(タブ・説明)から。 */
    public static boolean enabledAnywhere(Feature feature) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null && server.isSameThread()) return enabled(server, feature);
        return clientEnabled(feature);
    }

    public static boolean clientEnabled(Feature feature) {
        return (clientMask & (1 << feature.ordinal())) != 0;
    }

    /** サーバーから届いた値をクライアントに覚える。変わったら true。 */
    public static boolean acceptClient(int mask) {
        boolean changed = clientMask != mask;
        clientMask = mask;
        return changed;
    }

    /** 送る値(オンの要素のビット)。 */
    public static int mask(MinecraftServer server) {
        int m = 0;
        for (Feature f : Feature.values()) {
            if (enabled(server, f)) m |= 1 << f.ordinal();
        }
        return m;
    }

    /** 新しいネザーの汚染がオンか(11-12 からの口。テストが読む)。 */
    public static boolean enabled(MinecraftServer server) {
        return enabled(server, Feature.NETHER);
    }

    /** 新しいネザーの汚染がオンか(11-12 からの口。{@code NetherLedger#active} が読む)。 */
    public static boolean enabled(Level level) {
        return enabled(level, Feature.NETHER);
    }

    /** このワールドの値を書く(テスト・コマンド用)。 */
    public static void set(MinecraftServer server, Feature feature, boolean on) {
        WorldDifficultyData.get(server).set(feature.setting(), on ? 1.0D : 0.0D);
        ExperimentalSync.sendAll(server);
    }

    /** ワールドの値を消す(既定に戻す。テスト用)。 */
    public static void reset(MinecraftServer server, Feature feature) {
        WorldDifficultyData.get(server).reset(feature.setting());
        ExperimentalSync.sendAll(server);
    }

    /** 全部をまとめて書く({@code /ov experimental on|off})。 */
    public static void setAll(MinecraftServer server, boolean on) {
        for (Feature f : Feature.values()) WorldDifficultyData.get(server).set(f.setting(), on ? 1.0D : 0.0D);
        ExperimentalSync.sendAll(server);
    }

    /** 新しいネザーの汚染の値を書く(11-12 からの口。テスト用)。 */
    public static void set(MinecraftServer server, boolean on) {
        set(server, Feature.NETHER, on);
    }

    /** 新しいネザーの汚染の値を消す(11-12 からの口。テスト用)。 */
    public static void reset(MinecraftServer server) {
        reset(server, Feature.NETHER);
    }

    private ExperimentalFeatures() {
    }
}
