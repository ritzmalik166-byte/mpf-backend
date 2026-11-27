package com.mypropertyfact.estate.automation.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Google Programmable Search JSON API adapter; does not scrape listing pages or download images.
 * Requires an API key and Programmable Search Engine ID supplied through secrets.
 * Search results are LEADS, not verified property or RERA records. */
@Component
public class Magicbricks99acresSearch {
    private static final Pattern RERA = Pattern.compile("(?i)\\bUPRERAPRJ[0-9]+(?:/[0-9]{2}/[0-9]{4})?\\b");
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final String key, engine;
    public Magicbricks99acresSearch(ObjectMapper mapper,
            @Value("${mpf.discovery.google.key:}") String key,
            @Value("${mpf.discovery.google.cx:}") String engine) {
        this.mapper=mapper; this.key=key; this.engine=engine;
    }
    public boolean configured(){ return !key.isBlank() && !engine.isBlank(); }
    public List<DiscoveryCandidate> discover(String city) throws Exception {
        if (!configured()) throw new IllegalStateException("Search API credentials missing; no discovery was run");
        if (!city.matches("[\\p{L} .-]{2,70}")) throw new IllegalArgumentException("Invalid city");
        List<DiscoveryCandidate> out=new ArrayList<>();
        String[] domains={"magicbricks.com","99acres.com"};
        for(String domain:domains) {
            String query="site:"+domain+" "+city+" new launch under construction RERA project";
            String url="https://www.googleapis.com/customsearch/v1?key="+enc(key)
                    +"&cx="+enc(engine)+"&num=10&q="+enc(query);
            HttpRequest request=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(25))
                    .header("Accept","application/json").GET().build();
            HttpResponse<String> response=http.send(request,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200) throw new IllegalStateException("Search provider HTTP "+response.statusCode());
            JsonNode items=mapper.readTree(response.body()).path("items");
            if(!items.isArray()) continue;
            for(JsonNode item:items) {
                String link=item.path("link").asText("");
                if(!DiscoveryScreeningRules.allowedSource(link)) continue;
                String title=item.path("title").asText("");
                String snippet=item.path("snippet").asText("");
                String name=extractName(title);
                Matcher m=RERA.matcher(title+" "+snippet);
                String rera=m.find()?m.group().toUpperCase(Locale.ROOT):"";
                // Builder and official RERA verification must be independently established.
                // Source links, search snippets and RERA-looking text never imply authorization.
                // Status is left blank: a search snippet does not establish it (it was hard-coded
                // "Under Construction" in the delivered patch, which passed the status check unverified).
                out.add(new DiscoveryCandidate(link,name,"",city,"",rera,"",""));
            }
        }
        // repeated result URLs are removed; MAX intake size is bounded
        LinkedHashMap<String,DiscoveryCandidate> unique=new LinkedHashMap<>();
        for(DiscoveryCandidate c:out) unique.putIfAbsent(c.sourceUrl(),c);
        return unique.values().stream().limit(20).toList();
    }
    static String extractName(String title) {
        // Title-derived names are intentionally provisional and require review.
        return title.split("(?i)\\s+(in|at)\\s+|[:|]")[0].trim();
    }
    private static String enc(String s){return URLEncoder.encode(s,StandardCharsets.UTF_8);}
}
