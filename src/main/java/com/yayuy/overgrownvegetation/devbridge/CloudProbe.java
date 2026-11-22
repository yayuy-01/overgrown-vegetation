package com.yayuy.overgrownvegetation.devbridge;

import com.yayuy.overgrownvegetation.sky.CloudField;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * 雲の流れの計測(2026-11-12、ユーザー報告「雲と雲の影が数秒ごとに瞬間移動する」の切り分け)。開発用。
 * {@code cloudprobe start} から {@code cloudprobe stop} まで、毎フレーム(空を描いた後)に壁の時計・クライアントの gameTime・partialTick と、
 * 雲と影が使う風の距離 {@link CloudField#offsetX} を記録し、フレームごとの進みの跳びと時刻の巻き戻りを数える。
 * 生の値は {@code devbridge/cloudprobe.csv}。
 */
final class CloudProbe {

    private record Sample(long nanos, long gameTime, float partial, double ox, double oz) {
    }

    private static final List<Sample> SAMPLES = new ArrayList<>();
    private static boolean armed;
    private static boolean registered;

    static String command(String arg, Path dir) {
        switch (arg.trim()) {
            case "start" -> {
                SAMPLES.clear();
                armed = true;
                if (!registered) {
                    registered = true;
                    NeoForge.EVENT_BUS.addListener(CloudProbe::onRender);
                }
                return "CLOUDPROBE 開始";
            }
            case "stop" -> {
                armed = false;
                return "CLOUDPROBE " + analyse(dir);
            }
            default -> {
                return "CLOUDPROBE start|stop";
            }
        }
    }

    private static void onRender(RenderLevelStageEvent event) {
        if (!armed || event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY) {
            return;
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        long gameTime = level.getGameTime();
        double t = (double) gameTime + partial;
        SAMPLES.add(new Sample(System.nanoTime(), gameTime, partial, CloudField.offsetX(t), CloudField.offsetZ(t)));
    }

    private static String analyse(Path dir) {
        int n = SAMPLES.size();
        if (n < 3) {
            return "フレームが足りない (" + n + ")";
        }
        StringBuilder csv = new StringBuilder("ms,gameTime,partial,t,offsetX,offsetZ,step,speedBlocksPerSec\n");
        double[] speeds = new double[n - 1];
        int back = 0;
        double worstBack = 0.0;
        Sample first = SAMPLES.get(0);
        for (int i = 0; i < n; i++) {
            Sample s = SAMPLES.get(i);
            double t = s.gameTime + s.partial;
            double step = 0.0;
            double speed = 0.0;
            if (i > 0) {
                Sample p = SAMPLES.get(i - 1);
                double dt = (s.nanos - p.nanos) / 1.0e9;
                step = Math.hypot(s.ox - p.ox, s.oz - p.oz);
                speed = dt > 0 ? step / dt : 0.0;
                speeds[i - 1] = speed;
                double dtGame = t - (p.gameTime + p.partial);
                if (dtGame < 0) {
                    back++;
                    worstBack = Math.min(worstBack, dtGame);
                }
            }
            csv.append(String.format(Locale.ROOT, "%.3f,%d,%.4f,%.4f,%.5f,%.5f,%.5f,%.4f%n", (s.nanos - first.nanos) / 1.0e6, s.gameTime, s.partial, t,
                    s.ox, s.oz, step, speed));
        }
        try {
            Files.writeString(dir.resolve("cloudprobe.csv"), csv.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // 書けなくても要約は返す
        }
        double[] sorted = speeds.clone();
        Arrays.sort(sorted);
        double median = sorted[sorted.length / 2];
        // 跳び = フレームの進みが中央の速さで進む分の 5 倍 + 0.02 ブロックを超えたもの
        int jumps = 0;
        double worstJump = 0.0;
        StringBuilder where = new StringBuilder();
        for (int i = 1; i < n; i++) {
            Sample s = SAMPLES.get(i);
            Sample p = SAMPLES.get(i - 1);
            double dt = (s.nanos - p.nanos) / 1.0e9;
            double step = Math.hypot(s.ox - p.ox, s.oz - p.oz);
            double expect = median * dt;
            if (step > expect * 5.0 + 0.02) {
                jumps++;
                worstJump = Math.max(worstJump, step);
                if (jumps <= 12) {
                    where.append(String.format(Locale.ROOT, " [%.2fs gt=%d→%d p=%.2f→%.2f %.3fブロック]", (s.nanos - first.nanos) / 1.0e9, p.gameTime, s.gameTime,
                            p.partial, s.partial, step));
                }
            }
        }
        double secs = (SAMPLES.get(n - 1).nanos - first.nanos) / 1.0e9;
        double total = Math.hypot(SAMPLES.get(n - 1).ox - first.ox, SAMPLES.get(n - 1).oz - first.oz);
        return String.format(Locale.ROOT, "%d フレーム %.1f 秒・進んだ %.2f ブロック(平均 %.3f ブロック/秒・フレームの中央 %.3f)・時刻の巻き戻り %d 回(最大 %.2f tick)・跳び %d 回(最大 %.3f ブロック)%s",
                n, secs, total, total / Math.max(secs, 1e-6), median, back, worstBack, jumps, worstJump, where);
    }

    private CloudProbe() {
    }
}
