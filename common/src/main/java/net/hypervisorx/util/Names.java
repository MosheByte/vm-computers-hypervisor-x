package net.hypervisorx.util;

/** cleans up names and labels that players type in. */
public final class Names {
    private Names() {
    }

    /** strips control chars (a newline would break .vmx files and chat), then trims and caps it at {@code max}. */
    public static String clean(String s, int max) {
        if (s == null) return "";
        String t = s.replaceAll("\\p{Cntrl}", "").strip();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
