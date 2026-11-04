package com.afliant.dbeaver.mcp.ui;

import java.util.LinkedHashSet;
import java.util.Set;

import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;

import com.afliant.dbeaver.mcp.ConnectionRegistry;
import com.afliant.dbeaver.mcp.McpPreferences;
import com.afliant.dbeaver.mcp.ServerManager;

/** Preferences > MCP Server. */
public class PrefPageMcp extends PreferencePage implements IWorkbenchPreferencePage {

    private Spinner portSpinner;
    private Spinner maxRowsSpinner;
    private Spinner timeoutSpinner;
    private Button auditCheck;
    private Text tokenText;
    private Table connectionsTable;

    @Override
    public void init(IWorkbench workbench) {
        setDescription("Expose DBeaver connections to MCP clients (Claude Code, Claude Desktop, Cursor). "
            + "Everything is read-only: only single SELECT/SHOW/EXPLAIN/DESCRIBE statements are executed, inside a rolled-back read-only transaction.");
        noDefaultAndApplyButton();
    }

    @Override
    protected Control createContents(Composite parent) {
        Composite root = new Composite(parent, SWT.NONE);
        root.setLayout(new GridLayout(1, false));
        root.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        Group serverGroup = group(root, "Server", 3);
        label(serverGroup, "Port (loopback only)");
        portSpinner = spinner(serverGroup, 1024, 65535, McpPreferences.getPort());
        new Label(serverGroup, SWT.NONE);
        label(serverGroup, "Bearer token");
        tokenText = new Text(serverGroup, SWT.BORDER | SWT.READ_ONLY);
        tokenText.setText(McpPreferences.getToken());
        tokenText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        Button regenerate = new Button(serverGroup, SWT.PUSH);
        regenerate.setText("Regenerate");
        regenerate.addListener(SWT.Selection, e -> tokenText.setText(McpPreferences.regenerateToken()));
        Label hint = new Label(serverGroup, SWT.WRAP);
        hint.setText(ServerManager.getInstance().isRunning()
            ? "The server is running: restart it from the MCP menu for port or token changes to take effect."
            : "Start the server from the MCP menu. It does not start automatically with DBeaver.");
        GridData hintData = new GridData(SWT.FILL, SWT.TOP, true, false, 3, 1);
        hint.setLayoutData(hintData);

        Group queryGroup = group(root, "Queries", 2);
        label(queryGroup, "Maximum rows per result");
        maxRowsSpinner = spinner(queryGroup, 1, 100000, McpPreferences.getMaxRows());
        label(queryGroup, "Query timeout (seconds)");
        timeoutSpinner = spinner(queryGroup, 1, 3600, McpPreferences.getTimeoutSeconds());
        auditCheck = new Button(queryGroup, SWT.CHECK);
        auditCheck.setText("Write an audit log of every tool call (workspace metadata folder)");
        auditCheck.setSelection(McpPreferences.isAuditEnabled());
        auditCheck.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        Group connectionsGroup = group(root, "Connections exposed to MCP clients", 1);
        connectionsTable = new Table(connectionsGroup, SWT.CHECK | SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL);
        connectionsTable.setHeaderVisible(true);
        connectionsTable.setLinesVisible(true);
        GridData tableData = new GridData(SWT.FILL, SWT.FILL, true, true);
        tableData.heightHint = 220;
        connectionsTable.setLayoutData(tableData);
        for (String title : new String[] { "Connection", "Project", "Driver", "Read-only in DBeaver" }) {
            TableColumn column = new TableColumn(connectionsTable, SWT.NONE);
            column.setText(title);
        }
        Set<String> allowed = McpPreferences.getAllowedConnectionIds();
        for (DBPDataSourceContainer c : ConnectionRegistry.allContainers()) {
            TableItem item = new TableItem(connectionsTable, SWT.NONE);
            item.setText(new String[] {
                c.getName(),
                c.getProject() != null ? c.getProject().getName() : "",
                c.getDriver() != null ? c.getDriver().getName() : "",
                c.isConnectionReadOnly() ? "yes" : "no" });
            item.setData(c.getId());
            item.setChecked(allowed.contains(c.getId()));
        }
        for (TableColumn column : connectionsTable.getColumns()) {
            column.pack();
        }
        Composite buttons = new Composite(connectionsGroup, SWT.NONE);
        buttons.setLayout(new GridLayout(2, true));
        Button all = new Button(buttons, SWT.PUSH);
        all.setText("Select all");
        all.addListener(SWT.Selection, e -> setAll(true));
        Button none = new Button(buttons, SWT.PUSH);
        none.setText("Select none");
        none.addListener(SWT.Selection, e -> setAll(false));
        Label note = new Label(connectionsGroup, SWT.WRAP);
        note.setText("Unticked connections are invisible to clients. Changes apply immediately, even while the server is running.");
        note.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        return root;
    }

    private void setAll(boolean checked) {
        for (TableItem item : connectionsTable.getItems()) {
            item.setChecked(checked);
        }
    }

    @Override
    public boolean performOk() {
        McpPreferences.store().setValue(McpPreferences.PORT, portSpinner.getSelection());
        McpPreferences.store().setValue(McpPreferences.MAX_ROWS, maxRowsSpinner.getSelection());
        McpPreferences.store().setValue(McpPreferences.TIMEOUT_SECONDS, timeoutSpinner.getSelection());
        McpPreferences.store().setValue(McpPreferences.AUDIT_ENABLED, auditCheck.getSelection());
        Set<String> ids = new LinkedHashSet<>();
        for (TableItem item : connectionsTable.getItems()) {
            if (item.getChecked()) {
                ids.add((String) item.getData());
            }
        }
        McpPreferences.setAllowedConnectionIds(ids);
        McpPreferences.save();
        return true;
    }

    private static Group group(Composite parent, String title, int columns) {
        Group group = new Group(parent, SWT.NONE);
        group.setText(title);
        group.setLayout(new GridLayout(columns, false));
        group.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, false));
        return group;
    }

    private static void label(Composite parent, String text) {
        Label label = new Label(parent, SWT.NONE);
        label.setText(text);
    }

    private static Spinner spinner(Composite parent, int min, int max, int value) {
        Spinner spinner = new Spinner(parent, SWT.BORDER);
        spinner.setMinimum(min);
        spinner.setMaximum(max);
        spinner.setSelection(value);
        spinner.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false));
        return spinner;
    }
}
