package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.DirtSpec;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import com.yayuy.overgrownvegetation.registry.ModDataComponents;
import com.yayuy.overgrownvegetation.registry.ModItems;
import com.yayuy.overgrownvegetation.spore.SporeExposureTracker;
import com.yayuy.overgrownvegetation.survival.decon.Decon;
import com.yayuy.overgrownvegetation.survival.decon.GearDirt;
import com.yayuy.overgrownvegetation.survival.decon.GearShower;
import com.yayuy.overgrownvegetation.survival.decon.HandWash;
import com.yayuy.overgrownvegetation.survival.vent.AirFilters;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import com.yayuy.overgrownvegetation.port.component.OvItemData;

/**
 * 装備の汚れ(2026-11-14 サバイバルの背骨 段 3b): 濃い所で汚れる(薄い所・創造では汚れない)・汚れた装備で暴露量がたまる(マスクが効けばたまらない)・
 * 防具の守りと掘る速さが落ちる・説明文に数字が出ない・手洗い/洗い場の枠/洗い場の上で落ちる・マスクを洗うとフィルターも洗われる・保存と復元。
 * 期待値は設計値({@code spore_field.json} の {@code dirt})から引く。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class GearDirtGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(GearDirtGameTests.class);
        }

        private Registration() {
        }
    }

    private GearDirtGameTests() {
    }

    private static final String TEMPLATE = "gametest_empty7";
    private static final String BATCH = "survival_dirt";

    private static DirtSpec spec() {
        return DesignData.sporeField().dirt();
    }

    static Player suited(GameTestHelper helper) {
        Player p = helper.makeMockPlayer(GameType.SURVIVAL);
        p.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        p.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
        p.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
        p.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
        p.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_PICKAXE));
        return p;
    }

    static void dirtyAll(Player p, int value) {
        for (EquipmentSlot slot : GearDirt.SLOTS) {
            ItemStack st = p.getItemBySlot(slot);
            if (GearDirt.canGetDirty(st)) GearDirt.setDirt(st, value);
        }
    }

    /** 濃い所(100%)に {@code seconds} 秒いると、着ている物は perSecondAt100 × 秒 汚れる(刻みの分だけ遅れる)。minPercent より薄い所・創造では汚れない。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void gearGetsDirtyInDenseSpores(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        DirtSpec s = spec();
        Player p = suited(helper);
        int seconds = 60;
        for (int i = 0; i < seconds; i++) GearDirt.step(level, p, 20, 100.0D);
        double expect = Math.min(s.full(), s.perSecondAt100() * seconds);
        int chest = GearDirt.dirt(p.getItemBySlot(EquipmentSlot.CHEST));
        int pick = GearDirt.dirt(p.getMainHandItem());
        if (Math.abs(chest - expect) > s.commitStepArmor() + 1 || Math.abs(pick - expect) > s.commitStepHand() + 1) {
            helper.fail(seconds + " 秒で胸 " + chest + "・つるはし " + pick + "(約 " + expect + " のはず)");
            return;
        }
        // 段は見た目の段の境で変わる
        if (GearDirt.stage(p.getItemBySlot(EquipmentSlot.CHEST)) != s.stageOf(chest)) {
            helper.fail("段がずれた");
            return;
        }
        // 薄い所では汚れない
        Player q = suited(helper);
        for (int i = 0; i < seconds; i++) GearDirt.step(level, q, 20, s.minPercent() * 0.9D);
        if (GearDirt.dirt(q.getItemBySlot(EquipmentSlot.CHEST)) != 0) {
            helper.fail("薄い所(" + s.minPercent() * 0.9D + "%)で汚れた");
            return;
        }
        // 創造では汚れない
        Player c = helper.makeMockPlayer(GameType.CREATIVE);
        c.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
        for (int i = 0; i < seconds; i++) GearDirt.step(level, c, 20, 100.0D);
        if (GearDirt.dirt(c.getItemBySlot(EquipmentSlot.CHEST)) != 0) {
            helper.fail("創造で汚れた");
            return;
        }
        // 重なる物(素材)は汚れない
        if (GearDirt.canGetDirty(new ItemStack(Items.BEEF)) || !GearDirt.canGetDirty(new ItemStack(Items.SHIELD))) {
            helper.fail("汚れの付く物の判定が違う");
            return;
        }
        helper.succeed();
    }

    /**
     * 汚れた装備を着ていると、きれいな空気(0%)の中でも暴露量がたまる(4 か所 × seepPerStage)。きれいな装備ではたまらない。
     * フィルターの残ったマスクを被っていればたまらない。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void dirtyGearSeepsExposure(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        DirtSpec s = spec();
        int seconds = 60;

        Player clean = suited(helper);
        for (int i = 0; i < seconds; i++) SporeExposureTracker.tickWith(level, clean, 20, 0.0D);
        if (SporeExposureTracker.dose(clean) > 0.0D) {
            helper.fail("きれいな装備で暴露量がたまった: " + SporeExposureTracker.dose(clean));
            return;
        }

        Player dirty = suited(helper);
        dirtyAll(dirty, s.full());
        double rate = GearDirt.seepPerSecond(dirty);
        if (Math.abs(rate - 4 * s.seepPerStage()[2]) > 1e-9) {
            helper.fail("吸う速さ " + rate + "(" + 4 * s.seepPerStage()[2] + " のはず)");
            return;
        }
        for (int i = 0; i < seconds; i++) SporeExposureTracker.tickWith(level, dirty, 20, 0.0D);
        double dose = SporeExposureTracker.dose(dirty);
        if (Math.abs(dose - rate * seconds) > 1e-6) {
            helper.fail(seconds + " 秒で暴露量 " + dose + "(" + rate * seconds + " のはず)");
            return;
        }

        Player masked = suited(helper);
        dirtyAll(masked, s.full());
        masked.setItemSlot(EquipmentSlot.HEAD, new ItemStack(ModItems.PROTECTIVE_MASK.get()));
        for (int i = 0; i < seconds; i++) SporeExposureTracker.tickWith(level, masked, 20, 0.0D);
        if (SporeExposureTracker.dose(masked) > 0.0D) {
            helper.fail("マスクを被っていても汚れた装備から吸った: " + SporeExposureTracker.dose(masked));
            return;
        }
        helper.succeed();
    }

    /** 汚れの段ごとに防具の守り・強靭さ(プレイヤーの修飾)と掘る速さが落ちる。物の説明文は汚れても変わらない(数字を見せない)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void dirtLowersPerformanceWithoutNumbers(GameTestHelper helper) {
        DirtSpec s = spec();
        Player p = helper.makeMockPlayer(GameType.SURVIVAL);
        ItemStack chest = new ItemStack(Items.DIAMOND_CHESTPLATE);
        ItemStack clean = chest.copy();
        p.setItemSlot(EquipmentSlot.CHEST, chest);
        p.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_PICKAXE));
        for (int stage = 1; stage <= 3; stage++) {
            int v = stage == 1 ? s.stage1() : stage == 2 ? s.stage2() : s.stage3();
            GearDirt.setDirt(chest, v);
            GearDirt.setDirt(p.getMainHandItem(), v);
            GearDirt.applyModifiers(p);
            AttributeModifier a = p.getAttribute(Attributes.ARMOR).getModifier(GearDirt.ARMOR_ID);
            AttributeModifier t = p.getAttribute(Attributes.ARMOR_TOUGHNESS).getModifier(GearDirt.TOUGHNESS_ID);
            double loss = s.armorLossPerStage()[stage - 1];
            // ダイヤの胸当て = 守り 8・強靭さ 2
            if (a == null || Math.abs(a.amount() + 8.0D * loss) > 1e-6 || t == null || Math.abs(t.amount() + 2.0D * loss) > 1e-6) {
                helper.fail("段 " + stage + ": 守りの修飾 " + (a == null ? "なし" : a.amount()) + "・強靭さ " + (t == null ? "なし" : t.amount()));
                return;
            }
            double f = GearDirt.breakSpeedFactor(p);
            if (Math.abs(f - (1.0D - s.breakSpeedLossPerStage()[stage - 1])) > 1e-9) {
                helper.fail("段 " + stage + ": 掘る速さ ×" + f);
                return;
            }
        }
        // 洗えば戻る
        GearDirt.setDirt(chest, 0);
        GearDirt.setDirt(p.getMainHandItem(), 0);
        GearDirt.applyModifiers(p);
        if (p.getAttribute(Attributes.ARMOR).getModifier(GearDirt.ARMOR_ID) != null || GearDirt.breakSpeedFactor(p) != 1.0D) {
            helper.fail("きれいにしても修飾が残る");
            return;
        }
        // 説明文(高度な表示も)に汚れの数字が出ない
        GearDirt.setDirt(chest, s.full());
        var ctx = Item.TooltipContext.of(helper.getLevel());
        var dirtyLines = chest.getTooltipLines(ctx, p, TooltipFlag.Default.NORMAL).stream().map(c -> c.getString()).toList();
        var cleanLines = clean.getTooltipLines(ctx, p, TooltipFlag.Default.NORMAL).stream().map(c -> c.getString()).toList();
        if (!dirtyLines.equals(cleanLines)) {
            helper.fail("汚れで説明文が変わった: " + dirtyLines);
            return;
        }
        helper.succeed();
    }

    /** 汚れた物は耐久の減りが段ごとに wearExtraPerStage 増える(差し込み ItemStackDirtWearMixin)。きれいな物はそのまま。2026-11-14 決定 36。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void dirtyGearWearsFaster(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        DirtSpec s = spec();
        ItemStack clean = new ItemStack(Items.IRON_PICKAXE);
        clean.hurtAndBreak(100, level, (net.minecraft.world.entity.LivingEntity) null, i -> { });
        if (clean.getDamageValue() != 100) {
            helper.fail("きれいなつるはしの耐久の減り " + clean.getDamageValue() + "(100 のはず)");
            return;
        }
        for (int stage = 1; stage <= 3; stage++) {
            ItemStack dirty = new ItemStack(Items.IRON_PICKAXE);
            GearDirt.setDirt(dirty, stage == 1 ? s.stage1() : stage == 2 ? s.stage2() : s.stage3());
            dirty.hurtAndBreak(100, level, (net.minecraft.world.entity.LivingEntity) null, i -> { });
            double expect = 100.0D * (1.0D + s.wearExtraPerStage()[stage - 1]);
            if (Math.abs(dirty.getDamageValue() - expect) > 1.0D) {
                helper.fail("段 " + stage + " の耐久の減り " + dirty.getDamageValue() + "(約 " + expect + " のはず。差し込みが効いていない?)");
                return;
            }
        }
        helper.succeed();
    }

    /** 手洗い 1 回で handWashAmount 落ち(水 1 段)、洗い場の枠では全部落ち、洗い場の上に立つと showerPerTick ずつ落ちる。保存しても汚れは残る。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void dirtWashesOffAndPersists(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        DirtSpec s = spec();
        // 保存と復元
        ItemStack chest = new ItemStack(Items.IRON_CHESTPLATE);
        GearDirt.setDirt(chest, 777);
        Tag tag = chest.save(level.registryAccess());
        ItemStack back = ItemStack.parseOptional(level.registryAccess(), (net.minecraft.nbt.CompoundTag) tag);
        if (GearDirt.dirt(back) != 777) {
            helper.fail("保存で汚れが変わった: " + GearDirt.dirt(back));
            return;
        }
        // 手洗い
        BlockPos rel = new BlockPos(3, 2, 3);
        helper.setBlock(rel, Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, 3));
        BlockPos abs = helper.absolutePos(rel);
        Player p = helper.makeMockPlayer(GameType.SURVIVAL);
        p.getPersistentData().remove(HandWash.WORK_KEY);
        ItemStack held = new ItemStack(Items.IRON_CHESTPLATE);
        GearDirt.setDirt(held, s.full());
        p.setItemInHand(InteractionHand.MAIN_HAND, held);
        if (Decon.kind(level, held) != Decon.Kind.ITEM || !HandWash.wash(level, abs, p, InteractionHand.MAIN_HAND)) {
            helper.fail("汚れた防具を手で洗えない");
            return;
        }
        int after = GearDirt.dirt(p.getMainHandItem());
        if (after != Math.max(0, s.full() - s.handWashAmount())) {
            helper.fail("手洗い 1 回で汚れ " + s.full() + " → " + after);
            return;
        }
        if (!p.getMainHandItem().is(Items.IRON_CHESTPLATE)) {
            helper.fail("洗った防具が手に戻らない: " + p.getMainHandItem());
            return;
        }
        // 洗い場の枠(全部落ちる)
        ItemStack w = new ItemStack(Items.IRON_SWORD);
        GearDirt.setDirt(w, s.full());
        var out = Decon.washOne(level, w, level.random, GearDirt.machineStep());
        if (out.size() != 1 || GearDirt.dirt(out.get(0)) != Math.max(0, s.full() - s.machineWashAmount())) {
            helper.fail("洗い場の枠で落ちない: " + out);
            return;
        }
        // 洗い場の上(1 tick ごと)
        Player q = suited(helper);
        dirtyAll(q, s.full());
        int ticks = 0;
        while (GearShower.washPlayer(q, s.showerPerTick()) && ticks < 10_000) ticks++;
        int expectTicks = (int) Math.ceil(s.full() / (double) Math.max(1, s.showerPerTick()));
        if (GearDirt.dirt(q.getItemBySlot(EquipmentSlot.CHEST)) != 0 || ticks != expectTicks) {
            helper.fail("洗い場の上で " + ticks + " tick(" + expectTicks + " のはず)");
            return;
        }
        // マスクを洗うと汚れもフィルターも洗われる
        ItemStack mask = new ItemStack(ModItems.PROTECTIVE_MASK.get());
        OvItemData.set(mask, ModDataComponents.MASK_FILTER_USED, 5000);
        GearDirt.setDirt(mask, s.full());
        Decon.washOne(level, mask, level.random, GearDirt.machineStep());
        if (AirFilters.used(mask) != 0 || AirFilters.washes(mask) != 1 || GearDirt.dirt(mask) != 0) {
            helper.fail("マスクを洗った: 詰まり " + AirFilters.used(mask) + "・回数 " + AirFilters.washes(mask) + "・汚れ " + GearDirt.dirt(mask));
            return;
        }
        helper.setBlock(rel, Blocks.AIR);
        helper.succeed();
    }
}
