package net.luis.sudoku.match;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Map;

/**
 * Every WebSocket frame (server-spec 10.2):
 * <pre>{ "type": "PLACE", "seq": 42, "ts": 1750000000000, "payload": { } }</pre>
 * <p>
 * {@code seq} is a per-client monotonic counter. The server acknowledges the highest one it has
 * processed, which is what makes replay after a reconnect idempotent: a client re-sending everything
 * above the acked {@code seq} cannot double-apply anything the server already saw.
 *
 * @param type what this frame is
 * @param seq per-client monotonic sequence number; server-authored frames use 0
 * @param ts epoch milliseconds at which the frame was created
 * @param payload type-specific fields, empty rather than null when there are none
 */
public record MessageEnvelope(@NonNull String type, long seq, long ts, @Nullable Map<String, Object> payload) {
	
	public static @NonNull MessageEnvelope of(@NonNull MessageType type, @NonNull Map<String, Object> payload) {
		return new MessageEnvelope(type.name(), 0, System.currentTimeMillis(), payload);
	}
	
	public static @NonNull MessageEnvelope of(@NonNull MessageType type) {
		return of(type, Map.of());
	}
	
	public @NonNull Map<String, Object> payloadOrEmpty() {
		return this.payload == null ? Map.of() : this.payload;
	}
}
