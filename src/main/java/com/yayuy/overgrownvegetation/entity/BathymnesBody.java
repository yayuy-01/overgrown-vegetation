package com.yayuy.overgrownvegetation.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

/**
 * {@link BathymnesEntity バティムネス}の体の形と、そこから世界の点を出す式(2026-10-01)。
 * <b>サーバーとクライアントで同じ式</b>を使う(当たり判定の部位・掴んだ相手の置き場所・ram の当たり)。
 * GameTest から直接呼べるよう、状態を持たない静的な関数だけにしてある。
 *
 * <h2>座標の約束</h2>
 * 長さは<b>モデルの単位</b>(1 = 1/16 ブロック × 体格)。モデルのファイルの座標のまま書く:
 * +X = 生き物の左、+Y = 上、+Z = 後ろ(殻の切っ先の側)。体格 2(mobs.json の {@code scale})で NOTES の「ブロック」の値になる
 * (ブロック = 単位 × 体格 / 16。NOTES の「前 4.9 ブロック」= 39.2 単位)。
 * 数値の出どころはすべて {@code tools/g5-models/out/bathymnes/NOTES.md}(モデルから測った値)。当たりの半径・湧きの深さは仮。
 *
 * <h2>泳ぐ上下の向き(ピッチ)</h2>
 * モデルでは {@code body} の骨(軸 (0, 11.5, 4))に掛ける(NOTES の推奨。{@code client/BathymnesModel})。
 * ここの {@link #toWorld} も同じ軸のまわりに回すので、部位・掴んだ相手が描いた体とずれない。
 * 向きは Minecraft の {@code xRot} と同じで、<b>+ が鼻先を下げる</b>。
 */
public final class BathymnesBody {

    /** {@code body} の骨の軸(泳ぐ上下の向きの回転の中心)。 */
    public static final double PIVOT_Y = 11.5D;
    public static final double PIVOT_Z = 4.0D;

    /** 当たり判定の箱(幅 × 高さ)の素の大きさ(ブロック、体格 1 のとき)。ModEntities / mobs.json と同じ値(体格 2 で 2.5 × 2.5)。 */
    public static final double BASE_BOX = 1.25D;

    // ------------------------------------------------------------------ 当たり判定の部位

    /**
     * 本体の箱(原点を中心に前後 ±10 単位)の外へはみ出す体に置く部位 {x, y(中心), z(中心), 一辺}(モデルの単位)。
     * 本体の箱は殻の前 1/3(殻の口の少し後ろ〜前半の殻)しか覆わないので、頭・腕の束と殻の後ろ半分を部位で足す(NOTES の案)。
     * 大きさは殻の太さ(殻の口 13.2 から 1 単位ごとに 0.23 細る)と頭・腕の束の太さに合わせた(仮)。
     */
    public static final double[][] PARTS = {
            {0.0D, 11.0D, -21.0D, 17.6D},   // 頭(殻の口 z=-14 から腕の付け根 -20.6 の先まで)
            {0.0D, 10.0D, -36.5D, 13.6D},   // 腕と触腕の束(静止で前 5.6 ブロック = -44.8 まで)
            {0.0D, 11.5D, 17.0D, 11.2D},    // 殻の後ろ半分・前
            {0.0D, 11.5D, 29.0D, 8.8D},     // 殻の後ろ半分・中
            {0.0D, 11.5D, 38.5D, 6.4D},     // 切っ先(静止 42.6)
    };

    // ------------------------------------------------------------------ ram(突き上げ)

    /** ram の当たりの時刻(1.07〜1.12 秒)の殻口の縁と切っ先(原点からの 上 / 後ろ)。切っ先は NOTES の実測、殻口は殻の長さ 56.6 と 60° から出した。 */
    public static final double RAM_MOUTH_UP = 7.9D;
    public static final double RAM_MOUTH_BACK = 0.0D;
    public static final double RAM_TIP_UP = 56.9D;
    public static final double RAM_TIP_BACK = 28.3D;
    /** 殻のこの割合(殻口 0 → 切っ先 1)から先に当たる(仮。手前は本体の箱と頭に近い)。 */
    public static final double RAM_HIT_FROM = 0.15D;
    /** 切っ先の先へ伸ばす長さ(単位、仮。突き抜けた勢い)。 */
    public static final double RAM_HIT_BEYOND = 4.0D;
    /** 当たりの半径(単位、仮)。殻の太さ(切っ先寄りで 4〜8)より少し太く取る。 */
    public static final double RAM_HIT_RADIUS = 10.0D;
    /** 位置取りでは殻のこの割合の点を相手に合わせる(仮。切っ先の少し手前 = 切っ先が相手を突き抜ける)。 */
    public static final double RAM_AIM_U = 0.85D;

    // ------------------------------------------------------------------ grab・drag

    /** grab の当たり(0.7 秒)の掌の中ほどの中点(前 4.9・上 4.0 ブロック@体格 2)。 */
    public static final double GRAB_HIT_FWD = 39.2D;
    public static final double GRAB_HIT_UP = 32.0D;
    /** grab の止め(0.92 秒〜)の相手の胴(前 4.2・上 3.4 ブロック@体格 2)。 */
    public static final double GRAB_HOLD_FWD = 33.6D;
    public static final double GRAB_HOLD_UP = 27.2D;
    /** grab の当たりの半径(単位、仮)。掌の中ほどの間が 1 ブロック@体格 2 なので、その外まで少し余裕を持たせる。 */
    public static final double GRAB_HIT_RADIUS = 14.0D;
    /** drag の相手の胴: 締めの山(0.35 秒)で前 3.85・上 0.4、ゆるめで前 4.1・上 0.1(ブロック@体格 2)。 */
    public static final double DRAG_FWD_LOOSE = 32.8D;
    public static final double DRAG_FWD_TIGHT = 30.8D;
    public static final double DRAG_UP_LOOSE = 0.8D;
    public static final double DRAG_UP_TIGHT = 3.2D;
    /** drag の締めの山(秒)と周期(秒)。 */
    public static final double DRAG_SQUEEZE_AT = 0.35D;
    public static final double DRAG_CYCLE = 1.6D;
    /** 相手の胴の高さ(足元から体の高さのこの割合。NOTES の 6「プレイヤーの足元 = 掌の中点 − 1.1 ブロック」≒ 1.8 × 0.6)。 */
    public static final double PREY_TORSO_RATIO = 0.6D;

    // ------------------------------------------------------------------ 湧き(仮)

    /** 湧くのに要る水の深さ(ブロック、体格 1 あたり)。体格 2 で 8 —— ram は足元の下 3.1〜上 7.1 を使う(NOTES)。 */
    public static final double SPAWN_MIN_DEPTH_PER_SCALE = 4.0D;
    /** 体のまわりで水であるべき半径(ブロック、体格 1 あたり)。体格 2 で 5.5 = 体の長さ 11 の半分。 */
    public static final double SPAWN_RING_PER_SCALE = 2.75D;

    private BathymnesBody() {
    }

    /**
     * モデルの点(ファイルの座標、単位)を、原点からの世界の差分(ブロック)へ。体の向き {@code yaw}、泳ぐ上下の向き {@code pitch}
     * (+ で鼻先が下がる。{@code body} の骨の軸まわり)、体格 {@code scale}。
     */
    public static Vec3 toWorld(double x, double y, double z, float yaw, float pitch, double scale) {
        double u = scale / 16.0D;
        double dy = y - PIVOT_Y;
        double dz = z - PIVOT_Z;
        float p = pitch * Mth.DEG_TO_RAD;
        double c = Mth.cos(p);
        double s = Mth.sin(p);
        // 鼻先(-Z)が下がる向き: 前の点 (dy 0, dz -1) → (dy -sin, dz -cos)
        double ry = dy * c + dz * s;
        double rz = dz * c - dy * s;
        double lx = x * u;
        double ly = (PIVOT_Y + ry) * u;
        double lz = (PIVOT_Z + rz) * u;
        float r = yaw * Mth.DEG_TO_RAD;
        double fx = -Mth.sin(r);
        double fz = Mth.cos(r);
        double leftX = Mth.cos(r);
        double leftZ = Mth.sin(r);
        // +Z(後ろ)は前の逆
        return new Vec3(leftX * lx - fx * lz, ly, leftZ * lx - fz * lz);
    }

    /** 水平の前(体の向き)。 */
    public static Vec3 forward(float yaw) {
        float r = yaw * Mth.DEG_TO_RAD;
        return new Vec3(-Mth.sin(r), 0.0D, Mth.cos(r));
    }

    // ------------------------------------------------------------------ ram

    /** ram の当たりの線分の始まり(原点からの差分、ブロック。ram の間は泳ぐ上下の向きを 0 にしている)。 */
    public static Vec3 ramHitStart(float yaw, double scale) {
        return ramPoint(RAM_HIT_FROM, 0.0D, yaw, scale);
    }

    /** ram の当たりの線分の終わり(切っ先の少し先)。 */
    public static Vec3 ramHitEnd(float yaw, double scale) {
        return ramPoint(1.0D, RAM_HIT_BEYOND, yaw, scale);
    }

    /** 殻の割合 {@code u}(殻口 0 → 切っ先 1)の点。{@code beyond} は切っ先の先へ伸ばす単位。 */
    static Vec3 ramPoint(double u, double beyond, float yaw, double scale) {
        double dUp = RAM_TIP_UP - RAM_MOUTH_UP;
        double dBack = RAM_TIP_BACK - RAM_MOUTH_BACK;
        double len = Math.sqrt(dUp * dUp + dBack * dBack);
        double up = RAM_MOUTH_UP + dUp * u + dUp / len * beyond;
        double back = RAM_MOUTH_BACK + dBack * u + dBack / len * beyond;
        double k = scale / 16.0D;
        Vec3 f = forward(yaw);
        return new Vec3(-f.x * back * k, up * k, -f.z * back * k);
    }

    /** ram の当たりの半径(ブロック)。 */
    public static double ramHitRadius(double scale) {
        return RAM_HIT_RADIUS * scale / 16.0D;
    }

    /**
     * ram の位置取り: 狙う点 {@code aim}(ボートなら船体、泳ぐ相手なら胴)を殻の {@link #RAM_AIM_U} の点で突ける原点。
     * 体は {@code yaw} を向き、相手は体の<b>後ろ斜め上</b>(NOTES の「ram の位置取り」)。原点は {@code minY}(海底の少し上)より下げない ——
     * 下げられないときは殻の手前の点で突く(割合 {@link #RAM_HIT_FROM} まで)。
     */
    public static Vec3 ramOrigin(Vec3 aim, float yaw, double scale, double minY) {
        double k = scale / 16.0D;
        double dUp = RAM_TIP_UP - RAM_MOUTH_UP;
        double dBack = RAM_TIP_BACK - RAM_MOUTH_BACK;
        double u = RAM_AIM_U;
        double y = aim.y - (RAM_MOUTH_UP + dUp * u) * k;
        if (y < minY) {
            y = minY;
            u = Mth.clamp(((aim.y - y) / k - RAM_MOUTH_UP) / dUp, RAM_HIT_FROM, 1.0D);
        }
        double back = (RAM_MOUTH_BACK + dBack * u) * k;
        Vec3 f = forward(yaw);
        return new Vec3(aim.x + f.x * back, y, aim.z + f.z * back);
    }

    // ------------------------------------------------------------------ grab・drag

    /** grab の当たりの点(掌の中ほどの中点、原点からの差分)。 */
    public static Vec3 grabPoint(float yaw, double scale) {
        return toWorld(0.0D, GRAB_HIT_UP, -GRAB_HIT_FWD, yaw, 0.0F, scale);
    }

    /** grab の位置取り: 掌の中ほどの中点が {@code aim} に来る原点(体は相手を向く)。海底より下げない。 */
    public static Vec3 grabOrigin(Vec3 aim, float yaw, double scale, double minY) {
        Vec3 off = grabPoint(yaw, scale);
        return new Vec3(aim.x - off.x, Math.max(minY, aim.y - off.y), aim.z - off.z);
    }

    public static double grabHitRadius(double scale) {
        return GRAB_HIT_RADIUS * scale / 16.0D;
    }

    /**
     * 掴んだ相手の胴の位置(モデルの単位で {前, 上})。grab の当たりから止めまでは掌の中点を追い、drag は grab の止めから
     * drag の締めの拍へ {@code dragBlend} tick で寄せる(GeckoLib の補間と同じ長さ)。
     *
     * @param dragging drag 中か(false なら grab の当たりの後)
     * @param age      その動作を始めてからの tick
     * @param grabHitTick grab の当たりの tick、{@code grabHoldTick} 止めの tick
     */
    public static double[] preyTorso(boolean dragging, float age, int grabHitTick, int grabHoldTick, int dragBlend) {
        if (!dragging) {
            float t = Mth.clamp((age - grabHitTick) / (float) Math.max(1, grabHoldTick - grabHitTick), 0.0F, 1.0F);
            return new double[]{Mth.lerp(t, GRAB_HIT_FWD, GRAB_HOLD_FWD), Mth.lerp(t, GRAB_HIT_UP, GRAB_HOLD_UP)};
        }
        double loopSec = Math.max(0.0D, age - dragBlend) / 20.0D;
        double phase = (loopSec % DRAG_CYCLE);
        double squeeze = 0.5D + 0.5D * Math.cos((phase - DRAG_SQUEEZE_AT) / DRAG_CYCLE * Math.PI * 2.0D);
        double fwd = Mth.lerp(squeeze, DRAG_FWD_LOOSE, DRAG_FWD_TIGHT);
        double up = Mth.lerp(squeeze, DRAG_UP_LOOSE, DRAG_UP_TIGHT);
        double b = smoothstep(age / (double) Math.max(1, dragBlend));
        return new double[]{Mth.lerp(b, GRAB_HOLD_FWD, fwd), Mth.lerp(b, GRAB_HOLD_UP, up)};
    }

    static double smoothstep(double u) {
        double x = Mth.clamp(u, 0.0D, 1.0D);
        return x * x * (3.0D - 2.0D * x);
    }

    /** 点 {@code p} と線分 {@code a}〜{@code b} の距離。 */
    public static double segmentDistance(Vec3 p, Vec3 a, Vec3 b) {
        Vec3 ab = b.subtract(a);
        double len2 = ab.lengthSqr();
        double t = len2 < 1.0E-9D ? 0.0D : Mth.clamp(p.subtract(a).dot(ab) / len2, 0.0D, 1.0D);
        return p.distanceTo(a.add(ab.scale(t)));
    }

    // ------------------------------------------------------------------ 湧き

    /**
     * 湧いてよい水か(自然湧きの最後の関門 {@link OvergrownSpawnCheck})。{@code (x, y, z)} を含む水の柱が
     * {@link #SPAWN_MIN_DEPTH_PER_SCALE} × 体格 以上の深さで、体の中ほどの高さの 8 方向・半身の長さ({@link #SPAWN_RING_PER_SCALE} × 体格)
     * とその半分の点が開けた水なら、体を置く高さ(足元。底から 1 マス上・水面から体 + 1 マス下の間の下寄り)を返す。だめなら NaN。
     *
     * <p>読むのは柱の上下(64 マスまで)と 16 点だけ。<b>読み込み済みのチャンクだけ</b>を読む(未読み込みは「水でない」扱い。
     * {@code hasChunk} は読み込みの途中でも true で、そのまま読むと生成を待って止まる —— 湧かせ役の約束)。
     * 高さの地図は使わない(GameTest のテスト場では天井のバリアの上を答える)。
     */
    public static double spawnHeight(Level level, double x, int y, double z, double scale) {
        int bx = Mth.floor(x);
        int bz = Mth.floor(z);
        if (!openWater(level, bx, y, bz)) {
            return Double.NaN;
        }
        int bottom = y;
        while (y - bottom < 64 && openWater(level, bx, bottom - 1, bz)) {
            bottom--;
        }
        int top = y;
        while (top - y < 64 && openWater(level, bx, top + 1, bz)) {
            top++;
        }
        int depth = top - bottom + 1;
        if (depth < SPAWN_MIN_DEPTH_PER_SCALE * scale) {
            return Double.NaN;
        }
        double bodyH = BASE_BOX * scale;
        double lo = bottom + 1.0D;
        double hi = top + 1.0D - bodyH - 1.0D;
        if (hi < lo) {
            return Double.NaN;
        }
        double feet = lo + (hi - lo) * 0.35D;
        double cy = feet + bodyH * 0.5D;
        double r = SPAWN_RING_PER_SCALE * scale;
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4.0D;
            for (double rr : new double[]{r, r * 0.5D}) {
                if (!openWater(level, Mth.floor(x + Math.cos(a) * rr), Mth.floor(cy), Mth.floor(z + Math.sin(a) * rr))) {
                    return Double.NaN;
                }
            }
        }
        return feet;
    }

    /** 開けた水のマスか(水で、固い形が無い。海藻・昆布は水に数える)。読み込み済みのチャンクだけ。 */
    public static boolean openWater(Level level, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        BlockState state;
        if (level instanceof ServerLevel server) {
            LevelChunk chunk = server.getChunkSource().getChunkNow(x >> 4, z >> 4);
            if (chunk == null) {
                return false;
            }
            state = chunk.getBlockState(pos);
        } else {
            if (!level.isLoaded(pos)) {
                return false;
            }
            state = level.getBlockState(pos);
        }
        return state.getFluidState().is(FluidTags.WATER) && state.getCollisionShape(level, pos).isEmpty();
    }
}
