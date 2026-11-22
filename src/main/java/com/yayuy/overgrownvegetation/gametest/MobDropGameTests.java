package com.yayuy.overgrownvegetation.gametest;

import com.mojang.authlib.GameProfile;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.gear.ModMobDrops;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.registries.datamaps.builtin.NeoForgeDataMaps;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Mob ごとのドロップ(2026-11-17 夜、決定 24): 28 種のルートテーブルが種の素材を落とす(プレイヤーが倒したときだけ)・ペディキュラは殻を残さない・
 * 新しい素材にはどれも使い道がある(10-03 の反省)・材料に「または」を足したレシピが読み込めている。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class MobDropGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(MobDropGameTests.class);
        }

        private Registration() {
        }
    }

    private MobDropGameTests() {
    }

    private static final String TEMPLATE = "gametest_empty7";
    private static final String BATCH = "mob_drops";
    private static final int ROLLS = 300;

    /** 種 → 種の素材(ペディキュラ・萌芽体は種の素材なし)。 */
    private static Map<String, String> speciesDrops() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("arthropleura", "arthropleura_plate");
        m.put("giant_snail", "snail_slime");
        m.put("protophasma", "phasmid_meat");
        m.put("stenodictya_lobata", "wing_membrane");
        m.put("stenodictya_nymph", "wing_membrane");
        m.put("meganeura", "meganeura_wing");
        m.put("pulmonoscorpius", "venom_gland");
        m.put("phalangiotarbus", "silk_gland");
        m.put("hylonomus", "small_meat");
        m.put("petrolacosaurus", "scaled_hide");
        m.put("ianthasaurus", "sail_membrane");
        m.put("coelurosauravus", "light_rib");
        m.put("arbora", "scythe_shard");
        m.put("gregaria", "cone_scale");
        m.put("vesicaria", "heat_sac");
        m.put("strobila", "spore_cone");
        m.put("velophyll", "thermal_membrane");
        m.put("seminaria", "colony_seed");
        m.put("falcimnes", "scythe_blade");
        m.put("venamnes", "glowing_tendon");
        m.put("saltimnes", "hydraulic_pulvinus");
        m.put("clipeimnes", "shield_scale");
        m.put("pilimnes", "hydraulic_spine");
        m.put("acumnes", "glowing_spine_tip");
        m.put("phyllosens", "light_core");
        m.put("gestatrix", "egg_sac");
        return m;
    }

    /** 研究の標本・後の段 3 の材料として置くだけの物(使い道の確かめから外す)。 */
    private static final Set<String> SPECIMEN_ONLY = Set.of("hydraulic_pulvinus", "glowing_spine_tip", "light_core", "egg_sac");
    /** 2026-11-21 に段 1〜3 の装備を消したので、作り直すグレードの材料になるまで使い道が無い物(ユーザー了承「mob ドロップは残していて ok」)。 */
    private static final Set<String> AWAITING_GEAR_REMAKE = Set.of("arthropleura_plate", "cone_scale", "glowing_tendon", "light_rib", "scythe_blade",
            "scythe_shard", "spore_cone", "shield_scale", "heat_sac", "thermal_membrane");

    private static Item item(String id) {
        return BuiltInRegistries.ITEM.get(OvergrownVegetation.id(id));
    }

    /** {@code entity} を倒したときのドロップを {@code rolls} 回引いて、全部まとめる。{@code killer} が null なら誰も倒していない扱い。 */
    private static List<ItemStack> roll(ServerLevel level, LivingEntity entity, FakePlayer killer, int rolls) {
        ResourceKey<LootTable> key = entity.getLootTable();
        LootTable table = level.getServer().reloadableRegistries().getLootTable(key);
        DamageSource src = killer != null ? level.damageSources().playerAttack(killer) : level.damageSources().generic();
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < rolls; i++) {
            LootParams.Builder b = new LootParams.Builder(level)
                    .withParameter(LootContextParams.THIS_ENTITY, entity)
                    .withParameter(LootContextParams.ORIGIN, entity.position())
                    .withParameter(LootContextParams.DAMAGE_SOURCE, src);
            if (killer != null) {
                b.withParameter(LootContextParams.LAST_DAMAGE_PLAYER, killer)
                        .withParameter(LootContextParams.ATTACKING_ENTITY, killer)
                        .withParameter(LootContextParams.DIRECT_ATTACKING_ENTITY, killer);
            }
            out.addAll(table.getRandomItems(b.create(LootContextParamSets.ENTITY)));
        }
        return out;
    }

    private static LivingEntity create(ServerLevel level, String id) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(OvergrownVegetation.id(id));
        Entity e = type.create(level);
        return e instanceof LivingEntity l ? l : null;
    }

    /** 28 種: プレイヤーが倒すと種の素材が出る・誰も倒していなければ出ない。ペディキュラは繊維・胞子腺だけ。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 40)
    public static void everySpeciesDropsItsMaterial(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FakePlayer player = FakePlayerFactory.get(level, new GameProfile(UUID.nameUUIDFromBytes("ov_drop_hunter".getBytes(StandardCharsets.UTF_8)), "ov_drop_hunter"));
        List<String> bad = new ArrayList<>();
        for (Map.Entry<String, String> e : speciesDrops().entrySet()) {
            LivingEntity mob = create(level, e.getKey());
            if (mob == null) {
                bad.add(e.getKey() + ": 作れない");
                continue;
            }
            Item want = item(e.getValue());
            boolean byPlayer = roll(level, mob, player, ROLLS).stream().anyMatch(s -> s.is(want));
            boolean noKiller = roll(level, mob, null, ROLLS).stream().anyMatch(s -> s.is(want));
            if (!byPlayer) bad.add(e.getKey() + ": プレイヤーが倒しても " + e.getValue() + " が出ない");
            if (noKiller) bad.add(e.getKey() + ": 誰も倒していないのに " + e.getValue() + " が出た");
            mob.discard();
        }
        LivingEntity pedicula = create(level, "pedicula");
        if (pedicula != null) {
            for (ItemStack s : roll(level, pedicula, player, ROLLS)) {
                if (s.isEmpty()) continue; // 繊維の数が 0 の回は空が返る
                String path = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
                if (!path.equals("plant_fiber") && !path.equals("spore_gland_tissue")) {
                    bad.add("pedicula: 共通でない物 " + path);
                    break;
                }
            }
        }
        if (!bad.isEmpty()) {
            helper.fail(String.join(" / ", bad));
            return;
        }
        helper.succeed();
    }

    /** 新しい素材はどれも使い道がある: レシピの材料・食べ物・燃料・堆肥のどれか(段 3 の標本だけの物を除く)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void everyDropHasAUse(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<Ingredient> ingredients = new ArrayList<>();
        for (RecipeHolder<?> r : level.getRecipeManager().getRecipes()) {
            ingredients.addAll(r.value().getIngredients());
        }
        List<String> unused = new ArrayList<>();
        for (var d : ModMobDrops.all()) {
            String id = d.getId().getPath();
            if (SPECIMEN_ONLY.contains(id) || AWAITING_GEAR_REMAKE.contains(id)) continue;
            ItemStack stack = new ItemStack(d.get());
            boolean use = stack.has(net.minecraft.core.component.DataComponents.FOOD)
                    || d.get().builtInRegistryHolder().getData(NeoForgeDataMaps.FURNACE_FUELS) != null
                    || d.get().builtInRegistryHolder().getData(NeoForgeDataMaps.COMPOSTABLES) != null
                    || ingredients.stream().anyMatch(i -> i.test(stack));
            if (!use) unused.add(id);
        }
        if (!unused.isEmpty()) {
            helper.fail("使い道の無い素材: " + unused);
            return;
        }
        helper.succeed();
    }

    /** 材料に「または」を足した・新しく足したレシピが全部読み込めている(JSON の書き損じで黙って消えない)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void editedRecipesLoad(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        String[] ids = {
                "sleeping_bag", "arrow_from_meganeura_wing", "arrow_from_hydraulic_spine", "string_from_silk_gland", "slime_ball_from_snail_slime",
                "glass_pane_from_wing_membrane", "poison_arrow_from_venom_gland", "cooked_phasmid_meat_from_smelting", "cooked_small_meat_from_campfire_cooking"};
        List<String> missing = new ArrayList<>();
        for (String id : ids) {
            if (level.getRecipeManager().byKey(OvergrownVegetation.id(id)).isEmpty()) missing.add(id);
        }
        if (!missing.isEmpty()) {
            helper.fail("読み込めていないレシピ: " + missing);
            return;
        }
        helper.succeed();
    }
}
