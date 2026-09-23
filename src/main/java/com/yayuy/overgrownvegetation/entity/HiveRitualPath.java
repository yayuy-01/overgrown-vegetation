package com.yayuy.overgrownvegetation.entity;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.util.Mth;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 儀式アニメーションの body 骨の道筋を 20 Hz で焼いたもの
 * ({@code assets/overgrown_vegetation/ritual/hive_ritual_path.json}、{@code tools/ritual-assets/PathBake} が作る)。
 * 座席({@link HiveRitualSeatEntity})がプレイヤーの実際の位置を、見た目(PAL の体)と同じ道筋で動かすのに使う。
 * <ul>
 *   <li>導入(0〜{@link #introSeconds()}): {@link #progress}(0〜1 の「触れる位置までの進み具合」)。
 *   歩き始めの場所(右クリックした所)から触れる位置までを補間する。</li>
 *   <li>以降: {@link #x} / {@link #z}(px、ファイル座標)。</li>
 * </ul>
 * jar の中の資源なのでサーバーでもクライアントでも同じものを読める。
 */
public final class HiveRitualPath {
    private static final String RESOURCE = "/assets/overgrown_vegetation/ritual/hive_ritual_path.json";
    private static HiveRitualPath instance;

    private final int rate;
    private final double introSeconds;
    private final double touchPx;
    private final double strikeSeconds;
    private final double wakeSeconds;
    private final double lengthSeconds;
    private final float[] progress;
    private final double startSeconds;
    private final float[] x;
    private final float[] z;

    private HiveRitualPath(int rate, double introSeconds, double touchPx, double strikeSeconds, double wakeSeconds,
            double lengthSeconds, float[] progress, double startSeconds, float[] x, float[] z) {
        this.rate = rate;
        this.introSeconds = introSeconds;
        this.touchPx = touchPx;
        this.strikeSeconds = strikeSeconds;
        this.wakeSeconds = wakeSeconds;
        this.lengthSeconds = lengthSeconds;
        this.progress = progress;
        this.startSeconds = startSeconds;
        this.x = x;
        this.z = z;
    }

    public static synchronized HiveRitualPath get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    private static HiveRitualPath load() {
        try (InputStream in = HiveRitualPath.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("resource not found: " + RESOURCE);
            }
            JsonObject o = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            float[] progress = o.has("progress") ? floats(o.getAsJsonArray("progress")) : new float[] {0};
            return new HiveRitualPath(o.get("rate").getAsInt(),
                    num(o, "intro_seconds", 0.0), num(o, "touch_px", 0.0),
                    num(o, "strike_seconds", 17.2), num(o, "wake_seconds", 30.0), num(o, "length_seconds", 37.0),
                    progress, o.get("start_seconds").getAsDouble(),
                    floats(o.getAsJsonArray("x")), floats(o.getAsJsonArray("z")));
        } catch (Exception e) {
            OvergrownVegetation.LOGGER.error("HiveRitual: 道筋 {} を読めません。体は立ち位置から動きません。", RESOURCE, e);
            return new HiveRitualPath(20, 0.0, 0.0, 17.2, 30.0, 37.0, new float[] {0}, 0.0, new float[] {0}, new float[] {0});
        }
    }

    private static float[] floats(JsonArray a) {
        float[] r = new float[a.size()];
        for (int i = 0; i < r.length; i++) {
            r[i] = a.get(i).getAsFloat();
        }
        return r;
    }

    /** 導入の長さ(秒)。 */
    public double introSeconds() {
        return introSeconds;
    }

    private static double num(JsonObject o, String k, double def) {
        return o.has(k) ? o.get(k).getAsDouble() : def;
    }

    /** 胞子の塊が胸に刺さる時刻(秒)。ここで装備を受け取る。 */
    public double strikeSeconds() {
        return strikeSeconds;
    }

    /** 目を覚ます時刻(秒)。暗転はここで明ける。 */
    public double wakeSeconds() {
        return wakeSeconds;
    }

    /** アニメーションの長さ(秒)。ここで降ろす。 */
    public double lengthSeconds() {
        return lengthSeconds;
    }

    /** 触れる位置 = 原点からノードの方へ何 px か。 */
    public double touchPx() {
        return touchPx;
    }

    /** 導入の進み具合(0 = 歩き始め / 立ち位置、1 = 触れる位置)。 */
    public float progress(double seconds) {
        return sample(progress, seconds * rate);
    }

    /** 時刻 {@code seconds} の body の位置 x(px、ファイル座標)。範囲外は端の値。 */
    public float x(double seconds) {
        return sample(x, (seconds - startSeconds) * rate);
    }

    /** 時刻 {@code seconds} の body の位置 z(px、ファイル座標。+Z が後ろ)。 */
    public float z(double seconds) {
        return sample(z, (seconds - startSeconds) * rate);
    }

    private static float sample(float[] arr, double s) {
        if (s <= 0 || arr.length == 1) {
            return arr[0];
        }
        if (s >= arr.length - 1) {
            return arr[arr.length - 1];
        }
        int i = (int) s;
        return Mth.lerp((float) (s - i), arr[i], arr[i + 1]);
    }
}
