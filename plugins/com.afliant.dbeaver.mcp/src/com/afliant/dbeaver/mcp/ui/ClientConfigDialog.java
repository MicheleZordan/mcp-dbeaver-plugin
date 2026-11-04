package com.afliant.dbeaver.mcp.ui;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.PlatformUI;

/** Shows the client configuration snippets with a copy button. */
public class ClientConfigDialog extends Dialog {

    private static final int COPY_ID = IDialogConstants.CLIENT_ID + 1;

    private final String content;

    public ClientConfigDialog(Shell parentShell, String content) {
        super(parentShell);
        this.content = content;
        setShellStyle(getShellStyle() | SWT.RESIZE | SWT.MAX);
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText("MCP client configuration");
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        Label label = new Label(area, SWT.WRAP);
        label.setText("Paste the snippet for your client. The token is a secret: anyone holding it can run read-only queries on the exposed connections while the server is running.");
        label.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        Text text = new Text(area, SWT.MULTI | SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL | SWT.READ_ONLY);
        text.setText(content);
        Font mono = PlatformUI.getWorkbench().getThemeManager().getCurrentTheme().getFontRegistry().get("org.eclipse.jface.textfont");
        if (mono != null) {
            text.setFont(mono);
        }
        GridData gd = new GridData(SWT.FILL, SWT.FILL, true, true);
        gd.widthHint = 760;
        gd.heightHint = 460;
        text.setLayoutData(gd);
        return area;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, COPY_ID, "Copy to clipboard", true);
        createButton(parent, IDialogConstants.OK_ID, IDialogConstants.CLOSE_LABEL, false);
    }

    @Override
    protected void buttonPressed(int buttonId) {
        if (buttonId == COPY_ID) {
            Clipboard clipboard = new Clipboard(getShell().getDisplay());
            try {
                clipboard.setContents(new Object[] { content }, new Transfer[] { TextTransfer.getInstance() });
            } finally {
                clipboard.dispose();
            }
            return;
        }
        super.buttonPressed(buttonId);
    }
}
