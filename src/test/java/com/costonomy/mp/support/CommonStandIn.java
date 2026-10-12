package com.costonomy.mp.support;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;

/** costonomy-common-api for the integration tests: keys, service tokens, catalog sync and access lookups. */
public final class CommonStandIn {

    public static final String CONTEXT = "/costonomy-common-api";
    public static final WireMockServer SERVER = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        SERVER.start();
        defaults();
    }

    private CommonStandIn() {
    }

    public static String baseUrl() {
        return "http://localhost:" + SERVER.port() + CONTEXT;
    }

    static void defaults() {
        SERVER.stubFor(get(urlEqualTo(CONTEXT + "/.well-known/jwks.json")).willReturn(json(200, TestKeys.jwks())));
        SERVER.stubFor(post(urlEqualTo(CONTEXT + "/v1/oauth/token")).willReturn(json(200,
                "{\"access_token\":\"svc-token\",\"token_type\":\"Bearer\",\"expires_in\":900,"
                        + "\"scope\":\"internal.access internal.read internal.catalog\"}")));
        SERVER.stubFor(put(urlPathMatching(CONTEXT + "/internal/v1/apps/.*/catalog")).willReturn(json(200,
                "{\"permissionsUpserted\":0,\"permissionsDeprecated\":0,\"rolesUpserted\":0,\"changed\":false}")));
    }

    /** What common answers for this user's access (§10.1 shape). */
    public static void stubAccess(long userId, String body) {
        SERVER.stubFor(get(urlPathEqualTo(CONTEXT + "/internal/v1/principals/" + userId + "/access"))
                .willReturn(json(200, body)));
    }

    static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(int status, String body) {
        return aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body);
    }
}
