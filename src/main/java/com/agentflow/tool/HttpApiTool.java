package com.agentflow.tool;

import com.agentflow.shared.config.AgentFlowProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.support.ClassicRequestBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/** HTTPS host-allowlisted requests with DNS pinning, private-address rejection and no redirects. */
public class HttpApiTool {
    private final Set<String> allowedHosts;
    private final int maxBytes;
    private final ObjectMapper json;

    public HttpApiTool(AgentFlowProperties props, ObjectMapper json) {
        this.allowedHosts = Arrays.stream(Optional.ofNullable(props.tools().httpAllowedHosts()).orElse("").split(","))
                .map(String::trim).filter(s -> !s.isBlank()).map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        this.maxBytes = props.tools().maxResponseBytes();
        this.json = json;
    }

    @Tool(description = "Call an approved HTTPS JSON API using GET or POST. Only administrator-allowlisted domains are reachable; redirects are rejected.")
    public String request(@ToolParam(description = "Full HTTPS URL on an allowlisted host") String url,
                          @ToolParam(description = "GET or POST") String method,
                          @ToolParam(description = "JSON request body for POST; ignored for GET") Map<String, Object> body) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getRawUserInfo() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)
                    || !allowedHosts.contains(host.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("URL is not permitted");
            }

            InetAddress[] pinnedAddresses = InetAddress.getAllByName(host);
            if (pinnedAddresses.length == 0 || Arrays.stream(pinnedAddresses).anyMatch(address -> !isPublicAddress(address))) {
                throw new IllegalArgumentException("URL resolves to a non-public address");
            }
            DnsResolver pinnedResolver = new DnsResolver() {
                @Override public InetAddress[] resolve(String queriedHost) throws UnknownHostException {
                    if (!host.equalsIgnoreCase(queriedHost)) throw new UnknownHostException("Unapproved DNS lookup");
                    return pinnedAddresses.clone();
                }
                @Override public String resolveCanonicalHostname(String queriedHost) throws UnknownHostException {
                    if (!host.equalsIgnoreCase(queriedHost)) throw new UnknownHostException("Unapproved DNS lookup");
                    return host;
                }
            };

            String verb = method == null ? "" : method.toUpperCase(Locale.ROOT);
            if (!Set.of("GET", "POST").contains(verb)) throw new IllegalArgumentException("Only GET and POST are supported");
            ClassicRequestBuilder builder = ClassicRequestBuilder.create(verb).setUri(uri).setHeader("Accept", "application/json");
            if (verb.equals("POST")) {
                byte[] requestBody = json.writeValueAsBytes(body == null ? Map.of() : body);
                if (requestBody.length > 65536) throw new IllegalArgumentException("API request body exceeds configured size limit");
                builder.setHeader("Content-Type", "application/json").setEntity(requestBody, ContentType.APPLICATION_JSON);
            }
            ClassicHttpRequest request = builder.build();
            ConnectionConfig connectionConfig = ConnectionConfig.custom()
                    .setConnectTimeout(Timeout.ofSeconds(5)).setSocketTimeout(Timeout.ofSeconds(10)).build();
            RequestConfig requestConfig = RequestConfig.custom()
                    .setConnectionRequestTimeout(Timeout.ofSeconds(5)).setResponseTimeout(Timeout.ofSeconds(10)).build();
            PoolingHttpClientConnectionManager manager = PoolingHttpClientConnectionManagerBuilder.create()
                    .setDnsResolver(pinnedResolver).setDefaultConnectionConfig(connectionConfig).build();
            try (CloseableHttpClient client = HttpClients.custom().setConnectionManager(manager)
                    .setDefaultRequestConfig(requestConfig).disableRedirectHandling().disableAutomaticRetries().build()) {
                return client.execute(request, response -> {
                    HttpEntity entity = response.getEntity();
                    if (entity == null) return "HTTP " + response.getCode();
                    try (InputStream in = entity.getContent()) {
                        byte[] bytes = in.readNBytes(maxBytes + 1);
                        if (bytes.length > maxBytes) throw new IllegalArgumentException("API response exceeds configured size limit");
                        return "HTTP " + response.getCode() + "\n" + new String(bytes, StandardCharsets.UTF_8);
                    }
                });
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Approved API request failed", e);
        }
    }

    static boolean isPublicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) return isPublicIpv4(bytes, 0);
        if (!(address instanceof Inet6Address)) return false;
        if ((bytes[0] & 0xfe) == 0xfc) return false; // Unique-local fc00::/7
        if ((bytes[0] & 0xe0) != 0x20) return false; // Only global-unicast 2000::/3
        if ((bytes[0] & 0xff) == 0x20 && (bytes[1] & 0xff) == 0x01
                && ((bytes[2] & 0xff) == 0x0d && (bytes[3] & 0xff) == 0xb8)) return false; // Documentation prefix
        if ((bytes[0] & 0xff) == 0x20 && (bytes[1] & 0xff) == 0x01
                && (bytes[2] & 0xff) == 0x00 && (bytes[3] & 0xff) == 0x00) return false; // Teredo
        if ((bytes[0] & 0xff) == 0x20 && (bytes[1] & 0xff) == 0x02) return false; // 6to4
        boolean mappedIpv4 = true;
        for (int i = 0; i < 10; i++) mappedIpv4 &= bytes[i] == 0;
        mappedIpv4 &= bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff;
        return !mappedIpv4 || isPublicIpv4(bytes, 12);
    }

    private static boolean isPublicIpv4(byte[] address, int offset) {
        int a = address[offset] & 0xff, b = address[offset + 1] & 0xff;
        int c = address[offset + 2] & 0xff;
        if (a == 0 || a == 10 || a == 127 || a >= 224) return false;
        if (a == 100 && b >= 64 && b <= 127) return false;
        if (a == 169 && b == 254) return false;
        if (a == 172 && b >= 16 && b <= 31) return false;
        if (a == 192 && (b == 168 || (b == 0 && (c == 0 || c == 2)) || (b == 88 && c == 99))) return false;
        if (a == 198 && (b == 18 || b == 19 || (b == 51 && c == 100))) return false;
        if (a == 203 && b == 0 && c == 113) return false;
        return true;
    }
}
