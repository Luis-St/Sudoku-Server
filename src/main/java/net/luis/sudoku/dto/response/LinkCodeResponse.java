package net.luis.sudoku.dto.response;

import net.luis.sudoku.device.DeviceLinkService.LinkCode;
import org.jspecify.annotations.NonNull;

/**
 * Response to {@code POST /api/v1/devices/link-code}.
 *
 * @param code the short code to type on the new device
 * @param expiresAt ISO-8601 expiry, minutes away
 */
public record LinkCodeResponse(@NonNull String code, @NonNull String expiresAt) {
	
	public static @NonNull LinkCodeResponse of(@NonNull LinkCode linkCode) {
		return new LinkCodeResponse(linkCode.code(), linkCode.expiresAt().toString());
	}
}
