package net.luis.sudoku.handler;

import io.javalin.http.Context;
import io.javalin.openapi.*;
import net.luis.sudoku.ApiVersion;
import net.luis.sudoku.auth.Authentication;
import net.luis.sudoku.domain.Principal;
import net.luis.sudoku.dto.request.ChangeRoleRequest;
import net.luis.sudoku.dto.response.ErrorResponse;
import net.luis.sudoku.dto.response.UserResponse;
import net.luis.sudoku.permission.UserAdminService;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.UUID;

/**
 * User listing, role changes and kicks (server-spec 7).
 */
public class UserHandler {
	
	private final Authentication authentication;
	private final UserAdminService admin;
	
	public UserHandler(@NonNull Authentication authentication, @NonNull UserAdminService admin) {
		this.authentication = authentication;
		this.admin = admin;
	}
	
	@OpenApi(
		summary = "List users",
		operationId = "listUsers",
		path = ApiVersion.PATH_PREFIX + "/users",
		methods = HttpMethod.GET,
		tags = "Users",
		responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = UserResponse[].class))
	)
	public void list(@NonNull Context ctx) {
		this.authentication.require(ctx);
		List<UserResponse> users = this.admin.list().stream().map(UserResponse::of).toList();
		ctx.json(users);
	}
	
	@OpenApi(
		summary = "Change a user's role",
		description = "Requires CAN_CHANGE_ROLE. Rejected with LAST_ADMIN if it would leave zero administrators.",
		operationId = "changeUserRole",
		path = ApiVersion.PATH_PREFIX + "/users/{id}/role",
		methods = HttpMethod.PATCH,
		tags = "Users",
		pathParams = @OpenApiParam(name = "id", description = "User id"),
		requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = ChangeRoleRequest.class)),
		responses = {
			@OpenApiResponse(status = "200", content = @OpenApiContent(from = UserResponse.class)),
			@OpenApiResponse(status = "403", description = "FORBIDDEN", content = @OpenApiContent(from = ErrorResponse.class)),
			@OpenApiResponse(status = "409", description = "LAST_ADMIN", content = @OpenApiContent(from = ErrorResponse.class))
		}
	)
	public void changeRole(@NonNull Context ctx) {
		Principal actor = this.authentication.require(ctx);
		UUID targetId = Handlers.pathUuid(ctx, "id");
		ChangeRoleRequest request = ctx.bodyAsClass(ChangeRoleRequest.class);
		
		ctx.json(UserResponse.of(this.admin.changeRole(actor, targetId, request.requireRole())));
	}
	
	@OpenApi(
		summary = "Kick a user",
		description = "Requires CAN_KICK. Revokes every device key and ends the session; historical results are kept.",
		operationId = "kickUser",
		path = ApiVersion.PATH_PREFIX + "/users/{id}",
		methods = HttpMethod.DELETE,
		tags = "Users",
		pathParams = @OpenApiParam(name = "id", description = "User id"),
		responses = {
			@OpenApiResponse(status = "204"),
			@OpenApiResponse(status = "403", description = "FORBIDDEN", content = @OpenApiContent(from = ErrorResponse.class)),
			@OpenApiResponse(status = "409", description = "LAST_ADMIN", content = @OpenApiContent(from = ErrorResponse.class))
		}
	)
	public void kick(@NonNull Context ctx) {
		Principal actor = this.authentication.require(ctx);
		this.admin.kick(actor, Handlers.pathUuid(ctx, "id"));
		ctx.status(204);
	}
}
