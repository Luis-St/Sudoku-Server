package net.luis.sudoku.dto.response;

import net.luis.sudoku.domain.Device;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * A device in the caller's device list (server-spec 6.5).
 * <p>
 * The public key is deliberately not exposed: the list exists so a user can recognise and revoke their
 * own hardware, and a key fingerprint would add nothing but noise.
 *
 * @param id device id, used to revoke it
 * @param label human-readable name
 * @param keyAlgorithm the signature algorithm this device uses
 * @param createdAt ISO-8601 registration time
 * @param lastSeenAt ISO-8601 last successful authentication, or null if never
 * @param revoked whether the key has been withdrawn
 * @param current whether this is the device making the request
 */
public record DeviceResponse(
	@NonNull String id,
	@NonNull String label,
	@NonNull String keyAlgorithm,
	@NonNull String createdAt,
	@Nullable String lastSeenAt,
	boolean revoked,
	boolean current
) {
	
	public static @NonNull DeviceResponse of(@NonNull Device device, @NonNull UUID currentDeviceId) {
		return new DeviceResponse(
			device.id().toString(),
			device.label(),
			device.keyAlgorithm().name(),
			device.createdAt().toString(),
			device.lastSeenAt() == null ? null : device.lastSeenAt().toString(),
			device.revoked(),
			device.id().equals(currentDeviceId)
		);
	}
}
