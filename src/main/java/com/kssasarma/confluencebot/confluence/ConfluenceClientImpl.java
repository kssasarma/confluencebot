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

        try {
            while (true) {
                final int currentStart = start;
                PageSearchResult result = restClient.get()
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

                if (result == null || result.results() == null || result.results().isEmpty()) {
                    break;
                }

                allPages.addAll(result.results());
                log.debug("Fetched batch: {} pages, running total: {}", result.results().size(), allPages.size());

                boolean isLastPage = result.results().size() < props.pageFetchLimit()
                        || result._links() == null
                        || result._links().next() == null;

                if (isLastPage) break;

                start += props.pageFetchLimit();
            }
        } catch (RestClientException ex) {
            throw new ConfluenceException("Failed to fetch pages from space [" + spaceKey + "]", ex);
        }

        log.info("Completed fetch for space {}. Total pages: {}", spaceKey, allPages.size());
        return allPages;
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
