package com.yayuy.overgrownvegetation.client.atmosphere;

import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Vector3f;

/**
 * 太陽(夜は月)の向きと強さ、それを<b>見る向きでどう見えるか</b>(2026-10-30 作り直し。元は parked の {@code CanopyLight} の中の同じ名前の式)。
 * 光の筋・木漏れ日・光る胞子・林冠の影が同じ値を使う。
 *
 * <ul>
 *     <li>{@link #phase}: 空気の中の光(筋・胞子)は目からその点への向きと太陽の向きで明るさが変わる(霧・胞子は光を前へ強く散らす)。
 *     太陽の方 3 倍・真横 0.7・背 0.06(2026-10-16 ユーザー選択「昼の光の改善案 (1)」)。</li>
 *     <li>{@link #backlight}: 逆光のときだけ影を深くする(明るい筋に目が慣れて周りの影が暗く見える。同 (2))。影の明るさ 75% → 58%。
 *     視線と太陽の向きの cos が 0.35 → 0.85 で 0 → 1、昼の強さを掛け、実時間 0.8 秒の時定数で寄せる。</li>
 *     <li>{@link #lookScatter}: 視線の向きだけで決める 0.65〜1.35(地面に落ちた光のにじみに使う。背を向けても昼の木漏れ日まで消さないため)。</li>
 * </ul>
 */
public final class SunLight {

    /** 月光の筋の新月の夜の残り・色(青白)。仮決め。 */
    static final float MOON_FLOOR = 0.3F;
    static final float MOON_R = 0.62F;
    static final float MOON_G = 0.76F;
    static final float MOON_B = 1.0F;
    /** 逆光に慣れきるまでの時定数(秒)。仮決め。 */
    private static final float BACKLIGHT_SECONDS = 0.8F;

    private static float lookScatter = 1.0F;
    private static float backlight;
    private static long backlightMillis;

    /**
     * 太陽の向き(単位ベクトル)。空の描画と同じく、Y 軸で −90°・X 軸で時刻の角度だけ回した真上 = (−sin θ, cos θ, 0)。
     * 東(+X)から昇り西へ沈む。
     */
    public static Vec3 sunDirection(ClientLevel level, float partialTick) {
        float angle = level.getSunAngle(partialTick);
        return new Vec3(-Mth.sin(angle), Mth.cos(angle), 0.0);
    }

    /** 光の筋を落とす光の向き: 昼は太陽、夜は月(太陽の反対。2026-10-16 ユーザー指定「月光の筋」)。 */
    public static Vec3 lightDirection(ClientLevel level, float partialTick) {
        Vec3 sun = sunDirection(level, partialTick);
        return sun.y >= 0.0 ? sun : sun.scale(-1.0);
    }

    /** 今の光が月の光か(太陽が地平線より下)。 */
    public static boolean isMoonlight(ClientLevel level, float partialTick) {
        return sunDirection(level, partialTick).y < 0.0;
    }

    /** 昼の強さ(0〜1)。太陽の高さで寄せ、雨・雷雨で弱める。 */
    public static float daylight(ClientLevel level, Vec3 sun, float partialTick) {
        float height = smooth(Mth.clamp(((float) sun.y - 0.06F) / 0.3F, 0.0F, 1.0F));
        float rain = level.getRainLevel(partialTick);
        float thunder = level.getThunderLevel(partialTick);
        return height * (1.0F - 0.75F * rain) * (1.0F - 0.2F * thunder);
    }

    /**
     * 光の筋の強さ(0〜1)。昼は {@link #daylight}。夜は月の高さで寄せ、{@code moonStrength} × 月の満ち欠け(新月でも {@link #MOON_FLOOR})、
     * 雨は雲で月が隠れるので強く弱める。
     */
    public static float lightStrength(ClientLevel level, Vec3 dir, float partialTick) {
        if (!isMoonlight(level, partialTick)) {
            return daylight(level, dir, partialTick);
        }
        float height = smooth(Mth.clamp(((float) dir.y - 0.06F) / 0.3F, 0.0F, 1.0F));
        float moon = MOON_FLOOR + (1.0F - MOON_FLOOR) * level.getMoonBrightness();
        float rain = level.getRainLevel(partialTick);
        return height * DesignData.atmosphere().moonStrength() * moon * (1.0F - 0.9F * rain);
    }

    /**
     * 光の色 {@code out} = {r, g, b}(朝夕は橙・金へ、昼は葉を透かした淡い金、正午は白っぽく({@link #noon}。提案 1、10-30)、夜は青白い月の光)。
     */
    public static void airColor(ClientLevel level, Vec3 dir, float partialTick, float[] out) {
        if (isMoonlight(level, partialTick)) {
            out[0] = MOON_R;
            out[1] = MOON_G;
            out[2] = MOON_B;
            return;
        }
        float warm = 1.0F - Mth.clamp((float) dir.y / 0.5F, 0.0F, 1.0F);
        out[0] = 1.0F;
        out[1] = Mth.lerp(warm, 0.95F, 0.72F);
        out[2] = Mth.lerp(warm, 0.68F, 0.42F);
        float noon = noon(dir);
        out[1] = Mth.lerp(noon, out[1], 0.97F);
        out[2] = Mth.lerp(noon, out[2], 0.88F);
    }

    /**
     * 空気の中の光の向きの差。{@code (dx, dy, dz)} は目からその点への向き。c = 太陽の向きとの cos、q = (1 + c) / 2 として
     * phaseMin + (phaseMax − phaseMin) × q^phasePower。太陽が真上の正午は、水平に見るとどの向きでも真横になる(差は見上げる・見下ろすの間にだけ出る)。
     */
    public static float phase(double dx, double dy, double dz, Vec3 sun) {
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0E-5) {
            return 1.0F;
        }
        double c = (dx * sun.x + dy * sun.y + dz * sun.z) / len;
        double q = Mth.clamp((1.0 + c) * 0.5, 0.0, 1.0);
        var t = DesignData.atmosphere();
        return (float) (t.phaseMin() + (t.phaseMax() - t.phaseMin()) * Math.pow(q, t.phasePower()));
    }

    /** 視線(画面の真ん中)と太陽の向きだけで決める明るさ(0.65〜1.35)。地面に落ちた光のにじみに使う。 */
    public static float lookScatter() {
        return lookScatter;
    }

    /** 逆光の度合い(0〜1)。 */
    public static float backlight() {
        return backlight;
    }

    /** 筋のぼかし具合(0 = 斜めの朝夕のくっきりした筋、正午で {@code noonSoftness})。太陽の高さで寄せるので朝の筋から昼のにじみへ途切れずに移る。 */
    public static float softness(Vec3 sun) {
        return DesignData.atmosphere().noonSoftness() * smooth(Mth.clamp(((float) sun.y - 0.7F) / 0.27F, 0.0F, 1.0F));
    }

    /** 正午らしさ(0〜1)。太陽の高さ 0.6 で 0・0.95 で 1(朝 8 時半ごろから寄り始める。仮決め)。光の筋を白っぽく・短く・弱くする(提案 1)。 */
    public static float noon(Vec3 sun) {
        return smooth(Mth.clamp(((float) sun.y - 0.6F) / 0.35F, 0.0F, 1.0F));
    }

    public static float smooth(float t) {
        return t * t * (3.0F - 2.0F * t);
    }

    /** 空を描いた後(地形の前)に、視線の向きから {@link #lookScatter} と {@link #backlight} を決める(呼び手は {@link Atmosphere})。 */
    static void update(RenderLevelStageEvent event) {
        ClientLevel level = Minecraft.getInstance().level;
        long now = net.minecraft.Util.getMillis();
        float dt = Mth.clamp((now - backlightMillis) / 1000.0F, 0.0F, 0.25F);
        backlightMillis = now;
        float target = 0.0F;
        if (level != null) {
            float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
            Vec3 sun = sunDirection(level, partialTick);
            Vector3f look = event.getCamera().getLookVector();
            float toward = look.x() * (float) sun.x + look.y() * (float) sun.y + look.z() * (float) sun.z;
            float front = Math.max(0.0F, toward);
            lookScatter = 0.65F + 0.7F * front * front;
            target = smooth(Mth.clamp((toward - 0.35F) / 0.5F, 0.0F, 1.0F)) * daylight(level, sun, partialTick);
        }
        backlight += (target - backlight) * (1.0F - (float) Math.exp(-dt / BACKLIGHT_SECONDS));
    }

    private SunLight() {
    }
}
