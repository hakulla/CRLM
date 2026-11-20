package org.mobileagent.agent;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

interface McpTransport {
    final class Response {
        final int status;
        final String body, contentType, session;
        Response(int status, String body, String contentType, String session) {
            this.status = status; this.body = body; this.contentType = contentType; this.session = session;
        }
    }
    interface CancelHandle { void cancel(); }
    final class Scope {
        final String ownerRunId;
        private boolean cancelled;
        private final Set<CancelHandle> handles = new HashSet<>();
        Scope(String ownerRunId) { this.ownerRunId = ownerRunId; }
        synchronized void check() throws McpProtocol.Failure {
            if (cancelled || Thread.currentThread().isInterrupted()) throw McpProtocol.fail("MCP_CANCELLED");
        }
        synchronized void attach(CancelHandle handle) throws McpProtocol.Failure {
            if (cancelled) { handle.cancel(); throw McpProtocol.fail("MCP_CANCELLED"); }
            handles.add(handle);
        }
        synchronized void detach(CancelHandle handle) { handles.remove(handle); }
        void cancel() {
            Set<CancelHandle> owned;
            synchronized (this) { cancelled = true; owned = new HashSet<>(handles); handles.clear(); }
            for (CancelHandle handle : owned) {
                try { handle.cancel(); } catch (RuntimeException ignored) { /* Drain every owned handle. */ }
            }
        }
    }
    Response exchange(McpDirectory.Config server, JSONObject request, String session, Scope scope) throws Exception;

    /** Streamable HTTP POST only; redirects are denied to avoid endpoint/credential substitution. */
    final class Http implements McpTransport {
        @Override public Response exchange(McpDirectory.Config server, JSONObject request, String session, Scope scope)
                throws Exception {
            scope.check();
            final HttpURLConnection connection = (HttpURLConnection) new URL(server.endpoint).openConnection();
            CancelHandle handle = new CancelHandle() { @Override public void cancel() { connection.disconnect(); } };
            scope.attach(handle);
            try {
                connection.setInstanceFollowRedirects(false);
                connection.setRequestMethod("POST"); connection.setConnectTimeout(12000); connection.setReadTimeout(30000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setRequestProperty("Accept", "application/json, text/event-stream");
                connection.setRequestProperty("MCP-Protocol-Version", "2024-11-05");
                if (!server.credential.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + server.credential);
                if (session != null && !session.isEmpty()) connection.setRequestProperty("Mcp-Session-Id", session);
                byte[] bytes = request.toString().getBytes(StandardCharsets.UTF_8);
                if (bytes.length > McpProtocol.MAX_BODY) throw McpProtocol.fail("MCP_PAYLOAD_TOO_LARGE");
                connection.setFixedLengthStreamingMode(bytes.length);
                scope.check();
                try (OutputStream out = connection.getOutputStream()) { out.write(bytes); }
                int status = connection.getResponseCode();
                if (status < 200 || status >= 300) throw McpProtocol.fail("MCP_HTTP_" + status);
                if (connection.getContentLengthLong() > McpProtocol.MAX_BODY)
                    throw McpProtocol.fail("MCP_PAYLOAD_TOO_LARGE");
                String body;
                try (InputStream input = connection.getInputStream()) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int n;
                    while ((n = input.read(buffer)) != -1) {
                        scope.check();
                        if (out.size() + n > McpProtocol.MAX_BODY) throw McpProtocol.fail("MCP_PAYLOAD_TOO_LARGE");
                        out.write(buffer, 0, n);
                    }
                    body = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(out.toByteArray())).toString();
                }
                scope.check();
                return new Response(status, body, connection.getContentType(), connection.getHeaderField("Mcp-Session-Id"));
            } catch (McpProtocol.Failure e) { throw e; }
            catch (Exception e) { scope.check(); throw McpProtocol.fail("MCP_TRANSPORT_ERROR"); }
            finally { scope.detach(handle); connection.disconnect(); }
        }
    }
}
