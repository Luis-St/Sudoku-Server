package net.luis.sudoku.config;

import org.jspecify.annotations.NonNull;

/**
 * Duel-mode time-bank tuning (server-spec 3, 11.2). All bank values are in seconds.
 *
 * @param initialBank seconds each participant starts with
 * @param gainPerCorrect seconds credited to the controller for a correct entry
 * @param lossPerIncorrect seconds debited from the controller for an incorrect entry
 * @param maxBank ceiling a bank may be topped up or clamped to
 * @param minTurn seconds a turn lasts at minimum before a handover may occur
 * @param regenRatio idle seconds gained per elapsed second while not in control
 * @param maxHandovers stalemate cap; reaching it decides the match on correct-cell count
 */
public record DuelConfig(
	int initialBank,
	int gainPerCorrect,
	int lossPerIncorrect,
	int maxBank,
	int minTurn,
	double regenRatio,
	int maxHandovers
) {
	
	public DuelConfig {
		requirePositive(EnvKeys.DUEL_INITIAL_BANK, initialBank);
		requirePositive(EnvKeys.DUEL_GAIN_CORRECT, gainPerCorrect);
		requirePositive(EnvKeys.DUEL_LOSS_INCORRECT, lossPerIncorrect);
		requirePositive(EnvKeys.DUEL_MAX_BANK, maxBank);
		requirePositive(EnvKeys.DUEL_MIN_TURN, minTurn);
		requirePositive(EnvKeys.DUEL_MAX_HANDOVERS, maxHandovers);
		
		if (regenRatio < 0.0 || regenRatio > 1.0) {
			throw new ConfigException(EnvKeys.DUEL_REGEN_RATIO + " must be within [0.0, 1.0], got: " + regenRatio);
		}
		if (initialBank > maxBank) {
			throw new ConfigException(EnvKeys.DUEL_INITIAL_BANK + " (" + initialBank + ") must not exceed " + EnvKeys.DUEL_MAX_BANK + " (" + maxBank + ")");
		}
	}
	
	static @NonNull DuelConfig from(@NonNull Env env) {
		return new DuelConfig(
			env.integer(EnvKeys.DUEL_INITIAL_BANK, 90),
			env.integer(EnvKeys.DUEL_GAIN_CORRECT, 6),
			env.integer(EnvKeys.DUEL_LOSS_INCORRECT, 20),
			env.integer(EnvKeys.DUEL_MAX_BANK, 180),
			env.integer(EnvKeys.DUEL_MIN_TURN, 10),
			env.decimal(EnvKeys.DUEL_REGEN_RATIO, 0.5),
			env.integer(EnvKeys.DUEL_MAX_HANDOVERS, 40)
		);
	}
	
	private static void requirePositive(@NonNull String key, int value) {
		if (value < 1) {
			throw new ConfigException(key + " must be at least 1, got: " + value);
		}
	}
}
