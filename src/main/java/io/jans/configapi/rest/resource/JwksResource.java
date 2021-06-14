/*
 * Janssen Project software is available under the MIT License (2008). See http://opensource.org/licenses/MIT for full text.
 *
 * Copyright (c) 2020, Janssen Project
 */

package io.jans.configapi.rest.resource;

import com.github.fge.jsonpatch.JsonPatchException;
import io.jans.as.model.config.Conf;
import io.jans.as.model.config.WebKeysConfiguration;
import io.jans.as.model.jwk.JSONWebKey;
import io.jans.configapi.filters.ProtectedApi;
import io.jans.configapi.service.ConfigurationService;
import io.jans.configapi.service.KeyStoreService;
import io.jans.configapi.service.TestKeyGenerator;
import io.jans.configapi.util.ApiAccessConstants;
import io.jans.configapi.util.ApiConstants;
import io.jans.configapi.util.Jackson;

import javax.inject.Inject;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.IOException;

import org.slf4j.Logger;

/**
 * @author Yuriy Zabrovarnyy
 */
@Path(ApiConstants.CONFIG + ApiConstants.JWKS)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class JwksResource extends BaseResource {
    
    @Inject
    Logger log;

    @Inject
    ConfigurationService configurationService;

    @Inject
    KeyStoreService keyStoreService;

    @Inject
    TestKeyGenerator testKeyGenerator;
    
    @GET
    @Path("/test")
    public Response test() throws Exception{
        final String json = configurationService.findConf().getWebKeys().toString();
        log.info("\n\n json = "+json+"\n\n");
        
        keyStoreService.importPublicKey(configurationService.findConf().getWebKeys().getKey("47d65f1d-66f1-4b4a-92ec-d969522f4cbc_sig_rs256"));
        return Response.ok(json).build();
    }

    @GET
    @ProtectedApi(scopes = { ApiAccessConstants.JWKS_READ_ACCESS })
    public Response get() {
        final String json = configurationService.findConf().getWebKeys().toString();
        return Response.ok(json).build();
    }

    @PUT
    @ProtectedApi(scopes = { ApiAccessConstants.JWKS_WRITE_ACCESS })
    public Response put(WebKeysConfiguration webkeys) {
        log.debug("JWKS details to be updated - webkeys = "+webkeys);
        final Conf conf = configurationService.findConf();
        conf.setWebKeys(webkeys);
        configurationService.merge(conf);
        final String json = configurationService.findConf().getWebKeys().toString();
        return Response.ok(json).build();
    }

    @PATCH
    @Consumes(MediaType.APPLICATION_JSON_PATCH_JSON)
    @ProtectedApi(scopes = { ApiAccessConstants.JWKS_WRITE_ACCESS })
    public Response patch(String requestString) throws JsonPatchException, IOException {
        log.debug("JWKS details to be patched - requestString = "+requestString);
        final Conf conf = configurationService.findConf();
        WebKeysConfiguration webKeys = conf.getWebKeys();
        webKeys = Jackson.applyPatch(requestString, webKeys);
        conf.setWebKeys(webKeys);
        configurationService.merge(conf);
        final String json = configurationService.findConf().getWebKeys().toString();
        return Response.ok(json).build();
    }
    
    @POST
    @ProtectedApi(scopes = { ApiAccessConstants.JWKS_WRITE_ACCESS })
    @Path(ApiConstants.KEY_PATH)
    public Response addKeyById(@NotNull JSONWebKey jwk) {
        log.debug("Add a new Key to the JWKS = "+jwk); 
        Conf conf = configurationService.findConf();
        WebKeysConfiguration webkeys = configurationService.findConf().getWebKeys();
        log.debug("WebKeysConfiguration before addding new key =" + webkeys);
        
        //Reject if key with same kid already exists
        //if(webkeys.getKeys().stream().anyMatch(x -> x.getKid()!=null && x.getKid().equals(jwk.getKid())) ){
        if(getJSONWebKey(webkeys, jwk.getKid())!=null) {
            throw new NotAcceptableException(getNotAcceptableException(
                    "JWK with same kid - '" + jwk.getKid() + "' already exists!"));
        }      
        
        //Add key
        webkeys.getKeys().add(jwk);
        conf.setWebKeys(webkeys);
        configurationService.merge(conf);
        webkeys = configurationService.findConf().getWebKeys();
        return Response.status(Response.Status.CREATED).entity(jwk).build();
    }
    
    @GET
    @ProtectedApi(scopes = { ApiAccessConstants.JWKS_READ_ACCESS })
    @Path(ApiConstants.KID_PATH)
    public Response getKeyById(@PathParam(ApiConstants.KID) @NotNull String kid) {
        log.debug("Fetch JWK details by kid = "+kid);        
        WebKeysConfiguration webkeys = configurationService.findConf().getWebKeys();
        log.debug("WebKeysConfiguration before addding new key =" + webkeys);
        JSONWebKey jwk = getJSONWebKey(webkeys, kid);
        return Response.ok(jwk).build();
    }
      
    @PATCH
    @Consumes(MediaType.APPLICATION_JSON_PATCH_JSON)
    @ProtectedApi(scopes = { ApiAccessConstants.JWKS_WRITE_ACCESS })
    @Path(ApiConstants.KID_PATH)
    public Response patchKeyById(@PathParam(ApiConstants.KID) @NotNull String kid, @NotNull String requestString) throws JsonPatchException, IOException {
        log.debug("JWKS details to be patched for kid = "+kid+" ,requestString = "+requestString);
        Conf conf = configurationService.findConf();
        WebKeysConfiguration webkeys = configurationService.findConf().getWebKeys();
        JSONWebKey jwk = getJSONWebKey(webkeys, kid);
        if(jwk==null) {
            throw new NotFoundException(getNotFoundError(
                    "JWK with kid - '" + kid + "' does not exist!"));
        }
        
        //Patch
        jwk = Jackson.applyPatch(requestString, jwk);
        log.debug("JWKS details patched - jwk = "+jwk);
        
        //Remove old Jwk
        conf.getWebKeys().getKeys().removeIf(x -> x.getKid()!=null && x.getKid().equals(kid));
        log.debug("WebKeysConfiguration after removing old key =" + conf.getWebKeys().getKeys());
        
        //Update
        conf.getWebKeys().getKeys().add(jwk);
        configurationService.merge(conf);
        
        return Response.ok(jwk).build();
    }
        
    @DELETE
    @ProtectedApi(scopes = { ApiAccessConstants.JWKS_WRITE_ACCESS })
    @Path(ApiConstants.KID_PATH)
    public Response deleteKey(@PathParam(ApiConstants.KID) @NotNull String kid) {
        log.debug("Key to be to be deleted - kid = "+kid);
        final Conf conf = configurationService.findConf();
        WebKeysConfiguration webkeys = configurationService.findConf().getWebKeys();
        JSONWebKey jwk = getJSONWebKey(webkeys, kid);
        if(jwk==null) {
            throw new NotFoundException(getNotFoundError(
                    "JWK with kid - '" + kid + "' does not exist!"));
        }
        
        conf.getWebKeys().getKeys().removeIf(x -> x.getKid()!=null && x.getKid().equals(kid));
        configurationService.merge(conf);
        return Response.noContent().build();
    }
    
    private JSONWebKey getJSONWebKey(WebKeysConfiguration webkeys, String kid) {
        if(kid!=null && webkeys.getKeys()!=null && !webkeys.getKeys().isEmpty()) {
            return webkeys.getKeys().stream().filter(x -> x.getKid()!=null && x.getKid().equals(kid)).findAny()
                    .orElse(null);
        }
        return null;
    }

    private final String certStr = "-----BEGIN CERTIFICATE-----\n"
            + "MIIECTCCAvGgAwIBAgIUZ3VzPuFqXNxussiXQIJ+aygf8EIwDQYJKoZIhvcNAQEL\n"
            + "BQAwgZMxCzAJBgNVBAYTAklOMQ0wCwYDVQQIDARNQUhBMQ8wDQYDVQQHDAZNdW1i\n"
            + "YWkxFzAVBgNVBAoMDk15IFRlc3QgU2VydmVyMRAwDgYDVQQLDAdUZXN0aW5nMRsw\n"
            + "GQYDVQQDDBJteXRlc3Quc2VydmVyMS5jb20xHDAaBgkqhkiG9w0BCQEWDXB1amFA\n"
            + "Z2x1dS5vcmcwHhcNMjEwMzE3MTUzNzAyWhcNMzEwMzE1MTUzNzAyWjCBkzELMAkG\n"
            + "A1UEBhMCSU4xDTALBgNVBAgMBE1BSEExDzANBgNVBAcMBk11bWJhaTEXMBUGA1UE\n"
            + "CgwOTXkgVGVzdCBTZXJ2ZXIxEDAOBgNVBAsMB1Rlc3RpbmcxGzAZBgNVBAMMEm15\n"
            + "dGVzdC5zZXJ2ZXIxLmNvbTEcMBoGCSqGSIb3DQEJARYNcHVqYUBnbHV1Lm9yZzCC\n"
            + "ASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBAMIl0rJA2r+hi5sBSW2taqlN\n"
            + "5qostqJKIpHonyfLE06mLPfanK19gGLeAdno6ICRLKN4aAPLFcl+dD1w27EXhiyO\n"
            + "6yF4Ff4Mzf9qeB/uQRfcXhcQWldEQHQLvT/SLMpC8icKQ5TRfakw/XwHraQjF89c\n"
            + "HX8GvZ0pZ/C2WxG7PyTX/oRdyrf+SLe7gIRLGN2wFt+cF2Wg6xjXVJqmQexFXZOM\n"
            + "UTFSP5sXTyjJfiJAt4637QoS/G5n/fPJbH29F8MBChUK/oWHW6r1jQFnZt1Juobm\n"
            + "5+mTBgyYaDYXkb5EjxM6ODjz1wAJEUu6uuvjYu11Yv5mwWECu+l37po+gvoTDJEC\n"
            + "AwEAAaNTMFEwHQYDVR0OBBYEFKRINir2jY29zqTAmlFAFmJKCZeHMB8GA1UdIwQY\n"
            + "MBaAFKRINir2jY29zqTAmlFAFmJKCZeHMA8GA1UdEwEB/wQFMAMBAf8wDQYJKoZI\n"
            + "hvcNAQELBQADggEBAI6dnhCMmzYhToIPsCgGDVsdmpNeVnaHCioR56n0ovZpDv9J\n"
            + "7psbNx9OAq/tBjUko88lM04O8kVdr90HN+2s3z4z8Ge5xgpfd76IHo5TUP7FPUJ+\n"
            + "WCpu1F1IU3fS7ryuJQh5gBOlIl1af4hlPEELhI4jxArqvOujt4KuVjHXuIfqN3G/\n"
            + "5R7xXN0ZLltrjIBaClSpk1WRhQTyMB2c/N7vLmHyqhAjICIz8GIAkLPacd1pGW30\n"
            + "dpu/QUDIH8qsB3ZMWhrUtgWuIKiWWrT6TgZf//y4pgC8RRdhfDNnqNkACuW1UL35\n"
            + "ZEBslAa2KKoIbnV5SMpcLuVP1tT5806O6Rw7bes=\n" + "-----END CERTIFICATE-----";

    private final String keyStr = "-----BEGIN PRIVATE KEY-----\n"
            + "MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQDCJdKyQNq/oYub\n"
            + "AUltrWqpTeaqLLaiSiKR6J8nyxNOpiz32pytfYBi3gHZ6OiAkSyjeGgDyxXJfnQ9\n"
            + "cNuxF4YsjusheBX+DM3/angf7kEX3F4XEFpXREB0C70/0izKQvInCkOU0X2pMP18\n"
            + "B62kIxfPXB1/Br2dKWfwtlsRuz8k1/6EXcq3/ki3u4CESxjdsBbfnBdloOsY11Sa\n"
            + "pkHsRV2TjFExUj+bF08oyX4iQLeOt+0KEvxuZ/3zyWx9vRfDAQoVCv6Fh1uq9Y0B\n"
            + "Z2bdSbqG5ufpkwYMmGg2F5G+RI8TOjg489cACRFLurrr42LtdWL+ZsFhArvpd+6a\n"
            + "PoL6EwyRAgMBAAECggEAJhHqnd+PxXH1ASEgd+xAdRB8gbQg3/PvkXLu+oucrphx\n"
            + "SuzIOlDYwwpLjKJaLRPKkAeFRfElxYwRWRbvTWuSeNxRiQ+WKGn0XvhOs9wxUW95\n"
            + "18XyIRiWFutSYdhhxguYlkUx+VWW8X1Ux2RuDTiAa73hXs5AkjfTVOU9OF3iROPq\n"
            + "ahQ18JEsQstgE4RMOeX5NX6TNszHmfLyrSYu0RIk+0GCAYgpsYFEvnL8sx8/AKSS\n"
            + "ZZO6kO4Sbe25NUdgpuo2NWJ1+s2IqLNI7GU7uOt7Bu7HSvjYzDk7KdxCHsb3i0SI\n"
            + "GRftEYc+WSkWqcMAOxikXeVLRNgyivoISs4Elh/0LQKBgQDg3fUFWKomHQN31GzN\n"
            + "qRIJhhhlwoggV6bDdmUjdXHwpOh2/jQCMH/QmMf67shZReCguVcApssemO0f3R5n\n"
            + "fYJNswYisHtTqQpvtFIQV9YcVmdfg7TFdgzW/LCdo237FwDwLWuomhm3YdSJnxGN\n"
            + "9uRATtu/E1i1+WgurlvGi7aBzwKBgQDdBxJVwvMQvHi/qPz/H++Qsmyr5Hn6DTc/\n"
            + "cfexNuGl1mMLmh/UPfhnPbgaHhiv4bNSFuHepIUUgM5pc9i3/q4QmG3F/nOmMUpx\n"
            + "MXhhiMtUWvkLTSTV7n6C9ZBjikW/qqCaOaC4YRujIkQP5WsZ8kRAp9IQBqNIRRx0\n"
            + "JYehmF0DnwKBgQCdI76kG8/bjo7r4HCgT7QhH6pRAl5qa5ZIJaaL3vjet/8TmJTz\n"
            + "qrzHIt0tSEyNxj0xVBOuiuCK40dh6v3iSF0UuzRgbX/heNGoOhTXAurHJsJahwl+\n"
            + "q/5RBojNwHWM8Ahhzvva+MVb12vVOGnmEVB0eCcsIfLuR/o8FPBhkSTbFQKBgQCi\n"
            + "kU/4ClKTLby2Y4np8EhZKhLp/zuEInJPVPj0vEQNoPjqkKr2hboN3YRqmVZZDu3A\n"
            + "5Bmvk1xarz6iq1VsR4Mpq6OZ7ESNqVVymgtL2byLx/nDSTNdsnQUyJ8xx4LzrYFA\n"
            + "zkAbPTmBrHNZKcmzpNB0qbrElM1GG6cI+o2e2p0XSwKBgByZelQoau7TIUvZnpKQ\n"
            + "z+bi5YobCxFFuWIY7t8QRADPm/7ggrTd1Y2AC/DtMhcr+UmlhcztliOJjgO73Yk1\n"
            + "8cRu6km1rK9Zh+VNAQzI5FIKVR5Peag4+aOFk4Hq5g+SIUtSTLWeAtWEYOpXHacJ\n" + "Yp0EtsBTBxRvHx1EEPLt36wd\n"
            + "-----END PRIVATE KEY-----";

    @POST
    @Consumes(MediaType.APPLICATION_JSON_PATCH_JSON)
    @ProtectedApi(scopes = { ApiAccessConstants.JWKS_WRITE_ACCESS })
    public Response addKey(String format, String alias, String certificateStr, String privateKeyStr) throws Exception {
        System.out
                .println("JwksResource::addKey() - 1 - Json WEb Key to be imported - format =" + format + " , alias = "
                        + alias + " ,certificateStr = " + certificateStr + " , privateKeyStr = " + privateKeyStr);

        format = "PEM";
        alias = "PUJA8";
        certificateStr = certStr;
        privateKeyStr = keyStr;
        log.debug("*******************************");
        System.out
                .println("JwksResource::addKey() - 2 - Json WEb Key to be imported - format =" + format + " , alias = "
                        + alias + " ,certificateStr = " + certificateStr + " , privateKeyStr = " + privateKeyStr);
        log.debug("*******************************");
        

        keyStoreService.importKey(format, alias, certificateStr, privateKeyStr);
        final String json = configurationService.findConf().getWebKeys().toString();
        return Response.ok(json).build();

    }

}
