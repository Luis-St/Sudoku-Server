package net.luis.sudoku.db;

/**
 * Every advisory lock key used by the server, in one place so two of them can never collide.
 */
public final class AdvisoryLocks {
	
	/**
	 * Serialises the bootstrap-admin claim, whose "no non-revoked admin exists" check cannot be
	 * expressed as a constraint (server-spec 5.1, 6.3).
	 */
	public static final long BOOTSTRAP_ADMIN = 8_101_975_204_312L;
	
	/**
	 * Serialises the daily rollover fold-and-prune job, so two requests crossing midnight cannot both
	 * run it (server-spec 8.6).
	 */
	public static final long DAILY_ROLLOVER = 8_101_975_204_313L;
	
	/**
	 * Serialises every operation that could reduce the number of admins (server-spec 7.1).
	 * <p>
	 * {@code SELECT ... FOR UPDATE} on the affected user is <em>not</em> sufficient on its own: it locks
	 * the row being changed, but the "is there another admin?" count reads <em>other</em> rows, which it
	 * does not lock. Two admins demoting each other concurrently would each see the other still an admin,
	 * and both would commit - leaving zero. Holding this lock for the whole check-and-mutate closes that.
	 */
	public static final long ADMIN_INVARIANT = 8_101_975_204_314L;
	
	private AdvisoryLocks() {}
}
