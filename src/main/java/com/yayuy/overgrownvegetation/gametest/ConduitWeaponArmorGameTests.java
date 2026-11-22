package com.yayuy.overgrownvegetation.gametest;

import com.mojang.authlib.GameProfile;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.gear.ConduitArmor;
import com.yayuy.overgrownvegetation.gear.GearTierTable;
import com.yayuy.overgrownvegetation.gear.LeyCharge;
import com.yayuy.overgrownvegetation.gear.ModGear;
import com.yayuy.overgrownvegetation.ley.LeyBatteryHolder;
import com.yayuy.overgrownvegetation.ley.LeyBatteryItem;
import com.yayuy.overgrownvegetation.registry.ModLey;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import com.yayuy.overgrownvegetation.port.capabilities.Capabilities;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import com.yayuy.overgrownvegetation.port.component.OvItemData;
import com.yayuy.overgrownvegetation.port.capabilities.OvCaps;

/**
 * 導晶の武器と防具(2026-11-21 夜): 力の有無で剣の攻撃・弩の装填・防具の受ける傷が変わる、使うと力が減る、切れたらふつう、充電できる。
 * 期待値は {@code gear_tiers.json}({@link GearTierTable} の sword・crossbow・armor)から引く。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class ConduitWeaponArmorGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(ConduitWeaponArmorGameTests.class);
        }

        private Registration() {
        }
    }

    private ConduitWeaponArmorGameTests() {
    }

    private static final String TEMPLATE = "gametest_empty7";
    private static final String BATCH = "gear_tiers";
    private static final double EPS = 1.0E-3D;

    private static GearTierTable t() {
        return DesignData.gearTiers();
    }

    private static FakePlayer player(GameTestHelper helper, String name) {
        FakePlayer player = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)), name));
        Vec3 at = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(3, 2, 3)));
        player.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        player.getAbilities().instabuild = false;
        player.getInventory().clearContent();
        return player;
    }

    /** 手に持った剣の攻撃を、ゲームが持ち替えのときにするのと同じ形で付けて読む(素手の 1 を含む)。 */
    private static double attackWith(FakePlayer player, ItemStack sword) {
        var mods = sword.getAttributeModifiers();
        mods.forEach(EquipmentSlot.MAINHAND, (attr, mod) -> player.getAttribute(attr).addOrUpdateTransientModifier(mod));
        double v = player.getAttributeValue(Attributes.ATTACK_DAMAGE);
        mods.forEach(EquipmentSlot.MAINHAND, (attr, mod) -> player.getAttribute(attr).removeModifier(mod.id()));
        return v;
    }

    private static double speedWith(FakePlayer player, ItemStack sword) {
        var mods = sword.getAttributeModifiers();
        mods.forEach(EquipmentSlot.MAINHAND, (attr, mod) -> player.getAttribute(attr).addOrUpdateTransientModifier(mod));
        double v = player.getAttributeValue(Attributes.ATTACK_SPEED);
        mods.forEach(EquipmentSlot.MAINHAND, (attr, mod) -> player.getAttribute(attr).removeModifier(mod.id()));
        return v;
    }

    /** 剣: 力が無いと攻撃 6・振り 1.6、力がある間は +2。当てるごとに 1 回分減り、足りなければ減らず強くもならない。創造モードは減らない。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void conduitSwordChargedHitsHarder(GameTestHelper helper) {
        GearTierTable.Sword s = t().sword;
        FakePlayer player = player(helper, "ov_conduit_swordsman");
        Pig pig = helper.spawn(EntityType.PIG, new BlockPos(4, 2, 4));
        ItemStack sword = new ItemStack(ModGear.CONDUIT_SWORD.get());
        double plain = attackWith(player, sword);
        if (Math.abs(plain - s.damage) > EPS || Math.abs(speedWith(player, sword) - s.attackSpeed) > EPS) {
            helper.fail("力の無い剣の攻撃 " + plain + "(期待 " + s.damage + ")・振り " + speedWith(player, sword));
            return;
        }
        LeyCharge.set(sword, s.capacity);
        double charged = attackWith(player, sword);
        if (Math.abs(charged - (s.damage + s.chargedBonus)) > EPS) {
            helper.fail("力のある剣の攻撃 " + charged + "(期待 " + (s.damage + s.chargedBonus) + ")");
            return;
        }
        sword.hurtEnemy(pig, player);
        if (LeyCharge.stored(sword) != s.capacity - s.fePerHit) {
            helper.fail("1 回当てて減った力 " + (s.capacity - LeyCharge.stored(sword)) + "(期待 " + s.fePerHit + ")");
            return;
        }
        LeyCharge.set(sword, s.fePerHit - 1);
        if (Math.abs(attackWith(player, sword) - s.damage) > EPS) {
            helper.fail("力が足りないのに攻撃が上がる");
            return;
        }
        sword.hurtEnemy(pig, player);
        if (LeyCharge.stored(sword) != s.fePerHit - 1) {
            helper.fail("足りないのに力が減った");
            return;
        }
        player.getAbilities().instabuild = true;
        LeyCharge.set(sword, s.capacity);
        sword.hurtEnemy(pig, player);
        player.getAbilities().instabuild = false;
        if (LeyCharge.stored(sword) != s.capacity) {
            helper.fail("創造モードで力が減った");
            return;
        }
        if (sword.getMaxDamage() != t().conduit.durability || !sword.is(net.minecraft.tags.ItemTags.SWORDS)) {
            helper.fail("剣の壊れにくさ " + sword.getMaxDamage() + "・剣のタグ " + sword.is(net.minecraft.tags.ItemTags.SWORDS));
            return;
        }
        helper.succeed();
    }

    /** 弩: 力があると装填の時間が {@code chargedLoadMultiplier} 倍(その時間で矢が込もる)、無いとバニラと同じ。撃つと 1 回分減る。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void conduitCrossbowLoadsFasterWhileCharged(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        GearTierTable.Crossbow c = t().crossbow;
        FakePlayer player = player(helper, "ov_conduit_crossbowman");
        int vanilla = CrossbowItem.getChargeDuration(new ItemStack(Items.CROSSBOW), player);
        ItemStack bow = new ItemStack(ModGear.CONDUIT_CROSSBOW.get());
        if (CrossbowItem.getChargeDuration(bow, player) != vanilla) {
            helper.fail("力の無い弩の装填 " + CrossbowItem.getChargeDuration(bow, player) + " ≠ バニラ " + vanilla);
            return;
        }
        LeyCharge.set(bow, c.capacity);
        int fast = CrossbowItem.getChargeDuration(bow, player);
        int expect = Math.max(1, (int) Math.round(vanilla * c.chargedLoadMultiplier));
        if (fast != expect || bow.getUseDuration(player) != fast + 3) {
            helper.fail("力のある弩の装填 " + fast + "(期待 " + expect + ")・使う時間 " + bow.getUseDuration(player));
            return;
        }
        // 速い時間だけ引いて離すと込もる(力が無いと同じ時間では込もらない)
        player.setItemInHand(InteractionHand.MAIN_HAND, bow); // 先に手へ(矢を先に入れると手の枠に入って弩で上書きされる)
        player.getInventory().add(new ItemStack(Items.ARROW, 8));
        bow.releaseUsing(level, player, bow.getUseDuration(player) - fast);
        if (!CrossbowItem.isCharged(bow)) {
            helper.fail("力のある弩が速い時間で込もらない");
            return;
        }
        ItemStack slow = new ItemStack(ModGear.CONDUIT_CROSSBOW.get());
        slow.releaseUsing(level, player, slow.getUseDuration(player) - fast);
        if (CrossbowItem.isCharged(slow)) {
            helper.fail("力の無い弩が速い時間で込もった");
            return;
        }
        int before = LeyCharge.stored(bow);
        ((CrossbowItem) bow.getItem()).performShooting(level, player, InteractionHand.MAIN_HAND, bow, 3.15F, 1.0F, null);
        if (CrossbowItem.isCharged(bow) || LeyCharge.stored(bow) != before - c.fePerShot) {
            helper.fail("撃って減った力 " + (before - LeyCharge.stored(bow)) + "(期待 " + c.fePerShot + ")・まだ込もっている " + CrossbowItem.isCharged(bow));
            return;
        }
        AABB box = new AABB(player.blockPosition()).inflate(4);
        if (level.getEntitiesOfClass(AbstractArrow.class, box).isEmpty()) {
            helper.fail("矢が出ていない");
            return;
        }
        // 込めていないまま撃っても減らない
        before = LeyCharge.stored(bow);
        ((CrossbowItem) bow.getItem()).performShooting(level, player, InteractionHand.MAIN_HAND, bow, 3.15F, 1.0F, null);
        if (LeyCharge.stored(bow) != before) {
            helper.fail("空撃ちで力が減った");
            return;
        }
        if (bow.getMaxDamage() != c.durability || !bow.is(net.minecraft.tags.ItemTags.CROSSBOW_ENCHANTABLE)) {
            helper.fail("弩の壊れにくさ " + bow.getMaxDamage() + "・弩のエンチャントのタグ " + bow.is(net.minecraft.tags.ItemTags.CROSSBOW_ENCHANTABLE));
            return;
        }
        helper.succeed();
    }

    private static Pig armoredPig(GameTestHelper helper, BlockPos at, int chargedPieces) {
        Pig pig = helper.spawn(EntityType.PIG, at);
        Item[] items = {ModGear.CONDUIT_HELMET.get(), ModGear.CONDUIT_CHESTPLATE.get(), ModGear.CONDUIT_LEGGINGS.get(), ModGear.CONDUIT_BOOTS.get()};
        EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        for (int i = 0; i < 4; i++) {
            ItemStack s = new ItemStack(items[i]);
            if (i < chargedPieces) LeyCharge.full(s);
            pig.setItemSlot(slots[i], s);
        }
        return pig;
    }

    /** 防具: 力がある 1 部位ごとに受ける傷が {@code reductionPerPiece} 減り、部位ごとに 1 回分減る。切れたらふつう。防具を素通りする傷は減らさず力も使わない。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void conduitArmorReducesDamageWhileCharged(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        GearTierTable.Armor a = t().armor;
        FakePlayer attacker = player(helper, "ov_conduit_armor_tester");
        // 防御の数
        int[] defense = {a.helmetDefense, a.chestplateDefense, a.leggingsDefense, a.bootsDefense};
        Item[] items = {ModGear.CONDUIT_HELMET.get(), ModGear.CONDUIT_CHESTPLATE.get(), ModGear.CONDUIT_LEGGINGS.get(), ModGear.CONDUIT_BOOTS.get()};
        for (int i = 0; i < 4; i++) {
            ArmorItem piece = (ArmorItem) items[i];
            if (piece.getDefense() != defense[i] || Math.abs(piece.getToughness() - a.toughness) > EPS) {
                helper.fail(piece + " の防御 " + piece.getDefense() + "(期待 " + defense[i] + ")・強度 " + piece.getToughness());
                return;
            }
        }
        DamageSource hit = level.damageSources().playerAttack(attacker);
        float damage = 4.0F;
        Pig plain = armoredPig(helper, new BlockPos(1, 2, 1), 0);
        Pig full = armoredPig(helper, new BlockPos(5, 2, 1), 4);
        Pig half = armoredPig(helper, new BlockPos(1, 2, 5), 2);
        float hp0 = plain.getHealth();
        plain.hurt(hit, damage);
        full.hurt(hit, damage);
        half.hurt(hit, damage);
        double lossPlain = hp0 - plain.getHealth(), lossFull = hp0 - full.getHealth(), lossHalf = hp0 - half.getHealth();
        if (lossPlain <= 0) {
            helper.fail("傷が通っていない");
            return;
        }
        double expFull = lossPlain * (1.0D - 4 * a.reductionPerPiece), expHalf = lossPlain * (1.0D - 2 * a.reductionPerPiece);
        if (Math.abs(lossFull - expFull) > EPS || Math.abs(lossHalf - expHalf) > EPS) {
            helper.fail("受けた傷: 力なし " + lossPlain + "・4 部位 " + lossFull + "(期待 " + expFull + ")・2 部位 " + lossHalf + "(期待 " + expHalf + ")");
            return;
        }
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            if (LeyCharge.stored(full.getItemBySlot(slot)) != a.capacity - a.fePerHit) {
                helper.fail(slot + " の減った力 " + (a.capacity - LeyCharge.stored(full.getItemBySlot(slot))) + "(期待 " + a.fePerHit + ")");
                return;
            }
        }
        if (LeyCharge.stored(half.getItemBySlot(EquipmentSlot.LEGS)) != 0) {
            helper.fail("力の無い部位に力がある");
            return;
        }
        // 防具を素通りする傷(飢え)では減らさず、力も使わない
        Pig starving = armoredPig(helper, new BlockPos(5, 2, 5), 4);
        float hp1 = starving.getHealth();
        starving.hurt(level.damageSources().starve(), 2.0F);
        if (Math.abs((hp1 - starving.getHealth()) - 2.0F) > EPS || LeyCharge.stored(starving.getItemBySlot(EquipmentSlot.HEAD)) != a.capacity) {
            helper.fail("素通りの傷: 受けた " + (hp1 - starving.getHealth()) + "・力 " + LeyCharge.stored(starving.getItemBySlot(EquipmentSlot.HEAD)));
            return;
        }
        // 1 回分より少なければ力が無い扱い
        ItemStack low = new ItemStack(ModGear.CONDUIT_HELMET.get());
        LeyCharge.set(low, a.fePerHit - 1);
        if (LeyCharge.charged(low)) {
            helper.fail("1 回分より少ないのに力がある扱い");
            return;
        }
        helper.succeed();
    }

    /** 剣・弩・防具も道具と同じく、汲み上げ器の電池の口と電池の右クリックで充電でき、ためられる量は物ごと(防具 2 万)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void conduitWeaponsAndArmorCharge(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FakePlayer player = player(helper, "ov_conduit_gear_charger");
        BlockPos pumpPos = new BlockPos(1, 2, 1);
        helper.setBlock(pumpPos, ModLey.PUMP.get());
        BlockPos abs = helper.absolutePos(pumpPos);
        BlockState state = level.getBlockState(abs);
        if (!(level.getBlockEntity(abs) instanceof LeyBatteryHolder holder)) {
            helper.fail("電池の口が無い");
            return;
        }
        ItemStack chest = new ItemStack(ModGear.CONDUIT_CHESTPLATE.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, chest);
        if (!LeyBatteryHolder.insert(level, abs, state, player, player.getMainHandItem(), holder) || holder.battery().isEmpty()) {
            helper.fail("胸当てを電池の口に差せない");
            return;
        }
        IEnergyStorage port = holder.batteryStorage();
        if (port == null || port.getMaxEnergyStored() != t().armor.capacity || port.canExtract()) {
            helper.fail("胸当ての口: 容量 " + (port == null ? -1 : port.getMaxEnergyStored()) + "(期待 " + t().armor.capacity + ")");
            return;
        }
        int got = port.receiveEnergy(Integer.MAX_VALUE, false);
        if (got <= 0 || LeyCharge.stored(holder.battery()) != got) {
            helper.fail("胸当てに口から入らない: " + got);
            return;
        }
        Item[] all = {ModGear.CONDUIT_SWORD.get(), ModGear.CONDUIT_CROSSBOW.get(), ModGear.CONDUIT_HELMET.get(), ModGear.CONDUIT_BOOTS.get()};
        int[] cap = {t().sword.capacity, t().crossbow.capacity, t().armor.capacity, t().armor.capacity};
        for (int i = 0; i < all.length; i++) {
            ItemStack target = new ItemStack(all[i]);
            ItemStack battery = new ItemStack(ModLey.BATTERY.get());
            OvItemData.set(battery, ModLey.ENERGY.get(), LeyBatteryItem.capacity());
            int batteryBefore = LeyBatteryItem.stored(battery);
            boolean handled = LeyCharge.onStackedOn(target, battery, ClickAction.SECONDARY, player);
            int expect = Math.min(Math.min(cap[i], batteryBefore), t().conduit.pourPerClick);
            if (!handled || LeyCharge.stored(target) != expect || LeyBatteryItem.stored(battery) != batteryBefore - expect) {
                helper.fail(all[i] + " に電池から移らない: " + LeyCharge.stored(target) + "(期待 " + expect + ")");
                return;
            }
            IEnergyStorage cap1 = OvCaps.getCapability(target, Capabilities.EnergyStorage.ITEM);
            if (cap1 == null || cap1.getMaxEnergyStored() != cap[i] || OvItemData.getOrDefault(LeyCharge.full(new ItemStack(all[i])), ModLey.ENERGY.get(), 0) != cap[i]) {
                helper.fail(all[i] + " の FE の口・満タンが合わない");
                return;
            }
        }
        helper.succeed();
    }
}
