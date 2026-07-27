package net.luis.sudoku.db;

import net.luis.sudoku.db.schema.Schema;
import net.luis.utils.io.database.Sql;
import net.luis.utils.io.database.SqlConnectionSource;
import net.luis.utils.io.database.query.crud.SqlInsertQuery;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;

import static net.luis.sudoku.db.schema.Schema.*;

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
	
	public @Nullable String find(@NonNull String key) {
		return this.database.read(transaction -> transaction.from(SERVER_META).select(META_VALUE).where(Sql.equalTo(META_KEY, key)).fetchOneOrNull());
	}
	
	/**
	 * Whole-row upsert: the only other column, {@code value}, always moves to the given value.
	 */
	public void put(@NonNull String key, @NonNull String value) {
		this.database.execute(transaction -> SqlInsertQuery.upsert(SERVER_META, transaction.getDialect(), SqlConnectionSource.fixed(transaction.getConnection()), transaction.getQueryTimeout(), resultSet -> null, List.of(new Schema.ServerMetaRow(key, value)), META_KEY).execute());
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
		
		this.database.execute(transaction -> transaction.from(SERVER_META).insert(new Schema.ServerMetaRow(SERVER_ID_KEY, generated), META_KEY).execute());
		
		String stored = this.find(SERVER_ID_KEY);
		if (stored == null) {
			throw new DatabaseException("Server id vanished immediately after being written");
		}
		return stored;
	}
}
