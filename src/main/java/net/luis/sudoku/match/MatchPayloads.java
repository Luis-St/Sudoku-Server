package net.luis.sudoku.match;

import net.luis.sudoku.grid.GridSize;
import net.luis.sudoku.key.PuzzleKey;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.*;

/**
 * Payload parsing and building shared by the match modes.
 * <p>
 * Every cell coordinate and digit is validated against the match's own grid size, never trusted
 * (server-spec 12).
 */
final class MatchPayloads {
	
	private MatchPayloads() {}
	
	/**
	 * @return the cell index if it is within this grid, otherwise null
	 */
	static @Nullable Integer cell(@NonNull Map<String, Object> payload, @NonNull GridSize size) {
		Integer value = integer(payload.get("cell"));
		if (value == null || value < 0 || value >= size.cellCount()) {
			return null;
		}
		return value;
	}
	
	/**
	 * @return the digit if it is legal for this grid, otherwise null
	 */
	static @Nullable Integer digit(@NonNull Map<String, Object> payload, @NonNull GridSize size) {
		Integer value = integer(payload.get("digit"));
		if (value == null || !size.isValidDigit(value)) {
			return null;
		}
		return value;
	}
	
	static boolean flag(@NonNull Map<String, Object> payload, @NonNull String key) {
		return payload.get(key) instanceof Boolean value && value;
	}
	
	/**
	 * JSON numbers arrive as Integer, Long or Double depending on the mapper, so all three are accepted
	 * rather than assuming one.
	 */
	static @Nullable Integer integer(@Nullable Object value) {
		return switch (value) {
			case Integer i -> i;
			case Long l -> l.intValue();
			case Number n -> n.intValue();
			case String s -> {
				try {
					yield Integer.valueOf(s.trim());
				} catch (NumberFormatException e) {
					yield null;
				}
			}
			case null, default -> null;
		};
	}
	
	static @NonNull Map<String, Object> key(@NonNull PuzzleKey key) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("genVersion", key.genVersion());
		map.put("size", key.size().n());
		map.put("variant", key.variant().name());
		map.put("difficulty", key.difficulty().index());
		// A string, because a 64-bit seed does not survive a JSON double.
		map.put("seed", Long.toString(key.seed()));
		return map;
	}
	
	static @NonNull List<Map<String, Object>> participants(@NonNull Collection<LiveMatch.ParticipantState> participants) {
		return participants.stream()
			.map(participant -> {
				Map<String, Object> map = new LinkedHashMap<>();
				map.put("userId", participant.userId().toString());
				map.put("displayName", participant.displayName());
				map.put("connected", participant.connected);
				map.put("ready", participant.ready);
				return map;
			})
			.toList();
	}
}
