package com.yayuy.overgrownvegetation.covered;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.ModItems;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.DoubleHighBlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.AmethystBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.ChainBlock;
import net.minecraft.world.level.block.CopperBulbBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.NetherrackBlock;
import net.minecraft.world.level.block.PoweredBlock;
import net.minecraft.world.level.block.PressurePlateBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.WaterloggedTransparentBlock;
import net.minecraft.world.level.block.WeatheringCopperFullBlock;
import net.minecraft.world.level.block.WeightedPressurePlateBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.WoodType;
import net.neoforged.fml.util.ObfuscationReflectionHelper;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>覆われたブロック</b>(2026-10-05、ユーザー要望)。バニラの建築タブのブロック全部(蝋引きの銅を除く 366 個)に、
 * 汚染の覆い({@code overgrown_layer} の苔)を重ねた版。id は {@code covered_<バニラの id>}。
 *
 * <ul>
 *     <li><b>働きはバニラと同じ</b> —— 同じ種類のブロックを同じ性質({@code ofFullCopy})で作る。階段はつながり、
 *     ドアは開き、銅の電球は灯る。ドロップ・タグ(採掘の道具・つながり・燃料など)もバニラから写してある。</li>
 *     <li><b>銅は錆びない</b> —— 覆われて空気に触れないので、錆びる銅は蝋引きと同じ種類で作る。
 *     だから蝋引きの版は作らない(見た目も働きも同じになる)。</li>
 *     <li><b>見た目は描画側</b>({@code client/CoveredBlockModels})。元のブロックの模型を借りて苔の面を足す
 *     —— 汚染が対応表に無いブロックを覆うときの苔({@code OvergrownLayerQuads})と同じ作り。</li>
 *     <li><b>汚染がバニラのブロックをこれへ置き換える</b>(2026-10-05 夜、ユーザー指定「汚染された扉が対応した扉にならず
 *     過繁茂したドアになった。ほかにもあれば直して」)。置き換えは {@code CorruptionRegistry} の特別扱い({@link #coveredOf})。
 *     前から専用の「過繁茂している〜」がある 10 個(石・丸石など)だけはそちらのまま。</li>
 *     <li><b>汚染済み</b>(タグ {@code #overgrown_vegetation:covered} を {@code #corrupted} が含む)。もう汚染されない
 *     (苔を重ねない・汎用の汚染ブロックに置き換えない)うえ、汚染済みの判定・基地の修理・生物群系の数え方にも汚染済みとして入る
 *     —— 以前の置き換え先(過繁茂している板材など)と同じ扱い。最初は {@code #corruption_immune} だった。</li>
 * </ul>
 *
 * <p>一覧・素材・ドロップ・タグ・名前・板材のレシピは {@code tools/covered-blocks/CoveredGen.java} の生成物
 * ({@link CoveredBlockList})。ここは登録と、種類ごとの作り方だけ。共有の {@link ModBlocks} / {@link ModItems} を
 * 太らせないよう DeferredRegister だけ借りる({@link #init()} を {@code ModBlocks.register} より前に呼ぶ ——
 * {@code base.BaseBlocks} と同じ流儀)。
 */
public final class CoveredBlocks {

    /** 覆われたブロック 1 つぶん。{@code vanilla} は元のブロック。 */
    public record Entry(Block vanilla, DeferredBlock<Block> block, DeferredItem<? extends Item> item) {
    }

    private static final List<Entry> ENTRIES = new ArrayList<>();

    /**
     * 火の消えた松明(2026-10-05、{@link ExtinguishedTorchBlock})。汚染が松明に届くとこれになる。光らない・炎も煙も出ない。
     * アイテムは無い(壊すと棒)。壁の松明は向きを引き継ぐ。
     */
    public static final DeferredBlock<Block> EXTINGUISHED_TORCH = ModBlocks.BLOCKS.register("extinguished_torch",
            () -> new ExtinguishedTorchBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.TORCH).lightLevel(state -> 0)));
    public static final DeferredBlock<Block> EXTINGUISHED_WALL_TORCH = ModBlocks.BLOCKS.register("extinguished_wall_torch",
            () -> new ExtinguishedTorchBlock.Wall(BlockBehaviour.Properties.ofFullCopy(Blocks.WALL_TORCH).lightLevel(state -> 0)));

    /**
     * 原木から化けた過繁茂しているブロック(2026-10-05 夜、{@link OvergrownTrunkBlock})。見た目は過繁茂しているブロックと同じで、
     * {@code #logs} に入っているので木の一部として片付く(幹の途中に残って宙に浮かない)。アイテムは無い。
     * 性質は過繁茂しているブロックの写し(ModBlocks の登録が先に済んでいる —— この登録は ModBlocks の後ろに積まれる)。
     */
    public static final DeferredBlock<Block> OVERGROWN_TRUNK_BLOCK = ModBlocks.BLOCKS.register("overgrown_trunk_block",
            () -> new OvergrownTrunkBlock(BlockBehaviour.Properties.ofFullCopy(ModBlocks.OVERGROWN_BLOCK.get())));

    /** 覆われたブロック → 元のブロック。登録が済んでから初めて引かれたときに作る。 */
    private static volatile Map<Block, Block> vanillaOf;
    /** 元のブロック(蝋引きの銅を含む) → 覆われたブロック。同じく初めて引かれたときに作る。 */
    private static volatile Map<Block, Block> coveredOf;

    static {
        for (String id : CoveredBlockList.VANILLA_IDS) {
            Block vanilla = BuiltInRegistries.BLOCK.get(ResourceLocation.withDefaultNamespace(id));
            if (vanilla == Blocks.AIR) {
                OvergrownVegetation.LOGGER.error("覆われたブロックの元 minecraft:{} が無い。飛ばす。", id);
                continue;
            }
            String name = "covered_" + id;
            DeferredBlock<Block> block = ModBlocks.BLOCKS.register(name, () -> create(vanilla, id));
            Item.Properties props = new Item.Properties();
            if (vanilla.asItem().components().has(DataComponents.FIRE_RESISTANT)) {
                props.fireResistant(); // ネザライトブロック
            }
            DeferredItem<? extends Item> item = vanilla instanceof DoorBlock
                    ? ModItems.ITEMS.register(name, () -> new DoubleHighBlockItem(block.get(), props))
                    : ModItems.ITEMS.registerSimpleBlockItem(block, props);
            ENTRIES.add(new Entry(vanilla, block, item));
        }
    }

    private CoveredBlocks() {
    }

    /** 静的初期化で登録を済ませる。{@code ModBlocks.register} より前に呼ぶ。 */
    public static void init() {
    }

    /** バニラの建築タブの並び。 */
    public static List<Entry> entries() {
        return ENTRIES;
    }

    /** 覆われたブロックの元のブロック。覆われたブロックでなければ {@code null}。 */
    public static Block vanillaOf(Block covered) {
        Map<Block, Block> map = vanillaOf;
        if (map == null) {
            map = new IdentityHashMap<>();
            for (Entry entry : ENTRIES) {
                map.put(entry.block().get(), entry.vanilla());
            }
            vanillaOf = map;
        }
        return map.get(covered);
    }

    /**
     * 元のブロックの覆われた版。無ければ {@code null}。蝋引きの銅は、蝋を引いていない同じ錆び具合の覆われたブロック
     * (覆われた銅は錆びないので蝋引きと同じもの)。汚染が置き換え先を決めるのに使う({@code CorruptionRegistry})。
     */
    public static Block coveredOf(Block vanilla) {
        return coveredByVanilla().get(vanilla);
    }

    /** {@link #coveredOf} の表そのもの(元のブロック → 覆われたブロック。読むだけ)。 */
    public static Map<Block, Block> coveredByVanilla() {
        Map<Block, Block> map = coveredOf;
        if (map == null) {
            map = new IdentityHashMap<>();
            for (Entry entry : ENTRIES) {
                Block covered = entry.block().get();
                map.put(entry.vanilla(), covered);
                ResourceLocation waxed = ResourceLocation.withDefaultNamespace(
                        "waxed_" + BuiltInRegistries.BLOCK.getKey(entry.vanilla()).getPath());
                if (BuiltInRegistries.BLOCK.containsKey(waxed)) {
                    map.put(BuiltInRegistries.BLOCK.get(waxed), covered);
                }
            }
            map = java.util.Collections.unmodifiableMap(map);
            coveredOf = map;
        }
        return map;
    }

    /**
     * 覆われた版と元のブロックを入れ替えたとき(汚染で覆う・ハサミで剥がす)、入れ替える前のブロックに入っていた予定の tick
     * (押されたボタン・踏まれた感圧板が「戻るか確かめる」)を入れ替え先へ移す。予定はブロックの<b>種類</b>に付いているので、
     * 入れ替えると消え、押されたまま固まる(感圧板は信号が 0 のときしか乗っているものを確かめない —— つないだ扉が開きっぱなしになる)。
     */
    public static void carryScheduledTick(net.minecraft.world.level.LevelAccessor level, net.minecraft.core.BlockPos pos,
                                          BlockState from, BlockState to) {
        if (from.getBlock() != to.getBlock() && level.getBlockTicks().hasScheduledTick(pos, from.getBlock())) {
            level.scheduleTick(pos, to.getBlock(), 1);
        }
    }

    /** 覆われたブロックの状態を、同じ属性の値を持つ元のブロックの状態へ写す(模型を借りるため)。 */
    public static BlockState vanillaState(BlockState covered) {
        Block vanilla = vanillaOf(covered.getBlock());
        if (vanilla == null) {
            return covered;
        }
        return sameValues(vanilla.defaultBlockState(), covered);
    }

    /** {@code target} に、{@code source} と共通の属性の値を写したもの(覆われ具合のような片方だけの属性は無視)。 */
    private static BlockState sameValues(BlockState target, BlockState source) {
        BlockState out = target;
        for (Property<?> property : source.getProperties()) {
            if (out.hasProperty(property)) {
                out = copy(out, source, property);
            }
        }
        return out;
    }

    private static <T extends Comparable<T>> BlockState copy(BlockState to, BlockState from, Property<T> property) {
        return to.setValue(property, from.getValue(property));
    }

    // ---- 種類ごとの作り方 ----

    /**
     * 元と同じ種類のブロックを、元の性質の写しで作る。種類ごとの引数(ドアの音の組・ボタンの押されている長さなど)は
     * 元の id から引く。錆びる銅は錆びない種類で作る(上の説明)。知らない種類が来たら起動で止める ——
     * バニラの建築タブが増えたとき、黙って普通のブロックに化けさせないため。
     */
    private static Block create(Block vanilla, String id) {
        BlockBehaviour.Properties props = BlockBehaviour.Properties.ofFullCopy(vanilla);
        if (vanilla.getStateDefinition().getPossibleStates().stream().anyMatch(s -> s.getLightEmission() > 0)) {
            // 覆われた光源は元の明るさの 4 割(海のランタン 15 → 6、銅の電球。CoveredLight、2026-10-05 ユーザー要望)。
            // 状態ごとの明るさは、同じ値の属性を持つ元の状態から引く(点いた電球だけ光る)。
            props.lightLevel(state -> CoveredLight.dim(sameValues(vanilla.defaultBlockState(), state).getLightEmission()));
        }
        if (vanilla instanceof StairBlock stairs) {
            return new StairBlock(baseOf(stairs), props);
        }
        if (vanilla instanceof SlabBlock) {
            return new SlabBlock(props);
        }
        if (vanilla instanceof WallBlock) {
            return new WallBlock(props);
        }
        // 手で動かすもの(扉・トラップドア・フェンスゲート・ボタン・レバー)は時々動かない(CoveredInteractables)。
        if (vanilla instanceof FenceGateBlock) {
            return new CoveredInteractables.FenceGate(woodType(id, "_fence_gate"), props);
        }
        if (vanilla instanceof FenceBlock) {
            return new FenceBlock(props);
        }
        if (vanilla instanceof DoorBlock door) {
            return door.type().canOpenByHand()
                    ? new CoveredInteractables.Door(door.type(), props)
                    : new DoorBlock(door.type(), props); // 鉄の扉は手で開かない
        }
        if (vanilla instanceof TrapDoorBlock) {
            BlockSetType type = setType(id, "_trapdoor");
            return type.canOpenByHand()
                    ? new CoveredInteractables.TrapDoor(type, props)
                    : new TrapDoorBlock(type, props);
        }
        if (vanilla instanceof LeverBlock) {
            return new CoveredInteractables.Lever(props);
        }
        if (vanilla instanceof WeightedPressurePlateBlock) {
            return id.startsWith("heavy_")
                    ? new WeightedPressurePlateBlock(150, BlockSetType.IRON, props)
                    : new WeightedPressurePlateBlock(15, BlockSetType.GOLD, props);
        }
        if (vanilla instanceof PressurePlateBlock) {
            return new PressurePlateBlock(setType(id, "_pressure_plate"), props);
        }
        if (vanilla instanceof ButtonBlock) {
            BlockSetType type = setType(id, "_button");
            // 木のボタンは 30 tick(矢でも押せる)、石のボタンは 20 tick —— バニラの woodenButton / stoneButton と同じ。
            return new CoveredInteractables.Button(type, type.canButtonBeActivatedByArrows() ? 30 : 20, props);
        }
        if (vanilla instanceof CopperBulbBlock) {
            return new CopperBulbBlock(props); // 錆びる電球もここ(錆びない電球として作る)
        }
        if (vanilla instanceof WaterloggedTransparentBlock) {
            return new WaterloggedTransparentBlock(props); // 銅の格子
        }
        if (vanilla instanceof IronBarsBlock) {
            return new IronBarsBlock(props);
        }
        if (vanilla instanceof ChainBlock) {
            return new ChainBlock(props); // RotatedPillarBlock の子なので先に見る
        }
        if (vanilla instanceof RotatedPillarBlock) {
            return new RotatedPillarBlock(props);
        }
        if (vanilla instanceof PoweredBlock) {
            return new PoweredBlock(props); // レッドストーンブロック(覆われても信号は出す)
        }
        if (vanilla instanceof NetherrackBlock) {
            return new NetherrackBlock(props);
        }
        if (vanilla instanceof AmethystBlock) {
            return new AmethystBlock(props);
        }
        if (vanilla.getClass() == Block.class || vanilla instanceof WeatheringCopperFullBlock) {
            return new Block(props);
        }
        throw new IllegalStateException("覆われたブロックを作れない種類: minecraft:" + id + " (" + vanilla.getClass().getName() + ")");
    }

    /** 階段の元のブロック(オークの階段ならオークの板材)。バニラのフィールドは外から見えないので、フィールド名で読む。 */
    private static BlockState baseOf(StairBlock stairs) {
        try {
            BlockState base = ObfuscationReflectionHelper.getPrivateValue(StairBlock.class, stairs, "baseState");
            if (base != null) {
                return base;
            }
        } catch (RuntimeException e) {
            OvergrownVegetation.LOGGER.warn("階段の元のブロックを読めなかった({})。石として扱う。", stairs, e);
        }
        return Blocks.STONE.defaultBlockState();
    }

    /** ドア・トラップドア・感圧板・ボタンの音の組。銅は錆び具合にかかわらず銅。 */
    private static BlockSetType setType(String id, String suffix) {
        String base = id.substring(0, id.length() - suffix.length())
                .replaceFirst("^(exposed|weathered|oxidized)_copper$", "copper");
        return BlockSetType.values().filter(type -> type.name().equals(base)).findFirst()
                .orElseThrow(() -> new IllegalStateException("音の組が無い: " + id));
    }

    private static WoodType woodType(String id, String suffix) {
        String base = id.substring(0, id.length() - suffix.length());
        return WoodType.values().filter(type -> type.name().equals(base)).findFirst()
                .orElseThrow(() -> new IllegalStateException("木の種類が無い: " + id));
    }

    // 燃えやすさは purify.VanillaFire が決める(2026-10-09 朝、ユーザー指定「覆われたブロックは燃え切りにくく、延焼もしにくい」。
    // 元のブロックの値を写すのはやめた。表を書く所が 2 つあると、後に走った方が勝つ)。
}
