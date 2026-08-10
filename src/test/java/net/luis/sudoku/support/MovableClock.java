package net.luis.sudoku.support;

import org.jspecify.annotations.NonNull;

import java.time.*;

/**
 * A clock the test moves by hand, so ageing is exercised without a test that sleeps for an hour.
 * <p>
 * Shared rather than nested in one test class because every service here takes its {@link Clock} injected
 * for exactly this reason, and each test that wanted to move time was otherwise writing the same twenty
 * lines again.
 */
public final class MovableClock extends Clock {
	
	private Instant now;
	
	public MovableClock() {
		this(Instant.parse("2026-08-09T10:00:00Z"));
	}
	
	public MovableClock(@NonNull Instant now) {
		this.now = now;
	}
	
	public void advance(@NonNull Duration amount) {
		this.now = this.now.plus(amount);
	}
	
	@Override
	public @NonNull ZoneId getZone() {
		return ZoneOffset.UTC;
	}
	
	@Override
	public @NonNull Clock withZone(@NonNull ZoneId zone) {
		return this;
	}
	
	@Override
	public @NonNull Instant instant() {
		return this.now;
	}
}
