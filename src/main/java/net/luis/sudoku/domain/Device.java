package net.luis.sudoku.domain;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * One device belonging to a user, identified by the public half of its keypair.
 * <p>
 * The server never holds a private key or any other impersonation secret (server-spec 12).
 *
 * @param id primary key
 * @param userId owning user
 * @param publicKey X.509-encoded public key
 * @param keyAlgorithm how to verify signatures from this device
 * @param label human-readable name shown in the device list
 * @param createdAt when the device was registered or linked
 * @param lastSeenAt last successful authentication, null if never
 * @param revoked true once the key may no longer authenticate
 * @param revokedByKick true if {@link #revoked} was set by a kick rather than by dropping this device
 *   deliberately. Reinstating a user restores exactly these keys: a device its owner revoked - a phone
 *   they lost, say - must stay dead through a kick and a reinstatement, and the two are indistinguishable
 *   without recording which of them did it.
 * @param forceUpdate true while this device is to throw away its local copy of the account's state and
 *   take the server's instead (server-spec 7.3). <b>Nothing in this application ever writes it.</b> It is
 *   set by hand, in SQL, from whatever tool the operator manages the database with, and cleared the same
 *   way - which is why it sits on the device rather than on the user: a repair is aimed at the one client
 *   that has drifted, and the account's other devices are not to be disturbed by it.
 */
public record Device(
	@NonNull UUID id,
	@NonNull UUID userId,
	byte @NonNull [] publicKey,
	@NonNull KeyAlgorithm keyAlgorithm,
	@NonNull String label,
	@NonNull Instant createdAt,
	@Nullable Instant lastSeenAt,
	boolean revoked,
	boolean revokedByKick,
	boolean forceUpdate
) {
	
	public Device {
		// Records copy the array reference, so without this a caller could mutate a stored key.
		publicKey = publicKey.clone();
	}
	
	@Override
	public byte @NonNull [] publicKey() {
		return this.publicKey.clone();
	}
}
