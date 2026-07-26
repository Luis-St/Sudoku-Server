package net.luis.sudoku.handler;

import io.javalin.http.Context;
import io.javalin.openapi.*;
import net.luis.sudoku.ApiVersion;
import net.luis.sudoku.auth.Authentication;
import net.luis.sudoku.domain.Principal;
import net.luis.sudoku.dto.request.CreateInviteRequest;
import net.luis.sudoku.dto.response.ErrorResponse;
import net.luis.sudoku.dto.response.InviteResponse;
import net.luis.sudoku.invite.InviteService;
import org.jspecify.annotations.NonNull;

import java.util.List;

/**
 * Invite management (server-spec 7).
 */
public class InviteHandler {
	
	private final Authentication authentication;
	private final InviteService invites;
	
	public InviteHandler(@NonNull Authentication authentication, @NonNull InviteService invites) {
		this.authentication = authentication;
		this.invites = invites;
	}
	
	@OpenApi(
		summary = "Create an invite",
		description = "Requires CAN_INVITE. The invite always grants NEW; promotion is a separate admin action.",
		operationId = "createInvite",
		path = ApiVersion.PATH_PREFIX + "/invites",
		methods = HttpMethod.POST,
		tags = "Invites",
		requestBody = @OpenApiRequestBody(required = false, content = @OpenApiContent(from = CreateInviteRequest.class)),
		responses = {
			@OpenApiResponse(status = "201", content = @OpenApiContent(from = InviteResponse.class)),
			@OpenApiResponse(status = "403", description = "FORBIDDEN", content = @OpenApiContent(from = ErrorResponse.class))
		}
	)
	public void create(@NonNull Context ctx) {
		Principal actor = this.authentication.require(ctx);
		// The body is optional: an invite with no expiry is the common case.
		CreateInviteRequest request = ctx.body().isBlank()
			? new CreateInviteRequest(null)
			: ctx.bodyAsClass(CreateInviteRequest.class);
		
		ctx.status(201);
		ctx.json(InviteResponse.of(this.invites.create(actor, request.parseExpiresAt())));
	}
	
	@OpenApi(
		summary = "List invites",
		description = "Admins see every invite; everyone else sees only their own.",
		operationId = "listInvites",
		path = ApiVersion.PATH_PREFIX + "/invites",
		methods = HttpMethod.GET,
		tags = "Invites",
		responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = InviteResponse[].class))
	)
	public void list(@NonNull Context ctx) {
		Principal actor = this.authentication.require(ctx);
		List<InviteResponse> invites = this.invites.list(actor).stream().map(InviteResponse::of).toList();
		ctx.json(invites);
	}
	
	@OpenApi(
		summary = "Revoke an invite",
		description = "Permitted for the invite's creator or any admin. Already-used invites cannot be revoked.",
		operationId = "revokeInvite",
		path = ApiVersion.PATH_PREFIX + "/invites/{code}",
		methods = HttpMethod.DELETE,
		tags = "Invites",
		pathParams = @OpenApiParam(name = "code", description = "Invite code"),
		responses = {
			@OpenApiResponse(status = "204"),
			@OpenApiResponse(status = "403", description = "FORBIDDEN", content = @OpenApiContent(from = ErrorResponse.class))
		}
	)
	public void revoke(@NonNull Context ctx) {
		Principal actor = this.authentication.require(ctx);
		this.invites.revoke(actor, ctx.pathParam("code"));
		ctx.status(204);
	}
}
