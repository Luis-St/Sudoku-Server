-- V2 - daily difficulty preferences (server-spec 8.1).
--
-- Not in the spec's section 5 table list, which predates the preference endpoints, but 8.1 requires
-- both halves of this: the standing preference, and the difficulty that was in effect when a given day
-- began.

CREATE TABLE daily_preferences (
	user_id          UUID PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
	-- 1-5 only. LISA is single-player and never reaches the server (spec 16, open item 1).
	daily_difficulty INTEGER NOT NULL DEFAULT 3 CHECK (daily_difficulty BETWEEN 1 AND 5),
	updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The difficulty locked in for one player on one date.
--
-- This is what makes a preference change take effect "from the next day only": the row is written on
-- the first /daily request of a date and never updated, so changing the preference mid-day cannot
-- retroactively hand the player an easier puzzle for a day already in progress.
CREATE TABLE daily_assignments (
	user_id    UUID    NOT NULL REFERENCES users (id) ON DELETE CASCADE,
	date       DATE    NOT NULL,
	difficulty INTEGER NOT NULL CHECK (difficulty BETWEEN 1 AND 5),
	PRIMARY KEY (user_id, date)
);
