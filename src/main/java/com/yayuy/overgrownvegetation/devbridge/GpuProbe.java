package com.yayuy.overgrownvegetation.devbridge;

import com.yayuy.overgrownvegetation.client.atmosphere.Atmosphere;
import com.yayuy.overgrownvegetation.client.atmosphere.GlowBloom;
import com.yayuy.overgrownvegetation.config.AtmosphereQuality;
import com.yayuy.overgrownvegetation.config.OvergrownClientConfig;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;

import java.util.ArrayDeque;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 雰囲気の演出 1 つずつの GPU の時間(2026-11-23 軽量化 GPU)。{@link Atmosphere} の guard の前後に GL のタイムスタンプを打ち、
 * 結果は出たものから待たずに拾う。{@code fxprof} は受け手(Atmosphere.onRenderLevel まるごと)までしか分けないので、その中を名前ごとに分ける。
 *
 * <p>命令(DevBridge の {@code gpuprobe}):
 * <ul>
 *     <li>{@code on|off} — 測り始める・止める(on で数え直す)。</li>
 *     <li>{@code stat} — 名前ごとの GPU ms/フレーム(フレーム = AFTER_SKY の "sunmap" の回数)と呼ばれた回数。数え直す。</li>
 *     <li>{@code q high|medium|low} — 雰囲気の重さの段だけを変える(光のにじみなどほかの項目はそのまま)。</li>
 *     <li>{@code bloom on|off} — 光のにじみだけを止める。</li>
 * </ul>
 */
public final class GpuProbe {

    private record Pending(String name, int q0, int q1) {
    }

    private static final ArrayDeque<Integer> FREE = new ArrayDeque<>();
    private static final ArrayDeque<Pending> PENDING = new ArrayDeque<>();
    private static final Map<String, long[]> SUM = new TreeMap<>();
    private static long frames;

    private GpuProbe() {
    }

    public static String command(String arg) {
        String[] a = arg.trim().toLowerCase(Locale.ROOT).split("\\s+");
        switch (a[0]) {
            case "on" -> {
                SUM.clear();
                frames = 0;
                Atmosphere.devGuardWrap = GpuProbe::wrap;
                return "GPUPROBE on";
            }
            case "off" -> {
                Atmosphere.devGuardWrap = null;
                return "GPUPROBE off";
            }
            case "stat" -> {
                drain();
                StringBuilder sb = new StringBuilder("GPUPROBE " + frames + " フレーム");
                long total = 0;
                java.util.List<Map.Entry<String, long[]>> list = new java.util.ArrayList<>(SUM.entrySet());
                list.sort((x, y) -> Long.compare(y.getValue()[0], x.getValue()[0]));
                for (Map.Entry<String, long[]> e : list) {
                    total += e.getValue()[0];
                    sb.append(String.format(Locale.ROOT, " | %s %.3f ms(%d 回)", e.getKey(),
                            frames == 0 ? 0.0 : e.getValue()[0] / 1.0E6 / frames, e.getValue()[1]));
                }
                sb.insert(0, String.format(Locale.ROOT, "合計 %.3f ms/フレーム ", frames == 0 ? 0.0 : total / 1.0E6 / frames));
                SUM.clear();
                frames = 0;
                return sb.toString();
            }
            case "q" -> {
                AtmosphereQuality q = AtmosphereQuality.valueOf(a[1].toUpperCase(Locale.ROOT));
                OvergrownClientConfig.CLIENT.atmosphereQuality.set(q);
                return "GPUPROBE 雰囲気の重さ " + q;
            }
            case "clouds" -> {
                // 雲の設定だけを切る(雲のファイルには触らない。重さの切り分け用)
                OvergrownClientConfig.CLIENT.clouds.set(!(a.length > 1 && a[1].equals("off")));
                return "GPUPROBE 雲 " + OvergrownClientConfig.CLIENT.clouds.get();
            }
            case "farclouds" -> {
                // 遠くの雲: old = 空と一緒に地形より先(前の描き方)・new = 地形の後に型で(11-23)
                com.yayuy.overgrownvegetation.client.sky.VoxelClouds.devOldFarPass = a.length > 1 && a[1].equals("old");
                return "GPUPROBE 遠くの雲 " + (com.yayuy.overgrownvegetation.client.sky.VoxelClouds.devOldFarPass ? "前の描き方" : "地形の後");
            }
            case "bloom" -> {
                GlowBloom.devOff = a.length > 1 && a[1].equals("off");
                return "GPUPROBE 光のにじみ " + (GlowBloom.devOff ? "止めた" : "戻した");
            }
            default -> {
                return "GPUPROBE on|off|stat|q high|medium|low|bloom on|off";
            }
        }
    }

    private static int query() {
        Integer id = FREE.poll();
        return id != null ? id : GL15.glGenQueries();
    }

    /** 出ている結果を順に拾う(タイムスタンプは打った順に出る)。 */
    private static void drain() {
        while (!PENDING.isEmpty()) {
            Pending p = PENDING.peek();
            if (GL15.glGetQueryObjecti(p.q1, GL15.GL_QUERY_RESULT_AVAILABLE) == 0) {
                break;
            }
            PENDING.poll();
            long ns = GL33.glGetQueryObjecti64(p.q1, GL15.GL_QUERY_RESULT) - GL33.glGetQueryObjecti64(p.q0, GL15.GL_QUERY_RESULT);
            long[] s = SUM.computeIfAbsent(p.name, k -> new long[2]);
            s[0] += Math.max(0L, ns);
            s[1]++;
            FREE.add(p.q0);
            FREE.add(p.q1);
        }
    }

    private static void wrap(String name, Runnable run) {
        if (name.equals("sunmap")) {
            drain();
            frames++;
        }
        if (PENDING.size() > 4000) {
            run.run();
            return;
        }
        int q0 = query();
        GL33.glQueryCounter(q0, GL33.GL_TIMESTAMP);
        try {
            run.run();
        } finally {
            int q1 = query();
            GL33.glQueryCounter(q1, GL33.GL_TIMESTAMP);
            PENDING.add(new Pending(name, q0, q1));
        }
    }
}
