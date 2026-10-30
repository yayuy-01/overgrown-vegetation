package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「このマスは植物に覆われたか」を決める場(2026-09-22)。
 *
 * <h2>記録を持たない</h2>
 * <p>覆ったかどうかは<b>どこにも保存していない</b>。{@code climax.json} の
 * {@code overgrownLayer.chance}(既定 70%)の抽選を<b>座標のハッシュ</b>で行うので、
 * サーバーとクライアントが何も同期せずに同じ答えを出す。セーブデータも増えない。
 *
 * <p>これを乱数にすると、サーバーだけが答えを知ることになり、
 * <b>どのマスを覆ったかをチャンクごとに保存して送る</b>羽目になる。
 *
 * <h2>何に使うのか</h2>
 * <p>対応表に無いブロックが汚染されたときの行き先を 2 つに分けるためだけのもの
 * ({@code CorruptionRegistry#corruptUnmapped})。
 * <ul>
 *     <li><b>当たり</b> → {@code block/OvergrownHuskBlock}(見た目は元のまま・機能は無い)</li>
 *     <li><b>外れ</b> → {@code overgrown_block}(壁が一面まるごと同じ苔柄にならないようにする混ざり)</li>
 * </ul>
 * 外れを引かせるのは<b>6 面の実の立方体だけ</b>。それ以外は必ず覆う({@link #covered})。
 */
public final class OvergrownLayerField {

    /** 抽選に使う塩。地脈({@link LeyVeinField})や地域マップと模様が重ならないように別の値。 */
    private static final long SALT = 0x5AF3107A4077L;

    private OvergrownLayerField() {
    }

    /**
     * <b>そのブロックを覆うか。</b> 抽選に掛けるのは<b>6 面の実の立方体だけ</b>で、
     * そうでない形のブロックは<b>必ず覆う</b>(2026-09-22 の仕様変更)。
     *
     * <h2>なぜ立方体だけ抽選するのか</h2>
     * <p>{@code chance}(既定 70%)は「<b>一面が同じブロックで埋まったときに気持ち悪く見える</b>のを防ぐ」
     * ためのもの —— コンクリートの壁が全部同じ苔柄になると、模様の繰り返しが目立つ。
     * 抽選に外れたマスは<b>汎用の汚染ブロック</b>({@code overgrown_block})になり、壁に混ざりが出る。
     *
     * <p>立方体でないブロック(階段・尖った鍾乳石・他 MOD の機械など)は<b>そもそも壁を作らない</b>し、
     * 外れたときの逃げ道である {@code overgrown_block} に置き換えると<b>形が失われる</b>。
     * だから抽選せず 100% 覆う。
     *
     * <h2>「立方体」の見分け方</h2>
     * <p>{@link BlockState#isCollisionShapeFullBlock} —— 当たり判定が 1 マスを埋めているか。
     * サーバーとクライアントの<b>両方から同じ答えが引ける</b>ものでなければならないので、
     * モデルの形そのもの(クライアントしか知らない)ではなくこちらを使う。
     * ガラス・氷・羊毛・コンクリート・鉄ブロックは立方体、
     * 階段・フェンス・鍾乳石・歯車は立方体ではない、と期待どおりに分かれる。
     */
    public static boolean covered(BlockGetter level, BlockPos pos, BlockState state, double chance) {
        return !isPlainCube(level, pos, state) || covered(pos, chance);
    }

    /** 抽選の対象になる「6 面の実の立方体」か。 */
    public static boolean isPlainCube(BlockGetter level, BlockPos pos, BlockState state) {
        return state.isCollisionShapeFullBlock(level, pos);
    }

    /**
     * 世界を見ずに同じ問いに答える版。<b>描画から呼ぶ</b> ——
     * 「覆われて動かなくなったブロック」({@code block/OvergrownHuskBlock})の模型は
     * 化けている元のブロック状態しか持っておらず、座標が無い。
     *
     * <p>{@link EmptyBlockGetter} と原点を渡すのは {@code OvergrownHuskBlock#computeShape} と同じ理由 ——
     * 他 MOD の {@code getCollisionShape} に<b>この世界のこの座標</b>を渡すと、
     * 自分の {@link net.minecraft.world.level.block.entity.BlockEntity} を期待しているコードが落ちうる。
     * 例外が飛んだら「立方体ではない」に倒す(覆いすぎるより、抜きすぎるほうが目立たない)。
     */
    public static boolean isPlainCube(BlockState state) {
        return PLAIN_CUBE.computeIfAbsent(state, s -> {
            try {
                return Block.isShapeFullBlock(s.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO));
            } catch (Throwable ignored) {
                return Boolean.FALSE;
            }
        });
    }

    /** {@link #isPlainCube(BlockState)} の答え。毎フレーム他 MOD のコードを呼ばないため。 */
    private static final Map<BlockState, Boolean> PLAIN_CUBE = new ConcurrentHashMap<>();

    /**
     * 覆う確率(climax.json の {@code overgrownLayer.chance} の値)。2026-10-30 の整理(退避)で極相の表 ClimaxTable を
     * parked/ へ外したので、ここに既定値を置いた(仮。設計データへ戻すなら spread.json などへ)。
     */
    public static final double CHANCE = 0.7D;
    /** 覆われたブロックを機能の無いブロック(overgrown_husk)へ差し替えるか(climax.json の {@code overgrownLayer.encase})。 */
    public static final boolean ENCASE = true;

    /** そのマスが抽選に当たっているか。{@link #CHANCE} で引く。 */
    public static boolean covered(BlockPos pos) {
        return covered(pos, CHANCE);
    }

    /** 確率を外から渡す版(描画側が毎ブロック設定を引き直さないため)。 */
    public static boolean covered(BlockPos pos, double chance) {
        if (chance >= 1.0D) {
            return true;
        }
        if (chance <= 0.0D) {
            return false;
        }
        return roll(pos) < chance;
    }

    /**
     * そのマスの抽選の値([0, 1)、座標だけで決まる)。{@link #covered(BlockPos, double)} は {@code roll < chance}。
     * 3 つ以上に分けるとき(木材の「過繁茂した木材・覆われた版・過繁茂ブロック」、2026-10-05 夜)に同じ値をしきい値で切る。
     */
    public static double roll(BlockPos pos) {
        long h = hash(pos.getX(), pos.getY(), pos.getZ());
        // 上位 53 ビットを [0, 1) の実数にする(Random#nextDouble と同じ作り)。
        return (h >>> 11) * 0x1.0p-53;
    }

    private static long hash(int x, int y, int z) {
        long h = SALT;
        h ^= x * 0x9E3779B97F4A7C15L;
        h ^= y * 0xC2B2AE3D27D4EB4FL;
        h ^= z * 0x165667B19E3779F9L;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        h *= 0x94D049BB133111EBL;
        h ^= h >>> 29;
        return h;
    }
}
