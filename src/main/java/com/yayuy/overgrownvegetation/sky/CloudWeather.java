package com.yayuy.overgrownvegetation.sky;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ServerLevelData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * バニラの雨・雷雨を雲の多い日に合わせる(2026-11-09 雲の段階 2a-5。ユーザー決定「バニラの雨を雲の多い日だけに合わせる・/weather はそのまま使える・
 * 雨の前に空がだんだん曇る流れも」)。
 *
 * <p>雨の予定は雲の種と日の番号だけで決まる(サーバーもクライアントも同じ答え): 雲の多い日({@link CloudField#dayCoverage} が
 * {@link CloudField#MANY_FROM} 以上)のうち設計値 {@code weather.rainDays} の日に、その日の 3〜18 時のどこかから 6000〜18000 tick 降る。
 * 次の日も多い日なら日をまたいでよく、そうでなければ日の変わり目の 1 時間前に止む。雨のうち {@code weather.thunderShare} は雷雨
 * (雨の 4 分の 1 から、雨の 6 割か 15600 tick の短い方)。数値は全部仮決め。
 *
 * <p>サーバーはオーバーワールドの tick の初め({@link LevelTickEvent.Pre})に、予定どおりの雨・雷雨と、予定の切り替わりまでの残りを
 * {@code ServerLevelData} に書く(バニラの {@code advanceWeatherCycle} はその後に 1 減らすだけで、切り替えない)。Mixin は使わない。
 * <ul>
 *   <li>{@code /weather clear}(晴れの残り {@code clearWeatherTime} が 0 より大きい間)は予定の雨を始めない。</li>
 *   <li>{@code /weather rain|thunder}: 前の tick に書いた値と違う値が入っていたら手で変えられたと見なし、その雨が終わる(雨が止む)まではバニラに任せる。</li>
 *   <li>{@code gamerule doWeatherCycle false}・設計値 {@code weather.syncRain} 0 のときは何もしない(バニラのまま)。</li>
 * </ul>
 * 雨の前にだんだん曇る: 予定の雨の {@code weather.preRainTicks} 前から {@link #preRain} が 0 → 1(雲の量を 0.97 へ・荒天の色を半分まで。
 * {@link CloudSky#coverage}・{@code client.sky.VoxelClouds#roughness})。予定で天気を決めている間だけ(手で変えた天気・{@code /weather clear} の間は前触れなし。
 * クライアントはサーバーから {@code CloudDriftPayload.weatherActive} で受ける)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class CloudWeather {

    /** 雨が降り始める時刻の幅(その日の何時から何時の間、時)・長さの幅(tick)・日をまたがない雨が止む時刻(日の変わり目の何 tick 前)。仮決め。 */
    static final int START_FROM_HOUR = 3;
    static final int START_TO_HOUR = 18;
    static final int DURATION_MIN = 6000;
    static final int DURATION_MAX = 18000;
    static final int STOP_BEFORE_DAY_END = 1000;

    /** 1 つの雨の予定(ワールドの時刻 dayTime。終わりは含まない)。thunderFrom == thunderTo なら雷雨なし。 */
    public record Rain(long from, long to, long thunderFrom, long thunderTo) {
        boolean raining(long t) {
            return t >= from && t < to;
        }

        boolean thundering(long t) {
            return t >= thunderFrom && t < thunderTo;
        }
    }

    /** 日 day(ワールドの時刻で day × 24000 − 6000 = その日の 0 時から)の雨の予定。無ければ null。 */
    public static Rain rainOfDay(long day) {
        CloudShape shape = CloudShape.current();
        if (shape.syncRain() <= 0.0F || CloudField.dayCoverage(day) < CloudField.MANY_FROM) {
            return null;
        }
        long seed = day * 0xD6E8FEB86659FD93L ^ 0x7A1EL;
        if (CloudField.hash01(seed, 1) >= shape.rainDays()) {
            return null;
        }
        long dayStart = day * 24000L - 6000L;
        long from = dayStart + Math.round((START_FROM_HOUR + (START_TO_HOUR - START_FROM_HOUR) * CloudField.hash01(seed, 2)) * 1000.0);
        long to = from + DURATION_MIN + Math.round((DURATION_MAX - DURATION_MIN) * CloudField.hash01(seed, 3));
        long nextStart = dayStart + 24000L;
        if (to > nextStart - STOP_BEFORE_DAY_END && CloudField.dayCoverage(day + 1) < CloudField.MANY_FROM) {
            to = nextStart - STOP_BEFORE_DAY_END;
        }
        if (to - from < 3000L) {
            return null;
        }
        long tFrom = from;
        long tTo = from;
        if (CloudField.hash01(seed, 4) < shape.thunderShare()) {
            long len = to - from;
            tFrom = from + len / 4;
            tTo = Math.min(to, tFrom + Math.min(Math.round(len * 0.6), 15600L));
        }
        return new Rain(from, to, tFrom, tTo);
    }

    /** 日 day が雲の多い日か(GameTest 用)。 */
    public static boolean manyDay(long day) {
        return CloudField.dayCoverage(day) >= CloudField.MANY_FROM;
    }

    /** ワールドの時刻 t にかかる雨(その日か前の日から続く雨)。無ければ null。 */
    public static Rain rainAt(long t) {
        long day = Math.floorDiv(t + 6000L, 24000L);
        Rain r = rainOfDay(day);
        if (r != null && r.raining(t)) {
            return r;
        }
        Rain prev = rainOfDay(day - 1);
        return prev != null && prev.raining(t) ? prev : null;
    }

    /** t より後に始まる次の予定の雨(今日・明日・あさってから探す)。無ければ null。 */
    public static Rain nextRain(long t) {
        long day = Math.floorDiv(t + 6000L, 24000L);
        for (long d = day; d <= day + 2; d++) {
            Rain r = rainOfDay(d);
            if (r != null && r.from() > t) {
                return r;
            }
        }
        return null;
    }

    /**
     * 雨の前にだんだん曇る度合い(0〜1)。予定の雨の preRainTicks 前から 0 → 1、雨の間は 1(雨そのものの強さは別にバニラが持つ)。
     * 予定で天気を決めている間だけ(サーバーは自分の状態、クライアントは届いた値)。
     */
    public static float preRain(Level level, float partialTick) {
        if (!(level.isClientSide() ? clientActive : serverActive)) {
            return 0.0F;
        }
        float ticks = CloudShape.current().preRainTicks();
        if (ticks <= 0.0F) {
            return 0.0F;
        }
        double t = level.getDayTime() + (double) partialTick;
        if (rainAt((long) t) != null) {
            return 1.0F;
        }
        Rain next = nextRain((long) t);
        if (next == null) {
            return 0.0F;
        }
        double left = next.from() - t;
        if (left >= ticks) {
            return 0.0F;
        }
        float k = (float) (1.0 - left / ticks);
        return k * k * (3.0F - 2.0F * k);
    }

    // ---------------------------------------------------------------- サーバーの天気

    private static volatile boolean serverActive;
    private static volatile boolean clientActive;

    /** 予定で天気を決めている間か(サーバー。{@code CloudDriftPayload} で送る)。 */
    public static boolean serverActive() {
        return serverActive;
    }

    public static void setClientActive(boolean active) {
        clientActive = active;
    }

    /** 前の tick に書いた値(手で変えられたかを見る)。書いていなければ written = false。 */
    private static boolean written;
    private static boolean wroteRain;
    private static boolean wroteThunder;
    private static int wroteRainTime;
    private static int wroteThunderTime;
    /** 手で変えた雨の間(その雨が止むまでバニラに任せる)。 */
    private static boolean manual;

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Pre event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) {
            return;
        }
        tick(level);
    }

    /** 1 tick ぶん(GameTest からも呼ぶ)。 */
    public static void tick(ServerLevel level) {
        if (!(level.getLevelData() instanceof ServerLevelData data)) {
            serverActive = false;
            return;
        }
        if (CloudShape.current().syncRain() <= 0.0F || !level.getGameRules().getBoolean(GameRules.RULE_WEATHER_CYCLE)) {
            written = false;
            manual = false;
            serverActive = false;
            return;
        }
        if (data.getClearWeatherTime() > 0) {
            // /weather clear の間。
            written = false;
            manual = false;
            serverActive = false;
            return;
        }
        if (written && !manual) {
            // バニラは前の tick に書いた残りを 1 減らすだけ(2 以上を書くので切り替わらない)。違う値なら /weather などで手で変えられた。
            boolean same = data.isRaining() == wroteRain && data.isThundering() == wroteThunder
                    && data.getRainTime() == wroteRainTime - 1 && data.getThunderTime() == wroteThunderTime - 1;
            if (!same) {
                manual = true;
            }
        }
        if (manual) {
            written = false;
            serverActive = false;
            if (data.isRaining()) {
                return;
            }
            manual = false;
        }
        long t = level.getDayTime();
        Rain now = rainAt(t);
        boolean rain = now != null;
        boolean thunder = now != null && now.thundering(t);
        int rainTime;
        int thunderTime;
        if (rain) {
            rainTime = (int) Mth.clamp(now.to() - t, 2L, Integer.MAX_VALUE);
            long tEdge = thunder ? now.thunderTo() : (t < now.thunderFrom() ? now.thunderFrom() : now.to());
            thunderTime = (int) Mth.clamp(tEdge - t, 2L, Integer.MAX_VALUE);
        } else {
            Rain next = nextRain(t);
            long edge = next == null ? t + 24000L : next.from();
            rainTime = (int) Mth.clamp(edge - t, 2L, Integer.MAX_VALUE);
            thunderTime = rainTime;
        }
        data.setRaining(rain);
        data.setThundering(thunder);
        data.setRainTime(rainTime);
        data.setThunderTime(thunderTime);
        written = true;
        wroteRain = rain;
        wroteThunder = thunder;
        wroteRainTime = rainTime;
        wroteThunderTime = thunderTime;
        serverActive = true;
    }

    @SubscribeEvent
    public static void onServerStopping(net.neoforged.neoforge.event.server.ServerStoppingEvent event) {
        reset();
    }

    /** 覚えを消す(ワールドを出た・GameTest の後)。 */
    public static void reset() {
        written = false;
        manual = false;
        serverActive = false;
    }

    /** 開発用の要約(/ov storm here)。 */
    public static String describe(ServerLevel level) {
        long t = level.getDayTime();
        Rain now = rainAt(t);
        Rain next = nextRain(t);
        return String.format("天気の予定 %s / 今 %s / 次の雨 %s / 手で変えた雨 %s", serverActive ? "使う" : "使わない",
                now == null ? "降らない" : "雨" + (now.thundering(t) ? "・雷雨" : "") + "(あと " + (now.to() - t) + " tick)",
                next == null ? "2 日以内なし" : (next.from() - t) + " tick 後" + (next.thunderTo() > next.thunderFrom() ? "(雷雨あり)" : ""), manual ? "はい" : "いいえ");
    }

    private CloudWeather() {
    }
}
