package com.yayuy.overgrownvegetation.sky;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.network.CloudPuffPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 吐いた雲(2026-10-19。空の巨大生物が雲を吐いて身を隠す技。ユーザー指定 10-17「頭を下げて顎を開き、雲を吐き出す。その雲の中へ体をひねって潜り込む」、
 * 10-19 にユーザーが「雲の作業に口を頼む」を選んだ = 空の巨大生物のセッションからの依頼)。
 *
 * <p>サーバーから {@link #puff} を呼ぶと、その所に一時的な本物の雲(ボクセルの雲と同じ見た目)ができ、オーバーワールドの全員に見える。
 * 雲の場の座標に置くので、ふつうの雲と同じく風で流れる。当たり判定は無く見た目だけ(中に入ると積乱雲と同じ霧)。形・膨らみ方・消え方は {@link #state} と
 * {@link CloudField#blob}(サーバー・クライアントで同じ式。描くのは {@code client/CloudPuffs})。あとからワールドに入った人・オーバーワールドへ来た人にも、
 * まだ消えていない雲を送る。10-19 に膨らむ長さ・消える長さを渡せるようにした(雲の面の盛り上がり {@link CloudSurface#bulge})。数値は仮決め。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class CloudPuffs {
    /** 長さの上下限(tick)。 */
    public static final int LIFE_MIN = 100;
    public static final int LIFE_MAX = 12000;
    /** 膨らむ長さ・消える長さの既定(tick。消える長さは長さの 3 分の 1 まで)。 */
    public static final int GROW_DEFAULT = 80;
    public static final int FADE_DEFAULT = 400;

    private static final List<CloudPuffPayload> ACTIVE = new ArrayList<>();
    /** クライアントが受け取った雲(クライアントのスレッドだけが触る。{@code client/CloudPuffs} が足し、消えたら除く)。 */
    private static final List<CloudPuffPayload> CLIENT = new ArrayList<>();
    private static final Map<Long, List<CloudField.Puff>> SHAPES = new ConcurrentHashMap<>();
    private static final RandomSource RANDOM = RandomSource.create();
    private static int nextId = 1;
    private static final boolean DEV_LOG = Boolean.getBoolean("ov.devbridge");

    /** {@link #puff(ServerLevel, double, double, double, float, float, int, int, int)} を既定の膨らみ方・消え方で。 */
    public static int puff(ServerLevel level, double x, double y, double z, float radius, float height, int life) {
        return puff(level, x, y, z, radius, height, life, GROW_DEFAULT, FADE_DEFAULT);
    }

    /**
     * その所(世界の座標。(x, y, z) = 雲の真ん中)に吐いた雲を出す。オーバーワールドでだけ(ほかの次元では何もしないで 0)。
     *
     * @param radius 横の半径(ブロック。こぶの分だけ 1.3 倍ほどまで広がる)
     * @param height 高さ(ブロック。真ん中から上下に半分ずつ)
     * @param life   長さ(tick)
     * @param grow   膨らむ長さ(tick。真ん中から 0.12 倍 → 1 倍、はじめ速くだんだんゆっくり)
     * @param fade   消える長さ(tick。終わりのこの間で薄れながら 0.9 倍に。長さの 3 分の 1 まで)
     * @return 雲の番号(1 から)
     */
    public static int puff(ServerLevel level, double x, double y, double z, float radius, float height, int life, int grow, int fade) {
        if (level.dimension() != Level.OVERWORLD) {
            return 0;
        }
        long now = level.getGameTime();
        int id = nextId++;
        int l = Math.max(LIFE_MIN, Math.min(LIFE_MAX, life));
        CloudPuffPayload p = new CloudPuffPayload(id, CloudField.fieldX(x, now, 0.0F), y, CloudField.fieldZ(z, now, 0.0F),
                Math.max(40.0F, radius), Math.max(20.0F, height), now, l, Math.max(1, grow), Math.max(1, fade), RANDOM.nextLong());
        ACTIVE.add(p);
        PacketDistributor.sendToPlayersInDimension(level, p);
        if (DEV_LOG) {
            OvergrownVegetation.LOGGER.info("CloudPuffs 吐いた雲 {} 世界 {} {} {} 半径 {} 高さ {} 長さ {} 膨らむ {} 消える {}", id, Math.round(x), Math.round(y), Math.round(z),
                    Math.round(radius), Math.round(height), p.life(), p.grow(), p.fade());
        }
        return id;
    }

    /** 膨らみ(大きさの倍率)と濃さ(0〜1)。t = 吐いてからの tick。サーバーとクライアントで同じ。 */
    public static float[] state(CloudPuffPayload p, double t) {
        float g = Mth.clamp((float) (t / p.grow()), 0.0F, 1.0F);
        float ease = 1.0F - (1.0F - g) * (1.0F - g) * (1.0F - g);
        float fade = Math.min(p.fade(), p.life() / 3.0F);
        float f = Mth.clamp((float) ((t - (p.life() - fade)) / fade), 0.0F, 1.0F);
        float smoothF = f * f * (3.0F - 2.0F * f);
        float scale = (0.12F + 0.88F * ease) * (1.0F + 0.08F * Mth.clamp((float) (t / p.life()), 0.0F, 1.0F)) * (1.0F - 0.1F * smoothF);
        return new float[]{scale, 1.0F - smoothF};
    }

    /** 雲の形(seed で決まる。何度も引くので覚えておく)。 */
    public static List<CloudField.Puff> shape(CloudPuffPayload p) {
        if (SHAPES.size() > 64) {
            SHAPES.clear();
        }
        return SHAPES.computeIfAbsent(p.seed() ^ ((long) p.id() << 40), k -> CloudField.blob(p.seed(), p.fx(), p.y(), p.fz(), p.radius(), p.height()));
    }

    /** クライアントが受け取った雲を足す・消えたものを除く({@code client/CloudPuffs} から)。 */
    public static void clientAdd(CloudPuffPayload p) {
        CLIENT.add(p);
    }

    public static void clientPrune(long now) {
        CLIENT.removeIf(p -> now > p.start() + p.life() + 20);
    }

    public static void clientClear() {
        CLIENT.clear();
    }

    private static List<CloudPuffPayload> active(boolean client) {
        return client ? CLIENT : ACTIVE;
    }

    /** 場の座標 (fx, y, fz)・時刻 t が吐いた雲の中か(薄れて 3 割を切ったら外)。client = クライアントが受け取った雲で見るか。 */
    public static boolean inside(boolean client, double t, double fx, double y, double fz) {
        for (CloudPuffPayload p : active(client)) {
            double age = t - p.start();
            if (age < 0.0 || age > p.life()) {
                continue;
            }
            float[] st = state(p, age);
            if (st[1] < 0.3F) {
                continue;
            }
            double reach = p.radius() * 1.45 * st[0];
            if (Math.abs(fx - p.fx()) > reach || Math.abs(fz - p.fz()) > reach) {
                continue;
            }
            // 膨らみの倍率を戻した所で塊の中か。
            if (CloudField.inside(shape(p), p.fx() + (fx - p.fx()) / st[0], p.y() + (y - p.y()) / st[0], p.fz() + (fz - p.fz()) / st[0])) {
                return true;
            }
        }
        return false;
    }

    /** 場の座標 (fx, fz)・時刻 t の吐いた雲の柱(下 y・上 y・厚さ。無ければ null)。8 ブロックおきに縦にたどる。 */
    static int[] span(boolean client, double t, double fx, double fz) {
        int bottom = Integer.MAX_VALUE;
        int top = Integer.MIN_VALUE;
        int thick = 0;
        for (CloudPuffPayload p : active(client)) {
            double age = t - p.start();
            if (age < 0.0 || age > p.life()) {
                continue;
            }
            float[] st = state(p, age);
            if (st[1] < 0.3F) {
                continue;
            }
            double reach = p.radius() * 1.45 * st[0];
            if (Math.abs(fx - p.fx()) > reach || Math.abs(fz - p.fz()) > reach) {
                continue;
            }
            List<CloudField.Puff> sh = shape(p);
            double lx = p.fx() + (fx - p.fx()) / st[0];
            double lz = p.fz() + (fz - p.fz()) / st[0];
            double half = p.height() * 0.75;
            for (double ly = p.y() - half; ly <= p.y() + half; ly += 8.0 / st[0]) {
                if (CloudField.inside(sh, lx, ly, lz)) {
                    int y = (int) Math.round(p.y() + (ly - p.y()) * st[0]);
                    bottom = Math.min(bottom, y - 4);
                    top = Math.max(top, y + 4);
                    thick += 8;
                }
            }
        }
        return thick > 0 ? new int[]{bottom, top, thick} : null;
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD || ACTIVE.isEmpty()) {
            return;
        }
        long now = level.getGameTime();
        for (Iterator<CloudPuffPayload> it = ACTIVE.iterator(); it.hasNext(); ) {
            CloudPuffPayload p = it.next();
            if (now > p.start() + p.life()) {
                it.remove();
            }
        }
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        sendAll(event.getEntity());
    }

    @SubscribeEvent
    public static void onChangeDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getTo() == Level.OVERWORLD) {
            sendAll(event.getEntity());
        }
    }

    private static void sendAll(net.minecraft.world.entity.player.Player player) {
        if (!(player instanceof ServerPlayer sp) || sp.level().dimension() != Level.OVERWORLD) {
            return;
        }
        long now = sp.level().getGameTime();
        for (CloudPuffPayload p : ACTIVE) {
            if (now <= p.start() + p.life()) {
                PacketDistributor.sendToPlayer(sp, p);
            }
        }
    }

    private CloudPuffs() {
    }
}
