package com.kssasarma.confluencebot.confluence;

import com.kssasarma.confluencebot.confluence.dto.ConfluencePageDetail;
import com.kssasarma.confluencebot.confluence.dto.SpaceMetadata;
import java.util.List;
import java.util.Optional;

public interface ConfluenceClient {
    List<ConfluencePageDetail> fetchAllPages(String spaceKey);
    ConfluencePageDetail fetchPage(String pageId);
    SpaceMetadata fetchSpaceMetadata(String spaceKey);

    /**
     * Exact-title lookup within a space — page titles are unique per space in Confluence, but
     * not globally, so both must be given. Used to resolve an excerpt-include's target page,
     * whose storage format carries only a title, never an ID. Empty (not an exception) when no
     * page matches, so a dangling or renamed reference degrades gracefully rather than failing
     * the whole ingestion of the page that referenced it.
     */
    Optional<ConfluencePageDetail> fetchPageByTitle(String spaceKey, String title);
}
