package com.yayuy.overgrownvegetation.client.atmosphere;

import net.minecraft.world.phys.Vec3;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;

/**
 * GPU で動かす粒のための<b>風に押された量の履歴</b>(2026-10-23 の漂う胞子の束の作り、10-30 作り直しで光る胞子用に分けた。元は parked の {@code client/GpuSpores})。
 * カメラのまわり 16 × 16 升(8 ブロック)ごとに、胞子が風({@link Wind#current()})で流される速さを毎 tick 積み、{@link #HISTORY} tick ぶんの履歴を
 * 3D テクスチャに置く。粒は「今の押された量 − 生まれた時の押された量」だけ流れる(生まれた升の風で動くので、近くの粒は突風で一斉に流れる)。
 * 成分は (押された量 x, z, 今の風の強さ, 余韻)。余韻は前線が過ぎたあとの風の強さの残りで、粒の渦はこれで強まり {@code afterglowSeconds} かけて
 * ゆっくり落ち着く(提案 6、10-30 夜)。光る胞子({@link ShaftMotes})と環境の胞子({@link AmbientSpores})が同じ履歴を読む。
 *
 * <p>流される速さ: 凪(0.2)で毎秒 0.3 ブロック、0.5 で 0.9、突風のさなか(1.0)で 3.3、いちばん強い所(1.2)で 5 ブロック(風の強さの 1 乗と 3 乗の和。
 * 2026-10-15 夜 ユーザー「突風が吹く際はもっと速くしてもよい」)。描画のスレッドだけが触る。
 */
final class WindHistory {

    static final int CELL = 8;
    static final int N = 16;
    /** 履歴の長さ(tick)。粒の寿命より長く。 */
    static final int HISTORY = 1024;
    /** 座標を折り返す幅(float の精度のため。升の幅の倍数)。 */
    static final double WRAP = 2048.0;

    private static final int[] keyX = new int[N * N];
    private static final int[] keyZ = new int[N * N];
    private static final double[] accX = new double[N * N];
    private static final double[] accZ = new double[N * N];
    /** 前線が過ぎたあとの余韻(風の強さの残り。{@code afterglowSeconds} で減る)。 */
    private static final double[] residual = new double[N * N];
    private static final double[] WIND = new double[2];
    /** テクスチャと同じ中身([時刻][z][x][4])。 */
    private static float[] mirror;
    private static FloatBuffer rowUpload;
    private static FloatBuffer columnUpload;
    private static int texture = -1;
    private static long ticks;
    /** 開発用: 余韻の長さ(秒)をこのセッションだけ替える(負なら設計値)。 */
    static double afterglowOverride = -1.0;

    /** 今の行(時刻)。 */
    static long ticks() {
        return ticks;
    }

    static int texture() {
        return texture;
    }

    /** 新しいワールドに入ったとき。 */
    static void reset() {
        ticks = 0;
        java.util.Arrays.fill(keyX, Integer.MIN_VALUE);
        java.util.Arrays.fill(keyZ, Integer.MIN_VALUE);
        java.util.Arrays.fill(accX, 0.0);
        java.util.Arrays.fill(accZ, 0.0);
        java.util.Arrays.fill(residual, 0.0);
        if (mirror != null) {
            java.util.Arrays.fill(mirror, 0.0F);
            uploadAll();
        }
    }

    /** 1 tick 進める(描画のスレッド)。 */
    static void tick(Vec3 cam, double time) {
        ensureGl();
        ticks++;
        int c0x = Math.floorDiv((int) Math.floor(cam.x), CELL) - N / 2;
        int c0z = Math.floorDiv((int) Math.floor(cam.z), CELL) - N / 2;
        int row = (int) Math.floorMod(ticks, (long) HISTORY);
        // 新しく入った升の押された量は、カメラの升の値から始める(値の大きさを揃えるだけ。差しか使わない)。
        int camIndex = index(Math.floorMod(c0x + N / 2, N), Math.floorMod(c0z + N / 2, N));
        boolean camKnown = keyX[camIndex] == c0x + N / 2 && keyZ[camIndex] == c0z + N / 2;
        double startX = camKnown ? accX[camIndex] : 0.0;
        double startZ = camKnown ? accZ[camIndex] : 0.0;
        Wind wind = Wind.current();
        double afterglow = afterglowOverride >= 0.0 ? afterglowOverride
                : com.yayuy.overgrownvegetation.data.DesignData.atmosphere().wind().afterglowSeconds();
        double decay = afterglow > 0.0 ? Math.exp(-1.0 / (afterglow * 20.0)) : 0.0;
        for (int i = 0; i < N; i++) {
            for (int k = 0; k < N; k++) {
                int wx = c0x + i;
                int wz = c0z + k;
                int idx = index(Math.floorMod(wx, N), Math.floorMod(wz, N));
                if (keyX[idx] != wx || keyZ[idx] != wz) {
                    keyX[idx] = wx;
                    keyZ[idx] = wz;
                    accX[idx] = startX;
                    accZ[idx] = startZ;
                    residual[idx] = 0.0;
                    fillColumn(idx);
                }
                wind.velocity((wx + 0.5) * CELL, (wz + 0.5) * CELL, time, WIND);
                double w = Math.sqrt(WIND[0] * WIND[0] + WIND[1] * WIND[1]);
                double wc = Math.min(w, 1.2);
                double speed = 0.004 + 0.05 * wc + 0.11 * wc * wc * wc;
                if (w > 1.0E-6) {
                    accX[idx] += WIND[0] / w * speed;
                    accZ[idx] += WIND[1] / w * speed;
                }
                int m = (row * N * N + idx) * 4;
                mirror[m] = (float) accX[idx];
                mirror[m + 1] = (float) accZ[idx];
                mirror[m + 2] = (float) w;
                residual[idx] = Math.max(w, residual[idx] * decay);
                mirror[m + 3] = (float) residual[idx];
            }
        }
        // 押された量が大きくなりすぎたら(float の精度)、全部を同じだけずらす。差は変わらない。
        if (Math.abs(startX) > 1024.0 || Math.abs(startZ) > 1024.0) {
            float ox = (float) (Math.floor(startX / 256.0) * 256.0);
            float oz = (float) (Math.floor(startZ / 256.0) * 256.0);
            for (int i = 0; i < accX.length; i++) {
                accX[i] -= ox;
                accZ[i] -= oz;
            }
            for (int m = 0; m < mirror.length; m += 4) {
                mirror[m] -= ox;
                mirror[m + 1] -= oz;
            }
            uploadAll();
            return;
        }
        uploadRow(row);
    }

    private static int index(int x, int z) {
        return z * N + x;
    }

    private static void ensureGl() {
        if (texture >= 0) {
            return;
        }
        mirror = new float[N * N * HISTORY * 4];
        rowUpload = MemoryUtil.memAllocFloat(N * N * 4);
        columnUpload = MemoryUtil.memAllocFloat(HISTORY * 4);
        java.util.Arrays.fill(keyX, Integer.MIN_VALUE);
        java.util.Arrays.fill(keyZ, Integer.MIN_VALUE);
        texture = GL11.glGenTextures();
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, texture);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_WRAP_R, GL11.GL_REPEAT);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
        uploadAll();
    }

    private static void fillColumn(int idx) {
        float x = (float) accX[idx];
        float z = (float) accZ[idx];
        columnUpload.clear();
        for (int t = 0; t < HISTORY; t++) {
            int m = (t * N * N + idx) * 4;
            mirror[m] = x;
            mirror[m + 1] = z;
            mirror[m + 2] = 0.0F;
            mirror[m + 3] = 0.0F;
            columnUpload.put(x).put(z).put(0.0F).put(0.0F);
        }
        columnUpload.flip();
        bindForUpload();
        GL12.glTexSubImage3D(GL12.GL_TEXTURE_3D, 0, idx % N, idx / N, 0, 1, 1, HISTORY, GL11.GL_RGBA, GL11.GL_FLOAT, columnUpload);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
    }

    private static void uploadRow(int row) {
        rowUpload.clear();
        rowUpload.put(mirror, row * N * N * 4, N * N * 4);
        rowUpload.flip();
        bindForUpload();
        GL12.glTexSubImage3D(GL12.GL_TEXTURE_3D, 0, 0, 0, row, N, N, 1, GL11.GL_RGBA, GL11.GL_FLOAT, rowUpload);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
    }

    private static void uploadAll() {
        FloatBuffer all = MemoryUtil.memAllocFloat(mirror.length);
        try {
            all.put(mirror).flip();
            bindForUpload();
            GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, GL30.GL_RGBA32F, N, N, HISTORY, 0, GL11.GL_RGBA, GL11.GL_FLOAT, all);
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
        } finally {
            MemoryUtil.memFree(all);
        }
    }

    /** 3D テクスチャを結び、ほかの描画が残した読み込みの設定(行の長さ・飛ばし)を外す。 */
    private static void bindForUpload() {
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, texture);
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GL11.glPixelStorei(GL12.GL_UNPACK_IMAGE_HEIGHT, 0);
        GL11.glPixelStorei(GL12.GL_UNPACK_SKIP_IMAGES, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
    }

    private WindHistory() {
    }
}
