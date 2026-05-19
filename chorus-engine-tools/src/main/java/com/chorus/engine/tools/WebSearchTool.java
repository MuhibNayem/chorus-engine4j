package com.chorus.engine.tools;

import com.chorus.engine.core.reactive.CancellationToken;
import com.chorus.engine.core.result.Result;
import org.jspecify.annotations.NonNull;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Search the web and fetch pages.
 *
 * <p>Operations:
 * <ul>
 *   <li>{@code web_search} – queries DuckDuckGo Lite HTML and parses results</li>
 *   <li>{@code fetch_url} – fetches a URL via JDK {@link HttpClient}</li>
 * </ul>
 *
 * <p>Safety: URLs are validated through {@link SafetyValidator}. Content-type
 * filtering rejects non-text responses.
 */
public final class WebSearchTool implements Tool {

    private final HttpClient httpClient;
    private final Duration requestTimeout;

    public WebSearchTool() {
        this(Duration.ofSeconds(30));
    }

    public WebSearchTool(@NonNull Duration requestTimeout) {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
        this.requestTimeout = requestTimeout;
    }

    @Override
    public @NonNull String name() {
        return "web_search";
    }

    @Override
    public @NonNull String description() {
        return "Search the web and fetch pages. Supports web_search and fetch_url.";
    }

    @Override
    public @NonNull Map<String, Object> parametersSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "operation", Map.of("type", "string", "enum", List.of("web_search", "fetch_url")),
                "query", Map.of("type", "string", "description", "Search query for web_search"),
                "url", Map.of("type", "string", "description", "URL to fetch for fetch_url"),
                "numResults", Map.of("type", "integer", "description", "Number of results for web_search", "default", 5)
            ),
            "required", List.of("operation")
        );
    }

    @Override
    public @NonNull Result<ToolOutput, ToolError> execute(@NonNull Map<String, Object> args, @NonNull CancellationToken token) {
        String operation = getString(args, "operation");
        if (operation == null) {
            return Result.err(new ToolError.ValidationError("operation", "Missing operation"));
        }

        return switch (operation) {
            case "web_search" -> {
                String query = getString(args, "query");
                if (query == null || query.isBlank()) {
                    yield Result.err(new ToolError.ValidationError("query", "Query is required"));
                }
                int numResults = args.get("numResults") instanceof Number n ? n.intValue() : 5;
                yield webSearch(query, numResults, token);
            }
            case "fetch_url" -> {
                String url = getString(args, "url");
                if (url == null || url.isBlank()) {
                    yield Result.err(new ToolError.ValidationError("url", "URL is required"));
                }
                yield fetchUrl(url, token);
            }
            default -> Result.err(new ToolError.ValidationError("operation", "Unknown operation: " + operation));
        };
    }

    private Result<ToolOutput, ToolError> webSearch(String query, int numResults, CancellationToken token) {
        Optional<String> urlError = SafetyValidator.validateUrl("https://duckduckgo.com/?q=" + query);
        if (urlError.isPresent()) {
            return Result.err(new ToolError.SafetyBlocked(urlError.get()));
        }

        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String searchUrl = "https://html.duckduckgo.com/html/?q=" + encoded;

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(searchUrl))
                .header("User-Agent", "Mozilla/5.0 (compatible; ChorusEngine/0.1)")
                .timeout(requestTimeout)
                .GET()
                .build();

            token.throwIfCancelled();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return Result.err(new ToolError.ExecutionError("web_search", "HTTP " + response.statusCode(), response.statusCode()));
            }

            List<Map<String, String>> results = parseSearchResults(response.body(), numResults);
            Map<String, Object> structured = new LinkedHashMap<>();
            structured.put("results", results);
            structured.put("count", results.size());
            structured.put("query", query);

            StringBuilder sb = new StringBuilder();
            for (Map<String, String> r : results) {
                sb.append("Title: ").append(r.get("title")).append("\n")
                  .append("URL: ").append(r.get("url")).append("\n")
                  .append("Snippet: ").append(r.get("snippet")).append("\n\n");
            }

            return Result.ok(new ToolOutput(sb.toString(), structured));

        } catch (IOException e) {
            return Result.err(new ToolError.ExecutionError("web_search", e.getMessage(), 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.err(new ToolError.ExecutionError("web_search", "Interrupted", 1));
        }
    }

    private Result<ToolOutput, ToolError> fetchUrl(String url, CancellationToken token) {
        Optional<String> urlError = SafetyValidator.validateUrl(url);
        if (urlError.isPresent()) {
            return Result.err(new ToolError.SafetyBlocked(urlError.get()));
        }

        try {
            URI uri = URI.create(url);
            HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .header("User-Agent", "Mozilla/5.0 (compatible; ChorusEngine/0.1)")
                .timeout(requestTimeout)
                .GET()
                .build();

            token.throwIfCancelled();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            String contentType = response.headers().firstValue("Content-Type").orElse("");
            if (!contentType.isEmpty() && !contentType.startsWith("text/") && !contentType.contains("application/json")) {
                return Result.err(new ToolError.SafetyBlocked("Content type blocked: " + contentType));
            }

            Map<String, Object> structured = new LinkedHashMap<>();
            structured.put("url", url);
            structured.put("statusCode", response.statusCode());
            structured.put("contentType", contentType);

            return Result.ok(new ToolOutput(response.body(), structured));

        } catch (IllegalArgumentException e) {
            return Result.err(new ToolError.ValidationError("url", "Invalid URL: " + e.getMessage()));
        } catch (IOException e) {
            return Result.err(new ToolError.ExecutionError("fetch_url", e.getMessage(), 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.err(new ToolError.ExecutionError("fetch_url", "Interrupted", 1));
        }
    }

    private static List<Map<String, String>> parseSearchResults(String html, int maxResults) {
        List<Map<String, String>> results = new ArrayList<>();
        Pattern resultPattern = Pattern.compile("<a[^>]*class=\"result__a\"[^>]*href=\"([^\"]*)\"[^>]*>(.*?)</a>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Pattern snippetPattern = Pattern.compile("<a[^>]*class=\"result__snippet\"[^>]*>(.*?)</a>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

        Matcher titleMatcher = resultPattern.matcher(html);
        Matcher snippetMatcher = snippetPattern.matcher(html);

        while (titleMatcher.find() && results.size() < maxResults) {
            Map<String, String> result = new LinkedHashMap<>();
            String url = titleMatcher.group(1);
            String title = stripHtml(titleMatcher.group(2));
            result.put("title", title);
            result.put("url", url);

            if (snippetMatcher.find()) {
                result.put("snippet", stripHtml(snippetMatcher.group(1)));
            } else {
                result.put("snippet", "");
            }

            results.add(result);
        }

        return results;
    }

    private static String stripHtml(String html) {
        return html.replaceAll("<[^>]+>", "")
                   .replace("&quot;", "\"")
                   .replace("&amp;", "&")
                   .replace("&lt;", "<")
                   .replace("&gt;", ">")
                   .trim();
    }

    private static String getString(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v instanceof String s ? s : null;
    }
}
