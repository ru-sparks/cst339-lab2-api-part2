package com.sparkco.generate_entities.generator;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sparkco.generate_entities.EntityGeneratorRunner;

public class EntityGenerator {
    private static final Logger logger = LoggerFactory.getLogger(EntityGenerator.class);

    public void generateEntities(DatabaseMetaData metadata, String catalog, String schema, Path outputDir,
            String basePackage) throws SQLException, IOException {

        String schemaPattern = schema != null && !schema.isBlank() ? schema : null;

        try (ResultSet tables = metadata.getTables(catalog, schemaPattern, "%", new String[] { "TABLE" })) {
            while (tables.next()) {
                String tableSchema = tables.getString("TABLE_SCHEM");
                String tableName = tables.getString("TABLE_NAME");
                if (tableSchema == null || tableSchema.startsWith("pg_")
                        || "information_schema".equalsIgnoreCase(tableSchema)) {
                    continue;
                }

                Map<String, Column> columns = loadColumns(metadata, catalog, tableSchema, tableName);
                Set<String> primaryKeys = loadPrimaryKeys(metadata, catalog, tableSchema, tableName);

                String className = toPascalCase(tableName);
                String packagePath = basePackage.replace('.', '/');
                Path packageDir = outputDir.resolve(packagePath);
                logger.info(">>>Package Directory: " + packageDir);
                Files.createDirectories(packageDir);

                Path javaFile = packageDir.resolve(className + ".java");
                try (BufferedWriter writer = Files.newBufferedWriter(javaFile)) {
                    writeClass(writer, basePackage, className, tableName, columns, primaryKeys);
                }
            }
        }
    }

    private void writeClass(BufferedWriter writer, String basePackage, String className, String tableName,
            Map<String, Column> columns, Set<String> primaryKeys) throws IOException {

        Set<String> imports = new LinkedHashSet<>();
        imports.add("jakarta.persistence.Column");
        imports.add("jakarta.persistence.Entity");
        imports.add("jakarta.persistence.Id");
        imports.add("jakarta.persistence.Table");
        for (Column col : columns.values()) {
            if (col.javaType.fqcn() != null) {
                imports.add(col.javaType.fqcn());
            }
        }

        writer.write("package " + basePackage + ";\n\n");

        for (String imp : imports) {
            writer.write("import " + imp + ";\n");
        }
        if (!imports.isEmpty()) {
            writer.write("\n");
        }

        writer.write("@Entity\n");
        writer.write("@Table(name = \"" + tableName + "\")\n");
        writer.write("public class " + className + " {\n\n");

        // fields
        for (Column col : columns.values()) {
            String fieldName = toCamelCase(col.name);
            if (primaryKeys.contains(col.name)) {
                writer.write("    @Id\n");
            }
            writer.write("    @Column(name = \"" + col.name + "\")\n");
            writer.write("    private " + col.javaType.simpleName() + " " + fieldName + ";\n");
        }
        writer.write("\n");

        // getters and setters
        for (Column col : columns.values()) {
            String fieldName = toCamelCase(col.name);
            String methodName = toPascalCase(col.name);
            String type = col.javaType.simpleName();

            // getter
            writer.write("    public " + type + " get" + methodName + "() {\n");
            writer.write("        return this." + fieldName + ";\n");
            writer.write("    }\n\n");

            // setter
            writer.write("    public void set" + methodName + "(" + type + " " + fieldName + ") {\n");
            writer.write("        this." + fieldName + " = " + fieldName + ";\n");
            writer.write("    }\n\n");
        }

        // toString
        writer.write("    @Override\n");
        writer.write("    public String toString() {\n");
        writer.write("        return \"" + className + "{" + "\n");
        List<String> parts = new ArrayList<>();
        for (Column col : columns.values()) {
            String fieldName = toCamelCase(col.name);
            parts.add("                \"" + fieldName + "='\" + " + fieldName + " + '\\\''");
        }
        for (int i = 0; i < parts.size(); i++) {
            writer.write(parts.get(i));
            if (i < parts.size() - 1) {
                writer.write(" + \", \" +\n");
            } else {
                writer.write(" +\n");
            }
        }
        writer.write("                '}';\n");
        writer.write("    }\n");

        writer.write("}\n");

    }

    private Map<String, Column> loadColumns(DatabaseMetaData metadata, String catalog, String schema,
            String tableName) throws SQLException {
        Map<String, Column> columns = new LinkedHashMap<>();
        try (ResultSet resultSet = metadata.getColumns(catalog, schema, tableName, "%")) {
            while (resultSet.next()) {
                String columnName = resultSet.getString("COLUMN_NAME");
                String typeName = resultSet.getString("TYPE_NAME");
                SqlTypeMapper.JavaType javaType = SqlTypeMapper.map(typeName);
                columns.put(columnName, new Column(columnName, typeName, javaType));
            }
        }
        return columns;
    }

    private Set<String> loadPrimaryKeys(DatabaseMetaData metadata, String catalog, String schema, String tableName)
            throws SQLException {
        Set<String> pkColumns = new LinkedHashSet<>();
        try (ResultSet resultSet = metadata.getPrimaryKeys(catalog, schema, tableName)) {
            while (resultSet.next()) {
                pkColumns.add(resultSet.getString("COLUMN_NAME"));
            }
        }
        return pkColumns;
    }

    private static final record Column(String name, String sqlType, SqlTypeMapper.JavaType javaType) {
    }

    private String toPascalCase(String input) {
        String[] parts = input.split("[_\\- ]");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isBlank())
                continue;
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1).toLowerCase());
        }
        return sb.toString();
    }

    private String toCamelCase(String input) {
        String pascal = toPascalCase(input);
        if (pascal.isEmpty())
            return pascal;
        return Character.toLowerCase(pascal.charAt(0)) + pascal.substring(1);
    }
}
