package com.yayuy.overgrownvegetation.client;

import software.bernie.geckolib.core.animation.EasingType;
import software.bernie.geckolib.core.keyframe.AnimationPoint;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;

/**
 * 【1.20.1 だけの物・G5 の助け手】GeckoLib 4.8.4 のキーフレーム({@code core.keyframe.Keyframe})の値を読む。
 *
 * <p>4.8.4 のキーの値は mclib の {@code com.eliotlash.mclib.math.IValue}(4.7.6 は GeckoLib の {@code MathValue})で、mclib は GeckoLib の jar の
 * 中の jar(jarJar)にだけ入っている = コンパイルのクラスパスに無い。{@code Keyframe} の型を書くだけでコンパイルが通らないので、
 * ここで名前から引いて呼ぶ(実行のときは GeckoLib が mclib を読み込んでいる)。土台がコンパイルのクラスパスに mclib を足したら、普通の呼び出しに戻せる。
 */
public final class PortKeyframes {

    private static final MethodHandle LENGTH;
    private static final MethodHandle START;
    private static final MethodHandle END;
    private static final MethodHandle GET;
    private static final MethodHandle POINT;
    private static final Class<?> CONSTANT;

    static {
        try {
            ClassLoader loader = PortKeyframes.class.getClassLoader();
            Class<?> keyframe = Class.forName("software.bernie.geckolib.core.keyframe.Keyframe", false, loader);
            Class<?> value = Class.forName("com.eliotlash.mclib.math.IValue", false, loader);
            CONSTANT = Class.forName("com.eliotlash.mclib.math.Constant", false, loader);
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            LENGTH = lookup.findVirtual(keyframe, "length", MethodType.methodType(double.class))
                    .asType(MethodType.methodType(double.class, Object.class));
            START = lookup.findVirtual(keyframe, "startValue", MethodType.methodType(value))
                    .asType(MethodType.methodType(Object.class, Object.class));
            END = lookup.findVirtual(keyframe, "endValue", MethodType.methodType(value))
                    .asType(MethodType.methodType(Object.class, Object.class));
            GET = lookup.findVirtual(value, "get", MethodType.methodType(double.class))
                    .asType(MethodType.methodType(double.class, Object.class));
            POINT = lookup.findConstructor(AnimationPoint.class,
                            MethodType.methodType(void.class, keyframe, double.class, double.class, double.class, double.class))
                    .asType(MethodType.methodType(AnimationPoint.class, Object.class, double.class, double.class, double.class, double.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private PortKeyframes() {
    }

    /**
     * 本流の {@code HordePoses#value} と同じ(= GeckoLib の {@code AnimationController#getAnimationPointAtTick} と同じ引き方。
     * キーの位置・回転の度 → ラジアン・x と y の向き)。{@code frames} は {@code KeyframeStack#xKeyframes()} などの一覧。
     */
    public static double value(List<?> frames, double tick, boolean rotation, boolean flip) {
        if (frames.isEmpty()) return 0.0D;
        try {
            double total = 0.0D;
            Object frame = frames.get(frames.size() - 1);
            double local = tick;
            for (Object f : frames) {
                double length = (double) LENGTH.invokeExact(f);
                total += length;
                if (total > tick) {
                    frame = f;
                    local = tick - (total - length);
                    break;
                }
            }
            Object startValue = (Object) START.invokeExact(frame);
            Object endValue = (Object) END.invokeExact(frame);
            double start = (double) GET.invokeExact(startValue), end = (double) GET.invokeExact(endValue);
            if (rotation) {
                if (!CONSTANT.isInstance(startValue)) {
                    start = Math.toRadians(start);
                    if (flip) start = -start;
                }
                if (!CONSTANT.isInstance(endValue)) {
                    end = Math.toRadians(end);
                    if (flip) end = -end;
                }
            }
            double length = (double) LENGTH.invokeExact(frame);
            AnimationPoint point = (AnimationPoint) POINT.invokeExact(frame, local, length, start, end);
            return EasingType.lerpWithOverride(point, null);
        } catch (Throwable t) {
            throw new IllegalStateException("GeckoLib のキーフレームを読めない", t);
        }
    }
}
