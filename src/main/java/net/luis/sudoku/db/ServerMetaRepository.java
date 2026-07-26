package net.luis.sudoku.db;

import net.luis.utils.io.database.exception.SqlException;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.security.SecureRandom;
import java.sql.*;
import java.util.HexFormat;

/**
 * Key/value access to {@code server_meta}, and the home of the {@code server_id} bootstrap.
 */
public final class ServerMetaRepository {
	
	/**
	 * 128 bits, per server-spec 3.1.
	 */
	private static final int SERVER_ID_BYTES = 16;
	public static final String SERVER_ID_KEY = "server_id";
	private final Database database;
	
	public ServerMetaRepository(@NonNull Database database) {
		this.database = database;
	}
	
	private static @Nullable String read(@NonNull Connection connection, @NonNull String key) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("SELECT value FROM server_meta WHERE key = ?")) {
			statement.setString(1, key);
			try (ResultSet result = statement.executeQuery()) {
				return result.next() ? result.getString(1) : null;
			}
		}
	}
	
	public @Nullable String find(@NonNull String key) {
		return this.database.read(transaction -> {
			try {
				return read(transaction.getConnection(), key);
			} catch (SQLException e) {
				throw new SqlException("Failed to read server_meta", e);
			}
		});
	}
	
	public void put(@NonNull String key, @NonNull String value) {
		String sql = "INSERT INTO server_meta (key, value) VALUES (?, ?) ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value";
		this.database.execute(transaction -> {
			try (PreparedStatement statement = transaction.getConnection().prepareStatement(sql)) {
				statement.setString(1, key);
				statement.setString(2, value);
				statement.executeUpdate();
			} catch (SQLException e) {
				throw new SqlException("Failed to write server_meta", e);
			}
		});
	}
	
	/**
	 * Returns this deployment's {@code serverId}, generating and persisting it on first startup.
	 * <p>
	 * The value seeds every daily puzzle (server-spec 8), so it must never change for the lifetime of a
	 * deployment - changing it resets every daily and orphans historical results. The
	 * {@code DO NOTHING} insert makes the bootstrap safe even if two processes race: the loser reads
	 * back the winner's value rather than overwriting it.
	 *
	 * @return a 32-character lowercase hex string
	 */
	public @NonNull String serverId() {
		String existing = this.find(SERVER_ID_KEY);
		if (existing != null) {
			return existing;
		}
		
		byte[] random = new byte[SERVER_ID_BYTES];
		new SecureRandom().nextBytes(random);
		String generated = HexFormat.of().formatHex(random);
		
		String sql = "INSERT INTO server_meta (key, value) VALUES (?, ?) ON CONFLICT (key) DO NOTHING";
		this.database.execute(transaction -> {
			try (PreparedStatement statement = transaction.getConnection().prepareStatement(sql)) {
				statement.setString(1, SERVER_ID_KEY);
				statement.setString(2, generated);
				statement.executeUpdate();
			} catch (SQLException e) {
				throw new SqlException("Failed to bootstrap server id", e);
			}
		});
		
		String stored = this.find(SERVER_ID_KEY);
		if (stored == null) {
			throw new DatabaseException("Server id vanished immediately after being written");
		}
		return stored;
	}
}
