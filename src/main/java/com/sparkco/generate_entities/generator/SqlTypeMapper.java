package com.sparkco.generate_entities.generator;

import java.util.Locale;

public final class SqlTypeMapper {

    private SqlTypeMapper() {
    }

    public static JavaType map(String sqlTypeName) {
        if (sqlTypeName == null) {
            return new JavaType("Object", null);
        }

        String t = sqlTypeName.toLowerCase(Locale.ROOT);
        return switch (t) {
            case "int2", "smallint" -> new JavaType("Short", null);
            case "int4", "integer", "int" -> new JavaType("Integer", null);
            case "int8", "bigint" -> new JavaType("Long", null);
            case "float4", "real" -> new JavaType("Float", null);
            case "float8", "double precision", "double" -> new JavaType("Double", null);
            case "numeric", "decimal" -> new JavaType("BigDecimal", "java.math.BigDecimal");
            case "bool", "boolean" -> new JavaType("Boolean", null);
            case "date" -> new JavaType("LocalDate", "java.time.LocalDate");
            case "timestamp", "timestamp without time zone", "timestamptz", "timestamp with time zone" ->
                new JavaType("LocalDateTime", "java.time.LocalDateTime");
            case "time", "time without time zone" -> new JavaType("LocalTime", "java.time.LocalTime");
            case "varchar", "char", "character", "character varying", "text", "uuid" -> new JavaType("String", null);
            case "bytea" -> new JavaType("byte[]", null);
            default -> new JavaType("String", null);
        };
    }

    public static final class JavaType {
        private final String simpleName;
        private final String fqcn;

        public JavaType(String simpleName, String fqcn) {
            this.simpleName = simpleName;
            this.fqcn = fqcn;
        }

        public String simpleName() {
            return simpleName;
        }

        public String fqcn() {
            return fqcn;
        }
    }
}
