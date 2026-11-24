/** Small string helpers that replace String.repeat / String.isBlank (Java 11+) so the code runs on Java 8. */
final class Text {
    private Text() {
    }

    static String repeat(String s, int count) {
        StringBuilder sb = new StringBuilder(s.length() * Math.max(count, 0));
        for (int i = 0; i < count; i++) {
            sb.append(s);
        }
        return sb.toString();
    }

    static boolean isBlank(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isWhitespace(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
