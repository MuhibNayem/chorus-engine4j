package com.chorus.engine.core.result;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Railway-oriented programming result type.
 * Every fallible operation returns {@code Result<T, E>} instead of throwing.
 *
 * <p>Immutable, null-safe, and thread-safe. No checked exceptions.
 *
 * @param <T> the success value type
 * @param <E> the error type
 */
public sealed interface Result<T, E> {

    boolean isOk();
    boolean isErr();

    @NonNull T unwrap();
    @NonNull T unwrapOr(@NonNull T other);
    @NonNull T unwrapOrElse(@NonNull Function<E, T> fallback);
    @NonNull E unwrapErr();

    <U> @NonNull Result<U, E> map(@NonNull Function<T, U> mapper);
    <U> @NonNull Result<U, E> flatMap(@NonNull Function<T, Result<U, E>> mapper);
    <F> @NonNull Result<T, F> mapErr(@NonNull Function<E, F> mapper);

    @NonNull Result<T, E> ifOk(@NonNull Consumer<T> action);
    @NonNull Result<T, E> ifErr(@NonNull Consumer<E> action);
    @NonNull Result<T, E> filter(@NonNull Predicate<T> predicate, @NonNull Supplier<E> errorSupplier);

    /** Returns the success value if Ok, otherwise null. */
    @Nullable T toValue();

    /** Returns the error if Err, otherwise null. */
    @Nullable E toError();

    static <T, E> @NonNull Result<T, E> ok(@NonNull T value) {
        return new Ok<>(Objects.requireNonNull(value, "Ok value cannot be null"));
    }

    static <T, E> @NonNull Result<T, E> err(@NonNull E error) {
        return new Err<>(Objects.requireNonNull(error, "Err error cannot be null"));
    }

    static <T, E> @NonNull Result<T, E> ofNullable(@Nullable T value, @NonNull Supplier<E> errorSupplier) {
        return value != null ? ok(value) : err(errorSupplier.get());
    }

    static <T> @NonNull Result<T, Throwable> catchException(@NonNull Supplier<T> supplier) {
        try {
            return ok(supplier.get());
        } catch (Exception e) {
            return err(e);
        }
    }

    record Ok<T, E>(@NonNull T value) implements Result<T, E> {
        @Override public boolean isOk() { return true; }
        @Override public boolean isErr() { return false; }
        @Override public @NonNull T unwrap() { return value; }
        @Override public @NonNull T unwrapOr(@NonNull T other) { return value; }
        @Override public @NonNull T unwrapOrElse(@NonNull Function<E, T> fallback) { return value; }
        @Override public @NonNull E unwrapErr() { throw new IllegalStateException("Called unwrapErr on Ok"); }
        @Override public <U> @NonNull Result<U, E> map(@NonNull Function<T, U> mapper) { return Result.ok(mapper.apply(value)); }
        @Override public <U> @NonNull Result<U, E> flatMap(@NonNull Function<T, Result<U, E>> mapper) { return mapper.apply(value); }
        @Override public <F> @NonNull Result<T, F> mapErr(@NonNull Function<E, F> mapper) { return new Result.Ok<>(value); }
        @Override public @NonNull Result<T, E> ifOk(@NonNull Consumer<T> action) { action.accept(value); return this; }
        @Override public @NonNull Result<T, E> ifErr(@NonNull Consumer<E> action) { return this; }
        @Override public @NonNull Result<T, E> filter(@NonNull Predicate<T> predicate, @NonNull Supplier<E> errorSupplier) {
            return predicate.test(value) ? this : Result.err(errorSupplier.get());
        }
        @Override public @Nullable T toValue() { return value; }
        @Override public @Nullable E toError() { return null; }
    }

    record Err<T, E>(@NonNull E error) implements Result<T, E> {
        @Override public boolean isOk() { return false; }
        @Override public boolean isErr() { return true; }
        @Override public @NonNull T unwrap() { throw new IllegalStateException("Called unwrap on Err: " + error); }
        @Override public @NonNull T unwrapOr(@NonNull T other) { return other; }
        @Override public @NonNull T unwrapOrElse(@NonNull Function<E, T> fallback) { return fallback.apply(error); }
        @Override public @NonNull E unwrapErr() { return error; }
        @Override public <U> @NonNull Result<U, E> map(@NonNull Function<T, U> mapper) { return Result.err(error); }
        @Override public <U> @NonNull Result<U, E> flatMap(@NonNull Function<T, Result<U, E>> mapper) { return Result.err(error); }
        @Override public <F> @NonNull Result<T, F> mapErr(@NonNull Function<E, F> mapper) { return Result.err(mapper.apply(error)); }
        @Override public @NonNull Result<T, E> ifOk(@NonNull Consumer<T> action) { return this; }
        @Override public @NonNull Result<T, E> ifErr(@NonNull Consumer<E> action) { action.accept(error); return this; }
        @Override public @NonNull Result<T, E> filter(@NonNull Predicate<T> predicate, @NonNull Supplier<E> errorSupplier) { return this; }
        @Override public @Nullable T toValue() { return null; }
        @Override public @Nullable E toError() { return error; }
    }
}
