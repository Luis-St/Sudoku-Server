package net.luis.sudoku.config;

import net.luis.utils.io.network.mail.SmtpSecurity;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * SMTP settings for account recovery emails.
 * <p>
 * All-or-nothing: {@link #from} returns null when {@link EnvKeys#SMTP_HOST} is unset, so a server with
 * no mail configuration simply cannot send email (spec: account recovery). Once a host is given, the
 * credentials and sender address are required - a half-configured mailer would fail on the first send
 * anyway, so it is better to fail fast at boot.
 *
 * @param host SMTP server host
 * @param port SMTP server port
 * @param security transport security mode
 * @param username SMTP auth username
 * @param password SMTP auth password
 * @param from the {@code From:} address on outgoing mail
 */
public record MailConfig(
	@NonNull String host,
	int port,
	@NonNull SmtpSecurity security,
	@NonNull String username,
	@NonNull String password,
	@NonNull String from
) {
	
	public MailConfig {
		if (port < 1 || port > 65535) {
			throw new ConfigException(EnvKeys.SMTP_PORT + " must be a valid port number, got: " + port);
		}
	}
	
	static @Nullable MailConfig from(@NonNull Env env) {
		String host = env.optional(EnvKeys.SMTP_HOST);
		if (host == null) {
			return null;
		}
		
		return new MailConfig(
			host,
			env.integer(EnvKeys.SMTP_PORT, SmtpSecurity.STARTTLS.defaultPort()),
			env.enumeration(EnvKeys.SMTP_SECURITY, SmtpSecurity.STARTTLS),
			env.require(EnvKeys.SMTP_USERNAME),
			env.require(EnvKeys.SMTP_PASSWORD),
			env.require(EnvKeys.SMTP_FROM)
		);
	}
}
