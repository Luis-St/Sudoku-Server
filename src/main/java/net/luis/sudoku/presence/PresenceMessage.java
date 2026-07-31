package net.luis.sudoku.presence;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Map;

/**
 * A frame on the presence socket, {@code WS /ws/v1/presence}.
 * <p>
 * Deliberately not {@code MessageEnvelope}/{@code MessageType}: those are the match protocol
 * (server-spec 10.2), whose {@code seq}/replay semantics mean nothing here - this socket carries no
 * game state, only who is online and who wants to play.
 *
 * @param type {@link Type} name
 * @param ts epoch milliseconds at which the frame was created
 * @param payload type-specific fields, empty rather than null when there are none
 */
public record PresenceMessage(@NonNull String type, long ts, @Nullable Map<String, Object> payload) {
	
	public static @NonNull PresenceMessage of(@NonNull Type type, @NonNull Map<String, Object> payload) {
		return new PresenceMessage(type.name(), System.currentTimeMillis(), payload);
	}
	
	public @NonNull Map<String, Object> payloadOrEmpty() {
		return this.payload == null ? Map.of() : this.payload;
	}
	
	/**
	 * Every frame this socket may carry. All are server-authored: the client only ever listens, so
	 * there is no direction to enforce the way the match socket has to.
	 */
	public enum Type {
		
		/** {@code { userIds: [...] }} - the full online set, sent on connect and whenever it changes. */
		ONLINE,
		/**
		 * {@code { matchId, inviteToken, mode, fromUserId, fromDisplayName }} - another player wants to
		 * play. The match already exists and the token is the ordinary join token, so accepting is just
		 * {@code POST /matches/{id}/join}.
		 */
		MATCH_REQUEST
	}
}
