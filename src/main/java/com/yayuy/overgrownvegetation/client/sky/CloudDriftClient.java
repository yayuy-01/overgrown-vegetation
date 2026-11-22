package com.yayuy.overgrownvegetation.client.sky;

import com.yayuy.overgrownvegetation.network.CloudDriftPayload;
import com.yayuy.overgrownvegetation.sky.CloudField;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;

/**
 * サーバーから届く雲を流した距離(2026-11-08 雲の段階 1「風を 1 つに」。{@link CloudDriftPayload}、元は {@code sky.CloudDrift})。
 * 届いた時点の距離と速さから、次が届くまで速さで先読みする。届くたびの食い違いは一度に跳ばさず {@link #BLEND} tick かけて寄せる
 * (5 秒で風はほとんど変わらないので、ふつうは 1 ブロック未満)。届く前・この MOD の無いサーバーでは、前の時刻だけの風({@link CloudField#LEGACY})。
 * サーバーを抜けたら忘れる({@link #onLoggingOut}。次に入ったワールドに届かなければ、前の風へ戻る)。
 */
@EventBusSubscriber(modid = com.yayuy.overgrownvegetation.OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class CloudDriftClient implements CloudField.Drift {

    static final CloudDriftClient INSTANCE = new CloudDriftClient();
    /** 食い違いを寄せる長さ(tick)・先読みの上限(tick)。 */
    static final double BLEND = 60.0;
    static final double MAX_AHEAD = 400.0;
    /** 食い違いがこれより大きければ寄せずに跳ぶ(テレポートではなく、入り直し・サーバーの巻き戻し)。 */
    static final double SNAP = 256.0;

    private ClientLevel level;
    private long baseTime;
    private double baseX;
    private double baseZ;
    private double velX;
    private double velZ;
    private double errX;
    private double errZ;
    private boolean has;

    public static void accept(CloudDriftPayload p) {
        CloudField.setSeed(p.seed());
        // 雲の形もサーバーの値で(11-08 仕上げ 8)。
        com.yayuy.overgrownvegetation.sky.CloudShape.setOverride(new com.yayuy.overgrownvegetation.sky.CloudShape(p.style(), p.coverage(), p.floorThickness(), p.floorFlatness(),
                p.storms(), p.stormChance(), p.giantChance(), p.footScale(), p.syncRain(), p.rainDays(), p.thunderShare(), p.preRainTicks(), p.realistic(), p.stormShare(), p.big(), p.nest()));
        // 雨の予定で天気を決めている間か(11-09 段階 2a-5。雨の前の曇りはその間だけ)。
        com.yayuy.overgrownvegetation.sky.CloudWeather.setClientActive(p.weatherActive());
        INSTANCE.receive(p);
        CloudField.setDrift(INSTANCE);
    }

    private synchronized void receive(CloudDriftPayload p) {
        ClientLevel now = Minecraft.getInstance().level;
        if (has && now == level) {
            // 今見せている値と、届いた値の差(届いた時刻で比べる)。
            double t = p.gameTime();
            double ex = rawX(t) + errAt(errX, t) - p.offsetX();
            double ez = rawZ(t) + errAt(errZ, t) - p.offsetZ();
            if (Math.abs(ex) > SNAP || Math.abs(ez) > SNAP) {
                ex = 0.0;
                ez = 0.0;
            }
            errX = ex;
            errZ = ez;
        } else {
            errX = 0.0;
            errZ = 0.0;
        }
        level = now;
        baseTime = p.gameTime();
        baseX = p.offsetX();
        baseZ = p.offsetZ();
        velX = p.velX();
        velZ = p.velZ();
        has = true;
    }

    private double ahead(double t) {
        return Math.max(-MAX_AHEAD, Math.min(MAX_AHEAD, t - baseTime));
    }

    private double rawX(double t) {
        return baseX + velX * ahead(t);
    }

    private double rawZ(double t) {
        return baseZ + velZ * ahead(t);
    }

    private double errAt(double err, double t) {
        double k = 1.0 - Math.max(0.0, Math.min(1.0, (t - baseTime) / BLEND));
        return err * k;
    }

    @Override
    public synchronized double x(double t) {
        return has ? rawX(t) + errAt(errX, t) : CloudField.legacyOffsetX(t);
    }

    @Override
    public synchronized double z(double t) {
        return has ? rawZ(t) + errAt(errZ, t) : CloudField.legacyOffsetZ(t);
    }

    /** 今の雲の速さ(ブロック/tick。届いていなければ NaN)。 */
    static synchronized double velX() {
        return INSTANCE.has ? INSTANCE.velX : Double.NaN;
    }

    static synchronized double velZ() {
        return INSTANCE.has ? INSTANCE.velZ : Double.NaN;
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
    }

    /** ワールドを出たとき(次の世界では届くまで前の風)。 */
    static synchronized void clear() {
        INSTANCE.has = false;
        INSTANCE.level = null;
        CloudField.setDrift(null);
        CloudField.setSeed(CloudField.DEFAULT_SEED);
        com.yayuy.overgrownvegetation.sky.CloudShape.setOverride(null);
        com.yayuy.overgrownvegetation.sky.CloudWeather.setClientActive(false);
    }

    private CloudDriftClient() {
    }
}
