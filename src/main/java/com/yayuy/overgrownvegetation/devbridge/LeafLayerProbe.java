package com.yayuy.overgrownvegetation.devbridge;

import com.mojang.blaze3d.systems.RenderSystem;
import com.yayuy.overgrownvegetation.block.OvergrownLeavesBlock;
import com.yayuy.overgrownvegetation.client.LayerProbe;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * 開発用: 葉の層(cutout_mipped)の重ね塗りを数える(2026-10-24、DEV_NOTES「葉の層の塗りつぶしの削減(2026-10-24)」)。
 *
 * <p>{@code leafprobe <フレーム数> [equal|always|plain]}。層の描画({@code LevelRenderer#renderSectionLayer})を GPU のタイムスタンプと
 * GL_SAMPLES_PASSED・画素シェーダーの回数・頂点シェーダーの回数(ARB_pipeline_statistics_query)で包む。
 * <ul>
 *   <li>equal(既定): 描いた後に同じ層を同じプログラムで、色も奥行きも書かず奥行き「等しい」で描き直し、通った画素 = 葉の層が最後に持っている画素(見える葉の画素)を数える。</li>
 *   <li>always: 奥行きの試しを常に通す(絵は崩れる)。通った画素 = discard されなかった断片。画素シェーダーの回数との差が discard の数。</li>
 *   <li>plain: 包むだけ。</li>
 * </ul>
 * バニラの描き方のときだけ(Sodium では層の描画が呼ばれない)。{@code leafcull 0|1|2} は候補 F5 の試し({@link OvergrownLeavesBlock#faceCull})。
 */
final class LeafLayerProbe implements LayerProbe.Hook {

    private static final int GL_VERTEX_SHADER_INVOCATIONS = 0x82F0;
    private static final int GL_FRAGMENT_SHADER_INVOCATIONS = 0x82F4;
    private static final int SLOTS = 4;
    private static final int WARMUP = 8;

    private static LeafLayerProbe running;
    private static String result;

    private final String mode;
    private final int frames;
    private final boolean stats;
    // ts0 ts1 samples fs vs | ts2 ts3 samples2 fs2
    private final int[][] q = new int[SLOTS][9];
    private final boolean[] slotUsed = new boolean[SLOTS];
    private final boolean[] slotRedraw = new boolean[SLOTS];
    private int slot;
    private int seen;
    private int counted;
    private double timeMain;
    private double timeRedraw;
    private double samples;
    private double fs;
    private double vs;
    private double samples2;
    private double fs2;
    private long pixels;

    private LeafLayerProbe(String mode, int frames) {
        this.mode = mode;
        this.frames = frames;
        this.stats = GL.getCapabilities().GL_ARB_pipeline_statistics_query;
        for (int[] s : q) {
            GL15.glGenQueries(s);
        }
    }

    static String command(String arg, Consumer<String> log) {
        if (running == null && result != null) {
            log.accept("LEAFPROBE " + result);
            result = null;
            return null;
        }
        if (running == null) {
            String[] p = arg.trim().split("\\s+");
            int n = p.length > 0 && !p[0].isEmpty() ? Integer.parseInt(p[0]) : 120;
            String mode = p.length > 1 ? p[1] : "equal";
            // 命令はクライアントの tick(= 描画のスレッド)で来る
            running = new LeafLayerProbe(mode, n);
            LayerProbe.hook = running;
        }
        return "葉の層を数えている";
    }

    static String cull(String arg) {
        if (!arg.isBlank()) {
            OvergrownLeavesBlock.faceCull = Integer.parseInt(arg.trim());
            Minecraft mc = Minecraft.getInstance();
            mc.tell(() -> mc.levelRenderer.allChanged());
        }
        return "LEAFCULL " + OvergrownLeavesBlock.faceCull + "(0 = 全部描く・1 = 葉どうしの面を省く・2 = 奥にもう 1 枚葉があるときだけ省く)";
    }

    @Override
    public void begin(boolean redraw) {
        if (frames == 0) return;
        int[] s = q[slot];
        if (!redraw) {
            collect(slot);
            if (pixels == 0) {
                int[] vp = new int[4];
                GL11.glGetIntegerv(GL11.GL_VIEWPORT, vp);
                pixels = (long) vp[2] * vp[3];
            }
            GL33.glQueryCounter(s[0], GL33.GL_TIMESTAMP);
            GL15.glBeginQuery(GL15.GL_SAMPLES_PASSED, s[2]);
            if (stats) {
                GL15.glBeginQuery(GL_FRAGMENT_SHADER_INVOCATIONS, s[3]);
                GL15.glBeginQuery(GL_VERTEX_SHADER_INVOCATIONS, s[4]);
            }
        } else {
            GL33.glQueryCounter(s[5], GL33.GL_TIMESTAMP);
            GL15.glBeginQuery(GL15.GL_SAMPLES_PASSED, s[7]);
            if (stats) GL15.glBeginQuery(GL_FRAGMENT_SHADER_INVOCATIONS, s[8]);
        }
    }

    @Override
    public void afterSetup(boolean redraw) {
        if (frames == 0) return;
        if (redraw) {
            RenderSystem.depthFunc(GL11.GL_EQUAL);
            RenderSystem.depthMask(false);
            RenderSystem.colorMask(false, false, false, false);
        } else if (mode.equals("always")) {
            RenderSystem.depthFunc(GL11.GL_ALWAYS);
        }
    }

    @Override
    public void end(boolean redraw) {
        if (frames == 0) return;
        int[] s = q[slot];
        if (!redraw) {
            GL15.glEndQuery(GL15.GL_SAMPLES_PASSED);
            if (stats) {
                GL15.glEndQuery(GL_FRAGMENT_SHADER_INVOCATIONS);
                GL15.glEndQuery(GL_VERTEX_SHADER_INVOCATIONS);
            }
            GL33.glQueryCounter(s[1], GL33.GL_TIMESTAMP);
            slotUsed[slot] = true;
            slotRedraw[slot] = false;
            if (!wantsRedraw()) slot = (slot + 1) % SLOTS;
        } else {
            GL15.glEndQuery(GL15.GL_SAMPLES_PASSED);
            if (stats) GL15.glEndQuery(GL_FRAGMENT_SHADER_INVOCATIONS);
            GL33.glQueryCounter(s[6], GL33.GL_TIMESTAMP);
            slotRedraw[slot] = true;
            slot = (slot + 1) % SLOTS;
        }
    }

    @Override
    public boolean wantsRedraw() {
        return mode.equals("equal");
    }

    /** 4 フレーム前のスロットを読む(待つ)。 */
    private void collect(int i) {
        if (!slotUsed[i]) return;
        slotUsed[i] = false;
        int[] s = q[i];
        seen++;
        if (seen <= WARMUP) return;
        timeMain += GL33.glGetQueryObjecti64(s[1], GL15.GL_QUERY_RESULT) - GL33.glGetQueryObjecti64(s[0], GL15.GL_QUERY_RESULT);
        samples += GL33.glGetQueryObjecti64(s[2], GL15.GL_QUERY_RESULT);
        if (stats) {
            fs += GL33.glGetQueryObjecti64(s[3], GL15.GL_QUERY_RESULT);
            vs += GL33.glGetQueryObjecti64(s[4], GL15.GL_QUERY_RESULT);
        }
        if (slotRedraw[i]) {
            timeRedraw += GL33.glGetQueryObjecti64(s[6], GL15.GL_QUERY_RESULT) - GL33.glGetQueryObjecti64(s[5], GL15.GL_QUERY_RESULT);
            samples2 += GL33.glGetQueryObjecti64(s[7], GL15.GL_QUERY_RESULT);
            if (stats) fs2 += GL33.glGetQueryObjecti64(s[8], GL15.GL_QUERY_RESULT);
        }
        counted++;
        if (counted >= frames) finish();
    }

    private void finish() {
        LayerProbe.hook = null;
        double n = counted;
        double px = Math.max(1, pixels);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "%s %d フレーム・描いた画素 %d(%s)| 層の GPU %.3f ms・通った画素/画面 %.3f",
                mode, counted, pixels, Minecraft.getInstance().levelRenderer.getSectionStatistics(), timeMain / n / 1e6, samples / n / px));
        if (stats) {
            sb.append(String.format(Locale.ROOT, "・画素シェーダー/画面 %.3f・頂点シェーダー %.0f 万回(通った/画素シェーダー %.1f%%)",
                    fs / n / px, vs / n / 1e4, fs > 0 ? 100.0 * samples / fs : 0.0));
        }
        if (mode.equals("equal")) {
            sb.append(String.format(Locale.ROOT, " | 描き直し(奥行き等しい)GPU %.3f ms・見える葉の画素/画面 %.3f(通った画素の %.1f%%)",
                    timeRedraw / n / 1e6, samples2 / n / px, samples > 0 ? 100.0 * samples2 / samples : 0.0));
            if (stats) sb.append(String.format(Locale.ROOT, "・描き直しの画素シェーダー/画面 %.3f", fs2 / n / px));
        }
        for (int[] s : q) {
            GL15.glDeleteQueries(s);
        }
        result = sb.toString();
        running = null;
    }
}
