package com.yayuy.overgrownvegetation.client.atmosphere;

import com.yayuy.overgrownvegetation.client.atmosphere.sound.WindSoundModel;
import com.yayuy.overgrownvegetation.data.AtmosphereTable;
import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.util.Mth;

/**
 * <b>風の音が鳴っている度合い</b>(0〜1。2026-10-30 夜 第 2 段「動き」。元は parked の {@code LeafSway} の中の gate)。
 * 「揺れるのは風の音が鳴っている間だけ」(2026-10-16、ユーザー「大体 1〜2 分おきに風の音声が流れるようにし、その音声が流れている間だけ
 * 植物群が揺れるように」)を 1 か所で決める所。葉と草の揺れ({@link LeafSway})・太い蔓・落ち葉({@link FallingLeaves})がここだけを読む。
 *
 * <p>風の音と同じ見積もり({@link WindSoundModel}、聞く人のまわりと遠くの梢の音の大きさ)から作るので、音の大きさ・消音の設定には
 * 左右されない(音を切っても植物は風で揺れる)。{@code sway.gateFullLevel}(0.15)の大きさで全開。立ち上がりは {@code riseSeconds}
 * (0.6 秒)、収まりは {@code fallSeconds}(1.3 秒)。雷雨では凪の強さが上がって音が鳴り続けるので、揺れも続く。
 */
public final class WindGate {

    private static WindSoundModel model;
    private static float gate;
    private static float prevGate;

    /** 毎 tick({@link Atmosphere} から。風の場を進めた後)。 */
    static void tick(double x, double z, double time) {
        AtmosphereTable a = DesignData.atmosphere();
        if (model == null) {
            model = new WindSoundModel(a.sound().calmThreshold());
        }
        AtmosphereTable.SwayParams p = a.sway();
        prevGate = gate;
        model.compute(Wind.current(), x, z, time);
        double level = Math.max(model.nearLevel, model.farLevel);
        float t = (float) Mth.clamp(level / p.gateFullLevel(), 0.0, 1.0);
        float target = t * t * (3.0F - 2.0F * t);
        float seconds = target > gate ? p.riseSeconds() : p.fallSeconds();
        gate += (target - gate) * Math.min(1.0F, 1.0F / (seconds * 20.0F));
        if (gate < 0.002F && target == 0.0F) {
            gate = 0.0F;
        }
    }

    static void clear() {
        gate = 0.0F;
        prevGate = 0.0F;
    }

    /** 今の度合い(描画の途中の割合で補う)。 */
    public static float value(float partialTick) {
        return Mth.lerp(partialTick, prevGate, gate);
    }

    private WindGate() {
    }
}
