package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.horde.ClientHorde;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * 前触れ: 発光が消えていく(2026-10-16 ユーザー指定「光と結びつける遊びの仕組み ① 発光が消えていく = 前触れ」。相手は「今は TD だけ」)。
 *
 * <p>TD の侵攻の群れ(行進中の仮想の群れ、{@link ClientHorde})が近づくほど、まわりの夜の発光(地脈・結晶・光るツタ・胞子嚢のにじみ、
 * {@link LeyNightGlow})が<b>遠くの方から一つずつ</b>消えていく。群れが {@link #FAR} ブロックに入ると始まり、{@link #NEAR}(群れが霧の外から
 * 実体になって現れるあたり)で周り全部が消える。群れが実体になって一覧から消えた後も {@link #HOLD_TICKS} は暗いまま保ち、その後ゆっくり戻る
 * (戦っている最中に光が戻らないように。次の群れが来ればまた暗くなる)。見た目だけで、明るさ(ブロックの光)は変えない。
 *
 * <p>数値は全部 Claude の仮決め。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class TdOmen {

    /** 前触れが始まる距離と、周り全部が消える距離(群れの中心から水平に、ブロック)。 */
    static final double FAR = 320.0;
    static final double NEAR = 110.0;
    /** 消えていく速さ(1 tick に、0〜1 の強さをどれだけ上げるか。全部消えるまで約 12 秒)と、戻る速さ(約 33 秒)。 */
    static final float RISE = 0.004F;
    static final float FALL = 0.0015F;
    /** 群れが見えなくなってから暗いまま保つ時間(tick)。 */
    static final int HOLD_TICKS = 900;
    /** 1 つの光が消えきるまでの強さの幅。 */
    static final float SPAN = 0.08F;

    private static float omen;
    private static long lastNear = Long.MIN_VALUE;
    private static long ticks;

    /** 前触れの強さ(0 = 無し、1 = 周り全部が消えている)。 */
    public static float strength() {
        return omen;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) {
            omen = 0.0F;
            return;
        }
        if (mc.isPaused()) {
            return;
        }
        ticks++;
        double d = ClientHorde.nearestDistance(level, mc.player.position());
        float target = Double.isFinite(d) ? (float) Mth.clamp((FAR - d) / (FAR - NEAR), 0.0, 1.0) : 0.0F;
        if (target > 0.0F) {
            lastNear = ticks;
        }
        if (target >= omen) {
            omen = Math.min(target, omen + RISE);
        } else if (ticks - lastNear > HOLD_TICKS) {
            omen = Math.max(target, omen - FALL);
        }
    }

    /**
     * カメラから {@code dist} ブロックの光が前触れで消えている度合い(0〜1)。遠い光ほど早く消える(順番の 3/4 は距離、1/4 は場所のハッシュ
     * {@code hash01} で、同じ距離の光もそろわず一つずつ)。{@code range} は光を描く範囲(ブロック)。
     */
    public static float darkAt(double dist, float hash01, double range) {
        if (omen <= 0.0F) {
            return 0.0F;
        }
        float near01 = 1.0F - (float) Mth.clamp(dist / range, 0.0, 1.0);
        float order = 0.04F + 0.86F * (0.75F * near01 + 0.25F * hash01);
        return CanopyLight.smooth(Mth.clamp((omen - order) / SPAN + 0.5F, 0.0F, 1.0F));
    }

    /** 開発用(テストクライアント): 前触れの強さを直に決める(群れを出さずに見た目を確かめる)。次の tick からは群れの距離で動く。 */
    public static void forceForDev(float value) {
        omen = Mth.clamp(value, 0.0F, 1.0F);
        lastNear = ticks;
    }

    private TdOmen() {
    }
}
