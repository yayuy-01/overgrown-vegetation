package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownClientConfig;
import com.yayuy.overgrownvegetation.network.PhyllosensGazePayload;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 監視塔フィロセンスに見られているときの画面の縁(2026-11-14。Vault「Mob の能力/フィロセンス」の「見られているときの画面の縁の効果」と
 * 「見た目の決定」の節)。
 *
 * <h2>土台(このクラスが持つ。見た目の作り込みは Fable)</h2>
 * サーバーの知らせ({@link PhyllosensGazePayload}: 見え始め・見えている・印・切れた)から、見られ方の時間の流れを持つ。読み口:
 * <ul>
 *   <li>{@link #visible()} 今、視線が通っているか(どれか 1 本の塔から)</li>
 *   <li>{@link #seenTicks()} 見え始めてから(続けて見られている長さ。目が出そろう 6 秒に使う)</li>
 *   <li>{@link #markTick()}・{@link #ticksSinceMark()} 印の瞬間(仕掛け ①「一斉に瞬いてこちらを向く」)と、そこから続けて見られている長さ
 *   (慣れ: 約 10 秒で目を半分へ)。視線が切れて見え直したら印は消え、2 秒後にまた来る</li>
 *   <li>{@link #lostTicks()} 視線が切れてから(仕掛け ④「隠れた後も残る目」・閉じきるまで 4 秒)。見られていなければ -1</li>
 *   <li>{@link #watchers()} 今見ている塔の数・{@link #strength(float)} 強さ 0〜1(見え始め 0 → 印で 1、切れたら {@link #FADE_PER_TICK} ずつ引く)</li>
 *   <li>強さの設定: {@link #eyeScale()}(目の数の倍率 0〜1)・{@link #innerEyes()}(視界の内側に開く目)・{@link #pulse()}(鼓動の縁)。
 *   クライアントの設定 [watched](見た目の設定の画面「見られているとき」)</li>
 * </ul>
 *
 * <h2>見た目の差し替え</h2>
 * {@link #render} は {@link PhyllosensGazeEyes} へ渡すだけ(11-14 Fable: ユーザーの目の絵・仕掛け 6 つ・慣れ。仮の値もそこ。F1 では出さない)。
 * 絵は素材フォルダ {@code textures/gui/phyllosens_gaze_eyes.png}。時間の流れは上の読み口だけを使う(tick は {@link #now()})。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, value = Dist.CLIENT)
public final class PhyllosensGazeEdge {

    /** 知らせが来なくなって「切れた」とみなすまで(塔は 5 tick ごとに送る)・強さを引く/上げる速さ【仮】(1 → 0 を 1 秒)。 */
    static final int TIMEOUT_TICKS = 12;
    static final float FADE_PER_TICK = 0.05F, RISE_PER_TICK = 0.25F;

    /** 見ている塔ごとの {見え始めの tick, 最後に知らせが来た tick, 強さ}。 */
    private static final Map<Integer, float[]> TOWERS = new HashMap<>();
    private static long now;
    private static long seenSince = -1L, markTick = -1L, lostTick = -1L;
    private static float value, value0;

    private PhyllosensGazeEdge() {
    }

    /** サーバーから(メインスレッドで)。 */
    public static void receive(PhyllosensGazePayload p) {
        switch (p.event()) {
            case PhyllosensGazePayload.START, PhyllosensGazePayload.SEEN, PhyllosensGazePayload.MARK -> {
                float[] t = TOWERS.get(p.tower());
                if (t == null) {
                    t = new float[]{now, now, 0};
                    TOWERS.put(p.tower(), t);
                }
                t[1] = now;
                t[2] = Math.max(p.event() == PhyllosensGazePayload.START ? 0.0F : t[2], p.strength());
                // 知らせが途切れて(通信の詰まり・時間の止まったサーバー)忘れた後でも、サーバーの数えた見え始め・印からの tick で戻す。
                // 印の知らせは 1 回しか来ないので、これが無いと見え直した扱いのまま印が戻らなかった(11-14 Fable の指摘・tick freeze で再現)
                if (seenSince < 0L) {
                    seenSince = p.sinceSeen() >= 0 ? now - p.sinceSeen() : now;
                    lostTick = -1L;
                    markTick = -1L;
                }
                if (markTick < 0L) {
                    if (p.event() == PhyllosensGazePayload.MARK) markTick = now;
                    else if (p.sinceMark() >= 0) markTick = now - p.sinceMark();
                }
            }
            case PhyllosensGazePayload.LOST -> {
                if (TOWERS.remove(p.tower()) != null && TOWERS.isEmpty()) lose();
            }
            default -> {
            }
        }
    }

    private static void lose() {
        if (seenSince >= 0L) lostTick = now;
        seenSince = -1L;
        markTick = -1L;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        // ひとり用で一時停止の間はサーバーも止まって知らせが来ないので、この時計も止める(止めないと「切れた」扱いになる)
        if (Minecraft.getInstance().isPaused()) return;
        now++;
        value0 = value;
        if (Minecraft.getInstance().player == null) {
            TOWERS.clear();
            seenSince = markTick = lostTick = -1L;
            value = value0 = 0.0F;
            return;
        }
        float target = 0.0F;
        for (Iterator<float[]> it = TOWERS.values().iterator(); it.hasNext(); ) {
            float[] t = it.next();
            if (now - (long) t[1] > TIMEOUT_TICKS) {
                it.remove();
                continue;
            }
            target = Math.max(target, t[2]);
        }
        if (TOWERS.isEmpty() && seenSince >= 0L) lose();
        value += Mth.clamp(target - value, -FADE_PER_TICK, RISE_PER_TICK);
    }

    // ------------------------------------------------------------------ 読み口

    /** クライアントの tick(この画面効果の時計)。 */
    public static long now() {
        return now;
    }

    public static boolean visible() {
        return seenSince >= 0L;
    }

    /** 今見ている塔の数。 */
    public static int watchers() {
        return TOWERS.size();
    }

    /** 見え始めてから(見られていなければ -1)。 */
    public static long seenTicks() {
        return seenSince < 0L ? -1L : now - seenSince;
    }

    /** 印の瞬間の tick(この見え始めでまだなら -1)。 */
    public static long markTick() {
        return markTick;
    }

    /** 印から続けて見られている長さ(まだ・見られていなければ -1)。 */
    public static long ticksSinceMark() {
        return markTick < 0L ? -1L : now - markTick;
    }

    /** 視線が切れてから(見られている間・一度も見られていなければ -1)。 */
    public static long lostTicks() {
        return lostTick < 0L || seenSince >= 0L ? -1L : now - lostTick;
    }

    /** 今の強さ(0〜1、描画の補間)。 */
    public static float strength(float partialTick) {
        return Mth.lerp(partialTick, value0, value);
    }

    /** 設定: 目の数の倍率(0〜1)。 */
    public static float eyeScale() {
        return OvergrownClientConfig.CLIENT.gazeEyeScale.get() / 100.0F;
    }

    /** 設定: 視界の内側(照準の近く)に一瞬開く目。 */
    public static boolean innerEyes() {
        return OvergrownClientConfig.CLIENT.gazeInnerEyes.get();
    }

    /** 設定: 鼓動のように脈打つ縁の暗さ。 */
    public static boolean pulse() {
        return OvergrownClientConfig.CLIENT.gazePulse.get();
    }

    // ------------------------------------------------------------------ 描画

    static void render(GuiGraphics g, DeltaTracker delta) {
        // 見た目は PhyllosensGazeEyes(ユーザーの目の絵・仕掛け 6 つ・慣れ。11-14 Fable)。仮の値もそこにまとめてある
        PhyllosensGazeEyes.render(g, delta);
    }

    /** GUI の層の登録(MOD バス)。 */
    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class Layers {
        @SubscribeEvent
        public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
            // 照準のすぐ下 = 画面の効果(CAMERA_OVERLAYS の上に足される暴露量のもや など)より上、照準・ホットバーより下。
            // CAMERA_OVERLAYS の上に足すと、もやとの順が登録の順(決まらない)で入れ替わり、もやが目より上に来ることがあった(11-14)
            event.registerBelow(VanillaGuiLayers.CROSSHAIR, OvergrownVegetation.id("phyllosens_gaze_edge"), PhyllosensGazeEdge::render);
        }

        private Layers() {
        }
    }
}
