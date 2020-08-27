/**
 * 
 */
package org.gluu.oxauthconfigapi.rest.ressource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonString;
import javax.json.JsonValue;
import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.gluu.oxauth.model.common.GrantType;
import org.gluu.oxauthconfigapi.filters.ProtectedApi;
import org.gluu.oxauthconfigapi.util.ApiConstants;
import org.gluu.oxauthconfigapi.util.AttributeNames;
import org.gluu.oxtrust.model.OxAuthApplicationType;
import org.gluu.oxtrust.model.OxAuthClient;
import org.gluu.oxtrust.model.OxAuthSubjectType;
import org.gluu.oxtrust.service.ClientService;
import org.gluu.oxtrust.service.EncryptionService;
import org.gluu.oxtrust.service.ScopeService;
import org.oxauth.persistence.model.Scope;
import org.python.jline.internal.Log;
import org.slf4j.Logger;

/**
 * @author Mougang T.Gasmyr
 *
 */

@Path(ApiConstants.BASE_API_URL + ApiConstants.CLIENTS)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class OIDClientResource extends BaseResource {
	@Inject
	Logger logger;

	@Inject
	ClientService clientService;

	@Inject
	ScopeService scopeService;

	@Inject
	EncryptionService encryptionService;

	@GET
	@ProtectedApi(scopes = { READ_ACCESS })
	public Response getOpenIdConnectClients(@DefaultValue("50") @QueryParam(value = ApiConstants.LIMIT) int limit,
			@DefaultValue("") @QueryParam(value = ApiConstants.PATTERN) String pattern) {
		try {
			List<OxAuthClient> clients = new ArrayList<OxAuthClient>();
			if (!pattern.isEmpty() && pattern.length() >= 2) {
				clients = clientService.searchClients(pattern, limit);
			} else {
				clients = clientService.getAllClients(limit);
			}
			return Response.ok(clients).build();
		} catch (Exception ex) {
			logger.error("Failed to openid connects clients", ex);
			return getInternalServerError(ex);
		}
	}

	@GET
	@ProtectedApi(scopes = { READ_ACCESS })
	@Path(ApiConstants.INUM_PATH)
	public Response getOpenIdClientByInum(@PathParam(ApiConstants.INUM) String inum) {
		try {
			OxAuthClient client = clientService.getClientByInum(inum);
			if (client == null) {
				return getResourceNotFoundError();
			}
			return Response.ok(client).build();
		} catch (Exception ex) {
			logger.error("Failed to fetch  openId Client " + inum, ex);
			return getInternalServerError(ex);
		}
	}

	@POST
	@ProtectedApi(scopes = { WRITE_ACCESS })
	public Response createOpenIdConnect(@Valid OxAuthClient client) {
		try {
			String inum = clientService.generateInumForNewClient();
			client.setInum(inum);
			if (client.getDisplayName() == null) {
				return getMissingAttributeError(AttributeNames.DISPLAY_NAME);
			}
			if (client.getEncodedClientSecret() != null) {
				client.setEncodedClientSecret(encryptionService.encrypt(client.getEncodedClientSecret()));
			}
			if (client.getOxAuthAppType() == null) {
				client.setOxAuthAppType(OxAuthApplicationType.WEB);
			}
			if (client.getSubjectType() == null) {
				client.setSubjectType(OxAuthSubjectType.PUBLIC);
			}
			client.setDn(clientService.getDnForClient(inum));
			client.setDeletable(client.getExp() != null);
			clientService.addClient(client);
			OxAuthClient result = clientService.getClientByInum(inum);
			return Response.status(Response.Status.CREATED).entity(result).build();
		} catch (Exception ex) {
			logger.error("Failed to create new openid connect client", ex);
			return getInternalServerError(ex);
		}
	}

	@PUT
	@ProtectedApi(scopes = { WRITE_ACCESS })
	public Response updateClient(@Valid OxAuthClient client) {
		try {
			String inum = client.getInum();
			if (inum == null) {
				return getMissingAttributeError(AttributeNames.INUM);
			}
			if (client.getDisplayName() == null) {
				return getMissingAttributeError(AttributeNames.DISPLAY_NAME);
			}
			OxAuthClient existingClient = clientService.getClientByInum(inum);
			if (existingClient != null) {
				client.setInum(existingClient.getInum());
				client.setBaseDn(clientService.getDnForClient(inum));
				client.setDeletable(client.getExp() != null);
				if (client.getOxAuthClientSecret() != null) {
					client.setEncodedClientSecret(encryptionService.encrypt(client.getOxAuthClientSecret()));
				}
				clientService.updateClient(client);
				OxAuthClient result = clientService.getClientByInum(existingClient.getInum());
				if (result.getEncodedClientSecret() != null) {
					result.setOxAuthClientSecret(encryptionService.decrypt(client.getEncodedClientSecret()));
				}
				return Response.ok(result).build();
			} else {
				return getResourceNotFoundError();
			}
		} catch (Exception ex) {
			logger.error("Failed to update OpenId Connect client", ex);
			return getInternalServerError(ex);
		}
	}

	@PUT
	@ProtectedApi(scopes = { WRITE_ACCESS })
	@Path(ApiConstants.INUM_PATH + ApiConstants.SCOPES)
	public Response addScopesToClient(@NotNull @PathParam(ApiConstants.INUM) @NotNull String inum,
			@NotNull JsonObject object) {
		try {
			if (inum == null) {
				return getMissingAttributeError(AttributeNames.INUM);
			}
			JsonArray scopeInums = object.getJsonArray("scopes");
			if (scopeInums == null || scopeInums.isEmpty()) {
				return getMissingAttributeError(AttributeNames.SCOPES);
			}
			OxAuthClient existingClient = clientService.getClientByInum(inum);
			JsonObjectBuilder builder = Json.createObjectBuilder();
			if (existingClient != null) {
				List<String> oxAuthScopes = existingClient.getOxAuthScopes();
				if (oxAuthScopes == null) {
					oxAuthScopes = new ArrayList<String>();
				}
				for (JsonValue scopeInum : scopeInums) {
					String inumScope = ((JsonString) scopeInum).getString();
					Scope scope = scopeService.getScopeByInum(inumScope);
					if (scope != null) {
						builder.add(inumScope, Response.Status.OK.getStatusCode());
						oxAuthScopes.add(scope.getDn());
					} else {
						builder.add(inumScope, Response.Status.NOT_FOUND.getStatusCode());
					}
				}
				existingClient.setOxAuthScopes(oxAuthScopes);
				clientService.updateClient(existingClient);
				return Response.ok(builder.build()).build();
			} else {
				return getResourceNotFoundError();
			}
		} catch (Exception ex) {
			logger.error("Failed to add scopes to openId connect client", ex);
			return getInternalServerError(ex);
		}
	}

	@PUT
	@ProtectedApi(scopes = { WRITE_ACCESS })
	@Path(ApiConstants.INUM_PATH + ApiConstants.GRANT_TYPES)
	public Response addGrantTypeToClient(@NotNull @PathParam(ApiConstants.INUM) @NotNull String inum,
			@NotNull JsonObject object) {
		try {
			if (inum == null) {
				return getMissingAttributeError(AttributeNames.INUM);
			}
			JsonArray grantTypesValues = object.getJsonArray("grant-types");
			if (grantTypesValues == null || grantTypesValues.isEmpty()) {
				return getMissingAttributeError(AttributeNames.GRANT_TYPES);
			}
			OxAuthClient existingClient = clientService.getClientByInum(inum);
			JsonObjectBuilder builder = Json.createObjectBuilder();
			if (existingClient != null) {
				GrantType[] grantTypes = existingClient.getGrantTypes();
				if (grantTypes == null) {
					grantTypes = new GrantType[] {};
				}
				List<GrantType> myList = new ArrayList<GrantType>(Arrays.asList(grantTypes));
				for (JsonValue grantType : grantTypesValues) {
					String grantTypeName = ((JsonString) grantType).getString();
					GrantType mGrantType = getGrantTypeFromName(grantTypeName);
					if (mGrantType != null) {
						builder.add(grantTypeName, Response.Status.OK.getStatusCode());
						myList.add(mGrantType);
					} else {
						builder.add(grantTypeName, Response.Status.NOT_FOUND.getStatusCode());
					}
				}
				GrantType[] types = new GrantType[myList.size()];
				existingClient.setGrantTypes(myList.toArray(types));
				clientService.updateClient(existingClient);
				return Response.ok(builder.build()).build();
			} else {
				return getResourceNotFoundError("openid client");
			}
		} catch (

		Exception ex) {
			logger.error("Failed to add grand typpes to openId connect client", ex);
			return getInternalServerError(ex);
		}
	}

	private GrantType getGrantTypeFromName(String grantTypeName) {
		try {
			GrantType mGrantType = GrantType.fromString(grantTypeName);
			return mGrantType;
		} catch (Exception e) {
			Log.info("++++++++++++++++++++++++++++++++++");
			return null;
		}

	}

	@DELETE
	@Path(ApiConstants.INUM_PATH + ApiConstants.SCOPES + ApiConstants.SEPARATOR + ApiConstants.SCOPE_INUM_PATH)
	@ProtectedApi(scopes = { WRITE_ACCESS })
	public Response removeScopeFromClient(@PathParam(ApiConstants.INUM) @NotNull String inum,
			@PathParam(ApiConstants.SCOPE_INUM) @NotNull String scopeInum) {
		try {
			OxAuthClient client = clientService.getClientByInum(inum);
			Scope scope = scopeService.getScopeByInum(scopeInum);
			if (client != null) {
				if (scope != null) {
					List<String> oxAuthScopes = client.getOxAuthScopes();
					if (oxAuthScopes == null) {
						oxAuthScopes = new ArrayList<String>();
					}
					oxAuthScopes.remove(scope.getDn());
					client.setOxAuthScopes(oxAuthScopes);
					clientService.updateClient(client);
					return Response.ok().build();
				} else {
					return getResourceNotFoundError("scope");
				}
			} else {
				return getResourceNotFoundError("client");
			}
		} catch (Exception ex) {
			logger.error("Failed to Delete OpenId Connect client", ex);
			return getInternalServerError(ex);
		}
	}

	@DELETE
	@Path(ApiConstants.INUM_PATH)
	@ProtectedApi(scopes = { WRITE_ACCESS })
	public Response deleteClient(@PathParam(ApiConstants.INUM) @NotNull String inum) {
		try {
			OxAuthClient client = clientService.getClientByInum(inum);
			if (client != null) {
				clientService.removeClient(client);
				return Response.noContent().build();
			} else {
				return getResourceNotFoundError();
			}
		} catch (Exception ex) {
			logger.error("Failed to Delete OpenId Connect client", ex);
			return getInternalServerError(ex);
		}
	}

}
