package com.ywzjvehicle.armor;

/**
 * Which slice of a list a page shows, and how many pages there are.
 *
 * <p>Pure integers and nothing else, for the same reason {@link Suspension} is: the screen draws a
 * page and this decides which rows are on it, and keeping those apart is what lets
 * {@code AmmoCompendium} walk every page without a game running. An error here has no symptom worth
 * the name - a page of thirty rows does not look like one with a row missing, and a list that skips
 * a row is discovered by somebody who happens to be looking for that round.
 *
 * <h2>Why it clamps rather than wraps</h2>
 *
 * <p>{@link #turn} stops at the ends. The keys that drive it are the arrow keys, which mean up and
 * down rather than "the next one along", and holding "page down" at the end of the book and being
 * sent back to the first page is the one thing a directional key must not do. That is the same
 * decision {@code Suspension.step} makes, for the same reason, and it is written the same way so the
 * two read alike.
 *
 * <h2>Why an empty list is one page and not none</h2>
 *
 * <p>A page count of zero would leave the screen dividing by it, or drawing nothing where a heading
 * belongs. "One page with nothing on it" is what a book with no rounds looks like, and it is what
 * every count here returns for an empty list.
 */
public final class Pages {

    private Pages() {
    }

    /**
     * @return how many rows fit in this much room at this row height, and at least one - a window
     *         too short for a single row still has to show something rather than divide by zero.
     */
    public static int perPage(int room, int rowHeight) {
        if (rowHeight <= 0) {
            return 1;
        }
        return Math.max(1, room / rowHeight);
    }

    /** @return how many pages this many rows need, and at least one. */
    public static int count(int rows, int perPage) {
        if (rows <= 0) {
            return 1;
        }
        return (rows + size(perPage) - 1) / size(perPage);
    }

    /** @return the page index brought inside the range, so a resized window cannot point off the end. */
    public static int clamp(int page, int rows, int perPage) {
        return Math.max(0, Math.min(page, count(rows, perPage) - 1));
    }

    /** @return the index of the first row on a page. */
    public static int first(int page, int rows, int perPage) {
        return clamp(page, rows, perPage) * size(perPage);
    }

    /** @return one past the last row on a page, so a page is the half-open range {@code [first, end)}. */
    public static int end(int page, int rows, int perPage) {
        return Math.min(rows, first(page, rows, perPage) + size(perPage));
    }

    /**
     * @param direction negative for the page before, anything else for the page after
     * @return the page a press moves to, stopping at the ends rather than wrapping
     */
    public static int turn(int page, int rows, int perPage, int direction) {
        int next = clamp(page, rows, perPage) + (direction < 0 ? -1 : 1);
        return clamp(next, rows, perPage);
    }

    /** A page size that can be divided by, whatever a caller with a window one pixel tall passes in. */
    private static int size(int perPage) {
        return Math.max(1, perPage);
    }
}
