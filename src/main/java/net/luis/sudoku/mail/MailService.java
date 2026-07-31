package net.luis.sudoku.mail;

import net.luis.sudoku.config.MailConfig;
import net.luis.sudoku.error.ApiException;
import net.luis.sudoku.error.ErrorCode;
import net.luis.utils.io.network.connection.exception.NetworkConnectionException;
import net.luis.utils.io.network.mail.*;
import net.luis.utils.io.network.mail.message.*;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Sends account-recovery email through LUtils' {@link SmtpClient}.
 * <p>
 * Configuration is all-or-nothing (see {@link MailConfig}): when the server has none, {@link #send}
 * fails clearly with {@link ErrorCode#MAIL_NOT_CONFIGURED} rather than crashing at boot or silently
 * dropping the message.
 * <p>
 * Not {@code final}: tests that exercise {@link net.luis.sudoku.recovery.RecoveryService} need a double
 * that captures outgoing mail instead of opening a real SMTP connection.
 */
public class MailService {
	
	private final @Nullable MailConfig config;
	
	public MailService(@Nullable MailConfig config) {
		this.config = config;
	}
	
	/**
	 * @throws ApiException {@code MAIL_NOT_CONFIGURED} (503) if no SMTP settings are configured
	 */
	public void send(@NonNull String to, @NonNull String subject, @NonNull String body) {
		MailConfig config = this.config;
		if (config == null) {
			throw new ApiException(ErrorCode.MAIL_NOT_CONFIGURED, "Email is not configured on this server");
		}
		
		SmtpClientConfig smtpConfig = SmtpClientConfig.builder()
			.security(config.security())
			.auth(new SmtpAuth.Login(config.username(), config.password().toCharArray()))
			.build();
		MailMessage message = MailMessage.builder()
			.from(Mailbox.parse(config.from()))
			.to(Mailbox.parse(to))
			.subject(subject)
			.content(TextContent.of(body))
			.build();
		
		try (SmtpClient client = new SmtpClient(smtpConfig)) {
			client.connect(config.host(), config.port());
			client.send(message);
		} catch (NetworkConnectionException e) {
			throw new IllegalStateException("Failed to send email to " + to, e);
		}
	}
}
