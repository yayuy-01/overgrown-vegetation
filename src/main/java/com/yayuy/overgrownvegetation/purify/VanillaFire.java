package com.yayuy.overgrownvegetation.purify;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.OvergrownContainers;
import com.yayuy.overgrownvegetation.corruption.CorruptionDropSuppressor;
import com.yayuy.overgrownvegetation.covered.CoveredBlocks;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

/**
 * 汚染を焼く火 = ふつうの火(2026-10-09 朝から火炎放射器もふつうの火をまく —— {@link Burning#spray})。
 *
 * <p><b>ユーザー指定</b>:
 * <ul>
 *     <li>10-08 夜: 火が付いた汚染は、しばらくして汚染される前のブロックに戻る(過繁茂しているブロック・草ブロック・泥・土・砂利は焼けた土)。
 *     植物は燃えて消える。地脈・結晶など植物でないものは燃えない。火打石などの火は、焼けた土にならずに消えることもある。</li>
 *     <li>10-09 朝: 火炎放射器はふつうの火を広くつける道具。この MOD の木・植物ブロック・覆われたブロックは、燃え尽きにくく、燃え移りにくい。</li>
 *     <li>10-09: 過繁茂しているブロックに火が付くように(横の面に火が残らなかった)/ 火が燃え続ける時間を伸ばす。</li>
 * </ul>
 * 数値はすべて Claude 仮決め(10-09):
 * <ul>
 *     <li>バニラの火の表に「燃える物」として載せる。<b>燃え移りやすさはすべて 0</b>(隣の空いたマスへ火が飛ばない。燃え尽きたマスに火が残ったときだけ移る)。
 *     燃え尽きやすさは植物 {@value #PLANT_BURN}・葉 {@value #LEAF_BURN}・木 {@value #WOOD_BURN}・覆われたブロック {@value #COVERED_BURN}・
 *     汚染された地面(過繁茂しているブロック・草ブロック・土・石など){@value #GROUND_BURN}(バニラは草 100・葉 60・原木 5・木材 20)。
 *     覆われたブロックは、元のブロックが燃えるかどうかにかかわらず同じ値(10-09 朝まではバニラの値を写していた)。
 *     地面は 10-09 まで燃える物にしていなかった(バニラの決まりで燃え尽きると穴が開くため。今は燃え尽き方を横取りして焼けた土・元のブロックに変えるので穴は開かない)。</li>
 *     <li>燃え尽きると({@link #consume})、覆われたブロックと汚染された地面は元のブロック・燻る灰ブロック、植物は消える、木は消える(地面に埋まった根は燻る灰ブロック)。
 *     消えたマスにはバニラと同じ確率で火が残る。木のマスが燃え尽きたら、宙に残った部分が無いか見る({@link TreeBurn#onConsumed})。</li>
 *     <li>汚染された地面(覆われたブロックの仲間を除く)の上の火は、火の更新(約 1.75 秒ごと)のたびに {@value #GROUND_CHANCE} の確率で、
 *     その地面を元のブロック・燻る灰ブロックに変える。</li>
 *     <li>汚染の上・そばの火は、火の更新のたびに {@value #FIZZLE_CHANCE} の確率で消える(燃え尽きにくく・燃え移りにくく。10-09 に 5% から下げた)。</li>
 *     <li>燻る灰ブロックの上の火は、隣に燃える物が無くてもすぐには消えず、火の更新のたびに {@value #ASH_FIRE_OUT} の確率で消える(平均 1 分ほどくすぶる。
 *     バニラなら燃える物の無い所の火は 20 秒ほどで消える)。雨に当たると消える。</li>
 * </ul>
 * 火 1 つあたりの見込み(燃える前に消える分を引いたもの): 汚染された土を変える 8 割、隣の原木を燃やし尽くす 2 割、葉 5 割、植物 6 割、覆われたブロック 3.5 割。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class VanillaFire {

    /** 汚染の上・そばの火が、火の更新 1 回で消える確率(10-09 に「火が燃え続ける時間を伸ばして」で 5% から下げた)。 */
    public static final float FIZZLE_CHANCE = 0.03F;
    /** 汚染された地面(覆われたブロックの仲間を除く)の上の火が、火の更新 1 回でその地面を変える確率。 */
    public static final float GROUND_CHANCE = 0.15F;
    /** 燻る灰ブロックの上で燃える物の無い火が、火の更新 1 回で消える確率(平均 1 分ほどくすぶる)。 */
    public static final float ASH_FIRE_OUT = 0.03F;
    /** 燃え尽きやすさ: 汚染された地面(過繁茂しているブロック・草ブロック・土・泥・砂利・石・砂など)。 */
    public static final int GROUND_BURN = 5;
    /** 燃え尽きやすさ: 下草・つる・死骸・器官・過繁茂した木材など。 */
    public static final int PLANT_BURN = 15;
    /** 燃え尽きやすさ: 葉・菌の傘。 */
    public static final int LEAF_BURN = 10;
    /**
     * 燃え尽きやすさ: 原木・巨大樹・菌の柱。最初はバニラの原木と同じ 5 にしたが、クライアントで幹 1 本の木の根元を 3 秒吹くと、
     * 吹き始めから約 9 秒で幹が焼け切れた(「燃え切りにくく」に合わない)ので下げた。
     */
    public static final int WOOD_BURN = 2;
    /** 燃え尽きやすさ: 覆われたブロック・覆われた機械(殻)・火の消えた松明。 */
    public static final int COVERED_BURN = 5;
    private static final Direction[] DIRECTIONS = Direction.values();

    /** 燃える物の表は同時に触れないので {@code enqueueWork} の中で。この MOD のブロックの燃えやすさはここだけで書く(書く所が 2 つあると、後に走った方が勝つ)。 */
    @SubscribeEvent
    public static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(VanillaFire::register);
    }

    static void register() {
        FireBlock fire = (FireBlock) Blocks.FIRE;
        for (Block block : Burning.plants()) {
            if (!block.defaultBlockState().getFluidState().isEmpty()) {
                continue; // 汚染された水草(水の中にある)は燃える物にしない
            }
            fire.setFlammable(block, 0, PLANT_BURN);
        }
        for (Block block : Burning.leaves()) {
            fire.setFlammable(block, 0, LEAF_BURN);
        }
        for (Block block : Burning.wood()) {
            fire.setFlammable(block, 0, WOOD_BURN);
        }
        for (CoveredBlocks.Entry entry : CoveredBlocks.entries()) {
            fire.setFlammable(entry.block().get(), 0, COVERED_BURN);
        }
        fire.setFlammable(CoveredBlocks.EXTINGUISHED_TORCH.get(), 0, COVERED_BURN);
        fire.setFlammable(CoveredBlocks.EXTINGUISHED_WALL_TORCH.get(), 0, COVERED_BURN);
        fire.setFlammable(ModBlocks.OVERGROWN_HUSK.get(), 0, COVERED_BURN);
        // 汚染された地面(覆われたブロックの仲間・苔の生えるチェストなどの入れ物・水の中のものは除く)。起動時はまだタグが読まれていないので、
        // Burning#kind ではなく名前で見る(Burning#isGroundForFireTable)。
        for (Block block : BuiltInRegistries.BLOCK) {
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
            BlockState state = block.defaultBlockState();
            if (!id.getNamespace().equals(OvergrownVegetation.MODID) || !Burning.isGroundForFireTable(block, id) || Burning.isCoveredFamily(state)
                    || state.hasProperty(OvergrownContainers.OVERGROWTH) || !state.getFluidState().isEmpty()) {
                continue;
            }
            fire.setFlammable(block, 0, GROUND_BURN);
        }
    }

    // ------------------------------------------------------------------ 火の更新

    /**
     * ふつうの火の更新の頭({@code mixin/FireBlockPurifyMixin})。下が汚染された地面なら確率で変え、汚染の上・そばなら確率で消す。
     * 燻る灰ブロックの上で燃える物の無い火は、ここで消すかくすぶらせる(バニラの年齢で消える決まりを飛ばす)。
     *
     * @return バニラの残りの処理を飛ばすなら true(火を消した・くすぶらせて次の更新を予約した)
     */
    public static boolean onFireTick(ServerLevel level, BlockPos pos, RandomSource random) {
        if (!level.getGameRules().getBoolean(GameRules.RULE_DOFIRETICK)) {
            return false; // 火の広がりが切られている世界ではバニラの火も何もしない
        }
        return step(level, pos, random);
    }

    /** {@link #onFireTick} の中身(ゲームルールを見ない。GameTest は火の広がりが切られているので、ここを直接呼ぶ)。 */
    public static boolean step(ServerLevel level, BlockPos pos, RandomSource random) {
        BlockPos below = pos.below();
        BlockState ground = level.getBlockState(below);
        Burning.Kind kind = Burning.kind(ground);
        boolean layer = Burning.isLayerCovered(level, below, ground);
        boolean corruptedGround = kind == Burning.Kind.GROUND || layer;
        // 覆われたブロックの仲間はここでは変えない(バニラの火の決まりで燃え尽きる = 燃え尽きにくい)。
        // 実験的要素のオン・オフに関係なく変える(10-09 に一度オフで止めたが、10-10 にユーザー指定「炎の要素は隔離しない」で戻した)。
        if ((layer || (kind == Burning.Kind.GROUND && !Burning.isCoveredFamily(ground))) && random.nextFloat() < GROUND_CHANCE) {
            CorruptionDropSuppressor.run(() -> {
                if (Burning.convert(level, below, true)) {
                    BurntLand.noteBurnt(level, below);
                }
            });
            ground = level.getBlockState(below);
        }
        if (ground.isFireSource(level, below, Direction.UP)) {
            return false; // 消えない火(過繁茂しているネザーラックの上など)は消さない
        }
        if (ground.is(PurifyRegistry.BURNT_SOIL.get()) && !hasFlammableNeighbour(level, pos)) {
            // 燻る灰の上でくすぶる(2026-10-09 ユーザー指定「火が燃え続ける時間を伸ばして」)。バニラの火は燃える物の無い所では年齢で 20 秒ほどで消えるので、
            // ここで消すか次の更新を予約するかを決めて、バニラの残り(燃える物が無いので年齢で消すだけ)を飛ばす。
            if (random.nextFloat() < ASH_FIRE_OUT || level.isRainingAt(pos)) {
                level.removeBlock(pos, false);
            } else {
                level.scheduleTick(pos, level.getBlockState(pos).getBlock(), 30 + random.nextInt(10)); // バニラの火の更新の間隔と同じ
            }
            return true;
        }
        if ((corruptedGround || nearCorruption(level, pos)) && random.nextFloat() < FIZZLE_CHANCE) {
            level.removeBlock(pos, false);
            return true;
        }
        return false;
    }

    /** 周り 6 マスに燃える物があるか(バニラの火が年齢で消えない場所か)。 */
    private static boolean hasFlammableNeighbour(ServerLevel level, BlockPos pos) {
        BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
        for (Direction dir : DIRECTIONS) {
            n.setWithOffset(pos, dir);
            if (level.getBlockState(n).isFlammable(level, n, dir.getOpposite())) {
                return true;
            }
        }
        return false;
    }

    /** 周り 6 マスに汚染(植物・木・地面・覆われたブロック)があるか。 */
    private static boolean nearCorruption(ServerLevel level, BlockPos pos) {
        BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
        for (Direction dir : DIRECTIONS) {
            n.setWithOffset(pos, dir);
            if (Burning.kind(level.getBlockState(n)) != Burning.Kind.NONE) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ 燃え尽きる

    /**
     * ふつうの火が隣のマスを燃やすか決めるところ({@code FireBlock#checkBurnOut} の頭、{@code mixin/FireBlockPurifyMixin})。
     * この MOD が燃え尽き方を決めるマス({@link #handles})なら、バニラと同じ確率で燃やし、燃え尽き方は {@link #consume} が決める。
     *
     * ハイヴノードのまわり({@link HiveFireGuard})では、この MOD が扱わないマスもここで燃やし、燃えたマスに火を残さない(燃え広がらない)。
     *
     * @return この MOD が扱ったら true(バニラの処理 —— ブロックを消すか火にする —— を飛ばす)
     */
    public static boolean onBurnOut(Level level, BlockPos pos, int chance, RandomSource random, int age, Direction face) {
        if (!(level instanceof ServerLevel server)) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        if (!handles(state)) {
            if (!HiveFireGuard.protects(server, pos)) {
                return false;
            }
            // バニラの checkBurnOut と同じ確率で燃やすが、火にはせず消すだけ
            if (random.nextInt(chance) < state.getFlammability(level, pos, face)) {
                state.onCaughtFire(level, pos, face, null);
                level.removeBlock(pos, false);
            }
            return true;
        }
        if (random.nextInt(chance) < state.getFlammability(level, pos, face)) {
            consume(server, pos, random, age);
        }
        return true;
    }

    /** この MOD が燃え尽き方を決めるマスか: 汚染された植物・木・地面・覆われたブロックと、汚染されていない木(宙に残った部分を見るため)。 */
    public static boolean handles(BlockState state) {
        return Burning.kind(state) != Burning.Kind.NONE || TreeBurn.isTreeBlock(state);
    }

    /**
     * そのマスが燃え尽きる(テストからも直接呼ぶ —— GameTest のサーバーは火の広がりが切られている)。{@code age} はそばの火の年齢。
     * 覆われたブロックなど汚染された地面は元のブロック・焼けた土に変わり、植物と木は消える(地面に埋まった根は焼けた土)。
     * 植物・木の消えたマスには、バニラと同じく {@code 5 / (age + 10)} の確率で火が残る(雨の中では残らない。ハイヴノードのまわりでは残らない
     * —— {@link HiveFireGuard})。木なら宙に残った部分を見る。実験的要素のオン・オフには関係しない(10-10 にユーザー指定で隔離をやめた)。
     */
    public static void consume(ServerLevel level, BlockPos pos, RandomSource random, int age) {
        BlockState state = level.getBlockState(pos);
        Burning.Kind kind = Burning.kind(state);
        boolean tree = TreeBurn.isTreeBlock(state);
        boolean root = tree && TreeBurn.isWood(state) && TreeBurn.inGround(level, pos);
        CorruptionDropSuppressor.run(() -> {
            if (kind == Burning.Kind.GROUND) {
                if (Burning.convert(level, pos, true)) {
                    BurntLand.noteBurnt(level, pos); // 覆われた原木もここ(元の原木に戻るだけで、木の形は変わらない)
                }
                return;
            }
            if (root) {
                level.setBlock(pos, PurifyRegistry.BURNT_SOIL.get().defaultBlockState(), Block.UPDATE_ALL); // 空気にすると根元に穴が並ぶ
            } else if (random.nextInt(age + 10) < 5 && !level.isRainingAt(pos) && !HiveFireGuard.protects(level, pos)) {
                BlockState fire = BaseFireBlock.getState(level, pos);
                if (fire.is(Blocks.FIRE)) {
                    fire = fire.setValue(FireBlock.AGE, Math.min(age + random.nextInt(5) / 4, 15));
                }
                level.setBlock(pos, fire, Block.UPDATE_ALL);
            } else {
                level.removeBlock(pos, false);
            }
            if (kind != Burning.Kind.NONE) {
                BurntLand.noteBurnt(level, pos);
            }
            if (tree) {
                TreeBurn.onConsumed(level, pos);
            }
        });
    }

    private VanillaFire() {
    }
}
