package com.afliant.dbeaver.mcp.meta;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;

import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.DBPEvaluationContext;
import org.jkiss.dbeaver.model.DBPScriptObject;
import org.jkiss.dbeaver.model.DBPSystemObject;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSEntity;
import org.jkiss.dbeaver.model.struct.DBSEntityAssociation;
import org.jkiss.dbeaver.model.struct.DBSEntityAttribute;
import org.jkiss.dbeaver.model.struct.DBSEntityAttributeRef;
import org.jkiss.dbeaver.model.struct.DBSEntityConstraint;
import org.jkiss.dbeaver.model.struct.DBSEntityReferrer;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.struct.DBSObjectContainer;
import org.jkiss.dbeaver.model.struct.rdb.DBSCatalog;
import org.jkiss.dbeaver.model.struct.rdb.DBSSchema;
import org.jkiss.dbeaver.model.struct.rdb.DBSTable;
import org.jkiss.dbeaver.model.struct.rdb.DBSTableForeignKey;
import org.jkiss.dbeaver.model.struct.rdb.DBSTableIndex;
import org.jkiss.dbeaver.model.struct.rdb.DBSTableIndexColumn;
import org.jkiss.dbeaver.model.struct.rdb.DBSView;

import com.afliant.dbeaver.mcp.McpToolException;
import com.afliant.dbeaver.mcp.exec.QueryExecutor;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Schema browsing through DBeaver's own metadata model (no SQL is sent by the client for these). */
public final class MetadataService {

    private static final Log log = Log.getLog(MetadataService.class);

    private static DBRProgressMonitor monitor() {
        return new VoidProgressMonitor();
    }

    public JsonObject listSchemas(DBPDataSourceContainer container) throws McpToolException {
        DBSObjectContainer root = root(container);
        JsonArray schemas = new JsonArray();
        try {
            for (DBSObject child : children(root)) {
                if (child instanceof DBSObjectContainer sub && !(child instanceof DBSEntity)) {
                    JsonObject o = new JsonObject();
                    o.addProperty("name", child.getName());
                    o.addProperty("kind", kindOf(child));
                    addSystemFlag(o, child);
                    addDescription(o, child);
                    Class<? extends DBSObject> childType = sub.getPrimaryChildType(monitor());
                    if (childType != null && DBSObjectContainer.class.isAssignableFrom(childType) && !DBSEntity.class.isAssignableFrom(childType)) {
                        JsonArray nested = new JsonArray();
                        for (DBSObject n : children(sub)) {
                            if (n instanceof DBSObjectContainer && !(n instanceof DBSEntity)) {
                                nested.add(n.getName());
                            }
                        }
                        o.add("schemas", nested);
                        o.addProperty("note", "Reference nested schemas as '" + child.getName() + ".<schema>'.");
                    }
                    schemas.add(o);
                }
            }
        } catch (DBException e) {
            throw new McpToolException("Could not read schemas of '" + container.getName() + "': " + e.getMessage(), e);
        }
        JsonObject out = new JsonObject();
        out.addProperty("connection", container.getName());
        out.add("schemas", schemas);
        if (schemas.isEmpty()) {
            out.addProperty("note", "This connection has no schema level. Call list_tables without a schema.");
        }
        return out;
    }

    public JsonObject listTables(DBPDataSourceContainer container, String schema) throws McpToolException {
        DBSObjectContainer target = resolveContainer(root(container), schema);
        JsonArray tables = new JsonArray();
        JsonArray containers = new JsonArray();
        try {
            for (DBSObject child : children(target)) {
                if (child instanceof DBSEntity entity) {
                    JsonObject o = new JsonObject();
                    o.addProperty("name", entity.getName());
                    o.addProperty("kind", isView(entity) ? "view" : "table");
                    addSystemFlag(o, child);
                    addDescription(o, child);
                    tables.add(o);
                } else if (child instanceof DBSObjectContainer) {
                    containers.add(child.getName());
                }
            }
        } catch (DBException e) {
            throw new McpToolException("Could not list tables: " + e.getMessage(), e);
        }
        JsonObject out = new JsonObject();
        out.addProperty("connection", container.getName());
        out.addProperty("schema", schema == null || schema.isBlank() ? null : schema);
        out.add("tables", tables);
        if (!containers.isEmpty()) {
            out.add("schemas", containers);
            if (tables.isEmpty()) {
                out.addProperty("note", "This level contains schemas, not tables. Call list_tables with one of the listed schemas.");
            }
        }
        return out;
    }

    public JsonObject describeTable(DBPDataSourceContainer container, String schema, String table) throws McpToolException {
        DBSEntity entity = findEntity(container, schema, table);
        JsonObject out = new JsonObject();
        out.addProperty("connection", container.getName());
        out.addProperty("name", entity.getName());
        out.addProperty("full_name", DBUtils.getObjectFullName(entity, DBPEvaluationContext.DML));
        out.addProperty("kind", isView(entity) ? "view" : "table");
        addDescription(out, entity);
        try {
            out.add("columns", columns(entity));
            out.add("constraints", constraints(entity));
            out.add("indexes", indexes(entity));
            out.add("foreign_keys", foreignKeys(entity));
        } catch (DBException e) {
            throw new McpToolException("Could not read metadata of '" + table + "': " + e.getMessage(), e);
        }
        addDdl(out, entity);
        return out;
    }

    public JsonObject tableDdl(DBPDataSourceContainer container, String schema, String table) throws McpToolException {
        DBSEntity entity = findEntity(container, schema, table);
        JsonObject out = new JsonObject();
        out.addProperty("connection", container.getName());
        out.addProperty("full_name", DBUtils.getObjectFullName(entity, DBPEvaluationContext.DML));
        addDdl(out, entity);
        if (!out.has("ddl")) {
            throw new McpToolException("DDL is not available for '" + table + "'" + (out.has("ddl_error") ? ": " + out.get("ddl_error").getAsString() : "."));
        }
        return out;
    }

    // --- helpers -------------------------------------------------------------------------------------

    private static DBSObjectContainer root(DBPDataSourceContainer container) throws McpToolException {
        DBPDataSource dataSource = QueryExecutor.ensureConnected(container);
        if (dataSource instanceof DBSObjectContainer oc) {
            return oc;
        }
        throw new McpToolException("Connection '" + container.getName() + "' does not expose a browsable structure.");
    }

    private static DBSObjectContainer resolveContainer(DBSObjectContainer root, String schema) throws McpToolException {
        if (schema == null || schema.isBlank()) {
            return root;
        }
        DBSObjectContainer current = root;
        for (String part : schema.split("\\.")) {
            String name = part.strip();
            DBSObject child;
            try {
                child = current.getChild(monitor(), name);
            } catch (DBException e) {
                throw new McpToolException("Could not resolve schema '" + schema + "': " + e.getMessage(), e);
            }
            if (child == null) {
                throw new McpToolException("Schema '" + name + "' not found. Available: " + containerNames(current));
            }
            if (!(child instanceof DBSObjectContainer next) || child instanceof DBSEntity) {
                throw new McpToolException("'" + name + "' is not a schema or catalog.");
            }
            current = next;
        }
        return current;
    }

    private static DBSEntity findEntity(DBPDataSourceContainer container, String schema, String table) throws McpToolException {
        if (table == null || table.isBlank()) {
            throw new McpToolException("'table' is required.");
        }
        DBSObjectContainer root = root(container);
        String schemaRef = schema;
        String tableRef = table;
        if ((schemaRef == null || schemaRef.isBlank()) && table.contains(".")) {
            int dot = table.lastIndexOf('.');
            schemaRef = table.substring(0, dot);
            tableRef = table.substring(dot + 1);
        }
        DBSObjectContainer target = resolveContainer(root, schemaRef);
        DBSObject obj;
        try {
            obj = target.getChild(monitor(), tableRef);
        } catch (DBException e) {
            throw new McpToolException("Could not look up '" + tableRef + "': " + e.getMessage(), e);
        }
        if (obj instanceof DBSEntity entity) {
            return entity;
        }
        if (obj == null && (schemaRef == null || schemaRef.isBlank())) {
            throw new McpToolException("Table '" + tableRef + "' not found at the top level. This connection has schemas (" + containerNames(root) + "); pass 'schema' or use 'schema.table'.");
        }
        throw new McpToolException("Table or view '" + tableRef + "' not found" + (schemaRef == null || schemaRef.isBlank() ? "." : " in '" + schemaRef + "'."));
    }

    private static Collection<? extends DBSObject> children(DBSObjectContainer container) throws DBException {
        Collection<? extends DBSObject> children = container.getChildren(monitor());
        return children == null ? List.of() : children;
    }

    private static String containerNames(DBSObjectContainer container) {
        try {
            List<String> names = new ArrayList<>();
            for (DBSObject child : children(container)) {
                if (child instanceof DBSObjectContainer && !(child instanceof DBSEntity)) {
                    names.add(child.getName());
                }
            }
            return names.isEmpty() ? "none" : String.join(", ", names);
        } catch (DBException e) {
            return "unknown";
        }
    }

    private static String kindOf(DBSObject object) {
        if (object instanceof DBSCatalog) {
            return "catalog";
        }
        if (object instanceof DBSSchema) {
            return "schema";
        }
        return "container";
    }

    private static boolean isView(DBSEntity entity) {
        return entity instanceof DBSView || (entity instanceof DBSTable table && table.isView());
    }

    private static void addSystemFlag(JsonObject o, DBSObject object) {
        if (object instanceof DBPSystemObject sys && sys.isSystem()) {
            o.addProperty("system", true);
        }
    }

    private static void addDescription(JsonObject o, DBSObject object) {
        String description = object.getDescription();
        if (description != null && !description.isBlank()) {
            o.addProperty("description", description);
        }
    }

    private static JsonArray columns(DBSEntity entity) throws DBException {
        JsonArray columns = new JsonArray();
        List<? extends DBSEntityAttribute> attributes = entity.getAttributes(monitor());
        if (attributes == null) {
            return columns;
        }
        for (DBSEntityAttribute attr : attributes) {
            JsonObject o = new JsonObject();
            o.addProperty("name", attr.getName());
            o.addProperty("type", attr.getFullTypeName());
            o.addProperty("nullable", !attr.isRequired());
            if (attr.getDefaultValue() != null) {
                o.addProperty("default", attr.getDefaultValue());
            }
            if (attr.isAutoGenerated()) {
                o.addProperty("auto_generated", true);
            }
            addDescription(o, attr);
            columns.add(o);
        }
        return columns;
    }

    private static JsonArray constraints(DBSEntity entity) throws DBException {
        JsonArray constraints = new JsonArray();
        Collection<? extends DBSEntityConstraint> list = entity.getConstraints(monitor());
        if (list == null) {
            return constraints;
        }
        for (DBSEntityConstraint c : list) {
            JsonObject o = new JsonObject();
            o.addProperty("name", c.getName());
            o.addProperty("type", c.getConstraintType() != null ? c.getConstraintType().getName() : null);
            if (c instanceof DBSEntityReferrer referrer) {
                o.add("columns", refNames(referrer.getAttributeReferences(monitor())));
            }
            constraints.add(o);
        }
        return constraints;
    }

    private static JsonArray indexes(DBSEntity entity) {
        JsonArray indexes = new JsonArray();
        if (!(entity instanceof DBSTable table)) {
            return indexes;
        }
        try {
            Collection<? extends DBSTableIndex> list = table.getIndexes(monitor());
            if (list == null) {
                return indexes;
            }
            for (DBSTableIndex index : list) {
                JsonObject o = new JsonObject();
                o.addProperty("name", index.getName());
                o.addProperty("unique", index.isUnique());
                o.addProperty("primary", index.isPrimary());
                o.addProperty("type", index.getIndexType() != null ? index.getIndexType().getName() : null);
                JsonArray cols = new JsonArray();
                List<? extends DBSTableIndexColumn> refs = index.getAttributeReferences(monitor());
                if (refs != null) {
                    for (DBSTableIndexColumn ref : refs) {
                        if (ref.getTableColumn() != null) {
                            cols.add(ref.getTableColumn().getName());
                        } else if (ref.getAttribute() != null) {
                            cols.add(ref.getAttribute().getName());
                        }
                    }
                }
                o.add("columns", cols);
                addDescription(o, index);
                indexes.add(o);
            }
        } catch (DBException e) {
            log.debug("Could not read indexes of " + entity.getName(), e);
            JsonObject err = new JsonObject();
            err.addProperty("error", e.getMessage());
            indexes.add(err);
        }
        return indexes;
    }

    private static JsonArray foreignKeys(DBSEntity entity) throws DBException {
        JsonArray keys = new JsonArray();
        Collection<? extends DBSEntityAssociation> associations = entity.getAssociations(monitor());
        if (associations == null) {
            return keys;
        }
        for (DBSEntityAssociation association : associations) {
            if (!(association instanceof DBSTableForeignKey fk)) {
                continue;
            }
            JsonObject o = new JsonObject();
            o.addProperty("name", fk.getName());
            o.add("columns", refNames(fk.getAttributeReferences(monitor())));
            DBSEntity referenced = association.getAssociatedEntity();
            o.addProperty("referenced_table", referenced != null ? DBUtils.getObjectFullName(referenced, DBPEvaluationContext.DML) : null);
            DBSEntityConstraint referencedConstraint = association.getReferencedConstraint();
            if (referencedConstraint instanceof DBSEntityReferrer referrer) {
                o.add("referenced_columns", refNames(referrer.getAttributeReferences(monitor())));
            }
            if (fk.getDeleteRule() != null) {
                o.addProperty("on_delete", fk.getDeleteRule().getName());
            }
            if (fk.getUpdateRule() != null) {
                o.addProperty("on_update", fk.getUpdateRule().getName());
            }
            keys.add(o);
        }
        return keys;
    }

    private static JsonArray refNames(List<? extends DBSEntityAttributeRef> refs) {
        JsonArray names = new JsonArray();
        if (refs != null) {
            for (DBSEntityAttributeRef ref : refs) {
                if (ref.getAttribute() != null) {
                    names.add(ref.getAttribute().getName());
                }
            }
        }
        return names;
    }

    private static void addDdl(JsonObject out, DBSEntity entity) {
        if (entity instanceof DBPScriptObject scriptObject) {
            try {
                String ddl = scriptObject.getObjectDefinitionText(monitor(), new HashMap<>());
                if (ddl != null && !ddl.isBlank()) {
                    out.addProperty("ddl", ddl.strip());
                    return;
                }
            } catch (DBException e) {
                out.addProperty("ddl_error", e.getMessage());
                return;
            }
        }
        out.addProperty("ddl_error", "DDL is not provided by this driver.");
    }
}
