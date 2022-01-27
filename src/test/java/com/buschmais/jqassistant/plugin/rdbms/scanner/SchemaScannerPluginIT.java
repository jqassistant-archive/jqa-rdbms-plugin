package com.buschmais.jqassistant.plugin.rdbms.scanner;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

import com.buschmais.jqassistant.core.shared.map.MapBuilder;
import com.buschmais.jqassistant.core.store.api.model.Descriptor;
import com.buschmais.jqassistant.plugin.common.test.AbstractPluginIT;
import com.buschmais.jqassistant.plugin.java.api.scanner.JavaScope;
import com.buschmais.jqassistant.plugin.rdbms.api.RdbmsScope;
import com.buschmais.jqassistant.plugin.rdbms.api.model.*;
import com.buschmais.jqassistant.plugin.rdbms.impl.scanner.ConnectionPropertyFileScannerPlugin;

import org.hsqldb.jdbc.JDBCDriver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.hamcrest.collection.IsCollectionWithSize.hasSize;
import static org.junit.Assert.fail;

class SchemaScannerPluginIT extends AbstractPluginIT {

    public static final String PROPERTIES_MAXIMUM = "maximum";
    public static final String PROPERTIES_DEFAULT = "default";
    public static final String TABLE_PERSON = "PERSON";
    public static final String VIEW_PERSON = "PERSON_VIEW";
    public static final String COLUMN_A = "A";
    public static final String COLUMN_B = "B";
    public static final String COLUMN_C = "C";
    public static final String TABLE_ADDRESS = "ADDRESS";
    public static final String COLUMN_PERSON_A = "PERSON_A";
    public static final String COLUMN_PERSON_B = "PERSON_B";
    public static final String COLUMN_TYPE_DECIMAL = "DECIMAL";
    public static final String COLUMN_TYPE_VARCHAR = "VARCHAR";
    public static final String SEQUENCE_PERSON_SEQ = "PERSON_SEQ";
    public static final String ROUTINE_NEW_PERSON = "NEW_PERSON";
    public static final String ROUTINE_AN_HOUR_BEFORE = "AN_HOUR_BEFORE";

    @BeforeEach
    void createStructures() throws SQLException, ClassNotFoundException {
        Class.forName(JDBCDriver.class.getName());
        try (Connection c = DriverManager.getConnection("jdbc:hsqldb:file:target/testdb", "SA", "")) {
            execute(c, "drop procedure if exists new_person;");
            execute(c, "drop function if exists an_hour_before;");
            execute(c, "drop sequence if exists PERSON_SEQ");
            execute(c, "drop table if exists ADDRESS");
            execute(c, "drop trigger if exists PERSON_TRIGGER");
            execute(c, "drop view if exists PERSON_VIEW");
            execute(c, "drop table if exists PERSON");
            execute(c, "create table PERSON(a decimal(10,5), b decimal(5,2), c varchar(255) default 'defaultValue')");
            execute(c, "alter table PERSON add constraint PK_PERSON primary key (A,B)");
            execute(c, "create table ADDRESS(PERSON_A decimal(10,5), PERSON_B decimal(5,2))");
            execute(c, "alter table ADDRESS add constraint FK_ADDRESS_PERSON foreign key (PERSON_A,PERSON_B) references PERSON(A,B)");
            execute(c, "create sequence PERSON_SEQ minvalue 100 maxvalue 10000  start with 100 increment by 10 cycle");
            execute(c, "create view PERSON_VIEW as select a from PERSON");
            execute(c, "create trigger PERSON_TRIGGER after insert ON PERSON when (true) delete from PERSON");
            execute(c, "CREATE FUNCTION an_hour_before(t TIMESTAMP)\n" + "  RETURNS TIMESTAMP\n" + "  RETURN t-1 HOUR");
            execute(c, "CREATE PROCEDURE new_person(OUT c varchar(255), IN a decimal(10,5), IN b decimal(5,2))\n" + "  MODIFIES SQL DATA\n"
                    + "  BEGIN ATOMIC\n" + "    INSERT INTO PERSON VALUES (a, b, 'test');\n" + "    SET c = 'test';\n" + "  END;");
        }
    }

    /**
     * Verify view scanning.
     */
    @Test
    void view() {
        SchemaDescriptor schemaDescriptor = scanFile(PROPERTIES_DEFAULT);
        store.beginTransaction();
        ViewDescriptor view = getTableOrView(VIEW_PERSON);
        assertThat(schemaDescriptor.getViews(), hasItem(view));
        assertThat(view, notNullValue());
        assertThat(view, instanceOf(ViewDescriptor.class));
        assertThat(view.isUpdatable(), equalTo(false));
        assertThat(view.getCheckOption(), nullValue());
        assertThat(getColumn(VIEW_PERSON, COLUMN_A), notNullValue());
        store.commitTransaction();
    }

    /**
     * Verify trigger scanning.
     */
    @Test
    void trigger() {
        scanFile(PROPERTIES_MAXIMUM);
        store.beginTransaction();
        TableDescriptor table = getTableOrView(TABLE_PERSON);
        assertThat(table, notNullValue());
        List<TriggerDescriptor> triggers = table.getTriggers();
        assertThat(triggers, hasSize(1));
        TriggerDescriptor triggerDescriptor = triggers.get(0);
        assertThat(triggerDescriptor.getName(), equalTo("PERSON_TRIGGER"));
        assertThat(triggerDescriptor.getActionCondition(), equalTo("true"));
        assertThat(triggerDescriptor.getActionOrder(), equalTo(0));
        assertThat(triggerDescriptor.getActionOrientation(), equalTo("statement"));
        assertThat(triggerDescriptor.getActionStatement(), equalTo("DELETE FROM PUBLIC.PERSON"));
        assertThat(triggerDescriptor.getConditionTiming(), equalTo("after"));
        assertThat(triggerDescriptor.getEventManipulationTime(), equalTo("insert"));
        store.commitTransaction();
    }

    /**
     * Verify functions and procedures.
     */
    @Test
    void routines() {
        scanFile(PROPERTIES_MAXIMUM);
        store.beginTransaction();
        // Function
        RoutineDescriptor anHourBefore = getRoutine(ROUTINE_AN_HOUR_BEFORE);
        assertThat(anHourBefore, notNullValue());
        assertThat(anHourBefore, instanceOf(FunctionDescriptor.class));
        assertThat(anHourBefore.getName(), equalTo(ROUTINE_AN_HOUR_BEFORE));
        assertThat(anHourBefore.getReturnType(), equalTo("noTable"));
        assertThat(anHourBefore.getBodyType(), equalTo("sql"));
        assertThat(anHourBefore.getDefinition(), containsString("T-1 HOUR"));
        List<RoutineColumnDescriptor> anHourBeforeColumns = anHourBefore.getColumns();
        assertThat(anHourBeforeColumns, hasSize(1));
        RoutineColumnDescriptor t = anHourBeforeColumns.get(0);
        assertThat(t.getName(), equalTo("T"));
        assertThat(t.getType(), equalTo("in"));
        // Procedure
        RoutineDescriptor newPerson = getRoutine(ROUTINE_NEW_PERSON);
        assertThat(newPerson, notNullValue());
        assertThat(newPerson, instanceOf(ProcedureDescriptor.class));
        assertThat(newPerson.getName(), equalTo(ROUTINE_NEW_PERSON));
        assertThat(newPerson.getReturnType(), equalTo("noResult"));
        assertThat(newPerson.getBodyType(), equalTo("sql"));
        assertThat(newPerson.getDefinition(), containsString("INSERT INTO PUBLIC.PERSON"));
        List<RoutineColumnDescriptor> newPersonColumns = newPerson.getColumns();
        assertThat(newPersonColumns, hasSize(3));
        for (RoutineColumnDescriptor column : newPersonColumns) {
            if ("A".equals(column.getName())) {
                assertThat(column.getType(), equalTo("in"));
            } else if ("B".equals(column.getName())) {
                assertThat(column.getType(), equalTo("in"));
            } else if ("C".equals(column.getName())) {
                assertThat(column.getType(), equalTo("out"));
            } else {
                fail("Unexpected column " + column.getName());
            }
        }
        store.commitTransaction();
    }

    /**
     * Scan using infolevel minimum
     */
    @Test
    void minimum() {
        scanFile("minimum");
        store.beginTransaction();
        assertThat(getTableOrView(TABLE_PERSON), notNullValue());
        assertThat(getColumn(TABLE_PERSON, COLUMN_A), nullValue());
        store.commitTransaction();
    }

    /**
     * Scan using info level minimum
     */
    @Test
    void noTables() {
        scanFile("notables");
        store.beginTransaction();
        assertThat(getTableOrView(TABLE_PERSON), nullValue());
        store.commitTransaction();
    }

    @Test
    void tablesAndColumns() {
        SchemaDescriptor schemaDescriptor = scanFile(PROPERTIES_DEFAULT);
        store.beginTransaction();
        // Verify person
        TableDescriptor person = getTableOrView(TABLE_PERSON);
        assertThat(schemaDescriptor.getTables(), hasItem(person));
        assertThat(person.getName(), equalTo(TABLE_PERSON));
        // Verify column A
        ColumnDescriptor a = getColumn(TABLE_PERSON, COLUMN_A);
        assertThat(a.getName(), equalTo(COLUMN_A));
        assertThat(a.getSize(), equalTo(10));
        assertThat(a.getDecimalDigits(), equalTo(5));
        assertThat(a.isAutoIncremented(), equalTo(false));
        assertThat(a.isNullable(), equalTo(false));
        assertThat(a.getDefaultValue(), nullValue());
        assertThat(a.isPartOfPrimaryKey(), equalTo(true));
        assertThat(a.isPartOfIndex(), equalTo(true));
        assertThat(a.isPartOfForeignKey(), equalTo(false));
        assertThat(person.getColumns(), hasItem(a));
        // Verify column B
        ColumnDescriptor b = getColumn(TABLE_PERSON, COLUMN_B);
        assertThat(b.getName(), equalTo(COLUMN_B));
        assertThat(b.getSize(), equalTo(5));
        assertThat(b.getDecimalDigits(), equalTo(2));
        assertThat(b.isAutoIncremented(), equalTo(false));
        assertThat(b.isNullable(), equalTo(false));
        assertThat(b.getDefaultValue(), nullValue());
        assertThat(b.isPartOfPrimaryKey(), equalTo(true));
        assertThat(b.isPartOfIndex(), equalTo(true));
        assertThat(b.isPartOfForeignKey(), equalTo(false));
        assertThat(person.getColumns(), hasItem(b));
        // Verify column C
        ColumnDescriptor c = getColumn(TABLE_PERSON, COLUMN_C);
        assertThat(c.getName(), equalTo(COLUMN_C));
        assertThat(c.getSize(), equalTo(255));
        assertThat(c.getDecimalDigits(), equalTo(0));
        assertThat(c.isAutoIncremented(), equalTo(false));
        assertThat(c.isNullable(), equalTo(true));
        assertThat(c.getDefaultValue(), equalTo("'defaultValue'"));
        assertThat(c.isPartOfPrimaryKey(), equalTo(false));
        assertThat(c.isPartOfIndex(), equalTo(false));
        assertThat(c.isPartOfForeignKey(), equalTo(false));
        assertThat(person.getColumns(), hasItem(c));
        // Verify column type VARCHAR
        ColumnTypeDescriptor decimal = getColumnType(COLUMN_TYPE_DECIMAL);
        assertThat(a.getColumnType(), is(decimal));
        assertThat(b.getColumnType(), is(decimal));
        assertThat(decimal.getMinimumScale(), equalTo(0));
        assertThat(decimal.getMaximumScale(), equalTo(32767));
        assertThat(decimal.getNumericPrecisionRadix(), equalTo(10));
        assertThat(decimal.getPrecision(), equalTo((long) Integer.MAX_VALUE));
        assertThat(decimal.isNullable(), equalTo(true));
        assertThat(decimal.isAutoIncrementable(), equalTo(true));
        assertThat(decimal.isCaseSensitive(), equalTo(false));
        assertThat(decimal.isFixedPrecisionScale(), equalTo(true));
        assertThat(decimal.isUnsigned(), equalTo(false));
        assertThat(decimal.isUserDefined(), equalTo(false));
        // Verfify column type VARCHAR
        ColumnTypeDescriptor varchar = getColumnType(COLUMN_TYPE_VARCHAR);
        assertThat(c.getColumnType(), is(varchar));
        assertThat(varchar.getMinimumScale(), equalTo(0));
        assertThat(varchar.getMaximumScale(), equalTo(0));
        assertThat(varchar.getNumericPrecisionRadix(), equalTo(0));
        assertThat(varchar.getPrecision(), equalTo((long) Integer.MAX_VALUE));
        assertThat(varchar.isNullable(), equalTo(true));
        assertThat(varchar.isAutoIncrementable(), equalTo(false));
        assertThat(varchar.isCaseSensitive(), equalTo(true));
        assertThat(varchar.isFixedPrecisionScale(), equalTo(false));
        assertThat(varchar.isUnsigned(), equalTo(false));
        assertThat(varchar.isUserDefined(), equalTo(false));
        // Primary key
        PrimaryKeyDescriptor personPK = person.getPrimaryKey();
        assertThat(personPK, notNullValue());
        assertThat(personPK.getName(), equalTo("PK_PERSON"));
        assertThat(personPK.isUnique(), equalTo(true));
        verifyIndexColumns(personPK.getPrimaryKeyOnColumns(), a, b);
        // PK Index
        List<IndexDescriptor> indices = person.getIndices();
        assertThat(indices, hasSize(1));
        IndexDescriptor pkIndex = indices.get(0);
        assertThat(pkIndex.getName(), notNullValue());
        assertThat(pkIndex.isUnique(), equalTo(true));
        verifyIndexColumns(pkIndex.getIndexOnColumns(), a, b);
        store.commitTransaction();
    }

    private void verifyIndexColumns(List<? extends OnColumnDescriptor> primaryKeyOnColumns, ColumnDescriptor a, ColumnDescriptor b) {
        assertThat(primaryKeyOnColumns, hasSize(2));
        for (OnColumnDescriptor primaryKeyOnColumn : primaryKeyOnColumns) {
            assertThat(primaryKeyOnColumn.getSortSequence(), equalTo("ascending"));
            int position = primaryKeyOnColumn.getIndexOrdinalPosition();
            if (position == 1) {
                assertThat(primaryKeyOnColumn.getColumn(), equalTo(a));
            } else if (position == 2) {
                assertThat(primaryKeyOnColumn.getColumn(), equalTo(b));
            } else {
                fail("Unexpected index " + position);
            }
        }
    }

    @Test
    void foreignKey() {
        scanFile(PROPERTIES_DEFAULT);
        store.beginTransaction();
        TableDescriptor person = getTableOrView(TABLE_PERSON);
        assertThat(person, notNullValue());
        ColumnDescriptor pkA = getColumn(TABLE_PERSON, COLUMN_A);
        assertThat(pkA, notNullValue());
        ColumnDescriptor pkB = getColumn(TABLE_PERSON, COLUMN_B);
        assertThat(pkB, notNullValue());
        TableDescriptor address = getTableOrView(TABLE_ADDRESS);
        assertThat(address, notNullValue());
        ColumnDescriptor fkA = getColumn(TABLE_ADDRESS, COLUMN_PERSON_A);
        assertThat(fkA, notNullValue());
        ColumnDescriptor fkB = getColumn(TABLE_ADDRESS, COLUMN_PERSON_B);
        assertThat(fkB, notNullValue());
        ForeignKeyDescriptor foreignKey = getForeignKey("FK_ADDRESS_PERSON");
        assertThat(foreignKey, notNullValue());
        assertThat(foreignKey.getUpdateRule(), equalTo("noAction"));
        assertThat(foreignKey.getDeleteRule(), equalTo("noAction"));
        assertThat(foreignKey.getDeferrability(), equalTo("unknown"));
        List<ForeignKeyReferenceDescriptor> references = foreignKey.getForeignKeyReferences();
        assertThat(references, hasSize(2));
        for (ForeignKeyReferenceDescriptor reference : references) {
            ColumnDescriptor primaryKeyColumn = reference.getPrimaryKeyColumn();
            assertThat(primaryKeyColumn, notNullValue());
            ColumnDescriptor foreignKeyColumn = reference.getForeignKeyColumn();
            assertThat(foreignKeyColumn, notNullValue());
            if (pkA.equals(primaryKeyColumn)) {
                assertThat(foreignKeyColumn, equalTo(fkA));
            } else if (pkB.equals(primaryKeyColumn)) {
                assertThat(foreignKeyColumn, equalTo(fkB));
            } else {
                fail("Unexpected primary key column " + primaryKeyColumn.getName());
            }
        }
        store.commitTransaction();
    }

    @Test
    @Disabled("Need to investigate how schema crawler needs to be configured to retrieve sequence information from hsqldb")
    void sequences() throws IOException {
        scanFile(PROPERTIES_MAXIMUM);
        store.beginTransaction();
        SequenceDesriptor sequenceDesriptor = getSequence(SEQUENCE_PERSON_SEQ);
        assertThat(sequenceDesriptor, notNullValue());
        assertThat(sequenceDesriptor.getName(), equalTo(SEQUENCE_PERSON_SEQ));
        assertThat(sequenceDesriptor.getMinimumValue(), equalTo(100l));
        assertThat(sequenceDesriptor.getMaximumValue(), equalTo(10000l));
        assertThat(sequenceDesriptor.getIncrement(), equalTo(10l));
        assertThat(sequenceDesriptor.isCycle(), equalTo(true));
        store.commitTransaction();
    }

    @Test
    void scanUrl() throws URISyntaxException {
        store.beginTransaction();
        URI uri = new URI("jdbc:hsqldb:file:target/testdb;username=SA&password=");
        Descriptor descriptor = getScanner().scan(uri, uri.toString(), RdbmsScope.CONNECTION);
        verifyConnectionDescriptor(descriptor);
        store.commitTransaction();
    }

    /**
     * Scans the test tablesAndColumns.
     */
    private SchemaDescriptor scanFile(String name) {
        store.beginTransaction();
        String fileName = ConnectionPropertyFileScannerPlugin.PLUGIN_NAME + "-" + name + ConnectionPropertyFileScannerPlugin.PROPERTIES_SUFFIX;
        File propertyFile = new File(getClassesDirectory(SchemaScannerPluginIT.class), fileName);
        Descriptor descriptor = getScanner().scan(propertyFile, propertyFile.getAbsolutePath(), JavaScope.CLASSPATH);
        SchemaDescriptor publicSchema = verifyConnectionDescriptor(descriptor);
        store.commitTransaction();
        return publicSchema;
    }

    private SchemaDescriptor verifyConnectionDescriptor(Descriptor descriptor) {
        assertThat(descriptor, notNullValue());
        assertThat(descriptor, instanceOf(ConnectionDescriptor.class));
        List<SchemaDescriptor> schemas = ((ConnectionDescriptor) descriptor).getSchemas();
        assertThat(schemas, hasSize(greaterThan(0)));
        SchemaDescriptor publicSchema = null;
        for (SchemaDescriptor schema : schemas) {
            assertThat(schema.getName(), notNullValue());
            if ("PUBLIC".equals(schema.getName())) {
                publicSchema = schema;
            }
        }
        assertThat(publicSchema, notNullValue());
        return publicSchema;
    }

    /**
     * Execute a DDL statement.
     *
     * @param connection
     *            The connection.
     * @param ddl
     *            The ddl.
     * @throws SQLException
     *             If execution fails.
     */
    private void execute(Connection connection, String ddl) throws SQLException {
        try (PreparedStatement preparedStatement = connection.prepareStatement(ddl)) {
            preparedStatement.execute();
        }
    }

    /**
     * Get a table.
     *
     * @param table
     *            The table name.
     * @return The table descriptor.
     */
    private <T extends TableDescriptor> T getTableOrView(String table) {
        TestResult result = query("match (t:Rdbms:Table) where t.name=$table return t", MapBuilder.<String, Object>builder().entry("table", table).build());
        return result.getRows().isEmpty() ? null : result.<T>getColumn("t").get(0);
    }

    /**
     * Get a column.
     *
     * @param table
     *            The table name.
     * @param column
     *            The column name.
     * @return The column descriptor.
     */
    private ColumnDescriptor getColumn(String table, String column) {
        TestResult result = query("match (t:Table)-[:HAS_COLUMN]->(c:Rdbms:Column) where t.name=$table and c.name=$column return c",
            MapBuilder.<String, Object>builder().entry("table", table).entry("column", column).build());
        return result.getRows().isEmpty() ? null : result.<ColumnDescriptor>getColumn("c").get(0);
    }

    /**
     * Get a column type by its database type name.
     *
     * @param databaseType
     *            The name of the database type.
     * @return The descriptor.
     */
    private ColumnTypeDescriptor getColumnType(String databaseType) {
        List<ColumnTypeDescriptor> t = query("match (t:Rdbms:ColumnType) where t.databaseType=$databaseType return t",
                MapBuilder.<String, Object> builder().entry("databaseType", databaseType).build()).getColumn("t");
        return t == null ? null : t.get(0);
    }

    /**
     * Get a foreign key.
     *
     * @param foreignKey
     *            The foreign key name.
     * @return The foreign key descriptor.
     */
    private ForeignKeyDescriptor getForeignKey(String foreignKey) {
        List<ForeignKeyDescriptor> f = query("match (f:Rdbms:ForeignKey) where f.name=$foreignKey return f",
                MapBuilder.<String, Object> builder().entry("foreignKey", foreignKey).build()).getColumn("f");
        return f == null ? null : f.get(0);
    }

    /**
     * Get a sequence.
     *
     * @param sequence
     *            The sequence name.
     * @return The descriptor.
     */
    private SequenceDesriptor getSequence(String sequence) {
        List<SequenceDesriptor> s = query("match (s:Rdbms:Sequence) where s.name=$sequence return s",
                MapBuilder.<String, Object> builder().entry("sequence", sequence).build()).getColumn("s");
        return s == null ? null : s.get(0);
    }

    /**
     * Get a sequence.
     *
     * @param name
     *            The routine name.
     * @return The descriptor.
     */
    private <R extends RoutineDescriptor> R getRoutine(String name) {
        List<R> s = query("match (s:Rdbms:Routine) where s.name=$name return s", MapBuilder.<String, Object> builder().entry("name", name).build()).getColumn("s");
        return s == null ? null : s.get(0);
    }
}
