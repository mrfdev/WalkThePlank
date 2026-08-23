package com.mrfdev.walktheplank.command;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import io.papermc.paper.command.brigadier.argument.CustomArgumentType;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Typed RFC 3339 instant argument normalized to SQLite's millisecond precision. */
final class InstantArgument implements CustomArgumentType<Instant, String> {
    private static final Pattern RFC_3339 = Pattern.compile(
            "(\\d{4}-\\d{2}-\\d{2}[Tt]\\d{2}:\\d{2}:\\d{2})(?:\\.(\\d+))?([Zz]|[+-]\\d{2}:\\d{2})");
    private static final SimpleCommandExceptionType INVALID_INSTANT =
            new SimpleCommandExceptionType(new com.mojang.brigadier.LiteralMessage(
                    "Expected a quoted RFC 3339 instant such as \"2026-08-01T00:00:00Z\"."));

    @Override
    public Instant parse(StringReader reader) throws CommandSyntaxException {
        String value = StringArgumentType.string().parse(reader);
        try {
            Matcher matcher = RFC_3339.matcher(value);
            if (!matcher.matches()) {
                throw new DateTimeParseException("Not RFC 3339", value, 0);
            }
            String fraction = matcher.group(2);
            if (fraction != null && fraction.length() > 9) {
                fraction = fraction.substring(0, 9);
            }
            String zone = matcher.group(3);
            String canonical = matcher.group(1).replace('t', 'T')
                    + (fraction == null ? "" : '.' + fraction)
                    + (zone.equals("z") ? "Z" : zone);
            Instant parsed = Instant.parse(canonical);
            return Instant.ofEpochMilli(parsed.toEpochMilli());
        } catch (DateTimeParseException | ArithmeticException invalid) {
            throw INVALID_INSTANT.createWithContext(reader);
        }
    }

    @Override
    public ArgumentType<String> getNativeType() {
        return StringArgumentType.string();
    }
}
