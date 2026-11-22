package com.yayuy.overgrownvegetation.client.atmosphere;

import com.yayuy.overgrownvegetation.client.UnderwaterMoteParticle;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.client.ParticleStatus;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;

/**
 * <b>水中を漂う粒を足す</b>(2026-11-13、水まわり ⑤ ユーザー選択「漂う粒: バニラの水は白い粒が少し、汚染の水は胞子・泥炭・藻の粒が多く。ドット絵」)。
 * 潜っている間、カメラのまわり {@link #RADIUS} ブロックの水の中に、バニラの水中の粒({@code minecraft:underwater}。この MOD では
 * {@link UnderwaterMoteParticle} が色を付ける)を毎 tick 足す。数は過繁茂の水 {@link #OVERGROWN}・繁茂の水 {@link #OVERGROWTH}・
 * ほかの水 {@link #CLEAR}(1 tick あたり)。粒の設定が「減らす」なら半分、「最小」なら足さない。数はすべて仮決め。
 */
final class UnderwaterMotes {

    static final float OVERGROWN = 4.0F;
    static final float OVERGROWTH = 2.5F;
    static final float CLEAR = 0.6F;
    static final int RADIUS = 8;
    /** 水底の近くへ置き直す割合と、水底からの高さの幅(ブロック)。仮決め(11-13)。 */
    static final float BOTTOM_SHARE = 0.6F;
    static final double BOTTOM_BAND = 2.5;

    private static final RandomSource RANDOM = RandomSource.create();
    private static float carry;

    static void tick(ClientLevel level) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameRenderer.getMainCamera().getFluidInCamera() != FogType.WATER || !com.yayuy.overgrownvegetation.client.VisualQuality.underwater()) {
            carry = 0.0F;
            STREAMS.clear();
            return;
        }
        ParticleStatus status = mc.options.particles().get();
        if (status == ParticleStatus.MINIMAL) {
            return;
        }
        Vec3 eye = mc.gameRenderer.getMainCamera().getPosition();
        var biome = level.getBiome(BlockPos.containing(eye));
        float rate = biome.is(ModBiomes.OVERGROWN) || biome.is(ModBiomes.OVERGROWN_OCEAN) || biome.is(ModBiomes.OVERGROWN_RIVER) ? OVERGROWN
                : (UnderwaterMoteParticle.overgrownWater(biome) ? OVERGROWTH : CLEAR);
        if (status == ParticleStatus.DECREASED) {
            rate *= 0.5F;
        }
        // 11-13: 水中の浮遊物({@link WaterMotes}、シェーダーの小さな立方体)が動いている間は粒を足さない(泡と光るものは残す)。
        carry = WaterMotes.active() ? 0.0F : carry + rate;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        while (carry >= 1.0F) {
            carry -= 1.0F;
            double x = eye.x + (RANDOM.nextDouble() * 2.0 - 1.0) * RADIUS;
            double y = eye.y + (RANDOM.nextDouble() * 2.0 - 1.0) * RADIUS * 0.6;
            double z = eye.z + (RANDOM.nextDouble() * 2.0 - 1.0) * RADIUS;
            if (!level.getFluidState(pos.set(x, y, z)).is(FluidTags.WATER)) {
                continue;
            }
            // 11-13(水まわり ⑥ の直し「場所に紐づける」): 泥の粒は水底の近くに濃い。BOTTOM_SHARE は水底から BOTTOM_BAND ブロックの中へ置き直す。
            if (RANDOM.nextFloat() < BOTTOM_SHARE) {
                for (int i = 1; i <= 16; i++) {
                    if (!level.getFluidState(pos.set(x, y - i, z)).is(FluidTags.WATER)) {
                        y = Math.floor(y) - i + 1.0 + RANDOM.nextDouble() * BOTTOM_BAND;
                        break;
                    }
                }
            }
            level.addParticle(ParticleTypes.UNDERWATER, x, y, z, 0.0, 0.0, 0.0);
        }
        float scale = status == ParticleStatus.DECREASED ? 0.5F : 1.0F;
        boolean murky = UnderwaterMoteParticle.overgrownWater(biome);
        bubbles(level, eye, (murky ? BUBBLE_MURKY : BUBBLE_CLEAR) * scale, pos);
        glow(level, eye, scale, pos);
    }

    // ---------------------------------------------------------------- 底から上がる泡(11-13 水まわり ⑥)

    /**
     * 底から上がる泡(ユーザー選択。泥炭や腐った葉が出すガス。汚染の水ほど多い): ときどき、カメラのまわり {@link #BUBBLE_RADIUS} ブロックの水底の 1 点から、
     * {@link #STREAM_TICKS} tick の間ぽこぽこと泡(バニラの水中の泡)が上がる。1 tick あたり新しい泡の筋が出る確率は汚染の水 {@link #BUBBLE_MURKY}・
     * ほかの水 {@link #BUBBLE_CLEAR}。泡はバニラの通り水の外で消える。数値は仮決め。
     */
    static final float BUBBLE_MURKY = 0.12F;
    static final float BUBBLE_CLEAR = 0.03F;
    static final int BUBBLE_RADIUS = 10;
    static final int STREAM_TICKS = 30;
    static final int MAX_STREAMS = 6;

    private record Stream(double x, double y, double z, int[] left) {
    }

    private static final java.util.List<Stream> STREAMS = new java.util.ArrayList<>();

    private static void bubbles(ClientLevel level, Vec3 eye, float chance, BlockPos.MutableBlockPos pos) {
        if (STREAMS.size() < MAX_STREAMS && RANDOM.nextFloat() < chance) {
            int x = (int) Math.floor(eye.x + (RANDOM.nextDouble() * 2.0 - 1.0) * BUBBLE_RADIUS);
            int z = (int) Math.floor(eye.z + (RANDOM.nextDouble() * 2.0 - 1.0) * BUBBLE_RADIUS);
            int y = (int) Math.floor(eye.y);
            // 下へたどって水底(水の下の最初の水でないブロック)を探す。
            if (level.getFluidState(pos.set(x, y, z)).is(FluidTags.WATER)) {
                for (int i = 0; i < 24; i++) {
                    if (!level.getFluidState(pos.set(x, y - i - 1, z)).is(FluidTags.WATER)) {
                        STREAMS.add(new Stream(x + 0.2 + RANDOM.nextDouble() * 0.6, y - i, z + 0.2 + RANDOM.nextDouble() * 0.6, new int[]{STREAM_TICKS}));
                        break;
                    }
                }
            }
        }
        for (int i = STREAMS.size() - 1; i >= 0; i--) {
            Stream s = STREAMS.get(i);
            if (--s.left()[0] <= 0 || s.left()[0] > STREAM_TICKS) {
                STREAMS.remove(i);
                continue;
            }
            if (RANDOM.nextInt(3) == 0) {
                level.addParticle(ParticleTypes.BUBBLE, s.x() + (RANDOM.nextDouble() - 0.5) * 0.15, s.y() + 0.05, s.z() + (RANDOM.nextDouble() - 0.5) * 0.15,
                        WaterCurrent.x() * 4.0, 0.08 + RANDOM.nextDouble() * 0.04, WaterCurrent.z() * 4.0); // 流れに流される(11-13)
            }
        }
    }

    // ---------------------------------------------------------------- 深い所の光るもの(11-13 水まわり ⑥)

    /**
     * 深い所の光るもの(ユーザー選択。深みや洞窟の水に、昔からいた光る微生物がぽつぽつ光る。昼でも暗い所だけ): 目の所の明るさ
     * (ブロックの光と空の光の大きい方、昼夜込み)が {@link #GLOW_DARK_FROM} 以下で出始め、0 で {@link #GLOW_RATE} 粒 / tick。
     * 粒はバニラの光るイカの光の粒(光を受けずに光る)。数値は仮決め。
     */
    static final int GLOW_DARK_FROM = 7;
    static final float GLOW_RATE = 1.2F;
    static final int GLOW_RADIUS = 10;
    private static float glowCarry;

    private static void glow(ClientLevel level, Vec3 eye, float scale, BlockPos.MutableBlockPos pos) {
        int light = level.getMaxLocalRawBrightness(BlockPos.containing(eye));
        if (light > GLOW_DARK_FROM) {
            glowCarry = 0.0F;
            return;
        }
        glowCarry += GLOW_RATE * scale * (1.0F - light / (float) (GLOW_DARK_FROM + 1));
        while (glowCarry >= 1.0F) {
            glowCarry -= 1.0F;
            double x = eye.x + (RANDOM.nextDouble() * 2.0 - 1.0) * GLOW_RADIUS;
            double y = eye.y + (RANDOM.nextDouble() * 2.0 - 1.0) * GLOW_RADIUS * 0.6;
            double z = eye.z + (RANDOM.nextDouble() * 2.0 - 1.0) * GLOW_RADIUS;
            if (!level.getFluidState(pos.set(x, y, z)).is(FluidTags.WATER)) {
                continue;
            }
            level.addParticle(ParticleTypes.GLOW, x, y, z, (RANDOM.nextDouble() - 0.5) * 0.01, (RANDOM.nextDouble() - 0.5) * 0.01,
                    (RANDOM.nextDouble() - 0.5) * 0.01);
        }
    }

    private UnderwaterMotes() {
    }
}
