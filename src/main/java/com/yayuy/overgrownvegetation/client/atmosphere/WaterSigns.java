package com.yayuy.overgrownvegetation.client.atmosphere;

import com.yayuy.overgrownvegetation.client.VisualQuality;
import com.yayuy.overgrownvegetation.data.AtmosphereLife;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>水面の波紋と泡</b>(一覧 39、2026-10-31 見え方の段。設定: 森の水には見えない生き物がいて、泥の水底からはときどき泡が上がる)。
 * いつ・どこに出すかだけをここで決め、形は波紋の部品 {@link Ripples} が描く(10-31 作り直し)。
 * <ul>
 *     <li>大きな滴の波紋 {@link Ripples#drop}: 葉の滴の真下の水面に、滴が着く頃({@link #dropLanding}、10-31 ユーザー「波紋も水滴に連動して」)。
 *     泥の水底から上がった泡が水面に着いたとき(小さめ)。何も落ちないときの生き物の気配(まれ、{@code ripples.ringsPerMinute})。</li>
 *     <li>雨の波紋 {@link Ripples#rainOnWater}: 雨の当たる(空の開いた)水面に、雨の強さに比例してたくさん。カメラの近く {@code rainRadius} だけ。</li>
 * </ul>
 * 泥炭は植物の段でまだ無いので、泡は土・粘土・泥の水底から。値は {@code atmosphere.json} の節 {@code ripples}(仮決め)。
 */
final class WaterSigns {

    private static final RandomSource RANDOM = RandomSource.create();
    /** 上がっている途中の泡(水底 bottom から水面 y へ。着いたら波紋)。 */
    private static final List<Bubble> BUBBLES = new ArrayList<>();
    private static float ringCarry;
    private static float bubbleCarry;
    private static float rainCarry;
    /** 滴が葉の下でぶら下がる tick(バニラの dripping_water。仮)。 */
    private static final int HANG_TICKS = 40;

    private record Bubble(double x, double y, double z, double bottom, long born) {
        /** 水底から水面まで上がる tick(深さ 1 ブロック 0.9 秒ほど)。 */
        double riseTicks() {
            return Math.max(10.0, (y - bottom) * 18.0);
        }
    }

    static void clear() {
        BUBBLES.clear();
        Ripples.clear();
        ringCarry = 0.0F;
        bubbleCarry = 0.0F;
        rainCarry = 0.0F;
    }

    static String describe() {
        return "波紋 " + Ripples.count() + " 泡 " + BUBBLES.size();
    }

    static void tick(ClientLevel level, LocalPlayer player) {
        if (!VisualQuality.waterLife()) {
            clear();
            return;
        }
        AtmosphereLife.RipplesParams p = DesignData.atmosphere().ripples();
        long now = level.getGameTime();
        Ripples.tick(level);
        // 水面に着いた泡は小さめの大きな滴の波紋に。
        BUBBLES.removeIf(b -> {
            if (now - b.born < b.riseTicks()) {
                return false;
            }
            Ripples.drop(level, b.x, b.y, b.z, now, 0.5F);
            return true;
        });
        if (!VisualQuality.atmosphere() || ForestPresence.underground() > 0.5F) {
            return;
        }
        // 1 分あたりの数を tick ごとに積み、1 を超えたら 1 つ探す(見つからなければ捨てる = 水の少ない所では少ない)。
        ringCarry += p.ringsPerMinute() / 1200.0F;
        bubbleCarry += p.bubblesPerMinute() / 1200.0F;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        while (ringCarry >= 1.0F) {
            ringCarry -= 1.0F;
            spawn(level, player, p, pos, false, now);
        }
        while (bubbleCarry >= 1.0F) {
            bubbleCarry -= 1.0F;
            spawn(level, player, p, pos, true, now);
        }
        // 雨の波紋: 雨の強さに比例。雨の当たる(空の開いた)水面だけ。
        float rain = level.getRainLevel(1.0F);
        rainCarry = rain > 0.05F ? rainCarry + p.rainRingsPerSecond() * rain / 20.0F : 0.0F;
        while (rainCarry >= 1.0F) {
            rainCarry -= 1.0F;
            rainRing(level, player, p, pos);
        }
    }

    private static void rainRing(ClientLevel level, LocalPlayer player, AtmosphereLife.RipplesParams p, BlockPos.MutableBlockPos pos) {
        if (Ripples.full()) {
            return;
        }
        for (int tries = 0; tries < 3; tries++) {
            double fx = player.getX() + (RANDOM.nextDouble() * 2.0 - 1.0) * p.rainRadius();
            double fz = player.getZ() + (RANDOM.nextDouble() * 2.0 - 1.0) * p.rainRadius();
            int x = Mth.floor(fx);
            int z = Mth.floor(fz);
            int surface = waterSurface(level, x, Mth.floor(player.getY()) + 8, z, 24, pos);
            if (surface == Integer.MIN_VALUE || !level.isRainingAt(pos.set(x, surface + 1, z))) {
                continue;
            }
            FluidState fluid = level.getFluidState(pos.set(x, surface, z));
            Ripples.rainOnWater(level, fx, surface + fluid.getHeight(level, pos) + 0.01, fz, RANDOM.nextFloat(), RANDOM.nextFloat());
            return;
        }
    }

    /**
     * 葉の滴が出た({@link RainDrops})。真下(fromY から下、当たり判定の無い所を抜けて)が水面なら、ぶら下がり + 落ちる時間の後にそこへ大きな滴の波紋を出す。
     * バニラの粒は着いた知らせをくれないので、時間は見込み(ぶら下がり 40 tick + 重さ 0.06・空気抵抗 0.98 で落ちる tick)。
     */
    static void dropLanding(ClientLevel level, double x, int fromY, double z) {
        if (Ripples.full() || !VisualQuality.waterLife()) {
            return;
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(Mth.floor(x), fromY, Mth.floor(z));
        for (int y = fromY; y > fromY - 32; y--) {
            pos.setY(y);
            FluidState fluid = level.getFluidState(pos);
            if (fluid.is(FluidTags.WATER)) {
                if (!level.getFluidState(pos.above()).isEmpty()) {
                    return;
                }
                double surface = y + fluid.getHeight(level, pos);
                double fall = fromY + 0.95 - surface;
                int ticks = 0;
                double v = 0.0;
                for (double h = 0.0; h < fall && ticks < 200; ticks++) {
                    v = (v + 0.06) * 0.98;
                    h += v;
                }
                Ripples.drop(level, x, surface + 0.01, z, level.getGameTime() + HANG_TICKS + ticks, 0.85F + RANDOM.nextFloat() * 0.3F);
                return;
            }
            if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
                return;
            }
        }
    }

    /** まわりに水面を何か所か探して、見つかったら生き物の気配の波紋(か泡)を置く。 */
    private static void spawn(ClientLevel level, LocalPlayer player, AtmosphereLife.RipplesParams p, BlockPos.MutableBlockPos pos, boolean bubble,
                              long now) {
        for (int tries = 0; tries < 12; tries++) {
            int x = Mth.floor(player.getX() + (RANDOM.nextDouble() * 2.0 - 1.0) * p.radius());
            int z = Mth.floor(player.getZ() + (RANDOM.nextDouble() * 2.0 - 1.0) * p.radius());
            int surface = waterSurface(level, x, Mth.floor(player.getY()) + 8, z, 24, pos);
            if (surface == Integer.MIN_VALUE || !ModBiomes.isOvergrownLike(level.getBiome(pos.set(x, surface, z)))) {
                continue;
            }
            FluidState fluid = level.getFluidState(pos);
            double y = surface + fluid.getHeight(level, pos) + 0.01;
            double cx = x + 0.15 + RANDOM.nextDouble() * 0.7;
            double cz = z + 0.15 + RANDOM.nextDouble() * 0.7;
            if (!bubble) {
                Ripples.drop(level, cx, y, cz, now, 1.0F + RANDOM.nextFloat() * 0.3F);
                return;
            }
            // 泡は土・粘土・泥の水底から。
            int bottom = surface;
            while (bottom > surface - 12 && level.getFluidState(pos.set(x, bottom - 1, z)).is(FluidTags.WATER)) {
                bottom--;
            }
            if (!isMuddy(level.getBlockState(pos.set(x, bottom - 1, z)))) {
                continue;
            }
            int n = 1 + RANDOM.nextInt(3);
            for (int i = 0; i < n; i++) {
                BUBBLES.add(new Bubble(cx + RANDOM.nextGaussian() * 0.12, y, cz + RANDOM.nextGaussian() * 0.12, bottom,
                        now + i * (4 + RANDOM.nextInt(10))));
            }
            return;
        }
    }

    private static boolean isMuddy(BlockState s) {
        return s.is(BlockTags.DIRT) || s.is(Blocks.CLAY) || s.is(Blocks.MUD) || s.is(Blocks.MUDDY_MANGROVE_ROOTS);
    }

    /** (x, z) の列で fromY から下へ、上が空気の水のブロックを探す(無ければ MIN_VALUE)。葉の下へもたどる。 */
    static int waterSurface(ClientLevel level, int x, int fromY, int z, int depth, BlockPos.MutableBlockPos pos) {
        if (!level.hasChunkAt(pos.set(x, fromY, z))) {
            return Integer.MIN_VALUE;
        }
        for (int y = fromY; y > fromY - depth; y--) {
            FluidState f = level.getFluidState(pos.set(x, y, z));
            if (f.is(FluidTags.WATER)) {
                return level.getFluidState(pos.set(x, y + 1, z)).isEmpty() && level.getBlockState(pos).isAir() ? y : Integer.MIN_VALUE;
            }
            BlockState s = level.getBlockState(pos);
            if (!s.isAir() && s.blocksMotion() && !s.is(BlockTags.LEAVES) && !s.is(ModBlocks.OVERGROWN_LEAVES.get()) && !s.is(BlockTags.LOGS)) {
                return Integer.MIN_VALUE;
            }
        }
        return Integer.MIN_VALUE;
    }

    /** (x, z) の列で fromY から下へ、葉と幹でない動きを止めるブロックの上(林床)を探す(無ければ MIN_VALUE)。 */
    static int floorBelow(ClientLevel level, int x, int fromY, int z, int depth, BlockPos.MutableBlockPos pos) {
        if (!level.hasChunkAt(pos.set(x, fromY, z))) {
            return Integer.MIN_VALUE;
        }
        for (int y = fromY; y > fromY - depth; y--) {
            BlockState s = level.getBlockState(pos.set(x, y, z));
            if (s.blocksMotion() && !s.is(BlockTags.LEAVES) && !s.is(ModBlocks.OVERGROWN_LEAVES.get()) && !s.is(BlockTags.LOGS)) {
                return level.getBlockState(pos.set(x, y + 1, z)).blocksMotion() ? Integer.MIN_VALUE : y + 1;
            }
        }
        return Integer.MIN_VALUE;
    }

    static void render(ClientLevel level, float partialTick, Vector3f left, Vector3f up) {
        if (!VisualQuality.waterLife()) {
            return;
        }
        // 上がっている途中の泡(小さな明るい粒)。
        double now = level.getGameTime() + partialTick;
        float alpha = DesignData.atmosphere().dropRipple().alpha();
        for (Bubble b : BUBBLES) {
            double age = now - b.born;
            if (age < 0.0 || age >= b.riseTicks()) {
                continue;
            }
            double t = age / b.riseTicks();
            double wobble = Math.sin(age * 0.6 + b.x * 7.0) * 0.03;
            LifeSprites.blob(b.x + wobble, Mth.lerp(t, b.bottom + 0.1, b.y - 0.05), b.z, 0.035F, 1.0F, left, up, 0.85F, 0.92F, 0.90F,
                    Math.min(1.0F, alpha * 1.4F));
        }
        Ripples.render(level, partialTick);
    }

    private WaterSigns() {
    }
}
