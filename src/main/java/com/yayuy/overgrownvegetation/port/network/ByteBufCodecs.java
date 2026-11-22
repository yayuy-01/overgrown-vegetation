package com.yayuy.overgrownvegetation.port.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;

/**
 * 【1.20.1 だけの物・互換の層】バニラ 1.20.5 の {@code net.minecraft.network.codec.ByteBufCodecs} のうち、本流が使う物
 * (と、1.20.1 に無い {@code BlockPos.STREAM_CODEC} などの代わり)。足りない物は F の窓口へ(足すだけで、変えない)。
 */
public final class ByteBufCodecs {

    private ByteBufCodecs() {
    }

    private static FriendlyByteBuf f(ByteBuf b) {
        return b instanceof FriendlyByteBuf fb ? fb : new FriendlyByteBuf(b);
    }

    public static final StreamCodec<ByteBuf, Boolean> BOOL = StreamCodec.of(ByteBuf::writeBoolean, ByteBuf::readBoolean);
    public static final StreamCodec<ByteBuf, Byte> BYTE = StreamCodec.of((b, v) -> b.writeByte(v), ByteBuf::readByte);
    public static final StreamCodec<ByteBuf, Short> SHORT = StreamCodec.of((b, v) -> b.writeShort(v), ByteBuf::readShort);
    public static final StreamCodec<ByteBuf, Integer> INT = StreamCodec.of(ByteBuf::writeInt, ByteBuf::readInt);
    public static final StreamCodec<ByteBuf, Integer> VAR_INT = StreamCodec.of((b, v) -> f(b).writeVarInt(v), b -> f(b).readVarInt());
    public static final StreamCodec<ByteBuf, Long> LONG = StreamCodec.of(ByteBuf::writeLong, ByteBuf::readLong);
    public static final StreamCodec<ByteBuf, Long> VAR_LONG = StreamCodec.of((b, v) -> f(b).writeVarLong(v), b -> f(b).readVarLong());
    public static final StreamCodec<ByteBuf, Float> FLOAT = StreamCodec.of(ByteBuf::writeFloat, ByteBuf::readFloat);
    public static final StreamCodec<ByteBuf, Double> DOUBLE = StreamCodec.of(ByteBuf::writeDouble, ByteBuf::readDouble);
    public static final StreamCodec<ByteBuf, String> STRING_UTF8 = StreamCodec.of((b, v) -> f(b).writeUtf(v), b -> f(b).readUtf());
    public static final StreamCodec<ByteBuf, CompoundTag> COMPOUND_TAG = StreamCodec.of((b, v) -> f(b).writeNbt(v), b -> f(b).readNbt());
    public static final StreamCodec<ByteBuf, ResourceLocation> RESOURCE_LOCATION = StreamCodec.of((b, v) -> f(b).writeResourceLocation(v), b -> f(b).readResourceLocation());
    /** 1.20.5 の {@code BlockPos.STREAM_CODEC} の代わり。 */
    public static final StreamCodec<ByteBuf, BlockPos> BLOCK_POS = StreamCodec.of((b, v) -> f(b).writeBlockPos(v), b -> f(b).readBlockPos());
    /** 1.20.5 の {@code GlobalPos.STREAM_CODEC} の代わり。 */
    public static final StreamCodec<ByteBuf, GlobalPos> GLOBAL_POS = StreamCodec.of((b, v) -> f(b).writeGlobalPos(v), b -> f(b).readGlobalPos());

    /**
     * 登録簿の中の物を数の id で送る。バニラの登録簿(BuiltInRegistries にある物)はそちらを使い、無い物だけ registryAccess から引く。
     */
    @SuppressWarnings("unchecked")
    public static <T> StreamCodec<RegistryFriendlyByteBuf, T> registry(ResourceKey<? extends Registry<T>> key) {
        Registry<T> builtIn = (Registry<T>) BuiltInRegistries.REGISTRY.get(key.location());
        return StreamCodec.of(
                (buf, v) -> buf.writeVarInt(reg(builtIn, buf, key).getId(v)),
                buf -> reg(builtIn, buf, key).byIdOrThrow(buf.readVarInt()));
    }

    private static <T> Registry<T> reg(Registry<T> builtIn, RegistryFriendlyByteBuf buf, ResourceKey<? extends Registry<T>> key) {
        return builtIn != null ? builtIn : buf.registryAccess().registryOrThrow(key);
    }
}
