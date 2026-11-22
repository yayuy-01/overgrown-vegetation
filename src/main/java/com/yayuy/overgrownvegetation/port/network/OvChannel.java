package com.yayuy.overgrownvegetation.port.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.util.thread.EffectiveSide;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 【1.20.1 だけの物・互換の層】本流の payload を全部 1 本の SimpleChannel({@code overgrown_vegetation:main})で運ぶ。
 *
 * <ul>
 *   <li>SimpleChannel に載せる型は {@link Packet} 1 つだけ(番号 0)。中で payload の id(ResourceLocation)を先に書き、
 *       id から登録を引いて中身を読む。だから登録の順番や組の数に番号が左右されない。</li>
 *   <li>通信路の版 = 全部の {@code registrar("版")} を並べ替えてつないだ物。どれか 1 つが違えば接続のときにはじかれる。</li>
 *   <li>受け手はメインのスレッドで呼ぶ(NeoForge 1.21.1 の既定と同じ)。中身を読むのは受け取ったスレッド。</li>
 * </ul>
 */
public final class OvChannel {

    private OvChannel() {
    }

    enum Flow { TO_CLIENT, TO_SERVER, BOTH }

    private record Registration<T extends CustomPacketPayload>(
            CustomPacketPayload.Type<T> type, StreamCodec<? super RegistryFriendlyByteBuf, T> codec, IPayloadHandler<T> handler, Flow flow) {
    }

    private static final Map<ResourceLocation, Registration<?>> BY_ID = new ConcurrentHashMap<>();
    private static final TreeSet<String> VERSIONS = new TreeSet<>();
    private static volatile String protocol = "none";

    public static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
            .named(OvergrownVegetation.id("main"))
            .networkProtocolVersion(() -> protocol)
            .clientAcceptedVersions(v -> v.equals(protocol))
            .serverAcceptedVersions(v -> v.equals(protocol))
            .simpleChannel();

    static {
        CHANNEL.messageBuilder(Packet.class, 0)
                .encoder(Packet::write)
                .decoder(Packet::read)
                .consumerMainThread(OvChannel::receive)
                .add();
    }

    /** {@code PortSetup} が起動のときに呼ぶ(クラスを読み込ませるだけ)。 */
    public static void init() {
    }

    static synchronized void addVersion(String v) {
        VERSIONS.add(v);
        protocol = String.join("|", VERSIONS);
    }

    static <T extends CustomPacketPayload> void register(CustomPacketPayload.Type<T> type, StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
                                                         IPayloadHandler<T> handler, Flow flow) {
        Registration<?> prev = BY_ID.putIfAbsent(type.id(), new Registration<>(type, codec, handler, flow));
        if (prev != null) {
            throw new IllegalStateException("payload registered twice: " + type.id());
        }
    }

    // ── 送る ──

    static Packet wrap(CustomPacketPayload payload) {
        return new Packet(payload, null, null);
    }

    static void sendToServer(CustomPacketPayload payload) {
        CHANNEL.sendToServer(wrap(payload));
    }

    // ── 運ぶ物 ──

    /** 送る側は payload、受けた側は id と生のバイト(読むのは受け手の所。どちら向きかが分かってから登録簿を選ぶため)。 */
    record Packet(CustomPacketPayload payload, ResourceLocation id, ByteBuf raw) {

        void write(FriendlyByteBuf buf) {
            ResourceLocation pid = payload.type().id();
            Registration<?> reg = BY_ID.get(pid);
            if (reg == null) {
                throw new IllegalStateException("payload not registered: " + pid);
            }
            buf.writeResourceLocation(pid);
            encode(reg, new RegistryFriendlyByteBuf(buf, OvChannel::senderRegistryAccess), payload);
        }

        @SuppressWarnings("unchecked")
        private static <T extends CustomPacketPayload> void encode(Registration<T> reg, RegistryFriendlyByteBuf buf, CustomPacketPayload p) {
            reg.codec().encode(buf, (T) p);
        }

        static Packet read(FriendlyByteBuf buf) {
            ResourceLocation pid = buf.readResourceLocation();
            ByteBuf raw = Unpooled.buffer(buf.readableBytes());
            buf.readBytes(raw);
            return new Packet(null, pid, raw);
        }
    }

    private static RegistryAccess senderRegistryAccess() {
        if (EffectiveSide.get().isServer()) {
            var server = ServerLifecycleHooks.getCurrentServer();
            if (server != null) {
                return server.registryAccess();
            }
        }
        return DistExecutor.unsafeCallWhenOn(Dist.CLIENT, () -> ClientAccess::registryAccess);
    }

    // ── 受ける(メインのスレッド) ──

    private static void receive(Packet packet, Supplier<NetworkEvent.Context> ctxSup) {
        NetworkEvent.Context ctx = ctxSup.get();
        Registration<?> reg = BY_ID.get(packet.id());
        boolean toClient = ctx.getDirection() == NetworkDirection.PLAY_TO_CLIENT;
        if (reg == null) {
            OvergrownVegetation.LOGGER.warn("[port] 知らない payload {} を捨てた", packet.id());
            return;
        }
        if ((toClient && reg.flow() == Flow.TO_SERVER) || (!toClient && reg.flow() == Flow.TO_CLIENT)) {
            OvergrownVegetation.LOGGER.warn("[port] 向きの違う payload {} を捨てた", packet.id());
            return;
        }
        Supplier<RegistryAccess> access = toClient
                ? () -> DistExecutor.unsafeCallWhenOn(Dist.CLIENT, () -> ClientAccess::registryAccess)
                : () -> ctx.getSender().server.registryAccess();
        try {
            dispatch(reg, new RegistryFriendlyByteBuf(packet.raw(), access), ctx, toClient);
        } catch (RuntimeException e) {
            OvergrownVegetation.LOGGER.error("[port] payload {} の処理で落ちた", packet.id(), e);
        } finally {
            packet.raw().release();
        }
    }

    private static <T extends CustomPacketPayload> void dispatch(Registration<T> reg, RegistryFriendlyByteBuf buf, NetworkEvent.Context ctx, boolean toClient) {
        T payload = reg.codec().decode(buf);
        reg.handler().handle(payload, new Context(ctx, toClient));
    }

    private record Context(NetworkEvent.Context ctx, boolean toClient) implements IPayloadContext {
        @Override
        public Player player() {
            if (toClient) {
                return DistExecutor.unsafeCallWhenOn(Dist.CLIENT, () -> ClientAccess::player);
            }
            return ctx.getSender();
        }

        @Override
        public CompletableFuture<Void> enqueueWork(Runnable task) {
            // consumerMainThread なので、もうメインのスレッドにいる
            task.run();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void reply(CustomPacketPayload payload) {
            if (toClient) {
                sendToServer(payload);
            } else {
                ServerPlayer sp = ctx.getSender();
                if (sp != null) {
                    PacketDistributor.sendToPlayer(sp, payload);
                }
            }
        }

        @Override
        public void disconnect(Component reason) {
            ctx.getNetworkManager().disconnect(reason);
        }
    }
}
