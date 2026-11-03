package com.yayuy.overgrownvegetation.client.atmosphere;

import com.yayuy.overgrownvegetation.client.VisualQuality;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.shader.SodiumTerrain;
import com.yayuy.overgrownvegetation.data.AtmosphereTable;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.chunk.RenderChunkRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import org.jetbrains.annotations.Nullable;

/**
 * 葉の色の揺らぎ(2026-10-30 夜 第 2 段「動き」で parked の {@code LeafColors} から戻した。元は 2026-10-17。10-16 の「密林をさらに美しく見せる案」(5) 色の揺らぎ。10-16 の採用(おすすめ 3 案・雨上がり・着生植物と花・林冠の雲海・水面のきらめき・滝の虹・雲)には入っていなかったが、Claude が「まだ作っていない案」と取り違えて作り、10-17 にユーザーが見て了承)。
 * 過繁茂の葉を樹冠くらいのまとまりごとに、絵の色から少しだけ青緑へ寄せた成葉にし、一部の樹冠の外側に黄緑の若葉の房を混ぜる。
 * 10-17 ユーザー指定「もともとの色に近い色のみに。胞子で増える樹木なので若葉は黄緑色、成長しきった葉も元の色から少しだけ青緑が強くなるだけ」
 * (最初は黄緑・濃い青緑の樹冠と、赤・赤銅・えんじの若葉にしていた)。
 *
 * <p>葉の絵({@code overgrown_leaves.png})はほぼ純粋な濃い緑(平均 0.12 / 0.23 / 0.02)なので、バニラの色付け(掛け算)では
 * 暗くすることしかできず、黄緑にならない。そこで:
 * <ul>
 *   <li>このクラス(ブロックの色のハンドラ)がブロックごとに「どの色にするか」の番号を決め、色付けの色の<b>比</b>(R/G・B/G)に書く。
 *       面の陰と AO は 3 色に同じ数を掛けるだけなので、比は地形のシェーダーまで残る。</li>
 *   <li>葉の層のシェーダー({@code rendertype_cutout_mipped.vsh})は、風の印(頂点の透明度、{@link SwayModels})の付いた頂点 =
 *       この MOD の葉だけ比を読んで番号に戻し、色付けの色を外して陰だけを掛ける。fsh({@code ov_leaf.glsl})が絵の明るさを保ったまま
 *       番号の色へ塗り替える。</li>
 * </ul>
 * 番号は 0.2 刻みの比(R/G = 1 − 0.2 j、B/G = 1 − 0.2 k、G がいつも一番大きい)。暗い角(G が 25 段ほど)でも丸めて同じ番号に戻る。
 * <ul>
 *   <li>j = 0(成葉): k = 0 絵のまま / 1 少し青緑 / 2 もう少し青緑</li>
 *   <li>j = 1〜3(若葉の量 0.45 / 0.75 / 1): 黄緑(k = 0)</li>
 * </ul>
 * シェーダーが効かないとき(Sodium など・葉が固い層に入る「軽量」グラフィック)は比がそのまま掛け算になるが、減るのは R と B だけで、
 * この葉の絵の R・B はほぼ 0 なので見た目はほとんど変わらない(Sodium などのときは念のため色を付けない)。
 *
 * <p>樹冠のまとまり = 6 × 9 × 6 ブロックの格子に 1 点ずつ散らした点のうち一番近いもの(ボロノイ)。若葉は「芽吹いた樹冠」(8%)の外側
 * (上が空いている葉の 7 割と、横が空いている葉の 35%)に房で、ほかの樹冠でも上が空いた葉の 2% にうっすら。
 * 色はチャンクの形に焼き込まれる(毎フレームは計算しない)。強さ(設計値 {@code leaves.toneStrength}・{@code leaves.youngStrength}、了承済みの 1)は
 * シェーダーの uniform。色のハンドラは共有の {@code OvergrownVegetationClient} でなく、ここで登録する。葉を光らせない(明るさはライトマップのまま。若葉も暗い所では暗い)。数値はすべて仮決め。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class LeafTones {
    /** 樹冠のまとまりの格子(横・縦、ブロック)。木の間隔 5 に近い横幅、縦は林冠の層くらい。 */
    static final float CELL_XZ = 6.0F;
    static final float CELL_Y = 9.0F;
    /** 成葉の色合い k = 0〜2 の出やすさ(絵のまま・少し青緑・もう少し青緑)。 */
    private static final int[] TONE_WEIGHTS = {40, 35, 25};
    /** 隣の色合いへずらすブロックの割合(樹冠の中の小さなむら)。 */
    static final float TONE_JITTER = 0.12F;
    /** 芽吹いた樹冠の割合(赤い若葉だった最初の 0.15 では、上から見て秋の紅葉のように赤が広すぎた)。 */
    static final float FLUSH_CROWNS = 0.08F;
    /** 芽吹いた樹冠で、上が空いている葉のうち若葉にする割合。 */
    static final float TOP_TIPS = 0.7F;
    /** 芽吹いた樹冠で、横が空いている葉のうち若葉にする割合。 */
    static final float SIDE_TIPS = 0.35F;
    /** 芽吹いていない樹冠で、上が空いている葉のうちうっすら若葉にする割合。 */
    static final float STRAY_TIPS = 0.02F;

    /** 過繁茂の葉の色のハンドラを登録する。 */
    @SubscribeEvent
    public static void registerColors(RegisterColorHandlersEvent.Block event) {
        // 11-01 夜(見え方の直し 1): 封印木・木生シダの芯と板、胞子昆布の葉も(絵が違うので色は「絵からのずらし」で塗る。ov_leaf.glsl)。
        event.register(LeafTones::color, ModBlocks.OVERGROWN_LEAVES.get(), ModBlocks.OVERGROWN_LEAVES_TUFT.get(), ModBlocks.SIGILLARIA_LEAVES.get(), ModBlocks.SIGILLARIA_LEAVES_FRINGE.get(),
                ModBlocks.TREE_FERN_LEAVES.get(), ModBlocks.TREE_FERN_FROND.get(), ModBlocks.SPORE_KELP_FROND.get());
    }

    /** チャンクの形を組むスレッドごとの「直前に色を出したブロック」(葉は 6 面とも色付けなので、同じブロックで 6 回呼ばれる)。 */
    private static final class Memo {
        java.lang.ref.WeakReference<BlockAndTintGetter> region = new java.lang.ref.WeakReference<>(null);
        long pos;
        int color;
    }

    private static final ThreadLocal<Memo> MEMO = ThreadLocal.withInitial(Memo::new);

    /** 過繁茂の葉の色のハンドラ。 */
    public static int color(BlockState state, @Nullable BlockAndTintGetter level, @Nullable BlockPos pos, int tintIndex) {
        if (level == null || pos == null || SodiumTerrain.unsupported()) {
            return -1;
        }
        if (!(level instanceof RenderChunkRegion)) {
            return code(state, level, pos);
        }
        Memo memo = MEMO.get();
        long key = pos.asLong();
        if (memo.region.get() == level && memo.pos == key) {
            return memo.color;
        }
        int color = code(state, level, pos);
        if (memo.region.get() != level) {
            memo.region = new java.lang.ref.WeakReference<>(level);
        }
        memo.pos = key;
        memo.color = color;
        return color;
    }

    /**
     * そのブロックの色の番号を色付けの色(0xRRGGBB、G = 255)にしたもの。過繁茂の葉でない葉(11-01 夜から)は k に 3 を足し(B/G = 0.4〜0)、
     * シェーダーは絵の色を目標の色へ塗り替える代わりに、絵の色から同じだけずらす(絵の色合いを保つ)。胞子昆布の葉は若葉を付けない(水中)。
     */
    static int code(BlockState state, BlockAndTintGetter level, BlockPos pos) {
        boolean relative = !state.is(ModBlocks.OVERGROWN_LEAVES.get());
        boolean noYoung = state.is(ModBlocks.SPORE_KELP_FROND.get());
        long crown = nearestCrown(pos.getX(), pos.getY(), pos.getZ());
        long here = pos.asLong();
        int young = 0;
        int k;
        boolean openAbove = open(level.getBlockState(pos.above()));
        if (noYoung) {
            // 若葉なし
        } else if (hash01(crown, 1) < FLUSH_CROWNS) {
            float t = hash01(here, 2);
            boolean tip = openAbove ? t < TOP_TIPS : t < SIDE_TIPS && openSide(level, pos);
            if (tip) {
                float r = hash01(here, 3);
                young = r < 0.4F ? 1 : r < 0.75F ? 2 : 3;
            }
        } else if (openAbove && hash01(here, 4) < STRAY_TIPS) {
            young = 1;
        }
        if (young > 0) {
            k = 0;
        } else {
            k = tone(hash01(crown, 6));
            if (hash01(here, 7) < TONE_JITTER) {
                k = nudge(k);
            }
        }
        int r = Math.round(255.0F * (1.0F - 0.2F * young));
        int b = Math.round(255.0F * (1.0F - 0.2F * (k + (relative ? 3 : 0))));
        return (r << 16) | (255 << 8) | b;
    }

    /** 成葉の色合い k(0〜2)を重みどおりに選ぶ。 */
    private static int tone(float u) {
        int total = 0;
        for (int w : TONE_WEIGHTS) {
            total += w;
        }
        float at = u * total;
        int k = 0;
        for (int w : TONE_WEIGHTS) {
            if (at < w) {
                return k;
            }
            at -= w;
            k++;
        }
        return TONE_WEIGHTS.length - 1;
    }

    /** 隣の色合い(1 段だけ)。 */
    private static int nudge(int k) {
        return k == 1 ? 0 : 1;
    }

    /** 葉の外側か(空気・草など、葉でも光を遮るブロックでもない)。 */
    private static boolean open(BlockState state) {
        if (state.isAir()) {
            return true;
        }
        if (state.getBlock() instanceof com.yayuy.overgrownvegetation.block.OvergrownLeavesBlock || state.is(BlockTags.LEAVES)) {
            return false;
        }
        return !state.canOcclude();
    }

    private static boolean openSide(BlockAndTintGetter level, BlockPos pos) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (open(level.getBlockState(pos.relative(d)))) {
                return true;
            }
        }
        return false;
    }

    /** 一番近い樹冠の点の番号(6 × 9 × 6 の格子に 1 点ずつ。縦横の比をそろえて測る)。 */
    static long nearestCrown(int x, int y, int z) {
        float px = (x + 0.5F) / CELL_XZ;
        float py = (y + 0.5F) / CELL_Y;
        float pz = (z + 0.5F) / CELL_XZ;
        int cx = Mth.floor(px);
        int cy = Mth.floor(py);
        int cz = Mth.floor(pz);
        float best = Float.MAX_VALUE;
        long bestId = 0L;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int gx = cx + dx;
                    int gy = cy + dy;
                    int gz = cz + dz;
                    long id = gx * 0x9E3779B97F4A7C15L ^ gy * 0xC2B2AE3D27D4EB4FL ^ gz * 0x165667B19E3779F9L;
                    float ex = gx + hash01(id, 21) - px;
                    float ey = gy + hash01(id, 22) - py;
                    float ez = gz + hash01(id, 23) - pz;
                    float d = ex * ex + ey * ey + ez * ez;
                    if (d < best) {
                        best = d;
                        bestId = id;
                    }
                }
            }
        }
        return bestId;
    }

    /** 0〜1 の決まった乱数。 */
    static float hash01(long seed, int salt) {
        long h = seed + salt * 0x632BE59BD9B4E019L;
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return (h >>> 40) / (float) (1 << 24);
    }

    /** AFTER_SKY に、葉の層のシェーダーへ強さを渡す({@code on} が false なら 0。Sodium へは SodiumTerrain が写す)。 */
    static void upload(boolean on) {
        on = on && VisualQuality.canopyLight();
        ShaderInstance shader = GameRenderer.getRendertypeCutoutMippedShader();
        if (shader == null) {
            return;
        }
        AtmosphereTable.LeavesParams p = DesignData.atmosphere().leaves();
        boolean use = on && !SodiumTerrain.unsupported();
        // 葉の裏の透け(36、10-31)は 11-01 夜にユーザー決定で削除(太陽を葉越しに見ると葉が明るくなるのをやめる)。
        shader.safeGetUniform("OvLeafTone").set(use ? p.toneStrength() : 0.0F, use ? p.youngStrength() : 0.0F, 0.0F, 0.0F);
    }

    private LeafTones() {
    }
}
