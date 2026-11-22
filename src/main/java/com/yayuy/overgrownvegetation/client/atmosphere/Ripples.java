package com.yayuy.overgrownvegetation.client.atmosphere;

import com.yayuy.overgrownvegetation.data.AtmosphereLife;
import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>波紋の部品</b>(2026-10-31 作り直し。ユーザー「水が落ちた時の波紋というより白い輪が出ているだけに見える」)。2 種類:
 * <ul>
 *     <li>{@link #drop} 大きな滴の波紋: 葉から落ちた滴の着水・水の生き物の泡が水面に上がったとき。真ん中が一瞬盛り上がり、同心の帯が
 *     {@code dropRipple.rings} 本外へ広がる。帯は「明るい山 + その内側の暗い谷」の太く柔らかい組(白い線ではなく、水面の反射と屈折のゆがみ)。
 *     いちばん外の帯が速く、内ほど遅いので、帯の間隔は外ほど広い(水の波は短い波ほど速い)。振幅は外の帯ほど小さく、広がるほど薄く太く、
 *     {@code seconds} 秒で消える。</li>
 *     <li>{@link #rain} 雨の波紋: 小さな滴がたくさん降るとき。1 組の山と谷だけ、小さく(直径 {@code rainRipple.sizeMin}〜{@code sizeMax})短命。
 *     真ん中に小さな跳ねの点。<b>水面専用にしない</b>: 任意の平面(位置・向き・大きさ・寿命)に出せるので、後で水たまり・水たまりに落ちる雨・
 *     水たまりを踏んだときに使い回す。</li>
 * </ul>
 * 描くのは {@link LifeSprites} の間({@link Atmosphere} の AFTER_PARTICLES)。値は節 {@code dropRipple}・{@code rainRipple}(仮決め)。
 */
public final class Ripples {

    /** 一度に置いておく数の上限(雨の波紋が増えすぎないように)。 */
    private static final int MAX = 480;
    private static final List<Ripple> LIST = new ArrayList<>();

    /** 1 つ。面の 2 本の軸 (u, v)・直径 size・生まれる時刻(先の時刻なら待つ)と寿命(tick)・雨の波紋か。 */
    private record Ripple(double x, double y, double z, float ux, float uy, float uz, float vx, float vy, float vz, float size, long born,
                          float life, boolean rain) {
    }

    private Ripples() {
    }

    static void clear() {
        LIST.clear();
    }

    static int count() {
        return LIST.size();
    }

    static boolean full() {
        return LIST.size() >= MAX;
    }

    /** 大きな滴の波紋を水平な面 (x, y, z) に。bornAt はゲームの tick(先なら着水を待つ)。scale は大きさの倍率(泡は小さめ)。 */
    public static void drop(ClientLevel level, double x, double y, double z, long bornAt, float scale) {
        if (LIST.size() >= MAX) {
            return;
        }
        AtmosphereLife.DropRippleParams p = DesignData.atmosphere().dropRipple();
        LIST.add(new Ripple(x, y, z, 1.0F, 0.0F, 0.0F, 0.0F, 0.0F, 1.0F, scale * p.scale(), bornAt, p.seconds() * 20.0F, false));
    }

    /**
     * 雨の波紋を任意の面に。法線 (nx, ny, nz)(水平な水面・水たまりなら (0, 1, 0))・直径 size(ブロック)・寿命 seconds(秒)。
     * 大きさと寿命は呼ぶ側が決める(水面の雨は {@link #rainOnWater})。
     */
    public static void rain(ClientLevel level, double x, double y, double z, float nx, float ny, float nz, float size, float seconds) {
        if (LIST.size() >= MAX) {
            return;
        }
        float len = Mth.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1.0E-4F) {
            nx = 0.0F;
            ny = 1.0F;
            nz = 0.0F;
        } else {
            nx /= len;
            ny /= len;
            nz /= len;
        }
        // 面の中の 2 本の軸: 法線に直交する u と、法線 × u の v。
        float ax = Math.abs(ny) < 0.9F ? 0.0F : 1.0F;
        float ay = Math.abs(ny) < 0.9F ? 1.0F : 0.0F;
        float ux = ay * nz;
        float uy = -ax * nz;
        float uz = ax * ny - ay * nx;
        float ul = Mth.sqrt(ux * ux + uy * uy + uz * uz);
        ux /= ul;
        uy /= ul;
        uz /= ul;
        float vx = ny * uz - nz * uy;
        float vy = nz * ux - nx * uz;
        float vz = nx * uy - ny * ux;
        LIST.add(new Ripple(x, y, z, ux, uy, uz, vx, vy, vz, size, level.getGameTime(), Math.max(1.0F, seconds * 20.0F), true));
    }

    /** 水平な水面に雨の波紋 1 つ(大きさと寿命は節 rainRipple の幅から)。 */
    static void rainOnWater(ClientLevel level, double x, double y, double z, float r1, float r2) {
        AtmosphereLife.RainRippleParams p = DesignData.atmosphere().rainRipple();
        rain(level, x, y, z, 0.0F, 1.0F, 0.0F, Mth.lerp(r1, p.sizeMin(), p.sizeMax()), Mth.lerp(r2, p.secondsMin(), p.secondsMax()));
    }

    static void tick(ClientLevel level) {
        long now = level.getGameTime();
        LIST.removeIf(r -> now - r.born > r.life);
    }

    /** LifeSprites の begin と end の間で。 */
    static void render(ClientLevel level, float partialTick) {
        if (LIST.isEmpty()) {
            return;
        }
        AtmosphereLife.DropRippleParams dp = DesignData.atmosphere().dropRipple();
        AtmosphereLife.RainRippleParams rp = DesignData.atmosphere().rainRipple();
        double now = level.getGameTime() + partialTick;
        for (Ripple r : LIST) {
            double age = now - r.born;
            if (age < 0.0) {
                continue;
            }
            float t = (float) (age / r.life);
            if (t >= 1.0F) {
                continue;
            }
            if (r.rain) {
                renderRain(r, t, rp);
            } else {
                renderDrop(r, (float) (age / 20.0), t, dp);
            }
        }
    }

    /** 明るい山と、そのすぐ内側の暗い谷を 1 組。 */
    private static void crest(Ripple r, float radius, float width, float bright, float dark) {
        LifeSprites.ring(r.x, r.y, r.z, r.ux, r.uy, r.uz, r.vx, r.vy, r.vz, radius, width, 0.86F, 0.93F, 0.90F, bright);
        if (radius - width * 1.1F > 0.0F) {
            LifeSprites.ring(r.x, r.y, r.z, r.ux, r.uy, r.uz, r.vx, r.vy, r.vz, radius - width * 1.1F, width, 0.02F, 0.06F, 0.05F, dark);
        }
    }

    private static void renderDrop(Ripple r, float sec, float t, AtmosphereLife.DropRippleParams p) {
        float fade = 1.0F - t * t;
        // 真ん中の盛り上がり(最初の 0.25 秒、明るい点 → 暗いくぼみ)。
        if (sec < 0.25F) {
            float k = sec / 0.25F;
            LifeSprites.ring(r.x, r.y, r.z, r.ux, r.uy, r.uz, r.vx, r.vy, r.vz, 0.0F, 0.05F * r.size + 0.04F * r.size * k, 0.90F, 0.96F, 0.94F,
                    p.alpha() * (1.0F - k));
        } else if (sec < 0.6F) {
            float k = (sec - 0.25F) / 0.35F;
            LifeSprites.ring(r.x, r.y, r.z, r.ux, r.uy, r.uz, r.vx, r.vy, r.vz, 0.0F, 0.07F * r.size, 0.02F, 0.06F, 0.05F,
                    p.darkAlpha() * 0.6F * (1.0F - k));
        }
        int n = p.rings();
        for (int i = 0; i < n; i++) {
            // いちばん外(i = 0)が速い。内ほど 0.72 倍ずつ遅い = 間隔は外ほど広い。少し遅れて生まれる。
            float speed = p.speed() * r.size * (float) Math.pow(0.72, i);
            float start = 0.04F * i;
            float radius = Math.max(0.0F, sec - start) * speed;
            if (radius < 0.02F) {
                continue;
            }
            // 外の帯ほど振幅が小さい。広がるほど太く薄く。
            float amp = n == 1 ? 1.0F : 0.55F + 0.45F * i / (n - 1);
            float width = 0.025F * r.size + p.widthGrow() * radius;
            float spread = 1.0F / (1.0F + radius * 0.6F);
            crest(r, radius, width, p.alpha() * amp * fade * spread, p.darkAlpha() * amp * fade * spread);
        }
    }

    private static void renderRain(Ripple r, float t, AtmosphereLife.RainRippleParams p) {
        float ease = 1.0F - (1.0F - t) * (1.0F - t);
        float radius = r.size * 0.5F * ease;
        float width = 0.012F + 0.25F * radius;
        float fade = 1.0F - t;
        if (t < 0.25F) {
            // 真ん中の跳ねの点。
            LifeSprites.ring(r.x, r.y, r.z, r.ux, r.uy, r.uz, r.vx, r.vy, r.vz, 0.0F, 0.02F + 0.03F * r.size, 0.92F, 0.97F, 0.95F,
                    p.splash() * (1.0F - t / 0.25F));
        }
        if (radius > 0.01F) {
            crest(r, radius, width, p.alpha() * fade, p.darkAlpha() * fade);
        }
    }
}
