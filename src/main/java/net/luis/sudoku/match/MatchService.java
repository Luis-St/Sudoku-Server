package net.luis.sudoku.match;

import net.luis.sudoku.config.ServerConfig;
import net.luis.sudoku.currency.CurrencyService;
import net.luis.sudoku.db.Database;
import net.luis.sudoku.difficulty.Difficulty;
import net.luis.sudoku.domain.*;
import net.luis.sudoku.error.ApiException;
import net.luis.sudoku.error.ErrorCode;
import net.luis.sudoku.generation.GeneratedPuzzle;
import net.luis.sudoku.grid.GridSize;
import net.luis.sudoku.grid.Variant;
import net.luis.sudoku.permission.Permission;
import net.luis.sudoku.puzzle.PuzzleFactory;
import net.luis.sudoku.puzzle.PuzzleQueue;
import net.luis.sudoku.repository.MatchRepository;
import net.luis.sudoku.security.CodeGenerator;
import net.luis.sudoku.security.ConstantTime;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Match creation, joining and settlement (server-spec 10).
 * <p>
 * Owns the boundary between the durable half (rows in {@code matches}) and the live half
 * ({@link LiveMatch} objects in the {@link MatchRegistry}).
 */
public final class MatchService {
	
	private static final Logger log = LoggerFactory.getLogger(MatchService.class);
	
	private final Database database;
	private final MatchRepository matches;
	private final MatchRegistry registry;
	private final PuzzleQueue puzzles;
	private final CurrencyService currency;
	private final ServerConfig config;
	private final CodeGenerator codes;
	private final Clock clock;
	
	public MatchService(
		@NonNull Database database, @NonNull MatchRepository matches, @NonNull MatchRegistry registry, @NonNull PuzzleQueue puzzles, @NonNull CurrencyService currency,
		@NonNull ServerConfig config, @NonNull CodeGenerator codes, @NonNull Clock clock
	) {
		this.database = database;
		this.matches = matches;
		this.registry = registry;
		this.puzzles = puzzles;
		this.currency = currency;
		this.config = config;
		this.codes = codes;
		this.clock = clock;
	}
	
	/**
	 * Creates a match and registers its live counterpart.
	 *
	 * @throws ApiException {@code LISA_NOT_ALLOWED} if Lisa is requested (spec 10.1)
	 */
	public @NonNull Created create(@NonNull Principal actor, @NonNull MatchMode mode, @NonNull GridSize size, @NonNull Variant variant, @NonNull Difficulty difficulty, boolean livesEnabled, int stake) {
		actor.require(Permission.CAN_PLAY);
		// Lisa carries single-player gameplay modifiers, so it is refused for every mode (spec 10.1).
		PuzzleFactory.requireMultiplayerSafe(difficulty);
		if (!variant.isSupportedAt(size)) {
			throw ApiException.badRequest(variant + " is not supported at " + size.n() + "x" + size.n());
		}
		if (stake < 0) {
			throw ApiException.badRequest("stake must not be negative");
		}
		
		// Taken from the pre-generation pool so creation never blocks on generation (spec: keep the
		// queue at 2x the active-player count).
		GeneratedPuzzle puzzle = this.puzzles.take(size, variant, difficulty);
		String inviteToken = this.codes.sessionToken();
		Instant now = this.clock.instant();
		
		Match match = this.database.transaction(connection -> {
			Match created = this.matches.create(connection, mode, actor.userId(), size, variant, difficulty, puzzle.key().seed(), livesEnabled, stake, inviteToken, now);
			this.matches.addParticipant(connection, created.id(), actor.userId(), now);
			return created;
		});
		
		LiveMatch live = this.buildLive(match, puzzle);
		live.submit(() -> {});
		this.registry.register(live);
		
		log.info("Match {} created by {} ({} {}x{} {} stake {})", match.id(), actor.userId(), mode, size.n(), size.n(),
			difficulty, stake);
		return new Created(match, inviteToken);
	}
	
	/**
	 * Joins a match, checking capacity, the invite token and the stake.
	 *
	 * @throws ApiException {@code MATCH_FULL} at capacity, {@code INSUFFICIENT_BALANCE} below the stake
	 */
	public @NonNull Match join(@NonNull Principal actor, @NonNull UUID matchId, @Nullable String inviteToken) {
		actor.require(Permission.CAN_PLAY);
		Instant now = this.clock.instant();
		
		Match joined = this.database.transaction(connection -> {
			Match match = this.matches.findForUpdate(connection, matchId);
			if (match == null) {
				throw ApiException.notFound("No such match: " + matchId);
			}
			if (match.state() != MatchState.WAITING && match.state() != MatchState.CREATED) {
				throw new ApiException(ErrorCode.CONFLICT, "That match is no longer accepting participants");
			}
			// Constant-time: the token is a secret, and a timing oracle would leak it prefix by prefix.
			if (!ConstantTime.equals(match.inviteToken(), inviteToken)) {
				throw ApiException.forbidden("Invalid invite token");
			}
			
			List<MatchParticipant> participants = this.matches.participants(connection, matchId);
			boolean alreadyIn = participants.stream().anyMatch(p -> p.userId().equals(actor.userId()));
			if (!alreadyIn) {
				if (participants.size() >= match.mode().capacity()) {
					throw new ApiException(ErrorCode.MATCH_FULL, "That match is full");
				}
				// Refuse early rather than at start, so a player learns before committing to a lobby.
				if (match.stake() > 0) {
					long balance = this.currency.balance(actor.userId());
					if (balance < match.stake()) {
						throw new ApiException(ErrorCode.INSUFFICIENT_BALANCE, "You need " + match.stake() + " Rhubarb to join this match");
					}
				}
				this.matches.addParticipant(connection, matchId, actor.userId(), now);
			}
			return match;
		});
		
		log.info("User {} joined match {}", actor.userId(), matchId);
		return joined;
	}
	
	/**
	 * Calls off a match nobody has joined yet, at its creator's request.
	 * <p>
	 * Only reachable before the match starts, which is what makes it simple: no stake has been escrowed
	 * (that happens on the {@code RUNNING} transition), and no participant but the creator exists, so
	 * cancelling is a state change and nothing else. A {@code RUNNING} match is refused - leaving one is
	 * resigning, which the socket already handles, and answers with a result rather than erasing it.
	 * <p>
	 * Pending requests for this match are deliberately <em>not</em> deleted: a request is only ever served
	 * while its match is joinable ({@code MatchRequestRepository.findPending}), so an {@code ABANDONED}
	 * match's invitations stop being delivered the moment this commits, and the rows expire on their own.
	 * <p>
	 * Idempotent for a match that is already over, so a client retrying a cancel it is unsure landed is not
	 * handed a failure for having succeeded.
	 *
	 * @throws ApiException {@code FORBIDDEN} if the caller did not create it, {@code CONFLICT} if it is
	 *   already running
	 */
	public void cancel(@NonNull Principal actor, @NonNull UUID matchId) {
		Instant now = this.clock.instant();

		boolean cancelled = this.database.transaction(connection -> {
			Match match = this.matches.findForUpdate(connection, matchId);
			if (match == null) {
				throw ApiException.notFound("No such match: " + matchId);
			}
			if (!match.creatorId().equals(actor.userId())) {
				throw ApiException.forbidden("Only the match's creator may cancel it");
			}
			if (match.state().isTerminal()) {
				return false;
			}
			if (match.state() == MatchState.RUNNING) {
				throw new ApiException(ErrorCode.CONFLICT, "That match has already started");
			}

			this.matches.markEnded(connection, matchId, MatchState.ABANDONED, null, EndReason.CANCELLED, now);
			return true;
		});

		if (cancelled) {
			// Outside the transaction: shutting the live object down is not something a rollback could undo,
			// and a match whose row is still WAITING but whose executor is gone would accept a join it could
			// never run.
			this.registry.remove(matchId);
			log.info("Match {} cancelled by its creator {}", matchId, actor.userId());
		}
	}

	public @NonNull Match get(@NonNull UUID matchId) {
		Match match = this.database.read(connection -> this.matches.find(connection, matchId));
		if (match == null) {
			throw ApiException.notFound("No such match: " + matchId);
		}
		return match;
	}
	
	public @NonNull List<MatchParticipant> participants(@NonNull UUID matchId) {
		return this.database.read(connection -> this.matches.participants(connection, matchId));
	}
	
	/**
	 * Confirms a user may attach a socket to this match.
	 */
	public boolean isParticipant(@NonNull UUID matchId, @NonNull UUID userId) {
		return this.participants(matchId).stream().anyMatch(participant -> participant.userId().equals(userId));
	}
	
	/**
	 * Rebuilds the live object for a match, used when a client connects to one the registry has lost.
	 */
	public @NonNull LiveMatch liveFor(@NonNull Match match) {
		LiveMatch existing = this.registry.find(match.id());
		if (existing != null) {
			return existing;
		}
		LiveMatch live = this.buildLive(match, PuzzleFactory.generate(match.key()));
		this.registry.register(live);
		return live;
	}
	
	private @NonNull LiveMatch buildLive(@NonNull Match match, @NonNull GeneratedPuzzle puzzle) {
		LiveMatch.MatchCallbacks callbacks = new PersistenceCallbacks();
		return switch (match.mode()) {
			case RACE -> new RaceMatch(match, puzzle, this.config.match(), callbacks);
			case DUEL -> new DuelMatch(match, puzzle, this.config.match(), this.config.duel(), callbacks);
			case COOP -> new CoopMatch(match, puzzle, this.config.match(), callbacks);
		};
	}
	
	/**
	 * Closes out matches left unfinished by a crash or restart (spec 9a.3).
	 * <p>
	 * Live board state is memory-resident, so there is nothing to resume: every such match is abandoned
	 * and every stake refunded.
	 *
	 * @return how many matches were recovered
	 */
	public int recoverAfterRestart() {
		return this.database.transaction(connection -> {
			List<Match> unfinished = this.matches.findUnfinished(connection);
			for (Match match : unfinished) {
				this.matches.markEnded(connection, match.id(), MatchState.ABANDONED, null, EndReason.SERVER_RESTART, this.clock.instant());
				// Only a RUNNING match ever escrowed anything, so only those need refunding.
				if (match.state() == MatchState.RUNNING && match.stake() > 0) {
					for (MatchParticipant participant : this.matches.participants(connection, match.id())) {
						this.currency.refund(connection, participant.userId(), match.stake(), match.id());
						this.matches.setResult(connection, match.id(), participant.userId(), MatchResult.ABANDONED);
					}
				}
			}
			if (!unfinished.isEmpty()) {
				log.info("Recovered {} unfinished matches after restart; stakes refunded", unfinished.size());
			}
			return unfinished.size();
		});
	}
	
	/**
	 * @param match the created match
	 * @param inviteToken the token another player needs to join
	 */
	public record Created(@NonNull Match match, @NonNull String inviteToken) {}
	
	/**
	 * Persistence and currency side effects for a live match.
	 */
	private final class PersistenceCallbacks implements LiveMatch.MatchCallbacks {
		
		@Override
		public void onStart(@NonNull Match match, @NonNull List<UUID> participants) {
			// Stakes and the RUNNING transition share one transaction, so a crash can never deduct
			// without starting the match (spec 9a.3).
			MatchService.this.database.execute(connection -> {
				for (UUID userId : participants) {
					MatchService.this.currency.escrowStake(connection, userId, match.stake(), match.id());
				}
				MatchService.this.matches.markRunning(connection, match.id(), MatchService.this.clock.instant());
			});
		}
		
		@Override
		public void onEnd(@NonNull Match match, @NonNull MatchState state, @Nullable UUID winnerId, @NonNull EndReason reason, @NonNull List<UUID> participants) {
			MatchService.this.database.execute(connection -> {
				MatchService.this.matches.markEnded(connection, match.id(), state, winnerId, reason, MatchService.this.clock.instant());
				
				boolean decided = state == MatchState.ENDED && winnerId != null;
				if (match.stake() > 0) {
					if (decided) {
						// A single payout row credits the winner with the whole pot (spec 9a.3).
						MatchService.this.currency.payout(connection, winnerId, match.stake() * participants.size(), match.id());
					} else {
						for (UUID userId : participants) {
							MatchService.this.currency.refund(connection, userId, match.stake(), match.id());
						}
					}
				}
				
				for (UUID userId : participants) {
					MatchResult result;
					if (state == MatchState.ABANDONED) {
						result = MatchResult.ABANDONED;
					} else if (winnerId == null) {
						result = MatchResult.DRAW;
					} else {
						result = userId.equals(winnerId) ? MatchResult.WON : MatchResult.LOST;
					}
					MatchService.this.matches.setResult(connection, match.id(), userId, result);
				}
			});
			
			MatchService.this.registry.remove(match.id());
		}
	}
}
