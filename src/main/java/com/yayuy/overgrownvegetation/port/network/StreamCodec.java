package com.yayuy.overgrownvegetation.port.network;

import java.util.function.Function;

/**
 * 【1.20.1 だけの物・互換の層】バニラ 1.20.5 の {@code net.minecraft.network.codec.StreamCodec} と同じ名前・同じ使い方の物(本流が使う分)。
 * バッファの型 B は ByteBuf・FriendlyByteBuf・{@link RegistryFriendlyByteBuf} のどれでもよい(送るときは必ず RegistryFriendlyByteBuf を渡す)。
 */
public interface StreamCodec<B, V> extends StreamDecoder<B, V>, StreamEncoder<B, V> {

    static <B, V> StreamCodec<B, V> of(StreamEncoder<B, V> encoder, StreamDecoder<B, V> decoder) {
        return new StreamCodec<>() {
            @Override
            public V decode(B buffer) {
                return decoder.decode(buffer);
            }

            @Override
            public void encode(B buffer, V value) {
                encoder.encode(buffer, value);
            }
        };
    }

    static <B, V> StreamCodec<B, V> ofMember(StreamMemberEncoder<B, V> encoder, StreamDecoder<B, V> decoder) {
        return of((buf, v) -> encoder.encode(v, buf), decoder);
    }

    static <B, V> StreamCodec<B, V> unit(V instance) {
        return of((buf, v) -> {
            if (!v.equals(instance)) {
                throw new IllegalStateException("Can't encode '" + v + "', expected '" + instance + "'");
            }
        }, buf -> instance);
    }

    default <O> StreamCodec<B, O> map(Function<? super V, ? extends O> to, Function<? super O, ? extends V> from) {
        StreamCodec<B, V> self = this;
        return of((buf, o) -> self.encode(buf, from.apply(o)), buf -> to.apply(self.decode(buf)));
    }

    @SuppressWarnings("unchecked")
    default <S extends B> StreamCodec<S, V> cast() {
        return (StreamCodec<S, V>) this;
    }

    static <B, C, T1> StreamCodec<B, C> composite(
            StreamCodec<? super B, T1> c1, Function<C, T1> g1,
            Function<T1, C> make) {
        return of((buf, v) -> c1.encode(buf, g1.apply(v)), buf -> make.apply(c1.decode(buf)));
    }

    static <B, C, T1, T2> StreamCodec<B, C> composite(
            StreamCodec<? super B, T1> c1, Function<C, T1> g1,
            StreamCodec<? super B, T2> c2, Function<C, T2> g2,
            java.util.function.BiFunction<T1, T2, C> make) {
        return of((buf, v) -> {
            c1.encode(buf, g1.apply(v));
            c2.encode(buf, g2.apply(v));
        }, buf -> {
            T1 a = c1.decode(buf);
            T2 b = c2.decode(buf);
            return make.apply(a, b);
        });
    }

    static <B, C, T1, T2, T3> StreamCodec<B, C> composite(
            StreamCodec<? super B, T1> c1, Function<C, T1> g1,
            StreamCodec<? super B, T2> c2, Function<C, T2> g2,
            StreamCodec<? super B, T3> c3, Function<C, T3> g3,
            Function3<T1, T2, T3, C> make) {
        return of((buf, v) -> {
            c1.encode(buf, g1.apply(v));
            c2.encode(buf, g2.apply(v));
            c3.encode(buf, g3.apply(v));
        }, buf -> {
            T1 a = c1.decode(buf);
            T2 b = c2.decode(buf);
            T3 c = c3.decode(buf);
            return make.apply(a, b, c);
        });
    }

    static <B, C, T1, T2, T3, T4> StreamCodec<B, C> composite(
            StreamCodec<? super B, T1> c1, Function<C, T1> g1,
            StreamCodec<? super B, T2> c2, Function<C, T2> g2,
            StreamCodec<? super B, T3> c3, Function<C, T3> g3,
            StreamCodec<? super B, T4> c4, Function<C, T4> g4,
            Function4<T1, T2, T3, T4, C> make) {
        return of((buf, v) -> {
            c1.encode(buf, g1.apply(v));
            c2.encode(buf, g2.apply(v));
            c3.encode(buf, g3.apply(v));
            c4.encode(buf, g4.apply(v));
        }, buf -> {
            T1 a = c1.decode(buf);
            T2 b = c2.decode(buf);
            T3 c = c3.decode(buf);
            T4 d = c4.decode(buf);
            return make.apply(a, b, c, d);
        });
    }

    @FunctionalInterface
    interface Function3<A, B, C, R> {
        R apply(A a, B b, C c);
    }

    @FunctionalInterface
    interface Function4<A, B, C, D, R> {
        R apply(A a, B b, C c, D d);
    }
}
