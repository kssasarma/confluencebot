package com.kssasarma.confluencebot.confluence;

import com.kssasarma.confluencebot.config.ConfluenceProperties;
import com.kssasarma.confluencebot.confluence.dto.ConfluencePageDetail;
import com.kssasarma.confluencebot.confluence.dto.PageSearchResult;
import com.kssasarma.confluencebot.confluence.dto.SpaceMetadata;
import com.kssasarma.confluencebot.exception.ConfluenceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.List;

@Component
public class ConfluenceClientImpl implements ConfluenceClient {

    private static final Logger log = LoggerFactory.getLogger(ConfluenceClientImpl.class);
    private static final String CONTENT_API = "/rest/api/content";
    private static final String EXPAND_FIELDS = "body.storage,version,_links";

    private final RestClient restClient;
    private final ConfluenceProperties props;

    public ConfluenceClientImpl(
            @Qualifier("confluenceRestClient") RestClient restClient,
            ConfluenceProperties props) {
        this.restClient = restClient;
        this.props = props;
    }

    @Override
    public List<ConfluencePageDetail> fetchAllPages(String spaceKey) {
        log.info("Fetching all pages from Confluence space: {}", spaceKey);
        List<ConfluencePageDetail> allPages = new ArrayList<>();
        int start = 0;
        int batch = 0;

        try {
            while (true) {
                batch++;
                PageSearchResult result = fetchBatch(spaceKey, start);

                if (result == null || result.results() == null || result.results().isEmpty()) {
                    log.debug("Empty batch at start={}, stopping", start);
                    break;
                }

                int batchSize = result.results().size();
                allPages.addAll(result.results());
                log.info("Batch {}: start={}, fetched={}, running total={}",
                        batch, start, batchSize, allPages.size());

                // Advance by actual returned count — Confluence Cloud caps at 100 per request
                // regardless of the configured limit, so using pageFetchLimit() here skips pages.
                start += batchSize;

                // _links.next is the authoritative "more pages" signal.
                // A partial batch (size < limit) is NOT a reliable last-page indicator
                // because Confluence enforces its own per-request cap.
                if (!hasNextPage(result)) break;
            }
        } catch (RestClientException ex) {
            throw new ConfluenceException(
                    "Failed to fetch pages from space [" + spaceKey + "] at start=" + start, ex);
        }

        log.info("Completed fetch for space '{}': {} pages across {} batches",
                spaceKey, allPages.size(), batch);
        return allPages;
    }

    private PageSearchResult fetchBatch(String spaceKey, int start) {
        final int currentStart = start;
        return restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path(CONTENT_API)
                        .queryParam("spaceKey", spaceKey)
                        .queryParam("type", "page")
                        .queryParam("status", "current")
                        .queryParam("expand", EXPAND_FIELDS)
                        .queryParam("limit", props.pageFetchLimit())
                        .queryParam("start", currentStart)
                        .build())
                .retrieve()
                .body(PageSearchResult.class);
    }

    private boolean hasNextPage(PageSearchResult result) {
        return result._links() != null && result._links().next() != null;
    }

    @Override
    public ConfluencePageDetail fetchPage(String pageId) {
        try {
            return restClient.get()
                    .uri(CONTENT_API + "/{pageId}?expand=" + EXPAND_FIELDS, pageId)
                    .retrieve()
                    .body(ConfluencePageDetail.class);
        } catch (RestClientException ex) {
            throw new ConfluenceException("Failed to fetch page [" + pageId + "]", ex);
        }
    }

    @Override
    public SpaceMetadata fetchSpaceMetadata(String spaceKey) {
        try {
            return restClient.get()
                    .uri("/rest/api/space/{spaceKey}?expand=description.plain,homepage", spaceKey)
                    .retrieve()
                    .body(SpaceMetadata.class);
        } catch (RestClientException ex) {
            throw new ConfluenceException("Failed to fetch space metadata for [" + spaceKey + "]", ex);
        }
    }
}
