package com.yayuy.overgrownvegetation.port;

import com.mojang.serialization.Codec;
import com.yayuy.overgrownvegetation.port.PortConst;
import com.yayuy.overgrownvegetation.port.attachment.AttachmentType;
import com.yayuy.overgrownvegetation.port.attachment.OvAttach;
import com.yayuy.overgrownvegetation.port.component.DataComponentType;
import com.yayuy.overgrownvegetation.port.component.OvItemData;
import com.yayuy.overgrownvegetation.port.entity.PortAttributes;
import com.yayuy.overgrownvegetation.port.event.tick.EntityTickEvent;
import com.yayuy.overgrownvegetation.port.event.tick.LevelTickEvent;
import com.yayuy.overgrownvegetation.port.event.tick.ServerTickEvent;
import com.yayuy.overgrownvegetation.port.network.ByteBufCodecs;
import com.yayuy.overgrownvegetation.port.network.CustomPacketPayload;
import com.yayuy.overgrownvegetation.port.network.OvChannel;
import com.yayuy.overgrownvegetation.port.network.RegisterPayloadHandlersEvent;
import com.yayuy.overgrownvegetation.port.network.StreamCodec;
import com.yayuy.overgrownvegetation.port.registries.DeferredHolder;
import com.yayuy.overgrownvegetation.port.registries.DeferredRegister;
import com.yayuy.overgrownvegetation.port.registries.NeoForgeRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 【1.20.1 だけの物・互換の層の確かめ】環境変数 {@code OV_PORT_SELFTEST=1} のときだけ動く(普段は何もしない)。
 * {@code runGameTestServer} で起動すると、サーバーの起動後に attachment(Entity の保存と読み戻し・チャンク・Level)・アイテムのデータ・
 * 通信の書いて読み戻し・SCALE を確かめ、止まるときに tick のイベントの数を確かめて、ログに {@code [port-selftest] PASS/FAIL} を出す。
 */
public final class PortSelfTest {

    private PortSelfTest() {
    }

    public static boolean enabled() {
        return "1".equals(System.getenv("OV_PORT_SELFTEST"));
    }

    private static final DeferredRegister<AttachmentType<?>> ATT =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, PortConst.MODID);
    private static final DeferredHolder<AttachmentType<?>, AttachmentType<Integer>> T_INT =
            ATT.register("port_selftest_int", () -> AttachmentType.builder(() -> 0).serialize(Codec.INT).build());
    private static final DeferredHolder<AttachmentType<?>, AttachmentType<Integer>> T_TRANSIENT =
            ATT.register("port_selftest_transient", () -> AttachmentType.builder(() -> 7).build());
    private static final DeferredRegister.DataComponents COMP = DeferredRegister.createDataComponents(PortConst.MODID);
    private static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> C_INT =
            COMP.registerComponentType("port_selftest", b -> b.persistent(Codec.INT).networkSynchronized(ByteBufCodecs.VAR_INT));

    record Ping(int a, String s, BlockPos p) implements CustomPacketPayload {
        static final Type<Ping> TYPE = new Type<>(PortConst.id("port_selftest_ping"));
        static final StreamCodec<FriendlyByteBuf, Ping> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Ping::a, ByteBufCodecs.STRING_UTF8, Ping::s, ByteBufCodecs.BLOCK_POS, Ping::p, Ping::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private static final List<String> FAILS = new ArrayList<>();
    private static final AtomicInteger SERVER_POST = new AtomicInteger();
    private static final AtomicInteger LEVEL_POST = new AtomicInteger();
    private static final AtomicInteger PIG_PRE = new AtomicInteger();
    private static final AtomicInteger PIG_POST = new AtomicInteger();
    private static Pig pig;

    static void init(IEventBus modBus) {
        if (!enabled()) {
            return;
        }
        ATT.register(modBus);
        COMP.register(modBus);
        modBus.addListener((RegisterPayloadHandlersEvent e) -> e.registrar("selftest").playToClient(Ping.TYPE, Ping.STREAM_CODEC, (p, c) -> {
        }));
        MinecraftForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> SERVER_POST.incrementAndGet());
        MinecraftForge.EVENT_BUS.addListener((LevelTickEvent.Post e) -> LEVEL_POST.incrementAndGet());
        MinecraftForge.EVENT_BUS.addListener((EntityTickEvent.Pre e) -> {
            if (e.getEntity() == pig) {
                PIG_PRE.incrementAndGet();
            }
        });
        MinecraftForge.EVENT_BUS.addListener((EntityTickEvent.Post e) -> {
            if (e.getEntity() == pig) {
                PIG_POST.incrementAndGet();
            }
        });
        MinecraftForge.EVENT_BUS.addListener((ServerStartedEvent e) -> started(e.getServer()));
        MinecraftForge.EVENT_BUS.addListener((ServerStoppingEvent e) -> stopping());
        PortConst.LOGGER.info("[port-selftest] enabled");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            FAILS.add(what);
        }
        PortConst.LOGGER.info("[port-selftest] {} {}", ok ? "ok  " : "FAIL", what);
    }

    private static void started(MinecraftServer server) {
        try {
            ServerLevel level = server.overworld();
            // 1. Entity の attachment: 保存して別の個体に読み戻す
            Pig a = EntityType.PIG.create(level);
            OvAttach.setData(a, T_INT, 42);
            OvAttach.setData(a, T_TRANSIENT, 9);
            CompoundTag tag = a.saveWithoutId(new CompoundTag());
            Pig b = EntityType.PIG.create(level);
            b.load(tag);
            check(OvAttach.getData(b, T_INT) == 42, "entity attachment survives save/load (got " + OvAttach.getData(b, T_INT) + ")");
            check(OvAttach.getData(b, T_TRANSIENT) == 7, "non-serialized attachment resets to default");
            check(!OvAttach.hasData(EntityType.PIG.create(level), T_INT), "fresh entity has no data");
            // 取り除いた後も読める(Forge が Capability を無効にする)
            a.discard();
            a.remove(net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
            check(OvAttach.getData(a, T_INT) == 42, "attachment readable after entity removal");
            // 2. チャンク: 書くと保存が要る印が付く
            LevelChunk chunk = level.getChunkAt(BlockPos.ZERO);
            chunk.setUnsaved(false);
            OvAttach.setData(chunk, T_INT, 5);
            check(chunk.isUnsaved() && OvAttach.getData(chunk, T_INT) == 5, "chunk attachment marks chunk dirty");
            // 3. Level
            OvAttach.setData(level, T_INT, 11);
            check(OvAttach.getData(level, T_INT) == 11, "level attachment");
            // 4. アイテムのデータ: NBT の鍵と保存
            ItemStack s = new ItemStack(Items.STICK);
            OvItemData.set(s, C_INT, 3);
            check(s.getTag() != null && s.getTag().contains("overgrown_vegetation:port_selftest"), "item data NBT key = full id");
            ItemStack s2 = ItemStack.of(s.save(new CompoundTag()));
            check(Integer.valueOf(3).equals(OvItemData.get(s2, C_INT)), "item data survives save/load");
            OvItemData.set(s2, C_INT, null);
            check(s2.getTag() == null, "removing last item data clears tag");
            // 5. 通信: 書いて読み戻す
            Ping back = OvChannel.loopback(new Ping(300, "苔", new BlockPos(1, -2, 3)), server.registryAccess());
            check(back.equals(new Ping(300, "苔", new BlockPos(1, -2, 3))), "payload loopback " + back);
            check(OvChannel.protocolVersion().contains("selftest"), "protocol version " + OvChannel.protocolVersion());
            // 6. 体格: SCALE を 2 にした豚を置く(1 tick 後に当たり判定が 2 倍になるはず)
            pig = EntityType.PIG.create(level);
            pig.moveTo(level.getSharedSpawnPos().getX() + 0.5, level.getSharedSpawnPos().getY() + 1, level.getSharedSpawnPos().getZ() + 0.5);
            pig.setNoAi(true);
            pig.getAttribute(PortAttributes.SCALE.get()).setBaseValue(2.0D);
            level.addFreshEntity(pig);
            check(Math.abs(pig.getScale() - 2.0F) < 1e-4, "getScale uses SCALE attribute");
            // GameTest のサーバーは試験が無いと 1 tick で止まるので、ここで 3 回 tick させる(mixin を通る道)
            for (int i = 0; i < 3; i++) {
                level.tickNonPassenger(pig);
            }
        } catch (Throwable t) {
            check(false, "exception " + t);
            PortConst.LOGGER.error("[port-selftest]", t);
        }
    }

    private static void stopping() {
        check(SERVER_POST.get() > 0, "ServerTickEvent.Post fired " + SERVER_POST.get());
        check(LEVEL_POST.get() > 0, "LevelTickEvent.Post fired " + LEVEL_POST.get());
        check(PIG_PRE.get() > 0 && PIG_POST.get() > 0, "EntityTickEvent pre/post fired " + PIG_PRE.get() + "/" + PIG_POST.get());
        if (pig != null) {
            check(pig.getBbHeight() > 1.5F, "scaled pig hitbox height " + pig.getBbHeight() + " (base 0.9)");
        }
        PortConst.LOGGER.info("[port-selftest] {}", FAILS.isEmpty() ? "PASS" : "FAIL " + FAILS);
    }
}
