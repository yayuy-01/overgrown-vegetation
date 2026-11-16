package com.yayuy.overgrownvegetation.client.atmosphere;

import com.yayuy.overgrownvegetation.client.VisualQuality;
import com.yayuy.overgrownvegetation.data.AtmosphereTable;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

/**
 * <b>雨上がりの水滴</b>(合間の小物、一覧 25。2026-10-31 に parked の {@code AfterRain} の「葉先の滴」だけを戻した。元は 10-17、
 * ユーザー「青い滴も半透明でしっかり水のように見えるように」→「水に見えて良い」)。
 *
 * <p>頭の上の葉の下面から、<b>バニラの水の滴り</b>の粒({@code dripping_water}: ぶら下がる → {@code falling_water}: 落ちる →
 * 着地で {@code splash})を出す。鍾乳石や雨の日の葉の滴と同じ絵・色・動きで、Minecraft の見た目になじむ
 * (10-31 ユーザー「minecraft に合わせるために水のテクスチャを使用できますか?」で自前のしずく形から替えた)。
 * 粒の数の設定(パーティクルの「最小」など)はバニラの粒と同じく効く。
 *
 * <ul>
 *     <li>いつ: 雨の最中に少し({@code drops.duringRain}。10-31 ユーザー採用「雨の最中にも少し」)と、雨上がり。雨上がりは雨の最中の量から始めて
 *     {@code drops.afterMinutes} 分でなめらかに 0 へ(10-31 ユーザー「量が多すぎる、雨がやんでから徐々に減るように」。前は霧の 20 分で、止んだ途端に雨の最中の 4 倍に増えていた)。</li>
 *     <li>真下が水面なら、落ちて着く頃にそこへ小さな輪を出す({@link WaterSigns#dropLanding}。10-31 ユーザー「波紋も水滴に連動して」)。</li>
 *     <li>どこに: 森の度合い({@link ForestPresence#openForest})× 上の強さ。頭の上 24 ブロックまでの葉(過繁茂の葉とバニラの葉)の、下が空気の所。</li>
 *     <li>数: 近く {@code drops.range} ブロックだけ。最近 3 秒に出した数(ぶら下がり 2 秒 + 落ちる間のおおよそ)を品質の段の {@code drops.counts} までに。</li>
 * </ul>
 * 葉そのものは光らせない。数値は {@code atmosphere.json} の節 {@code drops}。
 */
final class RainDrops {

    private static final RandomSource RANDOM = RandomSource.create();
    /** 1 tick ごとに出した数(最近 60 tick の輪)。合計がおおよその生きている数。 */
    private static final int[] SPAWNED = new int[60];
    private static int recent;
    private static int slot;
    private static float carry;

    private RainDrops() {
    }

    static void clear() {
        java.util.Arrays.fill(SPAWNED, 0);
        recent = 0;
        carry = 0.0F;
    }

    static int count() {
        return recent;
    }

    /** 毎 tick({@link Atmosphere} から)。 */
    static void tick(ClientLevel level, LocalPlayer player) {
        if (!VisualQuality.motes()) {
            clear();
            return;
        }
        slot = (slot + 1) % SPAWNED.length;
        recent -= SPAWNED[slot];
        SPAWNED[slot] = 0;
        AtmosphereTable.DropsParams p = DesignData.atmosphere().drops();
        float strength = p.duringRain() * MistFog.wetAfter(p.afterMinutes(), level.getRainLevel(1.0F)) * ForestPresence.openOvergrown();
        int cap = p.counts()[Math.min(2, VisualQuality.atmosphereTier())];
        if (strength <= 0.01F || cap <= 0) {
            carry = 0.0F;
            return;
        }
        carry += p.perSecond() / 20.0F * strength;
        int tries = (int) carry;
        carry -= tries;
        double range = p.range();
        int eyeY = Mth.floor(player.getEyeY());
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 0; i < tries && recent < cap; i++) {
            int bx = Mth.floor(player.getX() + (RANDOM.nextDouble() * 2.0 - 1.0) * range);
            int bz = Mth.floor(player.getZ() + (RANDOM.nextDouble() * 2.0 - 1.0) * range);
            for (int y = eyeY + 1; y <= eyeY + 24; y++) {
                pos.set(bx, y, bz);
                if (!isLeaf(level.getBlockState(pos))) {
                    continue;
                }
                if (level.getBlockState(pos.setY(y - 1)).isAir()) {
                    // 葉の下面のすぐ下(バニラの葉の雨の滴と同じ 0.05 下)。
                    double dx = bx + 0.15 + RANDOM.nextDouble() * 0.7;
                    double dz = bz + 0.15 + RANDOM.nextDouble() * 0.7;
                    level.addParticle(ParticleTypes.DRIPPING_WATER, dx, y - 0.05, dz, 0.0, 0.0, 0.0);
                    WaterSigns.dropLanding(level, dx, y - 1, dz);
                    SPAWNED[slot]++;
                    recent++;
                }
                break;
            }
        }
    }

    private static boolean isLeaf(BlockState state) {
        return state.is(BlockTags.LEAVES) || state.is(com.yayuy.overgrownvegetation.ModTags.OVERGROWN_LEAVES);
    }
}
