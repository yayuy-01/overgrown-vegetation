package com.yayuy.overgrownvegetation;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModArmorMaterials;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import com.yayuy.overgrownvegetation.registry.ModBlockEntities;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.ModCreativeTabs;
import com.yayuy.overgrownvegetation.registry.ModDataComponents;
import com.yayuy.overgrownvegetation.registry.ModEntities;
import com.yayuy.overgrownvegetation.registry.ModFeatures;
import com.yayuy.overgrownvegetation.registry.ModItems;
import com.yayuy.overgrownvegetation.registry.ModMenus;
import com.yayuy.overgrownvegetation.registry.ModMobEffects;
import com.yayuy.overgrownvegetation.registry.ModParticles;
import com.yayuy.overgrownvegetation.registry.ModRecipes;
import com.yayuy.overgrownvegetation.registry.ModSounds;
import net.minecraft.world.item.crafting.ShapedRecipePattern;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OvergrownVegetation — 寄生植物が世界を侵食していく MOD。
 *
 * <p>第一段階の内容:
 * <ul>
 *     <li>{@code blight_fern} — 巨大シダの寄生植物 Mob。侵食の起点になる。</li>
 *     <li>周囲を球状にスキャンして、露出面のあるブロック → 内部のブロックの順に汚染ブロックへ置き換える。</li>
 *     <li>元ブロックに対応する汚染ブロックが無い場合は汎用の「汚染されたブロック」に置き換える。</li>
 * </ul>
 */
@Mod(OvergrownVegetation.MODID)
public class OvergrownVegetation {

    public static final String MODID = "overgrown_vegetation";
    public static final Logger LOGGER = LoggerFactory.getLogger("OvergrownVegetation");

    public OvergrownVegetation(IEventBus modBus, ModContainer container) {
        // 設計データ(Mob ステータス・捕食表・フェーズ・デバフ)を最初に読む。
        // エンティティ登録・EntityAttributeCreationEvent・MobEffect 生成より前に走る必要がある。
        DesignData.load();

        // 研究台の 5×5 作業台レシピ({@link com.yayuy.overgrownvegetation.crafting.SizedCraftingRecipe})を
        // ロードできるよう、定形レシピパターンの上限を 5×5 に広げる(バニラの作業台は 3×3 のまま)。
        ShapedRecipePattern.setCraftingSize(5, 5);

        com.yayuy.overgrownvegetation.registry.ModG1.init(); // G1 段階 0 の死体・Mob・卵(2026-11-08。下の DeferredRegister に足す)
        com.yayuy.overgrownvegetation.registry.ModStilt.init(); // 竹馬型の Mob(11-14 から内部用 phyllosens_stilt)
        com.yayuy.overgrownvegetation.registry.ModPhyllosens.init(); // 監視塔フィロセンスの Mob・卵・視線の印(2026-11-14)
        com.yayuy.overgrownvegetation.registry.ModRunners.init(); // ② 大型の地上を走る型(ヴェナムネス)の Mob・卵(2026-11-14)
        com.yayuy.overgrownvegetation.registry.ModLeapers.init(); // 跳ぶ型(サルティムネス)の Mob・卵(2026-11-14)
        com.yayuy.overgrownvegetation.registry.ModCrawlers.init(); // 小さな地這いの虫の型(グレガリア)の Mob・卵(2026-11-15)
        com.yayuy.overgrownvegetation.registry.ModFlyers.init(); // 飛ぶ型(ヴェロフィル)の Mob・卵(2026-11-15)
        com.yayuy.overgrownvegetation.registry.ModG23Flyers.init(); // G2・G3 の飛ぶ種(メガネウラ)の Mob・卵(2026-11-16)
        com.yayuy.overgrownvegetation.registry.ModLizards.init(); // トカゲ型(ピリムネス)の Mob・卵(2026-11-15)
        com.yayuy.overgrownvegetation.registry.ModLey.init(); // 地脈の最小限: 花・汲み上げ器・柱・電池・線(2026-11-13 サバイバルの背骨 段 2b)
        com.yayuy.overgrownvegetation.registry.ModVent.init(); // 換気の機械とフィルター(2026-11-14 サバイバルの背骨 段 2c)
        com.yayuy.overgrownvegetation.registry.ModPushBack.init(); // 押し返す用の道具と方法(2026-11-18 夜)
        com.yayuy.overgrownvegetation.registry.ModDecon.init(); // 除染: 変換表のレシピ・地脈の洗い場(2026-11-14 サバイバルの背骨 段 3a)
        com.yayuy.overgrownvegetation.registry.ModExpedition.init(); // 遠征の見返りと手当て: 茸・樹皮片・菌糸のフィルター・寝袋(2026-11-16 サバイバルの背骨 段 4)
        com.yayuy.overgrownvegetation.registry.ModCreepingWall.init(); // 蠢く壁の成れの果て・ダメージの型(2026-11-16 試作)
        com.yayuy.overgrownvegetation.gear.ModGear.init(); // 強くなる道 段 1・段 2: 繊維の草・遠征服・山刀・胞子弾の弓・菌集鉱・札鎧・両手剣・貫きの弩(2026-11-17)
        com.yayuy.overgrownvegetation.registry.ModResearch.init(); // 研究台と研究ツリー・器具(2026-11-17 強くなる道 決定 20・22・23)
        ModBlocks.register(modBus);
        ModBlockEntities.register(modBus);
        ModArmorMaterials.register(modBus); // 2026-11-04 防護マスクの素材だけ
        ModItems.register(modBus);
        com.yayuy.overgrownvegetation.organ.OrganBlocks.register(modBus); // 森の器官の専用ブロック(2026-11-17)
        ModEntities.register(modBus);
        ModFeatures.register(modBus);
        ModCreativeTabs.register(modBus);
        ModMobEffects.register(modBus);
        ModMenus.register(modBus);
        ModRecipes.register(modBus);
        ModSounds.register(modBus);
        ModParticles.register(modBus);
        ModAttachments.register(modBus);
        ModDataComponents.register(modBus);

        container.registerConfig(ModConfig.Type.COMMON, OvergrownVegetationConfig.SPEC);
        // この PC だけの見た目の設定(2026-10-22、画面の色の調子。画面は client/difficulty/VisualSettingsScreen)。
        container.registerConfig(ModConfig.Type.CLIENT, com.yayuy.overgrownvegetation.config.OvergrownClientConfig.SPEC);

        LOGGER.info("OvergrownVegetation loaded.");
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }
}
