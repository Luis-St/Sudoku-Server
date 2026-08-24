package net.luis.sudoku;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for {@link BuildVersion}.
 */
class BuildVersionTest {
	
	@Test
	void current_isTheVersionTheBuildStamped() {
		// The guard on the build plumbing rather than on the constant: the version lives in
		// build.gradle.kts and reaches the running server only through the generated resource, so a
		// generateVersionResource that stopped running would show up here and nowhere else until
		// /health started answering "unknown" in production.
		assertAll(
			() -> assertNotEquals(BuildVersion.UNKNOWN, BuildVersion.CURRENT, "the version resource is on the classpath"),
			() -> assertTrue(BuildVersion.CURRENT.matches("\\d+\\.\\d+\\.\\d+.*"), "and reads as a version, was: " + BuildVersion.CURRENT)
		);
	}
}
