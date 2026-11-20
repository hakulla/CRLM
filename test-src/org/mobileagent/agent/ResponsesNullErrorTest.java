package org.mobileagent.agent;

import java.io.*;
import java.net.*;
import org.json.*;

/** Offline regression: fake transport, real non-streaming Responses client. */
public final class ResponsesNullErrorTest {
    private static String body;
    private static int requests;
    public static void main(String[] args) throws Exception {
        URL.setURLStreamHandlerFactory(protocol -> {
            if (!"http".equals(protocol) && !"https".equals(protocol)) return null;
            return new URLStreamHandler() {
                protected URLConnection openConnection(URL url) {
                    return new HttpURLConnection(url) {
                        public void connect() { }
                        public void disconnect() { }
                        public boolean usingProxy() { return false; }
                        public OutputStream getOutputStream() {
                            return new ByteArrayOutputStream();
                        }
                        public int getResponseCode() { requests++; return 200; }
                        public InputStream getInputStream() throws IOException {
                            return new ByteArrayInputStream(body.getBytes("UTF-8"));
                        }
                    };
                }
            };
        });
        String text = "\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"OK\"}]}]";
        for (String error : new String[]{"", "\"error\":null,"}) {
            body = "{\"id\":\"resp_test\",\"status\":\"completed\"," + error + text + "}";
            LlmClient.Result result = call();
            require(result.error == null, "normal response: " + result.error);
            require("OK".equals(result.message.optString("content")), "text lost");
        }
        body = "{\"id\":\"resp_tool\",\"status\":\"completed\",\"error\":null,\"output\":[{\"type\":\"function_call\",\"call_id\":\"call_test\",\"name\":\"ping\",\"arguments\":\"{}\"}]}";
        LlmClient.Result tool = call();
        require(tool.error == null, "tool response: " + tool.error);
        JSONObject tc = tool.message.getJSONArray("tool_calls").getJSONObject(0);
        require("call_test".equals(tc.optString("id")), "call id lost");
        require("ping".equals(tc.getJSONObject("function").optString("name")), "tool lost");
        for (String error : new String[]{"{\"message\":\"synthetic failure\",\"type\":\"server_error\"}", "\"synthetic failure\""}) {
            body = "{\"error\":" + error + "}";
            require(call().error != null, "real error accepted");
        }
        require(requests >= 5, "transport not exercised");
        System.out.println("RESPONSES_NULL_ERROR_OK: 5 cases (absent, null text, null tool, object error, string error)");
    }
    private static LlmClient.Result call() throws Exception {
        Conversation settings = new Conversation();
        settings.baseUrl = "https://offline.invalid";
        settings.model = "offline-test";
        settings.protocol = ModelProtocols.RESPONSES;
        settings.protocolAuto = false;
        settings.maxTokens = 1024;
        settings.thinking = AppPrefs.THINKING_ON;
        JSONArray messages = new JSONArray().put(new JSONObject()
                .put("role", "user").put("content", "ping"));
        return new LlmClient().chat(messages, new JSONArray(), "offline-key", settings);
    }
    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
