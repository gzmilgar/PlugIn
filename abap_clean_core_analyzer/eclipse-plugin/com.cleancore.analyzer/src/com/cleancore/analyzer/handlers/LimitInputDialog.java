package com.cleancore.analyzer.handlers;

import org.eclipse.jface.dialogs.IInputValidator;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.swt.widgets.Shell;

/**
 * Asks the user how many objects should be analyzed in a single batch.
 */
public final class LimitInputDialog {

    public static final int MIN = 1;
    public static final int MAX = 5000;
    public static final int DEFAULT_LIMIT = 200;

    private LimitInputDialog() {}

    /**
     * Opens the dialog and returns the chosen limit.
     * Returns -1 if the user cancelled.
     */
    public static int prompt(Shell shell) {
        IInputValidator validator = new IInputValidator() {
            @Override
            public String isValid(String text) {
                if (text == null || text.trim().isEmpty()) {
                    return "Lutfen bir sayi girin.";
                }
                try {
                    int n = Integer.parseInt(text.trim());
                    if (n < MIN) return "Minimum " + MIN;
                    if (n > MAX) return "Maksimum " + MAX;
                    return null;
                } catch (NumberFormatException nfe) {
                    return "Gecerli bir tamsayi girin.";
                }
            }
        };

        InputDialog dlg = new InputDialog(
            shell,
            "Clean Core Analyzer",
            "Paket altinda kac obje analiz edilsin?\n"
            + "(Recursive: alt paketlerdeki objeler de dahil. "
            + "Yuksek sayilar uzun surebilir.)",
            String.valueOf(DEFAULT_LIMIT),
            validator);

        if (dlg.open() != InputDialog.OK) return -1;
        try {
            return Integer.parseInt(dlg.getValue().trim());
        } catch (Exception e) {
            return DEFAULT_LIMIT;
        }
    }
}
