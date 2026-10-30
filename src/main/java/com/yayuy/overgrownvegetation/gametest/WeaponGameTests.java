package com.yayuy.overgrownvegetation.gametest;

import com.mojang.authlib.GameProfile;
import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.GearTable;
import com.yayuy.overgrownvegetation.data.ResearchTable;
import com.yayuy.overgrownvegetation.item.ZweihanderItem;
import com.yayuy.overgrownvegetation.registry.ModItems;
import com.yayuy.overgrownvegetation.registry.ModTiers;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.ItemAbilities;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 武器(2026-10-09〜、ユーザー指定「G6 の Mob ができるまでは武器を作る」)。今はツヴァイヘンダー 3 本とエストック 3 本(10-10)。
 * <ul>
 *     <li>手に持ったときの攻撃力・攻撃速度・耐久は gear.json の {@code perTool.zweihander} とセットの値どおり、リーチと薙ぎ払いが足され、
 *     剣のタグ・過繁茂特効のタグに入り、薙ぎ払える。サンライト合金だけ燃えない。修理素材はそれぞれの素材({@link #zweihanderStatsFollowGearTable})。</li>
 *     <li>6 本ともレシピがあり、クロロフィライト・合金の分は同梱の研究ツリーのどれかのノードで開く({@link #weaponRecipesExist})。</li>
 *     <li>エストック: 数値・タグ(両手持ちではない)・薙ぎ払えない({@link #estocStatsFollowGearTable})、防具 20 のハスクに防具の半分しか効かず、隣には通らない
 *     ({@link #estocPiercesArmorAndDoesNotSweep})。</li>
 *     <li>偽のプレイヤーが振ると、隣の相手へ「1 + 薙ぎ払いの割合 × 攻撃力」が通り(バニラの剣は 1)、素手のリーチ 3 より遠い相手も巻き込む
 *     ({@link #zweihanderSweepsHarderAndFarther})。</li>
 *     <li>両手持ち: 利き手に持つと左手の雪玉・土・盾が使えない(ふつうの剣なら使える。{@link #twoHandedBlocksOffhand})。</li>
 * </ul>
 * 期待値は設計値(gear.json・{@link ZweihanderItem} の定数)から引く(mod マネージャーで数値を変えても落ちないように)。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class WeaponGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(WeaponGameTests.class);
        }

        private Registration() {
        }
    }

    private static final String TEMPLATE = "gametest_empty7";
    private static final String BATCH = "weapons";
    private static final double EPS = 1.0E-4D;

    private record Weapon(Item item, ModTiers tier, Item material, boolean fireResistant) {
    }

    private static List<Weapon> zweihanders() {
        return List.of(
                new Weapon(ModItems.CHLOROPHYLLITE_ZWEIHANDER.get(), ModTiers.CHLOROPHYLLITE, ModItems.CHLOROPHYLLITE.get(), false),
                new Weapon(ModItems.CHLOROPHYLLITE_ALLOY_ZWEIHANDER.get(), ModTiers.CHLOROPHYLLITE_ALLOY, ModItems.CHLOROPHYLLITE_ALLOY.get(), false),
                new Weapon(ModItems.SUNLIGHT_ALLOY_ZWEIHANDER.get(), ModTiers.SUNLIGHT_ALLOY, ModItems.SUNLIGHT_ALLOY.get(), true));
    }

    private static List<Weapon> estocs() {
        return List.of(
                new Weapon(ModItems.CHLOROPHYLLITE_ESTOC.get(), ModTiers.CHLOROPHYLLITE, ModItems.CHLOROPHYLLITE.get(), false),
                new Weapon(ModItems.CHLOROPHYLLITE_ALLOY_ESTOC.get(), ModTiers.CHLOROPHYLLITE_ALLOY, ModItems.CHLOROPHYLLITE_ALLOY.get(), false),
                new Weapon(ModItems.SUNLIGHT_ALLOY_ESTOC.get(), ModTiers.SUNLIGHT_ALLOY, ModItems.SUNLIGHT_ALLOY.get(), true));
    }

    /** エストック 3 本: 手に持ったときの攻撃力・攻撃速度・リーチ・耐久・タグ(剣・過繁茂特効。両手持ちではない)・薙ぎ払えない・修理・燃えなさ。 */
    @GameTest(template = TEMPLATE, batch = BATCH)
    public static void estocStatsFollowGearTable(GameTestHelper helper) {
        for (Weapon w : estocs()) {
            String name = w.item().toString();
            helper.assertTrue(w.item() instanceof com.yayuy.overgrownvegetation.item.EstocItem, name + " が EstocItem でない");
            GearTable.ToolSpec spec = DesignData.gear().tool(w.tier().key());
            ItemStack stack = new ItemStack(w.item());
            Map<ResourceLocation, Double> mods = mainHandModifiers(stack);
            expect(helper, name, "攻撃力", mods.get(Item.BASE_ATTACK_DAMAGE_ID), spec.attackDamage("estoc") + spec.attackDamageBonus());
            expect(helper, name, "攻撃速度", mods.get(Item.BASE_ATTACK_SPEED_ID), spec.attackSpeed("estoc"));
            expect(helper, name, "リーチ", mods.get(com.yayuy.overgrownvegetation.item.EstocItem.REACH_ID),
                    com.yayuy.overgrownvegetation.item.EstocItem.REACH_BONUS);
            helper.assertTrue(stack.getMaxDamage() == spec.durability("estoc"),
                    name + " の耐久が gear.json と違う: " + stack.getMaxDamage() + " / " + spec.durability("estoc"));
            helper.assertTrue(stack.is(ItemTags.SWORDS), name + " が #minecraft:swords に無い(エンチャントが付かない)");
            helper.assertTrue(stack.is(ModTags.OVERGROWTH_BANE), name + " が過繁茂特効のタグに無い");
            helper.assertTrue(!stack.is(ModTags.TWO_HANDED), name + " が両手持ちになっている(片手で持つ武器)");
            helper.assertTrue(!stack.canPerformAction(ItemAbilities.SWORD_SWEEP), name + " が薙ぎ払える(突くだけのはず)");
            helper.assertTrue(stack.canPerformAction(ItemAbilities.SWORD_DIG), name + " で蜘蛛の巣を速く切れない(剣のできることまで外した)");
            helper.assertTrue(w.item().isValidRepairItem(stack, new ItemStack(w.material())), name + " を素材で直せない");
            helper.assertTrue(stack.has(DataComponents.FIRE_RESISTANT) == w.fireResistant(),
                    name + " の燃えなさが違う: " + stack.has(DataComponents.FIRE_RESISTANT));
        }
        helper.succeed();
    }

    /**
     * 偽のプレイヤーがクロロフィライトのエストックで、防具 20(強靭 0)のハスクを突く: 防具が減らす分の半分しか減らない(バニラの式から期待値を出す)。
     * 隣のハスクには何も通らない(薙ぎ払わない)。
     */
    @GameTest(template = TEMPLATE, batch = BATCH)
    public static void estocPiercesArmorAndDoesNotSweep(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FakePlayer player = FakePlayerFactory.get(level,
                new GameProfile(UUID.nameUUIDFromBytes("ov_estoc_test".getBytes(StandardCharsets.UTF_8)), "ov_estoc_test"));
        ItemStack estoc = new ItemStack(ModItems.CHLOROPHYLLITE_ESTOC.get());
        try {
            Vec3 feet = helper.absoluteVec(new Vec3(3.5D, 2.0D, 0.5D));
            player.moveTo(feet.x, feet.y, feet.z, 0.0F, 0.0F); // 南(+Z)向き
            player.setOnGround(true);
            player.setSpeed(0.1F); // 薙ぎ払いの条件(歩いた距離の差 < 速さ)を満たしたうえで、薙ぎ払わないことを見る
            player.setItemInHand(InteractionHand.MAIN_HAND, estoc);
            estoc.forEachModifier(EquipmentSlot.MAINHAND, (attr, mod) -> {
                AttributeInstance inst = player.getAttribute(attr);
                if (inst != null) {
                    inst.removeModifier(mod.id());
                    inst.addTransientModifier(mod);
                }
            });
            fullyCharge(player);

            Husk target = husk(helper, 3.5D, 2.5D);
            Husk side = husk(helper, 4.5D, 2.5D);
            target.getAttribute(Attributes.ARMOR).setBaseValue(20.0D);
            target.getAttribute(Attributes.ARMOR_TOUGHNESS).setBaseValue(0.0D);
            float raw = (float) player.getAttributeValue(Attributes.ATTACK_DAMAGE);
            net.minecraft.world.damagesource.DamageSource source = level.damageSources().playerAttack(player);
            float afterArmor = net.minecraft.world.damagesource.CombatRules.getDamageAfterAbsorb(target, raw, source,
                    (float) target.getArmorValue(), (float) target.getAttributeValue(Attributes.ARMOR_TOUGHNESS));
            float expected = raw - (raw - afterArmor) * (1.0F - com.yayuy.overgrownvegetation.item.EstocItem.ARMOR_PIERCE);

            player.attack(target);

            float hit = target.getMaxHealth() - target.getHealth();
            float hitSide = side.getMaxHealth() - side.getHealth();
            String got = String.format("目の前 %.3f(期待 %.3f、防具どおりなら %.3f)・隣 %.3f", hit, expected, afterArmor, hitSide);
            helper.assertTrue(Math.abs(hit - expected) < 0.05F, "防具を半分無視していない: " + got);
            helper.assertTrue(hit > afterArmor + 1.0F, "防具どおりに減っている: " + got);
            helper.assertTrue(hitSide == 0.0F, "隣の相手に通った(薙ぎ払わないはず): " + got);
            helper.succeed();
        } finally {
            estoc.forEachModifier(EquipmentSlot.MAINHAND, (attr, mod) -> {
                AttributeInstance inst = player.getAttribute(attr);
                if (inst != null) {
                    inst.removeModifier(mod.id());
                }
            });
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        }
    }

    /** 手に持ったときの属性・耐久・タグ・修理素材・燃えないか。 */
    @GameTest(template = TEMPLATE, batch = BATCH)
    public static void zweihanderStatsFollowGearTable(GameTestHelper helper) {
        for (Weapon z : zweihanders()) {
            String name = z.item().toString();
            helper.assertTrue(z.item() instanceof ZweihanderItem, name + " が ZweihanderItem でない");
            GearTable.ToolSpec spec = DesignData.gear().tool(z.tier().key());
            ItemStack stack = new ItemStack(z.item());
            Map<ResourceLocation, Double> mods = mainHandModifiers(stack);
            expect(helper, name, "攻撃力", mods.get(Item.BASE_ATTACK_DAMAGE_ID), spec.attackDamage("zweihander") + spec.attackDamageBonus());
            expect(helper, name, "攻撃速度", mods.get(Item.BASE_ATTACK_SPEED_ID), spec.attackSpeed("zweihander"));
            expect(helper, name, "リーチ", mods.get(ZweihanderItem.REACH_ID), ZweihanderItem.REACH_BONUS);
            expect(helper, name, "薙ぎ払いの割合", mods.get(ZweihanderItem.SWEEP_ID), ZweihanderItem.SWEEP_RATIO);
            helper.assertTrue(stack.getMaxDamage() == spec.durability("zweihander"),
                    name + " の耐久が gear.json と違う: " + stack.getMaxDamage() + " / " + spec.durability("zweihander"));
            helper.assertTrue(stack.is(ItemTags.SWORDS), name + " が #minecraft:swords に無い(エンチャントが付かない)");
            helper.assertTrue(stack.is(ModTags.OVERGROWTH_BANE), name + " が過繁茂特効のタグに無い");
            helper.assertTrue(stack.canPerformAction(ItemAbilities.SWORD_SWEEP), name + " が薙ぎ払えない");
            helper.assertTrue(z.item().isValidRepairItem(stack, new ItemStack(z.material())), name + " を素材で直せない");
            helper.assertTrue(stack.has(DataComponents.FIRE_RESISTANT) == z.fireResistant(),
                    name + " の燃えなさが違う: " + stack.has(DataComponents.FIRE_RESISTANT));
        }
        helper.succeed();
    }

    /** ツヴァイヘンダー・エストックの 6 本ともレシピがあり、クロロフィライト・合金の分は同梱の研究ツリーのどれかのノードで開く(どのノードかはユーザーが mod マネージャーで動かせるので問わない)。 */
    @GameTest(template = TEMPLATE, batch = BATCH)
    public static void weaponRecipesExist(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<Weapon> all = new java.util.ArrayList<>(zweihanders());
        all.addAll(estocs());
        for (Weapon z : all) {
            ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(z.item());
            Optional<RecipeHolder<?>> recipe = level.getRecipeManager().byKey(id);
            helper.assertTrue(recipe.isPresent(), id + " のレシピが無い");
            ItemStack result = recipe.get().value().getResultItem(level.registryAccess());
            helper.assertTrue(result.is(z.item()), id + " のレシピの成果物が違う: " + result);
            boolean usesMaterial = recipe.get().value().getIngredients().stream()
                    .anyMatch(ing -> ing.test(new ItemStack(z.material())));
            helper.assertTrue(usesMaterial, id + " のレシピに素材が無い");
        }
        // 研究は同梱の design/research.json で見る(実行時の run/config の写しは古いことがあり、ほかのセッションの実行で落ちた)。
        ResearchTable.Json research;
        try (java.io.InputStream in = WeaponGameTests.class.getResourceAsStream("/overgrown_vegetation/design/research.json")) {
            research = new com.google.gson.Gson().fromJson(
                    new java.io.InputStreamReader(in, StandardCharsets.UTF_8), ResearchTable.Json.class);
        } catch (Exception e) {
            throw new AssertionError("design/research.json が読めない: " + e);
        }
        for (String id : List.of("chlorophyllite_zweihander", "chlorophyllite_alloy_zweihander", "chlorophyllite_estoc", "chlorophyllite_alloy_estoc")) {
            boolean unlocked = research.nodes.values().stream().anyMatch(n -> n.unlocks != null && n.unlocks.contains(id));
            helper.assertTrue(unlocked, id + " を開く研究ノードが同梱の研究ツリーに無い(研究に載せないと最初から作れる)");
        }
        helper.succeed();
    }

    /**
     * 偽のプレイヤー(南向き)が目の前のハスクを振り抜く: 隣のハスクと、2 歩後ろのハスク(プレイヤーから 3.3。素手・剣のリーチ 3 の外)が巻き込まれ、
     * どちらにも「1 + 薙ぎ払いの割合 × 攻撃力」近くが通る。バニラの剣なら隣に 1 だけ・後ろのハスクには届かない。
     */
    @GameTest(template = TEMPLATE, batch = BATCH)
    public static void zweihanderSweepsHarderAndFarther(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FakePlayer player = FakePlayerFactory.get(level,
                new GameProfile(UUID.nameUUIDFromBytes("ov_zweihander_test".getBytes(StandardCharsets.UTF_8)), "ov_zweihander_test"));
        ItemStack sword = new ItemStack(ModItems.CHLOROPHYLLITE_ZWEIHANDER.get());
        try {
            Vec3 feet = helper.absoluteVec(new Vec3(3.5D, 2.0D, 0.5D));
            player.moveTo(feet.x, feet.y, feet.z, 0.0F, 0.0F); // 南(+Z)向き
            player.setOnGround(true);
            player.setSpeed(0.1F); // 偽プレイヤーは tick しないので歩いた距離の差 0 < 速さ、を満たすよう速さだけ入れる
            player.setItemInHand(InteractionHand.MAIN_HAND, sword);
            // 手に持った物の属性は tick の装備の見直しで付く。偽プレイヤーは tick しないので手で付ける。
            sword.forEachModifier(EquipmentSlot.MAINHAND, (attr, mod) -> {
                AttributeInstance inst = player.getAttribute(attr);
                if (inst != null) {
                    inst.removeModifier(mod.id());
                    inst.addTransientModifier(mod);
                }
            });
            fullyCharge(player);
            helper.assertTrue(Math.abs(player.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE) - (3.0D + ZweihanderItem.REACH_BONUS)) < EPS,
                    "リーチが 3 + " + ZweihanderItem.REACH_BONUS + " でない: " + player.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE));
            helper.assertTrue(player.getAttackStrengthScale(0.5F) > 0.9F, "振りが溜まっていない(薙ぎ払いの条件)");

            Husk target = husk(helper, 3.5D, 2.5D);
            Husk side = husk(helper, 4.5D, 2.5D);
            Husk behind = husk(helper, 3.5D, 3.8D);
            double behindDist = Math.sqrt(player.distanceToSqr(behind));
            helper.assertTrue(behindDist > 3.0D && behindDist < 3.0D + ZweihanderItem.REACH_BONUS,
                    "後ろのハスクの距離が試したい範囲に無い: " + behindDist);

            double attack = player.getAttributeValue(Attributes.ATTACK_DAMAGE);
            double sweep = 1.0D + player.getAttributeValue(Attributes.SWEEPING_DAMAGE_RATIO) * attack;
            player.attack(target);

            float hitTarget = target.getMaxHealth() - target.getHealth();
            float hitSide = side.getMaxHealth() - side.getHealth();
            float hitBehind = behind.getMaxHealth() - behind.getHealth();
            String got = String.format("目の前 %.2f・隣 %.2f・後ろ %.2f(攻撃力 %.2f、薙ぎ払い %.2f)", hitTarget, hitSide, hitBehind, attack, sweep);
            helper.assertTrue(hitTarget > attack * 0.8D, "目の前の相手に攻撃力が通らない: " + got);
            // ハスクの防御点 2 で 2% ほど減る。バニラの剣の薙ぎ払い(1)と取り違えないよう、期待の 8 割を下限にする。
            helper.assertTrue(hitSide > sweep * 0.8D, "隣の相手への薙ぎ払いが弱い: " + got);
            helper.assertTrue(hitBehind > sweep * 0.8D, "リーチ 3 の外の相手を巻き込まない: " + got);
            helper.succeed();
        } finally {
            sword.forEachModifier(EquipmentSlot.MAINHAND, (attr, mod) -> {
                AttributeInstance inst = player.getAttribute(attr);
                if (inst != null) {
                    inst.removeModifier(mod.id());
                }
            });
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        }
    }

    /**
     * 両手持ち(10-10 ユーザー指定「左手のアイテムを使えないようにする」): ツヴァイヘンダーを利き手に持つと、左手の雪玉は投げられず、左手の土は置けず、
     * 盾は構えられない。盾を構えたまま持ち替えると止まる。ふつうの剣なら左手の物を使える。
     */
    @GameTest(template = TEMPLATE, batch = BATCH)
    public static void twoHandedBlocksOffhand(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FakePlayer player = FakePlayerFactory.get(level,
                new GameProfile(UUID.nameUUIDFromBytes("ov_two_handed_test".getBytes(StandardCharsets.UTF_8)), "ov_two_handed_test"));
        net.minecraft.core.BlockPos floor = helper.absolutePos(new net.minecraft.core.BlockPos(3, 1, 5));
        net.minecraft.core.BlockPos above = floor.above();
        net.minecraft.world.phys.BlockHitResult hit = new net.minecraft.world.phys.BlockHitResult(
                Vec3.atCenterOf(floor).add(0.0D, 0.5D, 0.0D), net.minecraft.core.Direction.UP, floor, false);
        ItemStack zweihander = new ItemStack(ModItems.CHLOROPHYLLITE_ZWEIHANDER.get());
        ItemStack sword = new ItemStack(net.minecraft.world.item.Items.IRON_SWORD);
        try {
            for (Weapon z : zweihanders()) {
                helper.assertTrue(new ItemStack(z.item()).is(ModTags.TWO_HANDED), z.item() + " が両手持ちのタグに無い");
            }
            net.minecraft.core.BlockPos feet = helper.absolutePos(new net.minecraft.core.BlockPos(3, 2, 3));
            player.moveTo(feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D, 0.0F, 0.0F);

            // 雪玉: ツヴァイヘンダーなら投げられない、剣なら投げられる
            player.setItemInHand(InteractionHand.MAIN_HAND, zweihander.copy());
            player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(net.minecraft.world.item.Items.SNOWBALL, 4));
            player.gameMode.useItem(player, level, player.getOffhandItem(), InteractionHand.OFF_HAND);
            helper.assertTrue(player.getOffhandItem().getCount() == 4, "両手持ちなのに左手の雪玉を投げた: 残り " + player.getOffhandItem().getCount());
            player.setItemInHand(InteractionHand.MAIN_HAND, sword.copy());
            player.gameMode.useItem(player, level, player.getOffhandItem(), InteractionHand.OFF_HAND);
            helper.assertTrue(player.getOffhandItem().getCount() == 3, "剣なのに左手の雪玉を投げられない: 残り " + player.getOffhandItem().getCount());

            // 土: ツヴァイヘンダーなら置けない、剣なら置ける
            player.setItemInHand(InteractionHand.MAIN_HAND, zweihander.copy());
            player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(net.minecraft.world.item.Items.DIRT, 4));
            player.gameMode.useItemOn(player, level, player.getOffhandItem(), InteractionHand.OFF_HAND, hit);
            helper.assertTrue(level.getBlockState(above).isAir(), "両手持ちなのに左手の土を置いた: " + level.getBlockState(above));
            player.setItemInHand(InteractionHand.MAIN_HAND, sword.copy());
            player.gameMode.useItemOn(player, level, player.getOffhandItem(), InteractionHand.OFF_HAND, hit);
            helper.assertTrue(level.getBlockState(above).is(net.minecraft.world.level.block.Blocks.DIRT), "剣なのに左手の土を置けない: " + level.getBlockState(above));

            // 盾: 剣で構えてから持ち替えると止まる、ツヴァイヘンダーでは構えられない
            player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(net.minecraft.world.item.Items.SHIELD));
            player.startUsingItem(InteractionHand.OFF_HAND);
            helper.assertTrue(player.isUsingItem(), "剣なのに左手の盾を構えられない");
            player.setItemInHand(InteractionHand.MAIN_HAND, zweihander.copy());
            com.yayuy.overgrownvegetation.item.TwoHandedRules.enforce(player);
            helper.assertTrue(!player.isUsingItem(), "盾を構えたままツヴァイヘンダーに持ち替えても止まらない");
            player.startUsingItem(InteractionHand.OFF_HAND);
            helper.assertTrue(!player.isUsingItem(), "両手持ちなのに左手の盾を構えられた");
            helper.succeed();
        } finally {
            player.stopUsingItem();
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
            level.setBlock(above, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);
        }
    }

    private static Husk husk(GameTestHelper helper, double x, double z) {
        Husk h = helper.spawn(EntityType.HUSK, new Vec3(x, 2.0D, z));
        h.setNoAi(true);
        return h;
    }

    /** 振りの溜め({@code attackStrengthTicker})を満タンにする。偽プレイヤーは tick しないので溜まらない。 */
    private static void fullyCharge(FakePlayer player) {
        try {
            java.lang.reflect.Field f = LivingEntity.class.getDeclaredField("attackStrengthTicker");
            f.setAccessible(true);
            f.setInt(player, 1000);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("attackStrengthTicker に触れない", e);
        }
    }

    private static Map<ResourceLocation, Double> mainHandModifiers(ItemStack stack) {
        Map<ResourceLocation, Double> out = new HashMap<>();
        stack.forEachModifier(EquipmentSlot.MAINHAND, (Holder<Attribute> attr, AttributeModifier mod) -> out.put(mod.id(), mod.amount()));
        return out;
    }

    private static void expect(GameTestHelper helper, String item, String what, Double got, double want) {
        helper.assertTrue(got != null && Math.abs(got - want) < EPS, item + " の" + what + "が違う: " + got + " / " + want);
    }

    private WeaponGameTests() {
    }
}
