package com.kssasarma.confluencebot.repository;

import com.kssasarma.confluencebot.domain.ConfluencePageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ConfluencePageRepository extends JpaRepository<ConfluencePageEntity, String> {

    List<ConfluencePageEntity> findBySpaceKey(String spaceKey);

    @Query("SELECT e.version FROM ConfluencePageEntity e WHERE e.pageId = :pageId")
    Integer findVersionByPageId(@Param("pageId") String pageId);

    /**
     * One row per distinct space, backing the space picker (GET /api/spaces). The most recently
     * ingested name for each space key wins, so a space rename is reflected as soon as any one of
     * its pages is re-ingested, without waiting for the whole space to be re-crawled.
     */
    @Query(value = """
            SELECT DISTINCT ON (space_key) space_key AS spaceKey, space_name AS spaceName
            FROM confluence_pages
            ORDER BY space_key, ingested_at DESC
            """, nativeQuery = true)
    List<SpaceKeyName> findDistinctSpaces();

    interface SpaceKeyName {
        String getSpaceKey();
        String getSpaceName();
    }
}
