package com.yayuy.overgrownvegetation.client.crowd;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import software.bernie.geckolib.animation.Animation;
import software.bernie.geckolib.animation.EasingType;
import software.bernie.geckolib.animation.keyframe.AnimationPoint;
import software.bernie.geckolib.animation.keyframe.BoneAnimation;
import software.bernie.geckolib.animation.keyframe.Keyframe;
import software.bernie.geckolib.animation.keyframe.KeyframeStack;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.loading.math.MathValue;
import software.bernie.geckolib.loading.math.value.Constant;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 遠くの群れ(仮想の群れの身代わり)が分け合う姿勢(2026-11-18 大群の軽量化 C。ユーザー「粗いモデルだけを選んだ人が重くならないように」)。
 *
 * <p>1 種につき「歩き」と「待ち」のアニメを、それぞれ {@value #PHASES} の位相で 1 フレームに 1 回だけ組み、骨ごとの行列(モデルの中の位置)にする。
 * 一員は群れの id と番号から決まる位相を 1 つ選んで使い回す(1 体ごとにアニメを計算しない)。GeckoLib のキーの補間({@link EasingType})を
 * そのまま使うので、キーの間の曲線は本物と同じ。<b>本物の Mob の骨({@link GeoBone} の値)には触らない</b>(同じモデルを本物も使っているため)。</p>
 *
 * <p>粗いところ: アニメの切り替えの補間・歩く速さに合わせた再生速度・手続きの動き(首を向ける等)は無い。遠くでだけ使う。</p>
 */
public final class HordePoses {

    /** 位相の数(1 つのアニメにつき)。 */
    public static final int PHASES = 8;
    /** 1 本の骨 = 位置の 3 行 + 法線の 3 列(RGBA32F の 6 texel)。 */
    static final int FLOATS_PER_BONE = 24;

    private static final String[] MOVING = {"walk", "move", "crawl", "run", "fly", "idle"};
    private static final String[] STILL = {"idle", "walk"};

    final BakedGeoModel model;
    final List<GeoBone> bones = new ArrayList<>();
    private final List<GeoBone> parents = new ArrayList<>();
    @Nullable
    private final Animation moving, still;
    /** [アニメ 2 つ × 位相 × 骨 × 24]。 */
    final float[] data;
    private final Map<String, BoneAnimation> movingByBone = new HashMap<>(), stillByBone = new HashMap<>();
    private final PoseStack stack = new PoseStack();

    HordePoses(BakedGeoModel model, java.util.function.Function<String, Animation> anims) {
        this.model = model;
        for (GeoBone b : model.topLevelBones()) collect(b, null);
        this.moving = pick(anims, MOVING);
        this.still = pick(anims, STILL);
        index(this.moving, this.movingByBone);
        index(this.still, this.stillByBone);
        this.data = new float[2 * PHASES * this.bones.size() * FLOATS_PER_BONE];
    }

    /** 選んだアニメの名前(確かめ用)。 */
    String describe() {
        return (this.moving == null ? "-" : this.moving.name()) + "/" + (this.still == null ? "-" : this.still.name());
    }

    int slots() {
        return 2 * PHASES;
    }

    int boneCount() {
        return this.bones.size();
    }

    /** 位相の番号(0〜2×PHASES-1)。動いている群れは前半、止まっている群れは後半。 */
    public static int slot(boolean moving, long id, int index) {
        long h = (id * 0x9E3779B97F4A7C15L) ^ (index * 0xC2B2AE3D27D4EB4FL);
        h ^= h >>> 29;
        int p = (int) Math.floorMod(h, PHASES);
        return moving ? p : PHASES + p;
    }

    /** 今の時刻(tick + 部分 tick)の姿勢を全部の位相で組み直す。 */
    void update(double ticks) {
        build(this.moving, this.movingByBone, ticks, 0);
        build(this.still, this.stillByBone, ticks, PHASES);
    }

    private void build(@Nullable Animation anim, Map<String, BoneAnimation> byBone, double ticks, int firstSlot) {
        double len = anim == null ? 1.0D : Math.max(1.0D, anim.length());
        for (int p = 0; p < PHASES; p++) {
            double t = anim == null ? 0.0D : (ticks + p * len / PHASES) % len;
            int at = (firstSlot + p) * this.bones.size() * FLOATS_PER_BONE;
            this.stack.setIdentity();
            for (GeoBone top : this.model.topLevelBones()) walk(top, byBone, t, at);
        }
    }

    private int cursor;

    private void walk(GeoBone bone, Map<String, BoneAnimation> byBone, double t, int base) {
        if (bone == this.model.topLevelBones().get(0)) this.cursor = 0;
        float rx = bone.getInitialSnapshot().getRotX(), ry = bone.getInitialSnapshot().getRotY(), rz = bone.getInitialSnapshot().getRotZ();
        float px = bone.getInitialSnapshot().getOffsetX(), py = bone.getInitialSnapshot().getOffsetY(), pz = bone.getInitialSnapshot().getOffsetZ();
        float sx = bone.getInitialSnapshot().getScaleX(), sy = bone.getInitialSnapshot().getScaleY(), sz = bone.getInitialSnapshot().getScaleZ();
        BoneAnimation ba = byBone.get(bone.getName());
        if (ba != null) {
            KeyframeStack<Keyframe<MathValue>> r = ba.rotationKeyFrames(), pos = ba.positionKeyFrames(), sc = ba.scaleKeyFrames();
            if (!r.xKeyframes().isEmpty()) {
                rx += (float) value(r.xKeyframes(), t, true, true);
                ry += (float) value(r.yKeyframes(), t, true, true);
                rz += (float) value(r.zKeyframes(), t, true, false);
            }
            if (!pos.xKeyframes().isEmpty()) {
                px = (float) value(pos.xKeyframes(), t, false, false);
                py = (float) value(pos.yKeyframes(), t, false, false);
                pz = (float) value(pos.zKeyframes(), t, false, false);
            }
            if (!sc.xKeyframes().isEmpty()) {
                sx = (float) value(sc.xKeyframes(), t, false, false);
                sy = (float) value(sc.yKeyframes(), t, false, false);
                sz = (float) value(sc.zKeyframes(), t, false, false);
            }
        }
        PoseStack ps = this.stack;
        ps.pushPose();
        // GeoEntityRenderer#renderRecursively と同じ積み方(RenderUtil の translateMatrixToBone … translateAwayFromPivotPoint)を、骨の値の代わりに上の値で
        ps.translate(-px / 16.0F, py / 16.0F, pz / 16.0F);
        ps.translate(bone.getPivotX() / 16.0F, bone.getPivotY() / 16.0F, bone.getPivotZ() / 16.0F);
        if (rz != 0.0F) ps.mulPose(Axis.ZP.rotation(rz));
        if (ry != 0.0F) ps.mulPose(Axis.YP.rotation(ry));
        if (rx != 0.0F) ps.mulPose(Axis.XP.rotation(rx));
        ps.scale(sx, sy, sz);
        ps.translate(-bone.getPivotX() / 16.0F, -bone.getPivotY() / 16.0F, -bone.getPivotZ() / 16.0F);
        int at = base + this.cursor * FLOATS_PER_BONE;
        this.cursor++;
        boolean visible = !bone.isHidden();
        write(ps.last(), visible, at);
        if (!bone.isHidingChildren()) {
            for (GeoBone c : bone.getChildBones()) walk(c, byBone, t, base);
        } else {
            int skip = countBelow(bone);
            for (int i = 0; i < skip; i++) {
                write(null, false, base + this.cursor * FLOATS_PER_BONE);
                this.cursor++;
            }
        }
        ps.popPose();
    }

    private static int countBelow(GeoBone bone) {
        int n = 0;
        for (GeoBone c : bone.getChildBones()) n += 1 + countBelow(c);
        return n;
    }

    private void write(@Nullable PoseStack.Pose pose, boolean visible, int at) {
        float[] d = this.data;
        if (!visible || pose == null) {
            java.util.Arrays.fill(d, at, at + FLOATS_PER_BONE, 0.0F);
            return;
        }
        org.joml.Matrix4f m = pose.pose();
        org.joml.Matrix3f n = pose.normal();
        d[at] = m.m00(); d[at + 1] = m.m10(); d[at + 2] = m.m20(); d[at + 3] = m.m30();
        d[at + 4] = m.m01(); d[at + 5] = m.m11(); d[at + 6] = m.m21(); d[at + 7] = m.m31();
        d[at + 8] = m.m02(); d[at + 9] = m.m12(); d[at + 10] = m.m22(); d[at + 11] = m.m32();
        d[at + 12] = n.m00(); d[at + 13] = n.m01(); d[at + 14] = n.m02(); d[at + 15] = 0.0F;
        d[at + 16] = n.m10(); d[at + 17] = n.m11(); d[at + 18] = n.m12(); d[at + 19] = 0.0F;
        d[at + 20] = n.m20(); d[at + 21] = n.m21(); d[at + 22] = n.m22(); d[at + 23] = 0.0F;
    }

    /** GeckoLib の AnimationController#getAnimationPointAtTick と同じ引き方(キーの位置・回転の度 → ラジアン・x と y の向き)。 */
    private static double value(List<Keyframe<MathValue>> frames, double tick, boolean rotation, boolean flip) {
        if (frames.isEmpty()) return 0.0D;
        double total = 0.0D;
        Keyframe<MathValue> frame = frames.get(frames.size() - 1);
        double local = tick;
        for (Keyframe<MathValue> f : frames) {
            total += f.length();
            if (total > tick) {
                frame = f;
                local = tick - (total - f.length());
                break;
            }
        }
        double start = frame.startValue().get(), end = frame.endValue().get();
        if (rotation) {
            if (!(frame.startValue() instanceof Constant)) {
                start = Math.toRadians(start);
                if (flip) start = -start;
            }
            if (!(frame.endValue() instanceof Constant)) {
                end = Math.toRadians(end);
                if (flip) end = -end;
            }
        }
        return EasingType.lerpWithOverride(new AnimationPoint(frame, local, frame.length(), start, end), null);
    }

    @Nullable
    private static Animation pick(java.util.function.Function<String, Animation> anims, String[] names) {
        for (String n : names) {
            try {
                Animation a = anims.apply(n);
                if (a != null) return a;
            } catch (RuntimeException ignored) {
                // 無いアニメは GeckoLib が例外を投げることがある
            }
        }
        return null;
    }

    private static void index(@Nullable Animation a, Map<String, BoneAnimation> out) {
        if (a == null) return;
        for (BoneAnimation ba : a.boneAnimations()) out.put(ba.boneName(), ba);
    }

    private void collect(GeoBone bone, @Nullable GeoBone parent) {
        this.bones.add(bone);
        this.parents.add(parent);
        for (GeoBone c : bone.getChildBones()) collect(c, bone);
    }
}
