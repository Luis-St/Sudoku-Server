package net.luis.sudoku.compat;

import net.luis.sudoku.difficulty.Difficulty;
import net.luis.sudoku.error.ApiException;
import net.luis.sudoku.error.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for {@link LegacyDifficulty}, the one place that still knows the six-tier scale.
 */
class LegacyDifficultyTest {
	
	// --- fromLegacy ---
	
	@Test
	void fromLegacy_atEachAnchor_namesTheAgreedTier() {
		assertAll(
			() -> assertEquals(Difficulty.ONE, LegacyDifficulty.fromLegacy(1)),
			() -> assertEquals(Difficulty.FOUR, LegacyDifficulty.fromLegacy(2)),
			() -> assertEquals(Difficulty.SEVEN, LegacyDifficulty.fromLegacy(3)),
			() -> assertEquals(Difficulty.TEN, LegacyDifficulty.fromLegacy(4)),
			() -> assertEquals(Difficulty.THIRTEEN, LegacyDifficulty.fromLegacy(5))
		);
	}
	
	@Test
	void fromLegacy_atSix_isLisa() {
		// The one legacy value whose meaning did not move: 6 was Lisa and Lisa it stays.
		assertEquals(Difficulty.LISA, LegacyDifficulty.fromLegacy(LegacyDifficulty.LEGACY_LISA));
	}
	
	@Test
	void fromLegacy_isStrictlyIncreasing() {
		int previous = 0;
		for (int legacy = 1; legacy <= LegacyDifficulty.LEGACY_LISA; legacy++) {
			int index = LegacyDifficulty.fromLegacy(legacy).index();
			assertTrue(index > previous, "legacy " + legacy + " maps to " + index + " after " + previous);
			previous = index;
		}
	}
	
	@Test
	void fromLegacy_belowTheRange_isRejected() {
		ApiException e = assertThrows(ApiException.class, () -> LegacyDifficulty.fromLegacy(0));
		assertAll(
			() -> assertEquals(ErrorCode.BAD_REQUEST, e.code()),
			() -> assertEquals(400, e.status())
		);
	}
	
	@Test
	void fromLegacy_aboveTheRange_isRejected() {
		// 7 is a perfectly good real tier and is still nonsense from a v1 client, which never had one.
		assertAll(
			() -> assertThrows(ApiException.class, () -> LegacyDifficulty.fromLegacy(7)),
			() -> assertThrows(ApiException.class, () -> LegacyDifficulty.fromLegacy(15)),
			() -> assertThrows(ApiException.class, () -> LegacyDifficulty.fromLegacy(-1))
		);
	}
	
	// --- toLegacy ---
	
	@Test
	void toLegacy_ofLisa_isSix() {
		assertEquals(LegacyDifficulty.LEGACY_LISA, LegacyDifficulty.toLegacy(Difficulty.LISA));
	}
	
	@Test
	void toLegacy_ofAnAnchor_isThatAnchorsIndex() {
		assertAll(
			() -> assertEquals(1, LegacyDifficulty.toLegacy(Difficulty.ONE)),
			() -> assertEquals(2, LegacyDifficulty.toLegacy(Difficulty.FOUR)),
			() -> assertEquals(3, LegacyDifficulty.toLegacy(Difficulty.SEVEN)),
			() -> assertEquals(4, LegacyDifficulty.toLegacy(Difficulty.TEN)),
			() -> assertEquals(5, LegacyDifficulty.toLegacy(Difficulty.THIRTEEN))
		);
	}
	
	@Test
	void toLegacy_ofATierBetweenAnchors_snapsToTheNearest() {
		assertAll(
			() -> assertEquals(1, LegacyDifficulty.toLegacy(Difficulty.TWO)),
			() -> assertEquals(2, LegacyDifficulty.toLegacy(Difficulty.THREE)),
			() -> assertEquals(2, LegacyDifficulty.toLegacy(Difficulty.FIVE)),
			() -> assertEquals(3, LegacyDifficulty.toLegacy(Difficulty.SIX)),
			() -> assertEquals(3, LegacyDifficulty.toLegacy(Difficulty.EIGHT)),
			() -> assertEquals(4, LegacyDifficulty.toLegacy(Difficulty.NINE)),
			() -> assertEquals(4, LegacyDifficulty.toLegacy(Difficulty.ELEVEN)),
			() -> assertEquals(5, LegacyDifficulty.toLegacy(Difficulty.TWELVE)),
			() -> assertEquals(5, LegacyDifficulty.toLegacy(Difficulty.FOURTEEN))
		);
	}
	
	@Test
	void toLegacy_forEveryTier_staysInTheSixValueRange() {
		for (Difficulty difficulty : Difficulty.values()) {
			int legacy = LegacyDifficulty.toLegacy(difficulty);
			assertTrue(legacy >= 1 && legacy <= LegacyDifficulty.LEGACY_LISA, difficulty + " mapped to " + legacy);
		}
	}
	
	@Test
	void toLegacy_isNonDecreasing() {
		int previous = 0;
		for (Difficulty difficulty : Difficulty.values()) {
			int legacy = LegacyDifficulty.toLegacy(difficulty);
			assertTrue(legacy >= previous, difficulty + " maps down to " + legacy + " after " + previous);
			previous = legacy;
		}
	}
	
	// --- round trip ---
	
	@Test
	void toLegacy_ofFromLegacy_isTheIdentityOnEveryAnchor() {
		// The property the whole compatibility layer rests on: a v1 client that reads a tier back, stores it
		// and sends it again must get the band it started from.
		for (int legacy = 1; legacy <= LegacyDifficulty.LEGACY_LISA; legacy++) {
			assertEquals(legacy, LegacyDifficulty.toLegacy(LegacyDifficulty.fromLegacy(legacy)), "legacy " + legacy);
		}
	}
	
	@Test
	void fromLegacy_ofToLegacy_isNotTheIdentity_forATierV1CannotName() {
		// Deliberately lossy the other way: fifteen values do not fit in six, and a v2 tier reduced for a v1
		// client cannot come back unchanged. Documented rather than pretended away.
		assertNotEquals(Difficulty.FIVE, LegacyDifficulty.fromLegacy(LegacyDifficulty.toLegacy(Difficulty.FIVE)));
	}
	
	// --- legacyTiersHighestFirst, and the properties SchemaMigration's rescale depends on ---
	
	@Test
	void legacyTiersHighestFirst_isEveryLegacyTierDescending() {
		assertEquals(List.of(6, 5, 4, 3, 2, 1), LegacyDifficulty.legacyTiersHighestFirst());
	}
	
	@Test
	void fromLegacy_isInjective_soRewritingAPrimaryKeyColumnCannotCollide() {
		// stats and daily_leaderboard carry the difficulty inside their primary key, so the rescale is only
		// safe if no two legacy tiers land on one real tier - otherwise a migration merges two players'
		// records into one row and loses the other.
		Set<Integer> real = new HashSet<>();
		for (int legacy = 1; legacy <= LegacyDifficulty.LEGACY_LISA; legacy++) {
			assertTrue(real.add(LegacyDifficulty.fromLegacy(legacy).index()), "legacy " + legacy + " collides");
		}
		assertEquals(LegacyDifficulty.LEGACY_LISA, real.size());
	}
	
	@Test
	void rewritingInPlace_highestFirst_touchesNoRowTwice() {
		// The migration rewrites a column with one statement per legacy value, in this order. Replayed here
		// over a column holding every legacy value at once: if the order were wrong, a row already moved to
		// its real tier would be found again by a later statement and moved a second time. Ascending, legacy
		// 2 becomes 4 and the statement for legacy 4 then drags those same rows on to 10.
		Map<Integer, Integer> column = new HashMap<>();
		for (int legacy = 1; legacy <= LegacyDifficulty.LEGACY_LISA; legacy++) {
			column.put(legacy, legacy); // Row keyed by the legacy tier it started at, valued by its current one.
		}
		
		for (int legacy : LegacyDifficulty.legacyTiersHighestFirst()) {
			int real = LegacyDifficulty.fromLegacy(legacy).index();
			column.replaceAll((startedAt, current) -> current == legacy ? real : current);
		}
		
		for (int legacy = 1; legacy <= LegacyDifficulty.LEGACY_LISA; legacy++) {
			assertEquals(LegacyDifficulty.fromLegacy(legacy).index(), column.get(legacy), "row that started at legacy " + legacy);
		}
	}
}
