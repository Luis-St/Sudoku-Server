package net.luis.sudoku.match;

import net.luis.sudoku.compat.LegacyDifficulty;
import net.luis.sudoku.difficulty.Difficulty;
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
	
	/**
	 * The {@code puzzleKey} block of a {@code MATCH_STATE} frame, in its v2 shape: the real fifteen-tier
	 * index, plus the givens so the client draws the board instead of generating it.
	 *
	 * @param key The match's puzzle key
	 * @param givens The encoded givens of the grid the match is played on
	 * @return The payload block
	 */
	static @NonNull Map<String, Object> key(@NonNull PuzzleKey key, @NonNull String givens) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("genVersion", key.genVersion());
		map.put("size", key.size().n());
		map.put("variant", key.variant().name());
		map.put("difficulty", key.difficulty().index());
		// A string, because a 64-bit seed does not survive a JSON double.
		map.put("seed", Long.toString(key.seed()));
		map.put("givens", givens);
		return map;
	}
	
	/**
	 * Reduces a {@code MATCH_STATE} frame to the shape a v1 socket expects: the six-tier difficulty integer
	 * and no givens.
	 * <p>
	 * Done here, once, at the point of sending, rather than by building two payloads in every mode: the
	 * three modes each assemble their own snapshot and the only thing that differs between the versions is
	 * this one block.
	 *
	 * @param message The v2 frame
	 * @return The same frame with its {@code puzzleKey} block downgraded, or the frame itself if it carries
	 *   no key
	 */
	static @NonNull MessageEnvelope downgradeState(@NonNull MessageEnvelope message) {
		Map<String, Object> payload = message.payloadOrEmpty();
		if (!(payload.get("puzzleKey") instanceof Map<?, ?> key)) {
			return message;
		}
		
		Map<String, Object> downgraded = new LinkedHashMap<>();
		key.forEach((name, value) -> {
			if ("givens".equals(name)) {
				return;
			}
			if ("difficulty".equals(name) && value instanceof Integer index) {
				downgraded.put("difficulty", LegacyDifficulty.toLegacy(Difficulty.ofIndex(index)));
				return;
			}
			downgraded.put(String.valueOf(name), value);
		});
		
		Map<String, Object> copy = new LinkedHashMap<>(payload);
		copy.put("puzzleKey", downgraded);
		return new MessageEnvelope(message.type(), message.seq(), message.ts(), copy);
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
