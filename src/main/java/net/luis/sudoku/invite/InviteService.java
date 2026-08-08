package net.luis.sudoku.invite;

import net.luis.sudoku.db.Database;
import net.luis.sudoku.domain.Invite;
import net.luis.sudoku.domain.Principal;
import net.luis.sudoku.error.ApiException;
import net.luis.sudoku.permission.Permission;
import net.luis.sudoku.permission.Role;
import net.luis.sudoku.repository.InviteRepository;
import net.luis.sudoku.security.CodeGenerator;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Issuing, listing and revoking invites (server-spec 7).
 */
public final class InviteService {
	
	private static final Logger log = LoggerFactory.getLogger(InviteService.class);
	
	private final Database database;
	private final InviteRepository invites;
	private final CodeGenerator codes;
	private final Clock clock;
	
	public InviteService(@NonNull Database database, @NonNull InviteRepository invites, @NonNull CodeGenerator codes, @NonNull Clock clock) {
		this.database = database;
		this.invites = invites;
		this.codes = codes;
		this.clock = clock;
	}
	
	/**
	 * Creates an invite granting {@link Role#NEW}.
	 * <p>
	 * The granted role is fixed rather than chosen by the caller: letting an inviter pick the role would
	 * make {@code CAN_INVITE} a backdoor to {@code CAN_CHANGE_ROLE}. Promotion is a separate, admin-only
	 * action.
	 */
	public @NonNull Invite create(@NonNull Principal actor, @Nullable Instant expiresAt) {
		actor.require(Permission.CAN_INVITE);
		if (expiresAt != null && !expiresAt.isAfter(this.clock.instant())) {
			throw ApiException.badRequest("expiresAt must be in the future");
		}
		
		String code = CodeGenerator.normalize(this.codes.inviteCode());
		Instant now = this.clock.instant();
		Invite invite = this.database.transaction(connection -> this.invites.create(connection, code, actor.userId(), Role.NEW, expiresAt, now));
		
		// Warn, like every other "Admin action:" line - see UserAdminService. An invite is what lets a new
		// account exist at all on a closed server, so who minted one has to survive an info-free log.
		log.warn("Admin action: {} ({}) created invite {}", actor.user().displayName(), actor.userId(), code);
		return invite;
	}
	
	/**
	 * @return every invite for an admin, or only the caller's own otherwise (server-spec 7)
	 */
	public @NonNull List<Invite> list(@NonNull Principal actor) {
		actor.require(Permission.CAN_INVITE);
		return this.database.read(connection -> this.invites.findByCreator(connection, actor.user().isAdmin() ? null : actor.userId()));
	}
	
	/**
	 * Revokes an unused invite. Permitted for its creator or any admin.
	 */
	public void revoke(@NonNull Principal actor, @NonNull String rawCode) {
		String code = CodeGenerator.normalize(rawCode);
		
		this.database.execute(connection -> {
			Invite invite = this.invites.find(connection, code);
			if (invite == null) {
				throw ApiException.notFound("No such invite");
			}
			boolean isCreator = actor.userId().equals(invite.createdBy());
			if (!isCreator && !actor.user().isAdmin()) {
				throw ApiException.forbidden("Only the invite's creator or an admin may revoke it");
			}
			if (invite.consumedAt() != null) {
				throw ApiException.badRequest("That invite has already been used");
			}
			this.invites.revoke(connection, code);
		});
		
		log.warn("Admin action: {} ({}) revoked invite {}", actor.user().displayName(), actor.userId(), code);
	}
}
