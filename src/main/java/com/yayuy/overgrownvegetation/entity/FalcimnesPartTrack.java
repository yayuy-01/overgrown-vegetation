package com.yayuy.overgrownvegetation.entity;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.util.Mth;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * ファルキムネスの叩ける部位の軌道表(11-08、森の中を動く型 ② 段 1 の直し。ユーザー決定「技の構えにも付いていく」)。
 *
 * <p>サーバーはアニメを流さないので、生成器 {@code tools/falcimnes-anim/PartTrack.java} がアニメごとに部位の中心を 0.05 秒おきに書き出した表
 * ({@code assets/overgrown_vegetation/animations/falcimnes.parts.dat}。アニメと同じく公開しない)をサーバーとクライアントの両方で読む。
 * 中心は体格 1 のブロックで [前, 左, 上](足元から)。<b>アニメを作り直したら表も作り直す</b>(生成器の README)。
 *
 * <p>表が無いとき(素材を入れていない組み立て)は、部位を全部胴の芯の上に重ねる(殴れるのは芯の周りだけになる)。
 */
public final class FalcimnesPartTrack {

    private static final String RESOURCE = "/assets/overgrown_vegetation/animations/falcimnes.parts.dat";
    /** 表の部位(表が無いときの名前と大きさもこれ)。生成器の PARTS と同じ順。 */
    static final String[] FALLBACK_NAMES = {"head", "neck_b", "neck_a", "arm_l", "blade_l", "arm_r", "blade_r", "abdomen_a", "abdomen_b"};

    private static FalcimnesPartTrack instance;

    final double step;
    public final String[] names;
    final float[] widths, heights;
    private final Map<String, Anim> anims;

    private record Anim(double length, boolean loop, float[][] frames) {
    }

    /**
     * 性格の構え(11-08 夜): 待機に構えを足したときの部位のずれ(体格 1 のブロック、[前, 左, 上] × 部位)。
     * guard = 慎重さ 1(畳む・低い・首を垂らす)、open = 攻撃性 1(開く・高い・首を起こす)、bold = 慎重さ 0(少し高い)。表に無ければ 0。
     */
    float[] postureGuard, postureOpen, postureBold;
    /** 段 3(11-08 夜): 狭い所の構え(squeeze)・低い葉の下で首を下げる構え(duck)のずれ。表に無ければ null。 */
    float[] shapeSqueeze, shapeDuck;

    /**
     * 11-08 夜(段 3 の直し): 狭い所の構えは効きの段ごとの表(鎌は突き掻きの前触れの通り道をなぞるので、効きに比例しない)。
     * {@code squeezeSteps[段][部位 × 3]} = 部位のずれ、{@code squeezeArmRot[段][骨 × 3]} = 鎌の骨の角度の差(ファイル座標の度)。
     */
    float[][] squeezeSteps, squeezeArmRot;
    @org.jetbrains.annotations.Nullable
    public String[] squeezeArmBones;

    /** 段の表を効き q(0〜1)で引く(段の間は線形)。 */
    private static void stepLerp(float[][] steps, float q, float scale, double[] outD, float[] outF) {
        float pos = Mth.clamp(q, 0.0F, 1.0F) * (steps.length - 1);
        int i = Math.min(steps.length - 1, (int) Math.floor(pos));
        int j = Math.min(steps.length - 1, i + 1);
        float f = pos - i;
        float[] a = steps[i], b = steps[j];
        int n = outD != null ? outD.length : outF.length;
        for (int k = 0; k < n && k < a.length; k++) {
            float v = (a[k] + (b[k] - a[k]) * f) * scale;
            if (outD != null) outD[k] += v;
            else outF[k] = v;
        }
    }

    /** 狭い所の構えの鎌の骨の角度の差(ファイル座標の度、骨 × 3)を {@code out} へ。表が無ければ false。 */
    public boolean squeezeArms(float q, float[] out) {
        if (this.squeezeArmRot == null) return false;
        stepLerp(this.squeezeArmRot, q, 1.0F, null, out);
        return true;
    }

    /** 段 3 の構えのずれを足す({@code q} = 狭い所の構えの効き、{@code d} = 首を下げる効き)。 */
    public void addShape(double[] out, float q, float d) {
        if (q > 0.0F) {
            if (this.squeezeSteps != null) {
                stepLerp(this.squeezeSteps, q, 1.0F, out, null);
            } else if (this.shapeSqueeze != null) {
                for (int k = 0; k < out.length && k < this.shapeSqueeze.length; k++) out[k] += this.shapeSqueeze[k] * q;
            }
        }
        if (d > 0.0F && this.shapeDuck != null) {
            for (int k = 0; k < out.length && k < this.shapeDuck.length; k++) out[k] += this.shapeDuck[k] * d;
        }
    }

    private static float[] floats(JsonArray a) {
        float[] out = new float[a.size()];
        for (int i = 0; i < out.length; i++) out[i] = a.get(i).getAsFloat();
        return out;
    }

    /**
     * 性格の構えのずれを {@code out} に足す。重みは {@code FalcimnesPersonality.postureWeights}(畳む g・開く o)と、慎重さが 0.5 より下の分(bold)。
     * {@code fade} は構えの効き(技の間は 0)。描画の {@code FalcimnesPostureRig} と同じ重み。
     */
    public void addPosture(double[] out, float g, float o, float bold, float fade) {
        if (this.postureGuard == null || fade <= 0.0F) return;
        for (int k = 0; k < out.length && k < this.postureGuard.length; k++) {
            out[k] += (this.postureGuard[k] * g + this.postureOpen[k] * o + this.postureBold[k] * bold) * fade;
        }
    }

    private FalcimnesPartTrack(double step, String[] names, float[] widths, float[] heights, Map<String, Anim> anims) {
        this.step = step;
        this.names = names;
        this.widths = widths;
        this.heights = heights;
        this.anims = anims;
    }

    public static synchronized FalcimnesPartTrack get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    public int count() {
        return this.names.length;
    }

    /** その表があるか。 */
    public boolean has(String anim) {
        return this.anims.containsKey(anim);
    }

    /** アニメの長さ(秒。無ければ 0)。 */
    public double length(String anim) {
        Anim a = this.anims.get(anim);
        return a == null ? 0.0D : a.length();
    }

    /**
     * アニメ {@code anim} の {@code t} 秒の部位の中心を {@code out}(部位の数 × 3)へ。ループは巻き、ほかは終わりで止める。
     *
     * @return 表にあったか(無ければ out は触らない)
     */
    public boolean sample(String anim, double t, double[] out) {
        Anim a = this.anims.get(anim);
        if (a == null) return false;
        if (a.loop() && a.length() > 0.0D) {
            t = t - Math.floor(t / a.length()) * a.length();
        }
        t = Mth.clamp(t, 0.0D, a.length());
        float[][] fr = a.frames();
        double pos = t / this.step;
        int i = Math.min(fr.length - 1, (int) Math.floor(pos));
        int j = Math.min(fr.length - 1, i + 1);
        double f = Mth.clamp(pos - i, 0.0D, 1.0D);
        float[] lo = fr[i], hi = fr[j];
        for (int k = 0; k < out.length && k < lo.length; k++) {
            out[k] = lo[k] + (hi[k] - lo[k]) * f;
        }
        return true;
    }

    private static FalcimnesPartTrack load() {
        try (InputStream in = FalcimnesPartTrack.class.getResourceAsStream(RESOURCE)) {
            if (in != null) {
                JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                double step = root.get("step").getAsDouble();
                JsonArray ps = root.getAsJsonArray("parts");
                String[] names = new String[ps.size()];
                float[] w = new float[ps.size()], h = new float[ps.size()];
                for (int i = 0; i < ps.size(); i++) {
                    JsonObject p = ps.get(i).getAsJsonObject();
                    names[i] = p.get("name").getAsString();
                    w[i] = p.get("w").getAsFloat();
                    h[i] = p.get("h").getAsFloat();
                }
                Map<String, Anim> anims = new HashMap<>();
                for (Map.Entry<String, com.google.gson.JsonElement> e : root.getAsJsonObject("anims").entrySet()) {
                    JsonObject ao = e.getValue().getAsJsonObject();
                    JsonArray fa = ao.getAsJsonArray("f");
                    float[][] frames = new float[fa.size()][];
                    for (int i = 0; i < fa.size(); i++) {
                        JsonArray row = fa.get(i).getAsJsonArray();
                        frames[i] = new float[row.size()];
                        for (int k = 0; k < row.size(); k++) frames[i][k] = row.get(k).getAsFloat();
                    }
                    anims.put(e.getKey(), new Anim(ao.get("length").getAsDouble(), ao.get("loop").getAsBoolean(), frames));
                }
                FalcimnesPartTrack track = new FalcimnesPartTrack(step, names, w, h, anims);
                if (root.has("posture")) {
                    JsonObject po = root.getAsJsonObject("posture");
                    track.postureGuard = floats(po.getAsJsonArray("guard"));
                    track.postureOpen = floats(po.getAsJsonArray("open"));
                    track.postureBold = floats(po.getAsJsonArray("bold"));
                    if (po.has("squeeze")) track.shapeSqueeze = floats(po.getAsJsonArray("squeeze"));
                    if (po.has("duck")) track.shapeDuck = floats(po.getAsJsonArray("duck"));
                    if (po.has("squeezeSteps")) {
                        JsonArray st = po.getAsJsonArray("squeezeSteps");
                        track.squeezeSteps = new float[st.size()][];
                        for (int i = 0; i < st.size(); i++) track.squeezeSteps[i] = floats(st.get(i).getAsJsonArray());
                    }
                    if (po.has("squeezeArms")) {
                        JsonObject sa = po.getAsJsonObject("squeezeArms");
                        JsonArray bn = sa.getAsJsonArray("bones"), rs = sa.getAsJsonArray("rot");
                        track.squeezeArmBones = new String[bn.size()];
                        for (int i = 0; i < bn.size(); i++) track.squeezeArmBones[i] = bn.get(i).getAsString();
                        track.squeezeArmRot = new float[rs.size()][];
                        for (int i = 0; i < rs.size(); i++) track.squeezeArmRot[i] = floats(rs.get(i).getAsJsonArray());
                    }
                }
                return track;
            }
            OvergrownVegetation.LOGGER.warn("ファルキムネスの部位の軌道表 {} が無い。部位は胴の芯に重ねる", RESOURCE);
        } catch (Exception ex) {
            OvergrownVegetation.LOGGER.error("ファルキムネスの部位の軌道表を読めない。部位は胴の芯に重ねる", ex);
        }
        int n = FALLBACK_NAMES.length;
        float[] w = new float[n], h = new float[n];
        java.util.Arrays.fill(w, 1.0F);
        java.util.Arrays.fill(h, 1.0F);
        float[] core = new float[n * 3];
        for (int i = 0; i < n; i++) core[i * 3 + 2] = 1.0F;
        Map<String, Anim> anims = new HashMap<>();
        anims.put("idle", new Anim(0.0D, false, new float[][]{core}));
        return new FalcimnesPartTrack(0.05D, FALLBACK_NAMES, w, h, anims);
    }
}
