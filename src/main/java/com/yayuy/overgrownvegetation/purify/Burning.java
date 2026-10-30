package com.yayuy.overgrownvegetation.purify;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.ally.SoldierEntity;
import com.yayuy.overgrownvegetation.block.OvergrownContainers;
import com.yayuy.overgrownvegetation.block.OvergrownDoorBlock;
import com.yayuy.overgrownvegetation.block.OvergrownHuskBlockEntity;
import com.yayuy.overgrownvegetation.corruption.CorruptionDropSuppressor;
import com.yayuy.overgrownvegetation.corruption.CorruptionRegistry;
import com.yayuy.overgrownvegetation.corruption.OvergrownLayerData;
import com.yayuy.overgrownvegetation.covered.CoveredBlocks;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.npc.Npc;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 汚染を焼く(順 2 の着手順 9)。2026-10-08 夜に「炎が当たったら焼けた土にする」から「火を付けて、燃え尽きたら変わる」へ作り直した。
 *
 * <p><b>ユーザー指定</b>(10-08 夜):
 * <ul>
 *     <li>火炎放射器は火を付ける。火が付いた汚染されたブロックは、しばらくして汚染される前のブロックに戻る。
 *     過繁茂しているブロック・過繁茂している草ブロック・泥・土・砂利は焼けた土になる。木やキノコなどの植物は燃えて消える。</li>
 *     <li>木は丸ごと燃える(原木を宙に残さない。{@link TreeBurn})。</li>
 *     <li>汚染されたブロックと植物はすべて燃える(巨大樹・器官も)。地脈と結晶などの植物ではないブロックは燃えない。</li>
 *     <li>火は燃え移りにくい。火打石などの火は焼けた土にならずに消えることもある({@link VanillaFire})。</li>
 * </ul>
 * <b>10-09 のユーザー指定</b>: 木は炎に置き換えず外側に火を付け、すぐには燃え尽きない / 過繁茂したブロック系以外にも火を付けられる。
 * <b>10-09 朝のユーザー指定</b>: 火炎放射器は火を広範囲につけるための道具。専用の炎をやめ、ふつうの火をまく({@link #spray})。
 * この MOD の木・植物ブロック・覆われたブロックは燃え尽きにくく、燃え移りにくい({@link VanillaFire})。木は 1 マスずつ燃え、幹が焼け切れて
 * 地面から離れた部分だけが丸ごと燃え落ちる({@link TreeBurn})。炎が通った下草はその場で火になる。
 *
 * <p>分け方({@link #kind}):
 * <ul>
 *     <li>{@link Kind#TREE} 木: 過繁茂の原木・木・葉、湿地の木、菌の柱と傘、発光菌糸、原木から化けた過繁茂しているブロック、巨大樹(原木・形成層・硬化した幹)。
 *     燃えて消える(地面に埋まった根は焼けた土)。</li>
 *     <li>{@link Kind#PLANT} 植物: 下草・シダ・つる・苔・死骸・器官・胞子腺・洞窟の菌、過繁茂した木材(板材・階段・ハーフ・フェンス)。燃えて消える。</li>
 *     <li>{@link Kind#GROUND} 地面など: 焼けると変わる。焼けた土になるのは上のユーザー指定の 5 つ(ハーフは 2 枚重ねだけ焼けた土、
 *     1 枚は燃え落ちる —— Claude 仮決め)。そのほかは汚染される前のブロック: 名前が {@code overgrown_<バニラの名前>} のもの(石・砂・鉱石・ネザーラック・
 *     エンドストーン・石の階段・エンドポータルの枠など)、過繁茂した塀 → 丸石の塀、覆われたブロック → 元のブロック、覆われた機械(殻) → 元の機械(中身は落とす)、
 *     消えた松明 → 松明、過繁茂したチェスト・樽・扉 → 苔が焼け落ちる(ハサミで剥がしたのと同じ)。苔の覆いの記録だけのマスは記録を消す。
 *     覆われたブロック・殻・消えた松明は燃える物(隣の火で燃え尽きると変わる)、そのほかは上に乗った火で変わる。</li>
 *     <li>{@link Kind#NONE} 焼けない: 汚染されていないもの・地脈・結晶・極相の鉱石・焼けた土・水の中のもの。</li>
 * </ul>
 */
public final class Burning {

    /** ブロックの燃え方。 */
    public enum Kind {
        /** 焼けない。 */
        NONE,
        /** 燃えて消える植物。 */
        PLANT,
        /** 木の一部(燃えて消える。幹が焼け切れて地面から離れた部分は丸ごと燃え落ちる)。 */
        TREE,
        /** 焼けると、元のブロック・焼けた土に変わる。 */
        GROUND
    }

    /** 炎の光線の向き(視線に直交する 2 軸の上のずれ。中心 + 十字 + 斜め = 9 本、半角約 6 度の細い扇)。 */
    private static final double[][] RAYS = {
            {0.0D, 0.0D},
            {0.1D, 0.0D}, {-0.1D, 0.0D}, {0.0D, 0.1D}, {0.0D, -0.1D},
            {0.07D, 0.07D}, {-0.07D, 0.07D}, {0.07D, -0.07D}, {-0.07D, -0.07D}
    };
    /** 光線を進める刻み(ブロック)。 */
    private static final double STEP = 0.5D;
    /** 炎に入った Mob を燃やす秒数。 */
    private static final float IGNITE_SECONDS = 5.0F;
    /** 炎に入った Mob へのダメージ(バニラの火 = is_fire。森の Mob は今の決まりで 1.5 倍)。 */
    private static final float FLAME_DAMAGE = 2.0F;
    /** 炎の太さ(Mob の体の箱を太らせる量、ブロック)。 */
    private static final double FLAME_WIDTH = 0.5D;
    /** {@link Direction#values()} は呼ぶたびに配列を複製するので 1 つを使い回す。 */
    private static final Direction[] DIRECTIONS = Direction.values();

    private static final String[] PLANT_IDS = {
            "overgrown_growth", "overgrown_bush", "overgrown_sapling", "giant_overgrown_sapling", "overgrown_spore_bloom",
            "overgrown_zenmai", "undeveloped_bush", "large_undeveloped_fruit", "overgrown_creeping_vine", "overgrown_shelf_lichen",
            "overgrown_thorn_vine", "overgrown_weeping_tendril", "overgrown_drip_moss", "overgrown_spore_tendril", "overgrown_tall_fern",
            "overgrown_spore_reed", "overgrown_curled_fern", "overgrown_moss_layer", "overgrown_glow_vines", "overgrown_glow_vines_plant",
            "overgrown_liana", "overgrown_lily", "overgrown_water_grass", "overgrown_tall_water_grass", "overgrown_water_column",
            "overgrown_water_column_flower", "overgrown_water_moss_layer", "overgrown_water_ceiling_growth",
            "overgrown_cow_corpse", "overgrown_pig_corpse", "overgrown_sheep_corpse", "overgrown_creeper_corpse", "overgrown_spider_corpse", "overgrown_villager_corpse", "overgrown_zombie_corpse", "overgrown_baby_zombie_corpse", "overgrown_skeleton_corpse", "overgrown_enderman_corpse", "overgrown_chicken_corpse",
            "overgrown_piglin_corpse", "overgrown_piglin_brute_corpse", "overgrown_zombified_piglin_corpse", "overgrown_wither_skeleton_corpse", "overgrown_hoglin_corpse", "overgrown_zoglin_corpse", "overgrown_strider_corpse",
            "overgrown_iron_golem_corpse", "overgrown_warden_corpse",
            "cave_spore_sac", "cave_moss_clump",
            "overgrown_planks", "overgrown_stairs", "overgrown_slab", "overgrown_fence"};
    private static final String[] WOOD_IDS = {
            "overgrown_log", "overgrown_wood", "overgrown_trunk_block", "overgrown_swamp_log", "overgrown_swamp_wood",
            "fungal_pillar", "glowing_mycelium", "giant_overgrown_log", "giant_overgrown_cambium", "hardened_giant_trunk"};
    private static final String[] LEAF_IDS = {"overgrown_leaves", "overgrown_swamp_leaves", "fungal_cap"};
    private static final String[] GIANT_IDS = {"giant_overgrown_log", "giant_overgrown_cambium", "hardened_giant_trunk"};
    /** 焼けた土になる(ユーザー指定 10-08 夜)。 */
    private static final String[] SCORCH_IDS = {"overgrown_block", "overgrown_grass_block", "overgrown_mud", "overgrown_dirt",
            "overgrown_gravel"};
    /** 焼けた土になるもののハーフ(2 枚重ねは焼けた土、1 枚は燃え落ちる)。 */
    private static final String[] SCORCH_SLAB_IDS = {"overgrown_block_slab", "overgrown_mud_slab"};
    /** 過繁茂した木材(建物の部材)。燃えて消えるが、焼けた荒地が戻るときの後始末では消さない。 */
    private static final String[] WOOD_BUILDING_IDS = {"overgrown_planks", "overgrown_stairs", "overgrown_slab", "overgrown_fence"};
    /**
     * 茂み(当たり判定の無い、葉の塊のような植物)。炎はここで止まり、茂みごとまわりに火が付く(2026-10-09)。形では見分けない
     * (下草 {@code overgrown_growth} も当たり判定の形は丸ごと 1 マスで、形で見ると下草でも炎が止まった)。
     */
    private static final String[] BUSH_IDS = {"overgrown_bush"};

    private static final Set<Block> PLANTS = new HashSet<>();
    private static final Set<Block> WOOD_BUILDING = new HashSet<>();
    private static final Set<Block> BUSHES = new HashSet<>();
    private static final Set<Block> WOOD = new HashSet<>();
    private static final Set<Block> LEAVES = new HashSet<>();
    private static final Set<Block> GIANT = new HashSet<>();
    private static final Set<Block> SCORCH = new HashSet<>();
    private static final Set<Block> SCORCH_SLABS = new HashSet<>();
    /** 汚染される前のブロック(引いた結果を覚える。無ければ AIR)。サーバースレッドだけが触る。 */
    private static final Map<Block, Block> ORIGINAL = new IdentityHashMap<>();
    private static volatile boolean ready;

    private static synchronized void build() {
        if (ready) {
            return;
        }
        add(PLANTS, PLANT_IDS);
        add(WOOD, WOOD_IDS);
        add(LEAVES, LEAF_IDS);
        add(GIANT, GIANT_IDS);
        add(SCORCH, SCORCH_IDS);
        add(SCORCH_SLABS, SCORCH_SLAB_IDS);
        add(WOOD_BUILDING, WOOD_BUILDING_IDS);
        add(BUSHES, BUSH_IDS);
        ready = true;
    }

    private static void add(Set<Block> set, String[] ids) {
        for (String id : ids) {
            Block b = mod(id);
            if (b != Blocks.AIR) {
                set.add(b);
            }
        }
    }

    private static Block mod(String path) {
        return BuiltInRegistries.BLOCK.get(OvergrownVegetation.id(path));
    }

    private static void init() {
        if (!ready) {
            build();
        }
    }

    // ------------------------------------------------------------------ 分け方

    /** ブロックの燃え方(位置で決まる苔の覆いの記録は見ない —— {@link #isLayerCovered})。 */
    public static Kind kind(BlockState state) {
        init();
        Block block = state.getBlock();
        if (WOOD.contains(block) || LEAVES.contains(block)) {
            return Kind.TREE;
        }
        if (PLANTS.contains(block)) {
            return Kind.PLANT;
        }
        if (SCORCH.contains(block) || SCORCH_SLABS.contains(block) || block == ModBlocks.OVERGROWN_HUSK.get()
                || state.hasProperty(OvergrownContainers.OVERGROWTH)
                || block == CoveredBlocks.EXTINGUISHED_TORCH.get() || block == CoveredBlocks.EXTINGUISHED_WALL_TORCH.get()
                || CoveredBlocks.vanillaOf(block) != null || original(state) != null) {
            return Kind.GROUND;
        }
        return Kind.NONE;
    }

    /**
     * 燃える物の表に載せる汚染された地面か(起動時用。まだタグが読まれていないので {@link #original} を使わない —— 使うと「元が無い」を覚えてしまう)。
     * 焼けた土になる 5 つとそのハーフ、過繁茂した塀、名前が {@code overgrown_<バニラの名前>} で植物・木でないもの(石・砂・鉱石・ネザーラック・石の階段など)。
     */
    static boolean isGroundForFireTable(Block block, ResourceLocation id) {
        init();
        if (SCORCH.contains(block) || SCORCH_SLABS.contains(block) || block == ModBlocks.OVERGROWN_WALL.get()) {
            return true;
        }
        if (PLANTS.contains(block) || WOOD.contains(block) || LEAVES.contains(block) || !id.getPath().startsWith("overgrown_")) {
            return false;
        }
        String vanilla = id.getPath().substring("overgrown_".length());
        if (vanilla.equals("netherrack") || vanilla.equals("magma_block")) {
            // 元が消えない火の台(ネザーラック・マグマブロック)は燃える物にしない: 溶岩がそばに火を付け、元に戻ったあと火が消えずに増え続ける。
            return false;
        }
        return BuiltInRegistries.BLOCK.containsKey(ResourceLocation.withDefaultNamespace(vanilla));
    }

    /** 茂み({@link #BUSH_IDS})か。 */
    static boolean isBush(BlockState state) {
        init();
        return BUSHES.contains(state.getBlock());
    }

    /** 覆われたブロックの仲間(覆われたブロック・覆われた機械(殻)・火の消えた松明)か。燃え尽きにくく、上の火では変わらない({@link VanillaFire})。 */
    public static boolean isCoveredFamily(BlockState state) {
        Block block = state.getBlock();
        return CoveredBlocks.vanillaOf(block) != null || block == ModBlocks.OVERGROWN_HUSK.get()
                || block == CoveredBlocks.EXTINGUISHED_TORCH.get() || block == CoveredBlocks.EXTINGUISHED_WALL_TORCH.get();
    }

    /** この MOD の木の幹・枝(巨大樹・菌の柱を含む)か。 */
    public static boolean isModWood(BlockState state) {
        init();
        return WOOD.contains(state.getBlock());
    }

    /** この MOD の木の葉(菌の傘を含む)か。 */
    public static boolean isModLeaf(BlockState state) {
        init();
        return LEAVES.contains(state.getBlock());
    }

    /** 巨大樹のブロックか。 */
    public static boolean isGiant(BlockState state) {
        init();
        return GIANT.contains(state.getBlock());
    }

    /** 燃えて消える植物・木のブロックの一覧(ふつうの火の燃えやすさの登録用)。 */
    static Set<Block> plants() {
        init();
        return PLANTS;
    }

    static Set<Block> wood() {
        init();
        return WOOD;
    }

    static Set<Block> leaves() {
        init();
        return LEAVES;
    }

    /** 焼けた土になる地面か(ふつうの火の下で変わる判定・テスト用)。 */
    public static boolean scorches(BlockState state) {
        init();
        return SCORCH.contains(state.getBlock()) || SCORCH_SLABS.contains(state.getBlock());
    }

    /**
     * 汚染される前のブロック。名前が {@code overgrown_<バニラの名前>} で {@code #corrupted} に入っているもの(石・砂・鉱石・ネザーラック・
     * エンドストーン・石の階段・エンドポータルの枠など)と、過繁茂した塀 → 丸石の塀。無ければ {@code null}。
     */
    @Nullable
    public static Block original(BlockState state) {
        Block block = state.getBlock();
        Block cached = ORIGINAL.get(block);
        if (cached == null) {
            cached = Blocks.AIR;
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
            if (block == ModBlocks.OVERGROWN_WALL.get()) {
                cached = Blocks.COBBLESTONE_WALL;
            } else if (id.getNamespace().equals(OvergrownVegetation.MODID) && id.getPath().startsWith("overgrown_")
                    && state.is(ModTags.CORRUPTED)) {
                cached = BuiltInRegistries.BLOCK.getOptional(ResourceLocation.withDefaultNamespace(
                        id.getPath().substring("overgrown_".length()))).orElse(Blocks.AIR);
            }
            ORIGINAL.put(block, cached);
        }
        return cached == Blocks.AIR ? null : cached;
    }

    /** 苔の覆いの記録だけのマス(ブロックは元のまま)か。 */
    static boolean isLayerCovered(ServerLevel level, BlockPos pos, BlockState state) {
        return !state.isAir() && OvergrownLayerData.isCovered(level, pos);
    }

    private static BlockState burntSoil() {
        return PurifyRegistry.BURNT_SOIL.get().defaultBlockState();
    }

    // ------------------------------------------------------------------ 1 マス

    /**
     * 焼けたマスを変える({@link Kind#GROUND} と苔の覆いの記録)。{@code scorch} が false なら焼けた土にはせず
     * 汚染される前のブロックへ戻す(焼けた荒地が元に戻るときの後始末 {@link #cleanChunk})。水の中・水に浸かったものは変えない。
     *
     * @return 変えたら true
     */
    public static boolean convert(ServerLevel level, BlockPos pos, boolean scorch) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || !state.getFluidState().isEmpty()) {
            return false;
        }
        init();
        Block block = state.getBlock();
        BlockState after = null;
        if (SCORCH.contains(block)) {
            Block original = original(state);
            after = scorch ? burntSoil() : (original != null ? original : Blocks.DIRT).defaultBlockState();
        } else if (SCORCH_SLABS.contains(block)) {
            boolean full = state.hasProperty(SlabBlock.TYPE) && state.getValue(SlabBlock.TYPE) == SlabType.DOUBLE;
            after = full ? (scorch ? burntSoil() : Blocks.DIRT.defaultBlockState()) : Blocks.AIR.defaultBlockState();
        } else if (block == ModBlocks.OVERGROWN_HUSK.get()) {
            return restoreHusk(level, pos);
        } else if (state.hasProperty(OvergrownContainers.OVERGROWTH)) {
            if (state.getValue(OvergrownContainers.OVERGROWTH) <= 0) {
                return false;
            }
            // 苔が焼け落ちる(ハサミで剥がしたのと同じ。中身はそのまま、時間がたつとまた覆われる)。
            if (block instanceof OvergrownDoorBlock) {
                OvergrownDoorBlock.setBothHalves(level, pos, state, 0);
            } else {
                OvergrownContainers.setSingle(level, pos, state, 0);
            }
            return true;
        } else if (block == CoveredBlocks.EXTINGUISHED_TORCH.get()) {
            after = Blocks.TORCH.defaultBlockState();
        } else if (block == CoveredBlocks.EXTINGUISHED_WALL_TORCH.get()) {
            after = CorruptionRegistry.copyMatchingProperties(state, Blocks.WALL_TORCH.defaultBlockState());
        } else if (CoveredBlocks.vanillaOf(block) != null) {
            after = CoveredBlocks.vanillaState(state); // 苔が焼け落ちて元のブロックに戻る(向きなどの状態はそのまま)
        } else {
            Block original = original(state);
            if (original != null) {
                after = CorruptionRegistry.copyMatchingProperties(state, original.defaultBlockState());
            }
        }
        if (after == null) {
            if (OvergrownLayerData.isCovered(level, pos)) {
                OvergrownLayerData.uncover(level, pos); // 苔の覆いの記録だけのマス: 記録を消す
                return true;
            }
            return false;
        }
        level.setBlock(pos, after, Block.UPDATE_ALL);
        return true;
    }

    /**
     * 覆われた機械(殻)を元の機械へ戻す。殻は壊れると元の機械をアイテムで返すので、先に元のブロックを預かり直してから置き換える
     * (置き換えで落ちるのは中身だけ。機械が 2 つにならない)。
     */
    private static boolean restoreHusk(ServerLevel level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof OvergrownHuskBlockEntity husk)) {
            return false;
        }
        BlockState mimic = husk.releaseMimic();
        level.setBlock(pos, mimic.isAir() ? Blocks.AIR.defaultBlockState() : mimic, Block.UPDATE_ALL);
        return true;
    }

    /**
     * そのマスに火を置けるか: 空気か、燃える薄い植物(汚染された植物と、汚染されていない草・花・つるなど。植物ごと火になる ——
     * 2026-10-09 朝のユーザー指定「炎が通った下草はその場で火になる」)。木・水の中には置かない。
     */
    static boolean canHoldFire(ServerLevel level, BlockPos pos, BlockState state) {
        return state.isAir() || isLightPlant(level, pos, state);
    }

    /**
     * 燃える薄い植物(炎が通り抜け、植物ごと火になる)か: 固くなく、水が無く、木でなく、汚染された植物か、汚染されていない燃える物(草・花・つる・じゅうたんなど)。
     * 覆われたブロックなど汚染された地面の薄いもの(消えた松明など)は入れない(燃え尽きにくい —— 隣の火で燃え尽きると元に戻る)。
     */
    static boolean isLightPlant(ServerLevel level, BlockPos pos, BlockState state) {
        if (state.blocksMotion() || !state.getFluidState().isEmpty() || TreeBurn.isTreeBlock(state)) {
            return false;
        }
        Kind kind = kind(state);
        return kind == Kind.PLANT || (kind == Kind.NONE && state.isFlammable(level, pos, Direction.UP));
    }

    /**
     * そのマスにふつうの火を置く(火炎放射器・燃え落ちる木)。置けるのは {@link #canHoldFire} のマスで、ふつうの火が残れる所だけ
     * (下が固いか、隣に燃える物がある。ソウルサンドの上は魂の火)。植物のマスなら植物ごと火になる。
     *
     * @return 置いたら true(もう火・置けないマスなら false)
     */
    public static boolean ignite(ServerLevel level, BlockPos pos) {
        BlockState fire = fireFor(level, pos);
        if (fire == null) {
            return false;
        }
        level.setBlock(pos, fire, Block.UPDATE_ALL);
        return true;
    }

    /** そのマスに置けるふつうの火({@link #ignite} の条件。置けなければ {@code null})。 */
    @Nullable
    private static BlockState fireFor(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.is(BlockTags.FIRE) || !canHoldFire(level, pos, state)) {
            return null;
        }
        BlockState fire = BaseFireBlock.getState(level, pos);
        return fire.canSurvive(level, pos) ? fire : null;
    }

    // ------------------------------------------------------------------ 焼けた荒地が戻るときの後始末

    /**
     * 焼けた荒地が元に戻るときの後始末(2026-10-08 にクライアントで足した仮決め。焼けた荒地になるのは地面の汚染が半分を下回ったときなので、
     * 戻した時点でまだ半分近く汚染が残り、周りの森がすぐ取り返していた): チャンクの地表に残った汚染を消す。植物・つる・死骸は空気、
     * 汚染された地面は汚染される前のブロック(焼けた土にはしない)、苔の覆いは元のブロック。焼けた土はそのまま(自分で土に戻る)。
     * <b>木は丸ごと消す</b>(10-08 夜。上から数段だけ消すと幹が宙に残る。巨大樹は大きすぎるので残す)。
     * 列ごとに一番上から下へ見て、最初の固いブロックまで(植物の茂みは 6 段まで、木は数えない)。周り 8 チャンクも読み込まれていること。
     *
     * @return 置き換えた数
     */
    public static int cleanChunk(ServerLevel level, LevelChunk chunk) {
        init();
        int[] changed = {0};
        ChunkPos cp = chunk.getPos();
        CorruptionDropSuppressor.run(() -> {
            BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                    int seen = 0;
                    for (int y = top, steps = 0; y >= level.getMinBuildHeight() && seen < 6 && steps < 96; y--, steps++) {
                        p.set(cp.getMinBlockX() + x, y, cp.getMinBlockZ() + z);
                        BlockState state = level.getBlockState(p);
                        if (state.isAir()) {
                            continue;
                        }
                        if (!state.getFluidState().isEmpty()) {
                            break;
                        }
                        if (TreeBurn.isTreeBlock(state)) {
                            changed[0] += TreeBurn.removeQuietly(level, p.immutable());
                            continue; // 木(消せなかった巨大樹・建物の原木も)は数えずに下へ
                        }
                        seen++;
                        Kind kind = kind(state);
                        BlockState after = null;
                        if (kind == Kind.PLANT && !WOOD_BUILDING.contains(state.getBlock())) {
                            level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                            changed[0]++;
                            after = Blocks.AIR.defaultBlockState();
                        } else if (kind == Kind.GROUND || isLayerCovered(level, p, state)) {
                            if (convert(level, p.immutable(), false)) {
                                changed[0]++;
                            }
                            after = level.getBlockState(p);
                        }
                        if ((after != null ? after : state).blocksMotion()) {
                            break; // 地面まで来た
                        }
                    }
                }
            }
        });
        return changed[0];
    }

    // ------------------------------------------------------------------ 炎を吹く

    /**
     * 火炎放射器の 1 回分({@link FlamethrowerItem} が 2 tick ごとに呼ぶ)。目から視線の向きへ 9 本の光線を {@code range} まで飛ばし、
     * 当たった所にふつうの火を付ける({@link #flameRay})。水に入ったら止まる。
     * {@code hurt} なら扇の中の Mob に火を付ける(撃った本人・ほかのプレイヤー・同盟軍の兵士・飼いならした動物・村人は除く)。
     */
    public static void spray(ServerLevel level, LivingEntity shooter, double range, boolean hurt) {
        Vec3 eye = shooter.getEyePosition();
        Vec3 look = shooter.getLookAngle().normalize();
        Vec3 side = look.cross(new Vec3(0.0D, 1.0D, 0.0D));
        Vec3 right = side.lengthSqr() < 1.0E-6D ? new Vec3(1.0D, 0.0D, 0.0D) : side.normalize();
        Vec3 up = right.cross(look).normalize();
        LongOpenHashSet visited = new LongOpenHashSet();
        LongOpenHashSet bushes = new LongOpenHashSet();
        double[] reach = new double[RAYS.length];
        Vec3[] dirs = new Vec3[RAYS.length];
        CorruptionDropSuppressor.run(() -> {
            for (int i = 0; i < RAYS.length; i++) {
                dirs[i] = look.add(right.scale(RAYS[i][0])).add(up.scale(RAYS[i][1])).normalize();
                reach[i] = flameRay(level, eye, dirs[i], range, visited, bushes);
            }
        });
        if (hurt) {
            igniteAlongRays(level, shooter, eye, dirs, reach, range);
        }
        flameParticles(level, eye, look, reach[0]);
    }

    /**
     * 光線 1 本ぶん火を付ける。炎が届いた距離を返す(粒の長さ)。{@code visited} は同じ回の別の光線と同じマスを二度扱わないため。
     * {@code bushes} は同じ回に燃やした茂みのマス(ほかの光線もそこで止まる —— 止めないと、先の光線が燃やした跡を後の光線がすり抜け、1 回で茂みの塊を掘り抜いた)。
     * 2026-10-09 朝から、火炎放射器は火を広範囲につけるための道具(ユーザー指定)。付けるのはふつうの火で、燃え方はふつうの火の決まり({@link VanillaFire})。
     * <ul>
     *     <li>燃える薄い植物(下草・草・花・つるなど)はその場で火になり(ユーザー指定「炎が通った下草はその場で火になる」)、炎は通り抜ける。
     *     もう燃えている火も通り抜ける。ただし過繁茂した茂み(葉の塊のような植物)は、炎がそこで止まり、茂みごとまわりに火が付く
     *     (2026-10-09 ユーザー指摘「過繁茂した茂みに火がつけれない」—— 当たり判定が無いので炎がすり抜け、通った列にしか付かなかった)。</li>
     *     <li>そのほかの薄いもの(松明・レール・消えた松明など)も通り抜ける。</li>
     *     <li>固いブロック・木で止まり、当たったブロックのまわり 3 × 3 × 3(手前・横・裏)に火を付ける({@link #splash})。木・固い植物(器官など)・覆われたブロックは、
     *     その場では燃えない(隣の火で、燃え尽きにくく燃える)。</li>
     * </ul>
     */
    static double flameRay(ServerLevel level, Vec3 from, Vec3 dir, double range, LongOpenHashSet visited, LongOpenHashSet bushes) {
        BlockPos prev = null;
        for (double t = 0.75D; t <= range; t += STEP) {
            BlockPos pos = BlockPos.containing(from.add(dir.scale(t)));
            if (pos.equals(prev)) {
                continue;
            }
            if (bushes.contains(pos.asLong())) {
                return t; // 同じ回に燃やした茂み: ほかの光線もそこで止まる
            }
            BlockState state = level.getBlockState(pos);
            if (!state.getFluidState().isEmpty()) {
                return t; // 水に入ったら炎は消える
            }
            boolean first = visited.add(pos.asLong());
            if (state.isAir() || state.is(BlockTags.FIRE)) {
                prev = pos;
                continue;
            }
            if (isLightPlant(level, pos, state)) {
                if (isBush(state)) {
                    if (first) {
                        splash(level, pos, bushes); // 過繁茂した茂み: 炎はそこで止まり、茂みごとまわりに火が付く
                    }
                    return t;
                }
                if (first) {
                    ignite(level, pos);
                }
                prev = pos;
                continue; // 下草はその場で火になり、炎は通り抜ける
            }
            if (!state.blocksMotion() && !TreeBurn.isTreeBlock(state)) {
                prev = pos;
                continue; // 薄いもの(松明・レールなど)は通り抜ける
            }
            if (first) {
                splash(level, pos, bushes);
            }
            return t; // 固いブロック・木で止まる
        }
        return range;
    }

    /**
     * 炎が当たったブロックのまわり 3 × 3 × 3(手前・横・裏・上下)に火を付ける(2026-10-09 ユーザー指定「ブロックの裏や側面にも火をつけれるように」。
     * それまでは当たった面の手前の 3 × 3 だけだった)。置くのは空気か燃える薄い植物のマスで、ふつうの火が残れる所だけ
     * (下が固いか、隣に燃える物がある —— {@link #ignite})。燃えない壁の前の宙には付かない。地面に当たったときは、まわりも地面なので手前(上)にだけ付く。
     * もとは 2026-10-08 にクライアントで足した「当たった所の周り 3 × 3 も焼く」。
     */
    private static void splash(ServerLevel level, BlockPos hit, LongOpenHashSet bushes) {
        // 先に全部のマスで火が残れるかを見てから置く(茂みの塊は、隣の茂みが先に火になると燃える物の隣でなくなり、置けなくなる)。
        // 当たったのが茂みなら茂みも火になる(固いブロックには付かない)。支えの茂みが火になって宙に浮いた火は、バニラの決まりで消える(燃え落ちた)。
        List<BlockPos> cells = new ArrayList<>(27);
        List<BlockState> fires = new ArrayList<>(27);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos cell = hit.offset(dx, dy, dz);
                    BlockState fire = fireFor(level, cell);
                    if (fire != null) {
                        cells.add(cell);
                        fires.add(fire);
                        if (isBush(level.getBlockState(cell))) {
                            bushes.add(cell.asLong()); // 茂みだった(同じ回のほかの光線はここで止まる)
                        }
                    }
                }
            }
        }
        for (int i = 0; i < cells.size(); i++) {
            level.setBlock(cells.get(i), fires.get(i), Block.UPDATE_ALL);
        }
    }

    /**
     * 炎の光線が届いた所まで(固いブロックで止まった所まで)に体が入っている Mob に火を付ける。体の箱は {@value #FLAME_WIDTH} だけ太らせる(炎の太さ)。
     * 最初は「視線から 20 度以内」で見ていたが、目の高さより低い小さな Mob(豚など)が目の前にいても外れた(2026-10-08 の GameTest)。
     */
    private static void igniteAlongRays(ServerLevel level, LivingEntity shooter, Vec3 eye, Vec3[] dirs, double[] reach, double range) {
        AABB box = new AABB(eye, eye.add(dirs[0].scale(range))).inflate(2.5D);
        for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class, box, e -> canBurn(shooter, e))) {
            AABB body = target.getBoundingBox().inflate(FLAME_WIDTH);
            boolean hit = false;
            for (int i = 0; i < dirs.length && !hit; i++) {
                hit = body.contains(eye) || body.clip(eye, eye.add(dirs[i].scale(reach[i]))).isPresent();
            }
            if (!hit) {
                continue;
            }
            target.igniteForSeconds(IGNITE_SECONDS);
            target.hurt(level.damageSources().inFire(), FLAME_DAMAGE);
        }
    }

    /** 燃やしてよい相手か(撃った本人・プレイヤー・同盟軍の兵士・飼いならした動物・村人などの NPC は燃やさない)。 */
    static boolean canBurn(LivingEntity shooter, LivingEntity target) {
        if (target == shooter || !target.isAlive() || target instanceof Player || target instanceof SoldierEntity
                || target instanceof Npc) {
            return false;
        }
        return !(target instanceof TamableAnimal tame && tame.isTame());
    }

    /** 炎の粒(サーバーから近くの全員へ)。 */
    private static void flameParticles(ServerLevel level, Vec3 eye, Vec3 look, double reach) {
        Vec3 start = eye.add(look.scale(0.8D)).add(0.0D, -0.25D, 0.0D);
        int points = Math.max(2, (int) Math.ceil(reach / 1.2D));
        for (int i = 0; i < points; i++) {
            double t = (i + 0.5D) / points * Math.max(1.0D, reach - 0.8D);
            Vec3 p = start.add(look.scale(t));
            double spread = 0.05D + 0.06D * t;
            level.sendParticles(ParticleTypes.FLAME, p.x, p.y, p.z, 2, spread, spread, spread, 0.01D);
            if (i == points - 1) {
                level.sendParticles(ParticleTypes.SMOKE, p.x, p.y, p.z, 2, spread, spread, spread, 0.01D);
            }
        }
    }

    private Burning() {
    }
}
