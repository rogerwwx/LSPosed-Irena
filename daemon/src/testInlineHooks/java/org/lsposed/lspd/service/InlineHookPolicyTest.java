package org.lsposed.lspd.service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class InlineHookPolicyTest {
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        var prefs = new HashMap<String, Object>();
        check(InlineHookPolicy.configuredPackages(prefs).isEmpty(), "Default must be opt-in");
        prefs.put("enable_verbose_log", true);
        prefs.put(InlineHookPolicy.KEY_PREFIX + "com.example.b", true);
        prefs.put(InlineHookPolicy.KEY_PREFIX + "com.example.a", true);
        prefs.put(InlineHookPolicy.KEY_PREFIX + "com.example.disabled", false);
        prefs.put(InlineHookPolicy.KEY_PREFIX + "com.example.badtype", "true");
        prefs.put(InlineHookPolicy.KEY_PREFIX, true);
        prefs.put(InlineHookPolicy.KEY_PREFIX + "system", true);
        check(List.copyOf(InlineHookPolicy.configuredPackages(prefs))
                .equals(List.of("com.example.a", "com.example.b")), "Read only valid enabled keys");
        prefs.remove(InlineHookPolicy.KEY_PREFIX + "com.example.a");
        check(InlineHookPolicy.configuredPackages(prefs).equals(Set.of("com.example.b")),
                "Changing a package must not replace the other selections");
        var bytes = new ByteArrayOutputStream();
        try (var out = new ObjectOutputStream(bytes)) { out.writeObject(prefs); }
        try (var in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            @SuppressWarnings("unchecked") var restored = (Map<String, Object>) in.readObject();
            check(InlineHookPolicy.configuredPackages(restored).equals(Set.of("com.example.b")),
                    "Settings retain their meaning after serialization");
        }
        for (String invalid : new String[]{null, "", "system", " com.example", "com.example ",
                "com/example", "com.example:remote", "com..example"}) {
            check(!InlineHookPolicy.isValidPackage(invalid), "Reject invalid/virtual package: " + invalid);
        }
        check(InlineHookPolicy.isValidPackage("android"), "Allow actual Android framework UI package");
        check(InlineHookPolicy.isValidPackage("com.example_app"), "Allow installed package names");
        check(!InlineHookPolicy.mayInvalidate("system", 1000), "Exclude registered system_server name");
        check(!InlineHookPolicy.mayInvalidate("system_server", 1000), "Exclude alternate system_server name");
        check(!InlineHookPolicy.mayInvalidate(null, 10123), "Reject missing process name");
        check(!InlineHookPolicy.mayInvalidate("com.example", -1), "Reject unknown uid");

        var processes = Set.of("com.example:remote", "custom.process", "shared.process");
        check(InlineHookPolicy.matches("com.example", 10123, 10123, "com.example",
                "com.example", processes), "Match main app process");
        check(InlineHookPolicy.matches("com.example", 10123, 10123, "com.example:remote",
                "com.example", processes), "Match declared subprocess");
        check(InlineHookPolicy.matches("com.example", 10123, 10123, "custom.process",
                "com.example", processes), "Match non-package component process");
        check(InlineHookPolicy.matches("com.example", 10123, 10123, "shared.process",
                "com.example", processes), "Shared process policy is process-wide");
        check(InlineHookPolicy.matches("com.example", 10123, 10123, "custom.main",
                "custom.main", Set.of()), "Honor application process name");
        check(!InlineHookPolicy.matches("com.example", 10123, 10123, "com.example:undeclared",
                "com.example", processes), "No package-prefix guessing");
        check(!InlineHookPolicy.matches("com.example", 10123, 10124, "com.example",
                "com.example", processes), "Never match another uid");
        check(!InlineHookPolicy.matches("com.example", 10123, 1010123, "com.example",
                "com.example", processes), "Do not reuse another user's package uid");
        check(InlineHookPolicy.matches("com.example", 1010123, 1010123, "com.example",
                "com.example", processes), "Match same package resolved for secondary user");
        check(InlineHookPolicy.matches("android", 1000, 1000, "system:ui",
                "system", Set.of()), "Support ResolverActivity without selecting system_server");
        check(!InlineHookPolicy.matches("android", 1000, 1000, "system",
                "system", Set.of("system")), "System exclusion overrides package match");
        check(!InlineHookPolicy.matches("android", 1000, 1000, "system_server",
                "system_server", Set.of("system_server")), "System exclusion overrides component match");
        check(!InlineHookPolicy.matches("android", 1000, 1001, "system:ui",
                "system", Set.of()), "Framework UI uid must also match");
        System.out.println("InlineHookPolicy: " + checks + " checks passed");
    }
}
