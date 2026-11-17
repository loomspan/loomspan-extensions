package ai.loomspan.decisions.internal.typesafe;

/** Status classification is independent of best-effort error evidence. */
final class TypeSafeHttpFailure extends IllegalStateException {
    final int status;
    final String body;
    final boolean truncated;
    final boolean unavailable;
    final String retryAfter;

    TypeSafeHttpFailure(int status, String body, boolean truncated, boolean unavailable, String retryAfter) {
        super("TypeSafe returned HTTP " + status);
        this.status = status;
        this.body = body;
        this.truncated = truncated;
        this.unavailable = unavailable;
        this.retryAfter = retryAfter;
    }

    boolean retryable() {
        return switch (status) {
            case 408, 429, 500, 502, 503, 504, 529 -> true;
            default -> false;
        };
    }
}
