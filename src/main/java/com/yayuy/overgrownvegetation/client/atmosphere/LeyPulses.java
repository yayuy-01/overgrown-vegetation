package com.yayuy.overgrownvegetation.client.atmosphere;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yayuy.overgrownvegetation.data.AtmosphereGiantTree;
import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;

/**
 * <b>夜に巨大樹の幹を昇る地脈の光</b>(2026-11-06 巨大樹の改善案 ③、ユーザー採用。数は仮決め、節 {@code giantTree.leyPulse})。
 * ハイヴノードは地脈を蓄える役 —— その貯まり具合を外から読み取れる手がかりにする(夜の遠くからの目印にも)。
 *
 * <p>貯蓄の量は {@link GiantTrees.Info#ley()}(サーバーの {@code GiantTreeSync}。<b>ハイヴノードの貯蓄はまだ無いので仮の物差し</b>:
 * 育ち具合 × 地脈の線への近さ)。多いほど明るく・速く昇る。止めたワールド・ハイヴノードを倒された木は 0 = 出さない。
 *
 * <p>描き方: 幹の断面({@link GiantTrees.Profile})の {@code veins} 方向に、樹皮の表面に沿った細い帯(外向きの面。カメラを向けない = 幹にめり込まない)を張り、
 * 根元から幹の先へ昇る長さ {@code length} の脈を足し算の光({@link AddLight})で描く。脈の後ろは尾を引いて消え、通り道にはかすかな光({@code trace})。
 * 奥の側の筋は幹に隠れる(深さを読む)。昼は出さない: 夕方 {@code duskStart} から灯り、明け方 {@code dawnEnd} で消える(時刻で見る。
 * 空の暗さでは見ない —— 嵐の昼に灯ってしまうので)。周りを照らす光(点光源・ブロックの光)は足さない。描画距離の外(幹が描かれない所)では描かない。
 */
final class LeyPulses {

    private static final float LIFT = 0.12F;
    private static final double WIDEN_FROM = 120.0;
    static boolean devOff;
    /** 開発用: 地脈の度合いの上書き(−1 = しない)・夜の度合いの上書き(−1 = しない)。 */
    static float devLey = -1.0F;
    static float devNight = -1.0F;
    private static int drawn;

    private LeyPulses() {
    }

    static int drawn() {
        return drawn;
    }

    static void render(RenderLevelStageEvent event) {
        drawn = 0;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        AtmosphereGiantTree.LeyPulseParams p = DesignData.atmosphere().giantTree().leyPulse();
        if (devOff || !p.enabled() || level == null || ForestPresence.underground() > 0.5F) {
            return;
        }
        float night = devNight >= 0.0F ? devNight : p.night(level.getDayTime());
        if (night <= 0.0F) {
            return;
        }
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 eye = event.getCamera().getPosition();
        double reach = Math.min(p.distance(), mc.options.getEffectiveRenderDistance() * 16.0 - 8.0);
        double seconds = Atmosphere.time(partialTick) / 20.0;
        MultiBufferSource.BufferSource buffers = AddLight.buffers();
        VertexConsumer c = null;
        for (GiantTrees.Info t : GiantTrees.all()) {
            if (!t.scanned() || t.profile() == null) {
                continue;
            }
            float ley = devLey >= 0.0F ? devLey : t.ley();
            if (!(ley > 0.0F)) {
                continue;
            }
            double dx = t.axisX() - eye.x;
            double dz = t.axisZ() - eye.z;
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d > reach) {
                continue;
            }
            // 遠さで薄れる(描く遠さの終わり 3 割・バニラの霧の終わりの手前・森の中の霞)
            float fade = (float) (1.0 - smooth((d - reach * 0.7) / (reach * 0.3)));
            if (MistView.hazeActive()) {
                // 森の中の霞の間だけ(外ではバニラの霧の終わりが印で手前に寄っていて、遠くの光が消えた)
                fade *= 1.0F - 0.6F * MistView.fogAmount((float) d); // 光は霞を少し透かす(全部消すと霞の中の幹の光が 1 本も見えなかった)
            }
            float bright = p.alpha() * Math.min(1.0F, ley) * night * fade;
            if (bright <= 0.004F) {
                continue;
            }
            if (c == null) {
                c = buffers.getBuffer(AddLight.renderType());
            }
            drawTree(c, t, ley, bright, p, eye, seconds, d);
            drawn++;
        }
        if (c != null) {
            buffers.endBatch(AddLight.renderType());
        }
    }

    private static void drawTree(VertexConsumer c, GiantTrees.Info t, float ley, float bright, AtmosphereGiantTree.LeyPulseParams p, Vec3 eye, double seconds, double distance) {
        // 遠くでも筋が画面で細くなりすぎないよう、{@value #WIDEN_FROM} ブロックより遠くは距離に比例して太くする
        double widen = Math.max(1.0, distance / WIDEN_FROM);
        GiantTrees.Profile prof = t.profile();
        int base = t.baseY();
        int top = t.topY();
        double height = top - base;
        double period = Mth.lerp(Math.min(1.0F, ley), p.periodSlow(), p.periodFast());
        double len = p.length();
        float[] col = p.color();
        for (int i = 0; i < p.veins(); i++) {
            int vein = Math.round(i * GiantTrees.Profile.VEINS / (float) p.veins()) % GiantTrees.Profile.VEINS;
            double angle = GiantTrees.Profile.angle(vein);
            double ux = Math.cos(angle);
            double uz = Math.sin(angle);
            // 筋ごとにずらす(同時に昇らない)。木ごとにも
            double phase = frac(i * 0.618 + (t.originX() * 0.013 + t.originZ() * 0.007));
            double cycle = frac(seconds / period + phase);
            // 脈の頭(根元の下 len から幹の先の上まで動かして、出入りをなめらかに)
            double head = base - len + cycle * (height + 2.0 * len);
            double from = Math.max(base, Math.min(head - len, top));
            double to = Math.max(base, Math.min(head, top));
            // 通り道のかすかな光(幹全体に 1 本)
            if (p.trace() > 0.0F) {
                strip(c, prof, vein, ux, uz, base, top, p.width() * 0.6 * widen, eye, col, bright * p.trace(), false, 0.0, 0.0);
            }
            if (to > from) {
                strip(c, prof, vein, ux, uz, from, to, p.width() * widen, eye, col, bright, true, head, len);
            }
        }
    }

    /**
     * 樹皮に沿った帯を y0〜y1 に描く。pulse なら脈(頭で最も明るく、len ブロック後ろで 0 へ)、でなければ一様の明るさ。
     */
    private static void strip(VertexConsumer c, GiantTrees.Profile prof, int vein, double ux, double uz, double y0, double y1,
                              double width, Vec3 eye, float[] col, float bright, boolean pulse, double head, double len) {
        double step = GiantTrees.Profile.STEP;
        double hx = -uz * width * 0.5;
        double hz = ux * width * 0.5;
        double prevY = Double.NaN;
        double prevX = 0.0;
        double prevZ = 0.0;
        float prevA = 0.0F;
        for (double y = y0; ; y = Math.min(y1, y + step)) {
            double r = prof.radius(vein, y);
            boolean ok = !Double.isNaN(r);
            double x = 0.0;
            double z = 0.0;
            float a = 0.0F;
            if (ok) {
                x = prof.centerX(y) + ux * (r + LIFT);
                z = prof.centerZ(y) + uz * (r + LIFT);
                a = bright;
                if (pulse) {
                    double k = 1.0 - (head - y) / len; // 頭 1 → 尾 0
                    a = (float) (bright * Math.pow(Math.max(0.0, Math.min(1.0, k)), 1.6));
                }
            }
            if (ok && !Double.isNaN(prevY) && (a > 0.002F || prevA > 0.002F)) {
                float r0 = col[0];
                float g0 = col[1];
                float b0 = col[2];
                c.addVertex((float) (prevX - hx - eye.x), (float) (prevY - eye.y), (float) (prevZ - hz - eye.z)).setColor(r0, g0, b0, prevA);
                c.addVertex((float) (prevX + hx - eye.x), (float) (prevY - eye.y), (float) (prevZ + hz - eye.z)).setColor(r0, g0, b0, prevA);
                c.addVertex((float) (x + hx - eye.x), (float) (y - eye.y), (float) (z + hz - eye.z)).setColor(r0, g0, b0, a);
                c.addVertex((float) (x - hx - eye.x), (float) (y - eye.y), (float) (z - hz - eye.z)).setColor(r0, g0, b0, a);
            }
            if (ok) {
                prevY = y;
                prevX = x;
                prevZ = z;
                prevA = a;
            } else {
                prevY = Double.NaN;
            }
            if (y >= y1) {
                break;
            }
        }
    }

    private static double frac(double v) {
        return v - Math.floor(v);
    }

    private static double smooth(double x) {
        double t = Math.max(0.0, Math.min(1.0, x));
        return t * t * (3.0 - 2.0 * t);
    }
}
