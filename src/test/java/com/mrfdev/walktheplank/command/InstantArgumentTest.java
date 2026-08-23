package com.mrfdev.walktheplank.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

final class InstantArgumentTest {
    private final InstantArgument argument = new InstantArgument();

    @Test
    void parsesQuotedRfc3339AndNormalizesToMilliseconds() throws Exception {
        assertEquals(
                Instant.parse("2026-08-01T00:00:00Z"),
                parse("\"2026-08-01T02:00:00+02:00\""));
        assertEquals(
                Instant.parse("2026-08-01T00:00:00.123Z"),
                parse("\"2026-08-01T00:00:00.123999999Z\""));
        assertEquals(
                Instant.parse("2026-08-01T00:00:00.123Z"),
                parse("\"2026-08-01t00:00:00.123999999987z\""));
        assertEquals(
                StringArgumentType.StringType.QUOTABLE_PHRASE,
                ((StringArgumentType) argument.getNativeType()).getType());
    }

    @Test
    void rejectsMalformedAndOutOfMillisecondRangeValues() {
        assertThrows(
                CommandSyntaxException.class,
                () -> parse("not-an-instant"));
        assertThrows(
                CommandSyntaxException.class,
                () -> parse("\"2026-08-01T00:00:00+02:00:30\""));
        assertThrows(
                CommandSyntaxException.class,
                () -> parse("\"+002026-08-01T00:00:00Z\""));
        assertThrows(
                CommandSyntaxException.class,
                () -> parse("\"+1000000000-12-31T23:59:59.999999999Z\""));
    }

    private Instant parse(String input) throws CommandSyntaxException {
        return argument.parse(new StringReader(input));
    }
}
