package net.luis.sudoku.handler;

import io.javalin.http.Context;
import io.javalin.openapi.*;
import net.luis.sudoku.auth.Authentication;
import net.luis.sudoku.device.DeviceLinkService;
import net.luis.sudoku.domain.KeyAlgorithm;
import net.luis.sudoku.domain.Principal;
import net.luis.sudoku.dto.request.LinkDeviceRequest;
import net.luis.sudoku.dto.response.*;
import net.luis.sudoku.permission.UserAdminService;
import net.luis.sudoku.security.ClientIp;
import net.luis.sudoku.security.RateLimiter;
import org.jspecify.annotations.NonNull;

import java.util.List;

/**
 * Device linking and management (server-spec 6.4, 6.5).
 */
public class DeviceHandler {
	
	private final Authentication authentication;
	private final DeviceLinkService links;
	private final UserAdminService admin;
	private final RateLimiter rateLimiter;
	private final boolean trustProxy;
	
	public DeviceHandler(@NonNull Authentication authentication, @NonNull DeviceLinkService links,
	                     @NonNull UserAdminService admin, @NonNull RateLimiter rateLimiter, boolean trustProxy) {
		this.authentication = authentication;
		this.links = links;
		this.admin = admin;
		this.rateLimiter = rateLimiter;
		this.trustProxy = trustProxy;
	}
	
	@OpenApi(
		summary = "Mint a device link code",
		description = "Authenticated. Returns a short, single-use code to type on the new device. Supersedes any "
			+ "outstanding code for this user.",
		operationId = "createLinkCode",
		path = "/api/v1/devices/link-code",
		methods = HttpMethod.POST,
		tags = "Devices",
		responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = LinkCodeResponse.class))
	)
	public void createLinkCode(@NonNull Context ctx) {
		Principal actor = this.authentication.require(ctx);
		ctx.json(LinkCodeResponse.of(this.links.mint(actor)));
	}
	
	@OpenApi(
		summary = "Link a new device with a code",
		description = "Unauthenticated - the new device has no session yet. The device inherits the user's role.",
		operationId = "linkDevice",
		path = "/api/v1/devices/link",
		methods = HttpMethod.POST,
		tags = "Devices",
		requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = LinkDeviceRequest.class)),
		responses = {
			@OpenApiResponse(status = "201", content = @OpenApiContent(from = SessionResponse.class)),
			@OpenApiResponse(status = "403", description = "LINK_CODE_INVALID",
				content = @OpenApiContent(from = ErrorResponse.class)),
			@OpenApiResponse(status = "429", description = "RATE_LIMITED", content = @OpenApiContent(from = ErrorResponse.class))
		}
	)
	public void link(@NonNull Context ctx) {
		// Link codes are the shortest secret in the system, so this budget is what actually protects
		// them; the TTL alone would not (spec 12).
		this.rateLimiter.check(RateLimiter.Bucket.DEVICE_LINK, ClientIp.of(ctx, this.trustProxy));
		
		LinkDeviceRequest request = ctx.bodyAsClass(LinkDeviceRequest.class);
		DeviceLinkService.Linked linked = this.links.link(
			request.requireLinkCode(),
			request.decodePublicKey(),
			KeyAlgorithm.of(request.requireKeyAlgorithm()),
			request.deviceLabelOrDefault()
		);
		
		ctx.status(201);
		ctx.json(SessionResponse.of(linked.session(), linked.user()));
	}
	
	@OpenApi(
		summary = "List your devices",
		operationId = "listDevices",
		path = "/api/v1/devices",
		methods = HttpMethod.GET,
		tags = "Devices",
		responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = DeviceResponse[].class))
	)
	public void list(@NonNull Context ctx) {
		Principal actor = this.authentication.require(ctx);
		List<DeviceResponse> devices = this.links.list(actor).stream()
			.map(device -> DeviceResponse.of(device, actor.deviceId()))
			.toList();
		ctx.json(devices);
	}
	
	@OpenApi(
		summary = "Revoke a device",
		description = "Revoking your current device ends your session. A user's last device may only be revoked if "
			+ "they are not the last admin.",
		operationId = "revokeDevice",
		path = "/api/v1/devices/{id}",
		methods = HttpMethod.DELETE,
		tags = "Devices",
		pathParams = @OpenApiParam(name = "id", description = "Device id"),
		responses = {
			@OpenApiResponse(status = "204"),
			@OpenApiResponse(status = "409", description = "LAST_ADMIN", content = @OpenApiContent(from = ErrorResponse.class))
		}
	)
	public void revoke(@NonNull Context ctx) {
		Principal actor = this.authentication.require(ctx);
		this.admin.revokeDevice(actor, Handlers.pathUuid(ctx, "id"));
		ctx.status(204);
	}
}
