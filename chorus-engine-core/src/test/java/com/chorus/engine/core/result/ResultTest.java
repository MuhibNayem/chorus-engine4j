package com.chorus.engine.core.result;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class ResultTest {

    @Test
    void ok_isOk_and_unwraps() {
        Result<String, String> r = Result.ok("hello");
        assertThat(r.isOk()).isTrue();
        assertThat(r.isErr()).isFalse();
        assertThat(r.unwrap()).isEqualTo("hello");
        assertThat(r.unwrapOr("fallback")).isEqualTo("hello");
        assertThat(r.toValue()).isEqualTo("hello");
        assertThat(r.toError()).isNull();
    }

    @Test
    void err_isErr_and_unwrapsErr() {
        Result<String, String> r = Result.err("failure");
        assertThat(r.isOk()).isFalse();
        assertThat(r.isErr()).isTrue();
        assertThat(r.unwrapErr()).isEqualTo("failure");
        assertThat(r.unwrapOr("fallback")).isEqualTo("fallback");
        assertThat(r.unwrapOrElse(e -> "recovered:" + e)).isEqualTo("recovered:failure");
        assertThat(r.toValue()).isNull();
        assertThat(r.toError()).isEqualTo("failure");
    }

    @Test
    void ok_map_transforms() {
        Result<Integer, String> r = Result.<Integer, String>ok(5).map(x -> x * 2);
        assertThat(r.unwrap()).isEqualTo(10);
    }

    @Test
    void err_map_passes_through() {
        Result<Integer, String> r = Result.<Integer, String>err("fail").map(x -> x * 2);
        assertThat(r.isErr()).isTrue();
    }

    @Test
    void ok_flatMap_chains() {
        Result<String, String> r = Result.<String, String>ok("5")
            .<String>flatMap(s -> {
                try {
                    return Result.<String, String>ok(String.valueOf(Integer.parseInt(s) * 3));
                } catch (NumberFormatException e) {
                    return Result.<String, String>err("parse error");
                }
            });
        assertThat(r.unwrap()).isEqualTo("15");
    }

    @Test
    void mapErr_transforms_error() {
        Result<String, RuntimeException> r = Result.<String, String>err("fail").mapErr(RuntimeException::new);
        assertThat(r.unwrapErr()).isInstanceOf(RuntimeException.class).hasMessage("fail");
    }

    @Test
    void filter_blocks_on_predicate() {
        Result<Integer, String> r = Result.<Integer, String>ok(5).filter(x -> x > 3, () -> "too small");
        assertThat(r.unwrap()).isEqualTo(5);

        Result<Integer, String> r2 = Result.<Integer, String>ok(2).filter(x -> x > 3, () -> "too small");
        assertThat(r2.isErr()).isTrue();
        assertThat(r2.unwrapErr()).isEqualTo("too small");
    }

    @Test
    void catchException_catches_and_wraps() {
        Result<Integer, Throwable> r = Result.catchException(() -> Integer.parseInt("not-a-number"));
        assertThat(r.isErr()).isTrue();
        assertThat(r.unwrapErr()).isInstanceOf(NumberFormatException.class);
    }

    @Test
    void catchException_success() {
        Result<Integer, Throwable> r = Result.catchException(() -> Integer.parseInt("42"));
        assertThat(r.unwrap()).isEqualTo(42);
    }

    @Test
    void null_value_rejected() {
        assertThatThrownBy(() -> Result.ok(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("Ok value cannot be null");
    }

    @Test
    void callbacks_fire_correctly() {
        StringBuilder sb = new StringBuilder();
        Result.ok("x").ifOk(v -> sb.append("ok:").append(v)).ifErr(e -> sb.append("err"));
        assertThat(sb.toString()).isEqualTo("ok:x");

        sb.setLength(0);
        Result.err("y").ifOk(v -> sb.append("ok")).ifErr(e -> sb.append("err:").append(e));
        assertThat(sb.toString()).isEqualTo("err:y");
    }

    @Test
    void ok_unwrapOrElse_returnsValue() {
        Result<String, String> r = Result.ok("hello");
        assertThat(r.unwrapOrElse(e -> "fallback")).isEqualTo("hello");
    }

    @Test
    void err_flatMap_passesThrough() {
        Result<Integer, String> r = Result.<Integer, String>err("fail")
                .flatMap(x -> Result.ok(x * 2));
        assertThat(r.isErr()).isTrue();
        assertThat(r.unwrapErr()).isEqualTo("fail");
    }

    @Test
    void ok_mapErr_passesThrough() {
        Result<String, String> r = Result.<String, String>ok("ok").mapErr(e -> "mapped");
        assertThat(r.isOk()).isTrue();
        assertThat(r.unwrap()).isEqualTo("ok");
    }

    @Test
    void err_filter_passesThrough() {
        Result<Integer, String> r = Result.<Integer, String>err("fail")
                .filter(x -> x > 0, () -> "too small");
        assertThat(r.isErr()).isTrue();
        assertThat(r.unwrapErr()).isEqualTo("fail");
    }

    @Test
    void ofNullable_withNonNull_returnsOk() {
        Result<String, String> r = Result.ofNullable("value", () -> "missing");
        assertThat(r.isOk()).isTrue();
        assertThat(r.unwrap()).isEqualTo("value");
    }

    @Test
    void ofNullable_withNull_returnsErr() {
        Result<String, String> r = Result.ofNullable(null, () -> "missing");
        assertThat(r.isErr()).isTrue();
        assertThat(r.unwrapErr()).isEqualTo("missing");
    }

    @Test
    void err_unwrap_throws() {
        Result<String, String> r = Result.err("boom");
        assertThatThrownBy(r::unwrap)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("boom");
    }

    @Test
    void ok_unwrapErr_throws() {
        Result<String, String> r = Result.ok("value");
        assertThatThrownBy(r::unwrapErr)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unwrapErr on Ok");
    }
}
