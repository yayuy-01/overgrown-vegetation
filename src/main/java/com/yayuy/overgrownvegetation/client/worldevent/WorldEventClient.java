package com.yayuy.overgrownvegetation.client.worldevent;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.network.WorldEventPayload;
import com.yayuy.overgrownvegetation.worldevent.WorldEventKind;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/**
 * サーバーから届く「今のワールドイベント」の受け口(2026-11-16 world-events。{@link WorldEventPayload})。値を持つだけで、描くのは見た目の側
 * (「自然環境とシェーダーの調整」から立てる Fable のセッション)。
 *
 * <ul>
 *   <li>{@link #kind()} = 起きている種(無ければ null)。{@link #is(WorldEventKind)} で 1 つだけ見る。</li>
 *   <li>{@link #progress(float)} = 進み 0〜1(始まり 0・終わり 1。クライアントの dayTime から毎フレーム出すので滑らか)。
 *       立ち上がり・消え方(包絡)はかけていない: 見た目の側が progress から作る(例 オーロラは始めの 1 時間で出る)。</li>
 *   <li>{@link #elapsedTicks(float)}・{@link #lengthTicks()} = 始まりからの tick と全体の長さ(日食の真っ暗な 1.5 分のように tick で決めたい物用。
 *       長さの設計値は {@code DesignData.worldEvents().spec(kind)} でも読める)。</li>
 *   <li>{@link #changeListener} = 種が変わった(始まった・終わった)ときに呼ぶ。既定は何もしない。</li>
 *   <li>確かめ用の上書き {@link #debugOverride}: 上書き中は届いた値より優先。サーバー側からは {@code /ov event start <名前> [進み]}。</li>
 * </ul>
 * オーバーワールド以外ではサーバーが「何も起きていない」を送る。ログアウトで消す。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, value = Dist.CLIENT)
public final class WorldEventClient {

    private static WorldEventPayload last = WorldEventPayload.NONE;
    private static boolean received;
    @Nullable
    private static WorldEventKind overrideKind;
    private static float overrideProgress;

    /** 種が変わったときに呼ぶ(新しい値。終わったときは kind -1)。見た目の側が差し替える。 */
    public static volatile Consumer<WorldEventPayload> changeListener = p -> {
    };

    public static void accept(WorldEventPayload payload) {
        boolean changed = payload.kind() != last.kind() || payload.start() != last.start();
        last = payload;
        received = true;
        if (changed) changeListener.accept(payload);
    }

    /** 1 通でも届いたか(上書き中は true)。 */
    public static boolean received() {
        return received || overrideKind != null;
    }

    /** 起きている種(無ければ null)。 */
    @Nullable
    public static WorldEventKind kind() {
        if (overrideKind != null) return overrideKind;
        return last.eventKind();
    }

    public static boolean is(WorldEventKind k) {
        return kind() == k;
    }

    /** 進み 0〜1(起きていなければ 0)。partialTick は描画の途中の tick(0〜1)。 */
    public static float progress(float partialTick) {
        if (overrideKind != null) return overrideProgress;
        long len = last.end() - last.start();
        if (last.eventKind() == null || len <= 0) return 0.0F;
        double p = (clientDayTime() + partialTick - last.start()) / (double) len;
        return (float) Math.max(0.0D, Math.min(1.0D, p));
    }

    /** 始まりからの tick(起きていなければ 0)。 */
    public static float elapsedTicks(float partialTick) {
        return progress(partialTick) * lengthTicks();
    }

    /** 全体の長さ(tick)。上書き中・起きていなければ設計値か 0。 */
    public static long lengthTicks() {
        if (overrideKind != null) {
            return com.yayuy.overgrownvegetation.data.DesignData.worldEvents().spec(overrideKind).length();
        }
        return last.eventKind() != null ? Math.max(0L, last.end() - last.start()) : 0L;
    }

    private static long clientDayTime() {
        var level = Minecraft.getInstance().level;
        return level != null ? level.getDayTime() : last.dayTime();
    }

    /** 確かめ用に上書きする(clearDebugOverride で戻す)。 */
    public static void debugOverride(WorldEventKind kind, float progress) {
        overrideKind = kind;
        overrideProgress = Math.max(0.0F, Math.min(1.0F, progress));
    }

    public static void clearDebugOverride() {
        overrideKind = null;
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        last = WorldEventPayload.NONE;
        received = false;
        overrideKind = null;
    }

    private WorldEventClient() {
    }
}
