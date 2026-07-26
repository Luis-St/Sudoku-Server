package net.luis.sudoku.db;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Unchecked wrapper for {@link java.sql.SQLException}, so repository signatures stay readable.
 * <p>
 * Nothing recovers from these by inspecting the cause; they either abort startup or become a 500.
 */
public class DatabaseException extends RuntimeException {
	
	public DatabaseException(@NonNull String message) {
		super(message);
	}
	
	public DatabaseException(@NonNull String message, @Nullable Throwable cause) {
		super(message, cause);
	}
}
