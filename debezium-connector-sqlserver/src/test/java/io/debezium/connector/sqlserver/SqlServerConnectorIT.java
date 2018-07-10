/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.sqlserver;

import static org.junit.Assert.assertNull;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.fest.assertions.Assertions;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import io.debezium.config.Configuration;
import io.debezium.connector.sqlserver.util.TestHelper;
import io.debezium.data.SchemaAndValueField;
import io.debezium.embedded.AbstractConnectorTest;
import io.debezium.util.Testing;

/**
 * Integration test for the Debezium SQL Server connector.
 *
 * @author Jiri Pechanec
 */
public class SqlServerConnectorIT extends AbstractConnectorTest {

    private static SqlServerConnection connection;

    @BeforeClass
    public static void beforeClass() {
    }

    @AfterClass
    public static void closeConnection() throws SQLException {
        if (connection != null) {
            connection.close();
        }
    }

    @Before
    public void before() throws SQLException {
        TestHelper.createTestDatabase();
        connection = TestHelper.testConnection();
        connection.execute(
                "CREATE TABLE tablea (id int primary key, cola varchar(30))",
                "CREATE TABLE tableb (id int primary key, colb varchar(30))",
                "INSERT INTO tablea VALUES(1, 'a')"
        );
        connection.enableTableCdc("tablea");
        connection.enableTableCdc("tableb");

        initializeConnectorTestFramework();
        Testing.Files.delete(TestHelper.DB_HISTORY_PATH);
    }

    @After
    public void after() throws SQLException {
        connection.close();
        TestHelper.dropTestDatabase();
    }

    @Test
    public void createAndDelete() throws Exception {
        final int RECORDS_PER_TABLE  = 5;
        final int TABLES = 2;
        final int ID_START = 10;
        final Configuration config = TestHelper.defaultConfig().build();

        start(SqlServerConnector.class, config);
        assertConnectorIsRunning();

        for (int i = 0; i < RECORDS_PER_TABLE; i++) {
            final int id = ID_START + i;
            connection.execute(
                    "INSERT INTO tablea VALUES(" + id + ", 'a')"
            );
            connection.execute(
                    "INSERT INTO tableb VALUES(" + id + ", 'b')"
            );
        }

        final SourceRecords records = consumeRecordsByTopic(RECORDS_PER_TABLE * TABLES);
        final List<SourceRecord> tableA = records.recordsForTopic("server1.dbo.tablea");
        final List<SourceRecord> tableB = records.recordsForTopic("server1.dbo.tableb");
        Assertions.assertThat(tableA).hasSize(RECORDS_PER_TABLE);
        Assertions.assertThat(tableB).hasSize(RECORDS_PER_TABLE);
        for (int i = 0; i < RECORDS_PER_TABLE; i++) {
            final SourceRecord recordA = tableA.get(i);
            final SourceRecord recordB = tableB.get(i);
            final List<SchemaAndValueField> expectedRowA = Arrays.asList(
                    new SchemaAndValueField("id", Schema.INT32_SCHEMA, i + ID_START),
                    new SchemaAndValueField("cola", Schema.OPTIONAL_STRING_SCHEMA, "a"));
            final List<SchemaAndValueField> expectedRowB = Arrays.asList(
                    new SchemaAndValueField("id", Schema.INT32_SCHEMA, i + ID_START),
                    new SchemaAndValueField("colb", Schema.OPTIONAL_STRING_SCHEMA, "b"));

            final Struct keyA = (Struct)recordA.key();
            final Struct valueA = (Struct)recordA.value();
            assertRecord((Struct)valueA.get("after"), expectedRowA);
            assertNull(valueA.get("before"));

            final Struct keyB = (Struct)recordB.key();
            final Struct valueB = (Struct)recordB.value();
            assertRecord((Struct)valueB.get("after"), expectedRowB);
            assertNull(valueB.get("before"));
        }

        connection.execute("DELETE FROM tableB");
        final SourceRecords deleteRecords = consumeRecordsByTopic(2 * RECORDS_PER_TABLE);
        final List<SourceRecord> deleteTableA = deleteRecords.recordsForTopic("server1.dbo.tablea");
        final List<SourceRecord> deleteTableB = deleteRecords.recordsForTopic("server1.dbo.tableb");
        Assertions.assertThat(deleteTableA).isNullOrEmpty();
        Assertions.assertThat(deleteTableB).hasSize(2 * RECORDS_PER_TABLE);

        for (int i = 0; i < RECORDS_PER_TABLE; i++) {
            final SourceRecord deleteRecord = deleteTableB.get(i * 2);
            final SourceRecord tombstoneRecord = deleteTableB.get(i * 2 + 1);
            final List<SchemaAndValueField> expectedDeleteRow = Arrays.asList(
                    new SchemaAndValueField("id", Schema.INT32_SCHEMA, i + ID_START),
                    new SchemaAndValueField("colb", Schema.OPTIONAL_STRING_SCHEMA, "b"));

            final Struct deleteKey = (Struct)deleteRecord.key();
            final Struct deleteValue = (Struct)deleteRecord.value();
            assertRecord((Struct)deleteValue.get("before"), expectedDeleteRow);
            assertNull(deleteValue.get("after"));

            final Struct tombstoneKey = (Struct)tombstoneRecord.key();
            final Struct tombstoneValue = (Struct)tombstoneRecord.value();
            assertNull(tombstoneValue);
        }

        stopConnector();
    }

    @Test
    public void update() throws Exception {
        final int RECORDS_PER_TABLE  = 5;
        final int ID_START = 10;
        final Configuration config = TestHelper.defaultConfig().build();

        start(SqlServerConnector.class, config);
        assertConnectorIsRunning();

        connection.setAutoCommit(false);
        final String[] tableBInserts = new String[RECORDS_PER_TABLE];
        for (int i = 0; i < RECORDS_PER_TABLE; i++) {
            final int id = ID_START + i;
            tableBInserts[i] = "INSERT INTO tableb VALUES(" + id + ", 'b')";
        }
        connection.execute(tableBInserts);
        connection.setAutoCommit(true);

        connection.execute("UPDATE tableb SET colb='z'");

        final SourceRecords records = consumeRecordsByTopic(RECORDS_PER_TABLE * 2);
        final List<SourceRecord> tableB = records.recordsForTopic("server1.dbo.tableb");
        Assertions.assertThat(tableB).hasSize(RECORDS_PER_TABLE * 2);
        for (int i = 0; i < RECORDS_PER_TABLE; i++) {
            final SourceRecord recordB = tableB.get(i);
            final List<SchemaAndValueField> expectedRowB = Arrays.asList(
                    new SchemaAndValueField("id", Schema.INT32_SCHEMA, i + ID_START),
                    new SchemaAndValueField("colb", Schema.OPTIONAL_STRING_SCHEMA, "b"));

            final Struct keyB = (Struct)recordB.key();
            final Struct valueB = (Struct)recordB.value();
            assertRecord((Struct)valueB.get("after"), expectedRowB);
            assertNull(valueB.get("before"));
        }

        for (int i = 0; i < RECORDS_PER_TABLE; i++) {
            final SourceRecord recordB = tableB.get(i + RECORDS_PER_TABLE);
            final List<SchemaAndValueField> expectedBefore = Arrays.asList(
                    new SchemaAndValueField("id", Schema.INT32_SCHEMA, i + ID_START),
                    new SchemaAndValueField("colb", Schema.OPTIONAL_STRING_SCHEMA, "b"));
            final List<SchemaAndValueField> expectedAfter = Arrays.asList(
                    new SchemaAndValueField("id", Schema.INT32_SCHEMA, i + ID_START),
                    new SchemaAndValueField("colb", Schema.OPTIONAL_STRING_SCHEMA, "z"));

            final Struct keyB = (Struct)recordB.key();
            final Struct valueB = (Struct)recordB.value();
            assertRecord((Struct)valueB.get("before"), expectedBefore);
            assertRecord((Struct)valueB.get("after"), expectedAfter);
        }

        stopConnector();
    }

    private void assertRecord(Struct record, List<SchemaAndValueField> expected) {
        expected.forEach(schemaAndValueField -> schemaAndValueField.assertFor(record));
    }
 }