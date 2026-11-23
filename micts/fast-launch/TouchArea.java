package dev.padhelper.fastcts;

/**
 * Pure limits (no Android types) for the user-adjustable touch area, hold time and Gemini settle wait.
 * Only values the user set are stored; everything else follows these defaults, so a default change never overwrites a
 * user value. Area values are dp: horizontal offset from the bottom centre, distance from the bottom edge, width, height.
 */
final class TouchArea {
    /** WIDTH without the ColorOS taskbar; with the gesture bar and the taskbar both on, TASKBAR_WIDTH (portrait) or TASKBAR_WIDTH_LAND. */
    static final int X = 0, BOTTOM = 0, WIDTH = 200, TASKBAR_WIDTH = 240, TASKBAR_WIDTH_LAND = 330, HEIGHT = 38;
    static final int MIN_WIDTH = 60, MIN_HEIGHT = 20, MAX_HEIGHT = 160, MAX_BOTTOM = 400;
    /**
     * User decisions: default 195 ms (0.3.10; was 275), minimum 50 ms. The shorter it is, the likelier an ordinary tap
     * or the start of a home swipe counts as a hold (the settings text warns about it); a finger that moves past the
     * touch slop still never triggers. A value the user saved is kept; only an unset value follows the default.
     */
    static final int HOLD = 195, MIN_HOLD = 50, MAX_HOLD = 1000;
    /** Wait after the Google rebind before Gemini is requested (VoiceRepair). Bounded well under POWER_LAUNCH_AGE. */
    static final int SETTLE = 20, MAX_SETTLE = 1000;
    static final String HOLD_KEY = "hold_ms", SETTLE_KEY = "gemini_settle_ms";
    static final String[] FIELDS = {"x", "bottom", "width", "height"};

    private TouchArea() {}

    static int clamp(int value, int low, int high) { return Math.max(low, Math.min(high, value)); }

    /** {x, bottom, width, height}, kept entirely on a screen of screenWidth × screenHeight dp. */
    static int[] area(int x, int bottom, int width, int height, int screenWidth, int screenHeight) {
        int w = clamp(width, MIN_WIDTH, Math.max(MIN_WIDTH, screenWidth));
        int h = clamp(height, MIN_HEIGHT, Math.max(MIN_HEIGHT, Math.min(MAX_HEIGHT, screenHeight)));
        int side = Math.max(0, (screenWidth - w) / 2);
        return new int[]{clamp(x, -side, side), clamp(bottom, 0, Math.max(0, Math.min(MAX_BOTTOM, screenHeight - h))), w, h};
    }

    /** Longest accepted typed text ("-1133" fits): also bounds the parse, so no overflow is possible. */
    static final int MAX_TYPED = 5;

    /**
     * A value typed into the settings dialog: only ASCII digits, with one leading '-' when signed. Anything else
     * (other scripts' digits, '+', spaces inside, decimals, empty, too long) is null. The caller still range-checks.
     */
    /**
     * 애니메이션 배율 typed in the settings dialog: 0.00 to 10.00, ASCII digits with at most one '.' and two decimals,
     * at least one digit; spaces around it are ignored. Anything else is null. Returns hundredths (0 to 1000).
     */
    static Integer typedScale(String text) {
        if (text == null) return null;
        int from = 0, to = text.length();
        while (from < to && text.charAt(from) == ' ') from++;
        while (to > from && text.charAt(to - 1) == ' ') to--;
        String value = text.substring(from, to);
        if (value.isEmpty() || value.length() > 5) return null;
        int dot = value.indexOf('.');
        String whole = dot < 0 ? value : value.substring(0, dot), fraction = dot < 0 ? "" : value.substring(dot + 1);
        if (whole.length() > 2 || fraction.length() > 2 || whole.length() + fraction.length() == 0) return null;
        for (char c : (whole + fraction).toCharArray()) if (c < '0' || c > '9') return null;
        int hundredths = (whole.isEmpty() ? 0 : Integer.parseInt(whole)) * 100
            + (fraction.isEmpty() ? 0 : Integer.parseInt(fraction) * (fraction.length() == 1 ? 10 : 1));
        return hundredths <= 1000 ? hundredths : null;
    }

    static Integer typed(String text, boolean signed) {
        if (text == null) return null;
        // Only ordinary spaces around the number are ignored (String.trim would also drop control characters).
        int from = 0, to = text.length();
        while (from < to && text.charAt(from) == ' ') from++;
        while (to > from && text.charAt(to - 1) == ' ') to--;
        String value = text.substring(from, to);
        if (value.isEmpty() || value.length() > MAX_TYPED) return null;
        int start = signed && value.charAt(0) == '-' ? 1 : 0;
        if (start == value.length()) return null;
        for (int i = start; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') return null;
        }
        return Integer.parseInt(value);
    }

    static int hold(int value) { return clamp(value, MIN_HOLD, MAX_HOLD); }
    static int settle(int value) { return clamp(value, 0, MAX_SETTLE); }

    /** Separate values per orientation: a tablet's portrait and landscape bars sit in different places. */
    static String key(boolean landscape, String field) { return "area_" + (landscape ? "land_" : "port_") + field; }
    /**
     * Wider default only for the gesture bar with the taskbar on: system enable_launcher_taskbar exactly "1" and not
     * 3-button navigation (hide_navigationbar_enable "0"). Unreadable values (null) keep the narrow default.
     */
    static boolean taskbar(String taskbar, String nav) { return "1".equals(taskbar) && !"0".equals(nav); }

    static int fallback(String field, boolean taskbar, boolean landscape) {
        switch (field) {
            case "x": return X;
            case "bottom": return BOTTOM;
            case "width": return !taskbar ? WIDTH : landscape ? TASKBAR_WIDTH_LAND : TASKBAR_WIDTH;
            default: return HEIGHT;
        }
    }
}
