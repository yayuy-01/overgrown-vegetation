package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.Set;

/**
 * <b>ネザーの熱水の森の地面</b>(2026-11-09 段階 1、正本は Vault の「ネザーの侵食の作り直し(熱を食べる森と熱の結晶)」)。
 *
 * <p>ネザーの植物は溶岩の熱を食べ、溶岩の表面を冷やして殻にする。赤と灰は残さない(ユーザー決定)。ネザーの中で汚染された
 * 地形のブロックは、置かれた向きで次のどれかになる({@link CorruptionRegistry#corrupt} の頭から呼ぶ。ほかの次元は今のまま)。
 * <ul>
 *     <li>上が空いている(床) → 熱の敷物 A(1 割は B の糸)</li>
 *     <li>下が空いている(天井) → 床まで {@link #HIGH_CEILING} 以上なら光の苔 A、低ければ冷えた殻</li>
 *     <li>埋まっている・壁 → 冷えた殻</li>
 *     <li>溶岩の源泉で上が空いている(海の表面) → 冷えた殻。下の溶岩はそのまま(殻の下は溶岩)。流れている溶岩もそのまま</li>
 * </ul>
 * 鉱石・グロウストーン・ネザーウォートブロック・建築ブロックは対象外(今までどおり覆い。資源を消さないための仮決め)。
 * 2026-10-02 の「溶岩の海も玄武岩に」は、この段階 1 で「表面だけ殻」に置き換えた。
 */
public final class HeatForestGround {

    /** 高い天井(空の側)とみなす、天井の下の空きの高さ(仮決め。下絵 3 回目の「天井 24 未満には逆さの木が生えない」に合わせた)。 */
    public static final int HIGH_CEILING = 24;
    /** 熱の敷物 B(糸)の割合(仮決め。仕様は「A に少し混ぜる」)。 */
    public static final int STRANDS_IN_TEN = 1;

    /** 熱の敷物・殻にするネザーの地形のブロック(仮決め)。 */
    private static final Set<Block> TERRAIN = Set.of(
            Blocks.NETHERRACK, Blocks.CRIMSON_NYLIUM, Blocks.WARPED_NYLIUM, Blocks.SOUL_SAND, Blocks.SOUL_SOIL,
            Blocks.BASALT, Blocks.SMOOTH_BASALT, Blocks.BLACKSTONE, Blocks.MAGMA_BLOCK, Blocks.GRAVEL);

    /** 消すネザーの元の草と火(火は「上書きできる薄いブロック」としてオーバーワールドの下草に化けていた。11-09 ゲームで見つけた)。 */
    private static final Set<Block> NETHER_PLANTS = Set.of(Blocks.FIRE, Blocks.SOUL_FIRE,
            Blocks.CRIMSON_ROOTS, Blocks.WARPED_ROOTS, Blocks.NETHER_SPROUTS, Blocks.CRIMSON_FUNGUS, Blocks.WARPED_FUNGUS,
            Blocks.WEEPING_VINES, Blocks.WEEPING_VINES_PLANT, Blocks.TWISTING_VINES, Blocks.TWISTING_VINES_PLANT);

    /**
     * 熱水の森の決まりを使うか: ネザーで、植物がネザーに適応した後({@link HeatForestAdaptation}。最初に汚染されてから 10 分。ユーザー決定)。
     * それまではオーバーワールドと同じ植生(段階 1 より前の作り)。
     */
    public static boolean applies(Level level) {
        return level.dimension() == Level.NETHER && level instanceof net.minecraft.server.level.ServerLevel server
                && HeatForestAdaptation.adapted(server);
    }

    /** 鉱石・グロウストーンの熱水の森の版(11-09 追記 1・11-10、どちらもユーザー決定)。 */
    @Nullable
    public static BlockState oreFor(BlockState state) {
        if (state.is(Blocks.NETHER_QUARTZ_ORE)) return ModBlocks.HEAT_QUARTZ_ORE.get().defaultBlockState();
        if (state.is(Blocks.NETHER_GOLD_ORE)) return ModBlocks.HEAT_GOLD_ORE.get().defaultBlockState();
        if (state.is(Blocks.ANCIENT_DEBRIS)) return ModBlocks.HEAT_ANCIENT_DEBRIS.get().defaultBlockState();
        if (state.is(Blocks.GLOWSTONE)) return ModBlocks.HEAT_GLOWSTONE.get().defaultBlockState();
        return null;
    }

    public static boolean isTerrain(BlockState state) {
        return TERRAIN.contains(state.getBlock());
    }

    /**
     * ネザーで汚染された {@code state} の行き先。熱水の森の決まりの外のブロックなら {@code null}(呼び手はふだんの対応表へ)。
     * 置き換えない溶岩(下の層・流れているもの)は {@code state} をそのまま返す(呼び手は {@link CorruptionRegistry#noChange} で素通り)。
     */
    @Nullable
    public static BlockState corrupt(BlockGetter level, BlockPos pos, BlockState state) {
        if (state.is(Blocks.LAVA)) {
            if (!state.getFluidState().isSource()) {
                return state;
            }
            BlockState above = level.getBlockState(pos.above());
            return NetherSurface.isOpen(above) ? ModBlocks.HEAT_COOLED_CRUST.get().defaultBlockState() : state;
        }
        if (NETHER_PLANTS.contains(state.getBlock())) {
            // ネザーの元の草(根・芽・キノコ・蔓)は消す。跡には熱水の森の植物が下のブロックから生える(11-09 ゲームで、
            // 対応表の「薄い植物」でオーバーワールドの下草ブロックに化けて残っていたのを見て足した)
            return Blocks.AIR.defaultBlockState();
        }
        BlockState ore = oreFor(state);
        if (ore != null) {
            return ore;
        }
        if (!isTerrain(state)) {
            return null;
        }
        return groundFor(level, pos);
    }

    /** その場所の地面(床 = 敷物、高い天井 = 光の苔、それ以外 = 殻)。 */
    public static BlockState groundFor(BlockGetter level, BlockPos pos) {
        if (NetherSurface.isOpen(level.getBlockState(pos.above()))) {
            boolean strands = Math.floorMod(Mth.getSeed(pos) >> 7, 10) < STRANDS_IN_TEN;
            return (strands ? ModBlocks.HEAT_MAT_STRANDS : ModBlocks.HEAT_MAT).get().defaultBlockState();
        }
        if (NetherSurface.isOpen(level.getBlockState(pos.below())) && openBelowSky(level, pos, HIGH_CEILING) >= HIGH_CEILING) {
            return ModBlocks.HEAT_CEILING_GLOW_MOSS.get().defaultBlockState();
        }
        return ModBlocks.HEAT_COOLED_CRUST.get().defaultBlockState();
    }

    /** 天井の高さ用: 逆さの木(幹・樹冠・房・気根)も空きとして数える(木の陰の天井も光の苔のままにする。11-10 木の段階)。 */
    public static int openBelowSky(BlockGetter level, BlockPos pos, int max) {
        BlockPos.MutableBlockPos c = pos.mutable();
        int n = 0;
        while (n < max) {
            c.move(0, -1, 0);
            if (c.getY() < level.getMinBuildHeight()) break;
            BlockState s = level.getBlockState(c);
            if (!NetherSurface.isOpen(s) && !s.is(ModBlocks.HEAT_SKY_LEAVES.get()) && !s.is(ModBlocks.HEAT_BARK.get())) break;
            n++;
        }
        return n;
    }

    /** {@code pos} の下に続く空き(動きを止めず液体も無い)の段数。{@code max} で打ち切る。 */
    public static int openBelow(BlockGetter level, BlockPos pos, int max) {
        BlockPos.MutableBlockPos c = pos.mutable();
        int n = 0;
        while (n < max) {
            c.move(0, -1, 0);
            if (c.getY() < level.getMinBuildHeight() || !NetherSurface.isOpen(level.getBlockState(c))) {
                break;
            }
            n++;
        }
        return n;
    }

    private HeatForestGround() {
    }
}
