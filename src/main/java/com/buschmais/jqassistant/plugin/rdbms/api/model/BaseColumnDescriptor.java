package com.buschmais.jqassistant.plugin.rdbms.api.model;

import com.buschmais.jqassistant.plugin.common.api.model.NamedDescriptor;
import com.buschmais.xo.neo4j.api.annotation.Relation;

public interface BaseColumnDescriptor extends NamedDescriptor, NullableDescriptor, CommentedDescriptor {

    @Relation("OF_COLUMN_TYPE")
    ColumnTypeDescriptor getColumnType();

    void setColumnType(ColumnTypeDescriptor columnType);

    int getSize();

    void setSize(int size);

    int getDecimalDigits();

    void setDecimalDigits(int decimalDigits);
}
