package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.EffectTable;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.ModItems;
import com.yayuy.overgrownvegetation.spore.GearContamination;
import com.yayuy.overgrownvegetation.spore.GearContaminationEffects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * 胞子汚染した装備の下がり方(2026-10-14 ユーザー指定。前は全部を一律に下げていて、汚染された盾が攻撃を防がなくなった)と、
 * 過繁茂の木材がバニラの木材のタグに入っていること(同日のバグ報告「過繁茂した板材がほかの板材と同じように使えない」)。
 * <ul>
 *     <li>武器は攻撃力と攻撃速度だけが下がり(リーチなど他の修飾はそのまま)、道具・盾・弓の属性は下がらない。防具は今までどおり
 *     ({@link #weaponsLoseDamageAndSpeedOnly})。</li>
 *     <li>耐久値の減り: 武器・道具は最大 3 倍、盾は最大 2 倍、弓・防具は変わらない({@link #durabilityWearByKind})。</li>
 *     <li>道具の採掘速度、矢の初速(飛距離)と矢のダメージ({@link #miningSpeedAndArrows})。</li>
 *     <li>過繁茂している板材は {@code #planks}、階段・フェンスはアイテムの木の階段・木のフェンス、原木は {@code #logs}・{@code #logs_that_burn}(焼くと木炭)、
 *     扉は木の扉のタグに入り、作業台・棒が作れる
 *     ({@link #overgrownWoodHasVanillaTags})。</li>
 * </ul>
 * 期待値は設計値({@code effects.json} の {@code gearContamination})から引く。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class GearContaminationGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(GearContaminationGameTests.class);
        }

        private Registration() {
        }
    }

    private static final String TEMPLATE = "gametest_empty7";
    private static final String BATCH = "gear_contamination";
    private static final double EPS = 1.0E-4D;

    private static ItemStack contaminated(Item item, float percent) {
        ItemStack stack = new ItemStack(item);
        GearContamination.setPercent(stack, percent);
        return stack;
    }

    /** 手に持ったとき(または着たとき)の属性の合計。素手の値 {@code bare} に加算値を足す。 */
    private static double total(ItemStack stack, EquipmentSlot slot, Holder<Attribute> attribute, double bare) {
        double[] sum = {bare};
        stack.forEachModifier(slot, (attr, modifier) -> {
            if (attr.is(attribute) && modifier.operation() == AttributeModifier.Operation.ADD_VALUE) {
                sum[0] += modifier.amount();
            }
        });
        return sum[0];
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) <= EPS;
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void weaponsLoseDamageAndSpeedOnly(GameTestHelper helper) {
        EffectTable.GearContaminationSpec spec = GearContamination.spec();
        for (float percent : new float[]{50.0F, 100.0F}) {
            ItemStack clean = new ItemStack(Items.IRON_SWORD);
            ItemStack dirty = contaminated(Items.IRON_SWORD, percent);
            double s = GearContamination.severity(dirty);
            if (percent >= 100.0F && !near(s, 1.0D)) {
                helper.fail("汚染 100% の効きの強さが 1 でない: " + s);
                return;
            }
            double damage = total(dirty, EquipmentSlot.MAINHAND, Attributes.ATTACK_DAMAGE, 1.0D);
            double expectDamage = total(clean, EquipmentSlot.MAINHAND, Attributes.ATTACK_DAMAGE, 1.0D) * (1.0D - spec.weaponAttackDamageMax() * s);
            double speed = total(dirty, EquipmentSlot.MAINHAND, Attributes.ATTACK_SPEED, 4.0D);
            double expectSpeed = total(clean, EquipmentSlot.MAINHAND, Attributes.ATTACK_SPEED, 4.0D) * (1.0D - spec.weaponAttackSpeedMax() * s);
            if (!near(damage, expectDamage) || !near(speed, expectSpeed)) {
                helper.fail("汚染 " + percent + "% の鉄の剣: 攻撃力 " + damage + "(期待 " + expectDamage + ")・攻撃速度 " + speed
                        + "(期待 " + expectSpeed + ")");
                return;
            }
        }
        // ツヴァイヘンダーのリーチ・薙ぎ払いは下げない。
        Item zwei = ModItems.CHLOROPHYLLITE_ZWEIHANDER.get();
        for (Holder<Attribute> attr : List.of(Attributes.ENTITY_INTERACTION_RANGE, Attributes.SWEEPING_DAMAGE_RATIO)) {
            double clean = total(new ItemStack(zwei), EquipmentSlot.MAINHAND, attr, 0.0D);
            double dirty = total(contaminated(zwei, 100.0F), EquipmentSlot.MAINHAND, attr, 0.0D);
            if (clean <= 0.0D || !near(clean, dirty)) {
                helper.fail("汚染したツヴァイヘンダーの " + attr.getRegisteredName() + " が変わった: " + clean + " → " + dirty);
                return;
            }
        }
        // 道具(斧の攻撃力も)・盾・弓の属性は下げない。
        for (Item item : List.of(Items.IRON_AXE, Items.IRON_PICKAXE, Items.SHIELD, Items.BOW)) {
            double clean = total(new ItemStack(item), EquipmentSlot.MAINHAND, Attributes.ATTACK_DAMAGE, 1.0D);
            double dirty = total(contaminated(item, 100.0F), EquipmentSlot.MAINHAND, Attributes.ATTACK_DAMAGE, 1.0D);
            if (!near(clean, dirty)) {
                helper.fail(item + " の攻撃力が汚染で変わった: " + clean + " → " + dirty);
                return;
            }
        }
        // 防具は今までどおり steps の低下率。
        ItemStack chest = contaminated(Items.IRON_CHESTPLATE, 100.0F);
        double armor = total(chest, EquipmentSlot.CHEST, Attributes.ARMOR, 0.0D);
        double expectArmor = total(new ItemStack(Items.IRON_CHESTPLATE), EquipmentSlot.CHEST, Attributes.ARMOR, 0.0D)
                * GearContamination.factor(chest);
        if (!near(armor, expectArmor)) {
            helper.fail("汚染 100% の鉄のチェストプレート: 防御力 " + armor + "(期待 " + expectArmor + ")");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void durabilityWearByKind(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        EffectTable.GearContaminationSpec spec = GearContamination.spec();
        record Case(Item item, float percent, int use, int expect) {
        }
        List<Case> cases = List.of(
                new Case(Items.IRON_SWORD, 0.0F, 1, 1),
                new Case(Items.IRON_SWORD, 100.0F, 1, (int) Math.round(spec.weaponDurabilityMultiplierMax())),
                new Case(Items.IRON_PICKAXE, 100.0F, 1, (int) Math.round(spec.toolDurabilityMultiplierMax())),
                new Case(Items.IRON_AXE, 100.0F, 1, (int) Math.round(spec.toolDurabilityMultiplierMax())),
                new Case(Items.SHIELD, 100.0F, 3, (int) Math.round(3 * spec.shieldDurabilityMultiplierMax())),
                new Case(Items.BOW, 100.0F, 1, 1),
                new Case(Items.IRON_CHESTPLATE, 100.0F, 1, 1));
        for (Case c : cases) {
            ItemStack stack = contaminated(c.item(), c.percent());
            stack.hurtAndBreak(c.use(), level, null, item -> {
            });
            if (stack.getDamageValue() != c.expect()) {
                helper.fail("汚染 " + c.percent() + "% の " + c.item() + " を " + c.use() + " 使ったら耐久値が " + stack.getDamageValue()
                        + " 減った(期待 " + c.expect() + ")");
                return;
            }
        }
        // 途中の段は 1 との間を縮める。
        ItemStack half = contaminated(Items.IRON_SWORD, 50.0F);
        double expect = 1.0D + (spec.weaponDurabilityMultiplierMax() - 1.0D) * GearContamination.severity(half);
        if (!near(GearContamination.durabilityMultiplier(half), expect)) {
            helper.fail("汚染 50% の剣の耐久値の倍率 " + GearContamination.durabilityMultiplier(half) + "(期待 " + expect + ")");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 40)
    public static void miningSpeedAndArrows(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        EffectTable.GearContaminationSpec spec = GearContamination.spec();
        double mining = GearContaminationEffects.miningSpeedFactor(contaminated(Items.IRON_PICKAXE, 100.0F));
        if (!near(mining, 1.0D - spec.toolMiningSpeedMax())) {
            helper.fail("汚染 100% のつるはしの採掘速度の倍率 " + mining);
            return;
        }
        if (!near(GearContaminationEffects.miningSpeedFactor(contaminated(Items.IRON_SWORD, 100.0F)), 1.0D)) {
            helper.fail("汚染した剣の採掘速度が下がった");
            return;
        }

        // 矢の初速: 飛距離 −70% → 初速 √0.3 倍。
        ItemStack bow = contaminated(Items.BOW, 100.0F);
        double vf = Math.sqrt(1.0D - spec.bowRangeMax());
        Vec3 at = Vec3.atCenterOf(helper.absolutePos(new BlockPos(1, 4, 1)));
        Arrow arrow = new Arrow(level, at.x, at.y, at.z, new ItemStack(Items.ARROW), bow);
        arrow.setNoGravity(true);
        arrow.setDeltaMovement(2.0D, 0.0D, 0.0D);
        level.addFreshEntity(arrow);
        if (!near(arrow.getDeltaMovement().x, 2.0D * vf)) {
            helper.fail("汚染 100% の弓の矢の初速 " + arrow.getDeltaMovement().x + "(期待 " + 2.0D * vf + ")");
            return;
        }
        Arrow cleanArrow = new Arrow(level, at.x, at.y, at.z, new ItemStack(Items.ARROW), new ItemStack(Items.BOW));
        cleanArrow.setNoGravity(true);
        cleanArrow.setDeltaMovement(2.0D, 0.0D, 0.0D);
        level.addFreshEntity(cleanArrow);
        if (!near(cleanArrow.getDeltaMovement().x, 2.0D)) {
            helper.fail("汚れていない弓の矢の初速が変わった: " + cleanArrow.getDeltaMovement().x);
            return;
        }

        // 矢のダメージ: 遅くなったぶんを戻して −35%(当たったときの速さ × 基本ダメージがもう vf 倍なので、合わせて 0.65 倍になる)。
        IronGolem golem = helper.spawn(EntityType.IRON_GOLEM, new BlockPos(3, 2, 3));
        float before = golem.getHealth();
        golem.hurt(level.damageSources().arrow(arrow, null), 10.0F);
        double expect = 10.0D * (1.0D - spec.bowDamageMax()) / vf;
        double dealt = before - golem.getHealth();
        arrow.discard();
        cleanArrow.discard();
        if (Math.abs(dealt - expect) > 1.0E-3D) {
            helper.fail("汚染 100% の弓の矢のダメージ " + dealt + "(期待 " + expect + ")");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void overgrownWoodHasVanillaTags(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ItemStack planks = new ItemStack(ModBlocks.OVERGROWN_PLANKS.get());
        if (!planks.is(ItemTags.PLANKS) || !ModBlocks.OVERGROWN_PLANKS.get().defaultBlockState().is(BlockTags.PLANKS)) {
            helper.fail("過繁茂している板材が #planks に入っていない");
            return;
        }
        if (!new ItemStack(ModBlocks.OVERGROWN_STAIRS.get()).is(ItemTags.WOODEN_STAIRS)
                || !new ItemStack(ModBlocks.OVERGROWN_SLAB.get()).is(ItemTags.WOODEN_SLABS)
                || !new ItemStack(ModBlocks.OVERGROWN_FENCE.get()).is(ItemTags.WOODEN_FENCES)) {
            helper.fail("過繁茂した階段・ハーフ・フェンスがアイテムの木のタグに入っていない");
            return;
        }
        // 原木(10-14 ユーザー指定「原木も log に」)と扉(「過繁茂した扉も木の扉タグに」)。
        for (net.minecraft.world.level.block.Block log : List.of(ModBlocks.OVERGROWN_LOG.get(), ModBlocks.OVERGROWN_WOOD.get(),
                ModBlocks.GIANT_OVERGROWN_LOG.get(), ModBlocks.GIANT_OVERGROWN_CAMBIUM.get())) {
            ItemStack item = new ItemStack(log);
            // 巨大樹の幹はアイテムだけ。ブロックのタグに入れると整地・道・基地が巨大樹(中身を持つ構造物)まで払う。
            boolean giant = log == ModBlocks.GIANT_OVERGROWN_LOG.get() || log == ModBlocks.GIANT_OVERGROWN_CAMBIUM.get();
            if (!item.is(ItemTags.LOGS) || !item.is(ItemTags.LOGS_THAT_BURN) || log.defaultBlockState().is(BlockTags.LOGS) == giant) {
                helper.fail(log + " が #logs / #logs_that_burn に入っていない");
                return;
            }
        }
        if (!new ItemStack(ModItems.OVERGROWN_DOOR_ITEM.get()).is(ItemTags.WOODEN_DOORS)
                || !ModBlocks.OVERGROWN_DOOR.get().defaultBlockState().is(BlockTags.WOODEN_DOORS)) {
            helper.fail("過繁茂した扉が木の扉のタグに入っていない");
            return;
        }
        net.minecraft.world.item.crafting.SingleRecipeInput logInput =
                new net.minecraft.world.item.crafting.SingleRecipeInput(new ItemStack(ModBlocks.OVERGROWN_LOG.get()));
        ItemStack charcoal = level.getRecipeManager().getRecipeFor(RecipeType.SMELTING, logInput, level)
                .map(holder -> holder.value().assemble(logInput, level.registryAccess())).orElse(ItemStack.EMPTY);
        if (!charcoal.is(Items.CHARCOAL)) {
            helper.fail("過繁茂している原木を焼いても木炭にならない(出たもの: " + charcoal + ")");
            return;
        }
        record Case(int w, int h, int n, Item result) {
        }
        for (Case c : List.of(new Case(2, 2, 4, Items.CRAFTING_TABLE), new Case(1, 2, 2, Items.STICK))) {
            CraftingInput grid = CraftingInput.of(c.w(), c.h(), java.util.Collections.nCopies(c.n(), planks));
            ItemStack out = level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, grid, level)
                    .map(holder -> holder.value().assemble(grid, level.registryAccess())).orElse(ItemStack.EMPTY);
            if (!out.is(c.result())) {
                helper.fail("過繁茂している板材から " + c.result() + " が作れない(出たもの: " + out + ")");
                return;
            }
        }
        helper.succeed();
    }

    private GearContaminationGameTests() {
    }
}
