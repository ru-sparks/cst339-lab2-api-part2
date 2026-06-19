package com.sparkco.generate_entities;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

import javax.sql.DataSource;
import java.sql.Connection;
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

@Component
public class EntityGeneratorRunner implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(EntityGeneratorRunner.class);
    private final DataSource dataSource;

    @Value("${entity.generator.enabled:true}")
    private boolean generatorEnabled;

    @Value("${entity.generator.package:com.sparkco.generate_entities.generated}")
    private String generatorPackage;

    @Value("${entity.generator.output-dir:src/main/java}")
    private String generatorOutputDir;

    public EntityGeneratorRunner(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(String... args) {
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try {
            logDatabaseMetadata(connection);
            if (generatorEnabled) {
                try {
                    var metadata = connection.getMetaData();
                    String catalog = connection.getCatalog();
                    String schema = connection.getSchema();
                    java.nio.file.Path out = java.nio.file.Path.of(generatorOutputDir);
                    new com.sparkco.generate_entities.generator.EntityGenerator()
                            .generateEntities(metadata, catalog, schema != null && !schema.isBlank() ? schema : null,
                                    out, generatorPackage);
                    logger.info("Entity generation completed (outputDir={})", generatorOutputDir);
                } catch (Exception ex) {
                    logger.error("Entity generation failed", ex);
                }
            } else {
                logger.info("Entity generation is disabled (set entity.generator.enabled=true to enable)");
            }
        } catch (SQLException ex) {
            logger.error("Unable to read database metadata", ex);
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    private void logDatabaseMetadata(Connection connection) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        String catalog = connection.getCatalog();
        String schema = connection.getSchema();
        logger.info("Connected to JDBC database: {} {}", metadata.getDatabaseProductName(),
                metadata.getDatabaseProductVersion());
        logger.info("Catalog: {} | Schema: {}", catalog, schema);

        String schemaPattern = schema != null && !schema.isBlank() ? schema : null;
        List<TableSummary> tableSummaries = new ArrayList<>();
        try (ResultSet tables = metadata.getTables(catalog, schemaPattern, "%", new String[] { "TABLE" })) {
            while (tables.next()) {
                String tableSchema = tables.getString("TABLE_SCHEM");
                String tableName = tables.getString("TABLE_NAME");
                if (isSystemSchema(tableSchema)) {
                    continue;
                }

                Map<String, ColumnInfo> columns = loadColumns(metadata, catalog, tableSchema, tableName);
                Set<String> primaryKeys = loadPrimaryKeys(metadata, catalog, tableSchema, tableName);
                Map<String, List<ForeignKeyInfo>> foreignKeys = loadForeignKeys(metadata, catalog, tableSchema,
                        tableName);

                logger.info("Table: {}.{}", tableSchema, tableName);
                for (ColumnInfo column : columns.values()) {
                    String fkDescription = foreignKeys.getOrDefault(column.name(), List.of()).stream()
                            .map(info -> String.format("%s(%s)", info.pkTable(), info.pkColumn()))
                            .collect(Collectors.joining(", "));

                    logger.info(
                            "  Column: {} | Type: {} | Length: {} | Nullable: {} | Primary Key: {} | Foreign Key: {}",
                            column.name(),
                            column.typeName(),
                            column.length() != null ? column.length() : "N/A",
                            column.nullable() ? "YES" : "NO",
                            primaryKeys.contains(column.name()),
                            fkDescription.isEmpty() ? "NONE" : fkDescription);
                }

                tableSummaries.add(new TableSummary(tableSchema, tableName, columns.size(), primaryKeys.size(),
                        foreignKeys.values().stream().mapToInt(List::size).sum()));
            }
        }

        logTableSummary(tableSummaries);
    }

    private boolean isSystemSchema(String schema) {
        return schema == null || schema.startsWith("pg_") || "information_schema".equalsIgnoreCase(schema);
    }

    private Map<String, ColumnInfo> loadColumns(DatabaseMetaData metadata, String catalog, String schema,
            String tableName) throws SQLException {
        Map<String, ColumnInfo> columns = new LinkedHashMap<>();
        try (ResultSet resultSet = metadata.getColumns(catalog, schema, tableName, "%")) {
            while (resultSet.next()) {
                String columnName = resultSet.getString("COLUMN_NAME");
                String typeName = resultSet.getString("TYPE_NAME");
                Integer length = getNullableInteger(resultSet, "COLUMN_SIZE");
                boolean nullable = resultSet.getInt("NULLABLE") == DatabaseMetaData.columnNullable;
                columns.put(columnName, new ColumnInfo(columnName, typeName, length, nullable));
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

    private Map<String, List<ForeignKeyInfo>> loadForeignKeys(DatabaseMetaData metadata, String catalog, String schema,
            String tableName) throws SQLException {
        Map<String, List<ForeignKeyInfo>> foreignKeys = new LinkedHashMap<>();
        try (ResultSet resultSet = metadata.getImportedKeys(catalog, schema, tableName)) {
            while (resultSet.next()) {
                String fkColumn = resultSet.getString("FKCOLUMN_NAME");
                String pkTable = resultSet.getString("PKTABLE_NAME");
                String pkColumn = resultSet.getString("PKCOLUMN_NAME");
                ForeignKeyInfo info = new ForeignKeyInfo(fkColumn, pkTable, pkColumn);
                foreignKeys.computeIfAbsent(fkColumn, ignored -> new ArrayList<>()).add(info);
            }
        }
        return foreignKeys;
    }

    private Integer getNullableInteger(ResultSet resultSet, String columnLabel) throws SQLException {
        Object value = resultSet.getObject(columnLabel);
        return value == null ? null : ((Number) value).intValue();
    }

    private void logTableSummary(List<TableSummary> tableSummaries) {
        if (tableSummaries.isEmpty()) {
            logger.info("Tables summary: no user tables found.");
            return;
        }

        logger.info("Tables summary: {} tables discovered", tableSummaries.size());
        for (TableSummary summary : tableSummaries) {
            logger.info("  {}.{} | Columns: {} | Primary Keys: {} | Foreign Keys: {}",
                    summary.schema(), summary.name(), summary.columnCount(), summary.primaryKeyCount(),
                    summary.foreignKeyCount());
        }
    }

    private static final record ColumnInfo(String name, String typeName, Integer length, boolean nullable) {
    }

    private static final record ForeignKeyInfo(String fkColumn, String pkTable, String pkColumn) {
    }

    private static final record TableSummary(String schema, String name, int columnCount, int primaryKeyCount,
            int foreignKeyCount) {
    }
}
