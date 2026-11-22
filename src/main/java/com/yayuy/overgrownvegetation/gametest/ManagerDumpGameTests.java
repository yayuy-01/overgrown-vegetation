package com.yayuy.overgrownvegetation.gametest;

import com.google.gson.GsonBuilder;
import com.mojang.serialization.JsonOps;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.ItemTable;
import com.yayuy.overgrownvegetation.registry.ModCreativeTabs;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 新しい mod マネージャー({@code tools/mod-manager2})のための GameTest(2026-11-13)。
 *
 * <ul>
 *     <li>{@link #dumpForManager} —— <b>今ゲームが組み立てた中身</b>を {@code tools/mod-manager2/dump/game.json} へ書き出す。
 *     マネージャーはブラウザの中で動くので、Java で決まること(クリエイティブタブに何が Java のどの順で出るか・
 *     {@code mobs.json} に書いていない欄がコードでいくつになっているか)を自分では知りようがない。
 *     アイテムや Mob を足したら {@code gradlew.bat runGameTestServer} を流し直す。設計データには触らない
 *     ({@code tools/} が無い所では何も書かない)。</li>
 *     <li>{@link #itemsJsonOrdersPlantsTab} —— {@code items.json} に書いた並びがタブに効くか
 *     (書いた物が書いた順で先、書いていない物は元の順で後ろ)。</li>
 * </ul>
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class ManagerDumpGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(ManagerDumpGameTests.class);
        }

        private Registration() {
        }
    }

    /** {@code mobs.json} の鍵 → 属性。 */
    private static final Map<String, Holder<Attribute>> ATTRIBUTES = new LinkedHashMap<>();

    static {
        ATTRIBUTES.put("maxHealth", Attributes.MAX_HEALTH);
        ATTRIBUTES.put("movementSpeed", Attributes.MOVEMENT_SPEED);
        ATTRIBUTES.put("attackDamage", Attributes.ATTACK_DAMAGE);
        ATTRIBUTES.put("attackKnockback", Attributes.ATTACK_KNOCKBACK);
        ATTRIBUTES.put("armor", Attributes.ARMOR);
        ATTRIBUTES.put("armorToughness", Attributes.ARMOR_TOUGHNESS);
        ATTRIBUTES.put("knockbackResistance", Attributes.KNOCKBACK_RESISTANCE);
        ATTRIBUTES.put("followRange", Attributes.FOLLOW_RANGE);
        ATTRIBUTES.put("stepHeight", Attributes.STEP_HEIGHT);
        ATTRIBUTES.put("flyingSpeed", Attributes.FLYING_SPEED);
        ATTRIBUTES.put("scale", Attributes.SCALE);
    }

    @GameTest(template = "gametest_empty7", timeoutTicks = 100)
    public static void dumpForManager(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("_comment", "ゲームから書き出した今の中身(ManagerDumpGameTests.dumpForManager)。手で直さない。"
                + "tabs = クリエイティブタブに出る順(items.json を当てる前の Java の順)、items = この mod のアイテム、"
                + "mobs = コードと mobs.json(書き出したゲームフォルダの写し)を合わせた値、recipes・loot = ゲームが読み込んだ作り方とドロップ。");

        Map<String, List<String>> tabs = new LinkedHashMap<>();
        // 並びは items.json を当てない Java の順で書き出す。GameTest はセッションごとに別のゲームフォルダで走り、
        // そのフォルダの古い items.json に左右されると、書き出すたびに順が変わる(マネージャーは items.json の順を自分で重ねる)。
        try {
            ItemTable.setForTests(ItemTable.fromJson(null));
            tabs.put(ItemTable.PLANTS, order(ModCreativeTabs.PLANTS.get(), params(level)));
        } finally {
            ItemTable.setForTests(null);
        }
        out.put("tabs", tabs);

        Map<String, Object> items = new TreeMap<>();
        for (Item item : BuiltInRegistries.ITEM) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            if (!OvergrownVegetation.MODID.equals(id.getNamespace())) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("block", item instanceof BlockItem);
            m.put("stack", item.getDefaultMaxStackSize());   // 作り方の完成品の個数はこれを超えるとゲームが読まない
            items.put(id.getPath(), m);
        }
        out.put("items", items);

        Map<String, Object> mobs = new TreeMap<>();
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
            if (!OvergrownVegetation.MODID.equals(id.getNamespace()) || !DefaultAttributes.hasSupplier(type)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            AttributeSupplier supplier = DefaultAttributes.getSupplier((EntityType<? extends LivingEntity>) type);
            Map<String, Object> m = new LinkedHashMap<>();
            for (Map.Entry<String, Holder<Attribute>> e : ATTRIBUTES.entrySet()) {
                if (supplier.hasAttribute(e.getValue())) {
                    m.put(e.getKey(), round(supplier.getBaseValue(e.getValue())));
                }
            }
            m.put("width", round(type.getDimensions().width()));
            m.put("height", round(type.getDimensions().height()));
            mobs.put(id.getPath(), m);
        }
        out.put("mobs", mobs);

        // ゲームが実際に読み込んだ作り方とドロップ(マネージャーの保存がゲームに効いたかを見比べる用)
        Map<String, Object> recipes = new TreeMap<>();
        for (RecipeHolder<?> holder : level.getServer().getRecipeManager().getRecipes()) {
            if (!OvergrownVegetation.MODID.equals(holder.id().getNamespace())) {
                continue;
            }
            ItemStack result = holder.value().getResultItem(level.registryAccess());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", String.valueOf(BuiltInRegistries.RECIPE_TYPE.getKey(holder.value().getType())));
            m.put("result", BuiltInRegistries.ITEM.getKey(result.getItem()).toString());
            m.put("count", result.getCount());
            recipes.put(holder.id().getPath(), m);
        }
        out.put("recipes", recipes);

        Map<String, Object> loot = new TreeMap<>();
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
            if (!OvergrownVegetation.MODID.equals(id.getNamespace()) || !DefaultAttributes.hasSupplier(type)) {
                continue;
            }
            LootTable table = level.getServer().reloadableRegistries().getLootTable(type.getDefaultLootTable());
            if (table == LootTable.EMPTY) {
                continue;
            }
            LootTable.DIRECT_CODEC.encodeStart(level.registryAccess().createSerializationContext(JsonOps.INSTANCE), table)
                    .result().ifPresent(json -> loot.put(id.getPath(), json));
        }
        out.put("loot", loot);

        if (tabs.get(ItemTable.PLANTS).isEmpty() || mobs.isEmpty()) {
            helper.fail("書き出す中身が空でした(登録が済んでいない?)");
            return;
        }
        Path dir = managerDir();
        if (dir != null) {
            try {
                Files.createDirectories(dir.resolve("dump"));
                Files.writeString(dir.resolve("dump/game.json"),
                        new GsonBuilder().setPrettyPrinting().create().toJson(out) + "\n", StandardCharsets.UTF_8);
                OvergrownVegetation.LOGGER.info("mod マネージャー用の書き出し: {}(アイテム {}・Mob {})",
                        dir.resolve("dump/game.json"), items.size(), mobs.size());
            } catch (IOException e) {
                helper.fail("dump/game.json を書けませんでした: " + e);
                return;
            }
        }
        helper.succeed();
    }

    @GameTest(template = "gametest_empty7", timeoutTicks = 100)
    public static void itemsJsonOrdersPlantsTab(GameTestHelper helper) {
        CreativeModeTab tab = ModCreativeTabs.PLANTS.get();
        CreativeModeTab.ItemDisplayParameters params = params(helper.getLevel());
        try {
            ItemTable.setForTests(ItemTable.fromJson(null));
            List<String> base = order(tab, params);
            if (base.size() < 3) {
                helper.fail("植物のタブの中身が少なすぎる: " + base.size());
                return;
            }
            String last = base.get(base.size() - 1);
            String second = base.get(1);
            ItemTable.Json json = new ItemTable.Json();
            json.tabs = Map.of(ItemTable.PLANTS, List.of(last, "no_such_item_in_the_mod", second));
            ItemTable.setForTests(ItemTable.fromJson(json));
            List<String> got = order(tab, params);

            List<String> want = new ArrayList<>(List.of(last, second));
            for (String id : base) {
                if (!want.contains(id)) {
                    want.add(id);
                }
            }
            if (!got.equals(want)) {
                helper.fail("items.json の並びがタブに効いていない。先頭 4 つ: " + got.subList(0, 4) + " / 期待: " + want.subList(0, 4)
                        + "(数 " + got.size() + " / " + want.size() + ")");
                return;
            }
        } finally {
            ItemTable.setForTests(null);
        }
        helper.succeed();
    }

    private static CreativeModeTab.ItemDisplayParameters params(ServerLevel level) {
        return new CreativeModeTab.ItemDisplayParameters(level.enabledFeatures(), true, level.registryAccess());
    }

    /** {@code tab} に出るアイテムを、出る順のまま id の一覧にする(この mod の物は名前空間を省く)。 */
    private static List<String> order(CreativeModeTab tab, CreativeModeTab.ItemDisplayParameters params) {
        tab.buildContents(params);
        Set<String> ids = new LinkedHashSet<>();
        for (ItemStack stack : tab.getDisplayItems()) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            ids.add(OvergrownVegetation.MODID.equals(id.getNamespace()) ? id.getPath() : id.toString());
        }
        return new ArrayList<>(ids);
    }

    private static double round(double v) {
        return Math.round(v * 10000.0D) / 10000.0D;
    }

    /** {@code tools/mod-manager2}。GameTest の作業フォルダ(run/ など)から親をたどって探す。無ければ null。 */
    private static Path managerDir() {
        Path root = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && root != null; i++) {
            Path dir = root.resolve("tools/mod-manager2");
            if (Files.isDirectory(dir) && Files.isDirectory(root.resolve("src/main/resources"))) {
                return dir;
            }
            root = root.getParent();
        }
        return null;
    }

    private ManagerDumpGameTests() {
    }
}
