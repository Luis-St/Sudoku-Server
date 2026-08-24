package net.luis.sudoku.handler;

import net.luis.sudoku.support.MovableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for {@link HealthHandler}.
 */
class HealthHandlerTest {
	
	private static HealthHandler handler(MovableClock clock) {
		return new HealthHandler("2.0.0", 8, () -> 0, () -> true, clock);
	}
	
	@Test
	void uptimeSeconds_onAProcessThatHasJustStarted_isZero() {
		assertEquals(0L, handler(new MovableClock()).uptimeSeconds());
	}
	
	@Test
	void uptimeSeconds_asTimePasses_growsWithIt() {
		// The field exists to catch a crash-looping container, which is a process whose uptime keeps
		// resetting instead of climbing. Off the injected clock rather than System.currentTimeMillis(),
		// which is the only reason this can be asserted at all rather than slept for.
		MovableClock clock = new MovableClock();
		HealthHandler handler = handler(clock);
		
		clock.advance(Duration.ofMinutes(90));
		
		assertEquals(5400L, handler.uptimeSeconds());
	}
	
	@Test
	void uptimeSeconds_withAClockThatJumpedBackwards_isZeroRatherThanNegative() {
		// A host clock correction is not something the health endpoint should report as a negative age to
		// whatever is parsing it.
		MovableClock clock = new MovableClock();
		HealthHandler handler = handler(clock);
		
		clock.advance(Duration.ofMinutes(-5));
		
		assertEquals(0L, handler.uptimeSeconds());
	}
}
