package com.yayuy.overgrownvegetation.port.attachment;

import com.yayuy.overgrownvegetation.port.PortConst;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.common.capabilities.CapabilityDispatcher;
import net.minecraftforge.common.capabilities.CapabilityProvider;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 【1.20.1 だけの物・互換の層】NeoForge の {@code IAttachmentHolder} の読み書き({@code holder.getData(TYPE)} など)を、static で行う所。
 *
 * <p>本流の {@code x.getData(ModAttachments.T)} は {@code OvAttach.getData(x, ModAttachments.T)} に書き換える
 * (port-tools/port-rewrite.pl が機械的にやる。{@code setData}・{@code hasData}・{@code removeData}・{@code getExistingData} も同じ)。
 *
 * <p>持ち主になれる物: {@link Entity}(全部)・{@link LevelChunk}(と {@link ImposterProtoChunk} の中身)・{@code Level}・{@code BlockEntity}。
 * 生成中の {@code ProtoChunk} には Capability が無いので、その間の読み書きは保存されない(既定値が返り、書いた物は捨てる。ログに 1 回出す)。
 * チャンク・ブロックエンティティへの {@code setData}・{@code removeData} は「保存が要る」にする(Forge はしてくれない)。
 */
public final class OvAttach {

    private OvAttach() {
    }

    private static final Set<Class<?>> WARNED = ConcurrentHashMap.newKeySet();
    @Nullable
    private static final Method GET_CAPABILITIES = findGetCapabilities();

    // ── 読み書き(型は DeferredHolder でも AttachmentType でもよい) ──

    public static <T> T getData(Object holder, Supplier<AttachmentType<T>> type) {
        return getData(holder, type.get());
    }

    public static <T> T getData(Object holder, AttachmentType<T> type) {
        return store(holder).getOrCreate(type);
    }

    public static <T> Optional<T> getExistingData(Object holder, Supplier<AttachmentType<T>> type) {
        return getExistingData(holder, type.get());
    }

    public static <T> Optional<T> getExistingData(Object holder, AttachmentType<T> type) {
        return Optional.ofNullable(store(holder).getExisting(type));
    }

    @Nullable
    public static <T> T getExistingDataOrNull(Object holder, Supplier<AttachmentType<T>> type) {
        return store(holder).getExisting(type.get());
    }

    public static boolean hasData(Object holder, Supplier<? extends AttachmentType<?>> type) {
        return hasData(holder, type.get());
    }

    public static boolean hasData(Object holder, AttachmentType<?> type) {
        return store(holder).has(type);
    }

    @Nullable
    public static <T> T setData(Object holder, Supplier<AttachmentType<T>> type, T value) {
        return setData(holder, type.get(), value);
    }

    @Nullable
    public static <T> T setData(Object holder, AttachmentType<T> type, T value) {
        T prev = store(holder).set(type, value);
        markDirty(holder);
        return prev;
    }

    @Nullable
    public static <T> T removeData(Object holder, Supplier<AttachmentType<T>> type) {
        return removeData(holder, type.get());
    }

    @Nullable
    public static <T> T removeData(Object holder, AttachmentType<T> type) {
        T prev = store(holder).remove(type);
        if (prev != null) {
            markDirty(holder);
        }
        return prev;
    }

    // ── 持ち主から入れ物を引く ──

    static AttachmentStore store(Object holder) {
        Object h = holder instanceof ImposterProtoChunk imp ? imp.getWrapped() : holder;
        if (h instanceof ICapabilityProvider p) {
            AttachmentStore s = p.getCapability(AttachmentStore.CAPABILITY).orElse(null);
            if (s != null) {
                return s;
            }
            // 取り除かれた Entity は Forge が Capability を無効にする(Entity#remove)。中身は残っているので直接引く
            s = fromDispatcher(h);
            if (s != null) {
                return s;
            }
        }
        if (WARNED.add(h.getClass())) {
            PortConst.LOGGER.warn("[port] attachment の持ち主になれない物 {}(読み書きは保存されない)", h.getClass().getName());
        }
        return new AttachmentStore();
    }

    @Nullable
    private static AttachmentStore fromDispatcher(Object h) {
        if (GET_CAPABILITIES == null || !(h instanceof CapabilityProvider<?>)) {
            return null;
        }
        try {
            CapabilityDispatcher d = (CapabilityDispatcher) GET_CAPABILITIES.invoke(h);
            return d == null ? null : d.getCapability(AttachmentStore.CAPABILITY).orElse(null);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    @Nullable
    private static Method findGetCapabilities() {
        try {
            Method m = CapabilityProvider.class.getDeclaredMethod("getCapabilities");
            m.setAccessible(true);
            return m;
        } catch (ReflectiveOperationException | RuntimeException e) {
            PortConst.LOGGER.warn("[port] CapabilityProvider#getCapabilities が無い({})。取り除かれた Entity の attachment は読めない", e.toString());
            return null;
        }
    }

    private static void markDirty(Object holder) {
        Object h = holder instanceof ImposterProtoChunk imp ? imp.getWrapped() : holder;
        if (h instanceof LevelChunk chunk) {
            chunk.setUnsaved(true);
        } else if (h instanceof net.minecraft.world.level.block.entity.BlockEntity be) {
            be.setChanged();
        }
    }
}
