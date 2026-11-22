package com.yayuy.overgrownvegetation.gear;

import com.yayuy.overgrownvegetation.registry.ModItems;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import com.yayuy.overgrownvegetation.port.registries.DeferredItem;

import java.util.List;

/**
 * <b>Mob ごとのドロップ</b>(2026-11-17 夜。強くなる道の決定 24「種ごとに 1 つ + グループ共通」)。名前・働き・率はすべて仮。
 * 正本は Vault「Mob のドロップ(11-17)」。落とす率・数は各 Mob のルートテーブル {@code loot_table/entities/<id>.json}(mod マネージャー 2 のドロップの画面で直す)。
 *
 * <p>使い道の無い素材を増やさない(10-03 の反省): どの素材にも 1 つ以上の使い道を付けた。
 * <ul>
 *   <li>環境生物(生活の素材): 食べ物(焼いて食べる)・バニラの物を作る(矢・糸・毒の矢・スライムボール・板ガラス)・段 1 の物の材料の「または」。</li>
 *   <li>G2・G3(段の素材 + 研究の標本): 段 1・2 の物の材料の「または」・かまどの燃料(熱の器官)・研究の標本。</li>
 *   <li>G4・段 3 向けの物(葉枕・棘の先・花芯・卵嚢): 研究の標本と、後の段 3 の作り方の材料として置くだけ。</li>
 * </ul>
 */
public final class ModMobDrops {

    private ModMobDrops() {
    }

    public static void init() {
    }

    private static DeferredItem<Item> simple(String id) {
        return ModItems.ITEMS.registerSimpleItem(id, new Item.Properties());
    }

    private static DeferredItem<Item> food(String id, int nutrition, float saturation) {
        return ModItems.ITEMS.registerSimpleItem(id, new Item.Properties().food(new FoodProperties.Builder()
                .nutrition(nutrition).saturationMod(saturation).build()));
    }

    // ---- 獲物の環境生物 ----
    /** アースロプレウラ: 体節の甲殻板(軽くて丈夫。遠征服の当て物)。 */
    public static final DeferredItem<Item> ARTHROPLEURA_PLATE = simple("arthropleura_plate");
    /** 陸生カタツムリ: 粘液(接着。スライムボールになる)。 */
    public static final DeferredItem<Item> SNAIL_SLIME = simple("snail_slime");
    /** プロトファスマ: ナナフシの身(生)と焼いた身。 */
    public static final DeferredItem<Item> PHASMID_MEAT = food("phasmid_meat", 2, 0.1F);
    public static final DeferredItem<Item> COOKED_PHASMID_MEAT = food("cooked_phasmid_meat", 5, 0.6F);
    /** ステノディクティア: 薄い翅膜(窓の代わり = 板ガラス)。 */
    public static final DeferredItem<Item> WING_MEMBRANE = simple("wing_membrane");

    // ---- 狩る側の環境生物 ----
    /** メガネウラ: 大きな翅(鳥のいない世界の矢羽根)。 */
    public static final DeferredItem<Item> MEGANEURA_WING = simple("meganeura_wing");
    /** プルモノスコルピウス: 尾の毒腺(毒の矢)。 */
    public static final DeferredItem<Item> VENOM_GLAND = simple("venom_gland");
    /** ファランギオタルブス: 糸腺(糸)。 */
    public static final DeferredItem<Item> SILK_GLAND = simple("silk_gland");
    /** ヒロノムス: 小さな肉(生)と焼いた肉。 */
    public static final DeferredItem<Item> SMALL_MEAT = food("small_meat", 2, 0.2F);
    public static final DeferredItem<Item> COOKED_SMALL_MEAT = food("cooked_small_meat", 5, 0.7F);
    /** ペトロラコサウルス: 鱗の皮(革の代わり)。 */
    public static final DeferredItem<Item> SCALED_HIDE = simple("scaled_hide");
    /** イアンタサウルス: 帆の膜(寝袋の材料)。 */
    public static final DeferredItem<Item> SAIL_MEMBRANE = simple("sail_membrane");
    /** コエルロサウラウス: 細く軽い肋の骨(弓の材料)。 */
    public static final DeferredItem<Item> LIGHT_RIB = simple("light_rib");

    // ---- G2(段 1・2 + 標本) ----
    /** アルボラ: 鎌の欠片(山刀の刃)。 */
    public static final DeferredItem<Item> SCYTHE_SHARD = simple("scythe_shard");
    /** グレガリア: 球果の鱗(遠征服の当て物)。 */
    public static final DeferredItem<Item> CONE_SCALE = simple("cone_scale");
    /** ヴェシカリア: 熱で膨らむ袋(熱の器官の標本・燃料)。 */
    public static final DeferredItem<Item> HEAT_SAC = simple("heat_sac");
    /** ストロビラ: 胞子の球果(燻し矢の材料)。 */
    public static final DeferredItem<Item> SPORE_CONE = simple("spore_cone");
    /** ヴェロフィル: 熱気をとらえる翼膜(熱の器官の標本・弩の部品・燃料)。 */
    public static final DeferredItem<Item> THERMAL_MEMBRANE = simple("thermal_membrane");
    /** セミナリア: 飛び地を作る種(押し返しの研究の標本)。 */
    public static final DeferredItem<Item> COLONY_SEED = simple("colony_seed");

    // ---- G3(段 2・3 + 標本) ----
    /** ファルキムネス: 大鎌の刃(大剣の刃)。 */
    public static final DeferredItem<Item> SCYTHE_BLADE = simple("scythe_blade");
    /** ヴェナムネス: 光る腱(弩の弦)。 */
    public static final DeferredItem<Item> GLOWING_TENDON = simple("glowing_tendon");
    /** サルティムネス: 水圧の葉枕(段 3 装甲の「落ちても痛まない」の標本)。 */
    public static final DeferredItem<Item> HYDRAULIC_PULVINUS = simple("hydraulic_pulvinus");
    /** クリペイムネス: 盾鱗(札鎧の当て物)。 */
    public static final DeferredItem<Item> SHIELD_SCALE = simple("shield_scale");
    /** ピリムネス: 水圧の棘(弩の太い矢 = 矢になる)。 */
    public static final DeferredItem<Item> HYDRAULIC_SPINE = simple("hydraulic_spine");
    /** アクムネス: 光る棘の先(段 3 地脈の杖の弾の標本)。 */
    public static final DeferredItem<Item> GLOWING_SPINE_TIP = simple("glowing_spine_tip");

    // ---- G4(段 3 + 標本) ----
    /** フィロセンス: 光を集める花芯。 */
    public static final DeferredItem<Item> LIGHT_CORE = simple("light_core");
    /** ゲスタトリクス: 卵嚢。 */
    public static final DeferredItem<Item> EGG_SAC = simple("egg_sac");

    public static List<DeferredItem<Item>> all() {
        return List.of(ARTHROPLEURA_PLATE, SNAIL_SLIME, PHASMID_MEAT, COOKED_PHASMID_MEAT, WING_MEMBRANE,
                MEGANEURA_WING, VENOM_GLAND, SILK_GLAND, SMALL_MEAT, COOKED_SMALL_MEAT, SCALED_HIDE, SAIL_MEMBRANE, LIGHT_RIB,
                SCYTHE_SHARD, CONE_SCALE, HEAT_SAC, SPORE_CONE, THERMAL_MEMBRANE, COLONY_SEED,
                SCYTHE_BLADE, GLOWING_TENDON, HYDRAULIC_PULVINUS, SHIELD_SCALE, HYDRAULIC_SPINE, GLOWING_SPINE_TIP,
                LIGHT_CORE, EGG_SAC);
    }

    /** 人類側のタブに並べる({@link ModGear#acceptItems} から)。 */
    public static void acceptItems(CreativeModeTab.Output output) {
        for (DeferredItem<Item> i : all()) output.accept(i.get());
    }
}
