package com.kssasarma.confluencebot.repository;

import com.kssasarma.confluencebot.domain.SpaceSuggestion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface SpaceSuggestionRepository extends JpaRepository<SpaceSuggestion, Long> {

    List<SpaceSuggestion> findBySpaceKeyOrderByIdAsc(String spaceKey);

    @Transactional
    void deleteBySpaceKey(String spaceKey);

    /**
     * A cross-space sample for the "all spaces" welcome screen (no space filter chosen yet).
     * Random rather than "most recent": with several ingested spaces, the newest one would
     * otherwise always crowd out the rest.
     */
    @Query(value = "SELECT * FROM space_suggestions ORDER BY random() LIMIT :limit", nativeQuery = true)
    List<SpaceSuggestion> findRandomSample(@Param("limit") int limit);
}
