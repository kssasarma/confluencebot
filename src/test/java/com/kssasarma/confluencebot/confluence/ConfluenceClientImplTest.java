package com.kssasarma.confluencebot.confluence;

import com.kssasarma.confluencebot.config.ConfluenceProperties;
import com.kssasarma.confluencebot.confluence.dto.ConfluencePageDetail;
import com.kssasarma.confluencebot.confluence.dto.PageSearchResult;
import com.kssasarma.confluencebot.exception.ConfluenceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConfluenceClientImplTest {

    @Mock private RestClient restClient;
    @Mock private RestClient.RequestHeadersUriSpec<?> uriSpec;
    @Mock private RestClient.ResponseSpec responseSpec;

    // pageFetchLimit=100 simulates the Confluence Cloud cap being equal to the configured limit.
    // pageFetchLimit=250 verifies the fix: even if configured limit > 100, we don't stop early.
    private ConfluenceProperties props250 = new ConfluenceProperties(
            "http://confluence.example.com", "token", "ENG", 250, 30);
    private ConfluenceProperties props100 = new ConfluenceProperties(
            "http://confluence.example.com", "token", "ENG", 100, 30);

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        when(restClient.get()).thenReturn((RestClient.RequestHeadersUriSpec) uriSpec);
        when(uriSpec.uri(any(Function.class))).thenReturn((RestClient.RequestHeadersSpec) uriSpec);
        when(uriSpec.retrieve()).thenReturn(responseSpec);
    }

    private static ConfluencePageDetail page(String id) {
        return new ConfluencePageDetail(id, "Page " + id,
                new ConfluencePageDetail.Version(1), null,
                new ConfluencePageDetail.Links("/page/" + id));
    }

    private static PageSearchResult resultWithNext(List<ConfluencePageDetail> pages) {
        return new PageSearchResult(pages, 0, 100, pages.size(),
                new PageSearchResult.Links("/rest/api/content?start=100"));
    }

    private static PageSearchResult resultNoNext(List<ConfluencePageDetail> pages) {
        return new PageSearchResult(pages, 0, 100, pages.size(), new PageSearchResult.Links(null));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Single batch that has no next link → stops after one call
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    void fetchAllPages_singleBatch_noNextLink_returnsAllPages() {
        List<ConfluencePageDetail> batch = List.of(page("1"), page("2"), page("3"));
        when(responseSpec.body(PageSearchResult.class)).thenReturn(resultNoNext(batch));

        ConfluenceClientImpl client = new ConfluenceClientImpl(restClient, props100);
        List<ConfluencePageDetail> result = client.fetchAllPages("ENG");

        assertThat(result).hasSize(3);
        verify(uriSpec, times(1)).uri(any(Function.class));  // exactly one HTTP call
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Two batches separated by a next link → continues until next is null
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    void fetchAllPages_twoBatches_followsNextLink() {
        List<ConfluencePageDetail> batch1 = List.of(page("1"), page("2"));
        List<ConfluencePageDetail> batch2 = List.of(page("3"), page("4"));
        when(responseSpec.body(PageSearchResult.class))
                .thenReturn(resultWithNext(batch1))
                .thenReturn(resultNoNext(batch2));

        ConfluenceClientImpl client = new ConfluenceClientImpl(restClient, props100);
        List<ConfluencePageDetail> result = client.fetchAllPages("ENG");

        assertThat(result).hasSize(4);
        assertThat(result).extracting(ConfluencePageDetail::id)
                .containsExactly("1", "2", "3", "4");
        verify(uriSpec, times(2)).uri(any(Function.class));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Critical regression: batch size < pageFetchLimit AND next link present
    // must NOT stop. This is the Confluence Cloud 100-cap scenario.
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    void fetchAllPages_partialBatchWithNextLink_doesNotStopEarly() {
        // props250 means pageFetchLimit=250, but Confluence returns only 100 per batch.
        // The old buggy code: 100 < 250 → isLastPage=true → stops after batch 1.
        List<ConfluencePageDetail> batch1 = List.of(page("1"), page("2"));  // size=2 < 250
        List<ConfluencePageDetail> batch2 = List.of(page("3"));
        when(responseSpec.body(PageSearchResult.class))
                .thenReturn(resultWithNext(batch1))   // next link present
                .thenReturn(resultNoNext(batch2));

        ConfluenceClientImpl client = new ConfluenceClientImpl(restClient, props250);
        List<ConfluencePageDetail> result = client.fetchAllPages("ENG");

        assertThat(result).hasSize(3);
        verify(uriSpec, times(2)).uri(any(Function.class));  // must make second call
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Null _links on the result → treated as last page, no NPE
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    void fetchAllPages_nullLinks_treatedAsLastPage() {
        PageSearchResult resultNullLinks = new PageSearchResult(
                List.of(page("1")), 0, 100, 1, null);
        when(responseSpec.body(PageSearchResult.class)).thenReturn(resultNullLinks);

        ConfluenceClientImpl client = new ConfluenceClientImpl(restClient, props100);
        List<ConfluencePageDetail> result = client.fetchAllPages("ENG");

        assertThat(result).hasSize(1);
        verify(uriSpec, times(1)).uri(any(Function.class));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Network error wraps into ConfluenceException
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    void fetchAllPages_restClientException_wrappedAsConfluenceException() {
        when(responseSpec.body(PageSearchResult.class))
                .thenThrow(new RestClientException("connection refused"));

        ConfluenceClientImpl client = new ConfluenceClientImpl(restClient, props100);

        assertThatThrownBy(() -> client.fetchAllPages("ENG"))
                .isInstanceOf(ConfluenceException.class)
                .hasMessageContaining("ENG");
    }
}
