package com.yayuy.overgrownvegetation.client.atmosphere;

import com.mojang.blaze3d.systems.RenderSystem;
import com.yayuy.overgrownvegetation.client.VisualQuality;
import com.yayuy.overgrownvegetation.data.AtmosphereLife;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>夜の光る藻</b>(一覧 18、2026-10-31 ユーザー決定「水中の弱い粒で作る」。設定: 発光プランクトンの代わり。石炭紀の森には花が無いので、
 * 色のアクセントを胞子嚢・若葉・光る藻で出す)。夜だけ、過繁茂の水面の下に黄緑(#ace300 系)の弱い光の粒がまばらに、ゆっくり漂う。
 * 光るのは粒だけ(水・葉のテクスチャは光らせない = 「葉を光らせない」決定)。光のブロックを置かないので湧きには効かない。
 * 水の半透明より先(AFTER_ENTITIES)に足し算で描くので、上から見ると水面越しに透けて見える。値は節 {@code algae}(仮決め)。
 * 2026-11-03 から、岩の天井の下(地下の水たまり・地底湖)では昼でも出し、粒を大きく濃くする({@link #UNDER_SIZE})。
 */
final class GlowAlgae {

    private static final RandomSource RANDOM = RandomSource.create();
    private static final List<Mote> MOTES = new ArrayList<>();
    private static float night;
    /**
     * 地下の水(2026-11-03 地下の植生 2・U8): 岩の天井の下({@link ForestPresence#underground})では昼夜を問わず出し、粒を大きく濃くする
     * (地上の {@code algae.size}・{@code alpha} = 0.07 / 0.35 から、地下では 0.10 / 0.9。仮決め、下絵の「B」の強さ)。
     * 地下ではバイオームを水面でなく真上の地表の列で見る(洞窟の 3D バイオームは過繁茂にならないことがあるため)。
     */
    private static final float UNDER_SIZE = 0.10F;
    private static final float UNDER_ALPHA = 0.9F;
    /** 夜の度合いと地下の度合いの大きい方(数と明るさに掛ける)。 */
    private static float strength;

    /** 1 粒。生まれた位置・漂う向き・生まれた時刻と寿命(tick)・水面の高さ(上へ抜けないように)。 */
    private record Mote(double x, double y, double z, float dx, float dz, float seed, long born, int life, double surface) {
    }

    static void clear() {
        MOTES.clear();
        night = 0.0F;
        strength = 0.0F;
    }

    static String describe() {
        return String.format("光る藻 %.2f 粒 %d", strength, MOTES.size());
    }

    static void tick(ClientLevel level, LocalPlayer player) {
        if (!VisualQuality.underwater()) {
            clear();
            return;
        }
        AtmosphereLife.AlgaeParams p = DesignData.atmosphere().algae();
        long now = level.getGameTime();
        float sunY = (float) SunLight.sunDirection(level, 1.0F).y;
        float target = Mth.clamp((-sunY - 0.02F) / 0.15F, 0.0F, 1.0F);
        night += (target - night) * 0.02F;
        float under = ForestPresence.underground();
        strength = Math.max(night, under);
        double r2 = p.radius() * p.radius() * 1.5;
        MOTES.removeIf(m -> now - m.born > m.life
                || (m.x - player.getX()) * (m.x - player.getX()) + (m.z - player.getZ()) * (m.z - player.getZ()) > r2);
        int cap = Math.round(p.counts()[Math.min(2, VisualQuality.atmosphereTier())] * strength);
        if (MOTES.size() >= cap || !VisualQuality.atmosphere()) {
            return;
        }
        boolean cave = under > 0.5F;
        // 1 tick に 2 列まで水面を探す(水の少ない所では少ない)。潜っているときは、目の上の水面より上から探す
        // (10-31 夜: 目の 8 ブロック上から探していたので、深く潜ると水面が見つからず粒が生まれなかった)。
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int fromY = Mth.floor(player.getEyeY()) + 8;
        if (player.isEyeInFluid(FluidTags.WATER)) {
            int y = Mth.floor(player.getEyeY());
            while (y < fromY + 56 && level.getFluidState(pos.set(player.getBlockX(), y, player.getBlockZ())).is(FluidTags.WATER)) {
                y++;
            }
            fromY = Math.max(fromY, y + 4);
        }
        int depth = fromY - Mth.floor(player.getY()) + 16;
        for (int tries = 0; tries < 2 && MOTES.size() < cap; tries++) {
            int x = Mth.floor(player.getX() + (RANDOM.nextDouble() * 2.0 - 1.0) * p.radius());
            int z = Mth.floor(player.getZ() + (RANDOM.nextDouble() * 2.0 - 1.0) * p.radius());
            int surface = WaterSigns.waterSurface(level, x, fromY, z, depth, pos);
            int biomeY = cave ? level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x, z) : surface;
            if (surface == Integer.MIN_VALUE || !ModBiomes.isOvergrownLike(level.getBiome(pos.set(x, biomeY, z)))) {
                continue;
            }
            FluidState fluid = level.getFluidState(pos.set(x, surface, z));
            double top = surface + fluid.getHeight(level, pos);
            double y = top - p.depthMin() - RANDOM.nextDouble() * (p.depthMax() - p.depthMin());
            // 水底より下へは置かない。
            if (!level.getFluidState(pos.set(x, Mth.floor(y), z)).is(FluidTags.WATER)) {
                continue;
            }
            float a = RANDOM.nextFloat() * Mth.TWO_PI;
            int life = Math.round((p.lifeMin() + RANDOM.nextFloat() * (p.lifeMax() - p.lifeMin())) * 20.0F);
            MOTES.add(new Mote(x + RANDOM.nextDouble(), y, z + RANDOM.nextDouble(), Mth.cos(a), Mth.sin(a), RANDOM.nextFloat() * 100.0F, now,
                    life, top));
        }
    }

    /** AFTER_ENTITIES({@link LifeSprites} の足し算の間)。 */
    static void render(ClientLevel level, float partialTick, Vector3f left, Vector3f up) {
        if (!VisualQuality.underwater()) {
            return;
        }
        if (MOTES.isEmpty() || strength <= 0.01F) {
            return;
        }
        AtmosphereLife.AlgaeParams p = DesignData.atmosphere().algae();
        float under = ForestPresence.underground();
        float size = Mth.lerp(under, p.size(), UNDER_SIZE);
        float baseAlpha = Mth.lerp(under, p.alpha(), UNDER_ALPHA);
        double now = level.getGameTime() + partialTick;
        // この描き方は霧を受けないので、遠い粒はその時の霧の距離で薄める(10-31 夜: 潜ると水中の霧の向こうの粒だけがくっきり浮いた)。
        Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        float fogStart = MistView.fogStart(); // 森の中の霞の印を外した始まり
        float fogEnd = Math.max(fogStart + 1.0F, RenderSystem.getShaderFogEnd());
        for (Mote m : MOTES) {
            double age = (now - m.born) / 20.0;
            float t = (float) ((now - m.born) / m.life);
            // 3 秒で灯って 3 秒で消える。ゆっくり明滅(夕方の光の粒よりずっと遅い)。
            float lifeSec = m.life / 20.0F;
            float fade = Mth.clamp((float) age / 3.0F, 0.0F, 1.0F) * Mth.clamp((lifeSec - (float) age) / 3.0F, 0.0F, 1.0F);
            float pulse = 0.65F + 0.35F * Mth.sin((float) age * 0.6F + m.seed);
            if (t > 1.0F) {
                continue;
            }
            double drift = age * p.drift();
            double x = m.x + m.dx * drift + Math.sin(age * 0.25 + m.seed) * 0.08;
            double z = m.z + m.dz * drift + Math.cos(age * 0.2 + m.seed) * 0.08;
            double y = Math.min(m.surface - 0.06, m.y + Math.sin(age * 0.3 + m.seed * 2.0) * 0.06);
            float far = Mth.clamp(((float) Math.sqrt((x - cam.x) * (x - cam.x) + (y - cam.y) * (y - cam.y) + (z - cam.z) * (z - cam.z)) - fogStart)
                    / (fogEnd - fogStart), 0.0F, 1.0F);
            float alpha = baseAlpha * fade * pulse * strength * (1.0F - far * far * (3.0F - 2.0F * far));
            LifeSprites.blob(x, y, z, size * 2.5F, 1.0F, left, up, p.r(), p.g(), p.b(), alpha * 0.35F);
            LifeSprites.blob(x, y, z, size, 1.0F, left, up, p.r(), p.g(), p.b(), alpha);
        }
    }

    private GlowAlgae() {
    }
}
