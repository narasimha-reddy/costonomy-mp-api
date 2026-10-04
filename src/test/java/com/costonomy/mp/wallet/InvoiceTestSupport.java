package com.costonomy.mp.wallet;

import com.costonomy.mp.wallet.WalletTopUpSupport.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/** Shared helpers for the bill (wallet entry invoice) integration tests. */
final class InvoiceTestSupport {

    /** A real-looking JPEG header followed by filler: enough for the byte check. */
    static byte[] jpeg(int extra) {
        byte[] out = new byte[8 + extra];
        out[0] = (byte) 0xFF;
        out[1] = (byte) 0xD8;
        out[2] = (byte) 0xFF;
        out[3] = (byte) 0xE0;
        for (int i = 4; i < out.length; i++) {
            out[i] = (byte) (i * 31);
        }
        return out;
    }

    static byte[] pdf() {
        return "%PDF-1.4\n1 0 obj<<>>endobj\ntrailer<<>>\n%%EOF".getBytes(StandardCharsets.US_ASCII);
    }

    static MockMultipartFile part(String name, String contentType, byte[] content) {
        return new MockMultipartFile("file", name, contentType, content);
    }

    static MockMultipartFile jpegPart() {
        return part("receipt.jpg", "image/jpeg", jpeg(100));
    }

    static String path(long outletId, long entryId) {
        return "/api/v1/outlets/" + outletId + "/wallet/transactions/" + entryId + "/invoice";
    }

    static Reply upload(MockMvc mvc, ObjectMapper json, String token, long outletId, long entryId,
                        MockMultipartFile... files) throws Exception {
        MockMultipartHttpServletRequestBuilder request = MockMvcRequestBuilders.multipart(path(outletId, entryId));
        for (MockMultipartFile file : files) {
            request.file(file);
        }
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return reply(mvc.perform(request).andReturn().getResponse(), json);
    }

    static Reply call(MockMvc mvc, ObjectMapper json, String method, String token, String path) throws Exception {
        var request = switch (method) {
            case "DELETE" -> MockMvcRequestBuilders.delete(path);
            case "PUT" -> MockMvcRequestBuilders.put(path);
            default -> MockMvcRequestBuilders.get(path);
        };
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return reply(mvc.perform(request).andReturn().getResponse(), json);
    }

    private static Reply reply(org.springframework.mock.web.MockHttpServletResponse response, ObjectMapper json)
            throws IOException {
        String text = response.getContentAsString(StandardCharsets.UTF_8);
        JsonNode body = text.isBlank() || !text.trim().startsWith("{") ? json.createObjectNode() : json.readTree(text);
        return new Reply(response.getStatus(), body);
    }

    static List<Path> filesUnder(Path root) throws IOException {
        if (!Files.exists(root)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).toList();
        }
    }

    private InvoiceTestSupport() {
    }

}
