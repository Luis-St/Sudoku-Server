package net.luis.sudoku.auth;

import net.luis.sudoku.db.Database;
import net.luis.sudoku.domain.*;
import net.luis.sudoku.error.ApiException;
import net.luis.sudoku.error.ErrorCode;
import net.luis.sudoku.repository.*;
import net.luis.sudoku.security.CodeGenerator;
import net.luis.utils.io.database.exception.SqlException;
import net.luis.utils.io.database.transaction.SqlTransaction;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Issues and resolves session tokens, and enforces that a user has at most one (server-spec 6.2).
 */
public final class SessionService {
	
	private static final Logger log = LoggerFactory.getLogger(SessionService.class);
	
	public static final Duration SESSION_TTL = Duration.ofDays(30);
	
	/** The close reason a displaced client sees; it returns to the offline state showing this. */
	public static final String SUPERSEDED_REASON = "SESSION_SUPERSEDED";
	
	private final Database database;
	private final SessionRepository sessions;
	private final UserRepository users;
	private final DeviceRepository devices;
	private final CodeGenerator codes;
	private final SessionCloser closer;
	
	public SessionService(@NonNull Database database, @NonNull SessionRepository sessions, @NonNull UserRepository users,
	                      @NonNull DeviceRepository devices, @NonNull CodeGenerator codes, @NonNull SessionCloser closer) {
		this.database = database;
		this.sessions = sessions;
		this.users = users;
		this.devices = devices;
		this.codes = codes;
		this.closer = closer;
	}
	
	/**
	 * Issues a session inside the caller's transaction, displacing any existing one for that user.
	 * <p>
	 * The socket close happens after the transaction commits, not here - dropping a connection for a
	 * login that then rolled back would sign the user out for nothing. Callers use
	 * {@link #announceSuperseded} once committed.
	 *
	 * @return the newly issued session, and the displaced one if there was any
	 */
	public @NonNull Issued issue(@NonNull SqlTransaction connection, @NonNull UUID userId, @NonNull UUID deviceId,
	                             @NonNull Instant now) throws SqlException {
		Session session = new Session(this.codes.sessionToken(), userId, deviceId, now, now.plus(SESSION_TTL));
		Session displaced = this.sessions.replace(connection, session);
		this.devices.touch(connection, deviceId, now);
		return new Issued(session, displaced);
	}
	
	/**
	 * Closes the displaced client's sockets. Call only after the issuing transaction has committed.
	 */
	public void announceSuperseded(@Nullable Session displaced) {
		if (displaced != null) {
			log.info("Session for user {} superseded by a newer login", displaced.userId());
			this.closer.closeSocketsFor(displaced.userId(), SUPERSEDED_REASON);
		}
	}
	
	/**
	 * Resolves a bearer token to the caller behind it.
	 *
	 * @throws ApiException if the token is unknown, expired, or belongs to a revoked user or device
	 */
	public @NonNull Principal authenticate(@NonNull String token, @NonNull Instant now) {
		return this.database.read(connection -> {
			Session session = this.sessions.findByToken(connection, token);
			if (session == null) {
				throw new ApiException(ErrorCode.UNAUTHORIZED, "Unknown session token");
			}
			if (session.isExpired(now)) {
				throw new ApiException(ErrorCode.UNAUTHORIZED, "Session expired");
			}
			
			User user = this.users.find(connection, session.userId());
			Device device = this.devices.find(connection, session.deviceId());
			if (user == null || device == null) {
				// The session outlived what it points at; treat it as gone rather than 500.
				throw new ApiException(ErrorCode.UNAUTHORIZED, "Session no longer resolves to a device");
			}
			if (user.revoked()) {
				throw new ApiException(ErrorCode.USER_REVOKED, "This account has been removed");
			}
			if (device.revoked()) {
				throw new ApiException(ErrorCode.UNAUTHORIZED, "This device has been revoked");
			}
			return new Principal(user, device, session);
		});
	}
	
	public void endSession(@NonNull String token) {
		this.database.execute(connection -> this.sessions.deleteByToken(connection, token));
	}
	
	/**
	 * @param session the freshly issued session
	 * @param displaced the session it replaced, or null if the user had none
	 */
	public record Issued(@NonNull Session session, @Nullable Session displaced) {}
}
