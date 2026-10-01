package net.wastu.solipsist;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Shared event vocabulary without a dependency on Xposed classes. */
final class RuntimeCategories {
    private static final Set<String> NAMES = new HashSet<>(Arrays.asList(
            "media_query", "media_redaction", "settings", "accessibility", "system_property",
            "clipboard", "accounts", "webview", "location", "vpn"));

    private RuntimeCategories() {}

    static boolean contains(String category) { return NAMES.contains(category); }
}
