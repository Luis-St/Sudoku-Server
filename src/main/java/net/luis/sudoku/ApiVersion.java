package net.luis.sudoku;

/**
 * The REST/WebSocket API version this server speaks.
 * <p>
 * Distinct from shared-core's {@code genVersion}: this one governs the shape of the HTTP contract,
 * that one governs puzzle generation. A client may need to reject on either.
 */
public final class ApiVersion {
	
	public static final int CURRENT = 1;
	
	public static final String PATH_PREFIX = "/api/v" + CURRENT;
	
	public static final String WS_PATH_PREFIX = "/ws/v" + CURRENT;
	
	private ApiVersion() {}
}
