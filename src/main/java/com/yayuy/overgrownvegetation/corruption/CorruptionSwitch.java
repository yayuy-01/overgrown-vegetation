package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * 汚染のオン・オフ(2026-10-10、ユーザー指定)。{@code /ov corruption true|false}。
 * 「false の場合はすべての汚染行為が止まるように。アルボラの死や胞子爆弾、死体や汚染シダによる汚染などすべて」。
 *
 * <p><b>オフで止めるもの</b>(汚染されたものが広がる・新しく生まれるもの全部):
 * <ul>
 *     <li>ブロックの汚染・苔の記録で覆う・スポナーの汚染 —— 共通の関門 {@link CorruptionRegistry#canCorrupt} と {@link CorruptionRegistry#corrupt}。
 *     アルボラ・ヴェロフィルの死、胞子爆弾、寄生クリーパー、胞子をまく Mob、地割れ、寄生植物装備の足元もここで止まる。</li>
 *     <li>汚染シダ・汚染死体・苗床の汚染の節の侵食({@link CorruptionSpreader} の入口。死体ブロックは侵食も孵化も待つ)。新しい汚染シダが生まれる({@code BlightFernPlacement})。</li>
 *     <li>バイオームの書き換え({@code OvergrownBiomeConverter} の入口)、台帳の広がり(オーバーワールド・ネザー)・台帳のチャンクの塗り・極相の昇格と深い変換、
 *     過繁茂の縁の広がり・建物の侵食・胞子濃度のバイオームの書き換え、湿地の地形・倒木、ポータルを越えた侵食・エンドの本島の塗り、苗床の根、
 *     胞子爆弾が機関砲台・忌避灯を覆う。</li>
 * </ul>
 * <b>止めないもの</b>(Claude 仮決め。ユーザーは境目を「どれでもない」と答えた): 苗木・巨大樹の成長、汚染済みの地面の植物・土から草への変化、
 * 感染死で死体ブロック・ネザーの媒介種になること(死体は置かれるが、侵食も孵化も止まって待つ)、Mob の湧き、フェーズの進行(別の切り替え)、TD の崖の橋。
 *
 * <p><b>途中の作業は捨てずに止める</b>(ユーザー指定「止めて true で再開」)。台帳のチャンクの塗り・極相の深い変換・エンドの塗り・ポータル越え・
 * 死んだ Mob の爆発の順番待ちは残り、オンに戻すと続きから進む。台帳の広がりはオフの間の分を後から取り戻さない(フェーズを止めたときと同じ)。
 *
 * <p>値はワールドごと(オーバーワールドの {@code data/})、<b>既定はオン</b>。画面には出さない(ユーザー指定: コマンドだけ)。
 * 汚染の処理はブロック 1 つごとに何千回も読むので、本体は static の写し {@link #isOn()}(別スレッドの台帳の計算からも読める)。
 */
public final class CorruptionSwitch extends SavedData {

    public static final String ID = "overgrown_vegetation_corruption_switch";

    private static final Factory<CorruptionSwitch> FACTORY = new Factory<>(CorruptionSwitch::new, CorruptionSwitch::load);

    /** 今のワールドの値の写し(サーバーが無いとき・読み込む前はオン)。 */
    private static volatile boolean on = true;

    private boolean enabled = true;

    private CorruptionSwitch() {
    }

    /** 汚染が動いてよいか(どのスレッドからでも)。 */
    public static boolean isOn() {
        return on;
    }

    /** 汚染が止まっているか({@code !isOn()})。 */
    public static boolean isOff() {
        return !on;
    }

    /** このワールドの値を書いて写しを合わせる(コマンド・テスト)。 */
    public static void set(MinecraftServer server, boolean enabled) {
        CorruptionSwitch data = get(server);
        if (data.enabled != enabled) {
            data.enabled = enabled;
            data.setDirty();
        }
        on = enabled;
        OvergrownVegetation.LOGGER.info("汚染を{}にした", enabled ? "オン" : "オフ");
    }

    /** このワールドの値(保存されている方)。 */
    public static boolean enabled(MinecraftServer server) {
        return get(server).enabled;
    }

    private static CorruptionSwitch get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putBoolean("enabled", enabled);
        return tag;
    }

    private static CorruptionSwitch load(CompoundTag tag, HolderLookup.Provider registries) {
        CorruptionSwitch data = new CorruptionSwitch();
        data.enabled = !tag.contains("enabled") || tag.getBoolean("enabled");
        return data;
    }

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
    public static final class Events {

        /** オーバーワールドの読み込みで写しを作る(スポーンのチャンクを作るより前)。 */
        @SubscribeEvent
        public static void onLevelLoad(LevelEvent.Load event) {
            if (event.getLevel() instanceof ServerLevel level && level.dimension() == Level.OVERWORLD) {
                on = enabled(level.getServer());
            }
        }

        @SubscribeEvent
        public static void onServerStopped(ServerStoppedEvent event) {
            on = true;
        }

        private Events() {
        }
    }
}
